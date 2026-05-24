/**
 * GET /branches typeahead benchmark — measures p95 latency for prefix queries.
 *
 * Simulates the client typeahead use case: short prefix strings typed by a
 * user are sent as rapid successive requests. Each VU sends requests with
 * increasingly specific prefixes to exercise the LIKE-prefix index path.
 *
 * Usage:
 *   k6 run --vus 50 --duration 60s \
 *          -e BASE_URL=http://localhost:8765 \
 *          -e BEARER_TOKEN=<token-or-empty> \
 *          branches-typeahead-benchmark.js
 *
 * Pass/fail thresholds (derived from BranchesLoadIT baseline — p95 < 200ms
 * for 50 concurrent clients with 1 000-branch dataset; the seed dataset is
 * larger so 500ms is used as the threshold for 50 000 branches):
 *   - http_req_duration p(95) < 500ms
 *   - http_req_failed < 1%
 */

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL    || 'http://localhost:8765';
const BEARER   = __ENV.BEARER_TOKEN || '';

// Typeahead prefix samples — vary length to hit different selectivity levels
const PREFIXES = [
    'f',
    'fe',
    'fea',
    'feat',
    'featu',
    'featur',
    'feature',
    'feature/',
    'feature/b',
    'feature/be',
    'feature/ben',
    'feature/benc',
    'feature/bench',
    'feature/bench-',
    'feature/bench-00',
];

export const options = {
    vus: 50,
    duration: '60s',
    thresholds: {
        http_req_duration: ['p(95)<500'],
        http_req_failed:   ['rate<0.01'],
    },
};

export default function () {
    const prefix  = PREFIXES[Math.floor(Math.random() * PREFIXES.length)];
    const headers = {};
    if (BEARER) {
        headers['Authorization'] = `Bearer ${BEARER}`;
    }

    const res = http.get(
        `${BASE_URL}/branches?prefix=${encodeURIComponent(prefix)}&limit=20`,
        { headers, timeout: '5s' }
    );

    check(res, {
        'status is 200':          (r) => r.status === 200,
        'content-type is json':   (r) =>
            (r.headers['Content-Type'] || '').includes('application/json'),
        'response is array':      (r) => {
            try { return Array.isArray(JSON.parse(r.body)); }
            catch { return false; }
        },
    });

    // Simulate typing cadence: 200–400 ms between keystrokes
    sleep(Math.random() * 0.2 + 0.2);
}
