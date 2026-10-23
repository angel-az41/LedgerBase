// ============================================================
// LEDGERBASE — SHARED API CLIENT
// ============================================================

/**
* Provides reusable HTTP functions for communicating
* with the LedgerBase Spring Boot backend.
*
* PURPOSE:
*
* React components should not repeatedly implement
* fetch(), HTTP headers, JSON parsing, and error handling.
*
* Instead, feature-specific API modules use these helpers.
*
* SUPPORTED OPERATIONS:
*
* - GET requests returning JSON.
* - POST requests sending and receiving JSON.
* - PATCH requests returning no content.
* - Multipart file uploads.
* - CSV and Excel file downloads.
*
* AUTHENTICATION:
*
* After login, AuthProvider registers the verified
* JWT access token with this shared client.
*
* The client automatically attaches:
*
* Authorization: Bearer <access-token>
*
* to eligible LedgerBase backend requests.
*
* SECURITY:
*
* - Access tokens remain in JavaScript memory.
* - No tokens are written to localStorage.
* - No tokens are written to sessionStorage.
* - Bearer tokens are not attached to external URLs.
* - Login and refresh endpoints do not receive
*   automatically attached Bearer tokens.
*
* Refresh tokens are managed separately through
* the backend's HttpOnly authentication cookies.
*
* DEVELOPMENT:
*
* Vite proxies /api requests to Spring Cloud Gateway
* on port 8081.
*
* Gateway routes financial reporting and accounting
* requests to their respective backend services.
*/


// ============================================================
// IN-MEMORY ACCESS TOKEN
// ============================================================

/**
* Holds the current access JWT in module memory.
*
* This variable is intentionally private.
*
* React AuthProvider manages it through exported
* functions rather than accessing it directly.
*
* A full browser reload resets this value to null.
*/
let accessToken: string | null = null


// ============================================================
// REGISTER OR CLEAR ACCESS TOKEN
// ============================================================

/**
* Register the authenticated user's access token.
*
* Called by AuthProvider after login succeeds
* and the user's identity has been verified.
*
* Passing null clears the current token.
*
* The access token is never persisted to browser
* storage or written to a cookie.
*
* @param token verified access JWT or null
*/
export function setApiAccessToken(
  token: string | null,
): void {

  // ----------------------------------------------------------
  // CLEAR EXISTING TOKEN
  // ----------------------------------------------------------

  if (
    token === null ||
    token.trim().length === 0
  ) {

    accessToken = null
    return
  }


  // ----------------------------------------------------------
  // STORE TOKEN IN JAVASCRIPT MEMORY
  // ----------------------------------------------------------

  accessToken = token
}


// ============================================================
// CLEAR ACCESS TOKEN ONLY IF STILL CURRENT
// ============================================================

/**
* Clear the token only when it matches the
* currently registered access token.
*
* PURPOSE:
*
* An older authentication session may expire
* after the user has signed in again.
*
* Its expiration handler must not remove the
* newer session's access token.
*
* @param expectedToken token belonging to old session
*/
export function clearApiAccessTokenIfCurrent(
  expectedToken: string,
): void {

  // ----------------------------------------------------------
  // PREVENT STALE SESSION CLEANUP
  // ----------------------------------------------------------

  if (accessToken === expectedToken) {
    accessToken = null
  }
}


// ============================================================
// AUTHENTICATION ENDPOINT EXCLUSIONS
// ============================================================

/**
* These authentication routes must not receive
* an automatically attached access JWT.
*
* LOGIN:
*
* Uses email and password credentials.
*
* REFRESH:
*
* Uses the HttpOnly refresh cookie to obtain
* a replacement access JWT.
*
* LOGOUT:
*
* Uses the refresh cookie to revoke the
* PostgreSQL authentication session.
*
* The /auth/me endpoint is deliberately not
* excluded because it requires a Bearer JWT.
*
* Its API helper can also supply a specific
* Authorization header explicitly.
*/
const COOKIE_AUTH_ENDPOINTS = new Set<string>([
  '/api/v1/auth/login',
  '/api/v1/auth/refresh',
  '/api/v1/auth/logout',
])


// ============================================================
// BUILD SHARED HTTP REQUEST HEADERS
// ============================================================

/**
* Construct headers for backend requests.
*
* AUTHENTICATION RULES:
*
* - Attach a Bearer token when one is available.
* - Only attach it to relative /api/ paths.
* - Exclude cookie-based authentication endpoints.
* - Preserve explicitly provided Authorization headers.
*
* SECURITY:
*
* Requiring paths to begin with /api/ prevents
* automatic credential forwarding to external
* HTTP URLs.
*
* The API helpers should use relative LedgerBase
* backend paths rather than arbitrary URLs.
*
* @param path requested API path
* @param requestHeaders optional custom headers
* @returns complete request headers
*/
function buildRequestHeaders(
  path: string,
  requestHeaders: Record<string, string> = {},
): Record<string, string> {

  // ----------------------------------------------------------
  // ADD STANDARD HTTP HEADERS
  // ----------------------------------------------------------

  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...requestHeaders,
  }


  // ----------------------------------------------------------
  // CHECK FOR EXPLICIT AUTHORIZATION
  // ----------------------------------------------------------

  /**
  * HTTP header names are case-insensitive.
  *
  * Detect Authorization, authorization, and
  * other capitalization combinations.
  *
  * This preserves the existing getCurrentUser()
  * behavior, which supplies a newly issued JWT
  * before AuthProvider registers it globally.
  */
  const hasExplicitAuthorization =
    Object.keys(requestHeaders).some(
      (name) =>
        name.toLowerCase() === 'authorization',
    )


  // ----------------------------------------------------------
  // REQUIRE A RELATIVE LEDGERBASE API PATH
  // ----------------------------------------------------------

  const isBackendApiPath =
    path.startsWith('/api/')


  // ----------------------------------------------------------
  // IDENTIFY COOKIE-AUTHENTICATION ENDPOINTS
  // ----------------------------------------------------------

  /**
  * Remove a potential query string before
  * matching endpoint paths.
  *
  * For example:
  *
  * /api/v1/auth/login?source=web
  *
  * should still be identified as login.
  */
  const endpointPath =
    path.split('?')[0]

  const isCookieAuthEndpoint =
    COOKIE_AUTH_ENDPOINTS.has(endpointPath)


  // ----------------------------------------------------------
  // ATTACH VERIFIED ACCESS TOKEN
  // ----------------------------------------------------------

  if (
    accessToken !== null &&
    isBackendApiPath &&
    !isCookieAuthEndpoint &&
    !hasExplicitAuthorization
  ) {

    headers.Authorization =
      `Bearer ${accessToken}`
  }


  // ----------------------------------------------------------
  // RETURN COMPLETE HEADERS
  // ----------------------------------------------------------

  return headers
}


// ============================================================
// HTTP ERROR HANDLING
// ============================================================

/**
* Validate that an HTTP response was successful.
*
* response.ok is true for HTTP status codes 200–299.
*
* If the backend returns an unsuccessful response,
* attempt to extract a meaningful JSON error message.
*
* Otherwise, use a generic HTTP status message.
*
* AUTOMATIC TOKEN REFRESH:
*
* This helper currently does not retry requests
* or obtain replacement access tokens.
*
* Refresh-token rotation will be integrated later.
*
* @param response raw fetch response
*/
async function ensureSuccessfulResponse(
  response: Response,
): Promise<void> {

  // ----------------------------------------------------------
  // ACCEPT SUCCESSFUL RESPONSES
  // ----------------------------------------------------------

  if (response.ok) {
    return
  }


  // ----------------------------------------------------------
  // PREPARE DEFAULT ERROR MESSAGE
  // ----------------------------------------------------------

  let errorMessage =
    `API request failed (${response.status})`


  // ----------------------------------------------------------
  // READ OPTIONAL BACKEND ERROR RESPONSE
  // ----------------------------------------------------------

  try {

    const errorBody: unknown =
      await response.json()


    // --------------------------------------------------------
    // CHECK FOR A MEANINGFUL ERROR PROPERTY
    // --------------------------------------------------------

    if (
      typeof errorBody === 'object' &&
      errorBody !== null &&
      'error' in errorBody &&
      typeof errorBody.error === 'string' &&
      errorBody.error.trim().length > 0
    ) {

      errorMessage =
        errorBody.error
    }

  } catch {

    /**
    * Some backend responses contain no JSON body.
    *
    * Keep the default message instead of masking
    * the original HTTP failure with a parsing error.
    */

  }


  // ----------------------------------------------------------
  // PROPAGATE THE REQUEST ERROR
  // ----------------------------------------------------------

  throw new Error(errorMessage)
}


// ============================================================
// JSON RESPONSE HANDLING
// ============================================================

/**
* Parse a successful HTTP response as JSON.
*
* T is the expected response type defined by
* the feature-specific API module.
*
* Examples:
*
* - Account[]
* - Account
* - LedgerTransaction[]
*
* IMPORTANT:
*
* TypeScript checks the returned type at compile
* time but does not validate JSON at runtime.
*
* The backend API contract must remain consistent.
*/
async function parseJsonResponse<T>(
  response: Response,
): Promise<T> {

  // ----------------------------------------------------------
  // CHECK HTTP STATUS
  // ----------------------------------------------------------

  await ensureSuccessfulResponse(response)


  // ----------------------------------------------------------
  // PARSE JSON BODY
  // ----------------------------------------------------------

  return (await response.json()) as T
}


// ============================================================
// HTTP GET
// ============================================================

/**
* Retrieve typed JSON data from Spring Boot.
*
* Optional custom headers support endpoints that
* need to supply Authorization explicitly.
*
* Existing accounting API calls remain compatible.
*
* @param path backend API path
* @param requestHeaders optional HTTP headers
* @returns parsed JSON response
*/
export async function getJson<T>(
  path: string,
  requestHeaders: Record<string, string> = {},
): Promise<T> {

  // ----------------------------------------------------------
  // SEND GET REQUEST
  // ----------------------------------------------------------

  const response = await fetch(path, {
    method: 'GET',

    headers: buildRequestHeaders(
      path,
      requestHeaders,
    ),
  })


  // ----------------------------------------------------------
  // VALIDATE AND PARSE RESPONSE
  // ----------------------------------------------------------

  return parseJsonResponse<T>(response)
}


// ============================================================
// HTTP POST — JSON
// ============================================================

/**
* Send JSON data to Spring Boot.
*
* Used for:
*
* - User login.
* - Creating financial accounts.
* - Creating transactions and journal entries.
* - Other JSON-based accounting operations.
*
* The shared request-header builder attaches
* the Bearer token when required.
*
* @param path backend API path
* @param body JSON-serializable request data
* @returns parsed JSON response
*/
export async function postJson<TResponse, TRequest>(
  path: string,
  body: TRequest,
): Promise<TResponse> {

  // ----------------------------------------------------------
  // SEND JSON REQUEST
  // ----------------------------------------------------------

  const response = await fetch(path, {
    method: 'POST',

    headers: {
      ...buildRequestHeaders(path),
      'Content-Type': 'application/json',
    },

    body: JSON.stringify(body),
  })


  // ----------------------------------------------------------
  // VALIDATE AND PARSE RESPONSE
  // ----------------------------------------------------------

  return parseJsonResponse<TResponse>(response)
}


// ============================================================
// HTTP PATCH — NO CONTENT
// ============================================================

/**
* Send a partial update to Spring Boot.
*
* Used for operations such as:
*
* - Renaming an account.
* - Deactivating an account.
*
* The request body is optional.
*
* Successful updates may return HTTP 204 No Content.
*
* Therefore, no JSON parsing is attempted.
*
* @param path backend API path
* @param body optional JSON object
*/
export async function patchNoContent(
  path: string,
  body?: object,
): Promise<void> {

  // ----------------------------------------------------------
  // BUILD AUTHENTICATED HEADERS
  // ----------------------------------------------------------

  const headers =
    buildRequestHeaders(path)


  // ----------------------------------------------------------
  // ADD CONTENT TYPE ONLY WHEN NECESSARY
  // ----------------------------------------------------------

  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }


  // ----------------------------------------------------------
  // SEND PATCH REQUEST
  // ----------------------------------------------------------

  const response = await fetch(path, {
    method: 'PATCH',

    headers,

    body:
      body === undefined
        ? undefined
        : JSON.stringify(body),
  })


  // ----------------------------------------------------------
  // VALIDATE RESPONSE WITHOUT PARSING JSON
  // ----------------------------------------------------------

  await ensureSuccessfulResponse(response)
}


// ============================================================
// HTTP MULTIPART POST
// ============================================================

/**
* Upload FormData to Spring Boot.
*
* Used for:
*
* - CSV account import preview.
* - XLSX account import preview.
* - Confirmed account imports.
*
* AUTHENTICATION:
*
* The shared Bearer token is included when present.
*
* IMPORTANT:
*
* Do not manually set Content-Type for FormData.
*
* The browser must generate the appropriate
* multipart/form-data boundary.
*
* @param path backend upload endpoint
* @param formData file upload data
* @returns parsed JSON response
*/
export async function postMultipart<TResponse>(
  path: string,
  formData: FormData,
): Promise<TResponse> {

  // ----------------------------------------------------------
  // SEND MULTIPART REQUEST
  // ----------------------------------------------------------

  const response = await fetch(path, {
    method: 'POST',

    headers: buildRequestHeaders(path),

    body: formData,
  })


  // ----------------------------------------------------------
  // VALIDATE AND PARSE RESPONSE
  // ----------------------------------------------------------

  return parseJsonResponse<TResponse>(response)
}


// ============================================================
// HTTP FILE DOWNLOAD
// ============================================================

/**
* Download a binary file from Spring Boot.
*
* Used for:
*
* - CSV account exports.
* - XLSX Excel account exports.
*
* The request automatically includes the active
* Bearer token when authentication is available.
*
* Unlike getJson(), this function expects a Blob
* rather than a JSON response.
*
* @param path backend export endpoint
* @param fallbackFilename filename used when no
*        server-provided filename is available
*/
export async function downloadFile(
  path: string,
  fallbackFilename: string,
): Promise<void> {

  // ----------------------------------------------------------
  // REQUEST FILE WITH AUTHENTICATION
  // ----------------------------------------------------------

  const response = await fetch(path, {
    method: 'GET',

    headers: buildRequestHeaders(path),
  })


  // ----------------------------------------------------------
  // CHECK HTTP RESPONSE
  // ----------------------------------------------------------

  await ensureSuccessfulResponse(response)


  // ----------------------------------------------------------
  // READ BINARY CONTENT
  // ----------------------------------------------------------

  const blob = await response.blob()


  // ----------------------------------------------------------
  // GET CONTENT-DISPOSITION HEADER
  // ----------------------------------------------------------

  /**
  * Prefer a filename supplied by the backend.
  *
  * Otherwise, retain the caller's fallback name.
  */
  const disposition =
    response.headers.get('Content-Disposition')


  // ----------------------------------------------------------
  // EXTRACT OPTIONAL SERVER FILENAME
  // ----------------------------------------------------------

  const filenameMatch = disposition?.match(
    /filename="?([^";]+)"?/i,
  )


  // ----------------------------------------------------------
  // NORMALIZE FILENAME
  // ----------------------------------------------------------

  /**
  * Remove directory separators from server
  * filenames to keep only the final component.
  */
  const filename = (
    filenameMatch?.[1] ?? fallbackFilename
  ).split(/[\\/]/).pop() || fallbackFilename


  // ----------------------------------------------------------
  // CREATE TEMPORARY DOWNLOAD URL
  // ----------------------------------------------------------

  const objectUrl =
    URL.createObjectURL(blob)


  // ----------------------------------------------------------
  // CREATE DOWNLOAD LINK
  // ----------------------------------------------------------

  const link =
    document.createElement('a')

  link.href = objectUrl

  link.download = filename


  // ----------------------------------------------------------
  // TRIGGER DOWNLOAD AND CLEAN UP
  // ----------------------------------------------------------

  try {

    document.body.appendChild(link)

    link.click()

  } finally {

    // Remove the temporary element.
    link.remove()

    // Release the browser's temporary object URL.
    URL.revokeObjectURL(objectUrl)

  }
}
