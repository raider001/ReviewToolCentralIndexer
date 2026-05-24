package com.kalynx.centralindexer.bench;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Java SSE client load harness.
 *
 * Spawns N virtual threads, each opening a persistent SSE connection to
 * {@code /events/stream?repository=<repo>}. Measures:
 * <ul>
 *   <li>First-event latency per client (time from connect to first {@code data:} line)</li>
 *   <li>Total events received across all clients over the test duration</li>
 *   <li>p50, p95, p99 first-event latencies</li>
 * </ul>
 *
 * This harness is designed to run against a live server with benchmark seed data loaded
 * and an event generator pumping events (e.g. via the webhook endpoint).
 *
 * Usage (after {@code mvn package}):
 * <pre>
 *   java -jar target/java-sse-client-harness.jar \
 *        --url http://localhost:8765 \
 *        --clients 200 \
 *        --duration 60 \
 *        --repos 100 \
 *        [--token <bearer-token>]
 * </pre>
 */
public final class SseLoadHarness {

    public static void main(String[] args) throws Exception {
        Config cfg = Config.parse(args);
        System.out.printf("Starting SSE harness: %d clients → %s (%d repos, %ds)%n",
                cfg.clients, cfg.baseUrl, cfg.repos, cfg.durationSeconds);

        List<Long> firstEventLatencies = new ArrayList<>(cfg.clients);
        AtomicLong totalEvents = new AtomicLong(0);
        CountDownLatch ready   = new CountDownLatch(cfg.clients);
        CountDownLatch done    = new CountDownLatch(cfg.clients);
        Instant deadline       = Instant.now().plusSeconds(cfg.durationSeconds);

        for (int i = 0; i < cfg.clients; i++) {
            final int clientId = i;
            final String repo  = "bench-org/repo-" + ((clientId % cfg.repos) + 1);

            Thread.ofVirtual().name("sse-client-" + clientId).start(() -> {
                long firstEvent = -1;
                long events = 0;
                try {
                    URL url = URI.create(cfg.baseUrl + "/events/stream?repository=" +
                            repo.replace("/", "%2F")).toURL();
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("Accept", "text/event-stream");
                    if (cfg.token != null) {
                        conn.setRequestProperty("Authorization", "Bearer " + cfg.token);
                    }
                    conn.setConnectTimeout(5_000);
                    conn.setReadTimeout((int) (cfg.durationSeconds * 1_000 + 5_000));
                    conn.connect();

                    Instant connectTime = Instant.now();
                    ready.countDown();

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()))) {
                        String line;
                        while (Instant.now().isBefore(deadline) &&
                               (line = reader.readLine()) != null) {
                            if (line.startsWith("data:")) {
                                events++;
                                if (firstEvent < 0) {
                                    firstEvent = Duration.between(connectTime, Instant.now()).toMillis();
                                }
                            }
                        }
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    ready.countDown();
                }
                totalEvents.addAndGet(events);
                if (firstEvent >= 0) {
                    synchronized (firstEventLatencies) {
                        firstEventLatencies.add(firstEvent);
                    }
                }
                done.countDown();
            });
        }

        ready.await();
        System.out.printf("All %d clients connected. Running for %ds…%n",
                cfg.clients, cfg.durationSeconds);
        done.await();

        System.out.printf("%nResults:%n");
        System.out.printf("  Total events received : %d%n", totalEvents.get());
        System.out.printf("  Clients with events   : %d / %d%n",
                firstEventLatencies.size(), cfg.clients);

        if (!firstEventLatencies.isEmpty()) {
            firstEventLatencies.sort(Long::compareTo);
            LongSummaryStatistics stats = firstEventLatencies.stream()
                    .mapToLong(Long::longValue).summaryStatistics();
            int size = firstEventLatencies.size();
            System.out.printf("  First-event latency:%n");
            System.out.printf("    min  : %d ms%n", stats.getMin());
            System.out.printf("    p50  : %d ms%n", firstEventLatencies.get((int)(size * 0.50)));
            System.out.printf("    p95  : %d ms%n", firstEventLatencies.get((int)(size * 0.95)));
            System.out.printf("    p99  : %d ms%n", firstEventLatencies.get(Math.min(size - 1, (int)(size * 0.99))));
            System.out.printf("    max  : %d ms%n", stats.getMax());
        }
    }

    private record Config(String baseUrl, int clients, int durationSeconds, int repos, String token) {

        static Config parse(String[] args) {
            String baseUrl = "http://localhost:8765";
            int clients  = 100;
            int duration = 60;
            int repos    = 100;
            String token = null;

            for (int i = 0; i < args.length - 1; i++) {
                switch (args[i]) {
                    case "--url"      -> baseUrl = args[++i];
                    case "--clients"  -> clients  = Integer.parseInt(args[++i]);
                    case "--duration" -> duration = Integer.parseInt(args[++i]);
                    case "--repos"    -> repos     = Integer.parseInt(args[++i]);
                    case "--token"    -> token     = args[++i];
                    default -> { /* ignore unknown flags */ }
                }
            }
            return new Config(baseUrl, clients, duration, repos, token);
        }
    }
}
