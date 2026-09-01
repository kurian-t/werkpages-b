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
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.ManagerService;
import org.werkpages.service.ServiceException;
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
        .withDatabaseName("werkpages_test")
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
        // Delete in FK order (child → parent) to avoid deadlock with background recalculate/scheduleSeedExpiry queries
        await(pool.query("DELETE FROM reviews").execute());
        await(pool.query("DELETE FROM review_deletions").execute());
        await(pool.query("DELETE FROM managers").execute());
        await(pool.query("DELETE FROM companies").execute());
        await(pool.query("DELETE FROM users").execute());
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
    void createDropOffDraft_ghostManagerExists_staysGhostAndAddsReview() throws Exception {
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

        // Ghost stays ghost — it is already live and should not enter the admin queue
        var after = await(pool.preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId)));
        assertEquals("ghost", after.iterator().next().getString("approval_status"));

        // Verify drop-off review was attached (seed review is weight=true; drop-off is weight=false)
        var reviewRows = await(pool.preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1 AND weight = FALSE")
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
    void createDropOffReview_ratingsButNoDates_isStillCaptured() throws Exception {
        // The reported bug: someone rated a manager and left before saying when they worked with
        // them. The rating was silently thrown away - the spec required the very fields they had
        // not reached, and the client swallows the resulting error. Half a review is worth keeping.
        long managerId = insertReviewableManager("Partial Corp", "Nina Partial");

        JsonObject body = new JsonObject()
            .put("overallRating", 4.5)
            .put("ratings", new JsonObject().put("communication", 4).put("support", 5))
            .put("managerCompany", "Partial Corp")
            .put("managerTitle", "VP");
        // No workedFrom, no workedUntil: the fields they never reached.
        await(service.createDropOffReview(managerId, body, null));

        var row = await(pool.preparedQuery(
            "SELECT author, user_id, worked_from FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId)).map(rs -> rs.iterator().next()));
        assertNull(row.getValue("worked_from"), "the date they never gave stays empty");
        assertNull(row.getValue("user_id"), "captured anonymously");
        assertNotNull(row.getString("author"), "a pseudonym stands in for the missing author");
    }

    @Test
    void createDropOffReview_withoutCompanyOrTitle_isStillCaptured() throws Exception {
        // Someone who rated before filling anything else in at all.
        long managerId = insertReviewableManager("Bare Corp", "Otto Bare");

        JsonObject body = new JsonObject()
            .put("overallRating", 3.0)
            .put("ratings", new JsonObject().put("communication", 3));
        await(service.createDropOffReview(managerId, body, null));

        long count = await(pool.preparedQuery(
            "SELECT COUNT(*) FROM reviews WHERE manager_id = $1 AND user_id IS NULL")
            .execute(Tuple.of(managerId)).map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, count);
    }

    @Test
    void submitReview_withoutDates_isStillRejected() throws Exception {
        // The tolerance is scoped to capture. A real submission must still say when.
        long managerId = insertReviewableManager("Strict Corp", "Pat Strict");

        JsonObject body = reviewData()
            .put("managerCompany", "Strict Corp")
            .put("managerTitle", "VP");
        body.remove("workedFrom");

        assertThrows(Exception.class,
            () -> await(service.createReview("auth0|nobody", managerId, body, null)),
            "a completed review still requires the dates a capture may omit");
    }

    /** A manager that can receive reviews, with its company row, as production always creates. */
    private long insertReviewableManager(String company, String managerName) throws Exception {
        var companyRows = await(pool.preparedQuery(
            "INSERT INTO companies (name, status, slug, created_at, updated_at) " +
            "VALUES ($1, 'ghost', $2, now(), now()) RETURNING id")
            .execute(Tuple.of(company, company.toLowerCase().replaceAll("[^a-z0-9]+", "-"))));
        long companyId = companyRows.iterator().next().getLong("id");
        var mgRows = await(pool.preparedQuery(
            "INSERT INTO managers (name, company, title, status, approval_status, country, " +
            "overall_rating, reviews_count, category_averages, company_id, created_at, updated_at) " +
            "VALUES ($1, $2, 'VP', 'active', 'approved', 'United States', 0, 0, '{}'::jsonb, $3, now(), now()) RETURNING id")
            .execute(Tuple.of(managerName, company, companyId)));
        return mgRows.iterator().next().getLong("id");
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

    // ── Incomplete captures ───────────────────────────────────────────────────
    //
    // The whole point of a drop-off. Somebody who walked away at the sign-in step never reached
    // the "when did you work with them" field, so their review is necessarily incomplete - and
    // this path was validating it as though it were a finished submission and rejecting it. Every
    // existing test above sends a complete review, which is why the bug survived them.

    @Test
    void createDropOffDraft_newManager_withoutWorkedFrom_stillCapturesTheReview() throws Exception {
        JsonObject body = dropOffBody("Syed Peer", "Amazon", "System Development Manager", "Canada");
        body.getJsonObject("review").remove("workedFrom");

        JsonObject result = await(service.createDropOffDraft(body, null));

        long managerId = result.getLong("id");
        Long reviews = await(pool.preparedQuery("SELECT COUNT(*) AS c FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId)).map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(1L, reviews, "the review someone walked away from is kept, not discarded");
    }

    @Test
    void createDropOffDraft_repeatedCapture_losesNoReview() throws Exception {
        // Two captures for the same new manager, which is what a retry looks like.
        //
        // findByNameAndCompany only matches approved/ghost, so the pending manager the first
        // capture created is invisible to the second and a second manager row appears. That
        // duplication is a separate problem - and the reason one abandoned review produced three
        // manager rows in production. What must hold here is that neither review is lost.
        await(service.createDropOffDraft(dropOffBody("Ada Byron", "Analytical Co", "Lead", "Canada"), null));

        JsonObject second = dropOffBody("Ada Byron", "Analytical Co", "Lead", "Canada");
        second.getJsonObject("review").remove("workedFrom");
        await(service.createDropOffDraft(second, null));

        Long reviews = await(pool.preparedQuery("""
                SELECT COUNT(*) AS c FROM reviews r
                JOIN managers m ON m.id = r.manager_id WHERE m.name = $1
                """)
            .execute(Tuple.of("Ada Byron")).map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(2L, reviews, "both captures are kept, complete or not");
    }

    @Test
    void createDropOffDraft_withoutCompanyOrTitleOnTheReview_stillCaptures() throws Exception {
        // Not something the real form produces - it collects both on step one and will not fire a
        // capture until step one is valid. This guards the public endpoint against a payload that
        // omits them, where the NOT NULL columns would otherwise reject the whole capture.
        JsonObject body = dropOffBody("Grace Hopper", "Naval Systems", "Rear Admiral", "Canada");
        body.getJsonObject("review").remove("managerCompany");
        body.getJsonObject("review").remove("managerTitle");

        JsonObject result = await(service.createDropOffDraft(body, null));

        Long reviews = await(pool.preparedQuery("SELECT COUNT(*) AS c FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(result.getLong("id"))).map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(1L, reviews);
    }

    @Test
    void createDropOffDraft_withNothingToCapture_leavesNoOrphanManager() throws Exception {
        // A rating is the one thing a capture cannot do without. The failure has to happen before
        // the manager row is written - otherwise a rejected review leaves a manager nobody
        // submitted sitting in the admin queue, which is how one abandoned review produced three.
        JsonObject body = dropOffBody("Nobody Here", "Empty Corp", "Manager", "Canada");
        body.getJsonObject("review").remove("ratings");
        body.getJsonObject("review").remove("overallRating");

        assertThrows(Exception.class, () -> await(service.createDropOffDraft(body, null)));

        Long managers = await(pool.preparedQuery("SELECT COUNT(*) AS c FROM managers WHERE name = $1")
            .execute(Tuple.of("Nobody Here")).map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(0L, managers, "no manager row is left behind by a capture that could not be kept");
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
