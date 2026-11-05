import { useState, type FormEvent } from 'react'

import {
  ArrowRight,
  Building2,
  ShieldCheck,
} from 'lucide-react'

import {
  createBusiness,
  type Business,
} from '../api/businesses'

import './FirstBusinessOnboarding.css'

// ============================================================
// LEDGERBASE — FIRST BUSINESS ONBOARDING
// ============================================================

/**
 * Help an authenticated user create their first
 * LedgerBase business.
 *
 * WORKFLOW:
 *
 * - Enter a business name.
 * - Select a base currency.
 * - Select the fiscal-year start month.
 * - Submit the authenticated business creation request.
 * - Notify App.tsx when the business is created.
 *
 * SECURITY:
 *
 * - The frontend never submits an owner ID.
 * - The backend derives ownership from the JWT.
 * - The backend creates the OWNER membership.
 * - App.tsx independently verifies the new role.
 */

// ============================================================
// COMPONENT PROPS
// ============================================================

interface FirstBusinessOnboardingProps {

  /**
   * Called only after the backend successfully
   * returns the newly created business.
   *
   * App.tsx will update its business state and
   * verify the creator's membership role.
   */
  onBusinessCreated: (business: Business) => void
}


// ============================================================
// CURRENCY OPTIONS
// ============================================================

/**
 * Initial currency choices for LedgerBase onboarding.
 *
 * Additional currencies can be supported later.
 */
const CURRENCY_OPTIONS = [
  { value: 'USD', label: 'USD — US Dollar' },
  { value: 'GBP', label: 'GBP — British Pound' },
  { value: 'EUR', label: 'EUR — Euro' },
]


// ============================================================
// FISCAL-YEAR MONTH OPTIONS
// ============================================================

const FISCAL_MONTH_OPTIONS = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
]


// ============================================================
// ONBOARDING COMPONENT
// ============================================================

function FirstBusinessOnboarding({
  onBusinessCreated,
}: FirstBusinessOnboardingProps) {

  // ==========================================================
  // FORM STATE
  // ==========================================================

  const [businessName, setBusinessName] =
    useState('')

  const [currency, setCurrency] =
    useState('USD')

  const [fiscalYearStartMonth, setFiscalYearStartMonth] =
    useState(1)


  // ==========================================================
  // SUBMISSION STATE
  // ==========================================================

  const [isSubmitting, setIsSubmitting] =
    useState(false)

  const [errorMessage, setErrorMessage] =
    useState<string | null>(null)


  // ==========================================================
  // HANDLE BUSINESS CREATION
  // ==========================================================

  async function handleSubmit(
    event: FormEvent<HTMLFormElement>,
  ): Promise<void> {

    event.preventDefault()

    // Prevent repeated submissions.
    if (isSubmitting) {
      return
    }

    setErrorMessage(null)


    // --------------------------------------------------------
    // VALIDATE BUSINESS NAME
    // --------------------------------------------------------

    const normalizedName = businessName.trim()

    if (
      normalizedName.length === 0 ||
      normalizedName.length > 200
    ) {

      setErrorMessage(
        'Enter a business name of up to 200 characters.',
      )

      return
    }


    // --------------------------------------------------------
    // SUBMIT AUTHENTICATED BUSINESS CREATION
    // --------------------------------------------------------

    setIsSubmitting(true)

    try {

      /**
       * The shared API client automatically attaches
       * the current user's verified access JWT.
       *
       * The backend determines ownerId and creates
       * the corresponding OWNER membership.
       */
      const createdBusiness = await createBusiness({
        name: normalizedName,
        currency,
        fiscalYearStartMonth,
      })


      // ------------------------------------------------------
      // VERIFY CREATED BUSINESS ID
      // ------------------------------------------------------

      if (
        !Number.isSafeInteger(createdBusiness.id) ||
        createdBusiness.id <= 0
      ) {

        throw new Error(
          'The server returned an invalid business ID.',
        )
      }


      // ------------------------------------------------------
      // NOTIFY PARENT APPLICATION
      // ------------------------------------------------------

      /**
       * App.tsx will:
       *
       * - Add the new business to its available list.
       * - Select the newly created business.
       * - Reset previously verified role information.
       * - Retrieve the new OWNER membership.
       * - Display the accounting workspace.
       */
      onBusinessCreated(createdBusiness)

    } catch {

      // ------------------------------------------------------
      // HANDLE BUSINESS CREATION ERRORS
      // ------------------------------------------------------

      /**
       * Display a safe error message without exposing
       * server internals or authentication credentials.
       */
      setErrorMessage(
        'Unable to create your business. ' +
        'Please try again.',
      )

    } finally {

      setIsSubmitting(false)

    }
  }


  // ==========================================================
  // RENDER FIRST BUSINESS ONBOARDING
  // ==========================================================

  return (
    <section
      className="lb-onboarding-page"
      aria-labelledby="lb-onboarding-title"
    >

      {/* ==================================================== */}
      {/* INTRODUCTION */}
      {/* ==================================================== */}

      <div className="lb-onboarding-intro">

        <div className="lb-onboarding-icon">
          <Building2
            size={25}
            strokeWidth={1.8}
            aria-hidden="true"
          />
        </div>

        <p className="lb-onboarding-eyebrow">
          WELCOME TO LEDGERBASE
        </p>

        <h1 id="lb-onboarding-title">
          Set up your workspace
        </h1>

        <p className="lb-onboarding-description">
          Create your first business to start managing
          your accounts, transactions, and financial
          reports.
        </p>

      </div>


      {/* ==================================================== */}
      {/* BUSINESS SETUP FORM */}
      {/* ==================================================== */}

      <div className="lb-onboarding-card">

        <div className="lb-onboarding-card-heading">

          <h2>Business details</h2>

          <p>
            Configure the basic information for
            your financial workspace.
          </p>

        </div>


        <form
          className="lb-onboarding-form"
          onSubmit={(event) => {
            void handleSubmit(event)
          }}
        >

          {/* ================================================ */}
          {/* BUSINESS NAME */}
          {/* ================================================ */}

          <div className="lb-onboarding-field">

            <label htmlFor="lb-onboarding-name">
              Business name
            </label>

            <input
              id="lb-onboarding-name"
              name="businessName"
              type="text"
              placeholder="e.g. Acme Consulting LLC"
              autoComplete="organization"
              maxLength={200}
              required
              value={businessName}
              disabled={isSubmitting}
              onChange={(event) => {
                setBusinessName(event.target.value)
                setErrorMessage(null)
              }}
            />

          </div>


          {/* ================================================ */}
          {/* BASE CURRENCY */}
          {/* ================================================ */}

          <div className="lb-onboarding-field">

            <label htmlFor="lb-onboarding-currency">
              Base currency
            </label>

            <select
              id="lb-onboarding-currency"
              name="currency"
              value={currency}
              disabled={isSubmitting}
              onChange={(event) => {
                setCurrency(event.target.value)
                setErrorMessage(null)
              }}
            >

              {CURRENCY_OPTIONS.map((option) => (
                <option
                  key={option.value}
                  value={option.value}
                >
                  {option.label}
                </option>
              ))}

            </select>

          </div>


          {/* ================================================ */}
          {/* FISCAL YEAR START MONTH */}
          {/* ================================================ */}

          <div className="lb-onboarding-field">

            <label htmlFor="lb-onboarding-month">
              Fiscal year starts in
            </label>

            <select
              id="lb-onboarding-month"
              name="fiscalYearStartMonth"
              value={fiscalYearStartMonth}
              disabled={isSubmitting}
              onChange={(event) => {
                setFiscalYearStartMonth(
                  Number(event.target.value),
                )
                setErrorMessage(null)
              }}
            >

              {FISCAL_MONTH_OPTIONS.map((month, index) => (
                <option
                  key={month}
                  value={index + 1}
                >
                  {month}
                </option>
              ))}

            </select>

            <p className="lb-onboarding-field-hint">
              You can configure accounting periods
              after creating your business.
            </p>

          </div>


          {/* ================================================ */}
          {/* ERROR MESSAGE */}
          {/* ================================================ */}

          {errorMessage && (

            <p
              className="lb-onboarding-error"
              role="alert"
            >
              {errorMessage}
            </p>

          )}


          {/* ================================================ */}
          {/* CREATE BUSINESS BUTTON */}
          {/* ================================================ */}

          <button
            type="submit"
            className="lb-onboarding-submit"
            disabled={isSubmitting}
          >

            <span>
              {isSubmitting
                ? 'Creating business...'
                : 'Create business'}
            </span>

            {!isSubmitting && (
              <ArrowRight
                size={18}
                aria-hidden="true"
              />
            )}

          </button>

        </form>

      </div>


      {/* ==================================================== */}
      {/* OWNERSHIP AND ACCESS INFORMATION */}
      {/* ==================================================== */}

      <div className="lb-onboarding-security-note">

        <ShieldCheck
          size={18}
          strokeWidth={1.8}
          aria-hidden="true"
        />

        <p>
          Your business workspace is private.
          You will be its owner, with control
          over its financial records.
        </p>

      </div>

    </section>
  )
}


// ============================================================
// EXPORT ONBOARDING COMPONENT
// ============================================================

export default FirstBusinessOnboarding
