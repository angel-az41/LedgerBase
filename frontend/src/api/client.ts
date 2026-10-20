// ============================================================
// LEDGERBASE — SHARED API CLIENT
// ============================================================

/**
 * This module provides reusable functions for sending
 * HTTP requests from React to our Spring Boot backend.
 *
 * React components should not repeatedly implement
 * fetch(), JSON parsing, and HTTP error handling.
 *
 * Instead, each accounting feature uses these helpers.
 *
 * During local development, Vite forwards requests
 * beginning with /api to http://localhost:8080.
 */

// ============================================================
// HTTP ERROR HANDLING
// ============================================================

/**
 * Verify that an HTTP request succeeded.
 *
 * response.ok is true for HTTP status codes 200–299.
 *
 * If a request fails, try to read an error message
 * returned by the Spring Boot backend.
 */
async function ensureSuccessfulResponse(
  response: Response,
): Promise<void> {

  // A successful response requires no further checks.
  if (response.ok) {
    return
  }

  // Fall back to the HTTP status when no message exists.
  let errorMessage = `API request failed (${response.status})`

  try {

    // Read the backend response as JSON.
    const errorBody: unknown = await response.json()

    /**
     * Spring Boot may return an error object.
     *
     * We only use its "error" property when it
     * contains a meaningful string.
     */
    if (
      typeof errorBody === 'object' &&
      errorBody !== null &&
      'error' in errorBody &&
      typeof errorBody.error === 'string' &&
      errorBody.error.trim().length > 0
    ) {
      errorMessage = errorBody.error
    }

  } catch {

    // The response may be empty or may not contain JSON.
    // Keep the default error message in that case.
  }

  throw new Error(errorMessage)
}

// ============================================================
// JSON RESPONSE HANDLING
// ============================================================

/**
 * Convert a successful HTTP response into typed data.
 *
 * T represents the data structure we expect.
 *
 * Examples:
 *
 * T = Account[]
 * T = Account
 * T = LedgerTransaction[]
 *
 * TypeScript helps check how the returned data is used.
 *
 * Note: A TypeScript type does not validate JSON at
 * runtime. The backend remains responsible for
 * returning data that matches the API contract.
 */
async function parseJsonResponse<T>(
  response: Response,
): Promise<T> {

  await ensureSuccessfulResponse(response)

  return (await response.json()) as T
}

// ============================================================
// HTTP GET
// ============================================================

/**
 * Retrieve JSON data from Spring Boot.
 *
 * GET requests should only retrieve information,
 * not modify financial records.
 */
export async function getJson<T>(
  path: string,
): Promise<T> {

  const response = await fetch(path, {
    method: 'GET',
    headers: {
      Accept: 'application/json',
    },
  })

  return parseJsonResponse<T>(response)
}

// ============================================================
// HTTP POST
// ============================================================

/**
 * Send JSON data to the backend.
 *
 * POST will be used to create:
 * - General ledger accounts
 * - Financial transactions
 * - Draft journal entries
 *
 * TResponse describes the returned data.
 * TRequest describes the submitted data.
 */
export async function postJson<TResponse, TRequest>(
  path: string,
  body: TRequest,
): Promise<TResponse> {

  const response = await fetch(path, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  })

  return parseJsonResponse<TResponse>(response)
}

// ============================================================
// HTTP PATCH
// ============================================================

/**
 * Send an update request to Spring Boot.
 *
 * The request body is optional.
 *
 * Current examples:
 *
 * Rename account:
 * PATCH /api/v1/businesses/1/accounts/6
 * Body: { "name": "Operating Checking Account" }
 *
 * Deactivate account:
 * PATCH /api/v1/businesses/1/accounts/6/deactivate
 *
 * Both endpoints return HTTP 204 No Content.
 *
 * Therefore, we must not attempt to parse their
 * successful responses as JSON.
 */
export async function patchNoContent(
  path: string,
  body?: object,
): Promise<void> {

  const headers: Record<string, string> = {
    Accept: 'application/json',
  }

  // Only declare JSON content when a body is present.
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  const response = await fetch(path, {
    method: 'PATCH',
    headers,
    body:
      body === undefined
        ? undefined
        : JSON.stringify(body),
  })

  // HTTP 204 is a successful response without JSON.
  await ensureSuccessfulResponse(response)
}

 // ============================================================
 // HTTP MULTIPART POST
 // ============================================================

 /**
  * Upload multipart/form-data to the Spring Boot backend.
  *
  * Unlike postJson(), this helper sends FormData.
  *
  * Used for:
  *
  * - CSV account import preview
  * - XLSX account import preview
  * - Confirmed account imports
  *
  * IMPORTANT:
  *
  * Do not manually set the Content-Type header.
  *
  * The browser automatically adds the correct
  * multipart/form-data boundary.
  *
  * Manually setting Content-Type would prevent
  * Spring Boot from correctly parsing the upload.
  *
  * Reuse the existing shared JSON response and
  * HTTP error handling functions.
  */
 export async function postMultipart<TResponse>(
   path: string,
   formData: FormData,
 ): Promise<TResponse> {

   // ==========================================================
   // SEND MULTIPART REQUEST
   // ==========================================================

   const response = await fetch(path, {

     method: 'POST',

     headers: {
       Accept: 'application/json',
     },

     body: formData,

   })

   // ==========================================================
   // VALIDATE RESPONSE AND PARSE JSON
   // ==========================================================

   return parseJsonResponse<TResponse>(response)
 }
