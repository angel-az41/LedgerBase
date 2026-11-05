package com.ledgerbase.backend.integration;

import com.ledgerbase.backend.dto.AuthenticatedUser;
import com.ledgerbase.backend.dto.RegisterRequest;
import com.ledgerbase.backend.dto.RegisterResponse;

import com.ledgerbase.backend.mapper.UserMapper;
import com.ledgerbase.backend.model.User;

import com.ledgerbase.backend.service.AuthenticationService;
import com.ledgerbase.backend.service.RegistrationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// ============================================================
// LEDGERBASE — REGISTRATION INTEGRATION TESTS
// ============================================================

/**
 * Verify user registration against a real PostgreSQL
 * database using the existing LedgerBase test profile.
 *
 * DATABASE:
 *
 * ledgerbase_test
 *
 * INTEGRATION COVERAGE:
 *
 * - Persist users through MyBatis.
 * - Retrieve PostgreSQL-generated user IDs.
 * - Verify BCrypt password hashes.
 * - Authenticate newly registered users.
 * - Reject duplicate registrations.
 * - Enforce case-insensitive email uniqueness
 *   directly through the PostgreSQL index.
 *
 * DATA SAFETY:
 *
 * - The test profile must target ledgerbase_test.
 * - Each test runs inside a Spring transaction.
 * - Test transactions are rolled back automatically.
 * - Existing development business data is untouched.
 *
 * No actual JWT or refresh cookie is generated here.
 * HTTP and session behavior is verified separately.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RegistrationIntegrationTest {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    // ========================================================
    // VERIFY DATABASE ISOLATION
    // ========================================================

    /**
     * Verify that the active JDBC connection uses
     * the dedicated test database.
     *
     * This check executes before each test method.
     *
     * If application configuration accidentally
     * points to the development database, the test
     * must fail before inserting any records.
     */
    @BeforeEach
    void verifyTestDatabase() {

        String databaseName =
            jdbcTemplate.queryForObject(
                "SELECT current_database()",
                String.class
            );

        assertEquals(
            "ledgerbase_test",
            databaseName,
            "Registration integration tests must use ledgerbase_test"
        );
    }


    // ========================================================
    // SUCCESSFUL REGISTRATION AND LOGIN
    // ========================================================

    /**
     * Verify that a newly registered user:
     *
     * - Receives a PostgreSQL-generated ID.
     * - Has a normalized email address.
     * - Has a normalized display name.
     * - Has a BCrypt password hash.
     * - Can authenticate through the existing
     *   AuthenticationService.
     *
     * The plaintext password must never be
     * persisted in the users table.
     */
    @Test
    void registersUserAndAuthenticatesSuccessfully() {

        // ----------------------------------------------------
        // PREPARE UNIQUE REGISTRATION DATA
        // ----------------------------------------------------

        String email = uniqueEmail("registration");

        String rawPassword =
            "SecurePassword123!";

        RegisterRequest request =
            new RegisterRequest(
                "  Registration Test User  ",
                "  " + email.toUpperCase(Locale.ROOT) + "  ",
                rawPassword
            );


        // ----------------------------------------------------
        // REGISTER USER THROUGH APPLICATION SERVICE
        // ----------------------------------------------------

        RegisterResponse response =
            registrationService.register(request);


        // ----------------------------------------------------
        // VERIFY SAFE REGISTRATION RESPONSE
        // ----------------------------------------------------

        assertNotNull(response.userId());

        assertTrue(response.userId() > 0);

        assertEquals(
            email,
            response.email()
        );

        assertEquals(
            "Registration Test User",
            response.fullName()
        );


        // ----------------------------------------------------
        // READ PERSISTED USER FROM POSTGRESQL
        // ----------------------------------------------------

        User storedUser =
            userMapper.findById(
                response.userId()
            );

        assertNotNull(storedUser);

        assertEquals(
            email,
            storedUser.getEmail()
        );

        assertEquals(
            "Registration Test User",
            storedUser.getFullName()
        );


        // ----------------------------------------------------
        // VERIFY PASSWORD HASHING
        // ----------------------------------------------------

        /**
         * PostgreSQL must contain a BCrypt hash,
         * not the submitted plaintext password.
         */
        String storedHash =
            storedUser.getPasswordHash();

        assertNotNull(storedHash);

        assertNotEquals(
            rawPassword,
            storedHash
        );

        assertTrue(
            storedHash.startsWith("$2"),
            "Stored password should use BCrypt"
        );

        assertTrue(
            passwordEncoder.matches(
                rawPassword,
                storedHash
            )
        );


        // ----------------------------------------------------
        // AUTHENTICATE REGISTERED USER
        // ----------------------------------------------------

        /**
         * Reuse the existing AuthenticationService
         * rather than introducing a separate login
         * implementation for registered users.
         */
        AuthenticatedUser authenticatedUser =
            authenticationService.authenticate(
                email.toUpperCase(Locale.ROOT),
                rawPassword
            );


        // ----------------------------------------------------
        // VERIFY AUTHENTICATED IDENTITY
        // ----------------------------------------------------

        assertEquals(
            response.userId(),
            authenticatedUser.id()
        );

        assertEquals(
            email,
            authenticatedUser.email()
        );

        assertEquals(
            "Registration Test User",
            authenticatedUser.fullName()
        );
    }


    // ========================================================
    // DUPLICATE REGISTRATION
    // ========================================================

    /**
     * Verify that two registration requests using
     * the same email identity cannot create
     * separate LedgerBase accounts.
     *
     * The second request uses uppercase email text.
     *
     * RegistrationService should return a conflict
     * after PostgreSQL rejects the duplicate.
     *
     * The database constraint remains authoritative.
     */
    @Test
    void rejectsDuplicateRegistrationIgnoringEmailCase() {

        String email =
            uniqueEmail("duplicate");

        String password =
            "SecurePassword123!";


        // ----------------------------------------------------
        // REGISTER FIRST ACCOUNT
        // ----------------------------------------------------

        RegisterResponse firstUser =
            registrationService.register(
                new RegisterRequest(
                    "First User",
                    email,
                    password
                )
            );

        assertNotNull(firstUser.userId());


        // ----------------------------------------------------
        // ATTEMPT DUPLICATE REGISTRATION
        // ----------------------------------------------------

        /**
         * The second registration normalizes to
         * the same email address.
         *
         * PostgreSQL must reject the new row.
         */
        ResponseStatusException exception =
            assertThrows(
                ResponseStatusException.class,
                () -> registrationService.register(
                    new RegisterRequest(
                        "Second User",
                        email.toUpperCase(Locale.ROOT),
                        password
                    )
                )
            );


        // ----------------------------------------------------
        // VERIFY DUPLICATE CONFLICT
        // ----------------------------------------------------

        assertEquals(
            HttpStatus.CONFLICT,
            exception.getStatusCode()
        );

        /*
         * PostgreSQL marks the transaction as failed
         * after a unique-constraint violation.
         *
         * Do not issue additional SQL statements here.
         * Spring's test transaction will roll back.
         */
    }


    // ========================================================
    // DATABASE CASE-INSENSITIVE UNIQUENESS
    // ========================================================

    /**
     * Verify the PostgreSQL unique index directly.
     *
     * This test intentionally bypasses the registration
     * service's email normalization.
     *
     * The database must reject mixed-case duplicate
     * email addresses even when a caller attempts
     * to insert them without normalization.
     *
     * This confirms that email uniqueness is not
     * dependent solely on application code.
     */
    @Test
    void databaseRejectsCaseInsensitiveDuplicateEmails() {

        String email =
            uniqueEmail("database");


        // ----------------------------------------------------
        // INSERT FIRST USER THROUGH REGISTRATION SERVICE
        // ----------------------------------------------------

        RegisterResponse firstUser =
            registrationService.register(
                new RegisterRequest(
                    "Database Test User",
                    email,
                    "SecurePassword123!"
                )
            );

        assertNotNull(firstUser.userId());


        // ----------------------------------------------------
        // PREPARE DIRECT DATABASE INSERT
        // ----------------------------------------------------

        /**
         * Insert an uppercase email directly through
         * MyBatis to exercise the database constraint.
         *
         * This bypasses RegistrationService's
         * lowercase normalization deliberately.
         */
        User duplicate = new User();

        duplicate.setEmail(
            email.toUpperCase(Locale.ROOT)
        );

        duplicate.setFullName(
            "Duplicate Database User"
        );

        duplicate.setPasswordHash(
            passwordEncoder.encode(
                "AnotherSecurePassword123!"
            )
        );


        // ----------------------------------------------------
        // VERIFY DATABASE CONSTRAINT
        // ----------------------------------------------------

        assertThrows(
            DataIntegrityViolationException.class,
            () -> userMapper.insertUser(duplicate)
        );

        /*
         * The violated PostgreSQL transaction is
         * rolled back by Spring TestContext.
         *
         * No duplicate test account is committed.
         */
    }


    // ========================================================
    // UNIQUE TEST EMAIL GENERATOR
    // ========================================================

    /**
     * Generate an email address unique to the test.
     *
     * UUIDs prevent accidental collisions with
     * records created by other integration tests.
     *
     * The test-domain email is never used for
     * sending actual messages.
     */
    private String uniqueEmail(String prefix) {

        String uniqueSuffix =
            UUID.randomUUID()
                .toString()
                .replace("-", "");

        return prefix
            + "."
            + uniqueSuffix
            + "@example.test";
    }
}
