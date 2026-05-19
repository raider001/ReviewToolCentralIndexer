package com.kalynx.centralindexer.db;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
/**
 * Unit tests for {@link EventRepository}.
 *
 * <p>JDBC {@link Connection} objects are mocked; SQL strings are verified via
 * {@link ArgumentCaptor} on {@link Connection#prepareStatement(String)}.
 */
class EventRepositoryTest {
    private ConnectionPool pool;
    private Connection conn;
    private EventRepository repo;
    private PreparedStatement psUpsert;
    private PreparedStatement psLock;
    private PreparedStatement psUpdate;
    private PreparedStatement psInsert;
    private PreparedStatement psNotify;
    @BeforeEach
    void setUp() throws Exception {
        pool = mock(ConnectionPool.class);
        conn = mock(Connection.class);
        when(pool.acquire()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true);
        psUpsert = mock(PreparedStatement.class);
        psLock   = mock(PreparedStatement.class);
        psUpdate = mock(PreparedStatement.class);
        psInsert = mock(PreparedStatement.class);
        psNotify = mock(PreparedStatement.class);
        when(conn.prepareStatement(contains("INSERT INTO repository_state"))).thenReturn(psUpsert);
        when(conn.prepareStatement(contains("SELECT last_sequence_no"))).thenReturn(psLock);
        when(conn.prepareStatement(contains("UPDATE repository_state"))).thenReturn(psUpdate);
        when(conn.prepareStatement(contains("INSERT INTO events"))).thenReturn(psInsert);
        when(conn.prepareStatement(contains("pg_notify"))).thenReturn(psNotify);
        repo = new EventRepository(pool);
    }
    @Test
    void insertReturnsStoredEventWithSequenceNo() throws Exception {
        stubLockResultSet(0L);
        Optional<ReviewEvent> result = repo.insert(testEvent("owner/repo", "d-1"));
        assertTrue(result.isPresent(), "Result should be present on successful insert");
        assertTrue(result.get().sequenceNo() > 0, "sequenceNo should be positive");
    }
    @Test
    void sequenceNoIsPerRepositoryMonotonic() throws Exception {
        stubLockResultSet(0L, 1L, 0L);
        Optional<ReviewEvent> first  = repo.insert(testEvent("owner/repo", "d-1"));
        Optional<ReviewEvent> second = repo.insert(testEvent("owner/repo", "d-2"));
        Optional<ReviewEvent> third  = repo.insert(testEvent("owner/other", "d-3"));
        assertEquals(1L, first.get().sequenceNo(),  "First insert for repo should be 1");
        assertEquals(2L, second.get().sequenceNo(), "Second insert for repo should be 2");
        assertEquals(1L, third.get().sequenceNo(),  "First insert for other repo should be 1");
    }
    @Test
    void upsertRepositoryStateIssuedBeforeLock() throws Exception {
        stubLockResultSet(0L);
        repo.insert(testEvent("owner/repo", "d-1"));
        InOrder order = inOrder(psUpsert, psLock);
        order.verify(psUpsert).executeUpdate();
        order.verify(psLock).executeQuery();
    }
    @Test
    void duplicateDeliveryIdReturnsEmpty() throws Exception {
        stubLockResultSet(0L);
        when(psInsert.executeUpdate()).thenThrow(new SQLException("duplicate key", "23505"));
        Optional<ReviewEvent> result = repo.insert(testEvent("owner/repo", "d-dup"));
        assertFalse(result.isPresent(), "Duplicate delivery ID should return empty");
    }
    @Test
    void nullDeliveryIdNotDeduplicated() throws Exception {
        stubLockResultSet(0L, 1L);
        Optional<ReviewEvent> first  = repo.insert(testEvent("owner/repo", null));
        Optional<ReviewEvent> second = repo.insert(testEvent("owner/repo", null));
        assertTrue(first.isPresent(),  "First insert with null deliveryId should succeed");
        assertTrue(second.isPresent(), "Second insert with null deliveryId should also succeed");
    }
    @Test
    void queryEventsPaginates() throws Exception {
        PreparedStatement psQuery = mock(PreparedStatement.class);
        when(conn.prepareStatement(contains("sequence_no > ?"))).thenReturn(psQuery);
        ResultSet rs = mock(ResultSet.class);
        when(psQuery.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getLong("sequence_no")).thenReturn(3L, 4L);
        when(rs.getString("repository")).thenReturn("owner/repo");
        when(rs.getString("event_type")).thenReturn(EventType.REVIEW_CREATED.name());
        when(rs.getString("review_id")).thenReturn(null);
        when(rs.getString("actor_user")).thenReturn(null);
        when(rs.getString("payload")).thenReturn("{}");
        when(rs.getString("delivery_id")).thenReturn(null);
        when(rs.getTimestamp("timestamp")).thenReturn(
                new Timestamp(Instant.parse("2026-05-19T10:00:00Z").toEpochMilli()));
        List<ReviewEvent> events = repo.queryEvents("owner/repo", 2L, 2);
        verify(psQuery).setLong(2, 2L);
        verify(psQuery).setInt(3, 2);
        assertEquals(2, events.size(), "Should return exactly 2 events");
        assertEquals(3L, events.get(0).sequenceNo());
        assertEquals(4L, events.get(1).sequenceNo());
    }
    @Test
    void pruneDeletesOldEventsOnly() throws Exception {
        PreparedStatement psPrune = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(psPrune);
        repo.pruneOlderThan(7);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sqlCaptor.capture());
        assertTrue(sqlCaptor.getValue().contains("DELETE FROM events"),
                "Prune SQL should target the events table");
        verify(psPrune).setInt(1, 7);
    }
    @Test
    void pruneNeverTouchesRepositoryState() throws Exception {
        PreparedStatement psPrune = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(psPrune);
        repo.pruneOlderThan(7);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sqlCaptor.capture());
        assertFalse(sqlCaptor.getValue().contains("repository_state"),
                "Prune should never reference repository_state");
    }
    @Test
    void hasEventAtReturnsTrueWhenPresent() throws Exception {
        PreparedStatement psHas = mock(PreparedStatement.class);
        when(conn.prepareStatement(contains("COUNT(*)"))).thenReturn(psHas);
        ResultSet rs = mock(ResultSet.class);
        when(psHas.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(1);
        assertTrue(repo.hasEventAt("owner/repo", 5L));
    }
    @Test
    void hasEventAtReturnsFalseWhenAbsent() throws Exception {
        PreparedStatement psHas = mock(PreparedStatement.class);
        when(conn.prepareStatement(contains("COUNT(*)"))).thenReturn(psHas);
        ResultSet rs = mock(ResultSet.class);
        when(psHas.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(0);
        assertFalse(repo.hasEventAt("owner/repo", 99L));
    }
    private void stubLockResultSet(long... values) throws Exception {
        ResultSet first = mockResultSetWithLong(values[0]);
        if (values.length == 1) {
            when(psLock.executeQuery()).thenReturn(first);
            return;
        }
        ResultSet[] rest = new ResultSet[values.length - 1];
        for (int i = 1; i < values.length; i++) {
            rest[i - 1] = mockResultSetWithLong(values[i]);
        }
        when(psLock.executeQuery()).thenReturn(first, rest);
    }
    private ResultSet mockResultSetWithLong(long value) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(value);
        return rs;
    }
    private ReviewEvent testEvent(String repository, String deliveryId) {
        return new ReviewEvent(0L, Instant.parse("2026-05-19T10:00:00Z"), repository,
                EventType.REVIEW_CREATED, null, null, deliveryId, Map.of());
    }
}