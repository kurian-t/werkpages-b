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
public record CorpusRelease(String id, Path indexPath, Path payloadPath, String remoteBase,
                            String country) {

    /** A release on local disk, which is every release the published pipeline produces today. */
    public CorpusRelease(String id, Path indexPath, Path payloadPath) {
        this(id, indexPath, payloadPath, null, null);
    }

    /**
     * A release read straight from object storage, for measuring whether that is viable.
     *
     * <p>DuckDB can ATTACH a database file over httpfs read-only and fetch ranges rather than the
     * whole file, so a local copy is not the only option. Whether it is fast ENOUGH is a different
     * question: an index does many small random reads where a Parquet scan does few large
     * sequential ones, and every one of those reads pays a round trip. This exists so that
     * question can be answered with a measurement instead of an assumption.
     */
    public static CorpusRelease remote(String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        return new CorpusRelease(base, null, null, base, null);
    }

    public boolean isRemote() { return remoteBase != null; }

    /** Where the payload lives, as the SQL needs to name it. */
    public String payloadLocation() {
        return isRemote() ? remoteBase + "payload.parquet" : payloadPath.toAbsolutePath().toString();
    }

    /** Where the index lives, as ATTACH needs to name it. */
    public String indexLocation() {
        return isRemote() ? remoteBase + "index.duckdb" : indexPath.toAbsolutePath().toString();
    }


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
    /**
     * 6 added the geography alignment tables: region_alias voted out of the data rather than
     * derived from the geography corpus's own region codes, plus city_alias and geo_place. A
     * schema-5 index cannot serve the current reader, because its region_alias maps a region name
     * to the wrong code outside the United States and it has no city aliases at all.
     */
    public static final int SCHEMA = 6;

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
            /*
              The COUNTRY comes from the manifest, never from the directory name.

              A single-country root is told apart from a routed one by which level holds the
              "current" pointer, and in the single-country case the directory it lives in may be
              named anything at all - the fixture releases in the tests are not named "US". Reading
              it from the manifest is the only way to know what an index actually holds.
            */
            return new CorpusRelease(m.getString("release", real.getFileName().toString()),
                                     index, payload, null, m.getString("country"));
        } catch (Exception e) {
            // An unreadable release is the same as no release: use the remote path and say so.
            System.err.println("local corpus release unreadable, falling back to remote: " + e);
            return null;
        }
    }

    /**
     * The JDBC URL to open.
     *
     * <p>A remote release opens an IN-MEMORY database and attaches the object-store file to it,
     * because a jdbc:duckdb: URL cannot name an s3:// path directly.
     */
    public String jdbcUrl() {
        return isRemote() ? "jdbc:duckdb:" : "jdbc:duckdb:" + indexPath.toAbsolutePath();
    }
}
