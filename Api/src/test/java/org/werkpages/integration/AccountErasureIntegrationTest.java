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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.ClaimView;
import org.werkpages.service.ManagerMergeService;
import org.werkpages.service.RatingOpportunityService;
import org.werkpages.service.RoleAwareSubmissionService;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deleting an account, against the schema the role-aware lifecycle actually produces.
 *
 * <h2>The principle under test</h2>
 *
 * <p>Rev 268: <b>"an account's deletion must not destroy evidence, and must not be blocked by
 * it."</b> Both halves matter, and the second half is the one that was broken: once a signed-in
 * contributor owned a claim, {@code role_claims_user_id_fkey} refused the delete.
 *
 * <h2>The worse half, which is what makes this a data-integrity suite</h2>
 *
 * <p>The deletion sequence anonymises first and deletes last. Before the correction those
 * statements were not transactional, so a refused delete left the person <b>half-deleted</b>: their
 * ratings severed and anonymous, their account still present, and no way to put the identity back.
 * {@link #aFailureAtTheDeleteLeavesTheContributorCompletelyIntact} is the regression test for that
 * state, and it is the one that matters most here: being told "deletion failed" is survivable, and
 * silently losing the link between a person and their own words is not.
 *
 * <h2>An identity may be added once and removed once, and never transferred</h2>
 *
 * <p>Adding is adoption, removing is deletion, transferring is the only one of the three that is
 * always an attack. The append-only tables permit exactly the removal shape and nothing else, which
 * {@link #appendOnlyHistoryStillRefusesEverythingExceptTheErasure} holds the line on.
 */
/* ROUTINE CI: tagged "critical" - security, erasure or data integrity. See the poms. */
@Tag("critical")
@Testcontainers
class AccountErasureIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("account_erasure")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static UserRepository users;
    static RoleAwareSubmissionService submissions;
    static RatingOpportunityService opportunities;
    static ManagerMergeService merges;

    long companyId;
    long managerId;
    long otherManagerId;
    UUID contributor;
    UUID admin;

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
        users         = new UserRepository(pool);
        submissions   = new RoleAwareSubmissionService(pool, true);
        opportunities = new RatingOpportunityService();
        merges        = new ManagerMergeService(pool);
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
        otherManagerId = insertManager("P. Raghunathan", "p-rag");
        contributor    = insertUser("auth0|erasure-contributor");
        admin          = insertUser("auth0|erasure-admin");
    }

    // -- the core case --------------------------------------------------------

    @Test
    void deletingAnAccountAfterARoleAwareSubmissionSucceedsAndKeepsTheContribution()
            throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selection(role, contributor)));
        assertTrue(out.live());
        assertEquals(1, await(opportunities.spent(pool, contributor, "manager", managerId)));

        await(users.deleteWithReviewAnonymization(contributor));

        // THE ACCOUNT IS GONE. Not "the call returned without throwing": the row is absent.
        assertEquals(0, countWhere("users", "id = '" + contributor + "'"));

        // THE EVIDENCE SURVIVES, which is the other half of the principle. The claim is still the
        // employment record and still resolves to the role, so the rating it carries is still
        // correctly attributed and still publishable.
        Row claim = claimRow(out.claimId());
        assertNull(claim.getUUID("user_id"), "the identity is gone");
        assertEquals(role, claim.getLong("resolved_role_id"), "the employment record is not");
        assertNotNull(claim.getOffsetDateTime("finalized_at"));

        // THE RATING IS STILL LIVE AND STILL COUNTED. reviews_user_id_fkey is ON DELETE CASCADE, so
        // without the anonymising UPDATE that runs first this row would have been DELETED, not
        // anonymised. That ordering is the only thing between a deletion request and the loss of
        // every opinion the person contributed.
        Row review = reviewRow(out.reviewId());
        assertNull(review.getUUID("user_id"));
        ErasedByline.assertMinted(review.getString("author"), "the anonymised rating");
        assertEquals("live", review.getString("disposition"));
        assertEquals(1, (int) reviewsCountOf(managerId), "still publicly counted");

        // THE ALLOWANCE IS GONE, deliberately. An allowance is a limit on an account; no account,
        // no limit to enforce. The honest consequence is that deleting and re-registering resets
        // it, which no schema fixes.
        assertEquals(0, countRows("rating_opportunities"));
    }

    @Test
    void deletingAnAccountAfterALegacySubmissionStillWorks() throws Exception {
        UUID legacy = insertLegacyReview(managerId, "Ash B", contributor);

        await(users.deleteWithReviewAnonymization(contributor));

        assertEquals(0, countWhere("users", "id = '" + contributor + "'"));
        assertNull(reviewRow(legacy).getUUID("user_id"));
        ErasedByline.assertMinted(reviewRow(legacy).getString("author"),
            "a legacy rating whose author erased themselves");
        assertEquals("live", reviewRow(legacy).getString("disposition"),
            "a legacy rating needs no claim and no role, so deletion cannot change its standing");
    }

    @Test
    void deletingAnAccountAfterAMergeKeepsTheMergeEventAndMarksTheActorWithdrawn()
            throws Exception {
        long role = insertRole(otherManagerId, "Dispatch Lead", "2024-01-15", "accepted");
        await(submissions.submit(selectionOn(otherManagerId, role, contributor)));
        // The administrator performs the merge, so manager_merge_events.merged_by names them.
        await(merges.mergeManagers(managerId, otherManagerId, admin));

        // Deleting the ADMINISTRATOR, which was blocked by manager_merge_events.merged_by being
        // both NOT NULL and NO ACTION.
        await(users.deleteWithReviewAnonymization(admin));

        assertEquals(0, countWhere("users", "id = '" + admin + "'"));
        Row event = soleRow("manager_merge_events");
        assertNull(event.getUUID("merged_by"), "who did it is gone");
        assertTrue(event.getBoolean("actor_withdrawn"),
            "and the record SAYS the actor was withdrawn, rather than reading as a merge no human "
            + "performed, which is a worse record than none");
        assertEquals(managerId, event.getLong("survivor_id"), "what happened is intact");

        // The contributor's own deletion still works afterwards, with their claim now on the
        // survivor.
        await(users.deleteWithReviewAnonymization(contributor));
        assertEquals(0, countWhere("users", "id = '" + contributor + "'"));
        assertEquals(managerId, claimRow(soleClaimId()).getLong("manager_id"));
    }

    @Test
    void deletingAnAdministratorAfterACorrectionKeepsTheCorrection() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selection(role, contributor)));
        await(pool.preparedQuery(
                "INSERT INTO claim_corrections (claim_id, field, was, now_is, reason, corrected_by) "
                + "VALUES ($1,'title','Dispatch Lead','Depot Lead','typo',$2)")
            .execute(Tuple.of(out.claimId(), admin)));

        await(users.deleteWithReviewAnonymization(admin));

        Row correction = soleRow("claim_corrections");
        assertNull(correction.getUUID("corrected_by"));
        assertTrue(correction.getBoolean("actor_withdrawn"));
        assertEquals("Dispatch Lead", correction.getString("was"), "what was corrected survives");
        assertEquals("Depot Lead", correction.getString("now_is"));
    }

    @Test
    void deletingAnAccountAfterAnOpportunityRetirementKeepsTheAuditAndClearsTheIdentity()
            throws Exception {
        // Two opportunities on the survivor and an earlier one on the absorbed profile, so the
        // merge retires one and writes a retirement audit row naming the contributor.
        insertOpportunity(contributor, managerId, 1, "2026-03-01T00:00:00Z");
        insertOpportunity(contributor, managerId, 2, "2026-05-01T00:00:00Z");
        insertOpportunity(contributor, otherManagerId, 1, "2026-01-01T00:00:00Z");
        await(merges.mergeManagers(managerId, otherManagerId, admin));
        assertEquals(1, countRows("rating_opportunity_retirements"));

        await(users.deleteWithReviewAnonymization(contributor));

        // NO FOREIGN KEY AT ALL on this table, so no cascade clears it and the append-only trigger
        // would have refused a cascade anyway. Erasure was found incomplete in exactly this one
        // table for exactly that reason, so the deletion clears it explicitly.
        assertEquals(1, countRows("rating_opportunity_retirements"),
            "the audit record survives: the cap was genuinely exercised and that stays true");
        Row retirement = soleRow("rating_opportunity_retirements");
        assertNull(retirement.getUUID("user_id"), "but it no longer names anybody");
        assertEquals("over_cap_on_merge", retirement.getString("reason"));
        assertEquals(managerId, retirement.getLong("surviving_subject_id"));
    }

    @Test
    void deletingAnAccountWithACanonicalizedClaimKeepsWhenAndFromWhat() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        long other = insertRole(managerId, "Depot Lead", "2025-01-15", "accepted");
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selection(role, contributor)));
        await(pool.preparedQuery(
                "UPDATE role_claims SET canonicalized_by = $2, canonicalized_at = now(), "
                + "canonicalized_from = $3 WHERE id = $1")
            .execute(Tuple.of(out.claimId(), admin, other)));

        await(users.deleteWithReviewAnonymization(admin));

        // claim_canonicalization_is_complete used to pair the actor WITH the timestamp, so nulling
        // the actor violated the CHECK. Relaxed so that WHEN and FROM WHICH ROLE still pair with
        // each other and only WHO is removed.
        Row claim = claimRow(out.claimId());
        assertNull(claim.getUUID("canonicalized_by"));
        assertNotNull(claim.getOffsetDateTime("canonicalized_at"), "when it happened survives");
        assertEquals(other, claim.getLong("canonicalized_from"), "and from which role");
        assertTrue(claim.getBoolean("canonicalization_actor_withdrawn"));
    }

    @Test
    void anAccountWithManagerCompanyAndInterviewContributionsLosesNoneOfThem() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        RoleAwareSubmissionService.Outcome managerRating =
            await(submissions.submit(selection(role, contributor)));
        UUID companyRating   = insertCompanyReview(contributor);
        UUID interviewRating = insertInterviewReview(contributor);

        // The published figures before, so "statistics are not reduced" is measured rather than
        // asserted from reading the triggers.
        int managerReviewsBefore = reviewsCountOf(managerId);
        long companyRowsBefore   = countRows("company_reviews");
        long interviewRowsBefore = countRows("interview_reviews");

        await(users.deleteWithReviewAnonymization(contributor));

        assertEquals(0, countWhere("users", "id = '" + contributor + "'"));

        // ALL THREE SURVIVE. Before V105 only the manager rating did: company_reviews.user_id and
        // interview_reviews.user_id were ON DELETE CASCADE and nothing anonymised them, so closing
        // an account silently destroyed every company rating and interview experience the person
        // had written while their manager ratings were carefully preserved.
        assertEquals(companyRowsBefore, countRows("company_reviews"),
            "the company rating survives its author");
        assertEquals(interviewRowsBefore, countRows("interview_reviews"),
            "so does the interview experience");
        assertEquals("live", dispositionOf(managerRating.reviewId()));

        // IDENTITY SEVERED on all three.
        //
        // The byline is MINTED, not nulled. These two read assertNull, which was the premise
        // UserRepository stated - "no author name is ever selected for display" - and that premise
        // was false: CompanyReviewService and InterviewService both put `author` in the response,
        // and the cards render a null one as "Anonymous employee" / "Anonymous candidate". So the
        // null was the same disclosure the manager path had already been fixed to avoid, and these
        // assertions were holding it in place.
        assertNull(companyReviewRow(companyRating).getUUID("user_id"));
        ErasedByline.assertMinted(companyReviewRow(companyRating).getString("author"),
            "the erased company rating");
        assertNull(interviewReviewRow(interviewRating).getUUID("user_id"));
        ErasedByline.assertMinted(interviewReviewRow(interviewRating).getString("author"),
            "the erased interview experience");
        assertNull(reviewRow(managerRating.reviewId()).getUUID("user_id"));

        // STILL ATTRIBUTED TO THEIR SUBJECT, which is what makes them worth keeping.
        assertEquals(companyId, companyReviewRow(companyRating).getLong("company_id"));
        assertEquals(companyId, interviewReviewRow(interviewRating).getLong("company_id"));
        assertEquals(managerId, (long) reviewRow(managerRating.reviewId()).getLong("manager_id"));

        // AND THE STATISTICS DID NOT FALL. Both tables carry statement-level AFTER DELETE triggers
        // that recalculate company_stats_live downward, so under the old CASCADE a person closing
        // their account quietly reduced the published figures for every company they had rated.
        assertEquals(managerReviewsBefore, (int) reviewsCountOf(managerId));
        assertEquals(0, driftCount(), "company_stats_live agrees with its source tables");
    }

    /**
     * REPORTED, 2026-11-26: a company page showed "Former employee / Anonymous employee" beside
     * ratings signed with ordinary handles, so a reader could tell at a glance which opinion had
     * been written by somebody who deleted their account. That is the one fact the erasure exists
     * to remove, and it was being announced on the card.
     *
     * <p>The cause was a false premise written into {@code UserRepository}: "a company rating is
     * anonymous by construction and no author name is ever selected for display, so severing the
     * identity is all that is needed". Both {@code CompanyReviewService} and
     * {@code InterviewService} do select it, and both clients render a null one as the literal
     * "Anonymous employee" / "Anonymous candidate" - strings worn by no live contribution.
     *
     * <p>So the manager path's fix (V107, {@code Pseudonyms.mint()}) never reached the other two
     * tables, and nulling the column was exactly as disclosing as the 'Anonymous User' literal it
     * had replaced on {@code reviews}.
     *
     * <p>Asserted here on its own, rather than only inside the three-contribution test, because
     * what has to hold is a property of the byline and not a side effect of counting rows.
     */
    @Test
    void anErasedCompanyRatingAndInterviewExperienceCarryPlausibleHandlesRatherThanNothing()
            throws Exception {
        UUID companyRating   = insertCompanyReview(contributor);
        UUID interviewRating = insertInterviewReview(contributor);

        await(users.deleteWithReviewAnonymization(contributor));

        String companyByline   = companyReviewRow(companyRating).getString("author");
        String interviewByline = interviewReviewRow(interviewRating).getString("author");

        ErasedByline.assertMinted(companyByline, "the erased company rating");
        ErasedByline.assertMinted(interviewByline, "the erased interview experience");

        // And NOT the handle they wrote it under: the fixture signs both 'Ash B', and keeping it
        // would leave the person's own name on a rating they asked to be disconnected from.
        assertNotEquals("Ash B", companyByline);
        assertNotEquals("Ash B", interviewByline);
    }

    @Test
    void aFailureAnywhereInTheSequenceCommitsNoPartOfIt() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        RoleAwareSubmissionService.Outcome managerRating =
            await(submissions.submit(selection(role, contributor)));
        UUID companyRating   = insertCompanyReview(contributor);
        UUID interviewRating = insertInterviewReview(contributor);

        // Injected at the LAST statement, which is the retirement clear, so every earlier mutation
        // in the sequence has already happened when it fires. That is the ordering that used to
        // leave somebody half-deleted.
        await(pool.query(
            "CREATE OR REPLACE FUNCTION t_block_retirement() RETURNS trigger AS $BODY$ "
            + "BEGIN RAISE EXCEPTION 'injected: retirement clear blocked'; END; $BODY$ "
            + "LANGUAGE plpgsql").execute());
        await(pool.query("CREATE TRIGGER t_block_ret BEFORE DELETE ON users "
            + "FOR EACH ROW EXECUTE FUNCTION t_block_retirement()").execute());
        try {
            Exception failed = assertThrows(Exception.class,
                () -> await(users.deleteWithReviewAnonymization(contributor)));
            assertTrue(message(failed).contains("injected"), message(failed));

            // NOTHING committed. Every byline and every association is exactly as it was, across
            // all three contribution types, and the account is intact.
            assertEquals(contributor, reviewRow(managerRating.reviewId()).getUUID("user_id"));
            assertEquals("Ash B", reviewRow(managerRating.reviewId()).getString("author"));
            assertEquals(contributor, companyReviewRow(companyRating).getUUID("user_id"));
            assertEquals(contributor, interviewReviewRow(interviewRating).getUUID("user_id"));
            assertEquals(contributor, claimRow(managerRating.claimId()).getUUID("user_id"));
            assertEquals(1, countWhere("users", "id = '" + contributor + "'"));
        } finally {
            await(pool.query("DROP TRIGGER t_block_ret ON users").execute());
            await(pool.query("DROP FUNCTION t_block_retirement()").execute());
        }

        await(users.deleteWithReviewAnonymization(contributor));
        assertEquals(0, countWhere("users", "id = '" + contributor + "'"));
    }

    @Test
    void anActorCannotBeErasedWhileTheirAccountStillExists() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selection(role, contributor)));
        await(pool.preparedQuery(
                "INSERT INTO claim_corrections (claim_id, field, was, now_is, reason, corrected_by) "
                + "VALUES ($1,'title','a','b','fix',$2)")
            .execute(Tuple.of(out.claimId(), admin)));
        long otherRole = insertRole(managerId, "Depot Lead", "2025-01-15", "accepted");
        await(pool.preparedQuery(
                "UPDATE role_claims SET canonicalized_by = $2, canonicalized_at = now(), "
                + "canonicalized_from = $3 WHERE id = $1")
            .execute(Tuple.of(out.claimId(), admin, otherRole)));
        await(merges.mergeManagers(managerId, otherManagerId, admin));

        // THE SECURITY PROPERTY. Dropping NOT NULL made `SET merged_by = NULL` syntactically
        // possible, and a trigger that merely stamped "withdrawn" on any such update would help an
        // attacker erase a moderator from the audit trail of their own decisions.
        //
        // The permitted transition and the authorization to perform it are separate concerns, so
        // every erasure trigger requires that the account is ALREADY GONE. A direct tamper while
        // the moderator still has an account is refused.
        for (String sql : List.of(
                "UPDATE manager_merge_events SET merged_by = NULL",
                "UPDATE claim_corrections SET corrected_by = NULL",
                "UPDATE role_claims SET canonicalized_by = NULL WHERE canonicalized_by IS NOT NULL")) {
            Exception tamper = assertThrows(Exception.class,
                () -> await(pool.query(sql).execute()), "expected refusal for: " + sql);
            assertTrue(message(tamper).contains("account has been deleted"),
                sql + " -> " + message(tamper));
        }

        // And reassigning an actor to somebody else is refused outright, which is the shape that is
        // always an attack rather than ever a mistake.
        Exception reassign = assertThrows(Exception.class, () -> await(pool.preparedQuery(
            "UPDATE manager_merge_events SET merged_by = $1").execute(Tuple.of(contributor))));
        assertTrue(message(reassign).contains("never reassigned"), message(reassign));

        // The administrator is still named everywhere, because none of that was permitted.
        assertEquals(admin, soleRow("manager_merge_events").getUUID("merged_by"));
        assertFalse(soleRow("manager_merge_events").getBoolean("actor_withdrawn"));

        // And the real deletion still works.
        await(users.deleteWithReviewAnonymization(admin));
        assertNull(soleRow("manager_merge_events").getUUID("merged_by"));
        assertTrue(soleRow("manager_merge_events").getBoolean("actor_withdrawn"));
    }

    // -- the employment period, withheld on anonymisation ---------------------

    @Test
    void anonymisingAnAccountHidesTheEmploymentPeriodButKeepsTheRatingPublished()
            throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selection(role, contributor)));
        await(pool.preparedQuery(
                "UPDATE reviews SET worked_from = '2023-03-01'::date, "
                + "worked_until = '2023-06-01'::date WHERE id = $1")
            .execute(Tuple.of(out.reviewId())));
        assertFalse(reviewRow(out.reviewId()).getBoolean("dates_hidden"),
            "fixture: an ordinary rating publishes its period");

        await(users.deleteWithReviewAnonymization(contributor));

        // A PRIVACY REQUIREMENT, not a tidy-up. "Worked with this manager March to June 2023"
        // narrows the candidates to very few at a small employer and to exactly one often enough to
        // matter. Severing the name while publishing the period leaves the rating re-attributable
        // by anybody who knows the team.
        assertTrue(reviewRow(out.reviewId()).getBoolean("dates_hidden"),
            "the employment period is withheld from the public the moment the author is severed");

        // THE OPINION SURVIVES, which is the whole point of anonymising rather than deleting.
        assertEquals("live", reviewRow(out.reviewId()).getString("disposition"));
        assertEquals(1, (int) reviewsCountOf(managerId), "and it is still counted");

        // PRESERVED INTERNALLY. Moderation, attribution and integrity still need the period; it is
        // the PUBLICATION of it that stops, not the record.
        assertNotNull(reviewRow(out.reviewId()).getLocalDate("worked_from"),
            "the dates are kept for moderation and attribution, only not published");
        assertNotNull(reviewRow(out.reviewId()).getLocalDate("worked_until"));
    }

    @Test
    void anOrdinaryRatingKeepsItsPublishedDates() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        UUID other = insertUser("auth0|untouched");
        RoleAwareSubmissionService.Outcome mine =
            await(submissions.submit(selection(role, contributor)));
        long otherRole = insertRole(managerId, "Depot Lead", "2025-02-01", "accepted");
        RoleAwareSubmissionService.Outcome theirs =
            await(submissions.submit(selectionFor(otherRole, other)));

        await(users.deleteWithReviewAnonymization(contributor));

        // Deleting one account must not change anybody else's date visibility. The rule applies to
        // ratings that have undergone anonymisation and to nothing else.
        assertTrue(reviewRow(mine.reviewId()).getBoolean("dates_hidden"));
        assertFalse(reviewRow(theirs.reviewId()).getBoolean("dates_hidden"),
            "an untouched rating still publishes its period");
    }

    @Test
    void aCompanyRatingsPeriodIsWithheldOnAnonymisationToo() throws Exception {
        UUID companyRating = insertCompanyReview(contributor);
        assertFalse(companyReviewRow(companyRating).getBoolean("dates_hidden"),
            "fixture: an ordinary company rating publishes its period");

        await(users.deleteWithReviewAnonymization(contributor));

        // company_reviews carries the same column and the same worked_from / worked_until pair, and
        // a period at one employer is no less identifying than a period under one manager. The
        // self-deletion path already set this, so the two routes now agree.
        assertTrue(companyReviewRow(companyRating).getBoolean("dates_hidden"));
        assertEquals(1, countRows("company_reviews"), "and the rating itself survives");
    }

    @Test
    void noPubliclyLinkedRecordCanReconstructTheHiddenPeriod() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        RoleAwareSubmissionService.Outcome out = await(submissions.submit(
            selectionWithPeriod(role, contributor, "2023-03-01", "2023-06-01")));
        await(pool.preparedQuery(
                "UPDATE reviews SET worked_from = '2023-03-01'::date, "
                + "worked_until = '2023-06-01'::date WHERE id = $1")
            .execute(Tuple.of(out.reviewId())));
        // The claim records the contributor's OWN asserted period, which is the same fact by
        // another route. It is SUBMITTED with that period rather than patched on afterwards:
        // V108's claim_assertion_is_immutable refuses the rewrite, and asserting it at submission
        // is what production does. The dates are unchanged, so the reconstruction path under test
        // is the same one.

        await(users.deleteWithReviewAnonymization(contributor));

        // THE RECONSTRUCTION PATH THAT MATTERS. Hiding the rating's dates achieves nothing if the
        // claim behind it publishes the same period, and the claim is reachable from the rating by
        // claim_id. This asserts the shape of the risk rather than the absence of one endpoint:
        // role_claims keeps the period internally, and NOTHING may expose it publicly.
        //
        // Today nothing does, because no endpoint reads role_claims at all: the role-aware flow is
        // behind a disabled flag. That is why this test pins the requirement now, while the public
        // readers are still being written in 4B and 4C.
        assertNotNull(claimRow(out.claimId()).getLocalDate("start_date"),
            "the claim keeps the period for moderation and attribution");
        assertTrue(reviewRow(out.reviewId()).getBoolean("dates_hidden"));

        // The manager's OWN role dates are a different fact and stay public: career_history says
        // when the MANAGER held the job, not when this contributor worked under them. Conflating
        // the two would hide the employment record the whole redesign exists to build.
        assertNotNull(roleRow(role).getLocalDate("start_date"),
            "the manager's employment record is not a contributor's period and is not hidden");
    }

    // -- the legacy dedup collision -------------------------------------------

    @Test
    void twoPeopleWhoRatedOneManagerIdenticallyCanBothDeleteTheirAccounts() throws Exception {
        UUID ada = insertUser("auth0|ada");
        UUID bea = insertUser("auth0|bea");
        // Same manager, same title, same employer, different people. Legacy ratings, so they are
        // covered by the author-string dedup index rather than the user-id one.
        UUID adasRating = insertLegacyReview(managerId, "Ada Lovelace", ada);
        UUID beasRating = insertLegacyReview(managerId, "Bea Clarke", bea);

        await(users.deleteWithReviewAnonymization(ada));

        // THE REGRESSION. Before V107 this second deletion was REFUSED, because Ada's rating had
        // already taken 'Anonymous User' on that manager-title-employer key:
        //
        //   ERROR: duplicate key value violates unique constraint
        //          "idx_reviews_author_manager_role_legacy"
        //
        // An unrelated person's earlier erasure stopped Bea erasing herself. On a platform carrying
        // sensitive workplace experiences that is not an inconvenience.
        await(users.deleteWithReviewAnonymization(bea));

        assertEquals(0, countWhere("users", "id = '" + ada + "'"));
        assertEquals(0, countWhere("users", "id = '" + bea + "'"));

        // BOTH OPINIONS SURVIVE, both anonymous, both still counted. Neither was sacrificed to make
        // the other possible.
        for (UUID r : List.of(adasRating, beasRating)) {
            assertNull(reviewRow(r).getUUID("user_id"));
            ErasedByline.assertMinted(reviewRow(r).getString("author"),
                "both surviving opinions");
            assertTrue(reviewRow(r).getBoolean("author_erased"));
            assertEquals("live", reviewRow(r).getString("disposition"));
            assertNull(reviewRow(r).getOffsetDateTime("deleted_at"));
        }
        // Counted from the source rather than from managers.reviews_count: that cached column is
        // written by the recalculation, and these fixtures insert ratings directly, so it was never
        // populated. The claim being made is about the ratings that remain publicly visible.
        assertEquals(2, livePublicRatings(managerId),
            "both ratings remain publicly visible after both deletions");
    }

    @Test
    void theOppositeDeletionOrderWorksToo() throws Exception {
        UUID ada = insertUser("auth0|ada2");
        UUID bea = insertUser("auth0|bea2");
        insertLegacyReview(managerId, "Ada Lovelace", ada);
        insertLegacyReview(managerId, "Bea Clarke", bea);

        // Order must not matter. A fix that happened to work one way round would be a coincidence.
        await(users.deleteWithReviewAnonymization(bea));
        await(users.deleteWithReviewAnonymization(ada));

        assertEquals(0, countRows("users WHERE auth0_id IN ('auth0|ada2','auth0|bea2')"));
        assertEquals(2, livePublicRatings(managerId));
    }

    @Test
    void aThirdAndFourthIdenticalRatingErasesJustAsWell() throws Exception {
        List<UUID> raters = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID u = insertUser("auth0|many-" + i);
            raters.add(u);
            insertLegacyReview(managerId, "Rater Number " + i, u);
        }

        // The index exempts erased rows entirely rather than permitting a second one, so there is
        // no new cap hiding behind the fix.
        for (UUID u : raters) {
            await(users.deleteWithReviewAnonymization(u));
        }

        assertEquals(0, countRows("users WHERE auth0_id LIKE 'auth0|many-%'"));
        assertEquals(4, livePublicRatings(managerId));
        assertEquals(4, countRows("reviews WHERE author_erased AND manager_id = " + managerId));
    }

    /**
     * ONE NAME PER ROW, not one per account, which is the property the byline change exists for.
     *
     * <p>Nothing covered this. Every other erasure test here gives an account a single rating, so
     * a per-account handle and a per-row handle are indistinguishable to all of them, and the
     * implementation could regress to one {@code UPDATE ... SET author = <one minted name>} with
     * the whole suite still green. That regression is attractive, too: it is simpler code than the
     * batch, and the batch comment in {@code UserRepository} exists precisely because the single
     * statement cannot produce a per-row value.
     *
     * <p>What a shared handle would cost: a reader of six profiles could see that one person rated
     * all six, which is a fact the erasure removed everywhere else. The account is gone, so the
     * link is no longer provable, but it is still visible, and "these six opinions are one
     * person's" is most of what the identity was.
     *
     * <p>Six ratings on six managers rather than six on one, because the legacy dedup index is
     * keyed on (author, manager_id, role) and six rows sharing an author on one manager could not
     * be inserted in the first place.
     */
    @Test
    void oneAccountsRatingsDoNotAllEraseToTheSameHandle() throws Exception {
        UUID prolific = insertUser("auth0|prolific");
        List<UUID> ratings = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            long subject = insertManager("Erasure Subject " + i, "erasure-subject-" + i);
            ratings.add(insertLegacyReview(subject, "Prolific Rater", prolific));
        }

        await(users.deleteWithReviewAnonymization(prolific));

        List<String> bylines = new ArrayList<>();
        for (UUID r : ratings) {
            bylines.add(reviewRow(r).getString("author"));
        }
        ErasedByline.assertNotAllIdentical(bylines, "six ratings from one erased account");

        // The opinions all survive. Varying the byline is not allowed to cost a contribution, and
        // author_erased still goes on every row: that flag, not the name, is what holds them out
        // of the legacy dedup index.
        assertEquals(0, countWhere("users", "id = '" + prolific + "'"));
        assertEquals(6, countRows("reviews WHERE author_erased AND user_id IS NULL"));
    }

    @Test
    void deletingAnAccountTwiceIsHarmless() throws Exception {
        UUID ada = insertUser("auth0|ada3");
        UUID adasRating = insertLegacyReview(managerId, "Ada Lovelace", ada);

        await(users.deleteWithReviewAnonymization(ada));
        // A retried request, or two tabs. The account is already gone, so every statement matches
        // nothing and the call is a no-op rather than an error.
        await(users.deleteWithReviewAnonymization(ada));

        assertEquals(0, countWhere("users", "id = '" + ada + "'"));
        ErasedByline.assertMinted(reviewRow(adasRating).getString("author"),
            "the rating after a retried deletion");
        assertEquals(1, livePublicRatings(managerId));
        assertTrue(reviewRow(adasRating).getBoolean("author_erased"),
            "and the row is still exempt from the dedup key, not re-entered by the second pass");
    }

    @Test
    void anActiveDuplicateLegacyAuthorIsStillRefused() throws Exception {
        insertLegacyReview(managerId, "Cy Dunn", null);

        // THE PROTECTION THAT MUST NOT HAVE BEEN WEAKENED. V107 exempts ERASED rows only; two
        // ACTIVE legacy ratings sharing an author string for the same manager, title and employer
        // are still refused, including whitespace and case variants.
        for (String variant : List.of("  cy dunn  ", "CY DUNN", "Cy Dunn")) {
            Exception dup = assertThrows(Exception.class,
                () -> insertLegacyReview(managerId, variant, null),
                "expected a refusal for author variant: " + variant);
            assertTrue(message(dup).contains("idx_reviews_author_manager_role_legacy"),
                variant + " -> " + message(dup));
        }
    }

    // -- the serialized response, inspected ------------------------------------
    //
    // These assert on the ACTUAL JSON a caller receives, built by the same public serialiser the
    // endpoint uses, from a row fetched by the same repository query the endpoint issues. The
    // reason they look at the response rather than at the column is that every previous leak in
    // this area was a serialisation leak: the column was correct and something published it anyway.

    @Test
    void theSerializedResponseCarriesNoPeriodForAnAnonymisedRating() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selection(role, contributor)));
        await(pool.preparedQuery(
                "UPDATE reviews SET worked_from = '2023-03-01'::date, "
                + "worked_until = '2023-06-01'::date WHERE id = $1")
            .execute(Tuple.of(out.reviewId())));

        // Before: an ordinary rating publishes its period, so the test is measuring a change.
        JsonObject before = publicJsonFor(out.reviewId());
        assertNotNull(before.getValue("workedFrom"), "fixture: the period is published normally");

        await(users.deleteWithReviewAnonymization(contributor));

        JsonObject after = publicJsonFor(out.reviewId());

        // ALL THREE. effectiveWorkedUntil is the one that leaks if forgotten, because it is the
        // value the profile actually renders: masking the first two alone publishes the end date
        // through the back door.
        for (String field : List.of("workedFrom", "workedUntil", "effectiveWorkedUntil")) {
            assertNull(after.getValue(field),
                field + " must not appear in a public response for an anonymised rating, got: "
                + after.getValue(field));
        }

        // The opinion is still there, which is the point of anonymising rather than deleting.
        assertNotNull(after.getValue("overallRating"));
        // The byline in the PUBLIC body matters more than the one in the row: this is the value a
        // reader actually sees, and it is the last place the erasure could leak through.
        ErasedByline.assertMinted(after.getString("author"), "the public response");

        // And no date survives anywhere in the serialized body under another name. A field added
        // later that happens to carry the period fails this without anybody remembering to extend
        // the list above.
        String body = after.encode();
        assertFalse(body.contains("2023-03-01"), "the start date appears in the response: " + body);
        assertFalse(body.contains("2023-06-01"), "the end date appears in the response: " + body);
    }

    @Test
    void theSerializedResponseStillCarriesThePeriodForAnOrdinaryRating() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selection(role, contributor)));
        await(pool.preparedQuery(
                "UPDATE reviews SET worked_from = '2023-03-01'::date, "
                + "worked_until = '2023-06-01'::date WHERE id = $1")
            .execute(Tuple.of(out.reviewId())));

        // The rule applies to ratings that have been anonymised and to NOTHING else. A privacy
        // change that quietly blanked everybody's dates would be a different product.
        JsonObject json = publicJsonFor(out.reviewId());
        assertEquals("2023-03-01", json.getString("workedFrom"));
        assertEquals("2023-06-01", json.getString("workedUntil"));
    }

    @Test
    void aClaimsPublicViewHasNoPathToTheContributorsPeriod() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        RoleAwareSubmissionService.Outcome out = await(submissions.submit(
            selectionWithPeriod(role, contributor, "2023-03-01", "2023-06-01")));
        Row claim = claimRow(out.claimId());

        // THE RECONSTRUCTION PATH. role_claims.start_date and end_date are the contributor's OWN
        // asserted period, and the claim is reachable from the rating by claim_id. Hiding the
        // rating's dates achieves nothing if a new endpoint publishes the claim behind it.
        JsonObject pub = ClaimView.publicJson(claim);
        for (String field : ClaimView.FIELDS_NEVER_PUBLIC) {
            assertFalse(pub.containsKey(field),
                field + " must not be in a claim's public view, got: " + pub.encode());
        }
        String body = pub.encode();
        assertFalse(body.contains("2023-03-01"), body);
        assertFalse(body.contains("2023-06-01"), body);
        assertFalse(body.contains(contributor.toString()),
            "nor may the contributor be named: " + body);

        // What the public view DOES carry is which employment the rating concerns, which is what a
        // public surface legitimately needs.
        assertEquals(managerId, (long) pub.getLong("managerId"));
        assertEquals(role, (long) pub.getLong("resolvedRoleId"));

        // And a moderator still gets the period, because deciding whether two people describe one
        // employment is impossible without it. The requirement was never that the dates stop
        // existing, only that they stop being published.
        JsonObject adm = ClaimView.adminJson(claim);
        assertEquals("2023-03-01", adm.getString("startDate"));
        assertEquals(contributor, adm.getValue("userId"));
    }

    @Test
    void theManagersOwnEmploymentHistoryStaysPublic() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selection(role, contributor)));

        await(users.deleteWithReviewAnonymization(contributor));

        // career_history records when the MANAGER held the job, not when a contributor worked under
        // them. They are different facts, and hiding the first would conceal the employment record
        // the whole redesign exists to build. Asserted so that a later over-correction is caught.
        assertNotNull(roleRow(role).getLocalDate("start_date"));
        assertEquals("accepted", roleRow(role).getString("state"));
        assertEquals(role, claimRow(out.claimId()).getLong("resolved_role_id"),
            "and the rating still says which employment it concerns");
    }

    // -- author_erased: who may set it, and on what ----------------------------
    //
    // The CHECK says an erased rating holds no account. It does NOT say that only a legitimate
    // erasure can set the flag, and those are different claims. These tests are the second one.
    //
    // THE DISTINCTION THAT MATTERS: a rating ERASED by account deletion is not the same as a
    // rating that was ANONYMOUS from the beginning. RMM has plenty of the latter, and they must
    // keep their duplicate protection.

    @Test
    void theFlagCannotBeSetOnARatingThatNeverHadAnAccount() throws Exception {
        // An anonymous-from-the-start legacy rating: no account, a generated pseudonym.
        UUID anon = insertLegacyReview(managerId, "WarmWolf61", null);

        Exception e = assertThrows(Exception.class, () -> await(pool.preparedQuery(
            "UPDATE reviews SET author_erased = TRUE WHERE id = $1").execute(Tuple.of(anon))));
        assertTrue(message(e).contains("only be set while severing the account"), message(e));

        // So it keeps its duplicate protection, which is the whole point: exempting
        // account-less ratings wholesale would remove the protection from the rows the
        // author-string index was built for.
        assertFalse(reviewRow(anon).getBoolean("author_erased"));
        Exception dup = assertThrows(Exception.class,
            () -> insertLegacyReview(managerId, "warmwolf61", null));
        assertTrue(message(dup).contains("idx_reviews_author_manager_role_legacy"), message(dup));
    }

    @Test
    void theFlagCannotBeSetWhileTheAccountStaysAttached() throws Exception {
        UUID owned = insertLegacyReview(managerId, "Ada Lovelace", contributor);

        // Exempting a rating from duplicate protection while its owner is still named on it would
        // be an exemption with no erasure behind it at all.
        Exception e = assertThrows(Exception.class, () -> await(pool.preparedQuery(
            "UPDATE reviews SET author_erased = TRUE WHERE id = $1").execute(Tuple.of(owned))));
        assertTrue(message(e).contains("only be set while severing the account"), message(e));
        assertEquals(contributor, reviewRow(owned).getUUID("user_id"));
    }

    @Test
    void theFlagIsSetOnlyInTheSameStatementThatSeversTheAccount() throws Exception {
        UUID owned = insertLegacyReview(managerId, "Ada Lovelace", contributor);

        // THE PERMITTED SHAPE: a transition, not a value the caller may choose. FALSE to TRUE in
        // the same statement that takes the account off the row. actor_is_erased() cannot be used
        // for this, unlike everywhere else in V105, because the write happens BEFORE the users row
        // is deleted - anonymisation is step one of the deletion and the delete is step four.
        //
        // The shape alone is NOT sufficient, and must be performed in a transaction that actually
        // deletes the account: the deferred check asks at commit. Both halves are exercised
        // separately by anArbitraryUpdateCannotManufactureAnErasureWhileTheAccountExists.
        await(pool.withTransaction(tx -> tx.preparedQuery(
                "UPDATE reviews SET user_id = NULL, author_erased = TRUE, "
                + "author = 'Anonymous User' WHERE id = $1")
            .execute(Tuple.of(owned))
            .compose(v -> tx.preparedQuery("DELETE FROM users WHERE id = $1")
                .execute(Tuple.of(contributor)))));
        assertTrue(reviewRow(owned).getBoolean("author_erased"));

        // And it cannot be undone: an erased row re-entering the index could then collide with
        // another erased row on the same key, which is the defect V107 exists to remove.
        Exception restore = assertThrows(Exception.class, () -> await(pool.preparedQuery(
            "UPDATE reviews SET author_erased = FALSE WHERE id = $1").execute(Tuple.of(owned))));
        assertTrue(message(restore).contains("cannot be restored"), message(restore));
    }

    @Test
    void aRatingCannotBeCreatedAlreadyErased() throws Exception {
        // No account has been severed by an INSERT, so the flag has nothing it could legitimately
        // mean on a new row. Without this, a caller could mint pre-exempted ratings.
        Exception e = assertThrows(Exception.class, () -> await(pool.preparedQuery(
                "INSERT INTO reviews (manager_id, user_id, author, overall_rating, "
                + "manager_company, manager_title, text, author_erased) "
                + "VALUES ($1,NULL,'Sneaky',4.0,'Halverson Logistics','Dispatch Lead','x',TRUE)")
            .execute(Tuple.of(managerId))));
        assertTrue(message(e).contains("cannot be created already erased"), message(e));
    }

    @Test
    void theRealDeletionPathSetsTheFlagAndAnAnonymousRatingNearbyKeepsItsProtection()
            throws Exception {
        UUID anon  = insertLegacyReview(managerId, "WarmWolf61", null);
        UUID owned = insertLegacyReview(managerId, "Ada Lovelace", contributor);

        await(users.deleteWithReviewAnonymization(contributor));

        // The authorized path does set it, so the authorization is not so tight that the real
        // operation cannot perform it. That is the failure mode of a guard like this.
        assertTrue(reviewRow(owned).getBoolean("author_erased"));
        ErasedByline.assertMinted(reviewRow(owned).getString("author"),
            "the rating erased through the authorized path");

        // The bystanding anonymous rating is untouched, and still protected.
        assertFalse(reviewRow(anon).getBoolean("author_erased"));
        assertEquals("WarmWolf61", reviewRow(anon).getString("author"));
        assertEquals(2, livePublicRatings(managerId));
    }

    @Test
    void theBackfillClaimsOnlyRatingsErasedByTheOldCode() throws Exception {
        // The migration's backfill predicate is author = 'Anonymous User' AND user_id IS NULL.
        // This asserts the premise that makes it safe: an anonymous-from-the-start rating never
        // carries that byline, because the forms hand anonymous contributors a minted handle of
        // the Adjective + Animal + two digits shape instead.
        UUID anon = insertLegacyReview(managerId, "WarmWolf61", null);
        assertFalse(reviewRow(anon).getBoolean("author_erased"),
            "a generated pseudonym is not the erasure byline, so the backfill cannot claim it");

        // A RATING ERASED BY THE CURRENT PATH IS OUT OF THE BACKFILL'S REACH TWICE OVER, and it
        // used to be only once. The migration excludes it with `AND NOT author_erased`, which is
        // still the guard that matters because the flag is what the backfill is trying to add.
        // On top of that the current path no longer writes the literal at all, so the predicate's
        // first half misses the row as well.
        //
        // This is the half of the test the byline change rewrote. It previously asserted that a
        // freshly erased rating DOES carry 'Anonymous User', which was the premise that the one
        // string identified the erasure path. That premise is now the opposite: the string
        // identifies only rows erased by the OLD code, which is exactly what makes it a safe
        // backfill predicate for rows the flag cannot find.
        UUID owned = insertLegacyReview(managerId, "Ada Lovelace", contributor);
        await(users.deleteWithReviewAnonymization(contributor));
        ErasedByline.assertMinted(reviewRow(owned).getString("author"),
            "a rating erased by the current path");
        assertTrue(reviewRow(owned).getBoolean("author_erased"));

        // AND THE LITERAL SURVIVES AS LEGACY DATA, which is the whole reason the migration has a
        // backfill. A row erased before author_erased existed carries the byline and not the flag,
        // and it is the only population the predicate is allowed to claim.
        UUID legacyErased = insertLegacyReview(managerId, ErasedByline.OLD_DISCLOSING_LITERAL, null);
        assertFalse(reviewRow(legacyErased).getBoolean("author_erased"),
            "fixture check: an old-code erasure has the byline and no flag, so the backfill is "
            + "what has to give it one");
    }

    @Test
    void anArbitraryUpdateCannotManufactureAnErasureWhileTheAccountExists() throws Exception {
        UUID owned = insertLegacyReview(managerId, "Ada Lovelace", contributor);

        // THE AUTHORIZATION QUESTION, as distinct from the shape question.
        //
        // The immediate trigger proves the TRANSITION looks like an erasure: FALSE to TRUE in the
        // same statement that severs the account. It does NOT prove the update WAS one. With only
        // that trigger, this succeeded while the account was still there, which was demonstrated
        // against a real database rather than argued about.
        //
        // A session flag would establish nothing, because any caller can set_config. Column
        // privileges plus a SECURITY DEFINER entry point would, but only against a NON-OWNER
        // application role, and this application connects as the table owner.
        //
        // What works without any role separation: a DEFERRED constraint trigger asks at COMMIT
        // whether the account is actually gone, so the flag cannot be set except by a transaction
        // that deletes the account.
        //
        // STATED ACCURATELY, because the stronger claim is wrong: this is a data-integrity
        // safeguard against ordinary DML, not a security boundary against a privileged database
        // administrator, who can simply disable or drop the trigger. And it proves the account was
        // deleted in THIS TRANSACTION, not that the deletion was AUTHORIZED - that is the job of
        // application authorization and database credentials.
        Exception forged = assertThrows(Exception.class, () -> await(pool.withTransaction(tx ->
            tx.preparedQuery("UPDATE reviews SET user_id = NULL, author_erased = TRUE WHERE id = $1")
                .execute(Tuple.of(owned)))));
        assertTrue(message(forged).contains("still exists at commit"),
            "expected the deferred authorization check to refuse a forged erasure, got: "
            + message(forged));

        // Nothing was committed, so the rating still belongs to its author.
        assertEquals(contributor, reviewRow(owned).getUUID("user_id"));
        assertFalse(reviewRow(owned).getBoolean("author_erased"));
    }

    @Test
    void theOnlyWayToSetTheFlagIsToDeleteTheAccount() throws Exception {
        UUID owned = insertLegacyReview(managerId, "Ada Lovelace", contributor);

        // The same statement the forgery used, in a transaction that DOES delete the account. It
        // passes, which is the other half of the proof: the control is not so tight that the real
        // operation cannot perform it.
        await(pool.withTransaction(tx ->
            tx.preparedQuery("UPDATE reviews SET user_id = NULL, author_erased = TRUE, "
                    + "author = 'Anonymous User' WHERE id = $1")
                .execute(Tuple.of(owned))
                .compose(v -> tx.preparedQuery("DELETE FROM users WHERE id = $1")
                    .execute(Tuple.of(contributor)))));

        assertTrue(reviewRow(owned).getBoolean("author_erased"));
        assertEquals(0, countWhere("users", "id = '" + contributor + "'"));
    }

    // -- atomicity ------------------------------------------------------------

    @Test
    void aFailureAtTheDeleteLeavesTheContributorCompletelyIntact() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selection(role, contributor)));

        // Inject a failure at the LAST statement, which is where the real defect lived. The
        // trigger is created and dropped inside this test; it stands in for whatever the next
        // unanticipated blocker turns out to be, because the property under test is that there is
        // no half-way state, not that any particular blocker is gone.
        await(pool.query("""
                CREATE OR REPLACE FUNCTION t_block_user_delete() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'injected: deletion blocked'; END;
                $$ LANGUAGE plpgsql
                """).execute());
        await(pool.query("CREATE TRIGGER t_block_delete BEFORE DELETE ON users "
            + "FOR EACH ROW EXECUTE FUNCTION t_block_user_delete()").execute());
        try {
            Exception failed = assertThrows(Exception.class,
                () -> await(users.deleteWithReviewAnonymization(contributor)));
            assertTrue(message(failed).contains("injected"), message(failed));

            // THE REGRESSION. Before the correction the anonymisation had already committed by this
            // point, so the person's ratings were severed from them while their account survived -
            // and no retry could undo it, because the identity it needed was gone.
            Row review = reviewRow(out.reviewId());
            assertEquals(contributor, review.getUUID("user_id"),
                "the rating must still belong to them after a failed deletion");
            assertEquals("Ash B", review.getString("author"), "and still carry their byline");
            assertEquals(contributor, claimRow(out.claimId()).getUUID("user_id"));
            assertEquals(1, countWhere("users", "id = '" + contributor + "'"));
            assertEquals(1, await(opportunities.spent(pool, contributor, "manager", managerId)));
        } finally {
            await(pool.query("DROP TRIGGER t_block_delete ON users").execute());
            await(pool.query("DROP FUNCTION t_block_user_delete()").execute());
        }

        // And with the blocker gone it succeeds, so the failure left a RETRYABLE state rather than
        // a corrupt one. That is the whole point of making it one transaction.
        await(users.deleteWithReviewAnonymization(contributor));
        assertEquals(0, countWhere("users", "id = '" + contributor + "'"));
    }

    // -- the line the erasure must not cross ----------------------------------

    @Test
    void appendOnlyHistoryStillRefusesEverythingExceptTheErasure() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        RoleAwareSubmissionService.Outcome out =
            await(submissions.submit(selection(role, contributor)));
        assertTrue(countRows("claim_resolution_events") > 0,
            "fixture: resolving the claim writes an audit event by itself");

        // An arbitrary edit to history: still refused. Permitting the erasure must not have
        // permitted anything else.
        Exception tamper = assertThrows(Exception.class, () -> await(pool.query(
            "UPDATE claim_resolution_events SET reason = 'tampered'").execute()));
        assertTrue(message(tamper).contains("append-only"), message(tamper));

        // A DELETE: still refused, always.
        Exception del = assertThrows(Exception.class, () -> await(pool.query(
            "DELETE FROM claim_resolution_events").execute()));
        assertTrue(message(del).contains("append-only"), message(del));

        // Transferring the actor to somebody else: refused. This is the shape that is always an
        // attack, and it is the reason the permitted shape is "cleared", not "changed".
        Exception transfer = assertThrows(Exception.class, () -> await(pool.preparedQuery(
            "UPDATE claim_resolution_events SET \"by\" = $1").execute(Tuple.of(admin))));
        assertTrue(message(transfer).contains("append-only"), message(transfer));

        // And an INSERT with no actor is still refused now that NOT NULL is dropped, so widening
        // the column did not make "nobody did this" representable.
        //
        // WHICH mechanism refuses it changed with V109, and what it proves did not. The CHECK
        // correction_actor_absent_is_withdrawn catches (NULL, FALSE), which is this insert, but it
        // is satisfied by (NULL, TRUE) - so a row could be born already-withdrawn and name nobody
        // with every constraint satisfied. V109 added a BEFORE INSERT trigger covering both, and a
        // trigger runs before a CHECK, so this now fails with the trigger's sentence rather than
        // the constraint's name. Either refusal is the guarantee; the assertion no longer insists
        // on the route, because the route is an implementation detail and the refusal is not.
        Exception noActor = assertThrows(Exception.class, () -> await(pool.preparedQuery(
                "INSERT INTO claim_corrections (claim_id, field, was, now_is, reason, corrected_by) "
                + "VALUES ($1,'t','a','b','r',NULL)")
            .execute(Tuple.of(out.claimId()))));
        assertTrue(message(noActor).contains("must name the actor")
                   || message(noActor).contains("actor_absent_is_withdrawn"), message(noActor));

        // The hole V109 actually closed: the same insert, declaring itself already withdrawn.
        Exception bornWithdrawn = assertThrows(Exception.class, () -> await(pool.preparedQuery(
                "INSERT INTO claim_corrections (claim_id, field, was, now_is, reason, "
                + "corrected_by, actor_withdrawn) VALUES ($1,'t','a','b','r',NULL,TRUE)")
            .execute(Tuple.of(out.claimId()))));
        assertTrue(message(bornWithdrawn).contains("must name the actor"), message(bornWithdrawn));

        // The erasure itself: permitted.
        await(users.deleteWithReviewAnonymization(contributor));
        assertEquals(0, countWhere("users", "id = '" + contributor + "'"));
    }

    // -- privacy, derived from the catalogue rather than from a hand-written list ----

    @Test
    void afterDeletionNoRowAnywhereStillNamesTheDeletedContributor() throws Exception {
        long role = insertRole(managerId, "Dispatch Lead", "2024-01-15", "accepted");
        await(submissions.submit(selection(role, contributor)));
        insertOpportunity(contributor, otherManagerId, 1, "2026-01-01T00:00:00Z");
        await(merges.mergeManagers(managerId, otherManagerId, admin));

        UUID deleted = contributor;
        await(users.deleteWithReviewAnonymization(deleted));

        // SCHEMA-DRIVEN, and that is the point: a hand-written list of tables is how two
        // identity-reconstruction defects survived review. Every uuid column in the database is
        // asked whether it still holds this id, so a table added later fails this without anybody
        // remembering to extend the test.
        List<String> holders = await(pool.preparedQuery("""
                SELECT c.table_name, c.column_name
                  FROM information_schema.columns c
                 WHERE c.table_schema = 'public' AND c.data_type = 'uuid'
                 ORDER BY 1, 2
                """).execute().map(rs -> {
                    List<String> out = new ArrayList<>();
                    for (Row r : rs) out.add(r.getString("table_name") + "." + r.getString("column_name"));
                    return out;
                }));

        List<String> stillNaming = new ArrayList<>();
        for (String col : holders) {
            String[] parts = col.split("\\.");
            long n = await(pool.preparedQuery(
                    "SELECT count(*) AS c FROM " + parts[0] + " WHERE " + parts[1] + " = $1")
                .execute(Tuple.of(deleted)).map(rs -> rs.iterator().next().getLong("c")));
            if (n > 0) stillNaming.add(col);
        }

        assertEquals(List.of(), stillNaming,
            "a deleted contributor must not be named anywhere, in any uuid column, including by a "
            + "two-hop path through a table that carries no contributor column of its own");
    }

    /**
     * Erasing the moderator who determined an employment status.
     *
     * <h2>Why this is not already covered</h2>
     *
     * <p>{@link #afterDeletionNoRowAnywhereStillNamesTheDeletedContributor()} is schema-driven and
     * asks every uuid column in the database, so it covers these two columns the moment they
     * exist. It covers them VACUOUSLY, though: its fixture submits a rating, and a status resolved
     * from a contributor's claim names no human at all, so neither
     * {@code career_history.status_resolved_by} nor {@code employment_status_events."by"} ever
     * holds the id being erased.
     *
     * <p>This fixture makes a human the actor, which is the only way those columns are ever
     * populated, and then asserts both halves of the rule: the identity goes and the DECISION
     * stays. An audit trail that lost its findings when somebody closed their account would be no
     * audit trail, and one that kept the name would not be an erasure.
     */
    @Test
    void erasingTheModeratorClearsTheActorAndKeepsTheDetermination() throws Exception {
        UUID moderator = insertUser("auth0|erased-moderator");
        long role = insertRole(managerId, "Dispatch Lead", "2021-03-01", "accepted");
        await(pool.preparedQuery("""
                UPDATE career_history
                   SET employment_status = 'ended', status_basis = 'moderator',
                       status_as_of = ('2023-07-31'::date)::timestamp AT TIME ZONE 'UTC',
                       status_resolved_at = now(), status_resolved_by = $2
                 WHERE id = $1
                """).execute(Tuple.of(role, moderator)));

        assertEquals(1, countWhere("employment_status_events", "\"by\" = '" + moderator + "'"),
            "fixture: the trigger recorded the determination and named them on it");

        await(users.deleteWithReviewAnonymization(moderator));

        assertEquals(0, countWhere("employment_status_events", "\"by\" = '" + moderator + "'"),
            "the event no longer names them. ON DELETE SET NULL performs an UPDATE, and the "
            + "append-only trigger permits exactly that one shape and no other");
        assertEquals(1, countWhere("employment_status_events", "role_id = " + role),
            "and the event itself survives: what was decided about this employment is still on "
            + "the record, which is the whole purpose of an append-only log");
        assertEquals("ended|moderator|2023-07-31", await(pool.preparedQuery("""
                SELECT employment_status || '|' || status_basis || '|'
                       || status_as_of::date::text AS s
                  FROM career_history WHERE id = $1
                """).execute(Tuple.of(role)).map(rs -> rs.iterator().next().getString("s"))),
            "the determination stands. Erasing who recorded a fact does not erase the fact, and a "
            + "role falling back to Unknown because an administrator closed their account would "
            + "be an account deletion silently changing what the public sees");
        assertEquals(0, countWhere("career_history", "status_resolved_by = '" + moderator + "'"),
            "while the employment record no longer names them either");
    }

    // -- fixtures --------------------------------------------------------------

    private UUID insertCompanyReview(UUID user) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO company_reviews (company_id, user_id, author, overall_rating, "
                + "work_life_balance, compensation_benefits, career_growth, job_security, "
                + "workload_sustainability, senior_leadership, company_communication, "
                + "flexibility, inclusion_belonging, tools_resources, worked_from) "
                + "VALUES ($1,$2,'Ash B',4.0,4,4,4,4,4,4,4,4,4,4,'2024-01-01'::date) RETURNING id")
            .execute(Tuple.of(companyId, user))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private UUID insertInterviewReview(UUID user) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO interview_reviews (company_id, user_id, author, overall_rating, "
                + "outcome, interview_year) "
                + "VALUES ($1,$2,'Ash B',4.0,'offer',2025) RETURNING id")
            .execute(Tuple.of(companyId, user))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private Row companyReviewRow(UUID id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM company_reviews WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private Row interviewReviewRow(UUID id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM interview_reviews WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private String dispositionOf(UUID reviewId) throws Exception {
        return reviewRow(reviewId).getString("disposition");
    }

    /** The database's own answer on whether company_stats_live still agrees with its sources. */
    private long driftCount() throws Exception {
        return await(pool.query("SELECT COUNT(*) AS c FROM company_stats_detect_drift()").execute()
            .map(rs -> rs.iterator().next().getLong("c")));
    }

    private RoleAwareSubmissionService.Submission selectionFor(long roleId, UUID user) {
        double[] cats = new double[10];
        java.util.Arrays.fill(cats, 4.0);
        return new RoleAwareSubmissionService.Submission(
            UUID.randomUUID(), 1, managerId, companyId, "Depot Lead", roleId,
            null, null, false, null, "Halverson Logistics", "Bea C", user,
            4.0, cats, OffsetDateTime.parse("2024-02-01T00:00:00Z"));
    }

    private Row roleRow(long id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM career_history WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    /** Ratings a visitor would see: live, not deleted, not an expired placeholder. */
    private int livePublicRatings(long manager) throws Exception {
        return await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM reviews WHERE manager_id = $1 AND deleted_at IS NULL "
                + "AND disposition = 'live' "
                + "AND (weight = FALSE OR weight_expires_on IS NULL "
                + "     OR weight_expires_on > CURRENT_DATE)")
            .execute(Tuple.of(manager))
            .map(rs -> rs.iterator().next().getLong("c").intValue()));
    }

    /**
     * The public response body for one rating.
     *
     * <p>The row comes from the repository query the endpoint issues, and the JSON from the public
     * serialiser the endpoint uses, so this is the response body rather than a paraphrase of it.
     * Viewer id null: the signed-out public view, which is the one that must not leak.
     */
    private JsonObject publicJsonFor(UUID reviewId) throws Exception {
        var reviews = new org.werkpages.repository.ReviewRepository(pool);
        var rows = await(reviews.findByManager(managerId, 50, 0, "newest", null));
        for (Row r : rows) {
            if (reviewId.equals(r.getUUID("id"))) {
                return org.werkpages.service.ManagerService.buildReviewJson(r, null);
            }
        }
        throw new AssertionError("rating not returned by the public query at all: " + reviewId);
    }

    private RoleAwareSubmissionService.Submission selection(long roleId, UUID user) {
        return selectionOn(managerId, roleId, user);
    }

    private RoleAwareSubmissionService.Submission selectionOn(long manager, long roleId, UUID user) {
        double[] cats = new double[10];
        java.util.Arrays.fill(cats, 4.0);
        return new RoleAwareSubmissionService.Submission(
            UUID.randomUUID(), 1, manager, companyId, "Dispatch Lead", roleId,
            null, null, false, null, "Halverson Logistics", "Ash B", user,
            4.0, cats, OffsetDateTime.parse("2024-02-01T00:00:00Z"));
    }

    /**
     * A selection that asserts an employment PERIOD, submitted rather than patched on afterwards.
     *
     * <p>These tests used to finalize the claim and then UPDATE start_date, end_date and
     * period_known onto it. Those are assertion columns, so V108 refuses it. The period now
     * arrives the way production delivers it and the dates are identical.
     */
    private RoleAwareSubmissionService.Submission selectionWithPeriod(
            long roleId, UUID user, String from, String until) {
        double[] cats = new double[10];
        java.util.Arrays.fill(cats, 4.0);
        return new RoleAwareSubmissionService.Submission(
            UUID.randomUUID(), 1, managerId, companyId, "Dispatch Lead", roleId,
            java.time.LocalDate.parse(from), java.time.LocalDate.parse(until), true, null,
            "Halverson Logistics", "Ash B", user,
            4.0, cats, OffsetDateTime.parse("2024-02-01T00:00:00Z"));
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

    private UUID insertLegacyReview(long manager, String author, UUID user) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO reviews (manager_id, user_id, author, overall_rating, "
                + "manager_company, manager_title, text) "
                + "VALUES ($1,$2,$3,4.0,'Halverson Logistics','Dispatch Lead','legacy') "
                + "RETURNING id")
            .execute(Tuple.of(manager, user, author))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private long insertOpportunity(UUID user, long subjectId, int ordinal, String openedAtIso)
            throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO rating_opportunities (user_id, subject_type, subject_id, ordinal, "
                + "opened_at) VALUES ($1,'manager',$2,$3,$4) RETURNING id")
            .execute(Tuple.of(user, subjectId, (short) ordinal, OffsetDateTime.parse(openedAtIso)))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private Row claimRow(long id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM role_claims WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private Row reviewRow(UUID id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM reviews WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private Row soleRow(String table) throws Exception {
        return await(pool.query("SELECT * FROM " + table).execute().map(rs -> {
            assertEquals(1, rs.rowCount(), "expected exactly one row in " + table);
            return rs.iterator().next();
        }));
    }

    private long soleClaimId() throws Exception {
        return await(pool.query("SELECT id FROM role_claims").execute().map(rs -> {
            assertEquals(1, rs.rowCount(), "expected exactly one claim");
            return rs.iterator().next().getLong("id");
        }));
    }

    private Integer reviewsCountOf(long manager) throws Exception {
        return await(pool.preparedQuery("SELECT reviews_count FROM managers WHERE id = $1")
            .execute(Tuple.of(manager))
            .map(rs -> rs.iterator().next().getInteger("reviews_count")));
    }

    private long countRows(String table) throws Exception {
        return await(pool.query("SELECT COUNT(*) AS c FROM " + table).execute()
            .map(rs -> rs.iterator().next().getLong("c")));
    }

    private long countWhere(String table, String where) throws Exception {
        return await(pool.query("SELECT COUNT(*) AS c FROM " + table + " WHERE " + where).execute()
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
