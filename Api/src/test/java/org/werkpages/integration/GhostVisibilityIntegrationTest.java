package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bug that reached production: search, get a tile, click it, "Manager not found".
 *
 * <p>{@code /api/managers/ghost} serves two callers with opposite intents. The anonymous search
 * needs a live {@code 'ghost'} that anybody can open; the add form's step-one capture needs a
 * {@code pending_approval} row that is never published. The endpoint was switched wholesale to
 * pending so that half-typing the form stopped publishing managers — correct for the form, and it
 * silently broke search, which went on rendering a clickable tile for a row the server refuses to
 * serve.
 *
 * <p>Every test here fails against that version. The Playwright click-through spec does not: it
 * mocks the API, so it can only catch the frontend half. This is the half that actually broke.
 */
@Testcontainers
class GhostVisibilityIntegrationTest {

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
        await(pool.query("TRUNCATE managers, companies, users, geo_observations, "
                       + "company_locations, anonymous_ghost_quota CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── The regression ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a search-created manager is live and anybody can open it")
    void searchCreatesAPubliclyViewableGhost() throws Exception {
        JsonObject created = await(service.createGhostManager(
            searchBody("Dana Searcher", "SearchCo"), null, anonymous("203.0.113.10")));

        assertTrue(created.getBoolean("published"),
            "a deliberate search that found nobody must publish");

        long managerId = created.getLong("id");
        assertEquals("ghost", approvalStatusOf(managerId));

        // The click. Anonymous - no auth0Id - exactly as the searcher arrives.
        Row profile = await(service.getManagerById(managerId, null));
        assertNotNull(profile);
        assertEquals("Dana Searcher", profile.getString("name"));
    }

    @Test
    @DisplayName("the add form's capture is NOT published, and NOT viewable")
    void addFormCaptureStaysPrivate() throws Exception {
        // Same endpoint, no fromSearch flag. Somebody typed into /add and may never submit.
        JsonObject captured = await(service.createGhostManager(
            new JsonObject()
                .put("name", "Half Typed")
                .put("company", "TypingCo")
                .put("title", "Manager")
                .put("country", "Canada"),
            null, anonymous("203.0.113.11")));

        assertFalse(captured.getBoolean("published"));
        assertEquals("pending_approval", approvalStatusOf(captured.getLong("id")));

        // And the profile must refuse it, which is why no tile may be rendered for it.
        assertThrows(Exception.class,
            () -> await(service.getManagerById(captured.getLong("id"), null)));
    }

    // ── The abuse limits ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a second search from the same address does not publish")
    void oneGhostPerAddressPerWindow() throws Exception {
        JsonObject first = await(service.createGhostManager(
            searchBody("First Person", "QuotaCo"), null, anonymous("203.0.113.20")));
        assertTrue(first.getBoolean("published"));

        JsonObject second = await(service.createGhostManager(
            searchBody("Second Person", "QuotaCo"), null, anonymous("203.0.113.20")));

        // Not an error - the search still records a row for an admin. It simply is not public,
        // and `published` is how the client knows not to render a tile for it.
        assertFalse(second.getBoolean("published"),
            "clearing localStorage must not buy another public manager");
        assertEquals("pending_approval", approvalStatusOf(second.getLong("id")));
    }

    @Test
    @DisplayName("a different address is unaffected by somebody else's quota")
    void quotaIsPerAddress() throws Exception {
        await(service.createGhostManager(
            searchBody("Alpha One", "SharedCo"), null, anonymous("203.0.113.30")));

        JsonObject other = await(service.createGhostManager(
            searchBody("Beta Two", "SharedCo"), null, anonymous("203.0.113.31")));

        assertTrue(other.getBoolean("published"),
            "one visitor's quota must not deny everybody else");
    }

    @Test
    @DisplayName("an unidentifiable address gets no free ghost")
    void unknownAddressDoesNotPublish() throws Exception {
        // A request we cannot attribute cannot be limited, and the safe reading of
        // "unidentifiable" is "no free ghost" rather than "an unlimited supply".
        JsonObject created = await(service.createGhostManager(
            searchBody("No Address", "AnonCo"), null, anonymous(null)));

        assertFalse(created.getBoolean("published"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** A deliberate search that found nobody. */
    private static JsonObject searchBody(String name, String company) {
        return new JsonObject()
            .put("name", name)
            .put("company", company)
            .put("title", "Manager")
            .put("country", "Canada")
            .put("fromSearch", true);
    }

    private static SubmissionContext anonymous(String clientIp) {
        return new SubmissionContext(new GeoObservation("Canada", "Ontario", "Toronto"),
                                     DeclaredLocation.NONE, null, clientIp);
    }

    private static String approvalStatusOf(long managerId) throws Exception {
        return await(pool.preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(io.vertx.sqlclient.Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("approval_status")));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
