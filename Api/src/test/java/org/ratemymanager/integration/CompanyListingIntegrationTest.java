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

        JsonObject result = await(service.getCompanyListing());
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

        JsonObject result = await(service.getCompanyListing());
        JsonArray data = result.getJsonArray("data");

        // Alpha Co: 3 total reviews; Zeta Co: 1 — Alpha Co should be first
        assertEquals("Alpha Co", data.getJsonObject(0).getString("name"));
        assertEquals("Zeta Co",  data.getJsonObject(1).getString("name"));
    }

    @Test
    void getCompanyListing_companiesWithReviewsBeforeCompaniesWithout() throws Exception {
        insertManager("M1", "No Reviews Co", "Manager", "ghost",    null, 0);
        insertManager("M2", "Has Reviews Co","Manager", "approved", 4.0, 5);

        JsonObject result = await(service.getCompanyListing());
        JsonArray data = result.getJsonArray("data");

        // Company with reviews must be listed before company with none
        assertEquals("Has Reviews Co", data.getJsonObject(0).getString("name"));
        assertEquals("No Reviews Co",  data.getJsonObject(1).getString("name"));
    }

    @Test
    void getCompanyListing_managerCountIsCorrect() throws Exception {
        insertManager("M1", "Acme Corp", "Manager",  "approved", 4.0, 3);
        insertManager("M2", "Acme Corp", "Director", "ghost",    3.0, 1);

        JsonObject result = await(service.getCompanyListing());
        JsonArray data = result.getJsonArray("data");

        assertEquals(1, data.size());
        JsonObject acme = data.getJsonObject(0);
        assertEquals(2L, acme.getLong("managerCount"));
        assertEquals(4L, acme.getLong("totalReviews")); // 3 + 1
    }

    @Test
    void getCompanyListing_emptyWhenNoManagers() throws Exception {
        JsonObject result = await(service.getCompanyListing());
        JsonArray data = result.getJsonArray("data");
        assertEquals(0, data.size());
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
    void getCompanyProfile_returnsEmptyProfileForUnknownCompany() throws Exception {
        JsonObject profile = await(service.getCompanyProfile("NonexistentCorp"));

        assertEquals("NonexistentCorp", profile.getString("name"));
        assertEquals(0, profile.getInteger("managerCount"));
        assertEquals(0, profile.getLong("totalReviews"));
        assertNull(profile.getValue("avgRating"));
        assertEquals(0, profile.getJsonArray("managers").size());
    }

    @Test
    void getCompanyProfile_throwsBadRequestForBlankCompany() {
        assertThrows(Exception.class, () -> await(service.getCompanyProfile("")));
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
    void getCompanyProfile_managersOrderedByReviewsCountDesc() throws Exception {
        insertManager("Few Reviews",   "Acme Corp", "Manager",  "approved", 4.0, 1);
        insertManager("Many Reviews",  "Acme Corp", "Director", "approved", 3.5, 5);

        JsonObject profile = await(service.getCompanyProfile("Acme Corp"));
        JsonArray managers = profile.getJsonArray("managers");

        assertEquals("Many Reviews", managers.getJsonObject(0).getString("name"));
        assertEquals("Few Reviews",  managers.getJsonObject(1).getString("name"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void insertManager(String name, String company, String title, String status,
                               Double overallRating, int reviewsCount) throws Exception {
        // Ensure a companies row exists first, then insert the manager linked to it
        Long companyId = null;
        if (status.equals("approved") || status.equals("ghost")) {
            Row companyRow = await(companyRepo.findOrCreate(company, null, null));
            companyId = companyRow.getLong("id");
        }
        await(pool
            .preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,
                                     overall_rating,reviews_count,category_averages,company_id)
                VALUES ($1,$2,$3,'img','active',$4,$5,$6,'{}', $7) RETURNING id
                """)
            .execute(Tuple.of(name, company, title, status, overallRating, reviewsCount, companyId)));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
