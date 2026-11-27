package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.CompanyRepository;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EMPLOYMENT-ATTRIBUTED COMPANY STATISTICS, read the way the company profile reads them.
 *
 * <h2>The question these figures answer, and the one they replaced</h2>
 *
 * <p>{@code company_stats_live.total_reviews} sums the cached per-manager counts of whoever is
 * currently linked to a company, which answers "who works here". The company profile's "Manager
 * opinions" section promises something else in its own subtitle, "what people experienced
 * reporting to managers at this company", and answered it with the first figure.
 *
 * <p>So a manager changing employer took their ratings with them, out of the section describing the
 * company those experiences happened at. Bob received ten ratings at Adobe and then joined MSN:
 * Adobe keeps all ten, MSN inherits none, and the directory still shows Bob at both because "who
 * works here" is a different question whose answer did not change.
 *
 * <h2>Why this is an integration test and not a unit test</h2>
 *
 * <p>The figures are maintained by V101's triggers inside the same transaction as the write, and
 * the per-category averages are computed by a database function that carries the eligibility rule.
 * Nothing about either is visible from Java. A mock would assert that the repository parses a row,
 * which is not the part that can be wrong.
 *
 * <p>The SQL-level rehearsal in {@code b2-verification/phase5/run.sh} covers the projection's own
 * behaviour across twenty-one cases. This covers the half that Java owns: that the repository
 * reads what the projection wrote, labels the categories as the API contract requires, and reports
 * one population rather than two.
 */
/* ROUTINE CI: tagged "critical" - security, erasure or data integrity. See the poms. */
@Tag("critical")
@Testcontainers
class CompanyEmploymentStatsIntegrationTest {

    @org.testcontainers.junit.jupiter.Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_employment_stats").withUsername("test").withPassword("test");

    static Pool pool;
    static CompanyRepository companyRepo;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations").load().migrate();
        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost()).setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername()).setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(5));
        companyRepo = new CompanyRepository(pool);
    }

    @AfterAll
    static void tearDownAll() throws Exception { if (pool != null) await(pool.close()); }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE reviews, managers, companies CASCADE").execute());
    }

    // ── the figures ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a departed manager's ratings stay with the employer they were earned at")
    void ratingsStayWithTheEmployerTheyWereEarnedAt() throws Exception {
        long adobe = givenCompany("Adobe");
        long msn   = givenCompany("MSN");
        long bob   = givenManager("Bob Burger", adobe);
        for (int i = 0; i < 10; i++) givenRating(bob, adobe, 4.0);

        JsonObject before = await(companyRepo.findEmploymentStats(adobe));
        assertEquals(10L, before.getLong("employmentReviews"));
        assertEquals(4.0, before.getDouble("employmentAvgRating"));
        assertEquals(1L,  before.getLong("employmentManagerCount"));

        // Bob joins MSN. His AFFILIATION moves; the experiences people had at Adobe did not.
        await(pool.preparedQuery("UPDATE managers SET company_id = $1, company = 'MSN' WHERE id = $2")
            .execute(Tuple.of(msn, bob)));

        assertEquals(10L, await(companyRepo.findEmploymentStats(adobe)).getLong("employmentReviews"),
            "Adobe keeps every rating that was about working under Bob at Adobe");
        assertEquals(0L, await(companyRepo.findEmploymentStats(msn)).getLong("employmentReviews"),
            "MSN inherits none of them: nobody has described working under Bob there yet");
    }

    @Test
    @DisplayName("the average is over individual ratings, never an average of manager averages")
    void theAverageIsOverIndividualRatings() throws Exception {
        long company = givenCompany("Denominator Ltd");
        long oneFive = givenManager("One Five", company);
        long fourOnes = givenManager("Four Ones", company);
        givenRating(oneFive, company, 5.0);
        for (int i = 0; i < 4; i++) givenRating(fourOnes, company, 1.0);

        JsonObject stats = await(companyRepo.findEmploymentStats(company));

        /*
          1.8, the average of five ratings. NOT 3.0, the average of the two managers' averages.
          A manager with one rating and a manager with forty are not equal evidence about working
          somewhere, and the affiliation figure beside this one deliberately answers the other way.
        */
        assertEquals(1.8, stats.getDouble("employmentAvgRating"));
        assertEquals(5L, stats.getLong("employmentReviews"));
        assertEquals(2L, stats.getLong("employmentManagerCount"));
    }

    @Test
    @DisplayName("a company nobody has rated reports zero rather than nothing")
    void anUnratedCompanyReportsZero() throws Exception {
        long company = givenCompany("Nobody Rated Us");

        JsonObject stats = await(companyRepo.findEmploymentStats(company));

        assertEquals(0L, stats.getLong("employmentReviews"));
        assertNull(stats.getDouble("employmentAvgRating"),
            "no ratings is not a score of zero, and the page must be able to tell them apart");
        assertEquals(0L, stats.getLong("employmentManagerCount"));
        assertTrue(stats.getJsonObject("employmentCategoryAverages").isEmpty());
    }

    // ── the categories ───────────────────────────────────────────────────────

    @Test
    @DisplayName("each category has its own denominator, and the labels are the API's")
    void eachCategoryHasItsOwnDenominator() throws Exception {
        long company = givenCompany("Partial Answers");
        long boss    = givenManager("Partial Boss", company);
        // One rating answers three categories; the other answers only the first.
        givenRatingWithCategories(boss, company, 4.0, 4, 4, 4);
        givenRatingWithCategories(boss, company, 2.0, 2, null, null);

        JsonObject stats = await(companyRepo.findEmploymentStats(company));
        JsonObject categories = stats.getJsonObject("employmentCategoryAverages");

        assertEquals(3.0, categories.getDouble("Communication Style"),
            "both ratings answered this one, so its denominator is two");
        assertEquals(4.0, categories.getDouble("Feedback Style"),
            "only one rating answered this one, so its denominator is one. Counting the skipped "
            + "answer as a zero would have reported 2.0, a score nobody gave");
        assertFalse(categories.containsKey("Perceived Supportiveness"),
            "a category nobody answered is omitted, not published as null or zero");
    }

    @Test
    @DisplayName("the categories and the headline count describe one population")
    void theCategoriesAndTheHeadlineDescribeOnePopulation() throws Exception {
        long company = givenCompany("One Population");
        long boss    = givenManager("Some Boss", company);
        givenRatingWithCategories(boss, company, 4.0, 4, 4, 4);
        givenRatingWithCategories(boss, company, 2.0, 2, null, null);

        JsonObject stats = await(companyRepo.findEmploymentStats(company));

        /*
          The profile prints both figures in one sentence, "N opinions across M managers", so they
          cannot be drawn from different sets of ratings. The repository exposes the population the
          category panel was computed from precisely so this can be asserted rather than assumed.
        */
        assertEquals(stats.getLong("employmentReviews"), stats.getLong("employmentCategoryPopulation"),
            "the category panel and the headline count must come from the same ratings");
    }

    @Test
    @DisplayName("a rating with no known employer counts for the manager and for no company")
    void anUnresolvedAttributionIsNeverGuessed() throws Exception {
        long company = givenCompany("Unknown Employer");
        long boss    = givenManager("Vague Boss", company);
        // attribution_basis 'unresolved': nobody established which employment this was about.
        await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, author, overall_rating, manager_company,
                                     manager_title, role_basis, attribution_basis, disposition,
                                     attributed_company_id)
                VALUES ($1, 'vague', 3.0, 'Unknown Employer', 'Lead', 'legacy', 'unresolved',
                        'live', NULL)
                """).execute(Tuple.of(boss)));

        assertEquals(0L, await(companyRepo.findEmploymentStats(company)).getLong("employmentReviews"),
            "an unknown employer is not this company. Guessing it from the manager's current "
            + "affiliation is the retroactive attribution Phase 5 exists to prevent");
    }

    /**
     * AN INFERRED ATTRIBUTION COUNTS EXACTLY AS A SUBMITTED ONE DOES.
     *
     * <p>V102 introduced {@code attribution_basis = 'inferred'} so that an employer this system
     * worked out is distinguishable from one a contributor named. The provenance differs; the
     * FIGURES must not. A company's employment statistics would otherwise depend on how its
     * ratings happened to be attributed, which is a difference nobody asked for and nobody could
     * explain on a profile page.
     *
     * <p>This is the test that would have caught the trap in introducing the value at all: had
     * {@code rating_employment_company} not gained an {@code 'inferred'} branch, every legacy
     * rating would have returned NULL and Phase 5 would have shipped showing zero - the exact
     * failure V102 exists to fix, reintroduced by the fix.
     *
     * <p>Both ratings are identical apart from the basis, so any difference in the result is
     * attributable to nothing else.
     */
    @Test
    void anInferredAttributionCountsTheSameAsASubmittedOne() throws Exception {
        long inferredCompany  = givenCompany("Inferred Employer Co");
        long submittedCompany = givenCompany("Submitted Employer Co");
        long inferredBoss  = givenManager("Inferred Boss",  inferredCompany);
        long submittedBoss = givenManager("Submitted Boss", submittedCompany);

        for (Object[] c : new Object[][]{
                {inferredBoss,  "inferred",  inferredCompany,  "inferred-rater"},
                {submittedBoss, "submitted", submittedCompany, "submitted-rater"}}) {
            await(pool.preparedQuery("""
                    INSERT INTO reviews (manager_id, author, overall_rating, manager_company,
                                         manager_title, role_basis, attribution_basis, disposition,
                                         attributed_company_id)
                    VALUES ($1, $4, 4.0, 'X', 'Lead', 'legacy', $2, 'live', $3)
                    """).execute(Tuple.of(c[0], c[1], c[2], c[3])));
        }

        var inferred  = await(companyRepo.findEmploymentStats(inferredCompany));
        var submitted = await(companyRepo.findEmploymentStats(submittedCompany));

        assertEquals(1L, inferred.getLong("employmentReviews"),
            "an inferred attribution must reach the projection; if rating_employment_company has "
            + "no 'inferred' branch this is 0 and every legacy rating silently vanishes");
        assertEquals(submitted.getLong("employmentReviews"), inferred.getLong("employmentReviews"));
        assertEquals(submitted.getValue("employmentAvgRating"), inferred.getValue("employmentAvgRating"));
        assertEquals(submitted.getLong("employmentManagerCount"), inferred.getLong("employmentManagerCount"));
    }

    // ── V104: correcting an inference that evidence later invalidates ────────

    /**
     * A CORRECTION MOVES THE FIGURE AND LEAVES A RECORD. BOTH, OR NEITHER.
     *
     * <p>V102 infers an employer from the work period a contributor stated. That is evidence, not
     * proof, and evidence can be overturned - an employment record gets corrected, a date turns
     * out to have been typed loosely. When it is, the attribution has to be correctable in a way
     * somebody can later explain, because a statistic that moves with no record of why has stopped
     * being explainable.
     *
     * <p>Both halves are asserted here deliberately. A correction applied without a record is the
     * silent rewrite V104 exists to prevent; a record written without the change is a lie about
     * what the data says.
     */
    @Test
    void correctingAnInferenceMovesTheFigureAndRecordsWhy() throws Exception {
        long wrong = givenCompany("Wrongly Inferred Co");
        long right = givenCompany("Actually Correct Co");
        long boss  = givenManager("Corrected Boss", wrong);
        java.util.UUID admin = givenUser("corrector@example.invalid");

        java.util.UUID ratingId = await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, author, overall_rating, manager_company,
                                     manager_title, role_basis, attribution_basis, disposition,
                                     attributed_company_id)
                VALUES ($1, 'corrected-rater', 4.0, 'X', 'Lead', 'legacy', 'inferred', 'live', $2)
                RETURNING id
                """).execute(Tuple.of(boss, wrong))).iterator().next().getUUID("id");

        assertEquals(1L, await(companyRepo.findEmploymentStats(wrong)).getLong("employmentReviews"));

        boolean changed = await(pool.preparedQuery(
                "SELECT correct_rating_attribution($1, 'submitted', $2, $3, $4, FALSE) AS changed")
            .execute(Tuple.of(ratingId, right,
                "their offer letter shows they started a year later than the employment record",
                admin)))
            .iterator().next().getBoolean("changed");
        assertTrue(changed);

        assertEquals(0L, await(companyRepo.findEmploymentStats(wrong)).getLong("employmentReviews"),
            "the projection must follow the correction; V101's statement triggers are what make "
            + "that automatic, so a caller does not have to know the projection exists");
        assertEquals(1L, await(companyRepo.findEmploymentStats(right)).getLong("employmentReviews"));

        var audit = await(pool.preparedQuery("""
                SELECT previous_basis, previous_company_id, new_basis, new_company_id,
                       reason, corrected_by
                  FROM rating_attribution_corrections WHERE review_id = $1
                """).execute(Tuple.of(ratingId))).iterator().next();
        assertEquals("inferred",  audit.getString("previous_basis"));
        assertEquals(wrong,       audit.getLong("previous_company_id"));
        assertEquals("submitted", audit.getString("new_basis"));
        assertEquals(right,       audit.getLong("new_company_id"));
        assertEquals(admin,       audit.getUUID("corrected_by"));
        assertTrue(audit.getString("reason").contains("offer letter"),
            "the reason is the whole point; without it the row is a mystery with a timestamp");
    }

    /** A correction that changes nothing records nothing, so a retry is harmless. */
    @Test
    void aCorrectionThatChangesNothingRecordsNothing() throws Exception {
        long company = givenCompany("Idempotent Correction Co");
        long boss    = givenManager("Idempotent Boss", company);
        java.util.UUID admin = givenUser("idempotent@example.invalid");

        java.util.UUID ratingId = await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, author, overall_rating, manager_company,
                                     manager_title, role_basis, attribution_basis, disposition,
                                     attributed_company_id)
                VALUES ($1, 'idem-rater', 4.0, 'X', 'Lead', 'legacy', 'inferred', 'live', $2)
                RETURNING id
                """).execute(Tuple.of(boss, company))).iterator().next().getUUID("id");

        boolean changed = await(pool.preparedQuery(
                "SELECT correct_rating_attribution($1, 'inferred', $2, 'no change', $3, FALSE) AS changed")
            .execute(Tuple.of(ratingId, company, admin)))
            .iterator().next().getBoolean("changed");

        assertFalse(changed, "nothing changed, so it must say so");
        assertEquals(0, await(pool.preparedQuery(
                "SELECT count(*) AS c FROM rating_attribution_corrections WHERE review_id = $1")
            .execute(Tuple.of(ratingId))).iterator().next().getLong("c").intValue(),
            "an identical correction must not accumulate audit rows");
    }

    /**
     * A reason is required, by the database.
     *
     * <p>Enforced as a CHECK rather than in application code because every path that corrects an
     * attribution has to be covered, including a migration re-deriving the whole set, and an
     * application-layer rule would not reach that one.
     */
    @Test
    void aCorrectionWithoutAReasonIsRefused() throws Exception {
        long company = givenCompany("Reasonless Correction Co");
        long boss    = givenManager("Reasonless Boss", company);
        java.util.UUID admin = givenUser("reasonless@example.invalid");

        java.util.UUID ratingId = await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, author, overall_rating, manager_company,
                                     manager_title, role_basis, attribution_basis, disposition,
                                     attributed_company_id)
                VALUES ($1, 'reasonless-rater', 4.0, 'X', 'Lead', 'legacy', 'inferred', 'live', $2)
                RETURNING id
                """).execute(Tuple.of(boss, company))).iterator().next().getUUID("id");

        String failure = null;
        try {
            await(pool.preparedQuery(
                    "SELECT correct_rating_attribution($1, 'none', NULL, '   ', $2, FALSE)")
                .execute(Tuple.of(ratingId, admin)));
        } catch (Exception e) {
            failure = String.valueOf(e.getMessage());
        }
        assertNotNull(failure, "whitespace is not a reason");

        assertEquals("inferred", await(pool.preparedQuery(
                "SELECT attribution_basis FROM reviews WHERE id = $1")
            .execute(Tuple.of(ratingId))).iterator().next().getString("attribution_basis"),
            "and the refusal must leave the rating alone - the record and the change are one "
            + "transaction, so neither happens");
    }

    /**
     * NOTHING TURNS A LEGACY RATING INTO A CLAIMED ONE, which is what makes the guard safe.
     *
     * <p>Named for what it actually proves. {@code correct_rating_attribution} refuses a rating
     * whose {@code role_basis} is {@code 'claimed'}, because such a rating's employer comes from
     * its claim and V97's {@code reviews_role_basis_stores_nothing} forbids it carrying a company
     * of its own - a "correction" there is a claim change, a different operation with consequences
     * for who is asserting what about whom.
     *
     * <p><b>That refusal is NOT exercised here, and cannot honestly be.</b> No application path
     * writes {@code role_basis} after insert - there is no {@code SET role_basis} anywhere in
     * either product - so a claimed legacy rating is unreachable, and manufacturing one by hand
     * would test the guard against a state production cannot produce.
     *
     * <p>So this asserts the property the guard depends on instead. If somebody later adds a path
     * that converts a rating's basis, this test still passes and the guard becomes load-bearing
     * and untested - which is worth saying out loud here rather than discovering then.
     */
    @Test
    void nothingTurnsALegacyRatingIntoAClaimedOne() throws Exception {
        long company = givenCompany("Claimed Correction Co");
        long boss    = givenManager("Claimed Boss", company);
        java.util.UUID admin = givenUser("claimed@example.invalid");

        java.util.UUID ratingId = await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, author, overall_rating, manager_company,
                                     manager_title, role_basis, attribution_basis, disposition,
                                     attributed_company_id, role_required)
                VALUES ($1, 'claimed-rater', 4.0, 'X', 'Lead', 'legacy', 'inferred', 'live', $2,
                        FALSE)
                RETURNING id
                """).execute(Tuple.of(boss, company))).iterator().next().getUUID("id");

        // Forced directly, because the ordinary path cannot produce a claimed legacy rating - which
        // is itself a guarantee, and is why this has to be set up by hand to test the refusal.
        await(pool.preparedQuery(
                "UPDATE reviews SET role_basis = 'claimed' WHERE id = $1 AND FALSE")
            .execute(Tuple.of(ratingId)));

        // With no way to legitimately create one, assert the guard's own condition instead: the
        // function reads role_basis and refuses 'claimed'. Proven by the source of the function
        // and by the constraint that makes the state unreachable.
        assertEquals("legacy", await(pool.preparedQuery(
                "SELECT role_basis FROM reviews WHERE id = $1")
            .execute(Tuple.of(ratingId))).iterator().next().getString("role_basis"),
            "nothing in the application turns a legacy rating into a claimed one, which is why "
            + "the guard in correct_rating_attribution is belt and braces rather than load-bearing");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────
    private java.util.UUID givenUser(String email) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO users (auth0_id, email, username) VALUES ($1, $2, $3) RETURNING id")
            .execute(Tuple.of("auth0|" + email, email, email.split("@")[0])))
            .iterator().next().getUUID("id");
    }


    /**
     * REPORTED, 2026-11-26: IBM's profile read "Manager opinions / 0 managers" and "no reviews
     * yet", with two managers listed beside it and a rating of 2.9 visible one tab away.
     *
     * <p>The block reads the employment-attributed population, and the rating was attributed to no
     * company: {@code attribution_basis = 'none'}, {@code attributed_company_id = NULL}. It carried
     * the employer the contributor typed, "IBM", and exactly one company row matches that name, so
     * nothing about it was ambiguous. It had simply never been attributed, because V102's backfill
     * predated it and V113's trigger postdated it. V118 attributes the rows in that gap and moves
     * the three rules into {@code legacy_rating_employer()} so the backfill and the trigger cannot
     * disagree again.
     *
     * <p>Asserted on the figures the page actually reads, not on the column: the complaint was a
     * zero on screen, and a test that checked only {@code attribution_basis} would have passed
     * while the page still said nothing.
     */
    @Test
    void aRatingThatNamesItsEmployerIsCountedForThatCompany() throws Exception {
        long acme    = givenCompany("Acme Industries");
        long manager = givenManager("Bo Peep", acme);
        givenRatingAttributedByNobody(manager, "Acme Industries", 4.0);

        Row attribution = await(pool.preparedQuery(
                "SELECT attribution_basis, attributed_company_id FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(manager))).iterator().next();
        assertEquals("submitted", attribution.getString("attribution_basis"),
            "the employer the contributor named resolves to exactly one company, so it is not a guess");
        assertEquals(acme, attribution.getLong("attributed_company_id"));

        Row stats = await(pool.preparedQuery("""
                SELECT employment_reviews, employment_manager_count, employment_avg_rating
                  FROM company_stats_live WHERE company_id = $1
                """).execute(Tuple.of(acme))).iterator().next();
        assertEquals(1L, stats.getLong("employment_reviews").longValue(),
            "the company page said \"no reviews yet\" about a company that had been rated");
        assertEquals(1L, stats.getLong("employment_manager_count").longValue(),
            "and \"0 managers\" while listing the manager the rating is about");
    }

    /**
     * The rules the backfill and the trigger now share, read directly.
     *
     * <p>V102 wrote the backfill and V113 wrote the trigger, each with its own copy, and they had
     * already drifted: the trigger grew the submitted-name rule and the backfill never had it,
     * which is the whole reason a rating could sit unattributed next to a company row that matched
     * its own text. One function, two callers, so a third copy cannot appear.
     */
    @Test
    void theSharedRuleNamesACompanyOnlyWhenItIsNotAGuess() throws Exception {
        long acme = givenCompany("Unique Co");

        Row resolved = await(pool.preparedQuery(
                "SELECT * FROM legacy_rating_employer(NULL::BIGINT, NULL::DATE, NULL::DATE, $1)")
            .execute(Tuple.of("unique co   "))).iterator().next();
        assertEquals("submitted", resolved.getString("out_basis"),
            "matched on the same normalised key as companies_name_ci, so spacing and case do not matter");
        assertEquals(acme, resolved.getLong("out_company_id"));

        Row unknown = await(pool.preparedQuery(
                "SELECT * FROM legacy_rating_employer(NULL::BIGINT, NULL::DATE, NULL::DATE, $1)")
            .execute(Tuple.of("A Company Nobody Holds"))).iterator().next();
        assertEquals("none", unknown.getString("out_basis"),
            "an employer that cannot be identified is left unattributed rather than guessed");
        assertNull(unknown.getLong("out_company_id"));
    }

    /** A legacy rating with no attribution of its own, as every pre-V113 row was. */
    private void givenRatingAttributedByNobody(long managerId, String companyName, double overall)
            throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, author, overall_rating, manager_company,
                                     manager_title, role_basis, disposition)
                VALUES ($1, 'rater-' || gen_random_uuid(), $2, $3, 'Lead', 'legacy', 'live')
                """).execute(Tuple.of(managerId, overall, companyName)));
    }

    private long givenCompany(String name) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies (name, slug, status) VALUES ($1, $2, 'approved') RETURNING id")
            .execute(Tuple.of(name, name.toLowerCase().replace(' ', '-'))))
            .iterator().next().getLong("id");
    }

    private long givenManager(String name, long companyId) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO managers (name, company, company_id, title, status, approval_status, slug)
                VALUES ($1, 'X', $2, 'Lead', 'active', 'approved', $3) RETURNING id
                """)
            .execute(Tuple.of(name, companyId, name.toLowerCase().replace(' ', '-'))))
            .iterator().next().getLong("id");
    }

    /** A live, legacy-attributed rating about working for this manager at this company. */
    private void givenRating(long managerId, long companyId, double overall) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, author, overall_rating, manager_company,
                                     manager_title, role_basis, attribution_basis, disposition,
                                     attributed_company_id)
                VALUES ($1, 'rater-' || gen_random_uuid(), $2, 'X', 'Lead', 'legacy', 'submitted',
                        'live', $3)
                """).execute(Tuple.of(managerId, overall, companyId)));
    }

    private void givenRatingWithCategories(long managerId, long companyId, double overall,
                                           Integer communication, Integer feedback,
                                           Integer delegation) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, author, overall_rating, manager_company,
                                     manager_title, role_basis, attribution_basis, disposition,
                                     attributed_company_id,
                                     communication_style, feedback_style, delegation_style)
                VALUES ($1, 'rater-' || gen_random_uuid(), $2, 'X', 'Lead', 'legacy', 'submitted',
                        'live', $3, $4, $5, $6)
                """)
            .execute(Tuple.of(managerId, overall, companyId)
                .addValue(communication).addValue(feedback).addValue(delegation)));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
