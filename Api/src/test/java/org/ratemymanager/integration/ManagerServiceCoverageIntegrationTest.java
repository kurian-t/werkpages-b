package org.ratemymanager.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ManagerServiceCoverageIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("ratemymanager_test")
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
        pool.query("TRUNCATE managers, users CASCADE").execute()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── getManagers — validation errors ──────────────────────────────────────

    @Test
    void getManagers_searchQueryTooLong_fails() {
        String longQuery = "a".repeat(101);
        try {
            await(service.getManagers(10, 0, longQuery, null, null));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void getManagers_companyFilterTooLong_fails() {
        String longCompany = "b".repeat(101);
        try {
            await(service.getManagers(10, 0, null, longCompany, null));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    // ── getManagerById ────────────────────────────────────────────────────────

    @Test
    void getManagerById_notFound_fails() {
        try {
            await(service.getManagerById(999999L, null));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void getManagerById_rejectedManager_fails() throws Exception {
        long id = insertManager("Rejected Mgr", "Corp", "Engineer", "rejected");
        try {
            await(service.getManagerById(id, null));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void getManagerById_approvedManager_succeeds() throws Exception {
        long id = insertManager("Approved Mgr", "ApprovedCorp", "Engineer", "approved");
        Row row = await(service.getManagerById(id, null));
        assertNotNull(row);
        assertEquals("Approved Mgr", row.getString("name"));
    }

    // ── hasReported ──────────────────────────────────────────────────────────

    @Test
    void hasReported_nullAuth0Id_returnsFalse() throws Exception {
        assertFalse(await(service.hasReported(1L, null)));
    }

    @Test
    void hasReported_unknownAuth0Id_returnsFalse() throws Exception {
        assertFalse(await(service.hasReported(1L, "auth0|no-such-user")));
    }

    @Test
    void hasReported_knownUserNoReport_returnsFalse() throws Exception {
        String auth0Id = insertUser("auth0|has-reported-test");
        long managerId = insertManager("Unreported Mgr", "Corp", "Lead", "approved");
        assertFalse(await(service.hasReported(managerId, auth0Id)));
    }

    // ── getManagerBySlug ──────────────────────────────────────────────────────

    @Test
    void getManagerBySlug_unknownSlug_fails() {
        try {
            await(service.getManagerBySlug("no-such-slug-xyz", null));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void getManagerBySlug_rejectedManagerSlug_fails() throws Exception {
        String slug = insertManagerWithSlug("Rejected Slug Mgr", "Corp", "Engineer", "rejected", "rejected-slug-mgr");
        try {
            await(service.getManagerBySlug(slug, null));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void getManagerBySlug_validApprovedSlug_returnsRow() throws Exception {
        String slug = insertManagerWithSlug("Slug Mgr", "SlugCorp", "Director", "approved", "slug-mgr-slugcorp");
        Row row = await(service.getManagerBySlug(slug, null));
        assertNotNull(row);
        assertEquals("Slug Mgr", row.getString("name"));
    }

    // ── getCompanies ──────────────────────────────────────────────────────────

    @Test
    void getCompanies_emptyDb_returnsEmptyDataArray() throws Exception {
        JsonObject result = await(service.getCompanies());
        assertNotNull(result);
        assertTrue(result.containsKey("data"));
        assertEquals(0, result.getJsonArray("data").size());
    }

    @Test
    void getCompanies_withApprovedManager_returnsCompanyName() throws Exception {
        insertManager("Comp Mgr", "TestCompany", "Engineer", "approved");
        JsonObject result = await(service.getCompanies());
        JsonArray data = result.getJsonArray("data");
        assertTrue(data.size() > 0);
        boolean found = false;
        for (int i = 0; i < data.size(); i++) {
            if ("TestCompany".equals(data.getString(i))) { found = true; break; }
        }
        assertTrue(found, "Expected 'TestCompany' in data array");
    }

    // ── getCompanyProfile ─────────────────────────────────────────────────────

    @Test
    void getCompanyProfile_unknownCompany_createsGhostAndReturnsEmpty() throws Exception {
        JsonObject result = await(service.getCompanyProfile("BrandNewCompanyXYZ"));
        assertNotNull(result);
        assertEquals(0, result.getInteger("managerCount"));
        assertEquals(0, result.getInteger("totalReviews"));

        // Ghost company row should now exist in the DB
        RowSet<Row> rows = pool.preparedQuery("SELECT * FROM companies WHERE LOWER(TRIM(name)) = LOWER(TRIM($1))")
            .execute(Tuple.of("BrandNewCompanyXYZ"))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(rows.iterator().hasNext(), "Ghost company row should have been created");
    }

    @Test
    void getCompanyProfile_companyWithManagers_returnsManagerCount() throws Exception {
        String auth0Id = insertUser("auth0|company-profile-test");
        insertCompany("ProfileCo", "approved");
        long managerId = insertManagerForCompany("Profile Mgr", "ProfileCo", "Lead");
        // Use the same company name in the review so createReview doesn't reassign the
        // manager's company_id to a different company (which would break the lookup below).
        JsonObject review = reviewBody().put("managerCompany", "ProfileCo").put("managerTitle", "Lead");
        await(service.createReview(auth0Id, managerId, review, null));

        JsonObject result = await(service.getCompanyProfile("ProfileCo"));
        assertNotNull(result);
        assertTrue(result.getInteger("managerCount") > 0);
        assertTrue(result.getLong("totalReviews") > 0);
        assertTrue(result.containsKey("categoryAverages"));
    }

    // ── getCompanyBySlug ──────────────────────────────────────────────────────

    @Test
    void getCompanyBySlug_nullSlug_fails() {
        try {
            await(service.getCompanyBySlug(null));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void getCompanyBySlug_blankSlug_fails() {
        try {
            await(service.getCompanyBySlug("   "));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void getCompanyBySlug_unknownSlug_fails() {
        try {
            await(service.getCompanyBySlug("no-such-company-slug-xyz"));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    // ── suggestCompanies ──────────────────────────────────────────────────────

    @Test
    void suggestCompanies_emptyQuery_returnsEmptyArray() throws Exception {
        JsonArray result = await(service.suggestCompanies(""));
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    void suggestCompanies_nullQuery_returnsEmptyArray() throws Exception {
        JsonArray result = await(service.suggestCompanies(null));
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    void suggestCompanies_matchingQuery_returnsCompany() throws Exception {
        insertManager("Suggest Mgr", "SuggestableCorp", "Engineer", "approved");
        JsonArray result = await(service.suggestCompanies("Suggestable"));
        assertNotNull(result);
        assertTrue(result.size() > 0);
        boolean found = false;
        for (int i = 0; i < result.size(); i++) {
            String name = result.getJsonObject(i).getString("name");
            if ("SuggestableCorp".equals(name)) { found = true; break; }
        }
        assertTrue(found, "Expected 'SuggestableCorp' in suggestions");
    }

    // ── getSimilarManagers ────────────────────────────────────────────────────

    @Test
    void getSimilarManagers_nullName_fails() {
        try {
            await(service.getSimilarManagers(null, null));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void getSimilarManagers_blankName_fails() {
        try {
            await(service.getSimilarManagers("  ", null));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void getSimilarManagers_validNameNoMatches_returnsEmptyData() throws Exception {
        JsonObject result = await(service.getSimilarManagers("ZzZzNoSuchPersonXxXx", null));
        assertNotNull(result);
        assertTrue(result.containsKey("data"));
        assertEquals(0, result.getJsonArray("data").size());
    }

    // ── getStats ──────────────────────────────────────────────────────────────

    @Test
    void getStats_returnsRequiredKeysAndNonNegativeCounts() throws Exception {
        JsonObject result = await(service.getStats());
        assertNotNull(result);
        assertTrue(result.containsKey("realManagers"));
        assertTrue(result.containsKey("realReviews"));
        assertTrue(result.containsKey("weightedOpinions"));
        assertTrue(result.containsKey("seededManagers"));
        assertTrue(result.containsKey("scrapedManagers"));
        assertTrue(result.getLong("realManagers")     >= 0);
        assertTrue(result.getLong("realReviews")      >= 0);
        assertTrue(result.getLong("weightedOpinions") >= 0);
        assertTrue(result.getLong("seededManagers")   >= 0);
        assertTrue(result.getLong("scrapedManagers")  >= 0);
    }

    // ── getMySubmittedManagers ────────────────────────────────────────────────

    @Test
    void getMySubmittedManagers_unknownAuth0Id_fails() {
        try {
            await(service.getMySubmittedManagers("auth0|no-such-user"));
            fail("expected failure");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().contains("User not found"));
        }
    }

    @Test
    void getMySubmittedManagers_knownUserNoSubmissions_returnsEmptyRowSet() throws Exception {
        String auth0Id = insertUser("auth0|no-submissions");
        RowSet<Row> result = await(service.getMySubmittedManagers(auth0Id));
        assertNotNull(result);
        assertFalse(result.iterator().hasNext());
    }

    // ── hasContributed ────────────────────────────────────────────────────────

    @Test
    void hasContributed_unknownAuth0Id_fails() {
        try {
            await(service.hasContributed("auth0|no-such-user"));
            fail("expected failure");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().contains("User not found"));
        }
    }

    @Test
    void hasContributed_knownUserNoReviews_returnsFalse() throws Exception {
        String auth0Id = insertUser("auth0|no-reviews-hc");
        JsonObject result = await(service.hasContributed(auth0Id));
        assertNotNull(result);
        assertTrue(result.containsKey("hasContributed"));
        assertFalse(result.getBoolean("hasContributed"));
    }

    // ── getMyReviews ──────────────────────────────────────────────────────────

    @Test
    void getMyReviews_unknownAuth0Id_fails() {
        try {
            await(service.getMyReviews("auth0|no-such-user", 10, 0));
            fail("expected failure");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().contains("User not found"));
        }
    }

    @Test
    void getMyReviews_knownUserNoReviews_returnsEmptyResult() throws Exception {
        String auth0Id = insertUser("auth0|no-reviews-mr");
        JsonObject result = await(service.getMyReviews(auth0Id, 10, 0));
        assertNotNull(result);
        assertEquals(0L, result.getLong("total").longValue());
        assertEquals(0, result.getJsonArray("data").size());
    }

    // ── updateManager — validation errors ─────────────────────────────────────

    @Test
    void updateManager_nullBody_fails() {
        try {
            await(service.updateManager("auth0|any", 1L, null));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void updateManager_emptyBody_fails() {
        try {
            await(service.updateManager("auth0|any", 1L, new JsonObject()));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void updateManager_newCompanyTooShort_fails() {
        try {
            await(service.updateManager("auth0|any", 1L, new JsonObject().put("company", "X")));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void updateManager_newCompanyTooLong_fails() {
        try {
            await(service.updateManager("auth0|any", 1L, new JsonObject().put("company", "A".repeat(101))));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void updateManager_newTitleTooLong_fails() {
        try {
            await(service.updateManager("auth0|any", 1L, new JsonObject().put("title", "T".repeat(101))));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void updateManager_newBioTooLong_fails() {
        try {
            await(service.updateManager("auth0|any", 1L, new JsonObject().put("bio", "B".repeat(1001))));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void updateManager_invalidStatus_fails() {
        try {
            await(service.updateManager("auth0|any", 1L, new JsonObject().put("status", "invalid_status")));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void updateManager_linkedinUrlTooLong_fails() {
        String longUrl = "https://www.linkedin.com/" + "a".repeat(480);
        try {
            await(service.updateManager("auth0|any", 1L, new JsonObject().put("linkedinUrl", longUrl)));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void updateManager_invalidLinkedinUrl_fails() {
        try {
            await(service.updateManager("auth0|any", 1L, new JsonObject().put("linkedinUrl", "https://twitter.com/someone")));
            fail("expected failure");
        } catch (Exception e) {
            // expected
        }
    }

    // ── createGhostManager ────────────────────────────────────────────────────

    @Test
    void createGhostManager_createsGhostWithCorrectApprovalStatus() throws Exception {
        JsonObject body = new JsonObject()
            .put("firstName", "Ghost")
            .put("lastName", "Manager")
            .put("name", "Ghost Manager")
            .put("title", "Engineer")
            .put("company", "GhostCo")
            .put("country", "Canada");

        JsonObject result = await(service.createGhostManager(body, null));
        assertNotNull(result);
        assertTrue(result.containsKey("id"));
        assertTrue(result.getBoolean("created"));

        long id = result.getLong("id");
        RowSet<Row> rows = pool.preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(id))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(rows.iterator().hasNext());
        assertEquals("ghost", rows.iterator().next().getString("approval_status"));
    }

    @Test
    void createGhostManager_returnsRowWithIdField() throws Exception {
        JsonObject body = new JsonObject()
            .put("name", "Ghost Worker")
            .put("title", "Analyst")
            .put("company", "GhostInc")
            .put("country", "USA");

        JsonObject result = await(service.createGhostManager(body, null));
        assertNotNull(result);
        assertNotNull(result.getLong("id"));
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

    private String insertManagerWithSlug(String name, String company, String title, String status, String slug) throws Exception {
        pool.preparedQuery(
                "INSERT INTO managers(name, company, title, image, status, approval_status, slug, " +
                "overall_rating, reviews_count, category_averages) " +
                "VALUES ($1,$2,$3,'img','active',$4,$5,0,0,'{}')")
            .execute(Tuple.of(name, company, title, status, slug))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        return slug;
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
