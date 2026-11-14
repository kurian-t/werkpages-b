package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V98 applied the way production will apply it: to a database that already holds managers.
 *
 * <h2>Why this is separate from every other integration test</h2>
 *
 * <p>Every other test in this suite migrates a brand-new container from V1 to the head, which
 * proves V98 runs on a CLEAN database and nothing else. Production is not a clean database. The
 * question that matters there is whether a schema with rows in it survives the upgrade, and the
 * only way to ask it is to migrate to V97, write the rows, and then migrate again.
 *
 * <p>The specific risks, from CLAUDE.md section 10, since {@code managers} is a shared table and
 * RateMyManagers reads and writes it with no knowledge of this column:
 *
 * <ul>
 *   <li>a NOT NULL column with no default would break every INSERT in both backends that omits
 *       it - which is all of them today;</li>
 *   <li>a plain UNIQUE index would be wrong for a column that is null on every existing row (it
 *       happens to be allowed in PostgreSQL, which is exactly why it is worth pinning rather than
 *       assuming);</li>
 *   <li>a foreign key is only safe because every existing value is null and the new code writes
 *       the referenced row first, in the same transaction.</li>
 * </ul>
 *
 * <p>Werkpages deploys the migration before any RateMyManagers code depends on it, and this is the
 * test that says the intervening state is a working one.
 */
@Testcontainers
class CaptureAttemptMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_upgrade_test")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static long companyBefore;
    static long approvedBefore;
    static long pendingDraftBefore;

    @BeforeAll
    static void upgradeTheWayProductionWill() throws Exception {
        // 1. The schema as it stands today, with V98 deliberately withheld.
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            // V96, the version production has. See Phase2UpgradeIntegrationTest.
            .target(MigrationVersion.fromVersion("96"))
            .load()
            .migrate();

        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(3));

        assertFalse(columnExists("managers", "capture_token"),
            "fixture: V98 must not have run yet, or this test is checking nothing");
        assertFalse(tableExists("capture_attempts"), "fixture: the ledger does not exist yet");

        // 2. Rows of the shapes production holds, written by the pre-V98 statements.
        companyBefore = await(pool.preparedQuery(
                "INSERT INTO companies (name, slug, status) VALUES ($1,$2,'ghost') RETURNING id")
            .execute(Tuple.of("Ashgrove Health", "ashgrove-health"))
            .map(rs -> rs.iterator().next().getLong("id")));

        approvedBefore = insertManagerTheOldWay("Priya Raghunathan", "approved", "priya-raghunathan");
        pendingDraftBefore = insertManagerTheOldWay("Dana Whitfield", "pending_approval", "dana-whitfield-pending");

        // 3. The upgrade.
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load()
            .migrate();
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void theUpgradeAddsTheLedgerAndTheColumn() throws Exception {
        assertTrue(tableExists("capture_attempts"), "the ledger exists after V98");
        assertTrue(columnExists("managers", "capture_token"), "and so does the reverse pointer");
        assertEquals(0, countAttempts(), "and the ledger starts empty: nothing is backfilled, "
            + "because no attempt was ever recorded for a row that predates it");
    }

    @Test
    void existingManagersSurviveUntouchedAndWithNoToken() throws Exception {
        assertEquals("approved", statusOf(approvedBefore), "a published manager is unchanged");
        assertEquals("pending_approval", statusOf(pendingDraftBefore));
        assertNull(tokenOf(approvedBefore),
            "every row that predates the ledger carries no attempt, and must not be given one");
        assertNull(tokenOf(pendingDraftBefore),
            "including the pending drafts, which are the rows adoption looks at: a submission "
            + "must not be able to adopt one of them, and a null token is what prevents it");
    }

    @Test
    void theColumnIsNullableSoEveryExistingInsertStillWorks() throws Exception {
        /*
         * The compatibility assertion that matters for the OTHER backend. RateMyManagers writes
         * managers through its own statements, which name no capture_token and never will until
         * its own Phase 1 lands. A NOT NULL column with no default would have broken every one of
         * them the moment Werkpages migrated.
         */
        long after = insertManagerTheOldWay("Fenella Oyelaran", "approved", "fenella-oyelaran");
        assertNull(tokenOf(after), "an INSERT that omits the column is accepted and leaves it null");
    }

    @Test
    void manyRowsMayShareTheAbsenceOfAToken() throws Exception {
        /*
         * The uniqueness is on attempts, not on rows. Every pre-existing manager has a null token
         * and they are not duplicates of one another, which is why the index is partial.
         */
        insertManagerTheOldWay("Marcus Whitlow", "ghost", "marcus-whitlow");
        insertManagerTheOldWay("Marcus Whitlow", "ghost", "marcus-whitlow-2");

        assertEquals(2, countManagersNamed("Marcus Whitlow"),
            "two token-less managers coexist; only two managers claiming ONE attempt would not");
    }

    @Test
    void oneAttemptCannotProduceTwoManagers() throws Exception {
        UUID token = UUID.randomUUID();
        await(pool.preparedQuery("INSERT INTO capture_attempts (token) VALUES ($1)")
            .execute(Tuple.of(token)).mapEmpty());

        long first = insertManagerWithToken("Ingrid Solberg", "ingrid-solberg-pending", token);
        assertEquals(token, tokenOf(first), "the first manager carries the attempt");

        Exception refused = assertThrows(Exception.class,
            () -> insertManagerWithToken("Ingrid Solberg", "ingrid-solberg-pending-2", token),
            "a second manager for one attempt is refused by the database, not merely avoided by "
            + "the application that happens to write it");
        assertTrue(String.valueOf(refused.getMessage()).contains("managers_capture_token"),
            "and refused by the index that says so: " + refused.getMessage());
    }

    @Test
    void anAttemptCannotNameAManagerThatDoesNotExist() throws Exception {
        assertThrows(Exception.class, () -> await(pool.preparedQuery(
                "INSERT INTO capture_attempts (token, manager_id) VALUES ($1, 9999999)")
            .execute(Tuple.of(UUID.randomUUID())).mapEmpty()),
            "the ledger's pointer is a foreign key, so it cannot describe a manager we do not hold");
    }

    @Test
    void deletingAManagerKeepsTheRecordThatItsAttemptHappened() throws Exception {
        /*
         * ON DELETE SET NULL rather than CASCADE, and the difference is the whole point: the
         * attempt stays spent after its manager is gone, so a replay of its token recreates
         * nothing. That is the resurrection the ledger exists to prevent.
         */
        UUID token = UUID.randomUUID();
        await(pool.preparedQuery("INSERT INTO capture_attempts (token) VALUES ($1)")
            .execute(Tuple.of(token)).mapEmpty());
        long manager = insertManagerWithToken("Tomas Feld", "tomas-feld-pending", token);
        await(pool.preparedQuery("UPDATE capture_attempts SET manager_id = $2 WHERE token = $1")
            .execute(Tuple.of(token, manager)).mapEmpty());

        await(pool.preparedQuery("DELETE FROM managers WHERE id = $1").execute(Tuple.of(manager)).mapEmpty());

        Row attempt = await(pool.preparedQuery(
                "SELECT token, manager_id FROM capture_attempts WHERE token = $1")
            .execute(Tuple.of(token)).map(rs -> rs.iterator().next()));
        assertEquals(token, attempt.getUUID("token"), "the attempt is still on record");
        assertNull(attempt.getLong("manager_id"), "with its pointer cleared rather than deleted");
    }

    @Test
    void anOutcomeMustBeOneWeRecogniseAndMustTravelWithItsTimestamp() throws Exception {
        UUID token = UUID.randomUUID();
        await(pool.preparedQuery("INSERT INTO capture_attempts (token) VALUES ($1)")
            .execute(Tuple.of(token)).mapEmpty());

        assertThrows(Exception.class, () -> await(pool.preparedQuery(
                "UPDATE capture_attempts SET resolved_at = now(), outcome = 'something_else' WHERE token = $1")
            .execute(Tuple.of(token)).mapEmpty()), "capture_outcome_known");

        assertThrows(Exception.class, () -> await(pool.preparedQuery(
                "UPDATE capture_attempts SET outcome = 'submitted' WHERE token = $1")
            .execute(Tuple.of(token)).mapEmpty()),
            "capture_resolved_pairs: an outcome without the moment it happened is half a record");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Exactly the column list the pre-V98 code writes: no capture_token anywhere. */
    private static long insertManagerTheOldWay(String name, String approvalStatus, String slug) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO managers
                (name, company, title, status, approval_status, country, company_id, slug,
                 overall_rating, reviews_count, category_averages)
                VALUES ($1,'Ashgrove Health','Manager','active',$2,'US',$3,$4,0,0,'{}'::jsonb)
                RETURNING id
                """)
            .execute(Tuple.of(name, approvalStatus, companyBefore, slug))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static long insertManagerWithToken(String name, String slug, UUID token) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO managers
                (name, company, title, status, approval_status, country, company_id, slug,
                 overall_rating, reviews_count, category_averages, capture_token)
                VALUES ($1,'Ashgrove Health','Manager','active','pending_approval','US',$2,$3,0,0,'{}'::jsonb,$4)
                RETURNING id
                """)
            .execute(Tuple.of(name, companyBefore, slug, token))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static boolean columnExists(String table, String column) throws Exception {
        return await(pool.preparedQuery("""
                SELECT 1 FROM information_schema.columns
                 WHERE table_name = $1 AND column_name = $2
                """)
            .execute(Tuple.of(table, column)).map(rs -> rs.iterator().hasNext()));
    }

    private static boolean tableExists(String table) throws Exception {
        return await(pool.preparedQuery(
                "SELECT 1 FROM information_schema.tables WHERE table_name = $1")
            .execute(Tuple.of(table)).map(rs -> rs.iterator().hasNext()));
    }

    private static int countAttempts() throws Exception {
        return await(pool.query("SELECT COUNT(*) AS c FROM capture_attempts").execute()
            .map(rs -> rs.iterator().next().getInteger("c")));
    }

    private static String statusOf(long managerId) throws Exception {
        return await(pool.preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId)).map(rs -> rs.iterator().next().getString("approval_status")));
    }

    private static UUID tokenOf(long managerId) throws Exception {
        return await(pool.preparedQuery("SELECT capture_token FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId)).map(rs -> rs.iterator().next().getUUID("capture_token")));
    }

    private static int countManagersNamed(String name) throws Exception {
        return await(pool.preparedQuery("SELECT COUNT(*) AS c FROM managers WHERE name = $1")
            .execute(Tuple.of(name)).map(rs -> rs.iterator().next().getInteger("c")));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
