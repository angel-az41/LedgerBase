package com.ledgerbase.backend.service;

import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.model.Account;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// ============================================================
// LEDGERBASE — ACCOUNT EXCEL EXPORT SERVICE TESTS
// ============================================================

/**
 * Unit tests for Chart of Accounts Excel export.
 *
 * MILESTONE 20 — ACCOUNTING IMPORT & EXPORT
 *
 * Apache POI generates an XLSX workbook.
 *
 * These tests independently open that workbook
 * and inspect the actual Excel cells.
 *
 * TEST COVERAGE:
 *
 * 1. Account data and parent relationships.
 * 2. Leading-zero account codes and text cell types.
 * 3. Spreadsheet formula safety.
 * 4. Empty Chart of Accounts.
 * 5. Nonexistent businesses.
 * 6. Missing parent account validation.
 *
 * Mocked dependencies prevent these tests from
 * changing the PostgreSQL development database.
 */
class AccountExcelExportServiceTest {

    // ========================================================
    // TEST DEPENDENCIES
    // ========================================================

    private AccountService accountService;

    private BusinessMapper businessMapper;

    private AccountExportService exportService;


    // ========================================================
    // TEST SETUP
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


    // ============================================================
    // TEST 1 — EXPORT ACCOUNTS AND HIERARCHY
    // ============================================================

    @Test
    void exportsAccountsWithCorrectColumnsAndParentCodes()
            throws IOException {

        // ====================================================
        // ARRANGE — PARENT GROUPING ACCOUNT
        // ====================================================

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

        // ====================================================
        // ARRANGE — CHILD POSTING ACCOUNT
        // ====================================================

        Account child = createAccount(
            11L,
            "1010",
            "Operating Cash",
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


        // ====================================================
        // ACT — GENERATE AND OPEN EXCEL WORKBOOK
        // ====================================================

        byte[] excelData =
            exportService.exportAccountsExcel(2L);

        try (
            XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(excelData)
            )
        ) {

            Sheet sheet = workbook.getSheet(
                "Chart of Accounts"
            );

            assertNotNull(sheet);


            // =================================================
            // VERIFY — HEADER COLUMNS
            // =================================================

            Row header = sheet.getRow(0);

            assertEquals(
                "Account Code",
                header.getCell(0).getStringCellValue()
            );

            assertEquals(
                "Account Name",
                header.getCell(1).getStringCellValue()
            );

            assertEquals(
                "Account Type",
                header.getCell(2).getStringCellValue()
            );

            assertEquals(
                "Reporting Group",
                header.getCell(3).getStringCellValue()
            );

            assertEquals(
                "Parent Account Code",
                header.getCell(4).getStringCellValue()
            );

            assertEquals(
                "Active",
                header.getCell(5).getStringCellValue()
            );

            assertEquals(
                "Postable",
                header.getCell(6).getStringCellValue()
            );


            // =================================================
            // VERIFY — PARENT ACCOUNT
            // =================================================

            Row parentRow = sheet.getRow(1);

            assertEquals(
                "1000",
                parentRow.getCell(0).getStringCellValue()
            );

            assertEquals(
                "Current Assets",
                parentRow.getCell(1).getStringCellValue()
            );

            assertEquals(
                "FALSE",
                parentRow.getCell(6).getStringCellValue()
            );


            // =================================================
            // VERIFY — CHILD ACCOUNT
            // =================================================

            Row childRow = sheet.getRow(2);

            assertEquals(
                "1010",
                childRow.getCell(0).getStringCellValue()
            );

            assertEquals(
                "Operating Cash",
                childRow.getCell(1).getStringCellValue()
            );

            /**
             * The parent reference must use "1000",
             * not the internal database ID "10".
             */
            assertEquals(
                "1000",
                childRow.getCell(4).getStringCellValue()
            );

            assertEquals(
                "TRUE",
                childRow.getCell(6).getStringCellValue()
            );


            // =================================================
            // VERIFY — EXCEL FORMATTING
            // =================================================

            /**
             * The header uses LedgerBase's navy
             * background and teal bottom border.
             */
            assertEquals(
                IndexedColors.DARK_BLUE.getIndex(),
                header.getCell(0)
                    .getCellStyle()
                    .getFillForegroundColor()
            );

            assertEquals(
                IndexedColors.TEAL.getIndex(),
                header.getCell(0)
                    .getCellStyle()
                    .getBottomBorderColor()
            );

            /**
             * The first row is frozen so its column
             * headings remain visible while scrolling.
             */
            assertNotNull(sheet.getPaneInformation());

            assertTrue(
                sheet.getPaneInformation().isFreezePane()
            );
        }

        verify(businessMapper).existsById(2L);

        verify(accountService).getAccounts(2L);
    }


    // ============================================================
    // TEST 2 — PRESERVE LEADING ZEROS AND FORMULA SAFETY
    // ============================================================

    @Test
    void writesAccountCodesAndFormulaLikeNamesAsText()
            throws IOException {

        // ====================================================
        // ARRANGE — ACCOUNT WITH LEADING ZEROS
        // ====================================================

        Account account = createAccount(
            20L,
            "0010",
            "=SUM(1,2)",
            "EXPENSE",
            "OPERATING_EXPENSE",
            null,
            true,
            true
        );

        when(businessMapper.existsById(2L))
            .thenReturn(true);

        when(accountService.getAccounts(2L))
            .thenReturn(List.of(account));


        // ====================================================
        // ACT — OPEN GENERATED WORKBOOK
        // ====================================================

        byte[] excelData =
            exportService.exportAccountsExcel(2L);

        try (
            XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(excelData)
            )
        ) {

            Sheet sheet = workbook.getSheetAt(0);

            Row accountRow = sheet.getRow(1);

            Cell codeCell = accountRow.getCell(0);

            Cell nameCell = accountRow.getCell(1);


            // =================================================
            // VERIFY — LEADING ZEROS
            // =================================================

            /**
             * Account codes must be stored as text.
             *
             * "0010" must not become numeric value 10.
             */
            assertEquals(
                CellType.STRING,
                codeCell.getCellType()
            );

            assertEquals(
                "0010",
                codeCell.getStringCellValue()
            );


            // =================================================
            // VERIFY — FORMULA SAFETY
            // =================================================

            /**
             * A formula-looking account name must be
             * stored as literal text.
             *
             * It must not become an executable
             * Excel formula cell.
             */
            assertEquals(
                CellType.STRING,
                nameCell.getCellType()
            );

            assertEquals(
                "=SUM(1,2)",
                nameCell.getStringCellValue()
            );
        }
    }


    // ============================================================
    // TEST 3 — EMPTY BUSINESS ACCOUNTS
    // ============================================================

    @Test
    void exportsHeaderOnlyWorkbookForEmptyBusiness()
            throws IOException {

        // ====================================================
        // ARRANGE — BUSINESS WITH NO ACCOUNTS
        // ====================================================

        when(businessMapper.existsById(3L))
            .thenReturn(true);

        when(accountService.getAccounts(3L))
            .thenReturn(List.of());


        // ====================================================
        // ACT — GENERATE EMPTY WORKBOOK
        // ====================================================

        byte[] excelData =
            exportService.exportAccountsExcel(3L);

        try (
            XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(excelData)
            )
        ) {

            Sheet sheet = workbook.getSheetAt(0);


            // =================================================
            // VERIFY — VALID HEADER-ONLY WORKBOOK
            // =================================================

            assertEquals(
                1,
                sheet.getPhysicalNumberOfRows()
            );

            assertEquals(
                "Account Code",
                sheet.getRow(0)
                    .getCell(0)
                    .getStringCellValue()
            );

            assertNull(sheet.getRow(1));
        }
    }


    // ============================================================
    // TEST 4 — REJECT NONEXISTENT BUSINESS
    // ============================================================

    @Test
    void rejectsExcelExportForNonexistentBusiness() {

        // ====================================================
        // ARRANGE — UNKNOWN BUSINESS
        // ====================================================

        when(businessMapper.existsById(999L))
            .thenReturn(false);


        // ====================================================
        // ACT AND ASSERT
        // ====================================================

        assertThrows(
            BusinessNotFoundException.class,
            () -> exportService.exportAccountsExcel(999L)
        );


        // ====================================================
        // VERIFY — ACCOUNT QUERY WAS NEVER CALLED
        // ====================================================

        verify(accountService, never())
            .getAccounts(anyLong());
    }


    // ============================================================
    // TEST 5 — REJECT MISSING PARENT ACCOUNT
    // ============================================================

    @Test
    void rejectsExcelExportWhenParentIsMissing() {

        // ====================================================
        // ARRANGE — INVALID PARENT REFERENCE
        // ====================================================

        Account account = createAccount(
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
            .thenReturn(List.of(account));


        // ====================================================
        // ACT — EXPECT VALIDATION FAILURE
        // ====================================================

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> exportService.exportAccountsExcel(2L)
        );


        // ====================================================
        // VERIFY — USEFUL ERROR MESSAGE
        // ====================================================

        assertTrue(
            error.getMessage().contains(
                "parent account was not found"
            )
        );
    }


    // ============================================================
    // HELPER — CREATE TEST ACCOUNT
    // ============================================================

    /**
     * Construct a test Account using the real
     * LedgerBase domain model.
     *
     * No SQL inserts or database connections
     * are required.
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
