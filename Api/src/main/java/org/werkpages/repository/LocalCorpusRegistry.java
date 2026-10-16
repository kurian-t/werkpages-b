package org.werkpages.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The per-country local indexes, opened on demand and bounded in number.
 *
 * <h2>Why per country at all</h2>
 *
 * <p>One index for the whole world is 24.4GB across 258 countries and 72,332,893 places, and the
 * host has 60GB of disk shared with everything else. A country is the natural unit: a search is
 * always for somewhere, the partitions are built and published independently, and a country can be
 * rebuilt without touching the others.
 *
 * <h2>Why bounded, and what eviction may not do</h2>
 *
 * <p>Each open pool is three DuckDB connections holding a memory-mapped index, and DuckDB
 * allocates outside the JVM heap, so the usual heap ceiling does not contain it. Opening every
 * country that anybody searches would therefore grow without limit. At most {@code MAX_OPEN}
 * countries are held, and the least recently used is closed when a new one is needed.
 *
 * <p>Closing is SAFE WHILE QUERIES ARE RUNNING, and that is a property of
 * {@link LocalCorpusPool#close()} rather than an accident of timing here: it stops new leases and
 * closes only the idle connections, leaving a connection that is executing a query to be closed by
 * its own caller when that caller finishes. An evicted pool therefore drains; it is never pulled
 * out from under a search in flight.
 *
 * <h2>What an unknown country means</h2>
 *
 * <p>{@code null}, and the caller falls back to the remote corpus, which covers the world. It does
 * NOT mean "search whatever happens to be open": answering a French query from the American index
 * would return nothing, and because an empty local answer is deliberately final it would REPLACE
 * the remote answer that does cover France with silence.
 */
public final class LocalCorpusRegistry implements AutoCloseable {

    private final Path root;
    private final boolean singleCountry;
    private final int poolSize, threads, maxOpen;
    private final String memory;
    private final Duration acquireTimeout;

    /** Access-ordered, so the eldest entry is the least recently used one. */
    private final LinkedHashMap<String, LocalCorpusPool> open =
        new LinkedHashMap<>(8, 0.75f, true);

    /** Countries that failed to open, so one broken release is not retried on every keystroke. */
    private final Set<String> failed = new TreeSet<>();

    /**
     * How many callers are relying on each open country RIGHT NOW, counted from the moment one is
     * handed out to the moment it finishes.
     *
     * <p>This exists because the pool's own lease count is not enough. A caller that has been
     * given a pool but has not yet taken a JDBC lease from it is invisible to
     * {@code stats().leased()}, so a pool with queued work looked idle and was evicted underneath
     * it. The reservation covers that whole span.
     */
    private final Map<String, Integer> inUse = new LinkedHashMap<>();

    /**
     * The release id last opened for each country, so a cached answer can be tied to the release
     * that produced it.
     *
     * <p>A release is immutable, which is what makes memoising safe - but a NEW release makes the
     * old answers wrong, and a cache keyed without it would serve them until the hour expired.
     * Including the id means a pointer swap simply makes the old entries unreachable, and they age
     * out under the usual eviction rather than needing to be hunted down.
     */
    private final Map<String, String> releaseIds = new LinkedHashMap<>();

    /** The release currently serving this country, or null if none has been opened. */
    public synchronized String releaseIdFor(String iso) {
        return iso == null ? null : releaseIds.get(iso.toUpperCase());
    }

    /** How often the bounded wait expired and a caller was sent to the remote corpus. */
    private final java.util.concurrent.atomic.AtomicLong saturationTimeouts =
        new java.util.concurrent.atomic.AtomicLong();

    public long saturationTimeouts() { return saturationTimeouts.get(); }

    private LocalCorpusRegistry(Path root, boolean singleCountry, int poolSize, int threads,
                                String memory, Duration acquireTimeout, int maxOpen) {
        this.root = root;
        this.singleCountry = singleCountry;
        this.poolSize = poolSize;
        this.threads = threads;
        this.memory = memory;
        this.acquireTimeout = acquireTimeout;
        this.maxOpen = maxOpen;
    }

    /**
     * Builds a registry over the corpus root, or null when there is nothing to serve.
     *
     * <p>A root that itself holds a {@code current} pointer is ONE country's releases, which is
     * how every release before country routing was laid out and how the tests still build their
     * fixtures. A root whose children hold the pointers is the routed layout. Supporting both is
     * what keeps an existing deployment and the fixture releases working unchanged.
     */
    public static LocalCorpusRegistry open(Path root, int poolSize, int threads, String memory,
                                           Duration acquireTimeout, int maxOpen) {
        if (root == null || !Files.isDirectory(root)) {
            System.err.println("local corpus registry: no corpus root at " + root);
            return null;
        }
        if (maxOpen < 1 || maxOpen > 16) {
            System.err.println("local corpus registry: refusing to hold " + maxOpen + " countries (want 1..16)");
            return null;
        }
        /*
          THE ROUTED LAYOUT WINS WHEN BOTH ARE PRESENT.

          Checking for a "current" pointer first was wrong on any root that has served both
          layouts: the corpus root here still holds the pointer to the last whole-continent
          release beside the new per-country directories, so the root looked like one country,
          that one release failed the schema check, and the registry reported "no releases" while
          four perfectly good country indexes sat next to it.
        */
        boolean single = !hasCountryDirs(root) && Files.exists(root.resolve("current"));
        LocalCorpusRegistry r = new LocalCorpusRegistry(root, single, poolSize, threads, memory,
                                                        acquireTimeout, maxOpen);
        List<String> found = r.countries();
        if (found.isEmpty()) {
            System.err.println("local corpus registry: no releases under " + root);
            return null;
        }
        System.err.println("local corpus registry: " + found.size() + " countr"
            + (found.size() == 1 ? "y" : "ies") + " available " + found
            + ", holding at most " + maxOpen);
        return r;
    }

    private static boolean hasCountryDirs(Path root) {
        try (var entries = Files.list(root)) {
            return entries.anyMatch(d -> Files.isDirectory(d)
                && d.getFileName().toString().toUpperCase().matches("[A-Z]{2}")
                && Files.exists(d.resolve("current")));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The countries this root can serve, read from disk each time.
     *
     * <p>Not cached: a release published while the process runs must become visible without a
     * restart, which is the whole point of the pointer swap. The cost is one directory listing.
     */
    public List<String> countries() {
        List<String> out = new ArrayList<>();
        if (singleCountry) {
            String c = single();
            if (c != null) out.add(c);
            return out;
        }
        try (var entries = Files.list(root)) {
            entries.filter(Files::isDirectory).forEach(dir -> {
                String name = dir.getFileName().toString().toUpperCase();
                if (name.matches("[A-Z]{2}") && Files.exists(dir.resolve("current"))) out.add(name);
            });
        } catch (Exception e) {
            System.err.println("local corpus registry: cannot list " + root + ": " + e);
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** The country a single-country root holds, taken from its manifest rather than guessed. */
    private String single() {
        CorpusRelease r = CorpusRelease.resolve(root);
        return r == null ? null : r.country();
    }

    /** Whether a local index can answer for this ISO country code. */
    public boolean covers(String iso) {
        if (iso == null || !iso.matches("[A-Za-z]{2}")) return false;
        String c = iso.toUpperCase();
        synchronized (this) { if (failed.contains(c)) return false; }
        return countries().contains(c);
    }

    /**
     * The pool for this country, opening it if necessary, or null if it cannot be served.
     *
     * <p>Synchronised on the registry, so two concurrent first-searches for one country open it
     * once rather than racing to build two pools over the same files.
     */
    /** Raised when every pool is in use and the configured limit forbids opening another. */
    public static final class Saturated extends RuntimeException {
        public Saturated(String message) { super(message); }
    }

    /**
     * Runs one search against a country's index, holding a reservation for its whole duration.
     *
     * <p>This replaces handing a pool back to the caller. The pool was fetched once to decide
     * whether a local answer was possible and again inside the blocking task that used it, and
     * between those two moments another thread could evict and close it - so the work was shed for
     * no reason, and the country was reopened moments later while the old pool still held a leased
     * connection. Reserving across the call closes that window.
     *
     * @throws Saturated when the limit is reached and every open country is busy. The caller falls
     *         back to the remote corpus rather than the limit being quietly exceeded: on a shared
     *         8GB host a predictable ceiling is worth more than an extra index opened under load.
     */
    public <T> T withCountry(String iso, LocalCorpusPool.Work<T> work) throws Exception {
        LocalCorpusPool pool;
        String c;
        synchronized (this) {
            /*
              WAITS BRIEFLY FOR A SLOT, rather than giving up the moment they are all busy.

              Refusing immediately kept the limit but paid for it twice: the caller then went to
              the remote corpus, and that is the expensive path this index exists to avoid - the
              buildings file is six times the cost of geography over S3 and seconds rather than
              milliseconds. Waiting a bounded moment for a search in front to finish is almost
              always cheaper than the fallback, and it does not weaken the bound at all: no extra
              pool is ever opened, the wait is capped, and a caller that waits too long still
              falls back rather than queueing without limit.
            */
            long deadline = System.currentTimeMillis() + acquireTimeout.toMillis();
            while (true) {
                try {
                    pool = poolFor(iso);
                    break;
                } catch (Saturated saturated) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        saturationTimeouts.incrementAndGet();
                        throw saturated;
                    }
                    // Releases the monitor, so the search in front can finish and notify.
                    wait(remaining);
                }
            }
            if (pool == null) return null;
            c = iso.toUpperCase();
            inUse.merge(c, 1, Integer::sum);
        }
        try {
            return pool.withIndex(work);
        } finally {
            synchronized (this) {
                Integer n = inUse.get(c);
                if (n == null || n <= 1) inUse.remove(c); else inUse.put(c, n - 1);
                // A slot may now be free; wake whoever is waiting for one.
                notifyAll();
            }
        }
    }

    /** Whether this country can be released: nobody reserved it and nothing is executing. */
    private boolean releasable(String country, LocalCorpusPool pool) {
        return !inUse.containsKey(country) && pool.stats().leased() == 0;
    }

    public synchronized LocalCorpusPool poolFor(String iso) {
        if (iso == null || !iso.matches("[A-Za-z]{2}")) return null;
        String c = iso.toUpperCase();
        LocalCorpusPool existing = open.get(c);
        if (existing != null) return existing;
        if (failed.contains(c)) return null;
        if (!countries().contains(c)) return null;

        Path dir = singleCountry ? root : root.resolve(c);
        LocalCorpusPool pool = LocalCorpusPool.openCurrent(dir, poolSize, threads, memory, acquireTimeout);
        if (pool == null) {
            /*
              REMEMBERED AS BROKEN, deliberately.

              Without this a release that cannot be opened - a half-copied index, a schema the
              build does not serve - was retried on every single keystroke, and each retry pays
              the full open attempt before failing. One bad country became a latency problem for
              every search in the country next to it.
            */
            failed.add(c);
            return null;
        }
        /*
          THE LIMIT IS A LIMIT. A busy country is never closed, and no extra one is opened either.

          Closing a busy pool is safe in itself - LocalCorpusPool.close() stops new leases and
          closes only the IDLE connections, so a connection executing a query is closed by its own
          caller when that caller finishes, and no running search is aborted. But it would shed a
          caller that had already decided to use that pool, and briefly hold a second set of
          connections beside the leased one.

          An earlier attempt skipped busy pools and opened the new country anyway, which made
          LOCAL_CORPUS_COUNTRIES stop being a bound at all: enough concurrent searches across
          enough countries could open as many indexes as there were countries. On a host with two
          backends sharing 8GB, an unpredictable ceiling is the worse failure. So when every open
          country is reserved, this REFUSES, and the caller uses the remote corpus for that
          keystroke.
        */
        while (open.size() >= maxOpen) {
            String victim = null;
            for (var e : open.entrySet()) {
                if (releasable(e.getKey(), e.getValue())) { victim = e.getKey(); break; }
            }
            if (victim == null) {
                throw new Saturated("all " + open.size() + " open countries are in use; "
                    + "refusing to exceed LOCAL_CORPUS_COUNTRIES=" + maxOpen);
            }
            LocalCorpusPool evicted = open.remove(victim);
            evicted.close();
            System.err.println("local corpus registry: closed " + victim + " to make room for " + c);
        }
        open.put(c, pool);
        releaseIds.put(c, pool.release().id());
        return pool;
    }

    /** The most recently used pool, for the diagnostics snapshot. Null when none is open. */
    public synchronized LocalCorpusPool current() {
        LocalCorpusPool last = null;
        for (LocalCorpusPool p : open.values()) last = p;   // access order: last is most recent
        return last;
    }

    public synchronized Map<String, LocalCorpusPool.Stats> stats() {
        Map<String, LocalCorpusPool.Stats> out = new LinkedHashMap<>();
        for (var e : open.entrySet()) out.put(e.getKey(), e.getValue().stats());
        return out;
    }

    @Override public synchronized void close() {
        for (LocalCorpusPool p : open.values()) p.close();
        open.clear();
    }
}
