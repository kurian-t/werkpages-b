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

    // ── Contributor rates ghost → seed kept on 14-day expiry counter ─────────

    @Test
    void contributor_ratesGhostManager_seedScheduledForExpiry() throws Exception {
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

        // 4. Both reviews must still exist (seed + user C's real review).
        assertEquals(1, countSeedReviews(ghostId),
            "Seed review must NOT be deleted when a contributor rates the ghost manager");
        assertEquals(2, countTotalReviews(ghostId),
            "Both seed and contributor's real review must coexist");

        // 5. Seed must now have weight_expires_on set (14-day countdown started).
        RowSet<Row> seedRows = await(pool.preparedQuery(
            "SELECT weight_expires_on FROM reviews WHERE manager_id = $1 AND weight = TRUE AND user_id IS NULL")
            .execute(Tuple.of(ghostId)));
        assertTrue(seedRows.iterator().hasNext(), "Seed review row must still exist");
        assertNotNull(seedRows.iterator().next().getLocalDate("weight_expires_on"),
            "weight_expires_on must be set on the seed review after a contributor rates the ghost");
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
        long expired  = insertManagerWithExpiredSeed("SweepCo", "Swept Manager");
        long counting = insertManagerWithSeed("KeepCo", "Kept Manager", "CURRENT_DATE + 5");

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
        long guarded = insertManagerWithSeed("GuardCo", "Guarded Manager", "CURRENT_DATE - 1");
        String ownerAuth0 = insertUser("auth0|seedguard", "seedguarduser");
        await(pool.preparedQuery(
                "UPDATE reviews SET user_id = (SELECT id FROM users WHERE auth0_id = $2) "
              + "WHERE manager_id = $1 AND weight = TRUE")
            .execute(Tuple.of(guarded, ownerAuth0)).mapEmpty());

        int deleted = await(reviewRepo.deleteExpiredSeedReviews());
        assertEquals(1, deleted, "exactly the one authorless placeholder past its date");
        assertEquals(1L, countReviews(guarded, true),
            "a weighted row with a real author is never swept, whatever its expiry says");

        assertEquals(0L, countReviews(expired, true),  "the expired placeholder is gone");
        assertEquals(1L, countReviews(expired, false), "the real review on that manager survives");
        assertEquals(1L, countReviews(counting, true), "a placeholder still counting down survives");
    }

    /** The placeholder's expiry date as the database holds it. */
    private java.time.LocalDate readSeedExpiry(long managerId) throws Exception {
        return await(pool
            .preparedQuery("SELECT weight_expires_on FROM reviews WHERE manager_id = $1 AND weight = TRUE")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLocalDate("weight_expires_on")));
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

        assertEquals(1L, countSeeds(managerId), "the placeholder goes on a timer, it is not deleted now");
        assertNotNull(readSeedExpiry(managerId),
            "a real review must start the placeholder's countdown - left NULL it counts toward the "
          + "manager's average forever, which is the mean of a genuine review and a fabricated one");
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

    @Test
    void recalculate_keepsAPlaceholderThatHasNotExpiredYet() throws Exception {
        long managerId = insertManagerWithSeed("Fresh Corp", "Frank Fresh", "now() + INTERVAL '14 days'");

        await(managerRepo.recalculate(managerId));

        Integer cached = await(pool.preparedQuery("SELECT reviews_count FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getInteger("reviews_count")));
        assertEquals(1, cached, "a placeholder inside its 14 days still counts");
    }

    @Test
    void findManagersWithExpiredWeights_returnsOnlyTheStaleOnes() throws Exception {
        // What the daily job iterates. It must find the manager whose cached count is now wrong,
        // and must not keep returning one it has already fixed, or the job never goes quiet.
        long stale = insertManagerWithExpiredSeed("Stale Corp", "Sam Stale");
        long fresh = insertManagerWithSeed("Current Corp", "Cara Current", "now() + INTERVAL '14 days'");

        var before = await(managerRepo.findManagersWithExpiredWeights());
        java.util.Set<Long> ids = new java.util.HashSet<>();
        before.forEach(r -> ids.add(r.getLong("id")));
        assertTrue(ids.contains(stale), "the manager with an expired placeholder is stale");
        assertFalse(ids.contains(fresh), "one still inside its window is not");

        await(managerRepo.recalculate(stale));

        var after = await(managerRepo.findManagersWithExpiredWeights());
        java.util.Set<Long> afterIds = new java.util.HashSet<>();
        after.forEach(r -> afterIds.add(r.getLong("id")));
        assertFalse(afterIds.contains(stale), "once recalculated it must stop being reported");
    }

    private long insertManagerWithExpiredSeed(String company, String managerName) throws Exception {
        return insertManagerWithSeed(company, managerName, "now() - INTERVAL '1 day'");
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
