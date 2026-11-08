/**
 * ============================================================
 * LEDGERBASE — AUTHENTICATED REPORTING BENCHMARK
 * ============================================================
 *
 * PURPOSE:
 *
 * Benchmark the Trial Balance reporting endpoint through
 * the deployed frontend and Spring Cloud Gateway.
 *
 * REQUIREMENTS:
 *
 * - Valid short-lived JWT access token.
 * - Business ID accessible to the authenticated user.
 * - Reporting date that is not in the future.
 *
 * SAFETY:
 *
 * - Read-only GET requests.
 * - Does not modify financial records.
 * - Does not print or persist authentication tokens.
 * - No credentials are stored in source code.
 */

import http from 'k6/http';
import { check } from 'k6';

// ============================================================
// CONFIGURATION
// ============================================================

const BASE_URL =
  __ENV.BASE_URL || 'http://127.0.0.1:3001';

const ACCESS_TOKEN = __ENV.ACCESS_TOKEN;

const BUSINESS_ID = __ENV.BUSINESS_ID;

const AS_OF_DATE = __ENV.AS_OF_DATE;

if (!ACCESS_TOKEN || !BUSINESS_ID || !AS_OF_DATE) {
  throw new Error(
    'ACCESS_TOKEN, BUSINESS_ID, and AS_OF_DATE are required.'
  );
}

if (!/^\d+$/.test(BUSINESS_ID)) {
  throw new Error('BUSINESS_ID must be a numeric ID.');
}

if (!/^\d{4}-\d{2}-\d{2}$/.test(AS_OF_DATE)) {
  throw new Error('AS_OF_DATE must use YYYY-MM-DD.');
}

// ============================================================
// LOAD PROFILE
// ============================================================
//
// Use a small fixed arrival rate initially.
//
// A later benchmark will introduce warm-up, repeated
// measurements, and controlled cache-on/off conditions.

export const options = {
  scenarios: {
    reporting_baseline: {
      executor: 'constant-arrival-rate',
      rate: 2,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 5,
      maxVUs: 10,
    },
  },

  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<2000'],
    checks: ['rate>0.99'],
    dropped_iterations: ['count==0'],
  },
};

// ============================================================
// AUTHENTICATED REPORT REQUEST
// ============================================================

export default function () {
  const endpoint =
    `${BASE_URL}/api/v1/businesses/${BUSINESS_ID}` +
    `/reports/trial-balance?asOfDate=${AS_OF_DATE}`;

  const response = http.get(endpoint, {
    headers: {
      Authorization: `Bearer ${ACCESS_TOKEN}`,
      Accept: 'application/json',
    },

    tags: {
      endpoint: 'trial-balance',
      benchmark: 'reporting-baseline',
    },

    timeout: '10s',
  });

  // Verify a successful HTTP response and valid JSON.
  // Keep payload-specific assertions for the next step,
  // after confirming the exact report response contract.
  check(response, {
    'Trial Balance returns HTTP 200': (res) =>
      res.status === 200,

    'Trial Balance returns JSON': (res) => {
      if (res.status !== 200) {
        return false;
      }

      try {
        const report = res.json();

        return (
          report !== null &&
          typeof report === 'object' &&
          !Array.isArray(report)
        );
      } catch (error) {
        return false;
      }
    },
  });
}
