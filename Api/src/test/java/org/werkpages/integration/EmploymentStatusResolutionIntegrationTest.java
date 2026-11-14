package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.EmploymentStatusResolver;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.AdminService;
import org.werkpages.service.ModerationService;
import org.werkpages.service.RoleClaimService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice 2: who resolves an employment's status, when, and what it refuses to do.
 *
 * <h2>What this is testing</h2>
 *
 * <p>V112 gave {@code career_history} a resolved status and an append-only log. Nothing wrote them.
 * This slice makes every path that accepts or changes employment resolve the status in the same
 * transaction, and the tests below are organised around the three things that can go wrong:
 *
 * <ol>
 *   <li><b>a path that does not resolve</b>, which leaves an accepted ended employment reading
 *       Unknown. The drift query is the backstop and it is tested too;</li>
 *   <li><b>a resolution that asserts more than the evidence supports</b>, which is the failure the
 *       whole model exists to prevent: a {@code still_there} claim with no departure date of its
 *       own must not become a Current with an invented date;</li>
 *   <li><b>a resolution that writes when nothing changed</b>, which turns the event log into a
 *       record of network traffic instead of a record of decisions.</li>
 * </ol>
 *
 * <p>The derivation cases drive {@link EmploymentStatusResolver} directly, because it is the unit
 * that owns the rules. The hook cases drive {@link ModerationService} and
 * {@link RoleClaimService} through their real entry points, because the question there is whether
 * the call is wired in at all, and a test that called the resolver itself would pass with every
 * hook removed.
 */
/* ROUTINE CI: tagged "critical" - security, erasure or data integrity. See the poms. */
@Tag("critical")
@Testcontainers
class EmploymentStatusResolutionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("employment_status")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static ModerationService moderation;
    static ManagerRepository managers;

    long companyId;
    long managerId;
    UUID contributor;
    String adminAuth0 = "auth0|admin";

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
        managers = new ManagerRepository(pool);
        moderation = new ModerationService(pool, new AdminService(
            new UserRepository(pool), managers, new ReviewRepository(pool), null, null));
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) {
            pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void cleanDb() throws Exception {
        // One statement, because TRUNCATE refuses a table another table references unless both
        // are named together. review_supersessions and role_conflict_reviews are in the list for
        // exactly that reason: they reference manager_merge_events and role_conflicts.
        await(pool.query("TRUNCATE review_supersessions, role_conflict_reviews, "
            + "role_conflict_claims, role_conflicts, claim_resolution_events, claim_corrections, "
            + "role_corrections, employment_status_events, rating_opportunity_retirements, "
            + "manager_merge_events").execute());
        await(pool.query("TRUNCATE reviews, role_claims, rating_opportunities, career_history, "
            + "capture_attempts, captured_drafts, company_stats_live, managers, companies, users "
            + "CASCADE").execute());
        companyId   = insertCompany("Halverson Logistics");
        managerId   = insertManager("Priya Raghunathan", "priya-r");
        contributor = insertUser("auth0|contributor", "user");
        insertUser(adminAuth0, "admin");
    }

    // ── the hooks: does anything actually call the resolver ──────────────────

    @Test
    @DisplayName("accepting an employment that already ended resolves it, in that transaction")
    void acceptingEndedEmploymentResolvesIt() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", "2022-12-31", "proposed");
        assertEquals("unknown|(null)", status(role), "fixture: a proposed role carries no status");

        await(moderation.acceptEmployment(adminAuth0, role));

        assertEquals("ended|end_date|2022-12-31", statusWithDate(role),
            "acceptance is when the status becomes answerable, and the record itself is the "
            + "evidence: an accepted role carrying an end date IS ended employment");
        assertEquals(1, events(role), "and the trigger recorded it, once");
    }

    @Test
    @DisplayName("accepting employment with no evidence resolves unknown, which is a normal row")
    void acceptanceIsNotCurrency() throws Exception {
        long role = insertRole("Night Lead", "2020-01-01", null, "proposed");

        await(moderation.acceptEmployment(adminAuth0, role));

        assertEquals("unknown|no_evidence", status(role),
            "a moderator accepting that somebody worked somewhere establishes the EMPLOYMENT, not "
            + "its present-day status. A null end date is the absence of information and is never "
            + "evidence of ongoing employment");
    }

    @Test
    @DisplayName("a correction that sets an end date resolves the role ended")
    void correctingAnEndDateResolvesEnded() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", null, "proposed");
        await(moderation.acceptEmployment(adminAuth0, role));
        assertEquals("unknown|no_evidence", status(role), "fixture: accepted, no evidence");

        await(moderation.correctEmployment(adminAuth0, role, new ModerationService.Correction(
            false, null, false, null, true, LocalDate.parse("2021-06-30"), false, false),
            "the contributor's leaving date was confirmed"));

        assertEquals("ended|end_date|2021-06-30", statusWithDate(role),
            "a correction changes the evidence, so the status follows it without anybody having to "
            + "remember to update it separately");
    }

    @Test
    @DisplayName("REGRESSION: clearing an end date takes the role back off 'ended'")
    void clearingAnEndDateUnwindsTheResolution() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", "2022-12-31", "proposed");
        await(moderation.acceptEmployment(adminAuth0, role));
        assertEquals("ended|end_date|2022-12-31", statusWithDate(role), "fixture: resolved ended");

        // periodKnown stays true and the start date is untouched; only the end date goes.
        await(moderation.correctEmployment(adminAuth0, role, new ModerationService.Correction(
            false, null, false, null, true, null, false, false),
            "that end date belonged to a different person's spell"));

        assertEquals("unknown|no_evidence", status(role),
            "the status is DERIVED, not accumulated. A correction saying the employment did not "
            + "finish must not leave the row asserting that it did, and nothing has to remember to "
            + "unwind it because there is nothing to unwind: the answer is recomputed");
    }

    @Test
    @DisplayName("a claim linked to an accepted role resolves it from the contributor's evidence")
    void aLinkedClaimResolvesTheRole() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", null, "accepted");
        long claim = insertClaim("Depot Supervisor", "2024-03-31", true);

        await(pool.withTransaction(tx ->
            new RoleClaimService().resolveBySelection(tx, claim, managerId, companyId, role)));

        assertEquals("ongoing|contributor_claim|2024-03-31", statusWithDate(role),
            "the contributor said the manager was still there when they left in March 2024, so "
            + "that is what the evidence speaks to. NOT today, and not the date we recorded it");
        assertEquals(claim, claimBehind(role), "and the row names the claim it came from");
    }

    @Test
    @DisplayName("THE RULE THAT EARNS THE MODEL: still_there with no departure date asserts nothing")
    void aStillThereClaimWithNoDepartureDateResolvesUnknown() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", null, "accepted");
        long claim = insertClaim("Depot Supervisor", null, true);

        await(pool.withTransaction(tx ->
            new RoleClaimService().resolveBySelection(tx, claim, managerId, companyId, role)));

        assertEquals("unknown|no_evidence", status(role),
            "a still_there claim whose own end date is unknown gives no assertable as_of, so it "
            + "resolves to unknown rather than to a Current with an invented date. Inventing one "
            + "would publish a claim about somebody's present employment that nothing supports");
    }

    @Test
    @DisplayName("a claim on a PROPOSED role waits for acceptance")
    void aClaimOnAProposedRoleResolvesNothing() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", null, "proposed");
        long claim = insertClaim("Depot Supervisor", "2024-03-31", true);

        await(pool.withTransaction(tx ->
            new RoleClaimService().resolveBySelection(tx, claim, managerId, companyId, role)));

        assertEquals("unknown|(null)", status(role),
            "nothing a contributor says publishes a status on employment the platform has not "
            + "accepted. The claim waits, and acceptance resolves it");
        assertEquals(0, events(role), "and nothing is logged, because nothing was decided");

        await(moderation.acceptEmployment(adminAuth0, role));
        assertEquals("ongoing|contributor_claim|2024-03-31", statusWithDate(role),
            "acceptance then reads the claim that was waiting");
    }

    // ── the repository's own career writes ──────────────────────────────────

    @Test
    @DisplayName("inserting a career entry with an end date resolves it in the same transaction")
    void insertingAnEndedEntryResolvesIt() throws Exception {
        await(managers.insertCareerEntry(managerId, "Halverson Logistics", "Yard Lead",
            day("2014-01-01"), day("2016-06-30"), companyId));

        long role = newestRole();
        assertEquals("ended|end_date|2016-06-30", statusWithDate(role),
            "the admin and manager edit flows write employment through this method, several times "
            + "per request and from four different branches. Resolving HERE is what makes all of "
            + "them correct without each one having to remember");
        assertEquals(1, events(role));
        assertEquals(List.of(), await(managers.driftingEmploymentStatuses()),
            "and nothing drifts, which is the property the reconciliation query checks");
    }

    @Test
    @DisplayName("inserting an open-ended entry leaves it unknown, not ongoing")
    void insertingAnOpenEntryResolvesUnknown() throws Exception {
        await(managers.insertCareerEntry(managerId, "Halverson Logistics", "Yard Lead",
            day("2014-01-01"), null, companyId));

        assertEquals("unknown|no_evidence", status(newestRole()),
            "an open-ended entry is the absence of an end date, which is not evidence that the "
            + "employment is current. Only a contributor saying so, with a date, is that");
    }

    @Test
    @DisplayName("closing the open entries resolves every row it closed")
    void closingOpenEntriesResolvesThem() throws Exception {
        await(managers.insertCareerEntry(managerId, "Halverson Logistics", "Yard Lead",
            day("2014-01-01"), null, companyId));
        long open = newestRole();
        assertEquals("unknown|no_evidence", status(open), "fixture: open, so unknown");

        int closed = await(managers.closeOpenCareerEntry(managerId, day("2018-03-31")));

        assertEquals(1, closed);
        assertEquals("ended|end_date|2018-03-31", statusWithDate(open),
            "closing an entry ends that employment, and the status says so without anybody "
            + "calling anything else");
    }

    @Test
    @DisplayName("REGRESSION: editing an entry to remove its end date unwinds the resolution")
    void editingAwayAnEndDateUnwindsTheResolution() throws Exception {
        await(managers.insertCareerEntry(managerId, "Halverson Logistics", "Yard Lead",
            day("2014-01-01"), day("2016-06-30"), companyId));
        long role = newestRole();
        assertEquals("ended|end_date|2016-06-30", statusWithDate(role), "fixture: resolved ended");

        int updated = await(managers.updateCareerEntry(role, managerId, "Halverson Logistics",
            "Yard Lead", day("2014-01-01"), null, companyId));

        assertEquals(1, updated);
        assertEquals("unknown|no_evidence", status(role),
            "an admin correcting away an end date is saying the employment did not finish there. "
            + "Leaving the row asserting 'ended' would publish the thing they just corrected");
    }

    // ── the three ways evidence can arrive ──────────────────────────────────

    @Test
    @DisplayName("identical evidence writes nothing, however many times it arrives")
    void anIdenticalResolutionIsIdempotent() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", "2022-12-31", "accepted");
        await(resolve(role));
        assertEquals(1, events(role), "fixture: resolved once");
        String first = resolvedAt(role);

        await(resolve(role));
        await(resolve(role));

        assertEquals(1, events(role),
            "a retried submission arrives with the same evidence and must not append an event. The "
            + "log is a record of decisions, not of traffic");
        assertEquals(first, resolvedAt(role), "and the row is not touched at all");
    }

    @Test
    @DisplayName("a second contributor asserting the same date is recorded as a verification")
    void independentCorroborationIsAVerification() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", null, "accepted");
        long first = insertClaimLinked(role, "2024-03-31", true);
        await(resolve(role));
        assertNull(verifiedAt(role), "fixture: established, not yet re-checked");
        String established = resolvedAt(role);

        long second = insertClaimLinked(role, "2024-03-31", true);
        await(resolve(role));

        assertEquals(established, resolvedAt(role),
            "status_resolved_at records when the answer was ESTABLISHED and does not move when "
            + "somebody else confirms it");
        assertNotNull(verifiedAt(role), "status_verified_at records that it was re-checked");
        assertEquals(second, claimBehind(role), "and the row names the evidence it last read");
        assertEquals(2, events(role), "a confirmation is a change worth logging, once");
        assertTrue(first > 0);
    }

    @Test
    @DisplayName("a later departure extends what the evidence speaks to")
    void aLaterDepartureExtendsTheEvidence() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", null, "accepted");
        insertClaimLinked(role, "2023-01-31", true);
        await(resolve(role));
        assertEquals("ongoing|contributor_claim|2023-01-31", statusWithDate(role));

        insertClaimLinked(role, "2025-02-28", true);
        await(resolve(role));

        assertEquals("ongoing|contributor_claim|2025-02-28", statusWithDate(role),
            "the later assertion is evidence about the later date, so it wins. Not because it was "
            + "submitted more recently: because it speaks to a later moment");
        assertNull(verifiedAt(role),
            "and this is a new answer rather than a confirmation of the old one");
    }

    @Test
    @DisplayName("the record outranks every claim, and clearing it falls back to them")
    void theRecordOutranksTheClaims() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", null, "accepted");
        insertClaimLinked(role, "2024-03-31", true);
        await(resolve(role));
        assertEquals("ongoing|contributor_claim|2024-03-31", statusWithDate(role));

        await(pool.preparedQuery("UPDATE career_history SET end_date = "
                + "('2024-12-31'::date)::timestamp AT TIME ZONE 'UTC' WHERE id = $1")
            .execute(Tuple.of(role)));
        await(resolve(role));
        assertEquals("ended|end_date|2024-12-31", statusWithDate(role),
            "what the platform has resolved about the employment outranks what a contributor "
            + "asserted about it");
        assertNull(claimBehind(role), "and a record-grounded status names no claim");

        await(pool.preparedQuery("UPDATE career_history SET end_date = NULL WHERE id = $1")
            .execute(Tuple.of(role)));
        await(resolve(role));
        assertEquals("ongoing|contributor_claim|2024-03-31", statusWithDate(role),
            "and removing it returns to the contributor evidence, which never went anywhere");
    }

    @Test
    @DisplayName("a moderator's determination is never overwritten by derivation")
    void aModeratorsDeterminationIsImmune() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", null, "accepted");
        /*
          A COMPATIBLE claim, and the date is the whole point of the fixture.

          This test is about derivation leaving a determination alone. A contributor who left in
          2022 saying the manager was still there then agrees with a determination that the job
          ended in May 2023, so there is nothing to dispute and what remains under test is
          immunity. Asserting a departure AFTER the determination is a contradiction and is
          supposed to raise a dispute: that case is aModeratorDeterminationCanBeContradicted.
        */
        insertClaimLinked(role, "2022-01-31", true);
        // Slice 4 writes this through an admin route; here it is set directly, because what is
        // being tested is that derivation LEAVES IT ALONE.
        await(pool.preparedQuery("""
                UPDATE career_history
                   SET employment_status = 'ended', status_basis = 'moderator',
                       status_as_of = ('2023-05-01'::date)::timestamp AT TIME ZONE 'UTC',
                       status_resolved_at = now()
                 WHERE id = $1
                """).execute(Tuple.of(role)));
        int before = events(role);

        await(resolve(role));

        assertEquals("ended|moderator|2023-05-01", statusWithDate(role),
            "rev 268: a new claim is evidence, and it 'may not quietly replace a moderator's "
            + "determination'. A human who has looked at both accounts outranks the derivation");
        assertEquals(before, events(role), "and nothing is logged, because nothing changed");
    }

    @Test
    @DisplayName("resolution names no human actor, because no human resolved it")
    void resolutionFabricatesNoActor() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", "2022-12-31", "proposed");
        await(moderation.acceptEmployment(adminAuth0, role));

        assertEquals(1, events(role));
        assertEquals(0, count("SELECT count(*) AS c FROM employment_status_events "
                              + "WHERE role_id = " + role + " AND \"by\" IS NOT NULL"),
            "an administrator accepted the EMPLOYMENT; the resolution component derived the status "
            + "from the record. Naming the admin as the author of that derivation would be a "
            + "fabrication with a column name attached, and basis carries the provenance instead");
        assertEquals("end_date", basisOfNewestEvent(role),
            "which is what the event says instead of an actor");
    }

    // ── slice 3: contradictory testimony and disputes ───────────────────────

    @Test
    @DisplayName("a contributor who puts the manager in the job after it ended raises a dispute")
    void aContradictingContributorRaisesADispute() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", "2024-06-30", "accepted");
        long claim = insertClaimLinked(role, "2025-02-28", true);

        await(resolve(role));

        assertEquals("disputed|(null)|(null)", statusWithDate(role),
            "the record says the job ended in June 2024 and a contributor says the manager was "
            + "still there when they left in February 2025. Those cannot both be true, so the "
            + "honest answer while it is unresolved is that we do not know");
        assertEquals(1, openDisputes(role), "and a moderator has a task");
        assertFalse(disputeBlocksPublication(role),
            "which does NOT hold any rating: a dispute about WHEN somebody worked somewhere says "
            + "nothing about whether an opinion of them may be published");
        assertEquals("evidence", disputeClaimRole(role, claim),
            "the claim is cited as evidence, never as subject. Subject means the rating itself is "
            + "in doubt, and it is not");
        assertEquals("ended->disputed", newestStatusEvent(role),
            "and the transition is in the log, so what the answer was before is recoverable");
    }

    @Test
    @DisplayName("an earlier departure is compatible and raises nothing")
    void anEarlierDepartureIsCompatible() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", "2024-06-30", "accepted");
        insertClaimLinked(role, "2021-03-31", true);

        await(resolve(role));

        assertEquals("ended|end_date|2024-06-30", statusWithDate(role),
            "somebody who left in 2021 saying the manager was still there then, and a record "
            + "saying the job ended in 2024, agree completely. He was there in 2021 and left in "
            + "2024, and a dispute here would be a moderator's time spent on arithmetic");
        assertEquals(0, openDisputes(role));
    }

    @Test
    @DisplayName("an assertion with no departure date raises nothing")
    void noDepartureDateRaisesNothing() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", "2024-06-30", "accepted");
        insertClaimLinked(role, null, true);

        await(resolve(role));

        assertEquals("ended|end_date|2024-06-30", statusWithDate(role));
        assertEquals(0, openDisputes(role),
            "a still_there with no date of its own speaks to no moment, so there is nothing for "
            + "the record to be incompatible WITH");
    }

    @Test
    @DisplayName("leaving later without saying the manager stayed raises nothing")
    void aLaterDepartureAloneRaisesNothing() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", "2024-06-30", "accepted");
        insertClaimLinked(role, "2025-02-28", false);

        await(resolve(role));

        assertEquals("ended|end_date|2024-06-30", statusWithDate(role));
        assertEquals(0, openDisputes(role),
            "a contributor leaving in 2025 is a fact about the CONTRIBUTOR. Only still_there is a "
            + "statement about the manager, and this one does not make it");
    }

    @Test
    @DisplayName("a withdrawn claim raises nothing, as it grounds nothing")
    void aWithdrawnClaimRaisesNothing() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", "2024-06-30", "accepted");
        long claim = insertClaimLinked(role, "2025-02-28", true);
        await(pool.preparedQuery("UPDATE role_claims SET withdrawn_at = now() WHERE id = $1")
            .execute(Tuple.of(claim)));

        await(resolve(role));

        assertEquals("ended|end_date|2024-06-30", statusWithDate(role));
        assertEquals(0, openDisputes(role),
            "the tombstone is there to stop that contributor rating this employment twice and to "
            + "keep the audit trail. It is not an open question on their behalf");
    }

    @Test
    @DisplayName("the dispute is raised once, however many times resolution runs")
    void theDisputeIsRaisedOnce() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", "2024-06-30", "accepted");
        insertClaimLinked(role, "2025-02-28", true);

        await(resolve(role));
        await(resolve(role));
        await(resolve(role));

        assertEquals(1, openDisputes(role),
            "role_conflicts_one_open makes this the database's guarantee rather than a prior read: "
            + "one open task per disputed employment per kind, so a retry adds nothing");
        assertEquals(1, count("SELECT count(*) AS c FROM employment_status_events "
                              + "WHERE role_id = " + role + " AND to_status = 'disputed'"),
            "and the log records the transition once, not once per attempt");
    }

    @Test
    @DisplayName("a repeat statement that contradicts the record raises a dispute")
    void aContradictingRepeatRaisesADispute() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", "2024-06-30", "accepted");
        long claim = insertClaimLinked(role, "2021-03-31", true);
        RoleClaimService claims = new RoleClaimService();
        UUID who = claimOwner(claim);
        await(claims.recordInitialTestimony(pool, claim, who, "Depot Supervisor",
            "Halverson Logistics", LocalDate.parse("2019-01-01"), LocalDate.parse("2021-03-31"),
            true, true));
        await(resolve(role));
        assertEquals(0, openDisputes(role), "fixture: what they first said was compatible");

        // Two years later they remember leaving much later. The claim's own columns CANNOT change,
        // so this is a second statement rather than a correction.
        assertTrue(await(claims.recordRepeatTestimony(pool, claim, who, "Depot Supervisor",
            "Halverson Logistics", LocalDate.parse("2019-01-01"), LocalDate.parse("2025-02-28"),
            true, true)), "fixture: something new was said");
        await(resolve(role));

        assertEquals(1, openDisputes(role),
            "rev 268: a repeat 'may raise a moderation item when it CONTRADICTS the standing "
            + "resolution'. Detection therefore reads the LATEST thing each contributor said, not "
            + "only the frozen claim");
        assertEquals("2021-03-31", claimEndDate(claim),
            "and the claim itself is untouched, because what somebody said in 2021 did not become "
            + "false by being remembered differently later");
    }

    @Test
    @DisplayName("a repeat that merely differs raises nothing")
    void aMerelyDifferentRepeatRaisesNothing() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", "2024-06-30", "accepted");
        long claim = insertClaimLinked(role, "2021-03-31", true);
        RoleClaimService claims = new RoleClaimService();
        UUID who = claimOwner(claim);
        await(claims.recordInitialTestimony(pool, claim, who, "Depot Supervisor",
            "Halverson Logistics", LocalDate.parse("2019-01-01"), LocalDate.parse("2021-03-31"),
            true, true));
        await(claims.recordRepeatTestimony(pool, claim, who, "Depot Supervisor",
            "Halverson Logistics", LocalDate.parse("2019-01-01"), LocalDate.parse("2022-05-31"),
            true, true));

        await(resolve(role));

        assertEquals("ended|end_date|2024-06-30", statusWithDate(role));
        assertEquals(0, openDisputes(role),
            "remembering 2022 instead of 2021 is not incompatible with the job ending in 2024. "
            + "Differing is not contradicting, and the whole value of the rule is that it refuses "
            + "to confuse the two");
    }

    @Test
    @DisplayName("a dispute leaves a live rating live")
    void aDisputeDoesNotHoldARating() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", "2024-06-30", "accepted");
        long claim = insertClaimLinked(role, "2025-02-28", true);
        UUID review = await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, author, user_id, overall_rating,
                                     manager_company, manager_title, claim_id, role_basis,
                                     role_required, attribution_basis, disposition, live_since)
                VALUES ($1,'Ash B',$2,4,'Halverson Logistics','Depot Supervisor',$3,'claimed',
                        TRUE,'role','live',now())
                RETURNING id
                """).execute(Tuple.of(managerId, claimOwner(claim)).addValue(claim))
            .map(rs -> rs.iterator().next().getUUID("id")));

        await(resolve(role));
        assertEquals(1, openDisputes(role), "fixture: the dispute exists");

        assertEquals("live", await(pool.preparedQuery(
                "SELECT disposition FROM reviews WHERE id = $1").execute(Tuple.of(review))
            .map(rs -> rs.iterator().next().getString("disposition"))),
            "the rating stays published. Withholding somebody's opinion of a manager because two "
            + "people disagree about which YEAR the manager left would be punishing the reader "
            + "for a question that has no bearing on the opinion");
        assertTrue(awaitBool(pool.preparedQuery("SELECT rating_is_publishable($1) AS b")
                .execute(Tuple.of(review)).map(rs -> rs.iterator().next().getBoolean("b"))),
            "and the predicate agrees, which is what the COMMIT would have checked");
    }

    @Test
    @DisplayName("a disputed role is left alone by further derivation")
    void aDisputedRoleIsInert() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", "2024-06-30", "accepted");
        insertClaimLinked(role, "2025-02-28", true);
        await(resolve(role));
        assertEquals("disputed|(null)|(null)", statusWithDate(role), "fixture");
        int events = events(role);

        // More evidence arrives, of the kind that would otherwise resolve the role outright.
        insertClaimLinked(role, "2023-01-31", true);
        await(resolve(role));

        assertEquals("disputed|(null)|(null)", statusWithDate(role),
            "the question is open and only a moderator closes it. Deriving over the top would be "
            + "the system quietly picking a side, which is the one thing a dispute means it must "
            + "not do");
        assertEquals(events, events(role), "and nothing is logged, because nothing was decided");
    }

    @Test
    @DisplayName("new testimony contradicting a moderator's determination still raises a dispute")
    void aModeratorDeterminationCanBeContradicted() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", null, "accepted");
        await(pool.preparedQuery("""
                UPDATE career_history
                   SET employment_status = 'ended', status_basis = 'moderator',
                       status_as_of = ('2024-06-30'::date)::timestamp AT TIME ZONE 'UTC',
                       status_resolved_at = now()
                 WHERE id = $1
                """).execute(Tuple.of(role)));
        insertClaimLinked(role, "2025-02-28", true);

        await(resolve(role));

        assertEquals("disputed|(null)|(null)", statusWithDate(role),
            "a determination outranks DERIVATION, which is why nothing silently overwrote it. It "
            + "does not outrank new evidence that it cannot be true: rev 268 says a claim 'may "
            + "also contradict it', and then the question is open again");
        assertEquals(1, openDisputes(role));
    }

    @Test
    @DisplayName("two concurrent detections raise one dispute, not two")
    void concurrentDetectionRaisesOneDispute() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", "2024-06-30", "accepted");
        insertClaimLinked(role, "2025-02-28", true);

        Future<Void> a = pool.withTransaction(tx -> EmploymentStatusResolver.resolveRole(tx, role));
        Future<Void> b = pool.withTransaction(tx -> EmploymentStatusResolver.resolveRole(tx, role));
        await(Future.join(List.of(a, b)));

        assertEquals(1, openDisputes(role),
            "the unique index decides it, so the loser's insert is a no-op instead of a duplicate "
            + "queue item or a failed request");
        assertEquals("disputed|(null)|(null)", statusWithDate(role));
    }

    @Test
    @DisplayName("two unknown-period roles for one job share one open dispute")
    void twoRolesOnOneCaseKeyShareOneDispute() throws Exception {
        /*
          THE CASE THE DEDUP IS ACTUALLY FOR, and it is not a retry.

          role_conflicts is keyed on the disputed EMPLOYMENT: manager, employer, normalised title
          and period. career_unique_known_employment stops two live roles sharing that key only
          when the period is KNOWN, so two unknown-period roles for the same job legitimately
          share a case_key, and each can be contradicted independently.

          Without ON CONFLICT DO NOTHING the second one's raise violates
          role_conflicts_one_open and fails the request that triggered it, which could be an
          ordinary contributor's submission. With it, the second finds the open task and attaches
          its evidence to the same one, which is what "one open task per disputed employment"
          means.

          A retry does NOT reach this: once a role is 'disputed' the detection query stops
          matching it, so nothing tries to raise twice for one role. That is why the first version
          of this suite did not catch a missing dedup.
        */
        long first  = insertUnknownPeriodRole("Depot Supervisor", "2024-06-30");
        long second = insertUnknownPeriodRole("Depot Supervisor", "2024-06-30");
        assertNotEquals(first, second, "fixture: two distinct roles");
        long claimOne = insertClaimLinked(first, "2025-02-28", true);
        long claimTwo = insertClaimLinked(second, "2025-02-28", true);

        await(resolve(first));
        await(resolve(second));

        assertEquals("disputed|(null)|(null)", statusWithDate(first));
        assertEquals("disputed|(null)|(null)", statusWithDate(second),
            "the second role is disputed too, which means its raise did not fail");
        assertEquals(1, count("SELECT count(*) AS c FROM role_conflicts "
            + "WHERE kind = 'employment_status_dispute' AND resolved_at IS NULL"),
            "and the moderator has ONE task for this employment rather than two for what is, as "
            + "far as the key can tell, one job");
        assertEquals(2, count("""
                SELECT count(*) AS c FROM role_conflict_claims rcc
                  JOIN role_conflicts cf ON cf.id = rcc.conflict_id
                 WHERE cf.kind = 'employment_status_dispute' AND cf.resolved_at IS NULL
                """),
            "with both contributors' accounts attached to it as evidence");
        assertTrue(claimOne > 0 && claimTwo > 0);
    }

    // ── slice 4: the moderator's determination ──────────────────────────────

    @Test
    @DisplayName("an ordinary signed-in user cannot determine an employment status")
    void anOrdinaryUserCannotDetermine() throws Exception {
        long conflict = disputed();
        try {
            await(moderation.determineEmploymentStatus("auth0|contributor", conflict,
                "ended", LocalDate.parse("2024-06-30"), "I think it ended"));
            fail("an ordinary user must not be able to answer a moderation question");
        } catch (Exception expected) {
            assertTrue(String.valueOf(expected.getMessage()).toLowerCase().contains("forbidden")
                    || String.valueOf(expected.getCause()).toLowerCase().contains("forbidden"),
                "refused as forbidden, not by accident. Got: " + expected);
        }
        assertEquals(1, count("SELECT count(*) AS c FROM role_conflicts WHERE id = " + conflict
            + " AND resolved_at IS NULL"), "and the task is still open");
    }

    @Test
    @DisplayName("a moderator ends a disputed employment, naming themselves and their evidence")
    void aModeratorEndsADisputedEmployment() throws Exception {
        long conflict = disputed();
        long role = roleOfConflict(conflict);

        JsonObject out = await(moderation.determineEmploymentStatus(adminAuth0, conflict,
            "ended", LocalDate.parse("2024-06-30"), "payroll confirmed the leaving date"));

        assertFalse(out.getBoolean("alreadyDone"));
        assertEquals(1, (int) out.getInteger("rolesDetermined"));
        assertEquals("ended|moderator|2024-06-30", statusWithDate(role),
            "the determination is the answer, and it says where it came from");
        assertEquals(adminId(), statusResolvedBy(role),
            "and who. This is the ONE path that names a human, because it is the one where a human "
            + "decided rather than the resolution component deriving from a record");
        assertNull(claimBehind(role),
            "no single claim grounds it: the moderator read the whole case, and the conflict is "
            + "what records what they were looking at");
        assertEquals("status_determined", conflictResolution(conflict));
        assertEquals(adminId(), conflictResolvedBy(conflict));
        assertEquals("disputed->ended", newestStatusEvent(role), "and the log carries the answer");
        assertEquals(adminId(), newestEventActor(role),
            "with the moderator named on it, which the derived events deliberately never are");
    }

    @Test
    @DisplayName("a moderator can determine that the employment is ongoing")
    void aModeratorCanDetermineOngoing() throws Exception {
        long conflict = disputed();
        long role = roleOfConflict(conflict);

        await(moderation.determineEmploymentStatus(adminAuth0, conflict, "ongoing",
            LocalDate.now().minusMonths(2), "spoke to the employer, still in post"));

        assertEquals("ongoing", status(role).split("\\|")[0]);
        assertEquals("current", presented(role, EmploymentStatusResolver.FRESHNESS_MONTHS),
            "and it presents as Current, because the evidence is recent. The eighteen-month "
            + "window applies to a determination exactly as it does to a contributor's assertion: "
            + "a moderator's confirmation ages too");
    }

    @Test
    @DisplayName("a moderator can determine that nothing is known")
    void aModeratorCanDetermineUnknown() throws Exception {
        long conflict = disputed();
        long role = roleOfConflict(conflict);

        await(moderation.determineEmploymentStatus(adminAuth0, conflict, "unknown", null,
            "the two accounts cannot be reconciled from what we have"));

        assertEquals("unknown|moderator|(null)", statusWithDate(role),
            "which is different from 'no_evidence': a human looked at both accounts and concluded "
            + "that neither can be established, and the basis records that somebody decided");
        assertEquals("status_determined", conflictResolution(conflict),
            "and the question is closed even though the answer is that we do not know");
    }

    @Test
    @DisplayName("an asserting determination must say what date its evidence speaks to")
    void anAssertingDeterminationNeedsADate() throws Exception {
        assertRefused(disputed(), "ended", null, "it just ended", "evidence speaks to");
    }

    @Test
    @DisplayName("an 'unknown' determination cannot carry an evidence date")
    void unknownRefusesADate() throws Exception {
        assertRefused(disputed(), "unknown", LocalDate.parse("2024-06-30"), "no idea",
            "asserts nothing");
    }

    @Test
    @DisplayName("evidence cannot speak to a future date")
    void aFutureDateIsRefused() throws Exception {
        assertRefused(disputed(), "ended", LocalDate.now().plusDays(1), "crystal ball",
            "future date");
    }

    @Test
    @DisplayName("a determination must record why")
    void aDeterminationMustRecordWhy() throws Exception {
        assertRefused(disputed(), "ended", LocalDate.parse("2024-06-30"), "   ", "record why");
    }

    @Test
    @DisplayName("a status outside the model is refused")
    void anUnknownStatusIsRefused() throws Exception {
        assertRefused(disputed(), "probably_ended", LocalDate.parse("2024-06-30"), "hmm",
            "must be one of");
    }

    @Test
    @DisplayName("a refused determination changes nothing at all")
    void aRefusedDeterminationChangesNothing() throws Exception {
        long conflict = disputed();
        long role = roleOfConflict(conflict);
        int events = events(role);

        assertRefused(conflict, "ended", null, "no date given", "evidence speaks to");

        assertEquals("disputed|(null)|(null)", statusWithDate(role),
            "the employment is still disputed");
        assertEquals(1, count("SELECT count(*) AS c FROM role_conflicts WHERE id = " + conflict
            + " AND resolved_at IS NULL"), "the task is still open");
        assertEquals(events, events(role), "and nothing was logged");
    }

    @Test
    @DisplayName("a conflict of another kind is refused by this endpoint")
    void anotherKindIsRefused() throws Exception {
        long other = await(pool.preparedQuery("""
                INSERT INTO role_conflicts (kind, manager_id, company_id, title_key, period_key,
                                            manager_name, company_name, title)
                VALUES ('end_date_disagreement',$1,$2,normalise_title('Depot Supervisor'),
                        '2019-01-01','Priya Raghunathan','Halverson Logistics','Depot Supervisor')
                RETURNING id
                """).execute(Tuple.of(managerId, companyId))
            .map(rs -> rs.iterator().next().getLong("id")));

        assertRefused(other, "ended", LocalDate.parse("2024-06-30"), "wrong endpoint",
            "not an employment-status dispute");
    }

    @Test
    @DisplayName("determining twice is idempotent")
    void determiningTwiceIsIdempotent() throws Exception {
        long conflict = disputed();
        long role = roleOfConflict(conflict);

        await(moderation.determineEmploymentStatus(adminAuth0, conflict, "ended",
            LocalDate.parse("2024-06-30"), "payroll"));
        int events = events(role);
        String resolvedAt = conflictResolvedAt(conflict);

        JsonObject again = await(moderation.determineEmploymentStatus(adminAuth0, conflict,
            "ended", LocalDate.parse("2024-06-30"), "payroll"));

        assertTrue(again.getBoolean("alreadyDone"),
            "a double-click is not an error, and it is not a second decision either");
        assertEquals(events, events(role), "no second event");
        assertEquals(resolvedAt, conflictResolvedAt(conflict),
            "and the record of WHEN it was decided does not move, which is what makes the audit "
            + "trail worth reading");
    }

    @Test
    @DisplayName("one determination answers every role in the case")
    void theWholeCaseIsDetermined() throws Exception {
        long first  = insertUnknownPeriodRole("Depot Supervisor", "2024-06-30");
        long second = insertUnknownPeriodRole("Depot Supervisor", "2024-06-30");
        insertClaimLinked(first, "2025-02-28", true);
        insertClaimLinked(second, "2025-02-28", true);
        await(resolve(first));
        await(resolve(second));
        long conflict = count("SELECT count(*) AS c FROM role_conflicts WHERE resolved_at IS NULL")
            == 1 ? openConflictId() : -1;
        assertTrue(conflict > 0, "fixture: one open dispute covering both roles");

        JsonObject out = await(moderation.determineEmploymentStatus(adminAuth0, conflict,
            "ended", LocalDate.parse("2024-06-30"), "one job, one answer"));

        assertEquals(2, (int) out.getInteger("rolesDetermined"));
        assertEquals("ended|moderator|2024-06-30", statusWithDate(first));
        assertEquals("ended|moderator|2024-06-30", statusWithDate(second),
            "BOTH roles leave disputed. Resolving the task while leaving a sibling row disputed "
            + "would be the worst outcome available: the moderator believes they have answered, "
            + "the task is gone, and that row presents as Unknown for ever with nothing to point "
            + "at, because a disputed role is deliberately inert");
    }

    @Test
    @DisplayName("the conflict and the roles move together")
    void theConflictAndTheRolesMoveTogether() throws Exception {
        long conflict = disputed();
        long role = roleOfConflict(conflict);

        await(moderation.determineEmploymentStatus(adminAuth0, conflict, "ended",
            LocalDate.parse("2024-06-30"), "payroll"));

        assertEquals(0, count("SELECT count(*) AS c FROM career_history "
            + "WHERE id = " + role + " AND employment_status = 'disputed'"));
        assertEquals(0, count("SELECT count(*) AS c FROM role_conflicts "
            + "WHERE id = " + conflict + " AND resolved_at IS NULL"),
            "neither can be left behind: one transaction writes the determination, the event and "
            + "the resolution, or none of them happens");
    }

    @Test
    @DisplayName("the evidence stays attached to the answered dispute")
    void theEvidenceStaysOnTheConflict() throws Exception {
        long conflict = disputed();
        await(moderation.determineEmploymentStatus(adminAuth0, conflict, "ended",
            LocalDate.parse("2024-06-30"), "payroll"));

        assertEquals(1, count("SELECT count(*) AS c FROM role_conflict_claims "
            + "WHERE conflict_id = " + conflict + " AND role = 'evidence'"),
            "what the moderator was looking at is still on the record. A decision whose evidence "
            + "has been tidied away cannot be reviewed later");
    }

    @Test
    @DisplayName("a determination is not overwritten by later compatible testimony")
    void aDeterminationSurvivesLaterTestimony() throws Exception {
        long conflict = disputed();
        long role = roleOfConflict(conflict);
        await(moderation.determineEmploymentStatus(adminAuth0, conflict, "ended",
            LocalDate.parse("2024-06-30"), "payroll"));
        int events = events(role);

        /*
          TWO THINGS AT ONCE, and the first is the one that nearly broke this slice.

          The claim that CAUSED the dispute is still attached to this role and still says the
          manager was there until February 2025, which contradicts the determination. Re-raising
          on it would loop for ever: determine, re-dispute, determine. A determination answers the
          evidence that existed when it was made, so that claim is settled.

          And a further contributor whose account agrees with nothing in particular changes
          nothing either, because 2022 does not contradict a job ending in 2024.
        */
        insertClaimLinked(role, "2022-01-31", true);
        await(resolve(role));
        await(resolve(role));

        assertEquals("ended|moderator|2024-06-30", statusWithDate(role),
            "a contributor's assertion is evidence, and it does not replace a determination");
        assertEquals(events, events(role), "and nothing is logged, because nothing changed");
        assertEquals(0, openDisputes(role),
            "and no new task: the moderator's answer stands rather than coming straight back");
    }

    @Test
    @DisplayName("later CONTRADICTORY testimony reopens the question rather than overwriting it")
    void laterContradictoryTestimonyReopensTheQuestion() throws Exception {
        long conflict = disputed();
        long role = roleOfConflict(conflict);
        await(moderation.determineEmploymentStatus(adminAuth0, conflict, "ended",
            LocalDate.parse("2024-06-30"), "payroll"));
        assertEquals("ended|moderator|2024-06-30", statusWithDate(role), "fixture: determined");

        insertClaimLinked(role, "2025-07-31", true);
        await(resolve(role));

        assertEquals("disputed|(null)|(null)", statusWithDate(role),
            "new evidence that the determination cannot be true does not silently overwrite it "
            + "and is not silently ignored either: the question is open again, through the same "
            + "process as the first time");
        assertEquals(1, count("SELECT count(*) AS c FROM role_conflicts "
            + "WHERE kind = 'employment_status_dispute' AND resolved_at IS NULL "
            + "AND role_id = " + role), "and there is a new task, the old one staying answered");
    }

    @Test
    @DisplayName("an end date corrected in under an 'ongoing' determination reopens it too")
    void aCorrectedEndDateUnderAnOngoingDeterminationReopensIt() throws Exception {
        long conflict = disputed();
        long role = roleOfConflict(conflict);
        await(moderation.determineEmploymentStatus(adminAuth0, conflict, "ongoing",
            LocalDate.parse("2025-01-31"), "the employer confirmed they are still there"));
        assertEquals("ongoing|moderator|2025-01-31", statusWithDate(role), "fixture: determined");

        // An administrator now records that the job finished BEFORE the moderator's evidence date.
        // updated_at as well as the date, because that is what applyPatch does and it is how
        // the record says "this changed AFTER the determination was made".
        await(pool.preparedQuery("UPDATE career_history SET end_date = "
                + "('2024-06-30'::date)::timestamp AT TIME ZONE 'UTC', updated_at = now() "
                + "WHERE id = $1").execute(Tuple.of(role)));
        await(resolve(role));

        assertEquals("disputed|(null)|(null)", statusWithDate(role),
            "derivation skips a moderator-grounded row by design, so without this the profile "
            + "would go on saying Current while the employment record under it said the job had "
            + "already finished: two public statements about one manager, disagreeing");
    }

    @Test
    @DisplayName("a determination does not change a rating's disposition")
    void aDeterminationLeavesRatingsAlone() throws Exception {
        long conflict = disputed();
        long role = roleOfConflict(conflict);
        long claim = await(pool.preparedQuery("SELECT rcc.claim_id AS c FROM role_conflict_claims "
                + "rcc WHERE rcc.conflict_id = $1 LIMIT 1").execute(Tuple.of(conflict))
            .map(rs -> rs.iterator().next().getLong("c")));
        UUID review = await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, author, user_id, overall_rating,
                                     manager_company, manager_title, claim_id, role_basis,
                                     role_required, attribution_basis, disposition, live_since)
                VALUES ($1,'Ash B',$2,4,'Halverson Logistics','Depot Supervisor',$3,'claimed',
                        TRUE,'role','live',now())
                RETURNING id
                """).execute(Tuple.of(managerId, claimOwner(claim)).addValue(claim))
            .map(rs -> rs.iterator().next().getUUID("id")));

        await(moderation.determineEmploymentStatus(adminAuth0, conflict, "ended",
            LocalDate.parse("2024-06-30"), "payroll"));

        assertEquals("live", await(pool.preparedQuery(
                "SELECT disposition FROM reviews WHERE id = $1").execute(Tuple.of(review))
            .map(rs -> rs.iterator().next().getString("disposition"))),
            "the dispute never held it, so answering the dispute does not release it. There is "
            + "nothing to release, which is what the kind being non-blocking means");
        assertTrue(role > 0);
    }

    // ── concurrency, drift, and the seed cleanup ────────────────────────────

    @Test
    @DisplayName("two concurrent resolutions of one role are serialised, not interleaved")
    void concurrentResolutionsAreSerialised() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", null, "accepted");
        insertClaimLinked(role, "2024-03-31", true);

        // Two transactions, two connections, started together. The lock inside resolveRole is
        // what makes the second derive from the first's result rather than from a snapshot taken
        // before it: without it, the loser would overwrite the winner with staler evidence.
        Future<Void> a = pool.withTransaction(tx -> EmploymentStatusResolver.resolveRole(tx, role));
        Future<Void> b = pool.withTransaction(tx -> EmploymentStatusResolver.resolveRole(tx, role));
        await(Future.join(List.of(a, b)));

        assertEquals("ongoing|contributor_claim|2024-03-31", statusWithDate(role),
            "both derived the same evidence, so the answer is the same either way round");
        assertEquals(1, events(role),
            "and only one of them wrote anything. The second found the row already saying it and "
            + "its UPDATE matched zero rows, which is the same guard that makes a retry free");
    }

    @Test
    @DisplayName("a resolution that starts before a newer claim lands still reads it")
    void theLockMakesTheSecondResolutionSeeTheFirstsEvidence() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", null, "accepted");
        insertClaimLinked(role, "2023-01-31", true);
        await(resolve(role));
        assertEquals("ongoing|contributor_claim|2023-01-31", statusWithDate(role), "fixture");

        /*
          THE INTERLEAVING THE LOCK EXISTS FOR, built deliberately rather than hoped for.

          The resolution is one UPDATE whose evidence comes from a CTE, and a CTE is evaluated ONCE
          at statement start. So a resolution whose statement begins before a newer claim is
          committed would compute its evidence from the older snapshot, block on the row lock at
          the UPDATE, and then write that stale answer over the newer one. PostgreSQL's
          re-evaluation substitutes the updated target row; it does not recompute the CTE.

          Taking the lock FIRST, in its own statement, is what prevents this: the second resolution
          waits before deriving anything, so its CTE is evaluated after the first has committed.

          A holds the lock and adds later evidence without committing. B then tries to resolve and
          must wait. Only when A commits may B derive, and it must then see A's claim.
        */
        SqlConnection a = await(pool.getConnection());
        try {
            await(a.begin());
            await(a.preparedQuery("SELECT id FROM career_history WHERE id = $1 FOR UPDATE")
                .execute(Tuple.of(role)));
            long later = insertClaimLinkedOn(a, role, "2025-02-28", true);
            assertTrue(later > 0, "fixture: the later claim exists, uncommitted");

            Future<Void> b = pool.withTransaction(tx ->
                EmploymentStatusResolver.resolveRole(tx, role));

            // B must be waiting on the lock, not finished. Bounded, and a B that has somehow
            // finished early is reported rather than silently tolerated, because then this test
            // would not be exercising the interleaving it is named for.
            for (int i = 0; i < 20 && !b.isComplete(); i++) Thread.sleep(50);
            assertFalse(b.isComplete(),
                "B should still be blocked on the row lock A holds. If it is not, this test is no "
                + "longer exercising the race and the lock it checks is unprotected");

            await(a.query("COMMIT").execute());
            await(b);
        } finally {
            a.close();
        }

        assertEquals("ongoing|contributor_claim|2025-02-28", statusWithDate(role),
            "B derived AFTER A committed, so it read the later departure. Without the lock it "
            + "would have computed its evidence from the older snapshot and written 2023 over 2025");
    }

    @Test
    @DisplayName("erasing the contributor behind a status leaves the status standing")
    void erasureLeavesTheResolutionIntact() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", null, "accepted");
        long claim = insertClaimLinked(role, "2024-03-31", true);
        await(resolve(role));
        assertEquals("ongoing|contributor_claim|2024-03-31", statusWithDate(role), "fixture");

        UUID who = await(pool.preparedQuery("SELECT user_id FROM role_claims WHERE id = $1")
            .execute(Tuple.of(claim)).map(rs -> rs.iterator().next().getUUID("user_id")));
        assertNotNull(who, "fixture: the claim names its contributor");

        await(pool.preparedQuery("DELETE FROM users WHERE id = $1").execute(Tuple.of(who)));

        assertEquals("ongoing|contributor_claim|2024-03-31", statusWithDate(role),
            "the account goes, the assertion stays. role_claims.user_id is ON DELETE SET NULL, so "
            + "the claim survives with its period intact and the employment it grounds is "
            + "unaffected: erasure removes who said it, not that it was said");
        assertEquals(claim, claimBehind(role), "and the status still names the claim it came from");

        await(resolve(role));
        assertEquals(1, events(role),
            "and a resolution after the erasure finds the same evidence, so it writes nothing");
    }

    @Test
    @DisplayName("the drift query finds an accepted ended role that nothing resolved")
    void theDriftQueryFindsAnUnresolvedRole() throws Exception {
        long resolved = insertRole("Depot Supervisor", "2019-01-01", "2022-12-31", "accepted");
        await(resolve(resolved));
        assertEquals(List.of(), await(managers.driftingEmploymentStatuses()),
            "a resolved role does not drift");

        // Exactly what a path that forgot to resolve leaves behind.
        long forgotten = insertRole("Yard Lead", "2015-01-01", "2017-01-01", "accepted");
        assertEquals(List.of(forgotten), await(managers.driftingEmploymentStatuses()),
            "an accepted role carrying an end date and not resolved as ended means a writing path "
            + "reached career_history without resolving. The sweep reports it rather than repairing "
            + "it, because repairing it would hide the defect and leave the path broken");

        long open = insertRole("Night Lead", "2021-01-01", null, "accepted");
        await(resolve(open));
        assertEquals(List.of(forgotten), await(managers.driftingEmploymentStatuses()),
            "and an accepted role with no end date is NOT drift: it is the commonest correct row "
            + "in the table, because acceptance is not currency");
    }

    @Test
    @DisplayName("the seed cleanup skips a manager whose employment history forbids deletion")
    void theSeedCleanupSkipsWhatItCannotDelete() throws Exception {
        long held = insertSeedManager("Seed Held", "seed-held", "seed_held");
        long heldRole = await(pool.preparedQuery(
                "INSERT INTO career_history (manager_id, company, title, start_date, end_date, "
                + "company_id, period_known, state, source) VALUES "
                + "($1,'Halverson Logistics','Lead',('2018-01-01'::date)::timestamp AT TIME ZONE "
                + "'UTC',('2020-01-01'::date)::timestamp AT TIME ZONE 'UTC',$2,TRUE,'accepted',"
                + "'import') RETURNING id")
            .execute(Tuple.of(held, companyId))
            .map(rs -> rs.iterator().next().getLong("id")));
        await(resolve(heldRole));
        assertEquals(1, events(heldRole), "fixture: this manager's role now has status history");

        long deletable = insertSeedManager("Seed Free", "seed-free", "seed_free");

        managers.deleteFakeManagerInBackground();
        // The job is fire-and-forget by design, so this waits on the OUTCOME rather than on it.
        for (int i = 0; i < 50 && count("SELECT count(*) AS c FROM managers WHERE id = "
                                        + deletable) > 0; i++) {
            Thread.sleep(100);
        }

        assertEquals(0, count("SELECT count(*) AS c FROM managers WHERE id = " + deletable),
            "the job must still drain the seed population: it picks a manager it CAN delete");
        assertEquals(1, count("SELECT count(*) AS c FROM managers WHERE id = " + held),
            "and leaves the one it cannot. Without this the ordering is deterministic, so the "
            + "same undeletable manager is chosen for ever, every call prints one stderr line, and "
            + "a cleanup that drains nothing looks exactly like one that is working");
        assertEquals(1, events(heldRole), "its history is intact, which is the point of RESTRICT");
    }

    @Test
    @DisplayName("resolving a status does not touch a rating's disposition")
    void publicationIsUnaffected() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", "2022-12-31", "accepted");
        UUID review = await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, author, user_id, overall_rating,
                                     manager_company, manager_title,
                                     disposition, role_basis, attribution_basis)
                VALUES ($1,'Anonymous User',$2,4,'Halverson Logistics','Depot Supervisor',
                        'live','legacy','none') RETURNING id
                """).execute(Tuple.of(managerId, contributor))
            .map(rs -> rs.iterator().next().getUUID("id")));

        await(resolve(role));

        assertEquals("live", await(pool.preparedQuery(
                "SELECT disposition FROM reviews WHERE id = $1").execute(Tuple.of(review))
            .map(rs -> rs.iterator().next().getString("disposition"))),
            "employment status is display-only and derived. It has no path to publication "
            + "eligibility, to a rating average or to a company figure, and this is the test that "
            + "says so rather than the comment that claims it");
    }

    @Test
    @DisplayName("the freshness window is eighteen months, in one named place")
    void theFreshnessWindowIsEighteenMonths() throws Exception {
        assertEquals(18, EmploymentStatusResolver.FRESHNESS_MONTHS,
            "rev 268's freshness policy. A judgement, written down as one: longer than the "
            + "contribution cadence so a label does not flicker while nothing has changed, and "
            + "shorter than typical tenure so it never asserts Current for somebody who has left");

        long role = insertRole("Depot Supervisor", "2019-01-01", null, "accepted");
        insertClaimLinked(role, LocalDate.now().minusMonths(6).toString(), true);
        await(resolve(role));

        assertEquals("current", presented(role, EmploymentStatusResolver.FRESHNESS_MONTHS));
        assertEquals("unknown", presented(role, 3),
            "the window is a PARAMETER, so the same stored row presents differently under a "
            + "different policy and nothing has to be rewritten to change it");
        assertEquals("ongoing", status(role).split("\\|")[0],
            "and the STORED status still says ongoing: freshness is applied when it is read, never "
            + "by a sweep over these rows, because rewriting them would destroy the record of what "
            + "was established and make the event log lie");
    }

    // ── what the moderator is shown before deciding (slice 7) ───────────────

    /**
     * The screen's promise and the write's selection are the same set of rows.
     *
     * <p>This is the test that matters most in slice 7, and it is not about rendering. A
     * determination applies to the CASE, so the detail a moderator reads has to name exactly the
     * records their click will move. Two copies of that predicate - one in the detail query, one
     * in the write - would eventually disagree, and a moderator would be told they were answering
     * two records while four changed, or two while one stayed disputed for ever.
     *
     * <p>So the assertion is a comparison between the two, not an expectation of a number.
     */
    @Test
    @DisplayName("the case the detail promises is the case the determination writes")
    void theCaseTheDetailPromisesIsTheCaseTheDeterminationWrites() throws Exception {
        long first  = insertUnknownPeriodRole("Depot Supervisor", "2024-06-30");
        long second = insertUnknownPeriodRole("Depot Supervisor", "2024-06-30");
        // A third record in the same case that is NOT disputed: same manager, employer, title and
        // unknown period, with no contradicting claim. The determination must leave it alone.
        long third  = insertUnknownPeriodRole("Depot Supervisor", "2024-06-30");
        insertClaimLinked(first, "2025-02-28", true);
        insertClaimLinked(second, "2025-02-28", true);
        await(resolve(first));
        await(resolve(second));
        await(resolve(third));
        long conflict = openConflictId();

        JsonObject detail = await(moderation.conflictDetail(adminAuth0, conflict));
        List<Long> promised = new ArrayList<>();
        List<Long> leftAlone = new ArrayList<>();
        for (int i = 0; i < detail.getJsonArray("caseEmployments").size(); i++) {
            JsonObject row = detail.getJsonArray("caseEmployments").getJsonObject(i);
            (row.getBoolean("willBeDetermined") ? promised : leftAlone).add(row.getLong("roleId"));
        }
        assertEquals(List.of(first, second), promised,
            "fixture: the two disputed records in the case");
        assertEquals(List.of(third), leftAlone);

        JsonObject out = await(moderation.determineEmploymentStatus(adminAuth0, conflict, "ended",
            LocalDate.parse("2024-06-30"), "one job, one answer"));

        assertEquals(promised.size(), (int) out.getInteger("rolesDetermined"),
            "exactly as many records moved as the screen said would");
        for (long role : promised) {
            assertEquals("ended|moderator|2024-06-30", statusWithDate(role),
                "role " + role + " was promised and had to move");
        }
        assertEquals("ended|end_date|2024-06-30", statusWithDate(third),
            "and the record the screen said it would leave alone keeps the answer it had, with "
            + "its own basis. Overwriting it would be taking a decision nobody asked for on a "
            + "record nobody disputed");
    }

    @Test
    @DisplayName("each record in the case says what it currently answers and why")
    void eachRecordInTheCaseSaysWhatItAnswers() throws Exception {
        long role = insertUnknownPeriodRole("Depot Supervisor", "2024-06-30");
        long settled = insertUnknownPeriodRole("Depot Supervisor", "2024-06-30");
        insertClaimLinked(role, "2025-02-28", true);
        await(resolve(role));
        await(resolve(settled));
        long conflict = openConflictId();

        JsonObject detail = await(moderation.conflictDetail(adminAuth0, conflict));
        JsonObject disputed = caseRow(detail, role);
        JsonObject other    = caseRow(detail, settled);

        assertEquals("disputed", disputed.getString("employmentStatus"));
        assertNull(disputed.getString("statusBasis"),
            "a disputed record asserts nothing: the conflict holds the context and the event log "
            + "holds what the answer was before");
        assertNull(disputed.getString("statusAsOf"));

        // The sibling's own answer, which is the thing a moderator needs in order not to assume
        // the case is unanimous. One may already carry somebody else's determination.
        assertEquals("ended", other.getString("employmentStatus"));
        assertEquals("end_date", other.getString("statusBasis"));
        assertTrue(String.valueOf(other.getString("statusAsOf")).startsWith("2024-06-30"),
            "the date the EVIDENCE speaks to, which for a record's end date is that date itself. "
            + "Got: " + other.getString("statusAsOf"));
    }

    /**
     * The evidence a moderator reads is what the contributor says NOW.
     *
     * <p>Somebody can revise their recollection, and V111 files the revision as new testimony
     * rather than as a correction, because both statements are evidence and neither makes the
     * other false. Detection reads the latest one. If the screen showed the claim's frozen
     * assertion instead, a moderator would be shown a contradiction nobody is making any more and
     * would be overriding a statement that had already been withdrawn in substance.
     */
    @Test
    @DisplayName("the detail reads the latest testimony, not the claim's frozen assertion")
    void theDetailReadsTheLatestTestimony() throws Exception {
        long conflict = disputed();
        long claim = soleEvidenceClaim(conflict);
        // What they said when they submitted, mirrored as the first entry in the series.
        insertTestimony(claim, "2025-02-28", true, "initial");
        // And what they said later, which is a different date for the same employment.
        insertTestimony(claim, "2026-01-31", true, "repeat");

        JsonObject c = claimRow(await(moderation.conflictDetail(adminAuth0, conflict)), claim);

        assertEquals("2026-01-31", c.getString("leftOn"),
            "the latest statement, which is the one detection weighed");
        assertTrue(c.getBoolean("restated"),
            "and the moderator is told it IS a revision, because a contributor who has changed "
            + "their account twice is different evidence from one who has said one thing once");
        assertEquals("2025-02-28", c.getString("assertedEnd"),
            "while the claim's own assertion is still there and unchanged. Both are available and "
            + "neither overwrites the other: claim_assertion_is_immutable would refuse it anyway");
        assertTrue(c.getBoolean("stillThere"));
    }

    @Test
    @DisplayName("a claim with no testimony series falls back to its own assertion")
    void aClaimWithNoTestimonyFallsBackToItsAssertion() throws Exception {
        // Claims predating V111 have no series at all. Reading them as "says nothing" would empty
        // the evidence out of every dispute raised on one.
        long conflict = disputed();
        long claim = soleEvidenceClaim(conflict);

        JsonObject c = claimRow(await(moderation.conflictDetail(adminAuth0, conflict)), claim);

        assertEquals("2025-02-28", c.getString("leftOn"));
        assertTrue(c.getBoolean("stillThere"));
        assertFalse(c.getBoolean("restated"), "nothing was restated");
        assertNull(c.getString("saidAt"));
    }

    @Test
    @DisplayName("a withdrawn claim is shown, and marked as grounding nothing")
    void aWithdrawnClaimIsShownAndMarked() throws Exception {
        long conflict = disputed();
        long claim = soleEvidenceClaim(conflict);
        await(pool.preparedQuery("UPDATE role_claims SET withdrawn_at = now() WHERE id = $1")
            .execute(Tuple.of(claim)));

        JsonObject c = claimRow(await(moderation.conflictDetail(adminAuth0, conflict)), claim);

        assertTrue(c.getBoolean("withdrawn"),
            "hiding it would hide why the queue item exists, and leaving it unmarked would have a "
            + "moderator overriding contributors on the strength of a statement that has been "
            + "withdrawn. EmploymentStatusResolver already excludes it from grounding a status; "
            + "this is the same fact, said to the person deciding");
    }

    @Test
    @DisplayName("a conflict of another kind carries no case records at all")
    void anotherKindCarriesNoCaseRecords() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", "2024-06-30", "accepted");
        // A non-blocking kind, because a blocking one is refused without a subject claim, and
        // the point here is the kind rather than what it holds: no other kind is keyed on a case.
        long conflict = await(pool.preparedQuery("""
                INSERT INTO role_conflicts (kind, manager_id, company_id, title_key, period_key,
                                            role_id, manager_name, company_name, title, period)
                SELECT 'end_date_disagreement', ch.manager_id, ch.company_id, ch.title_key,
                       COALESCE(ch.start_day::text,'unknown'), ch.id, 'Priya Raghunathan',
                       'Halverson Logistics', ch.title, '2019'
                  FROM career_history ch WHERE ch.id = $1
                RETURNING id
                """).execute(Tuple.of(role)).map(rs -> rs.iterator().next().getLong("id")));

        JsonObject detail = await(moderation.conflictDetail(adminAuth0, conflict));

        assertEquals(0, detail.getJsonArray("caseEmployments").size(),
            "every other kind decides the role the queue item names and nothing else. Listing a "
            + "case for one would offer a moderator rows that no decision there touches");
        assertEquals("end_date_disagreement", detail.getString("kind"),
            "and the rest of the detail is exactly what it was");
    }

    @Test
    @DisplayName("an ordinary user cannot read a dispute's case")
    void anOrdinaryUserCannotReadADisputesCase() throws Exception {
        long conflict = disputed();
        try {
            await(moderation.conflictDetail("auth0|contributor", conflict));
            fail("the case names every employment record under one manager and employer, and the "
                 + "evidence names what contributors said; it is admin-only like the rest of this "
                 + "service");
        } catch (Exception expected) {
            // Checked, rather than accepting any failure: a test that passes on a NullPointer
            // would still pass with the authorization removed.
            assertTrue(("" + expected.getMessage() + expected.getCause()).contains("Forbidden"),
                "refused, but check it was refused for authorization: "
                + expected.getMessage() + " / " + expected.getCause());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** One row of a conflict detail's case list, by the role it is about. */
    private JsonObject caseRow(JsonObject detail, long roleId) {
        JsonArray rows = detail.getJsonArray("caseEmployments");
        for (int i = 0; i < rows.size(); i++) {
            if (rows.getJsonObject(i).getLong("roleId") == roleId) return rows.getJsonObject(i);
        }
        return fail("no case record for role " + roleId + " in " + rows.encode());
    }

    private JsonObject claimRow(JsonObject detail, long claimId) {
        JsonArray rows = detail.getJsonArray("claims");
        for (int i = 0; i < rows.size(); i++) {
            if (rows.getJsonObject(i).getLong("claimId") == claimId) return rows.getJsonObject(i);
        }
        return fail("no claim " + claimId + " in " + rows.encode());
    }

    private long soleEvidenceClaim(long conflictId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT claim_id FROM role_conflict_claims WHERE conflict_id = $1")
            .execute(Tuple.of(conflictId)).map(rs -> rs.iterator().next().getLong("claim_id")));
    }

    /**
     * A statement in a claim's series, said at a time that orders after the ones before it.
     *
     * <p>said_at is set explicitly rather than defaulted, because two rows inserted in the same
     * millisecond would make "the latest" depend on the id tiebreak alone and the test would be
     * asserting the fixture's insert order rather than the query's ordering rule.
     */
    private void insertTestimony(long claimId, String endIso, boolean stillThere, String occasion)
            throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO claim_testimony (claim_id, user_id, title, end_date, period_known,
                                             still_there, occasion, said_at)
                SELECT $1, rc.user_id, 'Depot Supervisor', $2, TRUE, $3, $4,
                       now() + ($5 || ' seconds')::interval
                  FROM role_claims rc WHERE rc.id = $1
                """).execute(Tuple.of(claimId, LocalDate.parse(endIso)).addValue(stillThere)
                .addValue(occasion).addValue("initial".equals(occasion) ? "0" : "1")));
    }

    private Future<Void> resolve(long roleId) {
        return EmploymentStatusResolver.resolveRoleAtomically(pool, roleId);
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

    private long insertSeedManager(String name, String slug, String externalId) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO managers (name, company, title, status, approval_status, country, "
                + "company_id, slug, external_id, overall_rating, reviews_count, category_averages) "
                + "VALUES ($1,'Halverson Logistics','Lead','active','ghost','CA',$2,$3,$4,0,0,"
                + "'{}'::jsonb) RETURNING id")
            .execute(Tuple.of(name, companyId, slug).addValue(externalId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private UUID insertUser(String auth0Id, String role) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO users (auth0_id, username, role) VALUES ($1,$2,$3) RETURNING id")
            .execute(Tuple.of(auth0Id, "u" + Math.abs(auth0Id.hashCode()), role))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private long insertRole(String title, String startIso, String endIso, String state)
            throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO career_history (manager_id, company, title, start_date, end_date, "
                + "company_id, period_known, state, source) "
                + "VALUES ($1,'Halverson Logistics',$2,($3::date)::timestamp AT TIME ZONE 'UTC',"
                + "($4::date)::timestamp AT TIME ZONE 'UTC',$5,TRUE,$6,'contributor') RETURNING id")
            .execute(Tuple.of(managerId, title, LocalDate.parse(startIso))
                .addValue(endIso == null ? null : LocalDate.parse(endIso))
                .addValue(companyId).addValue(state))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    /** A finalized claim with no role yet, which is what a contributor's submission leaves. */
    private long insertClaim(String title, String endIso, boolean stillThere) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO role_claims (proposal_key, manager_id, company_id, title, start_date,
                                          end_date, period_known, still_there, source, user_id,
                                          finalized_at, original_manager_id)
                VALUES (gen_random_uuid(),$1,$2,$3,'2019-01-01'::date,$4,$5,TRUE,'contributor',
                        $6,now(),$1)
                RETURNING id
                """)
            .execute(Tuple.of(managerId, companyId, title)
                .addValue(endIso == null ? null : LocalDate.parse(endIso))
                .addValue(endIso != null)
                .addValue(stillThere ? contributor : null))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    /**
     * A claim already resolved onto a role, for the cases that are about the DERIVATION rather
     * than about the hook. Each gets its own contributor, because one contributor may hold only
     * one claim per role.
     */
    private long insertClaimLinked(long roleId, String endIso, boolean stillThere)
            throws Exception {
        UUID who = insertUser("auth0|" + UUID.randomUUID(), "user");
        return await(pool.preparedQuery("""
                INSERT INTO role_claims (proposal_key, manager_id, company_id, title, start_date,
                                          end_date, period_known, still_there, source, user_id,
                                          finalized_at, original_manager_id,
                                          resolved_role_id, resolution, resolved_at)
                VALUES (gen_random_uuid(),$1,$2,'Depot Supervisor','2019-01-01'::date,$3,TRUE,$4,
                        'contributor',$5,now(),$1,$6,'linked',now())
                RETURNING id
                """)
            .execute(Tuple.of(managerId, companyId,
                    endIso == null ? null : LocalDate.parse(endIso))
                .addValue(stillThere).addValue(who).addValue(roleId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    /** As above, on a connection the caller controls, so it can stay uncommitted. */
    private long insertClaimLinkedOn(SqlConnection conn, long roleId, String endIso,
                                     boolean stillThere) throws Exception {
        UUID who = insertUser("auth0|" + UUID.randomUUID(), "user");
        return await(conn.preparedQuery("""
                INSERT INTO role_claims (proposal_key, manager_id, company_id, title, start_date,
                                          end_date, period_known, still_there, source, user_id,
                                          finalized_at, original_manager_id,
                                          resolved_role_id, resolution, resolved_at)
                VALUES (gen_random_uuid(),$1,$2,'Depot Supervisor','2019-01-01'::date,$3,TRUE,$4,
                        'contributor',$5,now(),$1,$6,'linked',now())
                RETURNING id
                """)
            .execute(Tuple.of(managerId, companyId, LocalDate.parse(endIso))
                .addValue(stillThere).addValue(who).addValue(roleId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    /**
     * A role whose period is NOT known, which is how two roles come to share a case_key.
     *
     * <p>{@code career_unique_known_employment} is partial on {@code period_known}, so the
     * uniqueness that stops two live roles describing one employment does not apply here, and an
     * unknown start date makes {@code period_key} 'unknown' for both.
     */
    private long insertUnknownPeriodRole(String title, String endIso) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO career_history (manager_id, company, title, start_date, end_date, "
                + "company_id, period_known, state, source) "
                + "VALUES ($1,'Halverson Logistics',$2,NULL,"
                + "($3::date)::timestamp AT TIME ZONE 'UTC',$4,FALSE,'accepted','contributor') "
                + "RETURNING id")
            .execute(Tuple.of(managerId, title, LocalDate.parse(endIso)).addValue(companyId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    /** A disputed employment with one contradicting claim, and the conflict that was raised. */
    private long disputed() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", "2024-06-30", "accepted");
        insertClaimLinked(role, "2025-02-28", true);
        await(resolve(role));
        return await(pool.preparedQuery("SELECT id FROM role_conflicts "
                + "WHERE kind = 'employment_status_dispute' AND resolved_at IS NULL "
                + "AND role_id = $1").execute(Tuple.of(role))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long openConflictId() throws Exception {
        return await(pool.query("SELECT id FROM role_conflicts WHERE resolved_at IS NULL LIMIT 1")
            .execute().map(rs -> rs.iterator().next().getLong("id")));
    }

    private long roleOfConflict(long conflictId) throws Exception {
        return await(pool.preparedQuery("SELECT role_id FROM role_conflicts WHERE id = $1")
            .execute(Tuple.of(conflictId)).map(rs -> rs.iterator().next().getLong("role_id")));
    }

    private UUID adminId() throws Exception {
        return await(pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(adminAuth0)).map(rs -> rs.iterator().next().getUUID("id")));
    }

    private UUID statusResolvedBy(long roleId) throws Exception {
        return await(pool.preparedQuery("SELECT status_resolved_by FROM career_history WHERE id=$1")
            .execute(Tuple.of(roleId))
            .map(rs -> rs.iterator().next().getUUID("status_resolved_by")));
    }

    private UUID newestEventActor(long roleId) throws Exception {
        return await(pool.preparedQuery("SELECT \"by\" FROM employment_status_events "
                + "WHERE role_id = $1 ORDER BY id DESC LIMIT 1").execute(Tuple.of(roleId))
            .map(rs -> rs.iterator().next().getUUID("by")));
    }

    private String conflictResolution(long conflictId) throws Exception {
        return await(pool.preparedQuery("SELECT resolution FROM role_conflicts WHERE id = $1")
            .execute(Tuple.of(conflictId)).map(rs -> rs.iterator().next().getString("resolution")));
    }

    private UUID conflictResolvedBy(long conflictId) throws Exception {
        return await(pool.preparedQuery("SELECT resolved_by FROM role_conflicts WHERE id = $1")
            .execute(Tuple.of(conflictId)).map(rs -> rs.iterator().next().getUUID("resolved_by")));
    }

    private String conflictResolvedAt(long conflictId) throws Exception {
        return await(pool.preparedQuery("SELECT resolved_at::text AS s FROM role_conflicts "
                + "WHERE id = $1").execute(Tuple.of(conflictId))
            .map(rs -> rs.iterator().next().getString("s")));
    }

    /** Asserts the determination is refused, and refused for the stated reason. */
    private void assertRefused(long conflictId, String status, LocalDate asOf, String note,
                               String expectedInMessage) throws Exception {
        try {
            await(moderation.determineEmploymentStatus(adminAuth0, conflictId, status, asOf, note));
            fail("should have been refused: " + status + " as of " + asOf);
        } catch (Exception err) {
            String message = String.valueOf(err.getMessage()) + String.valueOf(err.getCause());
            assertTrue(message.contains(expectedInMessage),
                "refused, but not for the expected reason (" + expectedInMessage + "), which "
                + "means this test may be passing by accident. Got: " + message);
        }
    }

    private int openDisputes(long roleId) throws Exception {
        return count("SELECT count(*) AS c FROM role_conflicts "
            + "WHERE kind = 'employment_status_dispute' AND resolved_at IS NULL "
            + "AND role_id = " + roleId);
    }

    private boolean disputeBlocksPublication(long roleId) throws Exception {
        return awaitBool(pool.preparedQuery("SELECT blocks_publication AS b FROM role_conflicts "
                + "WHERE kind = 'employment_status_dispute' AND role_id = $1")
            .execute(Tuple.of(roleId)).map(rs -> rs.iterator().next().getBoolean("b")));
    }

    /** Whether the claim is attached as subject or as evidence, which decides if a rating is held. */
    private String disputeClaimRole(long roleId, long claimId) throws Exception {
        return await(pool.preparedQuery("""
                SELECT rcc.role AS r FROM role_conflict_claims rcc
                  JOIN role_conflicts cf ON cf.id = rcc.conflict_id
                 WHERE cf.kind = 'employment_status_dispute' AND cf.role_id = $1
                   AND rcc.claim_id = $2
                """).execute(Tuple.of(roleId, claimId))
            .map(rs -> rs.iterator().hasNext() ? rs.iterator().next().getString("r") : null));
    }

    private String newestStatusEvent(long roleId) throws Exception {
        return await(pool.preparedQuery("SELECT COALESCE(from_status,'(null)') || '->' || to_status "
                + "AS s FROM employment_status_events WHERE role_id = $1 ORDER BY id DESC LIMIT 1")
            .execute(Tuple.of(roleId)).map(rs -> rs.iterator().next().getString("s")));
    }

    private UUID claimOwner(long claimId) throws Exception {
        return await(pool.preparedQuery("SELECT user_id FROM role_claims WHERE id = $1")
            .execute(Tuple.of(claimId)).map(rs -> rs.iterator().next().getUUID("user_id")));
    }

    private String claimEndDate(long claimId) throws Exception {
        return await(pool.preparedQuery("SELECT end_date::text AS s FROM role_claims WHERE id = $1")
            .execute(Tuple.of(claimId)).map(rs -> rs.iterator().next().getString("s")));
    }

    private static java.time.OffsetDateTime day(String isoDate) {
        return java.time.OffsetDateTime.parse(isoDate + "T00:00:00Z");
    }

    /** The role this manager gained most recently, for the repository writes that return no id. */
    private long newestRole() throws Exception {
        return await(pool.preparedQuery("SELECT id FROM career_history WHERE manager_id = $1 "
                + "ORDER BY id DESC LIMIT 1").execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private String status(long roleId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT employment_status || '|' || COALESCE(status_basis,'(null)') AS s "
                + "FROM career_history WHERE id = $1")
            .execute(Tuple.of(roleId)).map(rs -> rs.iterator().next().getString("s")));
    }

    private String statusWithDate(long roleId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT employment_status || '|' || COALESCE(status_basis,'(null)') || '|' "
                + "|| COALESCE(status_as_of::date::text,'(null)') AS s "
                + "FROM career_history WHERE id = $1")
            .execute(Tuple.of(roleId)).map(rs -> rs.iterator().next().getString("s")));
    }

    private Long claimBehind(long roleId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT status_claim_id FROM career_history WHERE id = $1")
            .execute(Tuple.of(roleId)).map(rs -> rs.iterator().next().getLong(0)));
    }

    private String resolvedAt(long roleId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT status_resolved_at::text AS s FROM career_history WHERE id = $1")
            .execute(Tuple.of(roleId)).map(rs -> rs.iterator().next().getString("s")));
    }

    private String verifiedAt(long roleId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT status_verified_at::text AS s FROM career_history WHERE id = $1")
            .execute(Tuple.of(roleId)).map(rs -> rs.iterator().next().getString("s")));
    }

    private String presented(long roleId, int months) throws Exception {
        return await(pool.preparedQuery("""
                SELECT employment_status_presented(employment_status, status_as_of, $2) AS s
                  FROM career_history WHERE id = $1
                """).execute(Tuple.of(roleId, months))
            .map(rs -> rs.iterator().next().getString("s")));
    }

    private String basisOfNewestEvent(long roleId) throws Exception {
        return await(pool.preparedQuery("SELECT basis FROM employment_status_events "
                + "WHERE role_id = $1 ORDER BY id DESC LIMIT 1")
            .execute(Tuple.of(roleId)).map(rs -> rs.iterator().next().getString("basis")));
    }

    private int events(long roleId) throws Exception {
        return count("SELECT count(*) AS c FROM employment_status_events WHERE role_id = " + roleId);
    }

    private int count(String sql) throws Exception {
        return await(pool.query(sql).execute().map(rs -> rs.iterator().next().getInteger("c")));
    }

    /** Boolean, not boolean: assertTrue(BooleanSupplier, String) is otherwise ambiguous. */
    private static boolean awaitBool(Future<Boolean> future) throws Exception {
        return Boolean.TRUE.equals(await(future));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
