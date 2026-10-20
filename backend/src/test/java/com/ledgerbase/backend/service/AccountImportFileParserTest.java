package com.ledgerbase.backend.service;

import com.ledgerbase.backend.dto.AccountImportRow;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — ACCOUNT IMPORT FILE PARSER TESTS
// ============================================================

/**
 * Unit tests for CSV and Excel account file parsing.
 *
 * MILESTONE 20 — FINANCIAL DATA IMPORT
 *
 * These tests verify:
 *
 * 1. LedgerBase CSV files with UTF-8 BOM.
 * 2. Quoted CSV fields and multiline account names.
 * 3. Excel account codes stored as text.
 * 4. Excel account codes with numeric formatting.
 * 5. Rejection of Excel formula cells.
 * 6. Rejection of incorrect CSV headers.
 * 7. Rejection of unexpected CSV columns.
 * 8. Empty Excel worksheets.
 * 9. Unsupported file formats.
 *
 * IMPORTANT:
 *
 * These are parser-only tests.
 *
 * They do not access PostgreSQL, create accounts,
 * or modify financial records.
 */
class AccountImportFileParserTest {

    // ========================================================
    // EXPECTED CSV HEADER
    // ========================================================

    private static final String CSV_HEADER =
        "\"Account Code\","
        + "\"Account Name\","
        + "\"Account Type\","
        + "\"Reporting Group\","
        + "\"Parent Account Code\","
        + "\"Active\","
        + "\"Postable\"";


    // ========================================================
    // EXPECTED EXCEL HEADERS
    // ========================================================

    private static final String[] EXCEL_HEADERS = {
        "Account Code",
        "Account Name",
        "Account Type",
        "Reporting Group",
        "Parent Account Code",
        "Active",
        "Postable"
    };


    // ========================================================
    // TEST DEPENDENCY
    // ========================================================

    private AccountImportFileParser parser;


    // ========================================================
    // SETUP
    // ========================================================

    @BeforeEach
    void setUp() {

        parser = new AccountImportFileParser();
    }


    // ============================================================
    // TEST 1 — PARSE CSV WITH BOM AND LEADING ZEROS
    // ============================================================

    @Test
    void parsesCsvWithBomAndPreservesLeadingZeros() {

        // ====================================================
        // ARRANGE — LEDGERBASE-STYLE CSV
        // ====================================================

        String csv =
            "\uFEFF"
            + CSV_HEADER
            + "\r\n"
            + "\"0010\",\"Cash, East\","
            + "\"ASSET\",\"CURRENT_ASSET\","
            + "\"1000\",\"TRUE\",\"TRUE\""
            + "\r\n";

        // ====================================================
        // ACT — PARSE CSV
        // ====================================================

        List<AccountImportRow> rows = parser.parse(
            "accounts.csv",
            csv.getBytes(StandardCharsets.UTF_8)
        );

        // ====================================================
        // ASSERT — CORRECT ACCOUNT INFORMATION
        // ====================================================

        assertEquals(1, rows.size());

        AccountImportRow account = rows.get(0);

        assertEquals(2, account.rowNumber());

        assertEquals("0010", account.code());

        assertEquals("Cash, East", account.name());

        assertEquals("ASSET", account.accountType());

        assertEquals(
            "CURRENT_ASSET",
            account.reportingGroup()
        );

        assertEquals(
            "1000",
            account.parentAccountCode()
        );

        assertEquals("TRUE", account.active());

        assertEquals("TRUE", account.postable());
    }


    // ============================================================
    // TEST 2 — PARSE QUOTED CSV FIELDS
    // ============================================================

    @Test
    void parsesEscapedQuotesAndMultilineAccountNames() {

        // ====================================================
        // ARRANGE — CSV WITH SPECIAL CHARACTERS
        // ====================================================

        String csv =
            CSV_HEADER
            + "\r\n"
            + "\"5100\","
            + "\"Office \"\"Main\"\"\nExpense\","
            + "\"EXPENSE\","
            + "\"OPERATING_EXPENSE\","
            + "\"\","
            + "\"TRUE\","
            + "\"TRUE\""
            + "\r\n";

        // ====================================================
        // ACT
        // ====================================================

        List<AccountImportRow> rows = parser.parse(
            "accounts.csv",
            csv.getBytes(StandardCharsets.UTF_8)
        );

        // ====================================================
        // ASSERT
        // ====================================================

        assertEquals(1, rows.size());

        assertEquals(
            "Office \"Main\"\nExpense",
            rows.get(0).name()
        );

        assertEquals(
            "5100",
            rows.get(0).code()
        );
    }


    // ============================================================
    // TEST 3 — PRESERVE EXCEL TEXT ACCOUNT CODES
    // ============================================================

    @Test
    void parsesExcelTextCellsWithoutLosingZeros()
            throws IOException {

        // ====================================================
        // ARRANGE — CREATE XLSX ACCOUNT ROW
        // ====================================================

        byte[] excelData = createWorkbook(
            (workbook, sheet) -> {

                addTextRow(
                    sheet,
                    1,
                    "0010",
                    "Petty Cash",
                    "ASSET",
                    "CURRENT_ASSET",
                    "1000",
                    "TRUE",
                    "TRUE"
                );
            }
        );

        // ====================================================
        // ACT
        // ====================================================

        List<AccountImportRow> rows = parser.parse(
            "accounts.xlsx",
            excelData
        );

        // ====================================================
        // ASSERT
        // ====================================================

        assertEquals(1, rows.size());

        AccountImportRow account = rows.get(0);

        assertEquals(2, account.rowNumber());

        assertEquals("0010", account.code());

        assertEquals("Petty Cash", account.name());

        assertEquals(
            "1000",
            account.parentAccountCode()
        );

        assertEquals("TRUE", account.postable());
    }


    // ============================================================
    // TEST 4 — READ FORMATTED NUMERIC EXCEL CODES
    // ============================================================

    @Test
    void readsDisplayedNumericAccountCode()
            throws IOException {

        // ====================================================
        // ARRANGE — NUMERIC CELL WITH 0000 FORMAT
        // ====================================================

        byte[] excelData = createWorkbook(
            (workbook, sheet) -> {

                Row row = sheet.createRow(1);

                Cell codeCell = row.createCell(0);

                codeCell.setCellValue(10);

                /**
                 * Excel stores the number 10,
                 * but displays it as 0010.
                 *
                 * DataFormatter should preserve
                 * the displayed account code.
                 */
                CellStyle codeStyle =
                    workbook.createCellStyle();

                codeStyle.setDataFormat(
                    workbook
                        .createDataFormat()
                        .getFormat("0000")
                );

                codeCell.setCellStyle(codeStyle);

                row.createCell(1)
                    .setCellValue("Petty Cash");

                row.createCell(2)
                    .setCellValue("ASSET");
            }
        );

        // ====================================================
        // ACT
        // ====================================================

        List<AccountImportRow> rows = parser.parse(
            "accounts.xlsx",
            excelData
        );

        // ====================================================
        // ASSERT
        // ====================================================

        assertEquals(1, rows.size());

        assertEquals(
            "0010",
            rows.get(0).code()
        );
    }


    // ============================================================
    // TEST 5 — REJECT EXCEL FORMULAS
    // ============================================================

    @Test
    void rejectsExcelFormulaCells()
            throws IOException {

        // ====================================================
        // ARRANGE — FORMULA IN ACCOUNT NAME COLUMN
        // ====================================================

        byte[] excelData = createWorkbook(
            (workbook, sheet) -> {

                Row row = sheet.createRow(1);

                row.createCell(0)
                    .setCellValue("1010");

                row.createCell(1)
                    .setCellFormula("1+1");

                row.createCell(2)
                    .setCellValue("ASSET");
            }
        );

        // ====================================================
        // ACT AND ASSERT
        // ====================================================

        IllegalArgumentException exception =
            assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(
                    "accounts.xlsx",
                    excelData
                )
            );

        assertTrue(
            exception.getMessage().contains(
                "Formula cells are not allowed"
            )
        );
    }


    // ============================================================
    // TEST 6 — REJECT INCORRECT CSV HEADERS
    // ============================================================

    @Test
    void rejectsCsvWithInvalidColumnHeading() {

        // ====================================================
        // ARRANGE — CHANGE REQUIRED FIRST COLUMN
        // ====================================================

        String csv =
            CSV_HEADER.replace(
                "Account Code",
                "Ledger Code"
            )
            + "\r\n";

        // ====================================================
        // ACT AND ASSERT
        // ====================================================

        IllegalArgumentException exception =
            assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(
                    "accounts.csv",
                    csv.getBytes(
                        StandardCharsets.UTF_8
                    )
                )
            );

        assertTrue(
            exception.getMessage().contains(
                "Invalid column 1"
            )
        );
    }


    // ============================================================
    // TEST 7 — REJECT EXTRA CSV COLUMNS
    // ============================================================

    @Test
    void rejectsCsvRowsWithUnexpectedColumns() {

        // ====================================================
        // ARRANGE — EIGHT VALUES INSTEAD OF SEVEN
        // ====================================================

        String csv =
            CSV_HEADER
            + "\r\n"
            + "\"1010\",\"Cash\",\"ASSET\","
            + "\"CURRENT_ASSET\",\"\","
            + "\"TRUE\",\"TRUE\",\"EXTRA\""
            + "\r\n";

        // ====================================================
        // ACT AND ASSERT
        // ====================================================

        IllegalArgumentException exception =
            assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(
                    "accounts.csv",
                    csv.getBytes(
                        StandardCharsets.UTF_8
                    )
                )
            );

        assertTrue(
            exception.getMessage().contains(
                "too many columns"
            )
        );
    }


    // ============================================================
    // TEST 8 — PARSE EMPTY EXCEL WORKBOOK
    // ============================================================

    @Test
    void acceptsHeaderOnlyExcelWorkbook()
            throws IOException {

        // ====================================================
        // ARRANGE — HEADERS WITHOUT ACCOUNT ROWS
        // ====================================================

        byte[] excelData = createWorkbook(
            (workbook, sheet) -> {
                // No account data rows.
            }
        );

        // ====================================================
        // ACT
        // ====================================================

        List<AccountImportRow> rows = parser.parse(
            "accounts.xlsx",
            excelData
        );

        // ====================================================
        // ASSERT
        // ====================================================

        /**
         * Parsing is successful.
         *
         * The upcoming validation service will
         * mark an empty import as not importable.
         */
        assertTrue(rows.isEmpty());
    }


    // ============================================================
    // TEST 9 — REJECT UNSUPPORTED FILE FORMATS
    // ============================================================

    @Test
    void rejectsUnsupportedFileExtensions() {

        // ====================================================
        // ARRANGE
        // ====================================================

        byte[] content =
            "test".getBytes(StandardCharsets.UTF_8);

        // ====================================================
        // ACT AND ASSERT
        // ====================================================

        IllegalArgumentException exception =
            assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(
                    "accounts.xls",
                    content
                )
            );

        assertTrue(
            exception.getMessage().contains(
                "Unsupported file format"
            )
        );
    }


    // ========================================================
    // HELPER — CREATE AN EXCEL WORKBOOK
    // ========================================================

    /**
     * Generate real XLSX bytes for testing.
     *
     * The required headers are inserted automatically.
     *
     * Each test supplies additional worksheet content
     * through the callback.
     *
     * Apache POI closes all workbook resources.
     */
    private static byte[] createWorkbook(
            BiConsumer<XSSFWorkbook, Sheet> fillSheet)
            throws IOException {

        try (
            XSSFWorkbook workbook =
                new XSSFWorkbook();

            ByteArrayOutputStream output =
                new ByteArrayOutputStream()
        ) {

            Sheet sheet = workbook.createSheet(
                "Chart of Accounts"
            );

            // =================================================
            // ADD REQUIRED HEADER ROW
            // =================================================

            Row headerRow = sheet.createRow(0);

            for (
                int i = 0;
                i < EXCEL_HEADERS.length;
                i++
            ) {

                headerRow.createCell(i)
                    .setCellValue(EXCEL_HEADERS[i]);
            }

            // =================================================
            // ADD TEST-SPECIFIC ROWS
            // =================================================

            fillSheet.accept(workbook, sheet);

            // =================================================
            // WRITE THE WORKBOOK TO MEMORY
            // =================================================

            workbook.write(output);

            return output.toByteArray();
        }
    }


    // ========================================================
    // HELPER — ADD STRING CELLS TO AN EXCEL ROW
    // ========================================================

    /**
     * Write values explicitly as text.
     *
     * This mirrors the behavior of LedgerBase's
     * existing Excel export service.
     */
    private static void addTextRow(
            Sheet sheet,
            int rowIndex,
            String... values) {

        Row row = sheet.createRow(rowIndex);

        for (
            int i = 0;
            i < values.length;
            i++
        ) {

            row.createCell(i)
                .setCellValue(values[i]);
        }
    }
}
