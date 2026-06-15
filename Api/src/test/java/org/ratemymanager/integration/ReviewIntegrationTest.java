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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for review submission.
 * Spins up a real PostgreSQL container, runs Flyway migrations, and tests
 * through the real service and repository layers.
 */
@Testcontainers
class ReviewIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("ratemymanager_test")
        .withUsername("test")
        .withPassword("test");

    static Pool              pool;
    static ManagerService    service;
    static UserRepository    userRepo;
    static ReviewRepository  reviewRepo;
    static ManagerRepository managerRepo;

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

        userRepo    = new UserRepository(pool);
        reviewRepo  = new ReviewRepository(pool);
        managerRepo = new ManagerRepository(pool);
        EditRepository   editRepo   = new EditRepository(pool);
        ReportRepository reportRepo = new ReportRepository(pool);
        service = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, users CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void happyPath_reviewPersistedAndManagerProfileUpdated() throws Exception {
        long managerId = insertManager("Jane Smith", "OldCorp", "Junior Manager");
        String auth0Id = insertUser("auth0|happy-path", "HappyUser77");

        Row result = await(service.createReview(auth0Id, managerId, validBody("NewCorp", "Senior Manager", "2022-01", null), null));

        assertNotNull(result);

        // Review was persisted
        long reviewCount = await(pool
            .preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, reviewCount);

        // Manager profile was updated to match the review (most current)
        Row manager = await(pool
            .preparedQuery("SELECT company, title FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));
        assertEquals("NewCorp",        manager.getString("company"));
        assertEquals("Senior Manager", manager.getString("title"));
    }

    @Test
    void olderReview_doesNotUpdateManagerProfile() throws Exception {
        long managerId  = insertManager("Bob Jones", "OriginalCorp", "Director");
        String userA    = insertUser("auth0|user-a", "UserAlpha11");
        String userB    = insertUser("auth0|user-b", "UserBeta22");

        // User A submits a recent review (2023): this sets the manager profile
        await(service.createReview(userA, managerId,
            validBody("RecentCorp", "Lead Engineer", "2023-01", null), null));

        // User B submits an older review (2019-2020): must NOT overwrite the profile
        await(service.createReview(userB, managerId,
            validBody("OldCorp", "Junior Engineer", "2019-01", "2020-12"), null));

        Row manager = await(pool
            .preparedQuery("SELECT company, title FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));

        // Profile must still reflect the most recent review (User A)
        assertEquals("RecentCorp",   manager.getString("company"));
        assertEquals("Lead Engineer", manager.getString("title"));
    }

    @Test
    void currentRoleReview_takesOverPastRoleReview() throws Exception {
        long managerId = insertManager("Alice Wong", "Corp1", "Manager");
        String userA   = insertUser("auth0|user-a2", "UserA2");
        String userB   = insertUser("auth0|user-b2", "UserB2");

        // User A submits an older review (2019-2020)
        await(service.createReview(userA, managerId,
            validBody("OldCorp2", "Junior Dev", "2019-01", "2020-12"), null));

        // User B submits a current role review (workedUntil = null = still there)
        await(service.createReview(userB, managerId,
            validBody("CurrentCorp", "Staff Engineer", "2023-01", null), null));

        Row manager = await(pool
            .preparedQuery("SELECT company, title FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));

        assertEquals("CurrentCorp",   manager.getString("company"));
        assertEquals("Staff Engineer", manager.getString("title"));
    }

    // ── User / auth checks ────────────────────────────────────────────────────

    @Test
    void bannedUser_reviewRejected() throws Exception {
        long managerId = insertManager("Test Manager", "Corp", "Title");
        String auth0Id = insertUser("auth0|banned-user", "BannedUser55");
        UUID userId    = findUserId(auth0Id);

        await(pool
            .preparedQuery("INSERT INTO banned_users(user_id, reason, banned_by) VALUES ($1, $2, $3)")
            .execute(Tuple.of(userId, "spam", "admin")));

        ServiceException ex = assertServiceException(
            service.createReview(auth0Id, managerId, validBody("Corp", "Title", "2022-01", null), null));

        assertEquals(403, ex.getStatusCode());
        assertEquals("account_suspended", ex.getMessage());

        // No review persisted
        assertEquals(0L, countReviews(managerId));
    }

    @Test
    void unknownUser_reviewRejected() {
        ServiceException ex = assertServiceException(
            service.createReview("auth0|does-not-exist", 999L, validBody("Corp", "Title", "2022-01", null), null));
        assertEquals(404, ex.getStatusCode());
    }

    // ── Rate-limiting and cooldown ────────────────────────────────────────────

    @Test
    void dailyLimitExceeded_reviewRejected() throws Exception {
        String auth0Id = insertUser("auth0|daily-limit", "DailyLimitUser33");

        // Submit 6 reviews (to 6 different managers) — all should succeed
        for (int i = 0; i < 6; i++) {
            long mId = insertManager("Manager " + i, "Corp " + i, "Title " + i);
            await(service.createReview(auth0Id, mId,
                validBody("Corp " + i, "Title " + i, "2022-01", "2023-12"), null));
        }

        // 7th review to a 7th manager — must be rejected
        long extraManager = insertManager("Extra Manager", "Extra Corp", "Extra Title");
        ServiceException ex = assertServiceException(
            service.createReview(auth0Id, extraManager,
                validBody("Extra Corp", "Extra Title", "2022-01", "2023-12"), null));
        assertEquals(429, ex.getStatusCode());
        assertEquals("daily_limit_reached", ex.getMessage());
    }

    @Test
    void cooldown_reviewRejectedWithin30Days() throws Exception {
        long managerId = insertManager("Cooldown Manager", "Corp", "Title");
        String auth0Id = insertUser("auth0|cooldown-user", "CooldownUser44");
        UUID userId    = findUserId(auth0Id);

        // Simulate a deletion that happened 5 days ago
        await(pool
            .preparedQuery("INSERT INTO review_deletions(user_id, manager_id, deleted_at) VALUES ($1, $2, $3)")
            .execute(Tuple.of(userId, managerId, OffsetDateTime.now().minusDays(5))));

        ServiceException ex = assertServiceException(
            service.createReview(auth0Id, managerId, validBody("Corp", "Title", "2022-01", null), null));

        assertEquals(409, ex.getStatusCode());
        assertTrue(ex.getMessage().startsWith("review_cooldown:"),
            "Expected cooldown message, got: " + ex.getMessage());
    }

    @Test
    void cooldown_allowedAfter30Days() throws Exception {
        long managerId = insertManager("Past Cooldown Manager", "Corp", "Title");
        String auth0Id = insertUser("auth0|past-cooldown", "PastCooldown11");
        UUID userId    = findUserId(auth0Id);

        // Deletion 31 days ago — cooldown has expired
        await(pool
            .preparedQuery("INSERT INTO review_deletions(user_id, manager_id, deleted_at) VALUES ($1, $2, $3)")
            .execute(Tuple.of(userId, managerId, OffsetDateTime.now().minusDays(31))));

        // Should succeed
        Row result = await(service.createReview(auth0Id, managerId,
            validBody("Corp", "Title", "2022-01", null), null));
        assertNotNull(result);
    }

    // ── Business rule constraints ─────────────────────────────────────────────

    @Test
    void roleCapReached_5ReviewsRejectedOn6th() throws Exception {
        long managerId = insertManager("Cap Manager", "Corp", "Title");
        String auth0Id = insertUser("auth0|cap-user", "CapUser66");

        // 5 reviews with distinct titles
        for (int i = 0; i < 5; i++) {
            await(service.createReview(auth0Id, managerId,
                validBody("Corp", "Role " + i, "2022-01", "2022-12"), null));
        }

        // 6th review — must be rejected
        ServiceException ex = assertServiceException(
            service.createReview(auth0Id, managerId,
                validBody("Corp", "Role 5", "2022-01", "2022-12"), null));
        assertEquals(409, ex.getStatusCode());
        assertEquals("role_limit_reached", ex.getMessage());
    }

    @Test
    void duplicateRole_sameTitleAndCompanyRejected() throws Exception {
        long managerId = insertManager("Dupe Manager", "AcmeCorp", "Engineer");
        String auth0Id = insertUser("auth0|dupe-user", "DupeUser88");

        await(service.createReview(auth0Id, managerId,
            validBody("AcmeCorp", "Engineer", "2022-01", "2023-06"), null));

        // Same title + company again
        ServiceException ex = assertServiceException(
            service.createReview(auth0Id, managerId,
                validBody("AcmeCorp", "Engineer", "2021-01", "2021-12"), null));
        assertEquals(409, ex.getStatusCode());
        assertEquals("already_reviewed_this_role", ex.getMessage());
    }

    @Test
    void duplicateRole_caseInsensitiveMatch() throws Exception {
        long managerId = insertManager("Dupe CI Manager", "AcmeCorp", "Engineer");
        String auth0Id = insertUser("auth0|dupe-ci", "DupeCIUser");

        await(service.createReview(auth0Id, managerId,
            validBody("Acme Corp", "Software Engineer", "2022-01", "2023-06"), null));

        // All-caps variation
        ServiceException ex = assertServiceException(
            service.createReview(auth0Id, managerId,
                validBody("ACME CORP", "SOFTWARE ENGINEER", "2021-01", "2021-12"), null));
        assertEquals(409, ex.getStatusCode());
        assertEquals("already_reviewed_this_role", ex.getMessage());
    }

    @Test
    void differentTitle_sameCompany_allowed() throws Exception {
        long managerId = insertManager("Multi-Role Manager", "Corp", "Title");
        String auth0Id = insertUser("auth0|multi-role", "MultiRoleUser");

        await(service.createReview(auth0Id, managerId,
            validBody("Corp", "Senior Engineer", "2022-01", "2023-06"), null));

        // Different title — allowed
        Row result = await(service.createReview(auth0Id, managerId,
            validBody("Corp", "Staff Engineer", "2019-01", "2021-12"), null));
        assertNotNull(result);
        assertEquals(2L, countReviews(managerId));
    }

    // ── Date validation (end-to-end) ──────────────────────────────────────────

    @Test
    void workedFromInFuture_returns400() throws Exception {
        long managerId = insertManager("Date Manager", "Corp", "Title");
        String auth0Id = insertUser("auth0|date-user", "DateUser11");

        String nextMonth = formatNextMonth();
        ServiceException ex = assertServiceException(
            service.createReview(auth0Id, managerId,
                validBody("Corp", "Title", nextMonth, null), null));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("future"));
        assertEquals(0L, countReviews(managerId));
    }

    @Test
    void workedFromAfterWorkedUntil_returns400() throws Exception {
        long managerId = insertManager("Order Manager", "Corp", "Title");
        String auth0Id = insertUser("auth0|order-user", "OrderUser22");

        ServiceException ex = assertServiceException(
            service.createReview(auth0Id, managerId,
                validBody("Corp", "Title", "2023-06", "2022-01"), null));
        assertEquals(400, ex.getStatusCode());
        assertEquals(0L, countReviews(managerId));
    }

    // ── FK / referential integrity ────────────────────────────────────────────

    @Test
    void nonExistentManager_futureFails() throws Exception {
        String auth0Id = insertUser("auth0|fk-user", "FKUser55");

        // Manager ID 999999 does not exist — FK constraint on reviews will fire
        Future<Row> result = service.createReview(auth0Id, 999999L,
            validBody("Corp", "Title", "2022-01", null), null);

        Throwable cause = null;
        try {
            await(result);
            fail("Expected future to fail");
        } catch (ExecutionException e) {
            cause = e.getCause();
        }
        assertNotNull(cause, "Expected a failure cause");

        // No orphaned reviews
        long total = await(pool
            .query("SELECT COUNT(*) FROM reviews")
            .execute()
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0L, total);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static JsonObject validBody(String company, String title, String workedFrom, String workedUntil) {
        JsonObject ratings = new JsonObject();
        for (String key : new String[]{
                "Communication Style", "Perceived Approachability",
                "Perceived Clarity of Expectations", "Feedback Style",
                "Perceived Supportiveness", "Decision Making Style",
                "Organization and Planning Style", "Delegation Style",
                "Perceived Professional Demeanor", "Overall Working Experience"}) {
            ratings.put(key, 4.0);
        }
        JsonObject body = new JsonObject()
            .put("overallRating",   4.0)
            .put("ratings",         ratings)
            .put("managerCompany",  company)
            .put("managerTitle",    title)
            .put("workedFrom",      workedFrom)
            .put("author",          "AnonTester99")
            .put("authorType",      "anonymous");
        if (workedUntil != null) body.put("workedUntil", workedUntil);
        return body;
    }

    private long insertManager(String name, String company, String title) throws Exception {
        return await(pool
            .preparedQuery("INSERT INTO managers(name,company,title,image,status,overall_rating,reviews_count,category_averages) " +
                           "VALUES ($1,$2,$3,'img','active',0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private String insertUser(String auth0Id, String username) throws Exception {
        await(pool
            .preparedQuery("INSERT INTO users(auth0_id,email,username,first_name,last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User")));
        return auth0Id;
    }

    private UUID findUserId(String auth0Id) throws Exception {
        return await(pool
            .preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private long countReviews(long managerId) throws Exception {
        return await(pool
            .preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong(0)));
    }

    private static String formatNextMonth() {
        java.time.LocalDate next = java.time.LocalDate.now().plusMonths(1);
        return next.getYear() + "-" + String.format("%02d", next.getMonthValue());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // deleteReview
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void deleteReview_happyPath_deletesReviewAndRecordsCooldown() throws Exception {
        long managerId = insertManager("Del Manager", "Corp", "Title");
        String auth0Id = insertUser("auth0|del-user", "DelUser01");
        UUID userId    = findUserId(auth0Id);

        Row reviewRow = await(service.createReview(auth0Id, managerId,
            validBody("Corp", "Title", "2022-01", null), null));
        UUID reviewId = reviewRow.getUUID("id");

        await(service.deleteReview(auth0Id, managerId, reviewId));

        assertEquals(0L, countReviews(managerId));

        long cooldownCount = await(pool
            .preparedQuery("SELECT COUNT(*) FROM review_deletions WHERE user_id = $1 AND manager_id = $2")
            .execute(Tuple.of(userId, managerId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, cooldownCount, "Cooldown entry should have been recorded");
    }

    @Test
    void deleteReview_notOwner_returns403() throws Exception {
        long managerId = insertManager("Del Owner Manager", "Corp", "Title");
        String ownerAuth0Id   = insertUser("auth0|owner-del",  "OwnerDel01");
        String callerAuth0Id  = insertUser("auth0|caller-del", "CallerDel01");

        Row reviewRow = await(service.createReview(ownerAuth0Id, managerId,
            validBody("Corp", "Title", "2022-01", null), null));
        UUID reviewId = reviewRow.getUUID("id");

        ServiceException ex = assertServiceException(
            service.deleteReview(callerAuth0Id, managerId, reviewId));
        assertEquals(403, ex.getStatusCode());
        assertEquals(1L, countReviews(managerId), "Review must not be deleted");
    }

    @Test
    void deleteReview_nonExistentReview_returns404() throws Exception {
        long managerId = insertManager("No Review Manager", "Corp", "Title");
        String auth0Id = insertUser("auth0|del-none", "DelNone01");

        ServiceException ex = assertServiceException(
            service.deleteReview(auth0Id, managerId, UUID.randomUUID()));
        assertEquals(404, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // replaceReview
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void replaceReview_happyPath_replacesReviewAndNoCooldown() throws Exception {
        long managerId = insertManager("Replace Manager", "OrigCorp", "OrigTitle");
        String auth0Id = insertUser("auth0|replace-user", "ReplaceUser01");
        UUID userId    = findUserId(auth0Id);

        Row original = await(service.createReview(auth0Id, managerId,
            validBody("OrigCorp", "OrigTitle", "2022-01", "2023-06"), null));
        UUID oldId = original.getUUID("id");

        Row replacement = await(service.replaceReview(auth0Id, managerId, oldId,
            validBody("NewCorp", "NewTitle", "2022-01", "2023-06"), null));

        assertNotNull(replacement);
        assertNotEquals(oldId, replacement.getUUID("id"), "New review must have a different UUID");

        long total = await(pool
            .preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, total, "Exactly one review should exist after replace");

        long cooldownCount = await(pool
            .preparedQuery("SELECT COUNT(*) FROM review_deletions WHERE user_id = $1 AND manager_id = $2")
            .execute(Tuple.of(userId, managerId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0L, cooldownCount, "Replace must not record a cooldown");
    }

    @Test
    void replaceReview_notOwner_returns403() throws Exception {
        long managerId = insertManager("Replace Owner Manager", "Corp", "Title");
        String ownerAuth0Id  = insertUser("auth0|rep-owner",  "RepOwner01");
        String callerAuth0Id = insertUser("auth0|rep-caller", "RepCaller01");

        Row original = await(service.createReview(ownerAuth0Id, managerId,
            validBody("Corp", "Title", "2022-01", null), null));

        ServiceException ex = assertServiceException(
            service.replaceReview(callerAuth0Id, managerId, original.getUUID("id"),
                validBody("Corp", "NewTitle", "2022-01", null), null));
        assertEquals(403, ex.getStatusCode());
        assertEquals(1L, countReviews(managerId));
    }

    @Test
    void replaceReview_nonExistentReview_returns404() throws Exception {
        long managerId = insertManager("Replace 404 Mgr", "Corp", "Title");
        String auth0Id = insertUser("auth0|rep-404", "Rep404User01");

        ServiceException ex = assertServiceException(
            service.replaceReview(auth0Id, managerId, UUID.randomUUID(),
                validBody("Corp", "Title", "2022-01", null), null));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void replaceReview_invalidBody_returns400_andOriginalReviewIntact() throws Exception {
        long managerId = insertManager("Replace 400 Mgr", "Corp", "Title");
        String auth0Id = insertUser("auth0|rep-400", "Rep400User01");

        Row original = await(service.createReview(auth0Id, managerId,
            validBody("Corp", "Title", "2022-01", null), null));

        // workedFrom in future → sync validation fails before the delete
        JsonObject badBody = validBody("Corp", "NewTitle", null, null)
            .put("workedFrom", "2099-01");
        ServiceException ex = assertServiceException(
            service.replaceReview(auth0Id, managerId, original.getUUID("id"), badBody, null));
        assertEquals(400, ex.getStatusCode());

        // Critical: original review must still exist — validation fired before the delete
        assertEquals(1L, countReviews(managerId),
            "Original review must survive a failed replace — sync validation must run before delete");
    }

    @Test
    void replaceReview_soloReview_managerProfileUpdatedToNewReview() throws Exception {
        // Only one review exists; after replace, the new review is the only and most-current one.
        long managerId = insertManager("Solo Replace Mgr", "InitCorp", "InitTitle");
        String auth0Id = insertUser("auth0|solo-replace", "SoloReplace01");

        Row original = await(service.createReview(auth0Id, managerId,
            validBody("OldCorp", "Old Role", "2021-01", "2022-12"), null));

        await(service.replaceReview(auth0Id, managerId, original.getUUID("id"),
            validBody("NewCorp", "New Role", "2023-01", null), null));

        Row manager = await(pool
            .preparedQuery("SELECT company, title FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));
        assertEquals("NewCorp",  manager.getString("company"));
        assertEquals("New Role", manager.getString("title"));
        assertEquals(1L, countReviews(managerId));
    }

    @Test
    void replaceReview_withEarlierDates_managerProfileUpdatedToNewMostCurrent() throws Exception {
        // Setup: two users review the same manager.
        // User A's review (2023-present) is the most current → manager profile shows "CurrentCorp / Current Role".
        // User B (userA here) replaces their review with earlier dates (2020-2021).
        // After the replace, User B's "CurrentCorp" review is gone.
        // The NEW most-current review is user B's older replacement, but only if no other review is more current.
        // More interesting case: User A has the most-current, User B has a more-recent one.
        // After User B replaces with older dates, User A's review becomes the most-current again.

        long managerId = insertManager("Profile Resync Mgr", "InitCorp", "InitTitle");

        String userA = insertUser("auth0|resync-a", "ResyncUserA");
        String userB = insertUser("auth0|resync-b", "ResyncUserB");

        // User A: older role (2019-2020) → does NOT set manager profile (User B's will be more recent)
        await(service.createReview(userA, managerId,
            validBody("OldCorp", "Junior Dev", "2019-01", "2020-12"), null));

        // User B: recent role (2023-present) → becomes the most current, sets manager profile
        Row originalB = await(service.createReview(userB, managerId,
            validBody("CurrentCorp", "Staff Lead", "2023-01", null), null));
        UUID oldBId = originalB.getUUID("id");

        // Confirm manager profile reflects User B's (most current) review
        Row beforeReplace = await(pool
            .preparedQuery("SELECT company, title FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));
        assertEquals("CurrentCorp", beforeReplace.getString("company"));
        assertEquals("Staff Lead",  beforeReplace.getString("title"));

        // User B replaces their review with EARLIER dates (2018-2019) — now it's no longer most current
        await(service.replaceReview(userB, managerId, oldBId,
            validBody("OldestCorp", "Intern", "2018-01", "2018-12"), null));

        // After replace: User A's review (2019-2020) is now the most current
        Row afterReplace = await(pool
            .preparedQuery("SELECT company, title FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));
        assertEquals("OldCorp",    afterReplace.getString("company"),
            "Manager profile should reflect User A's review (now the most current)");
        assertEquals("Junior Dev", afterReplace.getString("title"),
            "Manager title should reflect the new most-current review");
    }

    @Test
    void replaceReview_withMoreRecentDates_managerProfileUpdatedToNewReview() throws Exception {
        long managerId = insertManager("Profile Forward Mgr", "InitCorp", "InitTitle");
        String userA = insertUser("auth0|forward-a", "ForwardUserA");
        String userB = insertUser("auth0|forward-b", "ForwardUserB");

        // User A: current role (most recent)
        await(service.createReview(userA, managerId,
            validBody("ExistingCorp", "Manager", "2022-01", null), null));

        // User B: older role
        Row originalB = await(service.createReview(userB, managerId,
            validBody("OldCorp", "Analyst", "2019-01", "2021-12"), null));
        UUID oldBId = originalB.getUUID("id");

        // User B replaces with a NEWER date (2024-present) — now User B is most current
        await(service.replaceReview(userB, managerId, oldBId,
            validBody("NewestCorp", "Director", "2024-01", null), null));

        Row afterReplace = await(pool
            .preparedQuery("SELECT company, title FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));
        assertEquals("NewestCorp", afterReplace.getString("company"));
        assertEquals("Director",   afterReplace.getString("title"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // updateReview
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void updateReview_happyPath_updatesFieldsInDb() throws Exception {
        long managerId = insertManager("Update Manager", "OrigCorp", "OrigTitle");
        String auth0Id = insertUser("auth0|update-user", "UpdateUser01");

        Row created = await(service.createReview(auth0Id, managerId,
            validBody("OrigCorp", "OrigTitle", "2022-01", "2023-06"), null));
        UUID reviewId = created.getUUID("id");

        await(service.updateReview(auth0Id, managerId, reviewId,
            validBody("UpdatedCorp", "UpdatedTitle", "2022-01", "2023-06")));

        Row row = await(pool
            .preparedQuery("SELECT manager_company, manager_title FROM reviews WHERE id = $1")
            .execute(Tuple.of(reviewId))
            .map(rs -> rs.iterator().next()));
        assertEquals("UpdatedCorp",  row.getString("manager_company"));
        assertEquals("UpdatedTitle", row.getString("manager_title"));
    }

    @Test
    void updateReview_notOwner_returns404() throws Exception {
        long managerId = insertManager("Update Owner Manager", "Corp", "Title");
        String ownerAuth0Id  = insertUser("auth0|upd-owner",  "UpdOwner01");
        String callerAuth0Id = insertUser("auth0|upd-caller", "UpdCaller01");

        Row created = await(service.createReview(ownerAuth0Id, managerId,
            validBody("Corp", "Title", "2022-01", null), null));

        // updateReview enforces ownership via the UPDATE WHERE user_id = callerId clause;
        // when no row matches, it returns Optional.empty() → 404
        ServiceException ex = assertServiceException(
            service.updateReview(callerAuth0Id, managerId, created.getUUID("id"),
                validBody("Corp", "NewTitle", "2022-01", null)));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void updateReview_nonExistentReview_returns404() throws Exception {
        long managerId = insertManager("Upd None Manager", "Corp", "Title");
        String auth0Id = insertUser("auth0|upd-none", "UpdNone01");

        ServiceException ex = assertServiceException(
            service.updateReview(auth0Id, managerId, UUID.randomUUID(),
                validBody("Corp", "Title", "2022-01", null)));
        assertEquals(404, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getMyReviews
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getMyReviews_returnsAllUserReviews() throws Exception {
        long mgrA    = insertManager("My Rev Manager A", "CorpA", "TitleA");
        long mgrB    = insertManager("My Rev Manager B", "CorpB", "TitleB");
        String auth0 = insertUser("auth0|myrev-user", "MyRevUser01");

        await(service.createReview(auth0, mgrA, validBody("CorpA", "TitleA", "2021-01", "2022-12"), null));
        await(service.createReview(auth0, mgrB, validBody("CorpB", "TitleB", "2022-01", "2023-12"), null));

        io.vertx.core.json.JsonObject result =
            (io.vertx.core.json.JsonObject) await(service.getMyReviews(auth0, 50, 0));
        assertNotNull(result);
        assertEquals(2, result.getJsonArray("data").size());
        assertEquals(2L, (long) result.getLong("total"));
    }

    @Test
    void getMyReviews_unknownUser_returns404() {
        ServiceException ex = assertServiceException(service.getMyReviews("auth0|no-such-user", 50, 0));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void getMyReviews_pagination_offsetSkipsReviews() throws Exception {
        long mgr = insertManager("Paginate Mgr", "PaginateCorp", "PaginateTitle");
        String auth0 = insertUser("auth0|paginate-user", "PaginateUser01");
        await(service.createReview(auth0, mgr, validBody("PaginateCorp", "PaginateTitle", "2021-01", "2021-06"), null));

        io.vertx.core.json.JsonObject page1 =
            (io.vertx.core.json.JsonObject) await(service.getMyReviews(auth0, 50, 0));
        assertEquals(1, page1.getJsonArray("data").size());
        assertEquals(1L, (long) page1.getLong("total"));

        io.vertx.core.json.JsonObject page2 =
            (io.vertx.core.json.JsonObject) await(service.getMyReviews(auth0, 50, 1));
        assertEquals(0, page2.getJsonArray("data").size());
        assertEquals(1L, (long) page2.getLong("total"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getManagerById
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getManagerById_approvedManager_returnsRow() throws Exception {
        long managerId = insertManagerWithStatus("Approved Mgr", "Corp", "Title", "approved");
        String auth0Id = insertUser("auth0|getbyid-user", "GetById01");

        Row result = await(service.getManagerById(managerId, auth0Id));
        assertNotNull(result);
        assertEquals("Approved Mgr", result.getString("name"));
    }

    @Test
    void getManagerById_pendingApproval_submitterCanAccess() throws Exception {
        String submitterAuth0 = insertUser("auth0|submitter-gbid", "SubmitterGbid01");
        long managerId = insertManagerByUser("Pending Mgr", findUserId(submitterAuth0));

        Row result = await(service.getManagerById(managerId, submitterAuth0));
        assertNotNull(result);
    }

    @Test
    void getManagerById_pendingApproval_otherUser_returns404() throws Exception {
        String submitterAuth0 = insertUser("auth0|sub-gbid2",   "SubGbid02");
        String otherAuth0     = insertUser("auth0|other-gbid2", "OtherGbid02");
        long managerId = insertManagerByUser("Pending Mgr2", findUserId(submitterAuth0));

        ServiceException ex = assertServiceException(
            service.getManagerById(managerId, otherAuth0));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void getManagerById_nonExistent_returns404() {
        ServiceException ex = assertServiceException(
            service.getManagerById(999999L, null));
        assertEquals(404, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // hasReported
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void hasReported_nullAuth0Id_returnsFalse() throws Exception {
        long managerId = insertManager("Reported Mgr", "Corp", "Title");
        assertFalse(await(service.hasReported(managerId, null)));
    }

    @Test
    void hasReported_userNotFound_returnsFalse() throws Exception {
        long managerId = insertManager("Reported Mgr2", "Corp", "Title");
        assertFalse(await(service.hasReported(managerId, "auth0|nobody")));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Role-period overlap (end-to-end)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void managerRoleOverlap_returns409() throws Exception {
        long managerId = insertManager("Overlap Mgr", "Corp", "Title");
        String userA   = insertUser("auth0|overlap-a", "OverlapUserA");
        String userB   = insertUser("auth0|overlap-b", "OverlapUserB");

        // First review: role period 2020-01 to 2022-12
        await(service.createReview(userA, managerId,
            validBodyWithRolePeriod("Corp", "Eng", "2021-01", "2022-06",
                LocalDate.of(2020, 1, 1), LocalDate.of(2022, 12, 31)), null));

        // Second review: overlapping role period 2022-01 to 2023-06 — overlaps with above
        ServiceException ex = assertServiceException(
            service.createReview(userB, managerId,
                validBodyWithRolePeriod("Corp", "Manager", "2022-01", null,
                    LocalDate.of(2022, 1, 1), LocalDate.of(2023, 6, 30)), null));
        assertEquals(409, ex.getStatusCode());
        assertTrue(ex.getMessage().startsWith("manager_role_overlap:"));
    }

    @Test
    void managerRoleOverlap_noOverlap_bothSucceed() throws Exception {
        long managerId = insertManager("No Overlap Mgr", "Corp", "Title");
        String userA   = insertUser("auth0|noverlap-a", "NoOverlapA");
        String userB   = insertUser("auth0|noverlap-b", "NoOverlapB");

        // First: 2020-01 to 2021-12
        await(service.createReview(userA, managerId,
            validBodyWithRolePeriod("Corp", "Junior", "2020-06", "2021-06",
                LocalDate.of(2020, 1, 1), LocalDate.of(2021, 12, 31)), null));

        // Second: 2022-01 to 2023-06 — adjacent, no overlap
        Row result = await(service.createReview(userB, managerId,
            validBodyWithRolePeriod("Corp", "Senior", "2022-03", "2023-01",
                LocalDate.of(2022, 1, 1), LocalDate.of(2023, 6, 30)), null));
        assertNotNull(result);
        assertEquals(2L, countReviews(managerId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Additional helpers
    // ══════════════════════════════════════════════════════════════════════════

    private long insertManagerWithStatus(String name, String company, String title, String status) throws Exception {
        return await(pool
            .preparedQuery("INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages) " +
                           "VALUES ($1,$2,$3,'img','active',$4,0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title, status))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertManagerByUser(String name, UUID submittedBy) throws Exception {
        return await(pool
            .preparedQuery("INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages,submitted_by) " +
                           "VALUES ($1,'Corp','Title','img','active','pending_approval',0,0,'{}',$2) RETURNING id")
            .execute(Tuple.of(name, submittedBy))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static JsonObject validBodyWithRolePeriod(
            String company, String title, String workedFrom, String workedUntil,
            LocalDate roleStart, LocalDate roleEnd) {
        JsonObject body = validBody(company, title, workedFrom, workedUntil);
        body.put("managerRoleStart", roleStart.toString().substring(0, 7)); // YYYY-MM
        if (roleEnd != null) body.put("managerRoleEnd", roleEnd.toString().substring(0, 7));
        return body;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getManagerCareerSegments
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getManagerCareerSegments_returnsSegmentsWithPagination() throws Exception {
        long managerId = insertManager("Career Mgr", "CareerCorp", "Title");
        String userA = insertUser("auth0|career-a", "CareerUserA");
        String userB = insertUser("auth0|career-b", "CareerUserB");

        // Two distinct company+title combinations → two segments
        await(service.createReview(userA, managerId, validBody("CorpA", "RoleA", "2021-01", "2022-06"), null));
        await(service.createReview(userB, managerId, validBody("CorpB", "RoleB", "2022-07", "2023-12"), null));

        io.vertx.core.json.JsonObject result =
            (io.vertx.core.json.JsonObject) await(service.getManagerCareerSegments(managerId, 20, 0));
        assertNotNull(result);
        assertEquals(2L, (long) result.getLong("total"));
        assertEquals(2, result.getJsonArray("data").size());
        assertEquals(20, (int) result.getInteger("limit"));
        assertEquals(0, (int) result.getInteger("offset"));
    }

    @Test
    void getManagerCareerSegments_pagination_offsetSkipsSegments() throws Exception {
        long managerId = insertManager("Career Page Mgr", "CPCorp", "Title");
        String userA = insertUser("auth0|cp-a", "CpUserA");
        String userB = insertUser("auth0|cp-b", "CpUserB");

        await(service.createReview(userA, managerId, validBody("CorpA", "RoleA", "2021-01", "2022-06"), null));
        await(service.createReview(userB, managerId, validBody("CorpB", "RoleB", "2022-07", "2023-12"), null));

        // Offset 0, limit 1 → first segment only
        io.vertx.core.json.JsonObject page1 =
            (io.vertx.core.json.JsonObject) await(service.getManagerCareerSegments(managerId, 1, 0));
        assertEquals(1, page1.getJsonArray("data").size());
        assertEquals(2L, (long) page1.getLong("total"));

        // Offset 1, limit 1 → second segment
        io.vertx.core.json.JsonObject page2 =
            (io.vertx.core.json.JsonObject) await(service.getManagerCareerSegments(managerId, 1, 1));
        assertEquals(1, page2.getJsonArray("data").size());
        assertEquals(2L, (long) page2.getLong("total"));
    }

    @Test
    void getManagerCareerSegments_noReviews_returnsEmptyWithZeroTotal() throws Exception {
        long managerId = insertManager("Empty Career Mgr", "EmptyCorp", "Title");

        io.vertx.core.json.JsonObject result =
            (io.vertx.core.json.JsonObject) await(service.getManagerCareerSegments(managerId, 20, 0));
        assertNotNull(result);
        assertEquals(0L, (long) result.getLong("total"));
        assertEquals(0, result.getJsonArray("data").size());
    }

    private static ServiceException assertServiceException(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            fail("Expected future to fail");
            return null;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ServiceException se) return se;
            fail("Expected ServiceException but got: " + e.getCause());
            return null;
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
            return null;
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getStats — realReviews excludes weight=true; weightedOpinions counts them
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getStats_realReviews_excludesWeightedReviews() throws Exception {
        long managerId = insertManager("Stats Manager", "StatsCorp", "Title");
        String auth0Id = insertUser("auth0|stats-user", "StatsUser01");

        // Insert one real review and one weighted (seed) review
        await(service.createReview(auth0Id, managerId, validBody("StatsCorp", "Title", "2022-01", null), null));
        await(pool.preparedQuery(
                "INSERT INTO reviews (manager_id, author, overall_rating, " +
                "communication_style, perceived_approachability, perceived_clarity_of_expectations, " +
                "feedback_style, perceived_supportiveness, decision_making_style, " +
                "organization_and_planning_style, delegation_style, perceived_professional_demeanor, " +
                "overall_working_experience, manager_company, manager_title, worked_from, " +
                "verified, helpful_count, weight, created_at, updated_at) " +
                "VALUES ($1, 'Anonymous', 4.0, 4.0,4.0,4.0,4.0,4.0,4.0,4.0,4.0,4.0,4.0, " +
                "'StatsCorp', 'Title', '2022-01-01', true, 0, true, now(), now())")
            .execute(Tuple.of(managerId)));

        JsonObject stats = await(service.getStats());

        assertEquals(1L, stats.getLong("realReviews"),
            "realReviews must exclude weight=true reviews");
        assertEquals(1L, stats.getLong("weightedOpinions"),
            "weightedOpinions must count active weight=true reviews");
    }

    @Test
    void getStats_weightedOpinions_excludesExpiredSeeds() throws Exception {
        long managerId = insertManager("Expired Stats Mgr", "ExpiredCorp", "Title");

        // Insert an expired seed (weight=true, weight_expires_on in the past)
        await(pool.preparedQuery(
                "INSERT INTO reviews (manager_id, author, overall_rating, " +
                "communication_style, perceived_approachability, perceived_clarity_of_expectations, " +
                "feedback_style, perceived_supportiveness, decision_making_style, " +
                "organization_and_planning_style, delegation_style, perceived_professional_demeanor, " +
                "overall_working_experience, manager_company, manager_title, worked_from, " +
                "verified, helpful_count, weight, weight_expires_on, created_at, updated_at) " +
                "VALUES ($1, 'Anonymous', 4.0, 4.0,4.0,4.0,4.0,4.0,4.0,4.0,4.0,4.0,4.0, " +
                "'ExpiredCorp', 'Title', '2022-01-01', true, 0, true, CURRENT_DATE - 1, now(), now())")
            .execute(Tuple.of(managerId)));

        JsonObject stats = await(service.getStats());

        assertEquals(0L, stats.getLong("weightedOpinions"),
            "weightedOpinions must not count expired seeds");
    }
}
