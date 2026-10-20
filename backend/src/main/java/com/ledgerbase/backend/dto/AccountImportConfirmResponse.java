package com.ledgerbase.backend.dto;

import java.util.List;

// ============================================================
// LEDGERBASE — ACCOUNT IMPORT CONFIRMATION RESPONSE
// ============================================================

/**
 * Represents the result of successfully importing
 * Chart of Accounts records into PostgreSQL.
 *
 * MILESTONE 20 — FINANCIAL DATA IMPORT
 *
 * Unlike AccountImportPreviewResponse, this DTO
 * describes accounts that have actually been created.
 *
 * The confirmation service will return this response
 * only after all imported accounts have been processed
 * successfully within one database transaction.
 *
 * IMPORTANT:
 *
 * 1. Preview operations must never return this DTO.
 * 2. Invalid imports must not return a success response.
 * 3. Created account IDs come from PostgreSQL.
 * 4. Parent IDs represent resolved account hierarchy.
 * 5. A failed import must roll back all changes.
 */
public record AccountImportConfirmResponse(

    // ========================================================
    // TARGET BUSINESS
    // ========================================================

    /**
     * The business whose Chart of Accounts
     * received the imported accounts.
     */
    Long businessId,


    // ========================================================
    // NUMBER OF IMPORTED ACCOUNTS
    // ========================================================

    /**
     * Total number of accounts created.
     *
     * This must match the number of items
     * in the accounts list.
     */
    int importedCount,


    // ========================================================
    // CREATED ACCOUNTS
    // ========================================================

    /**
     * Details of each successfully created account.
     *
     * The order may differ from the uploaded file
     * because grouping accounts must be inserted
     * before accounts that reference them.
     */
    List<CreatedAccount> accounts

) {

    // ========================================================
    // DEFENSIVE VALIDATION
    // ========================================================

    /**
     * Protect the response from accidental mutation.
     *
     * Ensure that the reported count matches the
     * actual number of created account records.
     */
    public AccountImportConfirmResponse {

        accounts = List.copyOf(accounts);

        if (importedCount != accounts.size()) {

            throw new IllegalArgumentException(
                "Imported account count does not match "
                + "the created account list"
            );
        }
    }


    // ========================================================
    // NESTED DTO — CREATED ACCOUNT
    // ========================================================

    /**
     * Represents an individual account created
     * during import confirmation.
     *
     * id:
     *   New PostgreSQL account ID.
     *
     * code:
     *   Account code, including leading zeros.
     *
     * parentAccountId:
     *   Resolved parent account ID, or null
     *   when the account has no parent.
     *
     * active:
     *   Final account active status.
     *
     * postable:
     *   Whether journal entries can be posted
     *   directly to this account.
     */
    public record CreatedAccount(
        Long id,
        String code,
        Long parentAccountId,
        boolean active,
        boolean postable
    ) {
    }


    // ========================================================
    // END — ACCOUNT IMPORT CONFIRMATION RESPONSE
    // ========================================================
}
