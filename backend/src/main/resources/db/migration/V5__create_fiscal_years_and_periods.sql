-- ============================================================
-- LEDGERBASE — DATABASE MIGRATION V5
-- Fiscal Years and Accounting Periods
-- ============================================================
--
-- Goals:
-- 1. Configure each business's fiscal-year starting month.
-- 2. Store fiscal years with non-overlapping date ranges.
-- 3. Store 12 monthly accounting periods per fiscal year.
-- 4. Initialize existing businesses without modifying
--    their transactions or ledger entries.
--
-- Posting restrictions will be introduced separately,
-- after the backend supports period management.
-- ============================================================


-- ============================================================
-- 1. FISCAL-YEAR CONFIGURATION
-- ============================================================

-- January (1) is the default.
-- Businesses may select another starting month (1–12).

ALTER TABLE businesses
    ADD COLUMN fiscal_year_start_month SMALLINT
        NOT NULL DEFAULT 1,

    ADD CONSTRAINT chk_business_fiscal_start_month
        CHECK (fiscal_year_start_month BETWEEN 1 AND 12);


-- ============================================================
-- 2. FISCAL YEARS
-- ============================================================

-- PostgreSQL's btree_gist extension allows us to combine
-- business IDs and date ranges in exclusion constraints.

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE fiscal_years (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    business_id BIGINT NOT NULL
        REFERENCES businesses(id) ON DELETE RESTRICT,

    -- Examples: FY2026, FY2027.
    -- The label uses the year in which the fiscal year ends.
    label VARCHAR(20) NOT NULL,

    start_date DATE NOT NULL,

    end_date DATE NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Supports business-scoped foreign keys.
    CONSTRAINT uq_fiscal_year_business_id
        UNIQUE (business_id, id),

    -- One label per business.
    CONSTRAINT uq_fiscal_year_business_label
        UNIQUE (business_id, label),

    -- Fiscal years start on the first day of a month.
    CONSTRAINT chk_fiscal_year_start_day
        CHECK (EXTRACT(DAY FROM start_date) = 1),

    -- Each fiscal year contains exactly 12 calendar months.
    CONSTRAINT chk_fiscal_year_duration
        CHECK (
            end_date = (
                start_date + INTERVAL '12 months'
                - INTERVAL '1 day'
            )::DATE
        ),

    -- A business cannot have overlapping fiscal years.
    CONSTRAINT ex_fiscal_year_no_overlap
        EXCLUDE USING GIST (
            business_id WITH =,
            daterange(start_date, end_date, '[]') WITH &&
        )
);


-- ============================================================
-- 3. ACCOUNTING PERIODS
-- ============================================================

-- Each fiscal year contains 12 monthly periods.
-- OPEN periods accept new postings.
-- CLOSED periods will reject new postings once the
-- posting-validation workflow is implemented.

CREATE TABLE accounting_periods (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    business_id BIGINT NOT NULL,

    fiscal_year_id BIGINT NOT NULL,

    period_number SMALLINT NOT NULL,

    start_date DATE NOT NULL,

    end_date DATE NOT NULL,

    status VARCHAR(10) NOT NULL DEFAULT 'OPEN',

    closed_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- A period must belong to a fiscal year
    -- owned by the same business.
    CONSTRAINT fk_period_fiscal_year
        FOREIGN KEY (business_id, fiscal_year_id)
        REFERENCES fiscal_years(business_id, id)
        ON DELETE RESTRICT,

    -- Each fiscal year can have only one Period 1,
    -- one Period 2, and so on.
    CONSTRAINT uq_period_fiscal_year_number
        UNIQUE (fiscal_year_id, period_number),

    CONSTRAINT chk_period_number
        CHECK (period_number BETWEEN 1 AND 12),

    CONSTRAINT chk_period_status
        CHECK (status IN ('OPEN', 'CLOSED')),

    CONSTRAINT chk_period_start_day
        CHECK (EXTRACT(DAY FROM start_date) = 1),

    -- Each accounting period covers one calendar month.
    CONSTRAINT chk_period_duration
        CHECK (
            end_date = (
                start_date + INTERVAL '1 month'
                - INTERVAL '1 day'
            )::DATE
        ),

    -- Prevent overlapping periods within a business.
    CONSTRAINT ex_period_no_overlap
        EXCLUDE USING GIST (
            business_id WITH =,
            daterange(start_date, end_date, '[]') WITH &&
        )
);


-- ============================================================
-- 4. INDEXES
-- ============================================================

-- Find fiscal years belonging to a business.
CREATE INDEX idx_fiscal_years_business_start
    ON fiscal_years(business_id, start_date DESC);

-- Find the accounting period containing a transaction date.
CREATE INDEX idx_accounting_periods_business_dates
    ON accounting_periods(business_id, start_date, end_date);


-- ============================================================
-- 5. INITIALIZE EXISTING FISCAL YEARS
-- ============================================================

-- Existing businesses start with January–December years.
--
-- Create a fiscal year for:
-- 1. The current calendar year of every existing business.
-- 2. Every additional year containing existing transactions.
--
-- UNION removes duplicate business/year combinations.
-- Existing transactions are not changed.

WITH required_years AS (
    SELECT
        b.id AS business_id,
        EXTRACT(YEAR FROM CURRENT_DATE)::INT AS year_number
    FROM businesses b

    UNION

    SELECT
        t.business_id,
        EXTRACT(YEAR FROM t.transaction_date)::INT
    FROM transactions t
)

INSERT INTO fiscal_years (
    business_id,
    label,
    start_date,
    end_date
)

SELECT
    business_id,
    'FY' || year_number,
    MAKE_DATE(year_number, 1, 1),
    MAKE_DATE(year_number, 12, 31)

FROM required_years;


-- ============================================================
-- 6. INITIALIZE EXISTING ACCOUNTING PERIODS
-- ============================================================

-- Generate all 12 monthly periods for each fiscal year.
--
-- generate_series(1, 12) produces period numbers 1–12.
--
-- All migrated periods start OPEN so historical data
-- is not accidentally locked during initialization.

INSERT INTO accounting_periods (
    business_id,
    fiscal_year_id,
    period_number,
    start_date,
    end_date,
    status
)

SELECT
    fy.business_id,
    fy.id,
    p.period_number,

    (
        fy.start_date
        + (p.period_number - 1) * INTERVAL '1 month'
    )::DATE,

    (
        fy.start_date
        + p.period_number * INTERVAL '1 month'
        - INTERVAL '1 day'
    )::DATE,

    'OPEN'

FROM fiscal_years fy

CROSS JOIN generate_series(1, 12)
    AS p(period_number);


-- ============================================================
-- END OF MIGRATION V5
-- ============================================================
