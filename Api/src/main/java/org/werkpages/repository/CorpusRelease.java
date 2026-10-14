package org.werkpages.repository;

import io.vertx.core.json.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One immutable corpus release, resolved once and held for the whole search.
 *
 * <h2>Why resolving once matters</h2>
 *
 * <p>The pointer is swapped atomically when a new release is published, but atomic swap only makes
 * each <em>resolution</em> consistent, not a pair of them. Opening {@code current/index.duckdb} and
 * later reading {@code current/payload.parquet} can straddle a swap and pair an index with a
 * payload from a different build. The place ids are assigned per build, so the result is not a
 * missing row, it is the WRONG business returned under the right-looking id. That exact mismatch
 * has already happened here once, from a different cause, and returned a San Antonio grotto for a
 * search for a Subaru dealership.
 *
 * <p>So the symlink is resolved to its real path once, and both files are addressed through that
 * one resolved path for the lifetime of the search. A release that is replaced while somebody is
 * reading it stays on disk: publishing adds a directory and moves a pointer, it never deletes.
 */
public record CorpusRelease(String id, Path indexPath, Path payloadPath) {

    /**
     * Resolves the current release, or null when none is published.
     *
     * <p>Null rather than an exception: a backend with no local corpus is a supported state, and it
     * falls back to the remote path rather than failing to start.
     */
    /**
     * The release layout this code can read.
     *
     * <p>Bumped whenever the query path starts depending on something a previous release does not
     * contain: the per-field {@code tok_freq}, the {@code region_alias} table, the separate
     * {@code meta.parquet}. An older release then falls back to the remote path instead of being
     * opened and quietly answering from a layout it no longer matches.
     */
    public static final int SCHEMA = 5;

    public static CorpusRelease resolve(Path root) {
        try {
            Path pointer = root.resolve("current");
            if (!Files.exists(pointer)) return null;
            // ONE resolution. Everything below is addressed from this immutable path.
            Path real = pointer.toRealPath();
            Path manifest = real.resolve("manifest.json");
            if (!Files.exists(manifest)) return null;
            JsonObject m = new JsonObject(Files.readString(manifest));
            /*
              A release built for an older query implementation must not be opened by a newer one.
              The schema number is the contract: when the query path starts requiring a table or a
              column that older releases do not have, this is what stops it reading them and
              returning wrong answers rather than failing.
            */
            int schema = m.getInteger("schema", 1);
            if (schema != SCHEMA) {
                System.err.println("local corpus release " + m.getString("release")
                    + " has schema " + schema + ", this build requires " + SCHEMA
                    + "; falling back to remote");
                return null;
            }
            Path index   = real.resolve(m.getString("index",   "index.duckdb"));
            Path payload = real.resolve(m.getString("payload", "payload.parquet"));
            // Every file named by the manifest must exist before the release is usable: a
            // half-published release must never be opened. meta is a table inside the index from
            // schema 5 onward, so there is no separate file to check.
            if (!Files.exists(index) || !Files.exists(payload)) return null;
            return new CorpusRelease(m.getString("release", real.getFileName().toString()),
                                     index, payload);
        } catch (Exception e) {
            // An unreadable release is the same as no release: use the remote path and say so.
            System.err.println("local corpus release unreadable, falling back to remote: " + e);
            return null;
        }
    }

    public String jdbcUrl() { return "jdbc:duckdb:" + indexPath.toAbsolutePath(); }
}
