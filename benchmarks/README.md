# LedgerBase Performance Benchmarks

This directory contains reproducible local performance
benchmarks for LedgerBase's distributed accounting platform.

## 1. Objective

Evaluate whether Redis Cluster caching improves Trial
Balance reporting performance and reduces PostgreSQL
read-replica connection activity.

The experiment compares the same reporting endpoint with
Redis caching enabled and disabled.

## 2. Test Environment

- Application: LedgerBase
- Backend: Java Spring Boot microservices
- Database: PostgreSQL 17 with WAL read replica
- Cache: Six-node Redis Cluster (3 primaries, 3 replicas)
- Deployment: Local kind Kubernetes cluster
- Load generator: Grafana k6
- Metrics: Spring Boot Actuator, Micrometer, Prometheus
- Benchmark date: November 8, 2026

All results were collected locally, not from a
production deployment.

## 3. Benchmark Dataset

The benchmark uses an isolated test business:

- Business ID: 3
- Posted transactions: 1,000
- Ledger entries: 2,000
- Accounts: 100
- Reporting date: 2026-10-31
- Total debits: $523,190.19
- Total credits: $523,190.19

The Trial Balance is verified to be balanced before
the k6 workload begins.

The dataset generator is available at:

`benchmarks/data/generate_dataset.py`

The generator supports dry-run operation, bounded
batch execution, and reconciliation of existing records.

## 4. Benchmark Methodology

The k6 reporting scenario uses:

- Arrival rate: 2 requests/second
- Duration: 30 seconds per condition
- Target: Authenticated Trial Balance endpoint
- Conditions: Redis enabled and disabled
- Repetitions: Three paired rounds
- Order: ON/OFF, OFF/ON, ON/OFF

Each condition receives the same dataset and request
configuration.

The runner verifies report correctness before the
measurement, captures Prometheus counters before and
after each run, and checks for reporting Pod changes.

The original Kubernetes cache configuration is
restored when the script exits normally or handles
an interruption.

## 5. Results

### Latency

| Round | Redis ON avg | Redis OFF avg | Reduction |
|---|---:|---:|---:|
| 1 | 19.85 ms | 35.65 ms | 44.30% |
| 2 | 19.51 ms | 29.19 ms | 33.17% |
| 3 | 20.38 ms | 30.12 ms | 32.34% |

Mean of paired average-latency reductions: approximately
36.6%.

This is the arithmetic mean of the three per-round
percentage reductions, not a pooled p95 measurement.

### PostgreSQL Replica Activity

| Round | Redis ON | Redis OFF |
|---|---:|---:|
| 1 | 6 / 61 | 128 / 61 |
| 2 | 6 / 61 | 128 / 61 |
| 3 | 6 / 60 | 128 / 61 |

Values represent HikariCP replica connection
acquisitions / HTTP requests.

Normalized replica connection-acquisition reduction:
approximately 95.3%.

Across all rounds:
- Redis ON: 182 cache hits / 182 requests
- Redis OFF: 183 cache bypasses / 183 requests
- HTTP failures: 0 / 365
- Dropped iterations: 0

The observed cache hit rate was 100% because the
benchmark intentionally warmed a repeatedly requested
Trial Balance report. It does not represent a general
production cache hit rate.

## 6. Run the Benchmarks

Requirements:

- Running LedgerBase kind Kubernetes environment
- kubectl
- Grafana k6
- jq
- curl
- Python 3
- Valid LedgerBase JWT

Run these commands from the repository root.

Validate the scripts:

```bash
bash -n benchmarks/scripts/compare_cache.sh
python3 -m py_compile benchmarks/scripts/analyze_results.py
```

After securely obtaining an access token, run:

```bash
ROUND=1 \
ACCESS_TOKEN="$ACCESS_TOKEN" \
BUSINESS_ID=3 \
AS_OF_DATE=2026-10-31 \
bash benchmarks/scripts/compare_cache.sh
```

Repeat using `ROUND=2` and `ROUND=3`.

Refresh the JWT before a round if its remaining
lifetime is insufficient.

Then analyze:

```bash
python3 benchmarks/scripts/analyze_results.py
```

The analyzer writes:

`benchmarks/results/cache-comparison/summary.csv`

Generated benchmark data and result artifacts are
excluded from Git.

## 7. Limitations

1. The workload was run against a local kind cluster,
   so results may differ under production hardware,
   networking, and traffic patterns.

2. The experiment used approximately two requests
   per second for 30 seconds per condition.

3. Three paired comparisons provide preliminary
   repeatability evidence, not statistical confidence
   suitable for general production predictions.

4. Redis caching was warmed before measurement.
   Cold-cache performance was not evaluated.

5. The test repeatedly requests one report for one
   business. Multi-business and mixed-query workloads
   were not evaluated.

6. HikariCP connection acquisitions are not equivalent
   to SQL query counts, PostgreSQL CPU utilization,
   or total database load.

7. Cache and Reporting Service metrics do not by
   themselves measure primary database activity
   in Accounting Core.

8. The runner requires valid authentication and
   assumes no concurrent deployment modifications.

## 8. Future Experiments

- Higher request rates and concurrent users
- Larger accounting datasets
- Cold-cache versus warm-cache comparisons
- Mixed-business and mixed-report workloads
- Direct PostgreSQL query-count measurements
- Longer duration and repeated statistical trials
- Resource utilization under sustained load
```
