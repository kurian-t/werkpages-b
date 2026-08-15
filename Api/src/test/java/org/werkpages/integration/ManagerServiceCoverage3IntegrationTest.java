package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
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
class ManagerServiceCoverage3IntegrationTest {

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

    // ── NameValidator paths through findOrCreate ──────────────────────────────

    @Test
    void findOrCreate_blankFirstName_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|nv-blank-fn");
        try {
            await(service.findOrCreate(auth0Id, "", "Smith", "Engineer", "Corp", "US", null, null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("required"),
                "Expected required error but got: " + e.getMessage());
        }
    }

    @Test
    void findOrCreate_firstNameTooShort_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|nv-short-fn");
        try {
            await(service.findOrCreate(auth0Id, "A", "Smith", "Engineer", "Corp", "US", null, null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("too short"),
                "Expected too short error but got: " + e.getMessage());
        }
    }

    @Test
    void findOrCreate_firstNameTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|nv-long-fn");
        try {
            await(service.findOrCreate(auth0Id, "A".repeat(51), "Smith", "Engineer", "Corp", "US", null, null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("too long"),
                "Expected too long error but got: " + e.getMessage());
        }
    }

    @Test
    void findOrCreate_firstNameInvalidChars_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|nv-invalid-chars");
        try {
            await(service.findOrCreate(auth0Id, "J0hn", "Smith", "Engineer", "Corp", "US", null, null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("invalid characters"),
                "Expected invalid characters error but got: " + e.getMessage());
        }
    }

    @Test
    void findOrCreate_fakeFirstName_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|nv-fake-fn");
        try {
            await(service.findOrCreate(auth0Id, "Test", "Smith", "Engineer", "Corp", "US", null, null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("real person"),
                "Expected real person error but got: " + e.getMessage());
        }
    }

    @Test
    void findOrCreate_profanityName_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|nv-profanity");
        try {
            await(service.findOrCreate(auth0Id, "Fuck", "Smith", "Engineer", "Corp", "US", null, null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("disallowed"),
                "Expected disallowed content error but got: " + e.getMessage());
        }
    }

    @Test
    void findOrCreate_missingCountry_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|nv-no-country");
        try {
            await(service.findOrCreate(auth0Id, "Alice", "Smith", "Engineer", "Corp", null, null, null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("required"),
                "Expected required error but got: " + e.getMessage());
        }
    }

    // ── getManagers validation ────────────────────────────────────────────────

    @Test
    void getManagers_searchTooLong_returnsBadRequest() {
        try {
            await(service.getManagers(10, 0, "A".repeat(101), null, (String) null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("too long"),
                "Expected 'too long' but got: " + e.getMessage());
        }
    }

    @Test
    void getManagers_companyTooLong_returnsBadRequest() {
        try {
            await(service.getManagers(10, 0, null, "B".repeat(101), (String) null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("too long"),
                "Expected 'too long' but got: " + e.getMessage());
        }
    }

    // ── getManagerById ────────────────────────────────────────────────────────

    @Test
    void getManagerById_notFound_returnsNotFound() {
        try {
            await(service.getManagerById(99999L, null));
            fail("expected not found");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("not found"),
                "Expected not found but got: " + e.getMessage());
        }
    }

    @Test
    void getManagerById_rejectedManager_returnsNotFound() throws Exception {
        long managerId = insertManager("Rejected Person", "Corp", "Dev", "rejected");
        try {
            await(service.getManagerById(managerId, null));
            fail("expected not found");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("not found"),
                "Expected not found but got: " + e.getMessage());
        }
    }

    @Test
    void getManagerById_pendingManager_asAnonymous_returnsNotFound() throws Exception {
        long managerId = insertManager("Pending Anon", "Corp", "Dev", "pending_approval");
        try {
            await(service.getManagerById(managerId, null));
            fail("expected not found");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("not found"),
                "Expected not found but got: " + e.getMessage());
        }
    }

    @Test
    void getManagerById_pendingManager_asWrongUser_returnsNotFound() throws Exception {
        String wrongUser = insertUser("auth0|wrong-user-get");
        long managerId   = insertManager("Pending Wrong", "Corp", "Dev", "pending_approval");
        try {
            await(service.getManagerById(managerId, wrongUser));
            fail("expected not found");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("not found"),
                "Expected not found but got: " + e.getMessage());
        }
    }

    @Test
    void getManagerById_pendingManager_asSubmitter_succeeds() throws Exception {
        String auth0Id = insertUser("auth0|submitter-get");
        UUID userId    = getUserId(auth0Id);
        long managerId = insertPendingManagerForUser("Pending Submitter", "SubmitCorp", "Dev", userId);

        Row result = await(service.getManagerById(managerId, auth0Id));
        assertNotNull(result);
        assertEquals("Pending Submitter", result.getString("name"));
    }

    // ── getMySubmittedManagers ────────────────────────────────────────────────

    @Test
    void getMySubmittedManagers_unknownUser_returnsUnauthorized() {
        try {
            await(service.getMySubmittedManagers("auth0|no-such-user-submitted"));
            fail("expected unauthorized");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("user not found"),
                "Expected user not found but got: " + e.getMessage());
        }
    }

    @Test
    void getMySubmittedManagers_withPendingManagers_returnsRows() throws Exception {
        String auth0Id = insertUser("auth0|my-submitted");
        UUID userId    = getUserId(auth0Id);
        insertPendingManagerForUser("My Pending Mgr", "MySubmittedCorp", "Lead", userId);

        var rows = await(service.getMySubmittedManagers(auth0Id));
        assertNotNull(rows);
        assertTrue(rows.size() > 0, "Should return pending managers");
    }

    // ── createManager — validation ────────────────────────────────────────────

    @Test
    void createManager_nullBody_returnsBadRequest() {
        try {
            await(service.createManager("auth0|any", null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("missing"),
                "Expected missing body but got: " + e.getMessage());
        }
    }

    @Test
    void createManager_missingRequiredFields_returnsBadRequest() {
        try {
            await(service.createManager("auth0|any",
                new JsonObject().put("name", "Jane Smith"),
                null));
            fail("expected bad request");
        } catch (Exception e) {
            // expected — missing company, title, image
        }
    }

    @Test
    void createManager_nameTooLong_returnsBadRequest() {
        try {
            await(service.createManager("auth0|any",
                new JsonObject()
                    .put("name", "A".repeat(101))
                    .put("company", "Corp")
                    .put("title", "Dev")
                    .put("image", "img"),
                null));
            fail("expected bad request");
        } catch (Exception e) {
            // expected — name too long
        }
    }

    @Test
    void createManager_companyTooShort_returnsBadRequest() {
        try {
            await(service.createManager("auth0|any",
                new JsonObject()
                    .put("name", "Jane Smith")
                    .put("company", "A")
                    .put("title", "Dev")
                    .put("image", "img"),
                null));
            fail("expected bad request");
        } catch (Exception e) {
            // expected — company too short
        }
    }

    @Test
    void createManager_companyTooLong_returnsBadRequest() {
        try {
            await(service.createManager("auth0|any",
                new JsonObject()
                    .put("name", "Jane Smith")
                    .put("company", "C".repeat(101))
                    .put("title", "Dev")
                    .put("image", "img"),
                null));
            fail("expected bad request");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void createManager_countryMissing_returnsBadRequest() {
        try {
            await(service.createManager("auth0|any",
                new JsonObject()
                    .put("name", "Jane Smith")
                    .put("company", "Valid Corp")
                    .put("title", "Engineer")
                    .put("image", "img"),
                null));
            fail("expected bad request");
        } catch (Exception e) {
            // expected — country required
        }
    }

    @Test
    void createManager_startDateMissing_returnsBadRequest() {
        try {
            await(service.createManager("auth0|any",
                new JsonObject()
                    .put("name", "Jane Smith")
                    .put("company", "Valid Corp")
                    .put("title", "Engineer")
                    .put("image", "img")
                    .put("country", "US"),
                null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("start date"),
                "Expected start date error but got: " + e.getMessage());
        }
    }

    @Test
    void createManager_startDateInFuture_returnsBadRequest() {
        try {
            await(service.createManager("auth0|any",
                new JsonObject()
                    .put("name", "Jane Smith")
                    .put("company", "Valid Corp")
                    .put("title", "Engineer")
                    .put("image", "img")
                    .put("country", "US")
                    .put("startDate", "2099-01"),
                null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("future"),
                "Expected future date error but got: " + e.getMessage());
        }
    }

    @Test
    void createManager_retiredWithoutEndDate_returnsBadRequest() {
        try {
            await(service.createManager("auth0|any",
                new JsonObject()
                    .put("name", "Jane Smith")
                    .put("company", "Valid Corp")
                    .put("title", "Engineer")
                    .put("image", "img")
                    .put("country", "US")
                    .put("startDate", "2020-01")
                    .put("status", "retired"),
                null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("end date"),
                "Expected end date error but got: " + e.getMessage());
        }
    }

    @Test
    void createManager_missingReview_returnsBadRequest() {
        try {
            await(service.createManager("auth0|any",
                new JsonObject()
                    .put("name", "Jane Smith")
                    .put("company", "Valid Corp")
                    .put("title", "Engineer")
                    .put("image", "img")
                    .put("country", "US")
                    .put("startDate", "2020-01"),
                null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("review"),
                "Expected review required error but got: " + e.getMessage());
        }
    }

    @Test
    void createManager_reviewMissingWorkedFrom_returnsBadRequest() {
        try {
            await(service.createManager("auth0|any",
                new JsonObject()
                    .put("name", "Jane Smith")
                    .put("company", "Valid Corp")
                    .put("title", "Engineer")
                    .put("image", "img")
                    .put("country", "US")
                    .put("startDate", "2020-01")
                    .put("review", new JsonObject()
                        .put("overallRating", 4.0)
                        .put("managerCompany", "Valid Corp")
                        .put("managerTitle", "Engineer")
                        .put("ratings", validRatings())),
                null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("start date"),
                "Expected start date error but got: " + e.getMessage());
        }
    }

    @Test
    void createManager_reviewWorkedFromBeforeStartDate_returnsBadRequest() {
        try {
            await(service.createManager("auth0|any",
                new JsonObject()
                    .put("name", "Jane Smith")
                    .put("company", "Valid Corp")
                    .put("title", "Engineer")
                    .put("image", "img")
                    .put("country", "US")
                    .put("startDate", "2022-01")
                    .put("review", new JsonObject()
                        .put("overallRating", 4.0)
                        .put("managerCompany", "Valid Corp")
                        .put("managerTitle", "Engineer")
                        .put("workedFrom", "2021-01")  // before manager startDate
                        .put("ratings", validRatings())),
                null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("before"),
                "Expected 'before' error but got: " + e.getMessage());
        }
    }

    @Test
    void createManager_reviewInvalidRating_returnsBadRequest() {
        try {
            JsonObject ratings = validRatings().put("Communication Style", 10.0);
            await(service.createManager("auth0|any",
                new JsonObject()
                    .put("name", "Jane Smith")
                    .put("company", "Valid Corp")
                    .put("title", "Engineer")
                    .put("image", "img")
                    .put("country", "US")
                    .put("startDate", "2020-01")
                    .put("review", new JsonObject()
                        .put("overallRating", 4.0)
                        .put("managerCompany", "Valid Corp")
                        .put("managerTitle", "Engineer")
                        .put("workedFrom", "2020-03")
                        .put("ratings", ratings)),
                null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("rating"),
                "Expected rating error but got: " + e.getMessage());
        }
    }

    @Test
    void createManager_userNotFound_returnsUnauthorized() {
        try {
            await(service.createManager("auth0|no-such-create-user", validCreateBody(), null));
            fail("expected unauthorized");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("user not found"),
                "Expected user not found but got: " + e.getMessage());
        }
    }

    @Test
    void createManager_bannedUser_returnsForbidden() throws Exception {
        String auth0Id = insertUser("auth0|create-banned");
        banUser(auth0Id);
        try {
            await(service.createManager(auth0Id, validCreateBody(), null));
            fail("expected forbidden");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("account_suspended"),
                "Expected account_suspended but got: " + e.getMessage());
        }
    }

    @Test
    void createManager_dailyLimitReached_returnsTooManyRequests() throws Exception {
        String auth0Id = insertUser("auth0|create-limit");
        // Submit 6 managers to hit the daily limit
        for (int i = 0; i < 6; i++) {
            JsonObject body = validCreateBody()
                .put("name", "Limit Test Manager " + i)
                .put("company", "LimitCorp" + i);
            body.getJsonObject("review")
                .put("managerCompany", "LimitCorp" + i);
            await(service.createManager(auth0Id, body, null));
        }
        try {
            JsonObject body = validCreateBody()
                .put("name", "Limit Test Manager X")
                .put("company", "LimitCorpX");
            body.getJsonObject("review").put("managerCompany", "LimitCorpX");
            await(service.createManager(auth0Id, body, null));
            fail("expected too many requests");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("daily_limit_reached"),
                "Expected daily_limit_reached but got: " + e.getMessage());
        }
    }

    @Test
    void createManager_success_createsPendingManagerWithReview() throws Exception {
        String auth0Id = insertUser("auth0|create-success");
        Row result = await(service.createManager(auth0Id, validCreateBody(), null));
        assertNotNull(result);
        assertEquals("pending_approval", result.getString("approval_status"));
        assertEquals("Margaret Williams", result.getString("name"));
        assertEquals("Acme Industries", result.getString("company"));
    }

    @Test
    void createManager_fuzzyMatchExistingManager_attachesReview() throws Exception {
        // "Margaret Wiliams" is close to "Margaret Williams" (1 char off) → attaches review
        String auth0Id = insertUser("auth0|create-fuzzy");
        // Create the existing ghost manager
        insertManager("Margaret Williams", "Acme Industries", "Engineering Manager", "ghost");

        JsonObject body = validCreateBody().put("name", "Margaret Wiliams"); // deliberate typo
        body.getJsonObject("review").put("managerCompany", "Acme Industries");
        Row result = await(service.createManager(auth0Id, body, null));
        assertNotNull(result);
        // Fuzzy match fired → existing ghost returned, not a new pending manager
        assertEquals("ghost", result.getString("approval_status"),
            "Should attach review to existing ghost, not create new pending_approval");
    }

    // ── getCompanyProfile ─────────────────────────────────────────────────────

    @Test
    void getCompanyProfile_blankCompany_returnsBadRequest() {
        try {
            await(service.getCompanyProfile(""));
            fail("expected bad request");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void getCompanyProfile_nullCompany_returnsBadRequest() {
        try {
            await(service.getCompanyProfile(null));
            fail("expected bad request");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void getCompanyProfile_unknownCompany_createsGhostAndReturnsEmpty() throws Exception {
        JsonObject result = await(service.getCompanyProfile("Brand New Corp XYZ"));
        assertNotNull(result);
        assertEquals("Brand New Corp XYZ", result.getString("name"));
        assertEquals(0, result.getInteger("managerCount").intValue());
        assertEquals(0, result.getJsonArray("managers").size());
    }

    @Test
    void getCompanyProfile_companyWithManagers_returnsManagerList() throws Exception {
        long companyId = insertCompanyAndGetId("Profile Corp");
        insertManagerWithCompanyId("Jane Lead", "Profile Corp", "Director", "approved", companyId);

        JsonObject result = await(service.getCompanyProfile("Profile Corp"));
        assertNotNull(result);
        assertTrue(result.getInteger("managerCount") > 0, "Should have managers");
        assertTrue(result.getJsonArray("managers").size() > 0, "Managers array should be non-empty");
    }

    // ── getCompanyBySlug ──────────────────────────────────────────────────────

    @Test
    void getCompanyBySlug_blankSlug_returnsBadRequest() {
        try {
            await(service.getCompanyBySlug(""));
            fail("expected bad request");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void getCompanyBySlug_unknownSlug_returnsNotFound() {
        try {
            await(service.getCompanyBySlug("no-such-slug-xyz"));
            fail("expected not found");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("not found"),
                "Expected not found but got: " + e.getMessage());
        }
    }

    // ── updateManager validation edge cases ──────────────────────────────────

    @Test
    void updateManager_nullBody_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|upd-null-body");
        long managerId = insertManager("Null Body Mgr", "Corp", "Dev", "approved");
        try {
            await(service.updateManager(auth0Id, managerId, null));
            fail("expected bad request");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void updateManager_emptyBody_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|upd-empty-body");
        long managerId = insertManager("Empty Body Mgr", "Corp", "Dev", "approved");
        try {
            await(service.updateManager(auth0Id, managerId, new JsonObject()));
            fail("expected bad request");
        } catch (Exception e) {
            // expected — no fields provided
        }
    }

    @Test
    void updateManager_blankCompany_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|upd-blank-co");
        long managerId = insertManager("Blank Co Mgr", "Corp", "Dev", "approved");
        try {
            await(service.updateManager(auth0Id, managerId, new JsonObject().put("company", "  ")));
            fail("expected bad request");
        } catch (Exception e) {
            // expected — company blank
        }
    }

    @Test
    void updateManager_companyTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|upd-co-long");
        long managerId = insertManager("Long Co Mgr", "Corp", "Dev", "approved");
        try {
            await(service.updateManager(auth0Id, managerId,
                new JsonObject().put("company", "C".repeat(101))));
            fail("expected bad request");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    void updateManager_invalidStatus_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|upd-bad-status");
        long managerId = insertManager("Bad Status Mgr", "Corp", "Dev", "approved");
        try {
            await(service.updateManager(auth0Id, managerId,
                new JsonObject().put("status", "unknown_status")));
            fail("expected bad request");
        } catch (Exception e) {
            // expected
        }
    }

    // ── suggestCompanies ──────────────────────────────────────────────────────

    @Test
    void suggestCompanies_nullQuery_returnsEmpty() throws Exception {
        JsonArray result = await(service.suggestCompanies(null));
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    void suggestCompanies_blankQuery_returnsEmpty() throws Exception {
        JsonArray result = await(service.suggestCompanies("   "));
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    void suggestCompanies_withQuery_returnsMatchingCompanies() throws Exception {
        insertManager("Suggest Person", "SuggestCorp Inc", "Analyst", "approved");
        JsonArray result = await(service.suggestCompanies("SuggestCorp"));
        assertNotNull(result);
        assertTrue(result.size() > 0, "Should return matching companies");
        assertEquals("SuggestCorp Inc", result.getJsonObject(0).getString("name"));
    }

    // ── getSimilarManagers ────────────────────────────────────────────────────

    @Test
    void getSimilarManagers_blankName_returnsBadRequest() {
        try {
            await(service.getSimilarManagers("", "Corp"));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("name"),
                "Expected name required but got: " + e.getMessage());
        }
    }

    @Test
    void getSimilarManagers_withMatch_returnsResults() throws Exception {
        insertManager("Similar Alice", "SimilarCorp", "Manager", "approved");
        JsonObject result = await(service.getSimilarManagers("Alice", "SimilarCorp"));
        assertNotNull(result);
        assertTrue(result.getJsonArray("data").size() > 0, "Should return similar managers");
    }

    @Test
    void getSimilarManagers_noMatch_returnsEmptyData() throws Exception {
        JsonObject result = await(service.getSimilarManagers("NoOneNamed", null));
        assertNotNull(result);
        assertEquals(0, result.getJsonArray("data").size());
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

    private long insertPendingManagerForUser(String name, String company, String title, UUID userId) throws Exception {
        return pool.preparedQuery(
                "INSERT INTO managers(name, company, title, image, status, approval_status, " +
                "overall_rating, reviews_count, category_averages, submitted_by) " +
                "VALUES ($1,$2,$3,'img','active','pending_approval',0,0,'{}', $4) RETURNING id")
            .execute(Tuple.of(name, company, title, userId))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getLong("id");
    }

    private long insertCompanyAndGetId(String name) throws Exception {
        return pool.preparedQuery(
                "INSERT INTO companies(name, status, created_at, updated_at) VALUES ($1,'ghost',now(),now()) RETURNING id")
            .execute(Tuple.of(name))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getLong("id");
    }

    private long insertManagerWithCompanyId(String name, String company, String title, String status, long companyId) throws Exception {
        return pool.preparedQuery(
                "INSERT INTO managers(name, company, title, image, status, approval_status, company_id, " +
                "overall_rating, reviews_count, category_averages) " +
                "VALUES ($1,$2,$3,'img','active',$4,$5,0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title, status, companyId))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getLong("id");
    }

    private String insertUser(String auth0Id) throws Exception {
        String username = auth0Id.replaceAll("[^a-zA-Z0-9]", "") + "User";
        pool.preparedQuery(
                "INSERT INTO users(auth0_id, email, username, first_name, last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User"))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        return auth0Id;
    }

    private UUID getUserId(String auth0Id) throws Exception {
        return pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getUUID("id");
    }

    private void banUser(String auth0Id) throws Exception {
        UUID userId = getUserId(auth0Id);
        pool.preparedQuery(
                "INSERT INTO banned_users(user_id, reason, banned_by) VALUES ($1, $2, $3)")
            .execute(Tuple.of(userId, "test ban", "test-admin"))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static JsonObject validRatings() {
        return new JsonObject()
            .put("Communication Style", 4)
            .put("Perceived Approachability", 4)
            .put("Perceived Clarity of Expectations", 4)
            .put("Feedback Style", 4)
            .put("Perceived Supportiveness", 4)
            .put("Decision Making Style", 4)
            .put("Organization and Planning Style", 4)
            .put("Delegation Style", 4)
            .put("Perceived Professional Demeanor", 4)
            .put("Overall Working Experience", 4);
    }

    private static JsonObject validCreateBody() {
        return new JsonObject()
            .put("name", "Margaret Williams")
            .put("company", "Acme Industries")
            .put("title", "Engineering Manager")
            .put("image", "https://example.com/avatar.jpg")
            .put("country", "US")
            .put("startDate", "2020-01")
            .put("review", new JsonObject()
                .put("overallRating", 4.0)
                .put("managerCompany", "Acme Industries")
                .put("managerTitle", "Engineering Manager")
                .put("workedFrom", "2020-03")
                .put("ratings", validRatings()));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
