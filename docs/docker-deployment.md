# LedgerBase — Docker Deployment Guide

## Overview

LedgerBase supports a local Docker Compose deployment that combines the React frontend, Java Spring Boot microservices, PostgreSQL, Redis Cluster, and Kafka.

The deployment uses multi-stage Docker images for application services and persistent Docker volumes for stateful infrastructure.

> **Scope:** This guide describes the Docker Compose environment. The separately verified local Kubernetes deployment is documented in [Kubernetes Deployment](kubernetes-deployment.md). Kubernetes test results do not independently establish that a fresh Docker Compose deployment has passed the same tests.

## Architecture

The application consists of:

- **Frontend:** React, TypeScript, Vite, and Nginx.
- **Gateway:** Spring Cloud Gateway.
- **Accounting Core:** Spring Boot, MyBatis, and PostgreSQL.
- **Reporting Service:** Spring Boot and PostgreSQL read replica.
- **PostgreSQL:** Primary database and WAL streaming replica.
- **Redis Cluster:** Distributed, revision-aware Trial Balance caching.
- **Kafka:** Transactional outbox event transport and idempotent consumer processing.

### Request flow

```text
Browser
   |
   v
React + Nginx (:3000)
   |
   v
Spring Cloud Gateway (:8081)
   |
   +----> Accounting Core (:8080)
   |        |
   |        +----> PostgreSQL Primary
   |        |        |
   |        |        +----> Transactional Outbox
   |        |                      |
   |        +----> Kafka Publisher +----> Kafka Broker
   |                                       |
   |                                       v
   |                                Kafka Consumer
   |                                       |
   |                                       v
   |                              PostgreSQL Inbox
   |
   +----> Reporting Service (:8082)
            |
            +----> PostgreSQL Read Replica
            |
            +----> Redis Cluster
```

The frontend uses Nginx to forward `/api/` requests to Spring Cloud Gateway over Docker networking.

Accounting Core processes authoritative accounting writes and advances reporting revisions after relevant changes. Committed outbox events can be published to Kafka when the optional Kafka profile is enabled.

Reporting Service reads PostgreSQL's streaming replica and uses revision-aware Redis caching to avoid reusing outdated financial reports. Its authentication and business-access checks rely on the configured backend security integration.

## Prerequisites

Install:

- Docker Desktop with Docker Compose support.
- Git.

Ensure Docker Desktop is running before executing the commands below. The complete stack needs sufficient CPU, memory, and disk capacity for its Java services and stateful infrastructure.

The documented local deployment uses these host ports:

| Service | Host port |
|---|---|
| React frontend | `3000` |
| Accounting Core | `8080` |
| Spring Cloud Gateway | `8081` |
| Reporting Service | `8082` |
| PostgreSQL primary | `5434` |
| PostgreSQL replica | `5435` |
| Kafka | `9092` |
| Redis Cluster | `7000–7005` |

> The actual published ports depend on the current Compose files and local environment overrides. Check the combined configuration before starting services.

## Environment Configuration

Configure the project's local `.env` file before starting the containers.

Relevant credentials and secrets include:

- `POSTGRES_PASSWORD`
- `POSTGRES_REPLICA_PASSWORD`
- `REDIS_PASSWORD`
- `JWT_SECRET_BASE64`

Use `.env.example` and the current Compose files to determine all required variables. If an existing `.env` is already configured, preserve it rather than overwriting it.

Never commit `.env` files, passwords, or JWT signing secrets to Git.

### Optional Kafka profile

To enable Kafka-based asynchronous processing inside Accounting Core, configure:

```dotenv
LEDGERBASE_ACCOUNTING_PROFILES=kafka-outbox
```

The Docker Compose configuration uses the internal Kafka bootstrap address `kafka:19092` for Accounting Core. Verify that address against `docker-compose.kafka.yml` if the broker listeners are changed.

The Kafka profile may remain disabled when asynchronous event processing is not needed for a particular development run.

## Docker Compose Files

LedgerBase uses three Compose configuration files:

| File | Purpose |
|---|---|
| `docker-compose.yml` | PostgreSQL and Redis infrastructure |
| `docker-compose.kafka.yml` | Kafka broker |
| `docker-compose.app.yml` | Application services |

These files are combined when deploying the complete application.

## Start the Complete Application

Run all commands from the repository root.

### Validate configuration

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.kafka.yml \
  -f docker-compose.app.yml \
  config --quiet
```

This checks the merged Compose configuration's syntax and variable substitution; it does not prove service availability.

### Build and start services

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.kafka.yml \
  -f docker-compose.app.yml \
  up -d --build
```

Docker Compose starts the configured services and builds application images where necessary. Service readiness may lag behind container startup.

### Check container status

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.kafka.yml \
  -f docker-compose.app.yml \
  ps
```

Verify the expected services are running and inspect unhealthy or restarting containers before proceeding.

## Access the Application

Open `http://localhost:3000` in a browser.

Sign in using an existing LedgerBase development account and select a business. A fresh installation might require initial user and accounting data setup; the documented demonstration transactions are not automatically seeded by cloning the repository.

The React frontend communicates with the backend through the Nginx reverse proxy and Spring Cloud Gateway.

## Health Checks

### Frontend

```bash
curl -i http://localhost:3000/
```

Expected: HTTP 200 with the React application HTML.

### Database connectivity through Gateway

```bash
curl -i http://localhost:3000/api/v1/health/database
```

Expected for a working environment: HTTP 200 with the backend's connected-database status.

### Reporting Service

```bash
curl -i http://localhost:8082/actuator/health
```

Expected when the endpoint is accessible from the host: HTTP 200 with an `UP` health status. The Kubernetes deployment separately uses dedicated Actuator liveness and readiness endpoints for its probes.

These checks demonstrate availability only; they do not replace authenticated accounting tests.

## Verify Accounting Workflows

Using the Dockerized frontend on port `3000`:

1. Sign in.
2. Open the financial dashboard.
3. View the Chart of Accounts.
4. View Transactions.
5. Create a balanced draft journal entry using disposable test data.
6. Post the journal entry and confirm posted records cannot be edited.
7. Generate a Trial Balance and confirm debits equal credits.
8. Open the Income Statement and Balance Sheet.

Check that the reports use the selected business and reporting period, and that posted entries—not drafts—affect financial balances.

> These are suggested verification steps for Docker Compose. The completed November 9, 2026 end-to-end smoke test was performed on the **kind Kubernetes environment**, not this Compose deployment.

## Redis Cluster

Reporting Service uses Redis Cluster for revision-aware Trial Balance caching.

The documented local Redis topology has six nodes:

- Three primaries.
- Three replicas.
- Host ports `7000–7005`.

The Reporting Service uses Docker-specific address mapping to resolve the Redis Cluster's advertised addresses. Keep the application and Redis networking configuration consistent.

### Inspect Redis activity

The following command inspects the three listed Redis ports in the existing local container configuration. **It does not assume that those ports are always primaries**:

```bash
docker exec ledgerbase-redis-cluster sh -c '
for port in 7000 7001 7002; do
  echo "=== Redis node on port $port ==="
  redis-cli -p "$port" INFO replication | grep "^role:"
  redis-cli -p "$port" INFO stats | grep -E "^keyspace_(hits|misses):"
done
'
```

If Redis requires authentication, use the configured credentials through a secure method. Avoid putting passwords in shell history or committed scripts.

To inspect all Redis nodes or investigate a topology change, use `CLUSTER NODES` through an appropriate cluster node. A cache-hit increase after repeated requests is supporting evidence of cache usage, but global counters can also reflect unrelated operations.

## Kafka

Kafka is configured through `docker-compose.kafka.yml`. Accounting Core uses the `kafka-outbox` Spring profile when asynchronous event processing is enabled.

Financial posting and outbox event insertion occur within a PostgreSQL transaction. An outbox publisher delivers committed events to Kafka; the consumer stores deduplication receipts in PostgreSQL before acknowledging processed events.

### Inspect Kafka consumer logs

```bash
docker logs --tail 200 ledgerbase-accounting-core
```

Look for subscription, partition assignment, publishing activity, and consumer-processing errors. Consumer group connectivity alone does not establish successful event publishing or durable inbox processing.

For an end-to-end check, post a test journal entry and verify the matching outbox status and consumer inbox receipt. Perform database checks using the correct database connection and credentials for your Compose environment.

## Logs and Troubleshooting

### Accounting Core

```bash
docker logs --tail 100 ledgerbase-accounting-core
```

### Reporting Service

```bash
docker logs --tail 100 ledgerbase-reporting-service
```

### Gateway

```bash
docker logs --tail 100 ledgerbase-gateway
```

### Frontend

```bash
docker logs --tail 100 ledgerbase-frontend
```

> The container names above follow the documented local configuration. If a name differs, use `docker compose ... ps` to obtain the actual name.

### Authentication

If a request returns HTTP 401:

- Confirm that the user is signed in.
- Check whether the browser sends the expected authentication token.
- Inspect Accounting Core and Reporting Service logs.
- Verify that the application services use compatible JWT configuration.
- Do not paste bearer tokens, passwords, or full authorization headers into issues or documentation.

### Reporting

If a Trial Balance request fails:

- Verify Reporting Service is running.
- Check the PostgreSQL replica connection and replication status.
- Review Redis connectivity and cache errors.
- Verify the user's business permissions.
- Consider replica synchronization delay when newly posted data is not visible immediately.

## Stop Services

To stop the application while retaining stored data:

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.kafka.yml \
  -f docker-compose.app.yml \
  stop
```

Restart with the combined Compose configuration when needed.

## Data Persistence

PostgreSQL and Redis use persistent Docker storage in the local development environment; other stateful services may also use persistent volumes according to their Compose definitions.

Do not remove volumes during normal restarts or routine debugging. In particular, avoid destructive commands such as `docker compose down -v` unless a deliberate data reset is intended and the relevant data has been backed up.

Local container volumes are not a substitute for off-host backups or tested disaster recovery.

## Deployment Scope and Verification

This configuration is intended for local development and portfolio demonstrations. It is separate from LedgerBase's local Kubernetes (kind) environment.

The November 9, 2026 checks—including seven successful browser accounting workflows, PostgreSQL outbox publication, Kafka consumer processing, and Redis repeat-request cache hits—were performed against the **Kubernetes deployment**. They should not be represented as independent verification of the Docker Compose environment.

Production deployment requires additional safeguards, including secure service communications, credential lifecycle management, high availability, backup and recovery, monitoring and alerting, and operational security controls.

For the tested Kubernetes deployment procedures and evidence, see [Local Kubernetes Deployment Guide](kubernetes-deployment.md).
