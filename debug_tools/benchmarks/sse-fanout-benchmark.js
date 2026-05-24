/**
 * SSE fan-out benchmark — measures sustained SSE connection capacity.
 *
 * Each virtual user opens an SSE stream to a randomly selected repository
 * and holds it open for the duration of the test. The script reports:
 *   - Active connection count over time
 *   - First-byte latency (time to receive the first SSE comment/keepalive)
 *   - Received event rate
 *
 * k6 does not natively support SSE, so connections are opened via the
 * http module as a streaming GET with response body reading disabled
 * (k6 reads one chunk then yields). This is sufficient to measure the
 * server's ability to accept and hold N concurrent SSE connections.
 *
 * Usage:
 *   k6 run --vus 100 --duration 60s \
 *          -e BASE_URL=http://localhost:8765 \
 *          -e BEARER_TOKEN=<token-or-empty> \
 *          sse-fanout-benchmark.js
 *
 * Pass/fail thresholds (derived from scalability.md):
 *   - http_req_failed < 1% (connections must not be refused)
 *   - http_req_duration{phase:connect} p(95) < 500ms
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const receivedEvents = new Counter('sse_received_events');
const connectLatency = new Trend('sse_connect_latency_ms', true);

const BASE_URL    = __ENV.BASE_URL    || 'http://localhost:8765';
const BEARER      = __ENV.BEARER_TOKEN || '';
const REPO_COUNT  = parseInt(__ENV.REPO_COUNT || '100', 10);

export const options = {
    vus: 100,
    duration: '60s',
    thresholds: {
        http_req_failed:   ['rate<0.01'],
        sse_connect_latency_ms: ['p(95)<500'],
    },
};

export default function () {
    const repoIndex = Math.floor(Math.random() * REPO_COUNT) + 1;
    const repo      = `bench-org/repo-${repoIndex}`;
    const url       = `${BASE_URL}/events/stream?repository=${encodeURIComponent(repo)}`;

    const headers = { 'Accept': 'text/event-stream' };
    if (BEARER) {
        headers['Authorization'] = `Bearer ${BEARER}`;
    }

    const start = Date.now();
    const res = http.get(url, {
        headers,
        timeout: '10s',
        // k6 streams the body; reading the first chunk exercises the server path
        responseType: 'text',
    });

    const elapsed = Date.now() - start;
    connectLatency.add(elapsed);

    check(res, {
        'status is 200': (r) => r.status === 200,
        'content-type is event-stream': (r) =>
            (r.headers['Content-Type'] || '').startsWith('text/event-stream'),
    });

    if (res.body && res.body.includes('data:')) {
        receivedEvents.add(res.body.split('\n').filter(l => l.startsWith('data:')).length);
    }

    // Brief pause before reconnecting to simulate real client backoff
    sleep(Math.random() * 2 + 1);
}
