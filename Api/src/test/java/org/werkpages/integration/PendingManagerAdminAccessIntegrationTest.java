package org.werkpages.integration;

import io.vertx.core.Future;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.ManagerService;
import org.werkpages.service.ServiceException;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An admin can open a pending submission's profile. Nobody else can.
 *
 * <p><b>The moderation dead end this closes.</b> Every admin control for a submission lives on the
 * manager's profile page: approve, reject, edit the manager, and the whole career-history and
 * career-stage toolset. Those endpoints are gated by {@code requireAdmin} and worked throughout.
 * The page that hosts them did not: {@code getManagerById} sent a {@code pending_approval} row
 * through {@code enforceSubmitterAccess}, which compared the caller against
 * {@code submitted_by} and had no admin arm at all. So an admin was asked to decide on
 * submissions they could not look at, and the only way to reach the controls was to build the
 * requests by hand.
 *
 * <p><b>Why these four cases and not one.</b> The fix must open exactly one door. A test that only
 * asserted the admin gets in would pass just as well if the row had been made public, which is the
 * failure that actually matters here, so the other three cases are the test: a stranger with a
 * perfectly good account, a visitor with no account, and a banned admin are each refused, and
 * refused with 404 rather than 403, because "forbidden" would confirm that a manager by that name
 * had been submitted - the one fact withholding a pending row exists to protect.
 */
@Testcontainers
class PendingManagerAdminAccessIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("pending_admin_test")
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

        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword()), new PoolOptions().setMaxSize(5));

        service = new ManagerService(
            new ManagerRepository(pool), new ReviewRepository(pool), new UserRepository(pool),
            new EditRepository(pool), new ReportRepository(pool), pool);
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, companies, users CASCADE").execute());
    }

    @Test
    void anAdminCanOpenAPendingSubmission() throws Exception {
        String submitter = insertUser("auth0|pending-submitter", "pendingsubmitter", "user");
        String admin     = insertUser("auth0|pending-admin",     "pendingadmin",     "admin");
        long managerId   = insertPendingManager("Priya Pending", "PendCo", userId(submitter));

        Row seen = await(service.getManagerById(managerId, admin));

        assertEquals("Priya Pending", seen.getString("name"));
        assertEquals("pending_approval", seen.getString("approval_status"),
            "and it is served as what it is, not quietly presented as approved");
    }

    /** The submitter's own access, which already worked and must keep working. */
    @Test
    void theSubmitterStillSeesTheirOwnSubmission() throws Exception {
        String submitter = insertUser("auth0|pending-owner", "pendingowner", "user");
        long managerId   = insertPendingManager("Owen Owner", "OwnCo", userId(submitter));

        assertEquals("Owen Owner", await(service.getManagerById(managerId, submitter)).getString("name"));
    }

    @Test
    void aSignedInStrangerIsStillRefused() throws Exception {
        String submitter = insertUser("auth0|pending-sub2", "pendingsub2", "user");
        String stranger  = insertUser("auth0|pending-stranger", "pendingstranger", "user");
        long managerId   = insertPendingManager("Nina Nosy", "NosyCo", userId(submitter));

        assertEquals("Manager not found", notFoundMessage(managerId, stranger),
            "a stranger with a perfectly good account must get the same answer an unused id gives");
    }

    @Test
    void anAnonymousVisitorIsStillRefused() throws Exception {
        String submitter = insertUser("auth0|pending-sub3", "pendingsub3", "user");
        long managerId   = insertPendingManager("Anon Target", "AnonCo", userId(submitter));

        assertEquals("Manager not found", notFoundMessage(managerId, null),
            "the public must not be able to see a submission under review");
    }

    /**
     * A banned admin is refused.
     *
     * <p>A ban is a removal of trust, and the role column outlives it. Without this check the
     * profile page would be the one admin surface in the product a banned account could still
     * reach, which is worse than the dead end being fixed.
     */
    @Test
    void aBannedAdminIsRefused() throws Exception {
        String submitter = insertUser("auth0|pending-sub4", "pendingsub4", "user");
        String admin     = insertUser("auth0|pending-banned-admin", "pendingbannedadmin", "admin");
        long managerId   = insertPendingManager("Barred Boss", "BarCo", userId(submitter));

        await(pool.preparedQuery("INSERT INTO banned_users(user_id, reason, banned_by) VALUES ($1, 'test', 'test-suite')")
            .execute(Tuple.of(userId(admin))).mapEmpty());

        assertEquals("Manager not found", notFoundMessage(managerId, admin));
    }

    /** A rejected row stays invisible to everybody, admin included - that rule is unchanged. */
    @Test
    void aRejectedManagerIsStillInvisibleEvenToAnAdmin() throws Exception {
        String submitter = insertUser("auth0|pending-sub5", "pendingsub5", "user");
        String admin     = insertUser("auth0|pending-admin5", "pendingadmin5", "admin");
        long managerId   = insertPendingManager("Rhea Rejected", "RejCo", userId(submitter));
        await(pool.preparedQuery("UPDATE managers SET approval_status = 'rejected' WHERE id = $1")
            .execute(Tuple.of(managerId)).mapEmpty());

        assertEquals("Manager not found", notFoundMessage(managerId, admin),
            "rejected is a decision already taken, and this fix deliberately did not widen to it");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** The message the service refuses with, so the test asserts the answer and not just failure. */
    private String notFoundMessage(long managerId, String auth0Id) {
        ExecutionException thrown = assertThrows(ExecutionException.class,
            () -> service.getManagerById(managerId, auth0Id)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
        Throwable cause = thrown.getCause();
        assertTrue(cause instanceof ServiceException, "expected a ServiceException, got " + cause);
        assertEquals(404, ((ServiceException) cause).getStatusCode(),
            "404 rather than 403: a 'forbidden' would confirm the submission exists");
        return cause.getMessage();
    }

    private long insertPendingManager(String name, String company, UUID submittedBy) throws Exception {
        long companyId = await(pool.preparedQuery(
                "INSERT INTO companies(name,status,slug) VALUES ($1,'ghost',$2) RETURNING id")
            .execute(Tuple.of(company, company.toLowerCase()))
            .map(rs -> rs.iterator().next().getLong("id")));
        return await(pool.preparedQuery(
                "INSERT INTO managers(name, company, company_id, title, image, status, "
              + "approval_status, submitted_by, category_averages) "
              + "VALUES ($1,$2,$3,'VP','img','active','pending_approval',$4,'{}') RETURNING id")
            .execute(Tuple.of(name, company, companyId, submittedBy))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private String insertUser(String auth0Id, String username, String role) throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO users(auth0_id, email, username, first_name, last_name, role) "
              + "VALUES ($1,$2,$3,'Test','User',$4)")
            .execute(Tuple.of(auth0Id, auth0Id + "@test.com", username, role)).mapEmpty());
        return auth0Id;
    }

    private UUID userId(String auth0Id) throws Exception {
        return await(pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
