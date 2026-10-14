package org.werkpages.repository;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves that a slow corpus search does not stall unrelated work on the same event loop.
 *
 * <h2>The production defect</h2>
 *
 * <p>The local location search ran synchronously inside a method that returned a {@code Future},
 * so it executed on a Vert.x event-loop thread: connection acquisition plus a DuckDB query measured
 * at 786ms p95 under eight concurrent callers. An event loop serves every request assigned to it,
 * so this did not only make the suggestion slow. It froze sign-in, profile loads and review
 * submission for anybody sharing that loop with somebody typing an address.
 *
 * <h2>How this test measures it</h2>
 *
 * <p>By TIMER LAG on one event loop, which is what "the loop is stalled" actually means: a periodic
 * timer that should fire every 10ms cannot fire at all while something else occupies its thread, so
 * the largest gap between ticks is exactly how long unrelated work was made to wait.
 *
 * <p>Measured through an HTTP round trip first, which proved flaky for reasons unrelated to the
 * defect. Timer lag measures the same property directly and has no client, socket or keep-alive
 * behaviour in the way.
 *
 * <p>The assertions are comparative, so the test does not depend on machine speed, and the
 * blocking half is asserted too: if it ever stops stalling the loop, this test has stopped
 * reproducing the defect it guards and must not be trusted as proof of the fix.
 */
class EventLoopIntegrationTest {

    static final int WORK_MS = 250;
    static final int TICK_MS = 10;

    static Vertx vertx;
    static Context loop;

    /** Largest observed gap between timer ticks, in milliseconds. */
    static final AtomicLong maxGap = new AtomicLong();
    static final AtomicLong lastTick = new AtomicLong();

    @BeforeAll
    static void setUp() throws Exception {
        OffloadedSearch.configure(3);
        vertx = Vertx.vertx();

        // ONE event-loop context, and the timer lives on it, so anything that occupies that thread
        // shows up as a gap between ticks.
        CountDownLatch ready = new CountDownLatch(1);
        loop = vertx.getOrCreateContext();
        loop.runOnContext(v -> {
            lastTick.set(System.nanoTime());
            vertx.setPeriodic(TICK_MS, id -> {
                long now = System.nanoTime();
                long gapMs = (now - lastTick.getAndSet(now)) / 1_000_000;
                maxGap.accumulateAndGet(gapMs, Math::max);
            });
            ready.countDown();
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS), "timer never started");
        settle();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    /** Lets the timer tick cleanly, then forgets whatever lag startup caused. */
    private static void settle() throws Exception {
        Thread.sleep(150);
        lastTick.set(System.nanoTime());
        maxGap.set(0);
        Thread.sleep(100);
    }

    private static void slowWork() {
        try { Thread.sleep(WORK_MS); } catch (InterruptedException ignored) { }
    }

    @Test
    @DisplayName("work done ON the event loop stalls it; the same work offloaded does not")
    void offloadingKeepsTheLoopResponsive() throws Exception {
        // --- the defect: the search ran here, on the loop ---
        settle();
        CountDownLatch blocked = new CountDownLatch(1);
        loop.runOnContext(v -> { slowWork(); blocked.countDown(); });
        assertTrue(blocked.await(30, TimeUnit.SECONDS));
        Thread.sleep(50);
        long gapBlocking = maxGap.get();

        // --- the fix: the same work, offloaded, resuming on the loop ---
        settle();
        CountDownLatch offloaded = new CountDownLatch(1);
        loop.runOnContext(v ->
            OffloadedSearch.run(() -> { slowWork(); return "done"; }, "shed", "failed")
                .onComplete(ar -> offloaded.countDown()));
        assertTrue(offloaded.await(30, TimeUnit.SECONDS));
        Thread.sleep(50);
        long gapOffloaded = maxGap.get();

        System.err.println("event-loop max gap: blocking=" + gapBlocking
            + "ms offloaded=" + gapOffloaded + "ms (" + OffloadedSearch.state() + ")");

        assertTrue(gapBlocking >= WORK_MS * 0.8,
            "the blocking case did not stall the loop (gap " + gapBlocking + "ms), so this test is "
            + "no longer reproducing the defect it guards");

        assertTrue(gapOffloaded < WORK_MS * 0.4,
            "offloaded search still stalled the event loop for " + gapOffloaded + "ms");

        assertTrue(gapOffloaded * 3 < gapBlocking,
            "offloading did not meaningfully improve responsiveness: blocking=" + gapBlocking
            + "ms offloaded=" + gapOffloaded + "ms");
    }

    @Test
    @DisplayName("many concurrent searches leave the loop responsive")
    void saturatedSearchesDoNotStallTheLoop() throws Exception {
        settle();
        int n = 24;
        CountDownLatch done = new CountDownLatch(n);
        loop.runOnContext(v -> {
            for (int i = 0; i < n; i++) {
                OffloadedSearch.run(() -> { slowWork(); return "done"; }, "shed", "failed")
                    .onComplete(ar -> done.countDown());
            }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "searches never completed");
        long gap = maxGap.get();
        System.err.println("event-loop max gap under " + n + " concurrent searches: " + gap + "ms");
        assertTrue(gap < WORK_MS * 0.4,
            "the loop was stalled for " + gap + "ms while " + n + " searches ran");
    }

    @Test
    @DisplayName("saturating the bounded executor sheds rather than queueing without limit")
    void saturationSheds() throws Exception {
        // Far more work than threads(3) plus the bounded queue can hold, so some must be shed.
        int n = 120;
        AtomicLong shed = new AtomicLong();
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            OffloadedSearch.run(() -> { slowWork(); return "ran"; }, "shed", "failed")
                .onComplete(ar -> {
                    if (ar.succeeded() && "shed".equals(ar.result())) shed.incrementAndGet();
                    done.countDown();
                });
        }
        assertTrue(done.await(120, TimeUnit.SECONDS), "shed work never completed");
        assertTrue(shed.get() > 0,
            "nothing was shed, so the queue grew without bound: " + OffloadedSearch.state());
        // Every caller still got an answer: a type-ahead must never see an error for being busy.
        assertEquals(0, done.getCount());
        System.err.println("shed " + shed.get() + " of " + n + " searches");
    }
}
