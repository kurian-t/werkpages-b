package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import org.werkpages.repository.CorpusBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which breaker each location tier is wired to.
 *
 * <h2>The defect these guard</h2>
 *
 * <p>{@link CorpusBreakerTest} proves the breaker itself counts correctly. It passed throughout a
 * production-shaped failure in which the geography breaker never opened at all, because the bug was
 * not in the breaker: the addresses tier was added later and handed the SAME instance that
 * geography uses.
 *
 * <p>Addresses are published for very few countries, so that read returns an empty array quickly
 * and counts as a success, and any success resets {@code consecutiveFailures} to zero. Every
 * request therefore recorded one geography failure and one addresses success. The counter
 * oscillated between 1 and 0 and the threshold of 5 was unreachable. Measured on a cold backend:
 * nine consecutive geography timeouts, nine {@code corpus geography exceeded 4000ms} lines, and
 * ZERO breaker lines, while {@code corpusBreakerState()} reported geography closed and healthy.
 * Every one of those nine requests paid the full four-second deadline for a dependency already
 * known to be unavailable, which is the exact cost the breaker exists to stop.
 *
 * <p>So these cases assert the wiring rather than the arithmetic: that the tiers keep separate
 * accounts, and that a succeeding address read cannot clear a run of geography failures. They
 * drive the real production constants through the real {@code guarded()}, with injected failures
 * and no network.
 */
class LocationBreakerWiringTest {

    /** A read that fails the way a corpus timeout fails: the deadline fails it, never empty. */
    private static Supplier<Future<JsonArray>> failing() {
        return () -> Future.failedFuture(new TimeoutException("corpus exceeded in test"));
    }

    /**
     * A read that succeeds with NOTHING, which is the address tier's normal answer.
     *
     * <p>This is the whole mechanism: an unpublished country is coverage, not failure, so it is
     * reported as a success and a success resets the counter it shares.
     */
    private static Supplier<Future<JsonArray>> answeringEmpty() {
        return () -> Future.succeededFuture(new JsonArray());
    }

    private static Supplier<Future<JsonArray>> answeringOneRow() {
        return () -> Future.succeededFuture(new JsonArray().add(new JsonObject().put("kind", "geo")));
    }

    /** Drives geography exactly as {@code suggestCompanyLocations} does. */
    private static JsonArray geography(Supplier<Future<JsonArray>> read) {
        Future<JsonArray> f = ManagerService.guarded(
            ManagerService.GEO_BREAKER, read, ManagerService.GEOGRAPHY_DEADLINE_MS);
        assertTrue(f.isComplete(), "an injected read completes synchronously; the test would lie otherwise");
        return f.result();
    }

    /** Drives addresses exactly as {@code suggestCompanyLocations} does, same deadline and all. */
    private static JsonArray addresses(Supplier<Future<JsonArray>> read) {
        Future<JsonArray> f = ManagerService.guarded(
            ManagerService.ADDRESS_BREAKER, read, ManagerService.GEOGRAPHY_DEADLINE_MS);
        assertTrue(f.isComplete(), "an injected read completes synchronously; the test would lie otherwise");
        return f.result();
    }

    /*
      The production breakers are static, so every case starts and leaves them closed. recordSuccess
      is the reset: it clears the failure run, drops any probe and closes the window.
    */
    @BeforeEach
    @AfterEach
    void closeEveryBreaker() {
        ManagerService.GEO_BREAKER.recordSuccess();
        ManagerService.ADDRESS_BREAKER.recordSuccess();
        ManagerService.PLACES_BREAKER.recordSuccess();
    }

    // ── the wiring ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("each tier holds its own breaker, and names itself")
    void eachTierHoldsItsOwnBreaker() {
        /*
          Three tiers, three instances. Sharing one was not a tuning choice, it disabled the
          sharing tier's breaker outright, and nothing in the state endpoint said so.
        */
        assertNotSame(ManagerService.GEO_BREAKER, ManagerService.ADDRESS_BREAKER,
            "addresses sharing geography's breaker is the defect this file exists for");
        assertNotSame(ManagerService.GEO_BREAKER, ManagerService.PLACES_BREAKER);
        assertNotSame(ManagerService.ADDRESS_BREAKER, ManagerService.PLACES_BREAKER);

        /*
          guarded() takes no separate label any more: it logs breaker.name(). It used to take both,
          and the addresses read was logged as "addresses" while its outcome was counted against
          geography. These names are what the log and the deadline message now say, so a tier
          cannot be reported as one thing and accounted as another.
        */
        assertEquals("geography", ManagerService.GEO_BREAKER.name());
        assertEquals("addresses", ManagerService.ADDRESS_BREAKER.name());
        assertEquals("places", ManagerService.PLACES_BREAKER.name());
    }

    @Test
    @DisplayName("geography reaches its threshold even though every address read in between succeeds")
    void geographyFailuresSurviveSucceedingAddressReads() {
        /*
          THE REGRESSION. This is the measured cold-start sequence: one failing geography read and
          one succeeding (empty) address read per request. Before the fix the counter went
          1, 0, 1, 0, ... and the breaker never opened however long the corpus stayed down.
        */
        for (int request = 1; request <= 5; request++) {
            JsonArray geo = geography(failing());
            assertEquals(0, geo.size(), "a failed corpus read degrades to empty, it does not fail the request");

            JsonArray addr = addresses(answeringEmpty());
            assertEquals(0, addr.size(), "an unpublished country answers nothing, and that is a success");

            if (request < 5) {
                assertFalse(ManagerService.GEO_BREAKER.isOpen(),
                    "opened after only " + request + " failures; the threshold is 5");
            }
        }

        assertTrue(ManagerService.GEO_BREAKER.isOpen(),
            "five consecutive geography failures must open the geography breaker, "
            + "whatever the address tier did in between");
        assertFalse(ManagerService.ADDRESS_BREAKER.isOpen(),
            "the address tier was answering throughout and must stay available");

        // And the point of opening it: the next geography read costs nothing at all.
        AtomicInteger reads = new AtomicInteger();
        JsonArray skipped = geography(() -> {
            reads.incrementAndGet();
            return Future.failedFuture(new TimeoutException("should never be asked"));
        });
        assertEquals(0, reads.get(), "an open breaker must not pay the deadline again");
        assertEquals(0, skipped.size());
    }

    @Test
    @DisplayName("a geography success clears the geography run, and only the geography run")
    void aGeographySuccessClearsItsOwnRun() {
        for (int i = 0; i < 4; i++) geography(failing());
        assertFalse(ManagerService.GEO_BREAKER.isOpen());

        JsonArray recovered = geography(answeringOneRow());
        assertEquals(1, recovered.size(), "a successful read must still return its rows");

        // The run is cleared, so four more failures are four, not eight.
        for (int i = 0; i < 4; i++) geography(failing());
        assertFalse(ManagerService.GEO_BREAKER.isOpen(),
            "a success between the runs means neither run reached 5");

        geography(failing());
        assertTrue(ManagerService.GEO_BREAKER.isOpen(), "the fifth consecutive failure opens it");
    }

    @Test
    @DisplayName("address failures are never charged to geography, and an open address tier does not skip geography")
    void addressOutcomesStayInTheAddressAccount() {
        // Far past the threshold, on the tier that is NOT geography.
        for (int i = 0; i < 20; i++) addresses(failing());
        assertTrue(ManagerService.ADDRESS_BREAKER.isOpen(), "20 consecutive address failures must open addresses");
        assertFalse(ManagerService.GEO_BREAKER.isOpen(),
            "geography was never asked; its breaker must not have moved");

        /*
          Proven by behaviour rather than by reading a counter: if those twenty had leaked into the
          geography account, geography would already be open and these four reads would be skipped.
        */
        AtomicInteger geoReads = new AtomicInteger();
        for (int i = 0; i < 4; i++) {
            geography(() -> {
                geoReads.incrementAndGet();
                return Future.failedFuture(new TimeoutException("corpus exceeded in test"));
            });
        }
        assertEquals(4, geoReads.get(),
            "geography must still be consulted while only the address tier is broken");
        assertFalse(ManagerService.GEO_BREAKER.isOpen(), "four geography failures is below the threshold of 5");

        geography(failing());
        assertTrue(ManagerService.GEO_BREAKER.isOpen(),
            "geography's own fifth failure opens geography, independently of addresses");
    }

    // ── recovery and the fallback, still intact ───────────────────────────────

    @Test
    @DisplayName("an open breaker answers empty without calling the read, then one probe closes it")
    void anOpenBreakerSkipsTheReadAndRecovers() {
        /*
          A local breaker with an injected clock, so the 30-second cooldown is exercised without
          sleeping through it. The fallback contract is the one the whole location field rests on:
          a corpus read is allowed to be down, and is never allowed to fail the request.
        */
        AtomicLong now = new AtomicLong(5_000_000);
        CorpusBreaker breaker = new CorpusBreaker("probe", 2, 10_000, now::get);
        AtomicInteger reads = new AtomicInteger();
        Supplier<Future<JsonArray>> counted = () -> {
            reads.incrementAndGet();
            return Future.failedFuture(new TimeoutException("still down"));
        };

        for (int i = 0; i < 2; i++) {
            JsonArray out = ManagerService.guarded(breaker, counted, 4000).result();
            assertEquals(0, out.size());
        }
        assertEquals(2, reads.get());
        assertTrue(breaker.isOpen());

        // Open: no read, no deadline, an empty answer.
        for (int i = 0; i < 10; i++) {
            assertEquals(0, ManagerService.guarded(breaker, counted, 4000).result().size());
        }
        assertEquals(2, reads.get(), "an open breaker must not call the dependency at all");

        // Cooldown elapsed: exactly one probe, and a success closes it.
        now.addAndGet(10_001);
        JsonArray probed = ManagerService.guarded(breaker, answeringOneRow(), 4000).result();
        assertEquals(1, probed.size(), "the probe's rows must reach the caller");
        assertFalse(breaker.isOpen(), "a successful probe closes the breaker");
        assertEquals(1, ManagerService.guarded(breaker, answeringOneRow(), 4000).result().size());
    }

    // ── concurrency ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("concurrent geography failures and address successes do not corrupt either account")
    void concurrentTiersDoNotCorruptEachOther() throws Exception {
        /*
          The cold start was measured with nine concurrent requests, so this is the shape that
          produced the defect. Sixteen geography reads fail while sixteen address reads succeed,
          all at once. Geography has no success among its own reads, so its run is monotonic and
          must cross 5 whatever the interleaving; the address successes must not touch it.
        */
        int threads = 16;
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> all = new ArrayList<>();
        AtomicInteger geoFailures = new AtomicInteger();
        AtomicInteger addrOk = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            Thread g = new Thread(() -> {
                try { go.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                if (geography(failing()).isEmpty()) geoFailures.incrementAndGet();
            });
            Thread a = new Thread(() -> {
                try { go.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                if (addresses(answeringEmpty()).isEmpty()) addrOk.incrementAndGet();
            });
            g.setDaemon(true); a.setDaemon(true);
            all.add(g); all.add(a);
            g.start(); a.start();
        }
        go.countDown();
        for (Thread t : all) t.join(10_000);

        assertEquals(threads, geoFailures.get(), "every geography read degraded to empty");
        assertEquals(threads, addrOk.get(), "every address read answered");
        assertTrue(ManagerService.GEO_BREAKER.isOpen(),
            "sixteen concurrent geography failures must open geography");
        assertFalse(ManagerService.ADDRESS_BREAKER.isOpen(),
            "sixteen concurrent address successes must leave addresses closed");
    }

    // ── the call sites, driven through the real tier dispatch ─────────────────

    /**
     * A corpus whose three tiers answer on command, with no network and no published release.
     *
     * <p>Nothing else in the suite could reach {@code appendCorpusSuggestions}: the corpus was
     * built inside ManagerService, so which tier is read and which breaker counts it were decided
     * in code no test could run. That is how the shared-breaker defect shipped green.
     */
    private static final class StubCorpus extends org.werkpages.repository.LocationCorpusRepository {
        final AtomicInteger geographyReads = new AtomicInteger();
        final AtomicInteger addressReads   = new AtomicInteger();
        final AtomicInteger placeReads     = new AtomicInteger();
        volatile boolean geographyFails = true;
        volatile boolean placesFail     = false;

        @Override
        public Future<JsonArray> suggestGeography(String query, String country, String state) {
            geographyReads.incrementAndGet();
            return geographyFails
                ? Future.failedFuture(new TimeoutException("corpus geography exceeded 4000ms"))
                : Future.succeededFuture(new JsonArray());
        }

        /*
          Succeeds with nothing, which is the address tier's ordinary answer and the whole
          mechanism of the defect: addresses are published for very few countries, so an
          unpublished one is coverage rather than failure and is reported as a success.
        */
        @Override
        public Future<JsonArray> suggestAddresses(String query, String visitor, int limit) {
            addressReads.incrementAndGet();
            return Future.succeededFuture(new JsonArray());
        }

        @Override
        public Future<JsonArray> suggestPlaces(String companyName, String query, String country,
                                               String city) {
            placeReads.incrementAndGet();
            return placesFail
                ? Future.failedFuture(new TimeoutException("corpus places exceeded 6000ms"))
                : Future.succeededFuture(new JsonArray());
        }
    }

    private static ManagerService serviceOver(StubCorpus corpus) {
        // Nothing on this path touches Postgres: appendCorpusSuggestions receives the confirmed
        // rows as an argument and reads only the corpus. The client is a mock so the repositories
        // the constructor builds have something to hold.
        SqlClient db = org.mockito.Mockito.mock(Pool.class);
        return new ManagerService(null, null, null, null, null, null, db, company -> null, corpus);
    }

    /** One suggest request, through the tier dispatch the endpoint actually calls. */
    private static JsonArray suggest(ManagerService service) {
        Future<JsonArray> f = service.appendCorpusSuggestions(
            new JsonArray(), null, "Toronto", "Canada", null, true);
        assertTrue(f.isComplete(), "the stubbed tiers answer synchronously");
        assertTrue(f.succeeded(), "a corpus failure must never fail the request: " + f.cause());
        return f.result();
    }

    @Test
    @DisplayName("the address tier is not wired to the geography breaker")
    void theAddressTierIsNotWiredToTheGeographyBreaker() {
        /*
          THE DEFECT, through the real call sites rather than through guarded() directly.

          Reverting the one-word wiring at the addresses call site makes this case fail and the
          others pass, which is why it exists: the breaker instances were always independent, it
          was the addresses tier that had been handed the wrong one.

          Five requests, each reading geography (failing, as on a cold backend) and addresses
          (succeeding with nothing, as for any country whose address artifact is unpublished).
        */
        StubCorpus corpus = new StubCorpus();
        ManagerService service = serviceOver(corpus);

        for (int request = 1; request <= 5; request++) {
            suggest(service);
            assertEquals(request, corpus.addressReads.get(),
                "the address tier must be consulted on every request");
            if (request < 5) {
                assertFalse(ManagerService.GEO_BREAKER.isOpen(),
                    "opened after " + request + " failures; the threshold is 5");
            }
        }

        assertEquals(5, corpus.geographyReads.get(), "geography was read once per request");
        assertTrue(ManagerService.GEO_BREAKER.isOpen(),
            "five consecutive geography failures must open the geography breaker. Before the fix "
            + "each succeeding address read reset the count, so it oscillated 1, 0, 1, 0 and the "
            + "threshold was unreachable: nine measured cold-start failures, zero breaker lines");
        assertFalse(ManagerService.ADDRESS_BREAKER.isOpen(),
            "the address tier answered every time and must stay available");

        // And the point of opening it: geography is no longer asked, so the deadline is not paid.
        suggest(service);
        assertEquals(5, corpus.geographyReads.get(),
            "an open geography breaker must stop the request paying the 4000ms deadline again");
        assertEquals(6, corpus.addressReads.get(),
            "and must not take the address tier down with it");
    }

    @Test
    @DisplayName("a failing places tier opens places alone, with geography answering")
    void thePlacesTierKeepsItsOwnAccount() {
        /*
          The third tier, for the same reason. Geography answers with no rows here, which does not
          answer the query, so the buildings file is read and allowed to fail.
        */
        StubCorpus corpus = new StubCorpus();
        corpus.geographyFails = false;
        corpus.placesFail = true;
        ManagerService service = serviceOver(corpus);

        for (int i = 0; i < 5; i++) suggest(service);

        assertEquals(5, corpus.placeReads.get(), "the buildings file was read once per request");
        assertTrue(ManagerService.PLACES_BREAKER.isOpen(),
            "five consecutive places failures must open the places breaker");
        assertFalse(ManagerService.GEO_BREAKER.isOpen(),
            "geography answered every request; a places outage must not disable the cheap read "
            + "that answers 'which city'");
        assertFalse(ManagerService.ADDRESS_BREAKER.isOpen());
    }
}
