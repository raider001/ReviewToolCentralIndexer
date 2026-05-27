# Event Overview

Maps every event type to the webhook condition that triggers it, the database action taken, and the SSE event fired to the client.

For the SSE event format and client-side handling of each event, see [`ServerlessReviewTool/Documentation/Design/Interfaces/Client-Interface.md`](../../../../../ServerlessReviewTool/Documentation/Design/Interfaces/Client-Interface.md).

---

## Trigger Sources

All events originate from one of two webhook payload types:

| Payload type | Ref pattern | What it signals |
|---|---|---|
| `commit.pushed` | `refs/heads/kalynx-reviews` | Review metadata or comment change |
| `commit.pushed` | `refs/heads/{branch}` | Code change on a tracked branch |
| `branch.created` | `refs/heads/{branch}` | New branch appeared |
| `branch.deleted` | `refs/heads/{branch}` | Branch removed |

---

## Event Table

| Event | Trigger condition | DB action | SSE fired | Detail |
|---|---|---|---|---|
| `review.created` | Push to `kalynx-reviews`; `reviews/{reviewId}/metadata/*` paths appear for a `review_id` not yet in `reviews` | `INSERT reviews`, `INSERT review_branches` | `review.created` | [Review-Update-Sequences](Review-Update-Sequences.md#review-created) |
| `review.updated` | Push to `kalynx-reviews`; `reviews/{reviewId}/metadata/status` changes for a known review | `UPDATE reviews SET status, last_updated` | `review.updated` | [Review-Update-Sequences](Review-Update-Sequences.md#review-status-updated) |
| `review.updated` | Push to `kalynx-reviews`; same `review_id` appears in a new repository (association added) | `UPSERT reviews`, `INSERT review_branches` | `review.updated` | [Code-Update-Sequences](Code-Update-Sequences.md#repository-associated-to-review) |
| `review.updated` | Push to `kalynx-reviews`; `review_id` tree absent from a repository (association removed) | `DELETE review_branches` | `review.updated` | [Code-Update-Sequences](Code-Update-Sequences.md#repository-dissociated-from-review) |
| `branch.updated` | Push to `refs/heads/{branch}`; branch matches an open review | None | `branch.updated` | [Code-Update-Sequences](Code-Update-Sequences.md#commit-pushed) |
| `branch.created` | New `refs/heads/{branch}` in tracked repository | `INSERT branches`; `INSERT review_branches` if branch matches open review | `review.updated` (if matched) | [Code-Update-Sequences](Code-Update-Sequences.md#branch-created) |
| `branch.deleted` | Deletion of `refs/heads/{branch}` in tracked repository | `DELETE branches`; `DELETE review_branches` for open reviews | `branch.deleted` | [Code-Update-Sequences](Code-Update-Sequences.md#branch-deleted) |
| `comment.added` | Push to `kalynx-reviews`; a `reviews/{reviewId}/threads/*` path is new or gains a `COMMENT`/`REPLY` event | `UPSERT comments_index` | `comment.added` | [Comment-Update-Sequences](Comment-Update-Sequences.md#comment-added) |
| `comment.updated` | Push to `kalynx-reviews`; a `reviews/{reviewId}/threads/*` path gains a `STATUS` event | `UPSERT comments_index` | `comment.updated` | [Comment-Update-Sequences](Comment-Update-Sequences.md#comment-status-updated) |

---

## Deduplication

All webhook events are checked against an in-memory dedup set before processing. A delivery ID (or derived hash of provider + ref + commit SHA) that has already been seen within the current process lifetime is discarded with a `200 OK`. This prevents double-firing on webhook retries.

---

## Closed Review Guard

Events that would modify a closed review (`COMPLETED` or `CANCELLED`) are silently discarded after the status check. The exception is `branch.deleted` — the branch association is cleaned up even for closed reviews to avoid stale routing data.

---

## Event Payload Summary

| SSE event | Payload fields |
|---|---|
| `review.created` | `review_id` |
| `review.updated` | `review_id` |
| `branch.updated` | `review_id`, `repository`, `repository_url`, `branch_name`, `head_commit` |
| `branch.deleted` | `review_id`, `repository`, `repository_url`, `branch_name` |
| `comment.added` | `review_id`, `repository_url`, `thread_key` |
| `comment.updated` | `review_id`, `repository_url`, `thread_key` |
