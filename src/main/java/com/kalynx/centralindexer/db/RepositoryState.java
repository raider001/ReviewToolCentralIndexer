package com.kalynx.centralindexer.db;

import java.time.Instant;

/**
 * Immutable snapshot of a row from the {@code repository_state} table.
 *
 * @param repository     canonical repository identifier in the form {@code owner/repo}
 * @param lastSequenceNo the highest {@code sequence_no} assigned for this repository
 * @param lastEventTime  UTC instant of the most recent event stored for this repository
 */
public record RepositoryState(String repository, long lastSequenceNo, Instant lastEventTime) {
}

