
-- =====================================================
-- LedgerBase - Database Migration V2
-- Financial Tables
-- =====================================================
--
-- Creates:
-- 1. categories
-- 2. customers
-- 3. vendors
-- 4. transactions
-- 5. ledger_entries
--
-- All financial records belong to a business.
-- Posted transactions will be protected by additional
-- posting and immutability rules in a later migration.
-- =====================================================


-- =====================================================
-- 1. CATEGORIES
-- =====================================================
-- Categories classify transactions for reporting.
-- Examples: Office Rent, Software, Consulting Revenue.

CREATE TABLE categories (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    business_id BIGINT NOT NULL
        REFERENCES businesses(id) ON DELETE RESTRICT,

    name VARCHAR(100) NOT NULL,

    category_type VARCHAR(20) NOT NULL
        CHECK (category_type IN ('INCOME', 'EXPENSE')),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Prevent duplicate category names within a business.
    CONSTRAINT uq_category_business_name
        UNIQUE (business_id, name),

    -- Supports business-scoped foreign keys.
    CONSTRAINT uq_category_business_id
        UNIQUE (business_id, id),

    CONSTRAINT chk_category_name
        CHECK (LENGTH(TRIM(name)) > 0)
);


-- =====================================================
-- 2. CUSTOMERS
-- =====================================================
-- Stores clients who purchase goods or services.
-- Customer information is stored separately to avoid
-- repeating details in every transaction.

CREATE TABLE customers (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    business_id BIGINT NOT NULL
        REFERENCES businesses(id) ON DELETE RESTRICT,

    name VARCHAR(150) NOT NULL,

    email VARCHAR(255),

    phone VARCHAR(30),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_customer_business_id
        UNIQUE (business_id, id),

    CONSTRAINT chk_customer_name
        CHECK (LENGTH(TRIM(name)) > 0)
);


-- =====================================================
-- 3. VENDORS
-- =====================================================
-- Stores suppliers paid by the business.

CREATE TABLE vendors (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    business_id BIGINT NOT NULL
        REFERENCES businesses(id) ON DELETE RESTRICT,

    name VARCHAR(150) NOT NULL,

    email VARCHAR(255),

    phone VARCHAR(30),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_vendor_business_id
        UNIQUE (business_id, id),

    CONSTRAINT chk_vendor_name
        CHECK (LENGTH(TRIM(name)) > 0)
);


-- =====================================================
-- 4. TRANSACTIONS
-- =====================================================
-- Stores one financial event per row.
--
-- DRAFT:    Can still be edited or deleted.
-- POSTED:   Has valid double-entry ledger records.
-- REVERSED: Corrected through a separate reversal.
--
-- Posting, reversal, and concurrency safety will be
-- implemented as a controlled service workflow.

CREATE TABLE transactions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    business_id BIGINT NOT NULL
        REFERENCES businesses(id) ON DELETE RESTRICT,

    created_by BIGINT NOT NULL
        REFERENCES users(id) ON DELETE RESTRICT,

    category_id BIGINT,

    customer_id BIGINT,

    vendor_id BIGINT,

    description TEXT NOT NULL,

    transaction_date DATE NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'POSTED', 'REVERSED')),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    posted_at TIMESTAMPTZ,

    -- Allow at most one counterparty type per transaction.
    CONSTRAINT chk_transaction_counterparty
        CHECK (customer_id IS NULL OR vendor_id IS NULL),

    CONSTRAINT chk_transaction_description
        CHECK (LENGTH(TRIM(description)) > 0),

    -- This composite key supports tenant-safe references.
    CONSTRAINT uq_transaction_business_id
        UNIQUE (business_id, id),

    -- All related records must belong to the same business.
    CONSTRAINT fk_transaction_category
        FOREIGN KEY (business_id, category_id)
        REFERENCES categories(business_id, id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_transaction_customer
        FOREIGN KEY (business_id, customer_id)
        REFERENCES customers(business_id, id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_transaction_vendor
        FOREIGN KEY (business_id, vendor_id)
        REFERENCES vendors(business_id, id)
        ON DELETE RESTRICT
);


-- =====================================================
-- 5. LEDGER ENTRIES
-- =====================================================
-- Each transaction can have multiple debit/credit entries.
--
-- Examples:
--   Debit Cash        1000.00
--   Credit Revenue    1000.00
--
-- Each entry must have exactly one positive side.
-- Transaction-level balancing is enforced at posting.

CREATE TABLE ledger_entries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    business_id BIGINT NOT NULL,

    transaction_id BIGINT NOT NULL,

    account_id BIGINT NOT NULL,

    debit NUMERIC(18, 2) NOT NULL DEFAULT 0,

    credit NUMERIC(18, 2) NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Exactly one side must contain a positive amount.
    CONSTRAINT chk_ledger_entry_amount
        CHECK (
            (debit > 0 AND credit = 0)
            OR
            (credit > 0 AND debit = 0)
        ),

    -- Ledger entry and transaction belong to same business.
    CONSTRAINT fk_entry_transaction
        FOREIGN KEY (business_id, transaction_id)
        REFERENCES transactions(business_id, id)
        ON DELETE RESTRICT,

    -- Ensures the ledger entry and account belong
    -- to the same business.
    CONSTRAINT fk_entry_account
        FOREIGN KEY (business_id, account_id)
        REFERENCES accounts(business_id, id)
        ON DELETE RESTRICT
);


-- =====================================================
-- 6. INDEXES
-- =====================================================
-- Speed up common business dashboard and lookup queries.

-- Fetch a business's recent transactions efficiently.
CREATE INDEX idx_transactions_business_date
ON transactions(business_id, transaction_date DESC, id DESC);

-- Retrieve all entries for a transaction.
CREATE INDEX idx_ledger_entries_transaction
ON ledger_entries(transaction_id);

-- Calculate account activity and balances.
CREATE INDEX idx_ledger_entries_account
ON ledger_entries(account_id);

-- Find customers belonging to a business.
CREATE INDEX idx_customers_business
ON customers(business_id);

-- Find vendors belonging to a business.
CREATE INDEX idx_vendors_business
ON vendors(business_id);


-- =====================================================
-- End of Migration V2
-- =====================================================
