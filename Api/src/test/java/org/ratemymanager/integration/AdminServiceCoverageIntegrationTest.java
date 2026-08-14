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
import org.ratemymanager.repository.MergeSuggestionsRepository;
import org.ratemymanager.repository.NotificationRepository;
import org.ratemymanager.repository.ReviewRepository;
import org.ratemymanager.repository.UserRepository;
import org.ratemymanager.service.AdminService;
import org.ratemymanager.service.ServiceException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class AdminServiceCoverageIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("ratemymanager_test")
        .withUsername("test")
        .withPassword("test");

    static Pool                    pool;
    static AdminService            service;
    static UserRepository          userRepo;
    static ManagerRepository       managerRepo;
    static CompanyRepository       companyRepo;
    static MergeSuggestionsRepository mergeSuggestionsRepo;

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

        pool                 = PgPool.pool(connectOptions, new PoolOptions().setMaxSize(5));
        userRepo             = new UserRepository(pool);
        managerRepo          = new ManagerRepository(pool);
        companyRepo          = new CompanyRepository(pool);
        mergeSuggestionsRepo = new MergeSuggestionsRepository(pool);
        ReviewRepository       reviewRepo = new ReviewRepository(pool);
        EditRepository         editRepo   = new EditRepository(pool);
        NotificationRepository notifRepo  = new NotificationRepository(pool);
        service = new AdminService(userRepo, managerRepo, reviewRepo, editRepo, notifRepo,
                                   companyRepo, mergeSuggestionsRepo, pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE notifications, manager_url_history, company_stats_live").execute());
        await(pool.query("TRUNCATE managers, users CASCADE").execute());
        await(pool.query("TRUNCATE merge_suggestions CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getGhostManagers — lines 82-100
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getGhostManagers_nonAdmin_returns403() throws Exception {
        String userAuth = insertUser("auth0|ghost-user01", "GhostUser01", "user");
        ServiceException ex = assertServiceException(service.getGhostManagers(userAuth, 10, 0));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void getGhostManagers_noGhosts_returnsEmpty() throws Exception {
        String adminAuth = insertUser("auth0|ghost-admin01", "GhostAdmin01", "admin");
        insertApprovedManager("Approved Manager", "Corp", "Title");

        JsonObject result = await(service.getGhostManagers(adminAuth, 10, 0));
        assertEquals(0, result.getJsonArray("data").size());
        assertEquals(10, result.getInteger("limit"));
        assertEquals(0, result.getInteger("offset"));
    }

    @Test
    void getGhostManagers_withGhosts_returnsGhostManagers() throws Exception {
        String adminAuth = insertUser("auth0|ghost-admin02", "GhostAdmin02", "admin");
        insertGhostManager("Ghost Alice", "GhostCorp", "Director");

        JsonObject result = await(service.getGhostManagers(adminAuth, 10, 0));
        assertEquals(1, result.getJsonArray("data").size());
        JsonObject m = result.getJsonArray("data").getJsonObject(0);
        assertEquals("Ghost Alice", m.getString("name"));
        assertEquals("GhostCorp", m.getString("company"));
        assertNotNull(m.getString("createdAt"));
    }

    @Test
    void getGhostManagers_paginationRespected() throws Exception {
        String adminAuth = insertUser("auth0|ghost-admin03", "GhostAdmin03", "admin");
        insertGhostManager("Ghost One", "Corp", "Title");
        insertGhostManager("Ghost Two", "Corp", "Title");
        insertGhostManager("Ghost Three", "Corp", "Title");

        JsonObject page1 = await(service.getGhostManagers(adminAuth, 2, 0));
        JsonObject page2 = await(service.getGhostManagers(adminAuth, 2, 2));
        assertEquals(2, page1.getJsonArray("data").size());
        assertEquals(1, page2.getJsonArray("data").size());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // markGhostReviewed — lines 103-113
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void markGhostReviewed_notFound_returnsSuccessFalse() throws Exception {
        String adminAuth = insertUser("auth0|ghostrev-admin01", "GhostRevAdmin01", "admin");

        JsonObject result = await(service.markGhostReviewed(adminAuth, 999999L));
        assertFalse(result.getBoolean("success"));
        assertEquals("Ghost manager not found", result.getString("message"));
    }

    @Test
    void markGhostReviewed_ghostManager_setsApproved() throws Exception {
        String adminAuth = insertUser("auth0|ghostrev-admin02", "GhostRevAdmin02", "admin");
        long managerId = insertGhostManager("Ghost Bob", "Corp", "Engineer");

        JsonObject result = await(service.markGhostReviewed(adminAuth, managerId));
        assertTrue(result.getBoolean("success"));

        String status = await(pool
            .preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("approval_status")));
        assertEquals("approved", status);
    }

    @Test
    void markGhostReviewed_nonGhostManager_returnsSuccessFalse() throws Exception {
        String adminAuth = insertUser("auth0|ghostrev-admin03", "GhostRevAdmin03", "admin");
        long managerId = insertApprovedManager("Already Approved", "Corp", "Title");

        JsonObject result = await(service.markGhostReviewed(adminAuth, managerId));
        assertFalse(result.getBoolean("success"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // approvePendingManager with search_created_by — line 249 area
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void approvePendingManager_searchCreatedManager_doesNotSendNotification() throws Exception {
        String adminAuth   = insertUser("auth0|scb-admin01", "ScbAdmin01", "admin");
        String userAuth    = insertUser("auth0|scb-user01",  "ScbUser01",  "user");
        UUID   userId      = findUserId(userAuth);

        long managerId = insertSearchCreatedPendingManager("Search Created", "Corp", "Title", userId);

        await(service.approvePendingManager(adminAuth, managerId, null));
        Thread.sleep(300);

        long notifCount = await(pool
            .preparedQuery("SELECT COUNT(*) FROM notifications WHERE user_id = $1 AND type = $2")
            .execute(Tuple.of(userId, "manager_approved"))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0L, notifCount, "Search-created managers must NOT send approval notification");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // updateManagerLogo — line 331-332
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void updateManagerLogo_updatesLogoUrl() throws Exception {
        long managerId = insertApprovedManager("Logo Manager", "Corp", "Title");

        await(service.updateManagerLogo(managerId, "https://cdn.example.com/logo.png"));

        String logoUrl = await(pool
            .preparedQuery("SELECT company_logo_url FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("company_logo_url")));
        assertEquals("https://cdn.example.com/logo.png", logoUrl);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // banUser — reason too long — line 399
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void banUser_reasonTooLong_returns400() throws Exception {
        String adminAuth = insertUser("auth0|ban-admin01", "BanAdmin01", "admin");
        String reason    = "x".repeat(501);
        ServiceException ex = assertServiceException(service.banUser(adminAuth, UUID.randomUUID(), reason));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void banUser_blankReason_returns400() throws Exception {
        String adminAuth = insertUser("auth0|ban-admin02", "BanAdmin02", "admin");
        ServiceException ex = assertServiceException(service.banUser(adminAuth, UUID.randomUUID(), "   "));
        assertEquals(400, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getMergeSuggestions — lines 525-556
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getMergeSuggestions_nonAdmin_returns403() throws Exception {
        String userAuth = insertUser("auth0|merge-sug-user01", "MergeSugUser01", "user");
        ServiceException ex = assertServiceException(service.getMergeSuggestions(userAuth, 10, 0));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void getMergeSuggestions_noSuggestions_returnsEmptyWithTotal0() throws Exception {
        String adminAuth = insertUser("auth0|merge-sug-admin01", "MergeSugAdmin01", "admin");

        JsonObject result = await(service.getMergeSuggestions(adminAuth, 10, 0));
        assertEquals(0, result.getJsonArray("data").size());
        assertEquals(0, result.getInteger("total"));
    }

    @Test
    void getMergeSuggestions_withPendingSuggestion_returnsIt() throws Exception {
        String adminAuth = insertUser("auth0|merge-sug-admin02", "MergeSugAdmin02", "admin");
        long managerA = insertApprovedManager("John Smith", "Corp", "Engineer");
        long managerB = insertApprovedManager("Jon Smith",  "Corp", "Engineer");

        await(mergeSuggestionsRepo.upsert(managerA, managerB, "LIKELY_SAME",
                "Very similar names", 0, 0));

        JsonObject result = await(service.getMergeSuggestions(adminAuth, 10, 0));
        assertEquals(1, result.getJsonArray("data").size());
        assertEquals(1, result.getInteger("total"));

        JsonObject suggestion = result.getJsonArray("data").getJsonObject(0);
        assertEquals("LIKELY_SAME", suggestion.getString("confidence"));
        assertEquals("pending", suggestion.getString("status"));
        assertNotNull(suggestion.getJsonObject("managerA"));
        assertNotNull(suggestion.getJsonObject("managerB"));
        assertEquals(managerA, suggestion.getJsonObject("managerA").getLong("id"));
        assertEquals(managerB, suggestion.getJsonObject("managerB").getLong("id"));
    }

    @Test
    void getMergeSuggestions_paginationRespected() throws Exception {
        String adminAuth = insertUser("auth0|merge-sug-admin03", "MergeSugAdmin03", "admin");
        long m1 = insertApprovedManager("Alice A", "Corp", "E");
        long m2 = insertApprovedManager("Alice B", "Corp", "E");
        long m3 = insertApprovedManager("Alice C", "Corp", "E");
        long m4 = insertApprovedManager("Alice D", "Corp", "E");

        await(mergeSuggestionsRepo.upsert(m1, m2, "SAME", "r1", 0, 0));
        await(mergeSuggestionsRepo.upsert(m1, m3, "SAME", "r2", 0, 0));
        await(mergeSuggestionsRepo.upsert(m1, m4, "SAME", "r3", 0, 0));

        JsonObject page1 = await(service.getMergeSuggestions(adminAuth, 2, 0));
        JsonObject page2 = await(service.getMergeSuggestions(adminAuth, 2, 2));

        assertEquals(2, page1.getJsonArray("data").size());
        assertEquals(1, page2.getJsonArray("data").size());
        assertEquals(3, page1.getInteger("total"));
    }

    @Test
    void getMergeSuggestions_dismissedSuggestion_notReturnedInPending() throws Exception {
        String adminAuth = insertUser("auth0|merge-sug-admin04", "MergeSugAdmin04", "admin");
        long managerA = insertApprovedManager("Sam Smith", "Corp", "Engineer");
        long managerB = insertApprovedManager("Sam Smyth", "Corp", "Engineer");

        await(mergeSuggestionsRepo.upsert(managerA, managerB, "LIKELY_SAME", "reason", 0, 0));

        JsonObject before = await(service.getMergeSuggestions(adminAuth, 10, 0));
        assertEquals(1, before.getJsonArray("data").size());
        long suggestionId = before.getJsonArray("data").getJsonObject(0).getLong("id");

        await(service.dismissMergeSuggestion(adminAuth, suggestionId));

        JsonObject after = await(service.getMergeSuggestions(adminAuth, 10, 0));
        assertEquals(0, after.getJsonArray("data").size());
        assertEquals(0, after.getInteger("total"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // dismissMergeSuggestion — lines 560-563
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void dismissMergeSuggestion_nonAdmin_returns403() throws Exception {
        String userAuth = insertUser("auth0|dismiss-user01", "DismissUser01", "user");
        ServiceException ex = assertServiceException(service.dismissMergeSuggestion(userAuth, 1L));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void dismissMergeSuggestion_success_updatesStatusToDismissed() throws Exception {
        String adminAuth = insertUser("auth0|dismiss-admin01", "DismissAdmin01", "admin");
        long managerA = insertApprovedManager("Tom A", "Corp", "E");
        long managerB = insertApprovedManager("Tom B", "Corp", "E");

        await(mergeSuggestionsRepo.upsert(managerA, managerB, "LIKELY_SAME", "reason", 0, 0));

        JsonObject list = await(service.getMergeSuggestions(adminAuth, 10, 0));
        long suggestionId = list.getJsonArray("data").getJsonObject(0).getLong("id");

        JsonObject result = await(service.dismissMergeSuggestion(adminAuth, suggestionId));
        assertTrue(result.getBoolean("success"));

        String status = await(pool
            .preparedQuery("SELECT status FROM merge_suggestions WHERE id = $1")
            .execute(Tuple.of(suggestionId))
            .map(rs -> rs.iterator().next().getString("status")));
        assertEquals("dismissed", status);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // requireAdminPublic — line 67
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void requireAdminPublic_nullAuth0Id_returns401() {
        ServiceException ex = assertServiceException(service.requireAdminPublic(null));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void requireAdminPublic_unknownAuth0Id_returns401() {
        ServiceException ex = assertServiceException(service.requireAdminPublic("auth0|nobody"));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void requireAdminPublic_adminUser_returnsAdminUuid() throws Exception {
        String adminAuth = insertUser("auth0|pub-admin01", "PubAdmin01", "admin");
        UUID adminId = await(service.requireAdminPublic(adminAuth));
        assertNotNull(adminId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getCountryStats — bot filter
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getCountryStats_excludesScrapedManagers() throws Exception {
        String adminAuth = insertUser("auth0|cs-admin01", "CsAdmin01", "admin");

        // 3 scraped managers: external_id set to a non-seed value
        for (int i = 0; i < 3; i++) {
            await(pool.preparedQuery("""
                    INSERT INTO managers(name,company,title,image,status,approval_status,
                                        overall_rating,reviews_count,category_averages,country,external_id)
                    VALUES ($1,$2,$3,'img','active','approved',0,0,'{}','United States',$4)
                    """)
                .execute(Tuple.of("Scraped " + i, "ScrapedCo", "Role", "scraper_" + i)));
        }

        // 1 organic manager: external_id IS NULL
        await(pool.preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,
                                     overall_rating,reviews_count,category_averages,country)
                VALUES ($1,$2,$3,'img','active','approved',0,0,'{}','United States')
                """)
            .execute(Tuple.of("Real Manager", "RealCo", "Director")));

        JsonObject result = await(service.getCountryStats(adminAuth));
        JsonArray managers = result.getJsonArray("managers");

        long usCount = managers.stream()
            .map(o -> (JsonObject) o)
            .filter(e -> "United States".equals(e.getString("country")))
            .mapToLong(e -> e.getLong("count"))
            .findFirst().orElse(0L);
        assertEquals(1L, usCount, "scraped managers (external_id set) must not appear in country stats");
    }

    @Test
    void getCountryStats_includesSeedManagers() throws Exception {
        String adminAuth = insertUser("auth0|cs-admin02", "CsAdmin02", "admin");

        // Seed manager (external_id LIKE 'seed_%') — must be included, it's a real ghost profile
        await(pool.preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,
                                     overall_rating,reviews_count,category_averages,country,external_id)
                VALUES ($1,$2,$3,'img','active','ghost',0,0,'{}','Canada','seed_abc')
                """)
            .execute(Tuple.of("Ghost Manager", "GhostCo", "Manager")));

        // Scraped manager in same country — must be excluded
        await(pool.preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,
                                     overall_rating,reviews_count,category_averages,country,external_id)
                VALUES ($1,$2,$3,'img','active','approved',0,0,'{}','Canada','scraper_x')
                """)
            .execute(Tuple.of("Scraped Manager", "ScrapedCo", "Bot")));

        JsonObject result = await(service.getCountryStats(adminAuth));
        JsonArray managers = result.getJsonArray("managers");

        long caCount = managers.stream()
            .map(o -> (JsonObject) o)
            .filter(e -> "Canada".equals(e.getString("country")))
            .mapToLong(e -> e.getLong("count"))
            .findFirst().orElse(0L);
        assertEquals(1L, caCount, "seed managers must appear; scraped managers must not");
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

    private UUID findUserId(String auth0Id) throws Exception {
        return await(pool
            .preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private long insertApprovedManager(String name, String company, String title) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages) " +
            "VALUES ($1,$2,$3,'img','active','approved',0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertGhostManager(String name, String company, String title) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages) " +
            "VALUES ($1,$2,$3,'img','active','ghost',0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertSearchCreatedPendingManager(String name, String company, String title,
                                                    UUID searchCreatedByUserId) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,status,approval_status,overall_rating,reviews_count,category_averages," +
            "submitted_by,search_created_by_user_id) " +
            "VALUES ($1,$2,$3,'active','pending_approval',0,0,'{}',$4,$4) RETURNING id")
            .execute(Tuple.of(name, company, title, searchCreatedByUserId))
            .map(rs -> rs.iterator().next().getLong("id")));
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
