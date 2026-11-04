# LedgerBase — Docker Deployment Guide

## Overview

LedgerBase supports a local Docker Compose deployment
that combines the React frontend, Java Spring Boot
microservices, PostgreSQL, Redis Cluster, and Kafka.

The deployment uses multi-stage Docker images for
the application services and persistent Docker volumes
for stateful infrastructure.

## Architecture

The application consists of:

- **Frontend:** React, TypeScript, Vite, and Nginx
- **Gateway:** Spring Cloud Gateway
- **Accounting Core:** Spring Boot, MyBatis, and PostgreSQL
- **Reporting Service:** Spring Boot and PostgreSQL read replica
- **PostgreSQL:** Primary database and streaming replica
- **Redis Cluster:** Distributed Trial Balance caching
- **Kafka:** Transactional outbox event transport

### Request flow

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
  |        |
  |        +----> Kafka
  |
  +----> Reporting Service (:8082)
           |
           +----> PostgreSQL Read Replica
           |
           +----> Redis Cluster

The frontend uses Nginx to forward `/api/` requests
to Spring Cloud Gateway over Docker networking.

Accounting Core processes accounting writes and
advances reporting revisions.

Reporting Service reads the PostgreSQL replica and
uses revision-aware Redis caching to avoid returning
outdated financial reports.

## Prerequisites

Install:

- Docker Desktop with Docker Compose support
- Git

Ensure Docker Desktop is running before executing
the commands below.

The local deployment uses these host ports:

| Service | Host port |
|---|---|
| React frontend | 3000 |
| Accounting Core | 8080 |
| Spring Cloud Gateway | 8081 |
| Reporting Service | 8082 |
| PostgreSQL primary | 5434 |
| PostgreSQL replica | 5435 |
| Kafka | 9092 |
| Redis Cluster | 7000–7005 |

## Environment Configuration

Configure the project's local `.env` file before
starting the containers.

Required credentials and secrets include:

- `POSTGRES_PASSWORD`
- `POSTGRES_REPLICA_PASSWORD`
- `REDIS_PASSWORD`
- `JWT_SECRET_BASE64`

Use the existing project configuration to provide
valid values for these variables.

Never commit `.env` files or credentials to Git.

### Optional Kafka Profile

To enable Kafka-based asynchronous processing
inside Accounting Core, configure:

```dotenv
LEDGERBASE_ACCOUNTING_PROFILES=kafka-outbox
```

Accounting Core uses the internal Kafka bootstrap
address `kafka:19092` when running in Docker.

The Kafka profile can be left disabled for
deployments that do not require event processing.

## Docker Compose Files

LedgerBase uses three Compose configuration files:

| File | Purpose |
|---|---|
| `docker-compose.yml` | PostgreSQL and Redis infrastructure |
| `docker-compose.kafka.yml` | Kafka broker |
| `docker-compose.app.yml` | Application services |

These files are combined when deploying the
complete application.

## Start the Complete Application

Run the following commands from the project root.

### Validate Configuration

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.kafka.yml \
  -f docker-compose.app.yml \
  config --quiet
```

### Build and Start Services

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.kafka.yml \
  -f docker-compose.app.yml \
  up -d --build
```

Docker Compose starts the configured services
and builds application images where necessary.

Depending on the existing service dependencies,
some application services may become ready
after their containers have started.

### Check Container Status

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.kafka.yml \
  -f docker-compose.app.yml \
  ps
```

Verify that the expected services are running.

## Access the Application

Open:

http://localhost:3000

Sign in using an existing LedgerBase development
account and select the appropriate business.

The React frontend communicates with the backend
through the Nginx reverse proxy.

## Health Checks

### Frontend

```bash
curl -i http://localhost:3000/
```

Expected: HTTP 200 with the React HTML document.

### Database Connectivity Through Gateway

```bash
curl -i http://localhost:3000/api/v1/health/database
```

Expected: HTTP 200 with a connected database status.

### Reporting Service

```bash
curl -i http://localhost:8082/actuator/health
```

Expected: HTTP 200 and an UP health status.

These health checks verify service availability,
but do not replace authenticated functional tests.

## Verify Accounting Workflows

Using the Dockerized frontend on port 3000:

1. Sign in.
2. Open the financial dashboard.
3. View the Chart of Accounts.
4. View Transactions.
5. Generate a Trial Balance.
6. Open the Income Statement.
7. Open the Balance Sheet.

Confirm that the reports display the expected
financial data for the selected business.

## Redis Cluster

Reporting Service uses Redis Cluster for
revision-aware Trial Balance caching.

The local Redis Cluster has six nodes:

- Three primary nodes
- Three replica nodes
- Ports 7000–7005

The Reporting Service uses Docker-specific
address mapping to resolve the Redis Cluster's
advertised localhost addresses.

This preserves the existing local Redis topology.

### Inspect Redis Activity

```bash
docker exec ledgerbase-redis-cluster sh -c '
for port in 7000 7001 7002; do
  echo "Redis primary $port"
  redis-cli -p "$port" INFO stats \
    | grep -E "^keyspace_(hits|misses):"
done
'
```

Cache hits and misses can be observed while
generating financial reports.

## Kafka

Kafka is configured through:

`docker-compose.kafka.yml`

Accounting Core uses the `kafka-outbox`
Spring profile when enabled.

Kafka supports asynchronous financial events
through the transactional outbox and
idempotent consumer infrastructure.

### Inspect Kafka Consumer Logs

```bash
docker logs --tail 200 \
  ledgerbase-accounting-core
```

Look for consumer group membership,
topic subscriptions, and partition assignments.

Consumer group connectivity alone does not
verify successful event publishing or processing.

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

### Authentication

If a request returns HTTP 401:

- Confirm that the user is signed in.
- Check that the browser sends its authentication token.
- Inspect Accounting Core and Reporting Service logs.
- Verify that the application containers share
  the expected JWT configuration.

### Reporting

If a Trial Balance request fails:

- Verify the reporting service is running.
- Check the PostgreSQL replica connection.
- Review Redis connectivity and cache errors.
- Verify the user's business permissions.
- Check whether replica synchronization is delayed.

## Stop Services

To stop the application while preserving
container configuration and stored data:

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.kafka.yml \
  -f docker-compose.app.yml \
  stop
```

Start the services again using the
combined Compose configuration.

## Data Persistence

PostgreSQL and Redis use persistent Docker
storage for local development.

Do not delete volumes when restarting services
or debugging application configuration.

In particular, avoid destructive commands
such as `docker compose down -v` unless
a deliberate data reset is required.

## Deployment Scope

This configuration is intended for local
development and portfolio demonstrations.

Production deployment requires additional
security, availability, secret management,
backup, and operational controls.
