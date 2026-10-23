// ============================================================
// IMPORTS
// ============================================================

import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// ============================================================
// LEDGERBASE — REACT VITE CONFIGURATION
// ============================================================

export default defineConfig({

  // Enable React support.
  plugins: [react()],

  // ============================================================
  // DEVELOPMENT SERVER
  // ============================================================

  server: {

    // React runs on port 5173.
    port: 5173,

    // Prevent automatic port switching.
    strictPort: true,

    // ============================================================
    // SPRING CLOUD API GATEWAY PROXY
    // ============================================================

    /**
    * Forward all requests starting with /api
    * to the Spring Cloud API Gateway.
    *
    * Request flow:
    *
    * React frontend (5173)
    *       |
    *       v
    * Spring Cloud Gateway (8081)
    *       |
    *       v
    * LedgerBase backend (8080)
    *       |
    *       v
    * PostgreSQL primary / read replica
    *
    * The gateway handles API routing.
    * The backend continues handling
    * authentication, RBAC, and accounting logic.
    *
    * No URL path rewriting is needed.
    */
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8081',
        changeOrigin: true,
      },
    },
  },
})
