<script setup lang="ts">

import AccountsPage from './components/AccountsPage.vue'

// ============================================================
// IMPORTS
// ============================================================

import { computed, ref } from 'vue'

// ============================================================
// TYPE DEFINITIONS
// ============================================================

/**
 * Define the pages available in our frontend.
 *
 * TypeScript only permits these three values.
 * This helps prevent invalid navigation states.
 */
type Page = 'overview' | 'accounts' | 'transactions'

/**
 * Define the information displayed in each
 * sidebar navigation item.
 */
interface NavigationItem {
  id: Page
  label: string
  icon: string
}

/**
 * Define the structure of a dashboard metric.
 *
 * Later, these metrics will be populated using
 * real data retrieved from Spring Boot.
 */
interface DashboardMetric {
  label: string
  value: string
  description: string
}

// ============================================================
// APPLICATION STATE
// ============================================================

/**
 * ref() creates reactive state in Vue.
 *
 * When activePage changes, Vue automatically
 * updates the page displayed in the browser.
 *
 * We start on the Overview page.
 */
const activePage = ref<Page>('overview')

// ============================================================
// NAVIGATION CONFIGURATION
// ============================================================

/**
 * Sidebar navigation items.
 *
 * For now, navigation uses local Vue state.
 * Vue Router can be introduced later when
 * we need dedicated page URLs.
 */
const navigationItems: NavigationItem[] = [
  {
    id: 'overview',
    label: 'Overview',
    icon: '▦',
  },
  {
    id: 'accounts',
    label: 'Chart of Accounts',
    icon: '▤',
  },
  {
    id: 'transactions',
    label: 'Transactions',
    icon: '⇄',
  },
]

/**
 * Titles displayed at the top of each page.
 */
const pageTitles: Record<Page, string> = {
  overview: 'Financial Overview',
  accounts: 'Chart of Accounts',
  transactions: 'Transactions',
}

/**
 * Descriptions displayed under each page title.
 */
const pageDescriptions: Record<Page, string> = {
  overview: 'Your accounting workspace at a glance.',
  accounts: 'Manage the accounts used in your general ledger.',
  transactions: 'Create, review, and post financial transactions.',
}

// ============================================================
// DASHBOARD DATA
// ============================================================

/**
 * Initial dashboard metrics.
 *
 * These are placeholders rather than fabricated values.
 *
 * After connecting our backend, we'll replace them
 * with real results from PostgreSQL.
 */
const dashboardMetrics: DashboardMetric[] = [
  {
    label: 'Total Accounts',
    value: '—',
    description: 'Awaiting backend connection',
  },
  {
    label: 'Draft Transactions',
    value: '—',
    description: 'Awaiting backend connection',
  },
  {
    label: 'Posted Transactions',
    value: '—',
    description: 'Awaiting backend connection',
  },
]

// ============================================================
// COMPUTED PROPERTIES
// ============================================================

/**
 * computed() derives a value from reactive state.
 *
 * Whenever activePage changes, Vue recalculates
 * the title and description automatically.
 */
const currentPageTitle = computed(
  () => pageTitles[activePage.value]
)

const currentPageDescription = computed(
  () => pageDescriptions[activePage.value]
)

// ============================================================
// NAVIGATION METHODS
// ============================================================

/**
 * Switch the currently displayed page.
 *
 * The Page type prevents navigating to
 * an unsupported page.
 */
function navigateTo(page: Page): void {
  activePage.value = page
}

</script>

<template>
  <!-- ======================================================== -->
  <!-- APPLICATION LAYOUT -->
  <!-- ======================================================== -->

  <div class="app-layout">

    <!-- ====================================================== -->
    <!-- SIDEBAR -->
    <!-- ====================================================== -->

    <aside class="sidebar">

      <!-- Application branding -->
      <div class="brand">
        <div class="brand-logo">L</div>

        <div class="brand-text">
          <span class="brand-name">LedgerBase</span>
          <span class="brand-subtitle">Financial Workspace</span>
        </div>
      </div>

      <!-- Navigation heading -->
      <div class="sidebar-section-title">
        WORKSPACE
      </div>

      <!--
        v-for loops through navigationItems.

        :class adds the active class when the
        current page matches the navigation item.

        @click listens for user clicks.
      -->
      <nav class="sidebar-navigation" aria-label="Main navigation">

        <button
          v-for="item in navigationItems"
          :key="item.id"
          type="button"
          class="nav-item"
          :class="{ active: activePage === item.id }"
          :aria-current="activePage === item.id ? 'page' : undefined"
          @click="navigateTo(item.id)"
        >
          <span class="nav-icon" aria-hidden="true">
            {{ item.icon }}
          </span>

          <span>{{ item.label }}</span>
        </button>

      </nav>

      <!-- Sidebar footer -->
      <div class="sidebar-footer">
        <div class="sidebar-status">
          <span class="status-dot"></span>
          Local Development
        </div>

        <p>LedgerBase MVP</p>
      </div>

    </aside>

    <!-- ====================================================== -->
    <!-- MAIN CONTENT -->
    <!-- ====================================================== -->

    <main class="main-content">

      <!-- ==================================================== -->
      <!-- PAGE HEADER -->
      <!-- ==================================================== -->

      <header class="page-header">

        <div>
          <p class="eyebrow">LEDGERBASE / WORKSPACE</p>

          <h1>{{ currentPageTitle }}</h1>

          <p class="page-description">
            {{ currentPageDescription }}
          </p>
        </div>

        <div class="preview-label">
          <span class="preview-dot"></span>
          UI Preview
        </div>

      </header>

      <!-- ==================================================== -->
      <!-- OVERVIEW PAGE -->
      <!-- ==================================================== -->

      <!--
        v-if renders this section only when the
        selected page is Overview.
      -->
      <template v-if="activePage === 'overview'">

        <!-- ================================================== -->
        <!-- WELCOME BANNER -->
        <!-- ================================================== -->

        <section class="welcome-banner">

          <div class="welcome-content">
            <p class="welcome-eyebrow">
              YOUR ACCOUNTING PLATFORM
            </p>

            <h2>
              Financial clarity starts here.
            </h2>

            <p class="welcome-description">
              Manage your chart of accounts, record balanced
              transactions, and maintain a reliable audit trail
              in one workspace.
            </p>

            <button
              type="button"
              class="primary-button"
              @click="navigateTo('transactions')"
            >
              Explore Transactions
              <span aria-hidden="true">→</span>
            </button>
          </div>

          <!--
            Illustrative journal entry.

            This is example accounting data, NOT a real
            transaction retrieved from PostgreSQL.
          -->
          <div class="journal-preview">

            <div class="journal-preview-header">
              <span>JOURNAL ENTRY</span>
              <span class="example-label">EXAMPLE</span>
            </div>

            <div class="journal-preview-title">
              Consulting Payment
            </div>

            <div class="journal-table-header">
              <span>Account</span>
              <span>Debit</span>
              <span>Credit</span>
            </div>

            <div class="journal-row">
              <span>Cash</span>
              <span>$500.00</span>
              <span>—</span>
            </div>

            <div class="journal-row">
              <span>Consulting Revenue</span>
              <span>—</span>
              <span>$500.00</span>
            </div>

            <div class="journal-total">
              <span>Balanced Entry</span>
              <span>$500.00</span>
              <span>$500.00</span>
            </div>

          </div>

        </section>

        <!-- ================================================== -->
        <!-- SUMMARY METRICS -->
        <!-- ================================================== -->

        <section class="dashboard-section">

          <div class="section-heading">
            <h2>At a Glance</h2>
            <p>Your financial activity overview</p>
          </div>

          <div class="metrics-grid">

            <article
              v-for="metric in dashboardMetrics"
              :key="metric.label"
              class="metric-card"
            >
              <p class="metric-label">
                {{ metric.label }}
              </p>

              <div class="metric-value">
                {{ metric.value }}
              </div>

              <p class="metric-description">
                {{ metric.description }}
              </p>
            </article>

          </div>

        </section>

        <!-- ================================================== -->
        <!-- QUICK ACCESS -->
        <!-- ================================================== -->

        <section class="dashboard-section">

          <div class="section-heading">
            <h2>Quick Access</h2>
            <p>Navigate to your accounting tools</p>
          </div>

          <div class="quick-access-grid">

            <!-- Navigate to Accounts -->
            <button
              type="button"
              class="quick-access-card"
              @click="navigateTo('accounts')"
            >
              <span class="quick-access-icon">
                ▤
              </span>

              <span class="quick-access-content">
                <strong>Chart of Accounts</strong>

                <span>
                  Organize your assets, liabilities,
                  equity, revenue, and expenses.
                </span>
              </span>

              <span class="quick-access-arrow">
                →
              </span>
            </button>

            <!-- Navigate to Transactions -->
            <button
              type="button"
              class="quick-access-card"
              @click="navigateTo('transactions')"
            >
              <span class="quick-access-icon">
                ⇄
              </span>

              <span class="quick-access-content">
                <strong>Transactions</strong>

                <span>
                  Review journal entries and manage
                  your posting workflow.
                </span>
              </span>

              <span class="quick-access-arrow">
                →
              </span>
            </button>

          </div>

        </section>

        <!-- ================================================== -->
        <!-- DEVELOPMENT NOTICE -->
        <!-- ================================================== -->

        <div class="development-notice">
          <span class="notice-icon" aria-hidden="true">ⓘ</span>

          <p>
            <strong>Frontend foundation:</strong>
            This dashboard is currently a UI preview.
            We'll connect it to your Spring Boot backend
            and PostgreSQL data in the next step.
          </p>
        </div>

      </template>
      
      <!-- ==================================================== -->
      <!-- ACCOUNTS PAGE -->
      <!-- ==================================================== -->

      <!--
        Display the real Chart of Accounts component.

        business-id=1 selects our local development
        business: LedgerBase Demo Business.

        Later, we'll replace this with the selected
        business from authenticated application state.
      -->
      <AccountsPage
        v-else-if="activePage === 'accounts'"
        :business-id="1"
      />

      <!-- ==================================================== -->
      <!-- TRANSACTIONS PAGE -->
      <!-- ==================================================== -->

      <section
        v-else-if="activePage === 'transactions'"
        class="empty-page"
      >

        <div class="empty-page-icon">
          ⇄
        </div>

        <h2>Transaction Management</h2>

        <p>
          This page will allow you to view draft and posted
          transactions, create balanced journal entries,
          and finalize transactions.
        </p>

        <div class="upcoming-feature">
          Next: Connect the Spring Boot transaction endpoints
        </div>

        <button
          type="button"
          class="secondary-button"
          @click="navigateTo('overview')"
        >
          ← Back to Overview
        </button>

      </section>

    </main>

  </div>
</template>

<style scoped>

/* ============================================================ */
/* GLOBAL PAGE STYLES */
/* ============================================================ */

/*
 * Reset the default browser/Vite page layout so
 * LedgerBase can use the entire browser window.
 */
:global(*) {
  box-sizing: border-box;
}

:global(body) {
  margin: 0;
  display: block;
  min-width: 320px;
  background: #f5f7fb;
  color: #1c2940;
  font-family:
    Inter,
    -apple-system,
    BlinkMacSystemFont,
    "Segoe UI",
    sans-serif;
}

:global(#app) {
  width: 100%;
  max-width: none;
  margin: 0;
  padding: 0;
  text-align: left;
  min-height: 100vh;
}

:global(button) {
  font: inherit;
}

/* ============================================================ */
/* APPLICATION LAYOUT */
/* ============================================================ */

.app-layout {
  display: flex;
  min-height: 100vh;
}

.main-content {
  flex: 1;
  min-width: 0;
  padding: 42px 48px;
  background: #f5f7fb;
}

/* ============================================================ */
/* SIDEBAR */
/* ============================================================ */

.sidebar {
  width: 252px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  padding: 30px 18px;
  background: #101b2f;
  color: #fff;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 4px 12px;
  margin-bottom: 55px;
}

.brand-logo {
  width: 42px;
  height: 42px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: #55d6ba;
  color: #102a35;
  font-size: 24px;
  font-weight: 800;
}

.brand-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.brand-name {
  font-size: 19px;
  font-weight: 750;
  letter-spacing: -0.5px;
}

.brand-subtitle {
  color: #93a3bd;
  font-size: 11px;
}

.sidebar-section-title {
  padding: 0 15px;
  margin-bottom: 16px;
  color: #71819a;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 1.6px;
}

.sidebar-navigation {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 13px;
  width: 100%;
  min-height: 45px;
  padding: 12px 15px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: #acbad0;
  text-align: left;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition:
    background 0.2s ease,
    color 0.2s ease;
}

.nav-item:hover {
  background: #1c2b43;
  color: #fff;
}

.nav-item.active {
  background: #253a50;
  color: #69e2c6;
  font-weight: 650;
}

.nav-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  font-size: 20px;
}

.sidebar-footer {
  margin-top: auto;
  padding: 22px 15px 0;
  border-top: 1px solid #29374b;
}

.sidebar-status {
  display: flex;
  align-items: center;
  gap: 9px;
  color: #c6d3e6;
  font-size: 12px;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #55d6ba;
}

.sidebar-footer p {
  margin: 10px 0 0;
  color: #73839c;
  font-size: 11px;
}

/* ============================================================ */
/* PAGE HEADER */
/* ============================================================ */

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 34px;
}

.eyebrow {
  margin: 0 0 12px;
  color: #8a96a8;
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 1.5px;
}

.page-header h1 {
  margin: 0;
  color: #16233b;
  font-size: 30px;
  font-weight: 750;
  letter-spacing: -1px;
}

.page-description {
  margin: 10px 0 0;
  color: #8490a3;
  font-size: 14px;
}

.preview-label {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border: 1px solid #e2e8ef;
  border-radius: 100px;
  background: #fff;
  color: #67778b;
  font-size: 12px;
  white-space: nowrap;
}

.preview-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #d5a849;
}

/* ============================================================ */
/* WELCOME BANNER */
/* ============================================================ */

.welcome-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 32px;
  min-height: 275px;
  padding: 35px;
  border-radius: 16px;
  background: linear-gradient(115deg, #182b45, #285066);
  color: #fff;
}

.welcome-content {
  max-width: 460px;
}

.welcome-eyebrow {
  margin: 0 0 14px;
  color: #73dec8;
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 1.6px;
}

.welcome-content h2 {
  margin: 0 0 15px;
  font-size: 27px;
  line-height: 1.25;
  letter-spacing: -0.7px;
}

.welcome-description {
  margin: 0 0 23px;
  color: #b5c8d7;
  font-size: 13px;
  line-height: 1.8;
}

.primary-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 12px 18px;
  border: 0;
  border-radius: 8px;
  background: #62ddc3;
  color: #103548;
  font-size: 12px;
  font-weight: 750;
  cursor: pointer;
  transition: background 0.2s ease;
}

.primary-button:hover {
  background: #8df0d9;
}

/* ============================================================ */
/* SAMPLE JOURNAL ENTRY */
/* ============================================================ */

.journal-preview {
  width: 360px;
  flex-shrink: 0;
  padding: 20px;
  border: 1px solid #e3ecf2;
  border-radius: 12px;
  background: #fff;
  color: #26354a;
  box-shadow: 0 14px 35px #071b2d30;
}

.journal-preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #8b9aae;
  font-size: 9px;
  font-weight: 750;
  letter-spacing: 1px;
}

.example-label {
  padding: 5px 8px;
  border-radius: 5px;
  background: #f0f4f7;
  color: #718096;
}

.journal-preview-title {
  margin: 15px 0 18px;
  color: #25354c;
  font-size: 15px;
  font-weight: 750;
}

.journal-table-header,
.journal-row,
.journal-total {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 80px 80px;
  gap: 5px;
  align-items: center;
}

.journal-table-header {
  padding-bottom: 10px;
  border-bottom: 1px solid #e8edf2;
  color: #9aa6b6;
  font-size: 10px;
}

.journal-row {
  padding: 12px 0;
  border-bottom: 1px solid #f0f3f6;
  font-size: 11px;
}

.journal-total {
  padding-top: 14px;
  color: #14856d;
  font-size: 11px;
  font-weight: 750;
}

/* ============================================================ */
/* DASHBOARD SECTIONS */
/* ============================================================ */

.dashboard-section {
  margin-top: 38px;
}

.section-heading {
  margin-bottom: 19px;
}

.section-heading h2 {
  margin: 0 0 5px;
  color: #243249;
  font-size: 17px;
  font-weight: 750;
}

.section-heading p {
  margin: 0;
  color: #8e99a9;
  font-size: 12px;
}

/* ============================================================ */
/* DASHBOARD METRICS */
/* ============================================================ */

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
}

.metric-card {
  padding: 23px;
  border: 1px solid #e8edf3;
  border-radius: 12px;
  background: #fff;
}

.metric-label {
  margin: 0 0 16px;
  color: #77869a;
  font-size: 12px;
  font-weight: 600;
}

.metric-value {
  margin-bottom: 12px;
  color: #1b2d44;
  font-size: 30px;
  font-weight: 750;
}

.metric-description {
  margin: 0;
  color: #a1acb9;
  font-size: 11px;
}

/* ============================================================ */
/* QUICK ACCESS CARDS */
/* ============================================================ */

.quick-access-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}

.quick-access-card {
  display: flex;
  align-items: center;
  gap: 17px;
  width: 100%;
  min-height: 115px;
  padding: 22px;
  border: 1px solid #e8edf3;
  border-radius: 12px;
  background: #fff;
  color: #243249;
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.2s ease,
    transform 0.2s ease;
}

.quick-access-card:hover {
  border-color: #69c9b8;
  transform: translateY(-2px);
}

.quick-access-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  flex-shrink: 0;
  border-radius: 11px;
  background: #e9f7f3;
  color: #188e76;
  font-size: 25px;
}

.quick-access-content {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
}

.quick-access-content strong {
  font-size: 13px;
}

.quick-access-content > span {
  color: #8794a6;
  font-size: 11px;
  line-height: 1.6;
}

.quick-access-arrow {
  color: #8d9bad;
  font-size: 20px;
}

/* ============================================================ */
/* DEVELOPMENT NOTICE */
/* ============================================================ */

.development-notice {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-top: 30px;
  padding: 16px 18px;
  border: 1px solid #dce9ec;
  border-radius: 10px;
  background: #ecf6f6;
}

.notice-icon {
  color: #318c89;
  font-size: 17px;
}

.development-notice p {
  margin: 0;
  color: #618186;
  font-size: 12px;
  line-height: 1.7;
}

/* ============================================================ */
/* PLACEHOLDER PAGES */
/* ============================================================ */

.empty-page {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 430px;
  padding: 50px 25px;
  border: 1px solid #e8edf3;
  border-radius: 14px;
  background: #fff;
  text-align: center;
}

.empty-page-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 68px;
  height: 68px;
  margin-bottom: 21px;
  border-radius: 18px;
  background: #e7f7f2;
  color: #189279;
  font-size: 30px;
}

.empty-page h2 {
  margin: 0 0 12px;
  color: #22344b;
  font-size: 22px;
}

.empty-page > p {
  max-width: 460px;
  margin: 0;
  color: #8592a4;
  font-size: 13px;
  line-height: 1.8;
}

.upcoming-feature {
  max-width: 100%;
  margin: 25px 0;
  padding: 12px 16px;
  border-radius: 8px;
  background: #f2f5f8;
  color: #688096;
  font-size: 11px;
  overflow-wrap: anywhere;
}

.secondary-button {
  padding: 11px 17px;
  border: 1px solid #d6e0e9;
  border-radius: 8px;
  background: #fff;
  color: #36536d;
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
}

.secondary-button:hover {
  background: #f5f8fa;
}

/* ============================================================ */
/* RESPONSIVE LAYOUT */
/* ============================================================ */

/**
 * Adjust the layout for medium-sized screens.
 */
@media (max-width: 1150px) {

  .main-content {
    padding: 32px;
  }

  .welcome-banner {
    flex-direction: column;
    align-items: stretch;
  }

  .welcome-content {
    max-width: 100%;
  }

  .journal-preview {
    width: 100%;
  }
}

/**
 * On smaller screens, stack the sidebar
 * above the main application content.
 */
@media (max-width: 760px) {

  .app-layout {
    flex-direction: column;
  }

  .sidebar {
    width: 100%;
    min-height: auto;
    padding: 18px;
  }

  .brand {
    margin-bottom: 22px;
  }

  .sidebar-navigation {
    flex-direction: row;
    flex-wrap: wrap;
  }

  .nav-item {
    width: auto;
  }

  .sidebar-section-title,
  .sidebar-footer {
    display: none;
  }

  .main-content {
    padding: 24px 18px;
  }

  .page-header {
    flex-direction: column;
  }

  .page-header h1 {
    font-size: 25px;
  }

  .welcome-banner {
    padding: 24px;
  }

  .welcome-content h2 {
    font-size: 23px;
  }

  .metrics-grid,
  .quick-access-grid {
    grid-template-columns: 1fr;
  }

  .journal-table-header,
  .journal-row,
  .journal-total {
    grid-template-columns: minmax(0, 1fr) 65px 65px;
  }
}

</style>
