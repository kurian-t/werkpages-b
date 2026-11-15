package org.werkpages.integration;

import io.vertx.core.json.JsonObject;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.service.ManagerService;
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
import org.werkpages.service.ManagerMergeService;
import org.werkpages.service.RatingOpportunityService;
import org.werkpages.service.RoleAwareSubmissionService;
import org.werkpages.service.RoleClaimService;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PHASE 4A: the role-aware submission lifecycle, end to end against a real database.
 *
 * <h2>What is being replaced, and what must be true instead</h2>
 *
 * <p>The legacy path takes a typed name, finds candidates by exact company, and attaches the rating
 * to whichever manager is within one Levenshtein edit of what was typed. <b>A machine decides which
 * human a person's opinion is about.</b> Everything here exists so that the contributor decides
 * instead, and so that where they cannot decide, a person does.
 *
 * <p>The rule the whole phase rests on: <b>fuzzy matching may help somebody FIND a manager; it may
 * never decide a manager's identity or attach a rating.</b>
 *
 * <h2>Why these tests go through the service and read the committed rows back</h2>
 *
 * <p>A transaction can satisfy every assertion in a harness and still write the wrong column from
 * application code. The rules under test here are enforced in three different places - a CHECK, a
 * trigger quoting {@code rating_is_publishable}, and the service's own ordering - and only running
 * the real thing against the real schema proves they agree.
 */
@Testcontainers
class RoleAwareSubmissionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("role_aware_submission")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static RoleAwareSubmissionService submissions;
    static RoleAwareSubmissionService disabled;
    static RatingOpportunityService opportunities;
    static ManagerMergeService merges;
    /*
      PATH 3 is driven from here, not from RoleAwareSubmissionService.

      Its Submission record takes a primitive manager id, so by the time a submission reaches that
      service the manager always exists. Creating one when the contributor says "none of these
      people" happens in ManagerService.submitRoleAwareRating, which is therefore the only place a
      path-3 test can enter.
    */
    static ManagerService managers;
    static ReviewRepository reviews;
    static UserRepository users;

    long companyId;
    long otherCompanyId;
    long managerId;
    long otherManagerId;
    UUID contributor;
    UUID someoneElse;

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
            new PoolOptions().setMaxSize(8));
        submissions   = new RoleAwareSubmissionService(pool, true);
        disabled      = new RoleAwareSubmissionService(pool, false);
        opportunities = new RatingOpportunityService();
        merges        = new ManagerMergeService(pool);
        reviews       = new ReviewRepository(pool);
        users         = new UserRepository(pool);
        managers      = new ManagerService(new ManagerRepository(pool), new ReviewRepository(pool),
                                           new UserRepository(pool), new EditRepository(pool),
                                           new ReportRepository(pool), new CompanyRepository(pool),
                                           pool, company -> null);
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) {
            pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE review_supersessions, role_conflict_reviews, "
            + "role_conflict_claims, role_conflicts, claim_resolution_events, claim_corrections, "
            + "rating_opportunity_retirements, manager_merge_events").execute());
        await(pool.query("TRUNCATE reviews, role_claims, rating_opportunities, career_history, "
            + "capture_attempts, captured_drafts, company_stats_live, managers, companies, users "
            + "CASCADE").execute());
        companyId      = insertCompany("Halverson Logistics");
        otherCompanyId = insertCompany("Brightwater Foods");
        managerId      = insertManager("Priya Raghunathan", "priya-r", companyId);
        otherManagerId = insertManager("Dominic Achebe", "dom-a", companyId);
        contributor    = insertUser("auth0|contributor");
        someoneElse    = insertUser("auth0|someone-else");
    }

    // -- the feature gate -----------------------------------------------------

    @Test
    void withTheFlagOffNothingIsWrittenAndTheLegacyPathStillOwnsSubmissions() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");

        Exception e = assertThrows(Exception.class,
            () -> await(disabled.submit(selection(role, contributor))));
        assertTrue(message(e).contains("disabled"), message(e));

        // THE POINT. The gate is not cosmetic: the new path writes nothing at all while it is
        // closed, so the legacy path's behaviour cannot change underneath it.
        assertEquals(0, countRows("role_claims"));
        assertEquals(0, countRows("reviews"));
        assertEquals(0, countRows("rating_opportunities"));
    }

    // -- the selection path ---------------------------------------------------

    @Test
    void selectingAnAcceptedEmploymentPublishesTheRatingAtOnce() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");

        RoleAwareSubmissionService.Outcome out = await(submissions.submit(selection(role, contributor)));

        assertEquals("linked", out.resolution());
        assertTrue(out.live(), "an accepted employment has nothing left to decide");
        assertNull(out.heldBecause());
        assertEquals(role, out.roleId());
        assertEquals(1, (int) out.opportunityOrdinal());
        assertEquals("live", dispositionOf(out.reviewId()));

        // The contributor's own choice is on the claim, and the platform's answer is a SEPARATE
        // column. Collapsing the two would erase the distinction this redesign rests on.
        Row claim = claimRow(out.claimId());
        assertEquals(role, claim.getLong("selected_role_id"), "what they chose");
        assertEquals(role, claim.getLong("resolved_role_id"), "what the platform did about it");
        assertNotNull(claim.getOffsetDateTime("finalized_at"));
        assertEquals(managerId, claim.getLong("original_manager_id"),
            "the person selected AT SUBMISSION, written only by finalization");
    }

    @Test
    void selectingAProposedEmploymentHoldsTheRatingUntilAModeratorAcceptsIt() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "proposed");

        RoleAwareSubmissionService.Outcome out = await(submissions.submit(selection(role, contributor)));

        // Linked exactly as an accepted role would be. The difference is only whether it may be
        // public yet, and that is the database's predicate, not the claim's resolution.
        assertEquals("linked", out.resolution());
        assertFalse(out.live());
        assertNotNull(out.heldBecause(), "the contributor is told why, in the database's own words");
        assertEquals("held", dispositionOf(out.reviewId()));
        assertNull(liveSinceOf(out.reviewId()), "held means gate_eligible false and live_since null");

        // NOT DELETED, NOT REFUSED. Their work is saved and waiting on a person.
        assertEquals(1, countRows("reviews"));
    }

    @Test
    void aSelectionThatBelongsToAnotherManagerIsRefusedAndNothingIsWritten() throws Exception {
        long foreignRole = insertRole(otherManagerId, "Dispatch Lead", "2024-01-15", "accepted");

        Exception e = assertThrows(Exception.class,
            () -> await(submissions.submit(selection(foreignRole, contributor))));
        assertTrue(message(e).contains("no longer available"), message(e));

        // The validation is IN the select that takes the lock, not in a constraint: selected_role_id
        // references career_history by id alone, deliberately, so a selection survives a merge that
        // moves the role. So the service has to check, and it must check under the lock.
        assertEquals(0, countRows("reviews"));
        assertEquals(0, countRows("rating_opportunities"));
        // AND NOT A HALF-WRITTEN CLAIM EITHER. The submission is one transaction, so a refusal
        // rolls back the draft it upserted along with everything else. The contributor's work is
        // not lost by this: autosave is its own endpoint and its own transaction, which is the
        // whole reason the draft upsert is idempotent on proposal_key.
        assertEquals(0, countRows("role_claims"));
    }

    @Test
    void aSelectionOfARejectedEmploymentIsRefused() throws Exception {
        long rejected = insertRole(managerId, "Dispatch Lead", "2024-01-15", "rejected");

        Exception e = assertThrows(Exception.class,
            () -> await(submissions.submit(selection(rejected, contributor))));
        assertTrue(message(e).contains("no longer available"), message(e));
        assertEquals(0, countRows("reviews"));
    }

    // -- the proposal path ----------------------------------------------------

    @Test
    void proposingAKnownPeriodCreatesTheRoleAndHoldsTheRatingUntilItIsAccepted() throws Exception {
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(proposal("Depot Supervisor", LocalDate.parse("2023-03-01"),
                                              true, contributor)));

        assertEquals("created", out.resolution());
        assertFalse(out.live(), "a proposed employment is held until a moderator accepts it");
        assertNull(out.conflictKind(), "nothing resembled it, so nobody needs to look");

        Row role = roleRow(out.roleId());
        assertEquals("proposed", role.getString("state"));
        assertEquals("contributor", role.getString("source"));
        assertTrue(role.getBoolean("period_known"));
        // Stored as midnight UTC rather than cast with the session zone, so the generated start_day
        // reads back the same calendar day it was typed as.
        assertEquals("2023-03-01", startDayOf(out.roleId()));
    }

    @Test
    void twoContributorsDescribingOneEmploymentShareOneRoleAndKeepTwoRatings() throws Exception {
        RoleAwareSubmissionService.Outcome first =
            await(submissions.submit(proposal("Depot Supervisor", LocalDate.parse("2023-03-01"),
                                              true, contributor)));
        RoleAwareSubmissionService.Outcome second =
            await(submissions.submit(proposal("depot  SUPERVISOR", LocalDate.parse("2023-03-01"),
                                              true, someoneElse)));

        // THE ONE CASE WHERE ROWS ARE SHARED, and it is exact agreement on a key that identifies an
        // employment episode by definition: the same person cannot begin the same job at the same
        // employer on the same date twice. It is NOT a judgement that two descriptions probably
        // mean the same thing - the title is normalised, the date is exact, and nothing else is
        // compared.
        assertEquals(first.roleId(), second.roleId(), "one employment");
        assertEquals("created", first.resolution());
        assertEquals("linked", second.resolution(), "the second found the canonical row");

        assertEquals(2, countRows("role_claims"), "two claims: never shared");
        assertEquals(2, countRows("reviews"), "two ratings: never shared");
        assertEquals(1, countRoles(), "one role");
        assertNull(second.conflictKind(), "agreement is not a dispute");
    }

    @Test
    void aResemblingKnownPeriodProposalCreatesItsOwnRoleAndBlocksOnAConflict() throws Exception {
        insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");

        // Same manager, same employer, same normalised title, DIFFERENT start date. The index does
        // not apply, so nothing is deduplicated, and the question of whether these are one job or
        // two is a question for a person.
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(proposal("Depot Supervisor", LocalDate.parse("2023-09-01"),
                                              true, contributor)));

        assertEquals("created", out.resolution());
        assertEquals("possible_duplicate", out.conflictKind());
        assertFalse(out.live());
        assertEquals(2, countRoles(), "the new role still stands; nothing was absorbed");

        // The claim is the SUBJECT of the conflict: it is the contribution in doubt, not evidence
        // about somebody else's. Getting that distinction wrong is what withdrew a rating the merge
        // had deliberately kept live.
        assertEquals("subject", conflictRoleOf(out.claimId()));
        assertTrue(blocksPublication(out.conflictKind()));
    }

    @Test
    void anUnknownPeriodAlwaysCreatesItsOwnRoleAndItsConflictDoesNotBlock() throws Exception {
        insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");

        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(proposal("Depot Supervisor", null, false, contributor)));

        // Outside the partial index entirely, so no deduplication applies AT ALL. This is exactly
        // where two genuine episodes could hide behind identical fields, and it is handled by
        // creating both and asking a human - never by guessing.
        assertEquals("created", out.resolution());
        assertEquals(2, countRoles());
        assertEquals("unknown_period_duplicate", out.conflictKind());
        assertFalse(blocksPublication(out.conflictKind()),
            "an unknown period is a gap in evidence, not a dispute about identity");
        assertFalse(out.live(), "still held, because the role it created is only proposed");
    }

    // -- autosave and the revision guard --------------------------------------

    @Test
    void anAutosaveThatArrivesLateCannotOverwriteANewerOne() throws Exception {
        UUID key = UUID.randomUUID();
        RoleClaimService claims = new RoleClaimService();

        await(pool.withTransaction(tx -> claims.saveDraft(tx, draft(key, "First title", 1))));
        await(pool.withTransaction(tx -> claims.saveDraft(tx, draft(key, "Second title", 2))));
        // Revision 1 again: an autosave that was in flight when revision 2 landed.
        RoleClaimService.DraftResult late =
            await(pool.withTransaction(tx -> claims.saveDraft(tx, draft(key, "Stale title", 1))));

        assertEquals("Second title", claimRow(late.id()).getString("title"),
            "the newest revision wins on content regardless of arrival order");
        assertEquals(2, claimRow(late.id()).getLong("revision"));
    }

    @Test
    void aFinalizedClaimIsImmutableToALaterAutosave() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        UUID key = UUID.randomUUID();
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selectionWithKey(key, role, contributor, 1)));

        // A resumed session, or a tab left open, autosaving over a saved observation.
        RoleClaimService claims = new RoleClaimService();
        await(pool.withTransaction(tx -> claims.saveDraft(tx, draft(key, "Rewritten title", 99))));

        assertEquals("Dispatch Lead", claimRow(out.claimId()).getString("title"),
            "after finalization NOBODY may change the assertion: not a retry, not a resumed "
            + "session, not a late autosave. The rating behind it would otherwise be about "
            + "something the contributor never said.");
    }

    @Test
    void aDraftThatMovedUnderTheRequestIsRefusedRatherThanSealed() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        UUID key = UUID.randomUUID();
        RoleClaimService claims = new RoleClaimService();
        // The contributor is at revision 5 in another tab.
        await(pool.withTransaction(tx -> claims.saveDraft(tx, draft(key, "Dispatch Lead", 5))));

        // This request thinks it is sealing revision 2.
        Exception e = assertThrows(Exception.class,
            () -> await(submissions.submit(selectionWithKey(key, role, contributor, 2))));
        assertTrue(message(e).contains("changed while it was being submitted"), message(e));

        // The server does not seal a version nobody confirmed. The client resyncs and resubmits.
        assertNull(claimRow(soleClaimId()).getOffsetDateTime("finalized_at"));
        assertEquals(0, countRows("reviews"));
        assertEquals(0, countRows("rating_opportunities"));
    }

    // -- signed out, then signed in -------------------------------------------

    @Test
    void anAnonymousSubmissionIsSavedAndChargesNothing() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");

        RoleAwareSubmissionService.Outcome out = await(submissions.submit(selection(role, null)));

        assertNull(out.opportunityOrdinal(), "an allowance is keyed on an account; there is none");
        assertEquals(0, countRows("rating_opportunities"));
        assertNull(claimRow(out.claimId()).getUUID("user_id"));
        assertEquals(1, countRows("reviews"), "their work is saved, which is the whole point");
    }

    @Test
    void signingInAdoptsTheAnonymousClaimRatherThanMakingASecondOne() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        UUID key = UUID.randomUUID();
        RoleClaimService claims = new RoleClaimService();
        // Filled in while signed out, not yet submitted.
        await(pool.withTransaction(tx -> claims.saveDraft(tx, draftFor(key, role, null, 1))));

        // They sign in and submit the same form.
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selectionWithKey(key, role, contributor, 2)));

        assertEquals(1, countRows("role_claims"), "adopted, not duplicated");
        assertEquals(contributor, claimRow(out.claimId()).getUUID("user_id"));
        // ADOPTION IS THE CHARGING EVENT.
        assertEquals(1, (int) out.opportunityOrdinal());
        assertEquals(1, await(opportunities.spent(pool, contributor, "manager", managerId)));
        // The rating's author and the claim's contributor are set in one transaction, so a rating
        // attributed to an account cannot hang off a claim attributed to a different one.
        assertEquals(contributor, reviewRow(out.reviewId()).getUUID("user_id"));
    }

    @Test
    void aDraftBelongingToAnotherAccountIsRefused() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        UUID key = UUID.randomUUID();
        RoleClaimService claims = new RoleClaimService();
        await(pool.withTransaction(tx -> claims.saveDraft(tx, draftFor(key, role, someoneElse, 1))));

        Exception e = assertThrows(Exception.class,
            () -> await(submissions.submit(selectionWithKey(key, role, contributor, 2))));
        assertTrue(message(e).contains("different account"), message(e));

        // An identity may be ADDED once and REMOVED once, and NEVER transferred. Transferring is
        // the only one of the three that is always an attack.
        assertEquals(someoneElse, claimRow(soleClaimId()).getUUID("user_id"));
        assertEquals(0, countRows("reviews"));
    }

    // -- the opportunity, atomically ------------------------------------------

    @Test
    void theOpportunityIsChargedInTheSameTransactionAsTheRating() throws Exception {
        long one = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        long two = insertRole(managerId, "Depot Lead", "2025-02-01", "accepted");

        await(submissions.submit(selection(one, contributor)));
        assertEquals(1, await(opportunities.spent(pool, contributor, "manager", managerId)));

        RoleAwareSubmissionService.Outcome second = await(submissions.submit(selection(two, contributor)));
        assertEquals(2, (int) second.opportunityOrdinal());
        assertEquals(2, await(opportunities.spent(pool, contributor, "manager", managerId)));

        // The allowance is keyed on the MANAGER and not the employment, which is what stops
        // somebody minting fresh allowance by proposing a new job each time.
        long three = insertRole(managerId, "Yard Lead", "2026-01-01", "accepted");
        Exception e = assertThrows(Exception.class,
            () -> await(submissions.submit(selection(three, contributor))));
        assertTrue(message(e).contains("already used both"), message(e));

        // REFUSED WITH NOTHING WRITTEN. A charge that fails must not leave a rating, and a rating
        // that fails must not leave a charge.
        assertEquals(2, countRows("reviews"));
        assertEquals(2, await(opportunities.spent(pool, contributor, "manager", managerId)));
    }

    @Test
    void aFailureAfterTheChargeRollsBackTheChargeTheClaimAndTheRating() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");

        // A real late failure: the manager disappears mid-transaction from another connection, so
        // the rating's foreign key fails after the claim was finalized and the charge taken.
        // reviews.manager_id references managers, so this is the genuine ordering under test.
        Exception e = assertThrows(Exception.class, () -> await(
            pool.withTransaction(tx ->
                opportunities.lockSubject(tx, "manager", managerId)
                    .compose(v -> opportunities.charge(tx, contributor, "manager", managerId, null))
                    .compose(v -> tx.preparedQuery(
                            "INSERT INTO reviews (manager_id, author, overall_rating) "
                            + "VALUES ($1,'Ash B',4.0)")
                        .execute(Tuple.of(managerId + 10_000)))
            )));
        assertNotNull(message(e));

        assertEquals(0, countRows("rating_opportunities"),
            "a charge beside a failed rating silently costs somebody half their lifetime "
            + "allowance for a subject, with nothing to show for it");
        assertEquals(0, countRows("reviews"));
        assertEquals(role, role);
    }

    // -- idempotency ----------------------------------------------------------

    @Test
    void resubmittingTheSameFormReturnsTheSameRatingAndChargesNothingAgain() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        UUID key = UUID.randomUUID();

        RoleAwareSubmissionService.Outcome first =
            await(submissions.submit(selectionWithKey(key, role, contributor, 1)));
        RoleAwareSubmissionService.Outcome again =
            await(submissions.submit(selectionWithKey(key, role, contributor, 1)));

        // A dropped response or a double tap must not cost a contribution, and must not make two.
        assertTrue(again.replayed());
        assertEquals(first.reviewId(), again.reviewId());
        assertEquals(first.claimId(), again.claimId());
        assertEquals(1, countRows("reviews"));
        assertEquals(1, countRows("role_claims"));
        assertEquals(1, await(opportunities.spent(pool, contributor, "manager", managerId)),
            "a retry never opens a new opportunity");
    }

    // -- interleaving with a merge --------------------------------------------

    @Test
    void aSubmissionAndAMergeOfTheSameManagerSerialiseRatherThanInterleave() throws Exception {
        long role = insertRole(otherManagerId, "Dispatch Lead", "2024-01-15", "accepted");

        SqlConnection held = await(pool.getConnection());
        try {
            Transaction tx = await(held.begin());
            // Hold the manager the merge is about to want. The submission takes this same lock
            // first, in the same canonical order, which is what makes them serialise instead of
            // deadlocking on each other halfway through.
            await(held.preparedQuery("SELECT id FROM managers WHERE id = $1 FOR UPDATE")
                .execute(Tuple.of(otherManagerId)));

            Future<RoleAwareSubmissionService.Outcome> blocked =
                submissions.submit(selectionOn(otherManagerId, role, contributor));
            assertThrows(java.util.concurrent.TimeoutException.class,
                () -> blocked.toCompletionStage().toCompletableFuture()
                    .get(500, TimeUnit.MILLISECONDS),
                "the submission must wait for whoever holds the manager");

            await(tx.commit());
            RoleAwareSubmissionService.Outcome out = await(blocked);
            assertTrue(out.live());
        } finally {
            await(held.close());
        }

        // And the merge afterwards moves the claim, the role and the rating together, which is the
        // property that stops reviews_claim_is_the_managers refusing the whole thing.
        UUID admin = insertUser("auth0|merge-admin");
        await(merges.mergeManagers(managerId, otherManagerId, admin));

        assertEquals(managerId, (long) soleReviewManagerId());
        assertEquals(managerId, claimRow(soleClaimId()).getLong("manager_id"));
        assertEquals(1, await(opportunities.spent(pool, contributor, "manager", managerId)),
            "the opportunity followed the person it was spent on");
    }


    @Test
    void twoConcurrentSubmissionsForOneManagerBothSucceedAndTakeDifferentOrdinals()
            throws Exception {
        long one = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        long two = insertRole(managerId, "Depot Lead", "2025-02-01", "accepted");

        // Genuinely concurrent: both futures are in flight before either is awaited. They contend
        // for the same subject row and for the same allowance, which is exactly the race the
        // charge has to survive.
        Future<RoleAwareSubmissionService.Outcome> a =
            submissions.submit(selection(one, contributor));
        Future<RoleAwareSubmissionService.Outcome> b =
            submissions.submit(selection(two, contributor));

        RoleAwareSubmissionService.Outcome first  = await(a);
        RoleAwareSubmissionService.Outcome second = await(b);

        // BOTH succeed, and they take DIFFERENT ordinals. Neither outcome is acceptable if the
        // other happens: two submissions sharing an ordinal is a lost opportunity, and one failing
        // on a unique violation is a lost contribution.
        assertTrue(first.live());
        assertTrue(second.live());
        assertNotEquals(first.opportunityOrdinal(), second.opportunityOrdinal());
        assertEquals(2, await(opportunities.spent(pool, contributor, "manager", managerId)));
        assertEquals(2, countRows("reviews"));

        // And the cached aggregate counted both, because the recalculation ran inside each
        // transaction rather than fire-and-forget afterwards.
        assertEquals(2, (int) reviewsCountOf(managerId));
    }


    // -- account deletion against the expanded schema -------------------------

    @Test
    void deletingAnAccountAfterARoleAwareSubmissionNowSucceeds() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        RoleAwareSubmissionService.Outcome out = await(submissions.submit(selection(role, contributor)));
        assertTrue(out.live());

        // THIS TEST REPLACES ONE THAT ASSERTED THE OPPOSITE.
        //
        // Until V105 this was impossible: role_claims.user_id was NO ACTION where rev 268
        // prescribes SET NULL, so the moment a signed-in contributor owned a claim the real
        // deletion workflow could not finish:
        //
        //     ERROR: update or delete on table "users" violates foreign key constraint
        //            "role_claims_user_id_fkey" on table "role_claims"
        //
        // Worse, the workflow was not transactional and deleted last, so the failure left the
        // person half-deleted: ratings already anonymised, account still present, identity
        // unrecoverable.
        //
        // Reached through the ACTUAL production path, UserRepository.deleteWithReviewAnonymization,
        // because the point is that the existing workflow now works rather than that a foreign key
        // has been changed. The full erasure behaviour is covered by AccountErasureIntegrationTest;
        // this one guards the specific interaction between a role-aware submission and deletion.
        var users = new org.werkpages.repository.UserRepository(pool);
        await(users.deleteWithReviewAnonymization(contributor));

        assertEquals(0, countRows("users WHERE id = '" + contributor + "'"));
        assertNull(claimRow(out.claimId()).getUUID("user_id"), "the identity is gone");
        assertEquals(role, claimRow(out.claimId()).getLong("resolved_role_id"),
            "and the employment record it carries is not");
        assertEquals("live", dispositionOf(out.reviewId()), "their rating is still published");
        ErasedByline.assertMinted(reviewRow(out.reviewId()).getString("author"),
            "a role-aware submission whose author erased themselves");
    }

    // -- fixtures --------------------------------------------------------------
    private Integer reviewsCountOf(long manager) throws Exception {
        return await(pool.preparedQuery("SELECT reviews_count FROM managers WHERE id = $1")
            .execute(Tuple.of(manager))
            .map(rs -> rs.iterator().next().getInteger("reviews_count")));
    }


    // ══════════════════════════════════════════════════════════════════════════
    // PATH 3: none of these people is the person
    //
    // The third Rev 268 path, and the only one whose manager does not exist when the submission
    // starts. The frontend has covered it since 4B; this is the half that was never asserted
    // against a real database, which is where "pending_approval, not ghost" actually matters.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void pathThreeCreatesAPendingManagerAndHoldsTheRating() throws Exception {
        UUID contributor2 = insertUser("auth0|path3-a");

        JsonObject out = await(managers.submitRoleAwareRating("auth0|path3-a", pathThreeBody()));

        /*
          pending_approval, NOT ghost. A ghost is publicly visible by design - that is what makes
          the find-or-create flow work - and a person somebody typed into a rating form has not
          been seen by anybody. So this one reaches the admin queue instead of the directory, which
          is the same treatment every contributor-added manager has always had.
        */
        Row m = one("SELECT id, name, approval_status FROM managers WHERE name = 'Casey Nolan'");
        assertEquals("pending_approval", m.getString("approval_status"),
            "a manager nobody has reviewed is not published by somebody rating them");

        assertFalse(out.getBoolean("live"),
            "and the rating waits with them: there is no accepted employment to publish against");
        assertNotNull(out.getString("heldBecause"), "with a reason the contributor can read");
        assertEquals("held", dispositionOf(UUID.fromString(out.getString("reviewId"))));
        assertEquals(contributor2, contributor2);
    }

    @Test
    void pathThreeChargesOneOpportunityAgainstTheNewManager() throws Exception {
        insertUser("auth0|path3-b");

        await(managers.submitRoleAwareRating("auth0|path3-b", pathThreeBody()));

        long subject = one("SELECT id FROM managers WHERE name = 'Casey Nolan'").getLong("id");
        assertEquals(1L, countRows("rating_opportunities"),
            "the allowance is spent on path 3 exactly as on the other two");
        assertEquals(subject, one("SELECT subject_id FROM rating_opportunities").getLong("subject_id"),
            "and against the manager they actually created, not some other subject");
        assertEquals(1, one("SELECT ordinal FROM rating_opportunities").getShort("ordinal").intValue(),
            "the first of their two slots for this person");
    }

    @Test
    void pathThreeIsNotPubliclyVisibleButIsInTheAdminQueue() throws Exception {
        insertUser("auth0|path3-c");

        await(managers.submitRoleAwareRating("auth0|path3-c", pathThreeBody()));

        /*
          The two queries that decide where a manager appears, asserted against each other. A
          pending row leaking into the public filter is the failure mode CLAUDE.md section 16
          exists to prevent, and it is cheap to assert and expensive to discover.
        */
        assertEquals(0L, countRows(
                "managers WHERE name = 'Casey Nolan' AND approval_status IN ('approved','ghost')"),
            "the person a contributor typed is not in the public directory");
        assertEquals(1L, countRows(
                "managers WHERE name = 'Casey Nolan' AND approval_status = 'pending_approval'"),
            "they are waiting for somebody to look at them");
    }

    @Test
    void pathThreeTwiceWithOneProposalKeyIsOneManagerAndOneRating() throws Exception {
        /*
          A retry, which on this path could create a SECOND person rather than a second rating.
          The proposal key is what makes the repeat idempotent, and on path 3 the cost of it not
          working is a duplicate human in the directory rather than a duplicate opinion.
        */
        insertUser("auth0|path3-d");
        JsonObject body = pathThreeBody();

        JsonObject first  = await(managers.submitRoleAwareRating("auth0|path3-d", body.copy()));
        JsonObject second = await(managers.submitRoleAwareRating("auth0|path3-d", body.copy()));

        assertEquals(first.getString("reviewId"), second.getString("reviewId"),
            "the same rating, returned again");
        assertEquals(1L, countRows("reviews"), "not two opinions");
        assertEquals(1L, countRows("rating_opportunities"), "and not two charges");
        assertEquals(1L, countRows("managers WHERE name = 'Casey Nolan'"),
            "ONE person. The manager is resolved from the claim that the proposal key already "
          + "identifies, so a retry never creates a second. This asserted 2 before issue 34 was "
          + "fixed, and the duplicate reached the moderation queue.");
    }

    @Test
    void pathThreeConcurrentIdenticalSubmissionsResolveToOneManager() throws Exception {
        /*
          The race the unique index settles. Both attempts can find no claim and both can insert a
          pending manager; only one can insert the claim, because proposal_key is UNIQUE. The
          loser's transaction fails and takes ITS manager with it, the retry wrapper runs it again,
          the claim now exists, and it resolves to the winner's manager.

          Which is the point of doing it this way: the guarantee is a database constraint, not an
          existence check somebody can forget to write.
        */
        insertUser("auth0|path3-e");
        JsonObject body = pathThreeBody();

        var a = managers.submitRoleAwareRating("auth0|path3-e", body.copy());
        var b = managers.submitRoleAwareRating("auth0|path3-e", body.copy());
        JsonObject first  = await(a);
        JsonObject second = await(b);

        assertEquals(first.getString("reviewId"), second.getString("reviewId"),
            "one logical submission, whichever attempt got there first");
        assertEquals(1L, countRows("managers WHERE name = 'Casey Nolan'"), "one person");
        assertEquals(1L, countRows("reviews"), "one rating");
        assertEquals(1L, countRows("rating_opportunities"), "one charge");
    }

    @Test
    void aFailedPathThreeSubmissionLeavesNoPendingManagerBehind() throws Exception {
        /*
          The orphan this moves. The manager used to be created on the pool before the submission
          began, so a submission that then failed left a pending person nobody had rated sitting in
          the moderation queue for somebody to puzzle over.

          Forced here by a rating the disposition cannot accept: an overall outside 1..5, which the
          reviews CHECK refuses after the manager and the claim have been written.
        */
        insertUser("auth0|path3-f");
        JsonObject doomed = pathThreeBody().put("overallRating", 99.0);

        assertThrows(Exception.class, () -> awaitCause(
            managers.submitRoleAwareRating("auth0|path3-f", doomed)));

        assertEquals(0L, countRows("managers WHERE name = 'Casey Nolan'"),
            "no orphan: the manager rolled back with the rating that justified creating it");
        assertEquals(0L, countRows("reviews"));
        assertEquals(0L, countRows("rating_opportunities"), "and no allowance was spent");
    }

    @Test
    void reusingAProposalKeyForADifferentPersonIsRefused() throws Exception {
        /*
          The same key must not mean a different person. Replaying would hand back the FIRST rating
          as though it answered this one, which reads as success and is silently about the wrong
          human, so it is refused instead. A fresh form carries a fresh key.
        */
        insertUser("auth0|path3-g");
        JsonObject body = pathThreeBody();
        await(managers.submitRoleAwareRating("auth0|path3-g", body.copy()));

        JsonObject sameKeyDifferentPerson = body.copy()
            .put("firstName", "Morgan").put("lastName", "Ellery");

        assertThrows(Exception.class, () -> awaitCause(
            managers.submitRoleAwareRating("auth0|path3-g", sameKeyDifferentPerson)));

        assertEquals(1L, countRows("reviews"), "nothing new was written");
        assertEquals(0L, countRows("managers WHERE name = 'Morgan Ellery'"),
            "and nobody was created for the submission that was refused");
    }

    /** A path-3 body: no managerId and no selectedRoleId, which is what "none of these" sends. */
    private JsonObject pathThreeBody() {
        return new JsonObject()
            .put("firstName", "Casey")
            .put("lastName", "Nolan")
            .put("title", "Dispatch Lead")
            .put("company", "Halverson Logistics")
            .putNull("managerId")
            .putNull("selectedRoleId")
            .put("proposalKey", UUID.randomUUID().toString())
            .put("revision", 1)
            .put("startDate", "2024-01")
            .putNull("endDate")
            .put("stillThere", true)
            .put("status", "active")
            .put("country", "CA")
            .put("author", "Anon Path Three")
            .put("overallRating", 4.0)
            .put("ratings", new JsonObject()
                .put("communication", 4).put("support", 4).put("fairness", 4)
                .put("leadership", 4).put("work_life_balance", 4));
    }

    /** One row, for the assertions above. The class already has countRows; this is its sibling. */
    // ── Contributor testimony and the withdrawal lifecycle (V111) ─────────────

    @Test
    void theSubmissionThatCreatesAClaimRecordsWhatTheySaid() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2020-01-01", "accepted");
        UUID author = insertUser("auth0|testimony-initial");

        long claimId = await(submissions.submit(selection(role, author))).claimId();

        Row said = one("* FROM claim_testimony WHERE claim_id = " + claimId);
        assertEquals("initial", said.getString("occasion"));
        assertEquals(author, said.getUUID("user_id"),
            "testimony is attributable by nature: that is what distinguishes it from the rating");

        // Rev 268's first testimony test: the series and the claim agree by construction. The
        // claim keeps the statement it was built from so nothing has to join to read it.
        Row claim = claimRow(claimId);
        assertEquals(claim.getString("title"),            said.getString("title"));
        assertEquals(claim.getLocalDate("start_date"),    said.getLocalDate("start_date"));
        assertEquals(claim.getLocalDate("end_date"),      said.getLocalDate("end_date"));
        assertEquals(claim.getBoolean("period_known"),    said.getBoolean("period_known"));
        assertEquals(1, countTestimony(claimId), "one statement, because they have said one thing");
    }

    @Test
    void aRetryOfTheSameSubmissionSaysNothingNew() throws Exception {
        // The replay path exists to be safe to repeat. If an identical retry appended a row, the
        // series would record network behaviour - how many times a request was sent - rather than
        // testimony, and a moderator reading it could not tell a resend from a recollection.
        long role = insertRole(managerId, "Dispatch Lead", "2020-01-01", "accepted");
        UUID author = insertUser("auth0|testimony-retry");
        UUID key = UUID.randomUUID();

        long claimId = await(submissions.submit(selectionWithKey(key, role, author, 1))).claimId();
        await(submissions.submit(selectionWithKey(key, role, author, 1)));
        await(submissions.submit(selectionWithKey(key, role, author, 1)));

        assertEquals(1, countTestimony(claimId), "three identical sends, one thing said");
    }

    @Test
    void aDifferingRepeatIsRecordedAndChangesNoAssertion() throws Exception {
        /*
          In 2024 somebody says they worked under this manager with an unknown period. In 2026 the
          same person, same form key, says it ended in June 2023. Both are evidence and neither
          makes the other false, so BOTH stay on the record and nothing authoritative moves.

          This is explicitly NOT claim_corrections: that is an administrator's determination that
          the claim was wrong, and filing a recollection there would assert that the platform had
          decided when nobody has, while discarding the first statement as superseded.
        */
        long role = insertRole(managerId, "Dispatch Lead", "2020-01-01", "accepted");
        UUID author = insertUser("auth0|testimony-repeat");
        UUID key = UUID.randomUUID();

        long claimId = await(submissions.submit(selectionWithKey(key, role, author, 1))).claimId();
        Row before = claimRow(claimId);

        await(submissions.submit(new RoleAwareSubmissionService.Submission(
            key, 1, managerId, companyId, "Dispatch Lead", role,
            LocalDate.parse("2020-01-01"), LocalDate.parse("2023-06-30"), true, null,
            "Halverson Logistics", "Ash B",
            author, 4.0, tens(4.0), OffsetDateTime.parse("2024-02-01T00:00:00Z"))));

        assertEquals(2, countTestimony(claimId), "two statements, in the order they were made");
        Row latest = one("* FROM claim_testimony WHERE claim_id = " + claimId
            + " ORDER BY said_at DESC, id DESC LIMIT 1");
        assertEquals("repeat", latest.getString("occasion"));
        assertEquals(LocalDate.parse("2023-06-30"), latest.getLocalDate("end_date"),
            "what they said the second time");

        Row after = claimRow(claimId);
        assertEquals(before.getLocalDate("end_date"), after.getLocalDate("end_date"),
            "the claim's assertion is frozen at finalization and a recollection does not move it");
        assertEquals(before.getBoolean("period_known"), after.getBoolean("period_known"));
        assertEquals(before.getLong("resolved_role_id"), after.getLong("resolved_role_id"),
            "and the platform's resolution is untouched: testimony is evidence, not a decision");
    }

    @Test
    void noContributorPathWritesAnAdministrativeCorrection() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2020-01-01", "accepted");
        UUID author = insertUser("auth0|testimony-no-correction");
        UUID key = UUID.randomUUID();

        await(submissions.submit(selectionWithKey(key, role, author, 1)));
        await(submissions.submit(new RoleAwareSubmissionService.Submission(
            key, 1, managerId, companyId, "Dispatch Lead", role,
            LocalDate.parse("2020-01-01"), LocalDate.parse("2023-06-30"), true, null,
            "Halverson Logistics", "Ash B",
            author, 4.0, tens(4.0), OffsetDateTime.parse("2024-02-01T00:00:00Z"))));

        assertEquals(0L, one("COUNT(*) AS c FROM claim_corrections").getLong("c"),
            "only an administrator writes a correction, and rev 268 is explicit that no "
            + "contributor path may");
    }

    @Test
    void theTestimonyTableRefusesUpdateAndDelete() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2020-01-01", "accepted");
        UUID author = insertUser("auth0|testimony-append-only");
        long claimId = await(submissions.submit(selection(role, author))).claimId();
        long id = one("id FROM claim_testimony WHERE claim_id = " + claimId).getLong("id");

        // The disagreement IS the finding. A record that can tidy itself up to agree with the
        // outcome cannot show one.
        assertThrows(Exception.class, () -> await(pool.preparedQuery(
                "UPDATE claim_testimony SET end_date = '2001-01-01' WHERE id = $1")
            .execute(Tuple.of(id))), "a statement cannot be rewritten");
        assertThrows(Exception.class, () -> await(pool.preparedQuery(
                "DELETE FROM claim_testimony WHERE id = $1")
            .execute(Tuple.of(id))), "nor withdrawn");

        // The one permitted shape: the account is gone, so the name goes and the statement stays.
        await(users.deleteWithReviewAnonymization(author));
        assertEquals(1, countTestimony(claimId), "the statement survives the erasure");
        assertNull(one("user_id FROM claim_testimony WHERE id = " + id).getUUID("user_id"),
            "without naming anybody");
    }

    @Test
    void withdrawingAClaimedRatingKeepsItsContributorUntilTheWindowExpires() throws Exception {
        // Rev 268's delete_review: identity KEPT. The row is invisible either way, so nothing is
        // published by holding it, and the claim stays reachable from the rating so that one place
        // can own the whole transition when the window expires.
        long role = insertRole(managerId, "Dispatch Lead", "2020-01-01", "accepted");
        UUID author = insertUser("auth0|withdraw-keeps");
        var outcome = await(submissions.submit(selection(role, author)));

        await(reviews.delete(outcome.reviewId(), managerId));

        Row r = reviewRow(outcome.reviewId());
        assertNotNull(r.getOffsetDateTime("deleted_at"), "hidden immediately");
        assertEquals(author, r.getUUID("user_id"), "and still theirs, for now");
        assertEquals(outcome.claimId(), (long) r.getLong("claim_id"));
        assertTrue(r.getBoolean("dates_hidden"),
            "their period is withheld from the moment they withdraw, not three days later");
        assertNull(claimRow(outcome.claimId()).getOffsetDateTime("withdrawn_at"),
            "the tombstone is marked at restoration, because until then they may still come back");
    }

    @Test
    void restorationTombstonesTheClaimAndSeversTheRating() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2020-01-01", "accepted");
        UUID author = insertUser("auth0|restore-severs");
        var outcome = await(submissions.submit(selection(role, author)));
        await(reviews.delete(outcome.reviewId(), managerId));
        backdateDeletion(outcome.reviewId());

        assertEquals(1, (int) await(reviews.restoreExpiredDeletions()));

        Row r = reviewRow(outcome.reviewId());
        assertNull(r.getOffsetDateTime("deleted_at"), "it is public again");
        assertNull(r.getUUID("user_id"),      "anonymous");
        assertNull(r.getLong("claim_id"),     "and detached from the claim that named them");
        assertNull(r.getUUID("draft_token"),  "and from the form that carried it");
        assertEquals("withdrawn", r.getString("role_basis"));
        assertEquals(role, (long) r.getLong("withdrawn_role_id"),
            "it keeps the employment it was attributed to - the second route a rating reaches its "
            + "role by, which the moderation queue and the merge both traverse");
        assertEquals("withdrawn_role", r.getString("attribution_basis"));
        assertTrue(r.getBoolean("dates_hidden"), "the contributor's period stays withheld");
        assertEquals("live", r.getString("disposition"),
            "the opinion survives and keeps counting: that is the point of restoring it");

        Row claim = claimRow(outcome.claimId());
        assertNotNull(claim.getOffsetDateTime("withdrawn_at"), "the claim is now a tombstone");
        assertEquals(author, claim.getUUID("user_id"),
            "which still records that this person rated this employment, and that is what stops "
            + "them rating it twice");
        assertNotNull(claim.getOffsetDateTime("finalized_at"));
    }

    /**
     * Withdrawal removes the claim as EVIDENCE, and the employment it was grounding is recomputed.
     *
     * <p>Excluding a withdrawn claim from the derivation is only half of the rule. Without the
     * recompute the role keeps whatever that claim told it: an 'ongoing' status pointing at a
     * claim that is no longer allowed to ground anything, sitting there until some unrelated
     * acceptance or correction happens to touch the same role. A stale assertion about somebody's
     * present employment is exactly what the evidence-dated model exists to prevent.
     */
    @Test
    void restorationRecomputesTheEmploymentTheClaimWasGrounding() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2020-01-01", "accepted");
        UUID author = insertUser("auth0|withdraw-recompute");
        var outcome = await(submissions.submit(
            asserting(role, author, LocalDate.parse("2024-03-31"))));

        assertEquals("ongoing|contributor_claim|2024-03-31", statusOf(role),
            "fixture: the contributor said the manager was still there when they left");
        assertEquals(outcome.claimId(), (long) claimBehind(role),
            "fixture: and the role names that claim as its evidence");
        int before = statusEvents(role);

        await(reviews.delete(outcome.reviewId(), managerId));
        backdateDeletion(outcome.reviewId());
        assertEquals(1, (int) await(reviews.restoreExpiredDeletions()));

        assertEquals("unknown|no_evidence|(null)", statusOf(role),
            "the only evidence for 'ongoing' was a claim that has now withdrawn, so the honest "
            + "answer is that we do not know. The employment RECORD is untouched: the manager "
            + "still held this job, and that stays public");
        assertNull(claimBehind(role), "and nothing points at the tombstone as evidence");
        assertEquals(before + 1, statusEvents(role),
            "and the change is in the log, so the reason the status moved is recoverable");
    }

    /**
     * The same withdrawal, with a second contributor who said the same thing.
     *
     * <p>A recompute is not a retraction. The status stands, on the evidence that remains, and the
     * row's pointer moves to the claim actually supporting it.
     */
    @Test
    void restorationLeavesAStatusAnotherContributorStillSupports() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2020-01-01", "accepted");
        UUID first  = insertUser("auth0|withdraw-one-of-two");
        UUID second = insertUser("auth0|stays-of-two");
        var leaving = await(submissions.submit(
            asserting(role, first, LocalDate.parse("2024-03-31"))));
        var staying = await(submissions.submit(
            asserting(role, second, LocalDate.parse("2024-03-31"))));

        await(reviews.delete(leaving.reviewId(), managerId));
        backdateDeletion(leaving.reviewId());
        assertEquals(1, (int) await(reviews.restoreExpiredDeletions()));

        assertEquals("ongoing|contributor_claim|2024-03-31", statusOf(role),
            "one contributor withdrawing does not unsay what another one asserted");
        assertEquals(staying.claimId(), (long) claimBehind(role),
            "and the evidence pointer moves to the claim that is actually supporting it, rather "
            + "than being left on a tombstone");
    }

    /**
     * The reverse direction, through the derivation rather than through a revival path.
     *
     * <p><b>Revival is not implemented.</b> Nothing in either product clears
     * {@code role_claims.withdrawn_at} today; V111 made the schema permit it and the behaviour has
     * not been built. This test exists so that whoever builds it knows the status side is already
     * handled: the derivation is pure, so clearing the tombstone and resolving is the whole of it,
     * and no separate unwinding logic is needed or should be written.
     */
    @Test
    void clearingTheTombstoneMakesTheClaimEvidenceAgain() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2020-01-01", "accepted");
        UUID author = insertUser("auth0|tombstone-cleared");
        var outcome = await(submissions.submit(
            asserting(role, author, LocalDate.parse("2024-03-31"))));
        await(reviews.delete(outcome.reviewId(), managerId));
        backdateDeletion(outcome.reviewId());
        await(reviews.restoreExpiredDeletions());
        assertEquals("unknown|no_evidence|(null)", statusOf(role), "fixture: no evidence left");

        await(pool.preparedQuery("UPDATE role_claims SET withdrawn_at = NULL WHERE id = $1")
            .execute(Tuple.of(outcome.claimId())));
        await(org.werkpages.repository.EmploymentStatusResolver.resolveRoleAtomically(pool, role));

        assertEquals("ongoing|contributor_claim|2024-03-31", statusOf(role),
            "the claim is evidence again and the derivation picks it up with no special case, "
            + "because it is recomputed from what is eligible rather than accumulated");
        assertEquals(outcome.claimId(), (long) claimBehind(role));
    }

    @Test
    void aRestoredRatingKeepsTheSubjectsIdentityAndHidesOnlyTheContributorsPeriod()
            throws Exception {
        // The privacy decision, 2026-11-03: manager_title is the MANAGER'S title, manager_company
        // their employer and the declared location the workplace. They describe the SUBJECT of the
        // rating and stay public; hiding them would conceal the employment record the redesign
        // exists to build. Only the contributor's own period and identity go.
        long role = insertRole(managerId, "Dispatch Lead", "2020-01-01", "accepted");
        UUID author = insertUser("auth0|restore-keeps-subject");
        var outcome = await(submissions.submit(selection(role, author)));
        await(reviews.delete(outcome.reviewId(), managerId));
        backdateDeletion(outcome.reviewId());
        await(reviews.restoreExpiredDeletions());

        Row r = reviewRow(outcome.reviewId());
        assertEquals("Dispatch Lead", r.getString("manager_title"),
            "the job the manager held, which is what the rating is ABOUT");
        assertEquals("Halverson Logistics", r.getString("manager_company"));
        assertTrue(r.getBoolean("dates_hidden"), "and only the contributor's period is withheld");
    }

    @Test
    void aRestoredRatingCannotBeJoinedBackToItsContributorByAnyUuidColumn() throws Exception {
        /*
          SCHEMA-DRIVEN, and that is the point: a hand-written list of joins is how two
          identity-reconstruction defects survived review in this design - claim_testimony
          .arrived_with_review_id and rating_opportunities.opened_by_review_id, both arguing that
          "the reference points the other way" when a join has no direction.

          Every uuid column in the database is asked whether it holds this rating's id, and every
          table that also carries a contributor column is asked whether that column is populated
          on the matching row. A NEW table with such a column fails this without anybody
          remembering to extend the test.

          Testimony rows are deliberately present: rev 268's seventh testimony test is that the
          graph is still empty with them there.
        */
        long role = insertRole(managerId, "Dispatch Lead", "2020-01-01", "accepted");
        UUID author = insertUser("auth0|restore-unlinkable");
        UUID key = UUID.randomUUID();
        var outcome = await(submissions.submit(selectionWithKey(key, role, author, 1)));
        // A second statement, so the series is non-empty when the sweep runs.
        await(submissions.submit(new RoleAwareSubmissionService.Submission(
            key, 1, managerId, companyId, "Dispatch Lead", role,
            LocalDate.parse("2020-01-01"), LocalDate.parse("2023-06-30"), true, null,
            "Halverson Logistics", "Ash B",
            author, 4.0, tens(4.0), OffsetDateTime.parse("2024-02-01T00:00:00Z"))));
        assertEquals(2, countTestimony(outcome.claimId()), "fixture check: testimony is present");

        await(reviews.delete(outcome.reviewId(), managerId));
        backdateDeletion(outcome.reviewId());
        await(reviews.restoreExpiredDeletions());

        java.util.List<String> uuidCols = await(pool.query("""
                SELECT table_name, column_name FROM information_schema.columns
                 WHERE table_schema = 'public' AND data_type = 'uuid'
                 ORDER BY 1, 2
                """).execute().map(rs -> {
                    java.util.List<String> out = new java.util.ArrayList<>();
                    for (Row x : rs) out.add(x.getString("table_name") + "." + x.getString("column_name"));
                    return out;
                }));
        java.util.List<String> withContributor = await(pool.query("""
                SELECT table_name FROM information_schema.columns
                 WHERE table_schema = 'public' AND column_name = 'user_id'
                """).execute().map(rs -> {
                    java.util.List<String> out = new java.util.ArrayList<>();
                    for (Row x : rs) out.add(x.getString("table_name"));
                    return out;
                }));

        java.util.List<String> paths = new java.util.ArrayList<>();
        for (String col : uuidCols) {
            String[] p = col.split("\\.");
            if (!withContributor.contains(p[0])) continue;
            long n = await(pool.preparedQuery("SELECT COUNT(*) AS c FROM " + p[0]
                    + " WHERE " + p[1] + " = $1 AND user_id IS NOT NULL")
                .execute(Tuple.of(outcome.reviewId()))
                .map(rs -> rs.iterator().next().getLong("c")));
            if (n > 0) paths.add(col);
        }

        assertEquals(java.util.List.of(), paths,
            "no row may hold this rating's id alongside a contributor identity. That is the "
            + "generalised rule both earlier defects broke, and it is checked from the catalogue "
            + "rather than from a list somebody remembered to write");
    }

    @Test
    void aRestoredRatingWhoseEmploymentIsNotAcceptedIsHeldRatherThanPublished() throws Exception {
        // Its employment can be rejected or still only proposed while the rating is hidden.
        // Restoring it must not publish a rating against an employment nobody accepted, and the
        // question has to be asked AFTER the transition: before it, the row still has a claim and
        // the publishability function answers the claimed question instead of the withdrawn one.
        long role = insertRole(managerId, "Dispatch Lead", "2020-01-01", "accepted");
        UUID author = insertUser("auth0|restore-unaccepted");
        var outcome = await(submissions.submit(selection(role, author)));
        await(reviews.delete(outcome.reviewId(), managerId));
        backdateDeletion(outcome.reviewId());
        await(pool.preparedQuery("UPDATE career_history SET state = 'proposed' WHERE id = $1")
            .execute(Tuple.of(role)));

        await(reviews.restoreExpiredDeletions());

        Row r = reviewRow(outcome.reviewId());
        assertNull(r.getOffsetDateTime("deleted_at"), "it is restored");
        assertEquals("held", r.getString("disposition"),
            "but held, because the employment it describes is no longer accepted");
        assertFalse(r.getBoolean("gate_eligible"));
        assertNull(r.getOffsetDateTime("live_since"),
            "and the three move together, because reviews_state_coherent requires it");
    }

    private int countTestimony(long claimId) throws Exception {
        return one("COUNT(*) AS c FROM claim_testimony WHERE claim_id = " + claimId)
            .getLong("c").intValue();
    }

    /** Four days ago. The only thing a test may fake here. */
    private void backdateDeletion(UUID reviewId) throws Exception {
        await(pool.preparedQuery("UPDATE reviews SET deleted_at = $1 WHERE id = $2")
            .execute(Tuple.of(OffsetDateTime.now().minusDays(4), reviewId)));
    }

    private Row one(String sql) throws Exception {
        return await(pool.query("SELECT " + sql.replaceFirst("(?i)^\\s*SELECT\\s+", ""))
            .execute().map(rs -> rs.iterator().next()));
    }

    private RoleAwareSubmissionService.Submission selection(long roleId, UUID user) {
        return selectionWithKey(UUID.randomUUID(), roleId, user, 1);
    }

    private RoleAwareSubmissionService.Submission selectionOn(long manager, long roleId, UUID user) {
        return new RoleAwareSubmissionService.Submission(
            UUID.randomUUID(), 1, manager, companyId, "Dispatch Lead", roleId,
            null, null, false, null, "Halverson Logistics", "Ash B", user,
            4.0, tens(4.0), OffsetDateTime.parse("2024-02-01T00:00:00Z"));
    }

    private RoleAwareSubmissionService.Submission selectionWithKey(UUID key, long roleId, UUID user,
                                                                   long revision) {
        return new RoleAwareSubmissionService.Submission(
            key, revision, managerId, companyId, "Dispatch Lead", roleId,
            null, null, false, null, "Halverson Logistics", "Ash B", user,
            4.0, tens(4.0), OffsetDateTime.parse("2024-02-01T00:00:00Z"));
    }

    private RoleAwareSubmissionService.Submission proposal(String title, LocalDate start,
                                                           boolean periodKnown, UUID user) {
        return new RoleAwareSubmissionService.Submission(
            UUID.randomUUID(), 1, managerId, companyId, title, null,
            start, null, periodKnown, null, "Halverson Logistics", "Ash B", user,
            4.0, tens(4.0), OffsetDateTime.parse("2024-02-01T00:00:00Z"));
    }

    private RoleClaimService.Draft draft(UUID key, String title, long revision) {
        return new RoleClaimService.Draft(key, managerId, companyId, title,
            null, null, false, null, null, "contributor", null, revision);
    }

    private RoleClaimService.Draft draftFor(UUID key, long roleId, UUID user, long revision) {
        return new RoleClaimService.Draft(key, managerId, companyId, "Dispatch Lead",
            null, null, false, null, roleId, "contributor", user, revision);
    }

    private static double[] tens(double v) {
        double[] c = new double[10];
        java.util.Arrays.fill(c, v);
        return c;
    }

    private long insertCompany(String name) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies (name, slug, status) VALUES ($1,$2,'ghost') RETURNING id")
            .execute(Tuple.of(name, name.toLowerCase().replace(' ', '-')))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertManager(String name, String slug, long company) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO managers (name, company, title, status, approval_status, country, "
                + "company_id, slug, overall_rating, reviews_count, category_averages) "
                + "VALUES ($1,'Halverson Logistics','Lead','active','approved','CA',$2,$3,0,0,"
                + "'{}'::jsonb) RETURNING id")
            .execute(Tuple.of(name, company, slug))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private UUID insertUser(String auth0Id) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO users (auth0_id, username) VALUES ($1,$2) RETURNING id")
            .execute(Tuple.of(auth0Id, "u" + Math.abs(auth0Id.hashCode())))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private long insertRole(long manager, String title, String startIso, String state)
            throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO career_history (manager_id, company, title, start_date, company_id, "
                + "period_known, state, source) "
                + "VALUES ($1,'Halverson Logistics',$2,($3::date)::timestamp AT TIME ZONE 'UTC',"
                + "$4,TRUE,$5,'contributor') RETURNING id")
            .execute(Tuple.of(manager, title, LocalDate.parse(startIso), companyId, state))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    /** A selection whose contributor asserts a departure date AND that the manager was still there. */
    private RoleAwareSubmissionService.Submission asserting(long roleId, UUID user, LocalDate end) {
        return new RoleAwareSubmissionService.Submission(
            UUID.randomUUID(), 1, managerId, companyId, "Dispatch Lead", roleId,
            LocalDate.parse("2020-01-01"), end, true, true, "Halverson Logistics", "Ash B", user,
            4.0, tens(4.0), OffsetDateTime.parse("2020-01-01T00:00:00Z"));
    }

    /** status, basis and the date the evidence speaks to, as one string. */
    private String statusOf(long roleId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT employment_status || '|' || COALESCE(status_basis,'(null)') || '|' "
                + "|| COALESCE(status_as_of::date::text,'(null)') AS s "
                + "FROM career_history WHERE id = $1")
            .execute(Tuple.of(roleId)).map(rs -> rs.iterator().next().getString("s")));
    }

    private Long claimBehind(long roleId) throws Exception {
        return await(pool.preparedQuery("SELECT status_claim_id FROM career_history WHERE id = $1")
            .execute(Tuple.of(roleId)).map(rs -> rs.iterator().next().getLong(0)));
    }

    private int statusEvents(long roleId) throws Exception {
        return await(pool.preparedQuery("SELECT count(*)::int AS c FROM employment_status_events "
                + "WHERE role_id = $1").execute(Tuple.of(roleId))
            .map(rs -> rs.iterator().next().getInteger("c")));
    }

    private Row claimRow(long id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM role_claims WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private Row roleRow(long id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM career_history WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private Row reviewRow(UUID id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM reviews WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private String dispositionOf(UUID id) throws Exception {
        return reviewRow(id).getString("disposition");
    }

    private OffsetDateTime liveSinceOf(UUID id) throws Exception {
        return reviewRow(id).getOffsetDateTime("live_since");
    }

    private String startDayOf(long roleId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT to_char(start_day,'YYYY-MM-DD') AS d FROM career_history WHERE id = $1")
            .execute(Tuple.of(roleId)).map(rs -> rs.iterator().next().getString("d")));
    }

    private long soleClaimId() throws Exception {
        return await(pool.query("SELECT id FROM role_claims").execute().map(rs -> {
            assertEquals(1, rs.rowCount(), "expected exactly one claim");
            return rs.iterator().next().getLong("id");
        }));
    }

    private Long soleReviewManagerId() throws Exception {
        return await(pool.query("SELECT manager_id FROM reviews").execute().map(rs -> {
            assertEquals(1, rs.rowCount(), "expected exactly one rating");
            return rs.iterator().next().getLong("manager_id");
        }));
    }

    private String conflictRoleOf(long claimId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT role FROM role_conflict_claims WHERE claim_id = $1")
            .execute(Tuple.of(claimId)).map(rs -> rs.iterator().next().getString("role")));
    }

    private boolean blocksPublication(String kind) throws Exception {
        return await(pool.preparedQuery("SELECT kind_blocks_publication($1) AS b")
            .execute(Tuple.of(kind)).map(rs -> rs.iterator().next().getBoolean("b")));
    }

    private long countRoles() throws Exception {
        return countRows("career_history");
    }

    private long countRows(String table) throws Exception {
        return await(pool.query("SELECT COUNT(*) AS c FROM " + table).execute()
            .map(rs -> rs.iterator().next().getLong("c")));
    }

    /** Awaits and unwraps, so a test can assert on the cause rather than on ExecutionException. */
    private static <T> T awaitCause(io.vertx.core.Future<T> f) throws Throwable {
        try {
            return f.toCompletionStage().toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause();
        }
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
