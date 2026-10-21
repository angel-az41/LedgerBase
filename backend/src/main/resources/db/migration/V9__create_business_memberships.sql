-- ============================================================
-- LEDGERBASE — FLYWAY MIGRATION V9
-- CREATE BUSINESS MEMBERSHIPS
-- ============================================================

-- PURPOSE:
--
-- Introduce business-level role assignments to support
-- future authentication and role-based access control.
--
-- Existing tables:
--
--   users
--   businesses
--
-- New table:
--
--   business_memberships
--
-- A user can belong to multiple businesses.
-- A business can have multiple authorized users.
--
-- IMPORTANT:
--
-- This migration preserves the existing businesses.owner_id
-- column and does not modify financial transactions.
--
-- Existing business owners automatically receive OWNER
-- memberships during migration.


-- ============================================================
-- 1. CREATE BUSINESS MEMBERSHIPS TABLE
-- ============================================================

CREATE TABLE business_memberships (

    -- --------------------------------------------------------
    -- BUSINESS IDENTIFIER
    -- --------------------------------------------------------

    -- References the business to which access is granted.

    business_id BIGINT NOT NULL,


    -- --------------------------------------------------------
    -- USER IDENTIFIER
    -- --------------------------------------------------------

    -- References the user receiving business access.

    user_id BIGINT NOT NULL,


    -- --------------------------------------------------------
    -- BUSINESS ROLE
    -- --------------------------------------------------------

    -- OWNER:
    -- Full authority over the business.
    --
    -- ADMIN:
    -- Business administration and accounting management.
    --
    -- ACCOUNTANT:
    -- Accounting operations and financial reporting.
    --
    -- VIEWER:
    -- Read-only accounting access.

    role VARCHAR(20) NOT NULL,


    -- --------------------------------------------------------
    -- MEMBERSHIP CREATION TIMESTAMP
    -- --------------------------------------------------------

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),


    -- ========================================================
    -- PRIMARY KEY
    -- ========================================================

    -- A user can have only one membership per business.
    --
    -- The composite primary key also supports efficient
    -- lookups by business_id and user_id.

    CONSTRAINT pk_business_memberships
        PRIMARY KEY (business_id, user_id),


    -- ========================================================
    -- FOREIGN KEY — BUSINESS
    -- ========================================================

    -- Every membership must reference an existing business.
    --
    -- Deleting a business removes its memberships.
    -- Existing financial-record constraints may separately
    -- prevent business deletion.

    CONSTRAINT fk_business_memberships_business
        FOREIGN KEY (business_id)
        REFERENCES businesses(id)
        ON DELETE CASCADE,


    -- ========================================================
    -- FOREIGN KEY — USER
    -- ========================================================

    -- Every membership must reference an existing user.
    --
    -- RESTRICT prevents deleting a user while their
    -- membership records still exist.

    CONSTRAINT fk_business_memberships_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE RESTRICT,


    -- ========================================================
    -- ROLE VALIDATION
    -- ========================================================

    -- Only supported business roles are allowed.
    --
    -- New roles can be added through future migrations.

    CONSTRAINT chk_business_memberships_role
        CHECK (
            role IN (
                'OWNER',
                'ADMIN',
                'ACCOUNTANT',
                'VIEWER'
            )
        )
);


-- ============================================================
-- 2. INDEX FOR USER-BASED BUSINESS LOOKUPS
-- ============================================================

-- Supports queries such as:
--
-- "Which businesses can the authenticated user access?"
--
-- The primary key already supports business-first lookups.
-- This index optimizes user-first membership queries.

CREATE INDEX idx_business_memberships_user
    ON business_memberships (user_id, business_id);


-- ============================================================
-- 3. ENFORCE AT MOST ONE OWNER MEMBERSHIP PER BUSINESS
-- ============================================================

-- The existing businesses.owner_id remains the canonical
-- owner identifier.
--
-- This partial unique index prevents multiple OWNER
-- membership rows for the same business.
--
-- Future ownership transfer logic must keep the membership
-- role consistent with businesses.owner_id.
--
-- This index alone does not guarantee that every business
-- always has an OWNER membership.

CREATE UNIQUE INDEX uq_business_memberships_single_owner
    ON business_memberships (business_id)
    WHERE role = 'OWNER';


-- ============================================================
-- 4. MIGRATE EXISTING BUSINESS OWNERS
-- ============================================================

-- Create an OWNER membership for each existing business.
--
-- Example:
--
-- business_id = 1, user_id = 1, role = OWNER
-- business_id = 2, user_id = 1, role = OWNER
--
-- This is a new table, so no existing membership records
-- need to be modified or replaced.
--
-- The migration does not change the users, businesses,
-- transactions, or ledger_entries tables.

INSERT INTO business_memberships (
    business_id,
    user_id,
    role
)
SELECT
    b.id,
    b.owner_id,
    'OWNER'
FROM businesses AS b;


-- ============================================================
-- END OF FLYWAY MIGRATION V9
-- ============================================================
