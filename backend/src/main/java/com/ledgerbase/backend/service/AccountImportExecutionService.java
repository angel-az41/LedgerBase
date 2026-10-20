package com.ledgerbase.backend.service;

import com.ledgerbase.backend.dto.AccountImportPreviewResponse;
import com.ledgerbase.backend.dto.AccountImportResultResponse;
import com.ledgerbase.backend.dto.AccountImportRow;
import com.ledgerbase.backend.model.Account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

// ============================================================
// ACCOUNT IMPORT EXECUTION SERVICE
// ============================================================

/**
 * Executes validated Chart of Accounts imports.
 *
 * Responsibilities:
 *
 * 1. Parse the uploaded CSV/XLSX file.
 * 2. Revalidate all imported rows.
 * 3. Resolve parent-child account relationships.
 * 4. Create accounts in dependency order.
 * 5. Preserve active/postable flags.
 * 6. Roll back all writes if any operation fails.
 *
 * This service reuses the existing AccountService
 * rather than bypassing its business rules.
 */
@Service
public class AccountImportExecutionService {

    private final AccountImportFileParser parser;

    private final AccountImportValidationService validationService;

    private final AccountService accountService;

    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    public AccountImportExecutionService(
            AccountImportFileParser parser,
            AccountImportValidationService validationService,
            AccountService accountService) {

        this.parser = parser;
        this.validationService = validationService;
        this.accountService = accountService;
    }

    // ========================================================
    // EXECUTE TRANSACTIONAL ACCOUNT IMPORT
    // ========================================================

    /**
     * Import all valid accounts as one atomic operation.
     *
     * @Transactional ensures that:
     *
     * - All inserts commit together.
     * - Any failure rolls back previous inserts.
     * - No partial Chart of Accounts import remains.
     *
     * rollbackFor = Exception.class also covers
     * checked exceptions.
     */
    @Transactional(rollbackFor = Exception.class)
    public AccountImportResultResponse importAccounts(
            Long businessId,
            String filename,
            byte[] fileBytes) throws IOException {

        // ====================================================
        // STEP 1 — PARSE UPLOADED FILE
        // ====================================================

        List<AccountImportRow> rows = parser.parse(
            filename,
            fileBytes
        );

        // ====================================================
        // STEP 2 — REVALIDATE BEFORE WRITING
        // ====================================================

        /*
         * Validation cannot rely on a previous preview.
         *
         * Database contents may have changed between
         * the preview request and the import request.
         */
        AccountImportPreviewResponse preview =
            validationService.validate(businessId, rows);

        if (!preview.readyToImport() || rows.isEmpty()) {

            throw new IllegalArgumentException(
                "Account import failed validation. "
                + "Run the preview again before importing."
            );
        }

        // ====================================================
        // STEP 3 — LOAD EXISTING ACCOUNT CODES AND IDS
        // ====================================================

        /*
         * Parent Account Code is provided by the file.
         *
         * PostgreSQL relationships require parentAccountId.
         *
         * This map translates account codes into database IDs.
         */
        Map<String, Long> accountIdsByCode = new HashMap<>();

        for (Account account :
                accountService.getAccounts(businessId)) {

            accountIdsByCode.put(
                account.getCode().trim(),
                account.getId()
            );
        }

        // ====================================================
        // STEP 4 — PREPARE IMPORT ROWS
        // ====================================================

        /*
         * Rows may arrive in any order.
         *
         * Example:
         *
         * 9951 -> Parent 9950
         * 9950 -> No parent
         *
         * We must create 9950 before 9951.
         */
        List<AccountImportRow> remaining =
            new ArrayList<>(rows);

        int importedCount = 0;

        // ====================================================
        // STEP 5 — INSERT ACCOUNTS IN DEPENDENCY ORDER
        // ====================================================

        while (!remaining.isEmpty()) {

            boolean madeProgress = false;

            Iterator<AccountImportRow> iterator =
                remaining.iterator();

            while (iterator.hasNext()) {

                AccountImportRow row = iterator.next();

                String parentCode = optionalValue(
                    row.parentAccountCode()
                );

                // =================================================
                // WAIT UNTIL THE PARENT HAS BEEN CREATED
                // =================================================

                if (parentCode != null &&
                        !accountIdsByCode.containsKey(parentCode)) {

                    continue;
                }

                Long parentAccountId = parentCode == null
                    ? null
                    : accountIdsByCode.get(parentCode);

                // =================================================
                // CREATE ACCOUNT USING EXISTING BUSINESS RULES
                // =================================================

                Account created = accountService.createAccount(
                    businessId,
                    row.code().trim(),
                    row.name().trim(),
                    row.accountType().trim(),
                    optionalValue(row.reportingGroup()),
                    parentAccountId,
                    booleanValue(row.postable())
                );

                // =================================================
                // PRESERVE INACTIVE STATUS
                // =================================================

                /*
                 * AccountService creates accounts as active.
                 *
                 * If the import specifies FALSE, deactivate
                 * the newly created account.
                 */
                if (!booleanValue(row.active())) {

                    boolean deactivated =
                        accountService.deactivateAccount(
                            businessId,
                            created.getId()
                        );

                    if (!deactivated) {

                        throw new IllegalStateException(
                            "Failed to deactivate imported account: "
                            + row.code()
                        );
                    }
                }

                // =================================================
                // REGISTER CREATED ACCOUNT FOR CHILDREN
                // =================================================

                accountIdsByCode.put(
                    created.getCode().trim(),
                    created.getId()
                );

                iterator.remove();

                importedCount++;

                madeProgress = true;
            }

            // ====================================================
            // PREVENT UNRESOLVABLE HIERARCHIES
            // ====================================================

            /*
             * The validation service already rejects
             * missing parents and circular relationships.
             *
             * This additional safeguard guarantees
             * that execution never loops indefinitely.
             */
            if (!madeProgress) {

                throw new IllegalStateException(
                    "Unable to resolve imported account hierarchy"
                );
            }
        }

        // ====================================================
        // STEP 6 — RETURN SUCCESSFUL IMPORT RESULT
        // ====================================================

        return new AccountImportResultResponse(
            businessId,
            importedCount,
            true
        );
    }

    // ========================================================
    // NORMALIZE OPTIONAL STRING VALUES
    // ========================================================

    /**
     * Convert blank optional values into null.
     */
    private String optionalValue(String value) {

        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    // ========================================================
    // CONVERT VALIDATED BOOLEAN VALUES
    // ========================================================

    /**
     * Treat blank values as TRUE.
     *
     * The validation service must already have rejected
     * any nonblank values other than TRUE or FALSE.
     */
    private boolean booleanValue(String value) {

        if (value == null || value.isBlank()) {
            return true;
        }

        return Boolean.parseBoolean(value.trim());
    }
}
