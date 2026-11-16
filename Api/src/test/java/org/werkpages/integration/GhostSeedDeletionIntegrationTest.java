package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
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
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.NotificationRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.ManagerService;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the conditional ghost seed-review handling in doAttachToExisting:
 *
 *   Non-contributor rates ghost → seed deleted immediately, real rating shows at once.
 *   Contributor     rates ghost → seed kept on 14-day expiry counter, both reviews coexist.
 */
@Testcontainers
class GhostSeedDeletionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool              pool;
    static ManagerService    service;
    static ManagerRepository managerRepo;
    static ReviewRepository  reviewRepo;
    static CompanyRepository companyRepo;
    static UserRepository    userRepo;

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
        userRepo    = new UserRepository(pool);
        managerRepo = new ManagerRepository(pool);
        reviewRepo  = new ReviewRepository(pool);
        companyRepo = new CompanyRepository(pool);

        service = new ManagerService(managerRepo, reviewRepo, userRepo,
            new EditRepository(pool), new ReportRepository(pool), pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE notifications, manager_url_history, company_stats_live").execute());
        await(pool.query("TRUNCATE managers, companies, users CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── Non-contributor rates ghost → seed deleted immediately ───────────────

    @Test
    void nonContributor_ratesGhostManager_seedDeletedImmediately() throws Exception {
        // 1. User A creates a ghost manager via /find (first-ever search, long names).
        String userAAuth0 = insertUser("auth0|ghost-creator", "GhostCreator");
        await(service.findOrCreate(userAAuth0, "Emma", "Davis", "Senior Manager", "SeedDeleteCo", "US", null, null, null));

        long ghostId = fetchManagerId("Emma Davis", "SeedDeleteCo");
        assertEquals(1, countSeedReviews(ghostId), "Ghost must have exactly 1 seed review before any real rating");
        assertEquals(1, countTotalReviews(ghostId));

        // 2. User B (non-contributor, 0 prior reviews) rates the same manager via the add-manager form.
        String userBAuth0 = insertUser("auth0|non-contributor", "NonContributor");
        await(service.createManager(userBAuth0, validCreateManagerBody("Emma Davis", "SeedDeleteCo", "Senior Manager"), null));
        Thread.sleep(1_000); // allow fire-and-forget recalculate

        // 3. Seed must be gone — only user B's real review remains.
        assertEquals(0, countSeedReviews(ghostId),
            "Seed review must be deleted immediately when a non-contributor rates the ghost manager");
        assertEquals(1, countTotalReviews(ghostId),
            "Only the non-contributor's real review must remain");

        // 4. Rating must reflect the real submitted value, not 0.
        Row manager = fetchManager(ghostId);
        assertNotNull(manager.getBigDecimal("overall_rating"),
            "overall_rating must be set after real review and recalculate");
        assertTrue(manager.getBigDecimal("overall_rating").doubleValue() > 0,
            "overall_rating must be > 0 after recalculate");
    }

    // ── Contributor rates ghost → seed deleted immediately, exactly as for anybody else ──

    /**
     * REWRITTEN, and the rewrite is the point of the test.
     *
     * <p>This asserted the opposite: that a contributor's rating put the placeholder on a 14-day
     * countdown and left it visible in the meantime, while a non-contributor's deleted it at once.
     * The submitter's history anywhere else on the site decided whether THIS manager's profile
     * showed a fabricated rating next to a real one, which is not a fact about this manager at all.
     *
     * <p>The product rule is now simply: a genuine rating arrives, the example goes. Who wrote it
     * is irrelevant, so this test and
     * {@code nonContributor_ratesGhostManager_seedDeletedImmediately} now assert the same outcome
     * by two different routes, deliberately.
     *
     * <p>RMM carried this correction from 2026-11-13 and Werkpages did not, which is why this one
     * test failed here and passed there against identical production code.
     */
    @Test
    void contributor_ratesGhostManager_seedDeletedImmediatelyToo() throws Exception {
        // 1. Create a ghost manager directly (avoids consuming userA's ghost slot for a second test).
        Row companyRow = await(companyRepo.findOrCreate("ExpiryCo", null, null));
        Row ghostRow = await(managerRepo.createAutoApproved(
            "Frank Miller", "ExpiryCo", "Director", "US", null, null, null, null, companyRow.getLong("id")));
        long ghostId = ghostRow.getLong("id");
        await(reviewRepo.createSeedReview(ghostId, "ExpiryCo", "Director"));
        Thread.sleep(500); // let seed recalculate settle

        assertEquals(1, countSeedReviews(ghostId), "Ghost must have 1 seed review");

        // 2. Create user C and give them a prior review on a different manager (makes them a contributor).
        String userCAuth0 = insertUser("auth0|contributor", "ContribUser");
        UUID userCId = findUserId(userCAuth0);

        Row priorCompany = await(companyRepo.findOrCreate("PriorCo", null, null));
        Row priorManager = await(managerRepo.createAutoApproved(
            "Prior Manager", "PriorCo", "Manager", "US", null, null, null, null, priorCompany.getLong("id")));
        long priorManagerId = priorManager.getLong("id");

        // Insert the prior review directly — user C has now contributed once.
        await(pool.preparedQuery("""
                INSERT INTO reviews (
                    manager_id, user_id, author, overall_rating,
                    communication_style, perceived_approachability, perceived_clarity_of_expectations,
                    feedback_style, perceived_supportiveness, decision_making_style,
                    organization_and_planning_style, delegation_style, perceived_professional_demeanor,
                    overall_working_experience, manager_company, manager_title,
                    worked_from, verified, helpful_count, weight, created_at, updated_at
                )
                VALUES ($1, $2, 'PriorAuthor', 4.0,
                        4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
                        'PriorCo', 'Manager', '2023-01-01',
                        true, 0, false, now(), now())
                """)
            .execute(Tuple.of(priorManagerId, userCId)));

        // 3. Contributor (user C) rates the ghost manager via the add-manager form.
        await(service.createManager(userCAuth0,
            validCreateManagerBody("Frank Miller", "ExpiryCo", "Director"), null));
        Thread.sleep(1_000); // allow fire-and-forget scheduleSeedExpiry / recalculate

        // 4. The example is gone, and only the contributor's own rating remains.
        assertEquals(0, countSeedReviews(ghostId),
            "the example must be deleted the moment a genuine rating lands, whoever wrote it");
        assertEquals(1, countTotalReviews(ghostId),
            "only the contributor's real rating is left on the profile");

        // 5. And it is gone rather than merely timed out: a row left behind with a date on it is
        //    what used to keep a fabricated rating in the list for a fortnight.
        RowSet<Row> seedRows = await(pool.preparedQuery(
            "SELECT weight_expires_on FROM reviews WHERE manager_id = $1 AND weight = TRUE AND user_id IS NULL")
            .execute(Tuple.of(ghostId)));
        assertFalse(seedRows.iterator().hasNext(),
            "no placeholder row may survive the arrival of a genuine rating, expiring or otherwise");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * The countdown starts on the first real review and is never restarted by later ones.
     *
     * <p>The UPDATE behind this had no {@code weight_expires_on IS NULL} guard, so every
     * subsequent real review pushed the expiry out another fortnight. A manager receiving a
     * review even occasionally therefore kept its placeholder <b>forever</b> - the clock restarted
     * before it could finish, and no amount of waiting would ever have cleared it.
     *
     * <p>Asserted as "the date did not move", not merely "a date is set", because the bug set a
     * date perfectly well. It set it again, and again.
     */
    @Test
    void seedExpiry_startsOnFirstRealReview_andLaterReviewsDoNotRestartIt() throws Exception {
        long managerId = insertManagerWithSeed("ClockCo", "Clock Manager", "NULL");

        await(reviewRepo.scheduleSeedExpiry(managerId));
        assertNotNull(readSeedExpiry(managerId), "the first real review must start the countdown");

        // Move the clock to a known nearer date, as though it had been running for a while.
        await(pool.preparedQuery(
                "UPDATE reviews SET weight_expires_on = CURRENT_DATE + 2 WHERE manager_id = $1 AND weight = TRUE")
            .execute(Tuple.of(managerId)).mapEmpty());
        java.time.LocalDate beforeSecondReview = readSeedExpiry(managerId);

        // A second real review arrives.
        await(reviewRepo.scheduleSeedExpiry(managerId));

        /*
            Compared against the value read back from the database, not against LocalDate.now().

            The first version of this assertion computed the expected date in the JVM and failed
            for the wrong reason: the Postgres container runs UTC and the JVM ran UTC-4, so late
            in the evening CURRENT_DATE was already tomorrow and the two disagreed by a day. The
            claim being tested is "this date did not move", which needs no clock of its own.
        */
        assertEquals(beforeSecondReview, readSeedExpiry(managerId),
            "a later review must not push the expiry out again - the clock runs down once");
    }

    /**
     * An expired placeholder is removed from the table, not merely ignored.
     *
     * <p>"Expired" used to mean hidden from the list and excluded from the rating while the row
     * stayed put indefinitely. Nothing ever deleted it. This is the sweep that does, and it must
     * take only the expired placeholders: a placeholder still counting down, and any real review,
     * have to survive it untouched.
     */
    @Test
    void deleteExpiredSeedReviews_removesOnlyPlaceholdersPastTheirDate() throws Exception {
        long expired  = insertManagerWithLegacyPlaceholder("SweepCo", "Swept Manager",
                                                           "now() - INTERVAL '1 day'");
        long counting = insertManagerWithLegacyPlaceholder("KeepCo", "Kept Manager",
                                                           "CURRENT_DATE + 5");

        /*
            An EXAMPLE past the very same date. It must survive, and this assertion is the new
            rule rather than an incidental extra: a generated example is a permanent demonstration
            that no clock removes, so the sweep that reaps legacy placeholders must leave it alone.
            Before this line existed the whole test passed on fixtures that were examples without
            saying so, which told us nothing about either rule.
        */
        long example = insertManagerWithSeed("ExampleCo", "Example Manager",
                                             "now() - INTERVAL '1 day'");

        // A real review on the same manager as the expired placeholder - it must not be collateral.
        await(pool.preparedQuery(
                "INSERT INTO reviews(manager_id, author, overall_rating, manager_company, manager_title, "
              + "worked_from, weight, created_at) "
              + "VALUES ($1,'Real Person',5.0,'SweepCo','VP',CURRENT_DATE - 10, FALSE, now())")
            .execute(Tuple.of(expired)).mapEmpty());

        /*
            A row that should not exist: weight TRUE with a real author attached. Every write path
            makes placeholders authorless, so this cannot arise normally - it is here because the
            statement deletes in bulk and unattended, and the cost of the invariant being broken by
            some future migration is somebody's real review vanishing irrecoverably. It must
            survive.
        */
        // A LEGACY placeholder, so the user_id guard is what spares it. Built as an example it
        // would be spared by the origin clause instead and this assertion would prove nothing.
        long guarded = insertManagerWithLegacyPlaceholder("GuardCo", "Guarded Manager",
                                                          "CURRENT_DATE - 1");
        String ownerAuth0 = insertUser("auth0|seedguard", "seedguarduser");
        await(pool.preparedQuery(
                "UPDATE reviews SET user_id = (SELECT id FROM users WHERE auth0_id = $2) "
              + "WHERE manager_id = $1 AND weight = TRUE")
            .execute(Tuple.of(guarded, ownerAuth0)).mapEmpty());

        int deleted = await(reviewRepo.deleteExpiredSeedReviews());
        assertEquals(1, deleted, "exactly the one authorless LEGACY placeholder past its date");
        assertEquals(1L, countReviews(example, true),
            "a platform example is never swept, whatever date it carries - it is a permanent "
          + "demonstration, and the clock belongs to the lifecycle origin replaced");
        assertEquals(1L, countReviews(guarded, true),
            "a weighted row with a real author is never swept, whatever its expiry says");

        assertEquals(0L, countReviews(expired, true),  "the expired placeholder is gone");
        assertEquals(1L, countReviews(expired, false), "the real review on that manager survives");
        assertEquals(1L, countReviews(counting, true), "a placeholder still counting down survives");
    }

    /**
     * The placeholder's expiry date as the database holds it, or null if there is no placeholder.
     *
     * <p><b>The empty case is the whole reason this is not a one-liner.</b> It used to call
     * {@code iterator().next()} unguarded, which threw {@code NoSuchElementException} the moment a
     * test asserted the correct outcome - that the row is deleted rather than dated. The caller
     * then reported an ERROR rather than a failure, naming the iterator instead of the assertion,
     * which is a slow way to find out that the test was right.
     */
    private java.time.LocalDate readSeedExpiry(long managerId) throws Exception {
        return await(pool
            .preparedQuery("SELECT weight_expires_on FROM reviews WHERE manager_id = $1 AND weight = TRUE")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().hasNext()
                ? rs.iterator().next().getLocalDate("weight_expires_on")
                : null));
    }

    /** Reviews on a manager, split by whether they are placeholders. */
    private long countReviews(long managerId, boolean placeholders) throws Exception {
        return await(pool
            .preparedQuery("SELECT COUNT(*) AS c FROM reviews WHERE manager_id = $1 AND weight = $2")
            .execute(Tuple.of(managerId, placeholders))
            .map(rs -> rs.iterator().next().getLong("c")));
    }

    /**
     * Rating a ghost from its own profile page retires the placeholder.
     *
     * <p>Found in live data: 304 placeholders, every one with {@code weight_expires_on} NULL, and
     * fourteen managers whose displayed rating was the mean of a real review and a fabricated one.
     *
     * <p>Only two callers ever touched a seed - the find-or-create flow and the drop-off draft.
     * {@code createReview}, which is what runs when somebody opens a ghost's profile and presses
     * "Write a Review", did neither: it inserted the genuine review and left the placeholder in
     * place, counting forever. Most people rate from the profile page, which is why the clock had
     * never started on a single one of the 304.
     *
     * <p>Retirement is a 14-day countdown, never an immediate delete, so no manager's displayed
     * rating jumps the instant somebody submits. What this asserts is that the clock <em>starts</em> -
     * a placeholder still sitting at NULL after a real review is the defect, and it is what the
     * 304 untouched placeholders in production all looked like.
     */
    @Test
    void createReview_fromProfilePage_removesThePlaceholder() throws Exception {
        long managerId = insertManagerWithSeed("ProfileCo", "Profile Rated", "NULL");
        assertEquals(1L, countSeeds(managerId), "precondition: the ghost carries a placeholder");

        String auth0Id = insertUser("auth0|profilerater", "profilerater");
        await(service.createReview(auth0Id, managerId,
            reviewBodyFor("ProfileCo", "VP"), null));

        assertEquals(0L, countSeeds(managerId),
            "the placeholder is deleted now, not put on a timer: this is the path most people take");
        assertNull(readSeedExpiry(managerId),
            "and nothing is left behind holding a date - the row itself is gone");
    }

    /** A complete, valid review payload - the same shape ReviewIntegrationTest uses. */
    private static JsonObject reviewBodyFor(String company, String title) {
        JsonObject ratings = new JsonObject();
        for (String key : new String[]{
                "Communication Style", "Perceived Approachability",
                "Perceived Clarity of Expectations", "Feedback Style",
                "Perceived Supportiveness", "Decision Making Style",
                "Organization and Planning Style", "Delegation Style",
                "Perceived Professional Demeanor", "Overall Working Experience"}) {
            ratings.put(key, 4.0);
        }
        return new JsonObject()
            .put("overallRating",  4.0)
            .put("ratings",        ratings)
            .put("managerCompany", company)
            .put("managerTitle",   title)
            .put("workedFrom",     "2022-01")
            .put("author",         "AnonTester99")
            .put("authorType",     "anonymous");
    }

    private long countSeeds(long managerId) throws Exception {
        return await(pool
            .preparedQuery("SELECT COUNT(*) AS c FROM reviews WHERE manager_id = $1 AND weight = TRUE")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong("c")));
    }

    private String insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id,email,username,first_name,last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, auth0Id + "@test.com", username, "Test", "User")));
        return auth0Id;
    }

    private UUID findUserId(String auth0Id) throws Exception {
        RowSet<Row> rs = await(pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id)));
        return rs.iterator().next().getUUID("id");
    }

    private long fetchManagerId(String name, String company) throws Exception {
        RowSet<Row> rs = await(pool.preparedQuery(
            "SELECT id FROM managers WHERE name = $1 AND company = $2")
            .execute(Tuple.of(name, company)));
        return rs.iterator().next().getLong("id");
    }

    private Row fetchManager(long managerId) throws Exception {
        return await(pool.preparedQuery(
            "SELECT id, overall_rating, reviews_count FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId)))
            .iterator().next();
    }

    private int countSeedReviews(long managerId) throws Exception {
        RowSet<Row> rs = await(pool.preparedQuery(
            "SELECT COUNT(*) AS cnt FROM reviews WHERE manager_id = $1 AND weight = TRUE AND user_id IS NULL AND deleted_at IS NULL")
            .execute(Tuple.of(managerId)));
        return rs.iterator().next().getInteger("cnt");
    }

    private int countTotalReviews(long managerId) throws Exception {
        RowSet<Row> rs = await(pool.preparedQuery(
            "SELECT COUNT(*) AS cnt FROM reviews WHERE manager_id = $1 AND deleted_at IS NULL")
            .execute(Tuple.of(managerId)));
        return rs.iterator().next().getInteger("cnt");
    }

    private static JsonObject validCreateManagerBody(String name, String company, String title) {
        String[] parts  = name.split(" ", 2);
        String firstName = parts[0];
        String lastName  = parts.length > 1 ? parts[1] : "X";
        JsonObject ratings = new JsonObject();
        for (String key : new String[]{
                "Communication Style", "Perceived Approachability",
                "Perceived Clarity of Expectations", "Feedback Style",
                "Perceived Supportiveness", "Decision Making Style",
                "Organization and Planning Style", "Delegation Style",
                "Perceived Professional Demeanor", "Overall Working Experience"}) {
            ratings.put(key, 4.0);
        }
        JsonObject review = new JsonObject()
            .put("overallRating",  4.0)
            .put("ratings",        ratings)
            .put("managerCompany", company)
            .put("managerTitle",   title)
            .put("workedFrom",     "2022-01")
            .put("authorType",     "anonymous")
            .put("author",         "AnonTester99");
        return new JsonObject()
            .put("name",      name)
            .put("firstName", firstName)
            .put("lastName",  lastName)
            .put("company",   company)
            .put("title",     title)
            .put("image",     "img")
            .put("country",   "US")
            .put("status",    "active")
            .put("startDate", "2020-01")
            .put("review",    review);
    }

    // ── Expired placeholder weights stop counting ─────────────────────────────

    @Test
    void recalculate_stopsCountingAPlaceholderWhoseWeightHasExpired() throws Exception {
        // The reported bug: the review vanishes from the list on day 14, but the count on the card
        // never moves. The list query filtered expired placeholders; the cached count did not.
        long managerId = insertManagerWithExpiredSeed("Expiry Corp", "Vera Expired");

        await(managerRepo.recalculate(managerId));

        Integer cached = await(pool.preparedQuery("SELECT reviews_count FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getInteger("reviews_count")));
        assertEquals(0, cached, "an expired placeholder must stop counting toward the cached total");

        BigDecimal rating = await(pool.preparedQuery("SELECT overall_rating FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getBigDecimal("overall_rating")));
        assertNull(rating, "and the rating it produced goes with it");
    }

    /**
     * An example contributes nothing even while its legacy window is wide open.
     *
     * <p><b>INVERTED, from {@code recalculate_keepsAPlaceholderThatHasNotExpiredYet}, which
     * asserted {@code reviews_count} was 1 and "a placeholder inside its 14 days still counts".</b>
     * That was true of the lifecycle {@code reviews.origin} replaced in V106, where an example
     * counted toward the cached average for a fortnight and then stopped. It is the mechanism that
     * put a fabricated rating on a brand-new ghost manager's tile, and {@code recalculate}'s own
     * comment records its removal: "the weight/expiry clause that used to be here is GONE... an
     * example must now contribute nothing, ever, so the exclusion is by provenance and the window
     * is irrelevant".
     *
     * <p>So the number this asserts is 0, and it is a genuine zero rather than a missing one: a
     * manager holding only a demonstration has not been rated. The demonstration is still shown -
     * {@code findByManager} returns it, flagged - it simply is not arithmetic.
     *
     * <p>The window is still set on the row, deliberately, so that this proves exclusion by
     * provenance and not merely that an expired row is ignored. That latter claim is
     * {@code recalculate_stopsCountingAPlaceholderWhoseWeightHasExpired}, which now passes for
     * this reason rather than its original one.
     */
    @Test
    void recalculate_excludesAnExampleEvenInsideTheOldWindow() throws Exception {
        long managerId = insertManagerWithSeed("Fresh Corp", "Frank Fresh", "now() + INTERVAL '14 days'");

        await(managerRepo.recalculate(managerId));

        Row cached = await(pool.preparedQuery(
                "SELECT reviews_count, overall_rating FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));
        assertEquals(0, cached.getInteger("reviews_count"),
            "an example contributes nothing to the count, window open or not");
        assertNull(cached.getBigDecimal("overall_rating"),
            "and no average either - a manager with only a demonstration has no rating");

        assertEquals(1L, countReviews(managerId, true),
            "while the demonstration itself is still there to be shown");
    }

    /**
     * What the nightly job iterates: managers whose cached count disagrees with their real ratings.
     *
     * <p>REWRITTEN from {@code findManagersWithExpiredWeights_returnsOnlyTheStaleOnes}. The old
     * query found managers holding a placeholder whose weight window had lapsed and compared the
     * cache against a count computed from that same window. Both halves described the lifecycle
     * V106 replaced, and the old test could not see either consequence, because every fixture it
     * used was an example and an example is outside both sides of the comparison.
     *
     * <p>The third assertion here is the one that matters, and it is a regression test for a
     * defect found in production on 2026-11-16: a placeholder still classified
     * {@code origin = 'contributor'} made the old query report the same manager as stale every
     * night forever. The cache said 1, because {@code recalculate} counts that row as genuine; the
     * window-based count said 0, because the window had lapsed; recalculating changed neither.
     * The job printed a line claiming it had fixed something, every night, having fixed nothing.
     */
    @Test
    void findManagersWithStaleRatingCache_reportsRealDriftOnceAndThenGoesQuiet() throws Exception {
        long stale = insertManagerWithExpiredSeed("Stale Corp", "Sam Stale");
        long fresh = insertManagerWithSeed("Current Corp", "Cara Current", "now() + INTERVAL '14 days'");

        // The fixture writes reviews_count = 1 on both, which is wrong for both: each holds only
        // an example. So both are genuinely drifted, and the window has nothing to do with it.
        java.util.Set<Long> before = staleCacheIds();
        assertTrue(before.contains(stale), "a cached count of 1 against no genuine rating is drift");
        assertTrue(before.contains(fresh),
            "and the window is irrelevant to that - this manager's window is wide open");

        await(managerRepo.recalculate(stale));
        await(managerRepo.recalculate(fresh));

        java.util.Set<Long> after = staleCacheIds();
        assertFalse(after.contains(stale), "once recalculated it must stop being reported");
        assertFalse(after.contains(fresh), "the pass converges, or the job never goes quiet");

        /*
            The production shape, and the livelock. A legacy placeholder classified 'contributor'
            IS genuine as far as recalculate is concerned, so once recalculated the cache and the
            count agree at 1 and the manager must go quiet. The old query compared the cache
            against the window instead, so it reported this manager forever.
        */
        long misclassified = insertManagerWithLegacyPlaceholder("Legacy Corp", "Lee Legacy",
                                                                "now() - INTERVAL '1 day'");
        await(managerRepo.recalculate(misclassified));
        assertFalse(staleCacheIds().contains(misclassified),
            "a misclassified placeholder must not be reported stale for ever: recalculate counts "
          + "it, so after one pass the cache and the count agree and there is nothing left to do");
    }

    private java.util.Set<Long> staleCacheIds() throws Exception {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        await(managerRepo.findManagersWithStaleRatingCache()).forEach(r -> ids.add(r.getLong("id")));
        return ids;
    }

    /**
     * The number above the list equals the list.
     *
     * <p>{@code findByManager} shows a platform example permanently, by origin.
     * {@code countByManager} counted only rows inside the legacy weight window, so an example
     * whose window had lapsed was listed and not counted: a profile showing one demonstration
     * rating under a heading that said there were none. Reachable in production the moment V108
     * reclassifies a legacy placeholder whose window has already passed, which is true of at least
     * one of the three rows found on 2026-11-16.
     */
    @Test
    void theCountAboveTheListMatchesTheList_forAnExamplePastItsOldWindow() throws Exception {
        long managerId = insertManagerWithExpiredSeed("Tally Corp", "Tina Tally");

        long listed = await(reviewRepo.findByManager(managerId, 50, 0, "recent", null, null, false))
            .size();
        long counted = await(reviewRepo.countByManager(managerId, null, null, false));

        assertEquals(1L, listed, "the example is shown - it is a permanent demonstration");
        assertEquals(listed, counted, "and the count above it must say the same number");
    }

    private long insertManagerWithExpiredSeed(String company, String managerName) throws Exception {
        return insertManagerWithSeed(company, managerName, "now() - INTERVAL '1 day'");
    }

    /**
     * A manager carrying an UNCLASSIFIED legacy placeholder: {@code weight = TRUE},
     * {@code user_id NULL}, {@code origin = 'contributor'}.
     *
     * <p>This is the exact shape of the three rows found in production on 2026-11-16, and it is the
     * only shape the expiry sweep can still act on. It cannot be produced by an INSERT any more:
     * V108 installs a BEFORE INSERT trigger that reclassifies precisely this combination, which is
     * the defect's fix. So the row is made the only way it can still come about - written, then
     * changed by an UPDATE, which that trigger does not see.
     *
     * <p>Building it any other way is what made four tests in this file fail against V108: their
     * fixtures named no origin at all, so the column default made them 'contributor' before V108
     * and the trigger made them examples after it. The tests were not asserting what they appeared
     * to assert; they were asserting the behaviour of a misclassified row.
     */
    private long insertManagerWithLegacyPlaceholder(String company, String managerName,
                                                    String expiresSql) throws Exception {
        long managerId = insertManagerWithSeed(company, managerName, expiresSql);
        await(pool.preparedQuery(
                "UPDATE reviews SET origin = 'contributor' WHERE manager_id = $1 AND weight = TRUE")
            .execute(Tuple.of(managerId)).mapEmpty());
        return managerId;
    }

    /** A manager carrying one weighted placeholder review whose expiry is the given SQL expression. */
    private long insertManagerWithSeed(String company, String managerName, String expiresSql) throws Exception {
        long companyId = await(pool.preparedQuery(
                "INSERT INTO companies(name,status,slug) VALUES ($1,'ghost',$2) ON CONFLICT DO NOTHING RETURNING id")
            .execute(Tuple.of(company, company.toLowerCase().replaceAll("[^a-z0-9]+", "-")))
            .compose(rs -> rs.iterator().hasNext()
                ? Future.succeededFuture(rs.iterator().next().getLong("id"))
                : pool.preparedQuery("SELECT id FROM companies WHERE LOWER(TRIM(name)) = LOWER(TRIM($1))")
                      .execute(Tuple.of(company)).map(r2 -> r2.iterator().next().getLong("id"))));

        long managerId = await(pool.preparedQuery(
                "INSERT INTO managers(name, company, company_id, title, image, status, approval_status, " +
                "overall_rating, reviews_count, category_averages) " +
                "VALUES ($1,$2,$3,'VP','img','active','approved',4.0,1,'{}') RETURNING id")
            .execute(Tuple.of(managerName, company, companyId))
            .map(rs -> rs.iterator().next().getLong("id")));

        await(pool.preparedQuery(
                "INSERT INTO reviews(manager_id, author, overall_rating, manager_company, manager_title, " +
                "worked_from, weight, weight_expires_on, created_at) " +
                "VALUES ($1,'Placeholder',4.0,$2,'VP',CURRENT_DATE - 30, TRUE, " + expiresSql + ", now())")
            .execute(Tuple.of(managerId, company)).mapEmpty());
        return managerId;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
