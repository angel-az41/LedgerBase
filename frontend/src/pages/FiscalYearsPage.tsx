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

interface FiscalYearsPageProps {

  // Business currently being managed.
  businessId: number

  /**
   * Temporary development actor.
   *
   * Authentication has not yet been implemented.
   *
   * Once authentication is introduced, Spring Boot
   * must determine the actor from the logged-in
   * session rather than trusting a client-supplied ID.
   */
  actorId: number
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
  actorId,
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
  // LOAD ACCOUNTING PERIODS
  // ==========================================================

  /**
   * Retrieve accounting periods when a fiscal year
   * is selected.
   *
   * The backend returns the actual 12 periods
   * ordered by period number.
   *
   * Changing fiscal years also clears any pending
   * Close/Reopen confirmation.
   */
  useEffect(() => {

    let cancelled = false

    const fiscalYearId = selectedFiscalYearId

    setPendingPeriodAction(null)
    setPeriodActionError(null)
    setPeriodActionSuccess(null)

    if (fiscalYearId === null) {

      setPeriods([])
      setPeriodsError(null)
      setIsPeriodsLoading(false)

      return
    }

    const validFiscalYearId: number = fiscalYearId

    async function loadPeriods() {

      setIsPeriodsLoading(true)
      setPeriodsError(null)
      setPeriods([])

      try {

        const result = await getAccountingPeriods(
          businessId,
          validFiscalYearId,
        )

        if (!cancelled) {
          setPeriods(result)
        }

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

    void loadPeriods()

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

    event.preventDefault()

    if (isCreatingFiscalYear) {
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

      /**
       * The accounting-period loading effect
       * runs when this selection changes.
       */
      setSelectedFiscalYearId(createdYear.id)

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

    if (isSavingPeriod) {
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

    if (
      pendingPeriodAction === null ||
      pendingPeriod === null ||
      isSavingPeriod
    ) {
      return
    }

    // ========================================================
    // VALIDATE TEMPORARY DEVELOPMENT ACTOR
    // ========================================================

    if (
      !Number.isInteger(actorId) ||
      actorId <= 0
    ) {

      setPeriodActionError(
        'A valid development actor ID is required.',
      )

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
          { actorId },
        )

      } else {

        updatedPeriod = await reopenAccountingPeriod(
          businessId,
          periodId,
          { actorId },
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

        {showCreateForm && (

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
              No fiscal years exist for this business.
              Use Create Fiscal Year to get started.
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

                    setSelectedFiscalYearId(
                      Number(event.target.value),
                    )

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

          {pendingPeriodAction && pendingPeriod && (

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
                      <th scope="col">Actions</th>
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
