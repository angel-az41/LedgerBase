#!/usr/bin/env python3

"""
============================================================
LEDGERBASE — CACHE BENCHMARK RESULTS ANALYZER
============================================================

PURPOSE:
    Analyze saved Redis ON/OFF benchmark rounds.

INPUTS:
    benchmarks/results/cache-comparison/
        round-*/
            redis-on/
            redis-off/

OUTPUT:
    benchmarks/results/cache-comparison/summary.csv

FEATURES:
    - Read k6 v2.3.0 summary JSON.
    - Extract average, median, p95, and p99 latency.
    - Validate request success and Pod stability.
    - Calculate Redis cache counter differences.
    - Calculate HikariCP replica connection activity.
    - Calculate paired latency improvements.
    - Preserve all original benchmark artifacts.

Only Python's standard library is required.
"""

import csv
import json
from pathlib import Path


# ============================================================
# CONFIGURATION
# ============================================================

RESULTS_ROOT = Path(
    "benchmarks/results/cache-comparison"
)

OUTPUT_FILE = RESULTS_ROOT / "summary.csv"

CACHE_METRIC = (
    "ledgerbase_trial_balance_cache_operations_total"
)

CONNECTION_METRIC = (
    "hikaricp_connections_acquire_seconds_count"
)

POOL_NAME = "ledgerbase-reporting-replica-pool"


# ============================================================
# READ PROMETHEUS COUNTERS
# ============================================================

def read_counter(path, metric_name, labels=None):
    """
    Find a Prometheus counter with matching labels.

    Return zero when the matching series is absent.
    A missing series is expected for unused outcomes,
    such as cache bypass when caching is enabled.

    The caller must provide valid Prometheus snapshots.
    """

    if labels is None:
        labels = {}

    with path.open(encoding="utf-8") as file:
        for line in file:
            if line.startswith("#"):
                continue

            parts = line.strip().split()

            if len(parts) < 2:
                continue

            series = parts[0]

            if series == metric_name:
                actual_name = metric_name
                label_text = ""

            elif series.startswith(metric_name + "{"):
                actual_name = series.split("{", 1)[0]
                label_text = series.split("{", 1)[1].rstrip("}")

            else:
                continue

            if actual_name != metric_name:
                continue

            if all(
                f'{key}="{value}"' in label_text
                for key, value in labels.items()
            ):
                return float(parts[1])

    return 0.0


def counter_delta(directory, metric_name, labels=None):
    """
    Calculate the increase within one benchmark run.

    Never subtract counters across different Pods.
    """

    before_path = directory / "before.prom"
    after_path = directory / "after.prom"

    before = read_counter(
        before_path, metric_name, labels
    )

    after = read_counter(
        after_path, metric_name, labels
    )

    delta = after - before

    if delta < 0:
        raise ValueError(
            f"Counter decreased in {directory}: "
            f"{metric_name}"
        )

    return delta


# ============================================================
# READ AND VALIDATE K6 RESULTS
# ============================================================

def read_run(directory, round_name, mode):
    """
    Extract results for one benchmark condition.

    A run is valid only if:
        - HTTP requests were made.
        - No HTTP requests failed.
        - No checks failed.
        - No iterations were dropped.
        - The Reporting Service Pod stayed unchanged.
    """

    summary_path = directory / "k6-summary.json"

    with summary_path.open(encoding="utf-8") as file:
        summary = json.load(file)

    metrics = summary["metrics"]

    requests = metrics["http_reqs"]["count"]
    errors = metrics["http_req_failed"]["value"]
    failed_checks = metrics["checks"]["fails"]
    dropped = metrics["dropped_iterations"]["count"]

    if (
        requests <= 0
        or errors != 0
        or failed_checks != 0
        or dropped != 0
    ):
        raise ValueError(
            f"Invalid k6 benchmark: {directory}"
        )

    pod_before = (
        directory / "pod-before.txt"
    ).read_text(encoding="utf-8").strip()

    pod_after = (
        directory / "pod-after.txt"
    ).read_text(encoding="utf-8").strip()

    if (
        not pod_before
        or pod_before != pod_after
        or len(pod_before.splitlines()) != 1
    ):
        raise ValueError(
            f"Reporting Pod changed or is ambiguous: "
            f"{directory}"
        )

    latency = metrics["http_req_duration"]

    return {
        "round": round_name,
        "mode": mode,
        "requests": requests,
        "avg_ms": latency["avg"],
        "median_ms": latency["med"],
        "p95_ms": latency["p(95)"],
        "p99_ms": latency["p(99)"],
        "max_ms": latency["max"],
        "http_error_rate": errors,
        "cache_hits": counter_delta(
            directory,
            CACHE_METRIC,
            {"result": "hit"},
        ),
        "cache_misses": counter_delta(
            directory,
            CACHE_METRIC,
            {"result": "miss"},
        ),
        "cache_bypasses": counter_delta(
            directory,
            CACHE_METRIC,
            {"result": "bypass"},
        ),
        "replica_acquisitions": counter_delta(
            directory,
            CONNECTION_METRIC,
            {"pool": POOL_NAME},
        ),
        "replica_acquisitions_per_request": (
            counter_delta(
                directory,
                CONNECTION_METRIC,
                {"pool": POOL_NAME},
            ) / requests
        ),
        "cache_hits_per_request": (
            counter_delta(
                directory,
                CACHE_METRIC,
                {"result": "hit"},
            ) / requests
        ),
    }


# ============================================================
# CALCULATE IMPROVEMENTS
# ============================================================

def improvement_percent(on_value, off_value):
    """
    Measure latency reduction relative to Redis OFF.

    Positive = Redis ON was faster.
    Negative = Redis ON was slower.
    """

    if off_value <= 0:
        return None

    return (
        (off_value - on_value)
        / off_value
        * 100
    )


# ============================================================
# WRITE CSV
# ============================================================

def write_csv(rows):
    OUTPUT_FILE.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    fields = [
        "round",
        "mode",
        "requests",
        "avg_ms",
        "median_ms",
        "p95_ms",
        "p99_ms",
        "max_ms",
        "http_error_rate",
        "cache_hits",
        "cache_misses",
        "cache_bypasses",
        "replica_acquisitions",
        "replica_acquisitions_per_request",
        "cache_hits_per_request",
    ]

    with OUTPUT_FILE.open(
        "w",
        newline="",
        encoding="utf-8",
    ) as file:

        writer = csv.DictWriter(
            file,
            fieldnames=fields,
        )

        writer.writeheader()
        writer.writerows(rows)


# ============================================================
# AGGREGATE EXPERIMENT RESULTS
# ============================================================

def print_aggregate_summary(rows):
    """
    Summarize all completed Redis ON/OFF pairs.

    Methodology:
        - Calculate latency reductions within each round.
        - Report the mean of paired percentage reductions.
        - Weight overall average latency by request count.
        - Normalize connection acquisitions per request.
        - Do not average p95/p99 percentiles across runs.
    """

    # Group valid runs by their unique round directory.
    rounds = {}

    for row in rows:
        round_name = row["round"]
        mode = row["mode"]

        rounds.setdefault(round_name, {})[mode] = row

    paired_average_reductions = []
    paired_p95_reductions = []

    for round_name, modes in rounds.items():
        on = modes.get("redis-on")
        off = modes.get("redis-off")

        if on is None or off is None:
            raise ValueError(
                f"Incomplete validated round: {round_name}"
            )

        paired_average_reductions.append(
            improvement_percent(
                on["avg_ms"],
                off["avg_ms"],
            )
        )

        paired_p95_reductions.append(
            improvement_percent(
                on["p95_ms"],
                off["p95_ms"],
            )
        )

    on_rows = [
        row for row in rows
        if row["mode"] == "redis-on"
    ]

    off_rows = [
        row for row in rows
        if row["mode"] == "redis-off"
    ]

    def total(items, field):
        return sum(
            item[field] for item in items
        )

    on_requests = total(on_rows, "requests")
    off_requests = total(off_rows, "requests")

    # Since each k6 summary's average is calculated
    # over requests, use request-weighted averages.
    on_average_ms = (
        sum(
            row["avg_ms"] * row["requests"]
            for row in on_rows
        ) / on_requests
    )

    off_average_ms = (
        sum(
            row["avg_ms"] * row["requests"]
            for row in off_rows
        ) / off_requests
    )

    on_acquisitions = total(
        on_rows, "replica_acquisitions"
    )

    off_acquisitions = total(
        off_rows, "replica_acquisitions"
    )

    on_acquisitions_per_request = (
        on_acquisitions / on_requests
    )

    off_acquisitions_per_request = (
        off_acquisitions / off_requests
    )

    acquisition_reduction = improvement_percent(
        on_acquisitions_per_request,
        off_acquisitions_per_request,
    )

    mean_paired_average_reduction = (
        sum(paired_average_reductions)
        / len(paired_average_reductions)
    )

    mean_paired_p95_reduction = (
        sum(paired_p95_reductions)
        / len(paired_p95_reductions)
    )

    print("")
    print("=" * 55)
    print("LEDGERBASE — AGGREGATE BENCHMARK SUMMARY")
    print("=" * 55)

    print(f"Completed paired rounds: {len(rounds)}")
    print(f"Total HTTP requests: {on_requests + off_requests}")
    print(f"Redis ON requests: {on_requests}")
    print(f"Redis OFF requests: {off_requests}")

    print("")
    print("LATENCY")
    print(f"Redis ON weighted average: {on_average_ms:.2f} ms")
    print(f"Redis OFF weighted average: {off_average_ms:.2f} ms")
    print(
        "Mean paired average-latency reduction: "
        f"{mean_paired_average_reduction:.2f}%"
    )
    print(
        "Mean paired p95-latency reduction: "
        f"{mean_paired_p95_reduction:.2f}%"
    )

    print("")
    print("POSTGRESQL REPLICA CONNECTION ACTIVITY")
    print(
        "Redis ON acquisitions/request: "
        f"{on_acquisitions_per_request:.4f}"
    )
    print(
        "Redis OFF acquisitions/request: "
        f"{off_acquisitions_per_request:.4f}"
    )
    print(
        "Normalized acquisition reduction: "
        f"{acquisition_reduction:.2f}%"
    )

    print("")
    print("CACHE ACTIVITY")
    print(
        f"Redis ON cache hits: "
        f"{total(on_rows, 'cache_hits'):.0f}"
    )
    print(
        f"Redis OFF cache bypasses: "
        f"{total(off_rows, 'cache_bypasses'):.0f}"
    )

    print("")
    print(
        "Note: These results describe a warmed-cache, "
        "local Kubernetes workload."
    )
    print(
        "Connection acquisitions do not equal SQL "
        "queries or total database load."
    )


# ============================================================
# MAIN
# ============================================================

def main():
    if not RESULTS_ROOT.exists():
        raise FileNotFoundError(
            f"Results directory not found: {RESULTS_ROOT}"
        )

    rows = []

    round_directories = sorted(
        path
        for path in RESULTS_ROOT.glob("round-*")
        if path.is_dir()
    )

    if not round_directories:
        raise RuntimeError(
            "No completed benchmark rounds found."
        )

    for round_dir in round_directories:
        print(f"\nRound: {round_dir.name}")

        on_dir = round_dir / "redis-on"
        off_dir = round_dir / "redis-off"

        # Ignore incomplete rounds, but display them
        # so missing data is never silently hidden.
        if not (
            (on_dir / "k6-summary.json").exists()
            and (off_dir / "k6-summary.json").exists()
        ):
            print("  Incomplete round — skipped.")
            continue

        on_result = read_run(
            on_dir, round_dir.name, "redis-on"
        )

        off_result = read_run(
            off_dir, round_dir.name, "redis-off"
        )

        # k6's constant-arrival-rate scheduling can produce
        # slightly different sample counts at run boundaries.
        #
        # Compare latency metrics per request, and normalize
        # connection acquisitions by their request counts.
        #
        # Reject substantially mismatched workloads rather
        # than silently treating them as equivalent.

        on_requests = on_result["requests"]
        off_requests = off_result["requests"]

        request_difference = abs(
            on_requests - off_requests
        )

        if request_difference > max(
            2,
            0.05 * max(on_requests, off_requests),
        ):
            raise ValueError(
                "Request counts differ substantially: "
                f"ON={on_requests}, OFF={off_requests}"
            )

        rows.extend([
            on_result,
            off_result,
        ])

        avg_improvement = improvement_percent(
            on_result["avg_ms"],
            off_result["avg_ms"],
        )

        p95_improvement = improvement_percent(
            on_result["p95_ms"],
            off_result["p95_ms"],
        )

        acquisitions_on = (
            on_result["replica_acquisitions"]
        )

        acquisitions_off = (
            off_result["replica_acquisitions"]
        )

        # Normalize connection acquisitions by request count.
        # These counters measure HikariCP connection usage,
        # not the number of SQL queries or total database load.

        acquisitions_per_request_on = (
            acquisitions_on / on_requests
        )

        acquisitions_per_request_off = (
            acquisitions_off / off_requests
        )

        database_improvement = improvement_percent(
            acquisitions_per_request_on,
            acquisitions_per_request_off,
        )

        if database_improvement is not None:
            print(
                "  Replica acquisitions per request: "
                f"ON={acquisitions_per_request_on:.3f}, "
                f"OFF={acquisitions_per_request_off:.3f}"
            )

            print(
                "  Normalized acquisition reduction: "
                f"{database_improvement:.2f}%"
            )

    if not rows:
        raise RuntimeError(
            "No complete valid benchmark rounds found."
        )

    # Save detailed per-run measurements.
    write_csv(rows)

    print("")
    print(f"Saved CSV: {OUTPUT_FILE}")
    print(f"Analyzed runs: {len(rows)}")

    # Calculate the aggregate results using only
    # completed and validated benchmark pairs.
    print_aggregate_summary(rows)


if __name__ == "__main__":
    main()
