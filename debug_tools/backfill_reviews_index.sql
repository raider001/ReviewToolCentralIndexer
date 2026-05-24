-- Backfill reviews_index from existing normalized tables
-- This script generates one reviews_index row per review with a compact repositories JSON array.

INSERT INTO reviews_index (review_id, status, last_updated, repositories)
SELECT
  r.review_id,
  r.status,
  r.last_updated,
  jsonb_agg(jsonb_build_object(
    'owner', rb.owner,
    'repository', rb.repository,
    'repository_url', rep.url,
    'branch_name', rb.branch_name,
    'head_commit', b.head_commit
  ))
FROM reviews r
JOIN review_branches rb ON r.review_id = rb.review_id
JOIN repositories rep ON rb.owner = rep.owner AND rb.repository = rep.repository
LEFT JOIN branches b ON b.owner = rb.owner AND b.repository = rb.repository AND b.branch_name = rb.branch_name
GROUP BY r.review_id, r.status, r.last_updated
ON CONFLICT (review_id) DO UPDATE
  SET status = EXCLUDED.status,
      last_updated = EXCLUDED.last_updated,
      repositories = EXCLUDED.repositories;

