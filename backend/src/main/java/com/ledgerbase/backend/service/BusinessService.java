package com.ledgerbase.backend.service;

import com.ledgerbase.backend.exception.BusinessNotFoundException;
import com.ledgerbase.backend.mapper.BusinessMapper;
import com.ledgerbase.backend.model.Business;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

// ============================================================
// LEDGERBASE — BUSINESS SERVICE
// ============================================================

/**
 * Business logic for LedgerBase business management.
 *
 * RESPONSIBILITIES:
 *
 * - Preserve existing owner-scoped operations.
 * - List businesses accessible through membership.
 * - Retrieve an individual accessible business.
 * - Validate and normalize new business information.
 * - Create businesses with OWNER membership.
 *
 * DATABASE:
 *
 * - businesses
 * - business_memberships
 *
 * SECURITY:
 *
 * User IDs must come from a trusted authenticated
 * identity at the HTTP boundary.
 *
 * This service expects callers to supply those IDs.
 *
 * Business-specific accounting operations will receive
 * separate permission checks in the RBAC layer.
 */
@Service
public class BusinessService {

    // ========================================================
    // DATABASE DEPENDENCY
    // ========================================================

    private final BusinessMapper businessMapper;


    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    /**
     * Preserve the original single-dependency constructor.
     *
     * This avoids unnecessary changes to existing
     * BusinessService unit-test setup.
     */
    public BusinessService(
        BusinessMapper businessMapper
    ) {

        this.businessMapper = businessMapper;
    }


    // ========================================================
    // EXISTING OWNER-SCOPED BUSINESS LIST
    // ========================================================

    /**
     * Retrieve businesses whose owner_id matches
     * the specified owner.
     *
     * Preserved for existing internal callers and tests.
     *
     * For the authenticated business directory,
     * use getBusinessesForUser() instead.
     */
    @Transactional(readOnly = true)
    public List<Business> getBusinessesByOwner(
        Long ownerId
    ) {

        validateOwnerId(ownerId);

        return businessMapper.findAllByOwnerId(
            ownerId
        );
    }


    // ========================================================
    // EXISTING OWNER-SCOPED BUSINESS LOOKUP
    // ========================================================

    /**
     * Retrieve one business by ID and canonical owner.
     *
     * Preserved for existing internal operations.
     *
     * Missing businesses or owner mismatches produce
     * the existing BusinessNotFoundException.
     */
    @Transactional(readOnly = true)
    public Business getBusinessById(
        Long businessId,
        Long ownerId
    ) {

        validateBusinessId(businessId);

        validateOwnerId(ownerId);

        Business business =
            businessMapper.findByIdAndOwnerId(
                businessId,
                ownerId
            );

        if (business == null) {

            throw new BusinessNotFoundException(
                businessId
            );
        }

        return business;
    }


    // ========================================================
    // LIST AUTHENTICATED USER'S BUSINESSES
    // ========================================================

    /**
     * Retrieve all businesses for which the user
     * has a business_memberships record.
     *
     * Supported membership roles:
     *
     * - OWNER
     * - ADMIN
     * - ACCOUNTANT
     * - VIEWER
     *
     * A user with no memberships receives
     * an empty list.
     *
     * The database determines membership.
     *
     * The frontend's selected business ID does not
     * grant access to a business.
     *
     * @param userId authenticated user's ID
     * @return accessible businesses
     */
    @Transactional(readOnly = true)
    public List<Business> getBusinessesForUser(
        Long userId
    ) {

        // ----------------------------------------------------
        // VALIDATE AUTHENTICATED USER ID
        // ----------------------------------------------------

        validateUserId(userId);


        // ----------------------------------------------------
        // QUERY MEMBERSHIP-SCOPED BUSINESS LIST
        // ----------------------------------------------------

        return businessMapper.findAllByMemberUserId(
            userId
        );
    }


    // ========================================================
    // RETRIEVE BUSINESS FOR AUTHENTICATED USER
    // ========================================================

    /**
     * Retrieve a business only if the authenticated
     * user belongs to it.
     *
     * The user may have any supported membership role.
     *
     * This is a read-access membership check.
     *
     * Role-specific write permissions will be handled
     * by separate authorization methods.
     *
     * Missing businesses and missing memberships
     * both result in BusinessNotFoundException.
     *
     * @param businessId requested business ID
     * @param userId authenticated user's ID
     * @return business accessible to the user
     */
    @Transactional(readOnly = true)
    public Business getBusinessForUser(
        Long businessId,
        Long userId
    ) {

        // ----------------------------------------------------
        // VALIDATE IDENTIFIERS
        // ----------------------------------------------------

        validateBusinessId(businessId);

        validateUserId(userId);


        // ----------------------------------------------------
        // QUERY BUSINESS AND MEMBERSHIP TOGETHER
        // ----------------------------------------------------

        Business business =
            businessMapper.findByIdAndMemberUserId(
                businessId,
                userId
            );


        // ----------------------------------------------------
        // HIDE OTHER BUSINESSES FROM NONMEMBERS
        // ----------------------------------------------------

        /**
         * Return the same application exception when:
         *
         * - The business does not exist.
         * - The user is not a member.
         *
         * This avoids disclosing the existence
         * of other users' businesses.
         */
        if (business == null) {

            throw new BusinessNotFoundException(
                businessId
            );
        }


        // ----------------------------------------------------
        // RETURN AUTHORIZED BUSINESS
        // ----------------------------------------------------

        return business;
    }


    // ========================================================
    // CREATE BUSINESS AND OWNER MEMBERSHIP
    // ========================================================

    /**
     * Create a business and assign its creator the
     * OWNER membership role.
     *
     * VALIDATION:
     *
     * - Owner ID must be positive.
     * - Name must not be blank.
     * - Name length must not exceed 200 characters.
     * - Currency must be three alphabetic characters.
     * - Fiscal-year start must be between 1 and 12.
     *
     * DEFAULTS:
     *
     * - Currency: USD.
     * - Fiscal-year starting month: January.
     *
     * ATOMICITY:
     *
     * The businesses INSERT and the membership INSERT
     * execute inside one Spring transaction.
     *
     * If membership creation fails, the newly inserted
     * business is rolled back as well.
     *
     * The caller must supply the authenticated user ID
     * as ownerId, never an HTTP request-body owner field.
     *
     * @param ownerId authenticated creator's user ID
     * @param name new business name
     * @param currency requested currency
     * @param fiscalYearStartMonth optional fiscal-year month
     * @return complete newly created business
     */
    @Transactional
    public Business createBusiness(
        Long ownerId,
        String name,
        String currency,
        Integer fiscalYearStartMonth
    ) {

        // ----------------------------------------------------
        // VALIDATE OWNER ID
        // ----------------------------------------------------

        validateOwnerId(ownerId);


        // ----------------------------------------------------
        // VALIDATE BUSINESS NAME
        // ----------------------------------------------------

        String validatedName =
            validateBusinessName(name);


        // ----------------------------------------------------
        // NORMALIZE CURRENCY
        // ----------------------------------------------------

        String validatedCurrency =
            validateCurrency(currency);


        // ----------------------------------------------------
        // VALIDATE FISCAL-YEAR START
        // ----------------------------------------------------

        int validatedStartMonth =
            validateFiscalYearStartMonth(
                fiscalYearStartMonth
            );


        // ----------------------------------------------------
        // CONSTRUCT BUSINESS ENTITY
        // ----------------------------------------------------

        Business business =
            new Business();

        business.setOwnerId(ownerId);
        business.setName(validatedName);
        business.setCurrency(validatedCurrency);
        business.setFiscalYearStartMonth(
            validatedStartMonth
        );


        // ----------------------------------------------------
        // INSERT BUSINESS
        // ----------------------------------------------------

        /**
         * MyBatis populates business.id using the
         * PostgreSQL-generated identity value.
         */
        int insertedBusinessRows =
            businessMapper.insert(business);


        // ----------------------------------------------------
        // VERIFY BUSINESS INSERT
        // ----------------------------------------------------

        if (
            insertedBusinessRows != 1 ||
            business.getId() == null
        ) {

            throw new IllegalStateException(
                "Failed to create business"
            );
        }


        // ----------------------------------------------------
        // INSERT OWNER MEMBERSHIP
        // ----------------------------------------------------

        /**
         * Every new business must immediately have
         * an OWNER membership.
         *
         * The ownerId is the same authenticated
         * user ID stored in businesses.owner_id.
         */
        int insertedMembershipRows =
            businessMapper.insertOwnerMembership(
                business.getId(),
                ownerId
            );


        // ----------------------------------------------------
        // VERIFY OWNER MEMBERSHIP INSERT
        // ----------------------------------------------------

        /**
         * Throwing an unchecked exception causes
         * Spring's @Transactional interceptor to
         * roll back both INSERT statements.
         */
        if (insertedMembershipRows != 1) {

            throw new IllegalStateException(
                "Failed to create business owner membership"
            );
        }


        // ----------------------------------------------------
        // RETRIEVE COMPLETE CREATED BUSINESS
        // ----------------------------------------------------

        /**
         * PostgreSQL generates created_at.
         *
         * Retrieve the row again to return all
         * database-generated fields.
         *
         * Reuse the existing owner-scoped lookup.
         */
        Business createdBusiness =
            businessMapper.findByIdAndOwnerId(
                business.getId(),
                ownerId
            );


        // ----------------------------------------------------
        // VERIFY CREATED BUSINESS
        // ----------------------------------------------------

        if (createdBusiness == null) {

            throw new IllegalStateException(
                "Created business could not be retrieved"
            );
        }


        // ----------------------------------------------------
        // RETURN CREATED BUSINESS
        // ----------------------------------------------------

        return createdBusiness;
    }


    // ========================================================
    // VALIDATE OWNER ID
    // ========================================================

    /**
     * Validate a canonical business owner ID.
     *
     * This validates the numeric format, not identity.
     *
     * Spring Security establishes the authenticated
     * identity before the controller calls this service.
     */
    private void validateOwnerId(
        Long ownerId
    ) {

        if (ownerId == null || ownerId <= 0) {

            throw new IllegalArgumentException(
                "Owner ID must be a positive number"
            );
        }
    }


    // ========================================================
    // VALIDATE AUTHENTICATED USER ID
    // ========================================================

    /**
     * Reject invalid user identifiers before
     * executing membership-scoped queries.
     */
    private void validateUserId(
        Long userId
    ) {

        if (userId == null || userId <= 0) {

            throw new IllegalArgumentException(
                "User ID must be a positive number"
            );
        }
    }


    // ========================================================
    // VALIDATE BUSINESS ID
    // ========================================================

    /**
     * Business IDs must be positive database identifiers.
     */
    private void validateBusinessId(
        Long businessId
    ) {

        if (businessId == null || businessId <= 0) {

            throw new IllegalArgumentException(
                "Business ID must be a positive number"
            );
        }
    }


    // ========================================================
    // VALIDATE BUSINESS NAME
    // ========================================================

    /**
     * Validate and normalize a new business name.
     *
     * PostgreSQL stores the value in VARCHAR(200).
     */
    private String validateBusinessName(
        String name
    ) {

        // ----------------------------------------------------
        // REQUIRE NONBLANK NAME
        // ----------------------------------------------------

        if (name == null || name.isBlank()) {

            throw new IllegalArgumentException(
                "Business name is required"
            );
        }


        // ----------------------------------------------------
        // REMOVE SURROUNDING WHITESPACE
        // ----------------------------------------------------

        String trimmedName =
            name.trim();


        // ----------------------------------------------------
        // ENFORCE MAXIMUM LENGTH
        // ----------------------------------------------------

        if (trimmedName.length() > 200) {

            throw new IllegalArgumentException(
                "Business name must not exceed 200 characters"
            );
        }

        return trimmedName;
    }


    // ========================================================
    // VALIDATE CURRENCY
    // ========================================================

    /**
     * Normalize currency to a three-letter uppercase code.
     *
     * This validates format only.
     *
     * It does not verify whether the code is an
     * officially assigned ISO 4217 currency.
     */
    private String validateCurrency(
        String currency
    ) {

        // ----------------------------------------------------
        // USE DEFAULT CURRENCY
        // ----------------------------------------------------

        if (currency == null) {
            return "USD";
        }


        // ----------------------------------------------------
        // NORMALIZE INPUT
        // ----------------------------------------------------

        String normalizedCurrency =
            currency.trim().toUpperCase(
                Locale.ROOT
            );


        // ----------------------------------------------------
        // VALIDATE THREE-LETTER FORMAT
        // ----------------------------------------------------

        if (!normalizedCurrency.matches("[A-Z]{3}")) {

            throw new IllegalArgumentException(
                "Currency must contain exactly three letters"
            );
        }

        return normalizedCurrency;
    }


    // ========================================================
    // VALIDATE FISCAL-YEAR STARTING MONTH
    // ========================================================

    /**
     * Validate a fiscal-year starting month.
     *
     * Examples:
     *
     * 1  = January
     * 4  = April
     * 7  = July
     * 10 = October
     *
     * A null month defaults to January.
     */
    private int validateFiscalYearStartMonth(
        Integer fiscalYearStartMonth
    ) {

        // ----------------------------------------------------
        // DEFAULT TO JANUARY
        // ----------------------------------------------------

        if (fiscalYearStartMonth == null) {
            return 1;
        }


        // ----------------------------------------------------
        // CHECK VALID MONTH RANGE
        // ----------------------------------------------------

        if (
            fiscalYearStartMonth < 1 ||
            fiscalYearStartMonth > 12
        ) {

            throw new IllegalArgumentException(
                "Fiscal-year starting month must be between 1 and 12"
            );
        }

        return fiscalYearStartMonth;
    }
}
