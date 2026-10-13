// ============================================================
// LEDGERBASE — MONEY UTILITIES
// ============================================================

/**
 * Financial calculations must avoid floating-point
 * rounding errors.
 *
 * We represent amounts as integer cents using bigint.
 *
 * Examples:
 *
 * "250.00" -> 25000n
 * "0.50"   -> 50n
 * "12.34"  -> 1234n
 *
 * The backend continues using Java BigDecimal,
 * which remains the authority for accounting rules.
 */

// ============================================================
// DATABASE AMOUNT LIMIT
// ============================================================

/**
 * PostgreSQL NUMERIC(18,2) permits at most 18
 * total decimal digits, including two cents digits.
 */
const MAX_AMOUNT_CENTS = 999999999999999999n

// ============================================================
// PARSE MONEY INPUT
// ============================================================

/**
 * Convert a decimal input string into integer cents.
 *
 * Returns null when the input is invalid.
 *
 * Accepted:
 * "250"
 * "250.00"
 * "0.50"
 *
 * Rejected:
 * "-10"
 * "12.345"
 * "abc"
 * ""
 */
export function parseMoneyToCents(
value: string,
): bigint | null {

const trimmed = value.trim()

// Accept digits and an optional decimal
// portion containing one or two digits.
if (!/^\d+(\.\d{1,2})?$/.test(trimmed)) {
    return null
}

const [wholePart, decimalPart = ''] =
    trimmed.split('.')

// Pad the cents portion to exactly two digits.
const centsPart = decimalPart.padEnd(2, '0')

// bigint performs exact integer arithmetic.
const cents =
    BigInt(wholePart) * 100n +
    BigInt(centsPart)

if (cents > MAX_AMOUNT_CENTS) {
    return null
}

return cents
}

// ============================================================
// CONVERT CENTS TO DECIMAL STRING
// ============================================================

/**
 * Convert exact integer cents into a decimal string.
 *
 * Examples:
 * 25000n -> "250.00"
 * 50n    -> "0.50"
 *
 * This is useful when preparing financial amounts
 * for API requests.
 */
export function centsToDecimalString(
cents: bigint,
): string {

const sign = cents < 0n ? '-' : ''
const absoluteCents = cents < 0n ? -cents : cents

const dollars = absoluteCents / 100n
const remainder = absoluteCents % 100n

return `${sign}${dollars}.${String(remainder).padStart(2, '0')}`
}

// ============================================================
// FORMAT USD AMOUNTS
// ============================================================

/**
 * Format integer cents for display in the UI.
 *
 * Examples:
 * 25000n -> "$250.00"
 * 123456n -> "$1,234.56"
 */
export function formatUsdCents(
cents: bigint,
): string {

const sign = cents < 0n ? '-' : ''
const absoluteCents = cents < 0n ? -cents : cents

const dollars = (absoluteCents / 100n)
    .toString()
    .replace(/\B(?=(\d{3})+(?!\d))/g, ',')

const remainder = String(
    absoluteCents % 100n,
).padStart(2, '0')

return `${sign}$${dollars}.${remainder}`
}
