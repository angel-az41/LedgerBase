package com.ledgerbase.backend.dto;

// ============================================================
// ACCOUNT IMPORT RESULT RESPONSE
// ============================================================

/**
 * Response returned after a successful account import.
 *
 * Unlike the preview response, this response confirms
 * that accounts have been persisted successfully.
 *
 * businessId:
 *     Business receiving the imported accounts.
 *
 * importedRows:
 *     Number of accounts successfully created.
 *
 * success:
 *     Whether the entire import succeeded.
 *
 * The import service uses a database transaction,
 * so partial imports are not considered successful.
 */
public record AccountImportResultResponse(

    Long businessId,

    int importedRows,

    boolean success

) {}
