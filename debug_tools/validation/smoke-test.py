#!/usr/bin/env python3
"""Smoke test for a live CentralIndexer instance.

Usage:
    python smoke-test.py [--url BASE_URL] [--token BEARER_TOKEN]

Examples:
    python smoke-test.py
    python smoke-test.py --url http://localhost:8765
    python smoke-test.py --url https://indexer.example.com --token my-secret

Exit code: 0 if all checks pass, 1 if any check fails.
"""
import argparse
import socket
import sys
import urllib.error
import urllib.request

RED = "\033[0;31m"
GRN = "\033[0;32m"
YLW = "\033[0;33m"
RST = "\033[0m"

passes = 0
failures = 0


def pass_(msg):
    global passes
    passes += 1
    print(f"  {GRN}PASS{RST}  {msg}")


def fail(msg):
    global failures
    failures += 1
    print(f"  {RED}FAIL{RST}  {msg}")


def info(msg):
    print(f"  {YLW}INFO{RST}  {msg}")


def get(base_url, path, token=None, timeout=15):
    """Return (status_code, headers_dict, body_str). Never raises."""
    url = base_url + path
    req = urllib.request.Request(url)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, {k.lower(): v for k, v in resp.headers.items()}, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}, e.read().decode("utf-8", errors="replace")
    except Exception:
        return 0, {}, ""


def get_sse(base_url, path, token=None, timeout=2):
    """Connect to an SSE endpoint and return the status code, timing out after `timeout` seconds."""
    url = base_url + path
    req = urllib.request.Request(url)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "text/event-stream")
    try:
        resp = urllib.request.urlopen(req, timeout=timeout)
        status = resp.status
        try:
            resp.read(1)
        except (socket.timeout, TimeoutError, OSError):
            pass
        resp.close()
        return status
    except urllib.error.HTTPError as e:
        return e.code
    except (socket.timeout, TimeoutError, OSError):
        return 0
    except Exception:
        return 0


def post(base_url, path, token=None, timeout=15):
    """POST with no body; return status code."""
    url = base_url + path
    req = urllib.request.Request(url, data=b"", method="POST")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:
        return 0


def main():
    parser = argparse.ArgumentParser(description="Smoke test a live CentralIndexer instance")
    parser.add_argument("--url", default="http://localhost:8765", help="Base URL (default: http://localhost:8765)")
    parser.add_argument("--token", default="", help="Bearer token for authenticated endpoints")
    args = parser.parse_args()

    base_url = args.url.rstrip("/")
    token = args.token or None

    print()
    print("CentralIndexer Smoke Test")
    print(f"Target: {base_url}")
    print(f"Auth:   {'Bearer token provided' if token else 'none'}")
    print("=" * 50)

    # --- GET /health ---
    print("\nGET /health")
    status, headers, body = get(base_url, "/health", token)

    if status == 200:
        pass_("HTTP 200")
    else:
        fail(f"HTTP {status} (expected 200)")

    if '"status":"UP"' in body:
        pass_('body contains "status":"UP"')
    else:
        fail(f'body missing "status":"UP" — got: {body}')

    if '"db":"UP"' in body:
        pass_('body contains "db":"UP"')
    else:
        fail(f'body missing "db":"UP" — got: {body}')

    if "application/json" in headers.get("content-type", ""):
        pass_("Content-Type: application/json")
    else:
        fail(f"Content-Type not application/json — got: {headers.get('content-type', '(missing)')}")

    # --- GET /metrics ---
    print("\nGET /metrics")
    status, headers, body = get(base_url, "/metrics", token)

    if status == 200:
        pass_("HTTP 200")
    else:
        fail(f"HTTP {status} (expected 200)")

    for key in ('"sse"', '"db"', '"branches"', '"backfill"'):
        if key in body:
            pass_(f"body contains {key}")
        else:
            fail(f"body missing {key}")

    for sub_key in (
        '"connected_clients_total"', '"writers_per_second"', '"write_latency_p95_ms"',
        '"get_reviews_p95_ms"', '"pool_active_connections"', '"pool_waiting_threads"',
        '"typeahead_p95_ms"', '"progress_pct"',
    ):
        if sub_key in body:
            pass_(f"body contains {sub_key}")
        else:
            fail(f"body missing {sub_key}")

    if '"pool_waiting_threads":0' in body:
        pass_("pool_waiting_threads is 0 (no DB contention)")
    else:
        info("pool_waiting_threads is non-zero — check for DB pool pressure")

    # --- GET /reviews ---
    print("\nGET /reviews")
    status, headers, body = get(base_url, "/reviews", token)

    if status == 200:
        pass_("HTTP 200")
    else:
        fail(f"HTTP {status} (expected 200)")

    if '"items"' in body:
        pass_('body contains "items"')
    else:
        fail(f'body missing "items" — got: {body}')

    if "application/json" in headers.get("content-type", ""):
        pass_("Content-Type: application/json")
    else:
        fail(f"Content-Type not application/json — got: {headers.get('content-type', '(missing)')}")

    # --- GET /branches ---
    print("\nGET /branches")
    status, headers, body = get(base_url, "/branches", token)

    if status == 200:
        pass_("HTTP 200")
    else:
        fail(f"HTTP {status} (expected 200)")

    if '"branches"' in body:
        pass_('body contains "branches"')
    else:
        fail(f'body missing "branches" — got: {body}')

    if '"next_cursor"' in body:
        pass_('body contains "next_cursor"')
    else:
        fail(f'body missing "next_cursor" — got: {body}')

    # --- SSE endpoint reachability ---
    print("\nGET /events/stream (reachability check)")
    status = get_sse(base_url, "/events/stream?repository=smoke-test", token)
    if status in (200, 401, 403):
        pass_(f"SSE endpoint reachable (HTTP {status})")
    else:
        fail(f"SSE endpoint unreachable or unexpected status (HTTP {status})")

    # --- POST /metrics → 405 ---
    print("\nPOST /metrics (must return 405)")
    status = post(base_url, "/metrics", token)
    if status == 405:
        pass_("POST /metrics → 405 Method Not Allowed")
    else:
        fail(f"POST /metrics → {status} (expected 405)")

    # --- Summary ---
    total = passes + failures
    print()
    print("=" * 50)
    if failures == 0:
        print(f"{GRN}All {total} checks passed.{RST}")
        sys.exit(0)
    else:
        print(f"{RED}{failures} of {total} checks FAILED.{RST}")
        sys.exit(1)


if __name__ == "__main__":
    main()