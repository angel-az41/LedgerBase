package com.ledgerbase.backend.service;

import com.ledgerbase.backend.dto.AccountImportError;
import com.ledgerbase.backend.dto.AccountImportPreviewResponse;
import com.ledgerbase.backend.dto.AccountImportRow;

import com.ledgerbase.backend.exception.BusinessNotFoundException;

import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.model.Account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// ============================================================
// LEDGERBASE — ACCOUNT IMPORT VALIDATION SERVICE
// ============================================================

/**
 * Validates Chart of Accounts import data.
 *
 * MILESTONE 20 — FINANCIAL DATA IMPORT
 *
 * RESPONSIBILITIES:
 *
 * 1. Verify that the target business exists.
 * 2. Validate required account fields.
 * 3. Check existing and uploaded account codes.
 * 4. Validate accounting classifications.
 * 5. Verify account hierarchy relationships.
 * 6. Validate account status and posting eligibility.
 * 7. Detect circular parent-child relationships.
 * 8. Generate a detailed import preview.
 *
 * IMPORTANT:
 *
 * Validation is READ-ONLY.
 *
 * This service must never:
 *
 * - Insert accounts.
 * - Update existing accounts.
 * - Delete accounts.
 * - Change ledger balances.
 * - Post journal entries.
 *
 * The later confirmation service will revalidate
 * everything before performing an atomic import.
 */
@Service
public class AccountImportValidationService {

    // ========================================================
    // VALID ACCOUNT TYPES
    // ========================================================

    /**
     * Match LedgerBase's CreateAccountRequest and
     * existing AccountService classifications.
     */
    private static final Set<String> ACCOUNT_TYPES = Set.of(
        "ASSET",
        "LIABILITY",
        "EQUITY",
        "REVENUE",
        "EXPENSE"
    );


    // ========================================================
    // ACCOUNT FIELD LENGTH LIMITS
    // ========================================================

    /**
     * These limits mirror the existing account
     * creation DTO.
     */
    private static final int MAX_CODE_LENGTH = 20;

    private static final int MAX_NAME_LENGTH = 150;

    private static final int MAX_GROUP_LENGTH = 40;

    private static final int MAX_IMPORT_ROWS = 5000;


    // ========================================================
    // DEPENDENCIES
    // ========================================================

    /**
     * Read existing business accounts.
     *
     * No additional MyBatis query is necessary.
     */
    private final AccountService accountService;

    /**
     * Verify that the selected business exists.
     */
    private final BusinessMapper businessMapper;


    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    public AccountImportValidationService(
            AccountService accountService,
            BusinessMapper businessMapper) {

        this.accountService = accountService;
        this.businessMapper = businessMapper;
    }


    // ============================================================
    // 1. VALIDATE IMPORT AND GENERATE PREVIEW
    // ============================================================

    /**
     * Validate uploaded rows against LedgerBase's
     * existing accounts and accounting rules.
     *
     * @param businessId Target business from the API URL.
     * @param importedRows Rows returned by the file parser.
     *
     * @return Import preview containing all errors.
     */
    @Transactional(readOnly = true)
    public AccountImportPreviewResponse validate(
            Long businessId,
            List<AccountImportRow> importedRows) {

        // ====================================================
        // STEP 1 — VERIFY BUSINESS EXISTENCE
        // ====================================================

        if (
            businessId == null ||
            !businessMapper.existsById(businessId)
        ) {

            throw new BusinessNotFoundException(
                businessId
            );
        }


        // ====================================================
        // STEP 2 — PREPARE VALIDATION DATA
        // ====================================================

        /**
         * Treat a null input list as empty.
         *
         * The parser normally returns an immutable,
         * non-null list of spreadsheet rows.
         */
        List<AccountImportRow> rows =
            importedRows == null
                ? List.of()
                : List.copyOf(importedRows);

        List<AccountImportError> errors =
            new ArrayList<>();


        // ====================================================
        // STEP 3 — CHECK FILE-LEVEL REQUIREMENTS
        // ====================================================

        if (rows.isEmpty()) {

            addError(
                errors,
                0,
                "file",
                "The import file contains no account rows"
            );
        }

        if (rows.size() > MAX_IMPORT_ROWS) {

            addError(
                errors,
                0,
                "file",
                "An import cannot exceed "
                    + MAX_IMPORT_ROWS
                    + " account rows"
            );
        }


        // ====================================================
        // STEP 4 — LOAD EXISTING BUSINESS ACCOUNTS
        // ====================================================

        /**
         * Only retrieve accounts belonging to the
         * target business.
         *
         * This includes both active and inactive
         * accounts because existing codes must not
         * be reused accidentally.
         */
        List<Account> existingAccounts =
            accountService.getAccounts(businessId);


        // ====================================================
        // STEP 5 — INDEX EXISTING ACCOUNTS BY CODE
        // ====================================================

        Map<String, Account> existingByCode =
            new HashMap<>();

        for (Account account : existingAccounts) {

            existingByCode.put(
                account.getCode(),
                account
            );
        }


        // ====================================================
        // STEP 6 — INDEX UPLOADED ROWS BY ACCOUNT CODE
        // ====================================================

        /**
         * A list is used because the same account
         * code may appear more than once in a file.
         *
         * We need to identify every duplicate row,
         * rather than silently replacing earlier rows.
         */
        Map<String, List<AccountImportRow>> uploadedByCode =
            new HashMap<>();

        for (AccountImportRow row : rows) {

            String code = clean(row.code());

            if (code.isEmpty()) {
                continue;
            }

            uploadedByCode
                .computeIfAbsent(
                    code,
                    ignored -> new ArrayList<>()
                )
                .add(row);
        }


        // ====================================================
        // STEP 7 — VALIDATE EACH IMPORTED ROW
        // ====================================================

        for (AccountImportRow row : rows) {

            validateAccountRow(
                row,
                existingByCode,
                uploadedByCode,
                errors
            );
        }


        // ====================================================
        // STEP 8 — DETECT CIRCULAR HIERARCHIES
        // ====================================================

        /**
         * A spreadsheet might contain:
         *
         * 1000 -> Parent 2000
         * 2000 -> Parent 1000
         *
         * Both accounts may have valid types and
         * non-postable settings.
         *
         * However, this relationship contains a cycle.
         *
         * Detect cycles before allowing confirmation.
         */
        validateHierarchyCycles(
            rows,
            uploadedByCode,
            errors
        );


        // ====================================================
        // STEP 9 — COUNT DISTINCT INVALID ROWS
        // ====================================================

        /**
         * One account row may have multiple errors.
         *
         * Count each invalid source row only once.
         */
        Set<Integer> invalidRowNumbers =
            new HashSet<>();

        for (AccountImportError error : errors) {

            if (error.rowNumber() > 0) {

                invalidRowNumbers.add(
                    error.rowNumber()
                );
            }
        }

        int invalidRows =
            invalidRowNumbers.size();

        int validRows =
            Math.max(0, rows.size() - invalidRows);


        // ====================================================
        // STEP 10 — DETERMINE IMPORT READINESS
        // ====================================================

        /**
         * Import confirmation is permitted only when:
         *
         * - The file contains account rows.
         * - The file is within the supported row limit.
         * - Every row passes validation.
         * - No file-level errors exist.
         */
        boolean readyToImport =
            !rows.isEmpty()
            && rows.size() <= MAX_IMPORT_ROWS
            && errors.isEmpty();


        // ====================================================
        // STEP 11 — RETURN VALIDATION PREVIEW
        // ====================================================

        return new AccountImportPreviewResponse(
            businessId,
            rows.size(),
            validRows,
            invalidRows,
            readyToImport,
            rows,
            errors
        );
    }


    // ============================================================
    // 2. VALIDATE ONE ACCOUNT ROW
    // ============================================================

    /**
     * Validate basic account information and hierarchy.
     *
     * All errors are collected rather than stopping
     * at the first invalid field.
     *
     * This allows users to correct multiple spreadsheet
     * problems in a single editing pass.
     */
    private void validateAccountRow(
            AccountImportRow row,
            Map<String, Account> existingByCode,
            Map<String, List<AccountImportRow>> uploadedByCode,
            List<AccountImportError> errors) {

        int rowNumber = row.rowNumber();

        String code = clean(row.code());

        String name = clean(row.name());

        String accountType = clean(row.accountType());

        String reportingGroup = clean(row.reportingGroup());

        String parentCode = clean(row.parentAccountCode());


        // ====================================================
        // VALIDATE ACCOUNT CODE
        // ====================================================

        if (code.isEmpty()) {

            addError(
                errors,
                rowNumber,
                "code",
                "Account code is required"
            );

        } else {

            if (code.length() > MAX_CODE_LENGTH) {

                addError(
                    errors,
                    rowNumber,
                    "code",
                    "Account code cannot exceed "
                        + MAX_CODE_LENGTH
                        + " characters"
                );
            }


            // =================================================
            // CHECK EXISTING DATABASE ACCOUNT CODES
            // =================================================

            if (existingByCode.containsKey(code)) {

                addError(
                    errors,
                    rowNumber,
                    "code",
                    "Account code " + code
                        + " already exists in this business"
                );
            }


            // =================================================
            // CHECK DUPLICATE CODES WITHIN UPLOAD
            // =================================================

            List<AccountImportRow> matchingRows =
                uploadedByCode.get(code);

            if (
                matchingRows != null
                && matchingRows.size() > 1
            ) {

                addError(
                    errors,
                    rowNumber,
                    "code",
                    "Account code " + code
                        + " appears multiple times in the file"
                );
            }
        }


        // ====================================================
        // VALIDATE ACCOUNT NAME
        // ====================================================

        if (name.isEmpty()) {

            addError(
                errors,
                rowNumber,
                "name",
                "Account name is required"
            );

        } else if (name.length() > MAX_NAME_LENGTH) {

            addError(
                errors,
                rowNumber,
                "name",
                "Account name cannot exceed "
                    + MAX_NAME_LENGTH
                    + " characters"
            );
        }


        // ====================================================
        // VALIDATE ACCOUNT TYPE
        // ====================================================

        if (!ACCOUNT_TYPES.contains(accountType)) {

            addError(
                errors,
                rowNumber,
                "accountType",
                "Invalid account type: " + accountType
            );
        }


        // ====================================================
        // VALIDATE REPORTING GROUP
        // ====================================================

        /**
         * Reporting group is optional.
         *
         * When supplied, it must be compatible with
         * the account's fundamental account type.
         */
        if (!reportingGroup.isEmpty()) {

            if (reportingGroup.length() > MAX_GROUP_LENGTH) {

                addError(
                    errors,
                    rowNumber,
                    "reportingGroup",
                    "Reporting group cannot exceed "
                        + MAX_GROUP_LENGTH
                        + " characters"
                );

            } else if (
                ACCOUNT_TYPES.contains(accountType)
                && !isValidReportingGroup(
                    accountType,
                    reportingGroup
                )
            ) {

                addError(
                    errors,
                    rowNumber,
                    "reportingGroup",
                    "Reporting group "
                        + reportingGroup
                        + " is invalid for account type "
                        + accountType
                );
            }
        }


        // ====================================================
        // VALIDATE ACTIVE STATUS
        // ====================================================

        /**
         * TRUE:
         *   The imported account will be active.
         *
         * FALSE:
         *   The confirmation workflow must create
         *   and deactivate the account atomically.
         *
         * This step only validates the value.
         */
        if (parseBoolean(row.active()) == null) {

            addError(
                errors,
                rowNumber,
                "active",
                "Active must be TRUE or FALSE"
            );
        }


        // ====================================================
        // VALIDATE POSTING ELIGIBILITY
        // ====================================================

        if (parseBoolean(row.postable()) == null) {

            addError(
                errors,
                rowNumber,
                "postable",
                "Postable must be TRUE or FALSE"
            );
        }


        // ====================================================
        // VALIDATE OPTIONAL PARENT CODE
        // ====================================================

        if (!parentCode.isEmpty()) {

            validateParentAccount(
                row,
                code,
                accountType,
                parentCode,
                existingByCode,
                uploadedByCode,
                errors
            );
        }
    }


    // ============================================================
    // 3. VALIDATE PARENT ACCOUNT
    // ============================================================

    /**
     * Resolve an imported parent code using either:
     *
     * 1. Another account in the uploaded spreadsheet.
     * 2. An existing account in the selected business.
     *
     * New parent accounts may appear anywhere in the file.
     *
     * The later confirmation service will create
     * parent accounts before their children.
     */
    private void validateParentAccount(
            AccountImportRow row,
            String code,
            String accountType,
            String parentCode,
            Map<String, Account> existingByCode,
            Map<String, List<AccountImportRow>> uploadedByCode,
            List<AccountImportError> errors) {

        int rowNumber = row.rowNumber();


        // ====================================================
        // PARENT CODE LENGTH
        // ====================================================

        if (parentCode.length() > MAX_CODE_LENGTH) {

            addError(
                errors,
                rowNumber,
                "parentAccountCode",
                "Parent account code cannot exceed "
                    + MAX_CODE_LENGTH
                    + " characters"
            );

            return;
        }


        // ====================================================
        // PREVENT SELF-PARENTING
        // ====================================================

        if (parentCode.equals(code)) {

            addError(
                errors,
                rowNumber,
                "parentAccountCode",
                "An account cannot be its own parent"
            );

            return;
        }


        // ====================================================
        // FIRST CHECK PARENTS IN THE UPLOADED FILE
        // ====================================================

        List<AccountImportRow> uploadedParents =
            uploadedByCode.get(parentCode);

        if (uploadedParents != null) {

            // =================================================
            // REJECT AMBIGUOUS PARENT CODE
            // =================================================

            if (uploadedParents.size() != 1) {

                addError(
                    errors,
                    rowNumber,
                    "parentAccountCode",
                    "Parent account " + parentCode
                        + " appears multiple times in the file"
                );

                return;
            }


            // =================================================
            // VALIDATE UPLOADED PARENT
            // =================================================

            AccountImportRow parent =
                uploadedParents.get(0);

            if (
                !accountType.equals(
                    clean(parent.accountType())
                )
            ) {

                addError(
                    errors,
                    rowNumber,
                    "parentAccountCode",
                    "Parent and child accounts must "
                        + "have the same account type"
                );
            }

            if (
                !Boolean.FALSE.equals(
                    parseBoolean(parent.postable())
                )
            ) {

                addError(
                    errors,
                    rowNumber,
                    "parentAccountCode",
                    "Parent account must be non-postable"
                );
            }

            return;
        }


        // ====================================================
        // OTHERWISE CHECK EXISTING BUSINESS ACCOUNTS
        // ====================================================

        Account existingParent =
            existingByCode.get(parentCode);

        if (existingParent == null) {

            addError(
                errors,
                rowNumber,
                "parentAccountCode",
                "Parent account " + parentCode
                    + " was not found in this business "
                    + "or the uploaded file"
            );

            return;
        }


        // ====================================================
        // VALIDATE EXISTING PARENT ACCOUNT TYPE
        // ====================================================

        if (
            !accountType.equals(
                existingParent.getAccountType()
            )
        ) {

            addError(
                errors,
                rowNumber,
                "parentAccountCode",
                "Parent and child accounts must "
                    + "have the same account type"
            );
        }


        // ====================================================
        // REQUIRE A NON-POSTABLE EXISTING PARENT
        // ====================================================

        if (
            !Boolean.FALSE.equals(
                existingParent.getPostable()
            )
        ) {

            addError(
                errors,
                rowNumber,
                "parentAccountCode",
                "Parent account must be non-postable"
            );
        }
    }


    // ============================================================
    // 4. DETECT CIRCULAR ACCOUNT HIERARCHIES
    // ============================================================

    /**
     * Walk each uploaded account's parent chain.
     *
     * Example of an invalid hierarchy:
     *
     * 1000 -> 2000
     * 2000 -> 3000
     * 3000 -> 1000
     *
     * A repeated code in a parent chain indicates
     * a circular dependency.
     *
     * Existing database hierarchies are already
     * protected by PostgreSQL constraints.
     */
    private void validateHierarchyCycles(
            List<AccountImportRow> rows,
            Map<String, List<AccountImportRow>> uploadedByCode,
            List<AccountImportError> errors) {

        for (AccountImportRow startingRow : rows) {

            String startingCode =
                clean(startingRow.code());

            if (startingCode.isEmpty()) {
                continue;
            }


            // =================================================
            // SKIP AMBIGUOUS UPLOADED ACCOUNT CODES
            // =================================================

            List<AccountImportRow> startingMatches =
                uploadedByCode.get(startingCode);

            if (
                startingMatches == null
                || startingMatches.size() != 1
            ) {

                continue;
            }


            // =================================================
            // TRACK VISITED ACCOUNTS
            // =================================================

            Set<String> visited =
                new HashSet<>();

            String currentCode =
                startingCode;


            // =================================================
            // WALK PARENT RELATIONSHIPS
            // =================================================

            while (!currentCode.isEmpty()) {

                if (!visited.add(currentCode)) {

                    addError(
                        errors,
                        startingRow.rowNumber(),
                        "parentAccountCode",
                        "Parent account hierarchy "
                            + "contains a circular relationship"
                    );

                    break;
                }


                // =============================================
                // LOOK UP CURRENT UPLOADED ACCOUNT
                // =============================================

                List<AccountImportRow> currentMatches =
                    uploadedByCode.get(currentCode);

                if (
                    currentMatches == null
                    || currentMatches.size() != 1
                ) {

                    break;
                }


                // =============================================
                // FOLLOW THE PARENT ACCOUNT CODE
                // =============================================

                AccountImportRow current =
                    currentMatches.get(0);

                String parentCode =
                    clean(current.parentAccountCode());

                /**
                 * Stop when the parent is blank
                 * or belongs to the existing database
                 * rather than this uploaded file.
                 */
                if (
                    parentCode.isEmpty()
                    || !uploadedByCode.containsKey(
                        parentCode
                    )
                ) {

                    break;
                }

                currentCode = parentCode;
            }
        }
    }


    // ============================================================
    // 5. CHECK REPORTING GROUP COMPATIBILITY
    // ============================================================

    /**
     * Mirror LedgerBase's V7 reporting group rules.
     *
     * The database and AccountService independently
     * enforce these classifications during creation.
     */
    private boolean isValidReportingGroup(
            String accountType,
            String reportingGroup) {

        Set<String> allowedGroups =
            switch (accountType) {

                case "ASSET" -> Set.of(
                    "CURRENT_ASSET",
                    "NON_CURRENT_ASSET"
                );

                case "LIABILITY" -> Set.of(
                    "CURRENT_LIABILITY",
                    "NON_CURRENT_LIABILITY"
                );

                case "EQUITY" -> Set.of(
                    "CONTRIBUTED_CAPITAL",
                    "RETAINED_EARNINGS",
                    "OTHER_EQUITY"
                );

                case "REVENUE" -> Set.of(
                    "OPERATING_REVENUE",
                    "OTHER_INCOME"
                );

                case "EXPENSE" -> Set.of(
                    "COST_OF_GOODS_SOLD",
                    "OPERATING_EXPENSE",
                    "OTHER_EXPENSE"
                );

                default -> Set.of();
            };

        return allowedGroups.contains(
            reportingGroup
        );
    }


    // ============================================================
    // 6. PARSE BOOLEAN SPREADSHEET VALUES
    // ============================================================

    /**
     * Accept TRUE and FALSE without regard to case.
     *
     * Examples:
     *
     * TRUE  -> true
     * false -> false
     *
     * Unsupported values return null and generate
     * a row-level validation error.
     */
    private Boolean parseBoolean(String value) {

        String normalized = clean(value);

        if ("TRUE".equalsIgnoreCase(normalized)) {

            return Boolean.TRUE;
        }

        if ("FALSE".equalsIgnoreCase(normalized)) {

            return Boolean.FALSE;
        }

        return null;
    }


    // ============================================================
    // 7. NORMALIZE FIELD WHITESPACE
    // ============================================================

    /**
     * Remove surrounding whitespace for validation.
     *
     * Account codes are kept as Strings, so leading
     * zeros are never converted to numbers.
     *
     * The original imported values remain unchanged
     * inside AccountImportRow for display purposes.
     */
    private String clean(String value) {

        return value == null
            ? ""
            : value.strip();
    }


    // ============================================================
    // 8. ADD VALIDATION ERROR
    // ============================================================

    /**
     * Add one field-specific error.
     *
     * Multiple errors per spreadsheet row are allowed.
     */
    private void addError(
            List<AccountImportError> errors,
            int rowNumber,
            String field,
            String message) {

        errors.add(
            new AccountImportError(
                rowNumber,
                field,
                message
            )
        );
    }
}
