package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An outage must not be remembered as an answer.
 *
 * <h2>The bug</h2>
 *
 * <p>A failed corpus query completed with an empty array rather than failing, and that empty array
 * then went into the one-hour result cache as a perfectly good result. So a single transient error
 * made one query return "no such place" for the next sixty minutes, and recovery needed either a
 * restart or an hour of waiting.
 *
 * <p>It also hid a programming error through a whole round of measurement: a stray per-cent sign in
 * a SQL comment threw on every query, and the benchmark read ten crashes as legitimate empty
 * results.
 *
 * <p>No corpus, no network and no S3 here: the point is the caching rule, so the work is a plain
 * supplier that fails once and then succeeds.
 */
class LocationCorpusCacheTest {

    @Test
    @DisplayName("a failure is not cached, and the next identical call can succeed")
    void aFailureIsNotCachedAndRecoveryNeedsNoRestart() {
        LocationCorpusRepository repo = new LocationCorpusRepository("bucket", "region", "512MB");
        AtomicInteger calls = new AtomicInteger();

        // First attempt: the bucket is unreachable.
        Future<JsonArray> first = repo.cached("k", () -> {
            calls.incrementAndGet();
            return Future.failedFuture(new IllegalStateException("corpus unreachable"));
        });
        assertTrue(first.failed(), "a failure must propagate, not become an empty success");

        // Same key, moments later, with the corpus back. This is the assertion that matters: the
        // work must actually run again rather than being answered from a cached empty.
        JsonArray good = new JsonArray().add(new JsonObject().put("name", "Elk Grove Subaru"));
        Future<JsonArray> second = repo.cached("k", () -> {
            calls.incrementAndGet();
            return Future.succeededFuture(good);
        });

        assertTrue(second.succeeded());
        assertEquals(1, second.result().size(), "recovery must not wait for the cache to expire");
        assertEquals(2, calls.get(), "the second call has to do the work, not read a cached outage");
    }

    @Test
    @DisplayName("a genuine empty result IS cached, because it is an answer")
    void aRealEmptyResultIsStillCached() {
        /*
          The distinction the bug destroyed. "Nothing matched" is a finding and worth remembering;
          "I could not look" is not. Caching the first is what keeps a repeated keystroke cheap.
        */
        LocationCorpusRepository repo = new LocationCorpusRepository("bucket", "region", "512MB");
        AtomicInteger calls = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            Future<JsonArray> result = repo.cached("empty-key", () -> {
                calls.incrementAndGet();
                return Future.succeededFuture(new JsonArray());
            });
            assertTrue(result.succeeded());
            assertEquals(0, result.result().size());
        }
        assertEquals(1, calls.get(), "a real no-match is computed once and remembered");
    }
}
