package org.werkpages.repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded pool of read-only DuckDB connections, all bound to ONE immutable corpus release.
 *
 * <h2>Why bounded, and why read-only</h2>
 *
 * <p>The host runs two backends on two shared vCPUs with a 1GiB limit each. Unbounded connections
 * would let a burst of typing exhaust CPU and native memory, and DuckDB allocates outside the JVM
 * heap, so the usual heap ceiling does not contain it. The pool is the admission control: when
 * every lease is out, a caller waits briefly and is then refused rather than queueing without
 * limit.
 *
 * <p>Read-only because the release never changes. Measured, four read-only connections answered a
 * concurrent workload at 457ms p95 against 492ms for one shared connection, so sharing a single
 * JDBC connection is both slower and needlessly risky.
 *
 * <h2>Release lifetime</h2>
 *
 * <p>A pool holds one release for its whole life. When a new one is published a new pool is built
 * and the old one is drained, so requests already running finish against the files they started
 * with. Nothing deletes a release while a pool still holds it.
 */
public final class LocalCorpusPool implements AutoCloseable {

    /** What the pool is doing, for logs and health checks. No user text is ever recorded. */
    public record Stats(String releaseId, int size, int available, int leased, long acquired,
                        long acquireTimeouts, long queryErrors, long discarded, boolean closed) {}

    private final CorpusRelease release;
    private final BlockingQueue<Connection> idle;
    private final Duration acquireTimeout;
    private final AtomicLong acquired = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong errors   = new AtomicLong();
    private final AtomicLong discarded = new AtomicLong();
    private final AtomicInteger size   = new AtomicInteger();
    private final AtomicInteger leased  = new AtomicInteger();
    private volatile boolean closed = false;

    private LocalCorpusPool(CorpusRelease release, int poolSize, int duckdbThreads,
                            String memoryLimit, Duration acquireTimeout) throws Exception {
        this.release = release;
        this.acquireTimeout = acquireTimeout;
        this.idle = new ArrayBlockingQueue<>(poolSize);

        /*
          EVERY CONNECTION OPENED SO FAR IS CLOSED IF A LATER ONE FAILS.

          Without this, a pool that failed on its third connection left the first two open for the
          life of the process: openCurrent catches the exception and returns null, so nothing ever
          held a reference to them again. DuckDB connections are native memory and memory-mapped
          files, not just heap, so a leaked one also pins the release directory against deletion.
        */
        List<Connection> opened = new ArrayList<>();
        try {
            for (int i = 0; i < poolSize; i++) {
                Connection c = open(release, duckdbThreads, memoryLimit);
                opened.add(c);
                idle.add(c);
                size.incrementAndGet();
            }
        } catch (Exception failure) {
            for (Connection c : opened) {
                try { c.close(); } catch (Exception ignored) { /* already failing */ }
            }
            idle.clear();
            size.set(0);
            throw failure;
        }
    }

    private static Connection open(CorpusRelease r, int threads, String memoryLimit) throws Exception {
        Properties props = new Properties();
        /*
          A local release opens the file read-only. A REMOTE one opens an in-memory database and
          attaches the object-store file to it, so the connection itself must not be read-only -
          the attach would have nowhere to record itself.
        */
        if (!r.isRemote()) props.setProperty("duckdb.read_only", "true");
        Connection c = DriverManager.getConnection(r.jdbcUrl(), props);
        try (Statement s = c.createStatement()) {
            if (r.isRemote()) {
                s.execute("INSTALL httpfs");
                s.execute("LOAD httpfs");
                // The instance role in production, ~/.aws/credentials locally. Identical to how
                // the remote places path authenticates; this never needs write access.
                s.execute("CREATE OR REPLACE SECRET corpus_idx (TYPE s3, PROVIDER credential_chain, "
                        + "REGION '" + System.getenv().getOrDefault("LOCATION_CORPUS_REGION", "ca-central-1") + "')");
                s.execute("ATTACH '" + r.indexLocation() + "' AS corpus_idx (READ_ONLY)");
                s.execute("USE corpus_idx");
            }
            // Per connection, so the pool as a whole cannot exceed what the container allows.
            s.execute("SET threads=" + threads);
            s.execute("SET memory_limit='" + memoryLimit + "'");
        } catch (Exception e) {
            // Opened but unusable: close it here or it leaks exactly as described above.
            try { c.close(); } catch (Exception ignored) { }
            throw e;
        }
        return c;
    }

    /**
     * Builds a pool for the current release, or null when there is none to serve.
     *
     * <p>Null is a supported state: a backend with no local corpus falls back to the remote path.
     * A release that cannot be opened is the same as none, and says so in the log rather than
     * failing startup.
     */
    public static LocalCorpusPool openCurrent(java.nio.file.Path root, int poolSize,
                                              int duckdbThreads, String memoryLimit,
                                              Duration acquireTimeout) {
        /*
          Configuration is validated rather than trusted. These come from environment variables, so
          a typo is a deployment-time mistake: LOCAL_CORPUS_POOL=0 built an ArrayBlockingQueue(0),
          which throws IllegalArgumentException from the constructor and disabled local search with
          a message that pointed at the release rather than the setting.
        */
        if (poolSize < 1 || poolSize > 32) {
            System.err.println("local corpus pool: refusing pool size " + poolSize + " (want 1..32)");
            return null;
        }
        if (duckdbThreads < 1 || duckdbThreads > 16) {
            System.err.println("local corpus pool: refusing thread count " + duckdbThreads + " (want 1..16)");
            return null;
        }
        if (memoryLimit == null || !memoryLimit.matches("(?i)\\d+(\\.\\d+)?\\s*(kb|mb|gb|tb|b)")) {
            System.err.println("local corpus pool: refusing memory limit '" + memoryLimit + "'");
            return null;
        }
        if (acquireTimeout == null || acquireTimeout.isNegative() || acquireTimeout.toMillis() > 5000) {
            System.err.println("local corpus pool: refusing acquire timeout " + acquireTimeout);
            return null;
        }

        /*
          An s3:// root is read in place rather than from disk. Same validations above, same pool
          below; only where the bytes live differs, which is exactly what is being measured.
        */
        String rootText = root.toString();
        CorpusRelease r = rootText.startsWith("s3:/")
            ? CorpusRelease.remote(rootText.replaceFirst("^s3:/+", "s3://"))
            : CorpusRelease.resolve(root);
        if (r == null) return null;
        try {
            LocalCorpusPool pool = new LocalCorpusPool(r, poolSize, duckdbThreads, memoryLimit, acquireTimeout);
            System.err.println("local corpus pool ready: release=" + r.id() + " connections=" + poolSize);
            return pool;
        } catch (Exception e) {
            System.err.println("local corpus pool could not open release " + r.id() + ": " + e);
            return null;
        }
    }

    /** A borrowed connection. Always returned, including when the body throws. */
    public interface Work<T> { T run(LocalPlaceIndex index) throws Exception; }

    /**
     * Runs one search on a borrowed connection.
     *
     * @throws Overloaded when no lease became free within the acquisition timeout. The caller is
     *         expected to shed the request rather than queue it: a keystroke whose answer arrives
     *         after the next keystroke is worth nothing.
     */
    public <T> T withIndex(Work<T> work) throws Exception {
        if (closed) throw new Overloaded("pool closed");

        Connection c;
        try {
            c = idle.poll(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            // Restore the flag: swallowing it leaves a worker that cannot be shut down, and this
            // runs on a pooled thread that something else owns.
            Thread.currentThread().interrupt();
            throw new Overloaded("interrupted while acquiring a corpus connection");
        }
        if (c == null) {
            timeouts.incrementAndGet();
            throw new Overloaded("no connection within " + acquireTimeout.toMillis() + "ms");
        }

        // Won the race against close(): hand it back and refuse, rather than query a closing pool.
        if (closed) { release(c); throw new Overloaded("pool closed"); }

        acquired.incrementAndGet();
        leased.incrementAndGet();
        boolean usable = true;
        try {
            return work.run(new LocalPlaceIndex(c, release));
        } catch (Exception e) {
            errors.incrementAndGet();
            /*
              A connection that is no longer valid is DISCARDED, not returned.

              A broken DuckDB connection returned to the pool is permanent: every later caller that
              borrows it fails the same way, so one transient fault becomes a dead pool slot that
              nothing ever repairs. The pool runs smaller instead, which degrades throughput rather
              than correctness.
            */
            usable = isUsable(c);
            throw e;
        } finally {
            leased.decrementAndGet();
            if (usable) release(c);
            else discard(c);
        }
    }

    private static boolean isUsable(Connection c) {
        try { return !c.isClosed() && c.isValid(1); } catch (Exception e) { return false; }
    }

    /**
     * Returns a lease, or closes it if the pool is shutting down.
     *
     * <p>This is the draining half of {@link #close()}: the connection whose caller was still
     * running is closed by that caller, at the moment it finishes, instead of being closed
     * underneath it.
     */
    private void release(Connection c) {
        if (closed || !idle.offer(c)) {
            // Either draining, or the queue is somehow full. Closing is the only safe option left;
            // dropping the reference would leak native memory.
            try { c.close(); } catch (Exception ignored) { }
            size.decrementAndGet();
        }
    }

    private void discard(Connection c) {
        discarded.incrementAndGet();
        size.decrementAndGet();
        try { c.close(); } catch (Exception ignored) { }
        System.err.println("local corpus pool: discarded an unusable connection, size now " + size.get());
    }

    /** Signalled when the pool is saturated, so the caller can shed rather than queue. */
    public static final class Overloaded extends RuntimeException {
        public Overloaded(String message) { super(message); }
    }

    public CorpusRelease release() { return release; }

    public Stats stats() {
        return new Stats(release.id(), size.get(), idle.size(), leased.get(),
                         acquired.get(), timeouts.get(), errors.get(), discarded.get(), closed);
    }

    /**
     * Stops new leases and closes the IDLE connections. In-flight searches are left alone.
     *
     * <p>This used to close every connection it had ever opened, including the ones currently
     * executing a query, while its own documentation claimed that "callers already running finish
     * first, because each holds its lease". They did not: closing a DuckDB connection under a
     * running query aborts it, so a release switch or a shutdown failed whatever searches happened
     * to be in flight.
     *
     * <p>Now a leased connection is closed by {@link #release} when its caller finishes, which is
     * what draining means. Safe to call twice, and from more than one thread.
     */
    @Override public void close() {
        closed = true;
        for (Connection c = idle.poll(); c != null; c = idle.poll()) {
            try { c.close(); } catch (Exception ignored) { }
            size.decrementAndGet();
        }
    }
}
