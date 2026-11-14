package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.service.RatingOpportunityService;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PHASE 4, V104: the rating allowance.
 *
 * <h2>The policy these tests pin</h2>
 *
 * <p>Two opportunities per contributor per subject, counted independently by type and subject, and
 * <b>an opportunity is consumed when one is OPENED, never when a rating is deleted</b>. Everything
 * here follows from that sentence. Counting submissions would make editing, retrying and replacing
 * all cost allowance; counting opportunities means a replacement inside the three-day window is
 * free, which is what was asked for.
 *
 * <h2>Why a cap being reached is not an error</h2>
 *
 * <p>{@link RatingOpportunityService#charge} returns an empty result rather than failing. A
 * contributor who has used both of their opportunities needs a message, not a 500, and a path that
 * throws here would have to be caught and translated at every call site, which is the sort of rule
 * the fifth call site gets wrong.
 *
 * <h2>The privacy rule, tested from the catalogue rather than by inspection</h2>
 *
 * <p><b>No column of either table may ever hold a rating id.</b> Two separate revisions put one
 * there, and both were identity-reconstruction defects: a value equal to a review id, in a row that
 * also carries {@code user_id}, recovers the author of an anonymised rating in a single join and
 * needs no foreign key to do it. "The reference points the other way" is not a defence, because a
 * join has no direction. {@link #noColumnOfTheAllowanceTablesCanHoldARatingId} derives the check
 * from {@code information_schema} so that a column added later fails it without anybody remembering
 * to extend the test.
 */
@Testcontainers
class RatingOpportunityIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("rating_opportunities")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static RatingOpportunityService opportunities;

    long companyId;
    long managerId;
    long otherManagerId;
    UUID contributor;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations").load().migrate();
        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(6));
        opportunities = new RatingOpportunityService();
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) {
            pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void cleanDb() throws Exception {
        // Each batch must name every table that references the ones in it: TRUNCATE refuses to
        // leave a referencing table behind. manager_merge_events is referenced by the claim audit
        // and the conflict tables, so they all go together.
        await(pool.query("TRUNCATE review_supersessions, role_conflict_reviews, "
            + "role_conflict_claims, role_conflicts, claim_resolution_events, claim_corrections, "
            + "rating_opportunity_retirements, manager_merge_events").execute());
        await(pool.query("TRUNCATE reviews, role_claims, rating_opportunities, career_history, "
            + "capture_attempts, captured_drafts, company_stats_live, managers, companies, users "
            + "CASCADE").execute());
        companyId      = insertCompany("Halverson Logistics");
        managerId      = insertManager("Priya Raghunathan", "priya-r");
        otherManagerId = insertManager("Dominic Achebe", "dom-a");
        contributor    = insertUser("auth0|allowance");
    }

    // -- the two opportunities -------------------------------------------------

    @Test
    void theFirstTwoChargesTakeOrdinalsOneAndTwoAndTheThirdIsRefused() throws Exception {
        assertEquals(OptionalInt.of(1), charge(null));
        assertEquals(OptionalInt.of(2), charge(null));

        // REFUSED, and not by an exception. The cap is a product outcome with a message, and the
        // ordinals are what enforce it: "at most two, distinguishable" is exactly
        // UNIQUE (user_id, subject_type, subject_id, ordinal) with ordinal IN (1,2).
        assertEquals(OptionalInt.empty(), charge(null));
        assertEquals(2, await(opportunities.spent(pool, contributor, "manager", managerId)));
        assertFalse(await(opportunities.hasCapacity(pool, contributor, "manager", managerId)));
    }

    @Test
    void theDatabaseRefusesAThirdOpportunityEvenIfTheServiceIsBypassed() throws Exception {
        charge(null);
        charge(null);

        // Belt and brace, and the brace is the one that matters: the cap is in the schema, so a
        // future call site that writes this table directly still cannot hand out a third.
        Exception e = assertThrows(Exception.class, () -> await(pool.preparedQuery(
            "INSERT INTO rating_opportunities (user_id, subject_type, subject_id, ordinal) "
            + "VALUES ($1,'manager',$2,3)").execute(Tuple.of(contributor, managerId))));
        assertTrue(message(e).contains("opportunity_ordinal_bounded"), message(e));

        Exception dup = assertThrows(Exception.class, () -> await(pool.preparedQuery(
            "INSERT INTO rating_opportunities (user_id, subject_type, subject_id, ordinal) "
            + "VALUES ($1,'manager',$2,2)").execute(Tuple.of(contributor, managerId))));
        assertTrue(message(dup).contains("opportunity_one_per_ordinal"), message(dup));
    }

    @Test
    void subjectsAreCountedIndependentlyByTypeAndById() throws Exception {
        charge(null);
        charge(null);

        // subject_type is part of the key, so the counters are logically independent: rating
        // manager A cannot affect manager B, cannot affect company A, and an interview review at
        // company A cannot affect a company rating at company A.
        assertTrue(await(opportunities.hasCapacity(pool, contributor, "manager", otherManagerId)));
        assertTrue(await(opportunities.hasCapacity(pool, contributor, "company", companyId)));
        assertTrue(await(opportunities.hasCapacity(pool, contributor, "interview", companyId)));

        assertEquals(OptionalInt.of(1), chargeFor("company", companyId, null));
        assertEquals(OptionalInt.of(1), chargeFor("interview", companyId, null));
        assertEquals(OptionalInt.of(1), chargeFor("manager", otherManagerId, null));

        // The manager subject is still full, and none of those touched it.
        assertEquals(2, await(opportunities.spent(pool, contributor, "manager", managerId)));
    }

    @Test
    void anAnonymousSubmissionCannotChargeAnOpportunity() throws Exception {
        // An allowance is keyed on an authenticated contributor, so there is nothing to charge it
        // to until there is an account. ADOPTION IS THE CHARGING EVENT: the signed-out person's
        // draft is captured, and the opportunity is charged in the transaction where they adopt it
        // under a real account and the rating is written.
        //
        // It FAILS rather than quietly doing nothing, because "the anonymous path skipped the
        // allowance" and "the allowance was never wired up" look identical in a bug report.
        Exception e = assertThrows(Exception.class, () -> await(pool.withTransaction(tx ->
            opportunities.charge(tx, null, "manager", managerId, null))));
        assertTrue(message(e).contains("anonymous"), message(e));
        assertEquals(0, countRows("rating_opportunities"));
    }

    @Test
    void anUnknownSubjectTypeIsRefusedBeforeItReachesTheDatabase() throws Exception {
        Exception e = assertThrows(Exception.class, () -> await(pool.withTransaction(tx ->
            opportunities.charge(tx, contributor, "workplace", companyId, null))));
        assertTrue(message(e).contains("Unknown rating subject type"), message(e));
    }

    // -- atomicity with the submission ----------------------------------------

    @Test
    void theChargeRollsBackWithTheSubmissionItAuthorises() throws Exception {
        // THE REASON charge() takes the caller's transaction rather than the pool. A charge that
        // commits beside a failed submission silently costs somebody half their lifetime allowance
        // for that subject, and they have nothing to show for it.
        Exception e = assertThrows(Exception.class, () -> await(pool.withTransaction(tx ->
            opportunities.lockSubject(tx, "manager", managerId)
                .compose(v -> opportunities.charge(tx, contributor, "manager", managerId, null))
                .compose(ordinal -> {
                    assertEquals(OptionalInt.of(1), ordinal);
                    // The submission this charge was authorising now fails.
                    return tx.query("INSERT INTO reviews (manager_id) VALUES (NULL)").execute();
                }))));
        assertNotNull(message(e));

        assertEquals(0, countRows("rating_opportunities"));
        assertEquals(0, await(opportunities.spent(pool, contributor, "manager", managerId)));
    }

    @Test
    void theChargeCommitsWithTheRatingInOneTransaction() throws Exception {
        UUID reviewId = await(pool.withTransaction(tx ->
            opportunities.lockSubject(tx, "manager", managerId)
                .compose(v -> opportunities.charge(tx, contributor, "manager", managerId, null))
                .compose(ordinal -> {
                    assertEquals(OptionalInt.of(1), ordinal);
                    return tx.preparedQuery(
                            "INSERT INTO reviews (manager_id, user_id, author, overall_rating, "
                            + "manager_company, manager_title) "
                            + "VALUES ($1,$2,'Ash B',4.0,'Halverson Logistics','Dispatch Lead') "
                            + "RETURNING id")
                        .execute(Tuple.of(managerId, contributor))
                        .map(rs -> rs.iterator().next().getUUID("id"));
                })));

        assertNotNull(reviewId);
        assertEquals(1, await(opportunities.spent(pool, contributor, "manager", managerId)));
    }

    @Test
    void underTheSubjectLockTwoConcurrentChargesCannotBothTakeTheLastOne() throws Exception {
        // One already spent, so exactly one opportunity remains to contend for.
        charge(null);

        SqlConnection first  = await(pool.getConnection());
        SqlConnection second = await(pool.getConnection());
        try {
            Transaction t1 = await(first.begin());
            Transaction t2 = await(second.begin());

            await(opportunities.lockSubject(first, "manager", managerId));
            OptionalInt won = await(opportunities.charge(first, contributor, "manager", managerId, null));
            assertEquals(OptionalInt.of(2), won);

            // The second transaction asks for the same subject row and WAITS. This is the whole
            // mechanism: an upsert cannot be used here, because ON CONFLICT refuses a deferrable
            // unique constraint as an arbiter, and opportunity_one_per_ordinal had to become
            // deferrable so the merge could move and renumber in one statement. So the read of the
            // free ordinal and the insert that takes it are serialised on the subject instead.
            Future<Void> blocked = opportunities.lockSubject(second, "manager", managerId);
            // PROVED by waiting, not by asking isComplete() straight away: every Vert.x query is
            // incomplete the instant it is issued, so that assertion would pass whether the row
            // were locked or not. There is no race in this direction either, because the row stays
            // locked until the commit below, so the wait cannot time out spuriously.
            assertThrows(java.util.concurrent.TimeoutException.class,
                () -> blocked.toCompletionStage().toCompletableFuture()
                    .get(500, TimeUnit.MILLISECONDS),
                "the second transaction must wait for the subject row");

            await(t1.commit());
            await(blocked);

            // It now sees the committed row and is properly refused rather than colliding.
            assertEquals(OptionalInt.empty(),
                await(opportunities.charge(second, contributor, "manager", managerId, null)));
            await(t2.commit());
        } finally {
            await(first.close());
            await(second.close());
        }
        assertEquals(2, await(opportunities.spent(pool, contributor, "manager", managerId)));
    }

    // -- provenance and privacy -----------------------------------------------

    @Test
    void twoOpportunitiesMayShareOneClaimBecauseTheClaimIsNotTheirIdentity() throws Exception {
        long claimId = insertClaim();

        assertEquals(OptionalInt.of(1), charge(claimId));
        // THE DEFECT THIS PINS. An earlier revision made origin_claim_id uniquely identify the
        // opportunity, which rejects this second insert: the claim identifies the EMPLOYMENT, which
        // is deliberately singular, while the opportunities on it are deliberately plural. The
        // index was written to catch a duplicated record of one opportunity and caught a legitimate
        // second opportunity instead.
        assertEquals(OptionalInt.of(2), charge(claimId));

        assertEquals(List.of(claimId, claimId), originClaimIds());
    }

    @Test
    void noColumnOfTheAllowanceTablesCanHoldARatingId() throws Exception {
        // SCHEMA-DRIVEN, and that is the point. A hand-written list of joins is how both
        // identity-reconstruction defects survived review: the original assertion walked the paths
        // somebody thought of. This derives the candidate columns from the catalogue, so a column
        // added later fails without anybody remembering to extend the test.
        //
        // reviews.id is UUID, so every uuid-typed column in a table that also carries a contributor
        // is a candidate path. The permitted ones are named here explicitly and nothing else is.
        List<String> uuidColumns = await(pool.query("""
                SELECT table_name || '.' || column_name AS c
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name IN ('rating_opportunities','rating_opportunity_retirements')
                   AND data_type = 'uuid'
                 ORDER BY 1
                """).execute().map(rs -> {
                    List<String> out = new ArrayList<>();
                    for (Row r : rs) out.add(r.getString("c"));
                    return out;
                }));

        assertEquals(List.of(
                // The contributor the allowance belongs to. This is the identity the rule is about
                // protecting, not a path in itself.
                "rating_opportunities.user_id",
                // A company merge's id, which is UUID because company_merges.id is. Constrained by
                // a foreign key to company_merges, so it cannot hold a review id.
                "rating_opportunity_retirements.company_merge_id",
                "rating_opportunity_retirements.user_id"),
            uuidColumns,
            "a new uuid column here is an identity-reconstruction path until proved otherwise: a "
            + "value equal to a review id, in a row that also carries user_id, recovers the author "
            + "of an anonymised rating in one join");

        // And no foreign key from either table points at reviews, in any column of any type.
        long toReviews = await(pool.query("""
                SELECT count(*) AS c
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.constraint_column_usage ccu
                    ON ccu.constraint_name = tc.constraint_name
                 WHERE tc.constraint_type = 'FOREIGN KEY'
                   AND tc.table_name IN ('rating_opportunities','rating_opportunity_retirements')
                   AND ccu.table_name = 'reviews'
                """).execute().map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(0, toReviews);
    }

    @Test
    void theAggregateReadCarriesNoContributorColumn() throws Exception {
        UUID other = insertUser("auth0|allowance-other");
        charge(null);
        await(pool.withTransaction(tx -> opportunities.charge(tx, other, "manager", managerId, null)));

        // The sanctioned operational interface for capacity questions: opportunities per subject,
        // with no contributor column, so they are answerable without identity. Rev 268 sanctions a
        // view for this; this repository does not use views as application architecture, so it is
        // repository SQL, which is also the only form that stays greppable.
        assertEquals(2, await(opportunities.openedForSubject(pool, "manager", managerId)));
        assertEquals(0, await(opportunities.openedForSubject(pool, "manager", otherManagerId)));
    }


    @Test
    void anOrdinalCollisionIsClassifiedAsContentionSoTheSubmissionRetriesRatherThan500s()
            throws Exception {
        charge(null);

        // A real 23505 on this constraint, produced the way a caller that forgot the subject lock
        // would produce it. Asserted on an actual exception rather than a constructed one, because
        // the classifier matches on the SQLSTATE and the constraint name and either could change.
        Exception collision = assertThrows(Exception.class, () -> await(pool.preparedQuery(
            "INSERT INTO rating_opportunities (user_id, subject_type, subject_id, ordinal) "
            + "VALUES ($1,'manager',$2,1)").execute(Tuple.of(contributor, managerId))));

        assertTrue(RatingOpportunityService.isOrdinalContention(collision),
            "a lost race for an ordinal is worth retrying: on the retry the contributor either "
            + "gets the other ordinal or is properly told the cap is reached. Returning a 500 "
            + "instead loses a contribution to a race they cannot see. Got: " + message(collision));

        // And an unrelated failure is NOT retried, because retrying a refusal just asks the same
        // question again and gets the same answer.
        Exception unrelated = assertThrows(Exception.class, () -> await(pool.preparedQuery(
            "INSERT INTO rating_opportunities (user_id, subject_type, subject_id, ordinal) "
            + "VALUES ($1,'manager',$2,3)").execute(Tuple.of(contributor, managerId))));
        assertFalse(RatingOpportunityService.isOrdinalContention(unrelated));
    }

    // -- fixtures --------------------------------------------------------------

    private OptionalInt charge(Long originClaimId) throws Exception {
        return chargeFor("manager", managerId, originClaimId);
    }

    private OptionalInt chargeFor(String subjectType, long subjectId, Long originClaimId)
            throws Exception {
        return await(pool.withTransaction(tx ->
            opportunities.lockSubject(tx, subjectType, subjectId)
                .compose(v -> opportunities.charge(tx, contributor, subjectType, subjectId,
                                                   originClaimId))));
    }

    private List<Long> originClaimIds() throws Exception {
        return await(pool.query(
                "SELECT origin_claim_id FROM rating_opportunities ORDER BY ordinal").execute()
            .map(rs -> {
                List<Long> out = new ArrayList<>();
                for (Row r : rs) out.add(r.getLong("origin_claim_id"));
                return out;
            }));
    }

    private long insertCompany(String name) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies (name, slug, status) VALUES ($1,$2,'ghost') RETURNING id")
            .execute(Tuple.of(name, name.toLowerCase().replace(' ', '-')))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertManager(String name, String slug) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO managers (name, company, title, status, approval_status, country, "
                + "company_id, slug, overall_rating, reviews_count, category_averages) "
                + "VALUES ($1,'Halverson Logistics','Lead','active','approved','CA',$2,$3,0,0,"
                + "'{}'::jsonb) RETURNING id")
            .execute(Tuple.of(name, companyId, slug))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private UUID insertUser(String auth0Id) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO users (auth0_id, username) VALUES ($1,$2) RETURNING id")
            .execute(Tuple.of(auth0Id, "u" + Math.abs(auth0Id.hashCode())))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private long insertClaim() throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO role_claims (proposal_key, manager_id, company_id, title, source, "
                + "user_id, finalized_at, original_manager_id) "
                + "VALUES ($1,$2,$3,'Dispatch Lead','contributor',$4,now(),$2) RETURNING id")
            .execute(Tuple.of(UUID.randomUUID(), managerId, companyId, contributor))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long countRows(String table) throws Exception {
        return await(pool.query("SELECT COUNT(*) AS c FROM " + table).execute()
            .map(rs -> rs.iterator().next().getLong("c")));
    }

    private static String message(Throwable t) {
        StringBuilder all = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) all.append(c.getMessage()).append(" | ");
        return all.toString();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
