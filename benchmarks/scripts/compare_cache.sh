#!/usr/bin/env bash

# ============================================================
# LEDGERBASE — REPEATED REDIS CACHE BENCHMARK
# ============================================================
#
# Compare Trial Balance reporting with Redis enabled
# and disabled in the local Kubernetes deployment.
#
# EXPERIMENT DESIGN:
#
# Round 1: Redis ON  -> Redis OFF
# Round 2: Redis OFF -> Redis ON
# Round 3: Redis ON  -> Redis OFF
#
# Each invocation executes exactly one round.
#
# SAFETY:
#
# - Validate authentication before testing.
# - Require a sufficiently fresh JWT.
# - Verify accounting totals before each benchmark.
# - Preserve all results in a unique directory.
# - Capture process-local Prometheus counters.
# - Manage the metrics port-forward automatically.
# - Restore the original cache setting on exit.
# - Never store credentials in result files.
#
# Run from the LedgerBase repository root.
#
# ============================================================

set -Eeuo pipefail

# ============================================================
# EXPERIMENT CONFIGURATION
# ============================================================

NAMESPACE="ledgerbase"
DEPLOYMENT="reporting-service"

BASE_URL="http://127.0.0.1:3001"

METRICS_PORT="18084"

METRICS_URL="http://127.0.0.1:${METRICS_PORT}/actuator/prometheus"

BUSINESS_ID="${BUSINESS_ID:-3}"
AS_OF_DATE="${AS_OF_DATE:-2026-10-31}"

ROUND="${ROUND:-1}"

EXPECTED_TOTAL="523190.19"
EXPECTED_ACCOUNT_COUNT=100

K6_SCRIPT="benchmarks/k6/reporting.js"

RESULTS_ROOT="benchmarks/results/cache-comparison"

METRICS_PID=""
RESTORE_NEEDED="false"
ORIGINAL_CACHE_STATE=""
ROUND_DIR=""

# ============================================================
# VALIDATE PREREQUISITES
# ============================================================

for command in kubectl k6 jq curl python3; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "Missing required command: $command" >&2
        exit 1
    fi
done

if [[ -z "${ACCESS_TOKEN:-}" ]]; then
    echo "ACCESS_TOKEN is required." >&2
    exit 1
fi

if [[ ! -f "$K6_SCRIPT" ]]; then
    echo "Missing benchmark script: $K6_SCRIPT" >&2
    exit 1
fi

if [[ ! "$ROUND" =~ ^[123]$ ]]; then
    echo "ROUND must be 1, 2, or 3." >&2
    exit 1
fi

# ============================================================
# READ KUBERNETES CONFIGURATION
# ============================================================

get_cache_state() {
    kubectl -n "$NAMESPACE" \
        get deployment "$DEPLOYMENT" \
        -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="LEDGERBASE_TRIAL_BALANCE_CACHE_ENABLED")].value}'
}

ORIGINAL_CACHE_STATE="$(get_cache_state)"

if [[ "$ORIGINAL_CACHE_STATE" != "true" &&
      "$ORIGINAL_CACHE_STATE" != "false" ]]; then
    echo "Unable to determine original cache state." >&2
    exit 1
fi

# Our controlled experiment starts from the known
# cache-enabled local deployment.
if [[ "$ORIGINAL_CACHE_STATE" != "true" ]]; then
    echo "Expected initial cache setting: true" >&2
    echo "Actual: $ORIGINAL_CACHE_STATE" >&2
    exit 1
fi

# ============================================================
# CREATE UNIQUE RESULTS DIRECTORY
# ============================================================

mkdir -p "$RESULTS_ROOT"

# mktemp prevents accidental overwrites, including
# when two runs begin in the same second.
ROUND_DIR="$(
    mktemp -d \
        "$RESULTS_ROOT/round-${ROUND}-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX"
)"

echo "=========================================="
echo "LedgerBase Cache Benchmark"
echo "=========================================="
echo "Round: $ROUND"
echo "Business: $BUSINESS_ID"
echo "Reporting date: $AS_OF_DATE"
echo "Original cache state: $ORIGINAL_CACHE_STATE"
echo "Results: $ROUND_DIR"

# ============================================================
# METRICS PORT-FORWARD MANAGEMENT
# ============================================================

stop_metrics_forward() {
    if [[ -n "$METRICS_PID" ]]; then
        kill "$METRICS_PID" 2>/dev/null || true
        wait "$METRICS_PID" 2>/dev/null || true
        METRICS_PID=""
    fi
}

start_metrics_forward() {
    stop_metrics_forward

    # The Deployment resolves to its current Pod.
    # Start a new forward after each Kubernetes rollout.
    kubectl -n "$NAMESPACE" port-forward \
        "deployment/$DEPLOYMENT" \
        "${METRICS_PORT}:8082" \
        --address 127.0.0.1 \
        > "$ROUND_DIR/port-forward.log" 2>&1 &

    METRICS_PID=$!

    for attempt in {1..30}; do
        if ! kill -0 "$METRICS_PID" 2>/dev/null; then
            echo "Port-forward exited unexpectedly." >&2
            cat "$ROUND_DIR/port-forward.log" >&2
            return 1
        fi

        if curl -fsS \
            --max-time 3 \
            "$METRICS_URL" \
            -o /dev/null 2>/dev/null; then

            echo "Prometheus metrics endpoint ready."
            return 0
        fi

        sleep 1
    done

    echo "Prometheus endpoint unavailable." >&2
    return 1
}

# ============================================================
# AUTOMATIC RESTORATION
# ============================================================

cleanup() {
    local exit_code=$?

    trap - EXIT INT TERM
    set +e

    stop_metrics_forward

    if [[ "$RESTORE_NEEDED" == "true" ]]; then
        echo ""
        echo "Restoring original cache configuration..."

        kubectl -n "$NAMESPACE" set env \
            "deployment/$DEPLOYMENT" \
            "LEDGERBASE_TRIAL_BALANCE_CACHE_ENABLED=$ORIGINAL_CACHE_STATE"

        local update_result=$?

        kubectl -n "$NAMESPACE" rollout status \
            "deployment/$DEPLOYMENT" \
            --timeout=180s

        local rollout_result=$?

        local actual_state
        actual_state="$(get_cache_state)"
        local read_result=$?

        if [[ "$update_result" -ne 0 ||
              "$rollout_result" -ne 0 ||
              "$read_result" -ne 0 ||
              "$actual_state" != "$ORIGINAL_CACHE_STATE" ]]; then

            echo "WARNING: Cache restoration not verified." >&2
            exit_code=1
        else
            echo "Cache restored and verified: $actual_state"
        fi
    fi

    exit "$exit_code"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ============================================================
# CHECK JWT EXPIRATION
# ============================================================

check_token_lifetime() {
    local minimum_seconds="$1"

    MIN_JWT_SECONDS="$minimum_seconds" python3 - <<'PY'
import base64
import json
import os
import sys
import time

# This is a local expiration check, not JWT
# signature verification.
# Spring Security validates the actual JWT.

token = os.environ["ACCESS_TOKEN"]

try:
    payload_part = token.split(".")[1]

    padded = (
        payload_part
        + "=" * (-len(payload_part) % 4)
    )

    payload = json.loads(
        base64.urlsafe_b64decode(padded)
    )

    remaining = int(
        payload["exp"] - time.time()
    )

except (
    IndexError,
    KeyError,
    ValueError,
    TypeError,
):
    print(
        "Unable to inspect JWT expiration.",
        file=sys.stderr,
    )
    sys.exit(1)

minimum = int(
    os.environ["MIN_JWT_SECONDS"]
)

if remaining < minimum:
    print(
        f"JWT remaining: {remaining}s; "
        f"required: {minimum}s.",
        file=sys.stderr,
    )
    sys.exit(1)

print(
    f"JWT remaining: {remaining}s"
)
PY
}

# ============================================================
# VERIFY REPORTING AND ACCOUNTING CORRECTNESS
# ============================================================

verify_reporting() {
    local response_file

    response_file="$(mktemp)"

    local status

    status=$(
        curl --silent --show-error \
            --max-time 15 \
            --output "$response_file" \
            --write-out '%{http_code}' \
            "$BASE_URL/api/v1/businesses/$BUSINESS_ID/reports/trial-balance?asOfDate=$AS_OF_DATE" \
            -H "Authorization: Bearer $ACCESS_TOKEN"
    ) || {
        rm -f "$response_file"
        return 1
    }

    if [[ "$status" != "200" ]]; then
        echo "Reporting request returned HTTP $status" >&2
        rm -f "$response_file"
        return 1
    fi

    # Validate the actual accounting result.
    # Do not treat arbitrary HTTP 200 responses as valid.
    if ! jq -e \
        --argjson business "$BUSINESS_ID" \
        --arg reportingDate "$AS_OF_DATE" \
        --arg total "$EXPECTED_TOTAL" \
        --argjson count "$EXPECTED_ACCOUNT_COUNT" \
        '
        .businessId == $business
        and .asOfDate == $reportingDate
        and .balanced == true
        and .totalDebits == $total
        and .totalCredits == $total
        and (.rows | length) == $count
        ' "$response_file" > /dev/null; then

        echo "Trial Balance correctness verification failed." >&2
        rm -f "$response_file"
        return 1
    fi

    rm -f "$response_file"
}

# ============================================================
# SWITCH REDIS CACHE STATE
# ============================================================

set_cache_state() {
    local desired="$1"
    local current

    stop_metrics_forward

    current="$(get_cache_state)"

    if [[ "$current" != "$desired" ]]; then
        # Set this before making changes so cleanup
        # also runs after a partially failed update.
        RESTORE_NEEDED="true"

        kubectl -n "$NAMESPACE" set env \
            "deployment/$DEPLOYMENT" \
            "LEDGERBASE_TRIAL_BALANCE_CACHE_ENABLED=$desired"

        kubectl -n "$NAMESPACE" rollout status \
            "deployment/$DEPLOYMENT" \
            --timeout=180s
    fi

    if [[ "$(get_cache_state)" != "$desired" ]]; then
        echo "Cache state verification failed." >&2
        return 1
    fi

    echo "Cache state verified: $desired"

    start_metrics_forward
}

# ============================================================
# RUN ONE BENCHMARK CONDITION
# ============================================================

run_benchmark() {
    local label="$1"
    local desired="$2"

    local output_dir="$ROUND_DIR/$label"

    mkdir -p "$output_dir"

    echo ""
    echo "=========================================="
    echo "Round $ROUND — $label"
    echo "=========================================="

    # Check token before changing the deployment.
    check_token_lifetime 90

    set_cache_state "$desired"

    # Check again after rollout, before measuring.
    check_token_lifetime 75

    # Warm up the actual reporting path.
    for attempt in {1..5}; do
        verify_reporting
    done

    # Verify the same business and report totals
    # immediately before running k6.
    verify_reporting

    # Capture metrics from the current Pod.
    curl -fsS --max-time 15 \
        "$METRICS_URL" \
        -o "$output_dir/before.prom"

    # Save the Pod identity for verification.
    kubectl -n "$NAMESPACE" get pods \
        -l app.kubernetes.io/name=reporting-service \
        -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' \
        > "$output_dir/pod-before.txt"

    # Run the identical k6 workload for both modes.
    ACCESS_TOKEN="$ACCESS_TOKEN" \
    BUSINESS_ID="$BUSINESS_ID" \
    AS_OF_DATE="$AS_OF_DATE" \
    k6 run \
        --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99)" \
        --summary-export="$output_dir/k6-summary.json" \
        "$K6_SCRIPT"

    curl -fsS --max-time 15 \
        "$METRICS_URL" \
        -o "$output_dir/after.prom"

    kubectl -n "$NAMESPACE" get pods \
        -l app.kubernetes.io/name=reporting-service \
        -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' \
        > "$output_dir/pod-after.txt"

    # Process-local counters are not comparable if
    # the Reporting Service Pod changes mid-run.
    if ! cmp -s \
        "$output_dir/pod-before.txt" \
        "$output_dir/pod-after.txt"; then

        echo "Reporting Pod changed during benchmark." >&2
        return 1
    fi

    # Reject failed requests, checks, or iterations.
    jq -e '
        .metrics.http_req_failed.value == 0
        and .metrics.checks.fails == 0
        and .metrics.dropped_iterations.count == 0
        and .metrics.http_reqs.count > 0
    ' "$output_dir/k6-summary.json" > /dev/null

    echo "Benchmark completed successfully: $label"
}

# ============================================================
# EXPERIMENT EXECUTION
# ============================================================

# Require a reasonably fresh token before starting
# a two-condition experiment.
check_token_lifetime 240
verify_reporting

if [[ "$ROUND" == "2" ]]; then
    FIRST_STATE="false"
    SECOND_STATE="true"
else
    FIRST_STATE="true"
    SECOND_STATE="false"
fi

if [[ "$FIRST_STATE" == "true" ]]; then
    FIRST_LABEL="redis-on"
    SECOND_LABEL="redis-off"
else
    FIRST_LABEL="redis-off"
    SECOND_LABEL="redis-on"
fi

echo ""
echo "Experiment order:"
echo "1. $FIRST_LABEL"
echo "2. $SECOND_LABEL"

run_benchmark "$FIRST_LABEL" "$FIRST_STATE"
run_benchmark "$SECOND_LABEL" "$SECOND_STATE"

echo ""
echo "=========================================="
echo "Round $ROUND completed successfully."
echo "Results: $ROUND_DIR"
echo "=========================================="

# The EXIT trap restores the original Kubernetes state.
