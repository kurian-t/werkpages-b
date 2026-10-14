package org.werkpages.repository;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lifecycle and adversarial-concurrency cases for {@link LocalCorpusPool}.
 *
 * <p>Each one corresponds to a defect found in review rather than an invented scenario. The pool is
 * the admission control for native, off-heap work on a two-vCPU host, so a fault here is not a slow
 * suggestion: it is a leaked DuckDB connection, an aborted query, or a permanently dead pool slot.
 */
class LocalCorpusPoolIntegrationTest {

    static Path root;

    @BeforeAll
    static void publish(@TempDir Path dir) throws Exception {
        FixtureCorpus.publish(dir);
        root = dir;
    }

    private static LocalCorpusPool pool(int size) {
        LocalCorpusPool p = LocalCorpusPool.openCurrent(root, size, 1, "256MB", Duration.ofMillis(120));
        assertNotNull(p, "fixture release should open");
        return p;
    }

    @Test
    @DisplayName("close() does not abort a search that is already running")
    void closeDrainsRatherThanAborting() throws Exception {
        /*
          THE DEFECT THIS GUARDS.

          close() iterated every connection it had ever opened and closed it, including connections
          currently executing a query, while its own javadoc claimed "callers already running finish
          first, because each holds its lease". They did not. Closing a DuckDB connection under a
          running query aborts it, so switching release or shutting down failed whatever searches
          were in flight - and under load that is most of them.
        */
        LocalCorpusPool p = pool(2);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch mayFinish = new CountDownLatch(1);
        AtomicReference<Object> outcome = new AtomicReference<>();

        Thread searcher = new Thread(() -> {
            try {
                Object result = p.withIndex(idx -> {
                    inside.countDown();
                    // Hold the lease while close() runs, then do real work on the connection.
                    assertTrue(mayFinish.await(20, TimeUnit.SECONDS));
                    return idx.search("laguna grove", 5);
                });
                outcome.set(result);
            } catch (Throwable t) {
                outcome.set(t);
            }
        });
        searcher.setDaemon(true);
        searcher.start();

        assertTrue(inside.await(20, TimeUnit.SECONDS), "search never started");
        p.close();                 // closes while the lease is out
        mayFinish.countDown();     // the search now uses its connection
        searcher.join(30_000);

        Object result = outcome.get();
        if (result instanceof Throwable t) {
            fail("close() aborted a search that was already running: " + t);
        }
        assertInstanceOf(List.class, result);
        assertFalse(((List<?>) result).isEmpty(), "the in-flight search should still have answered");
    }

    @Test
    @DisplayName("a lease returned after close() is closed, not put back in the pool")
    void leaseReturnedAfterCloseIsClosed() throws Exception {
        LocalCorpusPool p = pool(1);
        AtomicReference<Connection> borrowed = new AtomicReference<>();
        p.withIndex(idx -> { borrowed.set(connectionOf(idx)); return null; });

        p.close();
        // Borrow again post-close is refused outright.
        assertThrows(LocalCorpusPool.Overloaded.class, () -> p.withIndex(idx -> null));
        assertTrue(p.stats().closed());
        assertEquals(0, p.stats().available(), "no connection should remain pooled after close");
        assertTrue(borrowed.get().isClosed(), "the pooled connection should have been closed");
    }

    @Test
    @DisplayName("close() is safe to call twice and from several threads at once")
    void closeIsIdempotentAndThreadSafe() throws Exception {
        LocalCorpusPool p = pool(3);
        int threads = 6;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try { go.await(10, TimeUnit.SECONDS); p.close(); p.close(); }
                catch (Throwable e) { failure.compareAndSet(null, e); }
                finally { done.countDown(); }
            });
            t.setDaemon(true); t.start();
        }
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertNull(failure.get(), "concurrent close() threw: " + failure.get());
        assertEquals(0, p.stats().available());
    }

    @Test
    @DisplayName("saturation is refused quickly rather than queued without limit")
    void saturationIsRefused() throws Exception {
        LocalCorpusPool p = pool(1);
        try {
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                try { p.withIndex(idx -> { holding.countDown(); release.await(20, TimeUnit.SECONDS); return null; }); }
                catch (Exception ignored) { }
            });
            holder.setDaemon(true); holder.start();
            assertTrue(holding.await(20, TimeUnit.SECONDS));

            long t0 = System.nanoTime();
            LocalCorpusPool.Overloaded refused = assertThrows(LocalCorpusPool.Overloaded.class,
                () -> p.withIndex(idx -> idx.search("laguna", 5)));
            long waitedMs = (System.nanoTime() - t0) / 1_000_000;

            assertTrue(waitedMs < 1000, "waited " + waitedMs + "ms before refusing, which is queueing");
            assertTrue(refused.getMessage().contains("no connection"), refused.getMessage());
            assertEquals(1, p.stats().acquireTimeouts());

            release.countDown();
            holder.join(20_000);
            // The lease comes back, so the pool recovers rather than staying saturated.
            assertEquals(1, p.stats().available());
        } finally { p.close(); }
    }

    @Test
    @DisplayName("a failing search returns its connection, so one bad query cannot shrink the pool")
    void failingSearchReturnsItsLease() throws Exception {
        LocalCorpusPool p = pool(2);
        try {
            assertThrows(IllegalStateException.class, () -> p.withIndex(idx -> {
                throw new IllegalStateException("deliberate");
            }));
            assertEquals(2, p.stats().size(), "the pool shrank after a failed query");
            assertEquals(2, p.stats().available());
            assertEquals(1, p.stats().queryErrors());
            // Still usable.
            assertFalse(p.withIndex(idx -> idx.search("laguna grove", 5)).isEmpty());
        } finally { p.close(); }
    }

    @Test
    @DisplayName("a connection broken mid-search is discarded, not handed to the next caller")
    void brokenConnectionIsDiscarded() throws Exception {
        /*
          A broken connection returned to the pool is permanent: every later caller that borrows it
          fails the same way, so one transient fault becomes a dead slot nothing ever repairs. The
          pool must run SMALLER instead, degrading throughput rather than correctness.

          The connection is closed through the index's own field, which is the only way to produce a
          genuinely unusable connection without waiting for a real DuckDB fault.
        */
        LocalCorpusPool p = pool(2);
        try {
            assertThrows(Exception.class, () -> p.withIndex(idx -> {
                connectionOf(idx).close();
                return idx.search("laguna grove", 5);   // now fails on a closed connection
            }));
            assertEquals(1, p.stats().discarded(), "the broken connection should have been discarded");
            assertEquals(1, p.stats().size(), "the pool should now be one smaller");

            // The remaining connection still answers, which is the point of discarding.
            assertFalse(p.withIndex(idx -> idx.search("laguna grove", 5)).isEmpty());
        } finally { p.close(); }
    }

    @Test
    @DisplayName("concurrent callers never share a connection and never lose one")
    void concurrentCallersAreIsolated() throws Exception {
        LocalCorpusPool p = pool(3);
        try {
            int callers = 24;
            ExecutorService ex = Executors.newFixedThreadPool(8);
            List<Future<Boolean>> results = new ArrayList<>();
            AtomicInteger overloaded = new AtomicInteger();
            AtomicBoolean shared = new AtomicBoolean(false);
            ConcurrentHashMap<Connection, Boolean> inUse = new ConcurrentHashMap<>();

            for (int i = 0; i < callers; i++) {
                results.add(ex.submit(() -> {
                    try {
                        return p.withIndex(idx -> {
                            Connection c = connectionOf(idx);
                            // Two callers holding the same connection at once would corrupt results.
                            if (inUse.putIfAbsent(c, Boolean.TRUE) != null) shared.set(true);
                            try { return !idx.search("laguna grove", 5).isEmpty(); }
                            finally { inUse.remove(c); }
                        });
                    } catch (LocalCorpusPool.Overloaded o) {
                        overloaded.incrementAndGet();
                        return true;    // shedding is a correct outcome, not a failure
                    }
                }));
            }
            for (Future<Boolean> f : results) assertTrue(f.get(60, TimeUnit.SECONDS));
            ex.shutdownNow();

            assertFalse(shared.get(), "two callers held the same connection at the same time");
            assertEquals(3, p.stats().size(), "the pool lost or gained connections under load");
            assertEquals(3, p.stats().available(), "not every lease came back");
            assertEquals(0, p.stats().leased());
            System.err.println("pool under load: " + p.stats() + " overloaded=" + overloaded.get());
        } finally { p.close(); }
    }

    @Test
    @DisplayName("bad configuration is refused with a message about the setting, not the release")
    void configurationIsValidated() {
        // These arrive from environment variables, so a typo is a deployment mistake. poolSize=0
        // used to throw IllegalArgumentException out of ArrayBlockingQueue and disable local search
        // with a message that blamed the release.
        assertNull(LocalCorpusPool.openCurrent(root, 0, 1, "256MB", Duration.ofMillis(120)));
        assertNull(LocalCorpusPool.openCurrent(root, -1, 1, "256MB", Duration.ofMillis(120)));
        assertNull(LocalCorpusPool.openCurrent(root, 999, 1, "256MB", Duration.ofMillis(120)));
        assertNull(LocalCorpusPool.openCurrent(root, 2, 0, "256MB", Duration.ofMillis(120)));
        assertNull(LocalCorpusPool.openCurrent(root, 2, 1, "plenty", Duration.ofMillis(120)));
        assertNull(LocalCorpusPool.openCurrent(root, 2, 1, null, Duration.ofMillis(120)));
        assertNull(LocalCorpusPool.openCurrent(root, 2, 1, "256MB", Duration.ofMinutes(5)));
        assertNull(LocalCorpusPool.openCurrent(root, 2, 1, "256MB", null));

        // And the valid one still opens, so the validation is not simply refusing everything.
        LocalCorpusPool ok = LocalCorpusPool.openCurrent(root, 2, 1, "256MB", Duration.ofMillis(120));
        assertNotNull(ok);
        ok.close();
    }

    @Test
    @DisplayName("an interrupted acquisition restores the interrupt flag")
    void interruptedAcquisitionRestoresTheFlag() throws Exception {
        // The search runs on a pooled thread something else owns; swallowing the interrupt leaves a
        // worker that cannot be shut down.
        LocalCorpusPool p = pool(1);
        try {
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                try { p.withIndex(idx -> { holding.countDown(); release.await(20, TimeUnit.SECONDS); return null; }); }
                catch (Exception ignored) { }
            });
            holder.setDaemon(true); holder.start();
            assertTrue(holding.await(20, TimeUnit.SECONDS));

            AtomicBoolean flagStillSet = new AtomicBoolean(false);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread victim = new Thread(() -> {
                Thread.currentThread().interrupt();     // interrupted before it even asks
                try { p.withIndex(idx -> null); }
                catch (Throwable t) { thrown.set(t); }
                flagStillSet.set(Thread.currentThread().isInterrupted());
            });
            victim.setDaemon(true); victim.start();
            victim.join(20_000);

            assertInstanceOf(LocalCorpusPool.Overloaded.class, thrown.get());
            assertTrue(flagStillSet.get(), "the interrupt flag was swallowed");

            release.countDown(); holder.join(20_000);
        } finally { p.close(); }
    }

    /** The pool hands out an index, not a connection; the tests need the connection underneath. */
    private static Connection connectionOf(LocalPlaceIndex index) throws Exception {
        Field f = LocalPlaceIndex.class.getDeclaredField("conn");
        f.setAccessible(true);
        return (Connection) f.get(index);
    }
}
