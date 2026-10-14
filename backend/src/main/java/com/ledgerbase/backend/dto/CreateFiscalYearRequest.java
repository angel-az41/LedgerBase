package com.ledgerbase.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// ============================================================
// LEDGERBASE — CREATE FISCAL YEAR REQUEST DTO
// ============================================================

/**
 * Request body for creating a fiscal year.
 *
 * Example JSON:
 *
 * {
 *     "fiscalYearEndYear": 2027
 * }
 *
 * The supplied year represents the fiscal year
 * in which the accounting cycle ENDS.
 *
 * Examples:
 *
 * January-starting business:
 * FY2027 = Jan 1, 2027 - Dec 31, 2027
 *
 * April-starting business:
 * FY2027 = Apr 1, 2026 - Mar 31, 2027
 *
 * The client does not provide start or end dates.
 * FiscalYearService calculates them using the
 * business's configured fiscal-year starting month.
 *
 * Jakarta Bean Validation rejects missing or
 * out-of-range values before service execution.
 */
public record CreateFiscalYearRequest(

    @NotNull(message = "Fiscal year is required")
    @Min(
        value = 1900,
        message = "Fiscal year must be at least 1900"
    )
    @Max(
        value = 9999,
        message = "Fiscal year must not exceed 9999"
    )
    Integer fiscalYearEndYear

) {
}
