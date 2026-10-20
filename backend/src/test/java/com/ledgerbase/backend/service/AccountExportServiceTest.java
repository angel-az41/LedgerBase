package com.ledgerbase.backend.service;

import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.model.Account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — ACCOUNT CSV EXPORT SERVICE TESTS
// ============================================================

/**
 * Unit tests for Chart of Accounts CSV export.
 *
 * These tests use mocked dependencies so they do not
 * modify PostgreSQL or depend on existing demo data.
 *
 * Coverage:
 *
 * 1. CSV headers and account fields.
 * 2. Parent account code resolution.
 * 3. Leading-zero account codes.
 * 4. CSV escaping and spreadsheet formula protection.
 * 5. Missing businesses.
 * 6. Missing parent accounts.
 * 7. Empty Chart of Accounts.
 */
class AccountExportServiceTest {

    // ========================================================
    // TEST DEPENDENCIES
    // ========================================================

    private AccountService accountService;

    private BusinessMapper businessMapper;

    private AccountExportService exportService;


    // ========================================================
    // SET UP EACH TEST
    // ========================================================

    @BeforeEach
    void setUp() {

        accountService = mock(AccountService.class);

        businessMapper = mock(BusinessMapper.class);

        exportService = new AccountExportService(
            accountService,
            businessMapper
        );
    }


    // ========================================================
    // TEST 1 — CSV CONTENT AND PARENT ACCOUNT CODE
    // ========================================================

    @Test
    void exportsAccountsWithParentCodesAndAllColumns() {

        // Parent grouping account.
        Account parent = createAccount(
            10L,
            "1000",
            "Current Assets",
            "ASSET",
            "CURRENT_ASSET",
            null,
            true,
            false
        );

        // Child account with a leading-zero code.
        Account child = createAccount(
            11L,
            "0010",
            "Cash",
            "ASSET",
            "CURRENT_ASSET",
            10L,
            true,
            true
        );

        when(businessMapper.existsById(2L))
            .thenReturn(true);

        when(accountService.getAccounts(2L))
            .thenReturn(List.of(parent, child));

        // Generate CSV.
        byte[] result = exportService.exportAccountsCsv(2L);

        String csv = new String(
            result,
            StandardCharsets.UTF_8
        );

        // UTF-8 BOM and correct column headers.
        assertTrue(csv.startsWith(
            "\uFEFF"
            + "\"Account Code\",\"Account Name\","
            + "\"Account Type\",\"Reporting Group\","
            + "\"Parent Account Code\",\"Active\","
            + "\"Postable\"\r\n"
        ));

        // Parent account is non-postable.
        assertTrue(csv.contains(
            "\"1000\",\"Current Assets\",\"ASSET\","
            + "\"CURRENT_ASSET\",\"\",\"TRUE\",\"FALSE\""
        ));

        // Child references parent by code, not database ID.
        // Leading zeros must remain intact.
        assertTrue(csv.contains(
            "\"0010\",\"Cash\",\"ASSET\","
            + "\"CURRENT_ASSET\",\"1000\",\"TRUE\",\"TRUE\""
        ));

        verify(businessMapper).existsById(2L);
        verify(accountService).getAccounts(2L);
    }


    // ========================================================
    // TEST 2 — CSV ESCAPING AND FORMULA PROTECTION
    // ========================================================

    @Test
    void escapesSpecialCharactersAndSpreadsheetFormulas() {

        Account specialCharacters = createAccount(
            20L,
            "5000",
            "Office, \"Main\"\nExpense",
            "EXPENSE",
            "OPERATING_EXPENSE",
            null,
            true,
            true
        );

        Account formulaLikeName = createAccount(
            21L,
            "5100",
            "=SUM(1,2)",
            "EXPENSE",
            null,
            null,
            true,
            true
        );

        when(businessMapper.existsById(2L))
            .thenReturn(true);

        when(accountService.getAccounts(2L))
            .thenReturn(
                List.of(specialCharacters, formulaLikeName)
            );

        String csv = new String(
            exportService.exportAccountsCsv(2L),
            StandardCharsets.UTF_8
        );

        // CSV doubles embedded quotation marks.
        // Commas and newlines remain inside quoted cells.
        assertTrue(csv.contains(
            "\"Office, \"\"Main\"\"\nExpense\""
        ));

        // Formula-like text receives a protective apostrophe.
        assertTrue(csv.contains(
            "\"'=SUM(1,2)\""
        ));

        // Null reporting group exports as an empty field.
        assertTrue(csv.contains(
            "\"5100\",\"'=SUM(1,2)\",\"EXPENSE\",\"\""
        ));
    }


    // ========================================================
    // TEST 3 — REJECT UNKNOWN BUSINESSES
    // ========================================================

    @Test
    void rejectsExportForNonexistentBusiness() {

        when(businessMapper.existsById(999L))
            .thenReturn(false);

        assertThrows(
            BusinessNotFoundException.class,
            () -> exportService.exportAccountsCsv(999L)
        );

        // No account retrieval should take place.
        verify(accountService, never())
            .getAccounts(anyLong());
    }


    // ========================================================
    // TEST 4 — DETECT MISSING PARENT ACCOUNTS
    // ========================================================

    @Test
    void rejectsExportWhenParentAccountIsMissing() {

        Account orphan = createAccount(
            30L,
            "1010",
            "Cash",
            "ASSET",
            "CURRENT_ASSET",
            999L,
            true,
            true
        );

        when(businessMapper.existsById(2L))
            .thenReturn(true);

        when(accountService.getAccounts(2L))
            .thenReturn(List.of(orphan));

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> exportService.exportAccountsCsv(2L)
        );

        assertTrue(
            error.getMessage().contains(
                "parent account was not found"
            )
        );
    }


    // ========================================================
    // TEST 5 — ALLOW EMPTY CHART OF ACCOUNTS
    // ========================================================

    @Test
    void exportsHeadersForBusinessWithoutAccounts() {

        when(businessMapper.existsById(3L))
            .thenReturn(true);

        when(accountService.getAccounts(3L))
            .thenReturn(List.of());

        String csv = new String(
            exportService.exportAccountsCsv(3L),
            StandardCharsets.UTF_8
        );

        // The file is still valid and has its header row.
        assertTrue(csv.contains(
            "\"Account Code\",\"Account Name\""
        ));

        assertFalse(csv.contains("\"1010\""));
    }


    // ========================================================
    // HELPER — CREATE AN ACCOUNT FOR TESTING
    // ========================================================

    /**
     * Build test accounts without requiring PostgreSQL.
     *
     * Every field here mirrors an existing Account
     * property used by the export service.
     */
    private static Account createAccount(
            Long id,
            String code,
            String name,
            String accountType,
            String reportingGroup,
            Long parentAccountId,
            boolean active,
            boolean postable) {

        Account account = new Account();

        account.setId(id);
        account.setBusinessId(2L);
        account.setCode(code);
        account.setName(name);
        account.setAccountType(accountType);
        account.setReportingGroup(reportingGroup);
        account.setParentAccountId(parentAccountId);
        account.setActive(active);
        account.setPostable(postable);

        return account;
    }
}
