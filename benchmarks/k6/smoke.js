/**
 * ============================================================
 * LEDGERBASE — K6 HTTP SMOKE BENCHMARK
 * ============================================================
 *
 * PURPOSE:
 *
 * Verify that k6 can generate controlled traffic against
 * the locally deployed LedgerBase application.
 *
 * This smoke test exercises the frontend reverse proxy,
 * Spring Cloud Gateway, and Accounting Core health endpoint.
 *
 * It does not modify financial records and should not be
 * interpreted as a database throughput benchmark.
 *
 * USAGE:
 *
 * k6 run benchmarks/k6/smoke.js
 *
 * Override the target address:
 *
 * k6 run -e BASE_URL=http://127.0.0.1:3001 \
 *   benchmarks/k6/smoke.js
 */

import http from 'k6/http';
import { check } from 'k6';

// ------------------------------------------------------------
// BENCHMARK CONFIGURATION
// ------------------------------------------------------------

// Use an explicit IPv4 address to avoid ambiguity between
// Docker Desktop's port mapping and IPv6 port-forwarding.
const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:3001';

// One request iteration per second for ten seconds.
// Preallocate a small number of virtual users.
export const options = {
  scenarios: {
    smoke: {
      executor: 'constant-arrival-rate',
      rate: 1,
      timeUnit: '1s',
      duration: '10s',
      preAllocatedVUs: 2,
      maxVUs: 5,
    },
  },

  // Initial smoke-test acceptance criteria.
  // These are deliberately lenient starting thresholds,
  // not established production performance objectives.
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<2000'],
    checks: ['rate>0.99'],
    dropped_iterations: ['count==0'],
  },
};

// ------------------------------------------------------------
// TEST EXECUTION
// ------------------------------------------------------------

export default function () {
  // Exercise the existing LedgerBase database health API.
  const response = http.get(
    `${BASE_URL}/api/v1/health/database`,
    {
      tags: {
        endpoint: 'database-health',
      },
      timeout: '10s',
    }
  );

  // Verify both the HTTP status and application response.
  check(response, {
    'health endpoint returns HTTP 200': (res) =>
      res.status === 200,

    'database reports connected': (res) => {
      try {
        const body = res.json();

        return (
          body.status === 'connected' &&
          body.database === 'ledgerbase'
        );
      } catch (error) {
        return false;
      }
    },
  });
}
