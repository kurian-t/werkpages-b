package org.werkpages.repository;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;

/**
 * Which connection model the endpoint should use, decided by measurement.
 *
 * <p>An earlier conclusion that one shared connection was required came from four CLI processes
 * failing to open the database read-WRITE. That says nothing about read-only JDBC connections, and
 * a single shared Connection can serialise queries, which is the opposite of what a busy
 * autocomplete endpoint needs.
 *
 * <p>Both models here are read-only against the same immutable release, with DuckDB threads capped
 * so the two are compared on equal terms.
 */
class ConnectionModelBenchTest {

    private static final Path ROOT = Path.of(
        System.getenv().getOrDefault("LOCAL_CORPUS_ROOT", "/home/noragrats/loc-corpus"));
    private static final String[] Q = {
        "Elk Grove Subaru", "Texas Roadhouse", "The North Face", "Starbucks",
        "8581 Laguna Grove Drive", "New York Life", "Walmart", "Scotiabank",
    };

    /** Read-only, explicitly. The release is immutable, so nothing needs write access. */
    private static Connection openReadOnly(CorpusRelease r) throws Exception {
        Properties p = new Properties();
        p.setProperty("duckdb.read_only", "true");
        Connection c = DriverManager.getConnection(r.jdbcUrl(), p);
        try (Statement s = c.createStatement()) {
            s.execute("SET threads=2");
            s.execute("SET memory_limit='512MB'");
        }
        return c;
    }

    private static long pct(List<Long> s, double p) {
        return s.isEmpty() ? 0 : s.get(Math.min(s.size()-1, (int) Math.round(p*(s.size()-1))));
    }

    private static void report(String label, List<Long> micros, long wallMs) {
        List<Long> s = new ArrayList<>(micros); Collections.sort(s);
        System.out.printf("[conn] %-34s n=%-4d p50=%6.1fms p95=%6.1fms p99=%6.1fms wall=%dms%n",
            label, s.size(), pct(s,0.50)/1000.0, pct(s,0.95)/1000.0, pct(s,0.99)/1000.0, wallMs);
    }

    private List<Long> drive(CorpusRelease rel, int threads, int perThread,
                             java.util.function.Supplier<Connection> supply) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Long> lat = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(pool.submit(() -> {
                Connection c = supply.get();
                LocalPlaceIndex idx = new LocalPlaceIndex(c, rel);
                for (int i = 0; i < perThread; i++) {
                    long t0 = System.nanoTime();
                    try { idx.search(Q[i % Q.length], 8); }
                    catch (Exception e) { throw new RuntimeException(e); }
                    lat.add((System.nanoTime() - t0) / 1000);
                }
            }));
        }
        for (Future<?> f : fs) f.get(10, TimeUnit.MINUTES);
        pool.shutdown();
        return lat;
    }

    @Test
    @DisplayName("one shared connection versus a bounded read-only pool, under concurrency")
    void compareConnectionModels() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bench"), "set -Dbench=true");
        CorpusRelease rel = CorpusRelease.resolve(ROOT);
        Assumptions.assumeTrue(rel != null, "no published release");
        System.out.println("[conn] release " + rel.id());

        int threads = 4, perThread = 25;

        // A: ONE connection shared by every caller.
        try (Connection shared = openReadOnly(rel)) {
            long t0 = System.currentTimeMillis();
            List<Long> a = drive(rel, threads, perThread, () -> shared);
            report("A: single shared connection", a, System.currentTimeMillis() - t0);
        }

        // B: a BOUNDED pool, one read-only connection per worker, same release.
        List<Connection> pool = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) pool.add(openReadOnly(rel));
            Iterator<Connection> it = pool.iterator();
            Queue<Connection> q = new ConcurrentLinkedQueue<>(pool);
            long t0 = System.currentTimeMillis();
            List<Long> b = drive(rel, threads, perThread, q::poll);
            report("B: bounded read-only pool (" + threads + ")", b, System.currentTimeMillis() - t0);
        } finally {
            for (Connection c : pool) try { c.close(); } catch (Exception ignored) {}
        }
    }
}
