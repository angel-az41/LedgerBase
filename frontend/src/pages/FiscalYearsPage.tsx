import {
  useEffect,
  useState,
  type FormEvent,
} from 'react'

import {
  getFiscalYears,
  createFiscalYear,
  type FiscalYear,
} from '../api/fiscalYears'

import {
  getAccountingPeriods,
  closeAccountingPeriod,
  reopenAccountingPeriod,
  type AccountingPeriod,
} from '../api/accountingPeriods'

import './FiscalYearsPage.css'

// ============================================================
// LEDGERBASE — FISCAL YEARS & ACCOUNTING PERIODS PAGE
// ============================================================

/**
 * Manage a business's fiscal years and accounting periods.
 *
 * Responsibilities:
 *
 * 1. Retrieve and select fiscal years.
 * 2. Create new fiscal years.
 * 3. Display all monthly accounting periods.
 * 4. Show OPEN and CLOSED period statuses.
 * 5. Request accounting-period closing and reopening.
 * 6. Confirm actions before changing posting permissions.
 * 7. Display backend errors and success messages.
 *
 * All accounting rules are enforced by Spring Boot
 * and PostgreSQL, not by this React component.
 */

// ============================================================
// COMPONENT PROPERTIES
// ============================================================

/**
 * The current business determines which fiscal years
 * and accounting periods the page manages.
 *
 * SECURITY:
 *
 * No actorId prop is needed.
 *
 * The backend obtains the authenticated user
 * from the verified JWT.
 */
interface FiscalYearsPageProps {

  // Currently selected business.
  businessId: number

  /**
   * Whether the authenticated user may perform
   * accounting write operations for this business.
   *
   * OWNER / ADMIN / ACCOUNTANT: true
   * VIEWER: false
   *
   * App.tsx retrieves the current membership role
   * from Spring Boot and derives this permission.
   *
   * Backend authorization remains authoritative.
   */
  canWrite: boolean
}

// ============================================================
// PERIOD ACTION TYPE
// ============================================================

/**
 * Tracks the period and operation awaiting confirmation.
 */
interface PendingPeriodAction {
  periodId: number
  action: 'CLOSE' | 'REOPEN'
}

// ============================================================
// DATE FORMATTING
// ============================================================

/**
 * Convert YYYY-MM-DD into a readable US date.
 *
 * Adding local midnight avoids UTC date-only
 * parsing shifting the calendar date.
 */
function formatFiscalDate(date: string): string {

  return new Date(`${date}T00:00:00`).toLocaleDateString(
    'en-US',
    {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    },
  )
}

/**
 * Display the accounting period's calendar month.
 *
 * Works for calendar and non-calendar fiscal years.
 */
function formatPeriodMonth(date: string): string {

  return new Date(`${date}T00:00:00`).toLocaleDateString(
    'en-US',
    {
      month: 'long',
      year: 'numeric',
    },
  )
}

// ============================================================
// FISCAL YEARS PAGE COMPONENT
// ============================================================

function FiscalYearsPage({
  businessId,
  canWrite,
}: FiscalYearsPageProps) {

  // ==========================================================
  // FISCAL YEAR STATE
  // ==========================================================

  const [fiscalYears, setFiscalYears] =
    useState<FiscalYear[]>([])

  const [selectedFiscalYearId, setSelectedFiscalYearId] =
    useState<number | null>(null)

  const [isYearsLoading, setIsYearsLoading] =
    useState(true)

  const [yearsError, setYearsError] =
    useState<string | null>(null)

  // ==========================================================
  // CREATE FISCAL YEAR STATE
  // ==========================================================

  const [showCreateForm, setShowCreateForm] =
    useState(false)

  const [newFiscalYearEndYear, setNewFiscalYearEndYear] =
    useState('')

  const [isCreatingFiscalYear, setIsCreatingFiscalYear] =
    useState(false)

  const [createFiscalYearError, setCreateFiscalYearError] =
    useState<string | null>(null)

  const [createFiscalYearSuccess, setCreateFiscalYearSuccess] =
    useState<string | null>(null)

  // ==========================================================
  // ACCOUNTING PERIOD STATE
  // ==========================================================

  const [periods, setPeriods] =
    useState<AccountingPeriod[]>([])

  const [isPeriodsLoading, setIsPeriodsLoading] =
    useState(false)

  const [periodsError, setPeriodsError] =
    useState<string | null>(null)

  // ==========================================================
  // ACCOUNTING PERIOD ACTION STATE
  // ==========================================================

  /**
   * The period action currently awaiting confirmation.
   *
   * null means no confirmation is open.
   */
  const [pendingPeriodAction, setPendingPeriodAction] =
    useState<PendingPeriodAction | null>(null)

  /**
   * Whether Spring Boot is processing a Close/Reopen
   * request.
   *
   * While this is true, we disable period controls.
   */
  const [isSavingPeriod, setIsSavingPeriod] =
    useState(false)

  const [periodActionError, setPeriodActionError] =
    useState<string | null>(null)

  const [periodActionSuccess, setPeriodActionSuccess] =
    useState<string | null>(null)

  // ==========================================================
  // LOAD FISCAL YEARS
  // ==========================================================

  /**
   * Load fiscal years when the component mounts
   * or the selected business changes.
   *
   * The backend returns newest-first results.
   *
   * The cancelled flag prevents outdated responses
   * from replacing state after unmounting.
   */
  useEffect(() => {

    let cancelled = false

    async function loadFiscalYears() {

      setIsYearsLoading(true)
      setYearsError(null)

      setFiscalYears([])
      setSelectedFiscalYearId(null)
      setPeriods([])

      setShowCreateForm(false)
      setNewFiscalYearEndYear('')
      setCreateFiscalYearError(null)
      setCreateFiscalYearSuccess(null)

      setPendingPeriodAction(null)
      setPeriodActionError(null)
      setPeriodActionSuccess(null)

      try {

        const years = await getFiscalYears(businessId)

        if (cancelled) {
          return
        }

        setFiscalYears(years)

        setSelectedFiscalYearId(
          years.length > 0
            ? years[0].id
            : null,
        )

      } catch (error) {

        if (cancelled) {
          return
        }

        setYearsError(
          error instanceof Error
            ? error.message
            : 'Unable to load fiscal years.',
        )

      } finally {

        if (!cancelled) {
          setIsYearsLoading(false)
        }
      }
    }

    void loadFiscalYears()

    return () => {
      cancelled = true
    }

  }, [businessId])

  // ==========================================================
  // HANDLE FISCAL YEAR SELECTION
  // ==========================================================

  /**
   * Update the selected fiscal year and clear action
   * state belonging to the previously selected year.
   *
   * WHY CLEAR THESE STATES?
   *
   * A pending Close/Reopen confirmation, error, or
   * success message belongs to a specific fiscal year.
   *
   * When the user switches years, that information
   * must not appear in the newly selected workspace.
   *
   * The accounting-period loading effect will run
   * automatically when selectedFiscalYearId changes.
   *
   * Keeping these updates in an event handler avoids
   * synchronous React state updates inside useEffect.
   */
  function handleFiscalYearSelection(
    fiscalYearId: number,
  ): void {

    // --------------------------------------------------------
    // CLEAR PREVIOUS PERIOD ACTION STATE
    // --------------------------------------------------------

    setPendingPeriodAction(null)

    setPeriodActionError(null)

    setPeriodActionSuccess(null)


    // --------------------------------------------------------
    // SELECT THE NEW FISCAL YEAR
    // --------------------------------------------------------

    setSelectedFiscalYearId(fiscalYearId)
  }

  // ==========================================================
  // LOAD ACCOUNTING PERIODS
  // ==========================================================

  /**
   * Retrieve the accounting periods for the selected
   * fiscal year.
   *
   * The backend returns the actual accounting periods
   * ordered by period number.
   *
   * React automatically reruns this effect when:
   *
   * - The selected fiscal year changes.
   * - The active business changes.
   *
   * Pending action state is cleared by the selection
   * handler rather than synchronously in this effect.
   *
   * The cancelled flag prevents outdated requests
   * from updating state after a selection change.
   */
  useEffect(() => {

    // --------------------------------------------------------
    // REQUIRE A SELECTED FISCAL YEAR
    // --------------------------------------------------------

    /**
     * When no fiscal year is selected, do not issue
     * an accounting-period request.
     *
     * Initial state and the fiscal-year loading workflow
     * already clear the previous period data.
     */
    if (selectedFiscalYearId === null) {
      return
    }

    const fiscalYearId = selectedFiscalYearId

    let cancelled = false


    // --------------------------------------------------------
    // LOAD ACCOUNTING PERIODS FROM SPRING BOOT
    // --------------------------------------------------------

    async function loadPeriods() {

      setIsPeriodsLoading(true)

      setPeriodsError(null)

      setPeriods([])

      try {

        // ----------------------------------------------------
        // REQUEST DATABASE RECORDS
        // ----------------------------------------------------

        const result = await getAccountingPeriods(
          businessId,
          fiscalYearId,
        )


        // ----------------------------------------------------
        // IGNORE OUTDATED REQUESTS
        // ----------------------------------------------------

        if (cancelled) {
          return
        }


        // ----------------------------------------------------
        // UPDATE THE PERIOD TABLE
        // ----------------------------------------------------

        setPeriods(result)

      } catch (error) {

        if (cancelled) {
          return
        }

        setPeriodsError(
          error instanceof Error
            ? error.message
            : 'Unable to load accounting periods.',
        )

      } finally {

        if (!cancelled) {
          setIsPeriodsLoading(false)
        }
      }
    }


    // --------------------------------------------------------
    // START ASYNCHRONOUS DATA LOADING
    // --------------------------------------------------------

    void loadPeriods()


    // --------------------------------------------------------
    // CLEAN UP OUTDATED REQUESTS
    // --------------------------------------------------------

    return () => {
      cancelled = true
    }

  }, [businessId, selectedFiscalYearId])

  // ==========================================================
  // SELECTED FISCAL YEAR
  // ==========================================================

  const selectedFiscalYear =
    fiscalYears.find(
      year => year.id === selectedFiscalYearId,
    ) ?? null

  // ==========================================================
  // ACCOUNTING PERIOD COUNTS
  // ==========================================================

  /**
   * Counts update automatically whenever the
   * periods state changes.
   *
   * After a successful Close/Reopen request,
   * the table and counters remain consistent.
   */
  const openPeriodCount =
    periods.filter(
      period => period.status === 'OPEN',
    ).length

  const closedPeriodCount =
    periods.filter(
      period => period.status === 'CLOSED',
    ).length

  // ==========================================================
  // PENDING ACTION PERIOD
  // ==========================================================

  /**
   * Find the accounting period awaiting confirmation.
   *
   * We use the actual backend period record to
   * display its name and dates.
   */
  const pendingPeriod =
    periods.find(
      period =>
        period.id === pendingPeriodAction?.periodId,
    ) ?? null

  // ==========================================================
  // CREATE FISCAL YEAR
  // ==========================================================

  /**
   * Submit a new fiscal-year request.
   *
   * React validates the input for usability.
   * Spring Boot performs authoritative validation.
   */
  async function handleCreateFiscalYear(
    event: FormEvent<HTMLFormElement>,
  ): Promise<void> {

    // ----------------------------------------------------------
    // PREVENT NATIVE FORM SUBMISSION
    // ----------------------------------------------------------

    event.preventDefault()

    // ----------------------------------------------------------
    // REQUIRE ACCOUNTING WRITE PERMISSION
    // ----------------------------------------------------------

    /**
     * VIEWER users cannot submit fiscal-year creation.
     *
     * Also prevent duplicate requests while another
     * creation request is processing.
     *
     * Spring Security independently enforces the
     * user's current database membership permission.
     */
    if (!canWrite || isCreatingFiscalYear) {
      return
    }

    const requestedYear =
      Number(newFiscalYearEndYear.trim())

    // ========================================================
    // VALIDATE YEAR
    // ========================================================

    if (
      newFiscalYearEndYear.trim() === '' ||
      !Number.isInteger(requestedYear) ||
      requestedYear < 1900 ||
      requestedYear > 9999
    ) {

      setCreateFiscalYearError(
        'Enter a fiscal-year ending year between 1900 and 9999.',
      )

      return
    }

    // ========================================================
    // SUBMIT CREATION REQUEST
    // ========================================================

    setIsCreatingFiscalYear(true)
    setCreateFiscalYearError(null)
    setCreateFiscalYearSuccess(null)

    try {

      const createdYear = await createFiscalYear(
        businessId,
        {
          fiscalYearEndYear: requestedYear,
        },
      )

      /**
       * Store the record returned by Spring Boot
       * and preserve newest-first ordering.
       */
      setFiscalYears(previousYears => {

        return [
          ...previousYears,
          createdYear,
        ].sort(
          (first, second) =>
            second.endDate.localeCompare(
              first.endDate,
            ),
        )
      })

      // Select the newly created fiscal year.
      //
      // This also clears any confirmation or action messages
      // associated with the previously selected fiscal year.

      handleFiscalYearSelection(createdYear.id)

      setYearsError(null)
      setShowCreateForm(false)
      setNewFiscalYearEndYear('')

      setCreateFiscalYearSuccess(
        `${createdYear.label} was created successfully with 12 accounting periods.`,
      )

    } catch (error) {

      setCreateFiscalYearError(
        error instanceof Error
          ? error.message
          : 'Unable to create the fiscal year.',
      )

    } finally {

      setIsCreatingFiscalYear(false)
    }
  }

  // ==========================================================
  // PREPARE CLOSE / REOPEN ACTION
  // ==========================================================

  /**
   * Display a confirmation before making a change.
   *
   * Clicking Close or Reopen does not immediately
   * send a request to Spring Boot.
   *
   * The user must explicitly confirm the action.
   */
  function preparePeriodAction(
    period: AccountingPeriod,
  ): void {

    /**
     * VIEWER users cannot initiate period status changes.
     *
     * The current transaction workflow must also be idle.
     */
    if (!canWrite || isSavingPeriod) {
      return
    }

    setPendingPeriodAction({
      periodId: period.id,
      action:
        period.status === 'OPEN'
          ? 'CLOSE'
          : 'REOPEN',
    })

    setPeriodActionError(null)
    setPeriodActionSuccess(null)
  }

  // ==========================================================
  // CONFIRM CLOSE / REOPEN ACTION
  // ==========================================================

  /**
   * Submit a confirmed accounting-period status change.
   *
   * Backend safeguards:
   *
   * - Period must belong to the business.
   * - Status transition must be valid.
   * - Database locking coordinates with posting.
   * - Audit event is recorded transactionally.
   *
   * On success, React replaces the affected period
   * with the updated record returned by Spring Boot.
   */
  async function handleConfirmPeriodAction(): Promise<void> {

    /**
     * The confirmation handler independently checks
     * canWrite before issuing any write request.
     *
     * This prevents a pending confirmation from being
     * submitted after write permission is revoked in
     * the current React state.
     */
    if (
      !canWrite ||
      pendingPeriodAction === null ||
      pendingPeriod === null ||
      isSavingPeriod
    ) {
      return
    }

    const action = pendingPeriodAction.action
    const periodId = pendingPeriod.id

    const periodName = formatPeriodMonth(
      pendingPeriod.startDate,
    )

    // ========================================================
    // BEGIN BACKEND REQUEST
    // ========================================================

    setIsSavingPeriod(true)
    setPeriodActionError(null)
    setPeriodActionSuccess(null)

    try {

      let updatedPeriod: AccountingPeriod

      if (action === 'CLOSE') {

        updatedPeriod = await closeAccountingPeriod(
          businessId,
          periodId,
        )

      } else {

        updatedPeriod = await reopenAccountingPeriod(
          businessId,
          periodId,
        )

      }

      // ======================================================
      // UPDATE THE ACCOUNTING PERIOD TABLE
      // ======================================================

      /**
       * Replace only the period changed by Spring Boot.
       *
       * Never guess the resulting status in React.
       *
       * The backend response is authoritative.
       */
      setPeriods(previousPeriods =>
        previousPeriods.map(period =>
          period.id === updatedPeriod.id
            ? updatedPeriod
            : period,
        ),
      )

      // ======================================================
      // DISPLAY SUCCESS CONFIRMATION
      // ======================================================

      setPeriodActionSuccess(
        action === 'CLOSE'
          ? `${periodName} was closed successfully. New journal postings into this period are blocked.`
          : `${periodName} was reopened successfully.`,
      )

      setPendingPeriodAction(null)

    } catch (error) {

      // ======================================================
      // HANDLE FAILED STATUS TRANSITION
      // ======================================================

      setPeriodActionError(
        error instanceof Error
          ? error.message
          : 'Unable to update the accounting period.',
      )

      /**
       * Reload periods after a failed action.
       *
       * Another operation may have changed the
       * period's status in the database.
       *
       * Refreshing helps avoid displaying stale data.
       */
      if (selectedFiscalYearId !== null) {

        try {

          const refreshedPeriods =
            await getAccountingPeriods(
              businessId,
              selectedFiscalYearId,
            )

          setPeriods(refreshedPeriods)

        } catch {

          // Preserve the original action error.
          // A later page refresh can retrieve new data.
        }
      }

      setPendingPeriodAction(null)

    } finally {

      setIsSavingPeriod(false)
    }
  }

  // ==========================================================
  // RENDER PAGE
  // ==========================================================

  return (

    <div className="fy-page">

      {/* ==================================================== */}
      {/* PAGE HEADER */}
      {/* ==================================================== */}

      <header className="fy-page-header">

        <h1>Fiscal Years & Accounting Periods</h1>

        <p>
          Manage your accounting calendar and review
          monthly posting periods.
        </p>

      </header>

      {/* ==================================================== */}
      {/* FISCAL YEAR SELECTION CARD */}
      {/* ==================================================== */}

      <section className="fy-controls-card">

        <div className="fy-year-header-row">

          <div className="fy-section-heading">

            <h2>Fiscal Year</h2>

            <p>
              Select a fiscal year to review its
              accounting periods.
            </p>

          </div>

          {canWrite && (

            <button
              type="button"
              className="fy-create-toggle"
              disabled={
                isCreatingFiscalYear ||
                isYearsLoading ||
                isSavingPeriod
              }
              aria-expanded={showCreateForm}
              aria-controls="fy-create-form"
              onClick={() => {

                setShowCreateForm(
                  previous => !previous,
                )

                setCreateFiscalYearError(null)
                setCreateFiscalYearSuccess(null)

              }}
            >
              {showCreateForm
                ? 'Cancel'
                : '+ Create Fiscal Year'}
            </button>

          )}

        </div>

        {/* ================================================== */}
        {/* CREATE SUCCESS MESSAGE */}
        {/* ================================================== */}

        {createFiscalYearSuccess && (

          <p
            className="fy-create-success"
            role="status"
          >
            {createFiscalYearSuccess}
          </p>

        )}

        {/* ================================================== */}
        {/* CREATE FISCAL YEAR FORM */}
        {/* ================================================== */}

        {canWrite && showCreateForm && (

          <form
            id="fy-create-form"
            className="fy-create-form"
            onSubmit={event => {
              void handleCreateFiscalYear(event)
            }}
          >

            <div className="fy-create-field">

              <label htmlFor="fy-new-year">
                Fiscal-Year Ending Year
              </label>

              <input
                id="fy-new-year"
                type="number"
                min={1900}
                max={9999}
                step={1}
                required
                value={newFiscalYearEndYear}
                disabled={isCreatingFiscalYear}
                placeholder="e.g. 2028"
                onChange={event => {

                  setNewFiscalYearEndYear(
                    event.target.value,
                  )

                  setCreateFiscalYearError(null)

                }}
              />

              <p className="fy-create-help">
                Enter the calendar year in which
                the fiscal year ends. LedgerBase
                calculates the accounting dates
                automatically.
              </p>

            </div>

            {createFiscalYearError && (

              <p
                className="fy-error"
                role="alert"
              >
                {createFiscalYearError}
              </p>

            )}

            <div className="fy-create-actions">

              <button
                type="submit"
                className="fy-create-submit"
                disabled={isCreatingFiscalYear}
              >
                {isCreatingFiscalYear
                  ? 'Creating...'
                  : 'Create Fiscal Year'}
              </button>

              <button
                type="button"
                className="fy-create-cancel"
                disabled={isCreatingFiscalYear}
                onClick={() => {

                  setShowCreateForm(false)
                  setNewFiscalYearEndYear('')
                  setCreateFiscalYearError(null)

                }}
              >
                Cancel
              </button>

            </div>

          </form>

        )}

        {/* ================================================== */}
        {/* YEAR LOADING / ERROR / EMPTY STATES */}
        {/* ================================================== */}

        {isYearsLoading && (

          <p className="fy-message">
            Loading fiscal years...
          </p>

        )}

        {yearsError && (

          <p className="fy-error" role="alert">
            {yearsError}
          </p>

        )}

        {!isYearsLoading &&
          !yearsError &&
          fiscalYears.length === 0 && (

            <p className="fy-message">

              {canWrite
                ? 'No fiscal years exist for this business. Use Create Fiscal Year to get started.'
                : 'No fiscal years are available to display for this business.'}

            </p>

          )}

        {/* ================================================== */}
        {/* FISCAL YEAR SELECTOR */}
        {/* ================================================== */}

        {!isYearsLoading &&
          !yearsError &&
          fiscalYears.length > 0 && (

            <div className="fy-controls-content">

              <div className="fy-year-field">

                <label htmlFor="fy-year-select">
                  Fiscal Year
                </label>

                <select
                  id="fy-year-select"
                  value={selectedFiscalYearId ?? ''}
                  disabled={isSavingPeriod}
                  onChange={event => {

                    const fiscalYearId = Number(event.target.value)

                    handleFiscalYearSelection(fiscalYearId)

                  }}
                >

                  {fiscalYears.map(year => (

                    <option
                      key={year.id}
                      value={year.id}
                    >
                      {year.label}
                    </option>

                  ))}

                </select>

              </div>

              {/* ============================================== */}
              {/* FISCAL YEAR DATE RANGE */}
              {/* ============================================== */}

              {selectedFiscalYear && (

                <div className="fy-date-range">

                  <span>Accounting Period</span>

                  <strong>
                    {formatFiscalDate(
                      selectedFiscalYear.startDate,
                    )}

                    {' — '}

                    {formatFiscalDate(
                      selectedFiscalYear.endDate,
                    )}
                  </strong>

                </div>

              )}

            </div>

          )}

      </section>

      {/* ==================================================== */}
      {/* ACCOUNTING PERIODS CARD */}
      {/* ==================================================== */}

      {selectedFiscalYear && (

        <section className="fy-periods-card">

          {/* ================================================== */}
          {/* ACCOUNTING PERIODS HEADING */}
          {/* ================================================== */}

          <div className="fy-periods-heading">

            <div>

              <h2>Accounting Periods</h2>

              <p>
                {selectedFiscalYear.label}
                {' · '}
                Monthly posting status
              </p>

            </div>

            {!isPeriodsLoading &&
              !periodsError && (

                <div className="fy-period-summary">

                  <span>
                    {openPeriodCount} Open
                  </span>

                  <span>
                    {closedPeriodCount} Closed
                  </span>

                </div>

              )}

          </div>

          {/* ================================================== */}
          {/* PERIOD ACTION SUCCESS MESSAGE */}
          {/* ================================================== */}

          {periodActionSuccess && (

            <p
              className="fy-period-action-success"
              role="status"
            >
              {periodActionSuccess}
            </p>

          )}

          {/* ================================================== */}
          {/* PERIOD ACTION ERROR MESSAGE */}
          {/* ================================================== */}

          {periodActionError && (

            <p
              className="fy-period-action-error"
              role="alert"
            >
              {periodActionError}
            </p>

          )}

          {/* ================================================== */}
          {/* CLOSE / REOPEN CONFIRMATION */}
          {/* ================================================== */}

          {canWrite &&
            pendingPeriodAction &&
            pendingPeriod && (

            <div
              className="fy-period-confirmation"
              role="group"
              aria-label="Confirm accounting period action"
            >

              <div>

                <strong>
                  {pendingPeriodAction.action === 'CLOSE'
                    ? 'Close'
                    : 'Reopen'}
                  {' '}
                  {formatPeriodMonth(
                    pendingPeriod.startDate,
                  )}?
                </strong>

                <p>

                  {pendingPeriodAction.action === 'CLOSE'
                    ? 'Closing this period prevents new journal postings dated within it. Existing posted transactions remain unchanged.'
                    : 'Reopening this period allows eligible journal postings again, subject to the other accounting validation rules.'}

                </p>

              </div>

              <div className="fy-period-confirm-actions">

                <button
                  type="button"
                  className="fy-period-confirm-submit"
                  disabled={isSavingPeriod}
                  onClick={() => {
                    void handleConfirmPeriodAction()
                  }}
                >
                  {isSavingPeriod
                    ? 'Saving...'
                    : pendingPeriodAction.action === 'CLOSE'
                      ? 'Confirm Close'
                      : 'Confirm Reopen'}
                </button>

                <button
                  type="button"
                  className="fy-period-confirm-cancel"
                  disabled={isSavingPeriod}
                  onClick={() => {
                    setPendingPeriodAction(null)
                  }}
                >
                  Cancel
                </button>

              </div>

            </div>

          )}

          {/* ================================================== */}
          {/* LOADING AND ERROR STATES */}
          {/* ================================================== */}

          {isPeriodsLoading && (

            <p className="fy-message">
              Loading accounting periods...
            </p>

          )}

          {periodsError && (

            <p className="fy-error" role="alert">
              {periodsError}
            </p>

          )}

          {!isPeriodsLoading &&
            !periodsError &&
            periods.length === 0 && (

              <p className="fy-message">
                No accounting periods were found
                for this fiscal year.
              </p>

            )}

          {/* ================================================== */}
          {/* ACCOUNTING PERIODS TABLE */}
          {/* ================================================== */}

          {!isPeriodsLoading &&
            !periodsError &&
            periods.length > 0 && (

              <div className="fy-table-scroll">

                <table className="fy-table">

                  <thead>

                    <tr>
                      <th scope="col">Period</th>
                      <th scope="col">Month</th>
                      <th scope="col">Start Date</th>
                      <th scope="col">End Date</th>
                      <th scope="col">Status</th>
                      {canWrite && (
                        <th scope="col">Actions</th>
                      )}
                    </tr>

                  </thead>

                  <tbody>

                    {periods.map(period => (

                      <tr key={period.id}>

                        {/* ==================================== */}
                        {/* PERIOD NUMBER */}
                        {/* ==================================== */}

                        <td className="fy-period-number">
                          Period {period.periodNumber}
                        </td>

                        {/* ==================================== */}
                        {/* PERIOD MONTH */}
                        {/* ==================================== */}

                        <td className="fy-period-month">
                          {formatPeriodMonth(
                            period.startDate,
                          )}
                        </td>

                        {/* ==================================== */}
                        {/* START DATE */}
                        {/* ==================================== */}

                        <td>
                          {formatFiscalDate(
                            period.startDate,
                          )}
                        </td>

                        {/* ==================================== */}
                        {/* END DATE */}
                        {/* ==================================== */}

                        <td>
                          {formatFiscalDate(
                            period.endDate,
                          )}
                        </td>

                        {/* ==================================== */}
                        {/* OPEN / CLOSED STATUS */}
                        {/* ==================================== */}

                        <td>

                          <span
                            className={
                              period.status === 'OPEN'
                                ? 'fy-status fy-status-open'
                                : 'fy-status fy-status-closed'
                            }
                          >
                            {period.status === 'OPEN'
                              ? 'Open'
                              : 'Closed'}
                          </span>

                        </td>

                        {/* ==================================== */}
                        {/* CLOSE / REOPEN BUTTON */}
                        {/* ==================================== */}

                        {canWrite && (

                          <td>

                            <button
                              type="button"
                              className="fy-period-action-button"
                              disabled={
                                isSavingPeriod ||
                                isPeriodsLoading
                              }
                              aria-label={
                                `${period.status === 'OPEN' ? 'Close' : 'Reopen'} ` +
                                formatPeriodMonth(
                                  period.startDate,
                                )
                              }
                              onClick={() => {
                                preparePeriodAction(period)
                              }}
                            >
                              {period.status === 'OPEN'
                                ? 'Close'
                                : 'Reopen'}
                            </button>

                          </td>

                        )}

                      </tr>

                    ))}

                  </tbody>

                </table>

              </div>

            )}

        </section>

      )}

    </div>
  )
}

// ============================================================
// EXPORT PAGE
// ============================================================

export default FiscalYearsPage
