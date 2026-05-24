#!/usr/bin/env bash
# Smoke test for a live CentralIndexer instance.
#
# Usage:
#   ./smoke-test.sh <BASE_URL> [--token <BEARER_TOKEN>]
#
# Examples:
#   ./smoke-test.sh http://localhost:8765
#   ./smoke-test.sh https://indexer.example.com --token my-secret
#
# Exit code: 0 if all checks pass, 1 if any check fails.

set -euo pipefail

BASE_URL="${1:-http://localhost:8765}"
BEARER_TOKEN=""
PASS=0
FAIL=0

shift 1 2>/dev/null || true
while [[ $# -gt 0 ]]; do
    case "$1" in
        --token) BEARER_TOKEN="$2"; shift 2 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

RED='\033[0;31m'
GRN='\033[0;32m'
YLW='\033[0;33m'
RST='\033[0m'

pass() { echo -e "  ${GRN}PASS${RST}  $1"; ((PASS++)); }
fail() { echo -e "  ${RED}FAIL${RST}  $1"; ((FAIL++)); }
info() { echo -e "  ${YLW}INFO${RST}  $1"; }

auth_header() {
    if [[ -n "$BEARER_TOKEN" ]]; then
        echo "-H \"Authorization: Bearer ${BEARER_TOKEN}\""
    fi
}

curl_get() {
    local path="$1"
    local extra_args="${2:-}"
    eval curl -s -o /tmp/smoke_body -D /tmp/smoke_headers \
        -w "%{http_code}" \
        --connect-timeout 10 \
        --max-time 15 \
        $(auth_header) \
        $extra_args \
        "\"${BASE_URL}${path}\""
}

check_header() {
    local header_name="$1"
    local expected_value="$2"
    grep -i "^${header_name}:" /tmp/smoke_headers | grep -qi "${expected_value}"
}

echo ""
echo "CentralIndexer Smoke Test"
echo "Target: ${BASE_URL}"
echo "Auth: $([ -n "$BEARER_TOKEN" ] && echo 'Bearer token provided' || echo 'none')"
echo "=================================================="

# --- GET /health ---
echo ""
echo "GET /health"
status=$(curl_get "/health")
body=$(cat /tmp/smoke_body)

if [[ "$status" == "200" ]]; then
    pass "HTTP 200"
else
    fail "HTTP $status (expected 200)"
fi

if echo "$body" | grep -q '"status":"UP"'; then
    pass "body contains \"status\":\"UP\""
else
    fail "body missing \"status\":\"UP\" — got: $body"
fi

if echo "$body" | grep -q '"db":"UP"'; then
    pass "body contains \"db\":\"UP\""
else
    fail "body missing \"db\":\"UP\" — got: $body"
fi

if check_header "Content-Type" "application/json"; then
    pass "Content-Type: application/json"
else
    fail "Content-Type not application/json"
fi

# --- GET /metrics ---
echo ""
echo "GET /metrics"
status=$(curl_get "/metrics")
body=$(cat /tmp/smoke_body)

if [[ "$status" == "200" ]]; then
    pass "HTTP 200"
else
    fail "HTTP $status (expected 200)"
fi

for key in '"sse"' '"db"' '"branches"' '"backfill"'; do
    if echo "$body" | grep -q "$key"; then
        pass "body contains $key"
    else
        fail "body missing $key — got: $body"
    fi
done

for sub_key in '"connected_clients_total"' '"writers_per_second"' '"write_latency_p95_ms"' \
               '"get_reviews_p95_ms"' '"pool_active_connections"' '"pool_waiting_threads"' \
               '"typeahead_p95_ms"' '"progress_pct"'; do
    if echo "$body" | grep -q "$sub_key"; then
        pass "body contains $sub_key"
    else
        fail "body missing $sub_key"
    fi
done

if echo "$body" | grep -q '"pool_waiting_threads":0'; then
    pass "pool_waiting_threads is 0 (no DB contention)"
else
    info "pool_waiting_threads is non-zero — check for DB pool pressure"
fi

# --- GET /reviews ---
echo ""
echo "GET /reviews"
status=$(curl_get "/reviews")
body=$(cat /tmp/smoke_body)

if [[ "$status" == "200" ]]; then
    pass "HTTP 200"
else
    fail "HTTP $status (expected 200)"
fi

if echo "$body" | grep -q '"items"'; then
    pass "body contains \"items\""
else
    fail "body missing \"items\" — got: $body"
fi

if check_header "Content-Type" "application/json"; then
    pass "Content-Type: application/json"
else
    fail "Content-Type not application/json"
fi

# --- GET /branches ---
echo ""
echo "GET /branches"
status=$(curl_get "/branches")
body=$(cat /tmp/smoke_body)

if [[ "$status" == "200" ]]; then
    pass "HTTP 200"
else
    fail "HTTP $status (expected 200)"
fi

if echo "$body" | grep -q '"branches"'; then
    pass "body contains \"branches\""
else
    fail "body missing \"branches\" — got: $body"
fi

if echo "$body" | grep -q '"next_cursor"'; then
    pass "body contains \"next_cursor\""
else
    fail "body missing \"next_cursor\" — got: $body"
fi

# --- SSE endpoint reachability ---
echo ""
echo "GET /events/stream (reachability check)"
status=$(curl_get "/events/stream?repository=smoke-test" "--max-time 2" 2>/dev/null || echo "000")
if [[ "$status" == "200" || "$status" == "401" || "$status" == "403" ]]; then
    pass "SSE endpoint reachable (HTTP $status)"
else
    fail "SSE endpoint unreachable or unexpected status (HTTP $status)"
fi

# --- POST /metrics → 405 ---
echo ""
echo "POST /metrics (must return 405)"
status=$(eval curl -s -o /dev/null -w "%{http_code}" \
    --connect-timeout 10 \
    --max-time 15 \
    -X POST \
    "\"${BASE_URL}/metrics\"")
if [[ "$status" == "405" ]]; then
    pass "POST /metrics → 405 Method Not Allowed"
else
    fail "POST /metrics → $status (expected 405)"
fi

# --- Summary ---
echo ""
echo "=================================================="
TOTAL=$((PASS + FAIL))
if [[ $FAIL -eq 0 ]]; then
    echo -e "${GRN}All ${TOTAL} checks passed.${RST}"
    exit 0
else
    echo -e "${RED}${FAIL} of ${TOTAL} checks FAILED.${RST}"
    exit 1
fi
