# LedgerBase

LedgerBase is a full-stack financial accounting platform built with
**Java, Spring Boot, React, TypeScript, MyBatis, and PostgreSQL**.

The project focuses on reliable financial transaction processing,
double-entry bookkeeping, general ledger management, and
database-backed financial reporting.

LedgerBase is designed as a backend-focused full-stack portfolio
project demonstrating:

- RESTful API development with Spring Boot.
- Layered backend architecture and business logic.
- Relational database design with PostgreSQL.
- Transactional financial data processing.
- Double-entry bookkeeping and accounting integrity.
- Database migrations with Flyway.
- React and TypeScript frontend development.
- End-to-end accounting workflow validation.

The application is currently under active development.

---

## Tech Stack

| Layer | Technologies |
|---|---|
| Frontend | React, TypeScript, Vite |
| Backend | Java 25, Spring Boot, REST APIs |
| Database Access | MyBatis |
| Database | PostgreSQL 17 |
| Database Migrations | Flyway |
| Development Environment | Docker, Docker Compose |
| Build Tools | Maven, npm |
| Version Control | Git, GitHub |

---

## Architecture

LedgerBase follows a layered full-stack architecture.

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

### Frontend

The React frontend provides user interfaces for managing
financial data and interacting with the accounting system.

It handles:

- Business selection.
- User input and form validation.
- Chart of Accounts management.
- Journal entry creation and review.
- Transaction posting workflows.
- Financial statement presentation.
- Loading, error, and success states.

The frontend communicates with the Spring Boot backend
through REST APIs.

### Backend

The Spring Boot backend implements accounting business
logic and validates financial operations.

Responsibilities include:

- Processing REST API requests.
- Validating journal entries.
- Managing transaction lifecycle states.
- Enforcing double-entry bookkeeping rules.
- Coordinating database transactions.
- Retrieving general ledger and reporting data.
- Applying accounting and business-level constraints.

### Database

PostgreSQL stores the application's financial records.

The database supports:

- Relational integrity and foreign key constraints.
- Business-scoped financial data.
- Financial transaction and ledger entry records.
- Database-level protection of posted transactions.
- Business-specific journal numbering.
- Audit records.
- Accounting and reporting structures.

Flyway manages versioned database schema migrations.

MyBatis maps Java backend operations to SQL statements.

---

## Implemented Features

### 1. Business and Accounting Structure

LedgerBase supports business-scoped financial records.

Accounting data is associated with a specific business,
allowing the application to maintain separate financial
records for different businesses.

Implemented accounting structures include:

- Businesses and related financial data.
- Fiscal years.
- Accounting periods.
- General ledger accounts.
- Financial transactions.
- Ledger entries.
- Reporting classifications.

Business-scoped data access is not a substitute for
production authentication or authorization.

### 2. Chart of Accounts

Users can manage a business's general ledger accounts.

Features include:

- Create general ledger accounts.
- Assign customizable account codes.
- Classify accounts as Asset, Liability, Equity,
  Revenue, or Expense.
- Organize accounts using accounting reporting groups.
- View, search, and filter accounts.
- Update supported account information.
- Deactivate accounts while retaining financial history.
- Display account statistics and operation feedback.

Account classifications support financial reporting
and the aggregation of ledger balances.

### 3. Double-Entry Transaction Processing

LedgerBase supports journal-based financial transaction
processing.

Features include:

- Create draft journal entries.
- Record multiple debit and credit lines.
- Validate that total debits equal total credits.
- Validate account eligibility and business ownership.
- Retrieve and review transaction details.
- Post balanced draft transactions.
- Display transaction lifecycle states.
- Prevent modification of protected posted records.
- Record audit information for supported operations.

Transactions follow a lifecycle:

```text
        CREATE JOURNAL ENTRY
                 |
                 v
               DRAFT
                 |
                 | Review and validate
                 v
           POST TRANSACTION
                 |
                 v
               POSTED
                 |
                 v
       INCLUDED IN FINANCIAL REPORTS
```

Posted financial records are protected by backend
accounting rules and database-level safeguards.

### 4. Business-Specific Journal References

LedgerBase assigns human-readable journal references
within each business.

Examples:

```text
Business A
  JE-0001
  JE-0002
  JE-0003

Business B
  JE-0001
  JE-0002
```

Each business maintains an independent journal sequence.

The system distinguishes between:

- Internal transaction IDs used by PostgreSQL and APIs.
- Business-specific references displayed to users.

Journal references are assigned by PostgreSQL when
transactions are created.

The React interface displays the references in the
transaction register, detail panels, and notifications.

### 5. Financial Reporting

LedgerBase generates financial reports from posted
accounting data.

Implemented reports include:

**Trial Balance**

- Displays account debit and credit balances.
- Aggregates posted general ledger entries.
- Verifies that total debits equal total credits.

**Income Statement**

- Aggregates revenue and expense accounts.
- Calculates total revenue and expenses.
- Calculates net income or loss.
- Supports reporting periods.

**Balance Sheet**

- Classifies assets, liabilities, and equity.
- Aggregates posted account balances.
- Includes unclosed earnings.
- Validates the accounting equation.

The fundamental accounting equation is:

```text
Assets = Liabilities + Equity
```

Financial reports are based on applicable posted
ledger entries rather than unposted drafts.

### 6. React Accounting Interface

The React and TypeScript frontend provides interfaces
for the implemented accounting workflows.

Features include:

- Business selection.
- Chart of Accounts management.
- Transaction register.
- Transaction search and status filters.
- Journal entry creation form.
- Debit and credit balance validation.
- Transaction detail review.
- Posting confirmation workflow.
- Financial reporting pages.
- Loading and error handling.
- Success notifications.

The interface uses actual backend and database data
rather than hardcoded financial balances.

### 7. Accounting Data Integrity

Financial integrity is a central design goal.

LedgerBase uses application-level validation and
PostgreSQL constraints to protect accounting records.

Implemented safeguards include:

- Double-entry balance validation.
- Transaction lifecycle validation.
- Business-scoped data relationships.
- Database constraints and foreign keys.
- Protected posted financial records.
- Transactional database operations.
- Audit records for supported financial operations.
- Business-specific journal number assignment.

These controls support the reliability of the
general ledger and financial reporting workflows.

---

## Accounting Demo

LedgerBase includes a documented accounting demonstration
using a fictional company called **ABC Consulting LLC**.

The demonstration covers eight journal entries across
October 2026.

| Reference | Accounting Scenario |
|---|---|
| JE-0001 | Initial owner capital contribution |
| JE-0002 | Office rent payment |
| JE-0003 | Consulting revenue earned on credit |
| JE-0004 | Partial customer payment |
| JE-0005 | Unpaid professional services expense |
| JE-0006 | Payment of outstanding vendor liability |
| JE-0007 | Customer advance and unearned revenue |
| JE-0008 | Office equipment purchase |

The walkthrough tests:

- Capital contributions.
- Operating expenses.
- Accrual-based revenue recognition.
- Accounts Receivable collection.
- Accounts Payable recognition and settlement.
- Unearned revenue.
- Capitalization of non-current assets.
- Financial statement reconciliation.

### Verified Demo Results

The completed local demonstration produced:

| Financial Metric | Result |
|---|---:|
| Posted Journal Entries | 8 |
| Trial Balance Debits | $36,000.00 |
| Trial Balance Credits | $36,000.00 |
| Total Revenue | $8,000.00 |
| Total Expenses | $3,200.00 |
| Net Income | $4,800.00 |
| Total Assets | $32,800.00 |
| Total Liabilities | $3,000.00 |
| Total Equity | $29,800.00 |
| Balance Sheet Difference | $0.00 |

All eight entries were verified as POSTED and balanced
in the local PostgreSQL development database.

**[View the Complete Accounting Demo](docs/accounting-demo.md)**

The guide describes how to reproduce the scenario manually
in a configured development environment.

It is not an automated database seed.

The demonstrated transactions currently exist in the
local development database and are not automatically
created when cloning the repository.

---

## Project Structure

```text
LedgerBase/
├── backend/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   └── resources/
│   │   │       ├── db/migration/
│   │   │       └── mapper/
│   │   └── test/
│   ├── mvnw
│   └── pom.xml
│
├── frontend/
│   ├── src/
│   │   ├── api/
│   │   ├── components/
│   │   ├── pages/
│   │   └── utils/
│   └── package.json
│
├── docs/
│   └── accounting-demo.md
│
├── docker-compose.yml
└── README.md
```

The backend contains Spring Boot controllers, services,
models, data-access components, and integration tests.

The frontend contains React components, pages,
API clients, and supporting utilities.

The docs directory contains project demonstrations
and supporting documentation.

---

## Getting Started

### Prerequisites

Install:

- Java 25
- Node.js and npm
- Docker Desktop with Docker Compose
- Git

The backend includes the Maven Wrapper, so a separate
global Maven installation is not required.

### 1. Clone the Repository

```bash
git clone https://github.com/angel-az41/LedgerBase.git
cd LedgerBase
```

### 2. Configure Environment Variables

Configure the local database environment variables
required by Docker Compose and Spring Boot.

LedgerBase uses a local `.env` file for development
configuration.

The exact values must match the PostgreSQL
credentials configured for the development environment.

Do not commit database passwords or other secrets
to GitHub.

### 3. Start PostgreSQL

From the LedgerBase project root:

```bash
docker compose up -d
```

The local development environment uses PostgreSQL
in Docker.

The configured host database port is:

```text
5434
```

Check the container:

```bash
docker compose ps
```

### 4. Start the Backend

From the LedgerBase project root:

```bash
set -a
source .env
set +a

cd backend
./mvnw spring-boot:run
```

Spring Boot runs at:

```text
http://localhost:8080
```

Flyway manages the database schema migrations
during application startup.

Keep this terminal running while using the application.

### 5. Start the Frontend

Open a separate terminal.

From the LedgerBase project root:

```bash
cd frontend
npm install
npm run dev
```

Open the application at:

```text
http://localhost:5173
```

During local development, Vite proxies API requests
to the Spring Boot backend on port 8080.

### 6. Explore LedgerBase

Once the services are running, use the application
to explore its accounting workflows.

Suggested sequence:

1. Select an available development business.
2. Review its Chart of Accounts.
3. Create a balanced draft journal entry.
4. Inspect the journal details.
5. Post the transaction.
6. Review the Trial Balance.
7. Review the Income Statement.
8. Review the Balance Sheet.

For a more complete accounting demonstration,
follow:

[Accounting Demo Guide](docs/accounting-demo.md)

A fresh environment may require initial development
business and account setup before these workflows
can be demonstrated.

---

## Testing

### Backend Tests

The backend includes automated tests for accounting
operations, database behavior, and REST APIs.

From the project root:

```bash
cd backend
./mvnw test
```

Integration tests require the configured PostgreSQL
test database environment.

The development and test databases should remain
separate to avoid modifying real development records
while running automated tests.

### Frontend Production Build

From the project root:

```bash
cd frontend
npm run build
```

This validates TypeScript compilation and generates
a Vite production build.

### Manual End-to-End Validation

The ABC Consulting LLC demonstration was used to
validate financial behavior across:

```text
React UI
    |
    v
Spring Boot REST APIs
    |
    v
MyBatis / PostgreSQL
    |
    v
Posted Ledger Entries
    |
    v
Financial Reports
```

The documented end-to-end checks include:

- Journal reference assignment.
- Draft creation and posting.
- Balanced ledger entries.
- Accounts Receivable collection.
- Accounts Payable settlement.
- Unearned revenue treatment.
- Fixed-asset capitalization.
- Trial Balance reconciliation.
- Income Statement calculations.
- Balance Sheet calculations.

See [Accounting Demo](docs/accounting-demo.md).

---

## Planned Enhancements

LedgerBase is being developed incrementally.

Potential next features include:

### Financial Data Import

- Import Chart of Accounts data from Excel or CSV.
- Import journal entries and financial records.
- Validate imported accounting data.
- Prevent duplicate imports.
- Support opening financial balances and migration
  from external accounting systems.

### Authentication and Authorization

- User authentication.
- Business-level access controls.
- Role-based permissions.
- Secure financial API access.

### Additional Accounting Workflows

- Customer invoice and payment reconciliation.
- Vendor bill management and payment allocation.
- Transaction reversal workflows.
- Fixed-asset depreciation.
- Additional financial reporting capabilities.

### Distributed Systems and Performance

Longer-term engineering extensions may include:

- Redis caching.
- Kafka-based asynchronous processing.
- Transactional outbox patterns.
- Additional concurrency testing.
- Container orchestration with Kubernetes.
- Monitoring and performance benchmarking.

These are planned enhancements, not claims of
currently implemented functionality.

---

## Development Status

LedgerBase is an actively developed full-stack
financial accounting application.

The current implemented foundation includes:

- PostgreSQL database schema and migrations.
- General ledger account management.
- Double-entry transaction processing.
- Draft and posted journal workflows.
- Business-specific journal references.
- Fiscal year and accounting period structures.
- Trial Balance reporting.
- Income Statement reporting.
- Balance Sheet reporting.
- React interfaces for accounting workflows.
- Backend integration testing.
- End-to-end accounting demonstration.

The ABC Consulting LLC scenario validates the
accounting behavior of eight posted journal entries
and their corresponding financial statements.

### Important Limitations

LedgerBase is a development and portfolio application,
not production-ready accounting software.

In particular:

- Production authentication is not implemented.
- Business-level authorization is not yet enforced
  as a complete security boundary.
- Development workflows currently use a temporary
  demo user identity.
- The accounting demo is documented but not
  automatically seeded into a fresh database.
- Additional production security, operational,
  and financial controls are required before
  handling real customer financial data.

The project prioritizes correctness, maintainability,
and a strong accounting and backend engineering
foundation before introducing more advanced
distributed infrastructure.
