package com.kalynx.centralindexer.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewsIndexRepositoryTest {

    private ConnectionPool pool;
    private Connection conn;
    private PreparedStatement psUpsert;
    private ReviewsIndexRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        pool = mock(ConnectionPool.class);
        conn = mock(Connection.class);
        when(pool.acquire()).thenReturn(conn);
        psUpsert = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(psUpsert);
        repo = new ReviewsIndexRepository(pool);
    }

    @Test
    void upsertInvokesPreparedStatement() throws Exception {
        repo.upsert("r-1", "OPEN", Instant.parse("2026-05-19T10:00:00Z"), "[]");
        verify(conn).prepareStatement(anyString());
    }
}

