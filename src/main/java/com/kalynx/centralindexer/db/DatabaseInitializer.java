package com.kalynx.centralindexer.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates and migrates the database schema on startup.
 *
 * <p>All DDL is idempotent — safe to run against a fresh schema or an existing one.
 * No migration framework is required; idempotency is achieved via
 * {@code CREATE TABLE IF NOT EXISTS}, {@code ADD COLUMN IF NOT EXISTS},
 * {@code DROP COLUMN IF EXISTS}, and PL/pgSQL {@code DO} blocks that check
 * {@code pg_constraint} before adding or dropping named constraints.
 *
 * <h2>Target Schema (post-M1)</h2>
 * <pre>{@code
 * CREATE TABLE reviews_index (
 *     review_id     TEXT        PRIMARY KEY,
 *     status        TEXT,
 *     last_updated  TIMESTAMPTZ,
 *     repositories  JSONB
 * );
 *
 * CREATE TABLE repositories (
 *     repository_id       UUID        PRIMARY KEY,  -- surrogate key (stable)
 *     owner               TEXT        NOT NULL,     -- metadata only
 *     repository          TEXT        NOT NULL,     -- metadata only
 *     url                 TEXT        NOT NULL UNIQUE,
 *     kalynx_review_head  TEXT
 * );
 *
 * CREATE TABLE branches (
 *     repository_id UUID        NOT NULL REFERENCES repositories (repository_id),
 *     branch_name   TEXT        NOT NULL,
 *     head_commit   TEXT        NOT NULL,
 *     PRIMARY KEY (repository_id, branch_name)
 * );
 *
 * CREATE TABLE review_branches (
 *     review_id     TEXT NOT NULL REFERENCES reviews_index (review_id),
 *     repository_id UUID NOT NULL REFERENCES repositories (repository_id),
 *     branch_name   TEXT NOT NULL,
 *     PRIMARY KEY (review_id, repository_id, branch_name),
 *     FOREIGN KEY (repository_id, branch_name) REFERENCES branches (repository_id, branch_name)
 * );
 *
 * CREATE TABLE comments_index (
 *     comment_id    UUID        PRIMARY KEY,
 *     review_id     TEXT        NOT NULL REFERENCES reviews_index (review_id),
 *     repository_id UUID        NOT NULL REFERENCES repositories (repository_id),
 *     last_updated  TIMESTAMPTZ NOT NULL
 * );
 * }</pre>
 */
public final class DatabaseInitializer {

    private final ConnectionPool pool;

    /**
     * Constructs a {@code DatabaseInitializer} that uses the supplied connection pool.
     *
     * @param pool the connection pool to use for DDL execution
     */
    public DatabaseInitializer(ConnectionPool pool) {
        this.pool = pool;
    }

    /**
     * Creates and migrates all required tables and indexes.
     *
     * @throws SQLException         if any DDL statement fails
     * @throws InterruptedException if the thread is interrupted while waiting for a connection
     */
    public void init() throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try {
            executeDdl(conn);
        } finally {
            pool.release(conn);
        }
    }

    private void executeDdl(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Base tables (Spike 2)
            stmt.execute(createReviewsIndexTable());
            stmt.execute(createReviewsIndexLastUpdatedIndex());
            stmt.execute(createReviewsIndexRepositoriesGinIndex());
            stmt.execute(createRepositoriesTable());
            stmt.execute(addKalynxReviewHeadColumn());
            stmt.execute(createBranchesTable());
            stmt.execute(createReviewBranchesTable());
            stmt.execute(createBranchesNamePrefixIndex());

            // M0: Migrate to surrogate UUID primary key
            migrateToSurrogateKey(stmt);

            // M1: comments_index table
            stmt.execute(createCommentsIndexTable());
            stmt.execute(createCommentsIndexReviewIdIndex());
        }
    }

    // -------------------------------------------------------------------------
    // Base table DDL (Spike 2 — idempotent on already-migrated schema)
    // -------------------------------------------------------------------------

    private String createReviewsIndexTable() {
        return "CREATE TABLE IF NOT EXISTS reviews_index (" +
               "review_id TEXT PRIMARY KEY," +
               "status TEXT," +
               "last_updated TIMESTAMPTZ," +
               "repositories JSONB" +
               ")";
    }

    private String createReviewsIndexLastUpdatedIndex() {
        return "CREATE INDEX IF NOT EXISTS idx_reviews_index_last_updated ON reviews_index (last_updated DESC)";
    }

    private String createReviewsIndexRepositoriesGinIndex() {
        return "CREATE INDEX IF NOT EXISTS idx_reviews_index_repositories_gin ON reviews_index USING GIN (repositories)";
    }

    private String createRepositoriesTable() {
        return "CREATE TABLE IF NOT EXISTS repositories (" +
               "    owner               TEXT NOT NULL," +
               "    repository          TEXT NOT NULL," +
               "    url                 TEXT NOT NULL," +
               "    kalynx_review_head  TEXT," +
               "    PRIMARY KEY (owner, repository)" +
               ")";
    }

    private String addKalynxReviewHeadColumn() {
        return "ALTER TABLE repositories ADD COLUMN IF NOT EXISTS kalynx_review_head TEXT";
    }

    private String createBranchesTable() {
        return "CREATE TABLE IF NOT EXISTS branches (" +
               "    owner       TEXT NOT NULL," +
               "    repository  TEXT NOT NULL," +
               "    branch_name TEXT NOT NULL," +
               "    head_commit TEXT NOT NULL," +
               "    PRIMARY KEY (owner, repository, branch_name)," +
               "    FOREIGN KEY (owner, repository) REFERENCES repositories (owner, repository)" +
               ")";
    }

    private String createReviewBranchesTable() {
        return "CREATE TABLE IF NOT EXISTS review_branches (" +
               "    review_id   TEXT NOT NULL," +
               "    owner       TEXT NOT NULL," +
               "    repository  TEXT NOT NULL," +
               "    branch_name TEXT NOT NULL," +
               "    PRIMARY KEY (review_id, owner, repository, branch_name)," +
               "    FOREIGN KEY (review_id) REFERENCES reviews_index (review_id)," +
               "    FOREIGN KEY (owner, repository, branch_name) REFERENCES branches (owner, repository, branch_name)" +
               ")";
    }

    private String createBranchesNamePrefixIndex() {
        return "CREATE INDEX IF NOT EXISTS idx_branches_name_prefix " +
               "ON branches (branch_name text_pattern_ops)";
    }

    // -------------------------------------------------------------------------
    // M0: Surrogate UUID primary key migration
    // -------------------------------------------------------------------------

    private void migrateToSurrogateKey(Statement stmt) throws SQLException {
        // Step 1: Add repository_id to repositories
        stmt.execute("ALTER TABLE repositories ADD COLUMN IF NOT EXISTS repository_id UUID DEFAULT gen_random_uuid()");
        stmt.execute("UPDATE repositories SET repository_id = gen_random_uuid() WHERE repository_id IS NULL");
        stmt.execute("ALTER TABLE repositories ALTER COLUMN repository_id SET NOT NULL");
        // Drop the branches FK that references repositories_pkey before we can drop that PK
        stmt.execute(dropBranchesFkOnRepositoriesCompositeKey());
        stmt.execute(migrateRepositoriesPkAndUnique());

        // Step 2: Add repository_id to branches (backfill from repositories JOIN)
        stmt.execute("ALTER TABLE branches ADD COLUMN IF NOT EXISTS repository_id UUID");
        stmt.execute(
            "DO $$ BEGIN " +
            "  IF EXISTS (SELECT 1 FROM information_schema.columns " +
            "             WHERE table_schema = 'public' AND table_name = 'branches' " +
            "             AND column_name = 'owner') THEN " +
            "    UPDATE branches b SET repository_id = r.repository_id " +
            "    FROM repositories r " +
            "    WHERE b.owner = r.owner AND b.repository = r.repository AND b.repository_id IS NULL; " +
            "  END IF; " +
            "END $$"
        );
        stmt.execute("ALTER TABLE branches ALTER COLUMN repository_id SET NOT NULL");

        // Step 3: Add repository_id to review_branches (backfill from repositories JOIN)
        stmt.execute("ALTER TABLE review_branches ADD COLUMN IF NOT EXISTS repository_id UUID");
        stmt.execute(
            "DO $$ BEGIN " +
            "  IF EXISTS (SELECT 1 FROM information_schema.columns " +
            "             WHERE table_schema = 'public' AND table_name = 'review_branches' " +
            "             AND column_name = 'owner') THEN " +
            "    UPDATE review_branches rb SET repository_id = r.repository_id " +
            "    FROM repositories r " +
            "    WHERE rb.owner = r.owner AND rb.repository = r.repository AND rb.repository_id IS NULL; " +
            "  END IF; " +
            "END $$"
        );
        stmt.execute("ALTER TABLE review_branches ALTER COLUMN repository_id SET NOT NULL");

        // Step 4: Drop old FK from review_branches to branches before dropping branches_pkey
        stmt.execute(dropReviewBranchesOldBranchesFk());

        // Step 5: Migrate branches PK and FK to repositories
        stmt.execute(migrateBranchesPkAndFk());

        // Step 6: Drop owner/repository columns from branches
        stmt.execute("ALTER TABLE branches DROP COLUMN IF EXISTS owner");
        stmt.execute("ALTER TABLE branches DROP COLUMN IF EXISTS repository");

        // Step 7: Migrate review_branches PK, add new FK to branches
        stmt.execute(migrateReviewBranchesPkAndFk());

        // Step 8: Drop owner/repository columns from review_branches
        stmt.execute("ALTER TABLE review_branches DROP COLUMN IF EXISTS owner");
        stmt.execute("ALTER TABLE review_branches DROP COLUMN IF EXISTS repository");
    }

    private String dropBranchesFkOnRepositoriesCompositeKey() {
        return "DO $$ BEGIN " +
               "  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'branches_owner_repository_fkey' " +
               "             AND conrelid = 'branches'::regclass) THEN " +
               "    ALTER TABLE branches DROP CONSTRAINT branches_owner_repository_fkey; " +
               "  END IF; " +
               "END $$";
    }

    private String migrateRepositoriesPkAndUnique() {
        return "DO $$ BEGIN " +
               // Drop old (owner, repository) composite PK
               "  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'repositories_pkey' " +
               "             AND conrelid = 'repositories'::regclass) THEN " +
               "    ALTER TABLE repositories DROP CONSTRAINT repositories_pkey; " +
               "  END IF; " +
               // Add new UUID PK
               "  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'repositories_pk' " +
               "                 AND conrelid = 'repositories'::regclass) THEN " +
               "    ALTER TABLE repositories ADD CONSTRAINT repositories_pk PRIMARY KEY (repository_id); " +
               "  END IF; " +
               // Add UNIQUE constraint on url
               "  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'repositories_url_unique' " +
               "                 AND conrelid = 'repositories'::regclass) THEN " +
               "    ALTER TABLE repositories ADD CONSTRAINT repositories_url_unique UNIQUE (url); " +
               "  END IF; " +
               "END $$";
    }

    private String migrateBranchesPkAndFk() {
        return "DO $$ BEGIN " +
               // Drop old (owner, repository, branch_name) PK
               "  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'branches_pkey' " +
               "             AND conrelid = 'branches'::regclass) THEN " +
               "    ALTER TABLE branches DROP CONSTRAINT branches_pkey; " +
               "  END IF; " +
               // Add new (repository_id, branch_name) PK
               "  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'branches_pk' " +
               "                 AND conrelid = 'branches'::regclass) THEN " +
               "    ALTER TABLE branches ADD CONSTRAINT branches_pk PRIMARY KEY (repository_id, branch_name); " +
               "  END IF; " +
               // Drop old composite FK to repositories
               "  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'branches_owner_repository_fkey' " +
               "             AND conrelid = 'branches'::regclass) THEN " +
               "    ALTER TABLE branches DROP CONSTRAINT branches_owner_repository_fkey; " +
               "  END IF; " +
               // Add new single-column FK to repositories
               "  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_branches_repository' " +
               "                 AND conrelid = 'branches'::regclass) THEN " +
               "    ALTER TABLE branches ADD CONSTRAINT fk_branches_repository " +
               "        FOREIGN KEY (repository_id) REFERENCES repositories (repository_id); " +
               "  END IF; " +
               "END $$";
    }

    private String dropReviewBranchesOldBranchesFk() {
        return "DO $$ BEGIN " +
               "  IF EXISTS (SELECT 1 FROM pg_constraint " +
               "             WHERE conname = 'review_branches_owner_repository_branch_name_fkey' " +
               "             AND conrelid = 'review_branches'::regclass) THEN " +
               "    ALTER TABLE review_branches DROP CONSTRAINT review_branches_owner_repository_branch_name_fkey; " +
               "  END IF; " +
               "END $$";
    }

    // -------------------------------------------------------------------------
    // M1: comments_index table
    // -------------------------------------------------------------------------

    private String createCommentsIndexTable() {
        return "CREATE TABLE IF NOT EXISTS comments_index (" +
               "    comment_id    UUID        PRIMARY KEY," +
               "    review_id     TEXT        NOT NULL REFERENCES reviews_index (review_id)," +
               "    repository_id UUID        NOT NULL REFERENCES repositories (repository_id)," +
               "    last_updated  TIMESTAMPTZ NOT NULL" +
               ")";
    }

    private String createCommentsIndexReviewIdIndex() {
        return "CREATE INDEX IF NOT EXISTS idx_comments_index_review_id ON comments_index (review_id)";
    }

    private String migrateReviewBranchesPkAndFk() {
        return "DO $$ BEGIN " +
               // Drop old (review_id, owner, repository, branch_name) PK
               "  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'review_branches_pkey' " +
               "             AND conrelid = 'review_branches'::regclass) THEN " +
               "    ALTER TABLE review_branches DROP CONSTRAINT review_branches_pkey; " +
               "  END IF; " +
               // Add new (review_id, repository_id, branch_name) PK
               "  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'review_branches_pk' " +
               "                 AND conrelid = 'review_branches'::regclass) THEN " +
               "    ALTER TABLE review_branches ADD CONSTRAINT review_branches_pk " +
               "        PRIMARY KEY (review_id, repository_id, branch_name); " +
               "  END IF; " +
               // Add new FK to branches on (repository_id, branch_name)
               "  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_review_branches_branch' " +
               "                 AND conrelid = 'review_branches'::regclass) THEN " +
               "    ALTER TABLE review_branches ADD CONSTRAINT fk_review_branches_branch " +
               "        FOREIGN KEY (repository_id, branch_name) REFERENCES branches (repository_id, branch_name); " +
               "  END IF; " +
               "END $$";
    }
}
