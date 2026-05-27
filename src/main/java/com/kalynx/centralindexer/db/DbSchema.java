package com.kalynx.centralindexer.db;

/**
 * Shared constants for all table names, column names, and index names in the indexer schema.
 * Use these in {@code SQL_*} constants in each repository class to keep names in one place.
 */
public final class DbSchema {

    private DbSchema() {}

    // --- Tables ------------------------------------------------------------------

    public static final String TABLE_REPOSITORIES    = "repositories";
    public static final String TABLE_BRANCHES        = "branches";
    public static final String TABLE_REVIEW_BRANCHES = "review_branches";
    public static final String TABLE_REVIEWS_INDEX   = "reviews_index";
    public static final String TABLE_COMMENTS_INDEX  = "comments_index";

    // --- Columns — repositories --------------------------------------------------

    public static final String COL_REPOSITORY_ID       = "repository_id";
    public static final String COL_OWNER               = "owner";
    public static final String COL_REPOSITORY          = "repository";
    public static final String COL_URL                 = "url";
    public static final String COL_KALYNX_REVIEW_HEAD  = "kalynx_review_head";

    // --- Columns — branches ------------------------------------------------------

    public static final String COL_BRANCH_NAME  = "branch_name";
    public static final String COL_HEAD_COMMIT  = "head_commit";

    // --- Columns — reviews_index -------------------------------------------------

    public static final String COL_REVIEW_ID    = "review_id";
    public static final String COL_STATUS       = "status";
    public static final String COL_LAST_UPDATED = "last_updated";
    public static final String COL_REPOSITORIES = "repositories";

    // --- Columns — comments_index ------------------------------------------------

    public static final String COL_COMMENT_ID   = "comment_id";
}
