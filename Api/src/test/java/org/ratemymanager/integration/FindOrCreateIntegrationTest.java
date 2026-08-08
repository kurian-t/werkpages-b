package org.ratemymanager.integration;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ratemymanager.repository.EditRepository;
import org.ratemymanager.repository.ManagerRepository;
import org.ratemymanager.repository.ReportRepository;
import org.ratemymanager.repository.ReviewRepository;
import org.ratemymanager.repository.UserRepository;
import org.ratemymanager.service.ManagerService;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class FindOrCreateIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("ratemymanager_test")
        .withUsername("test")
        .withPassword("test");

    static Pool           pool;
    static ManagerService service;
    static UserRepository userRepo;

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

        pool     = PgPool.pool(connectOptions, new PoolOptions().setMaxSize(5));
        userRepo = new UserRepository(pool);

        ManagerRepository managerRepo = new ManagerRepository(pool);
        ReviewRepository  reviewRepo  = new ReviewRepository(pool);
        EditRepository    editRepo    = new EditRepository(pool);
        ReportRepository  reportRepo  = new ReportRepository(pool);
        service = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE notifications, manager_url_history, company_stats_live").execute());
        await(pool.query("TRUNCATE managers, companies, users CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── First search, long names → ghost ─────────────────────────────────────

    @Test
    void findOrCreate_firstSearch_longNames_createsGhost() throws Exception {
        String auth0Id = insertUser("auth0|u1", "user1");

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Alice", "Smith", "Engineer", "Acme Corp", "US", null, null, null));

        assertTrue(result.getBoolean("created"));
        JsonArray data = result.getJsonArray("data");
        assertEquals(1, data.size());
        assertEquals("ghost", data.getJsonObject(0).getString("approvalStatus"));

        boolean flagSet = ghostSlotClaimed(findUserId(auth0Id));
        assertTrue(flagSet);
    }

    // ── Second search, long names → ghost slot taken, returns empty, no pending created ──

    @Test
    void findOrCreate_secondSearch_longNames_returnsEmptyWithoutCreating() throws Exception {
        String auth0Id = insertUser("auth0|u2", "user2");

        // First search — creates ghost (user sees it)
        await(service.findOrCreate(
            auth0Id, "Alice", "Smith", "Engineer", "Acme Corp", "US", null, null, null));

        // Second search (different manager) — ghost slot taken, returns empty, no pending created
        JsonObject result = await(service.findOrCreate(
            auth0Id, "David", "Lee", "Manager", "Beta Corp", "US", null, null, null));

        assertFalse(result.getBoolean("created"));
        assertEquals(0, result.getJsonArray("data").size());

        // No pending created — user searched, not explicitly submitted
        long count = await(pool.preparedQuery("SELECT COUNT(*) FROM managers WHERE name ILIKE 'David Lee'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0, count);
    }

    // ── Short company name → returns empty, no pending created, ghost slot untouched ──

    @Test
    void findOrCreate_shortCompanyName_returnsEmptyWithoutCreating() throws Exception {
        String auth0Id = insertUser("auth0|u3", "user3");

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Carol", "Lee", "Engineer", "Go", "US", null, null, null));

        // Nothing returned to user — short name can't safely ghost
        assertFalse(result.getBoolean("created"));
        assertEquals(0, result.getJsonArray("data").size());

        // No pending created — user searched, not explicitly submitted
        long count = await(pool.preparedQuery("SELECT COUNT(*) FROM managers WHERE name ILIKE 'Carol Lee'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0, count);

        // Ghost slot should NOT be consumed for short-name searches
        boolean flagSet = ghostSlotClaimed(findUserId(auth0Id));
        assertFalse(flagSet);
    }

    // ── Second search with short name → pending, ghost slot still free ────────

    @Test
    void findOrCreate_shortName_doesNotConsumeGhostSlot_allowsGhostOnLaterSearch() throws Exception {
        String auth0Id = insertUser("auth0|u4", "user4");

        // Short-name search → pending queued silently (ghost slot not consumed)
        JsonObject shortResult = await(service.findOrCreate(
            auth0Id, "Dan", "Park", "Engineer", "Go", "US", null, null, null));
        assertFalse(shortResult.getBoolean("created"));
        assertEquals(0, shortResult.getJsonArray("data").size());

        // Long-name search next → should still create ghost (slot was not consumed)
        JsonObject result = await(service.findOrCreate(
            auth0Id, "Eve", "Walker", "Manager", "Microsoft", "US", null, null, null));

        assertTrue(result.getBoolean("created"));
        assertEquals("ghost", result.getJsonArray("data").getJsonObject(0).getString("approvalStatus"));
    }

    // ── Ghost slot taken: repeated searches return empty, no managers created ──

    @Test
    void findOrCreate_ghostSlotTaken_repeatedSearchesReturnEmpty() throws Exception {
        String auth0Id = insertUser("auth0|u5", "user5");

        // First search: creates ghost (consume slot)
        await(service.findOrCreate(
            auth0Id, "Alice", "Smith", "Engineer", "Acme Corp", "US", null, null, null));

        // Second search: different name → ghost slot taken, returns empty, no manager created
        JsonObject second = await(service.findOrCreate(
            auth0Id, "David", "Lee", "Manager", "Beta Corp", "US", null, null, null));
        assertFalse(second.getBoolean("created"));
        assertEquals(0, second.getJsonArray("data").size());

        // Third search: same name/company again → still empty, still no manager created
        JsonObject third = await(service.findOrCreate(
            auth0Id, "David", "Lee", "Manager", "Beta Corp", "US", null, null, null));
        assertFalse(third.getBoolean("created"));
        assertEquals(0, third.getJsonArray("data").size());

        long count = await(pool.preparedQuery("SELECT COUNT(*) FROM managers WHERE name ILIKE 'David Lee'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0, count);
    }

    // ── Ghost slot taken for user1, user2 can still create ghost for same manager ──

    @Test
    void findOrCreate_user2CanCreateGhostWhenUser1SlotWasExhausted() throws Exception {
        String auth0Id1 = insertUser("auth0|u6", "user6");
        String auth0Id2 = insertUser("auth0|u7", "user7");

        // user1 creates ghost (Alice at Acme) — consumes their slot
        await(service.findOrCreate(
            auth0Id1, "Alice", "Smith", "Engineer", "Acme Corp", "US", null, null, null));

        // user1's second search (David at Beta) — slot taken, returns empty, no manager created
        JsonObject u1Result = await(service.findOrCreate(
            auth0Id1, "David", "Lee", "Manager", "Beta Corp", "US", null, null, null));
        assertFalse(u1Result.getBoolean("created"));
        assertEquals(0, u1Result.getJsonArray("data").size());

        // user2 searches same name/company — their slot is free, creates ghost
        JsonObject u2Result = await(service.findOrCreate(
            auth0Id2, "David", "Lee", "Manager", "Beta Corp", "US", null, null, null));
        assertEquals("ghost", u2Result.getJsonArray("data").getJsonObject(0).getString("approvalStatus"));

        // Only the ghost exists — no pending was created by user1's search
        long pendingCount = await(pool.preparedQuery(
            "SELECT COUNT(*) FROM managers WHERE name ILIKE 'David Lee' AND approval_status = 'pending_approval'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0, pendingCount);
    }

    // ── findPendingByUser excludes search-created managers ───────────────────

    @Test
    void findPendingByUser_excludesSearchCreatedManagers() throws Exception {
        String auth0Id = insertUser("auth0|u8", "user8");
        UUID userId = findUserId(auth0Id);

        // Create a regular pending manager via direct DB insert (user explicitly submitted)
        await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,status,approval_status,overall_rating,reviews_count,category_averages,submitted_by) " +
            "VALUES ('Submitted Manager','Corp','Title','active','pending_approval',0,0,'{}',$1)")
            .execute(Tuple.of(userId)));

        // Create a search-created pending manager via DB insert with search_created_by_user_id
        await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,status,approval_status,overall_rating,reviews_count,category_averages,submitted_by,search_created_by_user_id) " +
            "VALUES ('Search Manager','Corp','Title','active','pending_approval',0,0,'{}',$1,$1)")
            .execute(Tuple.of(userId)));

        ManagerRepository managerRepo = new ManagerRepository(pool);
        var rows = await(managerRepo.findPendingByUser(userId));
        assertEquals(1, rows.size());
        assertEquals("Submitted Manager", rows.iterator().next().getString("name"));
    }

    // ── Concurrent searches by same user: only one ghost, loser gets empty ───

    @Test
    void findOrCreate_concurrentSearches_onlyOneGhostCreated() throws Exception {
        String auth0Id = insertUser("auth0|u9", "user9");

        // Fire two simultaneous searches for different manager names
        Future<JsonObject> f1 = service.findOrCreate(
            auth0Id, "Anna", "Brown", "Engineer", "Acme Corp", "US", null, null, null);
        Future<JsonObject> f2 = service.findOrCreate(
            auth0Id, "Ben", "Green", "Manager", "Beta Corp", "US", null, null, null);

        List<JsonObject> results = await(Future.all(f1, f2)
            .map(cf -> List.of((JsonObject) cf.resultAt(0), (JsonObject) cf.resultAt(1))));

        // Exactly one ghost, zero pending — loser just gets empty results
        long ghostsInDB = await(pool.query("SELECT COUNT(*) FROM managers WHERE approval_status = 'ghost'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        long pendingInDB = await(pool.query("SELECT COUNT(*) FROM managers WHERE approval_status = 'pending_approval'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));

        assertEquals(1, ghostsInDB, "Expected exactly 1 ghost; race condition may have created 2");
        assertEquals(0, pendingInDB, "Loser should get empty results, not a pending manager");

        // Only the winner returns data
        long dataWithResults = results.stream().filter(r -> r.getJsonArray("data").size() > 0).count();
        assertEquals(1, dataWithResults, "Only the ghost response should have data");

        long totalManagers = await(pool.query("SELECT COUNT(*) FROM managers")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1, totalManagers);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean ghostSlotClaimed(UUID userId) throws Exception {
        return await(pool.preparedQuery("SELECT has_auto_created_manager FROM users WHERE id = $1")
            .execute(Tuple.of(userId))
            .map(rs -> rs.iterator().next().getBoolean("has_auto_created_manager")));
    }

    private String insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id, email, username, first_name, last_name, role) " +
            "VALUES ($1,$2,$3,$4,$5,$6)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User", "user")));
        return auth0Id;
    }

    private UUID findUserId(String auth0Id) throws Exception {
        return await(pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
