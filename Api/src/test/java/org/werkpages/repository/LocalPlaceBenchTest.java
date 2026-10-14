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
 * Latency of the real Java search path against a published release.
 *
 * <p>Timed with a monotonic outer timer around the WHOLE operation, including the frequency and
 * field lookups, not around the final SQL statement. Earlier numbers in this work measured only
 * part of the operation and read better than the truth.
 *
 * <p>Run with {@code -Dbench=true}; skipped otherwise so the ordinary suite stays fast.
 */
class LocalPlaceBenchTest {

    private static final Path ROOT = Path.of(
        System.getenv().getOrDefault("LOCAL_CORPUS_ROOT", "/home/noragrats/loc-corpus"));

    private static final String[] QUERIES = {
        "Elk Grove Subaru", "Subaru Elk Grove", "8581 Laguna Grove Drive", "8581 Laguna Grove Dr",
        "Texas Roadhouse", "The North Face", "North Face", "New York Life",
        "Starbucks", "Walmart", "Scotiabank", "Madison Square Garden",
        "Café Du Monde", "Zzzqqx Nonexistent Holdings",
    };

    private static Connection open(CorpusRelease r) throws Exception {
        Connection c = DriverManager.getConnection(r.jdbcUrl());
        try (Statement s = c.createStatement()) {
            s.execute("SET threads=2");
            s.execute("SET memory_limit='512MB'");
        }
        return c;
    }

    private static long pct(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.round(p * (sorted.size() - 1))));
    }

    private static void report(String label, List<Long> micros) {
        List<Long> s = new ArrayList<>(micros);
        Collections.sort(s);
        System.out.printf("[bench] %-26s n=%-4d p50=%6.1fms p95=%6.1fms p99=%6.1fms max=%6.1fms%n",
            label, s.size(), pct(s,0.50)/1000.0, pct(s,0.95)/1000.0, pct(s,0.99)/1000.0,
            s.get(s.size()-1)/1000.0);
    }

    @Test
    @DisplayName("latency of the real search path, warm and concurrent")
    void benchmark() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bench"), "set -Dbench=true to run");
        CorpusRelease rel = CorpusRelease.resolve(ROOT);
        Assumptions.assumeTrue(rel != null, "no published corpus release");
        System.out.println("[bench] release " + rel.id());

        // FIRST CALL per query on a fresh connection: the closest this can get to a user's first
        // search without clearing caches the rest of the machine is using.
        try (Connection c = open(rel)) {
            LocalPlaceIndex idx = new LocalPlaceIndex(c, rel);
            List<Long> first = new ArrayList<>();
            for (String q : QUERIES) {
                long t0 = System.nanoTime();
                idx.search(q, 8);
                first.add((System.nanoTime() - t0) / 1000);
            }
            report("first call per query", first);

            List<Long> warm = new ArrayList<>();
            for (int round = 0; round < 20; round++) {
                for (String q : QUERIES) {
                    long t0 = System.nanoTime();
                    idx.search(q, 8);
                    warm.add((System.nanoTime() - t0) / 1000);
                }
            }
            report("warm, repeated", warm);
        }

        // Concurrent typing: four callers, as two APIs each serving two users would behave.
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Long> conc = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(pool.submit(() -> {
                try (Connection c = open(rel)) {
                    LocalPlaceIndex idx = new LocalPlaceIndex(c, rel);
                    for (int i = 0; i < 25; i++) {
                        String q = QUERIES[i % QUERIES.length];
                        long t0 = System.nanoTime();
                        idx.search(q, 8);
                        conc.add((System.nanoTime() - t0) / 1000);
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            }));
        }
        for (Future<?> f : fs) f.get(5, TimeUnit.MINUTES);
        pool.shutdown();
        report("concurrent x" + threads, conc);

        Runtime rt = Runtime.getRuntime();
        System.out.printf("[bench] jvm heap used=%dMB max=%dMB%n",
            (rt.totalMemory() - rt.freeMemory()) / 1048576, rt.maxMemory() / 1048576);
    }
}
