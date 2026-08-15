package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.werkpages.repository.ManagerRepository;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the approval-status filter rules defined in CLAUDE.md:
 *
 *   search()          → approved, ghost only
 *   count()           → approved, ghost only
 *   countApproved()   → approved, ghost only
 *   findAllCompanies()→ approved, ghost only
 *   findSimilar()     → approved, ghost only
 *   findByNameAndCompany() → approved, ghost only
 *   findPendingByUser()    → pending_approval + rejected only
 *   findPendingForAdmin()  → pending_approval only
 *
 * If any of these start returning wrong statuses it means the public directory
 * will expose draft or rejected managers, or the admin queue will see approved ones.
 */
@Testcontainers
class ApprovalStatusFilterIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool              pool;
    static ManagerRepository repo;

    // IDs of the five seeded managers (one per status)
    static long approvedId;
    static long ghostId;
    static long pendingId;
    static long rejectedId;
    static UUID submittingUserId;

    @BeforeAll
    static void setUpAll() throws Exception {
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

        pool = PgPool.pool(opts, new PoolOptions().setMaxSize(5));
        repo = new ManagerRepository(pool);

        // One user who submitted the pending/rejected managers
        submittingUserId = UUID.randomUUID();
        await(pool.preparedQuery("INSERT INTO users(id,auth0_id,email,username,first_name,last_name) VALUES ($1,$2,$3,$4,$5,$6)")
            .execute(Tuple.of(submittingUserId, "auth0|filter-test", "filter@test.com", "FilterUser", "Filter", "User")));

        seedManagers();
    }

    static void seedManagers() throws Exception {
        approvedId = insertManager("Alex Approved", "FilterCorp", "approved",    null);
        ghostId    = insertManager("Ghost Manager", "FilterCorp", "ghost",       null);
        pendingId  = insertManager("Pending Review", "FilterCorp", "pending_approval", submittingUserId);
        rejectedId = insertManager("Rejected Person", "FilterCorp", "rejected",  submittingUserId);
    }

    @BeforeEach
    void noop() {
        // Data is seeded once in @BeforeAll; no truncation between tests.
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── search() ─────────────────────────────────────────────────────────────

    @Test
    void search_returnsApproved() throws Exception {
        List<Long> ids = searchIds(null);
        assertTrue(ids.contains(approvedId), "search() must include approved managers");
    }

    @Test
    void search_returnsGhost() throws Exception {
        List<Long> ids = searchIds(null);
        assertTrue(ids.contains(ghostId), "search() must include ghost managers");
    }

    @Test
    void search_excludesPending() throws Exception {
        List<Long> ids = searchIds(null);
        assertFalse(ids.contains(pendingId), "search() must NOT include pending_approval managers");
    }

    @Test
    void search_excludesRejected() throws Exception {
        List<Long> ids = searchIds(null);
        assertFalse(ids.contains(rejectedId), "search() must NOT include rejected managers");
    }

    @Test
    void search_withQuery_excludesPending() throws Exception {
        List<Long> ids = searchIdsWithQuery("Pending Review");
        assertFalse(ids.contains(pendingId));
    }

    @Test
    void search_withQuery_returnsApproved() throws Exception {
        List<Long> ids = searchIdsWithQuery("Alex Approved");
        assertTrue(ids.contains(approvedId));
    }

    // ── count() ───────────────────────────────────────────────────────────────

    @Test
    void count_includesApprovedAndGhost() throws Exception {
        long count = await(repo.count(null, null));
        // Must be ≥ 2 (approved + ghost from our seed)
        assertTrue(count >= 2, "count() must include approved and ghost: got " + count);
    }

    @Test
    void count_doesNotIncludePendingOrRejected() throws Exception {
        // Insert a manager with a unique company so we can count precisely
        String uniqueCompany = "UniqueCountCo_" + UUID.randomUUID();
        insertManager("Count Pending", uniqueCompany, "pending_approval", submittingUserId);
        insertManager("Count Ghost",   uniqueCompany, "ghost",            null);

        long countAll     = await(repo.count(null, uniqueCompany));
        long countPattern = await(repo.count(null, uniqueCompany));

        // Only the ghost should be counted, not the pending
        assertEquals(1L, countAll, "count() with company filter should find 1 (ghost only), not the pending");
    }

    // ── countApproved() ──────────────────────────────────────────────────────

    @Test
    void countApproved_includesGhost() throws Exception {
        String uniqueCompany = "UniqueCountApproved_" + UUID.randomUUID();
        insertManager("Count Ghost2", uniqueCompany, "ghost", null);

        // Can't isolate this precisely without a company filter; just verify the ghost contributes
        long before = await(repo.countApproved());
        insertManager("Count Ghost3", uniqueCompany + "B", "ghost", null);
        long after = await(repo.countApproved());

        assertEquals(before + 1, after, "countApproved() must include ghost managers");
    }

    @Test
    void countApproved_doesNotIncrementForPending() throws Exception {
        long before = await(repo.countApproved());
        insertManager("Extra Pending", "SomeCompany_" + UUID.randomUUID(), "pending_approval", submittingUserId);
        long after = await(repo.countApproved());

        assertEquals(before, after, "countApproved() must not count pending_approval managers");
    }

    // ── findSimilar() ─────────────────────────────────────────────────────────

    @Test
    void findSimilar_returnsApproved() throws Exception {
        RowSet<Row> rows = await(repo.findSimilar("Alex Approved", "FilterCorp"));
        List<Long> ids = toIds(rows);
        assertTrue(ids.contains(approvedId));
    }

    @Test
    void findSimilar_returnsGhost() throws Exception {
        RowSet<Row> rows = await(repo.findSimilar("Ghost Manager", "FilterCorp"));
        List<Long> ids = toIds(rows);
        assertTrue(ids.contains(ghostId));
    }

    @Test
    void findSimilar_excludesPending() throws Exception {
        RowSet<Row> rows = await(repo.findSimilar("Pending Review", "FilterCorp"));
        List<Long> ids = toIds(rows);
        assertFalse(ids.contains(pendingId), "findSimilar() must not show pending managers");
    }

    @Test
    void findSimilar_excludesRejected() throws Exception {
        RowSet<Row> rows = await(repo.findSimilar("Rejected Person", "FilterCorp"));
        List<Long> ids = toIds(rows);
        assertFalse(ids.contains(rejectedId), "findSimilar() must not show rejected managers");
    }

    // ── findPendingByUser() ───────────────────────────────────────────────────

    @Test
    void findPendingByUser_includesPending() throws Exception {
        RowSet<Row> rows = await(repo.findPendingByUser(submittingUserId));
        List<Long> ids = toIds(rows);
        assertTrue(ids.contains(pendingId), "findPendingByUser() must include pending_approval managers");
    }

    @Test
    void findPendingByUser_includesRejected() throws Exception {
        RowSet<Row> rows = await(repo.findPendingByUser(submittingUserId));
        List<Long> ids = toIds(rows);
        assertTrue(ids.contains(rejectedId), "findPendingByUser() must include rejected managers");
    }

    @Test
    void findPendingByUser_excludesApproved() throws Exception {
        // Insert an approved manager by the same user so we have something to check
        long approvedByUser = insertManagerByUser("User Approved", "UserCorp", "approved", submittingUserId);
        RowSet<Row> rows = await(repo.findPendingByUser(submittingUserId));
        List<Long> ids = toIds(rows);
        assertFalse(ids.contains(approvedByUser),
            "findPendingByUser() must NOT include approved managers");
    }

    @Test
    void findPendingByUser_excludesGhost() throws Exception {
        long ghostByUser = insertManagerByUser("User Ghost", "UserCorp", "ghost", submittingUserId);
        RowSet<Row> rows = await(repo.findPendingByUser(submittingUserId));
        List<Long> ids = toIds(rows);
        assertFalse(ids.contains(ghostByUser),
            "findPendingByUser() must NOT include ghost managers");
    }

    @Test
    void findPendingByUser_onlyReturnsOwnManagers() throws Exception {
        UUID otherUser = UUID.randomUUID();
        await(pool.preparedQuery("INSERT INTO users(id,auth0_id,email,username,first_name,last_name) VALUES ($1,$2,$3,$4,$5,$6)")
            .execute(Tuple.of(otherUser, "auth0|other", "other@test.com", "OtherUser", "Other", "User")));
        long otherPending = insertManagerByUser("Other Pending", "OtherCorp", "pending_approval", otherUser);

        RowSet<Row> rows = await(repo.findPendingByUser(submittingUserId));
        List<Long> ids = toIds(rows);
        assertFalse(ids.contains(otherPending),
            "findPendingByUser() must not return another user's managers");
    }

    // ── findPendingForAdmin() ─────────────────────────────────────────────────

    @Test
    void findPendingForAdmin_includesPending() throws Exception {
        RowSet<Row> rows = await(repo.findPendingForAdmin(100, 0));
        List<Long> ids = toIds(rows);
        assertTrue(ids.contains(pendingId), "findPendingForAdmin() must include pending_approval managers");
    }

    @Test
    void findPendingForAdmin_excludesApproved() throws Exception {
        RowSet<Row> rows = await(repo.findPendingForAdmin(100, 0));
        List<Long> ids = toIds(rows);
        assertFalse(ids.contains(approvedId), "findPendingForAdmin() must NOT include approved managers");
    }

    @Test
    void findPendingForAdmin_excludesGhost() throws Exception {
        RowSet<Row> rows = await(repo.findPendingForAdmin(100, 0));
        List<Long> ids = toIds(rows);
        assertFalse(ids.contains(ghostId), "findPendingForAdmin() must NOT include ghost managers");
    }

    @Test
    void findPendingForAdmin_excludesRejected() throws Exception {
        RowSet<Row> rows = await(repo.findPendingForAdmin(100, 0));
        List<Long> ids = toIds(rows);
        assertFalse(ids.contains(rejectedId), "findPendingForAdmin() must NOT include rejected managers");
    }

    // ── findByNameAndCompany() ────────────────────────────────────────────────

    @Test
    void findByNameAndCompany_returnsApproved() throws Exception {
        RowSet<Row> rows = await(repo.findByNameAndCompany("Alex Approved", "FilterCorp"));
        List<Long> ids = toIds(rows);
        assertTrue(ids.contains(approvedId));
    }

    @Test
    void findByNameAndCompany_returnsGhost() throws Exception {
        RowSet<Row> rows = await(repo.findByNameAndCompany("Ghost Manager", "FilterCorp"));
        List<Long> ids = toIds(rows);
        assertTrue(ids.contains(ghostId));
    }

    @Test
    void findByNameAndCompany_excludesPending() throws Exception {
        RowSet<Row> rows = await(repo.findByNameAndCompany("Pending Review", "FilterCorp"));
        List<Long> ids = toIds(rows);
        assertFalse(ids.contains(pendingId), "findByNameAndCompany() must not find pending managers");
    }

    @Test
    void findByNameAndCompany_excludesRejected() throws Exception {
        RowSet<Row> rows = await(repo.findByNameAndCompany("Rejected Person", "FilterCorp"));
        List<Long> ids = toIds(rows);
        assertFalse(ids.contains(rejectedId), "findByNameAndCompany() must not find rejected managers");
    }

    // ── findAllCompanies() ───────────────────────────────────────────────────

    @Test
    void findAllCompanies_includesCompanyWithApprovedManager() throws Exception {
        String uniqueCompany = "ApprovedOnlyCo_" + UUID.randomUUID();
        insertManager("Some Name", uniqueCompany, "approved", null);
        RowSet<Row> rows = await(repo.findAllCompanies());
        List<String> companies = toStrings(rows, "company");
        assertTrue(companies.contains(uniqueCompany));
    }

    @Test
    void findAllCompanies_includesCompanyWithGhostManager() throws Exception {
        String uniqueCompany = "GhostOnlyCo_" + UUID.randomUUID();
        insertManager("Ghost Name", uniqueCompany, "ghost", null);
        RowSet<Row> rows = await(repo.findAllCompanies());
        List<String> companies = toStrings(rows, "company");
        assertTrue(companies.contains(uniqueCompany));
    }

    @Test
    void findAllCompanies_excludesCompanyWithOnlyPendingManagers() throws Exception {
        String uniqueCompany = "PendingOnlyCo_" + UUID.randomUUID();
        insertManager("Pending Only", uniqueCompany, "pending_approval", submittingUserId);
        RowSet<Row> rows = await(repo.findAllCompanies());
        List<String> companies = toStrings(rows, "company");
        assertFalse(companies.contains(uniqueCompany),
            "findAllCompanies() must not include companies that only have pending managers");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<Long> searchIds(String unused) throws Exception {
        // Use the public (unauthenticated) search — same approved/ghost filter
        RowSet<Row> rows = await(repo.search(100, 0, null, null, null));
        return toIds(rows);
    }

    private List<Long> searchIdsWithQuery(String query) throws Exception {
        RowSet<Row> rows = await(repo.search(100, 0, query, null, null));
        return toIds(rows);
    }

    private List<Long> toIds(RowSet<Row> rows) {
        List<Long> ids = new ArrayList<>();
        for (Row row : rows) ids.add(row.getLong("id"));
        return ids;
    }

    private List<String> toStrings(RowSet<Row> rows, String column) {
        List<String> vals = new ArrayList<>();
        for (Row row : rows) vals.add(row.getString(column));
        return vals;
    }

    private static long insertManager(String name, String company, String status, UUID submittedBy) throws Exception {
        if (submittedBy != null) {
            return await(pool.preparedQuery(
                "INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages,submitted_by) " +
                "VALUES ($1,$2,'Title','img','active',$3,null,0,null,$4) RETURNING id")
                .execute(Tuple.of(name, company, status, submittedBy))
                .map(rs -> rs.iterator().next().getLong("id")));
        }
        return await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages) " +
            "VALUES ($1,$2,'Title','img','active',$3,null,0,null) RETURNING id")
            .execute(Tuple.of(name, company, status))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static long insertManagerByUser(String name, String company, String status, UUID userId) throws Exception {
        return insertManager(name, company, status, userId);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
