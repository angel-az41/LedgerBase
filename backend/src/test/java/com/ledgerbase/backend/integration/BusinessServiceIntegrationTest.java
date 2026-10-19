package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.model.Business;
import com.ledgerbase.backend.service.BusinessService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — BUSINESS SERVICE INTEGRATION TESTS
// ============================================================

/**
 * Verify BusinessService against real PostgreSQL.
 *
 * REQUEST FLOW:
 *
 * BusinessService
 *       |
 *       v
 * BusinessMapper
 *       |
 *       v
 * PostgreSQL
 *
 * TEST COVERAGE:
 *
 * 1. Business creation.
 * 2. Default configuration.
 * 3. Input normalization.
 * 4. Owner-scoped business listing.
 * 5. Owner-scoped business retrieval.
 * 6. Missing business handling.
 * 7. Business name validation.
 * 8. Currency validation.
 * 9. Fiscal-year month validation.
 * 10. Identifier validation.
 *
 * IMPORTANT:
 *
 * @ActiveProfiles("test") selects the isolated
 * LedgerBase integration-test database.
 *
 * @Transactional rolls back database changes
 * after every test.
 *
 * No existing development businesses are modified.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BusinessServiceIntegrationTest {

    // ========================================================
    // TEST DEPENDENCIES
    // ========================================================

    @Autowired
    private BusinessService businessService;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    // ========================================================
    // TEST FIXTURE IDENTIFIERS
    // ========================================================

    /**
     * Use independently created owners rather than
     * assuming a development user has ID 1.
     */
    private Long ownerAId;

    private Long ownerBId;


    // ========================================================
    // CREATE TEST USERS
    // ========================================================

    @BeforeEach
    void setUp() {

        ownerAId = createTestUser("Service Test Owner A");

        ownerBId = createTestUser("Service Test Owner B");
    }


    // ========================================================
    // TEST 1 — CREATE BUSINESS SUCCESSFULLY
    // ========================================================

    @Test
    void createsBusinessSuccessfully() {

        // ====================================================
        // CREATE BUSINESS THROUGH SERVICE
        // ====================================================

        Business business = businessService.createBusiness(
            ownerAId,
            "Service Test Consulting LLC",
            "USD",
            1
        );


        // ====================================================
        // VERIFY GENERATED DATABASE VALUES
        // ====================================================

        assertNotNull(business);

        assertNotNull(business.getId());

        assertTrue(business.getId() > 0);

        assertNotNull(business.getCreatedAt());


        // ====================================================
        // VERIFY CONFIGURATION
        // ====================================================

        assertEquals(ownerAId, business.getOwnerId());

        assertEquals(
            "Service Test Consulting LLC",
            business.getName()
        );

        assertEquals("USD", business.getCurrency());

        assertEquals(
            Integer.valueOf(1),
            business.getFiscalYearStartMonth()
        );


        // ====================================================
        // VERIFY BUSINESS IS STORED IN POSTGRESQL
        // ====================================================

        Long databaseCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM businesses
            WHERE id = ?
              AND owner_id = ?
            """,
            Long.class,
            business.getId(),
            ownerAId
        );

        assertEquals(Long.valueOf(1), databaseCount);
    }


    // ========================================================
    // TEST 2 — DEFAULT ACCOUNTING CONFIGURATION
    // ========================================================

    @Test
    void appliesDefaultBusinessConfiguration() {

        // ====================================================
        // OMIT OPTIONAL CONFIGURATION
        // ====================================================

        Business business = businessService.createBusiness(
            ownerAId,
            "Default Configuration Business",
            null,
            null
        );


        // ====================================================
        // VERIFY DEFAULT CURRENCY
        // ====================================================

        assertEquals(
            "USD",
            business.getCurrency()
        );


        // ====================================================
        // VERIFY JANUARY FISCAL-YEAR START
        // ====================================================

        assertEquals(
            Integer.valueOf(1),
            business.getFiscalYearStartMonth()
        );
    }


    // ========================================================
    // TEST 3 — NORMALIZE BUSINESS INPUT
    // ========================================================

    @Test
    void normalizesBusinessNameAndCurrency() {

        // ====================================================
        // CREATE BUSINESS WITH EXTRA WHITESPACE
        // ====================================================

        Business business = businessService.createBusiness(
            ownerAId,
            "   Example Consulting LLC   ",
            " gbp ",
            4
        );


        // ====================================================
        // VERIFY NORMALIZED BUSINESS NAME
        // ====================================================

        assertEquals(
            "Example Consulting LLC",
            business.getName()
        );


        // ====================================================
        // VERIFY UPPERCASE CURRENCY
        // ====================================================

        assertEquals(
            "GBP",
            business.getCurrency()
        );


        // ====================================================
        // VERIFY CUSTOM FISCAL-YEAR MONTH
        // ====================================================

        assertEquals(
            Integer.valueOf(4),
            business.getFiscalYearStartMonth()
        );
    }


    // ========================================================
    // TEST 4 — LIST BUSINESSES BY OWNER
    // ========================================================

    @Test
    void listsOnlyBusinessesBelongingToOwner() {

        // ====================================================
        // CREATE TWO BUSINESSES FOR OWNER A
        // ====================================================

        Business businessA1 = businessService.createBusiness(
            ownerAId,
            "Owner A Business One",
            "USD",
            1
        );

        Business businessA2 = businessService.createBusiness(
            ownerAId,
            "Owner A Business Two",
            "USD",
            1
        );


        // ====================================================
        // CREATE ONE BUSINESS FOR OWNER B
        // ====================================================

        Business businessB = businessService.createBusiness(
            ownerBId,
            "Owner B Business",
            "GBP",
            4
        );


        // ====================================================
        // REQUEST OWNER A'S BUSINESSES
        // ====================================================

        List<Business> ownerABusinesses =
            businessService.getBusinessesByOwner(ownerAId);


        // ====================================================
        // VERIFY OWNER A RECEIVES EXACTLY TWO
        // ====================================================

        assertEquals(2, ownerABusinesses.size());

        assertEquals(
            List.of(
                businessA1.getId(),
                businessA2.getId()
            ),
            ownerABusinesses.stream()
                .map(Business::getId)
                .toList()
        );


        // ====================================================
        // VERIFY OWNER B'S BUSINESS IS EXCLUDED
        // ====================================================

        assertTrue(
            ownerABusinesses.stream()
                .noneMatch(business ->
                    businessB.getId().equals(business.getId())
                )
        );


        // ====================================================
        // REQUEST OWNER B'S BUSINESSES
        // ====================================================

        List<Business> ownerBBusinesses =
            businessService.getBusinessesByOwner(ownerBId);

        assertEquals(1, ownerBBusinesses.size());

        assertEquals(
            businessB.getId(),
            ownerBBusinesses.get(0).getId()
        );
    }


    // ========================================================
    // TEST 5 — OWNER-SCOPED RETRIEVAL
    // ========================================================

    @Test
    void preventsRetrievalWithDifferentOwnerId() {

        // ====================================================
        // CREATE BUSINESS FOR OWNER A
        // ====================================================

        Business business = businessService.createBusiness(
            ownerAId,
            "Owner A Private Business",
            "USD",
            1
        );


        // ====================================================
        // CORRECT OWNER CAN RETRIEVE BUSINESS
        // ====================================================

        Business result = businessService.getBusinessById(
            business.getId(),
            ownerAId
        );

        assertEquals(
            business.getId(),
            result.getId()
        );


        // ====================================================
        // DIFFERENT OWNER RECEIVES NOT FOUND
        // ====================================================

        /**
         * We deliberately use BusinessNotFoundException
         * for both missing businesses and owner mismatches.
         *
         * This does not authenticate the caller:
         * the controller must eventually obtain ownerId
         * from a trusted security context.
         */
        assertThrows(
            BusinessNotFoundException.class,
            () -> businessService.getBusinessById(
                business.getId(),
                ownerBId
            )
        );
    }


    // ========================================================
    // TEST 6 — NONEXISTENT BUSINESS
    // ========================================================

    @Test
    void throwsWhenBusinessDoesNotExist() {

        // ====================================================
        // REQUEST UNKNOWN BUSINESS ID
        // ====================================================

        assertThrows(
            BusinessNotFoundException.class,
            () -> businessService.getBusinessById(
                Long.MAX_VALUE,
                ownerAId
            )
        );
    }


    // ========================================================
    // TEST 7 — BUSINESS NAME VALIDATION
    // ========================================================

    @Test
    void rejectsInvalidBusinessNames() {

        // ====================================================
        // NULL BUSINESS NAME
        // ====================================================

        assertThrows(
            IllegalArgumentException.class,
            () -> businessService.createBusiness(
                ownerAId,
                null,
                "USD",
                1
            )
        );


        // ====================================================
        // BLANK BUSINESS NAME
        // ====================================================

        assertThrows(
            IllegalArgumentException.class,
            () -> businessService.createBusiness(
                ownerAId,
                "    ",
                "USD",
                1
            )
        );


        // ====================================================
        // BUSINESS NAME EXCEEDS DATABASE LIMIT
        // ====================================================

        assertThrows(
            IllegalArgumentException.class,
            () -> businessService.createBusiness(
                ownerAId,
                "A".repeat(201),
                "USD",
                1
            )
        );
    }


    // ========================================================
    // TEST 8 — CURRENCY VALIDATION
    // ========================================================

    @Test
    void rejectsInvalidCurrencyCodes() {

        // ====================================================
        // TOO SHORT
        // ====================================================

        assertThrows(
            IllegalArgumentException.class,
            () -> businessService.createBusiness(
                ownerAId,
                "Invalid Currency Business",
                "US",
                1
            )
        );


        // ====================================================
        // CONTAINS A NUMBER
        // ====================================================

        assertThrows(
            IllegalArgumentException.class,
            () -> businessService.createBusiness(
                ownerAId,
                "Invalid Currency Business",
                "US1",
                1
            )
        );


        // ====================================================
        // EMPTY CURRENCY
        // ====================================================

        assertThrows(
            IllegalArgumentException.class,
            () -> businessService.createBusiness(
                ownerAId,
                "Invalid Currency Business",
                "",
                1
            )
        );
    }


    // ========================================================
    // TEST 9 — FISCAL-YEAR MONTH VALIDATION
    // ========================================================

    @Test
    void rejectsInvalidFiscalYearStartMonths() {

        // ====================================================
        // MONTH BELOW MINIMUM
        // ====================================================

        assertThrows(
            IllegalArgumentException.class,
            () -> businessService.createBusiness(
                ownerAId,
                "Invalid Fiscal Calendar",
                "USD",
                0
            )
        );


        // ====================================================
        // MONTH ABOVE MAXIMUM
        // ====================================================

        assertThrows(
            IllegalArgumentException.class,
            () -> businessService.createBusiness(
                ownerAId,
                "Invalid Fiscal Calendar",
                "USD",
                13
            )
        );
    }


    // ========================================================
    // TEST 10 — IDENTIFIER VALIDATION
    // ========================================================

    @Test
    void rejectsInvalidOwnerAndBusinessIds() {

        // ====================================================
        // INVALID OWNER FOR BUSINESS CREATION
        // ====================================================

        assertThrows(
            IllegalArgumentException.class,
            () -> businessService.createBusiness(
                0L,
                "Invalid Owner Business",
                "USD",
                1
            )
        );


        // ====================================================
        // NULL OWNER FOR BUSINESS LISTING
        // ====================================================

        assertThrows(
            IllegalArgumentException.class,
            () -> businessService.getBusinessesByOwner(
                null
            )
        );


        // ====================================================
        // INVALID BUSINESS ID
        // ====================================================

        assertThrows(
            IllegalArgumentException.class,
            () -> businessService.getBusinessById(
                -1L,
                ownerAId
            )
        );


        // ====================================================
        // INVALID OWNER FOR BUSINESS RETRIEVAL
        // ====================================================

        assertThrows(
            IllegalArgumentException.class,
            () -> businessService.getBusinessById(
                1L,
                0L
            )
        );
    }


    // ========================================================
    // HELPER — CREATE TEST USER
    // ========================================================

    /**
     * Insert a temporary user into PostgreSQL.
     *
     * Uses the same users table columns and
     * fixture pattern as our existing integration tests.
     *
     * UUID prevents duplicate email conflicts.
     *
     * The placeholder password hash is only
     * test fixture data, never a real credential.
     *
     * @Transactional ensures the user record
     * is rolled back after the test.
     */
    private Long createTestUser(String fullName) {

        return jdbcTemplate.queryForObject(
            """
            INSERT INTO users (
                email,
                password_hash,
                full_name
            )
            VALUES (?, ?, ?)
            RETURNING id
            """,
            Long.class,
            "business-service-"
                + UUID.randomUUID()
                + "@ledgerbase.local",
            "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
            fullName
        );
    }
}
