package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.*;
import io.vertx.sqlclient.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.*;
import org.werkpages.service.AdminService;
import org.werkpages.service.ManagerService;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The admin review queue, end to end.
 *
 * <p>A search request is a LEAD, not a person. An admin must be able to say which of three things
 * it is - a genuinely new manager, a job we did not know an existing manager held, or something
 * unverifiable - and the queue has to survive being searched repeatedly without filling with
 * copies.
 */
@Testcontainers
class AdminSearchRequestWorkflowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("admin_wf").withUsername("test").withPassword("test");

    static Pool pool;
    static ManagerService managerService;
    static AdminService admin;
    static ManagerSearchRequestRepository requests;

    @BeforeAll
    static void setUp() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations").load().migrate();
        pool = PgPool.pool(new PgConnectOptions().setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432)).setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername()).setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(5));
        managerService = new ManagerService(new ManagerRepository(pool), new ReviewRepository(pool),
            new UserRepository(pool), new EditRepository(pool), new ReportRepository(pool), pool);
        admin = new AdminService(new UserRepository(pool), new ManagerRepository(pool), new ReviewRepository(pool), new EditRepository(pool), new NotificationRepository(pool), new CompanyRepository(pool), null, pool);
        requests = new ManagerSearchRequestRepository(pool);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @BeforeEach
    void clean() throws Exception {
        await(pool.query("TRUNCATE managers, companies, users, career_history, reviews, manager_search_requests CASCADE").execute());
    }

    @Test
    @DisplayName("repeated searches make ONE request, counted as evidence")
    void repeatsAreDeduplicated() throws Exception {
        for (int i = 0; i < 5; i++) recordRequest("Aaron", "Yip", "Warrington", "Controller");

        RowSet<Row> all = await(pool.query("SELECT * FROM manager_search_requests").execute());
        assertEquals(1, all.size(), "five identical searches must not become five queue entries");
        assertEquals(5, all.iterator().next().getInteger("seen_count"),
            "the repeats are the evidence an admin needs to judge it");
    }

    @Test
    @DisplayName("approve as a genuinely new manager")
    void approveAsNewManager() throws Exception {
        String adminAuth = insertAdmin();
        Row req = recordRequest("Nadia", "Oyelaran", "Fernhill Dairy", "Operations Lead");

        JsonObject result = await(admin.createManagerFromRequest(adminAuth, req.getLong("id"), null));

        assertEquals("new_manager", result.getString("resolution"));
        Row m = await(pool.preparedQuery("SELECT name, company, approval_status, submitted_by FROM managers WHERE id = $1")
            .execute(Tuple.of(result.getLong("managerId")))).iterator().next();
        assertEquals("Nadia Oyelaran", m.getString("name"));
        assertEquals("approved", m.getString("approval_status"),
            "a human decided this person exists, which is stronger than an automatic ghost");
        assertNotNull(m.getValue("submitted_by"), "the deciding admin must be recorded");

        Row after = await(requests.findById(req.getLong("id")));
        assertEquals("resolved", after.getString("status"));
        assertEquals(result.getLong("managerId"), after.getLong("resolved_manager_id"),
            "the request keeps what it became, so the decision stays auditable");
    }

    @Test
    @DisplayName("attach the searched job to an EXISTING manager instead of duplicating them")
    void attachRoleToExistingManager() throws Exception {
        String adminAuth = insertAdmin();
        long aaron = seedManager("Aaron Yip", "QuadReal Property Group", "Controller");
        Row req = recordRequest("Aaron", "Yip", "Warrington PCI Management", "Divisional Controller");

        JsonObject result = await(admin.attachRequestToManager(
            adminAuth, req.getLong("id"), aaron, "2023-01", "2024-01"));

        assertEquals("existing_role", result.getString("resolution"));

        RowSet<Row> career = await(pool.preparedQuery(
                "SELECT company, title FROM career_history WHERE manager_id = $1").execute(Tuple.of(aaron)));
        assertEquals(1, career.size(), "the searched job is now part of his history");
        assertEquals("Warrington PCI Management", career.iterator().next().getString("company"));

        assertEquals(1, await(pool.query("SELECT count(*) AS n FROM managers").execute())
            .iterator().next().getLong("n"),
            "and NO second Aaron Yip was created - that is the whole point of this action");
    }

    @Test
    @DisplayName("dismiss keeps the record and counts later searches")
    void dismissRecordsRatherThanDeletes() throws Exception {
        String adminAuth = insertAdmin();
        Row req = recordRequest("Qqzz", "Xyvv", "Nowhere Ltd", "Chief Nothing");

        await(admin.dismissSearchRequest(adminAuth, req.getLong("id")));
        Row after = await(requests.findById(req.getLong("id")));
        assertEquals("dismissed", after.getString("status"));
        assertEquals("not_verifiable", after.getString("resolution"));
        assertEquals(0, after.getInteger("seen_since_review"), "the counter restarts at the decision");

        // Searched twice more after the dismissal.
        recordRequest("Qqzz", "Xyvv", "Nowhere Ltd", "Chief Nothing");
        recordRequest("Qqzz", "Xyvv", "Nowhere Ltd", "Chief Nothing");

        Row later = await(requests.findById(req.getLong("id")));
        assertEquals("dismissed", later.getString("status"),
            "a dismissal is not reopened automatically - no cooldown was invented");
        assertEquals(2, later.getInteger("seen_since_review"),
            "but the admin can see they may have been wrong, by evidence rather than by a timer");

        JsonArray recurring = await(admin.listSearchRequests(adminAuth, "dismissed", 20, 0));
        assertEquals(1, recurring.size(), "and it surfaces in the dismissed-but-recurring view");
    }

    @Test
    @DisplayName("the queue leads with the most-searched request")
    void queueOrdersByEvidence() throws Exception {
        String adminAuth = insertAdmin();
        recordRequest("Once", "Searched", "A Co", "Analyst");
        for (int i = 0; i < 9; i++) recordRequest("Often", "Searched", "B Co", "Director");

        JsonArray queue = await(admin.listSearchRequests(adminAuth, "open", 20, 0));
        assertEquals(2, queue.size());
        assertEquals("Often", queue.getJsonObject(0).getString("firstName"),
            "weight of evidence leads, not age - a manager nine people looked for matters more "
            + "than one somebody typed once");
        assertEquals(9, queue.getJsonObject(0).getInteger("seenCount"));
    }

    @Test
    @DisplayName("a request cannot be reviewed twice")
    void doubleReviewIsRefused() throws Exception {
        String adminAuth = insertAdmin();
        Row req = recordRequest("Nadia", "Oyelaran", "Fernhill Dairy", "Operations Lead");
        await(admin.createManagerFromRequest(adminAuth, req.getLong("id"), null));

        Future<JsonObject> second = admin.createManagerFromRequest(adminAuth, req.getLong("id"), null);
        assertThrows(Exception.class, () -> await(second),
            "a resolved request must not be actioned again, or one lead becomes two managers");
    }

    // ── helpers ──────────────────────────────────────────────────────────────


    @Test
    @DisplayName("two admins approving the same request at once create ONE manager")
    void concurrentAdminsApproveOnce() throws Exception {
        /*
          THE RACE A SEQUENTIAL TEST CANNOT SEE.

          The queue is a shared surface: two admins open it, see the same row and act on it within
          a second of each other. This used to READ the request, check its status was 'open' in
          Java, and only then create - so both passed the check on the same 'open' value and both
          created, publishing two managers for one person. The sequential test above never caught
          it, because by the time the second call ran the first had already written its status.

          The guard is now `AND status = 'open'` inside the UPDATE, so PostgreSQL picks the winner.
          Two things are asserted: exactly one manager exists, and the admin who lost is TOLD so
          rather than silently receiving somebody else's result.
        */
        String adminA = insertAdmin("auth0|admin-race-a", "racea");
        String adminB = insertAdmin("auth0|admin-race-b", "raceb");
        Row req = recordRequest("Imogen", "Castellanos", "Harbourline Freight", "Depot Manager");
        long requestId = req.getLong("id");

        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ConcurrentLinkedQueue<String> failures = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.atomic.AtomicInteger successes = new java.util.concurrent.atomic.AtomicInteger();

        java.util.List<Thread> threads = new java.util.ArrayList<>();
        for (String auth : java.util.List.of(adminA, adminB)) {
            Thread t = new Thread(() -> {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    await(admin.createManagerFromRequest(auth, requestId, null));
                    successes.incrementAndGet();
                } catch (Throwable e) {
                    failures.add(String.valueOf(e.getMessage()));
                }
            });
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        go.countDown();
        for (Thread t : threads) t.join(60_000);

        long managers = await(pool.query("SELECT count(*) AS n FROM managers").execute())
            .iterator().next().getLong("n");
        assertEquals(1, managers,
            "one request is one person; two admins acting together must not publish two");
        assertEquals(1, successes.get(), "exactly one admin's decision can take effect");
        assertEquals(1, failures.size(), "and the other must be told, not silently ignored");
        assertTrue(String.valueOf(failures.peek()).contains("already reviewed"),
            "the loser needs to know somebody else decided it, not see a generic failure: "
            + failures.peek());

        Row after = await(requests.findById(requestId));
        assertEquals("resolved", after.getString("status"));
        assertNotNull(after.getLong("resolved_manager_id"),
            "the surviving decision still records what it produced");
    }

    private static Row recordRequest(String first, String last, String company, String title) throws Exception {
        return await(requests.record(first, last, company, title,
            org.werkpages.service.ManagerMatcher.norm(first + " " + last),
            org.werkpages.service.ManagerMatcher.normCompany(company),
            org.werkpages.service.ManagerMatcher.normTitle(title),
            null, java.util.List.of(), "company_differs", "CONFIRMED", "DIFFERENT", "DIFFERENT"));
    }

    /** A second admin, so two of them can act on one request at the same time. */
    private static String insertAdmin(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO users(auth0_id, username, email, role) VALUES ($1,$2,$3,'admin')")
            .execute(Tuple.of(auth0Id, username, username + "@example.com")));
        return auth0Id;
    }

    private static String insertAdmin() throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO users(auth0_id, username, email, role) VALUES ($1,$2,$3,'admin')")
            .execute(Tuple.of("auth0|admin-wf", "adminwf", "adminwf@example.com")));
        return "auth0|admin-wf";
    }

    private static long seedManager(String name, String company, String title) throws Exception {
        Row c = await(pool.preparedQuery(
                "INSERT INTO companies (name, status) VALUES ($1,'ghost') ON CONFLICT ((LOWER(TRIM(name)))) DO UPDATE SET updated_at = now() RETURNING id")
            .execute(Tuple.of(company))).iterator().next();
        return await(pool.preparedQuery("""
                INSERT INTO managers (name, company, company_id, title, status, approval_status, country, slug, created_at, updated_at)
                VALUES ($1,$2,$3,$4,'active','ghost','Canada',lower(regexp_replace($1,'[^a-zA-Z0-9]+','-','g')),now(),now())
                RETURNING id
                """).execute(Tuple.of(name, company, c.getLong("id"), title))).iterator().next().getLong("id");
    }

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
