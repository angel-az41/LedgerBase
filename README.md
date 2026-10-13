# LedgerBase

LedgerBase is a full-stack financial ledger and accounting platform built with Java, Spring Boot, React, TypeScript, and PostgreSQL.

The project focuses on reliable financial transaction processing, double-entry bookkeeping, and database-backed accounting workflows. It is designed as a portfolio project demonstrating full-stack development, backend engineering, relational database design, and financial data integrity.

## Tech Stack

| Layer | Technologies |
|---|---|
| Frontend | React, TypeScript, Vite |
| Backend | Java, Spring Boot, REST APIs |
| Database Access | MyBatis |
| Database | PostgreSQL |
| Database Migrations | Flyway |
| Development Environment | Docker, Docker Compose |
| Build Tools | Maven, npm |

## Architecture

LedgerBase follows a layered full-stack architecture:

```text
React + TypeScript
        |
        | HTTP / JSON
        v
Spring Boot REST API
        |
        v
Service Layer
        |
        v
MyBatis Mappers
        |
        v
PostgreSQL
```

The frontend handles user interactions and accounting workflows, while Spring Boot implements business logic and validation. MyBatis connects the application to PostgreSQL, and Flyway manages database schema migrations.

## Features

### Chart of Accounts

- Create general ledger accounts with customizable account codes.
- Classify accounts as Asset, Liability, Equity, Revenue, or Expense.
- View, search, and filter accounts.
- Rename active accounts.
- Deactivate accounts without deleting their historical records.
- Display account statistics and operation feedback.

### Transaction Processing — Backend Implemented

- Create financial transactions with double-entry ledger records.
- Validate that total debits equal total credits.
- Support draft and posted transaction states.
- Protect posted financial records against subsequent modification.
- Record audit information for supported transaction operations.

The React transaction management interface is currently under development.

### Planned Enhancements

- Transaction creation, review, and posting through React.
- Trial Balance, Balance Sheet, and Income Statement.
- Account reporting groups and financial statement mappings.
- Authentication and business-level authorization.
- Financial analytics and dashboard reporting.

## Project Structure

```text
LedgerBase/
├── backend/             # Spring Boot REST API and business logic
├── frontend/            # React + TypeScript application
├── docker-compose.yml   # Local PostgreSQL environment
└── README.md
```

## Getting Started

### Prerequisites

- Java 25
- Node.js and npm
- Docker and Docker Compose
- Git

### 1. Clone the Repository

```bash
git clone <https://github.com/angel-az41/LedgerBase>
cd LedgerBase
```

### 2. Start PostgreSQL

```bash
docker compose up -d
```

The development database is configured to use port `5434`.

### 3. Start the Backend

Configure the required database environment variables in a local `.env` file.

From the project root:

```bash
set -a
source .env
set +a

cd backend
./mvnw spring-boot:run
```

The Spring Boot API runs at:

```text
http://localhost:8080
```

### 4. Start the Frontend

In a separate terminal, from the project root:

```bash
cd frontend
npm install
npm run dev
```

Open the application at:

```text
http://localhost:5173
```

During local development, Vite proxies `/api` requests to Spring Boot on port `8080`.

## Testing

Run backend tests:

```bash
cd backend
./mvnw test
```

Backend integration tests require the configured PostgreSQL test environment.

Verify the frontend production build:

```bash
cd frontend
npm run build
```

## Development Status

LedgerBase is under active development.

The database foundation, backend account management, double-entry transaction processing, and React Chart of Accounts interface have been implemented.

Transaction management, financial reporting, authentication, and additional accounting workflows are planned or in progress.

**Note:** LedgerBase is currently a development and portfolio application, not production-ready accounting software. Authentication and business ownership validation must be implemented before public deployment.
