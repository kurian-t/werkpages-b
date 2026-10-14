package org.werkpages.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Failure injection for {@link CorpusBreaker}.
 *
 * <p>The breaker exists because a per-request deadline bounds one failure and not the hundredth: a
 * dependency that is down fails every time, so every keystroke waited the full deadline and the
 * location field paused on every letter. These cases drive it entirely through injected failures
 * and an injected clock, so the cooldown is exercised without sleeping through it.
 */
class CorpusBreakerTest {

    /** A clock the test moves by hand. */
    private static final class Clock {
        final AtomicLong now = new AtomicLong(1_000_000);
        long get() { return now.get(); }
        void advance(long ms) { now.addAndGet(ms); }
    }

    @Test
    @DisplayName("stays closed while failures are not consecutive")
    void intermittentFailuresDoNotOpenIt() {
        Clock clock = new Clock();
        CorpusBreaker b = new CorpusBreaker("test", 3, 30_000, clock::get);

        for (int i = 0; i < 20; i++) {
            b.recordFailure();
            b.recordFailure();
            b.recordSuccess();      // any success clears the run
            assertTrue(b.allow(), "a dependency that sometimes fails is not a dependency that is down");
            assertFalse(b.isOpen());
        }
    }

    @Test
    @DisplayName("opens after the threshold and then refuses without calling the dependency")
    void opensAfterConsecutiveFailures() {
        Clock clock = new Clock();
        CorpusBreaker b = new CorpusBreaker("test", 3, 30_000, clock::get);

        b.recordFailure();
        assertTrue(b.allow());
        b.recordFailure();
        assertTrue(b.allow());
        b.recordFailure();                    // third in a row
        assertTrue(b.isOpen());

        // Now every caller is refused immediately, which is the point: no deadline is paid.
        for (int i = 0; i < 50; i++) assertFalse(b.allow());
        assertEquals(50, b.rejectedCount());
    }

    @Test
    @DisplayName("after the cooldown exactly one caller probes, not the whole burst")
    void onlyOneCallerProbes() {
        Clock clock = new Clock();
        CorpusBreaker b = new CorpusBreaker("test", 2, 30_000, clock::get);
        b.recordFailure(); b.recordFailure();
        assertTrue(b.isOpen());

        assertFalse(b.allow(), "still inside the cooldown");
        clock.advance(30_001);

        /*
          One probe, then closed again until that probe reports back. Letting the whole waiting
          burst through would hit a recovering dependency with everything at once, which is how a
          breaker turns a recovery into a second outage.
        */
        int allowed = 0;
        for (int i = 0; i < 20; i++) if (b.allow()) allowed++;
        assertEquals(1, allowed, "more than one caller probed the dependency");
    }

    @Test
    @DisplayName("a successful probe closes it; a failing probe re-opens it for another cooldown")
    void probeOutcomeDecides() {
        Clock clock = new Clock();
        CorpusBreaker b = new CorpusBreaker("test", 2, 10_000, clock::get);
        b.recordFailure(); b.recordFailure();

        clock.advance(10_001);
        assertTrue(b.allow());          // the probe
        b.recordFailure();              // still broken
        assertTrue(b.isOpen(), "a failing probe must re-open it");
        assertFalse(b.allow());

        clock.advance(10_001);
        assertTrue(b.allow());          // next probe
        b.recordSuccess();              // recovered
        assertFalse(b.isOpen());
        // Fully closed: everybody gets through again.
        for (int i = 0; i < 10; i++) assertTrue(b.allow());
    }

    @Test
    @DisplayName("concurrent callers see a consistent breaker and only one probes")
    void concurrentCallersAreConsistent() throws Exception {
        Clock clock = new Clock();
        CorpusBreaker b = new CorpusBreaker("test", 2, 5_000, clock::get);
        b.recordFailure(); b.recordFailure();
        clock.advance(5_001);

        int threads = 16;
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger allowed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Thread> all = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try { go.await(10, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                if (b.allow()) allowed.incrementAndGet();
            });
            t.setDaemon(true); all.add(t); t.start();
        }
        go.countDown();
        for (Thread t : all) t.join(10_000);

        assertEquals(1, allowed.get(),
            "the probe must be handed to exactly one caller even under contention");
    }

    @Test
    @DisplayName("configuration is rejected rather than silently disabling the breaker")
    void badConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CorpusBreaker("t", 0, 1000));
        assertThrows(IllegalArgumentException.class, () -> new CorpusBreaker("t", -1, 1000));
        assertThrows(IllegalArgumentException.class, () -> new CorpusBreaker("t", 1, 0));
    }
}
