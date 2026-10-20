package org.werkpages.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ConnectionPool}: bounding, return-on-close, timeout and shutdown.
 *
 * <p>These exist because the obvious implementation - one connection per thread - is wrong under
 * Vert.x, which owns and recycles worker threads. A ThreadLocal connection is created per worker,
 * never released when a task finishes, and never closed when the pool is resized or the verticle
 * is undeployed, so "however many threads Vert.x has" silently becomes the memory budget.
 */
class ConnectionPoolTest {

    private static Path file;
    private static String url;

    @BeforeAll
    static void makeIndex() throws Exception {
        file = Files.createTempFile("pooltest", ".sqlite");
        Files.deleteIfExists(file);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t(k TEXT)");
            s.execute("INSERT INTO t VALUES ('x')");
        }
        url = "jdbc:sqlite:file:" + file + "?mode=ro&immutable=1";
    }

    @AfterAll
    static void cleanUp() throws Exception { Files.deleteIfExists(file); }

    @Test
    @DisplayName("a borrowed connection works, and closing it returns it rather than closing it")
    void borrowAndReturn() throws Exception {
        try (ConnectionPool pool = new ConnectionPool(2, 1_000)) {
            Connection first;
            try (Connection c = pool.borrow(url)) {
                first = c;
                try (Statement s = c.createStatement()) {
                    assertTrue(s.executeQuery("SELECT k FROM t").next());
                }
            }
            assertEquals(1, pool.openCount(url), "one real connection was opened");
            // The same underlying connection is handed out again instead of a second being made.
            try (Connection c = pool.borrow(url)) {
                try (Statement s = c.createStatement()) {
                    assertTrue(s.executeQuery("SELECT k FROM t").next());
                }
            }
            assertEquals(1, pool.openCount(url), "the pool reused it rather than opening another");
        }
    }

    @Test
    @DisplayName("the pool never opens more connections than its limit")
    void respectsItsLimit() throws Exception {
        try (ConnectionPool pool = new ConnectionPool(3, 2_000)) {
            List<Connection> held = new ArrayList<>();
            for (int i = 0; i < 3; i++) held.add(pool.borrow(url));
            assertEquals(3, pool.openCount(url));
            assertTrue(pool.openCount(url) <= pool.limit());
            for (Connection c : held) c.close();
            assertEquals(3, pool.openCount(url), "returned, not discarded");
        }
    }

    @Test
    @DisplayName("a borrow that cannot be satisfied fails loudly instead of queueing forever")
    void timesOutRatherThanHanging() throws Exception {
        try (ConnectionPool pool = new ConnectionPool(1, 150)) {
            Connection held = pool.borrow(url);
            long started = System.nanoTime();
            /*
              An autocomplete request that cannot be served in time is better refused than left
              holding a worker thread: an unbounded queue turns one slow index into a stalled
              worker pool and then a stalled API.
            */
            assertThrows(SQLException.class, () -> pool.borrow(url));
            long tookMs = (System.nanoTime() - started) / 1_000_000;
            assertTrue(tookMs >= 100, "should have waited for the timeout, waited " + tookMs + "ms");
            assertTrue(tookMs < 2_000, "should not have waited far beyond it, waited " + tookMs + "ms");
            held.close();
            try (Connection c = pool.borrow(url)) {   // freed again once returned
                assertFalse(c.isClosed());
            }
        }
    }

    @Test
    @DisplayName("closing a borrowed connection twice does not corrupt the pool")
    void doubleCloseIsSafe() throws Exception {
        try (ConnectionPool pool = new ConnectionPool(2, 1_000)) {
            Connection c = pool.borrow(url);
            c.close();
            c.close();   // a second close must not release a permit that was already released
            Connection a = pool.borrow(url);
            Connection b = pool.borrow(url);
            assertTrue(pool.openCount(url) <= pool.limit(),
                "a double close must not let the pool exceed its limit");
            a.close(); b.close();
        }
    }

    @Test
    @DisplayName("concurrent borrowers are bounded and all make progress")
    void concurrentBorrowersAreBounded() throws Exception {
        try (ConnectionPool pool = new ConnectionPool(4, 5_000)) {
            int threads = 16;
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger ok = new AtomicInteger();
            for (int i = 0; i < threads; i++) {
                Thread t = new Thread(() -> {
                    try (Connection c = pool.borrow(url); Statement s = c.createStatement()) {
                        if (s.executeQuery("SELECT k FROM t").next()) ok.incrementAndGet();
                    } catch (SQLException ignored) {
                    } finally { done.countDown(); }
                });
                t.setDaemon(true);
                t.start();
            }
            assertTrue(done.await(20, TimeUnit.SECONDS), "all borrowers should finish");
            assertEquals(threads, ok.get(), "every borrower should have run its query");
            assertTrue(pool.openCount(url) <= pool.limit(),
                "opened " + pool.openCount(url) + " for a limit of " + pool.limit());
        }
    }

    @Test
    @DisplayName("closeAll releases every connection, for shutdown and for index replacement")
    void closeAllReleases() throws Exception {
        ConnectionPool pool = new ConnectionPool(2, 1_000);
        Connection a = pool.borrow(url);
        a.close();
        assertTrue(pool.openCount(url) > 0);
        /*
          Index replacement matters as much as shutdown: a new release is a new file, and handles
          left open against a swapped path keep serving the old bytes.
        */
        pool.closeAll(url);
        assertEquals(0, pool.openCount(url));
        pool.close();
    }
}
