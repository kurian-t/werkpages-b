package org.werkpages.integration;

import io.vertx.core.Future;
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
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RepositoryCoverageIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool              pool;
    static CompanyRepository companyRepo;
    static UserRepository    userRepo;
    static ReviewRepository  reviewRepo;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load()
            .migrate();

        PgConnectOptions opts = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        pool        = PgPool.pool(opts, new PoolOptions().setMaxSize(5));
        companyRepo = new CompanyRepository(pool);
        userRepo    = new UserRepository(pool);
        reviewRepo  = new ReviewRepository(pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, users, companies CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── CompanyRepository.updateLogo ──────────────────────────────────────────

    @Test
    void updateLogo_setsLogoUrl() throws Exception {
        Row company = await(companyRepo.findOrCreate("LogoCo", null, null));
        long id = company.getLong("id");

        boolean updated = await(companyRepo.updateLogo(id, "https://img.logo.dev/logoco.com?token=x"));

        assertTrue(updated);
        Row row = await(pool.preparedQuery("SELECT logo_url FROM companies WHERE id = $1")
            .execute(Tuple.of(id))
            .map(rs -> rs.iterator().next()));
        assertEquals("https://img.logo.dev/logoco.com?token=x", row.getString("logo_url"));
    }

    @Test
    void updateLogo_nonExistentId_returnsFalse() throws Exception {
        boolean updated = await(companyRepo.updateLogo(Long.MAX_VALUE, "https://img.logo.dev/x.com?token=x"));
        assertFalse(updated);
    }

    // ── CompanyRepository.mergeCompanies ─────────────────────────────────────

    @Test
    void mergeCompanies_reassignsManagersAndDeletesSource() throws Exception {
        Row keep  = await(companyRepo.findOrCreate("KeepCo",  null, null));
        Row merge = await(companyRepo.findOrCreate("MergeCo", null, null));
        long keepId  = keep.getLong("id");
        long mergeId = merge.getLong("id");

        // Insert a manager linked to the merge company
        long managerId = await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages,company_id) " +
            "VALUES ('Mgr One','MergeCo','Director','img','active','ghost',null,0,null,$1) RETURNING id")
            .execute(Tuple.of(mergeId))
            .map(rs -> rs.iterator().next().getLong("id")));

        await(companyRepo.mergeCompanies(keepId, mergeId));

        // The source company row must be gone
        Long mergeCount = await(pool.preparedQuery("SELECT COUNT(*) FROM companies WHERE id = $1")
            .execute(Tuple.of(mergeId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0L, mergeCount, "source company must be deleted after merge");

        // The manager must now point to keepId and carry the keep company name
        Row mgr = await(pool.preparedQuery("SELECT company_id, company FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));
        assertEquals(keepId, (long) mgr.getLong("company_id"));
        assertEquals("KeepCo", mgr.getString("company"));
    }

    @Test
    void mergeCompanies_targetNotFound_fails() {
        Future<Void> result = companyRepo.mergeCompanies(Long.MAX_VALUE, Long.MAX_VALUE - 1);
        assertThrows(Exception.class, () -> await(result));
    }

    // ── CompanyRepository.findOrCreate recover path ───────────────────────────
    // The recover() branch handles a 23505 on the companies_name_ci unique index.
    // To trigger it: insert a row with a custom slug that doesn't match what findOrCreate
    // would compute for the same name, so the ON CONFLICT (slug) clause misses it,
    // and the INSERT hits the companies_name_ci index → 23505 → recover().

    @Test
    void findOrCreate_recoverPath_returnsExistingCompanyWhenNameCiConflicts() throws Exception {
        // Insert with a slug that differs from the computed slug for "RecoverCo"
        await(pool.preparedQuery(
            "INSERT INTO companies(name, slug, status, created_at, updated_at) " +
            "VALUES ('RecoverCo', 'custom-slug-that-differs', 'ghost', now(), now())")
            .execute());

        // findOrCreate will try ON CONFLICT (slug) for slug='recoverco', which won't match
        // 'custom-slug-that-differs', so the INSERT proceeds and hits companies_name_ci → recover()
        Row found = await(companyRepo.findOrCreate("RecoverCo", null, null));

        assertNotNull(found);
        assertEquals("RecoverCo", found.getString("name"));
    }

    // ── UserRepository.findByIdForAdmin ──────────────────────────────────────

    @Test
    void findByIdForAdmin_returnsUserRow() throws Exception {
        await(userRepo.create("auth0|admin-lookup-1", "admin1@test.com", "AdminLookup1", "Admin", "One"));
        UUID userId = await(userRepo.findIdByAuth0Id("auth0|admin-lookup-1")).orElseThrow();

        Optional<Row> opt = await(userRepo.findByIdForAdmin(userId));

        assertTrue(opt.isPresent());
        assertEquals("AdminLookup1", opt.get().getString("username"));
    }

    @Test
    void findByIdForAdmin_unknownId_returnsEmpty() throws Exception {
        Optional<Row> opt = await(userRepo.findByIdForAdmin(UUID.randomUUID()));
        assertTrue(opt.isEmpty());
    }

    // ── UserRepository.findEmailByUsername ────────────────────────────────────

    @Test
    void findEmailByUsername_returnsEmail() throws Exception {
        // Insert with plaintext email column so COALESCE(email_encrypted, email) resolves plaintext
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id, email, username, first_name, last_name) " +
            "VALUES ($1, $2, $3, $4, $5)")
            .execute(Tuple.of("auth0|email-uname-1", "emailuname@test.com", "EmailUname1", "Email", "User")));

        Optional<String> email = await(userRepo.findEmailByUsername("EmailUname1"));

        assertTrue(email.isPresent());
        assertEquals("emailuname@test.com", email.get());
    }

    @Test
    void findEmailByUsername_caseInsensitive() throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id, email, username, first_name, last_name) " +
            "VALUES ($1, $2, $3, $4, $5)")
            .execute(Tuple.of("auth0|email-uname-2", "caseemail@test.com", "CaseUname2", "Case", "User")));

        Optional<String> email = await(userRepo.findEmailByUsername("caseuname2"));

        assertTrue(email.isPresent());
        assertEquals("caseemail@test.com", email.get());
    }

    @Test
    void findEmailByUsername_unknownUsername_returnsEmpty() throws Exception {
        Optional<String> email = await(userRepo.findEmailByUsername("NoSuchUser999"));
        assertTrue(email.isEmpty());
    }

    // ── UserRepository.findUsernameByAuth0Id ─────────────────────────────────

    @Test
    void findUsernameByAuth0Id_returnsUsername() throws Exception {
        await(userRepo.create("auth0|uname-lookup-1", "unamelookup@test.com", "UnameLookup1", "Uname", "One"));

        String username = await(userRepo.findUsernameByAuth0Id("auth0|uname-lookup-1"));

        assertEquals("UnameLookup1", username);
    }

    @Test
    void findUsernameByAuth0Id_unknownId_returnsAdmin() throws Exception {
        String username = await(userRepo.findUsernameByAuth0Id("auth0|nonexistent-user-xyz"));
        assertEquals("admin", username);
    }

    // ── ReviewRepository.findByManager with userIdFilter ─────────────────────

    @Test
    void findByManager_withUserIdFilter_returnsOnlyThatUsersReviews() throws Exception {
        long managerId = insertManager("Filter Mgr", "FilterCo");
        await(userRepo.create("auth0|filter-1", "filter1@test.com", "FilterUser1", "Filter", "One"));
        UUID userId = await(userRepo.findIdByAuth0Id("auth0|filter-1")).orElseThrow();

        await(insertReview(managerId, userId));

        var rows = await(reviewRepo.findByManager(managerId, 10, 0, "recent", userId));

        assertEquals(1, rows.size());
        assertEquals(userId, rows.iterator().next().getUUID("user_id"));
    }

    @Test
    void findByManager_withUserIdFilter_excludesOtherUsersReviews() throws Exception {
        long managerId = insertManager("Filter Mgr2", "FilterCo2");
        await(userRepo.create("auth0|filter-2a", "filter2a@test.com", "FilterUser2A", "Filter", "Two"));
        await(userRepo.create("auth0|filter-2b", "filter2b@test.com", "FilterUser2B", "Filter", "Three"));
        UUID userId1 = await(userRepo.findIdByAuth0Id("auth0|filter-2a")).orElseThrow();
        UUID userId2 = await(userRepo.findIdByAuth0Id("auth0|filter-2b")).orElseThrow();

        // Different authors and titles to satisfy the unique-per-(author, manager, role) constraint
        await(insertReview(managerId, userId1, "AuthorAlpha", "Manager"));
        await(insertReview(managerId, userId2, "AuthorBeta",  "Senior Manager"));

        var rows = await(reviewRepo.findByManager(managerId, 10, 0, "recent", userId1));

        assertEquals(1, rows.size());
        assertEquals(userId1, rows.iterator().next().getUUID("user_id"));
    }

    // ── ReviewRepository.countByManager with userIdFilter ────────────────────

    @Test
    void countByManager_withUserIdFilter_countsOnlyThatUsersReviews() throws Exception {
        long managerId       = insertManager("Count Filter Mgr",  "CountFilterCo");
        long otherManagerId  = insertManager("Count Filter Mgr2", "CountFilterCo2");
        await(userRepo.create("auth0|count-filter-1", "cntf1@test.com", "CntFilterUsr1", "Cnt", "One"));
        await(userRepo.create("auth0|count-filter-2", "cntf2@test.com", "CntFilterUsr2", "Cnt", "Two"));
        UUID userId      = await(userRepo.findIdByAuth0Id("auth0|count-filter-1")).orElseThrow();
        UUID otherUserId = await(userRepo.findIdByAuth0Id("auth0|count-filter-2")).orElseThrow();

        await(insertReview(managerId,      userId,      "CntAuthor1", "Manager"));
        await(insertReview(otherManagerId, otherUserId, "CntAuthor2", "Director"));

        // userId has exactly 1 review for managerId; otherUserId's review is for a different manager
        long count = await(reviewRepo.countByManager(managerId, userId));

        assertEquals(1L, count);
    }

    @Test
    void countByManager_withUserIdFilter_returnsZeroWhenNone() throws Exception {
        long managerId = insertManager("Count Zero Mgr", "CountZeroCo");
        long count = await(reviewRepo.countByManager(managerId, UUID.randomUUID()));
        assertEquals(0L, count);
    }

    // ── ReviewRepository.deleteSeedReview ────────────────────────────────────

    @Test
    void deleteSeedReview_removesSeedReview() throws Exception {
        long managerId = insertManager("Seed Mgr", "SeedCo");

        await(reviewRepo.createSeedReview(managerId, "SeedCo", "Manager"));

        Long before = await(pool.preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1 AND weight = TRUE")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, before);

        await(reviewRepo.deleteSeedReview(managerId));

        Long after = await(pool.preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1 AND weight = TRUE")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0L, after);
    }

    @Test
    void deleteSeedReview_noSeedPresent_doesNothing() throws Exception {
        long managerId = insertManager("No Seed Mgr", "NoSeedCo");
        // Should complete without error even when there is no seed review
        await(reviewRepo.deleteSeedReview(managerId));
    }

    // ── ReviewRepository.deleteByDraftToken ──────────────────────────────────

    @Test
    void deleteByDraftToken_removesUnauthenticatedDraftReview() throws Exception {
        long managerId = insertManager("Draft Mgr", "DraftCo");
        UUID draftToken = UUID.randomUUID();

        // Draft reviews have no user_id and carry a draft_token
        await(pool.preparedQuery(
            "INSERT INTO reviews(manager_id, user_id, author, overall_rating, " +
            "communication_style, perceived_approachability, perceived_clarity_of_expectations, " +
            "feedback_style, perceived_supportiveness, decision_making_style, " +
            "organization_and_planning_style, delegation_style, perceived_professional_demeanor, " +
            "overall_working_experience, manager_company, manager_title, draft_token) " +
            "VALUES ($1, NULL, 'DraftAuthor', 4.0, 4,4,4,4,4,4,4,4,4,4, 'DraftCo','Manager',$2)")
            .execute(Tuple.of(managerId, draftToken)));

        await(reviewRepo.deleteByDraftToken(draftToken));

        Long count = await(pool.preparedQuery("SELECT COUNT(*) FROM reviews WHERE draft_token = $1")
            .execute(Tuple.of(draftToken))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0L, count);
    }

    @Test
    void deleteByDraftToken_doesNotDeleteWhenUserIdSet() throws Exception {
        long managerId = insertManager("Draft Auth Mgr", "DraftAuthCo");
        await(userRepo.create("auth0|draft-auth-1", "draftauth@test.com", "DraftAuthUsr1", "Draft", "Auth"));
        UUID userId = await(userRepo.findIdByAuth0Id("auth0|draft-auth-1")).orElseThrow();
        UUID draftToken = UUID.randomUUID();

        // Review has a user_id set — deleteByDraftToken must not touch it
        await(pool.preparedQuery(
            "INSERT INTO reviews(manager_id, user_id, author, overall_rating, " +
            "communication_style, perceived_approachability, perceived_clarity_of_expectations, " +
            "feedback_style, perceived_supportiveness, decision_making_style, " +
            "organization_and_planning_style, delegation_style, perceived_professional_demeanor, " +
            "overall_working_experience, manager_company, manager_title, draft_token) " +
            "VALUES ($1, $2, 'AuthDraft', 4.0, 4,4,4,4,4,4,4,4,4,4, 'DraftAuthCo','Manager',$3)")
            .execute(Tuple.of(managerId, userId, draftToken)));

        await(reviewRepo.deleteByDraftToken(draftToken));

        Long count = await(pool.preparedQuery("SELECT COUNT(*) FROM reviews WHERE draft_token = $1")
            .execute(Tuple.of(draftToken))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, count, "review with user_id must not be deleted by deleteByDraftToken");
    }

    // ── ReviewRepository.findMostCurrentReviewForManager ─────────────────────

    @Test
    void findMostCurrentReviewForManager_prefersCurrentRole() throws Exception {
        long managerId = insertManager("Current Role Mgr", "CurrentRoleCo");
        await(userRepo.create("auth0|current-1", "current1@test.com", "CurrentUsr1", "Current", "One"));
        await(userRepo.create("auth0|current-2", "current2@test.com", "CurrentUsr2", "Current", "Two"));
        UUID userId1 = await(userRepo.findIdByAuth0Id("auth0|current-1")).orElseThrow();
        UUID userId2 = await(userRepo.findIdByAuth0Id("auth0|current-2")).orElseThrow();

        // Older review with worked_until set
        await(pool.preparedQuery(
            "INSERT INTO reviews(manager_id, user_id, author, overall_rating, " +
            "communication_style, perceived_approachability, perceived_clarity_of_expectations, " +
            "feedback_style, perceived_supportiveness, decision_making_style, " +
            "organization_and_planning_style, delegation_style, perceived_professional_demeanor, " +
            "overall_working_experience, manager_company, manager_title, worked_from, worked_until) " +
            "VALUES ($1,$2,'OldAuthor',3.0,3,3,3,3,3,3,3,3,3,3,'CurrentRoleCo','Manager','2020-01-01','2022-01-01')")
            .execute(Tuple.of(managerId, userId1)));

        // Newer review with worked_until IS NULL (still working there)
        await(pool.preparedQuery(
            "INSERT INTO reviews(manager_id, user_id, author, overall_rating, " +
            "communication_style, perceived_approachability, perceived_clarity_of_expectations, " +
            "feedback_style, perceived_supportiveness, decision_making_style, " +
            "organization_and_planning_style, delegation_style, perceived_professional_demeanor, " +
            "overall_working_experience, manager_company, manager_title, worked_from, worked_until) " +
            "VALUES ($1,$2,'CurrentAuthor',5.0,5,5,5,5,5,5,5,5,5,5,'CurrentRoleCo','Senior Manager','2022-06-01',NULL)")
            .execute(Tuple.of(managerId, userId2)));

        Row most = await(reviewRepo.findMostCurrentReviewForManager(managerId));

        assertNotNull(most);
        assertEquals("Senior Manager", most.getString("manager_title"),
            "review with worked_until IS NULL should be preferred");
    }

    @Test
    void findMostCurrentReviewForManager_fallsBackToLatestWorkedFrom() throws Exception {
        long managerId = insertManager("Latest From Mgr", "LatestFromCo");
        await(userRepo.create("auth0|latest-1", "latest1@test.com", "LatestUsr1", "Latest", "One"));
        await(userRepo.create("auth0|latest-2", "latest2@test.com", "LatestUsr2", "Latest", "Two"));
        UUID userId1 = await(userRepo.findIdByAuth0Id("auth0|latest-1")).orElseThrow();
        UUID userId2 = await(userRepo.findIdByAuth0Id("auth0|latest-2")).orElseThrow();

        // Two reviews both with worked_until set — most recent worked_from wins
        await(pool.preparedQuery(
            "INSERT INTO reviews(manager_id, user_id, author, overall_rating, " +
            "communication_style, perceived_approachability, perceived_clarity_of_expectations, " +
            "feedback_style, perceived_supportiveness, decision_making_style, " +
            "organization_and_planning_style, delegation_style, perceived_professional_demeanor, " +
            "overall_working_experience, manager_company, manager_title, worked_from, worked_until) " +
            "VALUES ($1,$2,'Early',3.0,3,3,3,3,3,3,3,3,3,3,'LatestFromCo','Junior','2019-01-01','2020-01-01')")
            .execute(Tuple.of(managerId, userId1)));

        await(pool.preparedQuery(
            "INSERT INTO reviews(manager_id, user_id, author, overall_rating, " +
            "communication_style, perceived_approachability, perceived_clarity_of_expectations, " +
            "feedback_style, perceived_supportiveness, decision_making_style, " +
            "organization_and_planning_style, delegation_style, perceived_professional_demeanor, " +
            "overall_working_experience, manager_company, manager_title, worked_from, worked_until) " +
            "VALUES ($1,$2,'Late',4.0,4,4,4,4,4,4,4,4,4,4,'LatestFromCo','Senior','2021-01-01','2023-01-01')")
            .execute(Tuple.of(managerId, userId2)));

        Row most = await(reviewRepo.findMostCurrentReviewForManager(managerId));

        assertNotNull(most);
        assertEquals("Senior", most.getString("manager_title"));
    }

    @Test
    void findMostCurrentReviewForManager_noReviews_returnsNull() throws Exception {
        long managerId = insertManager("Empty Mgr", "EmptyCo");
        Row result = await(reviewRepo.findMostCurrentReviewForManager(managerId));
        assertNull(result);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long insertManager(String name, String company) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages) " +
            "VALUES ($1,$2,'Manager','img','active','ghost',null,0,null) RETURNING id")
            .execute(Tuple.of(name, company))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private Future<Row> insertReview(long managerId, UUID userId, String author, String title) {
        return pool.preparedQuery(
            "INSERT INTO reviews(manager_id, user_id, author, overall_rating, " +
            "communication_style, perceived_approachability, perceived_clarity_of_expectations, " +
            "feedback_style, perceived_supportiveness, decision_making_style, " +
            "organization_and_planning_style, delegation_style, perceived_professional_demeanor, " +
            "overall_working_experience, manager_company, manager_title) " +
            "VALUES ($1,$2,$3,4.0,4,4,4,4,4,4,4,4,4,4,'TestCo',$4) RETURNING id")
            .execute(Tuple.of(managerId, userId, author, title))
            .map(rs -> rs.iterator().next());
    }

    private Future<Row> insertReview(long managerId, UUID userId) {
        return insertReview(managerId, userId, "TestAuthor", "Manager");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
