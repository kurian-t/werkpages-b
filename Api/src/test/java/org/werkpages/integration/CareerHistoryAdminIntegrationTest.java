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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.MergeSuggestionsRepository;
import org.werkpages.repository.NotificationRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.AdminService;
import org.werkpages.service.ServiceException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class CareerHistoryAdminIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool              pool;
    static AdminService      service;
    static ManagerRepository managerRepo;

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
        managerRepo = new ManagerRepository(pool);
        UserRepository              userRepo    = new UserRepository(pool);
        ReviewRepository            reviewRepo  = new ReviewRepository(pool);
        EditRepository              editRepo    = new EditRepository(pool);
        NotificationRepository      notifRepo   = new NotificationRepository(pool);
        CompanyRepository           companyRepo = new CompanyRepository(pool);
        MergeSuggestionsRepository  mergeRepo   = new MergeSuggestionsRepository(pool);
        service = new AdminService(userRepo, managerRepo, reviewRepo, editRepo, notifRepo,
                                   companyRepo, mergeRepo, pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE notifications, manager_url_history, company_stats_live").execute());
        await(pool.query("TRUNCATE managers, users, companies CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // adminUpdateCareerEntry — auth checks
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void update_nonAdmin_returns403() throws Exception {
        String userAuth  = insertUser("auth0|ch-user01", "ChUser01", "user");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        ServiceException ex = assertServiceException(
            service.adminUpdateCareerEntry(userAuth, managerId, entryId, "Acme", "Director", "2020", null));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void update_nullAuth_returns401() throws Exception {
        long managerId = insertManager("Alice", "Acme", "Manager");
        long entryId   = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        ServiceException ex = assertServiceException(
            service.adminUpdateCareerEntry(null, managerId, entryId, "Acme", "Director", "2020", null));
        assertEquals(401, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // adminUpdateCareerEntry — validation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void update_blankCompany_returns400() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin01", "ChAdmin01", "admin");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        ServiceException ex = assertServiceException(
            service.adminUpdateCareerEntry(adminAuth, managerId, entryId, "  ", "Manager", "2020", null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void update_blankTitle_returns400() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin02", "ChAdmin02", "admin");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        ServiceException ex = assertServiceException(
            service.adminUpdateCareerEntry(adminAuth, managerId, entryId, "Acme", "", "2020", null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void update_blankStartDate_returns400() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin03", "ChAdmin03", "admin");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        ServiceException ex = assertServiceException(
            service.adminUpdateCareerEntry(adminAuth, managerId, entryId, "Acme", "Manager", "  ", null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void update_invalidDateFormat_returns400() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin04", "ChAdmin04", "admin");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        ServiceException ex = assertServiceException(
            service.adminUpdateCareerEntry(adminAuth, managerId, entryId, "Acme", "Manager", "not-a-date", null));
        assertEquals(400, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // adminUpdateCareerEntry — success paths
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void update_validYearStartDate_updatesEntry() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin05", "ChAdmin05", "admin");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        JsonObject result = await(service.adminUpdateCareerEntry(
            adminAuth, managerId, entryId, "Globex", "Director", "2019", null));

        assertTrue(result.getBoolean("success"));
        assertEquals(1, result.getInteger("updated"));

        Row row = await(pool
            .preparedQuery("SELECT company, title, start_date, end_date FROM career_history WHERE id = $1")
            .execute(Tuple.of(entryId))
            .map(rs -> rs.iterator().next()));

        assertEquals("Globex", row.getString("company"));
        assertEquals("Director", row.getString("title"));
        assertNotNull(row.getOffsetDateTime("start_date"));
        assertNull(row.getOffsetDateTime("end_date"), "end_date must be null when endDate not provided");
    }

    @Test
    void update_withEndDate_setsEndDate() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin06", "ChAdmin06", "admin");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2018");

        await(service.adminUpdateCareerEntry(
            adminAuth, managerId, entryId, "Acme", "Manager", "2018", "2022"));

        Row row = await(pool
            .preparedQuery("SELECT end_date FROM career_history WHERE id = $1")
            .execute(Tuple.of(entryId))
            .map(rs -> rs.iterator().next()));

        assertNotNull(row.getOffsetDateTime("end_date"), "end_date must be set when endDate provided");
    }

    @Test
    void update_wrongManagerId_updatesZeroRows() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin07", "ChAdmin07", "admin");
        long managerId1  = insertManager("Alice", "Acme", "Manager");
        long managerId2  = insertManager("Bob",   "Globex", "Lead");
        long entryId     = insertCareerEntry(managerId1, "Acme", "Manager", "2020");

        // Attempt to update an entry that belongs to managerId1 but using managerId2
        JsonObject result = await(service.adminUpdateCareerEntry(
            adminAuth, managerId2, entryId, "Globex", "Director", "2020", null));

        assertEquals(0, result.getInteger("updated"), "update must not affect entries belonging to other managers");
    }

    @Test
    void update_yearMonthStartDate_accepted() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin08", "ChAdmin08", "admin");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        JsonObject result = await(service.adminUpdateCareerEntry(
            adminAuth, managerId, entryId, "Acme", "Manager", "2021-06", null));

        assertTrue(result.getBoolean("success"));
        assertEquals(1, result.getInteger("updated"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // adminDeleteCareerEntry — auth checks
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void delete_nonAdmin_returns403() throws Exception {
        String userAuth  = insertUser("auth0|ch-user02", "ChUser02", "user");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        ServiceException ex = assertServiceException(
            service.adminDeleteCareerEntry(userAuth, managerId, entryId));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void delete_nullAuth_returns401() throws Exception {
        long managerId = insertManager("Alice", "Acme", "Manager");
        long entryId   = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        ServiceException ex = assertServiceException(
            service.adminDeleteCareerEntry(null, managerId, entryId));
        assertEquals(401, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // adminDeleteCareerEntry — success paths
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void delete_removesEntry() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin09", "ChAdmin09", "admin");
        long managerId   = insertManager("Alice", "Acme", "Manager");
        long entryId     = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        JsonObject result = await(service.adminDeleteCareerEntry(adminAuth, managerId, entryId));
        assertTrue(result.getBoolean("success"));
        assertEquals(1, result.getInteger("deleted"));

        long count = await(pool
            .preparedQuery("SELECT COUNT(*) FROM career_history WHERE id = $1")
            .execute(Tuple.of(entryId))
            .map(rs -> rs.iterator().next().getLong(0)));

        assertEquals(0L, count, "entry must be removed from career_history table");
    }

    @Test
    void delete_wrongManagerId_deletesZeroRows() throws Exception {
        String adminAuth = insertUser("auth0|ch-admin10", "ChAdmin10", "admin");
        long managerId1  = insertManager("Alice", "Acme", "Manager");
        long managerId2  = insertManager("Bob",   "Globex", "Lead");
        long entryId     = insertCareerEntry(managerId1, "Acme", "Manager", "2020");

        JsonObject result = await(service.adminDeleteCareerEntry(adminAuth, managerId2, entryId));
        assertEquals(0, result.getInteger("deleted"), "must not delete entries belonging to other managers");

        // Confirm entry is still there
        long count = await(pool
            .preparedQuery("SELECT COUNT(*) FROM career_history WHERE id = $1")
            .execute(Tuple.of(entryId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, count, "entry must still exist after incorrect managerId delete");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // careerHistory id exposed in manager response
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getManagerById_careerHistoryIncludesId() throws Exception {
        long managerId = insertManager("Alice", "Acme", "Manager");
        long entryId   = insertCareerEntry(managerId, "Acme", "Manager", "2020");

        Row managerRow = await(pool
            .preparedQuery(ManagerRepository.GET_BY_ID_SQL)
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));

        JsonArray history = managerRow.getJsonArray("career_history");
        assertNotNull(history, "career_history must not be null");
        assertEquals(1, history.size());
        JsonObject entry = history.getJsonObject(0);
        assertEquals(entryId, entry.getLong("id"), "career_history entry must expose id");
        assertEquals("Acme",    entry.getString("company"));
        assertEquals("Manager", entry.getString("title"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private String insertUser(String auth0Id, String username, String role) throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id,email,username,first_name,last_name,role) VALUES ($1,$2,$3,$4,$5,$6)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User", role)));
        return auth0Id;
    }

    @Test
    void update_movingCompanyRepointsTheForeignKey() throws Exception {
        // The bug this guards. The edit wrote the new company NAME and left company_id pointing at
        // the old company, so the row said one thing and referenced another. Every query deciding
        // which managers appear on a company page matches the id, not the text - so the manager
        // stayed listed under the old company with nothing on screen explaining why, and editing
        // them again did nothing either.
        String adminAuth = insertUser("auth0|ch-admin-fk", "ChAdminFk", "admin");
        long oldCo     = insertCompany("Loblaw Companies Limited");
        long newCo     = insertCompany("Zehrs Markets");
        long managerId = insertManager("Danielle", "Loblaw Companies Limited", "Assistant Store Manager");
        long entryId   = insertCareerEntry(managerId, "Loblaw Companies Limited", "Assistant Store Manager", "2020");
        await(pool.preparedQuery("UPDATE career_history SET company_id = $1 WHERE id = $2")
            .execute(Tuple.of(oldCo, entryId)).mapEmpty());

        await(service.adminUpdateCareerEntry(
            adminAuth, managerId, entryId, "Zehrs Markets", "Assistant Store Manager", "2020", null));

        Row row = await(pool.preparedQuery("SELECT company, company_id FROM career_history WHERE id = $1")
            .execute(Tuple.of(entryId)).map(rs -> rs.iterator().next()));
        assertEquals("Zehrs Markets", row.getString("company"));
        assertNotEquals(oldCo, row.getLong("company_id"), "the entry no longer points at the old company");
        assertEquals(newCo, row.getLong("company_id"),
            "the id follows the name, or the manager keeps appearing under the old company");
    }
    private long insertCompany(String name) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies(name,status,slug) VALUES ($1,'approved',$2) RETURNING id")
            .execute(Tuple.of(name, name.toLowerCase().replaceAll("[^a-z0-9]+", "-")))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertManager(String name, String company, String title) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,
                                     overall_rating,reviews_count,category_averages)
                VALUES ($1,$2,$3,'img','active','approved',0,0,'{}') RETURNING id
                """)
            .execute(Tuple.of(name, company, title))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertCareerEntry(long managerId, String company, String title, String startYear) throws Exception {
        java.time.OffsetDateTime startDt = java.time.OffsetDateTime.parse(startYear + "-01-01T00:00:00Z");
        return await(pool.preparedQuery("""
                INSERT INTO career_history(manager_id, company, title, start_date)
                VALUES ($1, $2, $3, $4) RETURNING id
                """)
            .execute(Tuple.of(managerId, company, title, startDt))
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
