package com.ledgerbase.reporting.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.OptionalLong;

// ============================================================
// LEDGERBASE — ACCOUNTING CORE AUTHORIZATION CLIENT
// ============================================================

/**
 * Verifies reporting access through Accounting Core.
 *
 * RESPONSIBILITIES:
 *
 * - Forward the authenticated JWT to Accounting Core.
 * - Verify the user's current authentication session.
 * - Verify current business membership and read access.
 * - Accept only HTTP 204 as authorization success.
 * - Retrieve the authoritative PostgreSQL reporting revision.
 *
 * SECURITY:
 *
 * - Authentication and membership checks use the
 *   authoritative Accounting Core database.
 *
 * - Financial reporting queries continue to use
 *   the PostgreSQL read replica.
 *
 * - The authoritative reporting revision comes from
 *   Accounting Core, not the read replica.
 *
 * - Network errors, timeouts, and unexpected responses
 *   must never grant authorization.
 *
 * - Missing or malformed revision headers must never
 *   be converted into fabricated revision values.
 *
 * - JWT values must not be written to application logs.
 *
 * COMPATIBILITY:
 *
 * Existing ReportingBusinessAuthorizationManager code
 * continues using hasReportingAccess().
 *
 * getAuthorizedReportingRevision() is an additional API
 * that will be used by revision-aware Redis report caching.
 */
@Component
public class AccountingCoreAuthorizationClient {

    // ========================================================
    // RESPONSE HEADER CONSTANT
    // ========================================================

    /**
     * Must match the header name returned by
     * Accounting Core's ReportingAccessController.
     *
     * Services do not share Java implementation classes,
     * so the HTTP header is their integration contract.
     */
    public static final String REPORTING_REVISION_HEADER =
        "X-LedgerBase-Reporting-Revision";

    // ========================================================
    // HTTP CLIENT
    // ========================================================

    private final RestClient restClient;

    // ========================================================
    // CONSTRUCTOR
    // ========================================================

    public AccountingCoreAuthorizationClient(
        @Value("${ledgerbase.accounting-core.base-url}")
        String baseUrl
    ) {

        // Use a dedicated HTTP client with bounded timeouts.
        // Never follow redirects carrying Bearer tokens.
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        JdkClientHttpRequestFactory requestFactory =
            new JdkClientHttpRequestFactory(httpClient);

        requestFactory.setReadTimeout(
            Duration.ofSeconds(3)
        );

        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .build();
    }

    // ========================================================
    // VERIFY BUSINESS REPORTING ACCESS
    // ========================================================

    /**
     * Ask Accounting Core whether the current JWT
     * grants reporting access to the requested business.
     *
     * Accounting Core performs:
     *
     * 1. JWT signature and claim validation.
     * 2. Authentication session validation.
     * 3. Current business membership lookup.
     * 4. Read permission verification.
     *
     * HTTP 204 means access was granted.
     *
     * Every other response means access is denied.
     *
     * IMPORTANT:
     *
     * Preserve the original authorization contract.
     * This method does not require a revision header.
     *
     * The dedicated revision method below provides
     * stricter validation for Redis caching.
     *
     * @param businessId requested business identifier
     * @param accessToken authenticated JWT value
     * @return true only when Accounting Core approves
     */
    public boolean hasReportingAccess(
        Long businessId,
        String accessToken
    ) {

        // ====================================================
        // VALIDATE INPUTS
        // ====================================================

        if (!hasValidInputs(businessId, accessToken)) {
            return false;
        }

        try {

            // =================================================
            // SEND AUTHENTICATED AUTHORIZATION REQUEST
            // =================================================

            Boolean authorized = restClient.get()

                .uri(
                    "/api/v1/businesses/{businessId}/reporting-access",
                    businessId
                )

                // Forward the existing authenticated JWT.
                .headers(headers ->
                    headers.setBearerAuth(accessToken)
                )

                // Only HTTP 204 is authorization success.
                // Redirects and other statuses are rejected.
                .exchange((request, response) ->
                    response.getStatusCode().value() == 204
                );

            return Boolean.TRUE.equals(authorized);

        } catch (RestClientException exception) {

            // Never grant access when Accounting Core is
            // unavailable or the HTTP request fails.
            //
            // Do not log the Bearer token.
            return false;
        }
    }

    // ========================================================
    // RETRIEVE AUTHORIZED REPORTING REVISION
    // ========================================================

    /**
     * Retrieve the authoritative reporting revision
     * for a business that Accounting Core has authorized.
     *
     * This method calls the existing authorization
     * endpoint with the caller's JWT.
     *
     * A valid result requires BOTH:
     *
     * 1. HTTP 204 No Content.
     * 2. A valid nonnegative reporting revision header.
     *
     * HTTP 401, HTTP 403, HTTP 404, redirects,
     * network failures, missing headers, and malformed
     * values all produce OptionalLong.empty().
     *
     * Zero is a valid reporting revision.
     *
     * IMPORTANT:
     *
     * An empty result must not be treated as revision zero.
     *
     * Future caching logic must fail closed when
     * the authoritative revision is unavailable.
     *
     * @param businessId requested business identifier
     * @param accessToken authenticated JWT value
     * @return authorized revision, or empty on failure
     */
    public OptionalLong getAuthorizedReportingRevision(
        Long businessId,
        String accessToken
    ) {

        // ====================================================
        // VALIDATE INPUTS
        // ====================================================

        if (!hasValidInputs(businessId, accessToken)) {
            return OptionalLong.empty();
        }

        try {

            // =================================================
            // REQUEST AUTHORIZATION AND REVISION
            // =================================================

            return restClient.get()

                .uri(
                    "/api/v1/businesses/{businessId}/reporting-access",
                    businessId
                )

                // Forward the requesting user's JWT.
                .headers(headers ->
                    headers.setBearerAuth(accessToken)
                )

                .exchange((request, response) -> {

                    // ----------------------------------------
                    // REQUIRE AUTHORIZATION SUCCESS
                    // ----------------------------------------

                    /*
                     * Do not interpret a revision header
                     * from an unauthorized or unexpected
                     * HTTP response.
                     *
                     * Only the exact 204 status is accepted.
                     */
                    if (
                        response.getStatusCode().value() != 204
                    ) {
                        return OptionalLong.empty();
                    }

                    // ----------------------------------------
                    // READ REPORTING REVISION HEADER
                    // ----------------------------------------

                    String rawRevision =
                        response.getHeaders().getFirst(
                            REPORTING_REVISION_HEADER
                        );

                    // ----------------------------------------
                    // PARSE REVISION SAFELY
                    // ----------------------------------------

                    return parseReportingRevision(
                        rawRevision
                    );
                });

        } catch (RestClientException exception) {

            // --------------------------------------------
            // FAIL CLOSED ON COMMUNICATION ERRORS
            // --------------------------------------------

            /*
             * Unavailable Accounting Core means the
             * Reporting Service cannot establish the
             * authoritative report revision.
             *
             * Do not serve revision-dependent cached data
             * based on a guessed version.
             *
             * Do not log the Bearer token.
             */
            return OptionalLong.empty();
        }
    }

    // ========================================================
    // VALIDATE AUTHORIZATION REQUEST INPUTS
    // ========================================================

    /**
     * Shared input validation for both client methods.
     *
     * Reject missing business IDs and missing tokens
     * before making an HTTP request.
     */
    private boolean hasValidInputs(
        Long businessId,
        String accessToken
    ) {

        return businessId != null
            && businessId > 0
            && accessToken != null
            && !accessToken.isBlank();
    }

    // ========================================================
    // PARSE AUTHORITATIVE REPORTING REVISION
    // ========================================================

    /**
     * Parse the revision header returned by Accounting Core.
     *
     * Revision values are nonnegative Long integers.
     *
     * Examples:
     *
     * "0"     -> Valid
     * "12"    -> Valid
     * "-1"    -> Invalid
     * "abc"   -> Invalid
     * null    -> Invalid
     *
     * Malformed values are never silently changed to zero.
     */
    private OptionalLong parseReportingRevision(
        String rawRevision
    ) {

        // ----------------------------------------------------
        // REQUIRE NONEMPTY HEADER
        // ----------------------------------------------------

        if (
            rawRevision == null
            || rawRevision.isBlank()
        ) {
            return OptionalLong.empty();
        }

        // ----------------------------------------------------
        // PARSE NUMERIC REVISION
        // ----------------------------------------------------

        try {

            long revision = Long.parseLong(
                rawRevision.trim()
            );

            // Revisions cannot be negative.
            if (revision < 0) {
                return OptionalLong.empty();
            }

            return OptionalLong.of(revision);

        } catch (NumberFormatException exception) {

            // Reject nonnumeric values and Long overflow.
            return OptionalLong.empty();
        }
    }
}
