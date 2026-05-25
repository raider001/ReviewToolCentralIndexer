#!/usr/bin/env python3
"""Query the reviews_index table.

Usage:
    python query-reviews.py [--status STATUS] [--container NAME]
"""
import argparse
import subprocess
import sys


DEFAULT_CONTAINER = "reviewtoolcentralindexer-postgres-1"


def prompt_args():
    container = input(f"Container [{DEFAULT_CONTAINER}]: ").strip() or DEFAULT_CONTAINER
    status = input("Status filter (blank for all, e.g. OPEN, COMPLETED): ").strip()
    return argparse.Namespace(container=container, status=status)


def main():
    parser = argparse.ArgumentParser(description="Query the reviews_index table")
    parser.add_argument("--container", default=DEFAULT_CONTAINER,
                        help="Docker container name (default: reviewtoolcentralindexer-postgres-1)")
    parser.add_argument("--status", default="", help="Filter by status (case-insensitive substring, e.g. OPEN, COMPLETED)")
    args = parser.parse_args() if len(sys.argv) > 1 else prompt_args()

    conditions = []
    if args.status:
        conditions.append(f"status ILIKE '%{args.status}%'")

    where = f" WHERE {' AND '.join(conditions)}" if conditions else ""
    label = f"reviews_index ({', '.join(conditions)})" if conditions else "reviews_index"
    sql = f"SELECT review_id, status, last_updated, repositories FROM reviews_index{where} ORDER BY last_updated DESC;"

    print(f"\n=== {label} ===")
    result = subprocess.run(
        ["docker", "exec", "-i", args.container, "psql", "-U", "postgres", "-d", "indexer", "-c", sql]
    )
    sys.exit(result.returncode)


if __name__ == "__main__":
    main()