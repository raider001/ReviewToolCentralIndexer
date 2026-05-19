package com.kalynx.centralindexer.db;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;

import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provides all persistence operations for {@link ReviewEvent} and repository state.
 *
 * <p>Each method acquires a connection from the pool, performs its work, and releases
 * the connection before returning — even when an exception is thrown.
 *
 * <p>The {@link #insert} method runs its SQL inside an explicit transaction with a
 * {@code SELECT … FOR UPDATE} row lock on {@code repository_state} to guarantee
 * monotonic, gap-free per-repository sequence numbers under concurrent write load.
 * A unique-constraint violation on {@code (repository, delivery_id)} is swallowed
 * silently and represented as {@link Optional#empty()}.
 */
public final class EventRepository {

    private static final String SQL_UPSERT_REPO_STATE =
            "INSERT INTO repository_state (repository, last_sequence_no, last_event_time) " +
            "VALUES (?, 0, NOW()) ON CONFLICT (repository) DO NOTHING";

    private static final String SQL_LOCK_REPO_STATE =
            "SELECT last_sequence_no FROM repository_state WHERE repository = ? FOR UPDATE";

    private static final String SQL_UPDATE_REPO_STATE =
            "UPDATE repository_state SET last_sequence_no = ?, last_event_time = ? WHERE repository = ?";

    private static final String SQL_INSERT_EVENT =
            "INSERT INTO events (sequence_no, repository, event_type, review_id, actor_user, " +
            "payload, timestamp, delivery_id) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)";

    private static final String SQL_NOTIFY =
            "SELECT pg_notify('events', ?)";

    private static final String SQL_QUERY_EVENTS =
            "SELECT sequence_no, repository, event_type, review_id, actor_user, payload, timestamp, delivery_id " +
            "FROM events WHERE repository = ? AND sequence_no > ? ORDER BY sequence_no ASC LIMIT ?";

    private static final String SQL_QUERY_REPO_STATES =
            "SELECT repository, last_sequence_no, last_event_time FROM repository_state";

    private static final String SQL_PRUNE =
            "DELETE FROM events WHERE timestamp < NOW() - ? * INTERVAL '1 day'";

    private static final String SQL_HAS_EVENT =
            "SELECT COUNT(*) FROM events WHERE repository = ? AND sequence_no = ?";

    private static final String DUPLICATE_SQLSTATE = "23505";

    private static final Type PAYLOAD_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final ConnectionPool pool;
    private final Gson gson;

    /**
     * Constructs an {@code EventRepository} that uses the supplied connection pool.
     *
     * @param pool the connection pool for all database operations
     */
    public EventRepository(ConnectionPool pool) {
        this.pool = pool;
        this.gson = GsonFactory.getInstance();
    }

    /**
     * Persists an event and sends a {@code pg_notify} notification within a single transaction.
     *
     * <p>The transaction acquires a {@code SELECT … FOR UPDATE} row lock on
     * {@code repository_state} to assign a monotonically increasing per-repository
     * {@code sequence_no}. If the repository has no prior state row, one is created
     * via an upsert before the lock is acquired.
     *
     * <p>If the event's {@code deliveryId} is non-null and a row with the same
     * {@code (repository, delivery_id)} pair already exists, the unique-index violation
     * is caught and the method returns {@link Optional#empty()} without throwing.
     *
     * @param event the event to persist; {@code sequenceNo} on the input value is ignored
     * @return the stored event with its assigned {@code sequenceNo}, or empty on duplicate
     * @throws SQLException         if any SQL operation fails for a reason other than deduplication
     * @throws InterruptedException if the thread is interrupted while waiting for a connection
     */
    public Optional<ReviewEvent> insert(ReviewEvent event) throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        boolean autoCommit = conn.getAutoCommit();
        try {
            conn.setAutoCommit(false);
            long seqNo = reserveSequenceNo(conn, event.repository(), event.timestamp());
            ReviewEvent stored = withSequenceNo(event, seqNo);
            insertEventRow(conn, stored);
            sendNotification(conn, stored);
            conn.commit();
            return Optional.of(stored);
        } catch (SQLException e) {
            rollbackQuietly(conn);
            if (DUPLICATE_SQLSTATE.equals(e.getSQLState())) {
                return Optional.empty();
            }
            throw e;
        } finally {
            restoreAutoCommit(conn, autoCommit);
            pool.release(conn);
        }
    }

    /**
     * Returns up to {@code limit} events for the given repository with
     * {@code sequence_no > since}, ordered ascending by {@code sequence_no}.
     *
     * @param repository the repository to query
     * @param since      the exclusive lower bound on {@code sequence_no}
     * @param limit      the maximum number of events to return
     * @return a list of matching events, possibly empty
     * @throws SQLException         if the query fails
     * @throws InterruptedException if the thread is interrupted while waiting for a connection
     */
    public List<ReviewEvent> queryEvents(String repository, long since, int limit)
            throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(SQL_QUERY_EVENTS)) {
            ps.setString(1, repository);
            ps.setLong(2, since);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<ReviewEvent> events = new ArrayList<>();
                while (rs.next()) {
                    events.add(mapRowToEvent(rs));
                }
                return events;
            }
        } finally {
            pool.release(conn);
        }
    }

    /**
     * Returns one {@link RepositoryState} for every row in the {@code repository_state} table.
     *
     * @return all repository states; empty list when the table has no rows
     * @throws SQLException         if the query fails
     * @throws InterruptedException if the thread is interrupted while waiting for a connection
     */
    public List<RepositoryState> queryRepositoryStates() throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(SQL_QUERY_REPO_STATES);
             ResultSet rs = ps.executeQuery()) {
            List<RepositoryState> states = new ArrayList<>();
            while (rs.next()) {
                Timestamp ts = rs.getTimestamp("last_event_time");
                Instant lastEventTime = ts != null ? ts.toInstant() : Instant.EPOCH;
                states.add(new RepositoryState(
                        rs.getString("repository"),
                        rs.getLong("last_sequence_no"),
                        lastEventTime));
            }
            return states;
        } finally {
            pool.release(conn);
        }
    }

    /**
     * Deletes events whose {@code timestamp} is older than {@code now() - days}.
     * The {@code repository_state} table is never modified.
     *
     * @param days the retention window in days; events older than this are removed
     * @throws SQLException         if the delete fails
     * @throws InterruptedException if the thread is interrupted while waiting for a connection
     */
    public void pruneOlderThan(int days) throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(SQL_PRUNE)) {
            ps.setInt(1, days);
            ps.executeUpdate();
        } finally {
            pool.release(conn);
        }
    }

    /**
     * Returns {@code true} if an event row with the given {@code sequenceNo} exists
     * for the given repository.
     *
     * @param repository the repository to check
     * @param sequenceNo the sequence number to look up
     * @return {@code true} if such a row exists, {@code false} otherwise
     * @throws SQLException         if the query fails
     * @throws InterruptedException if the thread is interrupted while waiting for a connection
     */
    public boolean hasEventAt(String repository, long sequenceNo)
            throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(SQL_HAS_EVENT)) {
            ps.setString(1, repository);
            ps.setLong(2, sequenceNo);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        } finally {
            pool.release(conn);
        }
    }

    private long reserveSequenceNo(Connection conn, String repository, Instant eventTime)
            throws SQLException {
        upsertRepositoryState(conn, repository);
        return incrementAndLockSequenceNo(conn, repository, eventTime);
    }

    private void upsertRepositoryState(Connection conn, String repository) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_REPO_STATE)) {
            ps.setString(1, repository);
            ps.executeUpdate();
        }
    }

    private long incrementAndLockSequenceNo(Connection conn, String repository, Instant eventTime)
            throws SQLException {
        long nextSeqNo;
        try (PreparedStatement ps = conn.prepareStatement(SQL_LOCK_REPO_STATE)) {
            ps.setString(1, repository);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                nextSeqNo = rs.getLong(1) + 1;
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_REPO_STATE)) {
            ps.setLong(1, nextSeqNo);
            ps.setTimestamp(2, Timestamp.from(eventTime));
            ps.setString(3, repository);
            ps.executeUpdate();
        }
        return nextSeqNo;
    }

    private void insertEventRow(Connection conn, ReviewEvent event) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_EVENT)) {
            ps.setLong(1, event.sequenceNo());
            ps.setString(2, event.repository());
            ps.setString(3, event.eventType().name());
            ps.setString(4, event.reviewId());
            ps.setString(5, event.actorUser());
            ps.setString(6, gson.toJson(event.payload()));
            ps.setTimestamp(7, Timestamp.from(event.timestamp()));
            ps.setString(8, event.deliveryId());
            ps.executeUpdate();
        }
    }

    private void sendNotification(Connection conn, ReviewEvent event) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_NOTIFY)) {
            ps.setString(1, gson.toJson(event));
            ps.execute();
        }
    }

    private ReviewEvent mapRowToEvent(ResultSet rs) throws SQLException {
        String payloadJson = rs.getString("payload");
        Map<String, String> payload = payloadJson != null
                ? gson.fromJson(payloadJson, PAYLOAD_TYPE)
                : Map.of();
        return new ReviewEvent(
                rs.getLong("sequence_no"),
                rs.getTimestamp("timestamp").toInstant(),
                rs.getString("repository"),
                EventType.valueOf(rs.getString("event_type")),
                rs.getString("review_id"),
                rs.getString("actor_user"),
                rs.getString("delivery_id"),
                payload);
    }

    private ReviewEvent withSequenceNo(ReviewEvent event, long seqNo) {
        return new ReviewEvent(seqNo, event.timestamp(), event.repository(),
                event.eventType(), event.reviewId(), event.actorUser(),
                event.deliveryId(), event.payload());
    }

    private void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
        }
    }

    private void restoreAutoCommit(Connection conn, boolean autoCommit) {
        try {
            conn.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
        }
    }
}

