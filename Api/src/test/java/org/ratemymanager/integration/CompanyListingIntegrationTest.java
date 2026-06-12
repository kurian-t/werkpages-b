package org.ratemymanager.integration;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class CompanyListingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("ratemymanager_test")
        .withUsername("test")
        .withPassword("test");

    static Pool              pool;
    static ManagerService    service;
    static ManagerRepository managerRepo;
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

        pool = PgPool.pool(connectOptions, new PoolOptions().setMaxSize(5));

        managerRepo = new ManagerRepository(pool);
        companyRepo = new CompanyRepository(pool);
        ReviewRepository  reviewRepo  = new ReviewRepository(pool);
        UserRepository    userRepo    = new UserRepository(pool);
        EditRepository    editRepo    = new EditRepository(pool);
        ReportRepository  reportRepo  = new ReportRepository(pool);
        service = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, companyRepo, pool, company -> null);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, users, companies CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── getCompanyListing ────────────────────────────────────────────────────

    @Test
    void getCompanyListing_returnsAllApprovedAndGhostCompanies() throws Exception {
        insertManager("Alice A", "Acme Corp",    "Manager",  "approved", 4.0, 2);
        insertManager("Bob B",   "Acme Corp",    "Director", "approved", 3.0, 1);
        insertManager("Carol C", "Skynet Inc",   "VP",       "ghost",    5.0, 3);
        insertManager("Dave D",  "Pending Corp", "Lead",     "pending_approval", null, 0);

        JsonObject result = refreshAndGetListing();
        JsonArray data = result.getJsonArray("data");

        // Pending corp should be excluded (no approved/ghost managers)
        long companyNames = StreamSupport.stream(data.spliterator(), false)
            .map(o -> ((JsonObject) o).getString("name"))
            .filter(n -> n.equals("Pending Corp"))
            .count();
        assertEquals(0, companyNames);

        // Only 2 companies: Acme Corp and Skynet Inc
        assertEquals(2, data.size());
    }

    @Test
    void getCompanyListing_orderedByTotalReviewsDescThenManagerCountDescThenNameAsc() throws Exception {
        insertManager("M1", "Zeta Co",  "Manager", "approved", 4.0, 1);
        insertManager("M2", "Alpha Co", "Manager", "approved", 3.0, 1);
        insertManager("M3", "Alpha Co", "Director","ghost",    4.0, 2);

        JsonObject result = refreshAndGetListing();
        JsonArray data = result.getJsonArray("data");

        // Alpha Co: 3 total reviews; Zeta Co: 1 — Alpha Co should be first
        assertEquals("Alpha Co", data.getJsonObject(0).getString("name"));
        assertEquals("Zeta Co",  data.getJsonObject(1).getString("name"));
    }

    @Test
    void getCompanyListing_companiesWithReviewsBeforeCompaniesWithout() throws Exception {
        insertManager("M1", "No Reviews Co", "Manager", "ghost",    null, 0);
        insertManager("M2", "Has Reviews Co","Manager", "approved", 4.0, 5);

        JsonObject result = refreshAndGetListing();
        JsonArray data = result.getJsonArray("data");

        // Company with reviews must be listed before company with none
        assertEquals("Has Reviews Co", data.getJsonObject(0).getString("name"));
        assertEquals("No Reviews Co",  data.getJsonObject(1).getString("name"));
    }

    @Test
    void getCompanyListing_managerCountIsCorrect() throws Exception {
        insertManager("M1", "Acme Corp", "Manager",  "approved", 4.0, 3);
        insertManager("M2", "Acme Corp", "Director", "ghost",    3.0, 1);

        JsonObject result = refreshAndGetListing();
        JsonArray data = result.getJsonArray("data");

        assertEquals(1, data.size());
        JsonObject acme = data.getJsonObject(0);
        assertEquals(2L, acme.getLong("managerCount"));
        assertEquals(4L, acme.getLong("totalReviews")); // 3 + 1
    }

    @Test
    void getCompanyListing_emptyWhenNoManagers() throws Exception {
        JsonObject result = refreshAndGetListing();
        JsonArray data = result.getJsonArray("data");
        assertEquals(0, data.size());
    }

    @Test
    void getCompanyListing_avgRatingIgnoresZeroReviewManagers() throws Exception {
        insertManager("Rated",   "Acme Corp", "Manager",  "approved", 4.0, 2);
        insertManager("Unrated", "Acme Corp", "Director", "approved", 0.0, 0);

        JsonObject result = refreshAndGetListing();
        JsonArray data = result.getJsonArray("data");

        assertEquals(1, data.size());
        // Only the rated manager should contribute — avg must be 4.0, not (4.0 + 0.0) / 2
        Object avg = data.getJsonObject(0).getValue("avgRating");
        assertNotNull(avg, "avgRating should not be null when at least one manager has reviews");
        assertEquals(4.0, ((Number) avg).doubleValue(), 0.05);
    }

    @Test
    void getCompanyListing_avgRatingIsNullWhenAllManagersHaveZeroReviews() throws Exception {
        insertManager("Unrated", "Acme Corp", "Manager", "approved", 0.0, 0);

        JsonObject result = refreshAndGetListing();
        JsonArray data = result.getJsonArray("data");

        assertEquals(1, data.size());
        assertNull(data.getJsonObject(0).getValue("avgRating"),
            "avgRating must be null when all managers have 0 reviews");
    }

    // ── getCompanyProfile ────────────────────────────────────────────────────

    @Test
    void getCompanyProfile_returnsManagersForMatchingCompany() throws Exception {
        insertManager("Alice A", "Acme Corp",  "Manager",  "approved", 4.0, 2);
        insertManager("Bob B",   "Acme Corp",  "Director", "ghost",    5.0, 1);
        insertManager("Carol C", "Skynet Inc", "VP",       "approved", 3.0, 1);

        JsonObject profile = await(service.getCompanyProfile("Acme Corp"));

        assertEquals("Acme Corp", profile.getString("name"));
        assertEquals(2, profile.getInteger("managerCount"));
        assertEquals(3L, profile.getLong("totalReviews")); // 2 + 1

        JsonArray managers = profile.getJsonArray("managers");
        assertEquals(2, managers.size());
    }

    @Test
    void getCompanyProfile_caseInsensitiveCompanyMatch() throws Exception {
        insertManager("Alice A", "Acme Corp", "Manager", "approved", 4.0, 1);

        // Query with different casing — findOrCreate uses LOWER(TRIM) so it finds the same company
        JsonObject profile = await(service.getCompanyProfile("acme corp"));

        assertEquals(1, profile.getInteger("managerCount"));
    }

    @Test
    void getCompanyProfile_excludesPendingManagers() throws Exception {
        insertManager("Alice A", "Acme Corp", "Manager", "approved",         4.0, 2);
        insertManager("Bob B",   "Acme Corp", "Director","pending_approval", null, 0);

        JsonObject profile = await(service.getCompanyProfile("Acme Corp"));

        assertEquals(1, profile.getInteger("managerCount"));
    }

    @Test
    void getCompanyProfile_unknownCompany_createsGhostAndReturnsEmpty() throws Exception {
        // Navigating to any company name auto-creates a ghost entry and returns an empty profile.
        JsonObject result = await(service.getCompanyProfile("BrandNewCorp"));
        assertEquals("BrandNewCorp", result.getString("name"));
        assertEquals(0, result.getInteger("managerCount"));
        assertEquals(0, result.getInteger("totalReviews"));
    }

    @Test
    void getCompanyProfile_throwsBadRequestForBlankCompany() {
        assertThrows(Exception.class, () -> await(service.getCompanyProfile("")));
    }

    @Test
    void getCompanyListing_excludesSeedManagers() throws Exception {
        insertManager("Alice A", "Acme Corp", "Manager", "approved", 4.0, 2);
        insertManagerWithExternalId("Seed Sam", "Acme Corp", "Fake Lead", "approved", null, 0, "seed_001");

        JsonObject result = refreshAndGetListing();
        JsonArray data = result.getJsonArray("data");

        assertEquals(1, data.size());
        JsonObject acme = data.getJsonObject(0);
        assertEquals(1L, acme.getLong("managerCount"), "seed_ manager must not count toward managerCount");
        assertEquals(2L, acme.getLong("totalReviews"));
    }

    @Test
    void getCompanyListing_companyWithOnlySeedManagers_hiddenFromListing() throws Exception {
        insertManagerWithExternalId("Seed Sam", "Bootstrap Corp", "Fake Lead", "approved", null, 0, "seed_002");

        JsonObject result = refreshAndGetListing();
        JsonArray data = result.getJsonArray("data");

        assertEquals(0, data.size(), "Company with only seed_ managers must not appear in listing");
    }

    @Test
    void getCompanyListing_managerCountIncludesReviewBasedAssociation() throws Exception {
        // Ensure Acme Corp exists in the companies table (normally happens on first profile view)
        await(companyRepo.findOrCreate("Acme Corp", null, null));
        // Bob's company_id points to Skynet, but he has a review at Acme Corp
        insertManager("Bob B", "Skynet Inc", "Director", "approved", 4.0, 1);
        Long bobId = await(pool
            .preparedQuery("SELECT id FROM managers WHERE name = 'Bob B'")
            .execute()
            .map(rs -> rs.iterator().next().getLong("id")));
        insertReview(bobId, "Acme Corp", "VP");

        JsonObject result = refreshAndGetListing();
        JsonArray data = result.getJsonArray("data");

        // Both Acme Corp and Skynet Inc should appear
        long acmeCount = data.stream()
            .map(o -> (JsonObject) o)
            .filter(o -> "Acme Corp".equals(o.getString("name")))
            .mapToLong(o -> o.getLong("managerCount"))
            .findFirst().orElse(0);
        assertEquals(1, acmeCount, "Acme Corp should count Bob via his review there");
    }

    @Test
    void getCompanyProfile_excludesSeedManagers() throws Exception {
        insertManager("Alice A", "Acme Corp", "Manager", "approved", 4.0, 2);
        insertManagerWithExternalId("Seed Sam", "Acme Corp", "Fake Lead", "approved", null, 0, "seed_003");

        JsonObject profile = await(service.getCompanyProfile("Acme Corp"));

        assertEquals(1, profile.getInteger("managerCount"), "seed_ manager must not count toward managerCount");
        JsonArray managers = profile.getJsonArray("managers");
        assertEquals(1, managers.size());
        assertEquals("Alice A", managers.getJsonObject(0).getString("name"));
    }

    @Test
    void getCompanyProfile_scrapedDef14aManagersAreNotExcluded() throws Exception {
        insertManagerWithExternalId("Scraped Sarah", "Acme Corp", "VP", "approved", 4.0, 3, "DEF14A_001");

        JsonObject profile = await(service.getCompanyProfile("Acme Corp"));

        assertEquals(1, profile.getInteger("managerCount"), "DEF14A_ scraped managers must remain visible");
        assertEquals("Scraped Sarah", profile.getJsonArray("managers").getJsonObject(0).getString("name"));
    }

    @Test
    void getCompanyProfile_avgRatingComputedAcrossManagers() throws Exception {
        insertManager("Alice A", "Acme Corp", "Manager",  "approved", 4.0, 1);
        insertManager("Bob B",   "Acme Corp", "Director", "approved", 2.0, 1);

        JsonObject profile = await(service.getCompanyProfile("Acme Corp"));

        // avg of 4.0 and 2.0 = 3.0
        assertNotNull(profile.getValue("avgRating"));
        double avg = ((Number) profile.getValue("avgRating")).doubleValue();
        assertEquals(3.0, avg, 0.05);
    }

    @Test
    void getCompanyProfile_managerWithZeroReviews_overallRatingIsNull() throws Exception {
        insertManager("Alice A", "Acme Corp", "Manager", "approved", 0.0, 0);

        JsonObject profile = await(service.getCompanyProfile("Acme Corp"));

        // Manager exists but has no reviews — rating must be null, not 0.0
        assertNull(profile.getValue("avgRating"), "avgRating must be null when all managers have 0 reviews");
        JsonObject manager = profile.getJsonArray("managers").getJsonObject(0);
        assertNull(manager.getValue("overallRating"), "manager overallRating must be null when reviews_count is 0");
    }

    @Test
    void getCompanyProfile_avgRatingIgnoresZeroReviewManagers() throws Exception {
        insertManager("Rated",   "Acme Corp", "Manager",  "approved", 4.0, 2);
        insertManager("Unrated", "Acme Corp", "Director", "approved", 0.0, 0);

        JsonObject profile = await(service.getCompanyProfile("Acme Corp"));

        // Only the rated manager should contribute — avg must be 4.0, not (4.0 + 0.0) / 2
        assertNotNull(profile.getValue("avgRating"));
        double avg = ((Number) profile.getValue("avgRating")).doubleValue();
        assertEquals(4.0, avg, 0.05);
    }

    @Test
    void getCompanyProfile_managersOrderedByReviewsCountDesc() throws Exception {
        insertManager("Few Reviews",   "Acme Corp", "Manager",  "approved", 4.0, 1);
        insertManager("Many Reviews",  "Acme Corp", "Director", "approved", 3.5, 5);

        JsonObject profile = await(service.getCompanyProfile("Acme Corp"));
        JsonArray managers = profile.getJsonArray("managers");

        assertEquals("Many Reviews", managers.getJsonObject(0).getString("name"));
        assertEquals("Few Reviews",  managers.getJsonObject(1).getString("name"));
    }

    @Test
    void getCompanyProfile_includesManagersWithCareerHistoryAtCompany() throws Exception {
        // Alice currently works at Skynet Inc (company_id points to Skynet)
        insertManager("Alice A", "Skynet Inc", "Director", "approved", 4.0, 3);

        // Insert career_history row linking Alice to Acme Corp
        Long aliceId = await(pool
            .preparedQuery("SELECT id FROM managers WHERE name = 'Alice A'")
            .execute()
            .map(rs -> rs.iterator().next().getLong("id")));
        insertCareerHistory(aliceId, "Acme Corp", "Manager");

        // Acme Corp profile should show Alice even though her company_id is Skynet
        JsonObject acmeProfile = await(service.getCompanyProfile("Acme Corp"));
        assertEquals(1, acmeProfile.getInteger("managerCount"),
            "Manager with career_history at Acme Corp must appear on Acme Corp profile");
        assertEquals("Alice A", acmeProfile.getJsonArray("managers").getJsonObject(0).getString("name"));

        // Skynet profile should also still show Alice (her current company)
        JsonObject skynetProfile = await(service.getCompanyProfile("Skynet Inc"));
        assertEquals(1, skynetProfile.getInteger("managerCount"));
        assertEquals("Alice A", skynetProfile.getJsonArray("managers").getJsonObject(0).getString("name"));
    }

    @Test
    void getCompanyProfile_includesManagersWithReviewAtCompany() throws Exception {
        // Bob currently works at Skynet Inc but has a review where manager_company = "Acme Corp"
        insertManager("Bob B", "Skynet Inc", "Director", "approved", 3.5, 1);
        Long bobId = await(pool
            .preparedQuery("SELECT id FROM managers WHERE name = 'Bob B'")
            .execute()
            .map(rs -> rs.iterator().next().getLong("id")));
        insertReview(bobId, "Acme Corp", "VP");

        // Acme Corp profile should include Bob because of the review
        JsonObject acmeProfile = await(service.getCompanyProfile("Acme Corp"));
        assertEquals(1, acmeProfile.getInteger("managerCount"),
            "Manager with a review at Acme Corp must appear on Acme Corp profile");

        // Skynet profile should also show Bob (his current company)
        JsonObject skynetProfile = await(service.getCompanyProfile("Skynet Inc"));
        assertEquals(1, skynetProfile.getInteger("managerCount"));
    }

    @Test
    void getCompanyProfile_managerNotDuplicatedWhenCurrentAndHistoricalMatch() throws Exception {
        // Alice currently works at Acme Corp AND has a career_history entry there (rejoined)
        insertManager("Alice A", "Acme Corp", "Director", "approved", 4.0, 3);
        Long aliceId = await(pool
            .preparedQuery("SELECT id FROM managers WHERE name = 'Alice A'")
            .execute()
            .map(rs -> rs.iterator().next().getLong("id")));
        insertCareerHistory(aliceId, "Acme Corp", "Manager");

        JsonObject profile = await(service.getCompanyProfile("Acme Corp"));
        assertEquals(1, profile.getInteger("managerCount"), "Manager must appear exactly once, not duplicated");
    }

    @Test
    void getCompanyProfile_findsManagerByCompanyNameWhenCompanyIdIsNull() throws Exception {
        // Simulate a manager that has no company_id set (e.g. created before companies table was
        // fully wired, or via a code path that didn't link the FK). The m.company field must be
        // enough to find them on the company profile page.
        await(pool
            .preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,
                                     overall_rating,reviews_count,category_averages,company_id)
                VALUES ('Unlinked Alice','Acme Corp','Manager','img','active','approved',4.0,1,'{}',NULL)
                """)
            .execute());

        JsonObject profile = await(service.getCompanyProfile("Acme Corp"));

        assertEquals(1, profile.getInteger("managerCount"),
            "Manager must be found via m.company name even when company_id is null");
        assertEquals("Unlinked Alice", profile.getJsonArray("managers").getJsonObject(0).getString("name"));
    }

    @Test
    void getCompanyProfile_responseIncludesCompanyId() throws Exception {
        insertManager("Alice A", "Acme Corp", "Manager", "approved", 4.0, 1);

        JsonObject profile = await(service.getCompanyProfile("Acme Corp"));

        assertNotNull(profile.getValue("id"), "getCompanyProfile must include the company id");
        assertTrue(profile.getLong("id") > 0, "id must be a positive long");
    }

    @Test
    void getCompanyProfile_emptyCompany_responseIncludesCompanyId() throws Exception {
        // Company row exists (ghost) but has no linked managers — triggers empty-profile path
        await(companyRepo.findOrCreate("Empty Corp", null, null));

        JsonObject profile = await(service.getCompanyProfile("Empty Corp"));

        assertNotNull(profile.getValue("id"), "empty company profile must include id");
        assertTrue(profile.getLong("id") > 0);
        assertEquals(0, profile.getInteger("managerCount"));
    }

    // ── logo priority tests ──────────────────────────────────────────────────

    @Test
    void getCompanyListing_prefersLogoDevUrlFromManagerOverCompanyStoredUrl() throws Exception {
        // Company row has a Clearbit URL stored; manager has a logo.dev URL with the real domain.
        // After V21 the matview must surface the logo.dev URL.
        Row company = await(companyRepo.findOrCreate("Acme Corp", "acmecorp.com",
            "https://logo.clearbit.com/acmecorp.com"));
        insertManagerWithLogoUrl("Alice A", "Acme Corp", "Manager", "approved", 4.0, 2,
            company.getLong("id"), "https://img.logo.dev/acme.io?token=pk_test");

        JsonObject result = refreshAndGetListing();
        JsonArray data = result.getJsonArray("data");

        assertEquals(1, data.size());
        assertEquals("https://img.logo.dev/acme.io?token=pk_test",
            data.getJsonObject(0).getString("logoUrl"),
            "Company listing must prefer logo.dev URL from manager over Clearbit URL stored in companies");
    }

    @Test
    void getCompanyProfile_prefersLogoDevUrlFromManagerOverResolvedUrl() throws Exception {
        // logoResolver in test returns null; manager has the correct logo.dev URL.
        Row company = await(companyRepo.findOrCreate("Acme Corp", null, null));
        insertManagerWithLogoUrl("Alice A", "Acme Corp", "Manager", "approved", 4.0, 2,
            company.getLong("id"), "https://img.logo.dev/acme.io?token=pk_test");

        JsonObject profile = await(service.getCompanyProfile("Acme Corp"));

        assertEquals("https://img.logo.dev/acme.io?token=pk_test", profile.getString("logoUrl"),
            "Company profile must prefer logo.dev URL from manager rows");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void insertManager(String name, String company, String title, String status,
                               Double overallRating, int reviewsCount) throws Exception {
        insertManagerWithExternalId(name, company, title, status, overallRating, reviewsCount, null);
    }

    private void insertManagerWithExternalId(String name, String company, String title, String status,
                                              Double overallRating, int reviewsCount, String externalId) throws Exception {
        Long companyId = null;
        if (status.equals("approved") || status.equals("ghost")) {
            Row companyRow = await(companyRepo.findOrCreate(company, null, null));
            companyId = companyRow.getLong("id");
        }
        await(pool
            .preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,
                                     overall_rating,reviews_count,category_averages,company_id,external_id)
                VALUES ($1,$2,$3,'img','active',$4,$5,$6,'{}', $7, $8) RETURNING id
                """)
            .execute(Tuple.of(name, company, title, status, overallRating, reviewsCount, companyId, externalId)));
    }

    private void insertManagerWithLogoUrl(String name, String company, String title, String status,
                                          Double overallRating, int reviewsCount,
                                          Long companyId, String companyLogoUrl) throws Exception {
        await(pool
            .preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,
                                     overall_rating,reviews_count,category_averages,company_id,company_logo_url)
                VALUES ($1,$2,$3,'img','active',$4,$5,$6,'{}', $7, $8) RETURNING id
                """)
            .execute(Tuple.of(name, company, title, status, overallRating, reviewsCount, companyId, companyLogoUrl)));
    }

    private void insertReview(long managerId, String managerCompany, String managerTitle) throws Exception {
        await(pool
            .preparedQuery("""
                INSERT INTO reviews(manager_id, author, overall_rating, manager_company, manager_title,
                                    verified, helpful_count, created_at, updated_at)
                VALUES ($1, 'anon', 4.0, $2, $3, true, 0, now(), now())
                """)
            .execute(Tuple.of(managerId, managerCompany, managerTitle)));
    }

    private void insertCareerHistory(long managerId, String company, String title) throws Exception {
        Long companyId = await(companyRepo.findOrCreate(company, null, null)).getLong("id");
        await(pool
            .preparedQuery("""
                INSERT INTO career_history(manager_id, company, title, start_date, company_id)
                VALUES ($1, $2, $3, '2020-01-01 00:00:00+00', $4)
                """)
            .execute(Tuple.of(managerId, company, title, companyId)));
    }

    private JsonObject refreshAndGetListing() throws Exception {
        await(companyRepo.refreshCompanyStats());
        return await(service.getCompanyListing());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
