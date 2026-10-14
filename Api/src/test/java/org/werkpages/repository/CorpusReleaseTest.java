package org.werkpages.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A release switch must not change what a search already in flight is reading.
 *
 * <p>Publishing is "add a directory, move a pointer", and the move is atomic. That makes each
 * resolution consistent; it does not make two resolutions consistent with each other. A search that
 * opened the index from {@code current} and then read the payload from {@code current} could pair
 * files from different builds, and because place ids are assigned per build the result would be the
 * wrong business under a plausible id, not an obvious error.
 */
class CorpusReleaseTest {

    private static Path publish(Path root, String id, String indexBody, String payloadBody)
            throws Exception {
        Path dir = root.resolve("releases").resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.duckdb"), indexBody);
        Files.writeString(dir.resolve("payload.parquet"), payloadBody);
        Files.writeString(dir.resolve("manifest.json"),
            "{\"release\":\"" + id + "\",\"index\":\"index.duckdb\","
          + "\"payload\":\"payload.parquet\",\"schema\":"
          + CorpusRelease.SCHEMA + "}");
        // Atomic pointer move, exactly as the build script does it.
        Path tmp = root.resolve(".current.new");
        Files.deleteIfExists(tmp);
        Files.createSymbolicLink(tmp, dir);
        Files.move(tmp, root.resolve("current"),
                   java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                   java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return dir;
    }

    @Test
    @DisplayName("a release switch mid-search leaves the held release intact and self-consistent")
    void switchDuringSearchDoesNotCrossReleases(@TempDir Path root) throws Exception {
        publish(root, "rel-A", "INDEX-A", "PAYLOAD-A");
        CorpusRelease held = CorpusRelease.resolve(root);
        assertNotNull(held);
        assertEquals("rel-A", held.id());

        // A new release is published while the search above is still running.
        publish(root, "rel-B", "INDEX-B", "PAYLOAD-B");

        // The held release still names rel-A's files, and BOTH of them, so the pair cannot straddle
        // the switch. This is the assertion that fails if anything re-resolves `current` per file.
        assertEquals("INDEX-A",   Files.readString(held.indexPath()));
        assertEquals("PAYLOAD-A", Files.readString(held.payloadPath()));

        // And a search starting now gets the new release, both halves of it.
        CorpusRelease fresh = CorpusRelease.resolve(root);
        assertEquals("rel-B", fresh.id());
        assertEquals("INDEX-B",   Files.readString(fresh.indexPath()));
        assertEquals("PAYLOAD-B", Files.readString(fresh.payloadPath()));
    }

    @Test
    @DisplayName("publishing never deletes a release a reader may still hold")
    void publishingKeepsOldReleases(@TempDir Path root) throws Exception {
        Path a = publish(root, "rel-A", "INDEX-A", "PAYLOAD-A");
        publish(root, "rel-B", "INDEX-B", "PAYLOAD-B");
        assertTrue(Files.exists(a.resolve("index.duckdb")),
                   "the superseded release must stay on disk while readers may hold it");
    }

    @Test
    @DisplayName("a release built for an older query implementation is refused, not opened")
    void incompatibleSchemaIsRefused(@TempDir Path root) throws Exception {
        Path dir = root.resolve("releases/rel-old");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.duckdb"), "OLD");
        Files.writeString(dir.resolve("payload.parquet"), "OLD");
        // Schema 1: no per-field tok_freq, no region_alias. Opening it with today's query path
        // would silently answer from a layout it no longer matches.
        Files.writeString(dir.resolve("manifest.json"),
            "{\"release\":\"rel-old\",\"index\":\"index.duckdb\","
          + "\"payload\":\"payload.parquet\",\"schema\":1}");
        Files.createSymbolicLink(root.resolve("current"), dir);
        assertNull(CorpusRelease.resolve(root),
                   "an incompatible release must fall back, never be opened");
    }

    @Test
    @DisplayName("no release, or a broken one, falls back rather than failing")
    void missingOrBrokenReleaseIsNull(@TempDir Path root) throws Exception {
        assertNull(CorpusRelease.resolve(root), "no pointer means no local corpus");

        Path dir = root.resolve("releases/rel-C");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"),
            "{\"release\":\"rel-C\",\"index\":\"index.duckdb\",\"payload\":\"payload.parquet\"}");
        // Manifest present, files absent: a half-published release must not be used.
        Files.createSymbolicLink(root.resolve("current"), dir);
        assertNull(CorpusRelease.resolve(root),
                   "a manifest without its files is not a usable release");
    }
}
