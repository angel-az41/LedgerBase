<script setup lang="ts">

// ============================================================
// IMPORTS
// ============================================================

import { ref } from 'vue'

import {
  createAccount,
  type Account,
  type AccountType,
} from '@/api/accounts'

// ============================================================
// COMPONENT PROPERTIES
// ============================================================

/**
 * The parent AccountsPage component supplies
 * the business ID for which we're creating an account.
 */
const props = defineProps<{
  businessId: number
}>()

// ============================================================
// COMPONENT EVENTS
// ============================================================

/**
 * Communicate with the parent component.
 *
 * created:
 * Emitted after Spring Boot successfully saves the account.
 *
 * cancel:
 * Emitted when the user closes the form.
 *
 * The parent decides how to refresh the table
 * and whether to display a success message.
 */
const emit = defineEmits<{
  created: [account: Account]
  cancel: []
}>()

// ============================================================
// TYPE DEFINITIONS
// ============================================================

/**
 * Define one available account classification.
 */
interface AccountTypeOption {
  value: AccountType
  label: string
}

// ============================================================
// FORM STATE
// ============================================================

/**
 * ref() creates reactive values bound to the form fields.
 *
 * v-model will automatically update these values
 * when the user types or selects an option.
 */
const code = ref('')
const name = ref('')
const accountType = ref<AccountType>('ASSET')

/**
 * Track submission progress and backend errors.
 */
const isSubmitting = ref(false)
const errorMessage = ref('')

// ============================================================
// ACCOUNT TYPE OPTIONS
// ============================================================

/**
 * These classifications match the values supported
 * by our LedgerBase backend.
 */
const accountTypeOptions: AccountTypeOption[] = [
  { value: 'ASSET', label: 'Asset' },
  { value: 'LIABILITY', label: 'Liability' },
  { value: 'EQUITY', label: 'Equity' },
  { value: 'REVENUE', label: 'Revenue' },
  { value: 'EXPENSE', label: 'Expense' },
]

// ============================================================
// FORM SUBMISSION
// ============================================================

/**
 * Validate and submit a new account to Spring Boot.
 *
 * The backend performs the authoritative validation.
 * Frontend checks improve the user experience.
 *
 * On success:
 * 1. PostgreSQL stores the account.
 * 2. Spring Boot returns the newly created account.
 * 3. The component emits a "created" event.
 *
 * On failure:
 * Display the error without closing the form.
 */
async function submitForm(): Promise<void> {

  // Prevent duplicate requests while saving.
  if (isSubmitting.value) {
    return
  }

  // Clear any previous submission error.
  errorMessage.value = ''

  // Remove leading and trailing whitespace.
  const trimmedCode = code.value.trim()
  const trimmedName = name.value.trim()

  // ============================================================
  // CLIENT-SIDE VALIDATION
  // ============================================================

  if (!trimmedCode) {
    errorMessage.value = 'Account code is required.'
    return
  }

  if (trimmedCode.length > 20) {
    errorMessage.value =
      'Account code must be 20 characters or fewer.'
    return
  }

  if (!trimmedName) {
    errorMessage.value = 'Account name is required.'
    return
  }

  // ============================================================
  // API REQUEST
  // ============================================================

  isSubmitting.value = true

  try {

    /**
     * Send the form values to Spring Boot.
     *
     * The backend determines the new account ID,
     * creation timestamp, and initial active status.
     */
    const createdAccount = await createAccount(
      props.businessId,
      {
        code: trimmedCode,
        name: trimmedName,
        accountType: accountType.value,
      },
    )

    // Inform AccountsPage that creation succeeded.
    emit('created', createdAccount)

  } catch (error: unknown) {

    /**
     * Display backend validation or database errors.
     *
     * Examples:
     * - Duplicate account code
     * - Invalid request
     * - Network or server failure
     */
    errorMessage.value =
      error instanceof Error
        ? error.message
        : 'Unable to create account. Please try again.'

  } finally {

    // Re-enable the form buttons after the request.
    isSubmitting.value = false
  }
}

// ============================================================
// CANCEL ACTION
// ============================================================

/**
 * Ask the parent component to close the form.
 *
 * No HTTP request is made.
 */
function cancelForm(): void {
  emit('cancel')
}

</script>

<template>

  <!-- ======================================================== -->
  <!-- NEW ACCOUNT FORM -->
  <!-- ======================================================== -->

  <section class="form-card">

    <!-- ====================================================== -->
    <!-- FORM HEADER -->
    <!-- ====================================================== -->

    <div class="form-header">

      <div>
        <h2>Create New Account</h2>

        <p>
          Add an account to your business's chart of accounts.
        </p>
      </div>

    </div>

    <!-- ====================================================== -->
    <!-- ACCOUNT FORM -->
    <!-- ====================================================== -->

    <!--
      @submit.prevent stops the browser from reloading.

      Vue calls submitForm() instead, allowing us
      to send the data using our TypeScript API client.
    -->
    <form @submit.prevent="submitForm">

      <div class="form-grid">

        <!-- ================================================== -->
        <!-- ACCOUNT CODE -->
        <!-- ================================================== -->

        <div class="form-field">

          <label for="new-account-code">
            Account Code
            <span class="required">*</span>
          </label>

          <input
            id="new-account-code"
            v-model="code"
            type="text"
            placeholder="e.g. 1010"
            maxlength="20"
            autocomplete="off"
            required
            :disabled="isSubmitting"
          />

          <p class="field-hint">
            A unique identifier for this account.
          </p>

        </div>

        <!-- ================================================== -->
        <!-- ACCOUNT NAME -->
        <!-- ================================================== -->

        <div class="form-field">

          <label for="new-account-name">
            Account Name
            <span class="required">*</span>
          </label>

          <input
            id="new-account-name"
            v-model="name"
            type="text"
            placeholder="e.g. Business Checking Account"
            required
            :disabled="isSubmitting"
          />

          <p class="field-hint">
            A descriptive name used in your ledger.
          </p>

        </div>

        <!-- ================================================== -->
        <!-- ACCOUNT TYPE -->
        <!-- ================================================== -->

        <div class="form-field">

          <label for="new-account-type">
            Account Type
            <span class="required">*</span>
          </label>

          <select
            id="new-account-type"
            v-model="accountType"
            required
            :disabled="isSubmitting"
          >

            <option
              v-for="option in accountTypeOptions"
              :key="option.value"
              :value="option.value"
            >
              {{ option.label }}
            </option>

          </select>

          <p class="field-hint">
            Determines how the account is classified.
          </p>

        </div>

      </div>

      <!-- ==================================================== -->
      <!-- ERROR MESSAGE -->
      <!-- ==================================================== -->

      <div
        v-if="errorMessage"
        class="error-message"
        role="alert"
      >
        {{ errorMessage }}
      </div>

      <!-- ==================================================== -->
      <!-- FORM ACTIONS -->
      <!-- ==================================================== -->

      <div class="form-actions">

        <button
          type="button"
          class="cancel-button"
          :disabled="isSubmitting"
          @click="cancelForm"
        >
          Cancel
        </button>

        <button
          type="submit"
          class="save-button"
          :disabled="isSubmitting"
        >
          {{ isSubmitting ? 'Saving...' : 'Create Account' }}
        </button>

      </div>

    </form>

  </section>

</template>

<style scoped>

/* ============================================================ */
/* FORM CONTAINER */
/* ============================================================ */

.form-card {
  margin-bottom: 24px;
  padding: 26px;
  border: 1px solid #dce7ea;
  border-radius: 12px;
  background: #ffffff;
}

/* ============================================================ */
/* FORM HEADER */
/* ============================================================ */

.form-header {
  margin-bottom: 25px;
}

.form-header h2 {
  margin: 0 0 8px;
  color: #243249;
  font-size: 18px;
  font-weight: 750;
}

.form-header p {
  margin: 0;
  color: #8794a6;
  font-size: 12px;
  line-height: 1.6;
}

/* ============================================================ */
/* FORM FIELDS */
/* ============================================================ */

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.form-field label {
  color: #34465d;
  font-size: 12px;
  font-weight: 700;
}

.required {
  color: #c44949;
}

.form-field input,
.form-field select {
  width: 100%;
  min-height: 44px;
  padding: 0 13px;
  border: 1px solid #dce5ec;
  border-radius: 8px;
  background: #ffffff;
  color: #243249;
  font: inherit;
  font-size: 13px;
  transition: border-color 0.2s ease;
}

.form-field input:focus,
.form-field select:focus {
  outline: none;
  border-color: #2fa58e;
  box-shadow: 0 0 0 3px #2fa58e18;
}

.form-field input:disabled,
.form-field select:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.form-field input::placeholder {
  color: #a0adba;
}

.field-hint {
  margin: 0;
  color: #96a2b1;
  font-size: 11px;
  line-height: 1.5;
}

/* ============================================================ */
/* ERROR MESSAGE */
/* ============================================================ */

.error-message {
  margin-top: 24px;
  padding: 13px 15px;
  border: 1px solid #f1d4d4;
  border-radius: 8px;
  background: #fff5f5;
  color: #a33c42;
  font-size: 12px;
  line-height: 1.6;
}

/* ============================================================ */
/* FORM ACTION BUTTONS */
/* ============================================================ */

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 30px;
  padding-top: 22px;
  border-top: 1px solid #edf1f5;
}

.cancel-button,
.save-button {
  min-height: 42px;
  padding: 10px 19px;
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

.cancel-button:hover {
  background: #f5f8fa;
}

.save-button {
  border: 1px solid #188a76;
  background: #198f79;
  color: #ffffff;
}

.save-button:hover {
  background: #147b68;
}

.cancel-button:disabled,
.save-button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

/* ============================================================ */
/* RESPONSIVE LAYOUT */
/* ============================================================ */

@media (max-width: 760px) {

  .form-card {
    padding: 20px;
  }

  .form-grid {
    grid-template-columns: 1fr;
    gap: 20px;
  }

  .form-actions {
    flex-wrap: wrap;
  }
}

</style>
