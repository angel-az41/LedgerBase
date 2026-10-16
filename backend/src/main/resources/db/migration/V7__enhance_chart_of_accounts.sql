-- ============================================================
-- LEDGERBASE — DATABASE MIGRATION V7 (DRAFT)
-- ENHANCED CHART OF ACCOUNTS
-- ============================================================
--
-- PURPOSE:
--
-- Extend the existing Chart of Accounts to support:
--
-- 1. Financial statement reporting classifications.
-- 2. Optional parent-child account hierarchies.
-- 3. Postable and non-postable account distinctions.
--
-- IMPORTANT:
--
-- This is the SCHEMA portion of V7.
--
-- We will add database trigger functions in the
-- next step before moving this file into Flyway's
-- migration directory.
--
-- DO NOT EXECUTE THIS DRAFT.
--
-- Existing account IDs, journal entries, and
-- transaction history must remain unchanged.
-- ============================================================


-- ============================================================
-- STEP 1 — ADD NEW ACCOUNT COLUMNS
-- ============================================================

ALTER TABLE accounts

    -- Financial statement classification.
    --
    -- Examples:
    -- CURRENT_ASSET
    -- NON_CURRENT_ASSET
    -- OPERATING_REVENUE
    --
    -- NULL is permitted during migration because we must
    -- not guess classifications for existing accounts.
    ADD COLUMN reporting_group VARCHAR(40),

    -- Optional parent account used to organize the
    -- Chart of Accounts into a hierarchy.
    --
    -- NULL means this account has no parent.
    ADD COLUMN parent_account_id BIGINT,

    -- Whether the account may accept ledger entries.
    --
    -- Existing accounts remain postable by default.
    --
    -- Grouping accounts will use FALSE.
    ADD COLUMN is_postable BOOLEAN NOT NULL DEFAULT TRUE;


-- ============================================================
-- STEP 2 — VALIDATE REPORTING GROUP CLASSIFICATIONS
-- ============================================================

/*
 * Reporting groups must be compatible with the
 * account's fundamental accounting type.
 *
 * For example:
 *
 * ASSET -> CURRENT_ASSET
 *
 * EXPENSE -> OPERATING_EXPENSE
 *
 * A revenue account must not be assigned to
 * CURRENT_ASSET.
 *
 * NULL remains valid for accounts that have not
 * yet been classified.
 */

ALTER TABLE accounts
ADD CONSTRAINT chk_account_reporting_group
CHECK (

    reporting_group IS NULL

    OR

    (
        account_type = 'ASSET'
        AND reporting_group IN (
            'CURRENT_ASSET',
            'NON_CURRENT_ASSET'
        )
    )

    OR

    (
        account_type = 'LIABILITY'
        AND reporting_group IN (
            'CURRENT_LIABILITY',
            'NON_CURRENT_LIABILITY'
        )
    )

    OR

    (
        account_type = 'EQUITY'
        AND reporting_group IN (
            'CONTRIBUTED_CAPITAL',
            'RETAINED_EARNINGS',
            'OTHER_EQUITY'
        )
    )

    OR

    (
        account_type = 'REVENUE'
        AND reporting_group IN (
            'OPERATING_REVENUE',
            'OTHER_INCOME'
        )
    )

    OR

    (
        account_type = 'EXPENSE'
        AND reporting_group IN (
            'COST_OF_GOODS_SOLD',
            'OPERATING_EXPENSE',
            'OTHER_EXPENSE'
        )
    )

);


-- ============================================================
-- STEP 3 — PREVENT SELF-PARENTING
-- ============================================================

/*
 * An account cannot be its own parent.
 *
 * Example:
 *
 * Cash -> Cash
 *
 * This direct relationship is invalid.
 *
 * Longer circular relationships will be prevented
 * by a trigger added in the next step.
 */

ALTER TABLE accounts
ADD CONSTRAINT chk_account_not_own_parent
CHECK (
    parent_account_id IS NULL
    OR parent_account_id <> id
);


-- ============================================================
-- STEP 4 — ENFORCE SAME-BUSINESS PARENT REFERENCES
-- ============================================================

/*
 * Every parent account must belong to the
 * same business as its child.
 *
 * Example:
 *
 * Business 1:
 *   Cash -> Current Assets (Business 1)
 *
 * This is permitted.
 *
 * Business 1:
 *   Cash -> Current Assets (Business 2)
 *
 * This must be rejected.
 *
 * IMPORTANT:
 *
 * Our existing schema already provides:
 *
 * UNIQUE (business_id, id)
 *
 * through uq_account_business_id.
 *
 * We verified that constraint in PostgreSQL.
 *
 * The composite foreign key uses it to enforce
 * business isolation directly in the database.
 *
 * A NULL parent_account_id is permitted because
 * top-level accounts have no parent.
 */

ALTER TABLE accounts
ADD CONSTRAINT fk_account_parent_same_business
FOREIGN KEY (
    business_id,
    parent_account_id
)
REFERENCES accounts (
    business_id,
    id
)
ON DELETE RESTRICT;


-- ============================================================
-- STEP 5 — INDEX PARENT-CHILD ACCOUNT LOOKUPS
-- ============================================================

/*
 * This index supports queries such as:
 *
 * Find all children belonging to a specific
 * parent within one business.
 *
 * It will also help our account hierarchy
 * validation and tree-based React interface.
 *
 * Only accounts with a parent are indexed.
 */

CREATE INDEX idx_accounts_parent_by_business
ON accounts (
    business_id,
    parent_account_id
)
WHERE parent_account_id IS NOT NULL;



-- ============================================================
-- STEP 6 — VALIDATE ACCOUNT HIERARCHY AND POSTABILITY
-- ============================================================

/*
 * Enforce account hierarchy and posting eligibility
 * directly in PostgreSQL.
 *
 * ACCOUNT HIERARCHY RULES:
 *
 * 1. Parent accounts must belong to the same business.
 *    The composite foreign key already protects this.
 *
 * 2. Parent accounts must be non-postable.
 *
 * 3. Parent and child must have the same account type.
 *
 * 4. Circular account relationships are prohibited.
 *
 * 5. An account with children cannot become postable.
 *
 * POSTING ELIGIBILITY RULES:
 *
 * 6. An account with existing ledger entries cannot
 *    become non-postable.
 *
 * 7. Account identity, business ownership, and
 *    fundamental account type cannot be changed.
 *
 * LOCKING:
 *
 * Updating an account locks that account's row.
 *
 * Ancestor rows are read FOR SHARE so concurrent
 * hierarchy modifications cannot bypass validation.
 *
 * The separate ledger-entry trigger also locks
 * referenced accounts FOR SHARE.
 *
 * These locks coordinate account conversions
 * with concurrent ledger-entry operations.
 */

CREATE FUNCTION validate_account_hierarchy_and_postability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE

    -- Current ancestor being examined.
    current_parent_id BIGINT;

    -- The next ancestor in the hierarchy.
    next_parent_id BIGINT;

    -- Account type of the current ancestor.
    ancestor_account_type VARCHAR(20);

    -- Posting eligibility of the current ancestor.
    ancestor_is_postable BOOLEAN;

    -- Track visited accounts to detect circular paths.
    visited_account_ids BIGINT[];

BEGIN

    -- ========================================================
    -- RULE 1 — PRESERVE EXISTING ACCOUNT IDENTITY
    -- ========================================================

    /*
     * Account IDs and business ownership are immutable.
     *
     * An existing account must not be transferred
     * to another business or assigned a different ID.
     *
     * These changes could invalidate accounting
     * history and business-scoped relationships.
     */
    IF TG_OP = 'UPDATE' THEN

        IF NEW.id IS DISTINCT FROM OLD.id
           OR NEW.business_id IS DISTINCT FROM OLD.business_id THEN

            RAISE EXCEPTION
                'Cannot change account identity or business ownership'
                USING ERRCODE = '23514';

        END IF;


        -- ====================================================
        -- RULE 2 — PRESERVE FUNDAMENTAL ACCOUNT TYPE
        -- ====================================================

        /*
         * ASSET, LIABILITY, EQUITY, REVENUE, and EXPENSE
         * determine the accounting meaning of an account.
         *
         * Changing the type of an existing account can
         * reinterpret historical journal activity.
         *
         * LedgerBase therefore requires creating a
         * different account rather than changing its type.
         */
        IF NEW.account_type IS DISTINCT FROM OLD.account_type THEN

            RAISE EXCEPTION
                'Cannot change the fundamental account type'
                USING ERRCODE = '23514';

        END IF;

    END IF;


    -- ========================================================
    -- RULE 3 — NON-POSTABLE ACCOUNTS MUST HAVE NO ENTRIES
    -- ========================================================

    /*
     * A grouping account must never contain ledger entries.
     *
     * Check all existing ledger entries, including those
     * belonging to DRAFT transactions.
     *
     * This prevents existing accounting references from
     * becoming invalid when an account changes its role.
     *
     * The account row is already locked by the UPDATE.
     * Ledger-entry writes acquire a conflicting FOR SHARE
     * lock through the second V7 trigger.
     */
    IF NEW.is_postable = FALSE THEN

        IF EXISTS (

            SELECT 1
            FROM ledger_entries le
            WHERE le.business_id = NEW.business_id
              AND le.account_id = NEW.id

        ) THEN

            RAISE EXCEPTION
                'Account % has ledger entries and cannot become non-postable',
                NEW.id
                USING ERRCODE = '23514';

        END IF;

    END IF;


    -- ========================================================
    -- RULE 4 — ACCOUNTS WITH CHILDREN CANNOT BE POSTABLE
    -- ========================================================

    /*
     * If other accounts are organized beneath this
     * account, it acts as a grouping account.
     *
     * Grouping accounts cannot directly receive
     * journal entries.
     *
     * A child-creation operation locks its parent
     * FOR SHARE while validating the hierarchy.
     */
    IF NEW.is_postable = TRUE THEN

        IF EXISTS (

            SELECT 1
            FROM accounts child
            WHERE child.business_id = NEW.business_id
              AND child.parent_account_id = NEW.id

        ) THEN

            RAISE EXCEPTION
                'Account % has child accounts and cannot be postable',
                NEW.id
                USING ERRCODE = '23514';

        END IF;

    END IF;


    -- ========================================================
    -- RULE 5 — VALIDATE OPTIONAL PARENT HIERARCHY
    -- ========================================================

    /*
     * Accounts without a parent are valid top-level
     * accounts.
     *
     * Accounts with a parent must have a valid
     * chain of non-postable, same-type ancestors.
     *
     * We walk upward through the hierarchy.
     *
     * The walk also detects circular relationships.
     */
    IF NEW.parent_account_id IS NOT NULL THEN

        -- Begin at the proposed parent.
        current_parent_id := NEW.parent_account_id;

        -- Include the account being created or modified.
        visited_account_ids := ARRAY[NEW.id]::BIGINT[];

        -- ====================================================
        -- WALK THROUGH ALL ANCESTORS
        -- ====================================================

        WHILE current_parent_id IS NOT NULL LOOP

            -- ================================================
            -- DETECT CIRCULAR HIERARCHY
            -- ================================================

            /*
             * If we reach an account already visited,
             * the proposed hierarchy contains a cycle.
             *
             * Examples:
             *
             * A -> B -> A
             *
             * A -> B -> C -> A
             */
            IF current_parent_id = ANY(visited_account_ids) THEN

                RAISE EXCEPTION
                    'Circular account hierarchy detected for account %',
                    NEW.id
                    USING ERRCODE = '23514';

            END IF;


            -- ================================================
            -- LOAD AND LOCK THE CURRENT ANCESTOR
            -- ================================================

            /*
             * FOR SHARE prevents concurrent updates
             * from changing an ancestor while we
             * validate the hierarchy.
             *
             * PostgreSQL holds this lock until
             * the surrounding transaction completes.
             */
            SELECT
                a.account_type,
                a.is_postable,
                a.parent_account_id

            INTO
                ancestor_account_type,
                ancestor_is_postable,
                next_parent_id

            FROM accounts a

            WHERE a.business_id = NEW.business_id
              AND a.id = current_parent_id

            FOR SHARE;


            -- ================================================
            -- REJECT MISSING OR CROSS-BUSINESS PARENTS
            -- ================================================

            /*
             * The composite foreign key provides the
             * final database-level relationship check.
             *
             * This explicit validation provides a
             * more readable error.
             */
            IF NOT FOUND THEN

                RAISE EXCEPTION
                    'Parent account % does not exist in business %',
                    current_parent_id,
                    NEW.business_id
                    USING ERRCODE = '23503';

            END IF;


            -- ================================================
            -- REQUIRE MATCHING ACCOUNT TYPES
            -- ================================================

            /*
             * An ASSET account must not appear beneath
             * a LIABILITY, REVENUE, or other incompatible
             * fundamental account type.
             */
            IF ancestor_account_type <> NEW.account_type THEN

                RAISE EXCEPTION
                    'Parent and child accounts must have the same account type'
                    USING ERRCODE = '23514';

            END IF;


            -- ================================================
            -- REQUIRE NON-POSTABLE ANCESTORS
            -- ================================================

            /*
             * Any account with descendants functions
             * as a grouping account.
             *
             * Grouping accounts cannot accept
             * direct ledger postings.
             */
            IF ancestor_is_postable THEN

                RAISE EXCEPTION
                    'Parent account % must be non-postable',
                    current_parent_id
                    USING ERRCODE = '23514';

            END IF;


            -- ================================================
            -- CONTINUE UPWARD
            -- ================================================

            /*
             * Remember this ancestor and examine
             * its parent on the next iteration.
             */
            visited_account_ids :=
                array_append(
                    visited_account_ids,
                    current_parent_id
                );

            current_parent_id := next_parent_id;

        END LOOP;

    END IF;


    -- ========================================================
    -- ALLOW VALID ACCOUNT CHANGES
    -- ========================================================

    RETURN NEW;

END;
$$;


-- ============================================================
-- STEP 7 — INSTALL ACCOUNT VALIDATION TRIGGER
-- ============================================================

/*
 * Run account integrity validation before:
 *
 * - Creating an account
 * - Updating an existing account
 *
 * This protects the schema even when changes
 * bypass the Spring Boot API.
 */

CREATE TRIGGER trg_validate_account_hierarchy

BEFORE INSERT OR UPDATE

ON accounts

FOR EACH ROW

EXECUTE FUNCTION validate_account_hierarchy_and_postability();


-- ============================================================
-- STEP 8 — VALIDATE LEDGER ENTRY ACCOUNT ELIGIBILITY
-- ============================================================

/*
 * Every ledger entry must reference a postable account.
 *
 * This applies to:
 *
 * - Newly created DRAFT journal entries
 * - Updates to existing DRAFT journal entries
 * - Direct SQL modifications
 *
 * The existing V4 trigger continues protecting
 * entries belonging to POSTED transactions.
 *
 * The new trigger complements V4; it does not
 * replace transaction immutability rules.
 *
 * CONCURRENCY:
 *
 * The referenced account is locked FOR SHARE.
 *
 * This conflicts with an UPDATE that attempts
 * to change the account's posting eligibility.
 *
 * The account-validation trigger then checks
 * whether any ledger entries already exist.
 *
 * Consequently, a ledger-entry write and a
 * conversion to a grouping account cannot both
 * commit in an inconsistent state.
 */

CREATE FUNCTION validate_postable_ledger_account()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE

    -- Posting eligibility of the referenced account.
    account_is_postable BOOLEAN;

BEGIN

    -- ========================================================
    -- FIND AND LOCK THE REFERENCED ACCOUNT
    -- ========================================================

    /*
     * Match both business ID and account ID.
     *
     * FOR SHARE coordinates with account updates
     * that may change is_postable.
     */
    SELECT a.is_postable
    INTO account_is_postable

    FROM accounts a

    WHERE a.business_id = NEW.business_id
      AND a.id = NEW.account_id

    FOR SHARE;


    -- ========================================================
    -- REJECT UNKNOWN ACCOUNT REFERENCES
    -- ========================================================

    /*
     * The existing foreign key also protects
     * account references.
     *
     * Check explicitly to provide a readable error.
     */
    IF NOT FOUND THEN

        RAISE EXCEPTION
            'Ledger entry references an unknown account % in business %',
            NEW.account_id,
            NEW.business_id
            USING ERRCODE = '23503';

    END IF;


    -- ========================================================
    -- REJECT NON-POSTABLE ACCOUNTS
    -- ========================================================

    /*
     * Non-postable accounts are organizational
     * headings, not general ledger posting accounts.
     */
    IF account_is_postable = FALSE THEN

        RAISE EXCEPTION
            'Cannot create or update ledger entry: account % is non-postable',
            NEW.account_id
            USING ERRCODE = '23514';

    END IF;


    -- ========================================================
    -- ALLOW VALID LEDGER ENTRY
    -- ========================================================

    RETURN NEW;

END;
$$;


-- ============================================================
-- STEP 9 — INSTALL LEDGER ENTRY VALIDATION TRIGGER
-- ============================================================

/*
 * Validate the account whenever a ledger entry
 * is inserted or updated.
 *
 * PostgreSQL executes BEFORE triggers of the
 * same kind in alphabetical order by name.
 *
 * The existing V4 trigger:
 *
 * trg_protect_posted_ledger_entries
 *
 * runs before this new validation trigger:
 *
 * trg_validate_ledger_entry_account
 *
 * The V4 trigger therefore checks and locks the
 * parent transaction before V7 locks the account.
 */

CREATE TRIGGER trg_validate_ledger_entry_account

BEFORE INSERT OR UPDATE

ON ledger_entries

FOR EACH ROW

EXECUTE FUNCTION validate_postable_ledger_account();


-- ============================================================
-- END OF MIGRATION V7 — DRAFT
-- ============================================================
