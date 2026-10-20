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

    /**
     * Whether the active release's {@code search_text} is sorted, from its own manifest.
     *
     * <p>Decides whether the anchored predicate is used. Defaults to false so a release that says
     * nothing behaves exactly as it does today. See {@link #anchoredTokensClause} for the numbers.
     */
    private volatile boolean searchTextSorted = false;
    private volatile String release;
    private volatile boolean unavailable;

    public LocationCorpusRepository() {
        this(env("LOCATION_CORPUS_BUCKET", "location-corpus"),
             env("LOCATION_CORPUS_REGION", "ca-central-1"),
             env("LOCATION_CORPUS_MEMORY", "512MB"));
    }

    public LocationCorpusRepository(String bucket, String region, String memoryLimit) {
        this(bucket, region, memoryLimit, env("LOCATION_GEOGRAPHY_INDEX", ""));
    }

    /**
     * @param geographyIndexPath the published SQLite geography index, or blank for none.
     *
     * <p>Explicit rather than read from the environment here, so a test can exercise the indexed
     * path without the process having been started with it set.
     */
    public LocationCorpusRepository(String bucket, String region, String memoryLimit,
                                    String geographyIndexPath) {
        this(bucket, region, memoryLimit, geographyIndexPath,
             env("LOCATION_ROUTING_INDEX", ""), env("LOCATION_ADDRESS_INDEX_DIR", ""));
    }

    /** Every index path explicit, so a test can exercise the wired paths without the environment. */
    public LocationCorpusRepository(String bucket, String region, String memoryLimit,
                                    String geographyIndexPath, String routingIndexPath,
                                    String addressIndexDir) {
        this.bucket      = bucket;
        this.region      = region;
        this.memoryLimit = memoryLimit;

        /*
          THE SQLITE INDEXES, when a release has been published to this host.

          Optional on purpose. Absent, every lookup falls through to the DuckDB path exactly as
          before, so a host without the artifacts keeps working rather than returning nothing -
          an index that is missing and a place that does not exist must never look the same.

          Sized from the SAME three-thread worker this class already uses for blocking work, so
          no second execution model is introduced and the connection ceiling matches the number of
          threads that can actually be inside a query.
        */
        String geoPath = geographyIndexPath == null ? "" : geographyIndexPath;
        if (!geoPath.isBlank() && java.nio.file.Files.exists(java.nio.file.Path.of(geoPath))) {
            this.indexPool    = ConnectionPool.forWorkerPool(WORKER_THREADS);
            this.geographyIdx = new GeographyIndex(geoPath);
            LOG.info("geography index active: " + geoPath);
        } else {
            this.indexPool    = null;
            this.geographyIdx = null;
            if (!geoPath.isBlank()) LOG.warning("LOCATION_GEOGRAPHY_INDEX set but missing: " + geoPath);
        }


        /*
          ROUTING decides which country partitions are worth opening. Optional: without it the
          existing behaviour is unchanged.
        */
        String routePath = routingIndexPath == null ? "" : routingIndexPath;
        this.routingIdx = (!routePath.isBlank() && indexPool != null
                           && java.nio.file.Files.exists(java.nio.file.Path.of(routePath)))
            ? new RoutingIndex(routePath, indexPool) : null;
        if (routingIdx != null) LOG.info("routing index active: " + routePath);

        /*
          ADDRESSES are per country, named <CC>.sqlite in one directory.

          Only the countries whose artifact is actually present are loaded, and the map is the
          single source of truth for coverage: routing may well choose a country we hold no
          address index for, and that is a documented gap rather than a failure. V1 ships Canada.
        */
        java.util.Map<String, AddressIndex> addrs = new java.util.HashMap<>();
        String addrDir = addressIndexDir == null ? "" : addressIndexDir;
        if (!addrDir.isBlank() && indexPool != null) {
            java.nio.file.Path dir = java.nio.file.Path.of(addrDir);
            if (java.nio.file.Files.isDirectory(dir)) {
                try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
                    files.filter(f -> f.getFileName().toString().endsWith(".sqlite")).forEach(f -> {
                        String cc = f.getFileName().toString().replace(".sqlite", "").toUpperCase();
                        addrs.put(cc, new AddressIndex(f.toString(), cc, indexPool));
                    });
                } catch (java.io.IOException err) {
                    LOG.log(Level.WARNING, "listing address indexes", err);
                }
            }
        }
        this.addressIdx = java.util.Map.copyOf(addrs);
        if (!addrs.isEmpty()) LOG.info("address indexes active: " + addrs.keySet());
    }

    /**
     * Geography from the SQLite index, on the existing worker.
     *
     * <p>Runs on {@code worker}, never on an event loop: SQLite calls block, and a blocked event
     * loop stalls every request on that thread rather than only this one.
     *
     * <p>Alias and prefix are both consulted, because a reader types either. The country scope is
     * applied only when the QUERY named one - a visitor's own country is a ranking preference
     * upstream, never a filter here, which is the defect that once made a field holding "London,
     * Ontario" unable to reach the United Kingdom.
     */
    /**
     * How much an exact name match is worth against a guess, in units of prominence.
     *
     * <p>0.25 on the 0-1 importance scale is roughly the gap between a village and a small town:
     * enough that a real match always beats a guess of similar standing, not so much that a
     * hamlet outranks a capital.
     */
    private static final double EXACT_MATCH_BONUS = 0.25;

    /** Prominence, plus explicit credit for having actually matched what was typed. */
    private static double rank(GeographyIndex.Hit h, String needle) {
        boolean exact = h.editDistance() == 0 && needle.equals(h.norm());
        return h.importance() + (exact ? EXACT_MATCH_BONUS : 0.0);
    }

    /** Set by the lookup so a test can prove it ran off the caller's thread. */
    volatile String lastIndexLookupThread;

    private Future<JsonArray> geographyFromIndex(String rawQuery, String iso, String state) {
        /*
          THE SHARED FOLD, not this class's own normalise().

          normalise() only trims and lowercases, so "U.S.A." stayed "u.s.a." and never reached the
          alias "usa". The index was built with the fold in GeographyIndex, and a query that
          normalises differently from its index cannot match it - which is the entire class of bug
          the equivalence test exists to catch.
        */
        final String needle = GeographyIndex.fold(rawQuery);
        Promise<JsonArray> promise = Promise.promise();
        worker.execute(() -> {
            lastIndexLookupThread = Thread.currentThread().getName();
            try {
                java.util.List<GeographyIndex.Hit> hits = geographyIdx.byAlias(needle, iso, 10);
                /*
                  An initialism written with separators is the same word: "U.S.A." folds to
                  "u s a", which is a different key from "usa". Joining a run of single letters
                  covers U.K. and U.A.E. too, without an entry each.
                */
                if (hits.isEmpty() && needle.matches("(?:[a-z0-9] )+[a-z0-9]")) {
                    hits = geographyIdx.byAlias(needle.replace(" ", ""), iso, 10);
                }
                if (hits.isEmpty()) hits = geographyIdx.byPrefix(needle, iso, state, LIMIT);

                /*
                  A COUNTRY IS A PREFERENCE, NEVER A PRISON.

                  The scoped lookup above is right about ranking: a form holding Canada and a query
                  of "London" means London, Ontario, and it should lead. It is wrong as a wall.
                  "Tokyo" with Canada on the form returned NOTHING from geography, so the list led
                  with a Canadian restaurant called Tokyo Express and the city of 14 million was
                  not offered at all. Somebody recording a job in Tokyo could not say where it was.

                  So the scope orders the results instead of deciding them: whatever the country
                  held, first, and then the rest of the world fills the remaining seats. Nothing
                  that used to be offered stops being offered, and nothing that used to lead stops
                  leading - the list simply stops ending early.

                  Only when a scope was actually applied; an unscoped lookup has already seen
                  everything.
                */
                if (iso != null && hits.size() < LIMIT) {
                    java.util.List<GeographyIndex.Hit> worldwide =
                        geographyIdx.byPrefix(needle, null, blankToNull(state), LIMIT);
                    if (!worldwide.isEmpty()) {
                        java.util.Set<Long> already = new java.util.HashSet<>();
                        for (GeographyIndex.Hit h : hits) already.add(h.id());
                        java.util.List<GeographyIndex.Hit> widened = new java.util.ArrayList<>(hits);
                        for (GeographyIndex.Hit h : worldwide) {
                            if (widened.size() >= LIMIT) break;
                            if (already.add(h.id())) widened.add(h);
                        }
                        hits = widened;
                    }
                }
                /*
                  Typo recovery runs unless something SIGNIFICANT already matched the spelling as
                  typed. Gating it on an empty result meant two hamlets called Melborne suppressed
                  the correction to Melbourne, Australia: a misspelling that happens to be
                  somebody's place name switches the feature off.
                */
                boolean significant = hits.stream().anyMatch(h -> h.importance() >= 0.35);
                if (!significant) {
                    java.util.List<GeographyIndex.Hit> fuzzy =
                        geographyIdx.byTypo(needle, iso, state, 6);
                    if (!fuzzy.isEmpty()) {
                        /*
                          EXACT-MATCH EVIDENCE IS SCORED, NOT LEFT TO SORT STABILITY.

                          Appending and sorting by prominence alone does NOT keep an exact match
                          ahead of a guess: a stable sort only preserves order when the keys are
                          equal, so a more prominent fuzzy candidate could still displace a real
                          one. The match itself has to carry weight.

                          The bonus is deliberate rather than absolute. Making exact matches win
                          outright would defeat the feature: "Melborne" IS the exact name of two
                          hamlets, and the reader almost certainly meant Melbourne, Australia. So
                          an exact match outranks any guess of comparable standing, and only a far
                          more prominent destination can lead instead.
                        */
                        java.util.List<GeographyIndex.Hit> merged = new java.util.ArrayList<>(hits);
                        merged.addAll(fuzzy);
                        merged.sort((a, b) -> Double.compare(rank(b, needle), rank(a, needle)));
                        hits = merged;
                    }
                }
                /*
                  THE COUNTRY THE QUERY NAMED, ahead of everything and never scoped away.

                  Every lookup above applies the country as a filter, which is right for a place
                  name: somebody whose form says Canada and types "London" means London, Ontario.
                  It is wrong for a country NAME. "USA" names the United States, and the United
                  States is not in Canada, so the scoped lookups return nothing and a Canadian
                  visitor could not select it at all.

                  The DuckDB path this replaces never had that hole: it admits the named country by
                  its ISO CODE in a separate branch, because the corpus stores that row's search
                  text as "united states" and no token of "usa" can reach it either. Leaving the
                  equivalent out made the index strictly worse than what it replaced, on the single
                  most common country query there is.

                  Matched by identity rather than spelling, via the same Countries table the other
                  path uses, so "USA", "U.S.A.", "US" and "United States" all arrive at US - and
                  the row is deduplicated in case a scoped lookup already found it.
                */
                String spokenIso = Countries.iso(needle);
                if (spokenIso != null) {
                    java.util.Optional<GeographyIndex.Hit> named = geographyIdx.country(spokenIso);
                    if (named.isPresent()) {
                        java.util.List<GeographyIndex.Hit> withCountry = new java.util.ArrayList<>();
                        withCountry.add(named.get());
                        for (GeographyIndex.Hit h : hits) {
                            if (!("country".equals(h.kind()) && spokenIso.equals(h.countryCode()))) {
                                withCountry.add(h);
                            }
                        }
                        hits = withCountry;
                    }
                }

                JsonArray out = new JsonArray();
                for (GeographyIndex.Hit h : hits) {
                    out.add(new JsonObject()
                        .put("kind", "geo")
                        .put("name", h.name())
                        .put("geoKind", h.kind())
                        .put("stateName", h.stateName())
                        .put("stateCode", h.stateCode())
                        .put("countryCode", h.countryCode())
                        .put("population", h.population()));
                }
                promise.complete(out);
            } catch (Throwable err) {
                /*
                  Reported as a failure, never as an empty list. "The index is broken" and "no
                  such place" must not look the same to the caller, which is the mistake this
                  area has made repeatedly.
                */
                LOG.log(Level.WARNING, "geography index lookup failed", err);
                promise.fail(err);
            }
        });
        return promise.future();
    }

    private final RoutingIndex routingIdx;
    private final java.util.Map<String, AddressIndex> addressIdx;

    /** Countries with a published address index. Everything else is a documented coverage gap. */
    public java.util.Set<String> addressCoverage() { return addressIdx.keySet(); }

    public boolean routingIndexActive() { return routingIdx != null; }

    /**
     * Which country partitions to open, best first.
     *
     * <p>Falls back to the visitor's own country when no routing index is published, which is the
     * previous behaviour rather than an empty answer.
     */
    public java.util.List<String> routeCountries(String query, String visitor, int k) {
        if (routingIdx == null) {
            return visitor == null ? java.util.List.of() : java.util.List.of(visitor);
        }
        java.util.List<String> toks = new java.util.ArrayList<>();
        for (String t : GeographyIndex.fold(query).split(" ")) if (!t.isBlank()) toks.add(t);
        return routingIdx.route(toks, visitor, k);
    }

    /**
     * Street addresses for a query that carries a house number.
     *
     * <p>Emitted in the same RAW shape the corpus places path uses - name, street, city, country -
     * so they merge into the existing suggestion pipeline and render through the control the
     * frontend already has. No new suggestion kind, no client change.
     */
    public Future<JsonArray> suggestAddresses(String query, String visitor, int limit) {
        if (addressIdx.isEmpty() || query == null || query.isBlank()) {
            return Future.succeededFuture(new JsonArray());
        }
        Promise<JsonArray> promise = Promise.promise();
        worker.execute(() -> {
            lastAddressLookupThread = Thread.currentThread().getName();
            try {
                JsonArray out = new JsonArray();
                for (String cc : addressOrder(query, visitor)) {
                    AddressIndex idx = addressIdx.get(cc);
                    if (idx == null) continue;          // routed somewhere we hold no addresses
                    for (AddressIndex.Address a : idx.byAddress(query, limit)) {
                        /*
                          THE SHAPE THE PLACES PIPELINE ALREADY CONSUMES, exactly.

                          addCorpusPlaces reads "stateCode", and CorpusPlace.fromBody promotes a
                          chosen row only when it carries "sourcePlaceId" and "name". Emitting
                          "state" and "placeId" instead would have rendered a perfectly good
                          address tile that silently refused to promote on submit, and dropped the
                          region from its detail line. Different key, same value, no error.

                          No "street": the detail line is street + city, so repeating the address
                          there would print "55 Gould Street - 55 Gould Street, Toronto". The
                          address IS the street line, and it travels as the display name.
                        */
                        out.add(new JsonObject()
                            .put("kind", "place")
                            .put("name", a.display())
                            .put("city", a.city())
                            .put("stateCode", a.state())
                            .put("country", a.country())
                            .put("sourcePlaceId", a.placeId()));
                    }
                    if (!out.isEmpty()) break;
                }
                promise.complete(out);
            } catch (Throwable err) {
                LOG.log(Level.WARNING, "address index lookup failed", err);
                promise.fail(err);
            }
        });
        return promise.future();
    }

    /** Explicit country first, then routing, then the visitor - never the visitor alone. */
    private java.util.List<String> addressOrder(String query, String visitor) {
        java.util.LinkedHashSet<String> order = new java.util.LinkedHashSet<>();
        java.util.List<String> routed = routeCountries(query, visitor, 3);
        order.addAll(routed);
        if (visitor != null) order.add(visitor);
        order.addAll(addressIdx.keySet());   // anything we actually hold, as a last resort
        return new java.util.ArrayList<>(order);
    }

    /** Set by the address lookup so a test can prove it was invoked and ran off the caller. */
    volatile String lastAddressLookupThread;

    /** Matches the worker pool below; the two must not drift apart. */
    private static final int WORKER_THREADS = 3;

    private final ConnectionPool indexPool;
    private final GeographyIndex geographyIdx;

    /** Whether geography is served from the local SQLite index rather than the corpus files. */
    public boolean geographyIndexActive() { return geographyIdx != null; }

    /**
     * Releases the index connections and stops the worker.
     *
     * <p>Called on shutdown and whenever an index is REPLACED: a new release is a new file, and
     * handles left open against a swapped path keep serving the bytes of the old one. The worker
     * threads are daemons so they cannot hold the JVM open, but leaving SQLite handles open would
     * still pin memory-mapped pages for the life of the process.
     */
    public void close() {
        if (geographyIdx != null) {
            try { geographyIdx.close(); } catch (RuntimeException err) {
                LOG.log(Level.FINE, "closing geography index", err);
            }
        }
        if (routingIdx != null) {
            try { routingIdx.close(); } catch (RuntimeException err) {
                LOG.log(Level.FINE, "closing routing index", err);
            }
        }
        for (AddressIndex idx : addressIdx.values()) {
            try { idx.close(); } catch (RuntimeException err) {
                LOG.log(Level.FINE, "closing address index", err);
            }
        }
        if (indexPool != null) indexPool.close();
        worker.shutdown();
    }

    /**
     * A setting, from a system property first and then the environment.
     *
     * <p>Property first for the same reason {@code ManagerService.setting} does it: a test cannot
     * set an environment variable for the JVM it is already running in, so an environment-only
     * read means the index paths can never be pointed at a fixture and the wired path can only be
     * exercised by hand. Production passes these as environment variables and is unaffected,
     * because nothing sets the properties there.
     */
    private static String env(String key, String fallback) {
        String property = System.getProperty(key);
        if (property != null && !property.isBlank()) return property;
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
     * <h2>Why an hour, not a minute</h2>
     *
     * <p>This was 60 seconds and 256 entries, on the reasoning that a minute of staleness is
     * invisible. The reasoning was right and the number was still far too small, because it was
     * chosen against the wrong cost. Measured against the live bucket: a warm Canadian query is
     * ~0.35s, a United States one is <b>16–21s</b>, and a cache hit is 0.003s. A minute is shorter
     * than the gap between somebody typing a city and coming back to correct it, so the same
     * twenty-second scan was being paid repeatedly.
     *
     * <p>There is no staleness to trade away. A corpus release is immutable, they are published
     * roughly twice a year, and the active release id is resolved once when the connection is set
     * up and memoised for the life of the process — so a new release is not picked up without a
     * restart whatever this value is. An hour is bounded only so a long-lived process eventually
     * lets go of queries nobody is asking any more.
     *
     * <p>Memory is the real constraint and it is small: an entry holds at most
     * {@link #SUGGESTION_ROWS} short rows, so a few thousand of them is single-digit megabytes —
     * nothing against the engine's own {@code memory_limit}, and nowhere near enough to defeat the
     * reason the corpus is not in Postgres.
     */
    private static final long   CACHE_TTL_MS  = 60 * 60_000L;
    private static final int    CACHE_MAX     = 4_096;

    /** Rows an entry can hold, for the memory note above. Matches the query LIMIT. */
    private static final int    SUGGESTION_ROWS = LIMIT;
    private final java.util.Map<String, CacheEntry> cache =
        java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, CacheEntry> eldest) {
                return size() > CACHE_MAX;
            }
        });

    private record CacheEntry(JsonArray value, long expiresAt) {}

    // Package-private so the failure-is-not-cached rule can be tested without a corpus to break.
    Future<JsonArray> cached(String key, java.util.function.Supplier<Future<JsonArray>> compute) {
        CacheEntry hit = cache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && hit.expiresAt() > now) return Future.succeededFuture(hit.value());
        return compute.get().map(result -> {
            cache.put(key, new CacheEntry(result, now + CACHE_TTL_MS));
            return result;
        });
    }

    /**
     * A memoised LOCAL index result, or null when there is none to serve.
     *
     * <p>Split into a lookup and a store, deliberately, rather than wrapping the whole call. The
     * wrapped form cached whatever the future produced, and the local path produces three
     * different things: a real answer, an EMPTY array when the search executor is saturated and
     * sheds, and null when the index cannot answer at all. Caching the shed value turned one
     * saturated moment into an hour of "no results" for that query, which is a far worse outcome
     * than the momentary shed it came from. Only {@link #putLocalSearch} is ever called, and only
     * from the point where a query actually ran.
     *
     * <p>Bounded by the same store as every other entry: 4,096 entries, least-recently-used
     * eviction, one hour. Immutability justifies the TTL; it does not justify unbounded growth, and
     * the bound is on the map rather than on the reasoning.
     */
    public JsonArray localSearchHit(String key) {
        CacheEntry hit = cache.get("local|" + key);
        return hit != null && hit.expiresAt() > System.currentTimeMillis() ? hit.value() : null;
    }

    /** Stores a result that a real query produced. Never called for a shed or a failure. */
    public void putLocalSearch(String key, JsonArray value) {
        if (value == null) return;
        cache.put("local|" + key, new CacheEntry(value, System.currentTimeMillis() + CACHE_TTL_MS));
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
        /*
          A COUNTRY IS NO LONGER REQUIRED, when the whole-world file is local.

          It used to be, and the cost was paid by the caller: with no country it searched a
          configured shortlist of CA, US and GB one after another, so somebody in any of the other
          255 countries got nothing until they typed the country's name themselves, and the first
          country on the list was read on every keystroke. One worldwide read over 54MB replaces
          all of that, and ranks by population so the city somebody meant comes first.
        */
        if (needle == null) return Future.succeededFuture(new JsonArray());

        /*
          SERVED FROM THE SQLITE INDEX WHEN ONE IS PUBLISHED.

          Same contract: same arguments, same JSON shape, same worker. What changes is only where
          the rows come from.
        */
        if (geographyIdx != null) return geographyFromIndex(query, iso, blankToNull(state));

        if (iso == null && !geographyIsWorldwide()) return Future.succeededFuture(new JsonArray());

        // The partition already guarantees the country, and neither search_text contains it.
        java.util.List<String> words = withoutCountryWord(tokens(needle), country);

        /*
          One attempt. "folded" strips accents from both the column and the typed words. The
          ranking expression folds with the WHERE clause, or "montreal" would match Montréal and
          then be ranked as though it had not.
        */
        /*
          A QUERY THAT NAMES A COUNTRY IS ASKING FOR THAT COUNTRY.

          "USA" used to match nothing in this file. The corpus holds a United States row with a
          population of 327 million, but its search_text is "united states", so no token of "usa"
          appeared in it - and the field answered with eight Canadian businesses whose names
          contain those letters, offering no way to pick the country at all.

          Countries resolves the name, the ISO code and the common aliases in ONE table, and both
          of the things needed here come from it:

            - the country's own name, which the corpus uses, so the exact-match tier below ranks
              the country row FIRST rather than leaving it to compete on population;
            - the ISO code, which adds the country row to the result set even though the typed
              text appears nowhere in its search_text.

          Nothing is removed by this. The token predicate still runs exactly as before, so a query
          that is also a place name - "India" is a country and a locality in several countries -
          keeps every row it used to return and simply gains the country at the top.
        */
        String spokenCountry = Countries.displayName(needle);
        String spokenIso     = Countries.iso(needle);

        /*
          THE COUNTRY THE QUERY ITSELF STATES, after a comma: "Washington, United States".

          Reported as returning nothing at all, and it returned nothing for every "city, country"
          query - Toronto, Canada and Paris, France included. A locality's search_text is its name
          and its region, "washington district of columbia", and NEVER the country. The country
          word was stripped from the token list only when it arrived as a request PARAMETER, so a
          country somebody typed was left in the tokens and required to appear in a string that
          cannot contain it. Three tokens, no row with all three, zero rows.

          A comma means the location is STATED rather than guessed, which is the convention this
          field already follows, so the country it names SCOPES the read instead of being searched
          for inside it.
        */
        final String statedIso = statedCountry(needle);
        final String readIso   = statedIso != null ? statedIso : iso;
        /*
          Stripped of the stated country's words, exactly as the request parameter's country has
          always been stripped. withoutCountryWord takes a code or a name and removes both forms.
        */
        final java.util.List<String> readWords =
            statedIso != null ? withoutCountryWord(words, statedIso) : words;

        java.util.function.BiFunction<Boolean, Boolean, Future<JsonArray>> attempt =
            (anchored, folded) -> {
                String nameExpr = folded ? "strip_accents(lower(name))" : "lower(name)";
                /*
                  Ranked against the COUNTRY'S name when the query named one, so "usa" is compared
                  with "united states" and the country row lands in tier 0.
                */
                String rankBase = spokenCountry != null ? Countries.collapse(spokenCountry)
                                                        : String.join(" ", readWords);
                String ranked   = folded ? stripAccents(rankBase) : rankBase;
                return query(release -> """
                    -- EACH COUNTRY'S BEST MATCH COMPETES BEFORE ANY COUNTRY'S SECOND.
                    --
                    -- Ordering by prominence alone let one city's subdivisions take every slot:
                    -- "Paris" returned eight rows and all eight were French, because the
                    -- arrondissements - Paris 15 Vaugirard, Paris 20 Menilmontant and the rest -
                    -- each outrank Paris, Texas (24,782) and Paris, Ontario (12,310) on
                    -- population. They are all the same city, and between them they made every
                    -- other Paris on earth unreachable.
                    --
                    -- That matters more now that the field no longer filters by country: the
                    -- client can only reorder what it is given, so anything missing from these
                    -- eight rows cannot be chosen at all. A limit that hides places is the same
                    -- defect as a filter that excluded them.
                    --
                    -- Ranking within each country first and interleaving gives Paris, France;
                    -- Paris, Texas; Paris, Ontario - most prominent still first, and the other
                    -- Parises reachable.
                    SELECT name, kind, state_name, state_code, country_code, population FROM (
                      SELECT name, kind, state_name, state_code, country_code, population, tier,
                             row_number() OVER (PARTITION BY country_code
                                                ORDER BY tier, population DESC NULLS LAST)
                               AS per_country
                      FROM (
                        SELECT name, kind, state_name, state_code, country_code, population,
                               -- ... or the country the query NAMED, matched by IDENTITY
                               -- rather than by spelling. The corpus calls NL "The Netherlands",
                               -- so "Holland" resolved to the JDK's "Netherlands" prefix-matched
                               -- nothing: the country ranked below a hamlet in Singapore called
                               -- Holland Drive, and the suggestions led with Holland College.
                               -- The ISO code is the country's identity; the name is a label, and
                               -- the two datasets do not have to agree on it.
                               CASE WHEN %s = ? OR %s LIKE ?
                                      OR (? IS NOT NULL AND kind = 'country' AND country_code = ?)
                                    THEN 0 ELSE 1 END AS tier
                        FROM %s
                        -- The second branch is the named country itself. Its search_text holds
                        -- "united states", which no token of "USA" or "US" can match, so the
                        -- row is admitted by its CODE once Countries has recognised the alias.
                        -- Both placeholders take the same code; a null makes the branch false
                        -- and this is exactly the previous predicate.
                        WHERE (%s OR (? IS NOT NULL AND kind = 'country' AND country_code = ?))%s
                          AND (? IS NULL OR state_code = ? OR kind = 'country')
                      )
                    )
                    ORDER BY tier, per_country, population DESC NULLS LAST
                    LIMIT %d
                    """.formatted(nameExpr, nameExpr, geographyFrom(release, readIso),
                                   tokensClause(readWords.size(), anchored, folded),
                                   geographyCountryClause(readIso), LIMIT),
                statement -> {
                    // The ranking placeholders now sit in the SELECT list, so they bind BEFORE
                    // the token and state predicates rather than after them.
                    int i = 1;
                    statement.setString(i++, ranked);
                    statement.setString(i++, ranked + "%");
                    // The named country, for the tier test: null when none was named.
                    statement.setString(i++, spokenIso);
                    statement.setString(i++, spokenIso);
                    bindTokens(statement, readWords, i, anchored, folded);
                    i += readWords.size();
                    // The recognised country, twice: once to test for null, once to compare.
                    statement.setString(i++, spokenIso);
                    statement.setString(i++, spokenIso);
                    statement.setString(i++, blankToNull(state));
                    statement.setString(i, blankToNull(state));
                },
                rs -> new JsonObject()
                    .put("kind", "geo")
                    .put("name", rs.getString("name"))
                    .put("geoKind", rs.getString("kind"))
                    .put("stateName", rs.getString("state_name"))
                    .put("stateCode", rs.getString("state_code"))
                    // WHICH COUNTRY THIS ROW IS IN, which the caller can no longer assume it
                    // already knows: a worldwide search is not scoped to one, and the country is
                    // not merely a label - it is stored when somebody picks the suggestion.
                    .put("countryCode", rs.getString("country_code"))
                    // HOW MANY PEOPLE LIVE THERE, which decides whether this row is evidence that
                    // the person is typing a CITY rather than a business. See geographyAnswers.
                    .put("population", rs.getLong("population")));
            };

        /*
          Always folded, and deliberately NOT behind a fallback.

          A fallback fires only on an EMPTY result. That fixed "Montreal, Quebec, Canada" and left
          "Montreal" on its own still broken: Montreal Lake, Saskatchewan matches
          accent-sensitively, so the result was never empty, the fallback never ran, and Montréal
          - 1.7 million people - was missing from a list that did contain a hamlet. Every accented
          place had the same hole for as long as somebody was still typing.

          Anchoring buys nothing here anyway: strip_accents() on the column defeats Parquet
          row-group pruning whatever the pattern looks like, so this is one scan rather than two.
          Measured at roughly 20ms against the materialised geography file, which is the reason
          this dataset is copied locally at all. The sorted release's real win was the places
          dataset over S3, which is untouched by this and still anchored.
        */
        return cached("geo|" + readIso + "|" + blankToNull(state) + "|" + needle,
                      () -> attempt.apply(false, true).compose(rows ->
                          rows.isEmpty() ? geographyForIntent(query) : Future.succeededFuture(rows)));
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
        return suggestPlaces(companyName, query, country, null);
    }

    /**
     * @param city the city the query named, or null. It ORDERS the result rather than filtering
     *        it, in ONE scan.
     *
     *        <p>Filtering was the obvious reading and it was wrong twice over. The two datasets do
     *        not always spell a city alike, so a predicate that matches nothing turns a usable list
     *        into an empty one - "McDonalds Tokyo" went from a McDonald's in Kasukabe to no
     *        buildings at all. Retrying without the predicate fixed that and introduced something
     *        worse: two scans of an S3 partition on the slowest path in the system, which then
     *        exceeded the places deadline and returned empty anyway.
     *
     *        <p>Ordering gets both. The named city comes first when the spellings agree, anything
     *        else in that country follows rather than vanishing, and it costs one scan.
     */
    public Future<JsonArray> suggestPlaces(String companyName, String query, String country,
                                           String city) {
        return placesFor(companyName, query, country,
                         city == null || city.isBlank() ? null : city);
    }

    /**
     * The cities a street runs through, as coarse suggestions.
     *
     * <p>Used only when an address was not found. Returns {@code geo} rows at city precision, so
     * what gets stored is the city rather than somebody else's front door.
     */
    public Future<JsonArray> citiesOnStreet(String query, String country) {
        String needle = normalise(query);
        String iso = iso(country);
        if (needle == null || iso == null) return Future.succeededFuture(new JsonArray());
        java.util.List<String> words = tokens(needle);
        return cached("street-city|" + iso + "|" + needle, () ->
            query(release -> """
                SELECT DISTINCT p.city, p.state_code,
                       /*
                         The region's NAME, not its code.

                         The places file carries only "ON", so this fallback offered "Kitchener,
                         ON, Canada" while every other suggestion said "Ontario".

                         Joined on the CITY NAME, not on the region code: the two datasets spell
                         regions differently - GeoNames keys admin1 as "CA.08", Overture uses the
                         ISO "ON" - so a code join silently matches nothing and quietly falls back
                         to the code, which is how the first attempt at this looked correct and
                         changed nothing. Geography is a local file now, so this costs ~15ms.
                       */
                       COALESCE(g.state_name, p.state_code) AS state_name
                FROM read_parquet('s3://%s/processed/%s/places/country_code=%s/*.parquet') p
                LEFT JOIN (SELECT DISTINCT lower(name) AS city_key, state_name FROM %s
                            WHERE kind = 'locality' AND state_name IS NOT NULL%s) g
                       ON g.city_key = lower(p.city)
                WHERE %s AND p.city IS NOT NULL
                LIMIT %d
                """.formatted(bucket, release, partition(iso),
                               geographyFrom(release, iso), geographyCountryClause(iso),
                               allTokensClause(words.size()).replace("search_text", "p.search_text"),
                               LIMIT),
            statement -> {
                int i = 1;
                for (String word : words) statement.setString(i++, "%" + word + "%");
            },
            rs -> new JsonObject()
                .put("kind", "geo")
                .put("name", rs.getString("city"))
                .put("geoKind", "locality")
                .put("stateName", rs.getString("state_name"))
                .put("stateCode", rs.getString("state_code"))));
    }

    /** The query as typed, minus any purely numeric words, or null if that changes nothing. */
    public static String stripLeadingNumbers(String query) {
        if (query == null) return null;
        String[] words = query.trim().split("[\\s,]+");
        StringBuilder kept = new StringBuilder();
        boolean dropped = false;
        for (String word : words) {
            // Purely numeric, or a number with a unit suffix like "12b". A word that merely
            // CONTAINS a digit - "a1a 1a1", a postcode - is left alone.
            if (word.matches("\\d+[a-zA-Z]?")) { dropped = true; continue; }
            if (kept.length() > 0) kept.append(' ');
            kept.append(word);
        }
        // Nothing dropped, or nothing left worth searching for.
        return (!dropped || kept.length() < 2) ? null : kept.toString();
    }

    private Future<JsonArray> placesFor(String companyName, String query, String country,
                                        String city) {
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
        /*
          TWO INTERPRETATIONS, RETRIEVED TOGETHER.

          The query is never destroyed. "Texas Roadhouse" is both a name and, read another way, a
          roadhouse in Texas, and only the corpus can say which. So both readings are put to it in
          one scan and the literal one is preferred when it hits.

          Stripping alone was not merely imprecise, it EXCLUDED the right answer. Texas Roadhouse
          is in the corpus with confidence 0.92 and a search_text of
          "texas roadhouse 101 conduit rd colonial heights". Dropping "texas" as a region word left
          the anchored predicate as search_text LIKE 'roadhouse%', which that row cannot satisfy
          because its text begins with "texas" - and anchoredThenContains then returned the other
          roadhouses it did find, so the contains fallback that would have caught it never ran.
          Three mechanisms, one unfindable restaurant chain.

          A scoring pass cannot repair this. A candidate that retrieval never returned is not
          available to rank, which is why both readings have to reach the WHERE clause rather than
          being resolved afterwards.
        */
        java.util.function.Supplier<java.util.List<String>> literalWords = () -> tokens(needle);
        java.util.function.Supplier<java.util.List<String>> strippedWords =
            () -> forPlaces(tokens(needle), country, iso);

        // The city belongs in the key: the same query with and without it has different answers.
        return cached("place|" + iso + "|" + brand + "|" + needle + "|" + (city == null ? "" : city), () ->
          anchoredThenLiteralContains(anchored ->
            query(release -> """
                SELECT source_place_id, name, brand_name, street, city, state_code, postal_code,
                       literal_hit
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
                           -- The query as ONE CONTIGUOUS PHRASE, not token by token.
                           --
                           -- Per-token was too weak to mean anything: "Northwest Face and Body"
                           -- contains both words of "North Face" separately, so it counted as a
                           -- literal match and convinced the fast path it had found the answer. A
                           -- phrase match does not: "northwest face" does not contain the phrase
                           -- "north face", while "the north face the north face ..." does.
                           --
                           -- No per-cent signs in this text block: it goes through String.formatted,
                           -- which reads them as conversions and throws.
                           CASE WHEN search_text LIKE ? THEN 0 ELSE 1 END AS literal_hit,
                           row_number() OVER (
                               /*
                                 ONE ROW PER BUSINESS, not one per building.

                                 This grouped on postal code plus the first number in the street,
                                 which is not an identity: 10 Main Street and 10 King Street can
                                 share a postal code, and regexp_extract returns an EMPTY STRING
                                 rather than NULL when a street has no digits, so coalesce never
                                 reached the name fallback and every numberless address in a
                                 postcode collapsed into one. The survivors of that collapse were
                                 silently dropped.

                                 A pharmacy, a supermarket and a hair salon in one building are
                                 three employers with three sets of managers, so all three stay
                                 separately selectable. The name joins the key to keep them, while
                                 the same business listed twice at one address still collapses.
                                 nullif() is what makes the fallback work at all.
                               */
                               -- source_place_id, so a distinct workplace is NEVER merged away.
                               -- Keying on the name still merged two same-name branches that
                               -- happened to share a postal code and street number, which is the
                               -- identity question this was supposed to stop getting wrong.
                               -- Duplicate reconciliation is a separate, deliberate job.
                               PARTITION BY source_place_id
                               ORDER BY CASE WHEN brand_name IS NOT NULL THEN 0 ELSE 1 END,
                                        confidence DESC NULLS LAST) AS at_address
                    FROM read_parquet('s3://%s/processed/%s/places/country_code=%s/*.parquet')
                    -- The outer parentheses matter: AND binds tighter than OR, so without them
                    -- the closed-business filter would apply to the second reading only.
                    WHERE ((%s) OR (%s))
                      AND coalesce(operating_status, 'open') <> 'closed'
                )
                WHERE at_address = 1
                -- The literal reading first. Both are candidates; this decides which one the
                -- reader most likely meant, and only among rows retrieval actually returned.
                -- THE CITY THE QUERY NAMED FIRST, ahead of the brand preference: a branded place
                -- in the wrong city is still the wrong city.
                ORDER BY %s literal_hit,
                         CASE WHEN ? IS NOT NULL AND lower(coalesce(brand_name,'')) = ? THEN 0
                              WHEN brand_name IS NOT NULL                                THEN 1
                              ELSE 2 END,
                         confidence DESC NULLS LAST
                LIMIT %d
                """.formatted(bucket, release, partition(iso),
                              tokensClause(literalWords.get().size(), anchored),
                              tokensClause(strippedWords.get().size(), anchored),
                              city == null ? ""
                                : "CASE WHEN strip_accents(lower(coalesce(city,''))) = ? "
                                  + "THEN 0 ELSE 1 END,",
                              LIMIT),
            statement -> {
                int i = 1;
                // Order follows the SQL text: the literal_hit phrase, then the two WHERE
                // readings, then the brand preference.
                /*
                  The phrase is the FIRST comma segment, not the whole query.

                  "New York Life, New York" is a name followed by where it is, and no search_text
                  holds those as one run of words - so taking the whole string as the phrase meant
                  the signal never fired, the contains scan ran every time, and the company still
                  did not surface. Its first segment, "new york life", matches exactly.

                  Commas inform the reading here; they do not decide it. A query with no comma
                  simply uses all of itself, and a segment that matches nothing costs only the
                  fallback that would have run anyway.
                */
                int comma = needle.indexOf(',');
                String phrase = (comma > 0 ? needle.substring(0, comma) : needle).trim();
                statement.setString(i++, "%" + phrase + "%");
                bindTokens(statement, literalWords.get(), i, anchored);
                i += literalWords.get().size();
                bindTokens(statement, strippedWords.get(), i, anchored);
                i += strippedWords.get().size();
                if (city != null) statement.setString(i++, stripAccents(city.toLowerCase()));
                statement.setString(i++, brand);
                statement.setString(i, brand);
            },
            rs -> new JsonObject()
                .put("kind", "place")
                // Carried so the caller can tell a literal hit from a row that only matched the
                // qualifier-stripped reading. See anchoredThenLiteralContains.
                .put("literalHit", rs.getInt("literal_hit") == 0)
                .put("sourcePlaceId", rs.getString("source_place_id"))
                .put("name", rs.getString("name"))
                .put("brandName", rs.getString("brand_name"))
                .put("street", rs.getString("street"))
                .put("city", rs.getString("city"))
                .put("stateCode", rs.getString("state_code"))
                .put("postalCode", rs.getString("postal_code")))));
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
                /*
                  FAILS, rather than quietly succeeding with nothing.

                  This used to complete with an empty array, which had two consequences and both
                  bit. An outage became indistinguishable from "no such place", so a caller could
                  not tell a broken bucket from a place that does not exist - and the empty result
                  then went into the one-hour cache as a perfectly good answer, so one transient
                  error made that query return nothing for the next sixty minutes.

                  It also hid a programming error for an entire round of measurement: a stray
                  per-cent sign in a SQL comment threw on every query, and ten benchmark cases
                  reported as legitimate empty results rather than as the crash they were.

                  Softening still happens, but at the boundary where somebody can decide what to
                  tell the reader, not here where the evidence is thrown away. Throwable rather
                  than Exception because a native library problem arrives as an Error.
                */
                LOG.log(Level.WARNING, "location corpus query failed", err);
                promise.fail(err);
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
            // In the background: a cold start serves from S3 and gets faster, rather than waiting.
            materialiseGeography(conn, active);
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
        /*
          An override, so a candidate release can be proved before it is published.

          The release is otherwise read only from current.json, which means the only way to try a
          newly built corpus was to repoint production at it and find out in front of users. A
          rebuild is a multi-hour job over tens of millions of rows; it deserves to be measured
          against a real backend first.

          LOCATION_CORPUS_RELEASE names the release directly and LOCATION_CORPUS_SORTED says
          whether it carries a sorted search_text, since a candidate has no manifest of its own
          until it is published. Neither is set in production, where current.json stays the single
          source of truth.
        */
        String pinned = System.getenv("LOCATION_CORPUS_RELEASE");
        if (pinned != null && !pinned.isBlank()) {
            searchTextSorted = "true".equalsIgnoreCase(System.getenv("LOCATION_CORPUS_SORTED"));
            LOG.info("location corpus pinned to " + pinned.trim()
                     + " (sorted=" + searchTextSorted + ") by LOCATION_CORPUS_RELEASE");
            return pinned.trim();
        }
        String sql = "SELECT * FROM read_json_auto('s3://" + bucket + "/current.json')";
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) return null;
            /*
              The release declares whether its search_text is sorted, rather than a flag somebody
              has to remember to flip alongside publishing one.

              The anchored predicate is only worth using on a sorted release: anchoring is what
              lets row-group statistics prune, and on an unsorted one an anchored MISS still reads
              the whole partition and then pays for the contains fallback on top - slower than
              simply doing the contains. So a release that has not been sorted keeps today's
              behaviour exactly, and a sorted one turns the fast path on by existing.

              Read tolerantly: releases published before this field existed do not carry it.
            */
            java.sql.ResultSetMetaData meta = rs.getMetaData();
            String value = null;
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String column = meta.getColumnLabel(i);
                if ("release".equalsIgnoreCase(column)) {
                    value = rs.getString(i);
                } else if ("search_text_sorted".equalsIgnoreCase(column)) {
                    searchTextSorted = rs.getBoolean(i);
                }
            }
            return (value == null || value.isBlank() || "none".equals(value)) ? null : value;
        }
    }

    /**
     * Where the geography rows are read from: a local file when we have one, S3 otherwise.
     *
     * <h2>Why a local copy at all</h2>
     *
     * <p>Every corpus query pays a round trip to ca-central-1 before it does any work. Measured
     * against the live bucket, an anchored query that matches <b>nothing</b> still takes 0.50s:
     * that is latency, not scanning. The same query against a local file is <b>0.03s</b>.
     *
     * <p>Geography is the half worth holding: the entire world is <b>54MB</b> and downloads in
     * about eight seconds, against 13GB for places. A typical request reads geography twice - the
     * anchored attempt and the contains fallback - so removing those two round trips takes the
     * worst case from roughly 1.5s to 0.8s, and an ordinary city search to almost nothing.
     *
     * <p>Held as ONE file with country_code as a column rather than as partitioned folders: a
     * partition per country means a footer read per file, and reading 132 footers over HTTP was
     * the thing that made the sorted corpus no faster than the unsorted one.
     *
     * <p>Failure is not fatal. If the download does not finish, this stays null and every query
     * reads S3 exactly as before - slower, and correct.
     */
    private volatile String localGeography = null;

    /**
     * Counted down once the geography file is either local or known not to be coming.
     *
     * <p>There was a window at startup in which the file was still arriving, so
     * {@code geographyIsWorldwide()} was false and the SAME query was interpreted differently from
     * how it would be a second later: "Starbucks Paris" resolved no intent, fell back to the
     * configured countries and answered with Paris, Ontario, then answered with Paris, France once
     * the file landed. A first request that silently means something else is worse than a slightly
     * slower one.
     */
    private final java.util.concurrent.CountDownLatch geographyReady =
        new java.util.concurrent.CountDownLatch(1);

    /**
     * How long a request will wait for geography before giving up on intent.
     *
     * <p>Bounded, because the alternative to a short wait is a hung keystroke. On a machine that
     * already has the file this is not reached at all; it only applies to the first requests after
     * a cold start on a host with an empty temporary directory.
     */
    private static final long GEOGRAPHY_WAIT_MS = 2_000;

    /** The FROM clause for geography, and the country predicate that goes with it. */
    private String geographyFrom(String release, String iso) {
        String local = localGeography;
        return local != null
            ? "read_parquet('" + local + "')"
            : "read_parquet('s3://" + bucket + "/processed/" + release
              + "/geography/country_code=" + partition(iso) + "/*.parquet')";
    }

    /**
     * Restricts a local read to one country; the S3 path already does that by partition.
     *
     * <p>A NULL country means every country, which only the local file can serve: it holds all
     * 258 of them in 54MB, while the S3 layout addresses one partition per read. This is what
     * lets a search with no country settled find the city anyway, instead of guessing at a short
     * list of countries and missing everybody outside it.
     */
    private String geographyCountryClause(String iso) {
        if (localGeography == null) return "";
        return iso == null ? "" : " AND country_code = '" + partition(iso) + "'";
    }

    /**
     * The ISO country the QUERY ITSELF names, or null when it names none.
     *
     * <p>This is how a request with no country still reaches the right index. The agreed precedence
     * is: an explicitly selected location, then clear geographic intent in the query, then the
     * visitor's detected country, then general prominence. Without this, intent was skipped
     * entirely and the configured CA/US/GB list decided everything, so "Starbucks Paris" with no
     * country selected searched Canada and the United States and never France - while the very
     * same query with {@code country=France} worked perfectly. Those are not the same thing, and
     * the frontend sends the first one.
     *
     * <p>The LONGEST trailing phrase wins, because a shorter one is less specific: "new york" must
     * not be read as the trailing "york". Among places of that name the most POPULOUS wins, which
     * is how Paris means France rather than Texas and Tokyo means Japan rather than Papua New
     * Guinea. A phrase the corpus gives no population is not intent at all - it is a hamlet that
     * happens to share a word with a brand - so "Paris Baguette" and "London Drugs" resolve to
     * nothing here and leave the candidate list alone.
     *
     * <p>Worldwide only, and only off the local file: one read of 54MB, on a path that previously
     * did none.
     */
    public Future<String> intendedCountry(String query) {
        return intendedPlace(query).map(p -> p == null ? null : p.getString("country_code"));
    }

    /**
     * The place the query itself names: its country AND the corpus's name for the city.
     *
     * <p>The country alone is not enough, and believing it was produced a wrong answer that looked
     * right: "McDonalds Tokyo" with no country resolved to Japan, and then the remote places query
     * - which constrains only by country PARTITION and has no city predicate at all - returned a
     * McDonald's in Kasukabe. Worse, forPlaces strips region words from the needle, and Tokyo is a
     * region in Japan, so the one word carrying the location was removed before the scan. Right
     * country, wrong city, and nothing in the query left to object.
     */
    public Future<JsonObject> intendedPlace(String query) {
        if (!geographyIsWorldwide()) {
            /*
              WAIT, BRIEFLY, RATHER THAN ANSWER DIFFERENTLY.

              The wait happens on the worker, never on the event loop. If it expires the fallback
              is the same as before, but it is LOGGED rather than silent, so a first-request
              anomaly is attributable instead of looking like a ranking bug.
            */
            return awaitGeography().compose(ok -> {
                if (ok) return intendedPlace(query);
                LOG.warning("location corpus geography not ready after " + GEOGRAPHY_WAIT_MS
                    + "ms; this request cannot resolve geographic intent and will use the "
                    + "configured default countries");
                return Future.succeededFuture(null);
            });
        }
        String needle = normalise(query);
        if (needle == null) return Future.succeededFuture(null);
        java.util.List<String> words = tokens(needle);
        if (words.size() < 2) return Future.succeededFuture(null);   // a bare city is not a business query
        // Longest trailing run first, stopping before the whole query so something names the business.
        java.util.List<String> phrases = new java.util.ArrayList<>();
        for (int start = 1; start < words.size(); start++) {
            phrases.add(String.join(" ", words.subList(start, words.size())));
        }
        return intendedPlace(phrases, 0);
    }

    private Future<JsonObject> intendedPlace(java.util.List<String> phrases, int at) {
        if (at >= phrases.size()) return Future.succeededFuture(null);
        String phrase = phrases.get(at);
        return query(release -> """
                SELECT country_code, name, state_name, state_code, population
                FROM %s
                WHERE kind = 'locality' AND population > 0
                  AND strip_accents(lower(name)) = ?
                ORDER BY population DESC
                LIMIT 1
                """.formatted(geographyFrom(release, null)),
            statement -> statement.setString(1, stripAccents(phrase)),
            rs -> new JsonObject().put("country_code", rs.getString("country_code"))
                                  .put("name", rs.getString("name"))
                                  .put("state_name", rs.getString("state_name"))
                                  .put("state_code", rs.getString("state_code"))
                                  .put("population", rs.getLong("population")))
            .compose(rows -> rows.isEmpty()
                ? intendedPlace(phrases, at + 1)
                : Future.succeededFuture(rows.getJsonObject(0)));
    }

    /**
     * The PLACE the query named, as a geography suggestion, when nothing else matched.
     *
     * <p>Geography matches every word of the query, so "McDonalds Tokyo" matches no locality - no
     * place on earth has "mcdonalds" in its search text - and the buildings half cannot help
     * either once an unconfirmable city is correctly refused. The reader was left with an empty
     * list for a query that names a real city perfectly clearly.
     *
     * <p>So the city itself is offered. It is the honest answer to a query whose geography is
     * understood and whose BUSINESS cannot be matched in that country's data: Tokyo is right
     * there to be picked, and once it is picked the country is known and the ordinary search runs
     * against it. Nothing here invents a business.
     */
    private Future<JsonArray> geographyForIntent(String query) {
        return intendedPlace(query).map(place -> {
            JsonArray out = new JsonArray();
            if (place == null) return out;
            return out.add(new JsonObject()
                .put("kind", "geo")
                .put("name", place.getString("name"))
                .put("geoKind", "locality")
                .put("stateName", place.getString("state_name"))
                .put("stateCode", place.getString("state_code"))
                .put("countryCode", place.getString("country_code"))
                .put("population", place.getLong("population", 0L)));
        });
    }

    /**
     * Waits, briefly, for the worldwide geography file, and reports whether it arrived.
     *
     * <p>Shared by both tiers that depend on it, because a first request that answered differently
     * from the second was observed and neither tier could explain it alone: the places tier
     * resolved geographic intent while the geography tier had already fallen back to its
     * configured shortlist, so one typed query produced the right country from one half and the
     * wrong one from the other.
     *
     * <p>The wait runs on the worker, NEVER on the event loop, and is capped: the alternative to a
     * short wait is a hung keystroke. On a host that already has the file this returns immediately.
     */
    public Future<Boolean> awaitGeography() {
        if (geographyIsWorldwide()) return Future.succeededFuture(true);
        Promise<Boolean> ready = Promise.promise();
        worker.submit(() -> {
            try {
                ready.complete(geographyReady.await(GEOGRAPHY_WAIT_MS,
                                   java.util.concurrent.TimeUnit.MILLISECONDS)
                               && geographyIsWorldwide());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                ready.complete(false);
            }
        });
        return ready.future();
    }

    /**
     * Whether geography can be searched worldwide in one read.
     *
     * <p>True once the whole-world file is on local disk. Until then the only addressable form is
     * one S3 partition per country, so the caller falls back to its configured shortlist.
     */
    public boolean geographyIsWorldwide() {
        /*
          THE SQLITE INDEX IS WORLDWIDE GEOGRAPHY TOO, and saying otherwise cost two seconds a
          keystroke.

          This answered "has the geography PARQUET been copied to this host", because for a long
          time that was the only worldwide source. With the index active the parquet is never
          materialised - the index answers first and nothing asks for it - so localGeography stayed
          null, this returned false, and every request without a country waited out the whole
          GEOGRAPHY_WAIT_MS before falling back. Measured: 2,207ms total against 201ms of actual
          tier work, and the 2,000ms was pure waiting.

          The question being asked is whether worldwide geography can be answered RIGHT NOW. The
          index answers it for 252 countries and 5.2 million places, so it has to count.
        */
        return geographyIdx != null || localGeography != null;
    }

    /**
     * Downloads the geography dataset to local disk, once, in the background.
     *
     * <p>Runs on the worker after the connection is established, so a cold start serves from S3
     * and gets faster rather than waiting. Writing to a temporary file and renaming means a query
     * never sees a half-written copy.
     */
    private void materialiseGeography(Connection conn, String release) {
        worker.submit(() -> {
            try {
                java.nio.file.Path dir = java.nio.file.Path.of(
                    System.getProperty("java.io.tmpdir"), "location-corpus");
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path tmp   = dir.resolve("geography-" + release + ".parquet.tmp");
                java.nio.file.Path final_ = dir.resolve("geography-" + release + ".parquet");

                if (java.nio.file.Files.exists(final_)) {
                    localGeography = final_.toString();
                geographyReady.countDown();
                    geographyReady.countDown();
                    LOG.info("location corpus geography already local: " + final_);
                    return;
                }
                long started = System.currentTimeMillis();
                /*
                  Its own connection, not the shared one.

                  A DuckDBConnection is not safe to use from two threads at once, which is why
                  every query already runs on a duplicate. This download takes seconds and would
                  otherwise be holding the shared connection while requests use it.

                  NO MEMORY OVERRIDE. This used to raise the limit to 2GB here, on the reasoning
                  that a duplicated connection has "its own memory limit". It does not: duplicate()
                  shares the database instance and DuckDB's memory_limit is instance-wide, so the
                  line quietly lifted the cap for every concurrent request on a 4GB box, and never
                  put it back. The engine also allocates outside the buffer manager, so the cap was
                  never the whole story anyway.

                  If this COPY cannot run inside the configured limit, the answer is to publish the
                  compact geography file during the corpus build and download it, rather than
                  rebuilding it here with the engine's safety belt loosened.
                */
                try (Connection own = ((org.duckdb.DuckDBConnection) conn).duplicate();
                     Statement statement = own.createStatement()) {
                    statement.execute("COPY (SELECT * FROM read_parquet('s3://" + bucket
                        + "/processed/" + release + "/geography/*/*.parquet', hive_partitioning=true))"
                        + " TO '" + tmp + "' (FORMAT parquet, COMPRESSION zstd)");
                }
                java.nio.file.Files.move(tmp, final_,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                localGeography = final_.toString();
                LOG.info("location corpus geography cached locally in "
                         + (System.currentTimeMillis() - started) + "ms: " + final_);
            } catch (Throwable err) {
                /*
                  Throwable, not Exception.

                  The first version caught Exception and the task died in silence: an engine that
                  runs out of memory throws an Error, so nothing was logged, no file appeared, and
                  every query carried on hitting S3 with no indication why.
                */
                LOG.log(Level.WARNING, "could not cache geography locally; using S3", err);
            }
        });
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
        /*
          A street type is only a formatting detail when this is an ADDRESS.

          Dropping it unconditionally broke every business whose name ends in one: "Scotiabank
          Place" searched for "scotiabank" and returned the bank and a cinema, "Union Square"
          returned three states. Place, Square, Way, Court and Terrace are ordinary words in
          names, and a list that eats them everywhere trades one failure for a bigger one.

          A house number is the signal, and it is a reliable one: "8581 Laguna Grove Drive" is an
          address whose suffix the corpus may abbreviate to "Dr", while "Scotiabank Place" is a
          name in which every word is meant. Businesses that merely BEGIN with a number, "99 Ranch
          Market", are unaffected, because they carry no street type to drop.
        */
        boolean looksLikeAddress = withoutCountry.stream().anyMatch(LocationCorpusRepository::isHouseNumber);
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (String word : withoutCountry) {
            if (regions.contains(word)) continue;
            if (looksLikeAddress && STREET_SUFFIXES.contains(word)) continue;
            kept.add(word);
        }
        return kept.isEmpty() ? withoutCountry : kept;
    }

    /** Whether this token is a bare street number, which is what makes a query read as an address. */
    private static boolean isHouseNumber(String word) {
        if (word == null || word.isEmpty()) return false;
        for (int i = 0; i < word.length(); i++) {
            if (!Character.isDigit(word.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Street-type words, dropped from a places search for the same reason the country and the
     * region are.
     *
     * <h2>The failure</h2>
     *
     * <p>Overture abbreviates. Elk Grove Subaru is stored with the street {@code "8581 Laguna
     * Grove Dr"}, and somebody typing their workplace address in full writes "Drive". Every word
     * is ANDed against {@code search_text}, so that one word decided the result. Measured against
     * the live corpus:
     *
     * <pre>
     *   "8581 Laguna Grove Drive, Elk Grove, California"   13.7s   0 results
     *   "8581 Laguna Grove Dr, Elk Grove, California"       1.0s   1 result
     *   "8581 Laguna Grove, Elk Grove"                      1.0s   1 result
     * </pre>
     *
     * <p>The miss is also the slow one, and necessarily so: a query that matches nothing has to
     * read the whole 16.1M-row United States partition before it can say nothing matched. So the
     * more completely somebody typed their address, the longer they waited to be told it does not
     * exist. That is the worst possible shape for a field that is trying to be helpful.
     *
     * <h2>Why dropping rather than expanding</h2>
     *
     * <p>Expanding the needle to {@code (… LIKE '%drive%' OR … LIKE '%dr%')} would need the
     * alternation threaded through both the anchored and the contains clauses, and {@code '%dr%'}
     * is a substring of enough words to be worth little. Normalising the corpus side instead would
     * mean a rebuild, which is a multi-hour job over tens of millions of rows.
     *
     * <p>Dropping costs almost nothing in precision. By the time the number, the street name and
     * the city are all present the building is already identified - "8581 Laguna Grove, Elk Grove"
     * finds it on its own, as the measurement above shows - and ranking still orders what comes
     * back. It also makes both spellings ask the same question, which is the real goal: nobody
     * should have to guess whether a map wrote "Dr" or "Drive".
     *
     * <p>Both the long and the short form are listed, so "Dr" is dropped as readily as "Drive".
     * The existing guard in {@link #withoutQualifiers} keeps them when removing everything would
     * leave nothing, which is what saves a street genuinely named "The Street".
     */
    private static final java.util.Set<String> STREET_SUFFIXES = java.util.Set.of(
        "street",    "st",
        "road",      "rd",
        "avenue",    "ave",   "av",
        "drive",     "dr",
        "boulevard", "blvd",
        "lane",      "ln",
        "court",     "ct",
        "place",     "pl",
        "crescent",  "cres",
        "parkway",   "pkwy",
        "highway",   "hwy",
        "terrace",   "terr",
        "circle",    "cir",
        "square",    "sq",
        "trail",     "trl",
        "way",
        "suite",     "ste",
        "unit",
        "floor",     "fl");

    /**
     * The text the ORDER BY compares a row's name against.
     *
     * <p>It has to be the text the WHERE clause matched on, which is the needle minus the country
     * word. Binding the raw needle instead made "Wisconsin, United States" unselectable: no
     * {@code search_text} and no {@code name} contains a country, so
     * {@code lower(name) = 'wisconsin united states'} was false for every row, the exact and
     * prefix tiers caught nothing, and the whole result set fell through to
     * {@code population DESC NULLS LAST}.
     *
     * <p>Region rows carry NULL population - every one of the 51 US states does - so the state
     * somebody had just typed in full came back last, below three cities that merely share its
     * prefix, one of them a hamlet of population 0.
     *
     * <p>"Ontario, Canada" concealed this entirely, because exactly one Canadian row matches
     * {@code ontario%} and a one-row result cannot be mis-ordered.
     *
     * <p>Derived the same way as the WHERE clause's words, from the same call, so the two cannot
     * disagree about what was being searched for.
     */
    static String rankingNeedle(String needle, String country) {
        return String.join(" ", withoutCountryWord(tokens(needle), country));
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
        /*
          The country arrives as a display name from the forms and as an ISO code from anything
          that has already resolved one - the default-country fallback passes "CA".

          Both have to strip the same words, or the two callers disagree: "kitchener, ontario,
          canada" searched under "Canada" drops "canada" and finds the city, and the same query
          searched under "CA" keeps it, requires it of a search_text that never contains a country,
          and returns a car dealership instead of Kitchener.
        */
        String displayName = displayCountry(country.trim());
        if (displayName != null) {
            countryWords.addAll(java.util.Arrays.asList(displayName.toLowerCase().split("[\\s,]+")));
        }
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (String word : words) {
            if (!countryWords.contains(word)) kept.add(word);
        }
        return kept.isEmpty() ? words : kept;
    }

    /** {@code search_text LIKE ? AND search_text LIKE ?…}, one per token. */
    private static String allTokensClause(int count) {
        return allTokensClause(count, "search_text");
    }

    private static String allTokensClause(int count, String column) {
        return String.join(" AND ", java.util.Collections.nCopies(count, column + " LIKE ?"));
    }

    /** Anchored on a sorted release, plain contains otherwise. */
    private String tokensClause(int count) {
        return tokensClause(count, searchTextSorted);
    }

    private String tokensClause(int count, boolean anchored) {
        return tokensClause(count, anchored, false);
    }

    /** @param folded test an accent-stripped column, so "montreal" reaches "Montréal" */
    private String tokensClause(int count, boolean anchored, boolean folded) {
        String column = folded ? "strip_accents(search_text)" : "search_text";
        return anchored ? anchoredTokensClause(count, column) : allTokensClause(count, column);
    }

    /**
     * Runs an anchored attempt first and falls back to contains when it finds nothing.
     *
     * <p>Anchoring matches the start of {@code search_text}, which is the place's own name. That is
     * what people type first, and it is the case worth making fast. It is NOT how somebody finds
     * "the Walmart in Kitchener" — that city sits in the middle of the string and never
     * prefix-matches — so an empty anchored result has to mean "ask the slow way", not "no such
     * place". Without this a sorted release would quietly stop answering city and street queries.
     *
     * <p>On an unsorted release there is nothing to gain and a fallback to pay for, so the anchored
     * attempt is skipped entirely and this is exactly today's single query.
     */
    private Future<JsonArray> anchoredThenContains(
            java.util.function.Function<Boolean, Future<JsonArray>> run) {
        if (!searchTextSorted) return run.apply(false);
        return run.apply(true).compose(found -> found.isEmpty() ? run.apply(false)
                                                                : Future.succeededFuture(found));
    }

    /**
     * The anchored fast path, but it may not SUPPRESS the answer it cannot reach.
     *
     * <h2>What went wrong with stopping on any hit</h2>
     *
     * <p>Anchoring matches the first token against the start of {@code search_text}, so it can only
     * find a row whose text begins with what was typed. The North Face is stored as
     * {@code "the north face the north face 1450 ala moana..."} - the article comes first - so
     * {@code 'north%'} cannot reach it. "Northwest Face &amp; Body" and "Northface Resort" can, and
     * finding those counted as success, so the contains pass that would have returned all 320 North
     * Face rows never ran. An incidental prefix match hid the chain somebody was looking for.
     *
     * <p>So emptiness is the wrong test. The anchored pass is trusted only when it found a row
     * matching the LITERAL reading of the query; anything else is a near miss that has to be
     * checked properly. A query whose anchored hits are all qualifier-stripped matches pays for the
     * contains scan, which is the cost of not hiding the answer.
     *
     * <p>The two result sets are merged rather than substituted, because an anchored hit is still a
     * real match: somebody searching "Northwest" should keep finding Northwest Face &amp; Body.
     */
    private Future<JsonArray> anchoredThenLiteralContains(
            java.util.function.Function<Boolean, Future<JsonArray>> run) {
        if (!searchTextSorted) return run.apply(false);
        return run.apply(true).compose(anchoredRows -> {
            if (hasLiteralHit(anchoredRows)) return Future.succeededFuture(anchoredRows);
            return run.apply(false).map(containsRows -> mergePlaces(anchoredRows, containsRows));
        });
    }

    /** Whether any row matched the query as typed, rather than only its stripped reading. */
    private static boolean hasLiteralHit(JsonArray rows) {
        for (int i = 0; i < rows.size(); i++) {
            JsonObject row = rows.getJsonObject(i);
            if (row != null && Boolean.TRUE.equals(row.getBoolean("literalHit"))) return true;
        }
        return false;
    }

    /** Contains results first, since they are the ones the anchored pass could not reach. */
    private static JsonArray mergePlaces(JsonArray anchoredRows, JsonArray containsRows) {
        JsonArray out = containsRows.copy();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < out.size(); i++) {
            JsonObject row = out.getJsonObject(i);
            if (row != null) seen.add(String.valueOf(row.getValue("sourcePlaceId")));
        }
        for (int i = 0; i < anchoredRows.size(); i++) {
            JsonObject row = anchoredRows.getJsonObject(i);
            if (row != null && seen.add(String.valueOf(row.getValue("sourcePlaceId")))) out.add(row);
        }
        return out;
    }

    /**
     * Binds the token patterns to match {@link #tokensClause}.
     *
     * <p>The leading token loses its leading wildcard on a sorted release - that is the whole
     * point of anchoring - and the rest stay as contains.
     */
    private void bindTokens(PreparedStatement statement, java.util.List<String> words, int from,
                            boolean anchored) throws SQLException {
        bindTokens(statement, words, from, anchored, false);
    }

    private void bindTokens(PreparedStatement statement, java.util.List<String> words, int from,
                            boolean anchored, boolean folded) throws SQLException {
        int i = from;
        boolean first = true;
        for (String word : words) {
            // Folded on both sides, so somebody who DOES type "Montréal" is not then excluded by
            // the very attempt added to help the people who do not.
            String token = folded ? stripAccents(word) : word;
            String pattern = (anchored && first) ? token + "%" : "%" + token + "%";
            statement.setString(i++, pattern);
            first = false;
        }
    }

    /**
     * Drops diacritics, matching DuckDB's {@code strip_accents}.
     *
     * <p>NFD splits a letter into its base plus its combining marks; stripping the mark block
     * leaves the base letter, so "Montréal" becomes "montreal".
     */
    static String stripAccents(String value) {
        if (value == null) return null;
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }


    /**
     * The same test, with the FIRST token anchored to the start of {@code search_text}.
     *
     * <h2>Why this exists</h2>
     *
     * <p>{@code LIKE '%needle%'} has a leading wildcard, so no Parquet row-group statistic can
     * exclude anything and every query reads the whole country partition. That is the entire cost
     * of this control. Measured against the live bucket, the United States places partition is
     * 16.1M rows and takes 16-21s through the backend, against 0.35s for Canada's 1.5M.
     *
     * <p>An anchored first token is a range predicate — {@code search_text >= 'austin' AND < 'austio'}
     * in effect — which min/max statistics CAN prune. Measured on the real 16.1M-row partition,
     * locally, at two threads:
     *
     * <pre>
     *   predicate                unsorted   sorted by search_text
     *   LIKE '%austin%'            0.59s          0.58s
     *   LIKE 'austin%'             0.36s          0.03s
     * </pre>
     *
     * <p><b>Sorting alone buys nothing and anchoring alone buys little; together they are ~20x.</b>
     * So this is half of a change: the other half is {@code ORDER BY search_text} in the corpus
     * build, and until a release is published that way this is worth about the 0.59 → 0.36
     * improvement rather than the 0.59 → 0.03 one. It is correct either way.
     *
     * <h2>Why the remaining tokens stay unanchored</h2>
     *
     * <p>{@code search_text} is the name, then the brand, street and city. Anchoring the leading
     * token matches what people actually type first — the name of the place or the city — while
     * "kitchener" for a Walmart on Ottawa Street appears in the middle of the string and never
     * prefix-matches. So the leading token narrows the scan and the rest filter inside it, and
     * {@link #allTokensClause} remains the fallback for a query that the anchored form misses.
     */
    private static String anchoredTokensClause(int count) {
        return anchoredTokensClause(count, "search_text");
    }

    private static String anchoredTokensClause(int count, String column) {
        if (count == 0) return "TRUE";
        StringBuilder sql = new StringBuilder(column + " LIKE ?");
        for (int i = 1; i < count; i++) sql.append(" AND ").append(column).append(" LIKE ?");
        return sql.toString();
    }

    /**
     * The country the QUERY ITSELF states, after a comma, or null when it states none.
     *
     * <p>"Washington, United States" states one; "Paris, Texas" does not, and "Washington" on its
     * own does not either. A stated country scopes the search and its words leave the token list,
     * because the corpus's {@code search_text} never contains a country name.
     *
     * <p><b>A bare two-letter code is deliberately not read as a country here.</b> The codes
     * people write after a comma are regions, and they collide with country codes almost
     * perfectly: CA is California and Canada, IN is Indiana and India, DE is Delaware and Germany,
     * LA is Louisiana and Laos. Reading "Los Angeles, CA" as Canada would be worse than not
     * reading it at all, so a stated country has to be spelled out or be a known alias of three
     * characters or more. The consequence is that "London, UK" is not scoped by this - it is two
     * characters - and that is the safe direction: it still searches worldwide and still finds
     * London.
     */
    static String statedCountry(String needle) {
        if (needle == null) return null;
        int comma = needle.lastIndexOf(',');
        if (comma < 0) return null;
        String tail = needle.substring(comma + 1).trim();
        if (tail.length() < 3) return null;
        return Countries.iso(tail);
    }

    /**
     * {@code query} with a trailing stated country removed, or unchanged when it states none.
     *
     * <p>Used by the tier decision, which asks whether a coarse row IS what was typed. A stated
     * country scopes the search and is not part of the city's name, so it must not be required to
     * appear in the label: the corpus calls it "Paris, &Icirc;le-de-France, France", and "paris
     * france" is not a prefix of that.
     */
    public static String withoutStatedCountry(String query) {
        if (query == null || statedCountry(query) == null) return query;
        return query.substring(0, query.lastIndexOf(',')).trim();
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
    /** The English display name for a country given its code, its name or an alias, or null. */
    private static String displayCountry(String country) {
        return Countries.displayName(country);
    }

    /**
     * Delegated to {@link Countries}, which is the one table that knows what a country is called.
     *
     * <p>This method used to decide for itself, and got two things wrong. It accepted ANY two
     * letters as a code without checking the code exists, so "UK" became the partition {@code UK}
     * - not an ISO country, since the United Kingdom is {@code GB} - and the lookup reported
     * success while addressing a country absent from the data. And it compared the text only with
     * JDK display names, so "USA", "UAE", "Britain" and "Holland" resolved to nothing.
     *
     * <p>Kept as a method because its callers are many and it reads better where they are.
     */
    public static String iso(String country) {
        return Countries.iso(country);
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
