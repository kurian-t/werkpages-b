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
import org.werkpages.service.ManagerMergeService;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PHASE 3: the manager merge and the role merge, rebuilt.
 *
 * <h2>What these tests are for</h2>
 *
 * <p>Rev 268's merge transaction has been executed before, as SQL in a standalone harness. That
 * proved the statements, their order, their locks and their refusals. It could not prove any of
 * this, because none of it existed: the actual Java, against the actual migrated schema, through
 * the component the admin endpoint actually calls. A transaction can satisfy every assertion in a
 * harness and still write the wrong column from application code.
 *
 * <p>So everything here goes through {@link ManagerMergeService} and reads the committed rows back.
 *
 * <h2>The promise being tested</h2>
 *
 * <p><b>Nothing a person wrote is deleted, and nothing is silently removed from view.</b> That is
 * one claim with several halves: ratings move, roles move, claims move, a rating that cannot stay
 * live is held rather than deleted and says what it collided with, and the contributor's own
 * choices - which person they picked, which role they picked - are never rewritten.
 */
@Testcontainers
class ManagerMergeIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_merge")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static ManagerMergeService merges;
    static org.werkpages.repository.UserRepository users;

    long companyId;
    long survivor;
    long absorbed;
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
        merges = new ManagerMergeService(pool);
        users  = new org.werkpages.repository.UserRepository(pool);
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        // Leaf tables first. A single CASCADE on managers holds the managers lock and then wants
        // the children, which deadlocks against anything fire-and-forget still in flight.
        // The phase 2 tables first, and each batch names every table that references the ones in
        // it: TRUNCATE refuses to leave a referencing table behind. capture_attempts and
        // captured_drafts belong in the SECOND batch, because managers.capture_token points at
        // the first and captured_drafts.manager_id at managers.
        await(pool.query("TRUNCATE review_supersessions, role_conflict_reviews, "
            + "role_conflict_claims, role_conflicts, claim_resolution_events, claim_corrections, "
            + "rating_opportunity_retirements, manager_merge_events").execute());
        await(pool.query("TRUNCATE reviews, role_claims, rating_opportunities, career_history, "
            + "capture_attempts, captured_drafts, company_stats_live, managers, companies, users "
            + "CASCADE").execute());
        companyId = insertCompany("Halverson Logistics");
        survivor  = insertManager("Priya Raghunathan", "priya-r");
        absorbed  = insertManager("P. Raghunathan", "p-rag");
        admin     = insertUser("auth0|merge-admin");
    }

    // ── the plain cases ───────────────────────────────────────────────────────

    @Test
    void aManagerWithNoEmploymentHistoryMergesAndTheRatingsFollow() throws Exception {
        UUID one = insertLegacyReview(absorbed, "Halverson Logistics", "Dispatch Lead", "Ash B", null);
        UUID two = insertLegacyReview(absorbed, "Halverson Logistics", "Depot Lead", "Cal C", null);

        JsonObject m = merge();

        assertEquals(2, m.getInteger("moved"));
        assertEquals(0, m.getInteger("rolesMoved"));
        assertEquals(survivor, managerOf(one));
        assertEquals(survivor, managerOf(two));
        assertEquals("rejected", statusOf(absorbed), "the absorbed row is retired, never deleted");
        assertEquals(survivor, (long) mergedIntoOf(absorbed));
    }

    @Test
    void employmentHistoryWithNoEquivalentMovesUntouched() throws Exception {
        long role = insertRole(absorbed, "Night Supervisor", "2021-04-01T00:00:00Z");

        merge();

        Row r = roleRow(role);
        assertEquals(survivor, (long) r.getLong("manager_id"), "the job came with the person");
        assertEquals("accepted", r.getString("state"), "and it is untouched otherwise");
        assertNull(r.getLong("superseded_by"));
    }

    @Test
    void anEquivalentRoleIsSupersededANDMovedRatherThanLeftBehind() throws Exception {
        // Equivalence is exactly what career_unique_known_employment would refuse: same employer,
        // same normalised title, same canonical day.
        long keptRole     = insertRole(survivor, "Dispatch Lead", "2020-01-01T00:00:00Z");
        long absorbedRole = insertRole(absorbed, "  dispatch   LEAD! ", "2020-01-01T00:00:00Z");

        JsonObject m = merge();

        assertEquals(1, m.getInteger("rolesSuperseded"));
        Row gone = roleRow(absorbedRole);
        assertEquals("superseded", gone.getString("state"));
        assertEquals(keptRole, (long) gone.getLong("superseded_by"), "it names its replacement");
        // The half that is easy to get wrong. Retiring it without moving manager_id leaves it
        // owned by the absorbed profile while the claims pointing at it have moved to the
        // survivor, which claim_resolved_role_matches refuses outright.
        assertEquals(survivor, (long) gone.getLong("manager_id"),
            "a retired role is still that person's history, and the person is now the survivor");
        assertFalse(gone.getBoolean("rateable"));
    }

    @Test
    void twoUnknownPeriodRolesAreNotTreatedAsTheSameEmployment() throws Exception {
        // The narrowest defensible rule, and a deliberate refusal to guess: the unique index does
        // not cover unknown-period rows, so two of them can legitimately coexist. Calling them
        // equivalent would merge employment records the database is content to keep apart.
        long a = insertRoleWithUnknownPeriod(survivor, "Depot Lead");
        long b = insertRoleWithUnknownPeriod(absorbed, "Depot Lead");

        JsonObject m = merge();

        assertEquals(0, m.getInteger("rolesSuperseded"), "neither supersedes the other");
        assertEquals(1, m.getInteger("rolesMoved"));
        assertEquals("accepted", roleRow(a).getString("state"));
        assertEquals("accepted", roleRow(b).getString("state"));
        assertEquals(survivor, (long) roleRow(b).getLong("manager_id"));
    }

    // ── claims ────────────────────────────────────────────────────────────────

    @Test
    void everyClaimMovesIncludingTheUnresolvedOnes() throws Exception {
        // An unresolved claim on the absorbed manager is still an assertion about that person,
        // and that person is now the survivor. The previous reading moved only resolved claims.
        long unresolved = insertClaim(absorbed, "Dispatch Lead", insertUser("auth0|c-unresolved"));
        long role       = insertRole(absorbed, "Depot Lead", "2019-02-01T00:00:00Z");
        long resolved   = insertClaimSelecting(absorbed, "Depot Lead", insertUser("auth0|c-resolved"), role);
        resolveClaim(resolved, role);

        JsonObject m = merge();

        assertEquals(2, m.getInteger("claimsMoved"));
        assertEquals(survivor, (long) claimRow(unresolved).getLong("manager_id"));
        assertNull(claimRow(unresolved).getString("resolution"), "still unresolved, still queued");
        assertEquals(survivor, (long) claimRow(resolved).getLong("manager_id"));
    }

    @Test
    void aClaimOnASupersededRoleIsRedirectedAndTheRedirectionIsAudited() throws Exception {
        long keptRole     = insertRole(survivor, "Dispatch Lead", "2020-01-01T00:00:00Z");
        long absorbedRole = insertRole(absorbed, "Dispatch Lead", "2020-01-01T00:00:00Z");
        UUID author = insertUser("auth0|redirected");
        long claim  = insertClaimSelecting(absorbed, "Dispatch Lead", author, absorbedRole);
        resolveClaim(claim, absorbedRole);
        UUID rating = insertClaimedReview(absorbed, claim, "Dee D");

        JsonObject m = merge();

        assertEquals(1, m.getInteger("claimsRedirected"));
        Row c = claimRow(claim);
        assertEquals(keptRole, (long) c.getLong("resolved_role_id"), "it resolves to the survivor's role");
        assertEquals(absorbedRole, (long) c.getLong("canonicalized_from"),
            "and it records which role it came off");
        assertEquals(admin, c.getUUID("canonicalized_by"));
        assertNotNull(c.getOffsetDateTime("canonicalized_at"));
        // The contributor's own decision is never rewritten. This is the assertion that has to
        // survive any number of merges.
        assertEquals(absorbedRole, (long) c.getLong("selected_role_id"),
            "the role the contributor PICKED is untouched");
        assertEquals(absorbed, (long) c.getLong("original_manager_id"),
            "and so is the profile they picked it against");
        assertEquals("live", dispositionOf(rating), "its rating keeps publishing");

        // The audit is written by the trigger, derived from the transition, and stamped with the
        // merge that caused it.
        Row event = lastEventFor(claim);
        assertEquals("redirected", event.getString("event"));
        assertEquals(absorbedRole, (long) event.getLong("from_role_id"));
        assertEquals(keptRole,     (long) event.getLong("to_role_id"));
        assertEquals("manager_merge", event.getString("reason"));
        assertEquals(admin, event.getUUID("by"),
            "the administrator doing the MERGE, not whoever first resolved the claim");
        assertNotNull(event.getLong("merge_event_id"), "and it names the merge");
    }

    @Test
    void aContributorWhoAlreadyRatedTheEquivalentRoleHasTheirSecondClaimHeldNotDeleted() throws Exception {
        long keptRole     = insertRole(survivor, "Dispatch Lead", "2020-01-01T00:00:00Z");
        long absorbedRole = insertRole(absorbed, "Dispatch Lead", "2020-01-01T00:00:00Z");
        UUID author = insertUser("auth0|double-rater");

        long keeper = insertClaimSelecting(survivor, "Dispatch Lead", author, keptRole);
        resolveClaim(keeper, keptRole);
        UUID keeperRating = insertClaimedReview(survivor, keeper, "Same Person");

        long loser = insertClaimSelecting(absorbed, "Dispatch Lead", author, absorbedRole);
        resolveClaim(loser, absorbedRole);
        UUID loserRating = insertClaimedReview(absorbed, loser, "Same Person");

        JsonObject m = merge();

        // The claim cannot be redirected: role_claims_one_per_contributor_per_role forbids one
        // contributor holding two claims on one role. So it is marked duplicate and UNLINKED, and
        // it must NAME its target - claim_duplicate_names_its_target refuses a null one outright.
        Row l = claimRow(loser);
        assertEquals("duplicate", l.getString("resolution"));
        assertNull(l.getLong("resolved_role_id"), "nothing points at a superseded role");
        assertEquals(keeper, (long) l.getLong("duplicate_of_claim_id"), "it names what it duplicates");

        assertEquals("live", dispositionOf(keeperRating), "the rating already published stays public");
        assertEquals("held", dispositionOf(loserRating), "the second one is held");
        assertNull(reviewRow(loserRating).getOffsetDateTime("deleted_at"), "and NOT deleted");
        assertEquals(1, m.getInteger("ratingsHeld"));

        // The audit records the transition the first design could not express: a source role and
        // deliberately no destination.
        assertEquals("unlinked", lastEventFor(loser).getString("event"));

        // And the moderator gets a task, with the roles that let this transaction commit at all.
        Row task = await(pool.query(
                "SELECT id, kind, blocks_publication FROM role_conflicts ORDER BY id DESC LIMIT 1")
            .execute().map(rs -> rs.iterator().next()));
        assertEquals("duplicate_rating_on_merge", task.getString("kind"));
        assertTrue(task.getBoolean("blocks_publication"));
        assertEquals("subject",  roleInTask(task.getLong("id"), loser),
            "the held rating's claim is the SUBJECT");
        assertEquals("evidence", roleInTask(task.getLong("id"), keeper),
            "the live one is EVIDENCE: linking it as a subject would make the predicate refuse a "
            + "rating the merge deliberately kept live, and the whole transaction would fail");
    }

    @Test
    void aRejectedRolesClaimAndRatingSurviveTheMerge() throws Exception {
        long rejected = insertRole(absorbed, "Depot Lead", "2018-01-01T00:00:00Z");
        long claim    = insertClaimSelecting(absorbed, "Depot Lead", insertUser("auth0|rejected-role"), rejected);
        resolveClaim(claim, rejected);
        UUID rating = insertClaimedReview(absorbed, claim, "Eve E");
        // Held first, because a rating against a rejected role may not be live - that is the
        // publication predicate, not the merge.
        await(pool.preparedQuery("UPDATE reviews SET disposition = 'held', gate_eligible = FALSE, "
                + "live_since = NULL WHERE id = $1").execute(Tuple.of(rating)).mapEmpty());
        await(pool.preparedQuery("UPDATE career_history SET state = 'rejected' WHERE id = $1")
            .execute(Tuple.of(rejected)).mapEmpty());

        merge();

        assertEquals(survivor, (long) roleRow(rejected).getLong("manager_id"));
        assertEquals("rejected", roleRow(rejected).getString("state"), "the rejection stands");
        assertEquals(survivor, (long) claimRow(claim).getLong("manager_id"));
        assertEquals(survivor, managerOf(rating));
        assertEquals("held", dispositionOf(rating), "still held, and still there");
    }

    // ── legacy ratings ────────────────────────────────────────────────────────

    @Test
    void twoLegacyRatingsByOneAccountCollideAndBothArePreserved() throws Exception {
        UUID author = insertUser("auth0|legacy-collide");
        UUID earlier = insertLegacyReview(survivor, "Halverson Logistics", "Dispatch Lead", "Same Name", author);
        UUID later   = insertLegacyReview(absorbed, "Halverson Logistics", "Dispatch Lead", "Same Name", author);

        JsonObject m = merge();

        assertEquals("live", dispositionOf(earlier),
            "the rating already published on the surviving profile is never retracted");
        assertEquals("held", dispositionOf(later));
        assertEquals(survivor, managerOf(later), "the loser moves with everything else");
        assertNull(reviewRow(later).getOffsetDateTime("deleted_at"));
        assertEquals(earlier, reviewRow(later).getUUID("merge_duplicate_of"));
        assertEquals("held_duplicate", reviewRow(later).getString("legacy_dedup_state"),
            "the mark is what excludes it from the narrowed legacy unique index, which does not "
            + "mention disposition and would otherwise refuse the move outright");
        assertEquals(1, m.getInteger("supersessions"));

        Row s = await(pool.query("SELECT * FROM review_supersessions ORDER BY id DESC LIMIT 1")
            .execute().map(rs -> rs.iterator().next()));
        assertEquals("duplicate_on_merge", s.getString("cause"));
        assertTrue(s.getBoolean("superseded_row_kept"), "KEPT is the point");
        assertEquals(earlier, s.getUUID("replaced_by"));
        assertNotNull(s.getLong("merge_event_id"));
    }

    @Test
    void theLoserIsAlwaysTheAbsorbedSideEvenWhenItIsTheOlderRating() throws Exception {
        // An earlier rule picked the earlier of the two, which could hold a rating already live on
        // the surviving profile. A merge must never retract a published rating.
        UUID author = insertUser("auth0|older-absorbed");
        UUID old = insertLegacyReview(absorbed, "Halverson Logistics", "Dispatch Lead", "One Name", author);
        await(pool.preparedQuery("UPDATE reviews SET created_at = now() - interval '2 years' WHERE id = $1")
            .execute(Tuple.of(old)).mapEmpty());
        UUID recent = insertLegacyReview(survivor, "Halverson Logistics", "Dispatch Lead", "One Name", author);

        merge();

        assertEquals("live", dispositionOf(recent), "the survivor's rating stays public");
        assertEquals("held", dispositionOf(old), "even though it is the older contribution");
    }

    @Test
    void aSharedDisplayNameCollidesThroughTheAuthorIndexAndIsRecordedAsWeakerEvidence() throws Exception {
        // The author-keyed index has NO user_id predicate, so two DIFFERENT accounts sharing a
        // display name collide in it. A detector that required equal user ids missed exactly this.
        UUID one = insertUser("auth0|shared-name-1");
        UUID two = insertUser("auth0|shared-name-2");
        UUID keeper = insertLegacyReview(survivor, "Halverson Logistics", "Dispatch Lead", "J. Smith", one);
        UUID loser  = insertLegacyReview(absorbed, "Halverson Logistics", "Dispatch Lead", "j. smith", two);

        merge();

        assertEquals("held", dispositionOf(loser), "it still collides, through the author index");
        assertEquals(keeper, reviewRow(loser).getUUID("merge_duplicate_of"));
        assertEquals("live", dispositionOf(keeper));
    }

    @Test
    void oneAbsorbedRatingCollidingWithTwoSurvivorRatingsGetsOneDeterministicKeeper() throws Exception {
        // One absorbed rating can collide with DIFFERENT survivor ratings through the two indexes
        // at once: the user index with one, the author index with another. A detector that assumed
        // independent pairs could match a loser twice and PostgreSQL would pick an arbitrary
        // keeper.
        UUID account = insertUser("auth0|two-edges");
        UUID other   = insertUser("auth0|two-edges-other");
        // The two survivor ratings must NOT collide with each other, or the legacy index refuses
        // the fixture itself: different accounts and different author strings. The absorbed rating
        // then reaches one through the USER index and the other through the AUTHOR index.
        UUID byAccount = insertLegacyReview(survivor, "Halverson Logistics", "Dispatch Lead", "Alias One", account);
        await(pool.preparedQuery("UPDATE reviews SET created_at = now() - interval '1 year' WHERE id = $1")
            .execute(Tuple.of(byAccount)).mapEmpty());
        UUID byName = insertLegacyReview(survivor, "Halverson Logistics", "Dispatch Lead", "Alias Two", other);
        UUID loser = insertLegacyReview(absorbed, "Halverson Logistics", "Dispatch Lead", "Alias Two", account);

        merge();

        assertEquals("held", dispositionOf(loser));
        assertEquals(byAccount, reviewRow(loser).getUUID("merge_duplicate_of"),
            "the earliest surviving rating it collides with - reached through the USER index - "
            + "chosen deterministically rather than whichever row the planner reached first, "
            + "even though it also collides with " + byName + " through the AUTHOR index");
        assertEquals(1, countSupersessionsFor(loser), "and recorded exactly once");
    }

    @Test
    void aWithdrawnRatingIsNotTreatedAsALegacyDuplicate() throws Exception {
        // role_basis, not role_required. role_required = FALSE is true of a withdrawn rating too,
        // and the legacy indexes were narrowed to role_basis = 'legacy' for exactly that reason. A
        // detector keyed differently from the indexes it enforces would hold a withdrawn rating
        // against a legacy one it cannot collide with in either index - and restoration preserves
        // an existing hold deliberately, so the rating would never come back.
        long role = insertRole(absorbed, "Dispatch Lead", "2020-06-01T00:00:00Z");
        UUID author = insertUser("auth0|withdrawn-vs-legacy");
        UUID legacy = insertLegacyReview(survivor, "Halverson Logistics", "Dispatch Lead", "Withdrawn Author", author);
        UUID withdrawn = insertWithdrawnReview(absorbed, role, "Withdrawn Author");

        merge();

        assertEquals("live", dispositionOf(legacy));
        assertEquals("indexed", reviewRow(withdrawn).getString("legacy_dedup_state"),
            "a withdrawn rating is never marked a legacy duplicate");
        assertNull(reviewRow(withdrawn).getUUID("merge_duplicate_of"));
    }

    // ── The detector must be keyed identically to the index (V111 parity) ─────

    /**
     * A PRE-EXISTING MISMATCH, found by the V111 index-parity check and not by a failure.
     *
     * <p>V107 added {@code NOT author_erased} to {@code idx_reviews_author_manager_role_legacy},
     * so that two people who rated one manager identically could both delete their accounts: the
     * second erasure collided with the first on the byline 'Anonymous User'. An erased rating is
     * therefore OUT of the index and cannot collide with anything.
     *
     * <p>{@link ManagerMergeService#reconcileLegacyCollisions} never gained that predicate. So a
     * merge paired a live rating with an erased one, held the absorbed side, took a published
     * rating off the surface and filed a {@code review_supersessions} row saying it was a
     * duplicate on merge - for a collision that could not have happened. V102's own comment warns
     * about exactly this shape: "a detector keyed differently from the indexes it enforces would
     * hold a withdrawn rating against a legacy one it cannot collide with".
     *
     * <p>The erasure runs through the real path, because {@code author_erased} cannot be set any
     * other way: V107's trigger refuses it while the account still exists.
     */
    @Test
    void aRatingIsNotHeldAgainstAnAuthorWhoseAccountWasErased() throws Exception {
        UUID gone  = insertUser("auth0|erased-vs-live");
        UUID stays = insertUser("auth0|erased-vs-live-other");
        UUID erased = insertLegacyReview(survivor, "Halverson Logistics", "Dispatch Lead",
            "Same Byline", gone);
        UUID live   = insertLegacyReview(absorbed, "Halverson Logistics", "Dispatch Lead",
            "Same Byline", stays);

        await(users.deleteWithReviewAnonymization(gone));
        assertTrue(reviewRow(erased).getBoolean("author_erased"),
            "fixture check: the erasure flag is what takes it out of the index");

        // THE TWO BYLINES HAVE TO MATCH WHEN THE DETECTOR RUNS, or this test proves nothing.
        //
        // Both rows were inserted under one byline so that moving the absorbed rating onto the
        // survivor would be a genuine collision in idx_reviews_author_manager_role_legacy, leaving
        // the erasure exemption as the only reason no supersession is filed. The erasure now mints
        // a fresh handle on the row it anonymises, so by this point the two authors differ and the
        // collision the test is built around cannot happen for a reason that has nothing to do
        // with the exemption. The assertions below would pass on a detector that ignored
        // author_erased entirely.
        //
        // The fixture previously used the literal 'Anonymous User' for both, which worked only
        // because erasure used to write that same string back. Re-reading the minted byline and
        // giving it to the live row restores the premise without depending on what mint() picked.
        // Safe against the index itself: the two rows are still on different managers until the
        // merge, which is the whole scenario.
        String mintedByline = reviewRow(erased).getString("author");
        ErasedByline.assertMinted(mintedByline, "the erased rating");
        await(pool.preparedQuery("UPDATE reviews SET author = $1 WHERE id = $2")
            .execute(Tuple.of(mintedByline, live)).mapEmpty());
        assertEquals(mintedByline, reviewRow(live).getString("author"),
            "fixture check: the live rating now shares the erased one's byline, so only the "
            + "exemption can stop the detector pairing them");

        merge();

        assertEquals("live", dispositionOf(live),
            "the surviving rating cannot collide with an erased one, because an erased rating is "
            + "not in the index at all. Holding it retracts a published opinion for nothing");
        assertNull(reviewRow(live).getUUID("merge_duplicate_of"));
        assertEquals(0, countSupersessionsFor(live),
            "and no supersession record may claim it was a duplicate on merge");
    }

    /**
     * V111 part 4's counterpart in the detector.
     *
     * <p>A rating carrying a {@code deleted_reason} is never coming back - a moderator removed it,
     * a merge superseded a seed, or its own author replaced it - so V111 takes it out of the
     * legacy dedup index and it can no longer collide with anything. The detector has to agree, or
     * a merge holds a live rating against a permanently dead one.
     */
    @Test
    void aRatingIsNotHeldAgainstAPermanentlyDeletedOne() throws Exception {
        UUID one = insertUser("auth0|dead-vs-live");
        UUID two = insertUser("auth0|dead-vs-live-other");
        UUID dead = insertLegacyReview(survivor, "Halverson Logistics", "Dispatch Lead",
            "Same Byline", one);
        UUID live = insertLegacyReview(absorbed, "Halverson Logistics", "Dispatch Lead",
            "Same Byline", two);

        await(pool.preparedQuery(
                "UPDATE reviews SET deleted_at = now(), deleted_reason = 'junk' WHERE id = $1")
            .execute(Tuple.of(dead)).mapEmpty());

        merge();

        assertEquals("live", dispositionOf(live),
            "a rating that can never return holds no slot, so there is nothing to collide with");
        assertNull(reviewRow(live).getUUID("merge_duplicate_of"));
    }

    /**
     * THE RESERVATION V111 PART 4 DELIBERATELY KEEPS, which is the whole reason its predicate is
     * {@code deleted_reason IS NULL} rather than {@code deleted_at IS NULL}.
     *
     * <p>A rating withdrawn by its own author carries no reason and <b>comes back after three
     * days</b>. It keeps its slot in the index while it waits, so that an identical rating cannot
     * be inserted meanwhile and make the restoration sweep - one bulk UPDATE - abort for its whole
     * batch. It can therefore still genuinely collide after a merge, and the detector must still
     * see it.
     *
     * <p>This asserts the behaviour part 4 must NOT have changed.
     */
    @Test
    void aWithdrawnRatingAwaitingRestorationStillReservesItsDedupSlot() throws Exception {
        UUID one = insertUser("auth0|awaiting-vs-live");
        UUID two = insertUser("auth0|awaiting-vs-live-other");
        UUID keeper = insertLegacyReview(survivor, "Halverson Logistics", "Dispatch Lead",
            "Held Byline", one);
        UUID waiting = insertLegacyReview(absorbed, "Halverson Logistics", "Dispatch Lead",
            "Held Byline", two);

        // The withdrawal shape: deleted_at set, user_id severed, NO reason.
        await(pool.preparedQuery(
                "UPDATE reviews SET deleted_at = now(), user_id = NULL WHERE id = $1")
            .execute(Tuple.of(waiting)).mapEmpty());

        merge();

        assertEquals(keeper, reviewRow(waiting).getUUID("merge_duplicate_of"),
            "it is coming back in three days, so its slot is still reserved and the collision is "
            + "real. Excluding it here would let the sweep fail on restoration instead");
        assertEquals("live", dispositionOf(keeper));
    }

    @Test
    void aWithdrawnRatingFollowsTheCanonicalization() throws Exception {
        // It has no claim, so the claim redirection cannot reach it, and one left pointing at a
        // superseded role is attributed to a role nothing else uses.
        long keptRole     = insertRole(survivor, "Dispatch Lead", "2020-01-01T00:00:00Z");
        long absorbedRole = insertRole(absorbed, "Dispatch Lead", "2020-01-01T00:00:00Z");
        UUID withdrawn = insertWithdrawnReview(absorbed, absorbedRole, "Gone Author");

        merge();

        assertEquals(keptRole, (long) reviewRow(withdrawn).getLong("withdrawn_role_id"),
            "it is repointed at the surviving role");
        assertEquals(survivor, managerOf(withdrawn));
    }

    // ── identity, audit, idempotency ──────────────────────────────────────────

    @Test
    void theMergeEventRecordsWhatHappenedAndWhoDidIt() throws Exception {
        insertRole(absorbed, "Night Supervisor", "2021-04-01T00:00:00Z");
        insertLegacyReview(absorbed, "Halverson Logistics", "Depot Lead", "Ann A", null);

        JsonObject m = merge();

        Row e = await(pool.preparedQuery("SELECT * FROM manager_merge_events WHERE id = $1")
            .execute(Tuple.of(m.getLong("mergeEventId"))).map(rs -> rs.iterator().next()));
        assertEquals(survivor, (long) e.getLong("survivor_id"));
        assertEquals(absorbed, (long) e.getLong("absorbed_id"));
        assertEquals("P. Raghunathan", e.getString("absorbed_name"),
            "the name at merge time, so lineage does not depend on that row surviving unchanged");
        assertEquals("p-rag", e.getString("absorbed_slug"));
        assertEquals(admin, e.getUUID("merged_by"));
        assertEquals(1, (int) e.getInteger("roles_moved"));
        assertEquals(1, (int) e.getInteger("ratings_moved"));
    }

    @Test
    void mergingTheSamePairTwiceIsRefusedRatherThanRepeated() throws Exception {
        insertLegacyReview(absorbed, "Halverson Logistics", "Depot Lead", "Ann A", null);
        merge();

        // The second attempt has nothing to move, and it must not retire the survivor or write a
        // second event for the same absorption. Repeating one suggestion used to take both rows
        // out of the directory.
        JsonObject second = await(merges.mergeManagers(survivor, absorbed, admin));
        assertEquals(0, second.getInteger("moved"));
        assertEquals("approved", statusOf(survivor), "the survivor is still live");
        assertEquals(survivor, (long) mergedIntoOf(absorbed));
    }

    @Test
    void aChainOfMergesKeepsEveryContributorsOriginalChoice() throws Exception {
        long third = insertManager("Raghunathan P", "rag-p");
        long role  = insertRole(absorbed, "Depot Lead", "2019-02-01T00:00:00Z");
        UUID author = insertUser("auth0|chain");
        long claim = insertClaimSelecting(absorbed, "Depot Lead", author, role);
        resolveClaim(claim, role);

        await(merges.mergeManagers(survivor, absorbed, admin));
        await(merges.mergeManagers(third, survivor, admin));

        Row c = claimRow(claim);
        assertEquals(third, (long) c.getLong("manager_id"), "canonical identity follows the chain");
        assertEquals(absorbed, (long) c.getLong("original_manager_id"),
            "and lineage still names the profile the contribution ARRIVED against, which is the "
            + "thing merged_into cannot answer after three profiles become one");
        assertEquals(role, (long) c.getLong("selected_role_id"));
    }

    // ── failure, concurrency, aggregates ──────────────────────────────────────

    @Test
    void aFailureAnywhereLeavesNothingBehind() throws Exception {
        long role = insertRole(absorbed, "Night Supervisor", "2021-04-01T00:00:00Z");
        UUID rating = insertLegacyReview(absorbed, "Halverson Logistics", "Depot Lead", "Ann A", null);

        // A merge into a manager that does not exist: the lock in step 2 returns one row, and the
        // transaction refuses before writing anything. The merge event is written early, by
        // design, so this is also the proof that an early write does not survive a late refusal.
        assertThrows(Exception.class, () -> await(merges.mergeManagers(999_999L, absorbed, admin)));

        assertEquals(absorbed, managerOf(rating), "the rating did not move");
        assertEquals(absorbed, (long) roleRow(role).getLong("manager_id"), "nor did the role");
        assertEquals("approved", statusOf(absorbed), "and the profile was not retired");
        assertEquals(0L, countRows("manager_merge_events"), "no half-written audit");
    }

    @Test
    void aConcurrentSubmissionAgainstTheAbsorbedManagerDoesNotStrandARating() throws Exception {
        // Rev 268's race table: "a merge runs while a rating is written against the absorbed
        // manager -> the rating lands on the survivor, or the merge waits. No orphan."
        insertLegacyReview(absorbed, "Halverson Logistics", "Depot Lead", "Ann A", null);

        Future<JsonObject> mergeF = merges.mergeManagers(survivor, absorbed, admin);
        Future<UUID> writeF = pool.withTransaction(conn -> conn.preparedQuery(
                "INSERT INTO reviews (manager_id, author, overall_rating, manager_company, "
                + "manager_title, text) VALUES ($1,$2,4.0,'Halverson Logistics','Depot Lead','x') "
                + "RETURNING id")
            .execute(Tuple.of(absorbed, "Racer " + UUID.randomUUID()))
            .map(rs -> rs.iterator().next().getUUID("id")));

        await(mergeF);
        UUID raced = await(writeF);

        // Whichever order they committed in, nothing may be left pointing at a retired profile
        // with no way back: findByIdFollowingMerges resolves the absorbed id to the survivor, so
        // either outcome is reachable. What must NOT happen is a rating on a manager that no
        // longer exists.
        long landed = managerOf(raced);
        assertTrue(landed == survivor || landed == absorbed,
            "it landed on one of the two, not nowhere");
        assertEquals(survivor, (long) mergedIntoOf(absorbed),
            "and the absorbed profile still points at the survivor, so the rating is reachable");
    }

    @Test
    void bothManagersAggregatesAreRecalculatedNotJustTheSurvivors() throws Exception {
        // The old merge recalculated the survivor only, AFTER the transaction. So the absorbed
        // profile kept the cached figures it had before losing every rating it owned.
        insertLegacyReview(absorbed, "Halverson Logistics", "Depot Lead", "Ann A", null);
        insertLegacyReview(absorbed, "Halverson Logistics", "Depot Lead", "Bob B", null);
        await(pool.preparedQuery(
                "UPDATE managers SET reviews_count = 2, overall_rating = 4.0 WHERE id = $1")
            .execute(Tuple.of(absorbed)).mapEmpty());

        merge();

        assertEquals(2, (int) managerRow(survivor).getInteger("reviews_count"),
            "the survivor counts what it now holds");
        assertEquals(0, (int) managerRow(absorbed).getInteger("reviews_count"),
            "and the absorbed profile no longer claims ratings it does not have");
    }

    @Test
    void aHeldRatingIsNotCountedInTheSurvivorsFigures() throws Exception {
        UUID author = insertUser("auth0|held-not-counted");
        insertLegacyReview(survivor, "Halverson Logistics", "Dispatch Lead", "Twice Over", author);
        insertLegacyReview(absorbed, "Halverson Logistics", "Dispatch Lead", "Twice Over", author);

        merge();

        assertEquals(1, (int) managerRow(survivor).getInteger("reviews_count"),
            "two rows, one opinion in public, and the figure says one");
    }

    @Test
    void movingARatingWithoutItsClaimIsRefusedByTheDatabase() throws Exception {
        /*
          RED-BEFORE, preserved as a test rather than as a claim.

          This is the statement the OLD merge ran: UPDATE reviews SET manager_id, touching neither
          role_claims nor career_history. reviews_claim_is_the_managers is DEFERRABLE INITIALLY
          IMMEDIATE, so once any rating carries a claim that statement is refused outright. It is
          why the merge had to be rebuilt before role-bearing ratings go live, and this test fails
          if anybody reintroduces the shortcut.
        */
        long role  = insertRole(absorbed, "Depot Lead", "2019-02-01T00:00:00Z");
        long claim = insertClaimSelecting(absorbed, "Depot Lead", insertUser("auth0|composite"), role);
        resolveClaim(claim, role);
        insertClaimedReview(absorbed, claim, "Fay F");

        Exception e = assertThrows(Exception.class, () -> await(pool.preparedQuery(
                "UPDATE reviews SET manager_id = $1 WHERE manager_id = $2")
            .execute(Tuple.of(survivor, absorbed)).mapEmpty()));
        assertTrue(String.valueOf(e.getMessage()).contains("reviews_claim_is_the_managers"),
            "the composite key refuses it. Got: " + e.getMessage());

        // And the real merge, which moves all three together behind deferred constraints, works.
        merge();
        assertEquals(survivor, (long) claimRow(claim).getLong("manager_id"));
    }

    @Test
    void anAnonymisedRatingSurvivesAMergeWithItsIdentityStillSevered() throws Exception {
        // Account deletion anonymises rather than deletes: user_id goes null and dates are hidden.
        // A merge must not resurrect the link or lose the rating.
        UUID author = insertUser("auth0|to-be-deleted");
        UUID rating = insertLegacyReview(absorbed, "Halverson Logistics", "Depot Lead", "Anon", author);
        await(pool.preparedQuery("UPDATE reviews SET user_id = NULL, dates_hidden = TRUE, "
                + "author = 'anonymous' WHERE id = $1").execute(Tuple.of(rating)).mapEmpty());
        await(pool.preparedQuery("DELETE FROM users WHERE id = $1").execute(Tuple.of(author)).mapEmpty());

        merge();

        Row r = reviewRow(rating);
        assertEquals(survivor, (long) r.getLong("manager_id"), "it moved");
        assertNull(r.getUUID("user_id"), "and its identity is still severed");
        assertTrue(r.getBoolean("dates_hidden"));
    }

    // ── the absorbed profile's moderation queue ───────────────────────────────

    @Test
    void anOpenTaskOnTheAbsorbedProfileIsFoldedIntoTheSurvivorsAndSubjectWins() throws Exception {
        // case_key is generated from manager_id, so an open task left on the absorbed id describes
        // an employment nobody can reach, while the next submission against the survivor opens a
        // SECOND task for the same dispute: a stale queue on one side and a duplicate on the
        // other.
        long survivorRole = insertRole(survivor, "Dispatch Lead", "2020-01-01T00:00:00Z");
        long absorbedRole = insertRole(absorbed, "Dispatch Lead", "2020-01-01T00:00:00Z");
        long claimA = insertClaimSelecting(survivor, "Dispatch Lead", insertUser("auth0|fold-a"), survivorRole);
        resolveClaim(claimA, survivorRole);
        long claimB = insertClaimSelecting(absorbed, "Dispatch Lead", insertUser("auth0|fold-b"), absorbedRole);
        resolveClaim(claimB, absorbedRole);

        // Two open tasks for what is, after the merge, one employment. Non-blocking, so neither
        // holds a rating and the fixture does not have to satisfy the publication predicate.
        long survivingTask = insertConflict(survivor, "overlapping_period", "dispatch lead", "2020-01-01");
        long absorbedTask  = insertConflict(absorbed, "overlapping_period", "dispatch lead", "2020-01-01");
        linkClaimToTask(survivingTask, claimA, "evidence");
        linkClaimToTask(absorbedTask,  claimB, "subject");
        linkClaimToTask(absorbedTask,  claimA, "evidence");

        JsonObject m = merge();

        assertEquals(1, m.getInteger("conflictsFolded"));
        Row folded = conflictRow(absorbedTask);
        assertEquals("merged", folded.getString("resolution"));
        assertEquals(survivingTask, (long) folded.getLong("superseded_by_conflict_id"),
            "the closed task says where it went rather than vanishing");
        assertEquals(survivor, (long) folded.getLong("manager_id"));
        assertNotNull(folded.getOffsetDateTime("resolved_at"));

        // SUBJECT WINS. claimB was the subject of the absorbed task and evidence nowhere; claimA
        // was evidence in both. The stronger statement about a rating must not be weakened by a
        // merge.
        assertEquals("subject",  roleInTask(survivingTask, claimB));
        assertEquals("evidence", roleInTask(survivingTask, claimA));
        // And the folded task keeps its own membership rows: role_conflict_claims is RESTRICT on
        // purpose, because the evidence of what was once asked outlives the queue item.
        assertEquals("subject", roleInTask(absorbedTask, claimB));
    }

    @Test
    void aTaskWithNoCounterpartOnTheSurvivorJustMoves() throws Exception {
        long role  = insertRole(absorbed, "Depot Lead", "2019-02-01T00:00:00Z");
        long claim = insertClaimSelecting(absorbed, "Depot Lead", insertUser("auth0|move-task"), role);
        resolveClaim(claim, role);
        long task = insertConflict(absorbed, "overlapping_period", "depot lead", "2019-02-01");
        linkClaimToTask(task, claim, "subject");

        JsonObject m = merge();

        assertEquals(0, m.getInteger("conflictsFolded"));
        assertEquals(1, m.getInteger("conflictsMoved"));
        Row moved = conflictRow(task);
        assertEquals(survivor, (long) moved.getLong("manager_id"));
        assertNull(moved.getOffsetDateTime("resolved_at"), "it is still open, and still queued");
    }

    @Test
    void aResolvedTaskIsRepointedTooSoTheSweepKeepsFindingIt() throws Exception {
        // Resolved tasks are history, and the denormalised manager_name keeps the name that was
        // decided about - but their case_key has to follow the survivor, or the sweep's
        // suppression rule stops finding them and reopens disputes a moderator has closed.
        long task = insertConflict(absorbed, "correction_candidate", "depot lead", "unknown");
        await(pool.preparedQuery("UPDATE role_conflicts SET resolved_at = now(), "
                + "resolution = 'dismissed' WHERE id = $1").execute(Tuple.of(task)).mapEmpty());

        merge();

        Row r = conflictRow(task);
        assertEquals(survivor, (long) r.getLong("manager_id"));
        assertEquals("dismissed", r.getString("resolution"), "the decision stands");
    }

    // ── the role merge ────────────────────────────────────────────────────────

    @Test
    void mergingTwoRolesOnOneManagerSupersedesOneAndRepointsItsClaims() throws Exception {
        long keep = insertRole(survivor, "Dispatch Lead", "2020-01-01T00:00:00Z");
        long gone = insertRoleWithUnknownPeriod(survivor, "Dispatch Lead");
        UUID author = insertUser("auth0|role-merge");
        long claim  = insertClaimSelecting(survivor, "Dispatch Lead", author, gone);
        resolveClaim(claim, gone);
        UUID rating = insertClaimedReview(survivor, claim, "Gil G");

        JsonObject m = await(merges.mergeRoles(survivor, keep, gone, admin));

        assertEquals(1, m.getInteger("rolesSuperseded"));
        assertEquals("superseded", roleRow(gone).getString("state"));
        assertEquals(keep, (long) roleRow(gone).getLong("superseded_by"));
        assertEquals(keep, (long) claimRow(claim).getLong("resolved_role_id"));
        assertEquals(gone, (long) claimRow(claim).getLong("selected_role_id"),
            "what the contributor picked is still what the contributor picked");
        assertEquals("live", dispositionOf(rating), "and the rating keeps publishing");
        assertEquals("redirected", lastEventFor(claim).getString("event"));
    }

    @Test
    void aRoleMergeAcrossEmployersIsRefused() throws Exception {
        long otherCompany = insertCompany("Westmere Freight");
        long keep = insertRole(survivor, "Dispatch Lead", "2020-01-01T00:00:00Z");
        long gone = await(pool.preparedQuery(
                "INSERT INTO career_history (manager_id, company, title, start_date, company_id, "
                + "period_known, state, source) VALUES ($1,'Westmere Freight','Dispatch Lead',"
                + "$2,$3,TRUE,'accepted','contributor') RETURNING id")
            .execute(Tuple.of(survivor, OffsetDateTime.parse("2020-01-01T00:00:00Z"), otherCompany))
            .map(rs -> rs.iterator().next().getLong("id")));

        Exception e = assertThrows(Exception.class,
            () -> await(merges.mergeRoles(survivor, keep, gone, admin)));
        assertTrue(String.valueOf(e.getMessage()).contains("different employers"),
            "the employer is part of an employment's identity, and merging across employers would "
            + "silently move a rating's attribution to another company. Got: " + e.getMessage());
        assertEquals("accepted", roleRow(gone).getString("state"), "nothing was changed");
    }

    @Test
    void aRoleMergeHoldsTheSecondClaimOfOneContributor() throws Exception {
        long keep = insertRole(survivor, "Dispatch Lead", "2020-01-01T00:00:00Z");
        long gone = insertRoleWithUnknownPeriod(survivor, "Dispatch Lead");
        UUID author = insertUser("auth0|role-merge-dup");

        long keeper = insertClaimSelecting(survivor, "Dispatch Lead", author, keep);
        resolveClaim(keeper, keep);
        UUID keeperRating = insertClaimedReview(survivor, keeper, "One Person");

        long loser = insertClaimSelecting(survivor, "Dispatch Lead", author, gone);
        resolveClaim(loser, gone);
        UUID loserRating = insertClaimedReview(survivor, loser, "One Person");

        await(merges.mergeRoles(survivor, keep, gone, admin));

        assertEquals("duplicate", claimRow(loser).getString("resolution"));
        assertEquals(keeper, (long) claimRow(loser).getLong("duplicate_of_claim_id"));
        assertEquals("live", dispositionOf(keeperRating));
        assertEquals("held", dispositionOf(loserRating));
        assertNull(reviewRow(loserRating).getOffsetDateTime("deleted_at"));
    }


    // -- the rating allowance, reconciled -------------------------------------
    //
    // PHASE 4, V104. These are the tests deferred in phase 3, when rating_opportunities had no
    // migration and the merge could not defer a constraint that did not exist.
    //
    // The policy under test: two opportunities per contributor per subject, and A MERGE MUST NOT
    // HAND ANYBODY A FRESH ALLOWANCE. Duplicate profiles are exactly how that would happen, so the
    // reconciliation ranks every opportunity across BOTH profiles by WHEN IT WAS OPENED and keeps
    // the earliest two. Which profile a row sat on is irrelevant, which is why a survivor's own
    // opportunity can be retired in favour of an earlier absorbed one.

    @Test
    void oneAndOneBothSurviveRankedByWhenTheyWereOpened() throws Exception {
        UUID contributor = insertUser("auth0|opp-1-1");
        insertOpportunity(contributor, survivor, 1, "2026-03-01T00:00:00Z");
        insertOpportunity(contributor, absorbed, 1, "2026-01-01T00:00:00Z");

        JsonObject m = merge();

        assertEquals(2, m.getInteger("opportunitiesMoved"));
        assertEquals(0, m.getInteger("opportunitiesRetired"));
        // The absorbed one was opened FIRST, so it takes ordinal 1 and the survivor's own row is
        // renumbered to 2. Nothing is retired: one plus one is inside the cap.
        assertEquals(List.of("1@2026-01-01", "2@2026-03-01"), opportunityShape(contributor, survivor));
        assertEquals(0, countRows("rating_opportunity_retirements"));
    }

    @Test
    void twoAndOneKeepsTheEarliestTwoAndRetiresTheSurvivorsOwnLatest() throws Exception {
        UUID contributor = insertUser("auth0|opp-2-1");
        // The survivor holds BOTH its opportunities already.
        insertOpportunity(contributor, survivor, 1, "2026-03-01T00:00:00Z");
        insertOpportunity(contributor, survivor, 2, "2026-05-01T00:00:00Z");
        // The absorbed profile's single opportunity predates both of them.
        insertOpportunity(contributor, absorbed, 1, "2026-01-01T00:00:00Z");

        JsonObject m = merge();

        assertEquals(1, m.getInteger("opportunitiesRetired"));
        // THE POINT OF THIS TEST. The ranking is by when the opportunity was opened, not by which
        // row was already the survivor's, so the survivor's own May opportunity is the one retired
        // and the absorbed January one takes ordinal 1. A reconciliation that kept "the survivor's
        // two" would be wrong here, and would also be the version that hands out a third.
        assertEquals(List.of("1@2026-01-01", "2@2026-03-01"), opportunityShape(contributor, survivor));

        Row retired = soleRetirement();
        assertEquals(survivor, retired.getLong("former_subject_id"),
            "the retired row came off the SURVIVOR, which is the case a naive merge gets wrong");
        assertEquals(2, retired.get(Short.class, "former_ordinal").intValue());
        assertEquals(survivor, retired.getLong("surviving_subject_id"));
        assertEquals("manager", retired.getString("subject_type"));
        assertEquals(m.getLong("mergeEventId"), retired.getLong("manager_merge_event_id"),
            "provenance names the merge that caused it");
        assertNull(retired.getUUID("company_merge_id"),
            "a manager merge carries no company merge id, and the CHECK enforces exactly one");
        assertEquals("over_cap_on_merge", retired.getString("reason"));
        assertEquals(contributor, retired.getUUID("user_id"));
    }

    @Test
    void twoAndTwoKeepsTheEarliestTwoAndRetiresTwo() throws Exception {
        UUID contributor = insertUser("auth0|opp-2-2");
        insertOpportunity(contributor, survivor, 1, "2026-01-01T00:00:00Z");
        insertOpportunity(contributor, survivor, 2, "2026-04-01T00:00:00Z");
        insertOpportunity(contributor, absorbed, 1, "2026-02-01T00:00:00Z");
        insertOpportunity(contributor, absorbed, 2, "2026-03-01T00:00:00Z");

        JsonObject m = merge();

        assertEquals(2, m.getInteger("opportunitiesRetired"));
        // January and February are the two earliest exercises across both profiles. One came from
        // each side, and the March and April rows are recorded rather than deleted.
        assertEquals(List.of("1@2026-01-01", "2@2026-02-01"), opportunityShape(contributor, survivor));
        assertEquals(2, countRows("rating_opportunity_retirements"));
        assertEquals(List.of("2026-03-01", "2026-04-01"), retiredOpenedDays());
    }

    @Test
    void zeroAndTwoIsAPureMoveRenumberedFromOne() throws Exception {
        UUID contributor = insertUser("auth0|opp-0-n");
        insertOpportunity(contributor, absorbed, 1, "2026-02-01T00:00:00Z");
        insertOpportunity(contributor, absorbed, 2, "2026-06-01T00:00:00Z");

        JsonObject m = merge();

        assertEquals(2, m.getInteger("opportunitiesMoved"));
        assertEquals(0, m.getInteger("opportunitiesRetired"));
        assertEquals(List.of("1@2026-02-01", "2@2026-06-01"), opportunityShape(contributor, survivor));
        assertEquals(0, countOpportunities(contributor, absorbed),
            "nothing is left behind on the absorbed subject, which is what makes a re-run a no-op");
    }

    @Test
    void twoAndZeroRewritesTheSameValuesAndRetiresNothing() throws Exception {
        UUID contributor = insertUser("auth0|opp-n-0");
        insertOpportunity(contributor, survivor, 1, "2026-02-01T00:00:00Z");
        insertOpportunity(contributor, survivor, 2, "2026-06-01T00:00:00Z");

        JsonObject m = merge();

        assertEquals(0, m.getInteger("opportunitiesRetired"));
        assertEquals(List.of("1@2026-02-01", "2@2026-06-01"), opportunityShape(contributor, survivor));
    }

    @Test
    void twoContributorsAreReconciledIndependently() throws Exception {
        UUID capped = insertUser("auth0|opp-capped");
        UUID spare  = insertUser("auth0|opp-spare");
        insertOpportunity(capped, survivor, 1, "2026-01-01T00:00:00Z");
        insertOpportunity(capped, survivor, 2, "2026-02-01T00:00:00Z");
        insertOpportunity(capped, absorbed, 1, "2026-03-01T00:00:00Z");
        insertOpportunity(spare,  absorbed, 1, "2026-03-01T00:00:00Z");

        merge();

        // The ranking partitions by contributor, so one contributor being over the cap cannot
        // retire another's opportunity.
        assertEquals(2, countOpportunities(capped, survivor));
        assertEquals(1, countOpportunities(spare, survivor));
        assertEquals(1, countRows("rating_opportunity_retirements"));
        assertEquals(capped, soleRetirement().getUUID("user_id"));
    }

    @Test
    void aRepeatedMergeChangesNothingAndRecordsNoSecondRetirement() throws Exception {
        UUID contributor = insertUser("auth0|opp-repeat");
        insertOpportunity(contributor, survivor, 1, "2026-03-01T00:00:00Z");
        insertOpportunity(contributor, survivor, 2, "2026-05-01T00:00:00Z");
        insertOpportunity(contributor, absorbed, 1, "2026-01-01T00:00:00Z");

        merge();
        List<String> afterFirst = opportunityShape(contributor, survivor);
        long retirementsAfterFirst = countRows("rating_opportunity_retirements");

        // IDEMPOTENT BY CONSTRUCTION, not by a provenance column. This is why the table needs no
        // identity: a re-run matches only the survivor's rows, reproduces the same rn from the same
        // (opened_at, id), finds nothing above 2, and rewrites the values already there. The three
        // columns proposed to make this idempotent each held a rating id beside a contributor id,
        // which recovers the author of an anonymised rating in one join.
        merge();

        assertEquals(afterFirst, opportunityShape(contributor, survivor));
        assertEquals(retirementsAfterFirst, countRows("rating_opportunity_retirements"),
            "the audit table is append-only, so a second run must insert nothing rather than "
            + "duplicating the record of one retirement");
    }

    @Test
    void retiringAnOpportunityDoesNotRetireARating() throws Exception {
        UUID contributor = insertUser("auth0|opp-ratings-stay");
        insertOpportunity(contributor, survivor, 1, "2026-03-01T00:00:00Z");
        insertOpportunity(contributor, survivor, 2, "2026-05-01T00:00:00Z");
        insertOpportunity(contributor, absorbed, 1, "2026-01-01T00:00:00Z");
        // Four historical ratings, two on each profile, all live.
        UUID a = insertLegacyReview(survivor, "Halverson Logistics", "Dispatch Lead", "Ash A", null);
        UUID b = insertLegacyReview(survivor, "Halverson Logistics", "Depot Lead", "Bea B", null);
        UUID c = insertLegacyReview(absorbed, "Halverson Logistics", "Night Lead", "Cal C", null);
        UUID d = insertLegacyReview(absorbed, "Halverson Logistics", "Yard Lead", "Dev D", null);

        merge();

        // STATED BECAUSE CONFLATING THEM WOULD CORRUPT THE FIGURES. The cap is on opportunities and
        // never on published ratings: rating_opportunities governs how many times somebody may
        // submit in FUTURE and says nothing about what is already published.
        assertEquals(1, countRows("rating_opportunity_retirements"));
        for (UUID r : List.of(a, b, c, d)) {
            assertEquals(survivor, managerOf(r));
            assertNull(deletedAtOf(r), "no rating is deleted by an opportunity being retired");
            assertEquals("live", dispositionOf(r), "and none is held by it either");
        }
    }

    @Test
    void theRetirementAuditRefusesEveryChangeExceptClearingTheContributor() throws Exception {
        UUID contributor = insertUser("auth0|opp-append-only");
        insertOpportunity(contributor, survivor, 1, "2026-03-01T00:00:00Z");
        insertOpportunity(contributor, survivor, 2, "2026-05-01T00:00:00Z");
        insertOpportunity(contributor, absorbed, 1, "2026-01-01T00:00:00Z");
        merge();
        long id = soleRetirement().getLong("id");

        // DELETE, always refused.
        Exception del = assertThrows(Exception.class, () -> await(pool.preparedQuery(
            "DELETE FROM rating_opportunity_retirements WHERE id = $1").execute(Tuple.of(id))));
        assertTrue(rootMessage(del).contains("append-only"), rootMessage(del));

        // Rewriting history, refused.
        Exception upd = assertThrows(Exception.class, () -> await(pool.preparedQuery(
            "UPDATE rating_opportunity_retirements SET reason = 'nothing to see' WHERE id = $1")
            .execute(Tuple.of(id))));
        assertTrue(rootMessage(upd).contains("append-only"), rootMessage(upd));

        // Transferring the identity to somebody else, refused. This is the shape that is always an
        // attack: an identity may be ADDED once and REMOVED once, and never moved.
        UUID someoneElse = insertUser("auth0|opp-not-them");
        Exception xfer = assertThrows(Exception.class, () -> await(pool.preparedQuery(
            "UPDATE rating_opportunity_retirements SET user_id = $2 WHERE id = $1")
            .execute(Tuple.of(id, someoneElse))));
        assertTrue(rootMessage(xfer).contains("append-only"), rootMessage(xfer));

        // Clearing it WHILE THE ACCOUNT STILL EXISTS: refused since V105, and deliberately.
        // Permitting the transition is not the same as authorizing it, and a trigger that allowed
        // any caller to null this column would let somebody erase a contributor from the audit
        // trail without deleting anything.
        Exception early = assertThrows(Exception.class, () -> await(pool.preparedQuery(
            "UPDATE rating_opportunity_retirements SET user_id = NULL WHERE id = $1")
            .execute(Tuple.of(id))));
        assertTrue(rootMessage(early).contains("deleted account"), rootMessage(early));

        // Clearing it once the account is GONE: permitted, and the one exception. Erasing an
        // account has to be able to finish, and this table has no foreign key to cascade through:
        // a cascade would have been an UPDATE, which fires this very trigger, which is how erasure
        // was found incomplete in the first place.
        await(pool.preparedQuery("DELETE FROM users WHERE id = $1").execute(Tuple.of(contributor)));
        await(pool.preparedQuery(
            "UPDATE rating_opportunity_retirements SET user_id = NULL WHERE id = $1")
            .execute(Tuple.of(id)));
        assertNull(soleRetirement().getUUID("user_id"));
        assertEquals("over_cap_on_merge", soleRetirement().getString("reason"),
            "and the evidence of the retirement itself survives the erasure");
    }

    @Test
    void deletingTheAccountTakesTheAllowanceWithItButNotTheAudit() throws Exception {
        UUID contributor = insertUser("auth0|opp-deleted");
        insertOpportunity(contributor, survivor, 1, "2026-03-01T00:00:00Z");
        insertOpportunity(contributor, survivor, 2, "2026-05-01T00:00:00Z");
        insertOpportunity(contributor, absorbed, 1, "2026-01-01T00:00:00Z");
        merge();

        // THE ERASURE ORDER, and V105 made it load-bearing rather than incidental. Delete the
        // account FIRST, then clear the audit's contributor: that table has no foreign key, so no
        // cascade will ever do it, and its trigger now permits the clear only once the account is
        // gone. Clearing first is refused, which is the point - nothing may erase an identity from
        // history while the account it belongs to still exists.
        await(pool.preparedQuery("DELETE FROM users WHERE id = $1").execute(Tuple.of(contributor)));
        await(pool.preparedQuery(
            "UPDATE rating_opportunity_retirements SET user_id = NULL WHERE user_id = $1")
            .execute(Tuple.of(contributor)));

        // CASCADE. An allowance is a limit on an account; no account, no limit to enforce. The
        // honest consequence, which no schema fixes: deleting and re-registering resets it.
        assertEquals(0, countRows("rating_opportunities"));
        assertEquals(1, countRows("rating_opportunity_retirements"));
        assertNull(soleRetirement().getUUID("user_id"));
    }

    @Test
    void aFailedMergeRollsBackTheOpportunityReconciliationWhole() throws Exception {
        UUID contributor = insertUser("auth0|opp-rollback");
        insertOpportunity(contributor, survivor, 1, "2026-03-01T00:00:00Z");
        insertOpportunity(contributor, survivor, 2, "2026-05-01T00:00:00Z");
        insertOpportunity(contributor, absorbed, 1, "2026-01-01T00:00:00Z");
        List<String> before = opportunityShape(contributor, survivor);

        // A LATE, INJECTED FAILURE, after the opportunity reconciliation at step 4f. This test
        // originally used the seed collision on idx_one_seed_per_manager, which was a real merge
        // failure at step 7; V106 fixed that collision, so the failure is injected explicitly now.
        // The property under test is unchanged and is the one that matters: a partially applied
        // allowance would silently cost somebody an opportunity they never used.
        await(pool.query(
            "CREATE OR REPLACE FUNCTION t_block_opp_move() RETURNS trigger AS $BODY$ "
            + "BEGIN RAISE EXCEPTION 'injected: rating move blocked'; END; $BODY$ LANGUAGE plpgsql")
            .execute());
        await(pool.query("CREATE TRIGGER t_block_opp BEFORE UPDATE OF manager_id ON reviews "
            + "FOR EACH ROW EXECUTE FUNCTION t_block_opp_move()").execute());
        try {
            insertLegacyReview(absorbed, "Halverson Logistics", "Dispatch Lead", "Ash B", null);
            Exception failed = assertThrows(Exception.class, this::merge);
            assertTrue(rootMessage(failed).contains("injected"), rootMessage(failed));

            // ONE transaction. Nothing of the reconciliation survives its failure: the ordinals are
            // as they were, the absorbed row is still on the absorbed subject, and nothing was
            // audited as retired.
            assertEquals(before, opportunityShape(contributor, survivor));
            assertEquals(1, countOpportunities(contributor, absorbed));
            assertEquals(0, countRows("rating_opportunity_retirements"));
            assertEquals("approved", statusOf(absorbed), "and the absorbed profile was not retired");
        } finally {
            await(pool.query("DROP TRIGGER t_block_opp ON reviews").execute());
            await(pool.query("DROP FUNCTION t_block_opp_move()").execute());
        }
    }

    // -- the synthetic seed, reconciled ----------------------------------------
    //
    // V106. idx_one_seed_per_manager used to be partial on weight = TRUE and nothing else, so a
    // soft-deleted seed still held the slot and merging two seeded managers failed outright on the
    // unique index. The policy: keep the survivor's active seed, keep the absorbed one as
    // non-public history, and never convert a synthetic rating into a real one.

    @Test
    void mergingTwoSeededManagersRetiresTheAbsorbedSeedAndNoLongerFails() throws Exception {
        UUID survivorSeed = insertSeedReview(survivor, "Sam Seed");
        UUID absorbedSeed = insertSeedReview(absorbed, "Sid Seed");

        JsonObject m = merge();

        assertEquals(1, m.getInteger("seedsRetired"));

        // The survivor's seed is untouched and still the active one.
        assertNull(deletedAtOf(survivorSeed), "the survivor's seed stays active");
        assertEquals(survivor, managerOf(survivorSeed));

        // The absorbed one is history: retired, moved, still identifiable as synthetic.
        assertNotNull(deletedAtOf(absorbedSeed), "the absorbed seed is retired");
        assertEquals("superseded_seed_on_merge", deletedReasonOf(absorbedSeed));
        assertEquals(survivor, managerOf(absorbedSeed), "and it still moved with everything else");
        assertTrue(weightOf(absorbedSeed),
            "weight STAYS TRUE. Demoting it to FALSE is how this schema says 'a real contributor "
            + "rating', and describeCareerStage would then count it as a genuine opinion.");
        assertNull(userIdOf(absorbedSeed), "and it never gains a contributor");

        // Exactly one active seed, which is what the narrowed index now permits.
        assertEquals(1, activeSeedCount(survivor));
    }

    @Test
    void mergingWhenOnlyTheAbsorbedManagerIsSeededMovesThatSeedAndKeepsItActive() throws Exception {
        UUID absorbedSeed = insertSeedReview(absorbed, "Sid Seed");

        JsonObject m = merge();

        // Nothing to collide with, so nothing is retired: the survivor had no placeholder and
        // discarding this one would leave the profile with nothing to show.
        assertEquals(0, m.getInteger("seedsRetired"));
        assertNull(deletedAtOf(absorbedSeed));
        assertEquals(survivor, managerOf(absorbedSeed));
        assertEquals(1, activeSeedCount(survivor));
    }

    @Test
    void mergingWhenOnlyTheSurvivorIsSeededLeavesItAlone() throws Exception {
        UUID survivorSeed = insertSeedReview(survivor, "Sam Seed");

        JsonObject m = merge();

        assertEquals(0, m.getInteger("seedsRetired"));
        assertNull(deletedAtOf(survivorSeed));
        assertEquals(1, activeSeedCount(survivor));
    }

    @Test
    void mergingWithNoSeedsAtAllChangesNothing() throws Exception {
        insertLegacyReview(absorbed, "Halverson Logistics", "Dispatch Lead", "Ash B", null);

        JsonObject m = merge();

        assertEquals(0, m.getInteger("seedsRetired"));
        assertEquals(0, activeSeedCount(survivor));
    }

    @Test
    void anAlreadyRetiredSeedIsNotRetiredAgainAndDoesNotBlockTheMerge() throws Exception {
        insertSeedReview(survivor, "Sam Seed");
        UUID absorbedSeed = insertSeedReview(absorbed, "Sid Seed");
        // Already soft-deleted before the merge, by the expiry path or an earlier merge.
        await(pool.preparedQuery(
                "UPDATE reviews SET deleted_at = now(), deleted_reason = 'other' WHERE id = $1")
            .execute(Tuple.of(absorbedSeed)));

        JsonObject m = merge();

        // The predicate requires deleted_at IS NULL, so there is nothing to retire and the earlier
        // reason is not overwritten.
        assertEquals(0, m.getInteger("seedsRetired"));
        assertEquals("other", deletedReasonOf(absorbedSeed), "its own history is not rewritten");
        assertEquals(1, activeSeedCount(survivor));
    }

    @Test
    void aSecondMergeRetiresNoSeedAgain() throws Exception {
        insertSeedReview(survivor, "Sam Seed");
        UUID absorbedSeed = insertSeedReview(absorbed, "Sid Seed");

        JsonObject first = merge();
        assertEquals(1, first.getInteger("seedsRetired"));
        OffsetDateTime retiredAt = deletedAtOf(absorbedSeed);

        JsonObject second = merge();

        // Idempotent by construction: the absorbed seed is already soft-deleted, so the
        // deleted_at IS NULL predicate excludes it.
        assertEquals(0, second.getInteger("seedsRetired"));
        assertEquals(retiredAt, deletedAtOf(absorbedSeed), "and its retirement is not re-stamped");
        assertEquals(1, activeSeedCount(survivor));
    }

    @Test
    void aRetiredSeedCannotBeRepublishedByTheRestorationSweep() throws Exception {
        insertSeedReview(survivor, "Sam Seed");
        UUID absorbedSeed = insertSeedReview(absorbed, "Sid Seed");
        merge();

        // THE SWEEP'S OWN PREDICATE. It restores exactly those rows with deleted_reason IS NULL,
        // because a reason means somebody or something decided. Without the reason this seed would
        // come back and then collide with the survivor's on the narrowed index, which is the
        // original defect returning by a slower route.
        long restorable = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM reviews WHERE id = $1 AND deleted_at IS NOT NULL "
                + "AND deleted_reason IS NULL")
            .execute(Tuple.of(absorbedSeed)).map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(0, restorable, "a retired seed is permanently retired");
    }

    @Test
    void aFailedMergeRollsBackTheSeedRetirement() throws Exception {
        insertSeedReview(survivor, "Sam Seed");
        UUID absorbedSeed = insertSeedReview(absorbed, "Sid Seed");

        // A late, injected failure. This test used to rely on the seed collision itself to fail the
        // merge; V106 fixed that collision, so the failure is now injected explicitly. The property
        // under test is unchanged: the retirement is one statement inside the merge transaction, so
        // a later failure must revert it along with everything else.
        await(pool.query(
            "CREATE OR REPLACE FUNCTION t_block_review_move() RETURNS trigger AS $BODY$ "
            + "BEGIN RAISE EXCEPTION 'injected: review move blocked'; END; $BODY$ LANGUAGE plpgsql")
            .execute());
        await(pool.query("CREATE TRIGGER t_block_move BEFORE UPDATE OF manager_id ON reviews "
            + "FOR EACH ROW EXECUTE FUNCTION t_block_review_move()").execute());
        try {
            Exception failed = assertThrows(Exception.class, this::merge);
            assertTrue(rootMessage(failed).contains("injected"), rootMessage(failed));

            assertNull(deletedAtOf(absorbedSeed), "the retirement reverted with the transaction");
            assertEquals(absorbed, managerOf(absorbedSeed));
            assertEquals("approved", statusOf(absorbed), "and the merge did not happen at all");
        } finally {
            await(pool.query("DROP TRIGGER t_block_move ON reviews").execute());
            await(pool.query("DROP FUNCTION t_block_review_move()").execute());
        }

        // With the blocker gone the merge succeeds, so the failure left a retryable state.
        assertEquals(1, merge().getInteger("seedsRetired"));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    // ── employment status across a merge (slice 6) ───────────────────────────

    /**
     * A redirected claim is evidence for the role it now points at.
     *
     * <p>The merge moves a contributor's claim from the absorbed role onto the survivor's
     * equivalent one. That changes what the survivor's employment is grounded in, and a status
     * resolved before the merge was resolved without it. Leaving it is a stale assertion about
     * somebody's present employment, which is the failure the whole evidence-dated model exists
     * to prevent, and it would sit there until some unrelated acceptance happened to touch the
     * same role.
     */
    @Test
    void aRedirectedClaimResolvesTheSurvivorsEmployment() throws Exception {
        long survivorRole = insertRole(survivor, "Dispatch Lead", "2020-01-01T00:00:00Z");
        long absorbedRole = insertRole(absorbed, "Dispatch Lead", "2020-01-01T00:00:00Z");
        UUID who = insertUser("auth0|redirected-claim");
        long claim = insertAssertingClaim(absorbed, "Dispatch Lead", who, absorbedRole,
                                          java.time.LocalDate.now().minusMonths(4));
        await(org.werkpages.repository.EmploymentStatusResolver
            .resolveRoleAtomically(pool, absorbedRole));
        assertEquals("ongoing|contributor_claim", employmentStatusOf(absorbedRole),
            "fixture: the absorbed role is grounded in that claim");
        assertEquals("unknown|(null)", employmentStatusOf(survivorRole),
            "fixture: nothing has resolved the survivor's equivalent role at all, so its basis is "
            + "null rather than 'no_evidence'. The two are different statements: one is that "
            + "nobody has looked, the other that somebody looked and found nothing");

        merge();

        assertEquals(survivorRole, (long) resolvedRoleOf(claim),
            "fixture: the merge redirected the claim, which is what makes this a question");
        assertEquals("ongoing|contributor_claim", employmentStatusOf(survivorRole),
            "the survivor's employment is now grounded in the claim that arrived with the merge. "
            + "Before this was fixed the role kept saying it had no evidence while a resolved "
            + "claim sat pointing straight at it");
        assertEquals(claim, (long) statusClaimOf(survivorRole),
            "and it names the claim, so where the answer came from is readable");
    }

    /** A superseded role keeps its old answer, and that answer stops counting. */
    @Test
    void aSupersededRolesStatusNoLongerCounts() throws Exception {
        long survivorRole = insertRole(survivor, "Dispatch Lead", "2020-01-01T00:00:00Z");
        long absorbedRole = insertRole(absorbed, "Dispatch Lead", "2020-01-01T00:00:00Z");
        determine(absorbedRole, "ongoing", java.time.LocalDate.now().minusMonths(2));

        merge();

        assertEquals("superseded", stateOf(absorbedRole), "fixture: retired by the merge");
        assertEquals("ongoing|moderator", employmentStatusOf(absorbedRole),
            "its own row keeps what was decided about it, because history is not rewritten");
        assertEquals("unknown", labelFor(survivor),
            "but it no longer counts: the successor carries the answer, and the successor has no "
            + "evidence. A superseded role still influencing the label would revive an assertion "
            + "about employment the merge has just said is the same employment as another row");
    }

    /** New evidence that cannot be true raises a dispute on the role it arrived at. */
    @Test
    void aRedirectedClaimThatContradictsRaisesADisputeOnTheSurvivor() throws Exception {
        long survivorRole = insertRoleEnding(survivor, "Dispatch Lead", "2020-01-01T00:00:00Z",
                                             java.time.LocalDate.now().minusMonths(10));
        long absorbedRole = insertRole(absorbed, "Dispatch Lead", "2020-01-01T00:00:00Z");
        UUID who = insertUser("auth0|contradicting-redirect");
        insertAssertingClaim(absorbed, "Dispatch Lead", who, absorbedRole,
                             java.time.LocalDate.now().minusMonths(2));
        await(org.werkpages.repository.EmploymentStatusResolver
            .resolveRoleAtomically(pool, survivorRole));
        assertEquals("ended|end_date", employmentStatusOf(survivorRole), "fixture: the record ends");

        merge();

        assertEquals("disputed|(null)", employmentStatusOf(survivorRole),
            "the record says the job finished ten months ago and the contributor who arrived with "
            + "the merge says the manager was still there two months ago. The merge does not get "
            + "to pick a side");
        assertEquals(1, openDisputesFor(survivorRole), "so a moderator has the question");
    }

    /**
     * A determination survives the merge, and is reopened by evidence that arrives after it.
     *
     * <p>The claim's own statement may be years old, but it arrived at THIS role when the merge
     * canonicalized it, and a moderator cannot have weighed evidence that was not yet attached.
     */
    @Test
    void aDeterminationIsReopenedByEvidenceTheMergeBringsIt() throws Exception {
        long survivorRole = insertRole(survivor, "Dispatch Lead", "2020-01-01T00:00:00Z");
        long absorbedRole = insertRole(absorbed, "Dispatch Lead", "2020-01-01T00:00:00Z");

        /*
          THE ORDER OF THESE THREE IS THE TEST.

          The claim is resolved onto the ABSORBED role FIRST, so its own resolved_at is older than
          the determination that follows. The determination is then made on the survivor, weighing
          everything attached to it at that moment, which does not include this claim. Only the
          merge brings them together.

          Written the other way round - claim after determination - the claim's own timestamp is
          already newer and the dispute would be raised whether or not canonicalisation counted as
          an arrival. A mutation run proved that: removing the canonicalized_at term broke nothing,
          because the fixture was asserting the right outcome for the wrong reason.
        */
        UUID who = insertUser("auth0|late-arriving-evidence");
        insertAssertingClaim(absorbed, "Dispatch Lead", who, absorbedRole,
                             java.time.LocalDate.now().minusMonths(2));
        determine(survivorRole, "ended", java.time.LocalDate.now().minusMonths(12));

        merge();

        assertEquals("disputed|(null)", employmentStatusOf(survivorRole),
            "the determination said the job ended a year ago; the claim the merge brought says "
            + "the manager was there two months ago. The moderator never saw it, because it was "
            + "attached to another profile's role when they decided");
    }

    /** The same question for a role merge, which redirects claims the same way. */
    @Test
    void mergingTwoRolesResolvesTheKeptOne() throws Exception {
        long keep = insertRole(survivor, "Dispatch Lead", "2020-01-01T00:00:00Z");
        long gone = insertRole(survivor, "Dispatch Lead", "2020-06-01T00:00:00Z");
        UUID who = insertUser("auth0|role-merge-claim");
        long claim = insertAssertingClaim(survivor, "Dispatch Lead", who, gone,
                                          java.time.LocalDate.now().minusMonths(3));
        await(org.werkpages.repository.EmploymentStatusResolver.resolveRoleAtomically(pool, gone));
        assertEquals("unknown|(null)", employmentStatusOf(keep), "fixture: nothing yet");

        await(merges.mergeRoles(survivor, keep, gone, admin));

        assertEquals(keep, (long) resolvedRoleOf(claim), "fixture: the claim followed");
        assertEquals("ongoing|contributor_claim", employmentStatusOf(keep),
            "a role merge moves the evidence too, so the kept role has to be re-derived");
    }

    /** Withdrawal after a merge reaches the role the claim was redirected to. */
    @Test
    void withdrawalAfterAMergeUngroundsTheSurvivorsRole() throws Exception {
        long survivorRole = insertRole(survivor, "Dispatch Lead", "2020-01-01T00:00:00Z");
        long absorbedRole = insertRole(absorbed, "Dispatch Lead", "2020-01-01T00:00:00Z");
        UUID who = insertUser("auth0|withdraw-after-merge");
        long claim = insertAssertingClaim(absorbed, "Dispatch Lead", who, absorbedRole,
                                          java.time.LocalDate.now().minusMonths(4));
        merge();
        assertEquals("ongoing|contributor_claim", employmentStatusOf(survivorRole),
            "fixture: the merge brought the claim and resolved the role it landed on");

        await(pool.preparedQuery("UPDATE role_claims SET withdrawn_at = now() WHERE id = $1")
            .execute(Tuple.of(claim)));
        await(org.werkpages.repository.EmploymentStatusResolver
            .resolveRoleAtomically(pool, survivorRole));

        assertEquals("unknown|no_evidence", employmentStatusOf(survivorRole),
            "a tombstone grounds nothing, wherever the merge moved it to");
    }

    // ── slice 6 helpers ──────────────────────────────────────────────────────

    /** A resolved claim that asserts the manager was still there when the contributor left. */
    private long insertAssertingClaim(long managerId, String title, UUID userId, long roleId,
                                      java.time.LocalDate leftOn) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO role_claims (proposal_key, manager_id, company_id, title, source,
                                          user_id, finalized_at, original_manager_id,
                                          selected_role_id, start_date, end_date, period_known,
                                          still_there, resolved_role_id, resolution, resolved_at)
                VALUES ($1,$2,$3,$4,'contributor',$5,now(),$2,$6,'2020-01-01'::date,$7,TRUE,TRUE,
                        $6,'linked',now())
                RETURNING id
                """)
            .execute(Tuple.of(UUID.randomUUID(), managerId, companyId, title, userId)
                .addValue(roleId).addValue(leftOn))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertRoleEnding(long managerId, String title, String startIso,
                                  java.time.LocalDate endOn) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO career_history (manager_id, company, title, start_date, end_date, "
                + "company_id, period_known, state, source) "
                + "VALUES ($1,'Halverson Logistics',$2,$3,($4::date)::timestamp AT TIME ZONE "
                + "'UTC',$5,TRUE,'accepted','contributor') RETURNING id")
            .execute(Tuple.of(managerId, title, OffsetDateTime.parse(startIso))
                .addValue(endOn).addValue(companyId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private void determine(long roleId, String status, java.time.LocalDate asOf) throws Exception {
        await(pool.preparedQuery("""
                UPDATE career_history
                   SET employment_status = $2, status_basis = 'moderator',
                       status_as_of = ($3::date)::timestamp AT TIME ZONE 'UTC',
                       status_resolved_at = now()
                 WHERE id = $1
                """).execute(Tuple.of(roleId, status).addValue(asOf)));
    }

    private String employmentStatusOf(long roleId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT employment_status || '|' || COALESCE(status_basis,'(null)') AS s "
                + "FROM career_history WHERE id = $1")
            .execute(Tuple.of(roleId)).map(rs -> rs.iterator().next().getString("s")));
    }

    private Long statusClaimOf(long roleId) throws Exception {
        return await(pool.preparedQuery("SELECT status_claim_id FROM career_history WHERE id = $1")
            .execute(Tuple.of(roleId)).map(rs -> rs.iterator().next().getLong(0)));
    }

    private Long resolvedRoleOf(long claimId) throws Exception {
        return await(pool.preparedQuery("SELECT resolved_role_id FROM role_claims WHERE id = $1")
            .execute(Tuple.of(claimId)).map(rs -> rs.iterator().next().getLong(0)));
    }

    private String stateOf(long roleId) throws Exception {
        return await(pool.preparedQuery("SELECT state FROM career_history WHERE id = $1")
            .execute(Tuple.of(roleId)).map(rs -> rs.iterator().next().getString("state")));
    }

    private String labelFor(long managerId) throws Exception {
        return await(pool.preparedQuery("SELECT employment_label($1,$2,$3) AS s")
            .execute(Tuple.of(managerId, companyId,
                              org.werkpages.repository.EmploymentStatusResolver.FRESHNESS_MONTHS))
            .map(rs -> rs.iterator().next().getString("s")));
    }

    private int openDisputesFor(long roleId) throws Exception {
        return await(pool.preparedQuery("SELECT count(*)::int AS c FROM role_conflicts "
                + "WHERE kind = 'employment_status_dispute' AND resolved_at IS NULL "
                + "AND role_id = $1").execute(Tuple.of(roleId))
            .map(rs -> rs.iterator().next().getInteger("c")));
    }

    private JsonObject merge() throws Exception {
        return await(merges.mergeManagers(survivor, absorbed, admin));
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

    private long insertRole(long managerId, String title, String startIso) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO career_history (manager_id, company, title, start_date, company_id, "
                + "period_known, state, source) "
                + "VALUES ($1,'Halverson Logistics',$2,$3,$4,TRUE,'accepted','contributor') "
                + "RETURNING id")
            .execute(Tuple.of(managerId, title, OffsetDateTime.parse(startIso), companyId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertRoleWithUnknownPeriod(long managerId, String title) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO career_history (manager_id, company, title, start_date, company_id, "
                + "period_known, state, source) "
                + "VALUES ($1,'Halverson Logistics',$2,NULL,$3,FALSE,'accepted','contributor') "
                + "RETURNING id")
            .execute(Tuple.of(managerId, title, companyId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    /** Finalized, because a claim with a rating against it is a submitted observation. */
    private long insertClaim(long managerId, String title, UUID userId) throws Exception {
        return insertClaimSelecting(managerId, title, userId, null);
    }

    /**
     * A claim whose contributor PICKED an employment, with the pick present from the start.
     *
     * <p>selected_role_id used to be back-filled by resolveClaim after finalization. V108's
     * claim_assertion_is_immutable refuses that, correctly: the selection is what the contributor
     * chose, and production writes it at finalization and never afterwards. The end state of every
     * test is identical; only the route to it is now the one production takes.
     */
    private long insertClaimSelecting(long managerId, String title, UUID userId,
                                      Long selectedRoleId) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO role_claims (proposal_key, manager_id, company_id, title, source, "
                + "user_id, finalized_at, original_manager_id, selected_role_id) "
                + "VALUES ($1,$2,$3,$4,'contributor',$5,now(),$2,$6) RETURNING id")
            .execute(Tuple.of(UUID.randomUUID(), managerId, companyId, title, userId,
                              selectedRoleId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    /**
     * The PLATFORM's resolution, and only that.
     *
     * <p>The contributor's selection is set at finalization by insertClaimSelecting instead,
     * because selected_role_id is an assertion rather than a resolution: rev 268 lists it among
     * the columns nothing may rewrite once finalized_at is set, and V108 now enforces that.
     */
    private void resolveClaim(long claimId, long roleId) throws Exception {
        await(pool.preparedQuery(
                "UPDATE role_claims SET resolved_role_id = $2, "
                + "resolved_at = now(), resolution = 'linked' WHERE id = $1")
            .execute(Tuple.of(claimId, roleId)).mapEmpty());
    }

    private UUID insertClaimedReview(long managerId, long claimId, String author) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO reviews (manager_id, author, overall_rating, manager_company, "
                + "manager_title, claim_id, role_basis, role_required, attribution_basis) "
                + "VALUES ($1,$2,4.0,'Halverson Logistics','Dispatch Lead',$3,'claimed',TRUE,"
                + "'role') RETURNING id")
            .execute(Tuple.of(managerId, author + "-" + UUID.randomUUID(), claimId))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private UUID insertLegacyReview(long managerId, String company, String title, String author,
                                    UUID userId) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO reviews (manager_id, user_id, author, overall_rating, "
                + "manager_company, manager_title, text) "
                + "VALUES ($1,$2,$3,4.0,$4,$5,'legacy') RETURNING id")
            .execute(Tuple.of(managerId, userId, author, company, title))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    /** Withdrawn: no claim, but the role is known exactly, and it may not be live. */
    private UUID insertWithdrawnReview(long managerId, long roleId, String author) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO reviews (manager_id, author, overall_rating, manager_company, "
                + "manager_title, role_basis, withdrawn_role_id, attribution_basis, disposition, "
                + "gate_eligible, live_since) "
                + "VALUES ($1,$2,4.0,'Halverson Logistics','Dispatch Lead','withdrawn',$3,"
                + "'withdrawn_role','held',FALSE,NULL) RETURNING id")
            .execute(Tuple.of(managerId, author + "-" + UUID.randomUUID(), roleId))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private long insertConflict(long managerId, String kind, String titleKey, String periodKey)
            throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO role_conflicts (kind, manager_id, company_id, title_key, period_key, "
                + "manager_name, company_name, title) "
                + "VALUES ($1,$2,$3,$4,$5,'Priya Raghunathan','Halverson Logistics',"
                + "'Dispatch Lead') RETURNING id")
            .execute(Tuple.of(kind, managerId, companyId, titleKey, periodKey))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private void linkClaimToTask(long conflictId, long claimId, String role) throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO role_conflict_claims (conflict_id, claim_id, role) VALUES ($1,$2,$3)")
            .execute(Tuple.of(conflictId, claimId, role)).mapEmpty());
    }

    private Row conflictRow(long id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM role_conflicts WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private Row reviewRow(UUID id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM reviews WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private Row roleRow(long id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM career_history WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private Row claimRow(long id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM role_claims WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private Row managerRow(long id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM managers WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private Row lastEventFor(long claimId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT * FROM claim_resolution_events WHERE claim_id = $1 ORDER BY id DESC LIMIT 1")
            .execute(Tuple.of(claimId)).map(rs -> rs.iterator().next()));
    }

    private String roleInTask(long conflictId, long claimId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT role FROM role_conflict_claims WHERE conflict_id = $1 AND claim_id = $2")
            .execute(Tuple.of(conflictId, claimId))
            .map(rs -> rs.iterator().hasNext() ? rs.iterator().next().getString("role") : null));
    }

    private long managerOf(UUID reviewId) throws Exception {
        return reviewRow(reviewId).getLong("manager_id");
    }

    private String dispositionOf(UUID reviewId) throws Exception {
        return reviewRow(reviewId).getString("disposition");
    }

    private OffsetDateTime deletedAtOf(UUID reviewId) throws Exception {
        return reviewRow(reviewId).getOffsetDateTime("deleted_at");
    }

    private String statusOf(long managerId) throws Exception {
        return managerRow(managerId).getString("approval_status");
    }

    private Long mergedIntoOf(long managerId) throws Exception {
        return managerRow(managerId).getLong("merged_into");
    }

    private long countRows(String table) throws Exception {
        return await(pool.query("SELECT COUNT(*) AS c FROM " + table).execute()
            .map(rs -> rs.iterator().next().getLong("c")));
    }

    private int countSupersessionsFor(UUID reviewId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM review_supersessions WHERE superseded_review_id = $1")
            .execute(Tuple.of(reviewId)).map(rs -> rs.iterator().next().getLong("c").intValue()));
    }


    private long insertOpportunity(UUID userId, long subjectId, int ordinal, String openedAtIso)
            throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO rating_opportunities (user_id, subject_type, subject_id, ordinal, "
                + "opened_at) VALUES ($1,'manager',$2,$3,$4) RETURNING id")
            .execute(Tuple.of(userId, subjectId, (short) ordinal, OffsetDateTime.parse(openedAtIso)))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    /** "ordinal@opened-day", which is the whole state that matters and reads as one line. */
    private List<String> opportunityShape(UUID userId, long subjectId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT ordinal, to_char(opened_at AT TIME ZONE 'UTC','YYYY-MM-DD') AS d "
                + "FROM rating_opportunities WHERE user_id = $1 AND subject_type = 'manager' "
                + "AND subject_id = $2 ORDER BY ordinal")
            .execute(Tuple.of(userId, subjectId))
            .map(rs -> {
                List<String> out = new ArrayList<>();
                for (Row r : rs) out.add(r.get(Short.class, "ordinal") + "@" + r.getString("d"));
                return out;
            }));
    }

    private int countOpportunities(UUID userId, long subjectId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM rating_opportunities WHERE user_id = $1 "
                + "AND subject_type = 'manager' AND subject_id = $2")
            .execute(Tuple.of(userId, subjectId))
            .map(rs -> rs.iterator().next().getLong("c").intValue()));
    }

    private Row soleRetirement() throws Exception {
        return await(pool.query("SELECT * FROM rating_opportunity_retirements").execute()
            .map(rs -> {
                assertEquals(1, rs.rowCount(), "expected exactly one retirement row");
                return rs.iterator().next();
            }));
    }

    private List<String> retiredOpenedDays() throws Exception {
        return await(pool.query(
                "SELECT to_char(opened_at AT TIME ZONE 'UTC','YYYY-MM-DD') AS d "
                + "FROM rating_opportunity_retirements ORDER BY opened_at").execute()
            .map(rs -> {
                List<String> out = new ArrayList<>();
                for (Row r : rs) out.add(r.getString("d"));
                return out;
            }));
    }

    /** weight = true is the pre-existing seed marker, and idx_one_seed_per_manager allows one. */
    private UUID insertSeedReview(long managerId, String author) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO reviews (manager_id, author, overall_rating, manager_company, "
                + "manager_title, weight) "
                + "VALUES ($1,$2,4.0,'Halverson Logistics','Dispatch Lead',TRUE) RETURNING id")
            .execute(Tuple.of(managerId, author))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private static String rootMessage(Throwable t) {
        StringBuilder all = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) all.append(c.getMessage()).append(" | ");
        return all.toString();
    }

    private String deletedReasonOf(UUID reviewId) throws Exception {
        return reviewRow(reviewId).getString("deleted_reason");
    }

    private boolean weightOf(UUID reviewId) throws Exception {
        return reviewRow(reviewId).getBoolean("weight");
    }

    private UUID userIdOf(UUID reviewId) throws Exception {
        return reviewRow(reviewId).getUUID("user_id");
    }

    private int activeSeedCount(long managerId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM reviews WHERE manager_id = $1 AND weight = TRUE "
                + "AND deleted_at IS NULL")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong("c").intValue()));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
