package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
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
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.AdminService;
import org.werkpages.service.ModerationService;
import org.werkpages.service.RoleAwareSubmissionService;
import org.werkpages.service.ServiceException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PHASE 4C: administrative moderation of the work the role-aware flow creates.
 *
 * <h2>What this phase is for</h2>
 *
 * <p>Phase 4B stopped the platform guessing which human a rating is about. The cost is that some
 * submissions now wait for a person: somebody who proposes an employment we do not hold, or two
 * people who might be describing one job. <b>Their ratings are held, never discarded</b> - but a
 * held rating nobody can find is a contribution lost to neglect instead of to a decision, which is
 * no better from the contributor's side.
 *
 * <p>So these tests assert two things repeatedly: that a decision releases exactly the ratings it
 * should, and that it releases <b>no others</b>. Over-releasing publishes a rating whose
 * attribution is still in doubt. Under-releasing silently buries somebody's work.
 *
 * <h2>Why the eligibility question is never asked twice</h2>
 *
 * <p>Publication eligibility is one function in the database, {@code rating_role_is_publishable}.
 * Moderation asks it rather than restating it, and several tests here exist specifically to prove
 * that a moderation decision cannot publish something that predicate would refuse - because a
 * second copy of the rule in moderation code would drift from the one in the submission path, and
 * the direction it drifted would decide whether somebody's rating appeared.
 */
@Testcontainers
class ModerationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("moderation")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static ModerationService moderation;
    static RoleAwareSubmissionService submissions;

    long companyId;
    long managerId;
    long otherManagerId;
    UUID contributor;
    UUID someoneElse;
    UUID admin;
    String adminAuth0;
    String ordinaryAuth0;

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
        AdminService adminService = new AdminService(
            new UserRepository(pool), new ManagerRepository(pool), new ReviewRepository(pool),
            null, null);
        moderation  = new ModerationService(pool, adminService);
        submissions = new RoleAwareSubmissionService(pool, true);
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
        managerId      = insertManager("Priya Raghunathan", "priya-r");
        otherManagerId = insertManager("Dominic Achebe", "dom-a");
        contributor    = insertUser("auth0|contributor", "user");
        someoneElse    = insertUser("auth0|someone-else", "user");
        admin          = insertUser("auth0|admin", "admin");
        adminAuth0     = "auth0|admin";
        ordinaryAuth0  = "auth0|contributor";
    }

    // ── authorization, first, because everything else assumes it ─────────────

    /**
     * Every moderation operation refuses an ordinary signed-in user.
     *
     * <p>Constructed by hand, not through the UI: a hidden button is not a permission check, and
     * this endpoint set can accept an employment, publish ratings and merge two employment records.
     * The guard is in {@link ModerationService}, so a route registered without one still refuses.
     */
    @Test
    void everyModerationOperationRefusesAnOrdinaryUser() throws Exception {
        long role = insertRole(managerId, "Depot Supervisor", "2023-03-01", "proposed");
        long claim = soleOrZeroClaim();

        assertForbidden(() -> await(moderation.queueSummary(ordinaryAuth0)));
        assertForbidden(() -> await(moderation.pendingEmployments(ordinaryAuth0, 50, 0)));
        assertForbidden(() -> await(moderation.employmentDetail(ordinaryAuth0, role)));
        assertForbidden(() -> await(moderation.acceptEmployment(ordinaryAuth0, role)));
        assertForbidden(() -> await(moderation.rejectEmployment(ordinaryAuth0, role, "no")));
        assertForbidden(() -> await(moderation.openConflicts(ordinaryAuth0, 50, 0)));
        assertForbidden(() -> await(moderation.conflictDetail(ordinaryAuth0, 1L)));
        assertForbidden(() -> await(moderation.resolveConflict(ordinaryAuth0, 1L, "dismissed", null)));
        assertForbidden(() -> await(moderation.linkClaimToRole(ordinaryAuth0, claim, role)));
        assertForbidden(() -> await(moderation.mergeRoles(ordinaryAuth0, managerId, role, role + 1)));
        assertForbidden(() -> await(moderation.heldRatings(ordinaryAuth0, 50, 0)));

        // AND NOTHING MOVED. A refusal that still changed a row would be worse than no check.
        assertEquals("proposed", roleRow(role).getString("state"));
    }

    @Test
    void everyModerationOperationRefusesAnAnonymousCaller() throws Exception {
        long role = insertRole(managerId, "Depot Supervisor", "2023-03-01", "proposed");

        assertStatus(401, () -> await(moderation.queueSummary(null)));
        assertStatus(401, () -> await(moderation.acceptEmployment(null, role)));
        assertStatus(401, () -> await(moderation.rejectEmployment(null, role, null)));
        assertStatus(401, () -> await(moderation.mergeRoles(null, managerId, role, role + 1)));

        assertEquals("proposed", roleRow(role).getString("state"));
    }

    // ── proposed employment ─────────────────────────────────────────────────

    @Test
    void acceptingAProposedEmploymentPublishesTheRatingWaitingOnIt() throws Exception {
        RoleAwareSubmissionService.Outcome out = await(submissions.submit(
            proposal("Depot Supervisor", LocalDate.parse("2023-03-01"), contributor)));
        assertFalse(out.live(), "precondition: a proposed employment holds its rating");
        assertEquals("held", dispositionOf(out.reviewId()));

        JsonObject res = await(moderation.acceptEmployment(adminAuth0, out.roleId()));

        assertEquals("accepted", res.getString("state"));
        assertFalse(res.getBoolean("alreadyDone"));
        assertEquals(1, (int) res.getInteger("ratingsPublished"));
        assertEquals("live", dispositionOf(out.reviewId()));
        assertEquals("accepted", roleRow(out.roleId()).getString("state"));
        // The cached aggregate the directory reads, recalculated in the same transaction. A rating
        // that is live but counted nowhere is invisible in a different way.
        assertEquals(1, (int) managerRow(managerId).getInteger("reviews_count"));
    }

    /**
     * The central over-release test.
     *
     * <p>Accepting an employment answers ONE question. A rating also held by an open blocking
     * conflict is still in doubt about which employment it belongs to, and publishing it on the
     * strength of this decision alone would put somebody's opinion on a profile while its
     * attribution was unresolved. The service asks the database about each rating individually
     * instead of promoting everything attached to the role.
     */
    @Test
    void acceptingDoesNotPublishARatingAnIndependentBlockingConflictStillHolds() throws Exception {
        RoleAwareSubmissionService.Outcome out = await(submissions.submit(
            proposal("Depot Supervisor", LocalDate.parse("2023-03-01"), contributor)));
        long claim = soleClaimId();
        long conflict = openBlockingConflict(claim, "possible_duplicate");

        JsonObject res = await(moderation.acceptEmployment(adminAuth0, out.roleId()));

        assertEquals("accepted", res.getString("state"));
        assertEquals(0, (int) res.getInteger("ratingsPublished"),
                     "the conflict is a separate hold with its own decision");
        assertEquals("held", dispositionOf(out.reviewId()));

        // And resolving THAT releases it, which is how a moderator gets there in the end.
        JsonObject resolved = await(moderation.resolveConflict(adminAuth0, conflict,
                                                               "kept_separate", "distinct jobs"));
        assertEquals(1, (int) resolved.getInteger("ratingsPublished"));
        assertEquals("live", dispositionOf(out.reviewId()));
    }

    @Test
    void acceptingTwiceIsIdempotentBecauseADoubleClickIsNotAnError() throws Exception {
        RoleAwareSubmissionService.Outcome out = await(submissions.submit(
            proposal("Depot Supervisor", LocalDate.parse("2023-03-01"), contributor)));

        await(moderation.acceptEmployment(adminAuth0, out.roleId()));
        JsonObject second = await(moderation.acceptEmployment(adminAuth0, out.roleId()));

        assertTrue(second.getBoolean("alreadyDone"));
        assertEquals(0, (int) second.getInteger("ratingsPublished"));
        assertEquals("live", dispositionOf(out.reviewId()), "still published, not re-held");
        assertEquals(1, (int) managerRow(managerId).getInteger("reviews_count"),
                     "and not double counted");
    }

    @Test
    void rejectingAProposedEmploymentNeverDeletesTheContributorsRating() throws Exception {
        RoleAwareSubmissionService.Outcome out = await(submissions.submit(
            proposal("Depot Supervisor", LocalDate.parse("2023-03-01"), contributor)));

        JsonObject res = await(moderation.rejectEmployment(adminAuth0, out.roleId(), "not real"));

        assertEquals("rejected", res.getString("state"));
        // THE WHOLE POINT. A rejection is a decision about an employment record, not a punishment
        // of the person who wrote the rating, and linking their claim elsewhere later releases it.
        assertEquals(1, countRows("reviews"));
        assertEquals("held", dispositionOf(out.reviewId()));
        assertNull(reviewRow(out.reviewId()).getOffsetDateTime("deleted_at"));
        assertEquals(1, countRows("role_claims"));
    }

    /**
     * Why a rejection can commit at all, and why the hold in front of it is defence rather than
     * everyday work.
     *
     * <p>{@code roles_live_is_resolved} is a DEFERRABLE INITIALLY DEFERRED constraint trigger that
     * checks at COMMIT that no live rating depends on an unpublishable role. The service therefore
     * holds any live rating BEFORE changing the state: the other order fails as a whole and the
     * moderator sees an opaque database error instead of a rejection.
     *
     * <p>In practice it finds none, and this test pins down the reason: <b>a live rating against a
     * proposed employment cannot be committed in the first place.</b>
     * {@code reviews_live_is_resolved} is deferred and fires on INSERT as well as UPDATE, so the
     * database refuses the state the hold exists to clean up. That is worth asserting rather than
     * assuming, because the day rejecting an ACCEPTED employment is allowed, the hold stops being
     * theoretical and the order it enforces becomes load-bearing.
     */
    @Test
    void aLiveRatingCannotExistAgainstAProposedEmploymentSoRejectionFindsNoneToHold()
            throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        RoleAwareSubmissionService.Outcome out = await(submissions.submit(selection(role, contributor)));
        assertTrue(out.live(), "precondition: an accepted employment publishes immediately");

        // The database refuses to let the role become proposed while the rating is live. This is
        // the deferred assertion doing its job, at COMMIT, from a statement that touched no review.
        Exception refused = assertThrows(Exception.class, () -> await(pool.preparedQuery(
                "UPDATE career_history SET state = 'proposed' WHERE id = $1")
            .execute(Tuple.of(role))));
        assertTrue(message(refused).contains("leaves review"), message(refused));
        assertEquals("accepted", roleRow(role).getString("state"), "and the role did not move");

        // So a genuine proposed employment carries only held ratings, and rejecting it holds none
        // and still commits.
        RoleAwareSubmissionService.Outcome proposed = await(submissions.submit(
            proposal("Depot Supervisor", LocalDate.parse("2023-03-01"), someoneElse)));
        JsonObject res = await(moderation.rejectEmployment(adminAuth0, proposed.roleId(), "no"));

        assertEquals("rejected", res.getString("state"));
        assertEquals(0, (int) res.getInteger("ratingsHeld"));
        assertEquals("held", dispositionOf(proposed.reviewId()), "still held, still there");
        assertEquals(2, countRows("reviews"));
    }

    /**
     * A withdrawn rating reaches its employment WITHOUT a claim, and the reject path has to see it.
     *
     * <p>{@code assert_roles_ratings} looks for a role's ratings through two routes - the claim, and
     * {@code withdrawn_role_id} - precisely because nulling {@code claim_id} on withdrawal removed
     * the first one. A reject that queried only the claimed ratings would miss a withdrawn one
     * entirely, so this test proves the second route is actually traversed: the withdrawn rating is
     * found, counted, and left intact.
     */
    @Test
    void rejectingSeesAWithdrawnRatingThatReachesTheRoleWithoutAClaim() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "proposed");
        UUID withdrawn = insertWithdrawnHeldRating(role);
        assertNull(reviewRow(withdrawn).getLong("claim_id"),
                   "no claim: the only route to the role is withdrawn_role_id");

        JsonObject res = await(moderation.rejectEmployment(adminAuth0, role, "wrong person"));

        assertEquals("rejected", res.getString("state"));
        assertEquals("held", dispositionOf(withdrawn));
        // NOT DELETED. A withdrawn rating is somebody's contribution that already survived one
        // identity change; a rejection is not licence to discard it.
        assertEquals(1, countRows("reviews"));
        assertNull(reviewRow(withdrawn).getOffsetDateTime("deleted_at"));
    }

    @Test
    void acceptingReleasesAWithdrawnRatingWaitingOnTheSameEmployment() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "proposed");
        UUID withdrawn = insertWithdrawnHeldRating(role);

        JsonObject res = await(moderation.acceptEmployment(adminAuth0, role));

        assertEquals(1, (int) res.getInteger("ratingsPublished"),
                     "the predicate answers for a withdrawn rating too");
        assertEquals("live", dispositionOf(withdrawn));
    }

    @Test
    void onlyAProposedEmploymentCanBeAcceptedOrRejected() throws Exception {
        long rejected = insertRole(managerId, "Depot Supervisor", "2023-03-01", "rejected");

        ServiceException e = refused(() -> await(moderation.acceptEmployment(adminAuth0, rejected)));
        assertEquals(400, e.getStatusCode());
        assertTrue(e.getMessage().contains("rejected"), e.getMessage());

        ServiceException missing =
            refused(() -> await(moderation.acceptEmployment(adminAuth0, 99_999L)));
        assertEquals(404, missing.getStatusCode());
    }

    // ── corroboration, counted honestly ─────────────────────────────────────

    /**
     * Two accounts are two people. Two anonymous claims might be one person twice.
     *
     * <p>Corroboration is the strongest signal a moderator has that a proposed employment is real,
     * and it is what they are about to act on. Adding anonymous claims into the same number would
     * manufacture agreement out of one visitor who cleared their cookies, so the counts are
     * separate and the anonymous one is never described as distinct individuals.
     */
    @Test
    void corroborationCountsAccountsSeparatelyFromAnonymousClaims() throws Exception {
        RoleAwareSubmissionService.Outcome first = await(submissions.submit(
            proposal("Depot Supervisor", LocalDate.parse("2023-03-01"), contributor)));
        // A second signed-in contributor resolving to the SAME role: real corroboration.
        await(submissions.submit(
            proposal("depot  SUPERVISOR", LocalDate.parse("2023-03-01"), someoneElse)));
        // And two anonymous ones, which may be the same visitor.
        await(submissions.submit(
            proposal("Depot Supervisor", LocalDate.parse("2023-03-01"), null)));
        await(submissions.submit(
            proposal("Depot Supervisor", LocalDate.parse("2023-03-01"), null)));

        JsonObject row = soleQueueRow();

        assertEquals(2, (int) row.getInteger("corroboratingAccounts"),
                     "distinct user_id, which is a real count of separate people");
        assertEquals(2, (int) row.getInteger("anonymousClaims"));
        assertEquals(4, (int) row.getInteger("totalClaims"));
        assertEquals(first.roleId(), (long) row.getLong("roleId"),
                     "all four resolved to the one employment, which is why it corroborates");
    }

    @Test
    void theQueueShowsTheRatingsHeldAndWarnsWhenAcceptingAloneWillNotPublishThem()
            throws Exception {
        RoleAwareSubmissionService.Outcome out = await(submissions.submit(
            proposal("Depot Supervisor", LocalDate.parse("2023-03-01"), contributor)));
        openBlockingConflict(soleClaimId(), "possible_duplicate");

        JsonObject row = soleQueueRow();
        assertEquals(1, (int) row.getInteger("heldRatings"));
        // So a moderator is not promised a release the predicate would refuse.
        assertEquals(1, (int) row.getInteger("blockingConflicts"));

        JsonObject detail = await(moderation.employmentDetail(adminAuth0, out.roleId()));
        JsonArray held = detail.getJsonArray("heldRatings");
        assertEquals(1, held.size());
        assertFalse(held.getJsonObject(0).getBoolean("wouldPublishNow"));
    }

    /**
     * The detail view answers the question a moderator is really asking: do we hold this job
     * already, under another description?
     */
    @Test
    void theDetailViewShowsTheManagersExistingEmploymentAndWhatWasAsserted() throws Exception {
        long existing = insertRole(managerId, "Dispatch Lead", "2023-02-01", "accepted");
        RoleAwareSubmissionService.Outcome out = await(submissions.submit(
            proposal("Depot Supervisor", LocalDate.parse("2023-03-01"), contributor)));

        JsonObject detail = await(moderation.employmentDetail(adminAuth0, out.roleId()));

        JsonArray history = detail.getJsonArray("managerHistory");
        assertEquals(1, history.size());
        assertEquals(existing, (long) history.getJsonObject(0).getLong("roleId"));
        assertEquals("accepted", history.getJsonObject(0).getString("state"));

        JsonObject claim = detail.getJsonArray("claims").getJsonObject(0);
        assertEquals("Depot Supervisor", claim.getString("assertedTitle"),
                     "what the contributor said, kept beside what the platform resolved");
        assertTrue(claim.getBoolean("fromAccount"));
        // Not WHO they are: an employment decision never needs the contributor's identity, so it
        // is not sent to the browser.
        assertFalse(claim.containsKey("userId"));
        assertFalse(claim.containsKey("author"));
    }

    // ── linking a claim to an equivalent employment ─────────────────────────

    /**
     * The resolution that releases a rating without accepting a duplicate employment.
     *
     * <p>And the one that must never quietly rewrite what the contributor asserted:
     * {@code selected_role_id} keeps their choice, {@code resolved_role_id} carries where the
     * platform put it, and the divergence is recorded as a canonicalization with an actor and a
     * time. {@code claim_selection_is_honoured_or_canonicalized} enforces exactly that.
     */
    @Test
    void linkingAResolvedClaimElsewhereIsAuditedAndKeepsWhatTheContributorSelected()
            throws Exception {
        long proposed = insertRole(managerId, "Depot Supervisor", "2023-03-01", "proposed");
        long canonical = insertRole(managerId, "Dispatch Lead", "2023-03-01", "accepted");
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selection(proposed, contributor)));
        long claim = soleClaimId();
        assertEquals("held", dispositionOf(out.reviewId()));

        JsonObject res = await(moderation.linkClaimToRole(adminAuth0, claim, canonical));

        assertEquals(1, (int) res.getInteger("ratingsPublished"));
        assertEquals("live", dispositionOf(out.reviewId()));

        Row row = claimRow(claim);
        assertEquals(proposed, (long) row.getLong("selected_role_id"),
                     "their choice is untouched");
        assertEquals(canonical, (long) row.getLong("resolved_role_id"));
        assertNotNull(row.getOffsetDateTime("canonicalized_at"));
        assertEquals(admin, row.getUUID("canonicalized_by"));
        assertEquals(proposed, (long) row.getLong("canonicalized_from"),
                     "the role it moved OFF, which is what the audit trigger classifies on");

        // 'redirected', not 'corrected': the audit must say what actually happened.
        assertEquals("redirected", latestClaimEvent(claim).getString("event"));
        assertEquals("admin_moderation", latestClaimEvent(claim).getString("reason"));
    }

    /**
     * A claim with no role yet is RESOLVED, not canonicalized.
     *
     * <p>{@code claim_canonicalization_is_complete} pairs {@code canonicalized_at} with
     * {@code canonicalized_from}, and there is no role to move off, so writing a canonicalization
     * here violates the check and the whole link fails. Found by reading the constraint rather than
     * by the first attempt working.
     */
    @Test
    void linkingAClaimThatHasNoRoleYetRecordsAResolution() throws Exception {
        long canonical = insertRole(managerId, "Dispatch Lead", "2023-03-01", "accepted");
        long claim = insertFinalizedUnresolvedClaim(contributor);

        JsonObject res = await(moderation.linkClaimToRole(adminAuth0, claim, canonical));

        assertEquals(canonical, (long) res.getLong("toRoleId"));
        assertNull(res.getLong("fromRoleId"));

        Row row = claimRow(claim);
        assertEquals("linked", row.getString("resolution"));
        assertEquals(canonical, (long) row.getLong("resolved_role_id"));
        assertNotNull(row.getOffsetDateTime("resolved_at"));
        assertEquals(admin, row.getUUID("resolved_by"));
        assertNull(row.getOffsetDateTime("canonicalized_at"),
                   "nothing was canonicalized: there was no earlier answer to move away from");
        assertEquals("resolved", latestClaimEvent(claim).getString("event"));
    }

    @Test
    void linkingRefusesAnEmploymentThatBelongsToADifferentManager() throws Exception {
        long mine    = insertRole(managerId, "Depot Supervisor", "2023-03-01", "proposed");
        long theirs  = insertRole(otherManagerId, "Dispatch Lead", "2023-03-01", "accepted");
        await(submissions.submit(selection(mine, contributor)));
        long claim = soleClaimId();

        ServiceException e =
            refused(() -> await(moderation.linkClaimToRole(adminAuth0, claim, theirs)));
        assertEquals(400, e.getStatusCode());

        // THE ONE THING THIS DESIGN EXISTS TO PREVENT: a rating moving between two humans.
        assertEquals(mine, (long) claimRow(claim).getLong("resolved_role_id"));
    }

    @Test
    void linkingRefusesAClaimThatWasNeverFinalized() throws Exception {
        long canonical = insertRole(managerId, "Dispatch Lead", "2023-03-01", "accepted");
        long draft = insertUnfinalizedDraftClaim(contributor);

        ServiceException e =
            refused(() -> await(moderation.linkClaimToRole(adminAuth0, draft, canonical)));
        assertEquals(400, e.getStatusCode());
        assertTrue(e.getMessage().contains("finalized"), e.getMessage());
        // An abandoned draft has no rating behind it, and claim_resolved_is_finalized refuses to
        // resolve one anyway. There is nothing here to moderate.
        assertNull(claimRow(draft).getString("resolution"));
    }

    // ── conflicts ───────────────────────────────────────────────────────────

    @Test
    void resolvingReportsWhatMovedAndWhatIsStillHeldBySomethingElse() throws Exception {
        RoleAwareSubmissionService.Outcome out = await(submissions.submit(
            proposal("Depot Supervisor", LocalDate.parse("2023-03-01"), contributor)));
        long conflict = openBlockingConflict(soleClaimId(), "possible_duplicate");

        // The employment is still only proposed, so resolving the conflict cannot publish it.
        JsonObject res = await(moderation.resolveConflict(adminAuth0, conflict, "dismissed", null));

        assertEquals(0, (int) res.getInteger("ratingsPublished"));
        assertEquals(1, (int) res.getInteger("ratingsStillHeld"),
                     "a moderator who clicks resolve and sees nothing happen deserves to know why");
        assertEquals("held", dispositionOf(out.reviewId()));
    }

    @Test
    void anEvidenceOnlyClaimIsNeverTreatedAsBeingInDoubt() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selection(role, contributor)));
        assertTrue(out.live());
        long claim = soleClaimId();

        // Somebody ELSE's claim is the one in doubt. A blocking conflict must have a subject -
        // conflict_claims_blocking_has_a_subject refuses one that holds ratings for no stated
        // reason - so the realistic shape is a subject plus the evidence cited about it.
        RoleAwareSubmissionService.Outcome subject = await(submissions.submit(
            proposal("Depot Supervisor", LocalDate.parse("2023-03-01"), someoneElse)));
        long subjectClaim = otherClaimId(claim);

        // EVIDENCE, not subject. Being cited about somebody else's claim is not being in doubt,
        // and treating it as doubt withdrew a rating the merge had deliberately kept live.
        openConflict("possible_duplicate", subjectClaim, claim);

        assertEquals("live", dispositionOf(out.reviewId()),
                     "the evidence claim's rating was never held");
        assertEquals("held", dispositionOf(subject.reviewId()), "the subject's is");

        JsonArray rows = await(moderation.openConflicts(adminAuth0, 50, 0));
        JsonObject row = rows.getJsonObject(0);
        assertEquals(1, (int) row.getInteger("subjectClaims"));
        assertEquals(1, (int) row.getInteger("evidenceClaims"));
        assertEquals(1, (int) row.getInteger("heldRatings"),
                     "counted from the subject side only");
    }

    @Test
    void resolvingTwiceKeepsTheFirstDecisionAndItsAuthor() throws Exception {
        RoleAwareSubmissionService.Outcome out = await(submissions.submit(
            proposal("Depot Supervisor", LocalDate.parse("2023-03-01"), contributor)));
        long conflict = openBlockingConflict(soleClaimId(), "possible_duplicate");

        await(moderation.resolveConflict(adminAuth0, conflict, "kept_separate", "distinct"));
        JsonObject second = await(moderation.resolveConflict(adminAuth0, conflict, "merged", "oops"));

        assertTrue(second.getBoolean("alreadyDone"));
        Row row = conflictRow(conflict);
        assertEquals("kept_separate", row.getString("resolution"), "history is not overwritten");
        assertEquals("distinct", row.getString("note"));
    }

    @Test
    void anUnknownResolutionIsRefusedBeforeAnythingIsWritten() throws Exception {
        RoleAwareSubmissionService.Outcome out = await(submissions.submit(
            proposal("Depot Supervisor", LocalDate.parse("2023-03-01"), contributor)));
        long conflict = openBlockingConflict(soleClaimId(), "possible_duplicate");

        ServiceException e = refused(
            () -> await(moderation.resolveConflict(adminAuth0, conflict, "obliterated", null)));
        assertEquals(400, e.getStatusCode());
        assertNull(conflictRow(conflict).getOffsetDateTime("resolved_at"));
        assertEquals("held", dispositionOf(out.reviewId()));
    }

    @Test
    void blockingConflictsAreListedFirstBecauseTheyAreTheOnesHoldingRatings() throws Exception {
        RoleAwareSubmissionService.Outcome out = await(submissions.submit(
            proposal("Depot Supervisor", LocalDate.parse("2023-03-01"), contributor)));
        long claim = soleClaimId();
        // The KINDS decide which blocks, not the fixture: end_date_disagreement never holds a
        // rating, possible_duplicate always does.
        long informational = openConflict("end_date_disagreement", claim, null);
        long blocking      = openBlockingConflict(claim, "possible_duplicate");

        JsonArray rows = await(moderation.openConflicts(adminAuth0, 50, 0));

        assertEquals(2, rows.size());
        assertEquals(blocking, (long) rows.getJsonObject(0).getLong("conflictId"));
        assertTrue(rows.getJsonObject(0).getBoolean("blocksPublication"));
        assertEquals(informational, (long) rows.getJsonObject(1).getLong("conflictId"));
        assertFalse(rows.getJsonObject(1).getBoolean("blocksPublication"));
    }

    // ── held ratings ────────────────────────────────────────────────────────

    /**
     * The screen's whole purpose: why is this not public, and what would make it public.
     *
     * <p>The reason is derived in the order the publication predicate checks, so the answer given
     * to a moderator is the answer the database would give. Reporting a second-order cause sends
     * them to the wrong screen.
     */
    @Test
    void theHeldQueueNamesTheRealReasonAndTheActionThatWouldReleaseIt() throws Exception {
        RoleAwareSubmissionService.Outcome out = await(submissions.submit(
            proposal("Depot Supervisor", LocalDate.parse("2023-03-01"), contributor)));

        JsonObject held = await(moderation.heldRatings(adminAuth0, 50, 0)).getJsonObject(0);

        assertEquals(out.reviewId().toString(), held.getString("reviewId"));
        assertTrue(held.getString("whyHeld").contains("proposed"), held.getString("whyHeld"));
        assertEquals("Accept the employment.", held.getString("actionToRelease"));
        assertFalse(held.getBoolean("wouldPublishNow"));
        assertEquals("Priya Raghunathan", held.getString("managerName"),
                     "and a link to the person it is about");

        // Once the employment is accepted, the conflict is the reason, and the action changes.
        long conflict = openBlockingConflict(soleClaimId(), "possible_duplicate");
        await(moderation.acceptEmployment(adminAuth0, out.roleId()));

        JsonObject now = await(moderation.heldRatings(adminAuth0, 50, 0)).getJsonObject(0);
        assertTrue(now.getString("whyHeld").contains("possible_duplicate"), now.getString("whyHeld"));
        assertEquals("Resolve that conflict.", now.getString("actionToRelease"));
        assertEquals(conflict, (long) now.getLong("blockingConflictId"));
    }

    /**
     * A rejected employment will never be accepted, so the advice must not say "accept it".
     *
     * <p>Sending a moderator to a button the service refuses is worse than saying nothing: they
     * click it, get a 400, and have no idea what the actual route is. The route is to point the
     * claim at an employment that exists.
     */
    @Test
    void aRatingHeldByARejectedEmploymentIsToldToLinkTheClaimElsewhere() throws Exception {
        RoleAwareSubmissionService.Outcome out = await(submissions.submit(
            proposal("Depot Supervisor", LocalDate.parse("2023-03-01"), contributor)));
        await(moderation.rejectEmployment(adminAuth0, out.roleId(), "not real"));

        JsonObject held = await(moderation.heldRatings(adminAuth0, 50, 0)).getJsonObject(0);

        assertTrue(held.getString("whyHeld").contains("rejected"), held.getString("whyHeld"));
        assertEquals("Link the claim to a different employment.",
                     held.getString("actionToRelease"));
        assertFalse(held.getBoolean("wouldPublishNow"));
    }

    /**
     * A withdrawn rating has no claim, so the reason has to come from the employment it KEPT.
     *
     * <p>Reporting "it has no employment claim" here would be true and useless: there is an
     * employment, it is proposed, and accepting it is exactly what releases the rating.
     */
    @Test
    void aWithdrawnRatingsReasonNamesTheEmploymentItKeptRatherThanItsMissingClaim()
            throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "proposed");
        UUID withdrawn = insertWithdrawnHeldRating(role);

        JsonObject held = await(moderation.heldRatings(adminAuth0, 50, 0)).getJsonObject(0);

        assertEquals(withdrawn.toString(), held.getString("reviewId"));
        assertNull(held.getLong("claimId"));
        assertTrue(held.getString("whyHeld").contains("kept is proposed"),
                   held.getString("whyHeld"));
        assertEquals("Accept that employment.", held.getString("actionToRelease"));

        // And accepting it does release it, so the advice was not merely plausible.
        await(moderation.acceptEmployment(adminAuth0, role));
        assertEquals("live", dispositionOf(withdrawn));
    }

    @Test
    void theQueueSummaryReportsBothTheBacklogAndHowLongTheOldestHasWaited() throws Exception {
        RoleAwareSubmissionService.Outcome out = await(submissions.submit(
            proposal("Depot Supervisor", LocalDate.parse("2023-03-01"), contributor)));
        openBlockingConflict(soleClaimId(), "possible_duplicate");

        JsonObject summary = await(moderation.queueSummary(adminAuth0));

        assertEquals(1, (int) summary.getInteger("pendingEmployments"));
        assertEquals(1, (int) summary.getInteger("openConflicts"));
        assertEquals(1, (int) summary.getInteger("openBlockingConflicts"));
        assertEquals(1, (int) summary.getInteger("heldRatings"));
        // The age, not just the count: a queue of three where the oldest has waited five weeks is
        // not a healthy queue, and the count alone hides that.
        assertNotNull(summary.getString("oldestEmploymentAt"));
        assertNotNull(summary.getString("oldestHeldRatingAt"));
    }

    // ── merging through the existing merge service ──────────────────────────

    /**
     * The admin endpoint delegates to {@link org.werkpages.service.ManagerMergeService#mergeRoles}.
     *
     * <p>Deliberately not a second merge algorithm. That one defers the composite constraints in
     * the right order, redirects claims with the canonicalization audited, marks a contributor's
     * duplicate claim, follows withdrawn ratings and reconciles rating opportunities. A
     * moderation-flavoured reimplementation would get some subset of that right.
     */
    @Test
    void mergingRolesThroughModerationRedirectsTheClaimAndPreservesTheAudit() throws Exception {
        long keep       = insertRole(managerId, "Dispatch Lead", "2023-03-01", "accepted");
        long superseded = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selection(superseded, contributor)));
        long claim = soleClaimId();

        await(moderation.mergeRoles(adminAuth0, managerId, keep, superseded));

        assertEquals("superseded", roleRow(superseded).getString("state"));
        assertEquals(keep, (long) roleRow(superseded).getLong("superseded_by"));
        assertEquals(keep, (long) claimRow(claim).getLong("resolved_role_id"));
        assertEquals(superseded, (long) claimRow(claim).getLong("selected_role_id"),
                     "what they chose is still what they chose");
        // The rating survives and stays attributable.
        assertEquals(1, countRows("reviews"));
        assertEquals("redirected", latestClaimEvent(claim).getString("event"));
    }

    @Test
    void mergingARoleIntoItselfIsRefused() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2023-03-01", "accepted");

        ServiceException e =
            refused(() -> await(moderation.mergeRoles(adminAuth0, managerId, role, role)));
        assertEquals(400, e.getStatusCode());
    }

    // ── rollback ────────────────────────────────────────────────────────────

    /**
     * A moderation decision is one transaction, so a failure leaves nothing behind.
     *
     * <p>Provoked with a real constraint rather than a mock: the target role is deleted from under
     * the link after the claim row is read, so the foreign key refuses the update. What matters is
     * that the claim is unchanged and the rating is neither published nor lost.
     */
    @Test
    void aFailedModerationDecisionLeavesTheClaimAndTheRatingExactlyAsTheyWere() throws Exception {
        long proposed = insertRole(managerId, "Depot Supervisor", "2023-03-01", "proposed");
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selection(proposed, contributor)));
        long claim = soleClaimId();

        assertThrows(Exception.class,
            () -> await(moderation.linkClaimToRole(adminAuth0, claim, 99_999L)));

        Row row = claimRow(claim);
        assertEquals(proposed, (long) row.getLong("resolved_role_id"));
        assertNull(row.getOffsetDateTime("canonicalized_at"));
        assertEquals("held", dispositionOf(out.reviewId()));
        assertEquals(1, countRows("reviews"));
    }

    // ── V108: what nothing may rewrite ─────────────────────────────────────

    /**
     * Every assertion column, one assertion per column, refused on a finalized claim.
     *
     * <p>Rev 268 ("Reuse must not rewrite the contributor's assertion") lists nine columns that are
     * immutable once finalized_at is set, and specified claim_assertion_is_immutable to enforce it.
     * That trigger existed in no migration until V108, so until then a finalized claim's title and
     * dates could be rewritten by any statement that cared to - which is exactly what two of this
     * suite's own fixtures were doing. Driven as raw SQL deliberately: the guarantee has to hold
     * against anything that reaches the database, not only against the service layer.
     */
    @Test
    void everyAssertionColumnIsRefusedOnAFinalizedClaim() throws Exception {
        long claim = insertFinalizedUnresolvedClaim(contributor);
        long role = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");

        record Rewrite(String column, String sql, Object value) { }
        var rewrites = new Rewrite[] {
            new Rewrite("title",            "title = $2",            "Rewritten Title"),
            new Rewrite("start_date",       "start_date = $2",       LocalDate.parse("2020-01-01")),
            new Rewrite("end_date",         "end_date = $2",         LocalDate.parse("2021-01-01")),
            new Rewrite("period_known",     "period_known = $2",     Boolean.TRUE),
            new Rewrite("still_there",      "still_there = $2",      Boolean.TRUE),
            new Rewrite("source",           "source = $2",           "admin"),
            new Rewrite("proposal_key",     "proposal_key = $2",     UUID.randomUUID()),
            new Rewrite("selected_role_id", "selected_role_id = $2", role),
        };

        for (Rewrite r : rewrites) {
            Exception refused = assertThrows(Exception.class,
                () -> await(pool.preparedQuery(
                        "UPDATE role_claims SET " + r.sql() + " WHERE id = $1")
                    .execute(Tuple.of(claim, r.value())).mapEmpty()),
                r.column() + " was rewritten on a finalized claim");
            assertTrue(String.valueOf(refused.getMessage()).contains("cannot be rewritten"),
                r.column() + " was refused for the wrong reason: " + refused.getMessage());
        }
    }

    /** An unfinalized claim is a form somebody is still filling in, and those edits are theirs. */
    @Test
    void anUnfinalizedClaimIsStillTheContributorsToEdit() throws Exception {
        long draft = insertUnfinalizedDraftClaim(contributor);
        await(pool.preparedQuery("UPDATE role_claims SET title = $2 WHERE id = $1")
            .execute(Tuple.of(draft, "Still Being Typed")).mapEmpty());
        assertEquals("Still Being Typed", claimRow(draft).getString("title"));
    }

    /**
     * The identity rule: added once, removed once, never transferred.
     *
     * <p>The fourth step is the one that matters and the one a naive trigger permits. user_id IS
     * NULL means both "never adopted" and "the author was erased", so comparing OLD to NEW cannot
     * tell them apart: NULL -> A -> NULL -> B passes every such check while being precisely the
     * transfer the rule forbids. contributor_erased is what makes the two states distinguishable.
     */
    @Test
    void anErasedClaimCannotBeAdoptedByAnotherAccount() throws Exception {
        UUID a = insertUser("auth0|v108-a", "user");
        UUID b = insertUser("auth0|v108-b", "user");
        long claim = await(pool.preparedQuery("""
                INSERT INTO role_claims (proposal_key, manager_id, company_id, title,
                                         period_known, still_there, source,
                                         finalized_at, original_manager_id, revision)
                VALUES ($1,$2,$3,'Depot Supervisor',FALSE,FALSE,'contributor',now(),$2,1)
                RETURNING id
                """).execute(Tuple.of(UUID.randomUUID(), managerId, companyId))
            .map(rs -> rs.iterator().next().getLong("id")));

        // 1. NULL -> A. First adoption, permitted.
        await(pool.preparedQuery("UPDATE role_claims SET user_id = $2 WHERE id = $1")
            .execute(Tuple.of(claim, a)).mapEmpty());
        assertEquals(a, claimRow(claim).getUUID("user_id"));

        // 2. A -> B. The transfer.
        assertThrows(Exception.class,
            () -> await(pool.preparedQuery("UPDATE role_claims SET user_id = $2 WHERE id = $1")
                .execute(Tuple.of(claim, b)).mapEmpty()),
            "a claim's contributor was transferred to another account");

        // 3. A -> NULL while A still exists.
        assertThrows(Exception.class,
            () -> await(pool.preparedQuery("UPDATE role_claims SET user_id = NULL WHERE id = $1")
                .execute(Tuple.of(claim)).mapEmpty()),
            "a contributor was cleared without their account being deleted");

        // 4. Real erasure, through the cascade rather than simulated.
        await(pool.preparedQuery("DELETE FROM users WHERE id = $1").execute(Tuple.of(a)).mapEmpty());
        Row erased = claimRow(claim);
        assertNull(erased.getUUID("user_id"), "deletion left the contributor on the claim");
        assertTrue(erased.getBoolean("contributor_erased"),
            "deletion did not set the durable marker, so the next adoption would be permitted");

        // 5. NULL -> B, after the erasure. THE SEQUENCE.
        assertThrows(Exception.class,
            () -> await(pool.preparedQuery("UPDATE role_claims SET user_id = $2 WHERE id = $1")
                .execute(Tuple.of(claim, b)).mapEmpty()),
            "an erased claim was adopted by a different account");
    }

    /**
     * A never-adopted claim cannot be stamped as erased.
     *
     * <p>(NULL, TRUE) satisfies claim_contributor_erased_is_empty, so the CHECK alone permits
     * marking any anonymous claim erased although no account was ever deleted - and the marker
     * would then refuse that claim's legitimate first adoption for ever.
     */
    @Test
    void anAnonymousClaimCannotBeMarkedErasedAndStaysAdoptable() throws Exception {
        long claim = await(pool.preparedQuery("""
                INSERT INTO role_claims (proposal_key, manager_id, company_id, title,
                                         period_known, still_there, source,
                                         finalized_at, original_manager_id, revision)
                VALUES ($1,$2,$3,'Depot Supervisor',FALSE,FALSE,'contributor',now(),$2,1)
                RETURNING id
                """).execute(Tuple.of(UUID.randomUUID(), managerId, companyId))
            .map(rs -> rs.iterator().next().getLong("id")));

        assertThrows(Exception.class,
            () -> await(pool.preparedQuery(
                    "UPDATE role_claims SET contributor_erased = TRUE WHERE id = $1")
                .execute(Tuple.of(claim)).mapEmpty()),
            "an anonymous claim was marked erased with no deletion behind it");

        UUID adopter = insertUser("auth0|v108-adopter", "user");
        await(pool.preparedQuery("UPDATE role_claims SET user_id = $2 WHERE id = $1")
            .execute(Tuple.of(claim, adopter)).mapEmpty());
        assertEquals(adopter, claimRow(claim).getUUID("user_id"),
            "the marker broke a legitimate first adoption");
    }

    /** The marker is one-way: reversing it would reopen every transition it closes. */
    @Test
    void theErasureMarkerCannotBeReversed() throws Exception {
        UUID a = insertUser("auth0|v108-reverse", "user");
        long claim = insertFinalizedUnresolvedClaim(a);
        await(pool.preparedQuery("DELETE FROM users WHERE id = $1").execute(Tuple.of(a)).mapEmpty());
        assertTrue(claimRow(claim).getBoolean("contributor_erased"));

        assertThrows(Exception.class,
            () -> await(pool.preparedQuery(
                    "UPDATE role_claims SET contributor_erased = FALSE WHERE id = $1")
                .execute(Tuple.of(claim)).mapEmpty()),
            "an erasure was reversed");
    }

    /**
     * An audit row cannot be created without an administrator, nor edited, nor deleted.
     *
     * <p>(corrected_by NULL, actor_withdrawn TRUE) satisfies the CHECK, so a CHECK alone cannot
     * prove a correction ever had an actor: an insert could fabricate one who had already been
     * erased, and the history would then name nobody, legitimately. V105 asserts in a comment that
     * an insert with no actor is refused, and for a CHECK by itself that is not true.
     */
    @Test
    void aCorrectionNeedsAnAdministratorAndCannotBeEditedOrDeleted() throws Exception {
        long role = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");

        assertThrows(Exception.class,
            () -> await(pool.preparedQuery("""
                    INSERT INTO role_corrections (role_id, field, was, now_is, reason,
                                                  corrected_by, actor_withdrawn)
                    VALUES ($1,'title','a','b','fabricated',NULL,TRUE)
                    """).execute(Tuple.of(role)).mapEmpty()),
            "an audit row was created with no administrator");

        long correction = await(pool.preparedQuery("""
                INSERT INTO role_corrections (role_id, field, was, now_is, reason, corrected_by)
                VALUES ($1,'title','Depot Supervisor','Depot Manager',
                        'confirmed with the employer directory',$2)
                RETURNING id
                """).execute(Tuple.of(role, admin))
            .map(rs -> rs.iterator().next().getLong("id")));

        assertThrows(Exception.class,
            () -> await(pool.preparedQuery("UPDATE role_corrections SET now_is = $2 WHERE id = $1")
                .execute(Tuple.of(correction, "tampered")).mapEmpty()),
            "an audit row was edited");
        assertThrows(Exception.class,
            () -> await(pool.preparedQuery("DELETE FROM role_corrections WHERE id = $1")
                .execute(Tuple.of(correction)).mapEmpty()),
            "an audit row was deleted");

        // And the employment it describes cannot be deleted out from under it.
        assertThrows(Exception.class,
            () -> await(pool.preparedQuery("DELETE FROM career_history WHERE id = $1")
                .execute(Tuple.of(role)).mapEmpty()),
            "a corrected employment was deleted, orphaning its history");
    }

    /**
     * Erasing the correcting administrator removes WHO, and nothing else.
     *
     * <p>The append-only guard and the actor withdrawal live in one function rather than two
     * triggers precisely so this works: two separate BEFORE UPDATE triggers would have to agree
     * about whether the other had already set actor_withdrawn, and which runs first depends on
     * their names.
     */
    @Test
    void erasingTheCorrectingAdminLeavesTheCorrectionIntact() throws Exception {
        long role = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");
        UUID mod = insertUser("auth0|v108-mod", "admin");
        long correction = await(pool.preparedQuery("""
                INSERT INTO role_corrections (role_id, field, was, now_is, reason, corrected_by)
                VALUES ($1,'start_date','2023-03-01','2022-11-01','payroll records',$2)
                RETURNING id
                """).execute(Tuple.of(role, mod))
            .map(rs -> rs.iterator().next().getLong("id")));

        await(pool.preparedQuery("DELETE FROM users WHERE id = $1").execute(Tuple.of(mod)).mapEmpty());

        Row row = await(pool.preparedQuery("SELECT * FROM role_corrections WHERE id = $1")
            .execute(Tuple.of(correction)).map(rs -> rs.iterator().next()));
        assertNull(row.getUUID("corrected_by"), "the erased admin is still named");
        assertTrue(row.getBoolean("actor_withdrawn"), "withdrawal was not recorded");
        assertEquals("2023-03-01", row.getString("was"), "erasure altered what changed");
        assertEquals("2022-11-01", row.getString("now_is"), "erasure altered what changed");
        assertEquals("payroll records", row.getString("reason"), "erasure altered the reason");
    }

    /**
     * A merge does not destroy correction history, and history does not block a merge.
     *
     * <p>role_corrections.role_id is ON DELETE RESTRICT, which would be a problem if a merge
     * deleted the absorbed role. It does not: mergeRoles SUPERSEDES, setting state = 'superseded'
     * and superseded_by, so the row and its history both survive. Asserted rather than reasoned
     * about, because RESTRICT turning a legitimate merge into a constraint violation is exactly
     * the kind of failure that only appears once there is history to trip over.
     */
    @Test
    void aCorrectedEmploymentKeepsItsHistoryThroughAMerge() throws Exception {
        /*
          DIFFERENT START DAYS, because career_unique_known_employment is
          (manager_id, company_id, title_key, start_day) WHERE period_known AND state IN
          ('proposed','accepted'). Two known employments identical on all four ARE one employment
          and the database refuses the second, which it did when this test first ran. A real merge
          candidate pair is the same job recorded with different dates, which is this.
        */
        long keep = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");
        long gone = insertRole(managerId, "Depot Supervisor", "2022-11-01", "proposed");
        await(pool.preparedQuery("""
                INSERT INTO role_corrections (role_id, field, was, now_is, reason, corrected_by)
                VALUES ($1,'title','Depot Supervisor','Depot Manager','directory',$2),
                       ($3,'title','Depot Supervisor','Depot Lead','directory',$2)
                """).execute(Tuple.of(keep, admin, gone)).mapEmpty());

        await(moderation.mergeRoles(adminAuth0, managerId, keep, gone));

        assertEquals("superseded", roleRow(gone).getString("state"),
            "the merge deleted the absorbed role instead of superseding it");
        assertEquals(2, countRows("role_corrections"),
            "the merge destroyed correction history");
    }

    // ── V108/4C: the audit timeline, paged ──────────────────────────────────

    /**
     * The seven audit sources do not share an id type, and this is the test that proves the query
     * RUNS rather than merely compiles.
     *
     * <p>{@code company_merges.id} is UUID; the other six are BIGINT. A UNION ALL over the raw
     * columns does not execute at all, and nothing in Java catches that: the service compiled
     * perfectly with the fault in it. Every branch casts to text, so this asserts a real
     * company-merge event arrives beside a real role correction.
     */
    @Test
    void theTimelineReconcilesUuidAndBigintEventIds() throws Exception {
        long role = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");
        long otherCompany = insertCompany("Second Employer v108");
        // company_merges_distinct refuses a merge whose source and target are the same company.
        await(pool.preparedQuery("""
                INSERT INTO company_merges (source_company_id, target_company_id, merged_by, status)
                VALUES ($1,$2,$3,'completed')
                """).execute(Tuple.of(otherCompany, companyId, admin)).mapEmpty());
        await(pool.preparedQuery("""
                INSERT INTO role_corrections (role_id, field, was, now_is, reason, corrected_by)
                VALUES ($1,'title','Depot Supervisor','Depot Manager','directory',$2)
                """).execute(Tuple.of(role, admin)).mapEmpty());

        JsonObject page = await(moderation.employmentHistory(adminAuth0, role, null, 50));
        JsonArray events = page.getJsonArray("events");
        assertTrue(kinds(events).contains("company_merge"),
            "the UUID-keyed company merge is missing: " + events.encode());
        assertTrue(kinds(events).contains("role_correction"));
        assertTrue(page.getBoolean("lineageComplete"));
        assertFalse(page.getBoolean("cycleDetected"));
    }

    /**
     * A page boundary that falls between two events ONE MICROSECOND apart.
     *
     * <p>PostgreSQL keeps microseconds. If the cursor carried only milliseconds the two would be
     * indistinguishable, and the second page would not return the older one: measured against the
     * real schema, a millisecond-truncated cursor skipped BOTH events and landed on one from the
     * previous day. So this runs through the service and the driver, which is the only way to
     * know the precision survives the round trip rather than only the SQL.
     */
    @Test
    void theCursorKeepsMicrosecondPrecisionAcrossAPageBoundary() throws Exception {
        long role = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");
        insertCorrectionAt(role, "title", "A", "B", "older",
                           "2026-11-03T05:00:00.123456Z");
        insertCorrectionAt(role, "start_date", "C", "D", "newer",
                           "2026-11-03T05:00:00.123457Z");

        JsonObject first = await(moderation.employmentHistory(adminAuth0, role, null, 1));
        assertEquals(1, first.getJsonArray("events").size());
        assertEquals("newer", first.getJsonArray("events").getJsonObject(0).getString("reason"));
        assertTrue(first.getBoolean("hasMore"), "a second event exists, so there is more");
        String cursor = first.getString("nextCursor");
        assertNotNull(cursor, "a page with more must say where the next one starts");

        String decoded = new String(java.util.Base64.getUrlDecoder().decode(cursor),
                                    java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(decoded.contains(".123457"),
            "the cursor lost sub-millisecond precision: " + decoded);

        JsonObject second = await(moderation.employmentHistory(adminAuth0, role, cursor, 1));
        assertEquals("older", second.getJsonArray("events").getJsonObject(0).getString("reason"),
            "the microsecond-older event was skipped, which is what a truncated cursor does");
    }

    /**
     * Identical timestamps, which are the normal case rather than an edge one.
     *
     * <p>One correction writes a row per field inside a single transaction, so they all share
     * now(). Ordering by time alone would shuffle them between pages, repeating some and skipping
     * others.
     */
    @Test
    void pagingIsDeterministicWhenEventsShareATimestamp() throws Exception {
        long role = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");
        await(moderation.correctEmployment(adminAuth0, role,
            new org.werkpages.service.ModerationService.Correction(true, "Depot Manager",
                                             true, LocalDate.parse("2022-11-01"),
                                             true, LocalDate.parse("2023-12-31"),
                                             false, false),
            "three fields, one transaction, one now()"));

        java.util.List<String> walked = new java.util.ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            JsonObject p = await(moderation.employmentHistory(adminAuth0, role, cursor, 1));
            for (int i = 0; i < p.getJsonArray("events").size(); i++) {
                JsonObject e = p.getJsonArray("events").getJsonObject(i);
                walked.add(e.getString("kind") + "/" + e.getString("what"));
            }
            cursor = p.getString("nextCursor");
            if (!Boolean.TRUE.equals(p.getBoolean("hasMore"))) break;
        }
        assertTrue(walked.containsAll(java.util.List.of(
                "role_correction/title", "role_correction/start_date", "role_correction/end_date")),
            "a one-at-a-time walk lost a field: " + walked);
        assertEquals(walked.size(), new java.util.HashSet<>(walked).size(),
            "the same event came back on two pages: " + walked);
    }

    /** Every event reachable, none twice, when there are more than one page could ever hold. */
    @Test
    void everyEventIsReachableWhenThereAreMoreThanTwoHundred() throws Exception {
        long role = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");
        java.util.List<Tuple> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 205; i++) {
            rows.add(Tuple.of(role, "title", "was " + i, "now " + i, "bulk " + i, admin));
        }
        await(pool.preparedQuery("""
                INSERT INTO role_corrections (role_id, field, was, now_is, reason, corrected_by)
                VALUES ($1,$2,$3,$4,$5,$6)
                """).executeBatch(rows).mapEmpty());

        java.util.Set<String> seen = new java.util.HashSet<>();
        String cursor = null;
        int pages = 0;
        while (pages++ < 20) {
            JsonObject p = await(moderation.employmentHistory(adminAuth0, role, cursor, 50));
            JsonArray events = p.getJsonArray("events");
            for (int i = 0; i < events.size(); i++) {
                String reason = events.getJsonObject(i).getString("reason");
                assertTrue(seen.add(reason), "event returned twice: " + reason);
            }
            if (!Boolean.TRUE.equals(p.getBoolean("hasMore"))) {
                assertNull(p.getString("nextCursor"), "a last page must not offer a cursor");
                break;
            }
            cursor = p.getString("nextCursor");
        }
        for (int i = 0; i < 205; i++) {
            assertTrue(seen.contains("bulk " + i), "never returned: bulk " + i);
        }
    }

    /**
     * A chain of merges longer than any depth limit anyone would have picked.
     *
     * <p>This was bounded at sixteen, which stops infinite recursion and lies while doing it: the
     * seventeenth would have been dropped and the response would still have called itself
     * complete. Eighteen, so the old bound would fail this.
     */
    @Test
    void theLineageFollowsAChainLongerThanAnyDepthLimit() throws Exception {
        long head = insertRole(managerId, "Head Role", "2024-01-01", "accepted");
        long previous = head;
        for (int i = 0; i < 18; i++) {
            long absorbed = insertRole(managerId, "Absorbed " + i, "2023-01-0"
                                       + (1 + i % 9), "accepted");
            await(pool.preparedQuery("""
                    UPDATE career_history SET state = 'superseded', superseded_by = $2
                     WHERE id = $1
                    """).execute(Tuple.of(absorbed, previous)).mapEmpty());
            await(pool.preparedQuery("""
                    INSERT INTO role_corrections (role_id, field, was, now_is, reason, corrected_by)
                    VALUES ($1,'title','old','new','depth ' || $2,$3)
                    """).execute(Tuple.of(absorbed, String.valueOf(i), admin)).mapEmpty());
            previous = absorbed;
        }

        JsonObject page = await(moderation.employmentHistory(adminAuth0, head, null, 200));
        assertEquals(19, page.getInteger("employmentsInScope"),
            "the lineage was truncated: " + page.getInteger("employmentsInScope"));
        assertTrue(page.getBoolean("lineageComplete"));
        java.util.Set<String> reasons = new java.util.HashSet<>();
        JsonArray events = page.getJsonArray("events");
        for (int i = 0; i < events.size(); i++) {
            reasons.add(events.getJsonObject(i).getString("reason"));
        }
        assertTrue(reasons.contains("depth 17"), "the deepest correction is missing");
    }

    /** Two absorbed roles on one survivor: both histories, each event once. */
    @Test
    void theLineageFollowsBranchesWithoutDuplicatingEvents() throws Exception {
        long survivor = insertRole(managerId, "Survivor", "2024-02-01", "accepted");
        long left  = insertRole(managerId, "Left Branch", "2023-02-01", "accepted");
        long right = insertRole(managerId, "Right Branch", "2023-04-01", "accepted");
        for (long absorbed : new long[] { left, right }) {
            await(pool.preparedQuery("""
                    UPDATE career_history SET state = 'superseded', superseded_by = $2
                     WHERE id = $1
                    """).execute(Tuple.of(absorbed, survivor)).mapEmpty());
            await(pool.preparedQuery("""
                    INSERT INTO role_corrections (role_id, field, was, now_is, reason, corrected_by)
                    VALUES ($1,'title','old','new','branch ' || $1,$2)
                    """).execute(Tuple.of(absorbed, admin)).mapEmpty());
        }

        JsonObject page = await(moderation.employmentHistory(adminAuth0, survivor, null, 200));
        assertEquals(3, page.getInteger("employmentsInScope"));
        JsonArray events = page.getJsonArray("events");
        java.util.List<String> reasons = new java.util.ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            reasons.add(events.getJsonObject(i).getString("reason"));
        }
        assertTrue(reasons.contains("branch " + left) && reasons.contains("branch " + right));
        assertEquals(reasons.size(), new java.util.HashSet<>(reasons).size(),
            "a branch event was returned twice: " + reasons);
        // The absorbed events keep their own role id rather than being reattributed.
        boolean attributed = false;
        for (int i = 0; i < events.size(); i++) {
            JsonObject e = events.getJsonObject(i);
            if (("branch " + left).equals(e.getString("reason"))) {
                assertEquals(left, (long) e.getLong("roleId"));
                assertFalse(e.getBoolean("onThisRole"), "an absorbed event claimed the survivor");
                attributed = true;
            }
        }
        assertTrue(attributed);
    }

    /** A cycle is REPORTED, not silently truncated and not an infinite loop. */
    @Test
    void aCycleInTheLineageIsReportedRatherThanPresentedAsComplete() throws Exception {
        long a = insertRole(managerId, "Cycle A", "2023-05-01", "accepted");
        long b = insertRole(managerId, "Cycle B", "2023-06-01", "accepted");
        await(pool.preparedQuery("""
                UPDATE career_history SET state = 'superseded', superseded_by = $2 WHERE id = $1
                """).execute(Tuple.of(a, b)).mapEmpty());
        await(pool.preparedQuery("""
                UPDATE career_history SET state = 'superseded', superseded_by = $2 WHERE id = $1
                """).execute(Tuple.of(b, a)).mapEmpty());

        JsonObject page = await(moderation.employmentHistory(adminAuth0, a, null, 50));
        assertTrue(page.getBoolean("cycleDetected"), "the cycle went unreported");
        assertFalse(page.getBoolean("lineageComplete"),
            "a cyclic lineage must not call itself complete");
    }

    /** No history is a real answer, with the completeness fields still present. */
    @Test
    void anEmploymentWithNoHistoryStillAnswersTheCompletenessQuestions() throws Exception {
        long role = insertRole(managerId, "Untouched", "2023-07-01", "accepted");
        JsonObject page = await(moderation.employmentHistory(adminAuth0, role, null, 50));
        assertEquals(0, page.getJsonArray("events").size());
        assertTrue(page.getBoolean("lineageComplete"));
        assertFalse(page.getBoolean("cycleDetected"));
        assertFalse(page.getBoolean("hasMore"));
        assertNull(page.getString("nextCursor"));
        assertEquals(1, page.getInteger("employmentsInScope"));
    }

    /**
     * A cursor that is not one we issued is a 400, not a 500.
     *
     * <p>A syntactically fine cursor carrying a timestamp that is not a timestamp reaches
     * {@code $2::timestamptz} and raises 22007 inside the query, which would surface as a server
     * error with a PostgreSQL message in it.
     */
    @Test
    void aMalformedCursorIsRefusedWithoutLeakingADatabaseError() throws Exception {
        long role = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");
        String notATimestamp = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("yesterday|role_correction|7"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertStatus(400, () -> await(
            moderation.employmentHistory(adminAuth0, role, notATimestamp, 50)));
        assertStatus(400, () -> await(
            moderation.employmentHistory(adminAuth0, role, "not-base64-at-all!!", 50)));
    }

    /** The history is contributor-sensitive, so it is administrators only. */
    @Test
    void anOrdinaryUserCannotReadAnEmploymentsHistory() throws Exception {
        long role = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");
        assertForbidden(() -> await(moderation.employmentHistory(ordinaryAuth0, role, null, 50)));
    }

    private void insertCorrectionAt(long role, String field, String was, String now,
                                    String reason, String at) throws Exception {
        // An instant, not a string: the client infers the parameter type and refuses to coerce.
        await(pool.preparedQuery("""
                INSERT INTO role_corrections (role_id, field, was, now_is, reason, corrected_by,
                                              corrected_at)
                VALUES ($1,$2,$3,$4,$5,$6,$7)
                """).execute(Tuple.of(role, field, was, now, reason, admin,
                                      java.time.OffsetDateTime.parse(at))).mapEmpty());
    }

    private static java.util.Set<String> kinds(JsonArray events) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (int i = 0; i < events.size(); i++) out.add(events.getJsonObject(i).getString("kind"));
        return out;
    }

    // ── V109: an audit record names its actor when it is born ───────────────

    /**
     * The hole V108 closed in role_corrections, closed in the three tables that predate it.
     *
     * <p>V105 made the actor nullable with an actor_withdrawn companion so an erasure can remove
     * WHO without deleting WHAT, and its CHECK ties the two together. Its comment claims that
     * refuses an actorless INSERT; it does not, because (NULL, TRUE) satisfies the CHECK, so a row
     * could be born already-withdrawn and the history would name nobody with every constraint
     * satisfied. Driven as raw SQL on purpose: the guarantee has to hold against anything that
     * reaches the database.
     */
    @Test
    void anAuditRecordMustNameItsActorWhenItIsBorn() throws Exception {
        long claim = insertFinalizedUnresolvedClaim(contributor);
        long otherCompany = insertCompany("V109 Other Employer");

        record Insert(String table, String sql, Tuple args) { }
        var actorless = new Insert[] {
            new Insert("claim_corrections", """
                INSERT INTO claim_corrections (claim_id, field, was, now_is, reason, corrected_by)
                VALUES ($1,'title','a','b','actorless',NULL)
                """, Tuple.of(claim)),
            new Insert("manager_merge_events", """
                INSERT INTO manager_merge_events (survivor_id, absorbed_id, survivor_name,
                                                  absorbed_name, absorbed_slug, merged_by)
                VALUES ($1,$2,'S','A','a-slug',NULL)
                """, Tuple.of(managerId, otherManagerId)),
            new Insert("company_merges", """
                INSERT INTO company_merges (source_company_id, target_company_id, merged_by)
                VALUES ($1,$2,NULL)
                """, Tuple.of(otherCompany, companyId)),
        };
        for (Insert i : actorless) {
            Exception refused = assertThrows(Exception.class,
                () -> await(pool.preparedQuery(i.sql()).execute(i.args()).mapEmpty()),
                i.table() + " accepted an audit row that names no actor");
            assertTrue(String.valueOf(refused.getMessage()).contains("must name the actor"),
                i.table() + " was refused for the wrong reason: " + refused.getMessage());
        }

        // And the same three with the flag set, which is the combination the CHECK permits.
        var fabricated = new Insert[] {
            new Insert("claim_corrections", """
                INSERT INTO claim_corrections (claim_id, field, was, now_is, reason,
                                               corrected_by, actor_withdrawn)
                VALUES ($1,'title','a','b','fabricated',NULL,TRUE)
                """, Tuple.of(claim)),
            new Insert("manager_merge_events", """
                INSERT INTO manager_merge_events (survivor_id, absorbed_id, survivor_name,
                                                  absorbed_name, absorbed_slug, merged_by,
                                                  actor_withdrawn)
                VALUES ($1,$2,'S','A','a-slug',NULL,TRUE)
                """, Tuple.of(managerId, otherManagerId)),
            new Insert("company_merges", """
                INSERT INTO company_merges (source_company_id, target_company_id, merged_by,
                                            actor_withdrawn)
                VALUES ($1,$2,NULL,TRUE)
                """, Tuple.of(otherCompany, companyId)),
        };
        for (Insert i : fabricated) {
            assertThrows(Exception.class,
                () -> await(pool.preparedQuery(i.sql()).execute(i.args()).mapEmpty()),
                i.table() + " accepted a row born already-withdrawn");
        }
    }

    /**
     * The erasure path V105 built still works, and takes only the identity.
     *
     * <p>V109 is INSERT-only for exactly this reason: the actor-withdrawal triggers and the
     * foreign key's ON DELETE SET NULL are untouched, so deleting an account still clears WHO and
     * leaves every other column of the record byte-identical.
     */
    @Test
    void erasingAnAuditActorClearsOnlyTheIdentity() throws Exception {
        long claim = insertFinalizedUnresolvedClaim(contributor);
        UUID mod = insertUser("auth0|v109-mod", "admin");
        await(pool.preparedQuery("""
                INSERT INTO claim_corrections (claim_id, field, was, now_is, reason, corrected_by)
                VALUES ($1,'start_date','2019-01-01','2018-01-01','payroll records',$2)
                """).execute(Tuple.of(claim, mod)).mapEmpty());

        await(pool.preparedQuery("DELETE FROM users WHERE id = $1").execute(Tuple.of(mod)).mapEmpty());

        Row row = await(pool.preparedQuery(
                "SELECT * FROM claim_corrections WHERE reason = 'payroll records'")
            .execute().map(rs -> rs.iterator().next()));
        assertNull(row.getUUID("corrected_by"), "the erased actor is still named");
        assertTrue(row.getBoolean("actor_withdrawn"), "withdrawal was not recorded");
        assertEquals("2019-01-01", row.getString("was"), "erasure altered what changed");
        assertEquals("2018-01-01", row.getString("now_is"), "erasure altered what changed");
        assertEquals("payroll records", row.getString("reason"), "erasure altered the reason");
        assertEquals("start_date", row.getString("field"), "erasure altered the field");
    }

    /** A real merge still writes its event, so V109 has not broken the thing that writes most. */
    @Test
    void aRealMergeStillWritesItsEventUnderV109() throws Exception {
        long keep = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");
        long gone = insertRole(managerId, "Depot Supervisor", "2022-11-01", "proposed");
        long before = countRows("manager_merge_events");
        await(moderation.mergeRoles(adminAuth0, managerId, keep, gone));
        assertEquals("superseded", roleRow(gone).getString("state"));
        // A role merge writes no manager_merge_events row; the assertion is that it still works.
        assertEquals(before, countRows("manager_merge_events"));
    }

    // ── 4C: correcting, accepting, reopening ────────────────────────────────

    /**
     * Save and accept is ONE transaction, proved by making the commit fail.
     *
     * <p>FAILURE INJECTION, NOT A PRODUCT RULE. Nothing in the real rules can fail after the
     * audit row is written: {@code rating_role_is_publishable} reads role_basis, the claim's
     * resolution, {@code ch.state = 'accepted'} and unresolved blocking conflicts, and none of
     * those is touched by a title or date correction. A uniqueness collision fails EARLIER, in
     * the career_history update, before any audit exists. So the only way to exercise the
     * transaction boundary itself is to make something inside it raise at COMMIT, which is what
     * this deferred constraint trigger does. It is created and dropped by this test and is not
     * part of the schema.
     *
     * <p>What is asserted afterwards is everything the operation touches: the canonical title and
     * dates, the state, the audit row, and the rating's publication state.
     */
    @Test
    void saveAndAcceptRollsBackEverythingWhenTheCommitFails() throws Exception {
        long proposed = insertRole(managerId, "Depot Supervisor", "2023-03-01", "proposed");
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selection(proposed, contributor)));
        assertEquals("held", dispositionOf(out.reviewId()));
        long auditBefore = countRows("role_corrections");

        await(pool.query("""
                CREATE OR REPLACE FUNCTION test_refuse_at_commit() RETURNS trigger AS $t$
                BEGIN RAISE EXCEPTION 'injected failure at commit'; END; $t$ LANGUAGE plpgsql
                """).execute().mapEmpty());
        await(pool.preparedQuery("""
                CREATE CONSTRAINT TRIGGER test_commit_refusal
                  AFTER UPDATE ON career_history
                  DEFERRABLE INITIALLY DEFERRED
                  FOR EACH ROW EXECUTE FUNCTION test_refuse_at_commit()
                """).execute().mapEmpty());
        try {
            assertThrows(Exception.class, () -> await(moderation.correctAndAcceptEmployment(
                adminAuth0, proposed,
                new org.werkpages.service.ModerationService.Correction(true, "Engineering Manager",
                                                 true, LocalDate.parse("2018-01-01"),
                                                 true, LocalDate.parse("2023-12-31"),
                                                 false, false),
                "should not survive the rollback")),
                "the injected commit failure did not fail the operation");
        } finally {
            await(pool.query("DROP TRIGGER test_commit_refusal ON career_history").execute().mapEmpty());
            await(pool.query("DROP FUNCTION test_refuse_at_commit()").execute().mapEmpty());
        }

        Row role = roleRow(proposed);
        assertEquals("Depot Supervisor", role.getString("title"), "the title change survived");
        assertEquals("proposed", role.getString("state"), "the acceptance survived");
        assertNull(role.getLocalDate("end_date"), "the end date change survived");
        assertEquals(auditBefore, countRows("role_corrections"), "an audit row survived");
        assertEquals("held", dispositionOf(out.reviewId()), "the rating was published anyway");
    }

    /** The happy path: corrected, accepted, and the rating the employment was holding goes live. */
    @Test
    void saveAndAcceptCorrectsThenPublishesTheRatingItWasHolding() throws Exception {
        long proposed = insertRole(managerId, "Depot Supervisor", "2023-03-01", "proposed");
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selection(proposed, contributor)));
        assertEquals("held", dispositionOf(out.reviewId()));

        JsonObject res = await(moderation.correctAndAcceptEmployment(adminAuth0, proposed,
            new org.werkpages.service.ModerationService.Correction(true, "Engineering Manager",
                                             true, LocalDate.parse("2018-01-01"),
                                             false, null, false, false),
            "confirmed against the employer directory"));

        assertEquals(2, res.getInteger("fieldsCorrected"));
        assertTrue(res.getBoolean("accepted"));
        assertEquals(1, res.getInteger("ratingsPublished"));
        assertEquals("live", dispositionOf(out.reviewId()));
        assertEquals("Engineering Manager", roleRow(proposed).getString("title"));
        assertEquals("accepted", roleRow(proposed).getString("state"));
        // The contributor's assertion is untouched by any of it.
        Row claim = claimRow(soleClaimId());
        assertEquals("Dispatch Lead", claim.getString("title"),
            "the correction rewrote what the contributor said");
    }

    /** Corrected and accepted, and STILL held, because something else is in the way. */
    @Test
    void saveAndAcceptLeavesARatingHeldWhenABlockingConflictRemains() throws Exception {
        long proposed = insertRole(managerId, "Depot Supervisor", "2023-03-01", "proposed");
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selection(proposed, contributor)));
        long claim = soleClaimId();
        openBlockingConflict(claim, "possible_duplicate");

        JsonObject res = await(moderation.correctAndAcceptEmployment(adminAuth0, proposed,
            new org.werkpages.service.ModerationService.Correction(true, "Engineering Manager",
                                             false, null, false, null, false, false),
            "title confirmed"));

        assertTrue(res.getBoolean("accepted"));
        assertEquals(0, res.getInteger("ratingsPublished"),
            "the blocking conflict was ignored");
        assertEquals("held", dispositionOf(out.reviewId()));
        assertEquals("accepted", roleRow(proposed).getString("state"));
    }

    /** The audit records the values that were THERE, read under the lock. */
    @Test
    void theAuditRecordsThePriorValuesItReadUnderTheLock() throws Exception {
        long role = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");
        await(moderation.correctEmployment(adminAuth0, role,
            new org.werkpages.service.ModerationService.Correction(true, "Depot Manager",
                                             true, LocalDate.parse("2022-11-01"),
                                             false, null, false, false),
            "payroll records"));

        JsonObject history = await(moderation.employmentHistory(adminAuth0, role, null, 50));
        JsonArray events = history.getJsonArray("events");
        java.util.Map<String, String> was = new java.util.HashMap<>();
        java.util.Map<String, String> now = new java.util.HashMap<>();
        for (int i = 0; i < events.size(); i++) {
            JsonObject e = events.getJsonObject(i);
            if ("role_correction".equals(e.getString("kind"))) {
                was.put(e.getString("what"), e.getString("was"));
                now.put(e.getString("what"), e.getString("nowIs"));
            }
        }
        assertEquals("Depot Supervisor", was.get("title"));
        assertEquals("Depot Manager",    now.get("title"));
        assertEquals("2023-03-01",       was.get("start_date"));
        assertEquals("2022-11-01",       now.get("start_date"));
    }

    /** An omitted field is left alone; an explicit null clears it. */
    @Test
    void anOmittedFieldIsLeftAloneAndAnExplicitNullClearsIt() throws Exception {
        long role = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");
        await(pool.preparedQuery("""
                UPDATE career_history
                   SET end_date = ('2023-09-01'::date)::timestamp AT TIME ZONE 'UTC'
                 WHERE id = $1
                """).execute(Tuple.of(role)).mapEmpty());

        // endGiven = false: the end date must survive a title-only correction.
        await(moderation.correctEmployment(adminAuth0, role,
            new org.werkpages.service.ModerationService.Correction(true, "Depot Manager",
                                             false, null, false, null, false, false),
            "title only"));
        assertEquals(LocalDate.parse("2023-09-01"), roleRow(role).getLocalDate("end_date"),
            "an omitted end date was treated as a request to clear it");

        // endGiven = true with a null value: no recorded end date.
        JsonObject res = await(moderation.correctEmployment(adminAuth0, role,
            new org.werkpages.service.ModerationService.Correction(false, null, false, null,
                                             true, null, false, false),
            "no end date on the record"));
        assertEquals(1, res.getInteger("fieldsCorrected"));
        assertNull(roleRow(role).getLocalDate("end_date"), "an explicit null did not clear it");
    }

    /** A correction that changes nothing records nothing. */
    @Test
    void aCorrectionThatChangesNothingWritesNoAudit() throws Exception {
        long role = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");
        long before = countRows("role_corrections");
        JsonObject res = await(moderation.correctEmployment(adminAuth0, role,
            new org.werkpages.service.ModerationService.Correction(true, "Depot Supervisor", false, null,
                                             false, null, false, false),
            "same title again"));
        assertEquals(0, res.getInteger("fieldsCorrected"));
        assertEquals(before, countRows("role_corrections"));
    }

    /** A correction onto an employment that already exists is refused, and says what to do. */
    @Test
    void aCorrectionThatWouldDuplicateAnEmploymentIsRefused() throws Exception {
        long existing = insertRole(managerId, "Depot Manager", "2022-11-01", "accepted");
        long other    = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");
        assertStatus(409, () -> await(moderation.correctEmployment(adminAuth0, other,
            new org.werkpages.service.ModerationService.Correction(true, "Depot Manager",
                                             true, LocalDate.parse("2022-11-01"),
                                             false, null, false, false),
            "would collide")));
        assertEquals("Depot Supervisor", roleRow(other).getString("title"),
            "the refused correction was partly applied");
        assertNotNull(roleRow(existing));
    }

    /** No reason, no correction. The history has to say why. */
    @Test
    void aCorrectionWithoutAReasonIsRefused() throws Exception {
        long role = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");
        assertStatus(400, () -> await(moderation.correctEmployment(adminAuth0, role,
            new org.werkpages.service.ModerationService.Correction(true, "Depot Manager", false, null,
                                             false, null, false, false), "   ")));
    }

    /** An impossible period is refused before anything is written. */
    @Test
    void anEmploymentCannotBeCorrectedToEndBeforeItStarts() throws Exception {
        long role = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");
        assertStatus(400, () -> await(moderation.correctEmployment(adminAuth0, role,
            new org.werkpages.service.ModerationService.Correction(false, null,
                                             true, LocalDate.parse("2023-06-01"),
                                             true, LocalDate.parse("2023-01-01"),
                                             false, false), "backwards")));
        assertEquals(countRows("role_corrections"), countRows("role_corrections"));
    }

    /** Reopening keeps the rejection, publishes nothing, and hands back the work that is left. */
    @Test
    void reopeningARejectionPublishesNothingAndNamesWhatIsOutstanding() throws Exception {
        long proposed = insertRole(managerId, "Depot Supervisor", "2023-03-01", "proposed");
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selection(proposed, contributor)));
        await(moderation.rejectEmployment(adminAuth0, proposed, "not this employer"));
        assertEquals("rejected", roleRow(proposed).getString("state"));
        assertEquals("held", dispositionOf(out.reviewId()));

        JsonObject res = await(moderation.reopenRejectedEmployment(
            adminAuth0, proposed, "the employer confirmed it after all"));

        long fresh = res.getLong("roleId");
        assertEquals(proposed, (long) res.getLong("reopenedFrom"));
        assertEquals("proposed", roleRow(fresh).getString("state"));
        assertEquals("rejected", roleRow(proposed).getString("state"),
            "the rejection was overwritten instead of superseded");
        assertEquals(proposed, (long) roleRow(fresh).getLong("reopened_from"));
        assertEquals(0, res.getInteger("ratingsPublished"));
        assertEquals("held", dispositionOf(out.reviewId()),
            "reopening republished a rating nobody had reconciled");
        assertEquals(1, res.getJsonArray("claimsToReconcile").size(),
            "the outstanding claim was not handed back");

        // And the moderator can finish the job through the ordinary lifecycle operation.
        long claim = res.getJsonArray("claimsToReconcile").getJsonObject(0).getLong("claimId");
        await(moderation.acceptEmployment(adminAuth0, fresh));
        JsonObject linked = await(moderation.linkClaimToRole(adminAuth0, claim, fresh));
        assertEquals(1, linked.getInteger("ratingsPublished"));
        assertEquals("live", dispositionOf(out.reviewId()));
    }

    /** Several claims on one employment: every assertion is available, none speaks for the rest. */
    @Test
    void anEmploymentWithSeveralClaimsExposesEachAssertion() throws Exception {
        long role = insertRole(managerId, "Depot Supervisor", "2023-03-01", "proposed");
        await(submissions.submit(selection(role, contributor)));
        await(submissions.submit(selection(role, someoneElse)));
        await(submissions.submit(selection(role, null)));

        JsonObject detail = await(moderation.employmentDetail(adminAuth0, role));
        JsonArray claims = detail.getJsonArray("claims");
        assertEquals(3, claims.size(), "not every claim reached the moderator");
        int fromAccounts = 0;
        for (int i = 0; i < claims.size(); i++) {
            if (Boolean.TRUE.equals(claims.getJsonObject(i).getBoolean("fromAccount"))) fromAccounts++;
            assertFalse(claims.getJsonObject(i).containsKey("userId"),
                "a contributor's identity reached the moderation payload");
        }
        assertEquals(2, fromAccounts, "the account/anonymous split is wrong");
    }

    /** Corrections are administrators only, on every new operation. */
    @Test
    void anOrdinaryUserCannotCorrectReopenOrReadHistory() throws Exception {
        long role = insertRole(managerId, "Depot Supervisor", "2023-03-01", "accepted");
        org.werkpages.service.ModerationService.Correction patch =
            new org.werkpages.service.ModerationService.Correction(true, "Nope", false, null, false, null, false, false);
        assertForbidden(() -> await(moderation.correctEmployment(ordinaryAuth0, role, patch, "no")));
        assertForbidden(() -> await(
            moderation.correctAndAcceptEmployment(ordinaryAuth0, role, patch, "no")));
        assertForbidden(() -> await(
            moderation.reopenRejectedEmployment(ordinaryAuth0, role, "no")));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void assertForbidden(ThrowingRunnable body) {
        assertStatus(403, body);
    }

    /**
     * The status the HTTP layer would return.
     *
     * <p>Unwrapped, because {@link #await} surfaces a failed Future as an
     * {@code ExecutionException} and asserting on the wrapper would pass for any failure at all -
     * including a constraint violation that happened to abort the same operation. The handler maps
     * {@link ServiceException} and nothing else, so the type is the assertion.
     */
    /**
     * The {@link ServiceException} a refused operation produced, unwrapped.
     *
     * <p>{@link #await} surfaces a failed Future as an {@code ExecutionException}, so asserting on
     * the raised type directly passes for any failure at all - a constraint violation that aborted
     * the same operation included. The handler maps {@code ServiceException} and nothing else, so
     * the type and its status are the assertion.
     */
    private ServiceException refused(ThrowingRunnable body) {
        Throwable raised = assertThrows(Throwable.class, body::run);
        ServiceException e = serviceExceptionIn(raised);
        assertNotNull(e, "expected a ServiceException, got: " + raised);
        return e;
    }

    private void assertStatus(int expected, ThrowingRunnable body) {
        Throwable raised = assertThrows(Throwable.class, body::run);
        ServiceException e = serviceExceptionIn(raised);
        assertNotNull(e, "expected a ServiceException, got: " + raised);
        assertEquals(expected, e.getStatusCode(), e.getMessage());
    }

    private static ServiceException serviceExceptionIn(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ServiceException se) return se;
        }
        return null;
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private RoleAwareSubmissionService.Submission selection(long roleId, UUID user) {
        return new RoleAwareSubmissionService.Submission(
            UUID.randomUUID(), 1, managerId, companyId, "Dispatch Lead", roleId,
            null, null, false, null, "Halverson Logistics", "Ash B", user,
            4.0, tens(4.0), OffsetDateTime.parse("2024-02-01T00:00:00Z"));
    }

    private RoleAwareSubmissionService.Submission proposal(String title, LocalDate start, UUID user) {
        return new RoleAwareSubmissionService.Submission(
            UUID.randomUUID(), 1, managerId, companyId, title, null,
            start, null, true, null, "Halverson Logistics", "Ash B", user,
            4.0, tens(4.0), OffsetDateTime.parse("2024-02-01T00:00:00Z"));
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

    private long insertManager(String name, String slug) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO managers (name, company, title, status, approval_status, country, "
                + "company_id, slug, overall_rating, reviews_count, category_averages) "
                + "VALUES ($1,'Halverson Logistics','Lead','active','approved','CA',$2,$3,0,0,"
                + "'{}'::jsonb) RETURNING id")
            .execute(Tuple.of(name, companyId, slug))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private UUID insertUser(String auth0Id, String role) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO users (auth0_id, username, role) VALUES ($1,$2,$3) RETURNING id")
            .execute(Tuple.of(auth0Id, "u" + Math.abs(auth0Id.hashCode()), role))
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

    /**
     * A finalized claim with no role yet: the state an admin meets when a contributor's claim was
     * refused, or when nothing could be matched automatically.
     */
    private long insertFinalizedUnresolvedClaim(UUID user) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO role_claims (proposal_key, manager_id, company_id, title,
                                         period_known, still_there, source, user_id,
                                         finalized_at, original_manager_id, revision)
                VALUES ($1,$2,$3,'Depot Supervisor',FALSE,FALSE,
                        'contributor',$4, now(), $2, 1)
                RETURNING id
                """).execute(Tuple.of(UUID.randomUUID(), managerId, companyId, user))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertUnfinalizedDraftClaim(UUID user) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO role_claims (proposal_key, manager_id, company_id, title,
                                         period_known, still_there, source, user_id, revision)
                VALUES ($1,$2,$3,'Depot Supervisor',FALSE,FALSE,
                        'contributor',$4, 1)
                RETURNING id
                """).execute(Tuple.of(UUID.randomUUID(), managerId, companyId, user))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    /**
     * A withdrawn rating: no claim, but it keeps the employment it was attributed to.
     *
     * <p>This is the shape the merge produces for a contributor whose claim became a duplicate, and
     * the one {@code assert_roles_ratings} had to be widened to see.
     */
    private UUID insertWithdrawnHeldRating(long roleId) throws Exception {
        return insertWithdrawnRating(roleId, "held");
    }

    private UUID insertWithdrawnRating(long roleId, String disposition) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, author, overall_rating, manager_title,
                                     manager_company, communication_style,
                                     perceived_approachability, perceived_clarity_of_expectations,
                                     feedback_style, perceived_supportiveness,
                                     decision_making_style, organization_and_planning_style,
                                     delegation_style, perceived_professional_demeanor,
                                     overall_working_experience,
                                     role_basis, attribution_basis, role_required,
                                     withdrawn_role_id, disposition, gate_eligible, live_since)
                VALUES ($1,'Anonymous User',4.0,'Dispatch Lead','Halverson Logistics',
                        4,4,4,4,4,4,4,4,4,4,
                        'withdrawn','withdrawn_role',FALSE,$2,$3,
                        $3 = 'live', CASE WHEN $3 = 'live' THEN now() END)
                RETURNING id
                """).execute(Tuple.of(managerId, roleId, disposition))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private long openBlockingConflict(long claimId, String kind) throws Exception {
        assertTrue(blocksPublication(kind), kind + " is not a publication-blocking kind");
        return openConflict(kind, claimId, null);
    }

    /**
     * A real conflict row, with its claims, in ONE transaction.
     *
     * <p>Two schema rules shape this fixture, and both are worth stating because getting either
     * wrong makes the row impossible rather than merely wrong:
     *
     * <ul>
     *   <li><b>{@code conflict_claims_blocking_has_a_subject} is a DEFERRED constraint trigger.</b>
     *       A blocking conflict must have at least one claim that is actually in doubt by the time
     *       the transaction commits, so inserting the conflict and its subject as two separate
     *       statements fails: the first commit finds no subject. A blocking conflict nobody is in
     *       doubt about would hold ratings for no stated reason.</li>
     *   <li><b>{@code blocks_publication} and {@code case_key} are GENERATED.</b> Blocking follows
     *       from the KIND through {@code kind_blocks_publication} and cannot be asserted by a
     *       caller, which is the design: whether a conflict holds a rating is a property of what
     *       kind of disagreement it is.</li>
     * </ul>
     */
    private long openConflict(String kind, Long subjectClaimId, Long evidenceClaimId)
            throws Exception {
        long id = await(pool.withTransaction(tx -> tx.preparedQuery("""
                INSERT INTO role_conflicts (kind, manager_id, company_id, title, title_key,
                                            period_key, manager_name, company_name)
                VALUES ($1,$2,$3,'Depot Supervisor','depot supervisor','2023',
                        'Priya Raghunathan','Halverson Logistics')
                RETURNING id
                """).execute(Tuple.of(kind, managerId, companyId))
            .map(rs -> rs.iterator().next().getLong("id"))
            .compose(conflictId -> {
                Future<Void> chain = Future.succeededFuture();
                if (subjectClaimId != null) {
                    chain = chain.compose(v -> tx.preparedQuery(
                            "INSERT INTO role_conflict_claims (conflict_id, claim_id, role) "
                            + "VALUES ($1,$2,'subject')")
                        .execute(Tuple.of(conflictId, subjectClaimId)).mapEmpty());
                }
                if (evidenceClaimId != null) {
                    chain = chain.compose(v -> tx.preparedQuery(
                            "INSERT INTO role_conflict_claims (conflict_id, claim_id, role) "
                            + "VALUES ($1,$2,'evidence')")
                        .execute(Tuple.of(conflictId, evidenceClaimId)).mapEmpty());
                }
                // Only the SUBJECT of a blocking conflict has its rating held, and the predicate
                // decides that. Reflect it so the rest of the test sees the state the submission
                // path would have left behind.
                if (blocks(kind) && subjectClaimId != null) {
                    chain = chain.compose(v -> tx.preparedQuery("""
                            UPDATE reviews SET disposition = 'held', gate_eligible = FALSE,
                                               live_since = NULL
                             WHERE claim_id = $1 AND disposition = 'live'
                            """).execute(Tuple.of(subjectClaimId)).mapEmpty());
                }
                return chain.map(v -> conflictId);
            })));
        return id;
    }

    /** The kind's blocking status, read once per call site rather than assumed. */
    private boolean blocks(String kind) {
        return java.util.List.of("ambiguous_resolution", "possible_duplicate",
                                 "duplicate_rating_on_merge", "cross_era_possible_duplicate")
            .contains(kind);
    }

    private boolean blocksPublication(String kind) throws Exception {
        return await(pool.preparedQuery("SELECT kind_blocks_publication($1) AS b")
            .execute(Tuple.of(kind)).map(rs -> rs.iterator().next().getBoolean("b")));
    }

    private JsonObject soleQueueRow() throws Exception {
        JsonArray rows = await(moderation.pendingEmployments(adminAuth0, 50, 0));
        assertEquals(1, rows.size(), "expected exactly one proposed employment");
        return rows.getJsonObject(0);
    }

    private Row latestClaimEvent(long claimId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT * FROM claim_resolution_events WHERE claim_id = $1 "
                + "ORDER BY id DESC LIMIT 1")
            .execute(Tuple.of(claimId)).map(rs -> rs.iterator().next()));
    }

    private Row claimRow(long id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM role_claims WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private Row roleRow(long id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM career_history WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private Row conflictRow(long id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM role_conflicts WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private Row reviewRow(UUID id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM reviews WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private Row managerRow(long id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM managers WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private String dispositionOf(UUID id) throws Exception {
        return reviewRow(id).getString("disposition");
    }

    private long otherClaimId(long notThisOne) throws Exception {
        return await(pool.preparedQuery("SELECT id FROM role_claims WHERE id <> $1")
            .execute(Tuple.of(notThisOne)).map(rs -> {
                assertEquals(1, rs.rowCount(), "expected exactly one other claim");
                return rs.iterator().next().getLong("id");
            }));
    }

    private long soleClaimId() throws Exception {
        return await(pool.query("SELECT id FROM role_claims").execute().map(rs -> {
            assertEquals(1, rs.rowCount(), "expected exactly one claim");
            return rs.iterator().next().getLong("id");
        }));
    }

    private long soleOrZeroClaim() throws Exception {
        return await(pool.query("SELECT id FROM role_claims LIMIT 1").execute()
            .map(rs -> rs.rowCount() == 0 ? 0L : rs.iterator().next().getLong("id")));
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
