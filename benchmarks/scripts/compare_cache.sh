#!/usr/bin/env bash

# ============================================================
# LEDGERBASE — AUTOMATED REDIS CACHE BENCHMARK
# ============================================================
#
# Compare Redis-enabled and uncached Trial Balance reporting.
#
# Safety:
# - Validate JWT authentication and remaining lifetime.
# - Preserve the original Kubernetes cache setting.
# - Restart metrics port-forward after each rollout.
# - Reject failed HTTP requests and failed checks.
# - Restore configuration on normal exit or interruption.
# - Save all experimental artifacts outside Git.
#
# Run from the LedgerBase repository root.
#
# ============================================================

set -Eeuo pipefail

NAMESPACE="ledgerbase"
DEPLOYMENT="reporting-service"

BASE_URL="http://127.0.0.1:3001"
METRICS_PORT="18084"
METRICS_URL="http://127.0.0.1:${METRICS_PORT}/actuator/prometheus"

BUSINESS_ID="${BUSINESS_ID:-3}"
AS_OF_DATE="${AS_OF_DATE:-2026-10-31}"

K6_SCRIPT="benchmarks/k6/reporting.js"
RESULTS_DIR="benchmarks/results/cache-comparison"

METRICS_PID=""
RESTORE_NEEDED="false"
ORIGINAL_CACHE_STATE=""

# ============================================================
# PREREQUISITES
# ============================================================

for command in kubectl k6 jq curl python3; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "Missing command: $command" >&2
        exit 1
    fi
done

if [[ -z "${ACCESS_TOKEN:-}" ]]; then
    echo "ACCESS_TOKEN is required." >&2
    exit 1
fi

if [[ ! -f "$K6_SCRIPT" ]]; then
    echo "Missing k6 script: $K6_SCRIPT" >&2
    exit 1
fi

mkdir -p "$RESULTS_DIR"

# ============================================================
# READ CURRENT KUBERNETES CACHE SETTING
# ============================================================

get_cache_state() {
    kubectl -n "$NAMESPACE" get deployment "$DEPLOYMENT" \
        -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="LEDGERBASE_TRIAL_BALANCE_CACHE_ENABLED")].value}'
}

ORIGINAL_CACHE_STATE="$(get_cache_state)"

if [[ "$ORIGINAL_CACHE_STATE" != "true" &&
      "$ORIGINAL_CACHE_STATE" != "false" ]]; then
    echo "Invalid original cache state." >&2
    exit 1
fi

echo "Original cache state: $ORIGINAL_CACHE_STATE"

# ============================================================
# METRICS PORT-FORWARD LIFECYCLE
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

    # Target the active Deployment's Pod after rollout.
    # Use a separate metrics port from manual diagnostics.
    kubectl -n "$NAMESPACE" port-forward \
        "deployment/$DEPLOYMENT" \
        "${METRICS_PORT}:8082" \
        --address 127.0.0.1 \
        > "$RESULTS_DIR/port-forward.log" 2>&1 &

    METRICS_PID=$!

    # Poll until the fresh forwarding connection responds.
    for attempt in {1..30}; do
        if ! kill -0 "$METRICS_PID" 2>/dev/null; then
            echo "Metrics port-forward exited." >&2
            cat "$RESULTS_DIR/port-forward.log" >&2
            return 1
        fi

        if curl -fsS --max-time 3 \
            "$METRICS_URL" -o /dev/null 2>/dev/null; then
            echo "Prometheus metrics endpoint ready."
            return 0
        fi

        sleep 1
    done

    echo "Metrics endpoint did not become ready." >&2
    return 1
}

# ============================================================
# RESTORE KUBERNETES ON EXIT
# ============================================================

cleanup() {
    local original_exit_code=$?

    trap - EXIT INT TERM
    set +e

    stop_metrics_forward

    if [[ "$RESTORE_NEEDED" == "true" ]]; then
        echo ""
        echo "Restoring cache=$ORIGINAL_CACHE_STATE..."

        kubectl -n "$NAMESPACE" set env \
            "deployment/$DEPLOYMENT" \
            "LEDGERBASE_TRIAL_BALANCE_CACHE_ENABLED=$ORIGINAL_CACHE_STATE"

        local update_status=$?

        kubectl -n "$NAMESPACE" rollout status \
            "deployment/$DEPLOYMENT" \
            --timeout=180s

        local rollout_status=$?
        local restored_state

        restored_state="$(get_cache_state)"
        local verify_status=$?

        if [[ "$update_status" -ne 0 ||
              "$rollout_status" -ne 0 ||
              "$verify_status" -ne 0 ||
              "$restored_state" != "$ORIGINAL_CACHE_STATE" ]]; then
            echo "WARNING: Cache restoration not verified!" >&2
            original_exit_code=1
        else
            echo "Original Kubernetes cache setting restored."
        fi
    fi

    exit "$original_exit_code"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ============================================================
# JWT LIFETIME VERIFICATION
# ============================================================

check_token_lifetime() {
    # Decode JWT payload locally. This is only a preflight
    # expiration check; Spring Security remains responsible
    # for cryptographic authentication and authorization.
    python3 - <<'PY'
import base64
import json
import os
import sys
import time

token = os.environ["ACCESS_TOKEN"]

try:
    payload_part = token.split(".")[1]
    padded = payload_part + "=" * (-len(payload_part) % 4)

    payload = json.loads(
        base64.urlsafe_b64decode(padded)
    )

    remaining = int(payload["exp"] - time.time())

except (IndexError, KeyError, ValueError, TypeError):
    print("Unable to inspect JWT expiration.", file=sys.stderr)
    sys.exit(1)

# Allow enough time for warm-up and a 30-second k6 run.
# The caller specifies the minimum remaining lifetime.
# Individual runs need less time than the full comparison.
minimum_seconds = int(
    os.environ.get("MIN_JWT_SECONDS", "120")
)

if remaining < minimum_seconds:
    print(
        f"JWT has only {remaining}s remaining; "
        f"at least {minimum_seconds}s required.",
        file=sys.stderr,
    )
    sys.exit(1)

print(f"JWT lifetime remaining: {remaining}s")
PY
}

# ============================================================
# AUTHENTICATED REPORT VERIFICATION
# ============================================================

verify_reporting() {
    local status

    status=$(
        curl --silent --show-error --max-time 15 \
            -o /dev/null \
            -w '%{http_code}' \
            "$BASE_URL/api/v1/businesses/$BUSINESS_ID/reports/trial-balance?asOfDate=$AS_OF_DATE" \
            -H "Authorization: Bearer $ACCESS_TOKEN"
    )

    if [[ "$status" != "200" ]]; then
        echo "Reporting verification failed: HTTP $status" >&2
        return 1
    fi
}

# ============================================================
# SWITCH CACHE CONFIGURATION
# ============================================================

set_cache_state() {
    local desired="$1"
    local current

    stop_metrics_forward

    current="$(get_cache_state)"

    if [[ "$current" != "$desired" ]]; then
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
# ONE EXPERIMENTAL RUN
# ============================================================

run_benchmark() {
    local label="$1"
    local output_dir="$RESULTS_DIR/$label"

    mkdir -p "$output_dir"

    echo ""
    echo "============================================"
    echo "BENCHMARK: $label"
    echo "============================================"

    check_token_lifetime
    verify_reporting

    # Warm up the Reporting Service and relevant data path.
    for attempt in {1..5}; do
        verify_reporting
    done

    curl -fsS --max-time 15 \
        "$METRICS_URL" \
        -o "$output_dir/before.prom"

    # Preserve the existing k6 scenario and its thresholds.
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

    # Ignore error-response latency as a performance result.
    jq -e '
        .metrics.http_req_failed.value == 0
        and .metrics.checks.fails == 0
        and .metrics.dropped_iterations.count == 0
        and .metrics.http_reqs.count > 0
    ' "$output_dir/k6-summary.json" > /dev/null

    echo "Valid benchmark completed: $label"
}

# ============================================================
# RUN CACHE ON / OFF
# ============================================================

echo ""
echo "Verifying JWT and reporting endpoint..."

# Reserve sufficient lifetime for two k6 runs,
# Kubernetes rollout, and application warm-up.
MIN_JWT_SECONDS=300 check_token_lifetime

verify_reporting

echo ""
echo "Beginning Redis cache comparison."

set_cache_state "true"
run_benchmark "redis-on"

set_cache_state "false"
run_benchmark "redis-off"

echo ""
echo "Both benchmark runs completed."
echo "Results directory: $RESULTS_DIR"

# The EXIT trap stops forwarding and restores the
# original Kubernetes cache state.
