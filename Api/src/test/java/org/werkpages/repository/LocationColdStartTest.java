package org.werkpages.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cold start: when the corpus is brought up, and what a request that arrives first is told.
 *
 * <h2>The defect</h2>
 *
 * <p>Everything about the corpus was lazy. The DuckDB engine opened on the first query, and the
 * worldwide geography file was only asked for at the END of that open. But a request with no
 * country calls {@code awaitGeography()} BEFORE it issues any query, because the answer depends on
 * whether geography can be searched worldwide. So on a cold process the first such request waited
 * the full {@link LocationCorpusRepository#GEOGRAPHY_WAIT_MS} for a signal that nothing had been
 * started to produce, gave up, and answered a worldwide question from the configured country
 * shortlist.
 *
 * <p>Measured on a genuinely cold process, with the geography file already on disk:
 * 2,000ms of unproductive waiting, then 801ms to open the engine and 663ms of real work. 3.51s,
 * and "Starbucks Paris" came back as Paris, Ontario. The wait was not too short. It was waiting
 * for something nobody had asked for.
 *
 * <h2>And two signalling defects behind it</h2>
 *
 * <p>The latch that says "geography has arrived, or is not coming" was counted down TWICE on the
 * already-local path and NOT AT ALL on the download path - neither on success nor on failure - and
 * never when the corpus could not be opened in the first place. A latch of one makes the double
 * countdown harmless; the missing ones meant a waiter that was already inside the await when the
 * file landed still sat out the whole two seconds, and on a host where the corpus is unreachable
 * EVERY countryless request paid two seconds to learn what the first one already knew.
 *
 * <h2>What is proven here, and what is not</h2>
 *
 * <p>These run with no network and no published corpus. The release is pinned by property and the
 * geography file is a handful of synthetic rows written to a temporary directory, so the real
 * {@code read_parquet} path executes against a real DuckDB engine on real local data.
 *
 * <p>What is NOT proven here is a successful S3 download, which needs the bucket. Its signalling is
 * covered through the failure branch and the already-local branch; the 54MB copy itself is measured
 * by hand against the live corpus.
 */
class LocationColdStartTest {

    /** Where the repository looks for the geography file; redirected for the test's lifetime. */
    private String savedTmpDir;
    private String savedRelease;
    private LocationCorpusRepository repo;

    @AfterEach
    void restore() {
        if (repo != null) repo.close();
        if (savedTmpDir != null) System.setProperty("java.io.tmpdir", savedTmpDir);
        if (savedRelease == null) System.clearProperty("LOCATION_CORPUS_RELEASE");
        else System.setProperty("LOCATION_CORPUS_RELEASE", savedRelease);
    }

    private static final String RELEASE = "coldstart-test";

    /**
     * A corpus whose release is pinned and whose geography file is already local.
     *
     * <p>Nothing here reaches S3: the pinned release is read from the property instead of
     * {@code current.json}, and geography is read from the local file. The bucket name is
     * deliberately one that cannot exist, so a test that accidentally reads it fails loudly rather
     * than quietly measuring somebody's network.
     */
    private LocationCorpusRepository withLocalGeography(Path tmp) throws Exception {
        savedTmpDir  = System.getProperty("java.io.tmpdir");
        savedRelease = System.getProperty("LOCATION_CORPUS_RELEASE");
        System.setProperty("java.io.tmpdir", tmp.toString());
        System.setProperty("LOCATION_CORPUS_RELEASE", RELEASE);
        writeGeography(tmp.resolve("location-corpus").resolve("geography-" + RELEASE + ".parquet"));
        return fresh();
    }

    private LocationCorpusRepository fresh() {
        // Four arguments with an explicit blank index path: the SQLite geography index would make
        // geographyIsWorldwide() true from construction, and then none of the waiting this covers
        // would ever happen.
        repo = new LocationCorpusRepository("bucket-that-does-not-exist-" + System.nanoTime(),
                                            "ca-central-1", "64MB", "");
        return repo;
    }

    /** Three localities, in the shape the geography corpus publishes. Paris, France is the biggest. */
    private static void writeGeography(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement()) {
            s.execute("COPY (SELECT * FROM (VALUES "
                + "('FR','Paris','Ile-de-France','IDF','locality',2148000),"
                + "('US','Paris','Texas','TX','locality',25171),"
                + "('CA','Toronto','Ontario','ON','locality',2794356)"
                + ") AS t(country_code,name,state_name,state_code,kind,population)) "
                + "TO '" + file + "' (FORMAT parquet)");
        }
        assertTrue(Files.exists(file), "the fixture parquet was not written");
    }

    /** Waits for initialisation to report an outcome, either outcome. */
    private static void awaitSignal(LocationCorpusRepository repo, long ms) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        while (!repo.geographySignalled() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(repo.geographySignalled(),
            "initialisation never reported an outcome within " + ms + "ms");
    }

    private static <T> T get(io.vertx.core.Future<T> f, long ms) throws Exception {
        CompletableFuture<T> done = new CompletableFuture<>();
        f.onSuccess(done::complete).onFailure(done::completeExceptionally);
        return done.get(ms, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("initialisation begins without any request, and makes geography worldwide")
    void initialisationBeginsWithoutAnyRequest(@TempDir Path tmp) throws Exception {
        LocationCorpusRepository corpus = withLocalGeography(tmp);

        // Cold: nothing has been opened and geography cannot be searched worldwide.
        assertEquals(0, corpus.engineOpenAttempts(), "nothing should have opened the engine yet");
        assertFalse(corpus.geographyIsWorldwide());
        assertFalse(corpus.geographySignalled());

        corpus.beginInitialisation();
        awaitSignal(corpus, 30_000);

        // THE POINT: no query was ever issued, and the engine opened anyway. Before this existed,
        // engineOpenAttempts() stayed at zero until somebody typed, which is why the first person
        // to type paid for it.
        assertEquals(1, corpus.engineOpenAttempts(),
            "initialisation must open the engine itself, not wait for a query to do it");
        assertTrue(corpus.geographyIsWorldwide(),
            "geography should be worldwide once initialisation has run; if this fails with the "
            + "engine unopened, check that the DuckDB httpfs extension is available to this JVM");
    }

    @Test
    @DisplayName("a request after initialisation resolves the worldwide answer, not the shortlist")
    void aRequestAfterInitialisationResolvesWorldwide(@TempDir Path tmp) throws Exception {
        LocationCorpusRepository corpus = withLocalGeography(tmp);
        corpus.beginInitialisation();
        awaitSignal(corpus, 30_000);

        long started = System.currentTimeMillis();
        JsonObject place = get(corpus.intendedPlace("starbucks paris"), 30_000);
        long took = System.currentTimeMillis() - started;

        // France, not Texas: the most populous Paris wins, which is the whole reason intent is
        // resolved worldwide rather than against a configured country list.
        assertNotNull(place, "a cold process that has initialised must still resolve intent");
        assertEquals("FR", place.getString("country_code"));
        assertEquals("Paris", place.getString("name"));
        // And it did not wait: the signal was already down before the request arrived.
        assertTrue(took < LocationCorpusRepository.GEOGRAPHY_WAIT_MS,
            "an initialised corpus must not make a request wait for geography; took " + took + "ms");
    }

    @Test
    @DisplayName("a request arriving before initialisation falls back within the bound")
    void aRequestBeforeInitialisationIsBounded(@TempDir Path tmp) throws Exception {
        LocationCorpusRepository corpus = withLocalGeography(tmp);
        // Deliberately NOT initialised: this is the request that arrives during startup, before
        // geography is ready. It must degrade, and it must degrade within the bound.
        long started = System.currentTimeMillis();
        JsonObject place = get(corpus.intendedPlace("starbucks paris"),
                               LocationCorpusRepository.GEOGRAPHY_WAIT_MS + 20_000);
        long took = System.currentTimeMillis() - started;

        assertNull(place, "geography is not ready, so no intent may be claimed");
        assertTrue(took >= LocationCorpusRepository.GEOGRAPHY_WAIT_MS - 200,
            "it should have waited for geography rather than answering instantly; took " + took);
        assertTrue(took < LocationCorpusRepository.GEOGRAPHY_WAIT_MS + 5_000,
            "the wait must stay bounded; took " + took + "ms");
    }

    @Test
    @DisplayName("REGRESSION: a waiter wakes as soon as the already-local path signals")
    void aWaiterWakesWhenGeographyBecomesLocal(@TempDir Path tmp) throws Exception {
        LocationCorpusRepository corpus = withLocalGeography(tmp);

        CountDownLatch waiting = new CountDownLatch(1);
        CompletableFuture<Long> woke = new CompletableFuture<>();
        Thread waiter = new Thread(() -> {
            long began = System.currentTimeMillis();
            waiting.countDown();
            try {
                boolean ready = get(corpus.awaitGeography(),
                                    LocationCorpusRepository.GEOGRAPHY_WAIT_MS + 20_000);
                woke.complete(ready ? System.currentTimeMillis() - began : -1L);
            } catch (Exception err) {
                woke.completeExceptionally(err);
            }
        });
        waiter.start();
        assertTrue(waiting.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);

        // Initialisation finishes while the request is already inside the await.
        corpus.cacheGeographyLocally(null, RELEASE);

        long took = woke.get(30, TimeUnit.SECONDS);
        assertTrue(took >= 0, "the waiter should have been told geography IS ready");
        assertTrue(took < LocationCorpusRepository.GEOGRAPHY_WAIT_MS,
            "the waiter must be released by the signal, not by the timeout; took " + took + "ms");
    }

    @Test
    @DisplayName("REGRESSION: a copy that cannot run still signals, and does not claim readiness")
    void aFailedCopyStillSignals(@TempDir Path tmp) throws Exception {
        savedTmpDir  = System.getProperty("java.io.tmpdir");
        savedRelease = System.getProperty("LOCATION_CORPUS_RELEASE");
        System.setProperty("java.io.tmpdir", tmp.toString());
        LocationCorpusRepository corpus = fresh();

        // No local file, so this takes the download branch, and it is handed a connection it cannot
        // copy with. The cheapest way to reach the failure path with no bucket: what is under test
        // is the signalling, not the SQL.
        corpus.cacheGeographyLocally(null, RELEASE);

        assertTrue(corpus.geographySignalled(),
            "the download path must report its outcome; it used to report nothing at all");
        assertFalse(corpus.geographyIsWorldwide(),
            "a failed copy must NOT be mistaken for geography being ready");

        // And the consequence that matters: a request is told immediately, not in two seconds.
        long started = System.currentTimeMillis();
        assertFalse(get(corpus.awaitGeography(),
                        LocationCorpusRepository.GEOGRAPHY_WAIT_MS + 20_000));
        long took = System.currentTimeMillis() - started;
        assertTrue(took < 500,
            "a signalled failure must not make the caller wait out the full bound; took " + took);
    }

    @Test
    @DisplayName("a real copy against an unreachable bucket signals too")
    void aRealCopyAgainstAnUnreachableBucketSignals(@TempDir Path tmp) throws Exception {
        savedTmpDir  = System.getProperty("java.io.tmpdir");
        savedRelease = System.getProperty("LOCATION_CORPUS_RELEASE");
        System.setProperty("java.io.tmpdir", tmp.toString());
        LocationCorpusRepository corpus = fresh();

        // The actual COPY statement, run against a bucket that does not exist. No timing assertion:
        // how long a bogus host takes to fail is the network's business, not this test's. The end
        // state is what matters.
        try (Connection own = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement s = own.createStatement()) {
                // The same bound the repository puts on its own engine, and for the same reason:
                // DuckDB defaults to three retries against a 30s timeout, so an unreachable host
                // would otherwise hold this test for a minute and a half.
                s.execute("SET http_retries=0");
                s.execute("SET http_timeout=2000");
            }
            corpus.cacheGeographyLocally(own, RELEASE);
        }
        assertTrue(corpus.geographySignalled());
        assertFalse(corpus.geographyIsWorldwide());
        assertFalse(Files.exists(tmp.resolve("location-corpus")
                                    .resolve("geography-" + RELEASE + ".parquet")),
            "a failed copy must not leave a file behind for the next process to trust");
    }

    @Test
    @DisplayName("REGRESSION: a corpus that cannot be opened at all signals, once")
    void anUnopenableCorpusSignals() throws Exception {
        savedRelease = System.getProperty("LOCATION_CORPUS_RELEASE");
        // No pinned release, so the release pointer is read from a bucket that does not exist and
        // the whole open fails. This is the state of any host without corpus credentials, and it
        // used to leave the latch up for the life of the process.
        System.clearProperty("LOCATION_CORPUS_RELEASE");
        LocationCorpusRepository corpus = fresh();

        corpus.beginInitialisation();
        awaitSignal(corpus, 60_000);
        assertFalse(corpus.geographyIsWorldwide());

        long started = System.currentTimeMillis();
        assertFalse(get(corpus.awaitGeography(),
                        LocationCorpusRepository.GEOGRAPHY_WAIT_MS + 20_000));
        long took = System.currentTimeMillis() - started;
        assertTrue(took < 500,
            "every request after a failed open paid " + LocationCorpusRepository.GEOGRAPHY_WAIT_MS
            + "ms for nothing; it must now be told at once. took " + took + "ms");
    }

    @Test
    @DisplayName("concurrent callers start initialisation once, and open the engine once")
    void concurrentCallersInitialiseOnce(@TempDir Path tmp) throws Exception {
        LocationCorpusRepository corpus = withLocalGeography(tmp);

        int callers = 16;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        AtomicInteger failed = new AtomicInteger();
        for (int i = 0; i < callers; i++) {
            new Thread(() -> {
                try {
                    go.await();
                    corpus.beginInitialisation();
                } catch (Exception err) {
                    failed.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertEquals(0, failed.get());
        awaitSignal(corpus, 30_000);

        assertEquals(1, corpus.initialisationsBegun(),
            callers + " callers must not queue " + callers + " initialisations");
        assertEquals(1, corpus.engineOpenAttempts(), "the engine must be opened exactly once");
        assertTrue(corpus.geographyIsWorldwide());
    }
}
