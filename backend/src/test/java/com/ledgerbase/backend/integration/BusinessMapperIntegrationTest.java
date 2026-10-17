package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.model.Business;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — BUSINESS MAPPER INTEGRATION TESTS
// ============================================================

/**
 * Verify BusinessMapper against a real PostgreSQL database.
 *
 * TEST FLOW:
 *
 * BusinessMapper
 *       |
 *       v
 * MyBatis SQL Annotations
 *       |
 *       v
 * PostgreSQL
 *
 * TEST COVERAGE:
 *
 * 1. List businesses belonging to one owner.
 * 2. Return an empty list for an owner without businesses.
 * 3. Retrieve businesses using both business ID and owner ID.
 * 4. Insert businesses and retrieve generated PostgreSQL IDs.
 * 5. Preserve existing business existence and fiscal-year queries.
 * 6. Reject business creation when the owner does not exist.
 *
 * IMPORTANT:
 *
 * Each test creates temporary users and businesses.
 *
 * We do not depend on the existing development user
 * or any hardcoded business IDs.
 *
 * @Transactional automatically rolls back all
 * fixture records after each test.
 *
 * These tests use the database configured by
 * application-test.properties.
 *
 * They must not modify the normal development database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BusinessMapperIntegrationTest {

    // ========================================================
    // TEST DEPENDENCIES
    // ========================================================

    /**
     * BusinessMapper is the component under test.
     *
     * Spring Boot injects the actual MyBatis mapper.
     */
    @Autowired
    private BusinessMapper businessMapper;

    /**
     * JdbcTemplate creates test users.
     *
     * We need valid users because businesses.owner_id
     * has a foreign key referencing users.id.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;


    // ========================================================
    // TEST FIXTURE IDENTIFIERS
    // ========================================================

    /**
     * We create two independent users.
     *
     * This allows us to verify that business queries
     * correctly enforce their owner_id filters.
     */
    private Long ownerAId;
    private Long ownerBId;

    /**
     * Owner A has two businesses.
     *
     * Owner B has one business.
     */
    private Long businessA1Id;
    private Long businessA2Id;
    private Long businessB1Id;


    // ========================================================
    // CREATE INDEPENDENT TEST FIXTURES
    // ========================================================

    /**
     * Runs before every test.
     *
     * Creates:
     *
     * Owner A
     *   - Business A1: January fiscal-year start
     *   - Business A2: April fiscal-year start
     *
     * Owner B
     *   - Business B1: October fiscal-year start
     *
     * All records belong to the current test transaction
     * and are rolled back when the test finishes.
     */
    @BeforeEach
    void setUp() {

        // ====================================================
        // CREATE TEST USERS
        // ====================================================

        ownerAId = createTestUser("Owner A");

        ownerBId = createTestUser("Owner B");


        // ====================================================
        // CREATE OWNER A'S BUSINESSES
        // ====================================================

        businessA1Id = createBusiness(
            ownerAId,
            "Integration Test Business A1",
            "USD",
            1
        );

        businessA2Id = createBusiness(
            ownerAId,
            "Integration Test Business A2",
            "USD",
            4
        );


        // ====================================================
        // CREATE OWNER B'S BUSINESS
        // ====================================================

        businessB1Id = createBusiness(
            ownerBId,
            "Integration Test Business B1",
            "GBP",
            10
        );
    }


    // ========================================================
    // TEST 1 — LIST BUSINESSES BY OWNER
    // ========================================================

    @Test
    void listsOnlyBusinessesBelongingToRequestedOwner() {

        // ====================================================
        // RETRIEVE OWNER A'S BUSINESSES
        // ====================================================

        List<Business> businesses =
            businessMapper.findAllByOwnerId(ownerAId);


        // ====================================================
        // VERIFY THE RESULT COUNT
        // ====================================================

        assertEquals(
            2,
            businesses.size()
        );


        // ====================================================
        // VERIFY RESULTS ARE ORDERED BY BUSINESS ID
        // ====================================================

        /**
         * Business A1 was inserted before Business A2.
         *
         * Our mapper uses:
         *
         * ORDER BY id ASC
         */
        assertEquals(
            List.of(businessA1Id, businessA2Id),
            businesses.stream()
                .map(Business::getId)
                .toList()
        );


        // ====================================================
        // VERIFY THE OWNER FILTER
        // ====================================================

        /**
         * Every returned business must belong
         * to the requested owner.
         */
        assertTrue(
            businesses.stream()
                .allMatch(business ->
                    ownerAId.equals(business.getOwnerId())
                )
        );


        // ====================================================
        // VERIFY OWNER B'S BUSINESS IS EXCLUDED
        // ====================================================

        assertTrue(
            businesses.stream()
                .noneMatch(business ->
                    businessB1Id.equals(business.getId())
                )
        );


        // ====================================================
        // VERIFY MAPPED BUSINESS FIELDS
        // ====================================================

        Business firstBusiness = businesses.get(0);

        assertEquals(
            "Integration Test Business A1",
            firstBusiness.getName()
        );

        assertEquals(
            "USD",
            firstBusiness.getCurrency()
        );

        assertEquals(
            Integer.valueOf(1),
            firstBusiness.getFiscalYearStartMonth()
        );

        assertNotNull(firstBusiness.getCreatedAt());
    }


    // ========================================================
    // TEST 2 — EMPTY BUSINESS LIST
    // ========================================================

    @Test
    void returnsEmptyListWhenOwnerHasNoBusinesses() {

        // ====================================================
        // CREATE A USER WITHOUT ANY BUSINESSES
        // ====================================================

        Long emptyOwnerId = createTestUser(
            "Owner Without Businesses"
        );


        // ====================================================
        // QUERY THE BUSINESS MAPPER
        // ====================================================

        List<Business> businesses =
            businessMapper.findAllByOwnerId(emptyOwnerId);


        // ====================================================
        // VERIFY AN EMPTY LIST IS RETURNED
        // ====================================================

        /**
         * MyBatis should return an empty collection,
         * rather than including another owner's data.
         */
        assertNotNull(businesses);

        assertTrue(businesses.isEmpty());
    }


    // ========================================================
    // TEST 3 — OWNER-SCOPED BUSINESS LOOKUP
    // ========================================================

    @Test
    void retrievesBusinessOnlyWhenOwnerMatches() {

        // ====================================================
        // CORRECT OWNER
        // ====================================================

        Business allowedBusiness =
            businessMapper.findByIdAndOwnerId(
                businessA1Id,
                ownerAId
            );

        assertNotNull(allowedBusiness);

        assertEquals(
            businessA1Id,
            allowedBusiness.getId()
        );

        assertEquals(
            ownerAId,
            allowedBusiness.getOwnerId()
        );


        // ====================================================
        // WRONG OWNER
        // ====================================================

        /**
         * Owner B must not retrieve Owner A's business.
         *
         * The mapper SQL includes:
         *
         * WHERE id = #{businessId}
         *   AND owner_id = #{ownerId}
         */
        Business forbiddenBusiness =
            businessMapper.findByIdAndOwnerId(
                businessA1Id,
                ownerBId
            );

        assertNull(forbiddenBusiness);


        // ====================================================
        // NONEXISTENT BUSINESS
        // ====================================================

        Business nonexistentBusiness =
            businessMapper.findByIdAndOwnerId(
                Long.MAX_VALUE,
                ownerAId
            );

        assertNull(nonexistentBusiness);


        // ====================================================
        // OWNER B CAN RETRIEVE THEIR OWN BUSINESS
        // ====================================================

        Business ownerBBusiness =
            businessMapper.findByIdAndOwnerId(
                businessB1Id,
                ownerBId
            );

        assertNotNull(ownerBBusiness);

        assertEquals(
            "Integration Test Business B1",
            ownerBBusiness.getName()
        );
    }


    // ========================================================
    // TEST 4 — INSERT AND GENERATED BUSINESS ID
    // ========================================================

    @Test
    void insertsBusinessAndRetrievesGeneratedId() {

        // ====================================================
        // PREPARE A NEW BUSINESS
        // ====================================================

        Business newBusiness = new Business();

        newBusiness.setOwnerId(ownerAId);
        newBusiness.setName("ABC Consulting LLC");
        newBusiness.setCurrency("USD");
        newBusiness.setFiscalYearStartMonth(4);


        // ====================================================
        // VERIFY ID IS INITIALLY UNASSIGNED
        // ====================================================

        /**
         * PostgreSQL generates the ID.
         *
         * We must not manually calculate or assign it.
         */
        assertNull(newBusiness.getId());


        // ====================================================
        // INSERT THROUGH THE REAL MYBATIS MAPPER
        // ====================================================

        int insertedRows = businessMapper.insert(newBusiness);


        // ====================================================
        // VERIFY INSERT SUCCESS
        // ====================================================

        assertEquals(
            1,
            insertedRows
        );


        // ====================================================
        // VERIFY GENERATED PRIMARY KEY
        // ====================================================

        /**
         * @Options(useGeneratedKeys = true) should
         * populate newBusiness.id after insertion.
         */
        assertNotNull(
            newBusiness.getId(),
            "PostgreSQL-generated business ID must be returned"
        );

        assertTrue(
            newBusiness.getId() > 0
        );


        // ====================================================
        // RETRIEVE THE PERSISTED BUSINESS
        // ====================================================

        Business savedBusiness =
            businessMapper.findByIdAndOwnerId(
                newBusiness.getId(),
                ownerAId
            );


        // ====================================================
        // VERIFY ALL STORED VALUES
        // ====================================================

        assertNotNull(savedBusiness);

        assertEquals(
            newBusiness.getId(),
            savedBusiness.getId()
        );

        assertEquals(
            ownerAId,
            savedBusiness.getOwnerId()
        );

        assertEquals(
            "ABC Consulting LLC",
            savedBusiness.getName()
        );

        assertEquals(
            "USD",
            savedBusiness.getCurrency()
        );

        assertEquals(
            Integer.valueOf(4),
            savedBusiness.getFiscalYearStartMonth()
        );


        // ====================================================
        // VERIFY DATABASE-GENERATED TIMESTAMP
        // ====================================================

        /**
         * created_at is generated by PostgreSQL NOW().
         *
         * It must be available when the row is retrieved.
         */
        assertNotNull(savedBusiness.getCreatedAt());


        // ====================================================
        // VERIFY OTHER OWNERS CANNOT RETRIEVE IT
        // ====================================================

        assertNull(
            businessMapper.findByIdAndOwnerId(
                newBusiness.getId(),
                ownerBId
            )
        );
    }


    // ========================================================
    // TEST 5 — PRESERVE EXISTING MAPPER OPERATIONS
    // ========================================================

    @Test
    void preservesExistingBusinessQueries() {

        // ====================================================
        // BUSINESS EXISTENCE — POSITIVE CASE
        // ====================================================

        assertTrue(
            businessMapper.existsById(businessA1Id)
        );

        assertTrue(
            businessMapper.existsById(businessB1Id)
        );


        // ====================================================
        // BUSINESS EXISTENCE — NEGATIVE CASE
        // ====================================================

        assertFalse(
            businessMapper.existsById(Long.MAX_VALUE)
        );


        // ====================================================
        // FISCAL-YEAR CONFIGURATION
        // ====================================================

        /**
         * Business A1:
         * January starting fiscal year.
         */
        assertEquals(
            Integer.valueOf(1),
            businessMapper.findFiscalYearStartMonth(
                businessA1Id
            )
        );


        /**
         * Business A2:
         * April starting fiscal year.
         */
        assertEquals(
            Integer.valueOf(4),
            businessMapper.findFiscalYearStartMonth(
                businessA2Id
            )
        );


        /**
         * Business B1:
         * October starting fiscal year.
         */
        assertEquals(
            Integer.valueOf(10),
            businessMapper.findFiscalYearStartMonth(
                businessB1Id
            )
        );


        // ====================================================
        // UNKNOWN BUSINESS
        // ====================================================

        assertNull(
            businessMapper.findFiscalYearStartMonth(
                Long.MAX_VALUE
            )
        );
    }


    // ========================================================
    // TEST 6 — REJECT INVALID OWNER REFERENCE
    // ========================================================

    @Test
    void rejectsBusinessWhenOwnerDoesNotExist() {

        // ====================================================
        // PREPARE INVALID BUSINESS DATA
        // ====================================================

        Business invalidBusiness = new Business();

        invalidBusiness.setOwnerId(Long.MAX_VALUE);
        invalidBusiness.setName("Invalid Owner Business");
        invalidBusiness.setCurrency("USD");
        invalidBusiness.setFiscalYearStartMonth(1);


        // ====================================================
        // VERIFY POSTGRESQL FOREIGN KEY ENFORCEMENT
        // ====================================================

        /**
         * The businesses table has:
         *
         * FOREIGN KEY (owner_id)
         * REFERENCES users(id)
         * ON DELETE RESTRICT
         *
         * Since the supplied owner does not exist,
         * PostgreSQL must reject the insertion.
         *
         * Spring/MyBatis translates the database
         * constraint failure into a data-access
         * exception.
         *
         * IMPORTANT:
         *
         * PostgreSQL marks the current transaction
         * as failed after this exception.
         *
         * Therefore, this is deliberately the final
         * database operation in this test.
         *
         * Spring will roll back the test transaction
         * automatically afterward.
         */
        assertThrows(
            DataIntegrityViolationException.class,
            () -> businessMapper.insert(invalidBusiness)
        );
    }


    // ========================================================
    // HELPER — CREATE A TEST USER
    // ========================================================

    /**
     * Create an independent test user.
     *
     * We reuse the same table columns and fixture
     * pattern as BalanceSheetMapperIntegrationTest.
     *
     * UUID guarantees a unique test email.
     *
     * The password hash is only a test fixture;
     * it is not used for real authentication.
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
            "business-mapper-"
                + UUID.randomUUID()
                + "@ledgerbase.local",
            "TEST_ONLY_NOT_A_REAL_PASSWORD_HASH",
            fullName
        );
    }


    // ========================================================
    // HELPER — CREATE A BUSINESS THROUGH MYBATIS
    // ========================================================

    /**
     * Create a business owned by a test user.
     *
     * Using BusinessMapper.insert() in the fixture
     * verifies that generated IDs work consistently.
     *
     * All inserted records are rolled back after
     * the test completes.
     */
    private Long createBusiness(
        Long ownerId,
        String name,
        String currency,
        int fiscalYearStartMonth
    ) {

        // ====================================================
        // CONSTRUCT BUSINESS MODEL
        // ====================================================

        Business business = new Business();

        business.setOwnerId(ownerId);
        business.setName(name);
        business.setCurrency(currency);
        business.setFiscalYearStartMonth(
            fiscalYearStartMonth
        );


        // ====================================================
        // PERSIST THROUGH MYBATIS
        // ====================================================

        int insertedRows = businessMapper.insert(business);


        // ====================================================
        // VERIFY GENERATED ID
        // ====================================================

        assertEquals(1, insertedRows);

        assertNotNull(
            business.getId(),
            "Business fixture should receive a generated ID"
        );


        // ====================================================
        // RETURN NEW BUSINESS ID
        // ====================================================

        return business.getId();
    }
}
