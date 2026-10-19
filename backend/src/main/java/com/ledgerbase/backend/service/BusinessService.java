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
 * Business logic for managing LedgerBase businesses.
 *
 * RESPONSIBILITIES:
 *
 * 1. List businesses belonging to an owner.
 * 2. Retrieve one business with an owner filter.
 * 3. Validate new business information.
 * 4. Create businesses through BusinessMapper.
 * 5. Return the newly created business.
 *
 * DATABASE ACCESS:
 *
 * All SQL operations are delegated to BusinessMapper.
 *
 * SECURITY NOTE:
 *
 * ownerId must eventually come from the authenticated
 * user's identity, NOT directly from an HTTP request.
 *
 * Until authentication is implemented, this service
 * is intended for our local development workflow.
 *
 * Owner-scoped SQL queries alone do not provide
 * complete authentication or authorization.
 */
@Service
public class BusinessService {

    // ========================================================
    // DEPENDENCIES
    // ========================================================

    /**
     * MyBatis mapper responsible for business queries.
     */
    private final BusinessMapper businessMapper;


    // ========================================================
    // CONSTRUCTOR INJECTION
    // ========================================================

    /**
     * Spring Boot automatically supplies BusinessMapper.
     *
     * Constructor injection makes dependencies explicit
     * and simplifies testing.
     */
    public BusinessService(BusinessMapper businessMapper) {
        this.businessMapper = businessMapper;
    }


    // ========================================================
    // 1. LIST BUSINESSES BELONGING TO AN OWNER
    // ========================================================

    /**
     * Return all businesses belonging to an owner.
     *
     * Example:
     *
     * ownerId = 1
     *
     * Possible result:
     *
     * - LedgerBase Demo Business
     * - ABC Consulting LLC
     *
     * If the owner has no businesses, an empty list
     * is returned.
     *
     * @Transactional(readOnly = true) indicates that
     * this operation does not modify the database.
     *
     * @param ownerId owner whose businesses are requested
     * @return list of businesses owned by that user
     */
    @Transactional(readOnly = true)
    public List<Business> getBusinessesByOwner(Long ownerId) {

        // ====================================================
        // VALIDATE OWNER ID
        // ====================================================

        validateOwnerId(ownerId);

        // ====================================================
        // QUERY DATABASE
        // ====================================================

        return businessMapper.findAllByOwnerId(ownerId);
    }


    // ========================================================
    // 2. RETRIEVE ONE BUSINESS
    // ========================================================

    /**
     * Retrieve a business only if the owner matches.
     *
     * Both businessId and ownerId are required.
     *
     * If the business does not exist OR belongs to
     * another owner, throw BusinessNotFoundException.
     *
     * Using the same exception for both situations
     * avoids revealing whether another owner's
     * business exists.
     *
     * @param businessId requested business ID
     * @param ownerId expected owner ID
     * @return matching business
     */
    @Transactional(readOnly = true)
    public Business getBusinessById(
        Long businessId,
        Long ownerId
    ) {

        // ====================================================
        // VALIDATE IDENTIFIERS
        // ====================================================

        validateBusinessId(businessId);

        validateOwnerId(ownerId);

        // ====================================================
        // QUERY BUSINESS USING BOTH IDS
        // ====================================================

        Business business =
            businessMapper.findByIdAndOwnerId(
                businessId,
                ownerId
            );

        // ====================================================
        // HANDLE MISSING OR NONMATCHING BUSINESS
        // ====================================================

        if (business == null) {
            throw new BusinessNotFoundException(businessId);
        }

        // ====================================================
        // RETURN MATCHING BUSINESS
        // ====================================================

        return business;
    }


    // ========================================================
    // 3. CREATE A NEW BUSINESS
    // ========================================================

    /**
     * Create a new business for an existing owner.
     *
     * Example:
     *
     * ownerId = 1
     * name = "ABC Consulting LLC"
     * currency = "USD"
     * fiscalYearStartMonth = 1
     *
     * VALIDATION:
     *
     * - Owner ID must be positive.
     * - Business name must not be blank.
     * - Business name must contain at most 200 characters.
     * - Currency must be a three-letter code.
     * - Fiscal-year starting month must be 1 through 12.
     *
     * DEFAULT VALUES:
     *
     * - Null currency becomes USD.
     * - Null fiscal-year start becomes January (1).
     *
     * DATABASE SAFEGUARDS:
     *
     * PostgreSQL generates the business ID.
     *
     * PostgreSQL also enforces the owner foreign key,
     * so the owner must exist in users.
     *
     * @Transactional ensures that the INSERT and
     * subsequent SELECT run within one transaction.
     *
     * @return newly created business, including its
     *         database-generated ID and creation time
     */
    @Transactional
    public Business createBusiness(
        Long ownerId,
        String name,
        String currency,
        Integer fiscalYearStartMonth
    ) {

        // ====================================================
        // VALIDATE OWNER
        // ====================================================

        validateOwnerId(ownerId);


        // ====================================================
        // VALIDATE BUSINESS NAME
        // ====================================================

        String validatedName = validateBusinessName(name);


        // ====================================================
        // VALIDATE AND NORMALIZE CURRENCY
        // ====================================================

        String validatedCurrency =
            validateCurrency(currency);


        // ====================================================
        // VALIDATE FISCAL-YEAR STARTING MONTH
        // ====================================================

        int validatedStartMonth =
            validateFiscalYearStartMonth(
                fiscalYearStartMonth
            );


        // ====================================================
        // CONSTRUCT BUSINESS MODEL
        // ====================================================

        Business business = new Business();

        business.setOwnerId(ownerId);
        business.setName(validatedName);
        business.setCurrency(validatedCurrency);
        business.setFiscalYearStartMonth(
            validatedStartMonth
        );


        // ====================================================
        // INSERT BUSINESS THROUGH MYBATIS
        // ====================================================

        /**
         * MyBatis uses PostgreSQL-generated keys
         * to populate business.id.
         */
        int insertedRows = businessMapper.insert(business);


        // ====================================================
        // VERIFY INSERT SUCCESS
        // ====================================================

        if (insertedRows != 1 || business.getId() == null) {

            throw new IllegalStateException(
                "Failed to create business"
            );
        }


        // ====================================================
        // RETRIEVE COMPLETE CREATED BUSINESS
        // ====================================================

        /**
         * The inserted Business object contains its
         * generated ID, but createdAt is generated
         * by PostgreSQL.
         *
         * Retrieve the row again to return all fields,
         * including the creation timestamp.
         */
        Business createdBusiness =
            businessMapper.findByIdAndOwnerId(
                business.getId(),
                ownerId
            );


        // ====================================================
        // VERIFY CREATED BUSINESS CAN BE RETRIEVED
        // ====================================================

        if (createdBusiness == null) {

            throw new IllegalStateException(
                "Created business could not be retrieved"
            );
        }

        return createdBusiness;
    }


    // ========================================================
    // 4. VALIDATE OWNER ID
    // ========================================================

    /**
     * Owner IDs must be positive database identifiers.
     *
     * This validation does not prove that the owner
     * exists or is authenticated.
     *
     * The database foreign key enforces existence
     * when a new business is inserted.
     */
    private void validateOwnerId(Long ownerId) {

        if (ownerId == null || ownerId <= 0) {

            throw new IllegalArgumentException(
                "Owner ID must be a positive number"
            );
        }
    }


    // ========================================================
    // 5. VALIDATE BUSINESS ID
    // ========================================================

    /**
     * Reject null or nonpositive business IDs.
     */
    private void validateBusinessId(Long businessId) {

        if (businessId == null || businessId <= 0) {

            throw new IllegalArgumentException(
                "Business ID must be a positive number"
            );
        }
    }


    // ========================================================
    // 6. VALIDATE BUSINESS NAME
    // ========================================================

    /**
     * Remove leading and trailing spaces.
     *
     * PostgreSQL defines:
     *
     * name VARCHAR(200) NOT NULL
     *
     * It also rejects empty or whitespace-only names.
     *
     * We validate the same basic requirements
     * before sending data to PostgreSQL.
     */
    private String validateBusinessName(String name) {

        if (name == null || name.isBlank()) {

            throw new IllegalArgumentException(
                "Business name is required"
            );
        }

        String trimmedName = name.trim();

        if (trimmedName.length() > 200) {

            throw new IllegalArgumentException(
                "Business name must not exceed 200 characters"
            );
        }

        return trimmedName;
    }


    // ========================================================
    // 7. VALIDATE CURRENCY
    // ========================================================

    /**
     * The database requires exactly three uppercase
     * alphabetic characters.
     *
     * Examples:
     *
     * USD
     * GBP
     * EUR
     *
     * This checks the three-letter format.
     *
     * It does not verify that a code is an officially
     * assigned ISO 4217 currency.
     */
    private String validateCurrency(String currency) {

        // ====================================================
        // APPLY DEFAULT
        // ====================================================

        if (currency == null) {
            return "USD";
        }

        // ====================================================
        // NORMALIZE INPUT
        // ====================================================

        String normalizedCurrency =
            currency.trim().toUpperCase(Locale.ROOT);

        // ====================================================
        // VALIDATE THREE-LETTER FORMAT
        // ====================================================

        if (!normalizedCurrency.matches("[A-Z]{3}")) {

            throw new IllegalArgumentException(
                "Currency must contain exactly three letters"
            );
        }

        return normalizedCurrency;
    }


    // ========================================================
    // 8. VALIDATE FISCAL-YEAR STARTING MONTH
    // ========================================================

    /**
     * Valid months are 1 through 12.
     *
     * Examples:
     *
     * 1  = January
     * 4  = April
     * 7  = July
     * 10 = October
     *
     * If the caller does not specify a month,
     * LedgerBase uses January.
     */
    private int validateFiscalYearStartMonth(
        Integer fiscalYearStartMonth
    ) {

        // ====================================================
        // APPLY DEFAULT
        // ====================================================

        if (fiscalYearStartMonth == null) {
            return 1;
        }

        // ====================================================
        // VALIDATE MONTH RANGE
        // ====================================================

        if (
            fiscalYearStartMonth < 1
            || fiscalYearStartMonth > 12
        ) {

            throw new IllegalArgumentException(
                "Fiscal-year starting month must be between 1 and 12"
            );
        }

        return fiscalYearStartMonth;
    }
}
