package com.ledgerbase.backend.service;

import com.ledgerbase.backend.dto.AccountImportError;
import com.ledgerbase.backend.dto.AccountImportPreviewResponse;
import com.ledgerbase.backend.dto.AccountImportRow;

import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.model.Account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — ACCOUNT IMPORT VALIDATION SERVICE TESTS
// ============================================================

/**
 * Unit tests for Chart of Accounts import validation.
 *
 * MILESTONE 20 — ACCOUNTING IMPORT & EXPORT
 *
 * These tests verify that uploaded account data
 * is validated before any database writes occur.
 *
 * COVERAGE:
 *
 * 1. Valid parent-child account imports.
 * 2. Duplicate codes against existing accounts.
 * 3. Duplicate codes within an uploaded file.
 * 4. Invalid account types and reporting groups.
 * 5. Missing required fields and invalid statuses.
 * 6. Missing parent accounts.
 * 7. Incompatible existing parent accounts.
 * 8. Valid existing grouping accounts.
 * 9. Circular account hierarchies.
 * 10. Empty import files.
 * 11. Nonexistent businesses.
 * 12. Whitespace handling and original values.
 *
 * IMPORTANT:
 *
 * AccountService and BusinessMapper are mocked.
 *
 * No PostgreSQL changes occur during these tests.
 */
class AccountImportValidationServiceTest {

    // ========================================================
    // MOCKED DEPENDENCIES
    // ========================================================

    private AccountService accountService;

    private BusinessMapper businessMapper;

    private AccountImportValidationService validationService;


    // ========================================================
    // TEST SETUP
    // ========================================================

    @BeforeEach
    void setUp() {

        accountService = mock(AccountService.class);

        businessMapper = mock(BusinessMapper.class);

        validationService = new AccountImportValidationService(
            accountService,
            businessMapper
        );

        // ====================================================
        // DEFAULT TEST BUSINESS
        // ====================================================

        /**
         * Business 2 exists.
         *
         * Unless a test overrides this behavior,
         * its Chart of Accounts is initially empty.
         */
        when(businessMapper.existsById(2L))
            .thenReturn(true);

        when(accountService.getAccounts(2L))
            .thenReturn(List.of());
    }


    // ============================================================
    // TEST 1 — VALID ACCOUNT HIERARCHY
    // ============================================================

    @Test
    void acceptsValidAccountsWithUploadedParent() {

        AccountImportRow parent = row(
            2,
            "1000",
            "Current Assets",
            "ASSET",
            "CURRENT_ASSET",
            "",
            "TRUE",
            "FALSE"
        );

        AccountImportRow child = row(
            3,
            "0010",
            "Petty Cash",
            "ASSET",
            "CURRENT_ASSET",
            "1000",
            "TRUE",
            "TRUE"
        );

        AccountImportPreviewResponse preview =
            validationService.validate(
                2L,
                List.of(parent, child)
            );

        assertEquals(2L, preview.businessId());

        assertEquals(2, preview.totalRows());

        assertEquals(2, preview.validRows());

        assertEquals(0, preview.invalidRows());

        assertTrue(preview.readyToImport());

        assertTrue(preview.errors().isEmpty());

        assertEquals(
            "0010",
            preview.rows().get(1).code()
        );
    }


    // ============================================================
    // TEST 2 — DUPLICATE CODE IN POSTGRESQL
    // ============================================================

    @Test
    void rejectsAccountCodeAlreadyInDatabase() {

        // ====================================================
        // ARRANGE — EXISTING BUSINESS ACCOUNT
        // ====================================================

        Account existing = existingAccount(
            "1010",
            "ASSET",
            true
        );

        when(accountService.getAccounts(2L))
            .thenReturn(List.of(existing));

        AccountImportRow imported = row(
            2,
            "1010",
            "New Cash Account",
            "ASSET",
            "CURRENT_ASSET",
            "",
            "TRUE",
            "TRUE"
        );


        // ====================================================
        // ACT
        // ====================================================

        AccountImportPreviewResponse preview =
            validationService.validate(
                2L,
                List.of(imported)
            );


        // ====================================================
        // ASSERT
        // ====================================================

        assertFalse(preview.readyToImport());

        assertEquals(1, preview.invalidRows());

        assertTrue(
            hasError(
                preview,
                2,
                "code",
                "already exists"
            )
        );
    }


    // ============================================================
    // TEST 3 — DUPLICATE CODES INSIDE UPLOAD
    // ============================================================

    @Test
    void rejectsDuplicateCodesWithinUploadedFile() {

        AccountImportRow first = row(
            2,
            "4000",
            "Consulting Revenue",
            "REVENUE",
            "OPERATING_REVENUE",
            "",
            "TRUE",
            "TRUE"
        );

        AccountImportRow second = row(
            3,
            "4000",
            "Service Revenue",
            "REVENUE",
            "OPERATING_REVENUE",
            "",
            "TRUE",
            "TRUE"
        );

        AccountImportPreviewResponse preview =
            validationService.validate(
                2L,
                List.of(first, second)
            );

        assertFalse(preview.readyToImport());

        assertEquals(2, preview.invalidRows());

        assertTrue(
            hasError(
                preview,
                2,
                "code",
                "multiple times"
            )
        );

        assertTrue(
            hasError(
                preview,
                3,
                "code",
                "multiple times"
            )
        );
    }


    // ============================================================
    // TEST 4 — INVALID ACCOUNTING CLASSIFICATIONS
    // ============================================================

    @Test
    void rejectsInvalidTypesAndReportingGroups() {

        // ====================================================
        // INVALID REPORTING GROUP FOR ASSET
        // ====================================================

        AccountImportRow invalidGroup = row(
            2,
            "1010",
            "Cash",
            "ASSET",
            "OPERATING_REVENUE",
            "",
            "TRUE",
            "TRUE"
        );


        // ====================================================
        // INVALID FUNDAMENTAL ACCOUNT TYPE
        // ====================================================

        AccountImportRow invalidType = row(
            3,
            "9000",
            "Unknown Account",
            "INVALID_TYPE",
            "",
            "",
            "TRUE",
            "TRUE"
        );


        // ====================================================
        // ACT
        // ====================================================

        AccountImportPreviewResponse preview =
            validationService.validate(
                2L,
                List.of(invalidGroup, invalidType)
            );


        // ====================================================
        // ASSERT
        // ====================================================

        assertFalse(preview.readyToImport());

        assertEquals(2, preview.invalidRows());

        assertTrue(
            hasError(
                preview,
                2,
                "reportingGroup",
                "invalid for account type"
            )
        );

        assertTrue(
            hasError(
                preview,
                3,
                "accountType",
                "Invalid account type"
            )
        );
    }


    // ============================================================
    // TEST 5 — REQUIRED FIELDS AND BOOLEAN VALUES
    // ============================================================

    @Test
    void rejectsMissingFieldsAndInvalidStatuses() {

        AccountImportRow invalid = row(
            2,
            "",
            "",
            "ASSET",
            "",
            "",
            "Maybe",
            ""
        );

        AccountImportPreviewResponse preview =
            validationService.validate(
                2L,
                List.of(invalid)
            );

        assertFalse(preview.readyToImport());

        /**
         * One row has four validation errors.
         *
         * It still counts as only one invalid row.
         */
        assertEquals(1, preview.invalidRows());

        assertEquals(0, preview.validRows());

        assertTrue(
            hasError(
                preview,
                2,
                "code",
                "required"
            )
        );

        assertTrue(
            hasError(
                preview,
                2,
                "name",
                "required"
            )
        );

        assertTrue(
            hasError(
                preview,
                2,
                "active",
                "TRUE or FALSE"
            )
        );

        assertTrue(
            hasError(
                preview,
                2,
                "postable",
                "TRUE or FALSE"
            )
        );
    }


    // ============================================================
    // TEST 6 — MISSING PARENT ACCOUNT
    // ============================================================

    @Test
    void rejectsParentAccountThatDoesNotExist() {

        AccountImportRow child = row(
            2,
            "1010",
            "Cash",
            "ASSET",
            "CURRENT_ASSET",
            "9999",
            "TRUE",
            "TRUE"
        );

        AccountImportPreviewResponse preview =
            validationService.validate(
                2L,
                List.of(child)
            );

        assertFalse(preview.readyToImport());

        assertEquals(1, preview.invalidRows());

        assertTrue(
            hasError(
                preview,
                2,
                "parentAccountCode",
                "was not found"
            )
        );
    }


    // ============================================================
    // TEST 7 — INCOMPATIBLE EXISTING PARENT
    // ============================================================

    @Test
    void rejectsParentWithWrongTypeAndPostingStatus() {

        // ====================================================
        // ARRANGE — EXISTING POSTABLE LIABILITY ACCOUNT
        // ====================================================

        Account parent = existingAccount(
            "2000",
            "LIABILITY",
            true
        );

        when(accountService.getAccounts(2L))
            .thenReturn(List.of(parent));


        // ====================================================
        // ARRANGE — ASSET CHILD
        // ====================================================

        AccountImportRow child = row(
            2,
            "1010",
            "Cash",
            "ASSET",
            "CURRENT_ASSET",
            "2000",
            "TRUE",
            "TRUE"
        );


        // ====================================================
        // ACT
        // ====================================================

        AccountImportPreviewResponse preview =
            validationService.validate(
                2L,
                List.of(child)
            );


        // ====================================================
        // ASSERT
        // ====================================================

        assertFalse(preview.readyToImport());

        assertEquals(1, preview.invalidRows());

        assertTrue(
            hasError(
                preview,
                2,
                "parentAccountCode",
                "same account type"
            )
        );

        assertTrue(
            hasError(
                preview,
                2,
                "parentAccountCode",
                "non-postable"
            )
        );
    }


    // ============================================================
    // TEST 8 — VALID EXISTING PARENT ACCOUNT
    // ============================================================

    @Test
    void acceptsChildOfExistingGroupingAccount() {

        // ====================================================
        // ARRANGE — EXISTING NON-POSTABLE ASSET
        // ====================================================

        Account parent = existingAccount(
            "1000",
            "ASSET",
            false
        );

        when(accountService.getAccounts(2L))
            .thenReturn(List.of(parent));


        // ====================================================
        // ARRANGE — NEW CHILD ACCOUNT
        // ====================================================

        AccountImportRow child = row(
            2,
            "1010",
            "Operating Cash",
            "ASSET",
            "CURRENT_ASSET",
            "1000",
            "TRUE",
            "TRUE"
        );


        // ====================================================
        // ACT
        // ====================================================

        AccountImportPreviewResponse preview =
            validationService.validate(
                2L,
                List.of(child)
            );


        // ====================================================
        // ASSERT
        // ====================================================

        assertTrue(preview.readyToImport());

        assertEquals(1, preview.validRows());

        assertEquals(0, preview.invalidRows());

        assertTrue(preview.errors().isEmpty());
    }


    // ============================================================
    // TEST 9 — CIRCULAR ACCOUNT HIERARCHY
    // ============================================================

    @Test
    void rejectsCircularParentRelationships() {

        // ====================================================
        // ARRANGE — TWO ACCOUNTS REFERENCE EACH OTHER
        // ====================================================

        AccountImportRow first = row(
            2,
            "1000",
            "Assets Group A",
            "ASSET",
            "CURRENT_ASSET",
            "2000",
            "TRUE",
            "FALSE"
        );

        AccountImportRow second = row(
            3,
            "2000",
            "Assets Group B",
            "ASSET",
            "CURRENT_ASSET",
            "1000",
            "TRUE",
            "FALSE"
        );


        // ====================================================
        // ACT
        // ====================================================

        AccountImportPreviewResponse preview =
            validationService.validate(
                2L,
                List.of(first, second)
            );


        // ====================================================
        // ASSERT
        // ====================================================

        assertFalse(preview.readyToImport());

        assertEquals(2, preview.invalidRows());

        assertTrue(
            hasError(
                preview,
                2,
                "parentAccountCode",
                "circular relationship"
            )
        );

        assertTrue(
            hasError(
                preview,
                3,
                "parentAccountCode",
                "circular relationship"
            )
        );
    }


    // ============================================================
    // TEST 10 — EMPTY IMPORT FILE
    // ============================================================

    @Test
    void rejectsEmptyAccountImport() {

        AccountImportPreviewResponse preview =
            validationService.validate(
                2L,
                List.of()
            );

        assertFalse(preview.readyToImport());

        assertEquals(0, preview.totalRows());

        assertEquals(0, preview.validRows());

        assertEquals(0, preview.invalidRows());

        assertTrue(
            hasError(
                preview,
                0,
                "file",
                "no account rows"
            )
        );
    }


    // ============================================================
    // TEST 11 — NONEXISTENT BUSINESS
    // ============================================================

    @Test
    void rejectsImportForNonexistentBusiness() {

        // ====================================================
        // ARRANGE
        // ====================================================

        when(businessMapper.existsById(999L))
            .thenReturn(false);


        // ====================================================
        // ACT AND ASSERT
        // ====================================================

        assertThrows(
            BusinessNotFoundException.class,
            () -> validationService.validate(
                999L,
                List.of(
                    row(
                        2,
                        "1010",
                        "Cash",
                        "ASSET",
                        "CURRENT_ASSET",
                        "",
                        "TRUE",
                        "TRUE"
                    )
                )
            )
        );


        // ====================================================
        // VERIFY NO ACCOUNT QUERY
        // ====================================================

        verify(accountService, never())
            .getAccounts(anyLong());
    }


    // ============================================================
    // TEST 12 — WHITESPACE AND ORIGINAL VALUES
    // ============================================================

    @Test
    void acceptsWhitespaceButPreservesOriginalRows() {

        AccountImportRow imported = row(
            2,
            " 0010 ",
            " Petty Cash ",
            "ASSET",
            "CURRENT_ASSET",
            "",
            "true",
            "false"
        );

        AccountImportPreviewResponse preview =
            validationService.validate(
                2L,
                List.of(imported)
            );

        // ====================================================
        // VALIDATION USES NORMALIZED VALUES
        // ====================================================

        assertTrue(preview.readyToImport());

        assertEquals(1, preview.validRows());


        // ====================================================
        // PREVIEW PRESERVES ORIGINAL UPLOADED VALUES
        // ====================================================

        assertEquals(
            " 0010 ",
            preview.rows().get(0).code()
        );

        assertEquals(
            " Petty Cash ",
            preview.rows().get(0).name()
        );

        assertEquals(
            "true",
            preview.rows().get(0).active()
        );

        assertEquals(
            "false",
            preview.rows().get(0).postable()
        );
    }


    // ========================================================
    // HELPER — CREATE IMPORT ROW
    // ========================================================

    /**
     * Construct a spreadsheet row without creating
     * a physical CSV or Excel file.
     *
     * File parsing is tested separately.
     */
    private static AccountImportRow row(
            int rowNumber,
            String code,
            String name,
            String accountType,
            String reportingGroup,
            String parentAccountCode,
            String active,
            String postable) {

        return new AccountImportRow(
            rowNumber,
            code,
            name,
            accountType,
            reportingGroup,
            parentAccountCode,
            active,
            postable
        );
    }


    // ========================================================
    // HELPER — CREATE EXISTING ACCOUNT
    // ========================================================

    /**
     * Simulate an account already stored in
     * the selected business's PostgreSQL database.
     */
    private static Account existingAccount(
            String code,
            String accountType,
            boolean postable) {

        Account account = new Account();

        account.setBusinessId(2L);

        account.setCode(code);

        account.setAccountType(accountType);

        account.setPostable(postable);

        account.setActive(true);

        return account;
    }


    // ========================================================
    // HELPER — FIND A VALIDATION ERROR
    // ========================================================

    /**
     * Check whether an import preview contains
     * an expected error for a specific row.
     *
     * This avoids depending on the order in which
     * validation errors are collected.
     */
    private static boolean hasError(
            AccountImportPreviewResponse preview,
            int rowNumber,
            String field,
            String messageFragment) {

        return preview.errors()
            .stream()
            .anyMatch(error ->
                error.rowNumber() == rowNumber
                && error.field().equals(field)
                && error.message().contains(messageFragment)
            );
    }
}
