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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.service.AdminService;
import org.werkpages.service.ServiceException;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Removing a career stage from the trajectory.
 *
 * <h2>The production failure this comes from</h2>
 *
 * <p>A manager's profile showed a career stage at a company called "Ro". No such company existed.
 * Somebody had typed "ROCKY MOUNTAIN TURF CLUB" into the manager form, a placeholder review was
 * created from the half-typed fragment, and because the trajectory groups reviews by
 * {@code reviews.manager_company}, that fragment rendered as a complete career stage with its own
 * rating beside the real employer.
 *
 * <p>The admin had an Edit control on it and no Delete, because Delete acted on a
 * {@code career_history} row and the stage had none. Clearing it took hand-written SQL against the
 * production database.
 *
 * <h2>What the endpoint has to get right</h2>
 *
 * <ul>
 *   <li>a stage with no {@code career_history} row is still removable;
 *   <li>a stage holding genuine reviews is NOT silently destroyed, because an admin tidying a chart
 *       must not delete somebody's real opinion by accident;
 *   <li>those reviews can be moved to another role instead, keeping the rating;
 *   <li>the manager's cached rating and count are rebuilt either way. Nothing maintains them: there
 *       is no trigger on {@code reviews}, unlike the company projections.
 * </ul>
 */
@Testcontainers
class CareerStageAdminIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("career_stage_test").withUsername("test").withPassword("test");

    static Pool pool;
    static AdminService admin;
    static org.werkpages.repository.ManagerRepository managers;

    private static final String ADMIN = "auth0|stage-admin";

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load().migrate();

        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost()).setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername()).setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(5));

        managers = new org.werkpages.repository.ManagerRepository(pool);
        /*
          Built with the pool, which the five-argument form leaves null.

          Without it AdminService has no ManagerMergeService, and moving ratings between two
          recorded roles at one employer - which IS a role merge and is performed by that service -
          would refuse with "Merging is unavailable" in the test while working in production. A
          wiring difference that only the test can see is a test that proves the wrong thing.
        */
        admin = new AdminService(
            new org.werkpages.repository.UserRepository(pool),
            managers,
            new org.werkpages.repository.ReviewRepository(pool),
            new org.werkpages.repository.EditRepository(pool),
            new org.werkpages.repository.NotificationRepository(pool),
            new org.werkpages.repository.CompanyRepository(pool),
            new org.werkpages.repository.MergeSuggestionsRepository(pool),
            pool);
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE reviews, career_history, company_stats_live, managers, companies, users CASCADE").execute());
        await(pool.preparedQuery(
                "INSERT INTO users(auth0_id, email, username, first_name, last_name, role) "
              + "VALUES ($1,$2,$3,'Stage','Admin','admin')")
            .execute(Tuple.of(ADMIN, "stageadmin@test.com", "stageadmin")));
    }

    // ── the regression ────────────────────────────────────────────────────────

    /**
     * A stage invented by a placeholder review can be removed, with no career_history row.
     *
     * <p>This is the "Ro" case exactly. Before the endpoint existed there was no way to do this
     * from the product at all.
     */
    @Test
    void aStageWithNoCareerHistoryRowCanBeRemoved() throws Exception {
        long managerId = insertManager("ROCKY MOUNTAIN TURF CLUB");
        insertSeedReview(managerId, "Ro", "Co-Founder", 4.1);
        insertRealReview(managerId, "ROCKY MOUNTAIN TURF CLUB", "CEO", 1.1);

        JsonObject result = await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "delete").put("company", "Ro")
                            .put("title", "Co-Founder")));

        assertTrue(result.getBoolean("success"));
        assertEquals(1, result.getInteger("reviewsAffected"));
        assertEquals(0L, countReviewsForCompany(managerId, "Ro"), "the invented stage is gone");
        assertEquals(1L, countReviewsForCompany(managerId, "ROCKY MOUNTAIN TURF CLUB"),
            "the real stage is untouched");
    }

    /** The cached rating and count are rebuilt, because no trigger does it. */
    @Test
    void theManagersCachedRatingIsRebuiltAfterRemoval() throws Exception {
        long managerId = insertManager("ROCKY MOUNTAIN TURF CLUB");
        insertSeedReview(managerId, "Ro", "Co-Founder", 4.1);
        insertRealReview(managerId, "ROCKY MOUNTAIN TURF CLUB", "CEO", 1.1);
        await(pool.preparedQuery("UPDATE managers SET reviews_count = 2, overall_rating = 2.6 WHERE id = $1")
            .execute(Tuple.of(managerId)));

        await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "delete").put("company", "Ro")
                            .put("title", "Co-Founder")));

        Row m = managerRow(managerId);
        assertEquals(1, m.getInteger("reviews_count"),
            "a stale count would keep the header claiming the deleted rating");
        assertEquals(0, m.getBigDecimal("overall_rating").compareTo(new java.math.BigDecimal("1.1")));
    }

    // ── genuine reviews are protected ─────────────────────────────────────────

    /**
     * Deleting a stage that holds real reviews is refused unless it is confirmed.
     *
     * <p>The guard that stops a chart button becoming a way to destroy somebody's rating by
     * accident. The count travels in the message so the caller can state what is at stake.
     */
    @Test
    void deletingAStageWithRealReviewsIsRefusedWithoutConfirmation() throws Exception {
        long managerId = insertManager("Acme");
        insertRealReview(managerId, "Wrong Co", "Director", 3.0);

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitFailure(admin.adminResolveCareerStage(ADMIN, managerId,
                new JsonObject().put("action", "delete").put("company", "Wrong Co")
                                .put("title", "Director"))));

        assertEquals(400, refused.getStatusCode());
        assertTrue(refused.getMessage().contains("1 real review"), refused.getMessage());
        assertEquals(1L, countReviewsForCompany(managerId, "Wrong Co"), "nothing may be deleted");
    }

    /** Confirmed, it goes. The admin has been told and said so. */
    @Test
    void aConfirmedDeleteRemovesRealReviewsToo() throws Exception {
        long managerId = insertManager("Acme");
        insertRealReview(managerId, "Wrong Co", "Director", 3.0);

        JsonObject result = await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "delete").put("company", "Wrong Co")
                            .put("title", "Director")
                            .put("confirmRealReviews", true)));

        assertEquals(1, result.getInteger("reviewsAffected"));
        assertEquals(0L, countReviewsForCompany(managerId, "Wrong Co"));
    }

    // ── moving the ratings instead ────────────────────────────────────────────

    /**
     * Real reviews can be moved onto another role rather than destroyed.
     *
     * <p>The case the user asked for by name: the stage is wrong but the opinions are real. The
     * rating survives and counts under the role it belongs to.
     */
    @Test
    void realReviewsCanBeMovedToAnotherRole() throws Exception {
        long managerId = insertManager("ROCKY MOUNTAIN TURF CLUB");
        insertRealReview(managerId, "Ro", "Co-Founder", 4.0);
        insertRealReview(managerId, "ROCKY MOUNTAIN TURF CLUB", "CEO", 2.0);

        JsonObject result = await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "reassign").put("company", "Ro")
                            .put("title", "Co-Founder")
                            .put("toCompany", "ROCKY MOUNTAIN TURF CLUB").put("toTitle", "CEO")));

        assertEquals(1, result.getInteger("reviewsAffected"));
        assertEquals(0L, countReviewsForCompany(managerId, "Ro"), "the bogus stage is gone");
        assertEquals(2L, countReviewsForCompany(managerId, "ROCKY MOUNTAIN TURF CLUB"),
            "and its rating now counts under the real role");
        assertEquals(2, managerRow(managerId).getInteger("reviews_count"),
            "nothing was destroyed, so the count is unchanged");
    }

    /** A reassign with no destination is refused rather than quietly blanking the company. */
    @Test
    void reassignWithoutATargetIsRefused() throws Exception {
        long managerId = insertManager("Acme");
        insertRealReview(managerId, "Wrong Co", "Director", 3.0);

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitFailure(admin.adminResolveCareerStage(ADMIN, managerId,
                new JsonObject().put("action", "reassign").put("company", "Wrong Co")
                                .put("title", "Director"))));
        assertEquals(400, refused.getStatusCode());
        assertEquals(1L, countReviewsForCompany(managerId, "Wrong Co"));
    }

    /** Reassigning a stage onto itself is refused: it would be a no-op that reports success. */
    @Test
    void reassignOntoItselfIsRefused() throws Exception {
        long managerId = insertManager("Acme");
        insertRealReview(managerId, "Acme", "Director", 3.0);

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitFailure(admin.adminResolveCareerStage(ADMIN, managerId,
                new JsonObject().put("action", "reassign").put("company", "Acme")
                                .put("title", "Director")
                                .put("toCompany", "acme").put("toTitle", "director"))));
        assertEquals(400, refused.getStatusCode());
    }

    // ── the career_history row, when there is one ─────────────────────────────

    /** A stage that does have a row loses the row as well as its reviews. */
    @Test
    void aRecordedRoleLosesItsCareerHistoryRowToo() throws Exception {
        long managerId = insertManager("Acme");
        insertSeedReview(managerId, "Old Co", "Director", 4.0);
        await(pool.preparedQuery(
                "INSERT INTO career_history(manager_id, company, title, start_date, date_source, company_id) "
              + "VALUES ($1, 'Old Co', 'Director', now() - interval '3 years', 'unknown', $2)")
            .execute(Tuple.of(managerId, fixtureCompanyId("Old Co"))));

        JsonObject result = await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "delete").put("company", "Old Co")
                            .put("title", "Director")));

        assertEquals(1, result.getInteger("entriesDeleted"));
        assertEquals(0L, await(pool.preparedQuery("SELECT count(*) AS c FROM career_history WHERE manager_id = $1")
            .execute(Tuple.of(managerId))).iterator().next().getLong("c"));
    }

    /**
     * After a reassign the employment record STAYS. This assertion used to be its opposite.
     *
     * <p>It read {@code assertEquals(0L, ...)} and was named
     * {@code reassignAlsoRemovesTheNowEmptyCareerHistoryRow}: moving a stage's ratings also deleted
     * the {@code career_history} row they left behind, on the reasoning that the stage would
     * otherwise redraw itself from that row with nothing under it.
     *
     * <p>That reasoning is about a chart, and the row is somebody's employment history. The
     * instruction that changed it: "moving ratings and merging employment records must have
     * distinct semantics" - and deleting a period outright is a stronger act than the supersession
     * that instruction already rules out. An admin correcting which job an opinion was filed under
     * is making a claim about an attribution, not about whether the person held the job.
     *
     * <p>So the empty stage does redraw, and that is a true picture: he worked there, and no
     * surviving rating describes it. Removing the period is now its own explicit act - the Delete
     * control, or {@code action = "merge"} where the two really are one employment recorded twice.
     *
     * <p>The response carries {@code sourceEmploymentKept} so the dialog can say so rather than
     * leaving an admin to wonder why the stage is still there.
     */
    @Test
    void reassignKeepsTheNowEmptyCareerHistoryRow() throws Exception {
        long managerId = insertManager("Acme");
        insertRealReview(managerId, "Old Co", "Director", 4.0);
        insertRealReview(managerId, "Acme", "CEO", 3.0);
        await(pool.preparedQuery(
                "INSERT INTO career_history(manager_id, company, title, start_date, date_source, company_id) "
              + "VALUES ($1, 'Old Co', 'Director', now() - interval '3 years', 'unknown', $2)")
            .execute(Tuple.of(managerId, fixtureCompanyId("Old Co"))));

        JsonObject result = await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "reassign").put("company", "Old Co")
                            .put("title", "Director")
                            .put("toCompany", "Acme").put("toTitle", "CEO")));

        assertEquals(1, result.getInteger("reviewsAffected"), "the rating moved");
        assertEquals(0, result.getInteger("entriesDeleted"), "and no employment record went");
        assertTrue(result.getBoolean("sourceEmploymentKept"));
        assertEquals(1L, await(pool.preparedQuery("SELECT count(*) AS c FROM career_history WHERE manager_id = $1")
            .execute(Tuple.of(managerId))).iterator().next().getLong("c"),
            "the period he worked is still on the record. This read 0 before, and a rating move "
            + "deleting an employment period is the behaviour that was rejected.");
        assertEquals(2L, countReviewsForRole(managerId, "Acme", "CEO"),
            "both ratings are under the destination role");
    }

    // ── authorization ─────────────────────────────────────────────────────────

    /** Not an admin, not allowed. The whole thing is a moderation power. */
    @Test
    void aNonAdminCannotRemoveAStage() throws Exception {
        long managerId = insertManager("Acme");
        insertSeedReview(managerId, "Ro", "Director", 4.0);
        await(pool.preparedQuery(
                "INSERT INTO users(auth0_id, email, username, first_name, last_name, role) "
              + "VALUES ($1,$2,$3,'Not','Admin','user')")
            .execute(Tuple.of("auth0|not-admin", "notadmin@test.com", "notadmin")));

        assertThrows(ServiceException.class,
            () -> awaitFailure(admin.adminResolveCareerStage("auth0|not-admin", managerId,
                new JsonObject().put("action", "delete").put("company", "Ro")
                                .put("title", "Director"))));
        assertEquals(1L, countReviewsForCompany(managerId, "Ro"), "nothing may be removed");
    }

    /** An unknown action is refused rather than guessed at. */
    @Test
    void anUnknownActionIsRefused() throws Exception {
        long managerId = insertManager("Acme");
        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitFailure(admin.adminResolveCareerStage(ADMIN, managerId,
                new JsonObject().put("action", "obliterate").put("company", "Acme")
                                .put("title", "CEO"))));
        assertEquals(400, refused.getStatusCode());
    }

    // ── two roles at ONE employer ─────────────────────────────────────────────

    /*
      THE SECOND PRODUCTION DEFECT, and the destructive one.

      Everything above identifies a stage by its company. A manager promoted inside one company has
      two stages there - "Software Engineer" and "Director Software Engineering" at Visa - and the
      chart draws them separately because it groups on the (company, title) PAIR.

      Every operation below used to match the company alone:

        * DELETE FROM reviews WHERE manager_company = 'Visa'   destroyed BOTH roles' ratings,
          including a genuine contributor's opinion of the other job, and reported success;
        * the career_history row was found by company and then ORDER BY start_date DESC LIMIT 1,
          so removing the earlier role deleted the LATER row and left the one meant to go;
        * a reassign whose target company equalled the source company was refused outright, so
          the one repair an admin actually needs - move a rating from the junior role to the
          senior one - could not be performed at all;
        * the headline cascade matched one HALF of the pair each, so correcting a manager's
          current title rewrote the other role's rating snapshots.

      These are the tests for each of those. They are written against the pair, which is what the
      chart shows, so they fail on any implementation that reasons about the employer.
    */

    /** A manager promoted at one employer: two recorded roles, one rating under each. */
    private long insertPromotedAtOneCompany() throws Exception {
        long managerId = insertManager("Visa");
        long companyId = fixtureCompanyId("Visa");
        insertRealReview(managerId, "Visa", "Software Engineer", 2.0);
        insertRealReview(managerId, "Visa", "Director Software Engineering", 4.0);
        await(pool.preparedQuery(
                "INSERT INTO career_history(manager_id, company, title, start_date, end_date, "
              + "date_source, company_id) VALUES "
              + "($1, 'Visa', 'Software Engineer', '2019-01-01', '2022-01-01', 'unknown', $2), "
              + "($1, 'Visa', 'Director Software Engineering', '2022-02-01', NULL, 'unknown', $2)")
            .execute(Tuple.of(managerId, companyId)));
        return managerId;
    }

    /**
     * Removing one role at a company does not take the other role's ratings with it.
     *
     * <p>The whole reason this work was authorized. An admin removing the junior role of a promoted
     * manager was issuing a company-wide delete: the Director rating - somebody's genuine opinion
     * of a different job - went with it, silently, and the confirmation had quoted a count that
     * included it.
     */
    @Test
    void deletingOneRoleAtACompanyLeavesTheSiblingRoleIntact() throws Exception {
        long managerId = insertPromotedAtOneCompany();

        JsonObject result = await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "delete").put("company", "Visa")
                            .put("title", "Software Engineer")
                            .put("confirmRealReviews", true)));

        assertEquals(1, result.getInteger("reviewsAffected"),
            "ONE rating, not both. A company-wide delete reported 2 here.");
        assertEquals(0L, countReviewsForRole(managerId, "Visa", "Software Engineer"));
        assertEquals(1L, countReviewsForRole(managerId, "Visa", "Director Software Engineering"),
            "the sibling role's genuine rating is still there");
        assertEquals(1, managerRow(managerId).getInteger("reviews_count"));
        assertEquals(1L, countCareerRows(managerId, "Visa", "Director Software Engineering"),
            "and so is its employment record");
    }

    /**
     * The confirmation count describes the one role, not the employer.
     *
     * <p>An admin told "this stage holds 2 real reviews" about a role with one rating has been
     * told something false, and it is the number they decide on.
     */
    @Test
    void theRefusalCountsOnlyTheRoleBeingRemoved() throws Exception {
        long managerId = insertPromotedAtOneCompany();

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitFailure(admin.adminResolveCareerStage(ADMIN, managerId,
                new JsonObject().put("action", "delete").put("company", "Visa")
                                .put("title", "Software Engineer"))));

        assertTrue(refused.getMessage().contains("1 real review"),
            "the count is the role's, not the company's: " + refused.getMessage());
        assertEquals(2L, countReviewsForManager(managerId), "and nothing was deleted");
    }

    /**
     * The row that goes is the role's own, not whichever one started most recently.
     *
     * <p>{@code findCareerEntryIdByCompany} matched the company and then took
     * {@code ORDER BY start_date DESC LIMIT 1}. Removing the EARLIER role therefore deleted the
     * later row: the admin's click removed a different job from the one they clicked, and the one
     * they meant to remove stayed on the chart.
     */
    @Test
    void theRowRemovedIsTheRoleClickedNotTheNewestAtThatCompany() throws Exception {
        long managerId = insertPromotedAtOneCompany();

        await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "delete").put("company", "Visa")
                            .put("title", "Software Engineer")
                            .put("confirmRealReviews", true)));

        assertEquals(0L, countCareerRows(managerId, "Visa", "Software Engineer"),
            "the earlier role's row is the one that went");
        assertEquals(1L, countCareerRows(managerId, "Visa", "Director Software Engineering"),
            "the current role survived. Keying on start_date DESC deleted THIS one instead.");
    }

    /**
     * MOVING RATINGS IS NOT MERGING EMPLOYMENT, and reassign used to do both.
     *
     * <p>The repair an admin actually needs on a promoted manager: a rating was filed under the
     * junior role and belongs under the senior one. This was refused outright before, because any
     * reassign whose target company matched the source was treated as a stage reassigned onto
     * itself. Two roles at one employer are not one stage.
     *
     * <p>But the first fix for that went too far the other way. Seeing two recorded employments at
     * one employer, reassign performed a ROLE MERGE: it superseded the source role through
     * {@code mergeRoles}. An admin correcting which job an opinion was filed under would find an
     * employment period superseded, and the trajectory one stage shorter, having asked for neither.
     *
     * <p>So this asserts the employment record is untouched on BOTH sides. The source role is still
     * {@code accepted} with no {@code superseded_by}, both rows are still live, and the chart still
     * draws two stages - the junior one now empty, which is a true picture of the data: he held
     * that job, and no surviving rating describes it. Merging is a separate decision, made by
     * {@code action = "merge"}, and the test below is the one that asserts supersession.
     */
    @Test
    void movingRatingsBetweenTwoRolesLeavesBothEmploymentsStanding() throws Exception {
        long managerId = insertPromotedAtOneCompany();

        JsonObject result = await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "reassign")
                            .put("company", "Visa").put("title", "Software Engineer")
                            .put("toCompany", "Visa")
                            .put("toTitle", "Director Software Engineering")));

        assertFalse(result.getBoolean("merged"),
            "moving ratings is not a merge, and saying it was told the admin something untrue");
        assertEquals(0, result.getInteger("rolesSuperseded"),
            "no employment record may be superseded by a request to move ratings");
        assertEquals(0, result.getInteger("entriesDeleted"),
            "and none deleted either: reassign used to drop the source row once it was empty");
        assertTrue(result.getBoolean("sourceEmploymentKept"),
            "the response says the period is still there, so the UI need not leave an admin "
            + "wondering why the stage is still drawn");

        assertEquals(2L, countReviewsForRole(managerId, "Visa", "Director Software Engineering"),
            "both ratings now sit under the senior role");
        assertEquals(0L, countReviewsForRole(managerId, "Visa", "Software Engineer"));
        assertEquals(2L, countReviewsForManager(managerId), "and NOTHING was destroyed");
        assertEquals(2, managerRow(managerId).getInteger("reviews_count"));

        assertEquals(1L, countCareerRows(managerId, "Visa", "Software Engineer"),
            "the junior employment is still on the record");
        assertEquals(1L, countCareerRows(managerId, "Visa", "Director Software Engineering"));

        Row source = await(pool.preparedQuery(
                "SELECT state, superseded_by FROM career_history "
              + "WHERE manager_id = $1 AND title = 'Software Engineer'")
            .execute(Tuple.of(managerId))).iterator().next();
        assertEquals("accepted", source.getString("state"),
            "untouched, not superseded. This is the assertion that fails if moving ratings is "
            + "ever quietly turned back into a merge.");
        assertNull(source.getLong("superseded_by"));

        assertEquals(2L, await(new org.werkpages.repository.ReviewRepository(pool)
            .countCareerSegmentsByManager(managerId)).longValue(),
            "two stages still, one of them now empty: he held that job and no rating describes it");
    }

    /**
     * Merging says the two stages are one employment recorded twice, and only merge supersedes.
     *
     * <p>{@code ManagerMergeService.mergeRoles} performs it, and nothing here reimplements it: the
     * superseded role is KEPT and pointed at its replacement rather than deleted, because the
     * employment record has to outlive the correction, claims are redirected with the
     * canonicalization audited, and a contributor who already holds a claim on the keeper has
     * their duplicate rating held rather than doubled.
     *
     * <p>The rating snapshots follow separately, because {@code mergeRoles} deliberately does not
     * touch them - it moves employment and claims, not the text an opinion was filed under.
     */
    @Test
    void mergingTwoRolesSupersedesTheSourceAndCarriesTheRatings() throws Exception {
        long managerId = insertPromotedAtOneCompany();

        JsonObject result = await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "merge")
                            .put("company", "Visa").put("title", "Software Engineer")
                            .put("toCompany", "Visa")
                            .put("toTitle", "Director Software Engineering")));

        assertTrue(result.getBoolean("merged"));
        assertEquals(1, result.getInteger("rolesSuperseded"));
        assertEquals(0, result.getInteger("entriesDeleted"),
            "a merge supersedes; it never deletes the employment record");
        assertEquals(2L, countReviewsForRole(managerId, "Visa", "Director Software Engineering"),
            "both ratings now sit under the surviving role");
        assertEquals(0L, countReviewsForRole(managerId, "Visa", "Software Engineer"));
        assertEquals(2L, countReviewsForManager(managerId), "and NOTHING was destroyed");
        assertEquals(2, managerRow(managerId).getInteger("reviews_count"));

        Row gone = await(pool.preparedQuery(
                "SELECT state, superseded_by FROM career_history "
              + "WHERE manager_id = $1 AND title = 'Software Engineer'")
            .execute(Tuple.of(managerId))).iterator().next();
        assertEquals("superseded", gone.getString("state"),
            "the merge supersedes the employment; it does not erase that the job existed");
        assertNotNull(gone.getLong("superseded_by"), "and records what replaced it");

        /*
          And the chart draws ONE tile.

          The superseded row is kept on purpose, so the grouping has to leave it out - otherwise
          the merge produces a second tile at the same employer with nothing under it, and from
          the admin's side the operation looks as though it half worked.
        */
        assertEquals(1L, await(new org.werkpages.repository.ReviewRepository(pool)
            .countCareerSegmentsByManager(managerId)).longValue(),
            "one stage at Visa, not an empty second one left by the merge");
    }

    /**
     * A merge across two employers is REFUSED, not quietly downgraded to a rating move.
     *
     * <p>{@code mergeRoles} rejects it, because two jobs at two companies are not one employment
     * however similar the titles. The refusal is surfaced with its own reason rather than caught
     * and turned into a snapshot edit: an admin who asked to merge two employment records and got
     * an attribution change instead has been told something untrue about their own data.
     *
     * <p>The assertion that matters is the last one. A fallback would have left the ratings moved.
     */
    @Test
    void mergingAcrossEmployersIsRefusedRatherThanDowngradedToAMove() throws Exception {
        long managerId = insertManager("Visa");
        insertRealReview(managerId, "Visa", "Director", 4.0);
        insertRealReview(managerId, "Shopify", "Director", 3.0);
        await(pool.preparedQuery(
                "INSERT INTO career_history(manager_id, company, title, start_date, end_date, "
              + "date_source, company_id) VALUES ($1, 'Visa', 'Director', '2015-01-01', "
              + "'2017-01-01', 'unknown', $2)")
            .execute(Tuple.of(managerId, fixtureCompanyId("Visa"))));
        await(pool.preparedQuery(
                "INSERT INTO career_history(manager_id, company, title, start_date, end_date, "
              + "date_source, company_id) VALUES ($1, 'Shopify', 'Director', '2018-01-01', "
              + "NULL, 'unknown', $2)")
            .execute(Tuple.of(managerId, fixtureCompanyId("Shopify"))));

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitFailure(admin.adminResolveCareerStage(ADMIN, managerId,
                new JsonObject().put("action", "merge")
                                .put("company", "Visa").put("title", "Director")
                                .put("toCompany", "Shopify").put("toTitle", "Director"))));

        assertEquals(409, refused.getStatusCode(),
            "a refusal the admin can act on, not a 500: " + refused.getMessage());
        assertEquals(1L, countReviewsForRole(managerId, "Visa", "Director"),
            "the rating did NOT move. A silent fallback to a snapshot edit would have moved it.");
        assertEquals(1L, countReviewsForRole(managerId, "Shopify", "Director"));
    }

    /**
     * There is nothing to merge when a stage is not a job.
     *
     * <p>A stage invented by a placeholder that captured a half-typed company name has no
     * employment record behind it. Merging it would be merging an employment that does not exist,
     * so the refusal names the action that does apply: move the ratings.
     */
    @Test
    void mergingAStageWithNoEmploymentRecordIsRefusedAndSaysWhatToDoInstead() throws Exception {
        long managerId = insertPromotedAtOneCompany();
        insertRealReview(managerId, "Vis", "Software Engineer", 3.0);

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitFailure(admin.adminResolveCareerStage(ADMIN, managerId,
                new JsonObject().put("action", "merge")
                                .put("company", "Vis").put("title", "Software Engineer")
                                .put("toCompany", "Visa")
                                .put("toTitle", "Director Software Engineering"))));

        assertEquals(400, refused.getStatusCode());
        assertTrue(refused.getMessage().contains("no employment record"),
            "says why, and what applies instead: " + refused.getMessage());
        assertEquals(1L, countReviewsForRole(managerId, "Vis", "Software Engineer"),
            "and the rating stayed where it was");
    }

    /** The same role really is the same role, so that is still refused. */
    @Test
    void reassigningARoleOntoItselfIsStillRefused() throws Exception {
        long managerId = insertPromotedAtOneCompany();

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitFailure(admin.adminResolveCareerStage(ADMIN, managerId,
                new JsonObject().put("action", "reassign")
                                .put("company", "Visa").put("title", "Software Engineer")
                                .put("toCompany", "visa").put("toTitle", "software  engineer"))));

        assertEquals(400, refused.getStatusCode());
        assertEquals(2L, countReviewsForManager(managerId));
    }

    /**
     * A request that cannot say which role it means is refused, not guessed at.
     *
     * <p>This is the shape every call used to have. Accepting it is what made a company-wide
     * delete expressible in the first place.
     */
    @Test
    void aStageCannotBeResolvedWithoutSayingWhichRole() throws Exception {
        long managerId = insertPromotedAtOneCompany();

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitFailure(admin.adminResolveCareerStage(ADMIN, managerId,
                new JsonObject().put("action", "delete").put("company", "Visa")
                                .put("confirmRealReviews", true))));

        assertEquals(400, refused.getStatusCode());
        assertTrue(refused.getMessage().toLowerCase().contains("title"), refused.getMessage());
        assertEquals(2L, countReviewsForManager(managerId),
            "and above all, nothing was deleted while it was ambiguous");
    }

    /**
     * Correcting the headline does not rewrite the other role's rating snapshots.
     *
     * <p>The same defect at a different entry point, found by auditing for it. The cascade in
     * {@code adminEdit} ran two statements keyed on one half of the pair each - every review whose
     * title matched, and every review whose company matched - while the {@code career_history}
     * statement beside them matched on both. So correcting the current title of a promoted manager
     * rewrote the junior role's snapshots too: those ratings moved to a job they were not written
     * about, and the chart folded two stages into one.
     *
     * <p>A rating's snapshot IS the record of which job it describes. Moving it is attribution
     * loss, and it is silent.
     */
    @Test
    void correctingTheHeadlineLeavesTheOtherRolesRatingsWhereTheyAre() throws Exception {
        long managerId = insertPromotedAtOneCompany();
        // The manager row's own title is the current role, which is what an admin edits.
        await(pool.preparedQuery("UPDATE managers SET title = 'Director Software Engineering' WHERE id = $1")
            .execute(Tuple.of(managerId)));

        await(managers.adminEdit(managerId, null, "Director, Software Engineering", null, null, null));

        assertEquals(1L, countReviewsForRole(managerId, "Visa", "Software Engineer"),
            "the junior role's rating still describes the junior role. The company-keyed "
            + "cascade rewrote it to the new title, moving somebody's opinion to another job.");
        assertEquals(1L, countReviewsForRole(managerId, "Visa", "Director, Software Engineering"),
            "and the corrected role's rating followed the correction, which is the point of it");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Counted on the PAIR, the way the trajectory groups, so the assertion and the chart agree. */
    // ── two SPELLS at one employer under one title ────────────────────────────

    /*
      ONE STAGE CAN BE TWO JOBS, which is the same defect one level down.

      The tests above fixed operations that identified a role by its COMPANY, so that removing one
      role at an employer destroyed a sibling role's ratings. They identify a role by the
      (company, title) pair instead, because that is what the trajectory groups on.

      But that pair is not unique either. Somebody can leave an employer and come back to the same
      title years later. Those are two separate employments, the chart draws them as ONE tile, and
      a stage-wide delete would take both while the admin was looking at one.

      The schema already treats them as distinct: career_unique_known_employment is
      UNIQUE (manager_id, company_id, title_key, start_day), so two live periods under one title
      are permitted exactly when their start dates differ. The fixture below inserting without
      error IS that proof.

      Since the database's own discriminator is the start day, and a rating carries no discriminator
      at all - its only attribution is the (company, title) snapshot - nothing can infer which
      period an operation meant. So nothing tries. A destructive action on an ambiguous stage is
      refused until the request names the period, and the refusal lists them with their dates.
    */

    /** Two spells at one employer under ONE title: left in 2017, came back in 2020. */
    private long insertTwoSpellsAtOneTitle() throws Exception {
        long managerId = insertManager("Visa");
        long companyId = fixtureCompanyId("Visa");
        insertRealReview(managerId, "Visa", "Director", 4.0);
        insertRealReview(managerId, "Visa", "Director", 2.0);
        // period_known TRUE puts both rows inside career_unique_known_employment, so this INSERT
        // succeeding proves the schema permits two spells under one title on distinct start days.
        await(pool.preparedQuery(
                "INSERT INTO career_history(manager_id, company, title, start_date, end_date, "
              + "date_source, company_id, period_known) VALUES "
              + "($1, 'Visa', 'Director', '2015-01-01', '2017-01-01', 'unknown', $2, TRUE), "
              + "($1, 'Visa', 'Director', '2020-01-01', '2022-01-01', 'unknown', $2, TRUE)")
            .execute(Tuple.of(managerId, companyId)));
        return managerId;
    }

    /**
     * Keyed on start_day, which is the schema's own discriminator between two spells.
     *
     * <p>career_history.start_date is a TIMESTAMPTZ, so a LocalDate parameter cannot be encoded
     * against it. start_day is the generated DATE that career_unique_known_employment is built on -
     * UNIQUE (manager_id, company_id, title_key, start_day) - so this asks the question the
     * database itself asks when deciding whether two periods are the same employment.
     */
    private long spellId(long managerId, String startDay) throws Exception {
        return await(pool.preparedQuery(
                "SELECT id FROM career_history WHERE manager_id = $1 AND start_day = $2")
            .execute(Tuple.of(managerId, java.time.LocalDate.parse(startDay))))
            .iterator().next().getLong("id");
    }

    /**
     * A stage covering two spells refuses a delete that names neither, and lists the choice.
     *
     * <p>This is the requirement-4 case: genuinely ambiguous, so it asks. Acting on both would
     * remove an employment the admin was never shown; acting on one would pick a job at random.
     *
     * <p>{@code confirmRealReviews} is set deliberately, so that the refusal under test cannot be
     * the real-reviews guard wearing the ambiguity's clothes.
     */
    @Test
    void aStageCoveringTwoSpellsRefusesADeleteThatNamesNeither() throws Exception {
        long managerId = insertTwoSpellsAtOneTitle();

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitFailure(admin.adminResolveCareerStage(ADMIN, managerId,
                new JsonObject().put("action", "delete")
                                .put("company", "Visa").put("title", "Director")
                                .put("confirmRealReviews", true))));

        assertEquals(400, refused.getStatusCode());
        assertTrue(refused.getMessage().contains("2 separate employment periods"),
            "says what is ambiguous: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("2015-01-01")
                && refused.getMessage().contains("2020-01-01"),
            "and lists the periods by their dates, which is the schema's own discriminator, so "
            + "the admin can choose rather than merely being declined: " + refused.getMessage());

        assertEquals(2L, countCareerRows(managerId, "Visa", "Director"), "nothing was removed");
        assertEquals(2L, countReviewsForManager(managerId), "and no rating was destroyed");
    }

    /**
     * Naming the spell removes that employment alone, and KEEPS the ratings.
     *
     * <p>While a period at this (company, title) survives, the ratings still describe a job that is
     * still in the history, and the pair is the only attribution they carry. Deleting them because
     * one of two spells went would destroy genuine opinions of a job the manager demonstrably held.
     */
    @Test
    void namingTheSpellRemovesThatEmploymentAndKeepsTheRatings() throws Exception {
        long managerId = insertTwoSpellsAtOneTitle();
        long firstSpell = spellId(managerId, "2015-01-01");

        JsonObject result = await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "delete")
                            .put("company", "Visa").put("title", "Director")
                            .put("careerHistoryId", firstSpell)));

        assertEquals(1, result.getInteger("entriesDeleted"), "one employment period, not both");
        assertEquals(0, result.getInteger("reviewsAffected"),
            "and no rating: the second spell still backs this stage");
        assertEquals(1L, countCareerRows(managerId, "Visa", "Director"));
        assertEquals(2L, countReviewsForManager(managerId), "both opinions survive");
        assertEquals(2, managerRow(managerId).getInteger("reviews_count"));
    }

    /** Saying every period is meant is allowed; it just has to be said. */
    @Test
    void allPeriodsRemovesBothSpellsAndTheirRatings() throws Exception {
        long managerId = insertTwoSpellsAtOneTitle();

        JsonObject result = await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "delete")
                            .put("company", "Visa").put("title", "Director")
                            .put("allPeriods", true)
                            .put("confirmRealReviews", true)));

        assertEquals(2, result.getInteger("entriesDeleted"), "both spells");
        assertEquals(2, result.getInteger("reviewsAffected"),
            "and the ratings, because no period at this role is left to attribute them to");
        assertEquals(0L, countCareerRows(managerId, "Visa", "Director"));
        assertEquals(0L, countReviewsForManager(managerId));
    }

    /**
     * A period id belonging to a different stage is refused.
     *
     * <p>Otherwise {@code careerHistoryId} would be a way to delete any row on the manager while
     * naming an unrelated stage, and the pair in the request would be decoration.
     */
    @Test
    void aPeriodIdFromAnotherStageIsRefused() throws Exception {
        long managerId = insertPromotedAtOneCompany();
        long directorRow = await(pool.preparedQuery(
                "SELECT id FROM career_history WHERE manager_id = $1 "
              + "AND title = 'Director Software Engineering'")
            .execute(Tuple.of(managerId))).iterator().next().getLong("id");

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitFailure(admin.adminResolveCareerStage(ADMIN, managerId,
                new JsonObject().put("action", "delete")
                                .put("company", "Visa").put("title", "Software Engineer")
                                .put("careerHistoryId", directorRow)
                                .put("confirmRealReviews", true))));

        assertEquals(400, refused.getStatusCode());
        assertTrue(refused.getMessage().contains("not an employment period of this stage"),
            refused.getMessage());
        assertEquals(1L, countCareerRows(managerId, "Visa", "Director Software Engineering"),
            "and the row it named is still there");
        assertEquals(2L, countReviewsForManager(managerId));
    }

    /**
     * Moving ratings off an ambiguous stage is NOT refused, because it reads no employment record.
     *
     * <p>The ambiguity is about which employment period an operation means. A rating move has no
     * answer to give: the ratings carry one (company, title) snapshot between them and cannot be
     * split by period even in principle. Refusing it would block the one safe repair available on
     * such a stage.
     */
    @Test
    void movingRatingsOffAnAmbiguousStageIsAllowed() throws Exception {
        long managerId = insertTwoSpellsAtOneTitle();
        await(pool.preparedQuery(
                "INSERT INTO career_history(manager_id, company, title, start_date, end_date, "
              + "date_source, company_id, period_known) VALUES "
              + "($1, 'Visa', 'Vice President', '2022-02-01', NULL, 'unknown', $2, TRUE)")
            .execute(Tuple.of(managerId, fixtureCompanyId("Visa"))));

        JsonObject result = await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "reassign")
                            .put("company", "Visa").put("title", "Director")
                            .put("toCompany", "Visa").put("toTitle", "Vice President")));

        assertEquals(2, result.getInteger("reviewsAffected"), "both ratings moved");
        assertEquals(0, result.getInteger("entriesDeleted"));
        assertEquals(2L, countCareerRows(managerId, "Visa", "Director"),
            "and both spells are still on the record, untouched");
    }

    private long countReviewsForRole(long managerId, String company, String title)
            throws Exception {
        return await(pool.preparedQuery("""
                SELECT count(*) AS c FROM reviews
                 WHERE manager_id = $1
                   AND LOWER(TRIM(COALESCE(manager_company,''))) = LOWER(TRIM($2))
                   AND LOWER(TRIM(COALESCE(manager_title,'')))   = LOWER(TRIM($3))
                """).execute(Tuple.of(managerId, company, title)))
            .iterator().next().getLong("c");
    }

    private long countReviewsForManager(long managerId) throws Exception {
        return await(pool.preparedQuery("SELECT count(*) AS c FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId))).iterator().next().getLong("c");
    }

    /** Live rows only: a superseded employment is kept on purpose and is not one of these. */
    private long countCareerRows(long managerId, String company, String title) throws Exception {
        return await(pool.preparedQuery("""
                SELECT count(*) AS c FROM career_history
                 WHERE manager_id = $1
                   AND LOWER(TRIM(company)) = LOWER(TRIM($2))
                   AND LOWER(TRIM(COALESCE(title,''))) = LOWER(TRIM($3))
                   AND state <> 'superseded'
                """).execute(Tuple.of(managerId, company, title)))
            .iterator().next().getLong("c");
    }

    private long insertManager(String company) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO managers(name, company, title, status, approval_status, reviews_count) "
              + "VALUES ('Max Gibb', $1, 'CEO', 'active', 'approved', 0) RETURNING id")
            .execute(Tuple.of(company))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    /** A placeholder: no author, weight true. Exactly what createSeedReview writes. */
    private void insertSeedReview(long managerId, String company, String title, double rating)
            throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO reviews(manager_id, user_id, author, overall_rating, manager_company, "
              + "manager_title, worked_from, verified, helpful_count, weight) "
              + "VALUES ($1, NULL, $2, $3, $4, $5, now() - interval '2 years', true, 0, true)")
            .execute(Tuple.of(managerId, "LoyalFalcon" + (int) (rating * 10), rating, company, title)));
    }

    /** A real one: a genuine author, weight false. */
    private void insertRealReview(long managerId, String company, String title, double rating)
            throws Exception {
        UUID userId = await(pool.preparedQuery(
                "INSERT INTO users(auth0_id, email, username, first_name, last_name, role) "
              + "VALUES ($1,$2,$3,'Real','Person','user') RETURNING id")
            .execute(Tuple.of("auth0|rev-" + UUID.randomUUID(),
                              UUID.randomUUID() + "@test.com", "rev" + Math.abs(UUID.randomUUID().hashCode())))
            .map(rs -> rs.iterator().next().getUUID("id")));
        await(pool.preparedQuery(
                "INSERT INTO reviews(manager_id, user_id, author, overall_rating, manager_company, "
              + "manager_title, worked_from, verified, helpful_count, weight) "
              + "VALUES ($1, $2, $3, $4, $5, $6, now() - interval '2 years', true, 0, false)")
            .execute(Tuple.of(managerId, userId, "RealPerson" + (int) (rating * 10), rating, company, title)));
    }

    private long countReviewsForCompany(long managerId, String company) throws Exception {
        return await(pool.preparedQuery(
                "SELECT count(*) AS c FROM reviews WHERE manager_id = $1 "
              + "AND LOWER(TRIM(COALESCE(manager_company,''))) = LOWER(TRIM($2))")
            .execute(Tuple.of(managerId, company))).iterator().next().getLong("c");
    }

    private Row managerRow(long managerId) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))).iterator().next();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private static void awaitFailure(Future<?> future) throws Throwable {
        try {
            future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause();
        }
    }

    /**
     * A real employer for a fixture role. {@code career_history.company_id} is NOT NULL as of V99,
     * so a career row has to name one. findOrCreate by name, which is the rule the product itself
     * uses, so a fixture mentioning one company twice gets one company row.
     */
    private long fixtureCompanyId(String name) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO companies (name, slug, status)
                VALUES ($1, 'fixture-' || md5(lower(btrim($1))), 'ghost')
                ON CONFLICT ((LOWER(TRIM(name)))) DO UPDATE SET updated_at = now()
                RETURNING id
                """).execute(Tuple.of(name)).map(rs -> rs.iterator().next().getLong("id")));
    }

}
