// ============================================================
// LEDGERBASE API CLIENT
// ============================================================

/**
 * Shared HTTP client for communicating with Spring Boot.
 *
 * During local development, Vite forwards requests
 * beginning with /api to http://localhost:8080.
 *
 * This file centralizes:
 * - GET requests
 * - POST requests
 * - PATCH requests
 * - JSON response handling
 * - API error handling
 */

// ============================================================
// HTTP RESPONSE VALIDATION
// ============================================================

/**
 * Check whether a backend request succeeded.
 *
 * Successful HTTP status codes range from 200 to 299.
 *
 * If the request fails, attempt to extract the
 * error message returned by Spring Boot.
 *
 * This method works for both JSON responses
 * and responses without a body.
 */
async function ensureSuccessfulResponse(
  response: Response,
): Promise<void> {

  // A successful response requires no error handling.
  if (response.ok) {
    return
  }

  // Provide a default error message.
  let errorMessage = `API request failed (${response.status})`

  try {

    /**
     * Attempt to read Spring Boot's JSON error body.
     *
     * Example:
     *
     * {
     *   "error": "Account not found"
     * }
     */
    const errorBody: unknown = await response.json()

    if (
      typeof errorBody === 'object' &&
      errorBody !== null &&
      'error' in errorBody &&
      typeof errorBody.error === 'string'
    ) {
      errorMessage = errorBody.error
    }

  } catch {

    // Keep the default message if the response
    // is empty or does not contain valid JSON.
  }

  throw new Error(errorMessage)
}

// ============================================================
// JSON RESPONSE HANDLING
// ============================================================

/**
 * Read a successful JSON response.
 *
 * T represents the expected TypeScript data type.
 *
 * This function should only be used for endpoints
 * that return a JSON response body.
 *
 * Endpoints returning HTTP 204 No Content should
 * use ensureSuccessfulResponse() instead.
 */
async function parseJsonResponse<T>(
  response: Response,
): Promise<T> {

  // Check the response status before parsing JSON.
  await ensureSuccessfulResponse(response)

  // Convert the response body into a JavaScript value.
  return (await response.json()) as T
}

// ============================================================
// HTTP GET REQUESTS
// ============================================================

/**
 * Retrieve JSON data from Spring Boot.
 *
 * Example:
 *
 * getJson<Account[]>('/api/v1/businesses/1/accounts')
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
// HTTP POST REQUESTS
// ============================================================

/**
 * Send JSON data to Spring Boot.
 *
 * TResponse represents the returned data type.
 * TRequest represents the request body type.
 *
 * This is used when creating accounts
 * and will also support transaction creation.
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
// HTTP PATCH REQUESTS
// ============================================================

/**
 * Send a PATCH request to update an existing record.
 *
 * The request body is optional because our backend
 * currently supports two PATCH operations:
 *
 * 1. Rename an account:
 *
 * PATCH /api/v1/businesses/1/accounts/2
 *
 * Body:
 * {
 *   "name": "Updated Account Name"
 * }
 *
 * 2. Deactivate an account:
 *
 * PATCH /api/v1/businesses/1/accounts/2/deactivate
 *
 * No request body is required.
 *
 * Both endpoints return HTTP 204 No Content.
 *
 * Therefore, we validate the response status
 * without attempting to parse an empty JSON body.
 */
export async function patchNoContent(
  path: string,
  body?: object,
): Promise<void> {

  // Construct the request headers.
  const headers: Record<string, string> = {
    Accept: 'application/json',
  }

  /**
   * Only include Content-Type when sending JSON.
   *
   * The deactivate endpoint does not need
   * a request body.
   */
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  // Send the PATCH request.
  const response = await fetch(path, {
    method: 'PATCH',
    headers,
    body: body === undefined
      ? undefined
      : JSON.stringify(body),
  })

  /**
   * Check whether Spring Boot accepted the update.
   *
   * HTTP 204 is successful, even though the
   * response does not contain JSON.
   */
  await ensureSuccessfulResponse(response)
}
