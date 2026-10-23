package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.model.Business;
import com.ledgerbase.backend.service.BusinessService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — BUSINESS MEMBERSHIP INTEGRATION TESTS
// ============================================================

/**
 * Verify membership-based business access with PostgreSQL.
 *
 * TEST ISOLATION:
 *
 * Every test creates its own users and business data.
 *
 * The tests do not depend on development users,
 * existing businesses, or manually seeded records.
 *
 * @Transactional rolls back test-created data.
 *
 * TEST COVERAGE:
 *
 * - Business creation assigns OWNER membership.
 * - Owners can list their businesses.
 * - Nonmembers cannot retrieve businesses.
 * - Users without memberships see no businesses.
 * - Non-owner members can access shared businesses.
 *
 * Role-specific write authorization will be
 * tested separately when financial RBAC is added.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Transactional
class BusinessMembershipIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    @Autowired
    private BusinessService businessService;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    // ========================================================
    // TEST PASSWORD ENCODER
    // ========================================================

    /**
     * Generate a valid BCrypt hash for temporary users.
     *
     * No test logs in using this password.
     *
     * The hash only provides a valid persistence value.
     */
    private static final BCryptPasswordEncoder
        PASSWORD_ENCODER = new BCryptPasswordEncoder();


    // ========================================================
    // CREATE TEMPORARY TEST USER
    // ========================================================

    /**
     * Insert a new user into the PostgreSQL test database.
     *
     * Unlike the previous helper, this method does not
     * assume users already exist.
     *
     * Random emails avoid unique-constraint collisions.
     *
     * The INSERT participates in the surrounding test
     * transaction and is rolled back automatically.
     *
     * @return generated PostgreSQL user ID
     */
    private Long createTestUserId() {

        // ----------------------------------------------------
        // GENERATE UNIQUE TEST EMAIL
        // ----------------------------------------------------

        String email =
            "membership-test-" +
            UUID.randomUUID() +
            "@example.invalid";


        // ----------------------------------------------------
        // GENERATE TEST PASSWORD HASH
        // ----------------------------------------------------

        String passwordHash =
            PASSWORD_ENCODER.encode(
                "temporary-test-password"
            );


        // ----------------------------------------------------
        // INSERT USER AND RETURN GENERATED ID
        // ----------------------------------------------------

        Long userId =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO users (
                    email,
                    full_name,
                    password_hash
                )
                VALUES (?, ?, ?)
                RETURNING id
                """,
                Long.class,
                email,
                "Membership Test User",
                passwordHash
            );


        // ----------------------------------------------------
        // VERIFY TEST FIXTURE
        // ----------------------------------------------------

        assertNotNull(
            userId,
            "Test user creation must return an ID"
        );

        return userId;
    }


    // ========================================================
    // CREATE TEST BUSINESS
    // ========================================================

    /**
     * Use the actual BusinessService to create
     * a business and its OWNER membership.
     *
     * This exercises the real transactional logic.
     */
    private Business createTestBusiness(
        Long ownerId
    ) {

        return businessService.createBusiness(
            ownerId,
            "Membership Test " + UUID.randomUUID(),
            "USD",
            1
        );
    }


    // ========================================================
    // TEST — OWNER MEMBERSHIP CREATION
    // ========================================================

    /**
     * Creating a business must create a corresponding
     * OWNER membership in the same transaction.
     */
    @Test
    void shouldCreateBusinessWithOwnerMembership() {

        // ----------------------------------------------------
        // CREATE TEST USER AND BUSINESS
        // ----------------------------------------------------

        Long ownerId =
            createTestUserId();

        Business business =
            createTestBusiness(ownerId);


        // ----------------------------------------------------
        // VERIFY BUSINESS OWNERSHIP
        // ----------------------------------------------------

        assertNotNull(business.getId());

        assertEquals(
            ownerId,
            business.getOwnerId()
        );


        // ----------------------------------------------------
        // VERIFY OWNER MEMBERSHIP ROLE
        // ----------------------------------------------------

        String role =
            jdbcTemplate.queryForObject(
                """
                SELECT role
                FROM business_memberships
                WHERE business_id = ?
                  AND user_id = ?
                """,
                String.class,
                business.getId(),
                ownerId
            );

        assertEquals("OWNER", role);


        // ----------------------------------------------------
        // VERIFY OWNER CAN RETRIEVE BUSINESS
        // ----------------------------------------------------

        Business accessible =
            businessService.getBusinessForUser(
                business.getId(),
                ownerId
            );

        assertEquals(
            business.getId(),
            accessible.getId()
        );
    }


    // ========================================================
    // TEST — OWNER CAN LIST NEW BUSINESS
    // ========================================================

    /**
     * A new business must appear in the authenticated
     * owner's membership-scoped directory.
     */
    @Test
    void shouldListNewBusinessForItsOwner() {

        Long ownerId =
            createTestUserId();

        Business business =
            createTestBusiness(ownerId);


        // ----------------------------------------------------
        // GET MEMBER-ACCESSIBLE BUSINESSES
        // ----------------------------------------------------

        List<Business> accessibleBusinesses =
            businessService.getBusinessesForUser(
                ownerId
            );


        // ----------------------------------------------------
        // CHECK NEW BUSINESS IS PRESENT
        // ----------------------------------------------------

        assertTrue(
            accessibleBusinesses.stream()
                .anyMatch(
                    item ->
                        item.getId().equals(
                            business.getId()
                        )
                )
        );
    }


    // ========================================================
    // TEST — NONMEMBER CANNOT ACCESS BUSINESS
    // ========================================================

    /**
     * A valid user who does not belong to a business
     * must not retrieve that business.
     *
     * This uses two real database users rather than
     * a made-up nonexistent user ID.
     */
    @Test
    void shouldRejectBusinessAccessWithoutMembership() {

        // ----------------------------------------------------
        // CREATE TWO DISTINCT USERS
        // ----------------------------------------------------

        Long ownerId =
            createTestUserId();

        Long nonMemberId =
            createTestUserId();


        // ----------------------------------------------------
        // CREATE BUSINESS BELONGING TO OWNER
        // ----------------------------------------------------

        Business business =
            createTestBusiness(ownerId);


        // ----------------------------------------------------
        // VERIFY NONMEMBER ACCESS IS REJECTED
        // ----------------------------------------------------

        assertThrows(
            BusinessNotFoundException.class,
            () -> businessService.getBusinessForUser(
                business.getId(),
                nonMemberId
            )
        );
    }


    // ========================================================
    // TEST — USER WITHOUT MEMBERSHIPS
    // ========================================================

    /**
     * A valid user with no membership records
     * must receive an empty business directory.
     */
    @Test
    void shouldReturnNoBusinessesWithoutMembership() {

        // ----------------------------------------------------
        // CREATE USER WITHOUT BUSINESS MEMBERSHIP
        // ----------------------------------------------------

        Long userId =
            createTestUserId();


        // ----------------------------------------------------
        // QUERY ACCESSIBLE BUSINESS DIRECTORY
        // ----------------------------------------------------

        List<Business> businesses =
            businessService.getBusinessesForUser(
                userId
            );


        // ----------------------------------------------------
        // VERIFY NO UNAUTHORIZED FALLBACK
        // ----------------------------------------------------

        assertTrue(
            businesses.isEmpty()
        );
    }


    // ========================================================
    // TEST — NON-OWNER MEMBERSHIP GRANTS READ ACCESS
    // ========================================================

    /**
     * A business can have multiple authorized users.
     *
     * A VIEWER membership must allow business retrieval
     * and listing without changing canonical ownership.
     *
     * This is stronger than the previous test that
     * assumed existing OWNER memberships were seeded.
     */
    @Test
    void shouldAllowNonOwnerMemberToViewBusiness() {

        // ----------------------------------------------------
        // CREATE TWO DISTINCT USERS
        // ----------------------------------------------------

        Long ownerId =
            createTestUserId();

        Long viewerId =
            createTestUserId();


        // ----------------------------------------------------
        // CREATE OWNER'S BUSINESS
        // ----------------------------------------------------

        Business business =
            createTestBusiness(ownerId);


        // ----------------------------------------------------
        // ASSIGN VIEWER MEMBERSHIP
        // ----------------------------------------------------

        int insertedRows =
            jdbcTemplate.update(
                """
                INSERT INTO business_memberships (
                    business_id,
                    user_id,
                    role
                )
                VALUES (?, ?, 'VIEWER')
                """,
                business.getId(),
                viewerId
            );

        assertEquals(1, insertedRows);


        // ----------------------------------------------------
        // VERIFY VIEWER CAN RETRIEVE BUSINESS
        // ----------------------------------------------------

        Business accessible =
            businessService.getBusinessForUser(
                business.getId(),
                viewerId
            );

        assertEquals(
            business.getId(),
            accessible.getId()
        );


        // ----------------------------------------------------
        // VERIFY VIEWER CAN LIST BUSINESS
        // ----------------------------------------------------

        List<Business> viewerBusinesses =
            businessService.getBusinessesForUser(
                viewerId
            );

        assertTrue(
            viewerBusinesses.stream()
                .anyMatch(
                    item ->
                        item.getId().equals(
                            business.getId()
                        )
                )
        );


        // ----------------------------------------------------
        // VERIFY CANONICAL OWNER DOES NOT CHANGE
        // ----------------------------------------------------

        assertEquals(
            ownerId,
            accessible.getOwnerId()
        );
    }
}
