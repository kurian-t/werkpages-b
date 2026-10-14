package org.werkpages.repository;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Stops asking a failing dependency, for a while.
 *
 * <h2>Why the deadline alone is not enough</h2>
 *
 * <p>The remote corpus read is bounded at a deadline, so one slow read costs one request that much
 * and no more. But a dependency that is DOWN fails every time, and a per-request bound then applies
 * per request: every keystroke waits the full deadline before giving up, so a broken S3 path turns
 * a type-ahead into a field that pauses on every letter. The deadline bounds the damage of one
 * failure; it does nothing about the hundredth.
 *
 * <p>So after a few consecutive failures the breaker opens and callers skip the dependency
 * immediately, answering from whatever else they have. After a cooldown one request is allowed
 * through to find out whether it has recovered: if it has, the breaker closes.
 *
 * <h2>Why failures must be CONSECUTIVE</h2>
 *
 * <p>A rate over a window would trip on a slow afternoon as readily as on an outage. A run of
 * consecutive failures is the signal that the dependency itself is unavailable rather than that
 * some queries are hard, and any single success clears it.
 */
public final class CorpusBreaker {

    private final String name;
    private final int threshold;
    private final long cooldownMs;
    /** Injected so the tests do not have to sleep through a real cooldown. */
    private final LongSupplier clockMs;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openedAtMs = new AtomicLong(0);
    private final AtomicLong rejected = new AtomicLong();
    /*
      HALF-OPEN HAS TO BE ITS OWN STATE.

      The probe used to CLOSE the breaker to let one caller through, which meant it was simply
      closed: every other waiting caller then saw a closed breaker and went straight to the
      dependency too. Measured by its own test, 20 of 20 callers probed instead of 1 - so a
      recovering dependency would have been hit by the whole backlog at once, which turns a
      recovery into a second outage.

      So the open timestamp STAYS SET while a probe is in flight, and this flag is what the one
      probing caller holds. The breaker closes only when that probe reports success.
    */
    private final java.util.concurrent.atomic.AtomicBoolean probing =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    public CorpusBreaker(String name, int threshold, long cooldownMs) {
        this(name, threshold, cooldownMs, System::currentTimeMillis);
    }

    public CorpusBreaker(String name, int threshold, long cooldownMs, LongSupplier clockMs) {
        if (threshold < 1) throw new IllegalArgumentException("threshold must be at least 1");
        if (cooldownMs < 1) throw new IllegalArgumentException("cooldown must be positive");
        this.name = name;
        this.threshold = threshold;
        this.cooldownMs = cooldownMs;
        this.clockMs = clockMs;
    }

    /**
     * @return whether to attempt the dependency now.
     *
     * <p>Once the cooldown has elapsed this returns true and CLOSES the window, so exactly one
     * caller probes rather than the whole burst arriving at once. If that probe fails, the next
     * failure re-opens it for another cooldown.
     */
    public boolean allow() {
        long openedAt = openedAtMs.get();
        if (openedAt == 0) return true;                      // closed

        if (clockMs.getAsLong() - openedAt < cooldownMs) {   // still open
            rejected.incrementAndGet();
            return false;
        }
        // Cooldown elapsed: hand the probe to exactly ONE caller. The breaker stays open for
        // everybody else until that probe reports back.
        if (probing.compareAndSet(false, true)) {
            System.err.println("corpus breaker " + name + ": probing after cooldown");
            return true;
        }
        rejected.incrementAndGet();
        return false;
    }

    public void recordSuccess() {
        if (consecutiveFailures.getAndSet(0) >= threshold) {
            System.err.println("corpus breaker " + name + ": closed again after a success");
        }
        probing.set(false);
        openedAtMs.set(0);
    }

    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (probing.get()) {
            // The probe failed: still down, so wait another cooldown before the next one.
            openedAtMs.set(clockMs.getAsLong());
            probing.set(false);
            System.err.println("corpus breaker " + name + ": probe failed, open for another "
                + cooldownMs + "ms");
            return;
        }
        if (failures >= threshold && openedAtMs.get() == 0) {
            openedAtMs.set(clockMs.getAsLong());
            System.err.println("corpus breaker " + name + ": OPEN after " + failures
                + " consecutive failures, skipping for " + cooldownMs + "ms");
        }
    }

    public boolean isOpen() { return openedAtMs.get() != 0; }

    public String state() {
        return name + (isOpen() ? (probing.get() ? " HALF-OPEN" : " OPEN") : " closed")
             + " consecutiveFailures=" + consecutiveFailures.get()
             + " rejected=" + rejected.get();
    }

    public long rejectedCount() { return rejected.get(); }
}
