-- ============================================================
-- V4: Protect posted financial transactions
--
-- Goals:
-- 1. Prevent modifying ledger entries after posting.
-- 2. Prevent adding entries to posted transactions.
-- 3. Prevent deleting posted transactions.
-- 4. Prevent changing a posted transaction back to DRAFT.
-- 5. Coordinate ledger changes with posting using row locks.
--
-- Posted financial records should be corrected using
-- separate reversing transactions, not by rewriting history.
-- ============================================================


-- ------------------------------------------------------------
-- Function 1: Protect ledger entries
-- ------------------------------------------------------------

CREATE FUNCTION protect_posted_ledger_entries()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    -- Transaction owning the entry being changed.
    parent_transaction_id BIGINT;
    parent_business_id BIGINT;

    -- Current status of the parent transaction.
    parent_status VARCHAR(20);
BEGIN

    -- DELETE operations use OLD because NEW does not exist.
    IF TG_OP = 'DELETE' THEN
        parent_transaction_id := OLD.transaction_id;
        parent_business_id := OLD.business_id;

    ELSE
        -- INSERT and UPDATE operations have NEW values.
        parent_transaction_id := NEW.transaction_id;
        parent_business_id := NEW.business_id;
    END IF;


    -- Do not allow existing entries to be moved
    -- to another transaction or business.
    IF TG_OP = 'UPDATE' THEN

        IF NEW.transaction_id IS DISTINCT FROM OLD.transaction_id
           OR NEW.business_id IS DISTINCT FROM OLD.business_id THEN

            RAISE EXCEPTION
                'Cannot move a ledger entry to another transaction'
                USING ERRCODE = '23514';

        END IF;

    END IF;


    -- Lock the parent transaction while checking its status.
    --
    -- FOR UPDATE coordinates entry changes with posting.
    -- If posting currently holds the row lock, this operation
    -- waits and then observes the latest committed status.
    SELECT status
    INTO parent_status
    FROM transactions
    WHERE id = parent_transaction_id
      AND business_id = parent_business_id
    FOR UPDATE;


    -- Once POSTED, no ledger entries may be inserted,
    -- updated, or deleted.
    IF parent_status = 'POSTED' THEN

        RAISE EXCEPTION
            'Cannot modify ledger entries of posted transaction %',
            parent_transaction_id
            USING ERRCODE = '23514';

    END IF;


    -- Return the appropriate row for the trigger operation.
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    RETURN NEW;

END;
$$;


-- Apply the protection to all ledger entry modifications.
CREATE TRIGGER trg_protect_posted_ledger_entries
BEFORE INSERT OR UPDATE OR DELETE
ON ledger_entries
FOR EACH ROW
EXECUTE FUNCTION protect_posted_ledger_entries();


-- ------------------------------------------------------------
-- Function 2: Protect posted transaction headers
-- ------------------------------------------------------------

CREATE FUNCTION protect_posted_transaction_headers()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN

    -- Deleting a posted transaction would erase
    -- part of the accounting history.
    IF TG_OP = 'DELETE' THEN

        IF OLD.status = 'POSTED' THEN

            RAISE EXCEPTION
                'Cannot delete posted transaction %',
                OLD.id
                USING ERRCODE = '23514';

        END IF;

        RETURN OLD;

    END IF;


    -- Reject every UPDATE to an already posted transaction.
    --
    -- This prevents changes to descriptions, dates,
    -- business ownership, and status after finalization.
    --
    -- The initial DRAFT -> POSTED update is permitted
    -- because OLD.status is still DRAFT.
    IF OLD.status = 'POSTED' THEN

        RAISE EXCEPTION
            'Cannot modify posted transaction %',
            OLD.id
            USING ERRCODE = '23514';

    END IF;

    RETURN NEW;

END;
$$;


-- Apply the protection to transaction headers.
CREATE TRIGGER trg_protect_posted_transaction_headers
BEFORE UPDATE OR DELETE
ON transactions
FOR EACH ROW
EXECUTE FUNCTION protect_posted_transaction_headers();
