package org.werkpages.integration;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.ResumeRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.ResumeService;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ResumeIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool              pool;
    static ResumeService     service;
    static ResumeRepository  resumeRepo;
    static CompanyRepository companyRepo;

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

        pool       = PgPool.pool(connectOptions, new PoolOptions().setMaxSize(5));
        resumeRepo = new ResumeRepository(pool);
        companyRepo = new CompanyRepository(pool);
        UserRepository userRepo = new UserRepository(pool);
        service = new ResumeService(userRepo, resumeRepo, companyRepo);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE user_resumes, reviews, managers, users, companies CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── getResume ────────────────────────────────────────────────────────────

    @Test
    void getResume_returnsNullWhenNoResumeSaved() throws Exception {
        String auth0Id = insertUser();
        JsonObject result = await(service.getResume(auth0Id));
        assertNull(result, "getResume must return null when no resume exists yet");
    }

    @Test
    void getResume_returnsDataAfterSave() throws Exception {
        String auth0Id = insertUser();
        JsonObject body = buildResumeBody("I build things.", skills("Java", "React"), new JsonArray(), new JsonArray(), new JsonArray());
        await(service.saveResume(auth0Id, body));

        JsonObject result = await(service.getResume(auth0Id));

        assertNotNull(result);
        assertEquals("I build things.", result.getString("summary"));
        assertEquals(2, result.getJsonArray("skills").size());
    }

    // ── saveResume ───────────────────────────────────────────────────────────

    @Test
    void saveResume_createsNewRow() throws Exception {
        String auth0Id = insertUser();
        JsonObject body = buildResumeBody("Engineer.", skills("Go"), new JsonArray(), new JsonArray(), new JsonArray());
        JsonObject result = await(service.saveResume(auth0Id, body));

        assertEquals("Engineer.", result.getString("summary"));
        assertEquals(1, result.getJsonArray("skills").size());
    }

    @Test
    void saveResume_updatesExistingRow() throws Exception {
        String auth0Id = insertUser();
        await(service.saveResume(auth0Id, buildResumeBody("First.", skills("Java"), new JsonArray(), new JsonArray(), new JsonArray())));
        JsonObject updated = await(service.saveResume(auth0Id, buildResumeBody("Updated.", skills("Python", "Go"), new JsonArray(), new JsonArray(), new JsonArray())));

        assertEquals("Updated.", updated.getString("summary"));
        assertEquals(2, updated.getJsonArray("skills").size());

        // Only one row in DB (upsert)
        Long count = await(pool.query("SELECT COUNT(*) AS cnt FROM user_resumes").execute()
            .map(rows -> rows.iterator().next().getLong("cnt")));
        assertEquals(1L, count);
    }

    @Test
    void saveResume_roundTripsWorkEntries() throws Exception {
        String auth0Id = insertUser();
        JsonArray workEntries = new JsonArray().add(new JsonObject()
            .put("company", "Acme Corp")
            .put("title", "Engineer")
            .put("startDate", "2021-03")
            .put("endDate", (String) null)
            .put("current", true)
            .put("bullets", new JsonArray()));

        await(service.saveResume(auth0Id, buildResumeBody("", new JsonArray(), new JsonArray(), workEntries, new JsonArray())));
        JsonObject result = await(service.getResume(auth0Id));

        JsonObject entry = result.getJsonArray("workEntries").getJsonObject(0);
        assertEquals("Acme Corp", entry.getString("company"));
        assertEquals("Engineer", entry.getString("title"));
        assertEquals("2021-03", entry.getString("startDate"));
        assertTrue(entry.getBoolean("current"));
    }

    @Test
    void saveResume_roundTripsDesign() throws Exception {
        String auth0Id = insertUser();
        JsonObject design = new JsonObject()
            .put("pageSize", "A4")
            .put("layout", "sidebar-left")
            .put("pageBackground", "#f0f0f0");

        JsonObject body = buildResumeBody("Bio.", new JsonArray(), new JsonArray(), new JsonArray(), new JsonArray());
        body.put("design", design);
        await(service.saveResume(auth0Id, body));

        JsonObject result = await(service.getResume(auth0Id));

        assertNotNull(result.getJsonObject("design"), "design must be persisted and returned");
        assertEquals("A4", result.getJsonObject("design").getString("pageSize"));
        assertEquals("sidebar-left", result.getJsonObject("design").getString("layout"));
        assertEquals("#f0f0f0", result.getJsonObject("design").getString("pageBackground"));
    }

    @Test
    void saveResume_noDesign_returnsNullDesign() throws Exception {
        String auth0Id = insertUser();
        await(service.saveResume(auth0Id, buildResumeBody("No design.", new JsonArray(), new JsonArray(), new JsonArray(), new JsonArray())));

        JsonObject result = await(service.getResume(auth0Id));

        assertNull(result.getJsonObject("design"), "design must be null when not provided");
    }

    @Test
    void getResume_enrichesWorkEntriesWithCompanyLogoFromDirectory() throws Exception {
        String auth0Id = insertUser();
        // Save a work entry with no logoUrl
        JsonArray workEntries = new JsonArray().add(new JsonObject()
            .put("company", "LogoCo")
            .put("title", "Engineer")
            .put("startDate", "2022-01")
            .put("endDate", (String) null)
            .put("current", true)
            .put("bullets", new JsonArray()));
        await(service.saveResume(auth0Id, buildResumeBody("", new JsonArray(), new JsonArray(), workEntries, new JsonArray())));

        // Give that company a logo in the directory
        await(pool.preparedQuery("UPDATE companies SET logo_url = 'https://cdn.example.com/logoco.png' WHERE LOWER(TRIM(name)) = 'logoco'")
            .execute());

        JsonObject result = await(service.getResume(auth0Id));
        JsonObject entry = result.getJsonArray("workEntries").getJsonObject(0);
        assertEquals("https://cdn.example.com/logoco.png", entry.getString("logoUrl"),
            "getResume must inject logoUrl from the companies directory when the work entry has none");
    }

    @Test
    void getResume_preservesManualLogoUrlOverDirectoryLogo() throws Exception {
        String auth0Id = insertUser();
        // Save a work entry that already has a logoUrl set by the user
        JsonArray workEntries = new JsonArray().add(new JsonObject()
            .put("company", "Acme Corp")
            .put("title", "Dev")
            .put("startDate", "2021-01")
            .put("endDate", (String) null)
            .put("current", true)
            .put("bullets", new JsonArray())
            .put("logoUrl", "https://manual.example.com/acme.png"));
        await(service.saveResume(auth0Id, buildResumeBody("", new JsonArray(), new JsonArray(), workEntries, new JsonArray())));

        // Company also has a logo in directory — manual URL must win
        await(pool.preparedQuery("UPDATE companies SET logo_url = 'https://dir.example.com/acme.png' WHERE LOWER(TRIM(name)) = 'acme corp'")
            .execute());

        JsonObject result = await(service.getResume(auth0Id));
        JsonObject entry = result.getJsonArray("workEntries").getJsonObject(0);
        assertEquals("https://manual.example.com/acme.png", entry.getString("logoUrl"),
            "Manually entered logoUrl must take precedence over the directory logo");
    }

    @Test
    void saveResume_ensuresCompanyInDirectory() throws Exception {
        String auth0Id = insertUser();
        JsonArray workEntries = new JsonArray().add(new JsonObject()
            .put("company", "NewCo Inc")
            .put("title", "Dev")
            .put("startDate", "2022-01")
            .put("endDate", "2023-01")
            .put("current", false)
            .put("bullets", new JsonArray()));

        await(service.saveResume(auth0Id, buildResumeBody("", new JsonArray(), new JsonArray(), workEntries, new JsonArray())));

        Long count = await(pool.preparedQuery("SELECT COUNT(*) AS cnt FROM companies WHERE LOWER(TRIM(name)) = 'newco inc'")
            .execute().map(rows -> rows.iterator().next().getLong("cnt")));
        assertEquals(1L, count, "saveResume must create company in directory for each work entry");
    }

    // ── getPrefill ───────────────────────────────────────────────────────────

    @Test
    void getPrefill_returnsEmptyWhenNoReviews() throws Exception {
        String auth0Id = insertUser();
        JsonObject result = await(service.getPrefill(auth0Id));
        assertEquals(0, result.getJsonArray("data").size());
    }

    @Test
    void getPrefill_returnsWorkEntriesFromReviews() throws Exception {
        String auth0Id = insertUser();
        UUID userId = resolveUserId(auth0Id);
        Long managerId = insertManager("Alice A", "TechCorp", "Director", "approved");
        insertReview(managerId, userId, "TechCorp", "Senior Engineer", "2020-01-01", "2022-06-01");

        JsonObject result = await(service.getPrefill(auth0Id));
        JsonArray data = result.getJsonArray("data");

        assertEquals(1, data.size());
        JsonObject entry = data.getJsonObject(0);
        assertEquals("TechCorp", entry.getString("company"));
        assertEquals("Senior Engineer", entry.getString("title"));
        assertEquals("2020-01", entry.getString("startDate"));
        assertEquals("2022-06", entry.getString("endDate"));
        assertFalse(entry.getBoolean("current"));
    }

    @Test
    void getPrefill_currentJobWhenWorkedUntilIsNull() throws Exception {
        String auth0Id = insertUser();
        UUID userId = resolveUserId(auth0Id);
        Long managerId = insertManager("Bob B", "FinCo", "Manager", "approved");
        insertReview(managerId, userId, "FinCo", "Analyst", "2021-03-01", null);

        JsonObject result = await(service.getPrefill(auth0Id));
        JsonObject entry = result.getJsonArray("data").getJsonObject(0);

        assertNull(entry.getString("endDate"));
        assertTrue(entry.getBoolean("current"));
    }

    @Test
    void getPrefill_excludesDeletedReviews() throws Exception {
        String auth0Id = insertUser();
        UUID userId = resolveUserId(auth0Id);
        Long managerId = insertManager("Carol C", "OldCo", "VP", "approved");
        insertReview(managerId, userId, "OldCo", "Lead", "2019-01-01", "2020-01-01");
        await(pool.preparedQuery("UPDATE reviews SET deleted_at = now() WHERE manager_id = $1 AND user_id = $2")
            .execute(Tuple.of(managerId, userId)));

        JsonObject result = await(service.getPrefill(auth0Id));
        assertEquals(0, result.getJsonArray("data").size(), "Soft-deleted reviews must not appear in prefill");
    }

    @Test
    void getPrefill_includesLogoUrlWhenCompanyHasLogo() throws Exception {
        String auth0Id = insertUser();
        UUID userId = resolveUserId(auth0Id);
        Long managerId = insertManager("Eve E", "LoggedCo", "Director", "approved");
        // Give the company a logo
        await(pool.preparedQuery("UPDATE companies SET logo_url = 'https://cdn.example.com/loggedco.png' WHERE LOWER(TRIM(name)) = 'loggedco'")
            .execute());
        insertReview(managerId, userId, "LoggedCo", "Engineer", "2021-01-01", "2023-01-01");

        JsonObject result = await(service.getPrefill(auth0Id));
        JsonObject entry = result.getJsonArray("data").getJsonObject(0);
        assertEquals("https://cdn.example.com/loggedco.png", entry.getString("logoUrl"),
            "Prefill must include the company logo_url when the company has one");
    }

    @Test
    void getPrefill_excludesPendingManagers() throws Exception {
        String auth0Id = insertUser();
        UUID userId = resolveUserId(auth0Id);
        Long managerId = insertManager("Dave D", "PendingCo", "Lead", "pending_approval");
        insertReview(managerId, userId, "PendingCo", "Dev", "2021-01-01", null);

        JsonObject result = await(service.getPrefill(auth0Id));
        assertEquals(0, result.getJsonArray("data").size(), "Reviews for pending managers must not appear in prefill");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String insertUser() throws Exception {
        String auth0Id = "auth0|" + UUID.randomUUID();
        await(pool.preparedQuery(
                "INSERT INTO users(id, auth0_id, username, role, has_auto_created_manager) VALUES (gen_random_uuid(), $1, $2, 'user', false)")
            .execute(Tuple.of(auth0Id, "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8))));
        return auth0Id;
    }

    private UUID resolveUserId(String auth0Id) throws Exception {
        return await(pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rows -> rows.iterator().next().getUUID("id")));
    }

    private Long insertManager(String name, String company, String title, String status) throws Exception {
        Long companyId = null;
        if (status.equals("approved") || status.equals("ghost")) {
            companyId = await(companyRepo.findOrCreate(company, null, null)).getLong("id");
        }
        return await(pool.preparedQuery(
                "INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages,company_id) VALUES ($1,$2,$3,'img','active',$4,0.0,0,'{}', $5) RETURNING id")
            .execute(Tuple.of(name, company, title, status, companyId))
            .map(rows -> rows.iterator().next().getLong("id")));
    }

    private void insertReview(Long managerId, UUID userId, String managerCompany, String managerTitle,
                              String workedFrom, String workedUntil) throws Exception {
        LocalDate from  = workedFrom  != null ? LocalDate.parse(workedFrom)  : null;
        LocalDate until = workedUntil != null ? LocalDate.parse(workedUntil) : null;
        await(pool.preparedQuery("""
                INSERT INTO reviews(manager_id, user_id, author, overall_rating, manager_company, manager_title,
                                    verified, helpful_count, worked_from, worked_until, created_at, updated_at)
                VALUES ($1, $2, 'tester', 4.0, $3, $4, true, 0, $5, $6, now(), now())
                """)
            .execute(Tuple.of(managerId, userId, managerCompany, managerTitle, from, until)));
    }

    private static JsonObject buildResumeBody(String summary,
                                              JsonArray skills, JsonArray education,
                                              JsonArray workEntries, JsonArray extraLinks) {
        return new JsonObject()
            .put("summary", summary)
            .put("skills", skills)
            .put("education", education)
            .put("workEntries", workEntries)
            .put("extraLinks", extraLinks);
    }

    private static JsonArray skills(String... items) {
        JsonArray arr = new JsonArray();
        for (String item : items) arr.add(item);
        return arr;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
