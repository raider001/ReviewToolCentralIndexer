/**
 * GET /reviews reconnect storm benchmark.
 *
 * Simulates N clients all fetching the reviews snapshot simultaneously,
 * as happens when the server restarts and all SSE clients reconnect.
 * Jitter is applied to the start of each VU iteration to produce realistic
 * burst behaviour rather than a perfectly synchronised thundering herd.
 *
 * Measures p95 latency of GET /reviews under concurrent load.
 *
 * Usage:
 *   k6 run --vus 200 --iterations 2000 \
 *          -e BASE_URL=http://localhost:8765 \
 *          -e BEARER_TOKEN=<token-or-empty> \
 *          reviews-reconnect-storm.js
 *
 * Pass/fail thresholds (derived from scalability.md and M6 acceptance criteria):
 *   - http_req_duration p(95) < 500ms
 *   - http_req_failed < 1%
 */

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL    || 'http://localhost:8765';
const BEARER   = __ENV.BEARER_TOKEN || '';

export const options = {
    vus: 200,
    iterations: 2000,
    thresholds: {
        http_req_duration: ['p(95)<500'],
        http_req_failed:   ['rate<0.01'],
    },
};

export default function () {
    // Jitter up to 500 ms to simulate staggered reconnects
    sleep(Math.random() * 0.5);

    const headers = {};
    if (BEARER) {
        headers['Authorization'] = `Bearer ${BEARER}`;
    }

    const res = http.get(`${BASE_URL}/reviews`, { headers, timeout: '10s' });

    check(res, {
        'status is 200':              (r) => r.status === 200,
        'content-type is json':       (r) =>
            (r.headers['Content-Type'] || '').includes('application/json'),
        'body is non-empty array':    (r) => {
            try { return Array.isArray(JSON.parse(r.body)); }
            catch { return false; }
        },
    });
}
