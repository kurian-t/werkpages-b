package org.werkpages.repository;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The local search against a real published release, through the real JDBC driver.
 *
 * <p>Skipped unless a release is published, so CI never depends on a multi-gigabyte artifact.
 * Point it elsewhere with {@code LOCAL_CORPUS_ROOT}.
 *
 * <p>This is also the evidence that the search path actually goes through
 * {@link CorpusRelease}: the release is resolved here and handed to the index, so index, meta and
 * payload are guaranteed to come from one build rather than from three separate path lookups that
 * could straddle a publish.
 */
class LocalPlaceIndexManualTest {

    private static final Path ROOT = Path.of(
        System.getenv().getOrDefault("LOCAL_CORPUS_ROOT", "/home/noragrats/loc-corpus"));

    private Connection open(CorpusRelease r) throws Exception {
        /*
          READ-ONLY, like production.

          Opening read-write takes an exclusive file lock, so this test failed outright whenever a
          backend was running against the same release: "Conflicting lock is held ... by user". The
          release is immutable and the server opens it read-only, so a test that needs write access
          to read it was simply wrong.
        */
        java.util.Properties props = new java.util.Properties();
        props.setProperty("duckdb.read_only", "true");
        Connection c = DriverManager.getConnection(r.jdbcUrl(), props);
        try (Statement s = c.createStatement()) {
            s.execute("SET threads=2");
            s.execute("SET memory_limit='512MB'");
        }
        return c;
    }

    @Test
    @DisplayName("a published release answers the regression queries by exact identity")
    void searchAgainstPublishedRelease() throws Exception {
        CorpusRelease rel = CorpusRelease.resolve(ROOT);
        Assumptions.assumeTrue(rel != null, "no published corpus release");

        try (Connection c = open(rel)) {
            LocalPlaceIndex idx = new LocalPlaceIndex(c, rel);

            List<LocalPlaceIndex.Hit> subaru = idx.search("Elk Grove Subaru", 8);
            assertFalse(subaru.isEmpty(), "the dealership must be found");
            assertEquals("fb0c7d04-be92-4a79-91a5-bdf7784d7f8e", subaru.get(0).sourcePlaceId(),
                         "asserted by identity, not by a name fragment");

            // Word order must not matter.
            assertEquals("fb0c7d04-be92-4a79-91a5-bdf7784d7f8e",
                         idx.search("Subaru Elk Grove", 8).get(0).sourcePlaceId());

            // The address that started all of this, in both spellings the corpus and people use.
            assertEquals("fb0c7d04-be92-4a79-91a5-bdf7784d7f8e",
                         idx.search("8581 Laguna Grove Drive", 8).get(0).sourcePlaceId());
            assertEquals("fb0c7d04-be92-4a79-91a5-bdf7784d7f8e",
                         idx.search("8581 Laguna Grove Dr", 8).get(0).sourcePlaceId());

            // A leading article must not hide a chain.
            assertTrue(idx.search("The North Face", 8).stream()
                          .anyMatch(h -> h.name().toLowerCase().contains("north face")));

            // A genuine miss is empty, not an error.
            assertTrue(idx.search("Zzzqqx Nonexistent Holdings", 8).isEmpty());
        }
    }

    @Test
    @DisplayName("geography narrows to the named city rather than ranking it slightly higher")
    void geographyConstrains() throws Exception {
        CorpusRelease rel = CorpusRelease.resolve(ROOT);
        Assumptions.assumeTrue(rel != null, "no published corpus release");
        try (Connection c = open(rel)) {
            LocalPlaceIndex idx = new LocalPlaceIndex(c, rel);
            List<LocalPlaceIndex.Hit> inElkGrove = idx.search("Subaru, Elk Grove", 8);
            assertFalse(inElkGrove.isEmpty(), "a branch in the named city must be found");
            assertTrue(inElkGrove.stream().allMatch(h -> "Elk Grove".equalsIgnoreCase(h.city())),
                       "every result must be in the named city; geography is a constraint, "
                     + "not a ranking bonus that lets other branches through");
        }
    }
}
