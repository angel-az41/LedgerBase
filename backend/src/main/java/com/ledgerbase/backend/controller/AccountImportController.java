package com.ledgerbase.backend.controller;

import com.ledgerbase.backend.dto.AccountImportError;
import com.ledgerbase.backend.dto.AccountImportPreviewResponse;
import com.ledgerbase.backend.dto.AccountImportResultResponse;
import com.ledgerbase.backend.dto.AccountImportRow;

import com.ledgerbase.backend.service.AccountImportExecutionService;
import com.ledgerbase.backend.service.AccountImportFileParser;
import com.ledgerbase.backend.service.AccountImportValidationService;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

// ============================================================
// LEDGERBASE — ACCOUNT IMPORT CONTROLLER
// ============================================================

/**
 * REST API for importing Chart of Accounts data.
 *
 * MILESTONE 20 — ACCOUNTING IMPORT & EXPORT
 *
 * CURRENT FUNCTIONALITY:
 *
 * - Accept uploaded CSV files.
 * - Accept uploaded XLSX files.
 * - Parse spreadsheet rows.
 * - Validate accounting rules.
 * - Return an import preview without database writes.
 * - Confirm validated imports.
 * - Insert accounts in one database transaction.
 * - Resolve parent accounts by account code.
 * - Roll back unsuccessful imports.
 *
 * BASE URL:
 *
 * /api/v1/businesses/{businessId}/accounts
 *
 * IMPORTANT:
 *
 * /import/preview performs read-only validation.
 * /import performs transactional account creation.
 *
 * The controller delegates all database writes to
 * AccountImportExecutionService.
 *
 * Business ownership comes from the URL,
 * not from the uploaded spreadsheet.
 *
 * Authentication and business-level authorization
 * must be implemented before public deployment.
 */
@RestController
@RequestMapping(
    "/api/v1/businesses/{businessId}/accounts"
)
public class AccountImportController {

    // ========================================================
    // FILE SIZE LIMIT
    // ========================================================

    /**
     * Match the 5 MB limit enforced by the parser.
     *
     * Check the uploaded size before calling getBytes()
     * to avoid unnecessary memory allocation.
     *
     * Spring's multipart request limits must also
     * be configured separately for larger uploads.
     */
    private static final long MAX_FILE_BYTES =
        5L * 1024 * 1024;


    // ========================================================
    // DEPENDENCIES
    // ========================================================

    /**
     * Reads CSV and Excel files and converts their
     * contents into AccountImportRow objects.
     */
    private final AccountImportFileParser fileParser;

    /**
     * Validates imported rows against the existing
     * Chart of Accounts and accounting rules.
     */
    private final AccountImportValidationService
        validationService;

    /**
     * Revalidates an import and saves all accounts
     * atomically, resolving parents before children.
     */
    private final AccountImportExecutionService
        importExecutionService;


    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    /**
     * Spring injects the parser, validator, and execution
     * service. Database writes happen in the service layer.
     */
    public AccountImportController(
            AccountImportFileParser fileParser,
            AccountImportValidationService validationService,
            AccountImportExecutionService importExecutionService) {

        this.fileParser = fileParser;

        this.validationService = validationService;

        this.importExecutionService = importExecutionService;
    }


    // ============================================================
    // IMPORT PREVIEW ENDPOINT
    // ============================================================

    /**
     * POST /api/v1/businesses/2/accounts/import/preview
     *
     * CONTENT TYPE:
     *
     * multipart/form-data
     *
     * FORM FIELD:
     *
     * file = uploaded CSV or XLSX spreadsheet
     *
     * RESPONSE:
     *
     * AccountImportPreviewResponse containing:
     *
     * - businessId
     * - totalRows
     * - validRows
     * - invalidRows
     * - readyToImport
     * - rows
     * - errors
     *
     * Invalid spreadsheet contents produce a preview
     * with readyToImport = false.
     *
     * Validation errors do not modify the database.
     */
    @PostMapping(
        value = "/import/preview",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<AccountImportPreviewResponse>
            previewAccountImport(

            @PathVariable
            Long businessId,

            @RequestParam("file")
            MultipartFile file) {

        // ====================================================
        // STEP 1 — REQUIRE NONEMPTY UPLOAD
        // ====================================================

        /**
         * Missing or empty uploaded files cannot
         * produce a meaningful accounting preview.
         */
        if (file == null || file.isEmpty()) {

            return previewError(
                businessId,
                "A nonempty CSV or XLSX file is required"
            );
        }


        // ====================================================
        // STEP 2 — ENFORCE FILE SIZE LIMIT
        // ====================================================

        /**
         * The parser also enforces this limit.
         *
         * Checking here first avoids copying
         * an oversized file into a byte array.
         */
        if (file.getSize() > MAX_FILE_BYTES) {

            return previewError(
                businessId,
                "The uploaded file exceeds the 5 MB limit"
            );
        }


        // ====================================================
        // STEP 3 — READ AND PARSE SPREADSHEET
        // ====================================================

        try {

            /**
             * MultipartFile provides:
             *
             * - The original filename.
             * - The uploaded file contents.
             *
             * The parser accepts CSV and XLSX,
             * verifies column headings, and preserves
             * the original spreadsheet values.
             */
            List<AccountImportRow> importedRows =
                fileParser.parse(
                    file.getOriginalFilename(),
                    file.getBytes()
                );


            // =================================================
            // STEP 4 — VALIDATE ACCOUNTING RULES
            // =================================================

            /**
             * The validator checks:
             *
             * - Target business existence.
             * - Required account fields.
             * - Existing and duplicate account codes.
             * - Valid account types.
             * - Reporting group compatibility.
             * - Parent-child account relationships.
             * - Account status values.
             * - Circular hierarchies.
             *
             * This operation is read-only.
             */
            AccountImportPreviewResponse preview =
                validationService.validate(
                    businessId,
                    importedRows
                );


            // =================================================
            // STEP 5 — RETURN VALIDATION PREVIEW
            // =================================================

            /**
             * HTTP 200 is returned even when
             * individual account rows are invalid.
             *
             * The response explains the issues
             * through its errors collection.
             *
             * readyToImport indicates whether the
             * frontend may offer confirmation.
             */
            return ResponseEntity
                .ok()
                .header(
                    HttpHeaders.CACHE_CONTROL,
                    "no-store"
                )
                .body(preview);


        // ====================================================
        // HANDLE INVALID SPREADSHEET CONTENTS
        // ====================================================

        } catch (IllegalArgumentException exception) {

            /**
             * File-level errors include:
             *
             * - Unsupported extensions.
             * - Invalid column headings.
             * - Unexpected extra columns.
             * - Invalid Excel formula cells.
             *
             * Return a structured preview error
             * instead of exposing a generic
             * server error to the frontend.
             */
            return previewError(
                businessId,
                exception.getMessage()
            );


        // ====================================================
        // HANDLE FILE READING FAILURES
        // ====================================================

        } catch (IOException exception) {

            /**
             * If the uploaded file cannot be read,
             * report a useful file-level error.
             *
             * Do not expose internal stack traces.
             */
            return previewError(
                businessId,
                "Unable to read the uploaded file"
            );
        }
    }


    // ============================================================
    // EXECUTE ACCOUNT IMPORT ENDPOINT
    // ============================================================

    /**
     * POST /api/v1/businesses/{businessId}/accounts/import
     *
     * CONTENT TYPE:
     * multipart/form-data
     *
     * FORM FIELD:
     * file = uploaded CSV or XLSX spreadsheet
     *
     * Unlike /import/preview, this endpoint writes to
     * PostgreSQL only after all imported rows pass validation.
     *
     * AccountImportExecutionService owns the transaction:
     * all accounts commit, or all inserts roll back.
     */
    @PostMapping(
        value = "/import",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<AccountImportResultResponse>
            importAccounts(

            @PathVariable("businessId") Long businessId,

            @RequestParam("file") MultipartFile file) {

        // ====================================================
        // STEP 1 — REQUIRE A NONEMPTY FILE
        // ====================================================

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "A nonempty CSV or XLSX file is required"
            );
        }

        // ====================================================
        // STEP 2 — CHECK SIZE BEFORE READING FILE BYTES
        // ====================================================

        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "The uploaded file exceeds the 5 MB limit"
            );
        }

        // ====================================================
        // STEP 3 — DELEGATE THE ENTIRE ATOMIC IMPORT
        // ====================================================

        try {

            AccountImportResultResponse result =
                importExecutionService.importAccounts(
                    businessId,
                    file.getOriginalFilename(),
                    file.getBytes()
                );

            // Success means the service transaction committed.
            // Disable caching of financial import results.
            return ResponseEntity
                .ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(result);

        } catch (IllegalArgumentException exception) {

            // An invalid file is a client error, not a
            // successful import. The service transaction
            // rolls back before the error reaches here.
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                exception.getMessage(),
                exception
            );

        } catch (IOException exception) {

            // An uploaded file that cannot be read or
            // parsed cannot be imported.
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unable to read the uploaded file",
                exception
            );
        }

        // Unexpected database errors are intentionally NOT
        // swallowed here, so they remain failures and cause
        // the service transaction to roll back.
    }


    // ============================================================
    // HELPER — CREATE FILE-LEVEL ERROR RESPONSE
    // ============================================================

    /**
     * Return a preview response for invalid files.
     *
     * Row number 0 indicates a file-level error,
     * rather than an individual account row.
     *
     * No account data is inserted or modified.
     *
     * HTTP 200 allows the React preview interface
     * to display these validation errors normally.
     */
    private ResponseEntity<AccountImportPreviewResponse>
            previewError(
                Long businessId,
                String message) {

        // ====================================================
        // CREATE FILE-LEVEL VALIDATION ERROR
        // ====================================================

        AccountImportError error =
            new AccountImportError(
                0,
                "file",
                message
            );


        // ====================================================
        // CREATE FAILED PREVIEW
        // ====================================================

        AccountImportPreviewResponse preview =
            new AccountImportPreviewResponse(
                businessId,
                0,
                0,
                0,
                false,
                List.of(),
                List.of(error)
            );


        // ====================================================
        // RETURN RESPONSE WITHOUT CACHING
        // ====================================================

        return ResponseEntity
            .ok()
            .header(
                HttpHeaders.CACHE_CONTROL,
                "no-store"
            )
            .body(preview);
    }
}
