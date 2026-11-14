package org.werkpages.integration;

import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UNLINKING A RATING: the contributor lets go, the rating stays.
 *
 * <h2>What makes this different from deleting</h2>
 *
 * <p>Withdrawal hides a rating and republishes it anonymously three days later. Unlinking never
 * unpublishes it: the scores keep counting throughout, and the contributor's ownership is severed
 * at once and permanently. Every assertion here is about one of those two halves - the rating is
 * untouched where it faces the public, and thoroughly severed where it faced its author.
 *
 * <h2>The requirement that is easiest to get wrong</h2>
 *
 * <p><b>Unlinking must not become a way to rate the same manager twice.</b> Every duplicate guard
 * in the submission path works from the ratings an account still OWNS, and unlinking is precisely
 * the act of ceasing to own one - so all of them go blind at the same moment. The participation
 * record is the only durable trace, and {@code relinquishmentSurvivesUnlinking} is the test that
 * matters most here.
 *
 * <p>And the mirror of it: the contributor must <b>keep</b> the profile access their contribution
 * paid for. The rating is still published and still counting toward the manager's average, so
 * penalising its author would be taking the contribution and the access both.
 */
@Tag("critical")
@Testcontainers
class UnlinkRatingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("wp_unlink").withUsername("test").withPassword("test");

    static Pool pool;
    static ReviewRepository reviewRepo;
    static ManagerRepository managerRepo;
    static UserRepository userRepo;

    @BeforeAll
    static void setUpAll() {
        // WerkPages owns the stream, so its own classpath copy IS the authoritative one.
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations").load().migrate();
        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost()).setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername()).setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(5));
        reviewRepo  = new ReviewRepository(pool);
        managerRepo = new ManagerRepository(pool);
        userRepo    = new UserRepository(pool, null);
    }

    @AfterAll
    static void tearDownAll() { if (pool != null) pool.close(); }

    // ── fixtures ────────────────────────────────────────────────────────────

    private <T> T await(io.vertx.core.Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private void sql(String s, Object... args) throws Exception {
        await(args.length == 0
            ? pool.query(s).execute().mapEmpty()
            : pool.preparedQuery(s).execute(Tuple.wrap(args)).mapEmpty());
    }

    private Row one(String s, Object... args) throws Exception {
        var rows = await(args.length == 0
            ? pool.query(s).execute()
            : pool.preparedQuery(s).execute(Tuple.wrap(args)));
        return rows.iterator().hasNext() ? rows.iterator().next() : null;
    }

    private UUID user(String tag) throws Exception {
        return one("INSERT INTO users (auth0_id,email,username) VALUES ($1,$2,$3) RETURNING id",
            "auth0|" + tag, tag + "@t.test", tag).getUUID("id");
    }

    private long manager(String slug) throws Exception {
        Long companyId = one("INSERT INTO companies (name,status) VALUES ($1,'approved') "
            + "ON CONFLICT ((LOWER(TRIM(name)))) DO UPDATE SET updated_at = now() RETURNING id",
            "Acme " + slug).getLong("id");
        return one("INSERT INTO managers (name,company,title,status,approval_status,slug,company_id) "
            + "VALUES ($1,$2,'Director','active','approved',$3,$4) RETURNING id",
            "M " + slug, "Acme " + slug, slug, companyId).getLong("id");
    }

    /** A genuine, owned, gate-eligible rating. */
    private UUID rating(long managerId, UUID userId, double score) throws Exception {
        return one("""
            INSERT INTO reviews (manager_id,user_id,author,overall_rating,manager_company,
                                 manager_title,worked_from,gate_eligible,origin)
            VALUES ($1,$2,'author',$3,'Acme','Director',now() - interval '2 years',TRUE,'contributor')
            RETURNING id
            """, managerId, userId, score).getUUID("id");
    }

    @BeforeEach
    void clean() throws Exception {
        sql("TRUNCATE reviews, rating_participation, managers, users, companies CASCADE");
    }

    // ── the public half: nothing changes ────────────────────────────────────

    @Test
    @DisplayName("the rating stays published, and keeps counting toward the average")
    void theRatingStaysPublishedAndKeepsCounting() throws Exception {
        UUID u = user("keeps");
        long m = manager("keeps");
        UUID r = rating(m, u, 4.0);
        await(managerRepo.recalculate(m));

        Row before = one("SELECT reviews_count, overall_rating FROM managers WHERE id = $1", m);
        assertEquals(1, before.getInteger("reviews_count"));

        assertTrue(await(reviewRepo.unlinkByOwner(pool, r, m, u)), "the unlink should have happened");
        await(managerRepo.recalculate(m));

        Row row = one("SELECT deleted_at, disposition, unlinked_at, overall_rating FROM reviews WHERE id = $1", r);
        assertNull(row.getLocalDateTime("deleted_at"),
            "unlinking must NEVER soft-delete: the rating stays published, which is the whole "
            + "difference from withdrawal");
        assertEquals("live", row.getString("disposition"));
        assertNotNull(row.getLocalDateTime("unlinked_at"));

        Row after = one("SELECT reviews_count, overall_rating FROM managers WHERE id = $1", m);
        assertEquals(1, after.getInteger("reviews_count"),
            "the score still counts - there is no three-day dip in the manager's average");
        assertEquals(before.getBigDecimal("overall_rating"), after.getBigDecimal("overall_rating"));
    }

    // ── the private half: thoroughly severed ───────────────────────────────

    @Test
    @DisplayName("ownership, the claim and the employment period are all severed")
    void ownershipAndPeriodAreSevered() throws Exception {
        UUID u = user("sever");
        long m = manager("sever");
        UUID r = rating(m, u, 4.0);

        assertTrue(await(reviewRepo.unlinkByOwner(pool, r, m, u)));

        Row row = one("SELECT user_id, claim_id, draft_token, dates_hidden FROM reviews WHERE id = $1", r);
        assertNull(row.getUUID("user_id"),   "the ownership link must be gone");
        assertNull(row.getValue("claim_id"), "the employment claim must be gone");
        assertNull(row.getValue("draft_token"));
        assertTrue(row.getBoolean("dates_hidden"),
            "the employment period must be withheld: on a manager with few reports a date range "
            + "identifies its author as precisely as a name");
    }

    @Test
    @DisplayName("the contributor can no longer edit it, because they no longer own it")
    void theContributorCanNoLongerEditIt() throws Exception {
        UUID u = user("noedit");
        long m = manager("noedit");
        UUID r = rating(m, u, 4.0);

        assertTrue(await(reviewRepo.findOwnerUserId(r, m)).isPresent(), "owned before unlinking");
        assertTrue(await(reviewRepo.unlinkByOwner(pool, r, m, u)));
        assertTrue(await(reviewRepo.findOwnerUserId(r, m)).isEmpty(),
            "after unlinking there is no owner, so every ownership check - edit, replace, unlink "
            + "again - refuses it");
    }

    // ── the requirement most easily lost ──────────────────────────────────

    @Test
    @DisplayName("relinquishment survives unlinking, so the manager cannot be rated again")
    void relinquishmentSurvivesUnlinking() throws Exception {
        UUID u = user("once");
        long m = manager("once");
        UUID r = rating(m, u, 4.0);

        assertFalse(await(reviewRepo.hasRelinquishedManager(pool, u, m)),
            "nothing relinquished yet");

        assertTrue(await(reviewRepo.unlinkByOwner(pool, r, m, u)));

        assertTrue(await(reviewRepo.hasRelinquishedManager(pool, u, m)),
            "THE defect this guards: every duplicate check works from ratings the account still "
            + "owns, and unlinking removes it from all of them at once. Without the participation "
            + "record, unlinking would itself be the way to rate a manager twice.");

        Row p = one("SELECT gate_eligible, unlinked_at FROM rating_participation "
                  + "WHERE user_id = $1 AND subject_type = 'manager' AND subject_id = $2", u, m);
        assertNotNull(p, "a participation record must exist");
        assertNotNull(p.getLocalDateTime("unlinked_at"));
    }

    @Test
    @DisplayName("a different manager is still rateable: the block is per subject")
    void aDifferentManagerIsStillRateable() throws Exception {
        UUID u = user("other");
        long m1 = manager("other-1");
        long m2 = manager("other-2");
        UUID r = rating(m1, u, 4.0);

        assertTrue(await(reviewRepo.unlinkByOwner(pool, r, m1, u)));
        assertTrue(await(reviewRepo.hasRelinquishedManager(pool, u, m1)));
        assertFalse(await(reviewRepo.hasRelinquishedManager(pool, u, m2)),
            "unlinking one manager must not bar the contributor from the rest of the platform");
    }

    @Test
    @DisplayName("participation alone does NOT bar a second rating - only relinquishment does")
    void participationAloneDoesNotBarASecondRating() throws Exception {
        UUID u = user("twinrole");
        long m = manager("twinrole");
        rating(m, u, 4.0);

        // Backfill/normal submission records participation without relinquishment.
        sql("INSERT INTO rating_participation (user_id,subject_type,subject_id,gate_eligible) "
          + "VALUES ($1,'manager',$2,TRUE)", u, m);

        assertFalse(await(reviewRepo.hasRelinquishedManager(pool, u, m)),
            "Rev 268 deliberately allows an account TWO ratings of one manager, for a contributor "
            + "who held two separate employments under them. Blocking on the participation row "
            + "itself would have revoked that from everyone who never unlinked anything.");
    }

    // ── eligibility ────────────────────────────────────────────────────────

    @Test
    @DisplayName("profile access survives unlinking")
    void profileAccessSurvivesUnlinking() throws Exception {
        UUID u = user("gate");
        long m = manager("gate");
        UUID r = rating(m, u, 4.0);

        assertTrue(await(userRepo.hasContributed(u)), "contributing opens the gate");
        assertTrue(await(reviewRepo.unlinkByOwner(pool, r, m, u)));
        assertTrue(await(userRepo.hasContributed(u)),
            "the rating is still published and still counting toward the manager's average. "
            + "Closing the gate would take the contribution AND the access.");
    }

    @Test
    @DisplayName("an example rating opens the gate for nobody")
    void anExampleOpensTheGateForNobody() throws Exception {
        UUID u = user("noexample");
        long m = manager("noexample");
        sql("""
            INSERT INTO reviews (manager_id,user_id,author,overall_rating,manager_company,
                                 manager_title,worked_from,weight,origin)
            VALUES ($1,NULL,'BraveFalcon42',4.6,'Acme','Director',now(),TRUE,'platform_example')
            """, m);
        assertFalse(await(userRepo.hasContributed(u)),
            "a platform demonstration is nobody's contribution and must unlock nothing");
    }

    // ── examples contribute nothing ────────────────────────────────────────

    @Test
    @DisplayName("a manager with one example and no genuine ratings has a genuine count of zero")
    void examplesContributeNothingToGenuineFigures() throws Exception {
        long m = manager("exonly");
        sql("""
            INSERT INTO reviews (manager_id,user_id,author,overall_rating,manager_company,
                                 manager_title,worked_from,weight,origin)
            VALUES ($1,NULL,'KeenOtter19',4.8,'Acme','Director',now(),TRUE,'platform_example')
            """, m);
        await(managerRepo.recalculate(m));

        Row row = one("SELECT reviews_count, overall_rating FROM managers WHERE id = $1", m);
        assertEquals(0, row.getInteger("reviews_count"),
            "requirement: one example and zero genuine ratings is ZERO genuine ratings");
        assertNull(row.getBigDecimal("overall_rating"), "and no genuine average");
    }

    @Test
    @DisplayName("an example alongside genuine ratings changes neither the count nor the average")
    void examplesDoNotDistortGenuineFigures() throws Exception {
        UUID u = user("mixed");
        long m = manager("mixed");
        rating(m, u, 4.0);
        await(managerRepo.recalculate(m));
        Row genuineOnly = one("SELECT reviews_count, overall_rating FROM managers WHERE id = $1", m);

        sql("""
            INSERT INTO reviews (manager_id,user_id,author,overall_rating,manager_company,
                                 manager_title,worked_from,weight,origin)
            VALUES ($1,NULL,'WiseLynx77',1.0,'Acme','Director',now(),TRUE,'platform_example')
            """, m);
        await(managerRepo.recalculate(m));
        Row withExample = one("SELECT reviews_count, overall_rating FROM managers WHERE id = $1", m);

        assertEquals(genuineOnly.getInteger("reviews_count"), withExample.getInteger("reviews_count"));
        assertEquals(genuineOnly.getBigDecimal("overall_rating"), withExample.getBigDecimal("overall_rating"),
            "a 1.0 example beside a 4.0 genuine rating must not move the average at all");
    }

    // ── safety ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("unlinking twice is safe, and the second call reports that it did nothing")
    void unlinkingTwiceIsSafe() throws Exception {
        UUID u = user("twice");
        long m = manager("twice");
        UUID r = rating(m, u, 4.0);

        assertTrue(await(reviewRepo.unlinkByOwner(pool, r, m, u)));
        assertFalse(await(reviewRepo.unlinkByOwner(pool, r, m, u)),
            "the ownership predicate is the guard: after the first commit there is no row with "
            + "that owner to find, so a repeat - or a concurrent second request - matches nothing");

        assertEquals(1, (int) one("SELECT count(*)::int AS c FROM rating_participation "
            + "WHERE user_id = $1 AND subject_id = $2", u, m).getInteger("c"),
            "and it must not write a second participation record");
    }

    @Test
    @DisplayName("somebody else's rating cannot be unlinked")
    void somebodyElsesRatingCannotBeUnlinked() throws Exception {
        UUID owner    = user("owner");
        UUID attacker = user("attacker");
        long m = manager("notyours");
        UUID r = rating(m, owner, 4.0);

        assertFalse(await(reviewRepo.unlinkByOwner(pool, r, m, attacker)),
            "the ownership predicate refuses it at the database, not only in the service");
        assertEquals(owner, one("SELECT user_id FROM reviews WHERE id = $1", r).getUUID("user_id"));
    }

    @Test
    @DisplayName("an example cannot be unlinked by anyone")
    void anExampleCannotBeUnlinked() throws Exception {
        UUID u = user("exunlink");
        long m = manager("exunlink");
        UUID ex = one("""
            INSERT INTO reviews (manager_id,user_id,author,overall_rating,manager_company,
                                 manager_title,worked_from,weight,origin)
            VALUES ($1,NULL,'BoldCrane31',4.4,'Acme','Director',now(),TRUE,'platform_example')
            RETURNING id
            """, m).getUUID("id");

        assertFalse(await(reviewRepo.unlinkByOwner(pool, ex, m, u)),
            "a demonstration was never anybody's to relinquish");
    }

    @Test
    @DisplayName("account erasure removes the participation record")
    void accountErasureRemovesParticipation() throws Exception {
        UUID u = user("erased");
        long m = manager("erased");
        UUID r = rating(m, u, 4.0);
        assertTrue(await(reviewRepo.unlinkByOwner(pool, r, m, u)));
        assertEquals(1, (int) one("SELECT count(*)::int AS c FROM rating_participation WHERE user_id = $1", u).getInteger("c"));

        sql("DELETE FROM users WHERE id = $1", u);

        assertEquals(0, (int) one("SELECT count(*)::int AS c FROM rating_participation WHERE user_id = $1", u).getInteger("c"),
            "ON DELETE CASCADE: the record is personal information and must not outlive the "
            + "account it describes");
        assertNotNull(one("SELECT id FROM reviews WHERE id = $1", r),
            "but the published rating survives erasure, as it already did - it is no longer "
            + "anybody's personal data");
    }
}
