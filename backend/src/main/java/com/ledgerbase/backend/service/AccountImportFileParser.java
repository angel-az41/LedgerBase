package com.ledgerbase.backend.service;

import com.ledgerbase.backend.dto.AccountImportRow;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;

import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

// ============================================================
// LEDGERBASE — ACCOUNT IMPORT FILE PARSER
// ============================================================

/**
 * Reads Chart of Accounts files in CSV or XLSX format.
 *
 * MILESTONE 20 — ACCOUNTING IMPORT & EXPORT
 *
 * Responsibilities:
 *
 * 1. Identify supported file formats.
 * 2. Read CSV and Excel spreadsheet contents.
 * 3. Verify the required column headings.
 * 4. Preserve account codes as text.
 * 5. Convert spreadsheet rows to AccountImportRow.
 * 6. Reject malformed files and Excel formulas.
 *
 * IMPORTANT:
 *
 * This service performs parsing only.
 *
 * It does not:
 *
 * - Validate accounting classifications.
 * - Check for duplicate account codes.
 * - Resolve parent accounts.
 * - Insert records into PostgreSQL.
 *
 * Those operations belong to the import
 * validation and confirmation services.
 */
@Service
public class AccountImportFileParser {

    // ========================================================
    // EXPECTED SPREADSHEET COLUMNS
    // ========================================================

    /**
     * Match the existing AccountExportService columns.
     *
     * For this initial implementation, uploads must
     * contain these seven columns in this order.
     */
    private static final String[] REQUIRED_HEADERS = {
        "Account Code",
        "Account Name",
        "Account Type",
        "Reporting Group",
        "Parent Account Code",
        "Active",
        "Postable"
    };


    // ========================================================
    // FILE PROCESSING LIMITS
    // ========================================================

    /**
     * Restrict uploaded files to 5 MB.
     *
     * Restrict the number of account rows to 5,000.
     *
     * These initial limits keep processing bounded.
     *
     * HTTP upload limits and additional Excel
     * security checks will also be configured
     * when we implement the REST endpoints.
     */
    private static final int MAX_FILE_BYTES =
        5 * 1024 * 1024;

    private static final int MAX_ACCOUNT_ROWS = 5000;


    // ========================================================
    // PUBLIC — PARSE UPLOADED FILE
    // ========================================================

    /**
     * Parse one uploaded Chart of Accounts file.
     *
     * Supported extensions:
     *
     * .csv
     * .xlsx
     *
     * @param filename Original uploaded filename.
     * @param fileBytes Uploaded file contents.
     * @return Parsed account rows.
     */
    public List<AccountImportRow> parse(
            String filename,
            byte[] fileBytes) {

        // ====================================================
        // STEP 1 — REQUIRE A NONEMPTY FILE
        // ====================================================

        if (
            filename == null ||
            filename.isBlank() ||
            fileBytes == null ||
            fileBytes.length == 0
        ) {

            throw new IllegalArgumentException(
                "A nonempty CSV or XLSX file is required"
            );
        }


        // ====================================================
        // STEP 2 — ENFORCE FILE SIZE LIMIT
        // ====================================================

        if (fileBytes.length > MAX_FILE_BYTES) {

            throw new IllegalArgumentException(
                "The uploaded file exceeds the 5 MB limit"
            );
        }


        // ====================================================
        // STEP 3 — IDENTIFY FILE FORMAT
        // ====================================================

        String normalizedFilename =
            filename.toLowerCase(Locale.ROOT);


        // ====================================================
        // STEP 4 — PARSE THE SELECTED FORMAT
        // ====================================================

        try {

            if (normalizedFilename.endsWith(".csv")) {

                return parseCsv(fileBytes);
            }

            if (normalizedFilename.endsWith(".xlsx")) {

                return parseExcel(fileBytes);
            }

        } catch (IOException exception) {

            throw new IllegalArgumentException(
                "Unable to read the uploaded spreadsheet",
                exception
            );
        }


        // ====================================================
        // STEP 5 — REJECT UNSUPPORTED FILE TYPES
        // ====================================================

        throw new IllegalArgumentException(
            "Unsupported file format. Use .csv or .xlsx"
        );
    }


    // ========================================================
    // PARSE CSV FILE
    // ========================================================

    /**
     * Read CSV records with Apache Commons CSV.
     *
     * RFC 4180 formatting correctly handles quoted
     * commas, quotation marks, and multiline fields.
     *
     * The first record must contain our headers.
     *
     * CSV row numbers represent logical records:
     * header = 1, first account = 2.
     *
     * A multiline CSV field may occupy additional
     * physical lines without changing its record number.
     */
    private List<AccountImportRow> parseCsv(
            byte[] fileBytes) throws IOException {

        // ====================================================
        // STEP 1 — DECODE UTF-8 CONTENT
        // ====================================================

        String content = new String(
            fileBytes,
            StandardCharsets.UTF_8
        );

        if (content.startsWith("\uFEFF")) {

            content = content.substring(1);
        }


        // ====================================================
        // STEP 2 — CONFIGURE CSV FORMAT
        // ====================================================

        CSVFormat format =
            CSVFormat.RFC4180
                .builder()
                .setIgnoreEmptyLines(true)
                .get();


        // ====================================================
        // STEP 3 — READ CSV RECORDS
        // ====================================================

        try (
            StringReader reader = new StringReader(content);

            CSVParser parser = format.parse(reader)
        ) {

            Iterator<CSVRecord> records =
                parser.iterator();


            // =================================================
            // REQUIRE A HEADER ROW
            // =================================================

            if (!records.hasNext()) {

                throw new IllegalArgumentException(
                    "The CSV file is empty"
                );
            }

            CSVRecord headerRecord = records.next();

            String[] headers = new String[
                headerRecord.size()
            ];

            for (int i = 0; i < headers.length; i++) {

                headers[i] = headerRecord.get(i);
            }

            validateHeaders(headers);


            // =================================================
            // PROCESS ACCOUNT DATA ROWS
            // =================================================

            List<AccountImportRow> rows =
                new ArrayList<>();

            while (records.hasNext()) {

                CSVRecord record = records.next();

                int rowNumber = Math.toIntExact(
                    record.getRecordNumber()
                );


                // =============================================
                // REJECT EXTRA COLUMNS
                // =============================================

                if (
                    record.size() > REQUIRED_HEADERS.length
                ) {

                    throw new IllegalArgumentException(
                        "CSV row " + rowNumber
                        + " contains too many columns"
                    );
                }


                // =============================================
                // READ SEVEN EXPECTED VALUES
                // =============================================

                String[] values = new String[
                    REQUIRED_HEADERS.length
                ];

                for (
                    int i = 0;
                    i < values.length;
                    i++
                ) {

                    values[i] =
                        i < record.size()
                            ? record.get(i)
                            : "";
                }


                // =============================================
                // SKIP COMPLETELY BLANK ROWS
                // =============================================

                if (isBlankRow(values)) {
                    continue;
                }


                // =============================================
                // ENFORCE ACCOUNT ROW LIMIT
                // =============================================

                enforceRowLimit(rows.size());


                // =============================================
                // CREATE IMPORT ROW
                // =============================================

                rows.add(
                    createImportRow(
                        rowNumber,
                        values
                    )
                );
            }

            return List.copyOf(rows);
        }
    }


    // ========================================================
    // PARSE EXCEL FILE
    // ========================================================

    /**
     * Read a native XLSX workbook using Apache POI.
     *
     * The workbook must contain one worksheet.
     *
     * DataFormatter reads displayed cell values,
     * including formatted numeric account codes.
     *
     * We reject actual formula cells rather than
     * calculating or executing spreadsheet formulas.
     */
    private List<AccountImportRow> parseExcel(
            byte[] fileBytes) throws IOException {

        try (
            XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(fileBytes)
            )
        ) {

            // =================================================
            // STEP 1 — REQUIRE ONE WORKSHEET
            // =================================================

            if (workbook.getNumberOfSheets() != 1) {

                throw new IllegalArgumentException(
                    "Excel import files must contain "
                    + "exactly one worksheet"
                );
            }

            Sheet sheet = workbook.getSheetAt(0);


            // =================================================
            // STEP 2 — READ COLUMN HEADERS
            // =================================================

            DataFormatter formatter = new DataFormatter(
                Locale.ROOT
            );

            Row headerRow = sheet.getRow(0);

            if (headerRow == null) {

                throw new IllegalArgumentException(
                    "The Excel header row is missing"
                );
            }

            String[] headers = readExcelRow(
                headerRow,
                formatter
            );

            validateHeaders(headers);


            // =================================================
            // STEP 3 — READ ACCOUNT ROWS
            // =================================================

            List<AccountImportRow> rows =
                new ArrayList<>();

            for (
                int rowIndex = 1;
                rowIndex <= sheet.getLastRowNum();
                rowIndex++
            ) {

                Row excelRow = sheet.getRow(
                    rowIndex
                );

                if (excelRow == null) {
                    continue;
                }

                String[] values = readExcelRow(
                    excelRow,
                    formatter
                );

                if (isBlankRow(values)) {
                    continue;
                }


                // =============================================
                // ENFORCE ACCOUNT ROW LIMIT
                // =============================================

                enforceRowLimit(rows.size());


                // =============================================
                // CREATE IMPORT ROW
                // =============================================

                rows.add(
                    createImportRow(
                        rowIndex + 1,
                        values
                    )
                );
            }

            return List.copyOf(rows);
        }
    }


    // ========================================================
    // HELPER — READ EXCEL ROW
    // ========================================================

    /**
     * Read seven expected spreadsheet values.
     *
     * Missing or blank cells become empty Strings.
     *
     * Extra nonempty cells are rejected.
     *
     * Formula cells are not accepted.
     */
    private String[] readExcelRow(
            Row row,
            DataFormatter formatter) {

        String[] values = new String[
            REQUIRED_HEADERS.length
        ];

        for (
            int i = 0;
            i < values.length;
            i++
        ) {

            values[i] = readExcelCell(
                row.getCell(i),
                formatter,
                row.getRowNum() + 1
            );
        }


        // ====================================================
        // CHECK FOR UNEXPECTED EXTRA COLUMNS
        // ====================================================

        for (
            int i = REQUIRED_HEADERS.length;
            i < row.getLastCellNum();
            i++
        ) {

            String extraValue = readExcelCell(
                row.getCell(i),
                formatter,
                row.getRowNum() + 1
            );

            if (!extraValue.isBlank()) {

                throw new IllegalArgumentException(
                    "Excel row "
                    + (row.getRowNum() + 1)
                    + " contains unexpected extra columns"
                );
            }
        }

        return values;
    }


    // ========================================================
    // HELPER — READ EXCEL CELL
    // ========================================================

    /**
     * Convert one Excel cell into a String.
     *
     * Examples:
     *
     * Text cell "0010" -> "0010"
     *
     * Boolean TRUE -> "TRUE"
     *
     * Empty cell -> ""
     *
     * Formula cells are rejected for safety.
     */
    private String readExcelCell(
            Cell cell,
            DataFormatter formatter,
            int rowNumber) {

        if (
            cell == null ||
            cell.getCellType() == CellType.BLANK
        ) {

            return "";
        }

        if (cell.getCellType() == CellType.FORMULA) {

            throw new IllegalArgumentException(
                "Excel row " + rowNumber
                + " contains a formula. "
                + "Formula cells are not allowed"
            );
        }

        return formatter.formatCellValue(cell);
    }


    // ========================================================
    // HELPER — VALIDATE COLUMN HEADINGS
    // ========================================================

    /**
     * Require exactly seven columns in the
     * same order as AccountExportService.
     *
     * A UTF-8 BOM is removed from the first
     * header when importing LedgerBase CSV files.
     *
     * Header whitespace is ignored.
     *
     * Account data values are not modified here.
     */
    private void validateHeaders(
            String[] actualHeaders) {

        if (
            actualHeaders.length !=
            REQUIRED_HEADERS.length
        ) {

            throw new IllegalArgumentException(
                "The import file must contain exactly "
                + REQUIRED_HEADERS.length
                + " columns"
            );
        }

        for (
            int i = 0;
            i < REQUIRED_HEADERS.length;
            i++
        ) {

            String actual = actualHeaders[i]
                .replace("\uFEFF", "")
                .strip();

            if (!REQUIRED_HEADERS[i].equals(actual)) {

                throw new IllegalArgumentException(
                    "Invalid column "
                    + (i + 1)
                    + ": expected '"
                    + REQUIRED_HEADERS[i]
                    + "' but found '"
                    + actual
                    + "'"
                );
            }
        }
    }


    // ========================================================
    // HELPER — IDENTIFY BLANK DATA ROW
    // ========================================================

    /**
     * Skip rows in which every field is blank.
     *
     * A partially completed row must be preserved
     * so the validator can report missing fields.
     */
    private boolean isBlankRow(String[] values) {

        return Arrays.stream(values)
            .allMatch(
                value -> value == null || value.isBlank()
            );
    }


    // ========================================================
    // HELPER — ENFORCE MAXIMUM ACCOUNT ROWS
    // ========================================================

    /**
     * Prevent excessively large spreadsheets
     * from entering the import workflow.
     */
    private void enforceRowLimit(int currentRows) {

        if (currentRows >= MAX_ACCOUNT_ROWS) {

            throw new IllegalArgumentException(
                "An import cannot exceed "
                + MAX_ACCOUNT_ROWS
                + " account rows"
            );
        }
    }


    // ========================================================
    // HELPER — CREATE ACCOUNT IMPORT ROW
    // ========================================================

    /**
     * Convert seven raw spreadsheet values into
     * the existing AccountImportRow DTO.
     *
     * The original text is preserved.
     *
     * Accounting validation comes later.
     */
    private AccountImportRow createImportRow(
            int rowNumber,
            String[] values) {

        return new AccountImportRow(
            rowNumber,
            values[0],
            values[1],
            values[2],
            values[3],
            values[4],
            values[5],
            values[6]
        );
    }
}
