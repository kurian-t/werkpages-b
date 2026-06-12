package org.ratemymanager.integration;

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
import org.ratemymanager.repository.CompanyRepository;
import org.ratemymanager.repository.EditRepository;
import org.ratemymanager.repository.ManagerRepository;
import org.ratemymanager.repository.ReportRepository;
import org.ratemymanager.repository.ReviewRepository;
import org.ratemymanager.repository.UserRepository;
import org.ratemymanager.service.ManagerService;
import org.ratemymanager.service.ServiceException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class DropOffDraftIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("ratemymanager_test")
        .withUsername("test")
        .withPassword("test");

    static Pool              pool;
    static ManagerService    service;
    static ManagerRepository managerRepo;
    static ReviewRepository  reviewRepo;
    static UserRepository    userRepo;

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

        pool        = PgPool.pool(connectOptions, new PoolOptions().setMaxSize(5));
        userRepo    = new UserRepository(pool);
        managerRepo = new ManagerRepository(pool);
        reviewRepo  = new ReviewRepository(pool);
        EditRepository   editRepo   = new EditRepository(pool);
        ReportRepository reportRepo = new ReportRepository(pool);
        CompanyRepository companyRepo = new CompanyRepository(pool);
        service = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, companyRepo, pool, name -> null);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, companies, users, reviews CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void createDropOffDraft_newManager_createsPendingWithReview() throws Exception {
        JsonObject body = dropOffBody("Alice Smith", "Acme Corp", "Engineering Manager", "United States");
        JsonObject result = await(service.createDropOffDraft(body, null));

        assertNotNull(result);
        assertTrue(result.getBoolean("created"));
        assertNotNull(result.getLong("id"));

        // Verify manager has pending_approval status
        var rows = await(pool.preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(result.getLong("id"))));
        assertTrue(rows.iterator().hasNext());
        assertEquals("pending_approval", rows.iterator().next().getString("approval_status"));

        // Verify review was created
        var reviewRows = await(pool.preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(result.getLong("id"))));
        assertEquals(1L, reviewRows.iterator().next().getLong(0));
    }

    @Test
    void createDropOffDraft_ghostManagerExists_promotesToPendingAndAddsReview() throws Exception {
        // First create a ghost manager via the ghost endpoint
        JsonObject ghostBody = new JsonObject()
            .put("name",    "Alice Smith")
            .put("company", "Acme Corp")
            .put("title",   "Engineering Manager")
            .put("country", "United States");
        JsonObject ghostResult = await(service.createGhostManager(ghostBody, null));
        assertTrue(ghostResult.getBoolean("created"));
        long managerId = ghostResult.getLong("id");

        // Verify it's ghost
        var before = await(pool.preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId)));
        assertEquals("ghost", before.iterator().next().getString("approval_status"));

        // Now create drop-off draft for the same manager
        JsonObject dropOffBody = dropOffBody("Alice Smith", "Acme Corp", "Engineering Manager", "United States");
        JsonObject result = await(service.createDropOffDraft(dropOffBody, null));

        assertFalse(result.getBoolean("created"));
        assertEquals(managerId, result.getLong("id"));

        // Verify manager is now pending_approval
        var after = await(pool.preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId)));
        assertEquals("pending_approval", after.iterator().next().getString("approval_status"));

        // Verify review was attached
        var reviewRows = await(pool.preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId)));
        assertEquals(1L, reviewRows.iterator().next().getLong(0));
    }

    @Test
    void createDropOffDraft_approvedManagerExists_addsReviewWithoutChangingStatus() throws Exception {
        // Seed an approved manager directly (approved is visible to findByNameAndCompany)
        var companyRows = await(pool.query(
            "INSERT INTO companies (name, status, created_at, updated_at) " +
            "VALUES ('Acme Corp', 'ghost', now(), now()) RETURNING id").execute());
        long companyId = companyRows.iterator().next().getLong("id");

        var mgRows = await(pool.preparedQuery(
            "INSERT INTO managers (name, company, title, status, approval_status, country, " +
            "overall_rating, reviews_count, category_averages, company_id, created_at, updated_at) " +
            "VALUES ('Alice Smith', 'Acme Corp', 'Engineering Manager', 'active', 'approved', " +
            "'United States', 0, 0, '{}'::jsonb, $1, now(), now()) RETURNING id")
            .execute(Tuple.of(companyId)));
        long managerId = mgRows.iterator().next().getLong("id");

        JsonObject body = dropOffBody("Alice Smith", "Acme Corp", "Engineering Manager", "United States");
        JsonObject result = await(service.createDropOffDraft(body, null));

        assertFalse(result.getBoolean("created"));
        assertEquals(managerId, result.getLong("id"));

        // Status should remain approved
        var statusRows = await(pool.preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId)));
        assertEquals("approved", statusRows.iterator().next().getString("approval_status"));

        // Review was attached
        var reviewRows = await(pool.preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId)));
        assertEquals(1L, reviewRows.iterator().next().getLong(0));
    }

    @Test
    void createDropOffDraft_missingName_returns400() {
        JsonObject body = dropOffBody(null, "Acme Corp", "Engineering Manager", "United States");
        body.remove("name");
        ExecutionException ex = assertThrows(ExecutionException.class,
            () -> await(service.createDropOffDraft(body, null)));
        assertTrue(ex.getCause() instanceof ServiceException);
        assertEquals(400, ((ServiceException) ex.getCause()).getStatusCode());
    }

    @Test
    void createDropOffDraft_missingCompany_returns400() {
        JsonObject body = dropOffBody("Alice Smith", null, "Engineering Manager", "United States");
        body.remove("company");
        ExecutionException ex = assertThrows(ExecutionException.class,
            () -> await(service.createDropOffDraft(body, null)));
        assertTrue(ex.getCause() instanceof ServiceException);
        assertEquals(400, ((ServiceException) ex.getCause()).getStatusCode());
    }

    @Test
    void createDropOffDraft_missingReview_returns400() {
        JsonObject body = new JsonObject()
            .put("name",    "Alice Smith")
            .put("company", "Acme Corp")
            .put("title",   "Engineering Manager")
            .put("country", "United States");
        ExecutionException ex = assertThrows(ExecutionException.class,
            () -> await(service.createDropOffDraft(body, null)));
        assertTrue(ex.getCause() instanceof ServiceException);
        assertEquals(400, ((ServiceException) ex.getCause()).getStatusCode());
        assertTrue(ex.getCause().getMessage().contains("review"));
    }

    @Test
    void createDropOffDraft_reviewAttachedToCorrectManager() throws Exception {
        // Create two managers so we can confirm the review goes to the right one
        await(service.createDropOffDraft(dropOffBody("Carlos Rivera", "Other Corp", "Director", "Canada"), null));

        JsonObject result = await(service.createDropOffDraft(
            dropOffBody("Alice Smith", "Acme Corp", "Engineering Manager", "United States"), null));
        long managerId = result.getLong("id");

        var reviewRows = await(pool.preparedQuery(
            "SELECT manager_id, author FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId)));
        assertTrue(reviewRows.iterator().hasNext());
        var reviewRow = reviewRows.iterator().next();
        assertEquals(managerId, reviewRow.getLong("manager_id"));
        assertEquals("TestAnon42", reviewRow.getString("author"));
    }

    // ── Draft token deduplication tests ───────────────────────────────────────

    @Test
    void createDropOffDraft_withDraftToken_storesDraftTokenOnReview() throws Exception {
        String token = "aaaabbbb-cccc-dddd-eeee-ffffffffffff";
        JsonObject body = dropOffBody("Bob Lee", "Token Corp", "Manager", "Canada");
        body.put("draftToken", token);

        JsonObject result = await(service.createDropOffDraft(body, null));
        long managerId = result.getLong("id");

        var rows = await(pool.preparedQuery("SELECT draft_token FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId)));
        assertTrue(rows.iterator().hasNext());
        assertEquals(token, rows.iterator().next().getUUID("draft_token").toString());
    }

    @Test
    void authenticatedSubmit_withMatchingDraftToken_deletesDraftReview() throws Exception {
        // 1. Anonymous drop-off creates a pending manager + anonymous review with a token
        String token = "11111111-2222-3333-4444-555555555555";
        JsonObject dropOff = dropOffBody("Carol King", "Dedup Corp", "Senior Manager", "United States");
        dropOff.put("draftToken", token);
        await(service.createDropOffDraft(dropOff, null));

        // Verify draft review exists with that token and no user_id
        var draftRows = await(pool.preparedQuery(
            "SELECT id FROM reviews WHERE draft_token = $1 AND user_id IS NULL")
            .execute(Tuple.of(java.util.UUID.fromString(token))));
        assertTrue(draftRows.iterator().hasNext(), "Draft review should exist before authenticated submit");

        // 2. User creates an account and submits for real with the same draftToken
        String auth0Id = insertUser("auth0|dedup-user", "DedupUser01");
        JsonObject createBody = createManagerBody("Carol King", "Dedup Corp", "Senior Manager");
        createBody.put("draftToken", token);
        await(service.createManager(auth0Id, createBody, null));

        // Draft review should be deleted; exactly 1 review remains (the authenticated one)
        var remaining = await(pool.preparedQuery(
            "SELECT user_id, draft_token FROM reviews WHERE manager_company = 'Dedup Corp'")
            .execute());
        assertEquals(1, remaining.size(), "Exactly 1 review should remain after deduplication");
        var reviewRow = remaining.iterator().next();
        assertNull(reviewRow.getUUID("draft_token"), "Authenticated review must not carry draft_token");
        assertNotNull(reviewRow.getUUID("user_id"), "Authenticated review must have a user_id");
    }

    @Test
    void authenticatedSubmit_withDraftToken_noMatchingDraft_stillSucceeds() throws Exception {
        // Token that was never used in a drop-off — the DELETE is a no-op and submit succeeds normally
        String auth0Id = insertUser("auth0|noop-token-user", "NoopTokenUser");
        JsonObject body = createManagerBody("Dave Finn", "Noop Corp", "Director");
        body.put("draftToken", "99999999-8888-7777-6666-555555555555");
        Row result = await(service.createManager(auth0Id, body, null));
        assertNotNull(result, "createManager should succeed even when draftToken matches nothing");
        assertEquals(1L, countReviewsForCompany("Noop Corp"));
    }

    @Test
    void authenticatedSubmit_withDraftToken_doesNotDeleteAuthenticatedReview() throws Exception {
        // Sanity check: the DELETE targets only user_id IS NULL rows, not authenticated ones
        // User A submits an authenticated review
        String auth0IdA = insertUser("auth0|safe-a", "SafeUserA");
        JsonObject bodyA = createManagerBody("Eve Stone", "Safe Corp", "VP");
        await(service.createManager(auth0IdA, bodyA, null));

        // Manually set a draft_token on User A's (authenticated) review to simulate a bad edge case
        String fakeToken = "deadbeef-dead-beef-dead-beefdeadbeef";
        await(pool.preparedQuery("UPDATE reviews SET draft_token = $1 WHERE manager_company = 'Safe Corp'")
            .execute(Tuple.of(java.util.UUID.fromString(fakeToken))));

        // User B submits with the same token but a different title (to avoid duplicate-role block).
        // The DELETE clause must not touch User A's review because user_id IS NULL is false for it.
        String auth0IdB = insertUser("auth0|safe-b", "SafeUserB");
        JsonObject bodyB = createManagerBody("Eve Stone", "Safe Corp", "Director");
        bodyB.put("draftToken", fakeToken);
        await(service.createManager(auth0IdB, bodyB, null));

        // User A's review must still exist
        UUID userAId = findUserId("auth0|safe-a");
        long userAReviews = await(pool.preparedQuery("SELECT COUNT(*) FROM reviews WHERE user_id = $1")
            .execute(Tuple.of(userAId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, userAReviews, "Authenticated review must not be deleted by draft token cleanup");
    }

    // ── createDropOffReview tests (existing manager, no auth) ─────────────────

    @Test
    void createDropOffReview_approvedManager_createsAnonymousReview() throws Exception {
        var companyRows = await(pool.query(
            "INSERT INTO companies (name, status, created_at, updated_at) " +
            "VALUES ('Review Corp', 'ghost', now(), now()) RETURNING id").execute());
        long companyId = companyRows.iterator().next().getLong("id");
        var mgRows = await(pool.preparedQuery(
            "INSERT INTO managers (name, company, title, status, approval_status, country, " +
            "overall_rating, reviews_count, category_averages, company_id, created_at, updated_at) " +
            "VALUES ('Frank Lee', 'Review Corp', 'VP', 'active', 'approved', " +
            "'United States', 0, 0, '{}'::jsonb, $1, now(), now()) RETURNING id")
            .execute(Tuple.of(companyId)));
        long managerId = mgRows.iterator().next().getLong("id");

        JsonObject body = reviewData()
            .put("managerCompany", "Review Corp")
            .put("managerTitle", "VP");
        await(service.createDropOffReview(managerId, body, null));

        long count = await(pool.preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1 AND user_id IS NULL")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, count);
    }

    @Test
    void createDropOffReview_withDraftToken_storesToken() throws Exception {
        var companyRows = await(pool.query(
            "INSERT INTO companies (name, status, created_at, updated_at) " +
            "VALUES ('Token Review Corp', 'ghost', now(), now()) RETURNING id").execute());
        long companyId = companyRows.iterator().next().getLong("id");
        var mgRows = await(pool.preparedQuery(
            "INSERT INTO managers (name, company, title, status, approval_status, country, " +
            "overall_rating, reviews_count, category_averages, company_id, created_at, updated_at) " +
            "VALUES ('Gina Ross', 'Token Review Corp', 'Director', 'active', 'ghost', " +
            "'Canada', 0, 0, '{}'::jsonb, $1, now(), now()) RETURNING id")
            .execute(Tuple.of(companyId)));
        long managerId = mgRows.iterator().next().getLong("id");

        String token = "aaaabbbb-1111-2222-3333-ccccddddeeee";
        JsonObject body = reviewData()
            .put("managerCompany", "Token Review Corp")
            .put("managerTitle", "Director")
            .put("draftToken", token);
        await(service.createDropOffReview(managerId, body, null));

        var rows = await(pool.preparedQuery("SELECT draft_token FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId)));
        assertTrue(rows.iterator().hasNext());
        assertEquals(token, rows.iterator().next().getUUID("draft_token").toString());
    }

    @Test
    void createReview_withMatchingDropOffToken_deletesDraftReview() throws Exception {
        var companyRows = await(pool.query(
            "INSERT INTO companies (name, status, created_at, updated_at) " +
            "VALUES ('Dedup2 Corp', 'ghost', now(), now()) RETURNING id").execute());
        long companyId = companyRows.iterator().next().getLong("id");
        var mgRows = await(pool.preparedQuery(
            "INSERT INTO managers (name, company, title, status, approval_status, country, " +
            "overall_rating, reviews_count, category_averages, company_id, created_at, updated_at) " +
            "VALUES ('Hank Wu', 'Dedup2 Corp', 'Manager', 'active', 'approved', " +
            "'United States', 0, 0, '{}'::jsonb, $1, now(), now()) RETURNING id")
            .execute(Tuple.of(companyId)));
        long managerId = mgRows.iterator().next().getLong("id");

        // 1. Anonymous drop-off for this existing manager
        String token = "12345678-1234-1234-1234-123456789012";
        JsonObject dropOff = reviewData().put("managerCompany", "Dedup2 Corp").put("managerTitle", "Manager").put("draftToken", token);
        await(service.createDropOffReview(managerId, dropOff, null));
        var draftCountRows = await(pool.preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1 AND user_id IS NULL")
            .execute(Tuple.of(managerId)));
        assertEquals(1L, draftCountRows.iterator().next().getLong(0));

        // 2. Authenticated user submits for real with same token
        String auth0Id = insertUser("auth0|dedup2-user", "Dedup2User");
        JsonObject authBody = reviewData()
            .put("managerCompany", "Dedup2 Corp")
            .put("managerTitle", "Manager")
            .put("authorType", "username")
            .put("draftToken", token);
        await(service.createReview(auth0Id, managerId, authBody, null));

        // Draft deleted; exactly 1 authenticated review remains
        var totalRows = await(pool.preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId)));
        assertEquals(1L, totalRows.iterator().next().getLong(0));
        var authRow = await(pool.preparedQuery("SELECT user_id, draft_token FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId))).iterator().next();
        assertNotNull(authRow.getUUID("user_id"));
        assertNull(authRow.getUUID("draft_token"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id,email,username,first_name,last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User")));
        return auth0Id;
    }

    private UUID findUserId(String auth0Id) throws Exception {
        return await(pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private long countReviewsForCompany(String company) throws Exception {
        return await(pool.preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_company = $1")
            .execute(Tuple.of(company))
            .map(rs -> rs.iterator().next().getLong(0)));
    }

    private static JsonObject createManagerBody(String name, String company, String title) {
        JsonObject ratings = new JsonObject()
            .put("communication_style", 4)
            .put("perceived_approachability", 4)
            .put("perceived_clarity_of_expectations", 4)
            .put("feedback_style", 4)
            .put("perceived_supportiveness", 4)
            .put("decision_making_style", 4)
            .put("organization_and_planning_style", 4)
            .put("delegation_style", 4)
            .put("perceived_professional_demeanor", 4)
            .put("overall_working_experience", 4);
        JsonObject review = new JsonObject()
            .put("overallRating",  4.0)
            .put("ratings",        ratings)
            .put("managerCompany", company)
            .put("managerTitle",   title)
            .put("authorType",     "anonymous")
            .put("author",         "AnonTester99")
            .put("workedFrom",     "2022-01");
        return new JsonObject()
            .put("name",      name)
            .put("company",   company)
            .put("title",     title)
            .put("image",     "T")
            .put("country",   "United States")
            .put("status",    "active")
            .put("startDate", "2020-01")
            .put("review",    review);
    }

    private static JsonObject reviewData() {
        return new JsonObject()
            .put("author", "TestAnon42")
            .put("overallRating", 4.0)
            .put("ratings", new JsonObject()
                .put("communication_style", 4)
                .put("perceived_approachability", 4)
                .put("perceived_clarity_of_expectations", 4)
                .put("feedback_style", 4)
                .put("perceived_supportiveness", 4)
                .put("decision_making_style", 4)
                .put("organization_and_planning_style", 4)
                .put("delegation_style", 4)
                .put("perceived_professional_demeanor", 4)
                .put("overall_working_experience", 4))
            .put("managerCompany", "Acme Corp")
            .put("managerTitle", "Engineering Manager")
            .put("workedFrom", "2022-01")
            .putNull("workedUntil");
    }

    private static JsonObject dropOffBody(String name, String company, String title, String country) {
        JsonObject body = new JsonObject();
        if (name    != null) body.put("name",    name);
        if (company != null) body.put("company", company);
        if (title   != null) body.put("title",   title);
        if (country != null) body.put("country", country);
        body.put("review", reviewData());
        return body;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
