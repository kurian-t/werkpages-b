package org.werkpages.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who starts the location corpus, and who must not.
 *
 * <p>The cold-start fix only works if something actually asks for initialisation at boot, and the
 * only caller is MainVerticle. That makes two things worth holding in a test: that the service
 * really does reach the corpus when asked, and that merely CONSTRUCTING a service does not.
 *
 * <p>The second is the one that will be broken by accident. Putting the call in the constructor
 * looks tidier and removes a line from MainVerticle, and it would make every unit test in this
 * product open a DuckDB engine and reach for an S3 bucket - thousands of them, none of which asked
 * for a corpus. Construction is documented as free and incapable of failing. This keeps it that
 * way.
 *
 * <p>See LocationColdStartTest for what initialisation itself is proven to do.
 */
class LocationStartupWiringTest {

    /** Counts the calls instead of opening anything. */
    private static final class CountingCorpus extends org.werkpages.repository.LocationCorpusRepository {
        final AtomicInteger started = new AtomicInteger();

        CountingCorpus() { super("unused-bucket", "ca-central-1", "64MB", ""); }

        @Override
        public void beginInitialisation() { started.incrementAndGet(); }
    }

    private static ManagerService serviceOver(CountingCorpus corpus) {
        // A mock client, because none of this touches the database. Pool rather than SqlClient:
        // the repositories the constructor builds need the wider type.
        SqlClient db = org.mockito.Mockito.mock(Pool.class);
        return new ManagerService(null, null, null, null, null, null, db, company -> null, corpus);
    }

    @Test
    @DisplayName("constructing a service does NOT start the corpus")
    void constructionDoesNotInitialise() {
        CountingCorpus corpus = new CountingCorpus();
        serviceOver(corpus);
        assertEquals(0, corpus.started.get(),
            "constructing a ManagerService must stay free: every unit test does it, and none of "
            + "them want a DuckDB engine or an S3 round trip");
    }

    @Test
    @DisplayName("startLocationCorpus reaches the corpus, once per call")
    void startReachesTheCorpus() {
        CountingCorpus corpus = new CountingCorpus();
        ManagerService service = serviceOver(corpus);

        service.startLocationCorpus();
        assertEquals(1, corpus.started.get(), "MainVerticle's one call must reach the corpus");

        // Repeated calls are the repository's business to collapse, and it does; the service must
        // not quietly swallow them, because then nothing here could tell the two apart.
        service.startLocationCorpus();
        assertEquals(2, corpus.started.get());
    }
}
