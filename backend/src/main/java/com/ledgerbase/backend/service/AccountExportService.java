package com.ledgerbase.backend.service;

import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.model.Account;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

// ============================================================
// LEDGERBASE — ACCOUNT EXPORT SERVICE
// ============================================================

/**
 * Generates downloadable Chart of Accounts files.
 *
 * MILESTONE 20:
 *
 * Supported formats:
 *
 * 1. CSV  — UTF-8 comma-separated values.
 * 2. XLSX — Native Microsoft Excel workbook.
 *
 * Both formats use the same underlying account data.
 *
 * IMPORTANT ACCOUNTING RULES:
 *
 * - Exports are read-only.
 * - Accounts belong to the requested business.
 * - Active and inactive accounts are included.
 * - Parent relationships use portable account codes.
 * - Account balances are not modified.
 * - No transactions are created or posted.
 */
@Service
public class AccountExportService {

    // ========================================================
    // EXPORT COLUMN DEFINITIONS
    // ========================================================

    /**
     * Keep CSV and Excel column headings identical.
     *
     * This shared format will also become the basis
     * for our future account import templates.
     */
    private static final String[] EXPORT_HEADERS = {
        "Account Code",
        "Account Name",
        "Account Type",
        "Reporting Group",
        "Parent Account Code",
        "Active",
        "Postable"
    };


    // ========================================================
    // DEPENDENCIES
    // ========================================================

    private final AccountService accountService;

    private final BusinessMapper businessMapper;


    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    public AccountExportService(
            AccountService accountService,
            BusinessMapper businessMapper) {

        this.accountService = accountService;
        this.businessMapper = businessMapper;
    }


    // ========================================================
    // 1. EXPORT ACCOUNTS AS CSV
    // ========================================================

    /**
     * Generate a UTF-8 CSV download.
     *
     * Includes the UTF-8 BOM for compatibility with
     * spreadsheet applications.
     *
     * Spreadsheet formula-like values are escaped
     * to reduce CSV formula injection risks.
     */
    @Transactional(readOnly = true)
    public byte[] exportAccountsCsv(Long businessId) {

        // ====================================================
        // LOAD AND VALIDATE BUSINESS DATA
        // ====================================================

        List<Account> accounts =
            loadBusinessAccounts(businessId);

        Map<Long, String> accountCodesById =
            buildAccountCodeLookup(accounts);


        // ====================================================
        // CREATE CSV CONTENT
        // ====================================================

        StringBuilder csv = new StringBuilder();

        csv.append('\uFEFF');

        appendCsvRow(csv, EXPORT_HEADERS);


        // ====================================================
        // APPEND ACCOUNT ROWS
        // ====================================================

        for (Account account : accounts) {

            appendCsvRow(
                csv,
                buildAccountRow(
                    account,
                    accountCodesById
                )
            );
        }


        // ====================================================
        // RETURN UTF-8 CSV BYTES
        // ====================================================

        return csv.toString().getBytes(
            StandardCharsets.UTF_8
        );
    }


    // ========================================================
    // 2. EXPORT ACCOUNTS AS EXCEL
    // ========================================================

    /**
     * Generate a native .xlsx workbook.
     *
     * APACHE POI:
     *
     * XSSFWorkbook creates an Excel workbook.
     *
     * Sheet represents one worksheet.
     *
     * Row represents one Excel row.
     *
     * Cell represents an individual spreadsheet cell.
     *
     * IMPORTANT:
     *
     * Every exported value is written as a STRING cell.
     *
     * This prevents Excel from automatically changing:
     *
     * "0010" into 10
     *
     * It also prevents account names resembling formulas
     * from being stored as executable Excel formulas.
     */
    @Transactional(readOnly = true)
    public byte[] exportAccountsExcel(Long businessId) {

        // ====================================================
        // STEP 1 — LOAD BUSINESS ACCOUNTS
        // ====================================================

        List<Account> accounts =
            loadBusinessAccounts(businessId);

        Map<Long, String> accountCodesById =
            buildAccountCodeLookup(accounts);


        // ====================================================
        // STEP 2 — CREATE EXCEL WORKBOOK
        // ====================================================

        /**
         * try-with-resources closes the workbook and
         * output stream automatically.
         *
         * Excel data is generated in memory.
         *
         * No temporary files or database changes
         * are required.
         */
        try (
            XSSFWorkbook workbook = new XSSFWorkbook();

            ByteArrayOutputStream outputStream =
                new ByteArrayOutputStream()
        ) {

            // =================================================
            // CREATE WORKSHEET
            // =================================================

            Sheet sheet = workbook.createSheet(
                "Chart of Accounts"
            );


            // =================================================
            // CREATE REUSABLE CELL STYLES
            // =================================================

            /**
             * Create styles once per workbook.
             *
             * Reusing styles is more efficient than
             * creating a separate style for every cell.
             */
            CellStyle headerStyle =
                createHeaderStyle(workbook);

            CellStyle standardRowStyle =
                createDataStyle(workbook, false);

            CellStyle alternateRowStyle =
                createDataStyle(workbook, true);


            // =================================================
            // STEP 3 — CREATE HEADER ROW
            // =================================================

            Row headerRow = sheet.createRow(0);

            headerRow.setHeightInPoints(30);

            for (
                int columnIndex = 0;
                columnIndex < EXPORT_HEADERS.length;
                columnIndex++
            ) {

                Cell cell = headerRow.createCell(
                    columnIndex,
                    CellType.STRING
                );

                cell.setCellValue(
                    EXPORT_HEADERS[columnIndex]
                );

                cell.setCellStyle(headerStyle);
            }


            // =================================================
            // STEP 4 — EXPORT ACCOUNT DATA
            // =================================================

            int excelRowIndex = 1;

            for (Account account : accounts) {

                // =============================================
                // CREATE ACCOUNT ROW
                // =============================================

                Row row = sheet.createRow(excelRowIndex);

                row.setHeightInPoints(23);


                // =============================================
                // RESOLVE EXPORTABLE ACCOUNT VALUES
                // =============================================

                String[] accountValues =
                    buildAccountRow(
                        account,
                        accountCodesById
                    );


                // =============================================
                // ALTERNATING ROW FORMATTING
                // =============================================

                CellStyle rowStyle =
                    excelRowIndex % 2 == 0
                        ? alternateRowStyle
                        : standardRowStyle;


                // =============================================
                // WRITE CELLS AS TEXT
                // =============================================

                for (
                    int columnIndex = 0;
                    columnIndex < accountValues.length;
                    columnIndex++
                ) {

                    Cell cell = row.createCell(
                        columnIndex,
                        CellType.STRING
                    );

                    cell.setCellValue(
                        accountValues[columnIndex]
                    );

                    cell.setCellStyle(rowStyle);
                }

                excelRowIndex++;
            }


            // =================================================
            // STEP 5 — SET READABLE COLUMN WIDTHS
            // =================================================

            /**
             * Excel column widths use units of 1/256
             * of a standard character.
             *
             * Fixed widths avoid platform-dependent
             * font measurement during export.
             */
            int[] columnWidths = {
                18,  // Account Code
                44,  // Account Name
                20,  // Account Type
                27,  // Reporting Group
                25,  // Parent Account Code
                15,  // Active
                15   // Postable
            };

            for (
                int columnIndex = 0;
                columnIndex < columnWidths.length;
                columnIndex++
            ) {

                sheet.setColumnWidth(
                    columnIndex,
                    columnWidths[columnIndex] * 256
                );
            }


            // =================================================
            // STEP 6 — KEEP HEADER VISIBLE
            // =================================================

            /**
             * Freeze the first row so column headings
             * remain visible while scrolling in Excel.
             */
            sheet.createFreezePane(0, 1);


            // =================================================
            // STEP 7 — ENABLE EXCEL FILTERS
            // =================================================

            /**
             * Users can filter accounts by type,
             * reporting group, or posting status.
             *
             * Only create the filter range when
             * there is at least one account row.
             */
            if (!accounts.isEmpty()) {

                sheet.setAutoFilter(
                    new CellRangeAddress(
                        0,
                        accounts.size(),
                        0,
                        EXPORT_HEADERS.length - 1
                    )
                );
            }


            // =================================================
            // STEP 8 — SERIALIZE WORKBOOK
            // =================================================

            workbook.write(outputStream);

            return outputStream.toByteArray();

        } catch (IOException exception) {

            throw new IllegalStateException(
                "Unable to generate Chart of Accounts Excel file",
                exception
            );
        }
    }


    // ========================================================
    // 3. LOAD BUSINESS ACCOUNTS
    // ========================================================

    /**
     * Shared retrieval method for CSV and Excel.
     *
     * Validate that the requested business exists
     * before retrieving its accounts.
     *
     * Reuse the existing AccountService and MyBatis
     * account queries.
     */
    private List<Account> loadBusinessAccounts(
            Long businessId) {

        if (
            businessId == null ||
            !businessMapper.existsById(businessId)
        ) {

            throw new BusinessNotFoundException(
                businessId
            );
        }

        return accountService.getAccounts(
            businessId
        );
    }


    // ========================================================
    // 4. BUILD ACCOUNT ID → CODE LOOKUP
    // ========================================================

    /**
     * Resolve database IDs to account codes.
     *
     * Example:
     *
     * Account ID: 42
     * Account Code: 1000
     *
     * Export files should contain "1000" rather
     * than the internal database identifier 42.
     */
    private Map<Long, String> buildAccountCodeLookup(
            List<Account> accounts) {

        Map<Long, String> codesById =
            new HashMap<>();

        for (Account account : accounts) {

            codesById.put(
                account.getId(),
                account.getCode()
            );
        }

        return codesById;
    }


    // ========================================================
    // 5. BUILD SHARED EXPORT ROW
    // ========================================================

    /**
     * Convert an Account into seven export values.
     *
     * Using the same method for CSV and XLSX keeps
     * both formats consistent.
     */
    private String[] buildAccountRow(
            Account account,
            Map<Long, String> accountCodesById) {

        // ====================================================
        // RESOLVE PARENT ACCOUNT CODE
        // ====================================================

        String parentAccountCode = "";

        if (account.getParentAccountId() != null) {

            parentAccountCode = accountCodesById.get(
                account.getParentAccountId()
            );

            if (parentAccountCode == null) {

                throw new IllegalStateException(
                    "Cannot export account "
                    + account.getCode()
                    + ": parent account was not found"
                );
            }
        }


        // ====================================================
        // RETURN EXPORT VALUES
        // ====================================================

        return new String[] {
            account.getCode(),
            account.getName(),
            account.getAccountType(),
            account.getReportingGroup(),
            parentAccountCode,
            formatBoolean(account.getActive()),
            formatBoolean(account.getPostable())
        };
    }


    // ========================================================
    // 6. CREATE EXCEL HEADER STYLE
    // ========================================================

    /**
     * Style Excel column headings.
     *
     * Use:
     *
     * - Dark blue background
     * - White bold text
     * - Teal bottom border
     * - Comfortable spacing and alignment
     */
    private CellStyle createHeaderStyle(
            Workbook workbook) {

        CellStyle style = workbook.createCellStyle();

        Font font = workbook.createFont();

        font.setBold(true);
        font.setFontName("Aptos");
        font.setFontHeightInPoints((short) 11);

        font.setColor(
            IndexedColors.WHITE.getIndex()
        );

        style.setFont(font);

        style.setFillForegroundColor(
            IndexedColors.DARK_BLUE.getIndex()
        );

        style.setFillPattern(
            FillPatternType.SOLID_FOREGROUND
        );

        style.setAlignment(
            HorizontalAlignment.LEFT
        );

        style.setVerticalAlignment(
            VerticalAlignment.CENTER
        );

        style.setBorderBottom(
            BorderStyle.MEDIUM
        );

        style.setBottomBorderColor(
            IndexedColors.TEAL.getIndex()
        );

        style.setIndention((short) 1);

        return style;
    }


    // ========================================================
    // 7. CREATE EXCEL DATA ROW STYLE
    // ========================================================

    /**
     * Use alternating backgrounds to improve
     * readability in larger Charts of Accounts.
     *
     * All cells use Excel's text number format.
     *
     * This is essential for preserving account
     * codes containing leading zeros.
     */
    private CellStyle createDataStyle(
            Workbook workbook,
            boolean alternateBackground) {

        CellStyle style = workbook.createCellStyle();

        Font font = workbook.createFont();

        font.setFontName("Aptos");
        font.setFontHeightInPoints((short) 11);

        font.setColor(
            IndexedColors.BLACK.getIndex()
        );

        style.setFont(font);

        style.setAlignment(
            HorizontalAlignment.LEFT
        );

        style.setVerticalAlignment(
            VerticalAlignment.CENTER
        );

        style.setIndention((short) 1);


        // ====================================================
        // EXPLICITLY FORMAT ALL VALUES AS TEXT
        // ====================================================

        style.setDataFormat(
            workbook
                .createDataFormat()
                .getFormat("@")
        );


        // ====================================================
        // ALTERNATING ROW BACKGROUND
        // ====================================================

        if (alternateBackground) {

            style.setFillForegroundColor(
                IndexedColors.GREY_25_PERCENT.getIndex()
            );

            style.setFillPattern(
                FillPatternType.SOLID_FOREGROUND
            );
        }

        return style;
    }


    // ========================================================
    // 8. APPEND A CSV ROW
    // ========================================================

    /**
     * Serialize a row using standard CSV quoting.
     *
     * Double quotes are escaped by doubling them.
     * Each value is enclosed in quotation marks.
     */
    private static void appendCsvRow(
            StringBuilder csv,
            String... values) {

        StringJoiner row = new StringJoiner(",");

        for (String value : values) {

            row.add(escapeCsvValue(value));
        }

        csv.append(row);
        csv.append("\r\n");
    }


    // ========================================================
    // 9. ESCAPE CSV VALUES
    // ========================================================

    /**
     * Escape commas, quotes, and line breaks.
     *
     * Prefix formula-like values with an apostrophe
     * before storing them in CSV.
     *
     * XLSX does not use this transformation because
     * its cells are explicitly written as text.
     */
    private static String escapeCsvValue(String value) {

        String safeValue =
            value == null ? "" : value;

        String trimmedStart =
            safeValue.stripLeading();

        if (!trimmedStart.isEmpty()) {

            char firstCharacter =
                trimmedStart.charAt(0);

            if (
                firstCharacter == '=' ||
                firstCharacter == '+' ||
                firstCharacter == '-' ||
                firstCharacter == '@'
            ) {

                safeValue = "'" + safeValue;
            }
        }

        return "\""
            + safeValue.replace("\"", "\"\"")
            + "\"";
    }


    // ========================================================
    // 10. FORMAT BOOLEAN VALUES
    // ========================================================

    /**
     * Export Boolean values as TRUE or FALSE.
     *
     * A null value remains blank.
     */
    private static String formatBoolean(
            Boolean value) {

        if (value == null) {
            return "";
        }

        return value ? "TRUE" : "FALSE";
    }
}
