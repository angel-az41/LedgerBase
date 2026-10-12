import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// ============================================================
// VITE CONFIGURATION
// ============================================================

/**
 * Configure the LedgerBase frontend development environment.
 *
 * Vite provides:
 * - A local development server
 * - Vue single-file component support
 * - TypeScript compilation
 * - Hot Module Replacement (HMR)
 * - API proxying to Spring Boot
 */
export default defineConfig({

  // ============================================================
  // VUE PLUGINS
  // ============================================================

  /**
   * vue() enables Vue 3 single-file components.
   *
   * vueDevTools() provides debugging tools
   * while developing our Vue application.
   */
  plugins: [
    vue(),
    vueDevTools(),
  ],

  // ============================================================
  // IMPORT ALIASES
  // ============================================================

  /**
   * The @ alias points to the frontend/src directory.
   *
   * Example:
   *
   * import App from '@/App.vue'
   *
   * Instead of using relative paths such as:
   *
   * import App from '../../App.vue'
   */
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },

  // ============================================================
  // DEVELOPMENT SERVER
  // ============================================================

  /**
   * Configure how Vite serves our frontend locally.
   *
   * The Vue application runs at:
   * http://localhost:5173
   *
   * The Spring Boot backend runs at:
   * http://localhost:8080
   */
  server: {

    // Port used by the Vue frontend.
    port: 5173,

    /**
     * Forward requests beginning with /api
     * to our Spring Boot backend.
     *
     * Example:
     *
     * Vue requests:
     * /api/v1/businesses/1/accounts
     *
     * Vite forwards to:
     * http://localhost:8080/api/v1/businesses/1/accounts
     *
     * The path is preserved because no rewrite
     * configuration is specified.
     *
     * This proxy is for local development only.
     */
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
