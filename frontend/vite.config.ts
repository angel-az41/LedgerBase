
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
    // SPRING BOOT API PROXY
    // ============================================================

    /**
     * Forward requests starting with /api
     * to our existing Spring Boot backend.
     */
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
