package org.werkpages.integration;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.Future;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.ManagerService;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ManagerServiceCoverage2IntegrationTest {

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

        ManagerRepository managerRepo = new ManagerRepository(pool);
        ReviewRepository  reviewRepo  = new ReviewRepository(pool);
        UserRepository    userRepo    = new UserRepository(pool);
        EditRepository    editRepo    = new EditRepository(pool);
        ReportRepository  reportRepo  = new ReportRepository(pool);
        service = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        pool.query("TRUNCATE managers, users, companies, manager_edits CASCADE").execute()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── getManagerReviews ─────────────────────────────────────────────────────

    @Test
    void getManagerReviews_withReviews_returnsDataAndTotal() throws Exception {
        String auth0Id  = insertUser("auth0|gmr-1");
        long managerId  = insertManager("Review Mgr", "RevCorp", "Lead", "approved");
        await(service.createReview(auth0Id, managerId, reviewBody(), null));

        JsonObject result = await(service.getManagerReviews(managerId, 10, 0, "recent", null));
        assertNotNull(result);
        assertTrue(result.getLong("total") > 0);
        assertTrue(result.getJsonArray("data").size() > 0);
        assertEquals(10,  result.getInteger("limit"));
        assertEquals(0,   result.getInteger("offset"));
    }

    @Test
    void getManagerReviews_noReviews_returnsEmptyData() throws Exception {
        long managerId = insertManager("Empty Mgr", "EmptyCorp", "Dev", "approved");

        JsonObject result = await(service.getManagerReviews(managerId, 10, 0, "recent", null));
        assertNotNull(result);
        assertEquals(0L, result.getLong("total").longValue());
        assertEquals(0,  result.getJsonArray("data").size());
    }

    // ── updateManager — success paths ─────────────────────────────────────────

    @Test
    void updateManager_bannedUser_returnsForbidden() throws Exception {
        String auth0Id = insertUser("auth0|upd-banned");
        long managerId = insertManager("Banned Target", "Corp", "Dev", "approved");
        banUser(auth0Id);

        try {
            await(service.updateManager(auth0Id, managerId, new JsonObject().put("bio", "new bio")));
            fail("expected forbidden");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("account_suspended"),
                "Expected account_suspended but got: " + e.getMessage());
        }
    }

    @Test
    void updateManager_managerNotFound_returnsNotFound() throws Exception {
        String auth0Id = insertUser("auth0|upd-notfound");

        try {
            await(service.updateManager(auth0Id, 99999L, new JsonObject().put("bio", "something")));
            fail("expected not found");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("not found"),
                "Expected not found but got: " + e.getMessage());
        }
    }

    @Test
    void updateManager_companyChanged_noStartDate_succeeds() throws Exception {
        String auth0Id = insertUser("auth0|upd-co-change");
        insertCompany("OldCorp", "approved");
        long managerId = insertManagerForCompany("Company Changer", "OldCorp", "Engineer");

        JsonObject body = new JsonObject().put("company", "NewCorp");
        JsonObject result = await(service.updateManager(auth0Id, managerId, body));

        assertNotNull(result);
        // The result should contain updated company info
        assertTrue(result.containsKey("id") || result.containsKey("name"),
            "Result should contain manager fields");
    }

    @Test
    void updateManager_companyChanged_withStartDate_succeeds() throws Exception {
        String auth0Id = insertUser("auth0|upd-co-start");
        insertCompany("OldCorpB", "approved");
        long managerId = insertManagerForCompany("Start Date Changer", "OldCorpB", "Director");

        JsonObject body = new JsonObject()
            .put("company", "NewCorpB")
            .put("startDate", "2022-01");
        JsonObject result = await(service.updateManager(auth0Id, managerId, body));

        assertNotNull(result);
        assertTrue(result.containsKey("id") || result.containsKey("name"),
            "Result should contain manager fields");
    }

    @Test
    void updateManager_noCompanyOrTitleChange_bioOnly_succeeds() throws Exception {
        String auth0Id = insertUser("auth0|upd-bio-only");
        long managerId = insertManager("Bio Updater", "BioCorp", "Analyst", "approved");

        JsonObject body = new JsonObject().put("bio", "Updated bio text");
        JsonObject result = await(service.updateManager(auth0Id, managerId, body));

        assertNotNull(result);
        assertTrue(result.containsKey("id") || result.containsKey("name"),
            "Result should contain manager fields");
    }

    // ── createEditRequest ─────────────────────────────────────────────────────

    @Test
    void createEditRequest_allFieldsBlank_returnsBadRequest() {
        try {
            await(service.createEditRequest("auth0|any", 1L, new JsonObject()));
            fail("expected bad request");
        } catch (Exception e) {
            // expected — at least one field is required
        }
    }

    @Test
    void createEditRequest_companyTooLong_returnsBadRequest() {
        try {
            await(service.createEditRequest("auth0|any", 1L,
                new JsonObject().put("company", "C".repeat(101))));
            fail("expected bad request");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void createEditRequest_titleTooLong_returnsBadRequest() {
        try {
            await(service.createEditRequest("auth0|any", 1L,
                new JsonObject().put("title", "T".repeat(101))));
            fail("expected bad request");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void createEditRequest_invalidStatus_returnsBadRequest() {
        try {
            await(service.createEditRequest("auth0|any", 1L,
                new JsonObject().put("status", "invalid_status")));
            fail("expected bad request");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void createEditRequest_userNotFound_returnsUnauthorized() {
        try {
            await(service.createEditRequest("auth0|no-such-user-edit", 1L,
                new JsonObject().put("company", "Some Corp")));
            fail("expected unauthorized");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("user not found"),
                "Expected user not found but got: " + e.getMessage());
        }
    }

    @Test
    void createEditRequest_bannedUser_returnsForbidden() throws Exception {
        String auth0Id = insertUser("auth0|edit-banned");
        long managerId = insertManager("Edit Target", "Corp", "Dev", "approved");
        banUser(auth0Id);

        try {
            await(service.createEditRequest(auth0Id, managerId,
                new JsonObject().put("company", "New Corp")));
            fail("expected forbidden");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("account_suspended"),
                "Expected account_suspended but got: " + e.getMessage());
        }
    }

    @Test
    void createEditRequest_dailyLimitExceeded_returnsTooManyRequests() throws Exception {
        String auth0Id = insertUser("auth0|edit-limit");
        // Create 6 different managers and submit an edit for each (upsert is per manager+user)
        for (int i = 0; i < 6; i++) {
            long mgrId = insertManager("Edit Limit Mgr" + i, "Corp" + i, "Dev", "approved");
            await(service.createEditRequest(auth0Id, mgrId,
                new JsonObject().put("company", "Updated Corp" + i)));
        }
        // 7th edit request should be rate-limited
        long seventhMgr = insertManager("Edit Limit Mgr7", "Corp7", "Dev", "approved");
        try {
            await(service.createEditRequest(auth0Id, seventhMgr,
                new JsonObject().put("company", "Updated Corp7")));
            fail("expected too many requests");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("daily_limit_reached"),
                "Expected daily_limit_reached but got: " + e.getMessage());
        }
    }

    @Test
    void createEditRequest_success_returnsEditWithExpectedFields() throws Exception {
        String auth0Id = insertUser("auth0|edit-success");
        long managerId = insertManager("Edit Success Mgr", "EditCorp", "Engineer", "approved");

        JsonObject result = await(service.createEditRequest(auth0Id, managerId,
            new JsonObject().put("company", "Updated Edit Corp")));

        assertNotNull(result);
        assertNotNull(result.getString("id"), "Should have an id");
        assertEquals(managerId, result.getLong("managerId").longValue());
        assertEquals("pending", result.getString("status"));
        assertNotNull(result.getString("createdAt"), "Should have a createdAt");
    }

    // ── getPendingEditsForManager ─────────────────────────────────────────────

    @Test
    void getPendingEditsForManager_nullAuth0Id_returnsEmptyData() throws Exception {
        JsonObject result = await(service.getPendingEditsForManager(1L, null));
        assertNotNull(result);
        assertEquals(0, result.getJsonArray("data").size());
    }

    @Test
    void getPendingEditsForManager_unknownAuth0Id_returnsEmptyData() throws Exception {
        JsonObject result = await(service.getPendingEditsForManager(1L, "auth0|no-such-user-edits"));
        assertNotNull(result);
        assertEquals(0, result.getJsonArray("data").size());
    }

    @Test
    void getPendingEditsForManager_withPendingEdits_returnsData() throws Exception {
        String auth0Id = insertUser("auth0|get-pending-edits");
        long managerId = insertManager("Pending Edit Mgr", "PendCorp", "Lead", "approved");

        // Create an edit request first
        await(service.createEditRequest(auth0Id, managerId,
            new JsonObject().put("company", "Updated Pend Corp")));

        JsonObject result = await(service.getPendingEditsForManager(managerId, auth0Id));
        assertNotNull(result);
        JsonArray data = result.getJsonArray("data");
        assertTrue(data.size() > 0, "Should have pending edit data");
        JsonObject edit = data.getJsonObject(0);
        assertNotNull(edit.getString("createdAt"), "Edit should have createdAt");
        assertEquals("Updated Pend Corp", edit.getString("newCompany"));
    }

    // ── findOrCreate ─────────────────────────────────────────────────────────

    @Test
    void findOrCreate_userNotFound_returnsUnauthorized() {
        try {
            await(service.findOrCreate("auth0|no-such-user-foc", "Alice", "Smith",
                "Engineer", "Acme Corp", "US", null, null, null));
            fail("expected unauthorized");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("user not found"),
                "Expected user not found but got: " + e.getMessage());
        }
    }

    @Test
    void findOrCreate_matchFound_ghost_returnsFalseCreated() throws Exception {
        String auth0Id = insertUser("auth0|foc-match");
        // Insert a ghost manager that will be matched by name+company search
        insertManager("Alice Smith", "Acme Corp", "Engineer", "ghost");

        JsonObject result = await(service.findOrCreate(auth0Id,
            "Alice", "Smith", "Engineer", "Acme Corp", "US", null, null, null));

        assertNotNull(result);
        assertFalse(result.getBoolean("created"), "Should not create a new manager when match exists");
        assertTrue(result.getJsonArray("data").size() > 0, "Should return matched manager");
    }

    @Test
    void findOrCreate_alreadyPending_returnsFalseCreatedEmptyData() throws Exception {
        String auth0Id = insertUser("auth0|foc-pending");
        // Get the user UUID so the pending manager is visible to their search results
        UUID userId = pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getUUID("id");
        // Insert a pending manager linked to this user — search() returns pending only when search_created_by_user_id matches
        pool.preparedQuery(
                "INSERT INTO managers(name, company, title, image, status, approval_status, " +
                "overall_rating, reviews_count, category_averages, search_created_by_user_id) " +
                "VALUES ($1,$2,$3,'img','active','pending_approval',0,0,'{}', $4)")
            .execute(Tuple.of("Patricia Chen", "PendingCo", "Manager", userId))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        JsonObject result = await(service.findOrCreate(auth0Id,
            "Patricia", "Chen", "Manager", "PendingCo", "US", null, null, null));

        assertNotNull(result);
        assertFalse(result.getBoolean("created"));
        assertEquals(0, result.getJsonArray("data").size());
    }

    @Test
    void findOrCreate_contributedUser_noMatch_returnsEmptyData() throws Exception {
        String auth0Id = insertUser("auth0|foc-contributed");
        long managerId = insertManager("Contrib Target", "ContribCorp", "Director", "approved");
        // Make the user a contributor
        await(service.createReview(auth0Id, managerId, reviewBody(), null));

        // Now search for a different, non-existent manager
        JsonObject result = await(service.findOrCreate(auth0Id,
            "Zara", "Quincey", "Analyst", "NoSuchCompanyXYZ", "US", null, null, null));

        assertNotNull(result);
        assertFalse(result.getBoolean("created"));
        assertEquals(0, result.getJsonArray("data").size());
        assertTrue(result.getBoolean("hasContributed"), "Should indicate user has contributed");
    }

    @Test
    void findOrCreate_shortName_returnsEmptyData() throws Exception {
        String auth0Id = insertUser("auth0|foc-short");

        // "Al Bo" — combined name length < 4 chars? Actually "Al Bo" = 5 chars > 4, but company "Go" = 2 chars < 4
        JsonObject result = await(service.findOrCreate(auth0Id,
            "Al", "Bo", "Engineer", "Go", "US", null, null, null));

        assertNotNull(result);
        assertFalse(result.getBoolean("created"));
        assertEquals(0, result.getJsonArray("data").size());
    }

    @Test
    void findOrCreate_slotClaimedGhostCreated_returnsCreatedTrue() throws Exception {
        String auth0Id = insertUser("auth0|foc-create");

        JsonObject result = await(service.findOrCreate(auth0Id,
            "Xavier", "Longname", "Software Engineer", "Longname Corporation", "US", null, null, null));

        assertNotNull(result);
        assertTrue(result.getBoolean("created"));
        assertEquals(1, result.getJsonArray("data").size());
        assertEquals("ghost",
            result.getJsonArray("data").getJsonObject(0).getString("approvalStatus"));
    }

    // ── captureAnonymousSearch ────────────────────────────────────────────────

    @Test
    void captureAnonymousSearch_missingFields_returnsBadRequest() {
        try {
            // missing country
            await(service.captureAnonymousSearch(
                new JsonObject()
                    .put("name", "John Doe")
                    .put("company", "Corp")
                    .put("title", "Dev"),
                null));
            fail("expected bad request");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void captureAnonymousSearch_nullBody_returnsBadRequest() {
        try {
            await(service.captureAnonymousSearch(null, null));
            fail("expected bad request");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void captureAnonymousSearch_alreadyExists_returnsVoidSuccess() throws Exception {
        // Insert an existing ghost manager
        insertManager("Jane Ghost", "GhostCo", "Analyst", "ghost");

        // captureAnonymousSearch should detect it exists and return success without creating another
        await(service.captureAnonymousSearch(
            new JsonObject()
                .put("name", "Jane Ghost")
                .put("company", "GhostCo")
                .put("title", "Analyst")
                .put("country", "US"),
            null));

        // Verify only one manager exists (the original ghost)
        long count = await(pool.preparedQuery("SELECT COUNT(*) FROM managers WHERE name = 'Jane Ghost'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1, count, "Should not create a duplicate manager");
    }

    @Test
    void captureAnonymousSearch_noMatch_createsPendingManager() throws Exception {
        await(service.captureAnonymousSearch(
            new JsonObject()
                .put("name", "Unique Anonymous Person")
                .put("company", "Anonymous Corp")
                .put("title", "Analyst")
                .put("country", "Canada"),
            null));

        // Manager should have been created as pending
        long count = await(pool.preparedQuery(
            "SELECT COUNT(*) FROM managers WHERE name = 'Unique Anonymous Person' AND approval_status = 'pending_approval'")
            .execute().map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1, count, "Should create a pending manager");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long insertManager(String name, String company, String title, String status) throws Exception {
        return pool.preparedQuery(
                "INSERT INTO managers(name, company, title, image, status, approval_status, " +
                "overall_rating, reviews_count, category_averages) " +
                "VALUES ($1,$2,$3,'img','active',$4,0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title, status))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getLong("id");
    }

    private long insertManagerWithStatus(String name, String company, String title, String approvalStatus) throws Exception {
        return pool.preparedQuery(
                "INSERT INTO managers(name, company, title, image, status, approval_status, " +
                "overall_rating, reviews_count, category_averages) " +
                "VALUES ($1,$2,$3,'img','active',$4,0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title, approvalStatus))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getLong("id");
    }

    private long insertManagerForCompany(String name, String company, String title) throws Exception {
        long companyId = pool.preparedQuery("SELECT id FROM companies WHERE LOWER(TRIM(name)) = LOWER(TRIM($1))")
            .execute(Tuple.of(company))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getLong("id");
        return pool.preparedQuery(
                "INSERT INTO managers(name, company, title, image, status, approval_status, company_id, " +
                "overall_rating, reviews_count, category_averages) " +
                "VALUES ($1,$2,$3,'img','active','approved',$4,0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title, companyId))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getLong("id");
    }

    private void insertCompany(String name, String status) throws Exception {
        pool.preparedQuery(
                "INSERT INTO companies(name, status, created_at, updated_at) VALUES ($1,$2,now(),now())")
            .execute(Tuple.of(name, status))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private String insertUser(String auth0Id) throws Exception {
        String username = auth0Id.replaceAll("[^a-zA-Z0-9]", "") + "User";
        pool.preparedQuery(
                "INSERT INTO users(auth0_id, email, username, first_name, last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User"))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        return auth0Id;
    }

    private void banUser(String auth0Id) throws Exception {
        UUID userId = pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getUUID("id");
        pool.preparedQuery(
                "INSERT INTO banned_users(user_id, reason, banned_by) VALUES ($1, $2, $3)")
            .execute(Tuple.of(userId, "test ban", "test-admin"))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static JsonObject reviewBody() {
        return new JsonObject()
            .put("overallRating", 4.0)
            .put("managerCompany", "TestCorp")
            .put("managerTitle", "Engineer")
            .put("workedFrom", "2022-01")
            .put("ratings", new JsonObject()
                .put("Communication Style", 4)
                .put("Perceived Approachability", 4)
                .put("Perceived Clarity of Expectations", 4)
                .put("Feedback Style", 4)
                .put("Perceived Supportiveness", 4)
                .put("Decision Making Style", 4)
                .put("Organization and Planning Style", 4)
                .put("Delegation Style", 4)
                .put("Perceived Professional Demeanor", 4)
                .put("Overall Working Experience", 4));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
