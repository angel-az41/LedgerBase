# LedgerBase — Local Kubernetes Deployment Guide

> **Scope:** Reproducible development and portfolio deployment with Docker Desktop, kind, Kubernetes, Java 25/Spring Boot, PostgreSQL 17, Redis Cluster, Kafka KRaft, Prometheus, and Grafana.

>
> **Validated:** November 6–9, 2026 · `kind-ledgerbase-local` · namespace `ledgerbase`.

>
> **Important:** This is a single-node local Kubernetes cluster, not a production high-availability deployment.

## 1. Architecture

```text
Developer Browser
       |
       v
http://localhost:3001
       |
       v
React + Nginx Frontend
       |
       v
Spring Cloud Gateway :8081
       |
       +----------------------------+
       |                            |
       v                            v
Accounting Core :8080       Reporting Service :8082
       |                            |
       v                            +--> Redis Cluster
PostgreSQL Primary                  |    (3 primaries, 3 replicas)
       |                            |
       +--> WAL Streaming ---------> PostgreSQL Read Replica
       |                            
       +--> Transactional Outbox
                 |
                 v
           Kafka Publisher
                 |
                 v
           Kafka KRaft Broker
                 |
                 v
           Kafka Consumer
                 |
                 v
           Idempotent Inbox
                 |
                 v
           PostgreSQL Primary
Monitoring:
Accounting Core ------+
Reporting Service ----+--> Prometheus --> Grafana
API Gateway ----------+
```

### Service responsibilities

**Accounting Core**

- Handles financial writes and transaction posting.
- Enforces JWT authentication and business-scoped RBAC.
- Executes PostgreSQL transactions with ACID guarantees.
- Runs Flyway database migrations.
- Publishes committed financial events through the transactional outbox.
- Consumes Kafka events using an idempotent inbox.

**Reporting Service**

- Reads financial data from PostgreSQL's streaming replica.
- Uses restricted database credentials.
- Calculates Trial Balance reports.
- Uses revision-aware Redis caching.
- Falls back to PostgreSQL when Redis is unavailable.

**Spring Cloud Gateway**

- Routes Trial Balance requests to Reporting Service.
- Routes other financial API requests to Accounting Core.
- Provides a single API entry point for the frontend.

**Infrastructure**

- PostgreSQL 17: primary database and streaming replica.
- Redis Cluster: six Pods, three primaries and three replicas.
- Kafka: single-node KRaft broker with persistent storage.
- Prometheus: collects application metrics.
- Grafana: visualizes operational metrics.

## 2. Prerequisites

Install and configure:

- Docker Desktop.
- kind.
- kubectl.
- Python 3.
- Git and GitHub Desktop.
- LedgerBase source repository.

Ensure Docker Desktop has sufficient CPU, memory, and storage for the Java services, PostgreSQL, Redis, Kafka, Prometheus, and Grafana.

Check Docker:

```bash
docker info
```

Check the current Kubernetes context:

```bash
kubectl config current-context
```

The expected context for the tested environment is:

```text
kind-ledgerbase-local
```

### Create the cluster

Only create a new cluster if one does not already exist.

```bash
kind get clusters
```

For a new environment:

```bash
kind create cluster \
  --name ledgerbase-local \
  --config k8s/kind/cluster-config.yaml
```

Select the cluster:

```bash
kubectl config use-context kind-ledgerbase-local
```

Create the LedgerBase namespace:

```bash
kubectl apply -f k8s/base/namespace.yaml
```

**Important:** Do not delete or recreate an existing kind cluster to fix ordinary deployment problems. Its local persistent volumes may contain PostgreSQL, Redis, Kafka, and monitoring data.

## 3. Kubernetes Secrets and Configuration

LedgerBase stores application credentials using Kubernetes Secrets.

| Resource | Purpose |
|---|---|
| `ledgerbase-postgres-auth` | PostgreSQL primary credentials |
| `ledgerbase-postgres-replication` | PostgreSQL streaming replication credentials |
| `ledgerbase-jwt-auth` | JWT signing secret |
| `ledgerbase-reporting-db-auth` | Reporting database reader credentials |
| `ledgerbase-redis-auth` | Redis Cluster authentication |
| `ledgerbase-grafana-admin` | Grafana administrator credentials |

The deployment also uses:

```text
ledgerbase-kafka-cluster-id
```

This ConfigMap stores the stable Kafka KRaft Cluster ID.

### Verify credentials exist

```bash
kubectl get secrets -n ledgerbase
```

Verify the Kafka Cluster ID:

```bash
kubectl get configmap \
  ledgerbase-kafka-cluster-id \
  -n ledgerbase
```

### Security requirements

- Never commit passwords or JWT secrets to Git.
- Do not store real credentials directly in Kubernetes manifests.
- Use Secret references in Deployment environment variables.
- Preserve the Kafka Cluster ID when reusing its existing persistent volume.
- Avoid printing sensitive Secret values in terminal screenshots.
- The PostgreSQL primary Pod sets `automountServiceAccountToken: false` to avoid unnecessarily mounting Kubernetes API credentials.

For a completely new environment, create credentials through a secure provisioning procedure before starting the dependent services.

The PostgreSQL replication Secret and database initialization credentials must match the existing manifests. Check their exact keys before automating initial setup.

## 4. Build Docker Images

LedgerBase uses Java 25 multi-stage Docker builds for its Java services.

### Accounting Core

Build context: `backend/`.

```bash
docker build \
  -t ledgerbase-accounting-core:local \
  -f backend/Dockerfile \
  backend
```

### Reporting Service

Build context: repository root.

```bash
docker build \
  -t ledgerbase-reporting-service:local \
  -f reporting-service/Dockerfile \
  .
```

### Spring Cloud Gateway

Build context: repository root.

```bash
docker build \
  -t ledgerbase-gateway:local \
  -f gateway/Dockerfile \
  .
```

### React Frontend

```bash
docker build \
  -t ledgerbase-frontend:local \
  -f frontend/Dockerfile \
  frontend
```

Confirm that the frontend build context matches the actual Dockerfile `COPY` instructions.

### Load Images Into kind

Local images must be imported into the kind cluster.

```bash
kind load docker-image \
  ledgerbase-accounting-core:local \
  --name ledgerbase-local
```

```bash
kind load docker-image \
  ledgerbase-reporting-service:local \
  --name ledgerbase-local
```

```bash
kind load docker-image \
  ledgerbase-gateway:local \
  --name ledgerbase-local
```

```bash
kind load docker-image \
  ledgerbase-frontend:local \
  --name ledgerbase-local
```

**Important:** Loading a rebuilt image does not automatically restart an existing Kubernetes Pod.

After rebuilding and loading an image, restart the corresponding Deployment:

```bash
kubectl rollout restart \
  deployment/accounting-core \
  -n ledgerbase
```

Verify:

```bash
kubectl rollout status \
  deployment/accounting-core \
  -n ledgerbase \
  --timeout=180s
```

Use the appropriate Deployment name for other services.

## 5. Kubernetes Deployment Order

Deploy the infrastructure in dependency order.

Do not delete existing persistent volumes or recreate initialized databases during routine redeployments.

### 5.1 PostgreSQL Primary

Apply PostgreSQL host authentication configuration:

```bash
kubectl apply \
  -f k8s/base/postgres-primary-hba.yaml
```

Deploy the primary:

```bash
kubectl apply \
  -f k8s/base/postgres-primary.yaml
```

Wait for readiness:

```bash
kubectl rollout status \
  statefulset/postgres \
  -n ledgerbase \
  --timeout=300s
```

The primary database is available internally at:

```text
postgres:5432
```

### 5.2 PostgreSQL Replication Role

Run the replication initialization Job:

```bash
kubectl apply \
  -f k8s/base/postgres-replication-role-job.yaml
```

Wait for completion:

```bash
kubectl wait \
  --for=condition=complete \
  job/postgres-replication-role \
  -n ledgerbase \
  --timeout=180s
```

The Job configures the replication role required by the standby.

### 5.3 PostgreSQL Streaming Replica

Deploy:

```bash
kubectl apply \
  -f k8s/base/postgres-replica.yaml
```

Verify:

```bash
kubectl rollout status \
  statefulset/postgres-replica \
  -n ledgerbase \
  --timeout=300s
```

The read replica is available at:

```text
postgres-replica:5432
```

Replication uses PostgreSQL WAL streaming and a physical replication slot.

### 5.4 Kafka KRaft Broker

Apply the stable Kafka Cluster ID:

```bash
kubectl apply \
  -f k8s/base/kafka-cluster-id.yaml
```

Deploy Kafka:

```bash
kubectl apply \
  -f k8s/base/kafka.yaml
```

Verify:

```bash
kubectl rollout status \
  statefulset/kafka \
  -n ledgerbase \
  --timeout=300s
```

Kafka uses:

```text
kafka:9092
```

The local broker runs in single-node KRaft mode with persistent storage.

Do not regenerate the Kafka Cluster ID when the existing Kafka volume contains broker metadata.

### 5.5 Kafka Topic Initialization

Create the transaction event topic:

```bash
kubectl apply \
  -f k8s/base/kafka-topic-init-job.yaml
```

Verify the Job:

```bash
kubectl wait \
  --for=condition=complete \
  job/kafka-topic-init \
  -n ledgerbase \
  --timeout=180s
```

The event topic is:

```text
ledgerbase.transaction-posted.v1
```

Configuration:

- Partitions: 1.
- Replication factor: 1.
- Broker: Kafka node 1.

The initialization Job preserves an existing topic and its messages.

### 5.6 Redis Cluster

Deploy Redis:

```bash
kubectl apply \
  -f k8s/base/redis-cluster.yaml
```

Verify all Redis Pods:

```bash
kubectl rollout status \
  statefulset/redis-cluster \
  -n ledgerbase \
  --timeout=300s
```

Initialize the cluster:

```bash
kubectl apply \
  -f k8s/base/redis-cluster-init-job.yaml
```

Verify initialization:

```bash
kubectl wait \
  --for=condition=complete \
  job/redis-cluster-init \
  -n ledgerbase \
  --timeout=180s
```

Redis uses:

- Six Redis Pods.
- Three primary nodes.
- Three replica nodes.
- 16,384 hash slots.
- Persistent Redis volumes.
- Password authentication.

Redis is accessible through the headless Service:

```text
redis-cluster-headless
```

### 5.7 Accounting Core

Deploy:

```bash
kubectl apply \
  -f k8s/base/accounting-core.yaml
```

Verify:

```bash
kubectl rollout status \
  deployment/accounting-core \
  -n ledgerbase \
  --timeout=300s
```

Accounting Core connects to the PostgreSQL primary and runs Flyway migrations.

The existing Kafka configuration uses:

```yaml
SPRING_PROFILES_ACTIVE: kafka-outbox
LEDGERBASE_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
```

These are conceptual environment settings; the actual Kubernetes manifest expresses them as `env` entries.

The profile enables transactional outbox publication and Kafka event consumption.

### 5.8 Reporting Database Reader

After Accounting Core has applied the schema migrations, run:

```bash
kubectl apply \
  -f k8s/base/postgres-reporting-reader-job.yaml
```

Verify:

```bash
kubectl wait \
  --for=condition=complete \
  job/postgres-reporting-reader \
  -n ledgerbase \
  --timeout=180s
```

The restricted `ledgerbase_reader` database role must have the required reporting permissions without general write access or access to authentication sessions.

### 5.9 Reporting Service

Deploy:

```bash
kubectl apply \
  -f k8s/base/reporting-service.yaml
```

Verify:

```bash
kubectl rollout status \
  deployment/reporting-service \
  -n ledgerbase \
  --timeout=300s
```

The Reporting Service reads PostgreSQL's replica and uses revision-aware Redis caching.

For workload health, Kubernetes uses dedicated Spring Boot Actuator probes:

- Readiness: `/actuator/health/readiness`
- Liveness: `/actuator/health/liveness`

This prevents optional cache connectivity from being treated as an unconditional Pod health dependency.

The verified Kubernetes settings include:

```yaml
LEDGERBASE_TRIAL_BALANCE_CACHE_ENABLED: "true"
MANAGEMENT_HEALTH_REDIS_ENABLED: "false"
```

These represent the configured values; the manifest uses Kubernetes `env` entry syntax.

Disabling Redis as a health dependency allows reporting to remain available when the optional cache becomes unavailable.

### 5.10 Spring Cloud Gateway

Deploy:

```bash
kubectl apply \
  -f k8s/base/gateway.yaml
```

Verify:

```bash
kubectl rollout status \
  deployment/gateway \
  -n ledgerbase \
  --timeout=300s
```

Gateway routes financial API requests between Accounting Core and Reporting Service.

### 5.11 React Frontend

Deploy:

```bash
kubectl apply \
  -f k8s/base/frontend.yaml
```

Verify:

```bash
kubectl rollout status \
  deployment/frontend \
  -n ledgerbase \
  --timeout=300s
```

Open:

```text
http://localhost:3001
```

The kind cluster maps localhost port `3001` to the frontend NodePort.

### 5.12 Prometheus

Deploy:

```bash
kubectl apply \
  -f k8s/base/prometheus.yaml
```

Verify:

```bash
kubectl rollout status \
  deployment/prometheus \
  -n ledgerbase \
  --timeout=180s
```

Prometheus scrapes the following application endpoints:

```text
accounting-core:8080/actuator/prometheus
reporting-service:8082/actuator/prometheus
gateway:8081/actuator/prometheus
```

Metrics are collected every 15 seconds, with seven-day retention in the local deployment.

### 5.13 Grafana

First apply the dashboard provider:

```bash
kubectl apply \
  -f k8s/base/grafana-dashboard-config.yaml
```

Create the dashboard ConfigMap from the version-controlled JSON:

```bash
kubectl create configmap grafana-dashboard-json \
  -n ledgerbase \
  --from-file=ledgerbase-dashboard.json=k8s/base/grafana-dashboard.json \
  --dry-run=client \
  -o yaml \
| kubectl apply -f -
```

Deploy Grafana:

```bash
kubectl apply \
  -f k8s/base/grafana.yaml
```

Verify:

```bash
kubectl rollout status \
  deployment/grafana \
  -n ledgerbase \
  --timeout=180s
```

Grafana loads its Prometheus datasource and LedgerBase dashboard automatically.

## 6. Final Deployment Verification

### 6.1 Kubernetes Resources

```bash
kubectl config current-context
```

```bash
kubectl get deployments,statefulsets,jobs,pvc \
  -n ledgerbase
```

Validated across November 6–9, 2026 (the Pod and workload readiness checks were repeated November 9):

| Resource | Result |
|---|---|
| Deployments | 6/6 ready |
| StatefulSets | 4/4 ready |
| Initialization Jobs | 4/4 complete |
| Persistent Volume Claims | 11/11 bound |

### 6.2 Frontend and API

Verify the database health API:

```bash
curl -i \
  http://localhost:3001/api/v1/health/database
```

Expected:

```json
{
  "status": "connected",
  "database": "ledgerbase"
}
```

Verify JWT protection:

```bash
curl -i \
  http://localhost:3001/api/v1/businesses
```

Expected:

```text
HTTP/1.1 401 Unauthorized
```

An unauthenticated request must not return protected business data.

### 6.3 PostgreSQL Replication

```bash
kubectl exec -n ledgerbase postgres-0 -- \
  psql -U ledgerbase -d ledgerbase \
  -c "
    SELECT application_name, state, sync_state
    FROM pg_stat_replication;
  "
```

Validated result:

```text
application_name | state     | sync_state
-----------------+-----------+-----------
walreceiver      | streaming | async
```

Verify that the standby is in recovery:

```bash
kubectl exec -n ledgerbase postgres-replica-0 -- \
  psql -U ledgerbase -d ledgerbase \
  -c "SELECT pg_is_in_recovery();"
```

Expected: `true`.

### 6.4 Redis Cluster

```bash
kubectl exec -n ledgerbase redis-cluster-0 -- \
  redis-cli -p 6379 CLUSTER INFO \
  | grep -E \
    'cluster_state|cluster_slots_ok|cluster_known_nodes'
```

Validated:

```text
cluster_state:ok
cluster_slots_ok:16384
cluster_known_nodes:6
```

Trial Balance caching uses business-scoped, reporting-date-specific, revision-aware keys. The November 9 test observed this key on a Redis primary:

```text
ledgerbase:report:trial-balance:v1:{business-1}:2026-11-08:rev-4
```

A `TTL` check returned 195 seconds remaining, confirming an expiring cached entry. Following two repeated report requests with the same date, Redis primary `redis-cluster-4` reported:

| Counter | Before | After | Change |
|---|---:|---:|---:|
| `keyspace_hits` | 61 | 63 | +2 |
| `keyspace_misses` | 3 | 3 | 0 |

The browser's corresponding authenticated Trial Balance API request returned HTTP 200 and displayed a balanced report. The matching key and correlated counter changes provide strong evidence of a Redis cache hit in this local test; these node-wide counters are not per-request tracing.

To repeat a non-destructive check, run:

```bash
kubectl exec -n ledgerbase redis-cluster-4 -- \
  redis-cli info stats | grep -E 'keyspace_hits:|keyspace_misses:'
```

Redis Pod roles and hash-slot assignments may change on cluster topology changes; identify the current primary for the relevant key instead of assuming Pod 4 is permanently its owner.

Earlier verification also covered revision-aware cache freshness and PostgreSQL fallback when Redis was unavailable.

### 6.5 Kafka Topic and Consumer Group

Verify the event topic:

```bash
kubectl exec -n ledgerbase kafka-0 -- \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 \
  --describe \
  --topic ledgerbase.transaction-posted.v1
```

Check the consumer:

```bash
kubectl exec -n ledgerbase kafka-0 -- \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 \
  --describe \
  --group ledgerbase-transaction-posted-inbox-v1
```

The consumer-group command reports current offsets and lag; do not treat earlier example offsets as a fixed expected value.

**November 9 transactional outbox and inbox verification:**

- All 1,007 rows in `outbox_events` were marked `PUBLISHED` with non-null publication timestamps.
- A Kafka console consumer observed transaction-posted events on `ledgerbase.transaction-posted.v1`.
- The application-created test transaction `1007` had a matching processed inbox receipt.
- That outbox row showed `attempt_count = 1` and the consumer receipt showed `PROCESSED`.

The recorded database publication and processing timestamps for transaction 1007 were `04:10:05.916754 UTC` and `04:10:05.963380 UTC` respectively. The approximately 47 ms difference is not a controlled end-to-end latency benchmark.

The message flow is:

```text
Posted Transaction
      |
      v
PostgreSQL Outbox -> Kafka Publisher -> Kafka Topic
                                           |
                                           v
                                    Kafka Consumer
                                           |
                                           v
                                  PostgreSQL Inbox
```

An idle timeout from the console consumer after it has read existing messages is not itself evidence of a failed application consumer. Earlier duplicate-replay testing verified the consumer's deduplication behavior; this smoke test independently verified a published event and processed inbox record.

Read-only database queries for independent verification:

```bash
kubectl exec -n ledgerbase postgres-0 -- \
  gosu postgres psql -U ledgerbase -d ledgerbase \
  -c "SELECT status, COUNT(*), COUNT(*) FILTER (WHERE published_at IS NOT NULL) AS stamped FROM outbox_events GROUP BY status;"
kubectl exec -n ledgerbase postgres-0 -- \
  gosu postgres psql -U ledgerbase -d ledgerbase \
  -c "SELECT o.transaction_id, o.status, o.attempt_count, i.processed_at FROM outbox_events o LEFT JOIN kafka_consumer_inbox i ON i.event_id=o.event_id WHERE o.transaction_id=1007;"
```

### 6.6 Prometheus

Start the port-forward:

```bash
kubectl port-forward \
  --address 127.0.0.1 \
  -n ledgerbase \
  service/prometheus \
  19090:9090
```

In another terminal:

```bash
curl -i http://localhost:19090/-/ready
```

Expected:

```text
Prometheus Server is Ready.
```

Query the three application targets:

```bash
curl -fsS -G \
  --data-urlencode 'query=up{job=~"accounting-core|reporting-service|gateway"}' \
  http://localhost:19090/api/v1/query
```

All three jobs should return `1`.

### 6.7 Grafana

Start the Grafana port-forward:

```bash
kubectl port-forward \
  --address 127.0.0.1 \
  -n ledgerbase \
  service/grafana \
  13000:3000
```

Open:

```text
http://localhost:13000
```

Navigate to:

**Dashboards → LedgerBase → LedgerBase | System Overview**

The dashboard contains:

- Accounting Core health.
- Reporting Service health.
- Gateway health.
- HTTP requests per second.
- HTTP 5xx errors per second.
- Mean HTTP response time.
- Process CPU usage.
- JVM heap usage.
- Reporting Service database connection pool.
- Kafka consumer requests.
- Outbox publisher polling.

These panels were verified using real application and infrastructure activity.

## 7. Troubleshooting

### Pod Does Not Become Ready

Check Pod status:

```bash
kubectl get pods -n ledgerbase
```

Inspect details:

```bash
kubectl describe pod \<pod-name> -n ledgerbase
```

Check application logs:

```bash
kubectl logs deployment/accounting-core \
  -n ledgerbase \
  --tail=150
```

Use the relevant Deployment name when troubleshooting another service.

### New Docker Image Does Not Appear

Rebuild the image, load it into kind, and restart the Deployment.

```bash
kind load docker-image \
  ledgerbase-accounting-core:local \
  --name ledgerbase-local
```

```bash
kubectl rollout restart \
  deployment/accounting-core \
  -n ledgerbase
```

### Redis Cluster Is Unhealthy

Inspect:

```bash
kubectl get pods -n ledgerbase \
  -l app.kubernetes.io/name=redis-cluster
```

Check cluster health:

```bash
kubectl exec -n ledgerbase redis-cluster-0 -- \
  redis-cli -p 6379 CLUSTER INFO
```

Do not delete Redis PVCs or cluster metadata as an initial troubleshooting step.

### Kafka Connection Failure

Check broker logs:

```bash
kubectl logs kafka-0 \
  -n ledgerbase \
  --tail=150
```

Verify the Kafka topic:

```bash
kubectl exec -n ledgerbase kafka-0 -- \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 \
  --list
```

Check the stable Kafka Cluster ID before attempting any recovery of persistent broker storage.

### Grafana Browser Connection Refused

Grafana is exposed locally through a temporary port-forward.

Restart it:

```bash
kubectl port-forward \
  --address 127.0.0.1 \
  -n ledgerbase \
  service/grafana \
  13000:3000
```

Leave the terminal open while using Grafana.

### Grafana Dashboard Missing

Verify ConfigMaps:

```bash
kubectl get configmaps -n ledgerbase \
  | grep grafana
```

Check Grafana provisioning logs:

```bash
kubectl logs deployment/grafana \
  -n ledgerbase \
  --tail=150
```

### Prometheus Target DOWN

Check the application metrics endpoint, its Kubernetes Service, and the Spring Security rules permitting `GET /actuator/prometheus`.

Do not assume that a Spring Security `permitAll()` rule restricts the endpoint to Prometheus. For production deployments, use appropriate authentication and network isolation.

## 8. Persistence and Data Safety

LedgerBase uses Kubernetes persistent volumes for:

- PostgreSQL primary.
- PostgreSQL replica.
- Six Redis nodes.
- Kafka.
- Prometheus.
- Grafana.

Persistent volumes preserve application state across ordinary Pod restarts.

**Do not delete these PVCs when updating application images or manifests.**

Local kind volumes are stored within the kind environment and are not independent backups.

For production deployments, additional requirements include:

- Off-cluster backups and tested recovery procedures.
- Multi-node Kubernetes infrastructure.
- Highly available PostgreSQL and Kafka.
- Appropriate Redis fault-domain separation.
- Encrypted service communications.
- Network policies.
- Secure secret management.
- Monitoring alerts.
- Resource planning and autoscaling.

## 9. Development Versus Production

The verified LedgerBase environment is a local distributed application deployment.

It demonstrates:

- Kubernetes-based multi-service orchestration.
- Containerized Java and React services.
- PostgreSQL primary/replica separation.
- Distributed Redis caching.
- Kafka asynchronous event processing.
- Transactional outbox reliability.
- Idempotent event consumption.
- Prometheus and Grafana observability.

It does **not** claim production-level high availability.

Limitations include:

- Single kind worker/control-plane node.
- Single Kafka broker.
- PostgreSQL asynchronous replication.
- Redis Pods sharing the same host.
- No demonstrated off-cluster backup and recovery system.
- No demonstrated production autoscaling or multi-node failure recovery.
- Internal application metrics exposed without additional monitoring authentication.

## 10. CI and Release Verification

GitHub Actions validates the repository through automated checks including Java/backend tests, frontend builds, Kubernetes resource validation, and multi-platform Docker image builds.

The November 8–9 Kubernetes security/reliability hardening change passed **16/16 CI checks** across the pull-request and main-branch push runs. CI validation included action and Kubernetes manifest linting as well as checks for build/benchmark scripts. The frontend Docker build runs the Node/Vite build on the builder's native architecture while retaining target-specific Nginx runtime images for multi-architecture output.

Before a documentation/release PR is merged:

1. Confirm the branch includes only intentional tracked changes and no credentials.
2. Run `git diff --check` and review the complete Markdown diffs.
3. Verify README links, code fences, deployment commands, and documented results.
4. Confirm GitHub Actions checks pass on the pull request and after merging into `main`.
5. Prepare versioned release notes stating local-environment limitations clearly.

Validate Grafana dashboard JSON without modifying the cluster:

```bash
python3 -m json.tool k8s/base/grafana-dashboard.json > /dev/null
```

The existing CI workflows are the authoritative reference for their precise test and manifest-validation coverage. Avoid using ad-hoc server-side `kubectl apply` validation against live, persistent resources during a documentation-only change.

## 11. Local Endpoints

| Component | Address | Access |
|---|---|---|
| LedgerBase frontend | `http://localhost:3001` | Local browser |
| Gateway | `gateway:8081` | Internal Kubernetes |
| Accounting Core | `accounting-core:8080` | Internal Kubernetes |
| Reporting Service | `reporting-service:8082` | Internal Kubernetes |
| PostgreSQL Primary | `postgres:5432` | Internal Kubernetes |
| PostgreSQL Replica | `postgres-replica:5432` | Internal Kubernetes |
| Kafka | `kafka:9092` | Internal Kubernetes |
| Redis Cluster | `redis-cluster-headless:6379` | Internal Kubernetes / per-Pod DNS |
| Prometheus | `http://localhost:19090` | Port-forward |
| Grafana | `http://localhost:13000` | Port-forward |

## 12. Verification Summary

**Initial deployment validation: November 6, 2026. Final smoke tests: November 8–9, 2026.**

| Area | Verified result |
|---|---|
| Kubernetes | 6/6 Deployments and 4/4 StatefulSets ready; running Pods had zero restarts at the final check |
| Initialization and storage | Four initialization Jobs complete and eleven PVCs bound in the earlier deployment verification |
| Frontend | HTTP 200 from `http://localhost:3001` |
| Accounting UI | Seven of seven browser workflow tests passed, including posting, immutability, and financial reports |
| PostgreSQL | WAL streaming replica reconnected and physical replication slot active after security changes |
| Kafka outbox | 1,007 published events with publication timestamps |
| Kafka inbox | `TransactionPosted` for transaction 1007 matched a processed inbox record |
| Redis | Six connected nodes, 3 primary/3 replica topology, 16,384 slots, and an expiring Trial Balance cache key |
| Cache behavior | Two additional keyspace hits with unchanged misses during repeated report requests |
| Reporting probes | Dedicated Spring Boot readiness and liveness Actuator endpoints deployed |
| Security | PostgreSQL primary disabled automatic ServiceAccount token mounting |
| CI | 16/16 successful PR and push checks for the final Kubernetes hardening change |
| Observability | Prometheus scraping and Grafana dashboards validated during the earlier Kubernetes deployment review |

These checks establish a working local Kubernetes application and the key financial/distributed processing paths. They do not certify high availability, production security, resilience under arbitrary failure, or disaster recovery.
