-- ============================================================
-- LEDGERBASE — V8 BUSINESS-SPECIFIC JOURNAL NUMBERING
-- ============================================================

-- PURPOSE:
--
-- Give each business its own independent sequence of
-- journal numbers while preserving globally unique
-- transactions.id primary keys.
--
-- EXAMPLE:
--
-- Business 1, Transaction ID 1 -> Journal Number 1
-- Business 2, Transaction ID 2 -> Journal Number 1
-- Business 2, Transaction ID 3 -> Journal Number 2
-- Business 1, Transaction ID 4 -> Journal Number 2
--
-- The application can display these as:
--
-- JE-0001
-- JE-0002
--
-- IMPORTANT:
--
-- This migration does NOT change:
--
-- - Existing transaction IDs
-- - Existing ledger entries
-- - Transaction statuses
-- - Posted transaction headers
-- - Accounting-period protection triggers
--
-- Historical journal numbers are stored separately
-- to preserve immutable posted financial records.
--
-- Flyway normally executes PostgreSQL SQL migrations
-- inside a database transaction.


-- ============================================================
-- 1. LOCK TRANSACTIONS DURING MIGRATION
-- ============================================================

-- Block concurrent transaction INSERT/UPDATE/DELETE
-- operations while historical numbering is initialized.
--
-- Ordinary SELECT queries can continue.
--
-- The lock is released when Flyway's migration
-- transaction commits or rolls back.

LOCK TABLE transactions IN SHARE ROW EXCLUSIVE MODE;


-- ============================================================
-- 2. CREATE PER-BUSINESS JOURNAL COUNTERS
-- ============================================================

-- One counter row exists for each business that has
-- created at least one journal transaction.
--
-- last_journal_number stores the highest number
-- allocated for that business.
--
-- Separate businesses maintain independent counters.
--
-- The PRIMARY KEY ensures one counter per business.
--
-- The foreign key ensures every counter references
-- a real business.

CREATE TABLE business_journal_counters (

    business_id BIGINT PRIMARY KEY,

    last_journal_number BIGINT NOT NULL,

    CONSTRAINT fk_journal_counter_business
        FOREIGN KEY (business_id)
        REFERENCES businesses(id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_last_journal_number_positive
        CHECK (last_journal_number > 0)
);


-- ============================================================
-- 3. CREATE TRANSACTION JOURNAL NUMBER MAPPING
-- ============================================================

-- Associates each transaction with its journal number.
--
-- business_id:
--   Identifies which company's numbering is used.
--
-- transaction_id:
--   References the existing global transactions.id.
--
-- journal_number:
--   The number shown within that company.
--
-- PRIMARY KEY (business_id, transaction_id):
--   One journal number per transaction.
--
-- UNIQUE (business_id, journal_number):
--   Prevents duplicate numbers within a company.
--
-- The same journal number is allowed in different
-- companies.
--
-- ON DELETE CASCADE:
--   Preserves existing deletion behavior for deletable
--   draft transactions.
--
--   Existing database triggers still protect POSTED
--   transaction headers from deletion.

CREATE TABLE transaction_journal_numbers (

    business_id BIGINT NOT NULL,

    transaction_id BIGINT NOT NULL,

    journal_number BIGINT NOT NULL,

    CONSTRAINT pk_transaction_journal_numbers
        PRIMARY KEY (business_id, transaction_id),

    CONSTRAINT uq_business_journal_number
        UNIQUE (business_id, journal_number),

    CONSTRAINT chk_journal_number_positive
        CHECK (journal_number > 0),

    CONSTRAINT fk_journal_number_transaction
        FOREIGN KEY (business_id, transaction_id)
        REFERENCES transactions(business_id, id)
        ON DELETE CASCADE
);


-- ============================================================
-- 4. NUMBER EXISTING TRANSACTIONS
-- ============================================================

-- Assign historical journal numbers in ascending
-- internal transaction ID order.
--
-- ROW_NUMBER() restarts at 1 for every business
-- because of PARTITION BY business_id.
--
-- Example:
--
-- id | business_id | journal_number
-- ---+-------------+---------------
-- 1  |      1      |      1
-- 2  |      2      |      1
--
-- This includes DRAFT and POSTED transactions.
--
-- We insert into the mapping table instead of
-- updating the protected transactions table.
--
-- Existing ledger entries remain unchanged.

INSERT INTO transaction_journal_numbers (
    business_id,
    transaction_id,
    journal_number
)
SELECT
    business_id,
    id AS transaction_id,

    ROW_NUMBER() OVER (
        PARTITION BY business_id
        ORDER BY id ASC
    ) AS journal_number

FROM transactions;


-- ============================================================
-- 5. INITIALIZE BUSINESS COUNTERS
-- ============================================================

-- Set each business counter to its highest assigned
-- historical journal number.
--
-- This ensures the next transaction continues with
-- the correct value rather than restarting at 1.
--
-- Businesses without transactions need no row yet.
-- Their first transaction will initialize its counter.

INSERT INTO business_journal_counters (
    business_id,
    last_journal_number
)
SELECT
    business_id,
    MAX(journal_number)
FROM transaction_journal_numbers
GROUP BY business_id;


-- ============================================================
-- 6. DEFINE AUTOMATIC NUMBER ALLOCATION FUNCTION
-- ============================================================

-- This function runs after a new transaction header
-- is inserted.
--
-- It allocates the next journal number for the
-- transaction's own business.
--
-- CONCURRENCY SAFETY:
--
-- INSERT ... ON CONFLICT DO UPDATE locks the
-- conflicting counter row in PostgreSQL.
--
-- Concurrent transactions for the same business
-- cannot successfully allocate the same number.
--
-- Different businesses use separate counter rows.
--
-- The counter update and journal mapping insert
-- participate in the original transaction.
--
-- If that transaction rolls back, the counter
-- update and mapping insert also roll back.

CREATE FUNCTION assign_transaction_journal_number()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE

    allocated_number BIGINT;

BEGIN

    -- ========================================================
    -- ALLOCATE THE NEXT NUMBER FOR THIS BUSINESS
    -- ========================================================

    -- First transaction for a business:
    --
    --   INSERT counter = 1
    --
    -- Existing counter:
    --
    --   UPDATE counter = previous value + 1
    --
    -- RETURNING retrieves the newly allocated number.

    INSERT INTO business_journal_counters (
        business_id,
        last_journal_number
    )
    VALUES (
        NEW.business_id,
        1
    )

    ON CONFLICT (business_id)
    DO UPDATE SET

        last_journal_number =
            business_journal_counters.last_journal_number + 1

    RETURNING last_journal_number
    INTO allocated_number;


    -- ========================================================
    -- STORE THE TRANSACTION'S JOURNAL NUMBER
    -- ========================================================

    -- The unique constraints enforce:
    --
    -- 1. One mapping per transaction.
    -- 2. No duplicate journal number within a business.

    INSERT INTO transaction_journal_numbers (
        business_id,
        transaction_id,
        journal_number
    )
    VALUES (
        NEW.business_id,
        NEW.id,
        allocated_number
    );


    -- ========================================================
    -- COMPLETE THE ORIGINAL TRANSACTION INSERT
    -- ========================================================

    -- AFTER triggers must return a value, but
    -- PostgreSQL ignores it for row-level AFTER
    -- trigger processing.

    RETURN NEW;

END;
$$;


-- ============================================================
-- 7. CREATE AUTOMATIC NUMBERING TRIGGER
-- ============================================================

-- Run after every new transaction header is inserted.
--
-- This allows existing Spring Boot/MyBatis INSERT
-- operations to keep working without first changing
-- their SQL.
--
-- The function creates the corresponding journal
-- number record automatically.
--
-- This trigger does not update existing transaction
-- headers or interfere with posted-entry protection.

CREATE TRIGGER trg_assign_transaction_journal_number

AFTER INSERT ON transactions

FOR EACH ROW

EXECUTE FUNCTION assign_transaction_journal_number();


-- ============================================================
-- END — V8 BUSINESS-SPECIFIC JOURNAL NUMBERING
-- ============================================================
