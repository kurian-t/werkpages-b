package org.werkpages.repository;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Country routing: which indexes are open, and the limit on how many.
 *
 * <p>The limit is the only thing keeping two backends inside a shared 8GB host, because each open
 * country is three DuckDB connections over a memory-mapped index and DuckDB allocates outside the
 * JVM heap, where the usual ceiling does not contain it.
 */
class LocalCorpusRegistryIntegrationTest {

    static Path root;

    @BeforeAll
    static void publishThreeCountries(@TempDir Path dir) throws Exception {
        root = dir;
        // The routed layout: one country per directory, each with its own release pointer.
        for (String country : List.of("US", "CA", "FR")) {
            Files.createDirectories(dir.resolve(country));
            FixtureCorpus.publish(dir.resolve(country));
        }
    }

    private static LocalCorpusRegistry open(int maxOpen) {
        return LocalCorpusRegistry.open(root, 2, 1, "256MB", Duration.ofMillis(500), maxOpen);
    }

    @Test
    @DisplayName("the countries on disk are discovered, and nothing else is")
    void discovery() {
        try (LocalCorpusRegistry r = open(2)) {
            assertEquals(List.of("CA", "FR", "US"), r.countries());
            assertTrue(r.covers("us"));
            assertTrue(r.covers("FR"));
            assertFalse(r.covers("JP"), "not published, so not covered");
            assertFalse(r.covers(null));
            assertFalse(r.covers("../etc"), "never used to build a path");
        }
    }

    @Test
    @DisplayName("more countries than the limit evicts the least recently used")
    void evictionKeepsTheLimit() throws Exception {
        try (LocalCorpusRegistry r = open(2)) {
            for (String c : List.of("US", "CA", "FR", "US")) {
                assertNotNull(r.withCountry(c, idx -> idx.search("Starbucks", 2)),
                              "every country must answer when it is its turn");
            }
            assertEquals(2, r.stats().size(), "never more than the configured limit");
        }
    }

    @Test
    @DisplayName("a country in use is never evicted, and the limit is never exceeded")
    void aReservedCountryIsNeverEvicted() throws Exception {
        /*
          THE REGRESSION THIS GUARDS.

          The pool was fetched once to decide whether a local answer was possible and again inside
          the task that used it, and between those two moments another thread could evict and close
          it - so the work was shed for no reason. The fix that skipped busy pools then opened the
          new country ANYWAY, which made the limit stop being a limit: enough concurrent searches
          across enough countries could open as many indexes as there were countries.

          So with one slot and one country held open, a second country must be REFUSED rather than
          opened beside it, and the held country must still be working afterwards.
        */
        try (LocalCorpusRegistry r = open(1)) {
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> held = pool.submit(() -> r.withCountry("US", idx -> {
                    holding.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    return !idx.search("Starbucks", 2).isEmpty();
                }));
                assertTrue(holding.await(10, TimeUnit.SECONDS), "the US search should be running");

                assertThrows(LocalCorpusRegistry.Saturated.class,
                             () -> r.withCountry("CA", idx -> idx.search("Starbucks", 2)),
                             "the only slot is in use, so refuse rather than exceed the limit");
                assertEquals(1, r.stats().size(), "still exactly one open country");

                release.countDown();
                assertTrue(held.get(10, TimeUnit.SECONDS),
                           "the held country's own search must complete normally");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("rapid switching between three countries stays inside the limit")
    void concurrentSwitchingStaysBounded() throws Exception {
        try (LocalCorpusRegistry r = open(2)) {
            List<String> countries = List.of("US", "CA", "FR");
            ExecutorService pool = Executors.newFixedThreadPool(6);
            AtomicInteger answered = new AtomicInteger(), refused = new AtomicInteger();
            AtomicInteger worstSeen = new AtomicInteger();
            try {
                List<Future<?>> all = new java.util.ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    final int w = i;
                    all.add(pool.submit(() -> {
                        for (int k = 0; k < 12; k++) {
                            String c = countries.get((w + k) % countries.size());
                            try {
                                if (r.withCountry(c, idx -> idx.search("Starbucks", 2)) != null) {
                                    answered.incrementAndGet();
                                }
                            } catch (LocalCorpusRegistry.Saturated s) {
                                // The documented saturation answer: the caller uses the remote
                                // corpus for that keystroke rather than the limit being broken.
                                refused.incrementAndGet();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                            worstSeen.accumulateAndGet(r.stats().size(), Math::max);
                        }
                    }));
                }
                for (Future<?> f : all) f.get(60, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            assertTrue(worstSeen.get() <= 2,
                       "the limit held throughout; worst observed was " + worstSeen.get());
            assertTrue(answered.get() > 0, "work got done, not merely refused");
            // Refusals are acceptable under saturation and are not failures; recorded for the log.
            System.out.println("  answered=" + answered.get() + " refused=" + refused.get()
                + " worst open=" + worstSeen.get());
        }
    }
}
