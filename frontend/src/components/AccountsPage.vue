<script setup lang="ts">

// ============================================================
// IMPORTS
// ============================================================

import { computed, nextTick, onMounted, ref, watch } from 'vue'

import {
  deactivateAccount,
  getAccounts,
  renameAccount,
  type Account,
} from '@/api/accounts'

import NewAccountForm from './NewAccountForm.vue'

// ============================================================
// COMPONENT PROPERTIES
// ============================================================

/**
 * Identify which business owns the displayed accounts.
 *
 * Business ID 1 is currently used for local development.
 */
const props = defineProps<{
  businessId: number
}>()

// ============================================================
// TYPE DEFINITIONS
// ============================================================

/**
 * Supported account status filters.
 */
type StatusFilter = 'all' | 'active' | 'inactive'

// ============================================================
// APPLICATION STATE
// ============================================================

/**
 * Store the accounts returned by Spring Boot.
 */
const accounts = ref<Account[]>([])

/**
 * Track API loading and error states.
 */
const isLoading = ref(false)
const errorMessage = ref('')

/**
 * Store search and filtering preferences.
 */
const searchQuery = ref('')
const statusFilter = ref<StatusFilter>('all')

/**
 * Control the New Account form.
 */
const isCreateFormOpen = ref(false)

/**
 * Display confirmation after successful account actions.
 */
const successMessage = ref('')

// ============================================================
// RENAME ACCOUNT STATE
// ============================================================

/**
 * Identify the account currently being renamed.
 *
 * null means no account is being edited.
 */
const editingAccountId = ref<number | null>(null)

/**
 * Store the updated account name entered by the user.
 */
const editedName = ref('')

/**
 * Track the rename request and any validation errors.
 */
const isSavingRename = ref(false)
const renameError = ref('')

// ============================================================
// DEACTIVATE ACCOUNT STATE
// ============================================================

/**
 * Reference the native HTML dialog element.
 *
 * showModal() opens it as a confirmation dialog.
 */
const deactivateDialog = ref<HTMLDialogElement | null>(null)

/**
 * Store the account awaiting deactivation.
 */
const pendingDeactivateAccount = ref<Account | null>(null)

/**
 * Track the deactivation request and errors.
 */
const isDeactivating = ref(false)
const deactivateError = ref('')

// ============================================================
// COMPUTED PROPERTIES
// ============================================================

/**
 * Calculate account statistics from real backend data.
 */
const totalAccounts = computed(
  () => accounts.value.length
)

const activeAccounts = computed(
  () => accounts.value.filter(
    (account) => account.active
  ).length
)

const inactiveAccounts = computed(
  () => accounts.value.filter(
    (account) => !account.active
  ).length
)

/**
 * Filter accounts by search text and active status.
 *
 * Sort by account code using natural numeric ordering.
 */
const filteredAccounts = computed(() => {

  const query = searchQuery.value.trim().toLowerCase()

  return accounts.value
    .filter((account) => {

      // Match account code, name, or classification.
      const matchesSearch =
        account.code.toLowerCase().includes(query) ||
        account.name.toLowerCase().includes(query) ||
        account.accountType.toLowerCase().includes(query)

      // Match the selected active-status filter.
      const matchesStatus =
        statusFilter.value === 'all' ||
        (
          statusFilter.value === 'active' &&
          account.active
        ) ||
        (
          statusFilter.value === 'inactive' &&
          !account.active
        )

      return matchesSearch && matchesStatus
    })
    .sort((first, second) =>
      first.code.localeCompare(
        second.code,
        undefined,
        { numeric: true },
      ),
    )
})

// ============================================================
// ACCOUNTS API METHODS
// ============================================================

/**
 * Retrieve the latest account records from Spring Boot.
 *
 * The table automatically updates when accounts.value
 * receives the new data.
 */
async function loadAccounts(): Promise<void> {

  isLoading.value = true
  errorMessage.value = ''

  try {

    accounts.value = await getAccounts(props.businessId)

  } catch (error: unknown) {

    errorMessage.value =
      error instanceof Error
        ? error.message
        : 'Unable to load accounts. Please try again.'

  } finally {

    isLoading.value = false
  }
}

// ============================================================
// CREATE ACCOUNT FORM METHODS
// ============================================================

/**
 * Open the existing New Account form.
 */
function openCreateForm(): void {

  successMessage.value = ''
  isCreateFormOpen.value = true

  // Close inline editing when opening another form.
  cancelRename()
}

/**
 * Close the New Account form without saving.
 */
function closeCreateForm(): void {

  isCreateFormOpen.value = false
}

/**
 * Handle successful creation of a new account.
 *
 * The NewAccountForm component emits the created
 * account after receiving HTTP 201 from Spring Boot.
 */
async function handleAccountCreated(
  account: Account,
): Promise<void> {

  isCreateFormOpen.value = false

  successMessage.value =
    `Account ${account.code} — ${account.name} created successfully.`

  await loadAccounts()
}

// ============================================================
// RENAME ACCOUNT METHODS
// ============================================================

/**
 * Begin editing an account name directly in its table row.
 *
 * The backend currently only allows renaming
 * existing active accounts.
 */
function startRename(account: Account): void {

  if (
    !account.active ||
    isLoading.value ||
    isSavingRename.value
  ) {
    return
  }

  successMessage.value = ''
  renameError.value = ''

  editingAccountId.value = account.id
  editedName.value = account.name
}

/**
 * Cancel inline editing.
 *
 * No backend request is sent.
 */
function cancelRename(): void {

  if (isSavingRename.value) {
    return
  }

  editingAccountId.value = null
  editedName.value = ''
  renameError.value = ''
}

/**
 * Save the renamed account.
 *
 * Steps:
 * 1. Validate the new name.
 * 2. Send PATCH to Spring Boot.
 * 3. Display success feedback.
 * 4. Refresh the account table.
 *
 * A failed request keeps the editor open.
 */
async function saveRename(account: Account): Promise<void> {

  if (isSavingRename.value || !account.active) {
    return
  }

  const trimmedName = editedName.value.trim()

  // ============================================================
  // CLIENT-SIDE VALIDATION
  // ============================================================

  if (!trimmedName) {
    renameError.value = 'Account name is required.'
    return
  }

  // Nothing needs to be saved if the name is unchanged.
  if (trimmedName === account.name) {
    cancelRename()
    return
  }

  // ============================================================
  // API REQUEST
  // ============================================================

  isSavingRename.value = true
  renameError.value = ''

  try {

    /**
     * Send:
     *
     * PATCH /api/v1/businesses/{businessId}/accounts/{accountId}
     *
     * Body:
     * { "name": "New Account Name" }
     */
    await renameAccount(
      props.businessId,
      account.id,
      { name: trimmedName },
    )

    // Clear the editor after successful saving.
    editingAccountId.value = null
    editedName.value = ''

    successMessage.value =
      `Account ${account.code} renamed to ${trimmedName}.`

    await loadAccounts()

  } catch (error: unknown) {

    renameError.value =
      error instanceof Error
        ? error.message
        : 'Unable to rename the account.'

  } finally {

    isSavingRename.value = false
  }
}

// ============================================================
// DEACTIVATE ACCOUNT METHODS
// ============================================================

/**
 * Open a confirmation dialog before deactivation.
 *
 * We do not immediately call the backend because
 * the user must confirm this action.
 */
async function openDeactivateDialog(
  account: Account,
): Promise<void> {

  if (
    !account.active ||
    isLoading.value ||
    isDeactivating.value ||
    isSavingRename.value
  ) {
    return
  }

  cancelRename()

  successMessage.value = ''
  deactivateError.value = ''

  pendingDeactivateAccount.value = account

  /**
   * Wait until Vue updates the dialog content
   * before opening the native modal dialog.
   */
  await nextTick()

  deactivateDialog.value?.showModal()
}

/**
 * Close the confirmation dialog without changing
 * the account.
 */
function closeDeactivateDialog(): void {

  if (isDeactivating.value) {
    return
  }

  deactivateDialog.value?.close()
}

/**
 * Prevent Escape from closing the dialog while
 * the deactivation request is in progress.
 */
function handleDeactivateDialogCancel(
  event: Event,
): void {

  if (isDeactivating.value) {
    event.preventDefault()
  }
}

/**
 * Reset dialog-related state when it closes.
 */
function handleDeactivateDialogClosed(): void {

  pendingDeactivateAccount.value = null
  deactivateError.value = ''
}

/**
 * Deactivate an account after the user confirms.
 *
 * Deactivation preserves the account's history.
 *
 * The backend returns HTTP 204 No Content.
 */
async function confirmDeactivate(): Promise<void> {

  const account = pendingDeactivateAccount.value

  if (!account || isDeactivating.value) {
    return
  }

  isDeactivating.value = true
  deactivateError.value = ''

  try {

    /**
     * Send:
     *
     * PATCH /api/v1/businesses/{businessId}
     *       /accounts/{accountId}/deactivate
     *
     * No JSON request body is needed.
     */
    await deactivateAccount(
      props.businessId,
      account.id,
    )

    // Close the confirmation dialog.
    deactivateDialog.value?.close()

    successMessage.value =
      `Account ${account.code} — ${account.name} deactivated.`

    // Retrieve the updated active status.
    await loadAccounts()

  } catch (error: unknown) {

    deactivateError.value =
      error instanceof Error
        ? error.message
        : 'Unable to deactivate the account.'

  } finally {

    isDeactivating.value = false
  }
}

// ============================================================
// FORMATTING METHODS
// ============================================================

/**
 * Convert backend account classifications
 * to readable labels.
 *
 * Example:
 * "ASSET" -> "Asset"
 */
function formatAccountType(
  accountType: string,
): string {

  return accountType
    .toLowerCase()
    .replace(/_/g, ' ')
    .replace(
      /\b\w/g,
      (letter) => letter.toUpperCase(),
    )
}

/**
 * Convert PostgreSQL ISO timestamps to dates.
 */
function formatDate(value: string): string {

  return new Intl.DateTimeFormat('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  }).format(new Date(value))
}

// ============================================================
// LIFECYCLE HOOKS
// ============================================================

/**
 * Load accounts when the component is first displayed.
 */
onMounted(() => {
  void loadAccounts()
})

/**
 * Refresh the page when the selected business changes.
 *
 * This will become useful for multi-business support.
 */
watch(
  () => props.businessId,
  () => {

    if (!isDeactivating.value) {
      deactivateDialog.value?.close()
    }

    accounts.value = []
    searchQuery.value = ''
    statusFilter.value = 'all'
    successMessage.value = ''
    isCreateFormOpen.value = false

    cancelRename()

    void loadAccounts()
  },
)

</script>

<template>

  <!-- ======================================================== -->
  <!-- ACCOUNTS PAGE -->
  <!-- ======================================================== -->

  <section class="accounts-page" :aria-busy="isLoading">

    <!-- ====================================================== -->
    <!-- ACCOUNT SUMMARY -->
    <!-- ====================================================== -->

    <div class="account-summary">

      <article class="summary-card">
        <p>Total Accounts</p>
        <strong>{{ totalAccounts }}</strong>
      </article>

      <article class="summary-card">
        <p>Active Accounts</p>
        <strong>{{ activeAccounts }}</strong>
      </article>

      <article class="summary-card">
        <p>Inactive Accounts</p>
        <strong>{{ inactiveAccounts }}</strong>
      </article>

    </div>

    <!-- ====================================================== -->
    <!-- ACCOUNT ACTION SUCCESS MESSAGE -->
    <!-- ====================================================== -->

    <p
      v-if="successMessage"
      class="success-notice"
      role="status"
    >
      {{ successMessage }}
    </p>

    <!-- ====================================================== -->
    <!-- NEW ACCOUNT FORM -->
    <!-- ====================================================== -->

    <NewAccountForm
      v-if="isCreateFormOpen"
      :business-id="businessId"
      @created="handleAccountCreated"
      @cancel="closeCreateForm"
    />

    <!-- ====================================================== -->
    <!-- ACCOUNT TABLE CONTAINER -->
    <!-- ====================================================== -->

    <section class="table-card">

      <!-- ==================================================== -->
      <!-- TABLE HEADER -->
      <!-- ==================================================== -->

      <div class="table-heading">

        <div>
          <h2>All Accounts</h2>

          <p>
            View and manage your business's
            general ledger accounts.
          </p>
        </div>

        <!-- ================================================== -->
        <!-- TABLE ACTIONS -->
        <!-- ================================================== -->

        <div class="table-actions">

          <button
            v-if="!isCreateFormOpen"
            type="button"
            class="create-button"
            :disabled="isLoading || isSavingRename"
            @click="openCreateForm"
          >
            + New Account
          </button>

          <button
            type="button"
            class="refresh-button"
            :disabled="isLoading || isSavingRename"
            @click="loadAccounts"
          >
            {{ isLoading ? 'Refreshing...' : '↻ Refresh' }}
          </button>

        </div>

      </div>

      <!-- ==================================================== -->
      <!-- SEARCH AND FILTER CONTROLS -->
      <!-- ==================================================== -->

      <div class="table-toolbar">

        <!-- Account search -->
        <label class="search-field">

          <span class="visually-hidden">
            Search accounts
          </span>

          <span aria-hidden="true">⌕</span>

          <input
            v-model="searchQuery"
            type="search"
            placeholder="Search by code, name, or type..."
          />

        </label>

        <!-- Status filter -->
        <label class="status-field">

          <span>Status</span>

          <select v-model="statusFilter">
            <option value="all">All statuses</option>
            <option value="active">Active</option>
            <option value="inactive">Inactive</option>
          </select>

        </label>

      </div>

      <!-- ==================================================== -->
      <!-- ERROR STATE -->
      <!-- ==================================================== -->

      <div
        v-if="errorMessage"
        class="message-state error-state"
        role="alert"
      >
        <h3>Unable to load accounts</h3>

        <p>{{ errorMessage }}</p>

        <button
          type="button"
          class="retry-button"
          @click="loadAccounts"
        >
          Try Again
        </button>
      </div>

      <!-- ==================================================== -->
      <!-- LOADING STATE -->
      <!-- ==================================================== -->

      <div
        v-else-if="isLoading"
        class="message-state"
        role="status"
      >
        <div class="loading-spinner"></div>

        <p>Loading accounts from LedgerBase...</p>
      </div>

      <!-- ==================================================== -->
      <!-- EMPTY DATABASE STATE -->
      <!-- ==================================================== -->

      <div
        v-else-if="accounts.length === 0"
        class="message-state"
      >
        <h3>No accounts yet</h3>

        <p>
          Click New Account to create your first
          general ledger account.
        </p>
      </div>

      <!-- ==================================================== -->
      <!-- NO SEARCH RESULTS STATE -->
      <!-- ==================================================== -->

      <div
        v-else-if="filteredAccounts.length === 0"
        class="message-state"
      >
        <h3>No matching accounts</h3>

        <p>Try changing your search or status filter.</p>
      </div>

      <!-- ==================================================== -->
      <!-- ACCOUNTS TABLE -->
      <!-- ==================================================== -->

      <div v-else class="table-scroll">

        <table class="accounts-table">

          <thead>
            <tr>
              <th scope="col">Account Code</th>
              <th scope="col">Account Name</th>
              <th scope="col">Account Type</th>
              <th scope="col">Status</th>
              <th scope="col">Created</th>
              <th scope="col" class="actions-column">
                Actions
              </th>
            </tr>
          </thead>

          <tbody>

            <tr
              v-for="account in filteredAccounts"
              :key="account.id"
            >

              <!-- ================================================== -->
              <!-- ACCOUNT CODE -->
              <!-- ================================================== -->

              <td>
                <span class="account-code">
                  {{ account.code }}
                </span>
              </td>

              <!-- ================================================== -->
              <!-- ACCOUNT NAME / INLINE EDITOR -->
              <!-- ================================================== -->

              <td>

                <!-- Display the editor for the selected account. -->
                <div
                  v-if="editingAccountId === account.id"
                  class="inline-editor"
                >

                  <label
                    :for="`rename-account-${account.id}`"
                    class="visually-hidden"
                  >
                    New name for account {{ account.code }}
                  </label>

                  <input
                    :id="`rename-account-${account.id}`"
                    v-model="editedName"
                    type="text"
                    autocomplete="off"
                    :disabled="isSavingRename"
                    @keydown.enter.prevent="saveRename(account)"
                    @keydown.esc="cancelRename"
                  />

                  <div class="inline-editor-actions">

                    <button
                      type="button"
                      class="inline-save-button"
                      :disabled="isSavingRename"
                      @click="saveRename(account)"
                    >
                      {{ isSavingRename ? 'Saving...' : 'Save' }}
                    </button>

                    <button
                      type="button"
                      class="inline-cancel-button"
                      :disabled="isSavingRename"
                      @click="cancelRename"
                    >
                      Cancel
                    </button>

                  </div>

                  <p
                    v-if="renameError"
                    class="inline-error"
                    role="alert"
                  >
                    {{ renameError }}
                  </p>

                </div>

                <!-- Otherwise display the saved account name. -->
                <span v-else class="account-name">
                  {{ account.name }}
                </span>

              </td>

              <!-- ================================================== -->
              <!-- ACCOUNT TYPE -->
              <!-- ================================================== -->

              <td>
                <span class="account-type">
                  {{ formatAccountType(account.accountType) }}
                </span>
              </td>

              <!-- ================================================== -->
              <!-- ACCOUNT STATUS -->
              <!-- ================================================== -->

              <td>

                <span
                  class="status-badge"
                  :class="account.active ? 'active' : 'inactive'"
                >
                  <span class="badge-dot"></span>

                  {{ account.active ? 'Active' : 'Inactive' }}
                </span>

              </td>

              <!-- ================================================== -->
              <!-- CREATION DATE -->
              <!-- ================================================== -->

              <td class="date-cell">
                {{ formatDate(account.createdAt) }}
              </td>

              <!-- ================================================== -->
              <!-- ACCOUNT MANAGEMENT ACTIONS -->
              <!-- ================================================== -->

              <td class="actions-column">

                <!-- Only active accounts offer management actions. -->
                <div v-if="account.active" class="row-actions">

                  <button
                    type="button"
                    class="row-action-button"
                    :disabled="
                      isLoading ||
                      isSavingRename ||
                      isDeactivating
                    "
                    @click="startRename(account)"
                  >
                    Rename
                  </button>

                  <button
                    type="button"
                    class="row-action-button danger"
                    :disabled="
                      isLoading ||
                      isSavingRename ||
                      isDeactivating
                    "
                    @click="openDeactivateDialog(account)"
                  >
                    Deactivate
                  </button>

                </div>

                <!-- Inactive accounts cannot be managed yet. -->
                <span
                  v-else
                  class="unavailable-action"
                  title="Reactivation is not available yet"
                >
                  No actions
                </span>

              </td>

            </tr>

          </tbody>

        </table>

      </div>

      <!-- ==================================================== -->
      <!-- TABLE FOOTER -->
      <!-- ==================================================== -->

      <div
        v-if="!isLoading && !errorMessage"
        class="table-footer"
      >
        Showing {{ filteredAccounts.length }}
        of {{ totalAccounts }} accounts
      </div>

    </section>

  </section>

  <!-- ======================================================== -->
  <!-- DEACTIVATE ACCOUNT CONFIRMATION DIALOG -->
  <!-- ======================================================== -->

  <!--
    The native HTML dialog provides modal behavior,
    including keyboard focus handling and Escape support.

    We deliberately require confirmation before
    changing an account to inactive.
  -->
  <dialog
    ref="deactivateDialog"
    class="confirmation-dialog"
    aria-labelledby="deactivate-dialog-title"
    @cancel="handleDeactivateDialogCancel"
    @close="handleDeactivateDialogClosed"
  >

    <div v-if="pendingDeactivateAccount" class="dialog-content">

      <div class="dialog-heading">

        <h2 id="deactivate-dialog-title">
          Deactivate account?
        </h2>

        <p>
          You are about to deactivate:
        </p>

      </div>

      <!-- Identify exactly which account is affected. -->
      <div class="selected-account">

        <span class="selected-account-code">
          {{ pendingDeactivateAccount.code }}
        </span>

        <strong>
          {{ pendingDeactivateAccount.name }}
        </strong>

      </div>

      <p class="dialog-warning">
        This account will no longer be available for
        new financial transactions. Existing accounting
        records will be preserved.
      </p>

      <p class="dialog-warning">
        LedgerBase does not currently support
        reactivating accounts through the interface.
      </p>

      <!-- Display backend errors without closing the dialog. -->
      <p
        v-if="deactivateError"
        class="dialog-error"
        role="alert"
      >
        {{ deactivateError }}
      </p>

      <!-- Confirmation actions -->
      <div class="dialog-actions">

        <button
          type="button"
          class="cancel-button"
          :disabled="isDeactivating"
          @click="closeDeactivateDialog"
        >
          Cancel
        </button>

        <button
          type="button"
          class="deactivate-button"
          :disabled="isDeactivating"
          @click="confirmDeactivate"
        >
          {{ isDeactivating ? 'Deactivating...' : 'Deactivate Account' }}
        </button>

      </div>

    </div>

  </dialog>

</template>

<style scoped>

/* ============================================================ */
/* ACCOUNTS PAGE LAYOUT */
/* ============================================================ */

.accounts-page {
  width: 100%;
}

/* ============================================================ */
/* ACCOUNT SUMMARY */
/* ============================================================ */

.account-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
  margin-bottom: 25px;
}

.summary-card {
  padding: 23px;
  border: 1px solid #e8edf3;
  border-radius: 12px;
  background: #ffffff;
}

.summary-card p {
  margin: 0 0 12px;
  color: #77869a;
  font-size: 12px;
  font-weight: 600;
}

.summary-card strong {
  color: #1b2d44;
  font-size: 29px;
  font-weight: 750;
}

/* ============================================================ */
/* ACCOUNT ACTION SUCCESS MESSAGE */
/* ============================================================ */

.success-notice {
  margin: 0 0 20px;
  padding: 14px 17px;
  border: 1px solid #cbe9dd;
  border-radius: 8px;
  background: #eef9f3;
  color: #237b5d;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.6;
}

/* ============================================================ */
/* TABLE CONTAINER */
/* ============================================================ */

.table-card {
  overflow: hidden;
  border: 1px solid #e8edf3;
  border-radius: 12px;
  background: #ffffff;
}

.table-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 25px 25px 20px;
}

.table-heading h2 {
  margin: 0 0 8px;
  color: #243249;
  font-size: 17px;
  font-weight: 750;
}

.table-heading p {
  margin: 0;
  color: #8794a6;
  font-size: 12px;
  line-height: 1.6;
}

/* ============================================================ */
/* TABLE ACTION BUTTONS */
/* ============================================================ */

.table-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.create-button {
  padding: 10px 16px;
  border: 1px solid #188a76;
  border-radius: 8px;
  background: #198f79;
  color: #ffffff;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
  cursor: pointer;
}

.create-button:hover {
  background: #147b68;
}

.refresh-button,
.retry-button {
  padding: 10px 15px;
  border: 1px solid #dce5ec;
  border-radius: 8px;
  background: #ffffff;
  color: #38546a;
  font-size: 12px;
  font-weight: 650;
  white-space: nowrap;
  cursor: pointer;
}

.refresh-button:hover,
.retry-button:hover {
  background: #f3f7fa;
}

.create-button:disabled,
.refresh-button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

/* ============================================================ */
/* SEARCH AND FILTER TOOLBAR */
/* ============================================================ */

.table-toolbar {
  display: flex;
  align-items: center;
  gap: 15px;
  padding: 0 25px 20px;
}

.search-field {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  max-width: 430px;
  padding: 0 13px;
  border: 1px solid #dce5ec;
  border-radius: 8px;
  background: #ffffff;
  color: #8c9caf;
}

.search-field input {
  width: 100%;
  min-height: 40px;
  border: none;
  outline: none;
  background: transparent;
  color: #243249;
  font-size: 12px;
}

.search-field input::placeholder {
  color: #9aa7b6;
}

.search-field:focus-within {
  border-color: #35aa94;
}

.status-field {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #77869a;
  font-size: 12px;
}

.status-field select {
  min-height: 42px;
  padding: 0 12px;
  border: 1px solid #dce5ec;
  border-radius: 8px;
  background: #ffffff;
  color: #34475f;
  font-size: 12px;
  cursor: pointer;
}

/* ============================================================ */
/* ACCOUNTS TABLE */
/* ============================================================ */

.table-scroll {
  width: 100%;
  overflow-x: auto;
}

.accounts-table {
  width: 100%;
  min-width: 830px;
  border-collapse: collapse;
  text-align: left;
}

.accounts-table thead {
  background: #f8fafc;
}

.accounts-table th {
  padding: 15px 20px;
  border-top: 1px solid #edf1f5;
  border-bottom: 1px solid #edf1f5;
  color: #8491a3;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}

.accounts-table td {
  padding: 17px 20px;
  border-bottom: 1px solid #f0f3f6;
  color: #53647b;
  font-size: 12px;
}

.accounts-table tbody tr:hover {
  background: #fbfcfd;
}

.account-code {
  color: #287e75;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 12px;
  font-weight: 700;
}

.account-name {
  color: #25364d;
  font-weight: 650;
}

.account-type {
  color: #53647b;
}

.date-cell {
  color: #8e9bae;
  white-space: nowrap;
}

/* ============================================================ */
/* STATUS BADGES */
/* ============================================================ */

.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 650;
}

.status-badge.active {
  background: #e8f8f1;
  color: #218666;
}

.status-badge.inactive {
  background: #f1f3f6;
  color: #7b8798;
}

.badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

/* ============================================================ */
/* ACCOUNT ROW ACTIONS */
/* ============================================================ */

.actions-column {
  text-align: right;
  white-space: nowrap;
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}

.row-action-button {
  padding: 6px 0;
  border: none;
  background: transparent;
  color: #277f75;
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
}

.row-action-button:hover {
  text-decoration: underline;
}

.row-action-button.danger {
  color: #b35858;
}

.row-action-button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  text-decoration: none;
}

.unavailable-action {
  color: #a1aab6;
  font-size: 11px;
}

/* ============================================================ */
/* INLINE RENAME EDITOR */
/* ============================================================ */

.inline-editor {
  display: flex;
  flex-direction: column;
  gap: 9px;
  min-width: 220px;
}

.inline-editor input {
  min-height: 38px;
  padding: 0 10px;
  border: 1px solid #35aa94;
  border-radius: 7px;
  background: #ffffff;
  color: #243249;
  font: inherit;
  font-size: 12px;
}

.inline-editor input:focus {
  outline: 2px solid #35aa9430;
  outline-offset: 1px;
}

.inline-editor-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.inline-save-button,
.inline-cancel-button {
  padding: 7px 12px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
}

.inline-save-button {
  border: 1px solid #188a76;
  background: #198f79;
  color: #ffffff;
}

.inline-cancel-button {
  border: 1px solid #dce5ec;
  background: #ffffff;
  color: #53657b;
}

.inline-save-button:disabled,
.inline-cancel-button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.inline-error {
  margin: 0;
  color: #b34047;
  font-size: 11px;
  line-height: 1.5;
}

/* ============================================================ */
/* EMPTY, LOADING, AND ERROR STATES */
/* ============================================================ */

.message-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 240px;
  padding: 35px;
  text-align: center;
}

.message-state h3 {
  margin: 0 0 10px;
  color: #25364d;
  font-size: 16px;
}

.message-state p {
  max-width: 360px;
  margin: 0;
  color: #8c9aac;
  font-size: 12px;
  line-height: 1.7;
}

.error-state h3 {
  color: #b53f46;
}

.retry-button {
  margin-top: 18px;
}

.loading-spinner {
  width: 28px;
  height: 28px;
  margin-bottom: 16px;
  border: 3px solid #e2ebe8;
  border-top-color: #39b49c;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* ============================================================ */
/* TABLE FOOTER */
/* ============================================================ */

.table-footer {
  padding: 17px 25px;
  color: #8d99aa;
  font-size: 11px;
}

/* ============================================================ */
/* DEACTIVATION CONFIRMATION DIALOG */
/* ============================================================ */

/**
 * A native HTML dialog keeps the confirmation
 * separate from the account table.
 */
.confirmation-dialog {
  width: min(100% - 32px, 480px);
  max-width: 480px;
  max-height: calc(100vh - 48px);
  padding: 0;
  overflow-y: auto;
  border: 1px solid #e1e7ee;
  border-radius: 14px;
  background: #ffffff;
  color: #243249;
  box-shadow: 0 24px 70px #101b2f35;
}

/**
 * Darken the background while the modal is open.
 */
.confirmation-dialog::backdrop {
  background: #101b2f85;
}

.dialog-content {
  padding: 27px;
}

.dialog-heading h2 {
  margin: 0 0 10px;
  color: #243249;
  font-size: 19px;
}

.dialog-heading p {
  margin: 0;
  color: #77869a;
  font-size: 13px;
  line-height: 1.6;
}

.selected-account {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin: 20px 0;
  padding: 16px;
  border: 1px solid #e8edf3;
  border-radius: 9px;
  background: #f8fafc;
}

.selected-account-code {
  color: #287e75;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 12px;
  font-weight: 700;
}

.selected-account strong {
  color: #243249;
  font-size: 14px;
}

.dialog-warning {
  margin: 12px 0;
  color: #697b90;
  font-size: 12px;
  line-height: 1.7;
}

.dialog-error {
  margin: 16px 0;
  padding: 12px;
  border: 1px solid #f1d4d4;
  border-radius: 8px;
  background: #fff5f5;
  color: #a33c42;
  font-size: 12px;
}

.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 11px;
  margin-top: 25px;
  padding-top: 20px;
  border-top: 1px solid #edf1f5;
}

.cancel-button,
.deactivate-button {
  min-height: 41px;
  padding: 10px 15px;
  border-radius: 8px;
  font: inherit;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.cancel-button {
  border: 1px solid #dce5ec;
  background: #ffffff;
  color: #53657b;
}

.deactivate-button {
  border: 1px solid #ab4646;
  background: #b94f4f;
  color: #ffffff;
}

.deactivate-button:hover {
  background: #a84343;
}

.cancel-button:disabled,
.deactivate-button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

/* ============================================================ */
/* ACCESSIBILITY */
/* ============================================================ */

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

button:focus-visible,
select:focus-visible,
input:focus-visible {
  outline: 2px solid #35aa94;
  outline-offset: 2px;
}

/* ============================================================ */
/* RESPONSIVE LAYOUT */
/* ============================================================ */

@media (max-width: 760px) {

  .account-summary {
    grid-template-columns: 1fr;
    gap: 12px;
  }

  .table-heading,
  .table-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .table-actions {
    flex-wrap: wrap;
  }

  .search-field {
    max-width: none;
  }

  .status-field {
    justify-content: space-between;
  }

  .dialog-actions {
    flex-wrap: wrap;
  }
}

</style>
