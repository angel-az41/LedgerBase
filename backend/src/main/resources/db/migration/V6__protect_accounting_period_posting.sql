-- ============================================================
-- V6: Protect accounting-period posting
--
-- Goals:
-- 1. Require an OPEN accounting period when posting.
-- 2. Reject posting into CLOSED periods.
-- 3. Reject posting without a matching period.
-- 4. Reject direct INSERT of POSTED transactions.
-- 5. Coordinate posting and period closing using row locks.
--
-- This complements V4, which protects financial records
-- after they have already been posted.
-- ============================================================


-- ============================================================
-- FUNCTION: VALIDATE ACCOUNTING PERIOD DURING POSTING
-- ============================================================

CREATE FUNCTION protect_accounting_period_posting()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE

    -- Status of the accounting period that contains
    -- the transaction's accounting date.
    matched_period_status VARCHAR(20);

BEGIN

    -- ========================================================
    -- REJECT DIRECT INSERTS OF POSTED TRANSACTIONS
    -- ========================================================

    /*
     * Journal transactions must begin as DRAFT.
     *
     * Their ledger entries are created and validated
     * before the transaction transitions to POSTED.
     *
     * Directly inserting a POSTED transaction header
     * would bypass that workflow.
     */
    IF TG_OP = 'INSERT' THEN

        IF NEW.status = 'POSTED' THEN

            RAISE EXCEPTION
                'Cannot insert a transaction directly as POSTED'
                USING ERRCODE = '23514';

        END IF;

        -- Normal DRAFT creation is permitted.
        RETURN NEW;

    END IF;


    -- ========================================================
    -- VALIDATE TRANSITIONS TO POSTED
    -- ========================================================

    /*
     * This trigger also runs on UPDATE.
     *
     * Only a transition into POSTED needs a new
     * accounting-period check.
     *
     * V4 separately rejects modifications to
     * transactions that are already POSTED.
     */
    IF NEW.status = 'POSTED'
       AND OLD.status IS DISTINCT FROM 'POSTED' THEN

        -- ====================================================
        -- FIND AND LOCK THE MATCHING ACCOUNTING PERIOD
        -- ====================================================

        /*
         * Match both the business and transaction date.
         *
         * FOR UPDATE locks the accounting-period row
         * until the surrounding transaction finishes.
         *
         * This coordinates with period-closing UPDATEs.
         *
         * If closing happens first, posting waits and
         * then observes the CLOSED status.
         *
         * If posting happens first, closing waits until
         * the posting transaction commits or rolls back.
         */
        SELECT ap.status
        INTO matched_period_status
        FROM accounting_periods ap
        WHERE ap.business_id = NEW.business_id
          AND NEW.transaction_date
              BETWEEN ap.start_date AND ap.end_date
        FOR UPDATE;


        -- ====================================================
        -- REJECT MISSING ACCOUNTING PERIODS
        -- ====================================================

        IF NOT FOUND THEN

            RAISE EXCEPTION
                'Cannot post transaction: no accounting period exists for date % (business %)',
                NEW.transaction_date,
                NEW.business_id
                USING ERRCODE = '23514';

        END IF;


        -- ====================================================
        -- REJECT CLOSED ACCOUNTING PERIODS
        -- ====================================================

        IF matched_period_status <> 'OPEN' THEN

            RAISE EXCEPTION
                'Cannot post transaction: accounting period is % for date %',
                matched_period_status,
                NEW.transaction_date
                USING ERRCODE = '23514';

        END IF;

    END IF;


    -- ========================================================
    -- ALLOW VALID OPERATIONS
    -- ========================================================

    /*
     * DRAFT creation remains allowed.
     *
     * A transition to POSTED is allowed only when
     * its matching accounting period is OPEN.
     *
     * Existing V4 triggers continue protecting
     * already-posted financial records.
     */
    RETURN NEW;

END;
$$;


-- ============================================================
-- TRIGGER: PROTECT TRANSACTION POSTING
-- ============================================================

/*
 * BEFORE INSERT:
 *   Reject transaction headers inserted as POSTED.
 *
 * BEFORE UPDATE:
 *   Validate the accounting period when status
 *   changes from DRAFT to POSTED.
 *
 * PostgreSQL executes this trigger in the same
 * database transaction as the original statement.
 *
 * Raising an exception rejects the statement.
 */
CREATE TRIGGER trg_protect_accounting_period_posting
BEFORE INSERT OR UPDATE
ON transactions
FOR EACH ROW
EXECUTE FUNCTION protect_accounting_period_posting();
