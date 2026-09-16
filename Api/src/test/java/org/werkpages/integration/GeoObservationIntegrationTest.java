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
import org.werkpages.service.DeclaredLocation;
import org.werkpages.service.SubmissionContext;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.ManagerService;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The private audit trail: what we observed about where a submission came from.
 *
 * <p>The point of this table is that it survives an edit. Until it existed, a person who corrected
 * the location on a form overwrote the Cloudflare-inferred value and it was gone, which made the one
 * question worth asking of this data — does the claim match what we saw — unanswerable. So these
 * tests care about two things above all: that a row is written for every submission, and that the
 * write is genuinely inside the caller's transaction rather than alongside it.
 */
@Testcontainers
class GeoObservationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool           pool;
    static ManagerService service;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load()
            .migrate();

        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        pool = PgPool.pool(connectOptions, new PoolOptions().setMaxSize(5));

        service = new ManagerService(
            new ManagerRepository(pool), new ReviewRepository(pool), new UserRepository(pool),
            new EditRepository(pool), new ReportRepository(pool), pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, companies, users, geo_observations CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── One row per submission ────────────────────────────────────────────────

    @Test
    void createManager_recordsWhatWasObserved() throws Exception {
        insertUser("auth0|obs-create", "ObsCreate");

        Row created = await(service.createManager(
            "auth0|obs-create",
            validCreateManagerBody("Dana Observed", "ObsCorp", "Director"),
            null,
            observedFrom("Canada", "Ontario", "Toronto")));

        Row obs = singleObservation();
        assertEquals("manager", obs.getString("subject_type"));
        assertEquals("create",  obs.getString("action"));
        assertEquals(String.valueOf(created.getLong("id")), obs.getString("subject_id"));
        assertEquals("Canada",  obs.getString("country"));
        assertEquals("Ontario", obs.getString("region"));
        assertEquals("Toronto", obs.getString("city"));
    }

    @Test
    void createManager_observedIsIndependentOfWhatTheFormSaid() throws Exception {
        /*
          The whole reason the table exists. The body carries the declared location — here, somebody
          correcting Toronto to Waterloo — and the observation carries what we actually saw. Both
          have to survive, or "people do lie" is not a question the data can answer.
        */
        insertUser("auth0|obs-differs", "ObsDiffers");

        JsonObject body = validCreateManagerBody("Pat Differs", "DiffCorp", "Manager")
            .put("country", "Canada").put("state", "Ontario").put("city", "Waterloo");

        Row created = await(service.createManager("auth0|obs-differs", body, null,
            observedFrom("Canada", "Ontario", "Toronto")));

        Row manager = await(pool.preparedQuery("SELECT city FROM managers WHERE id = $1")
            .execute(Tuple.of(created.getLong("id"))).map(rs -> rs.iterator().next()));
        assertEquals("Waterloo", manager.getString("city"), "the form's value is what is stored on the manager");

        assertEquals("Toronto", singleObservation().getString("city"),
            "the observed value must not be overwritten by the declared one");
    }

    @Test
    void createManager_withoutCloudflareHeaders_stillRecordsARow() throws Exception {
        /*
          A request that reached the origin with no location headers is itself worth being able to
          see later, so an empty observation is recorded rather than skipped. It also keeps "one row
          per submission" true, which is what makes a missing row meaningful.
        */
        insertUser("auth0|obs-none", "ObsNone");

        await(service.createManager("auth0|obs-none",
            validCreateManagerBody("Sam Headerless", "NoHeaderCo", "Lead"), null,
            SubmissionContext.NONE));

        Row obs = singleObservation();
        assertNull(obs.getString("country"));
        assertNull(obs.getString("region"));
        assertNull(obs.getString("city"));
    }

    @Test
    void createManager_calledWithoutARequest_stillRecordsARow() throws Exception {
        // The three-argument overload, used by callers with no HTTP context behind them.
        insertUser("auth0|obs-overload", "ObsOverload");

        await(service.createManager("auth0|obs-overload",
            validCreateManagerBody("Lee Overload", "OverloadCo", "Manager"), null));

        assertEquals(1, observationCount());
    }

    @Test
    void createReview_recordsAgainstTheReview_notTheManager() throws Exception {
        /*
          An opinion is its own contribution and gets its own observation, keyed to the review rather
          than the manager it is about. A manager collects opinions from many people in many places;
          rolling them up under the manager's id would lose exactly the detail worth keeping.
        */
        insertUser("auth0|obs-review", "ObsReview");
        JsonObject ghost = await(service.createGhostManager(new JsonObject()
            .put("name", "Robin Rated").put("company", "RateCo").put("title", "Manager")
            .put("country", "Canada"), null));
        long managerId = ghost.getLong("id");

        assertEquals(1, observationCount(), "creating the ghost is itself a submission");

        await(service.createReview("auth0|obs-review", managerId,
            validReviewBody("RateCo", "Manager"), null,
            observedFrom("Canada", "British Columbia", "Vancouver")));

        // Two submissions, two rows. The ghost's observation is not overwritten or reused by the
        // rating that follows it - they were made by different people at different times.
        assertEquals(2, observationCount());

        Row obs = observationOfType("review");
        assertEquals("review",    obs.getString("action"));
        assertEquals("Vancouver", obs.getString("city"));
        assertNotEquals(String.valueOf(managerId), obs.getString("subject_id"),
            "the observation should be keyed to the review, not the manager");
    }

    @Test
    void anonymousSearch_recordsTheSearchItself_withNoSubject() throws Exception {
        /*
          A capture that finds the manager already exists writes no row, and an observation hung off
          a created manager would therefore miss it. What happened is that somebody searched, so
          that is what gets recorded — with a null subject, because there is nothing to point at.
        */
        JsonObject body = new JsonObject()
            .put("name", "Casey Searched").put("company", "SearchCo")
            .put("title", "Manager").put("country", "Canada");

        await(service.captureAnonymousSearch(body, null, observedFrom("Canada", "Quebec", "Montreal")));

        Row obs = singleObservation();
        assertEquals("search",   obs.getString("subject_type"));
        assertEquals("search",   obs.getString("action"));
        assertNull(obs.getString("subject_id"), "a search has no row to point at");
        assertEquals("Montreal", obs.getString("city"));
    }

    @Test
    void repeatSearchForAManagerWeAlreadyHold_isStillRecorded() throws Exception {
        // The second capture creates nothing. It is still a search, and still evidence.
        JsonObject body = new JsonObject()
            .put("name", "Repeat Searched").put("company", "RepeatCo")
            .put("title", "Manager").put("country", "Canada");

        await(service.captureAnonymousSearch(body, null, observedFrom("Canada", null, "Ottawa")));
        await(service.captureAnonymousSearch(body, null, observedFrom("Canada", null, "Ottawa")));

        assertEquals(2, observationCount(), "the repeat search must not be swallowed");
    }

    @Test
    void createGhostManager_recordsAgainstTheGhost() throws Exception {
        JsonObject ghost = await(service.createGhostManager(new JsonObject()
            .put("name", "Ghost Observed").put("company", "GhostCo").put("title", "Manager")
            .put("country", "Canada"), null, observedFrom("Canada", "Alberta", "Calgary")));

        Row obs = singleObservation();
        assertEquals("manager", obs.getString("subject_type"));
        assertEquals("create",  obs.getString("action"));
        assertEquals(String.valueOf(ghost.getLong("id")), obs.getString("subject_id"));
        assertEquals("Calgary", obs.getString("city"));
    }

    // ── The write is inside the transaction, not beside it ────────────────────

    @Test
    void aFailedObservation_rollsBackTheManager() throws Exception {
        /*
          This is the test that distinguishes "composed into the transaction" from "fired and
          forgotten", and it is the only way to tell them apart from the outside — both look
          identical while everything is working.

          A CHECK constraint makes the observation insert fail for one sentinel value. If the
          observation is part of the manager's transaction, the manager must not exist afterwards.

          Verified by mutation: recording on the pool instead of the transaction's connection, with
          the result discarded, fails this test on the assertThrows above. Note what it does *not*
          catch — swapping compose() for andThen() while still using `conn` also passes, because a
          failed statement poisons the Postgres transaction regardless of whether anyone waited on
          it. The guarantee this test actually pins is the one that matters: the observation is
          written on the same connection as the work it describes.
        */
        insertUser("auth0|obs-rollback", "ObsRollback");
        await(pool.query("""
            ALTER TABLE geo_observations
            ADD CONSTRAINT geo_observations_test_reject
            CHECK (country IS DISTINCT FROM 'Rejectistan')
            """).execute());
        try {
            Future<Row> attempt = service.createManager("auth0|obs-rollback",
                validCreateManagerBody("Ghost Rollback", "RollbackCo", "Manager"), null,
                observedFrom("Rejectistan", null, null));

            assertThrows(Exception.class, () -> await(attempt),
                "the constraint should have failed the observation insert");

            assertEquals(0, count("SELECT count(*) FROM managers WHERE company = 'RollbackCo'"),
                "the manager was committed even though its observation failed - the insert is not "
                + "inside the transaction");
            assertEquals(0, observationCount());
        } finally {
            await(pool.query("ALTER TABLE geo_observations DROP CONSTRAINT geo_observations_test_reject").execute());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────


    /** A submission that observed this location and declared nothing - the shape these tests care about. */
    private static SubmissionContext observedFrom(String country, String region, String city) {
        return new SubmissionContext(new GeoObservation(country, region, city), DeclaredLocation.NONE);
    }

    private static Row singleObservation() throws Exception {
        assertEquals(1, observationCount(), "expected exactly one observation");
        return await(pool.query(
                "SELECT subject_type, subject_id, action, country, region, city FROM geo_observations")
            .execute().map(rs -> rs.iterator().next()));
    }

    private static Row observationOfType(String subjectType) throws Exception {
        return await(pool.preparedQuery("""
                SELECT subject_type, subject_id, action, country, region, city
                FROM geo_observations WHERE subject_type = $1
                """)
            .execute(Tuple.of(subjectType))
            .map(rs -> {
                var it = rs.iterator();
                assertTrue(it.hasNext(), "no observation of type " + subjectType);
                return it.next();
            }));
    }

    private static int observationCount() throws Exception {
        return count("SELECT count(*) FROM geo_observations");
    }

    private static int count(String sql) throws Exception {
        return await(pool.query(sql).execute().map(rs -> rs.iterator().next().getInteger(0)));
    }

    private static void insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery("INSERT INTO users(auth0_id, username, email) VALUES ($1,$2,$3)")
            .execute(Tuple.of(auth0Id, username, username + "@example.com")));
    }

    private static JsonObject validReviewBody(String company, String title) {
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
            .put("overallRating",  4.0)
            .put("ratings",        ratings)
            .put("managerCompany", company)
            .put("managerTitle",   title)
            .put("workedFrom",     "2022-01")
            .put("authorType",     "anonymous")
            .put("author",         "AnonRater42");
    }

    private static JsonObject validCreateManagerBody(String name, String company, String title) {
        JsonObject ratings = new JsonObject();
        for (String key : new String[]{
                "Communication Style", "Perceived Approachability",
                "Perceived Clarity of Expectations", "Feedback Style",
                "Perceived Supportiveness", "Decision Making Style",
                "Organization and Planning Style", "Delegation Style",
                "Perceived Professional Demeanor", "Overall Working Experience"}) {
            ratings.put(key, 4.0);
        }
        JsonObject review = new JsonObject()
            .put("overallRating",  4.0)
            .put("ratings",        ratings)
            .put("managerCompany", company)
            .put("managerTitle",   title)
            .put("workedFrom",     "2022-01")
            .put("authorType",     "anonymous")
            .put("author",         "AnonTester99");
        return new JsonObject()
            .put("name",      name)
            .put("company",   company)
            .put("title",     title)
            .put("image",     "img")
            .put("country",   "US")
            .put("status",    "active")
            .put("startDate", "2020-01")
            .put("review",    review);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
