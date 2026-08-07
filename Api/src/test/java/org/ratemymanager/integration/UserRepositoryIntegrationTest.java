package org.ratemymanager.integration;

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
import org.ratemymanager.repository.UserRepository;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class UserRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("ratemymanager_test")
        .withUsername("test")
        .withPassword("test");

    static Pool           pool;
    static UserRepository repo;

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

        pool = PgPool.pool(opts, new PoolOptions().setMaxSize(5));
        repo = new UserRepository(pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE users CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_persistsUser() throws Exception {
        await(repo.create("auth0|new-1", "user@test.com", "NewUser1", "New", "User"));

        Optional<Row> opt = await(repo.findByAuth0IdWithBan("auth0|new-1"));
        assertTrue(opt.isPresent());
        Row row = opt.get();
        assertEquals("NewUser1", row.getString("username"));
        assertFalse(row.getBoolean("is_banned"));

        // findByAuth0IdWithBan doesn't SELECT has_auto_created_manager; query directly
        Row userRow = await(pool
            .preparedQuery("SELECT has_auto_created_manager FROM users WHERE auth0_id = $1")
            .execute(Tuple.of("auth0|new-1"))
            .map(rs -> rs.iterator().next()));
        assertFalse(userRow.getBoolean("has_auto_created_manager"));
    }

    @Test
    void create_unknownAuth0Id_returnsEmpty() throws Exception {
        Optional<Row> opt = await(repo.findByAuth0IdWithBan("auth0|does-not-exist"));
        assertTrue(opt.isEmpty());
    }

    // ── findByAuth0IdForSignin ────────────────────────────────────────────────

    @Test
    void findByAuth0IdForSignin_returnsUser() throws Exception {
        await(repo.create("auth0|signin-1", "signin@test.com", "SigninUser1", "Sign", "In"));
        Optional<Row> opt = await(repo.findByAuth0IdForSignin("auth0|signin-1"));
        assertTrue(opt.isPresent());
        assertEquals("SigninUser1", opt.get().getString("username"));
    }

    @Test
    void findByAuth0IdForSignin_unknownUser_returnsEmpty() throws Exception {
        Optional<Row> opt = await(repo.findByAuth0IdForSignin("auth0|nobody"));
        assertTrue(opt.isEmpty());
    }

    // ── findIdByAuth0Id ───────────────────────────────────────────────────────

    @Test
    void findIdByAuth0Id_returnsUUID() throws Exception {
        await(repo.create("auth0|id-1", "id1@test.com", "IdUser1111", "Id", "One"));
        Optional<UUID> id = await(repo.findIdByAuth0Id("auth0|id-1"));
        assertTrue(id.isPresent());
        assertNotNull(id.get());
    }

    @Test
    void findIdByAuth0Id_unknown_returnsEmpty() throws Exception {
        Optional<UUID> id = await(repo.findIdByAuth0Id("auth0|ghost-user"));
        assertTrue(id.isEmpty());
    }

    // ── isBanned / banUser / unbanUser ────────────────────────────────────────

    @Test
    void newUser_isNotBanned() throws Exception {
        await(repo.create("auth0|ban-1", "ban1@test.com", "BanUser1111", "Ban", "One"));
        assertFalse(await(repo.isBanned("auth0|ban-1")));
    }

    @Test
    void banUser_setsIsBanned() throws Exception {
        await(repo.create("auth0|ban-2", "ban2@test.com", "BanUser2222", "Ban", "Two"));
        UUID userId = await(repo.findIdByAuth0Id("auth0|ban-2")).orElseThrow();

        boolean wasBanned = await(repo.banUser(userId, "spam", "admin@test.com"));
        assertTrue(wasBanned);
        assertTrue(await(repo.isBanned("auth0|ban-2")));
    }

    @Test
    void unbanUser_clearsIsBanned() throws Exception {
        await(repo.create("auth0|ban-3", "ban3@test.com", "BanUser3333", "Ban", "Three"));
        UUID userId = await(repo.findIdByAuth0Id("auth0|ban-3")).orElseThrow();

        await(repo.banUser(userId, "spam", "admin@test.com"));
        assertTrue(await(repo.isBanned("auth0|ban-3")));

        boolean wasUnbanned = await(repo.unbanUser(userId));
        assertTrue(wasUnbanned);
        assertFalse(await(repo.isBanned("auth0|ban-3")));
    }

    // ── hasContributed ────────────────────────────────────────────────────────

    @Test
    void hasContributed_falseWhenNoReviews() throws Exception {
        await(repo.create("auth0|contrib-1", "contrib1@test.com", "ContribUsr1", "Contrib", "One"));
        UUID userId = await(repo.findIdByAuth0Id("auth0|contrib-1")).orElseThrow();

        assertFalse(await(repo.hasContributed(userId)));
    }

    @Test
    void hasContributed_trueAfterReviewInserted() throws Exception {
        await(repo.create("auth0|contrib-2", "contrib2@test.com", "ContribUsr2", "Contrib", "Two"));
        UUID userId = await(repo.findIdByAuth0Id("auth0|contrib-2")).orElseThrow();

        // Insert a manager and a review directly so we can test hasContributed in isolation
        long managerId = await(pool.query(
            "INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages) " +
            "VALUES ('Test Mgr','Corp','Title','img','active','approved',null,0,null) RETURNING id")
            .execute()
            .map(rs -> rs.iterator().next().getLong("id")));

        await(pool.preparedQuery(
            "INSERT INTO reviews(manager_id, user_id, author, overall_rating, " +
            "communication_style, perceived_approachability, perceived_clarity_of_expectations, " +
            "feedback_style, perceived_supportiveness, decision_making_style, " +
            "organization_and_planning_style, delegation_style, perceived_professional_demeanor, " +
            "overall_working_experience, manager_company, manager_title) " +
            "VALUES ($1,$2,'Anon',4.0,4,4,4,4,4,4,4,4,4,4,'Corp','Title')")
            .execute(Tuple.of(managerId, userId)));

        assertTrue(await(repo.hasContributed(userId)));
    }

    // ── claimAutoCreatedManagerSlot / resetAutoCreatedManagerSlot ─────────────

    @Test
    void claimSlot_firstTime_returnsTrue() throws Exception {
        await(repo.create("auth0|slot-1", "slot1@test.com", "SlotUser1111", "Slot", "One"));
        UUID userId = await(repo.findIdByAuth0Id("auth0|slot-1")).orElseThrow();

        assertTrue(await(repo.claimAutoCreatedManagerSlot(userId)));

        // Verify it is set in DB
        Row row = await(pool.preparedQuery("SELECT has_auto_created_manager FROM users WHERE id = $1")
            .execute(Tuple.of(userId))
            .map(rs -> rs.iterator().next()));
        assertTrue(row.getBoolean("has_auto_created_manager"));
    }

    @Test
    void claimSlot_secondTime_returnsFalse() throws Exception {
        await(repo.create("auth0|slot-2", "slot2@test.com", "SlotUser2222", "Slot", "Two"));
        UUID userId = await(repo.findIdByAuth0Id("auth0|slot-2")).orElseThrow();

        await(repo.claimAutoCreatedManagerSlot(userId));
        boolean second = await(repo.claimAutoCreatedManagerSlot(userId));

        assertFalse(second, "claimAutoCreatedManagerSlot must return false when slot already claimed");
    }

    @Test
    void resetSlot_allowsClaimAgain() throws Exception {
        await(repo.create("auth0|slot-3", "slot3@test.com", "SlotUser3333", "Slot", "Three"));
        UUID userId = await(repo.findIdByAuth0Id("auth0|slot-3")).orElseThrow();

        await(repo.claimAutoCreatedManagerSlot(userId));
        await(repo.resetAutoCreatedManagerSlot(userId));

        boolean reClaimable = await(repo.claimAutoCreatedManagerSlot(userId));
        assertTrue(reClaimable, "After reset, claimAutoCreatedManagerSlot must succeed again");
    }

    // ── deleteWithReviewAnonymization ─────────────────────────────────────────

    @Test
    void deleteUser_removesUserRow() throws Exception {
        await(repo.create("auth0|del-1", "del1@test.com", "DeleteUsr11", "Del", "One"));
        UUID userId = await(repo.findIdByAuth0Id("auth0|del-1")).orElseThrow();

        await(repo.deleteWithReviewAnonymization(userId));

        Optional<Row> opt = await(repo.findByAuth0IdWithBan("auth0|del-1"));
        assertTrue(opt.isEmpty(), "User should be deleted");
    }

    @Test
    void deleteUser_anonymizesReviews() throws Exception {
        await(repo.create("auth0|del-2", "del2@test.com", "DeleteUsr22", "Del", "Two"));
        UUID userId = await(repo.findIdByAuth0Id("auth0|del-2")).orElseThrow();

        long managerId = await(pool.query(
            "INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages) " +
            "VALUES ('Mgr Del','Corp','Title','img','active','approved',null,0,null) RETURNING id")
            .execute()
            .map(rs -> rs.iterator().next().getLong("id")));

        await(pool.preparedQuery(
            "INSERT INTO reviews(manager_id, user_id, author, overall_rating, " +
            "communication_style, perceived_approachability, perceived_clarity_of_expectations, " +
            "feedback_style, perceived_supportiveness, decision_making_style, " +
            "organization_and_planning_style, delegation_style, perceived_professional_demeanor, " +
            "overall_working_experience, manager_company, manager_title) " +
            "VALUES ($1,$2,'Anon',4.0,4,4,4,4,4,4,4,4,4,4,'Corp','Title')")
            .execute(Tuple.of(managerId, userId)));

        await(repo.deleteWithReviewAnonymization(userId));

        // Review should still exist but user_id should be null
        Long count = await(pool.preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1 AND user_id IS NULL")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, count, "Reviews must remain but with user_id nulled out after account deletion");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
