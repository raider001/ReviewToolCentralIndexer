#!/usr/bin/env python3
"""Query the review_branches table.

Usage:
    python query-review-branches.py [--review-id ID] [--owner OWNER] [--repository REPO] [--container NAME]
"""
import argparse
import subprocess
import sys


DEFAULT_CONTAINER = "reviewtoolcentralindexer-postgres-1"


def prompt_args():
    container = input(f"Container [{DEFAULT_CONTAINER}]: ").strip() or DEFAULT_CONTAINER
    review_id = input("Review ID filter (blank for all): ").strip()
    owner = input("Owner filter (blank for all): ").strip()
    repository = input("Repository filter (blank for all): ").strip()
    return argparse.Namespace(container=container, review_id=review_id, owner=owner, repository=repository)


def main():
    parser = argparse.ArgumentParser(description="Query the review_branches table")
    parser.add_argument("--container", default=DEFAULT_CONTAINER,
                        help="Docker container name (default: reviewtoolcentralindexer-postgres-1)")
    parser.add_argument("--review-id", default="", help="Filter by review ID (case-insensitive substring)")
    parser.add_argument("--owner", default="", help="Filter by owner (case-insensitive substring)")
    parser.add_argument("--repository", default="", help="Filter by repository name (case-insensitive substring)")
    args = parser.parse_args() if len(sys.argv) > 1 else prompt_args()

    conditions = []
    if args.review_id:
        conditions.append(f"review_id ILIKE '%{args.review_id}%'")
    if args.owner:
        conditions.append(f"owner ILIKE '%{args.owner}%'")
    if args.repository:
        conditions.append(f"repository ILIKE '%{args.repository}%'")

    where = f" WHERE {' AND '.join(conditions)}" if conditions else ""
    label = f"review_branches ({', '.join(conditions)})" if conditions else "review_branches"
    sql = f"SELECT review_id, owner, repository, branch_name FROM review_branches{where} ORDER BY review_id, owner, repository;"

    print(f"\n=== {label} ===")
    result = subprocess.run(
        ["docker", "exec", "-i", args.container, "psql", "-U", "postgres", "-d", "indexer", "-c", sql]
    )
    sys.exit(result.returncode)


if __name__ == "__main__":
    main()