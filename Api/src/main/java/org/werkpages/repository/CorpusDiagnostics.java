package org.werkpages.repository;

import io.vertx.core.json.JsonObject;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters proving what the location-suggestion path actually DID, for validation runs.
 *
 * <h2>Why initialisation is not evidence</h2>
 *
 * <p>"The pool opened" says only that a release was found. It does not say a single query ran
 * against it, and the whole failure mode worth guarding against here is a SILENT FALLBACK: a
 * release that resolves, then every request quietly served by the remote corpus because the country
 * gate rejected it, the executor shed it, or the index returned nothing. That looks like success
 * with slightly worse latency, which is exactly what a benchmark cannot see.
 *
 * <p>So these count executions and outcomes, not configuration. A validation run is only
 * trustworthy if {@code localQueries} and {@code servedLocally} both moved, and the fallback
 * counters explain every request that did not.
 *
 * <h2>Exposure</h2>
 *
 * <p>Collected always - they are a handful of atomic increments on a path that reads a 16-million
 * row index, so the cost is not measurable. They are PUBLISHED only by the diagnostics listener in
 * MainVerticle, which starts solely when {@code LOCAL_CORPUS_DIAGNOSTICS_PORT} is set. Production
 * does not set it, so no route to this exists in production at all, on any port.
 */
public final class CorpusDiagnostics {

    private CorpusDiagnostics() {}

    /** Searches that actually executed against the local index. */
    private static final AtomicLong localQueries  = new AtomicLong();
    /** Rows those searches returned in total, so "ran but always empty" is visible. */
    private static final AtomicLong localRows     = new AtomicLong();
    /** Requests answered FROM the local index, including a deliberate empty answer. */
    private static final AtomicLong servedLocally = new AtomicLong();
    /** Of those, how many were empty - a final answer, not a fallback. */
    private static final AtomicLong localEmpty    = new AtomicLong();

    // Every reason a request did NOT use the local index. These must account for the remainder.
    private static final AtomicLong fallbackDisabled    = new AtomicLong();
    private static final AtomicLong fallbackNotCovered  = new AtomicLong();
    private static final AtomicLong fallbackShed        = new AtomicLong();
    private static final AtomicLong fallbackFailed      = new AtomicLong();
    private static final AtomicLong remoteCalls         = new AtomicLong();
    private static final AtomicLong breakerSkips        = new AtomicLong();

    public static void localQuery(int rows) {
        localQueries.incrementAndGet();
        localRows.addAndGet(rows);
    }
    public static void servedLocally(int rows) {
        servedLocally.incrementAndGet();
        if (rows == 0) localEmpty.incrementAndGet();
    }
    public static void fallbackDisabled()   { fallbackDisabled.incrementAndGet(); }
    public static void fallbackNotCovered() { fallbackNotCovered.incrementAndGet(); }
    public static void fallbackShed()       { fallbackShed.incrementAndGet(); }
    public static void fallbackFailed()     { fallbackFailed.incrementAndGet(); }
    public static void remoteCall()         { remoteCalls.incrementAndGet(); }
    public static void breakerSkip()        { breakerSkips.incrementAndGet(); }

    /** No user text, no query strings: counts and states only. */
    public static JsonObject snapshot(LocalCorpusPool pool, String breakers) {
        JsonObject local = new JsonObject()
            .put("localQueriesExecuted", localQueries.get())
            .put("localRowsReturned",    localRows.get())
            .put("requestsServedLocally", servedLocally.get())
            .put("servedLocallyEmpty",   localEmpty.get());

        JsonObject fallback = new JsonObject()
            .put("disabled",   fallbackDisabled.get())
            .put("notCovered", fallbackNotCovered.get())
            .put("shed",       fallbackShed.get())
            .put("failed",     fallbackFailed.get())
            .put("remoteCalls", remoteCalls.get())
            .put("breakerSkips", breakerSkips.get());

        JsonObject out = new JsonObject()
            .put("local", local)
            .put("fallback", fallback)
            .put("breakers", breakers);

        if (pool == null) {
            out.put("pool", "none");
        } else {
            LocalCorpusPool.Stats s = pool.stats();
            out.put("pool", new JsonObject()
                .put("release",   s.releaseId())
                .put("size",      s.size())
                .put("available", s.available())
                .put("leased",    s.leased())
                .put("acquired",  s.acquired())
                .put("acquireTimeouts", s.acquireTimeouts())
                .put("queryErrors",     s.queryErrors())
                .put("discarded",       s.discarded())
                .put("closed",          s.closed()));
        }
        return out;
    }

    /** For a test that needs a clean baseline between phases. */
    public static void reset() {
        for (AtomicLong c : new AtomicLong[]{localQueries, localRows, servedLocally, localEmpty,
                fallbackDisabled, fallbackNotCovered, fallbackShed, fallbackFailed,
                remoteCalls, breakerSkips}) {
            c.set(0);
        }
    }
}
