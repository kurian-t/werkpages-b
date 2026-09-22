package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads the location corpus: partitioned Parquet in S3, queried in process through DuckDB.
 *
 * <h2>Why this is not in Postgres</h2>
 *
 * The corpus is 73.6M places and 5.2M localities — roughly 13GB. Loading that into the shared
 * production database would cost about 35GB on a 20GB volume, to hold reference data that is not
 * ours and that no RateMyManagers or Werkpages write ever touches. It is read-only, replaceable,
 * and rebuilt from upstream on demand, so it lives where that shape belongs.
 *
 * <p>What <em>does</em> live in Postgres is {@code company_locations}: the buildings somebody has
 * actually selected. This class offers candidates; {@link CompanyLocationRepository} owns the ones
 * that were chosen. A corpus row is a suggestion, never a fact about a company.
 *
 * <h2>Connection lifetime</h2>
 *
 * One connection, held open. Measured against this corpus: a cold query in a fresh JVM costs about
 * 880ms, while a warm connection is far cheaper — DuckDB keeps httpfs metadata and Parquet footers
 * cached per connection, and throwing that away per request would make every keystroke pay the
 * cold price.
 *
 * <h2>Memory and threads are capped together, or neither is capped</h2>
 *
 * DuckDB claims a large share of available RAM by default — right for an analytics box, wrong here:
 * the host is a t3.medium with <b>4GB total</b>, already running both brands' backends and their
 * nginx containers, each API container limited to 1GiB.
 *
 * <p>But a memory limit alone is not portable, because <b>DuckDB sizes its per-worker buffers by
 * thread count</b>. A 256MB cap ran fine in theory and died in practice on a 32-core developer
 * machine, which spawned 32 workers into that budget:
 *
 * <pre>Out of Memory Error: failed to allocate data of size 4.0 MiB (240.3 MiB/244.1 MiB used)</pre>
 *
 * The same limit would have been ample on two vCPUs. So {@code threads} is pinned to the production
 * vCPU count and the limit raised to 512MB, which makes the footprint a property of the
 * configuration rather than of whoever's machine it happens to be running on.
 * {@code preserve_insertion_order=false} lets a large scan spill instead of failing outright.
 *
 * <p>Override with {@code LOCATION_CORPUS_MEMORY} and {@code LOCATION_CORPUS_THREADS}. When
 * RateMyManagers runs the same feature there are two engines on that 4GB box, so treat the number
 * as a shared budget rather than a private one.
 *
 * <h2>Failure is silent</h2>
 *
 * Every method returns empty rather than failing. A suggestion list that cannot load must never
 * stop somebody filling in a form — they can still type a city and submit coarse geography. The
 * corpus makes the field better; it is not allowed to make the field mandatory.
 */
public class LocationCorpusRepository {

    private static final Logger LOG = Logger.getLogger(LocationCorpusRepository.class.getName());

    /** Suggestions per tier. Small: this is a dropdown, not a search results page. */
    private static final int LIMIT = 8;

    private final String bucket;
    private final String region;
    private final String memoryLimit;

    /**
     * Worker threads for the engine.
     *
     * <p>Two, matching the production vCPU count, so the memory footprint is the same on a
     * developer machine as in the container. Left uncapped, DuckDB uses one worker per core and a
     * 32-core machine exhausts a limit that is ample in production — which is exactly how a 256MB
     * cap that looked fine turned into "no location suggestions, no error, no clue".
     */
    private static final int threads = intFromEnv("LOCATION_CORPUS_THREADS", 2);

    private static int intFromEnv(String key, int fallback) {
        String configured = System.getenv(key);
        if (configured == null || configured.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(configured.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * The threads the blocking JDBC work runs on, off the Vert.x event loop.
     *
     * <p>A single S3 round trip on a loop thread would stall every other request sharing it, so
     * none of this may touch one.
     *
     * <p><b>Why more than one.</b> This was a single thread, which quietly made the two halves of
     * a suggestion — geography and places — run one after the other even though the caller
     * submits them together and waits on both. Measured against the live corpus: geography ~368ms,
     * places ~406ms, and a cold suggestion ~774ms, which is their sum rather than their maximum.
     * They are independent reads of different files; the serialisation bought nothing and cost
     * every keystroke the slower query twice over.
     *
     * <p>Three, not two: the two halves of a suggestion, plus room for the one-off region lookup
     * behind {@link #regionWords} without it queueing in front of a person's keystroke.
     *
     * <p>Daemon, so they can never hold the JVM open at shutdown.
     */
    private final ExecutorService worker = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "location-corpus");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * One DuckDB connection per worker thread, all onto the same in-process database.
     *
     * <p>A {@code DuckDBConnection} is not safe to use from two threads at once — that is what the
     * single worker was really protecting — so concurrency needs a connection each rather than a
     * shared handle. {@code duplicate()} is DuckDB's own way to get one: same database, same
     * loaded extensions, same secret, separate handle.
     *
     * <p>If duplication is unavailable for any reason the shared connection is used instead, which
     * is exactly the behaviour that existed before. Slower, never broken.
     */
    private final ThreadLocal<Connection> perThread = new ThreadLocal<>();

    private volatile Connection connection;
    private volatile String release;
    private volatile boolean unavailable;

    public LocationCorpusRepository() {
        this(env("LOCATION_CORPUS_BUCKET", "location-corpus"),
             env("LOCATION_CORPUS_REGION", "ca-central-1"),
             env("LOCATION_CORPUS_MEMORY", "512MB"));
    }

    public LocationCorpusRepository(String bucket, String region, String memoryLimit) {
        this.bucket      = bucket;
        this.region      = region;
        this.memoryLimit = memoryLimit;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }


    /**
     * Recent answers, keyed by the exact question.
     *
     * <p>A typeahead asks the same thing repeatedly — "tor", "toro", "toron" — and backspacing
     * walks straight back through queries just answered. Each miss is a read of Parquet on S3,
     * which is the whole cost of this control; each hit is free.
     *
     * <p>Small and short-lived on purpose. The corpus changes when a release is published, not
     * between keystrokes, so a minute of staleness is invisible; and a cache large enough to
     * matter for memory would defeat the reason the corpus is not in Postgres.
     */
    private static final long   CACHE_TTL_MS  = 60_000;
    private static final int    CACHE_MAX     = 256;
    private final java.util.Map<String, CacheEntry> cache =
        java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, CacheEntry> eldest) {
                return size() > CACHE_MAX;
            }
        });

    private record CacheEntry(JsonArray value, long expiresAt) {}

    private Future<JsonArray> cached(String key, java.util.function.Supplier<Future<JsonArray>> compute) {
        CacheEntry hit = cache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && hit.expiresAt() > now) return Future.succeededFuture(hit.value());
        return compute.get().map(result -> {
            cache.put(key, new CacheEntry(result, now + CACHE_TTL_MS));
            return result;
        });
    }

    /**
     * Localities, regions and countries matching {@code query}.
     *
     * <p>Ranked exact name, then prefix, then contains, with population breaking ties — so
     * "waterloo" offers Waterloo, Ontario (105k) above Waterloo, Nova Scotia (0), and typing
     * "lon" reaches London before Longueuil.
     *
     * @param country ISO alpha-2 to search within; required, because the picker always knows it
     */
    public Future<JsonArray> suggestGeography(String query, String country, String state) {
        String needle = normalise(query);
        String iso = iso(country);
        if (needle == null || iso == null) return Future.succeededFuture(new JsonArray());

        // The partition already guarantees the country, and neither search_text contains it.
        java.util.List<String> words = withoutCountryWord(tokens(needle), country);

        return cached("geo|" + iso + "|" + blankToNull(state) + "|" + needle, () ->
            query(release -> """
                SELECT name, kind, state_name, state_code
                FROM read_parquet('s3://%s/processed/%s/geography/country_code=%s/*.parquet')
                WHERE %s
                  AND (? IS NULL OR state_code = ? OR kind = 'country')
                ORDER BY CASE WHEN lower(name) = ?      THEN 0
                              WHEN lower(name) LIKE ? THEN 1
                              ELSE 2 END,
                         population DESC NULLS LAST
                LIMIT %d
                """.formatted(bucket, release, partition(iso), allTokensClause(words.size()), LIMIT),
            statement -> {
                int i = 1;
                for (String word : words) statement.setString(i++, "%" + word + "%");
                statement.setString(i++, blankToNull(state));
                statement.setString(i++, blankToNull(state));
                statement.setString(i++, needle);
                statement.setString(i, needle + "%");
            },
            rs -> new JsonObject()
                .put("kind", "geo")
                .put("name", rs.getString("name"))
                .put("geoKind", rs.getString("kind"))
                .put("stateName", rs.getString("state_name"))
                .put("stateCode", rs.getString("state_code"))));
    }

    /**
     * Buildings matching {@code query}, preferring this company's own.
     *
     * <p>Two rules decide whether this list is usable at all.
     *
     * <p><b>Brand beats name.</b> Searching "walmart" must surface Walmart branches, not a corner
     * shop whose street happens to contain the word.
     *
     * <p><b>A branch outranks the services inside it.</b> Overture lists the pharmacy, photo centre
     * and garden centre at a supermarket as separate places. They are distinguishable because only
     * the parent carries {@code brand_name} — the sub-locations have none — so a present brand
     * sorts first and the same address is collapsed to one row. Without this, "Walmart Kitchener"
     * returns eight entries for three actual stores.
     */
    public Future<JsonArray> suggestPlaces(String companyName, String query, String country) {
        String needle = normalise(query);
        String iso = iso(country);
        if (needle == null || iso == null) return Future.succeededFuture(new JsonArray());

        String brand = normalise(companyName);
        /*
          Resolved inside the worker, not here.

          A place's search_text is name, brand, street and city - never the region, never the
          country - so forPlaces strips both, and the first call for a country reads the region
          names from S3. This method runs on the event loop; doing that here would block it,
          which is the one thing this whole class is arranged to prevent. Both lambdas below
          execute on the worker, and the region names are memoised, so asking twice is free.
        */
        java.util.function.Supplier<java.util.List<String>> words =
            () -> forPlaces(tokens(needle), country, iso);

        return cached("place|" + iso + "|" + brand + "|" + needle, () ->
            query(release -> """
                SELECT source_place_id, name, brand_name, street, city, state_code, postal_code
                FROM (
                    -- One row per BUILDING, not per street string. Overture lists the pharmacy,
                    -- photo centre, hair salon and grocery pickup inside a supermarket as separate
                    -- places, each with its own free-form address:
                    --
                    --   1400 Ottawa St S Fischer-Hallman Rd & Ottawa St S   the store
                    --   1400 Ottawa Street South                            photo centre
                    --   1400 Ottawa St S Unit E, Located Inside Walmart     hair salon
                    --   Inside Walmart, 1400 Ottawa Street S                phone repair
                    --
                    -- Grouping on that text fails on all four. Postal code plus the first number
                    -- in the street identifies the building instead: the postal code alone is too
                    -- coarse outside Canada (a US ZIP covers many buildings), and the street number
                    -- alone repeats on every street in a city, but together they are specific.
                    -- The nine columns this needs, not every column there is.
                    --
                    -- This was SELECT *, which carries all fourteen - longitude, latitude,
                    -- category, country_code included - through the window operator's sort for
                    -- every matching row. Measured warm against the live corpus on the DuckDB
                    -- CLI, the same query ran in 1.105s that way and 0.202s with the list written
                    -- out: the extra columns are read from S3 and sorted for nothing, since the
                    -- outer query discards them.
                    --
                    -- End to end through the API this did not move the number, because the
                    -- geography half of a suggestion costs about the same and the two now run
                    -- together. It is kept for being less work rather than for a win it did not
                    -- deliver here.
                    SELECT source_place_id, name, brand_name, street, city, state_code,
                           postal_code, confidence, operating_status,
                           row_number() OVER (
                               PARTITION BY coalesce(upper(replace(postal_code, ' ', '')), '?'),
                                            coalesce(regexp_extract(street, '\\d+'), name)
                               ORDER BY CASE WHEN brand_name IS NOT NULL THEN 0 ELSE 1 END,
                                        confidence DESC NULLS LAST) AS at_address
                    FROM read_parquet('s3://%s/processed/%s/places/country_code=%s/*.parquet')
                    WHERE %s
                      AND coalesce(operating_status, 'open') <> 'closed'
                )
                WHERE at_address = 1
                ORDER BY CASE WHEN ? IS NOT NULL AND lower(coalesce(brand_name,'')) = ? THEN 0
                              WHEN brand_name IS NOT NULL                                THEN 1
                              ELSE 2 END,
                         confidence DESC NULLS LAST
                LIMIT %d
                """.formatted(bucket, release, partition(iso), allTokensClause(words.get().size()), LIMIT),
            statement -> {
                int i = 1;
                for (String word : words.get()) statement.setString(i++, "%" + word + "%");
                statement.setString(i++, brand);
                statement.setString(i, brand);
            },
            rs -> new JsonObject()
                .put("kind", "place")
                .put("sourcePlaceId", rs.getString("source_place_id"))
                .put("name", rs.getString("name"))
                .put("brandName", rs.getString("brand_name"))
                .put("street", rs.getString("street"))
                .put("city", rs.getString("city"))
                .put("stateCode", rs.getString("state_code"))
                .put("postalCode", rs.getString("postal_code"))));
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private interface Binder { void bind(PreparedStatement statement) throws SQLException; }

    private interface RowMapper { JsonObject map(ResultSet rs) throws SQLException; }

    private interface SqlFor { String build(String release); }

    /**
     * Runs one query off the event loop.
     *
     * <p>DuckDB's JDBC driver is blocking and reads over the network, so it must never touch a
     * Vert.x event loop thread — a single S3 round trip would stall every other request on that
     * loop.
     */
    private Future<JsonArray> query(SqlFor sqlFor, Binder binder, RowMapper mapper) {
        Promise<JsonArray> promise = Promise.promise();
        worker.execute(() -> {
            try {
                Connection conn = threadConnection();
                if (conn == null) {
                    promise.complete(new JsonArray());
                    return;
                }
                JsonArray out = new JsonArray();
                try (PreparedStatement statement = conn.prepareStatement(sqlFor.build(release))) {
                    binder.bind(statement);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) out.add(mapper.map(rs));
                    }
                }
                promise.complete(out);
            } catch (Throwable err) {
                // Soft failure by design, and Throwable rather than Exception because a native
                // library problem arrives as an Error. No suggestions is worse than suggestions,
                // and far better than a form that will not submit.
                LOG.log(Level.WARNING, "location corpus query failed; returning no suggestions", err);
                promise.complete(new JsonArray());
            }
        });
        return promise.future();
    }

    /**
     * The shared connection, opened on first use.
     *
     * <p>Returns null once the corpus has been found unavailable, so a missing bucket or an
     * unpublished release costs one failed attempt rather than one per keystroke.
     */
    /**
     * This thread's own connection, duplicated from the shared one on first use.
     *
     * <p>Falls back to the shared connection when duplication is not possible. That reintroduces
     * the old serialisation - two threads contending on one handle - but a slower suggestion is
     * the correct trade against a broken one, and it is the behaviour this had all along.
     */
    private Connection threadConnection() {
        Connection mine = perThread.get();
        if (mine != null) return mine;

        Connection shared = connection();
        if (shared == null) return null;

        try {
            if (shared instanceof org.duckdb.DuckDBConnection duck) {
                Connection copy = duck.duplicate();
                perThread.set(copy);
                return copy;
            }
        } catch (Exception err) {
            LOG.log(Level.WARNING,
                "could not duplicate the corpus connection; queries will share one handle", err);
        }
        perThread.set(shared);
        return shared;
    }

    private synchronized Connection connection() {
        if (unavailable) return null;
        if (connection != null) return connection;

        try {
            Connection conn = DriverManager.getConnection("jdbc:duckdb:");
            try (Statement statement = conn.createStatement()) {
                statement.execute("INSTALL httpfs");
                statement.execute("LOAD httpfs");
                statement.execute("SET memory_limit='" + memoryLimit + "'");
                // Threads BEFORE memory matters: DuckDB sizes its per-worker buffers by thread
                // count, so an uncapped engine on a 32-core developer machine allocates far more
                // than the same engine on a 2-vCPU t3.medium - and blows the same limit that is
                // comfortable in production. Capping it makes the footprint a property of the
                // configuration rather than of whoever's laptop it happens to be running on.
                //
                // Two matches the production vCPU count. These queries read one country partition
                // with a LIMIT, so parallelism buys very little here.
                statement.execute("SET threads=" + threads);
                // Lets a large scan spill rather than fail. Without it the engine has nowhere to
                // go when the limit is reached and the query dies - which surfaced as location
                // search silently returning nothing.
                statement.execute("SET preserve_insertion_order=false");
            }
            /*
              Keep Parquet footers between queries.

              Every suggestion reads the same handful of files from S3, and without this the
              engine re-fetches each file's metadata on every keystroke - a round trip to
              ca-central-1 before a single row is read.

              Applied one at a time and tolerantly: these are engine tuning knobs whose names have
              moved between DuckDB versions, and an unrecognised SET throws. Letting that escape
              would abort the whole setup and disable location search to save a few milliseconds,
              which is the wrong way round.
            */
            for (String tuning : new String[] {
                    "SET enable_object_cache=true",
                    "SET enable_http_metadata_cache=true",
                    /*
                      Keep the file data, not just its footer.

                      On the DuckDB 1.5.5 CLI this is worth a great deal: four consecutive
                      searches over the Canadian places partition took 1.108s, 1.134s, 1.112s and
                      1.113s with it off, and 1.136s then 0.180s, 0.166s, 0.160s with it on.

                      On the version pinned here (duckdb_jdbc 1.3.1.0) the setting is recognised -
                      the name is in the jar - but made no measurable difference end to end: a
                      novel query stayed at roughly 0.38-0.43s either way. It is kept because it
                      is free and correct, NOT because it has been shown to help on this driver.
                      The gap between the two versions is the open question, and closing it means
                      a dependency upgrade, which is not a decision to take quietly.

                      Bounded by memory_limit, so it evicts rather than growing without limit -
                      which is why that limit is set deliberately above.
                    */
                    "SET enable_external_file_cache=true",
                    /*
                      How long a broken corpus takes to admit it.

                      DuckDB defaults to http_retries=3 with a 30s http_timeout, so a bucket that
                      never answers costs about ninety seconds before the first suggestion gives
                      up - on the request of whoever happened to type first. The guarantee this
                      class makes is that a corpus it cannot reach degrades to no suggestions;
                      ninety seconds of nothing is not degrading gracefully, it is hanging.

                      Ten seconds is far above any healthy round trip to the bucket, which sits in
                      the same region, so this bounds the failure path without touching the
                      working one.
                    */
                    "SET http_retries=1",
                    "SET http_timeout=10000" }) {
                try (Statement statement = conn.createStatement()) {
                    statement.execute(tuning);
                } catch (SQLException unsupported) {
                    LOG.fine(() -> "corpus engine did not accept: " + tuning);
                }
            }
            try (Statement statement = conn.createStatement()) {
                // credential_chain, never keys in configuration: the instance role in production,
                // ~/.aws/credentials locally. Identical to how SecretsConfig authenticates.
                statement.execute("CREATE OR REPLACE SECRET corpus (TYPE s3, "
                    + "PROVIDER credential_chain, REGION '" + region + "', "
                    + "SCOPE 's3://" + bucket + "')");
            }
            String active = activeRelease(conn);
            if (active == null) {
                LOG.warning("location corpus has no published release; suggestions disabled");
                unavailable = true;
                conn.close();
                return null;
            }
            release = active;
            connection = conn;
            LOG.info("location corpus ready, release " + active);
            return conn;
        } catch (UnsatisfiedLinkError | NoClassDefFoundError err) {
            // Named separately because these are Errors, not Exceptions, and because the cause is
            // never obvious from the stack trace. The shaded jar carries ONE DuckDB native -
            // linux/amd64, matching the t3.medium the containers run on (see the shade filter in
            // RestApi/pom.xml). Moving the host to Graviton, or building the image for arm64,
            // deletes the engine this needs while everything else keeps working.
            LOG.log(Level.SEVERE,
                "DuckDB native library will not load on this architecture (" + System.getProperty("os.arch")
                + "). The shaded jar ships only linux/amd64; see the maven-shade-plugin filter in "
                + "RestApi/pom.xml. Location suggestions are disabled.", err);
            unavailable = true;
            return null;
        } catch (Exception err) {
            LOG.log(Level.WARNING, "location corpus unavailable; suggestions disabled", err);
            unavailable = true;
            return null;
        }
    }

    /**
     * Which release to serve, from the pointer at the bucket root.
     *
     * <p>Read once per connection rather than per query. Publishing a new corpus is a deliberate,
     * rare act; re-reading the pointer on every keystroke would spend an S3 request to learn
     * something that changes twice a year.
     */
    private String activeRelease(Connection conn) throws SQLException {
        String sql = "SELECT release FROM read_json_auto('s3://" + bucket + "/current.json')";
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) return null;
            String value = rs.getString(1);
            return (value == null || value.isBlank() || "none".equals(value)) ? null : value;
        }
    }

    /** Lower-cased and trimmed, or null when there is nothing worth searching for. */
    private static String normalise(String value) {
        if (value == null) return null;
        String trimmed = value.trim().toLowerCase();
        return trimmed.length() < 2 ? null : trimmed;
    }

    /**
     * The words of a query, each of which must appear somewhere in {@code search_text}.
     *
     * <p>Searching the whole phrase as one substring does not work here. {@code search_text} is
     * name, brand, street and city concatenated, so the Walmart on Kingsway reads
     * {@code "walmart walmart 2960 kingsway dr kitchener"} — and "walmart kitchener", the most
     * natural thing anybody would type, appears nowhere in it as a contiguous string. Matching
     * each word separately is what makes "walmart kitchener", "kitchener walmart" and
     * "walmart 2960" all find the same store.
     *
     * <p><b>A comma is a separator, not a letter.</b> Splitting on whitespace alone turned
     * "Toronto, Ontario" into the tokens {@code "toronto,"} and {@code "ontario"}, and
     * {@code search_text} holds no commas — so the first token matched nothing and the city
     * vanished from the list, while "toronto ontario" typed without punctuation worked perfectly.
     *
     * <p>That is the way people actually write a location, and worse, it is the way this control
     * writes it back to them: the field collapses to "Toronto, Ontario, Canada" and the editor
     * reopens holding exactly that string. Typing one more character into a value we produced
     * ourselves emptied the list.
     */
    static java.util.List<String> tokens(String needle) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String token : needle.split("[\\s,]+")) {
            if (!token.isBlank()) out.add(token);
        }
        return out;
    }

    /**
     * Region names for one country, remembered for the life of the process.
     *
     * <p>Thirteen strings for Canada, fifty for the United States, read once from the geography
     * partition the search already uses. They do not change between releases in any way that
     * matters to a substring test.
     */
    private final java.util.Map<String, java.util.Set<String>> regionWords =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The lower-cased region names of a country, or an empty set when they cannot be read.
     *
     * <p>Failure is silent and cached as empty, in keeping with the rest of this class: not
     * knowing the provinces costs a slightly worse place search, never an error.
     */
    private java.util.Set<String> regionsOf(String iso) {
        return regionWords.computeIfAbsent(iso, code -> {
            try {
                Connection conn = threadConnection();
                if (conn == null) return java.util.Set.of();
                java.util.Set<String> names = new java.util.HashSet<>();
                String sql = ("SELECT DISTINCT lower(name) AS name FROM read_parquet("
                    + "'s3://%s/processed/%s/geography/country_code=%s/*.parquet') WHERE kind = 'region'")
                    .formatted(bucket, release, code);
                try (Statement statement = conn.createStatement();
                     ResultSet rs = statement.executeQuery(sql)) {
                    while (rs.next()) {
                        for (String word : rs.getString("name").split("[\\s,]+")) {
                            if (!word.isBlank()) names.add(word);
                        }
                    }
                }
                return names;
            } catch (Throwable err) {
                LOG.log(Level.WARNING, "could not read region names for " + code, err);
                return java.util.Set.of();
            }
        });
    }

    /**
     * The words of a query with the geography qualifiers removed, for the <em>places</em> search.
     *
     * <p>A place's {@code search_text} is name, brand, street and city — it holds no region and no
     * country. A Google office reads
     * {@code "google google 65 king st e, toronto, on m5c 1g3, canada toronto"}: the street says
     * "ON", never "Ontario". So requiring every word of "Google Toronto, Ontario, Canada" to
     * appear found nothing at all, while "Google Toronto" found five offices — the more complete
     * and more careful the person was, the worse the answer they got.
     *
     * <p>Both words are honoured rather than ignored: the country picks the partition that is
     * read, and the city is matched as a word. Dropping them is what makes them qualifiers
     * instead of substrings.
     *
     * <p>Geography keeps its region word, and must: {@code "toronto ontario"} is exactly what
     * distinguishes Toronto, Ontario from Toronto, Prince Edward Island.
     */
    private java.util.List<String> forPlaces(java.util.List<String> words, String country, String iso) {
        return withoutQualifiers(words, country, regionsOf(iso));
    }

    /**
     * The filtering itself, separated from where the region names come from so it can be tested
     * without a corpus to read them out of.
     *
     * <p>Falls back to keeping the region words when removing them would leave nothing. Somebody
     * who typed only "Ontario" is asking about Ontario, and a query with no words at all would
     * ask for everything.
     */
    static java.util.List<String> withoutQualifiers(java.util.List<String> words, String country,
                                                    java.util.Set<String> regions) {
        java.util.List<String> withoutCountry = withoutCountryWord(words, country);
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (String word : withoutCountry) {
            if (!regions.contains(word)) kept.add(word);
        }
        return kept.isEmpty() ? withoutCountry : kept;
    }

    /**
     * The same words, minus the one naming the country already being searched.
     *
     * <p>Both datasets are partitioned by country and the query reads one partition, so every row
     * it can return is in that country already. The country word therefore carries no information
     * — and it is not in either {@code search_text}: a locality's is
     * {@code "toronto ontario"} and a place's is name, brand, street and city. Requiring it to
     * match meant "Toronto, Ontario, Canada" — the complete, natural thing to type, and the exact
     * string this field shows — returned nothing at all.
     *
     * <p>Dropped only when something else remains. Somebody who typed just "Canada" is asking for
     * the country, and the country row is matched on its own name.
     */
    static java.util.List<String> withoutCountryWord(java.util.List<String> words, String country) {
        if (country == null || country.isBlank()) return words;
        // Word by word, so a country written in several - "United States", "United Kingdom" -
        // loses all of its words rather than the first one.
        java.util.Set<String> countryWords = new java.util.HashSet<>(
            java.util.Arrays.asList(country.trim().toLowerCase().split("[\\s,]+")));
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (String word : words) {
            if (!countryWords.contains(word)) kept.add(word);
        }
        return kept.isEmpty() ? words : kept;
    }

    /** {@code search_text LIKE ? AND search_text LIKE ?…}, one per token. */
    private static String allTokensClause(int count) {
        return String.join(" AND ", java.util.Collections.nCopies(count, "search_text LIKE ?"));
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * The ISO alpha-2 code for a country given either its code or its display name, or null.
     *
     * <p>Both forms arrive in practice. The corpus partitions on {@code CA}, while the forms hold
     * what a contributor confirmed — {@code "Canada"} — because that is what gets stored in
     * {@code declared_country} and shown back to them.
     *
     * <p>Resolved through {@link java.util.Locale} rather than a hand-kept table, which would be
     * one more list to drift out of date. A name the JDK does not recognise yields null, and the
     * caller then offers no corpus suggestions — the same soft failure as an unreachable bucket,
     * rather than an error on a form that is otherwise fine.
     */
    public static String iso(String country) {
        if (country == null) return null;
        String value = country.trim();
        if (value.isEmpty()) return null;

        if (value.length() == 2 && value.matches("[A-Za-z]{2}")) {
            return value.toUpperCase();
        }
        for (String code : java.util.Locale.getISOCountries()) {
            if (java.util.Locale.of("", code).getDisplayCountry(java.util.Locale.ENGLISH)
                    .equalsIgnoreCase(value)) {
                return code;
            }
        }
        return null;
    }

    /**
     * A country code safe to splice into a partition path.
     *
     * <p>This value reaches SQL by concatenation because a Hive partition path cannot be a bind
     * parameter, so it is re-checked here rather than trusted: two letters, nothing else.
     */
    private static String partition(String isoCode) {
        if (isoCode == null || !isoCode.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("country partition must be ISO alpha-2: " + isoCode);
        }
        return isoCode;
    }
}
