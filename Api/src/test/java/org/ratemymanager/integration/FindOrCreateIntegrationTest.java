package org.ratemymanager.integration;

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

import java.util.UUID;
import java.util.concurrent.ExecutionException;
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

        boolean flagSet = await(userRepo.hasAutoCreatedManager(findUserId(auth0Id)));
        assertTrue(flagSet);
    }

    // ── Second search, long names → pending_approval ──────────────────────────

    @Test
    void findOrCreate_secondSearch_longNames_createsPending() throws Exception {
        String auth0Id = insertUser("auth0|u2", "user2");

        // First search — creates ghost
        await(service.findOrCreate(
            auth0Id, "Alice", "Smith", "Engineer", "Acme Corp", "US", null, null, null));

        // Second search (different manager) — creates pending
        JsonObject result = await(service.findOrCreate(
            auth0Id, "David", "Lee", "Manager", "Beta Corp", "US", null, null, null));

        assertTrue(result.getBoolean("created"));
        JsonArray data = result.getJsonArray("data");
        assertEquals(1, data.size());
        assertEquals("pending_approval", data.getJsonObject(0).getString("approvalStatus"));
    }

    // ── Short company name → pending_approval (even on first search) ──────────

    @Test
    void findOrCreate_shortCompanyName_createsPendingNotGhost() throws Exception {
        String auth0Id = insertUser("auth0|u3", "user3");

        JsonObject result = await(service.findOrCreate(
            auth0Id, "Carol", "Lee", "Engineer", "Go", "US", null, null, null));

        assertTrue(result.getBoolean("created"));
        JsonArray data = result.getJsonArray("data");
        assertEquals(1, data.size());
        assertEquals("pending_approval", data.getJsonObject(0).getString("approvalStatus"));

        // Ghost slot should NOT be consumed for short-name searches
        boolean flagSet = await(userRepo.hasAutoCreatedManager(findUserId(auth0Id)));
        assertFalse(flagSet);
    }

    // ── Second search with short name → pending, ghost slot still free ────────

    @Test
    void findOrCreate_shortName_doesNotConsumeGhostSlot_allowsGhostOnLaterSearch() throws Exception {
        String auth0Id = insertUser("auth0|u4", "user4");

        // Short-name search → pending (ghost slot not consumed)
        await(service.findOrCreate(
            auth0Id, "Dan", "Park", "Engineer", "Go", "US", null, null, null));

        // Long-name search next → should still create ghost (slot was not consumed)
        JsonObject result = await(service.findOrCreate(
            auth0Id, "Eve", "Walker", "Manager", "Microsoft", "US", null, null, null));

        assertTrue(result.getBoolean("created"));
        assertEquals("ghost", result.getJsonArray("data").getJsonObject(0).getString("approvalStatus"));
    }

    // ── Duplicate guard: same user, same name+company → returns existing pending ─

    @Test
    void findOrCreate_sameUserSearchesSamePendingManager_returnsExistingNoDuplicate() throws Exception {
        String auth0Id = insertUser("auth0|u5", "user5");

        // First search: creates ghost (consume slot)
        await(service.findOrCreate(
            auth0Id, "Alice", "Smith", "Engineer", "Acme Corp", "US", null, null, null));

        // Second search: different name → pending
        JsonObject first = await(service.findOrCreate(
            auth0Id, "David", "Lee", "Manager", "Beta Corp", "US", null, null, null));
        long firstId = first.getJsonArray("data").getJsonObject(0).getLong("id");

        // Third search: same name/company as pending → must return existing, not create duplicate
        JsonObject second = await(service.findOrCreate(
            auth0Id, "David", "Lee", "Manager", "Beta Corp", "US", null, null, null));
        long secondId = second.getJsonArray("data").getJsonObject(0).getLong("id");

        assertEquals(firstId, secondId);
        assertFalse(second.getBoolean("created"));

        long count = await(pool.preparedQuery("SELECT COUNT(*) FROM managers WHERE name ILIKE 'David Lee'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1, count);
    }

    // ── Pending manager not visible to other users in search ─────────────────

    @Test
    void findOrCreate_pendingManagerNotVisibleToOtherUser() throws Exception {
        String auth0Id1 = insertUser("auth0|u6", "user6");
        String auth0Id2 = insertUser("auth0|u7", "user7");

        // user1 creates ghost
        await(service.findOrCreate(
            auth0Id1, "Alice", "Smith", "Engineer", "Acme Corp", "US", null, null, null));

        // user1 creates pending for "David Lee at Beta Corp"
        await(service.findOrCreate(
            auth0Id1, "David", "Lee", "Manager", "Beta Corp", "US", null, null, null));

        // user2 searches for same pending manager → should NOT see it (empty results, then creates its own ghost)
        JsonObject result = await(service.findOrCreate(
            auth0Id2, "David", "Lee", "Manager", "Beta Corp", "US", null, null, null));

        // user2 has not created a ghost yet, so they'd get a ghost too
        assertEquals("ghost", result.getJsonArray("data").getJsonObject(0).getString("approvalStatus"));
        // But it should be a different row from user1's pending
        long pendingId = await(pool.preparedQuery(
            "SELECT id FROM managers WHERE name ILIKE 'David Lee' AND approval_status = 'pending_approval'")
            .execute().map(rs -> rs.iterator().next().getLong("id")));
        long ghostId = result.getJsonArray("data").getJsonObject(0).getLong("id");
        assertNotEquals(pendingId, ghostId);
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

    // ── Helpers ───────────────────────────────────────────────────────────────

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
