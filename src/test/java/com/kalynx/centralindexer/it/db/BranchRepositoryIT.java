package com.kalynx.centralindexer.it.db;

import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.BranchRecord;
import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import com.kalynx.centralindexer.json.GsonFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link BranchRepository} against a real PostgreSQL container.
 */
@RequiresDocker
class BranchRepositoryIT {

    private PostgresTestContainer container;
    private ConnectionPool pool;
    private BranchRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        container = new PostgresTestContainer();
        pool = buildPool(container);
        new DatabaseInitializer(pool).init();
        repo = new BranchRepository(pool);
    }

    @AfterEach
    void tearDown() {
        pool.close();
        container.close();
    }

    @Test
    void query_noFilters_returnsAllBranches() throws Exception {
        insertRepository("alice", "repo-a", "https://example.com/a");
        insertBranch("alice", "repo-a", "feature/foo", "aaa");
        insertBranch("alice", "repo-a", "main", "bbb");
        insertBranch("alice", "repo-a", "develop", "ccc");

        List<BranchRecord> results = repo.query(null, null, null, 50, null);

        assertEquals(3, results.size());
        List<String> names = branchNames(results);
        assertTrue(names.contains("feature/foo"));
        assertTrue(names.contains("main"));
        assertTrue(names.contains("develop"));
    }

    @Test
    void query_prefixFilter_filtersCorrectly() throws Exception {
        insertRepository("alice", "repo-a", "https://example.com/a");
        insertBranch("alice", "repo-a", "feature/foo", "aaa");
        insertBranch("alice", "repo-a", "feature/bar", "bbb");
        insertBranch("alice", "repo-a", "main", "ccc");

        List<BranchRecord> results = repo.query("feature", null, null, 50, null);

        assertEquals(2, results.size());
        List<String> names = branchNames(results);
        assertTrue(names.contains("feature/foo"));
        assertTrue(names.contains("feature/bar"));
    }

    @Test
    void query_repositoryFilter_limitsScope() throws Exception {
        insertRepository("alice", "repo-a", "https://example.com/a");
        insertRepository("bob", "repo-b", "https://example.com/b");
        insertBranch("alice", "repo-a", "main", "aaa");
        insertBranch("bob", "repo-b", "main", "bbb");
        insertBranch("bob", "repo-b", "develop", "ccc");

        List<BranchRecord> results = repo.query(null, "bob", "repo-b", 50, null);

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(r -> r.owner().equals("bob")));
    }

    @Test
    void query_prefixAndRepositoryFilter_returnsMatchingBranches() throws Exception {
        insertRepository("alice", "repo-a", "https://example.com/a");
        insertRepository("alice", "repo-b", "https://example.com/b");
        insertBranch("alice", "repo-a", "feature/a1", "aaa");
        insertBranch("alice", "repo-a", "main", "bbb");
        insertBranch("alice", "repo-b", "feature/b1", "ccc");

        List<BranchRecord> results = repo.query("feature", "alice", "repo-a", 50, null);

        assertEquals(1, results.size());
        assertEquals("feature/a1", results.get(0).branchName());
    }

    @Test
    void query_validCursor_paginatesCorrectly() throws Exception {
        insertRepository("alice", "repo-a", "https://example.com/a");
        for (int i = 1; i <= 5; i++) {
            insertBranch("alice", "repo-a", "branch-0" + i, "sha" + i);
        }

        List<BranchRecord> page1 = repo.query(null, null, null, 3, null);
        assertEquals(3, page1.size());
        assertEquals("branch-01", page1.get(0).branchName());
        assertEquals("branch-03", page1.get(2).branchName());

        BranchRecord last = page1.get(page1.size() - 1);
        String[] cursor = {last.owner(), last.repository(), last.branchName()};

        List<BranchRecord> page2 = repo.query(null, null, null, 3, cursor);
        assertEquals(2, page2.size());
        assertEquals("branch-04", page2.get(0).branchName());
        assertEquals("branch-05", page2.get(1).branchName());
    }

    @Test
    void query_cursorWithPrefixFilter_paginatesCorrectly() throws Exception {
        insertRepository("alice", "repo-a", "https://example.com/a");
        insertBranch("alice", "repo-a", "feat-01", "sha1");
        insertBranch("alice", "repo-a", "feat-02", "sha2");
        insertBranch("alice", "repo-a", "feat-03", "sha3");
        insertBranch("alice", "repo-a", "main", "sha4");

        List<BranchRecord> page1 = repo.query("feat", null, null, 2, null);
        assertEquals(2, page1.size());

        BranchRecord last = page1.get(page1.size() - 1);
        String[] cursor = {last.owner(), last.repository(), last.branchName()};

        List<BranchRecord> page2 = repo.query("feat", null, null, 2, cursor);
        assertEquals(1, page2.size());
        assertEquals("feat-03", page2.get(0).branchName());
    }

    @Test
    void query_prefixWithSpecialChars_escapesLikeOperator() throws Exception {
        insertRepository("alice", "repo-a", "https://example.com/a");
        insertBranch("alice", "repo-a", "100%done", "sha1");
        insertBranch("alice", "repo-a", "feature/x", "sha2");

        // query for literal "100%done" using prefix — the % must be escaped
        List<BranchRecord> results = repo.query("100%done", null, null, 50, null);

        assertEquals(1, results.size());
        assertEquals("100%done", results.get(0).branchName());
    }

    @Test
    void query_unorderedData_resultsOrderedByOwnerRepoAndBranch() throws Exception {
        insertRepository("bob", "z-repo", "https://example.com/z");
        insertRepository("alice", "a-repo", "https://example.com/a");
        insertBranch("bob", "z-repo", "main", "sha1");
        insertBranch("alice", "a-repo", "main", "sha2");
        insertBranch("alice", "a-repo", "develop", "sha3");

        List<BranchRecord> results = repo.query(null, null, null, 50, null);

        assertEquals(3, results.size());
        assertEquals("alice", results.get(0).owner());
        assertEquals("develop", results.get(0).branchName());
        assertEquals("alice", results.get(1).owner());
        assertEquals("main", results.get(1).branchName());
        assertEquals("bob", results.get(2).owner());
    }

    private void insertRepository(String owner, String repository, String url) throws Exception {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO repositories (owner, repository, url) VALUES (?, ?, ?)")) {
            ps.setString(1, owner);
            ps.setString(2, repository);
            ps.setString(3, url);
            ps.executeUpdate();
        } finally {
            pool.release(conn);
        }
    }

    private void insertBranch(String owner, String repository, String branchName,
                               String headCommit) throws Exception {
        Connection conn = pool.acquire();
        try {
            UUID repositoryId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT repository_id FROM repositories WHERE owner = ? AND repository = ?")) {
                ps.setString(1, owner);
                ps.setString(2, repository);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    repositoryId = (UUID) rs.getObject(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO branches (repository_id, branch_name, head_commit) VALUES (?, ?, ?)")) {
                ps.setObject(1, repositoryId);
                ps.setString(2, branchName);
                ps.setString(3, headCommit);
                ps.executeUpdate();
            }
        } finally {
            pool.release(conn);
        }
    }

    private List<String> branchNames(List<BranchRecord> records) {
        return records.stream().map(BranchRecord::branchName).collect(Collectors.toList());
    }

    private ConnectionPool buildPool(PostgresTestContainer c) {
        DatabaseConfig config = GsonFactory.getInstance().fromJson("""
                {
                  "url": "%s",
                  "user": "%s",
                  "password": "%s",
                  "poolSize": 3
                }
                """.formatted(c.getJdbcUrl(), c.getUser(), c.getPassword()),
                DatabaseConfig.class);
        return new ConnectionPool(config);
    }
}
