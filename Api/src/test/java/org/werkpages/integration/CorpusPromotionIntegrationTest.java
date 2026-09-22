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
import org.junit.jupiter.api.DisplayName;
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
import org.werkpages.service.ManagerService;
import org.werkpages.service.SubmissionContext;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Promoting a building chosen from the search corpus into a real location.
 *
 * <p>The corpus is 73.6M places in S3; {@code company_locations} holds only the ones somebody
 * actually picked. These tests cover the moment one crosses over — which happens on submit, inside
 * the contribution's own transaction, and never on suggestion.
 *
 * <p>No corpus is read here. The suggestion half is a query against Parquet and is exercised
 * separately; what matters at this boundary is what arrives in the request body.
 */
@Testcontainers
class CorpusPromotionIntegrationTest {

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
        await(pool.query(
            "TRUNCATE managers, companies, users, geo_observations, company_locations CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── Promotion ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a corpus place becomes a location row, and the manager points at it")
    void promotesOnSubmit() throws Exception {
        insertUser("auth0|cp-new", "CpNew");
        JsonObject body = bodyWithCorpusPlace("Dana Branch", "PromoteCo", "gers-1");

        Row created = await(service.createManager("auth0|cp-new", body, null, submission(body)));
        Row m = manager(created.getLong("id"));

        Long locationId = m.getLong("company_location_id");
        assertNotNull(locationId, "the chosen building should have been promoted");
        assertEquals("exact", m.getString("declared_precision"));

        Row location = location(locationId);
        assertEquals("overture",          location.getString("source"));
        assertEquals("gers-1",            location.getString("source_place_id"));
        assertEquals("Walmart",           location.getString("display_name"));
        assertEquals("1400 Ottawa St S",  location.getString("street"));
        assertEquals("Kitchener",         location.getString("city"));
        assertEquals("active",            location.getString("status"));
    }

    @Test
    @DisplayName("the coarse values are derived from the location, not from the body")
    void coarseValuesComeFromTheLocation() throws Exception {
        insertUser("auth0|cp-coarse", "CpCoarse");
        // The body claims Vancouver while the building is in Kitchener - a form that drifted, or a
        // crafted request. The stored geography must describe the building.
        JsonObject body = bodyWithCorpusPlace("Sam Drift", "DriftCo", "gers-2")
            .put("declaredCity", "Vancouver")
            .put("declaredState", "British Columbia");

        Row created = await(service.createManager("auth0|cp-coarse", body, null, submission(body)));
        Row m = manager(created.getLong("id"));

        assertEquals("Kitchener", m.getString("declared_city"));
    }

    @Test
    @DisplayName("two people choosing the same store reach one row, not two")
    void promotionIsIdempotent() throws Exception {
        insertUser("auth0|cp-a", "CpA");
        insertUser("auth0|cp-b", "CpB");

        JsonObject first  = bodyWithCorpusPlace("First Picker", "SharedCo", "gers-same");
        Row one = await(service.createManager("auth0|cp-a", first, null, submission(first)));

        JsonObject second = bodyWithCorpusPlace("Second Picker", "SharedCo", "gers-same");
        Row two = await(service.createManager("auth0|cp-b", second, null, submission(second)));

        Long firstLocation  = manager(one.getLong("id")).getLong("company_location_id");
        Long secondLocation = manager(two.getLong("id")).getLong("company_location_id");

        // Two rows for one building would split that store's ratings across entries that look
        // identical on the page - the exact failure the unique index on
        // (company_id, source, source_place_id) exists to prevent.
        assertEquals(firstLocation, secondLocation);
        assertEquals(1, countLocations(), "one physical store, one row");
    }

    @Test
    @DisplayName("a corpus place with no city is rejected")
    void rejectsUnusablePlace() throws Exception {
        insertUser("auth0|cp-bad", "CpBad");
        JsonObject body = bodyWithCorpusPlace("No City", "NoCityCo", "gers-3");
        body.getJsonObject("corpusPlace").putNull("city");

        Future<Row> attempt = service.createManager("auth0|cp-bad", body, null, submission(body));
        assertThrows(Exception.class, () -> await(attempt));
        assertEquals(0, countLocations(), "a rejected submission must leave no location behind");
    }

    @Test
    @DisplayName("exact precision with neither an id nor a place is rejected")
    void exactWithoutAnythingIsRejected() throws Exception {
        insertUser("auth0|cp-empty", "CpEmpty");
        JsonObject body = validCreateManagerBody("Empty Exact", "EmptyCo", "Manager")
            .put("declaredPrecision", DeclaredLocation.EXACT);

        Future<Row> attempt = service.createManager("auth0|cp-empty", body, null, submission(body));
        assertThrows(Exception.class, () -> await(attempt));
    }

    @Test
    @DisplayName("a failed submission promotes nothing")
    void promotionIsTransactional() throws Exception {
        insertUser("auth0|cp-tx", "CpTx");
        // Missing the required name, so the insert fails after the location would have been
        // created. A building nobody ever worked at, sitting in the facets forever, is exactly what
        // sharing the caller's transaction prevents.
        JsonObject body = bodyWithCorpusPlace("Will Fail", "TxCo", "gers-tx").putNull("name");

        Future<Row> attempt = service.createManager("auth0|cp-tx", body, null, submission(body));
        assertThrows(Exception.class, () -> await(attempt));
        assertEquals(0, countLocations());
    }

    @Test
    @DisplayName("a body with no corpus place still submits as coarse geography")
    void coarseStillWorks() throws Exception {
        insertUser("auth0|cp-coarse2", "CpCoarse2");
        JsonObject body = validCreateManagerBody("Priya Nandakumar", "CoarseCo", "Manager")
            .put("declaredCountry", "Canada")
            .put("declaredState", "Ontario")
            .put("declaredCity", "Kitchener")
            .put("declaredPrecision", DeclaredLocation.CITY);

        Row created = await(service.createManager("auth0|cp-coarse2", body, null, submission(body)));
        Row m = manager(created.getLong("id"));

        assertEquals("city", m.getString("declared_precision"));
        assertNull(m.getLong("company_location_id"));
        assertEquals(0, countLocations(), "coarse geography creates no building");
    }

    @Test
    @DisplayName("search paths never promote, even when a body carries a place")
    void searchPathsDropTheCorpusPlace() throws Exception {
        // withoutDeclared() strips the declaration on /find and anonymous capture. The chosen
        // building has to go with it, or a search could quietly create a location row.
        SubmissionContext stripped = submission(
            bodyWithCorpusPlace("Ghosty", "GhostCo", "gers-ghost")).withoutDeclared();

        assertTrue(stripped.declared().isEmpty());
        assertNull(stripped.corpusPlace());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static SubmissionContext submission(JsonObject body) {
        return SubmissionContext.of(new GeoObservation("Canada", "Ontario", "Toronto"), body);
    }

    private static JsonObject corpusPlace(String sourcePlaceId) {
        return new JsonObject()
            .put("sourcePlaceId", sourcePlaceId)
            .put("name", "Walmart")
            .put("street", "1400 Ottawa St S")
            .put("city", "Kitchener")
            .put("stateCode", "ON")
            .put("countryCode", "CA")
            .put("postalCode", "N2E 4E2");
    }

    private static JsonObject bodyWithCorpusPlace(String name, String company, String sourcePlaceId) {
        return validCreateManagerBody(name, company, "Manager")
            .put("declaredPrecision", DeclaredLocation.EXACT)
            .put("corpusPlace", corpusPlace(sourcePlaceId));
    }

    private static Row manager(long id) throws Exception {
        return await(pool.preparedQuery("""
                SELECT declared_country, declared_state, declared_city,
                       declared_precision, company_location_id
                FROM managers WHERE id = $1
                """).execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private static Row location(long id) throws Exception {
        return await(pool.preparedQuery("""
                SELECT source, source_place_id, display_name, street, city, state,
                       state_code, country, country_code, postal_code, status
                FROM company_locations WHERE id = $1
                """).execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private static int countLocations() throws Exception {
        return await(pool.query("SELECT COUNT(*) AS n FROM company_locations").execute()
            .map(rs -> rs.iterator().next().getInteger("n")));
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
            .put("overallRating", 4.0).put("ratings", ratings)
            .put("managerCompany", company).put("managerTitle", title)
            .put("workedFrom", "2022-01")
            .put("authorType", "anonymous").put("author", "AnonRater42");
    }

    private static JsonObject validCreateManagerBody(String name, String company, String title) {
        return new JsonObject()
            .put("name", name).put("company", company).put("title", title)
            .put("image", "img").put("country", "US").put("status", "active")
            .put("startDate", "2020-01")
            .put("review", validReviewBody(company, title));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
