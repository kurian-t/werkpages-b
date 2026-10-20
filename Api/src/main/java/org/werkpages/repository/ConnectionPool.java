package org.werkpages.repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A bounded pool of read-only SQLite connections, one borrow per query.
 *
 * <h2>Why this is not a ThreadLocal</h2>
 *
 * <p>The obvious implementation is one connection per thread, and it is wrong here. Vert.x runs
 * these lookups on a worker pool whose threads it owns and recycles; a ThreadLocal connection is
 * therefore created per worker thread, never released when the task finishes, and never closed
 * when the pool is resized or the verticle is undeployed. On a container capped at 2 GiB with a
 * 13.5 MiB native SQLite library and memory-mapped index pages, "however many threads Vert.x
 * happens to have" is not a resource budget.
 *
 * <p>So connections are pooled per index file with an explicit ceiling, borrowed for the duration
 * of one query and returned immediately. A borrow that cannot be satisfied within the timeout
 * fails loudly rather than queueing without limit, because an autocomplete request that cannot be
 * served in time is better refused than left holding a worker.
 *
 * <h2>Immutable files make this cheap</h2>
 *
 * <p>Every index is opened {@code mode=ro&immutable=1}. There is no writer, so there is no lock
 * contention and no WAL; concurrent readers were measured flat to eight threads at roughly 30,000
 * lookups a second with p95 under 0.8ms. The pool exists to bound memory and file handles, not to
 * serialise access.
 */
public final class ConnectionPool implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ConnectionPool.class.getName());

    /** Per index file. Small on purpose: these are page-cache reads, not long transactions. */
    private final int maxPerUrl;
    private final long borrowTimeoutMs;

    private final Map<String, Deque<Connection>> idle = new HashMap<>();
    private final Map<String, List<Connection>> all = new HashMap<>();
    private final Map<String, Semaphore> permits = new HashMap<>();

    public ConnectionPool(int maxPerUrl, long borrowTimeoutMs) {
        this.maxPerUrl = maxPerUrl;
        this.borrowTimeoutMs = borrowTimeoutMs;
    }

    /** Sized from the worker pool that will actually call this, with a hard ceiling. */
    public static ConnectionPool forWorkerPool(int workerPoolSize) {
        return new ConnectionPool(Math.max(2, Math.min(workerPoolSize, 8)), 2_000L);
    }

    /**
     * A connection held for one query. Closing the returned handle RETURNS it to the pool rather
     * than closing the underlying connection, so ordinary try-with-resources is correct.
     */
    public Connection borrow(String url) throws SQLException {
        Semaphore sem;
        synchronized (this) {
            sem = permits.computeIfAbsent(url, u -> new Semaphore(maxPerUrl, true));
        }
        boolean acquired;
        try {
            acquired = sem.tryAcquire(borrowTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted waiting for a connection to " + url, e);
        }
        if (!acquired) {
            throw new SQLException("no connection available within " + borrowTimeoutMs
                + "ms for " + url + " (pool limit " + maxPerUrl + ")");
        }
        Connection real;
        synchronized (this) {
            Deque<Connection> free = idle.computeIfAbsent(url, u -> new ArrayDeque<>());
            real = free.poll();
        }
        if (real == null || real.isClosed()) {
            // Read-only comes from the URL. sqlite-jdbc rejects setReadOnly() after connecting.
            real = DriverManager.getConnection(url);
            synchronized (this) {
                all.computeIfAbsent(url, u -> new ArrayList<>()).add(real);
            }
        }
        return wrap(url, real, sem);
    }

    /**
     * A Connection whose {@code close()} returns it to the pool instead of closing it.
     *
     * <p>A dynamic proxy rather than a hand-written delegate: Connection has over fifty methods
     * and only one of them behaves differently here, so forwarding by hand is fifty chances to
     * introduce a subtle difference and nothing to gain.
     */
    private Connection wrap(String url, Connection real, Semaphore sem) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                if ("close".equals(method.getName()) && (args == null || args.length == 0)) {
                    release(url, real, sem);
                    return null;
                }
                if ("isWrapperFor".equals(method.getName())) return false;
                try {
                    return method.invoke(real, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            });
    }

    void release(String url, Connection real, Semaphore sem) {
        boolean dead = true;
        try { dead = real.isClosed(); } catch (SQLException ignored) { }
        synchronized (this) {
            if (!dead) idle.computeIfAbsent(url, u -> new ArrayDeque<>()).add(real);
        }
        sem.release();
    }

    /**
     * Closes every connection to one index file.
     *
     * <p>Called when an index is REPLACED as well as at shutdown: a new release is a new file, and
     * the old handles must not be left open against a path that has been swapped underneath them.
     */
    public void closeAll(String url) {
        List<Connection> conns;
        synchronized (this) {
            conns = all.remove(url);
            idle.remove(url);
            permits.remove(url);
        }
        if (conns == null) return;
        for (Connection c : conns) {
            try { c.close(); } catch (SQLException err) {
                LOG.log(Level.FINE, "closing pooled connection", err);
            }
        }
    }

    @Override public void close() {
        List<String> urls;
        synchronized (this) { urls = new ArrayList<>(all.keySet()); }
        for (String u : urls) closeAll(u);
    }

    /** How many connections are currently open to one index file, for tests and diagnostics. */
    public synchronized int openCount(String url) {
        List<Connection> conns = all.get(url);
        return conns == null ? 0 : conns.size();
    }

    /** The per-file ceiling this pool enforces. */
    public int limit() { return maxPerUrl; }
}
