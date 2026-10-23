package com.ledgerbase.reporting.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

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
 *
 * SECURITY:
 *
 * - Authentication and membership checks use the
 *   authoritative Accounting Core database.
 *
 * - Financial reporting queries continue to use
 *   the PostgreSQL read replica.
 *
 * - Network errors, timeouts, and unexpected responses
 *   must never grant authorization.
 *
 * - JWT values must not be written to application logs.
 */
@Component
public class AccountingCoreAuthorizationClient {

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
     * @param businessId requested business identifier
     * @param accessToken authenticated JWT value
     * @return true only when Accounting Core approves
     */
    public boolean hasReportingAccess(
        Long businessId,
        String accessToken
    ) {

        // Reject missing or invalid inputs.
        if (
            businessId == null
            || businessId <= 0
            || accessToken == null
            || accessToken.isBlank()
        ) {
            return false;
        }

        try {

            Boolean authorized = restClient.get()

                .uri(
                    "/api/v1/businesses/{businessId}/reporting-access",
                    businessId
                )

                // Forward the existing authenticated token.
                .headers(headers ->
                    headers.setBearerAuth(accessToken)
                )

                // Only the expected success response grants
                // access. This also rejects HTTP redirects.
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
}
