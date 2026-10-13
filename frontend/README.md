# LedgerBase Frontend

The frontend for LedgerBase, a full-stack financial ledger and accounting platform.

Built with React, TypeScript, and Vite, the application provides a professional interface for managing general ledger accounts and, as development continues, financial transactions and accounting reports.

## Tech Stack

- React
- TypeScript
- Vite
- ESLint
- CSS

## Features

### Chart of Accounts

- Display accounts retrieved from PostgreSQL through the Spring Boot API.
- Create general ledger accounts.
- Rename active accounts.
- Deactivate accounts while preserving their history.
- Search accounts by code, name, or type.
- Filter accounts by active or inactive status.
- Display account statistics, loading states, and error feedback.

### Dashboard

- Sidebar navigation between application sections.
- Overview dashboard and quick-access links.
- Financial metric placeholders until reporting APIs are implemented.

### Planned

- Transaction listing and journal entry creation.
- Draft transaction review and posting.
- Trial Balance, Balance Sheet, and Income Statement.
- Authentication and business selection.

## Project Structure

```text
src/
├── api/
│   ├── client.ts
│   └── accounts.ts
│
├── components/
│   └── accounts/
│       ├── AccountActions.tsx
│       ├── AccountActions.css
│       ├── NewAccountForm.tsx
│       └── NewAccountForm.css
│
├── pages/
│   ├── AccountsPage.tsx
│   └── AccountsPage.css
│
├── App.tsx
├── App.css
├── index.css
└── main.tsx
```

## Local Development

### Prerequisites

- Node.js and npm
- LedgerBase Spring Boot backend
- PostgreSQL database

### Install Dependencies

From the `frontend/` directory:

```bash
npm install
```

### Start the Development Server

```bash
npm run dev
```

Open:

http://localhost:5173/

### Production Build

```bash
npm run build
```

### Linting

```bash
npm run lint
```

## Backend Integration

The frontend communicates with the Java Spring Boot backend through REST APIs.

During local development, Vite forwards requests beginning with `/api` to:

http://localhost:8080

Example account request:

```http
GET /api/v1/businesses/1/accounts
```

The HTTP client is implemented in `src/api/client.ts`.

Account-specific API operations and TypeScript interfaces are defined in `src/api/accounts.ts`.

The frontend does not connect directly to PostgreSQL. All database access occurs through the Spring Boot backend.

## React Architecture

LedgerBase uses functional React components and TypeScript.

The application uses:

- `useState` for component state.
- `useEffect` for loading account data.
- `useMemo` for filtering and sorting accounts.
- Props and callbacks for communication between components.
- Controlled inputs for account forms.

Accounting business rules and database validation remain the responsibility of the backend.

## Development Notes

The application currently uses business ID `1` for local development.

Authentication and business-level authorization have not yet been implemented. The application should not be publicly deployed without those safeguards.

The frontend follows LedgerBase's design principles: professional accounting workflows, clear data tables, restrained navy and teal styling, accessible forms, and informative user feedback.

## Related Documentation

See the repository's root `README.md` for the full-stack architecture, backend setup, PostgreSQL configuration, and overall development status.
