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
import org.ratemymanager.repository.NotificationRepository;
import org.ratemymanager.repository.ReportRepository;
import org.ratemymanager.repository.ReviewRepository;
import org.ratemymanager.repository.UserRepository;
import org.ratemymanager.service.AdminService;
import org.ratemymanager.service.ManagerService;
import org.ratemymanager.service.ServiceException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class CompanyAdminIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("ratemymanager_test")
        .withUsername("test")
        .withPassword("test");

    static Pool              pool;
    static AdminService      service;
    static ManagerService    managerService;
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

        pool        = PgPool.pool(connectOptions, new PoolOptions().setMaxSize(5));
        companyRepo = new CompanyRepository(pool);
        UserRepository    userRepo    = new UserRepository(pool);
        ManagerRepository managerRepo = new ManagerRepository(pool);
        ReviewRepository  reviewRepo  = new ReviewRepository(pool);
        EditRepository    editRepo    = new EditRepository(pool);
        ReportRepository  reportRepo  = new ReportRepository(pool);
        NotificationRepository notifRepo = new NotificationRepository(pool);
        service = new AdminService(userRepo, managerRepo, reviewRepo, editRepo, notifRepo, companyRepo);
        managerService = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, companyRepo, pool, name -> null);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, users, companies CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // adminListCompanies
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void adminListCompanies_nonAdmin_returns403() throws Exception {
        String userAuth = insertUser("auth0|co-user01", "CoUser01", "user");
        ServiceException ex = assertServiceException(service.adminListCompanies(userAuth));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void adminListCompanies_returnsAllCompaniesWithManagerCount() throws Exception {
        String adminAuth = insertUser("auth0|co-admin01", "CoAdmin01", "admin");
        long companyId = insertCompany("Acme Corp");
        insertManagerForCompany("Alice A", "Acme Corp", "Manager", "approved", companyId);
        insertManagerForCompany("Bob B",   "Acme Corp", "Director","approved", companyId);

        JsonObject result = await(service.adminListCompanies(adminAuth));
        JsonArray data = result.getJsonArray("data");
        assertEquals(1, data.size());
        JsonObject co = data.getJsonObject(0);
        assertEquals("Acme Corp", co.getString("name"));
        assertEquals(2L, co.getLong("managerCount"));
    }

    @Test
    void adminListCompanies_returnsEmptyWhenNoCompanies() throws Exception {
        String adminAuth = insertUser("auth0|co-admin02", "CoAdmin02", "admin");
        JsonObject result = await(service.adminListCompanies(adminAuth));
        assertEquals(0, result.getJsonArray("data").size());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // adminRenameCompany
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void adminRenameCompany_nonAdmin_returns403() throws Exception {
        String userAuth = insertUser("auth0|co-user02", "CoUser02", "user");
        long companyId  = insertCompany("OldName");
        ServiceException ex = assertServiceException(service.adminRenameCompany(userAuth, companyId, "NewName"));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void adminRenameCompany_blankName_returns400() throws Exception {
        String adminAuth = insertUser("auth0|co-admin03", "CoAdmin03", "admin");
        long companyId   = insertCompany("Acme Corp");
        ServiceException ex = assertServiceException(service.adminRenameCompany(adminAuth, companyId, "  "));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void adminRenameCompany_conflictWithExistingCompany_returns409() throws Exception {
        String adminAuth = insertUser("auth0|co-admin04", "CoAdmin04", "admin");
        long keepId      = insertCompany("Keep Corp");
        insertCompany("Other Corp");
        ServiceException ex = assertServiceException(service.adminRenameCompany(adminAuth, keepId, "Other Corp"));
        assertEquals(409, ex.getStatusCode());
    }

    @Test
    void adminRenameCompany_allowsSameNameForSameCompany() throws Exception {
        String adminAuth = insertUser("auth0|co-admin05", "CoAdmin05", "admin");
        long companyId   = insertCompany("Acme Corp");
        // Renaming to the same name must succeed (idempotent)
        JsonObject result = await(service.adminRenameCompany(adminAuth, companyId, "Acme Corp"));
        assertTrue(result.getBoolean("success"));
    }

    @Test
    void adminRenameCompany_updatesCompanyRow() throws Exception {
        String adminAuth = insertUser("auth0|co-admin06", "CoAdmin06", "admin");
        long companyId   = insertCompany("OldCorp");
        await(service.adminRenameCompany(adminAuth, companyId, "NewCorp"));

        String name = await(pool.preparedQuery("SELECT name FROM companies WHERE id = $1")
            .execute(Tuple.of(companyId))
            .map(rs -> rs.iterator().next().getString("name")));
        assertEquals("NewCorp", name);
    }

    @Test
    void adminRenameCompany_cascadesToManagersCompanyField() throws Exception {
        String adminAuth = insertUser("auth0|co-admin07", "CoAdmin07", "admin");
        long companyId   = insertCompany("OldCorp");
        long managerId   = insertManagerForCompany("Alice A", "OldCorp", "Manager", "approved", companyId);

        await(service.adminRenameCompany(adminAuth, companyId, "NewCorp"));

        String company = await(pool.preparedQuery("SELECT company FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("company")));
        assertEquals("NewCorp", company);
    }

    @Test
    void adminRenameCompany_cascadesToCareerHistory() throws Exception {
        String adminAuth = insertUser("auth0|co-admin08", "CoAdmin08", "admin");
        long companyId   = insertCompany("OldCorp");
        long managerId   = insertManagerForCompany("Bob B", "OldCorp", "Director", "approved", companyId);
        insertCareerHistory(managerId, "OldCorp", "VP", companyId);

        await(service.adminRenameCompany(adminAuth, companyId, "NewCorp"));

        String chCompany = await(pool.preparedQuery("SELECT company FROM career_history WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("company")));
        assertEquals("NewCorp", chCompany);
    }

    @Test
    void getCompanyProfile_afterRename_newNameResolvesCorrectly() throws Exception {
        String adminAuth = insertUser("auth0|co-admin-cp01", "CpAdmin01", "admin");
        long companyId   = insertCompany("Acme Corp");
        insertManagerForCompany("Alice A", "Acme Corp", "Manager", "approved", companyId);

        await(service.adminRenameCompany(adminAuth, companyId, "Acme Corporation"));

        // New name must resolve correctly with the manager
        io.vertx.core.json.JsonObject result = await(managerService.getCompanyProfile("Acme Corporation"));
        assertEquals("Acme Corporation", result.getString("name"));
        assertEquals(1, result.getInteger("managerCount"));
    }

    @Test
    void adminRenameCompany_canBeRenamedMultipleTimes() throws Exception {
        String adminAuth = insertUser("auth0|co-admin-seq01", "CoAdminSeq01", "admin");
        long companyId   = insertCompany("OriginalName");
        insertManagerForCompany("Alice A", "OriginalName", "Manager", "approved", companyId);

        await(service.adminRenameCompany(adminAuth, companyId, "FirstRename"));

        // Second rename must succeed immediately after the first
        JsonObject result = await(service.adminRenameCompany(adminAuth, companyId, "SecondRename"));
        assertTrue(result.getBoolean("success"), "Second rename must succeed");

        // Company profile must resolve by the second name
        JsonObject profile = await(managerService.getCompanyProfile("SecondRename"));
        assertEquals("SecondRename", profile.getString("name"));
        assertEquals(1, profile.getInteger("managerCount"));

        // Old names must no longer exist in the companies table
        long oldCount = await(pool.preparedQuery(
            "SELECT COUNT(*) FROM companies WHERE LOWER(TRIM(name)) IN ('originalname', 'firstrename')")
            .execute()
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0L, oldCount, "Previous names must not exist after successive renames");
    }

    @Test
    void adminRenameCompany_cascadesToReviews() throws Exception {
        String adminAuth = insertUser("auth0|co-admin09", "CoAdmin09", "admin");
        long companyId   = insertCompany("OldCorp");
        long managerId   = insertManagerForCompany("Carol C", "OldCorp", "Manager", "approved", companyId);
        insertReview(managerId, "OldCorp", "Manager");

        await(service.adminRenameCompany(adminAuth, companyId, "NewCorp"));

        String reviewCompany = await(pool.preparedQuery("SELECT manager_company FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("manager_company")));
        assertEquals("NewCorp", reviewCompany);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // adminMergeCompanies
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void adminMergeCompanies_nonAdmin_returns403() throws Exception {
        String userAuth = insertUser("auth0|co-user03", "CoUser03", "user");
        long keepId     = insertCompany("Keep Corp");
        long mergeId    = insertCompany("Merge Corp");
        ServiceException ex = assertServiceException(service.adminMergeCompanies(userAuth, keepId, mergeId));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void adminMergeCompanies_sameId_returns400() throws Exception {
        String adminAuth = insertUser("auth0|co-admin10", "CoAdmin10", "admin");
        long companyId   = insertCompany("Acme Corp");
        ServiceException ex = assertServiceException(service.adminMergeCompanies(adminAuth, companyId, companyId));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void adminMergeCompanies_deletesSourceCompany() throws Exception {
        String adminAuth = insertUser("auth0|co-admin11", "CoAdmin11", "admin");
        long keepId      = insertCompany("Keep Corp");
        long mergeId     = insertCompany("Merge Corp");

        await(service.adminMergeCompanies(adminAuth, keepId, mergeId));

        long mergeExists = await(pool.preparedQuery("SELECT COUNT(*) FROM companies WHERE id = $1")
            .execute(Tuple.of(mergeId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0L, mergeExists, "Source company must be deleted after merge");

        long keepExists = await(pool.preparedQuery("SELECT COUNT(*) FROM companies WHERE id = $1")
            .execute(Tuple.of(keepId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, keepExists, "Target company must still exist after merge");
    }

    @Test
    void adminMergeCompanies_movesManagersToKeepCompany() throws Exception {
        String adminAuth = insertUser("auth0|co-admin12", "CoAdmin12", "admin");
        long keepId      = insertCompany("Keep Corp");
        long mergeId     = insertCompany("Merge Corp");
        long managerId   = insertManagerForCompany("Alice A", "Merge Corp", "Manager", "approved", mergeId);

        await(service.adminMergeCompanies(adminAuth, keepId, mergeId));

        Row row = await(pool.preparedQuery("SELECT company_id, company FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));
        assertEquals(keepId, row.getLong("company_id"), "Manager's company_id must point to keep company");
        assertEquals("Keep Corp", row.getString("company"), "Manager's company name must update to keep company name");
    }

    @Test
    void adminMergeCompanies_movesCareerHistoryToKeepCompany() throws Exception {
        String adminAuth = insertUser("auth0|co-admin13", "CoAdmin13", "admin");
        long keepId      = insertCompany("Keep Corp");
        long mergeId     = insertCompany("Merge Corp");
        long managerId   = insertManagerForCompany("Bob B", "Merge Corp", "Director", "approved", mergeId);
        insertCareerHistory(managerId, "Merge Corp", "VP", mergeId);

        await(service.adminMergeCompanies(adminAuth, keepId, mergeId));

        Row ch = await(pool.preparedQuery("SELECT company_id, company FROM career_history WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));
        assertEquals(keepId, ch.getLong("company_id"), "career_history company_id must point to keep company");
        assertEquals("Keep Corp", ch.getString("company"), "career_history company name must update");
    }

    @Test
    void adminMergeCompanies_updatesReviewsCompanyNameToKeep() throws Exception {
        String adminAuth = insertUser("auth0|co-admin14", "CoAdmin14", "admin");
        long keepId      = insertCompany("Keep Corp");
        long mergeId     = insertCompany("Merge Corp");
        long managerId   = insertManagerForCompany("Carol C", "Merge Corp", "Manager", "approved", mergeId);
        insertReview(managerId, "Merge Corp", "Manager");

        await(service.adminMergeCompanies(adminAuth, keepId, mergeId));

        String reviewCompany = await(pool.preparedQuery("SELECT manager_company FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("manager_company")));
        assertEquals("Keep Corp", reviewCompany, "Review manager_company must update to keep company name");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // adminMergeCompanies — company_stats matview refresh (synchronous)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void adminMergeCompanies_afterMerge_companyListingNoLongerShowsMergedCompany() throws Exception {
        String adminAuth = insertUser("auth0|co-admin20", "CoAdmin20", "admin");
        long keepId      = insertCompany("Keep Corp");
        long mergeId     = insertCompany("Merge Corp");
        insertManagerForCompany("Alice A", "Keep Corp",  "Manager",  "approved", keepId);
        insertManagerForCompany("Bob B",   "Merge Corp", "Director", "approved", mergeId);

        // Populate the matview so both companies appear before the merge
        await(companyRepo.refreshCompanyStats());
        JsonObject before = await(managerService.getCompanyListing());
        assertEquals(2, before.getJsonArray("data").size(), "Pre-condition: both companies must appear in listing");

        // Merge — refreshCompanyStats is awaited synchronously in adminMergeCompanies
        await(service.adminMergeCompanies(adminAuth, keepId, mergeId));

        // Company listing must immediately reflect the merge (no manual refresh needed)
        JsonObject after = await(managerService.getCompanyListing());
        long keepCount  = after.getJsonArray("data").stream()
            .filter(o -> "Keep Corp".equals(((io.vertx.core.json.JsonObject) o).getString("name")))
            .count();
        long mergeCount = after.getJsonArray("data").stream()
            .filter(o -> "Merge Corp".equals(((io.vertx.core.json.JsonObject) o).getString("name")))
            .count();
        assertEquals(1L, keepCount,  "Keep company must appear in listing after merge");
        assertEquals(0L, mergeCount, "Merged company must not appear in listing after merge");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // mergeManagers — company_stats matview refresh
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void mergeManagers_afterMerge_companyListingReflectsReducedCount() throws Exception {
        String adminAuth = insertUser("auth0|mg-admin01", "MgAdmin01", "admin");
        long companyId   = insertCompany("Acme Corp");
        long keepId      = insertManagerForCompany("Alice A", "Acme Corp", "Manager",  "approved", companyId);
        long mergeId     = insertManagerForCompany("Bob B",   "Acme Corp", "Director", "approved", companyId);

        // Populate the matview before the merge so it shows 2 managers
        await(companyRepo.refreshCompanyStats());
        JsonObject before = await(managerService.getCompanyListing());
        assertEquals(2L, before.getJsonArray("data").getJsonObject(0).getLong("managerCount"),
            "Pre-condition: listing must show 2 managers before merge");

        // Merge — internally fires refreshCompanyStats (fire-and-forget)
        await(service.mergeManagers(adminAuth, keepId, mergeId));

        // Explicitly refresh to ensure the matview is up-to-date for this assertion
        // (fire-and-forget timing is non-deterministic; the DB state after merge is what we test here)
        await(companyRepo.refreshCompanyStats());
        JsonObject after = await(managerService.getCompanyListing());
        assertEquals(1L, after.getJsonArray("data").getJsonObject(0).getLong("managerCount"),
            "After merge, company listing must show 1 manager (merged manager was deleted)");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private String insertUser(String auth0Id, String username, String role) throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id, email, username, first_name, last_name, role) " +
            "VALUES ($1,$2,$3,$4,$5,$6)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User", role)));
        return auth0Id;
    }

    private long insertCompany(String name) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO companies(name, status) VALUES ($1, 'approved') RETURNING id")
            .execute(Tuple.of(name))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertManagerForCompany(String name, String company, String title,
                                          String status, long companyId) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,
                                     overall_rating,reviews_count,category_averages,company_id)
                VALUES ($1,$2,$3,'img','active',$4,0,0,'{}',$5) RETURNING id
                """)
            .execute(Tuple.of(name, company, title, status, companyId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private void insertCareerHistory(long managerId, String company, String title, long companyId) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO career_history(manager_id, company, title, start_date, company_id)
                VALUES ($1, $2, $3, '2020-01-01 00:00:00+00', $4)
                """)
            .execute(Tuple.of(managerId, company, title, companyId)));
    }

    private void insertReview(long managerId, String company, String title) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO reviews(manager_id, author, overall_rating, manager_company, manager_title,
                                    verified, helpful_count, created_at, updated_at)
                VALUES ($1, 'anon', 4.0, $2, $3, true, 0, now(), now())
                """)
            .execute(Tuple.of(managerId, company, title)));
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
}
