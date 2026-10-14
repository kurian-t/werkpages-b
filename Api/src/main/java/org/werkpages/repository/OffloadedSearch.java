package org.werkpages.repository;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Runs a blocking corpus search off the event loop, and resumes on the context that asked.
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>The local search was called synchronously from a method returning a {@code Future}, so the
 * whole of it - acquiring a connection, and a DuckDB query measured at 786ms p95 under eight
 * concurrent callers - ran on a Vert.x event-loop thread. An event loop is shared by every request
 * it serves, so that did not merely make location search slow: it stalled unrelated requests on the
 * same loop for as long as the query took.
 *
 * <h2>Why a dedicated bounded pool</h2>
 *
 * <p>DuckDB is CPU-heavy and native. On a two-vCPU host shared by two backends, work queued without
 * limit converts a traffic spike into an out-of-memory kill rather than a slow response, so both
 * the threads and the QUEUE are bounded and excess work is REJECTED. A keystroke whose answer
 * arrives after the next keystroke is worth nothing, so shedding costs the caller nothing real.
 *
 * <p>This is one implementation used by production and by its tests. It was private inside the
 * service, which meant the thing most worth proving - that a saturated search does not stall the
 * loop - could not be tested at all.
 */
public final class OffloadedSearch {

    private OffloadedSearch() {}

    /** Threads sized with the connection pool; there is no point having more than it can lease. */
    private static volatile ThreadPoolExecutor executor;

    /** Short on purpose: a stale suggestion is worthless, so excess work is shed, not stored. */
    private static final int QUEUE_DEPTH = 16;

    public static synchronized void configure(int threads) {
        if (executor != null) return;
        executor = new ThreadPoolExecutor(
            Math.max(1, Math.min(threads, 8)), Math.max(1, Math.min(threads, 8)),
            30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_DEPTH),
            r -> { Thread t = new Thread(r, "corpus-search"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }

    /**
     * @param work          the blocking search
     * @param whenSaturated answer when the executor has no room. SEPARATE from the failure answer
     *                      on purpose: see below.
     * @param whenFailed    answer when the work itself throws
     *
     * <p>Never fails the returned future: the caller is a type-ahead field, and a 500 on a
     * keystroke is worse than a shorter list of suggestions.
     *
     * <h2>Why saturation and failure answer differently</h2>
     *
     * <p>Both returned the same value at first, and that quietly turned a local overload into a
     * REMOTE one. Shedding produced the same "no answer from the index" signal as an outage, so the
     * caller fell through to the S3 corpus scan - the slowest and only metered path in this
     * subsystem. A burst of typing would therefore have moved itself onto the expensive path at the
     * exact moment the cheap one was busy, which is load amplification rather than load shedding.
     * Measured in the saturation test: 101 of 120 searches shed, so 101 S3 scans.
     *
     * <p>So saturation answers with the caller's "busy" value, which for a suggestion list means
     * no suggestions for this keystroke, and the next keystroke gets through. Only a genuine
     * failure asks the fallback to try.
     */
    public static <T> Future<T> run(Supplier<T> work, T whenSaturated, T whenFailed) {
        ThreadPoolExecutor exec = executor;
        if (exec == null) { configure(3); exec = executor; }

        /*
          The context is captured BEFORE handing off, and the promise is completed back on it.

          Completing a Vert.x promise from a foreign thread runs its handlers on that thread, which
          would put the router's response write on a corpus worker. Vert.x responses are not
          thread-safe off-context: under concurrency that corrupts responses rather than failing
          cleanly, which is the hardest possible version of this bug to diagnose.
        */
        final Context caller = Vertx.currentContext();
        Promise<T> promise = Promise.promise();
        try {
            exec.execute(() -> {
                T result;
                try {
                    result = work.get();
                } catch (Throwable t) {
                    System.err.println("corpus search failed: " + t);
                    result = whenFailed;
                }
                final T settled = result;
                resumeOn(caller, () -> promise.tryComplete(settled));
            });
        } catch (RejectedExecutionException saturated) {
            System.err.println("corpus search executor saturated, shedding");
            promise.tryComplete(whenSaturated);
        }
        return promise.future();
    }

    private static void resumeOn(Context caller, Runnable action) {
        if (caller == null) action.run();
        else caller.runOnContext(v -> action.run());
    }

    /** Queue depth and thread count, for the saturation tests and for logs. */
    public static String state() {
        ThreadPoolExecutor e = executor;
        return e == null ? "unconfigured"
             : "threads=" + e.getPoolSize() + " active=" + e.getActiveCount()
               + " queued=" + e.getQueue().size();
    }
}
