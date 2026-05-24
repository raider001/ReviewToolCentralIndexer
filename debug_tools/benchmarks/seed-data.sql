-- Benchmark seed data: 100 repositories, 50 000 branches (500 per repo), 10 000 reviews
-- Run against a local or Docker PostgreSQL instance after schema initialisation.
--
-- Usage:
--   psql "$DATABASE_URL" -f seed-data.sql
--
-- Expected runtime: ~10–30 seconds depending on hardware.

BEGIN;

-- -----------------------------------------------------------------------
-- 1. Repositories  (100 rows)
-- -----------------------------------------------------------------------
INSERT INTO repositories (owner, repository, url)
SELECT
    'bench-org',
    'repo-' || i,
    'https://github.com/bench-org/repo-' || i || '.git'
FROM generate_series(1, 100) AS s(i)
ON CONFLICT (owner, repository) DO NOTHING;

-- -----------------------------------------------------------------------
-- 2. Branches  (500 per repo = 50 000 rows)
--    branch_name cycles through feature/bench-NNNN patterns to simulate
--    realistic name distribution with some shared prefixes.
-- -----------------------------------------------------------------------
INSERT INTO branches (owner, repository, branch_name, head_commit, updated_at)
SELECT
    'bench-org',
    'repo-' || r,
    'feature/bench-' || lpad(b::text, 4, '0'),
    md5(r::text || '-' || b::text),    -- deterministic fake SHA
    now() - (random() * interval '90 days')
FROM
    generate_series(1, 100) AS repos(r),
    generate_series(1, 500) AS branches(b)
ON CONFLICT (owner, repository, branch_name) DO NOTHING;

-- -----------------------------------------------------------------------
-- 3. Reviews  (10 000 rows in reviews_index, one branch mapping each)
-- -----------------------------------------------------------------------

-- 3a. reviews_index rows (status alternates open/closed for realistic mix)
INSERT INTO reviews_index (review_id, status, last_updated, repositories)
SELECT
    'bench-review-' || i,
    CASE WHEN i % 3 = 0 THEN 'closed' ELSE 'open' END,
    now() - (random() * interval '60 days'),
    '[]'::jsonb
FROM generate_series(1, 10000) AS s(i)
ON CONFLICT (review_id) DO NOTHING;

-- 3b. review_branches mappings (one branch per review, spread across repos/branches)
INSERT INTO review_branches (review_id, owner, repository, branch_name)
SELECT
    'bench-review-' || i,
    'bench-org',
    'repo-' || ((i - 1) % 100 + 1),
    'feature/bench-' || lpad(((i - 1) % 500 + 1)::text, 4, '0')
FROM generate_series(1, 10000) AS s(i)
ON CONFLICT (review_id, owner, repository, branch_name) DO NOTHING;

-- 3c. Backfill repositories JSONB using the same INNER JOIN logic as BackfillBranchesTool
UPDATE reviews_index ri
SET repositories = sub.repos_json
FROM (
    SELECT
        rb.review_id,
        jsonb_agg(
            jsonb_build_object(
                'owner',       rb.owner,
                'repository',  rb.repository,
                'branchName',  rb.branch_name,
                'headCommit',  b.head_commit,
                'url',         r.url
            )
            ORDER BY rb.owner, rb.repository, rb.branch_name
        ) AS repos_json
    FROM review_branches rb
    JOIN branches b
        ON rb.owner = b.owner
        AND rb.repository = b.repository
        AND rb.branch_name = b.branch_name
    JOIN repositories r
        ON rb.owner = r.owner
        AND rb.repository = r.repository
    WHERE rb.review_id LIKE 'bench-review-%'
    GROUP BY rb.review_id
) sub
WHERE ri.review_id = sub.review_id;

COMMIT;

-- Verify row counts
SELECT 'repositories' AS tbl, count(*) FROM repositories WHERE owner = 'bench-org'
UNION ALL
SELECT 'branches',           count(*) FROM branches      WHERE owner = 'bench-org'
UNION ALL
SELECT 'reviews_index',      count(*) FROM reviews_index WHERE review_id LIKE 'bench-review-%'
UNION ALL
SELECT 'review_branches',    count(*) FROM review_branches WHERE review_id LIKE 'bench-review-%';
