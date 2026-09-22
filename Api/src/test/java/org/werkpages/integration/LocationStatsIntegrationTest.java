package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.GeoObservation;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.DeclaredLocation;
import org.werkpages.service.LocationStatsProjector;
import org.werkpages.service.LocationStatsRebuilder;
import org.werkpages.service.ManagerService;
import org.werkpages.service.SubmissionContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The location read model: one contribution, every scope it counts toward.
 *
 * <p>The most valuable test here is {@link #incrementalAndRebuildAgree}. The incremental projector
 * and the full reconstruction are independent routes to the same numbers, so their agreement is
 * evidence in a way that either one alone is not.
 */
@Testcontainers
class LocationStatsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool                  pool;
    static ManagerService        service;
    static LocationStatsRebuilder rebuilder;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load()
            .migrate();

        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword()), new PoolOptions().setMaxSize(5));

        service = new ManagerService(
            new ManagerRepository(pool), new ReviewRepository(pool), new UserRepository(pool),
            new EditRepository(pool), new ReportRepository(pool), pool);
        rebuilder = new LocationStatsRebuilder(pool, new LocationStatsProjector());
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("""
            TRUNCATE managers, companies, users, geo_observations, company_locations,
                     manager_location_stats_live, manager_location_stats_rebuild CASCADE
            """).execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── One contribution, every rung ──────────────────────────────────────────

    @Test
    void oneRatingCountsTowardEveryScopeInItsHierarchy() throws Exception {
        long companyId = insertCompany("Walmart");
        long gerrard = insertLocation(companyId, "1000 Gerrard St E", "Toronto", "Ontario", "Canada");
        long managerId = ghost("Hierarchy Target", companyId);
        rate("auth0|hier", managerId, exactAt(gerrard));

        assertEquals(1, count("company", ""));
        assertEquals(1, count("country", "canada"));
        assertEquals(1, count("state",   "canada:ontario"));
        assertEquals(1, count("city",    "canada:ontario:toronto"));
        assertEquals(1, count("place",   String.valueOf(gerrard)));
    }

    @Test
    void aCityOnlyRatingStopsAtCity() throws Exception {
        /*
          Somebody who knows the city but not the branch contributes to four scopes, not five. The
          place row must not be invented - a filter on it would then show a number no contribution
          actually supports.
        */
        long companyId = insertCompany("Walmart");
        long managerId = ghost("City Only", companyId);
        rate("auth0|cityonly", managerId, cityLevel("Canada", "Ontario", "Kitchener"));

        assertEquals(1, count("city", "canada:ontario:kitchener"));
        assertEquals(0, scopeRows("place"));
    }

    @Test
    void aRatingThatDeclaredNothingStillCountsCompanyWide() throws Exception {
        // An unlocated opinion is not a missing opinion. It has to reach the overall number or the
        // company page would quietly under-report itself.
        long companyId = insertCompany("Walmart");
        long managerId = ghost("No Location", companyId);
        rate("auth0|noloc", managerId, new JsonObject());

        assertEquals(1, count("company", ""));
        assertEquals(0, scopeRows("country"));
    }

    @Test
    void citiesWithTheSameNameInDifferentProvincesAreDifferentScopes() throws Exception {
        // London, Ontario and London, England must never share a bucket.
        long companyId = insertCompany("Walmart");
        long a = ghost("Lorna Donovan", companyId);
        long b = ghost("Liam Donnelly", companyId);
        rate("auth0|lon-on", a, cityLevel("Canada", "Ontario", "London"));
        rate("auth0|lon-uk", b, cityLevel("United Kingdom", "England", "London"));

        assertEquals(1, count("city", "canada:ontario:london"));
        assertEquals(1, count("city", "united-kingdom:england:london"));
        assertEquals(2, count("company", ""));
    }

    @Test
    void punctuationDoesNotSplitACity() throws Exception {
        long companyId = insertCompany("Walmart");
        long a = ghost("Sasha Alpha", companyId);
        long b = ghost("Sasha Beta", companyId);
        rate("auth0|stj-1", a, cityLevel("Canada", "Newfoundland", "St. John's"));
        rate("auth0|stj-2", b, cityLevel("Canada", "Newfoundland", "st johns"));

        assertEquals(2, count("city", "canada:newfoundland:st-johns"),
            "two spellings of one city must land in one scope");
    }

    // ── Sums, and averages computed from them ─────────────────────────────────

    @Test
    void sumsAccumulateSoAnAverageCanBeComputedOnRead() throws Exception {
        long companyId = insertCompany("Walmart");
        long m1 = ghost("Rated One", companyId);
        long m2 = ghost("Rated Two", companyId);
        rate("auth0|sum-1", m1, cityLevel("Canada", "Ontario", "Toronto").put("overallRating", 5.0));
        rate("auth0|sum-2", m2, cityLevel("Canada", "Ontario", "Toronto").put("overallRating", 3.0));

        Row scope = scope("city", "canada:ontario:toronto");
        assertEquals(2, scope.getLong("review_count"));
        assertEquals(0, new BigDecimal("8.0").compareTo(scope.getBigDecimal("rating_sum")));
    }

    // ── The evidence ──────────────────────────────────────────────────────────

    @Test
    void incrementalAndRebuildAgree() throws Exception {
        /*
          The strongest correctness argument available for a derived table. The incremental path ran
          as each rating was written; the rebuild recomputes everything from `reviews` alone. They
          are independent routes to the same arithmetic, so agreement is evidence - and a rebuild
          that silently disagreed would mean the numbers on the page had been wrong.
        */
        long companyId = insertCompany("Walmart");
        long gerrard = insertLocation(companyId, "1000 Gerrard St E", "Toronto", "Ontario", "Canada");
        long m1 = ghost("Agree One", companyId);
        long m2 = ghost("Agree Two", companyId);
        long m3 = ghost("Agree Three", companyId);
        rate("auth0|ag-1", m1, exactAt(gerrard));
        rate("auth0|ag-2", m2, cityLevel("Canada", "Ontario", "Toronto"));
        rate("auth0|ag-3", m3, new JsonObject());

        String before = snapshot();
        LocationStatsRebuilder.RebuildResult result = await(rebuilder.rebuildManagerStats());
        String after = snapshot();

        assertEquals(before, after, "a rebuild from source must reproduce the incremental result");
        assertTrue(result.reviewsRead() >= 3, "the rebuild should have read the contributions");
    }

    @Test
    void reconciliationIsSilentWhenTheProjectionIsCorrect() throws Exception {
        long companyId = insertCompany("Walmart");
        long managerId = ghost("Rita Reconciled", companyId);
        rate("auth0|recon", managerId, cityLevel("Canada", "Ontario", "Toronto"));

        assertTrue(await(rebuilder.reconcile()).isEmpty());
    }

    @Test
    void reconciliationReportsDriftItDidNotCause() throws Exception {
        /*
          Silent divergence is the failure mode that makes derived tables frightening, so the check
          has to actually catch a projection that has drifted - not merely confirm a job ran.
        */
        long companyId = insertCompany("Walmart");
        long managerId = ghost("Dana Drifted", companyId);
        rate("auth0|drift", managerId, cityLevel("Canada", "Ontario", "Toronto"));

        // Corrupt the projection behind the projector's back, as a bug would.
        await(pool.query("""
            UPDATE manager_location_stats_live SET review_count = review_count + 7
             WHERE scope_type = 'company'
            """).execute());

        List<LocationStatsRebuilder.Drift> drift = await(rebuilder.reconcile());
        assertEquals(1, drift.size());
        assertEquals(8, drift.get(0).projected());
        assertEquals(1, drift.get(0).actual());

        // And a rebuild is the repair.
        await(rebuilder.rebuildManagerStats());
        assertTrue(await(rebuilder.reconcile()).isEmpty(), "a rebuild must put it right");
    }


    // ── Leaving and re-entering the projection ────────────────────────────────

    /*
      The rebuild defines what belongs in the read model:

          disposition = 'live' AND deleted_at IS NULL AND weight = FALSE AND company_id IS NOT NULL

      Every way a review crosses that line has to move its contribution with it, or the live table
      and a rebuild disagree - which is the failure that makes a derived table frightening. Only
      creation maintained it; the other four transitions did not, so reconcile() is the assertion
      throughout: it compares the projection against the source and is the same check that runs in
      production.
    */

    @Test
    void deletingARatingTakesItOutOfTheProjection() throws Exception {
        long companyId = insertCompany("Walmart");
        long managerId = ghost("Deb Deleted", companyId);
        rate("auth0|del", managerId, cityLevel("Canada", "Ontario", "Toronto"));

        await(service.deleteReview("auth0|del", managerId, ownReviewId("auth0|del", managerId)));

        assertTrue(await(rebuilder.reconcile()).isEmpty(),
            "a deleted rating is hidden from the public, so it must stop counting toward the figures");
    }

    @Test
    void aRatingThatComesBackAfterTheDeleteWindowCountsAgain() throws Exception {
        /*
          A delete here is a soft delete: the row is hidden for three days and then resurfaces as
          anonymous. So the projection has to move in BOTH directions - subtracting on delete and
          adding back on restore. Subtracting only would leave every restored rating permanently
          uncounted, and nothing would ever say so.
        */
        long companyId = insertCompany("Walmart");
        long managerId = ghost("Rea Restored", companyId);
        rate("auth0|res", managerId, cityLevel("Canada", "Ontario", "Toronto"));
        await(service.deleteReview("auth0|res", managerId, ownReviewId("auth0|res", managerId)));

        // Age the deletion past the window, exactly as three days would.
        await(pool.query("UPDATE reviews SET deleted_at = now() - INTERVAL '4 days' WHERE deleted_at IS NOT NULL").execute());
        await(service.restoreExpiredReviewDeletions());

        assertTrue(await(rebuilder.reconcile()).isEmpty(),
            "a restored rating is public again, so it must count again");
    }

    @Test
    void holdingARatingTakesItOutUntilItIsReleased() throws Exception {
        /*
          A held rating is withheld from the public until its author proves the submission was
          human. It is invisible on the page, so it must be invisible in the figures - and visible
          again the moment it is released.
        */
        long companyId = insertCompany("Walmart");
        long managerId = ghost("Hal Held", companyId);
        rate("auth0|held", managerId, cityLevel("Canada", "Ontario", "Toronto"));
        UUID reviewId = ownReviewId("auth0|held", managerId);

        await(service.setReviewDisposition(reviewId, "held"));
        assertTrue(await(rebuilder.reconcile()).isEmpty(), "held is not public, so it must not count");

        await(service.setReviewDisposition(reviewId, "live"));
        assertTrue(await(rebuilder.reconcile()).isEmpty(), "released is public again, so it must count");
    }

    @Test
    void aRejectedRatingStaysOut() throws Exception {
        long companyId = insertCompany("Walmart");
        long managerId = ghost("Rex Rejected", companyId);
        rate("auth0|rej", managerId, cityLevel("Canada", "Ontario", "Toronto"));

        await(service.setReviewDisposition(ownReviewId("auth0|rej", managerId), "rejected"));

        assertTrue(await(rebuilder.reconcile()).isEmpty(),
            "a rejected rating is never published, so it must never count");
    }

    /** The id of the one rating this user left on this manager. */
    private static UUID ownReviewId(String auth0Id, long managerId) throws Exception {
        return await(pool.preparedQuery("""
                SELECT r.id FROM reviews r
                JOIN users u ON u.id = r.user_id
                WHERE u.auth0_id = $1 AND r.manager_id = $2
                """)
            .execute(io.vertx.sqlclient.Tuple.of(auth0Id, managerId))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static JsonObject cityLevel(String country, String state, String city) {
        return baseReview()
            .put("declaredCountry", country).put("declaredState", state)
            .put("declaredCity", city).put("declaredPrecision", DeclaredLocation.CITY);
    }

    private static JsonObject exactAt(long locationId) {
        return baseReview()
            .put("companyLocationId", locationId).put("declaredPrecision", DeclaredLocation.EXACT);
    }

    private static void rate(String auth0Id, long managerId, JsonObject extra) throws Exception {
        insertUser(auth0Id, auth0Id.replace("auth0|", "").replace("-", ""));
        JsonObject body = baseReview().mergeIn(extra);
        await(service.createReview(auth0Id, managerId, body, null,
            SubmissionContext.of(new GeoObservation("Canada", "Ontario", "Toronto"), body)));
    }

    private static JsonObject baseReview() {
        JsonObject ratings = new JsonObject();
        for (String key : new String[]{
                "Communication Style", "Perceived Approachability",
                "Perceived Clarity of Expectations", "Feedback Style",
                "Perceived Supportiveness", "Decision Making Style",
                "Organization and Planning Style", "Delegation Style",
                "Perceived Professional Demeanor", "Overall Working Experience"}) {
            ratings.put(key, 4.0);
        }
        return new JsonObject()
            .put("overallRating", 4.0).put("ratings", ratings)
            .put("managerCompany", "Walmart").put("managerTitle", "Store Manager")
            .put("workedFrom", "2022-01")
            .put("authorType", "anonymous").put("author", "AnonRater42");
    }

    private static long ghost(String name, long companyId) throws Exception {
        JsonObject g = await(service.createGhostManager(new JsonObject()
            .put("name", name).put("company", "Walmart").put("title", "Store Manager")
            .put("country", "Canada").put("companyId", companyId), null));
        return g.getLong("id");
    }

    /** Every scope row, ordered, as one comparable string. */
    private static String snapshot() throws Exception {
        return await(pool.query("""
                SELECT company_id, scope_type, scope_key, review_count, rating_sum,
                       communication_style_sum, communication_style_count
                FROM manager_location_stats_live
                WHERE review_count <> 0
                ORDER BY company_id, scope_type, scope_key
                """).execute()
            .map(rs -> {
                StringBuilder sb = new StringBuilder();
                for (Row r : rs) {
                    sb.append(r.getLong("company_id")).append('|')
                      .append(r.getString("scope_type")).append('|')
                      .append(r.getString("scope_key")).append('|')
                      .append(r.getLong("review_count")).append('|')
                      .append(r.getBigDecimal("rating_sum").stripTrailingZeros().toPlainString()).append('|')
                      .append(r.getBigDecimal("communication_style_sum").stripTrailingZeros().toPlainString()).append('|')
                      .append(r.getLong("communication_style_count")).append('\n');
                }
                return sb.toString();
            }));
    }

    private static Row scope(String type, String key) throws Exception {
        return await(pool.preparedQuery("""
                SELECT review_count, rating_sum FROM manager_location_stats_live
                WHERE scope_type = $1 AND scope_key = $2
                """).execute(Tuple.of(type, key)).map(rs -> {
                    var it = rs.iterator();
                    assertTrue(it.hasNext(), "no scope row for " + type + ":" + key);
                    return it.next();
                }));
    }

    private static long count(String type, String key) throws Exception {
        return await(pool.preparedQuery("""
                SELECT COALESCE(SUM(review_count), 0) AS n FROM manager_location_stats_live
                WHERE scope_type = $1 AND scope_key = $2
                """).execute(Tuple.of(type, key)).map(rs -> rs.iterator().next().getLong("n")));
    }

    private static long scopeRows(String type) throws Exception {
        return await(pool.preparedQuery("""
                SELECT count(*) AS n FROM manager_location_stats_live
                WHERE scope_type = $1 AND review_count <> 0
                """).execute(Tuple.of(type)).map(rs -> rs.iterator().next().getLong("n")));
    }

    private static long insertCompany(String name) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies(name, slug, status) VALUES ($1,$2,'approved') RETURNING id")
            .execute(Tuple.of(name, name.toLowerCase()))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static long insertLocation(long companyId, String street, String city,
                                       String state, String country) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO company_locations
                    (company_id, source, source_place_id, display_name, street, city, state, country)
                VALUES ($1, 'manual', $2, $3, $4, $5, $6, $7) RETURNING id
                """)
            .execute(Tuple.of(companyId, street, "Store", street, city, state, country))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static void insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO users(auth0_id, username, email) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING")
            .execute(Tuple.of(auth0Id, username, username + "@example.com")));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
