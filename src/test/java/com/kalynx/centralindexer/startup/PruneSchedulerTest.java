package com.kalynx.centralindexer.startup;

import com.kalynx.centralindexer.db.EventRepository;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link PruneScheduler}.
 */
class PruneSchedulerTest {

    @Test
    void prunesImmediatelyOnStart() throws Exception {
        EventRepository repo = mock(EventRepository.class);
        CountDownLatch pruneCalled = new CountDownLatch(1);
        doAnswer(inv -> {
            pruneCalled.countDown();
            return null;
        }).when(repo).pruneOlderThan(anyInt());

        PruneScheduler scheduler = new PruneScheduler(repo, 7, 6);
        scheduler.start();
        boolean calledInTime = pruneCalled.await(200, TimeUnit.MILLISECONDS);
        scheduler.shutdown();

        assertTrue(calledInTime, "pruneOlderThan() must be called within 200 ms of start()");
    }

    @Test
    void shutdownDoesNotThrow() {
        EventRepository repo = mock(EventRepository.class);
        PruneScheduler scheduler = new PruneScheduler(repo, 7, 6);
        scheduler.start();
        scheduler.shutdown();
    }
}

