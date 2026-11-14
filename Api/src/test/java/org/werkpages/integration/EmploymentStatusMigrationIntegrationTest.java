package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V112 applied the way production will apply it: to a database that already holds employment.
 *
 * <h2>What this covers, and why the shape of the test matters</h2>
 *
 * <p>V112 carries the employment-status model: the resolved status on {@code career_history}, its
 * append-only event log, the {@code employment_status_dispute} conflict kind, and the three rules
 * as functions. Issue 40, approved 2026-11-09 after decision P8 answered that employment status
 * ships WITH the identity redesign rather than after cutover.
 *
 * <p>The backfill can only be tested by migrating to V96, writing rows, and migrating again. A
 * test that migrates a clean container from V1 to the head proves the migration RUNS and says
 * nothing about what it does to existing data, which is the only question production asks.
 * {@link CaptureAttemptMigrationIntegrationTest} established that shape for V98 and this follows
 * it.
 *
 * <p>Three things here are not ordinary assertions and are worth naming:
 *
 * <ul>
 *   <li>{@link #aRoleAcceptedAfterTheMigrationIsNotResolved()} asserts a GAP rather than a
 *       guarantee. The backfill repairs history and nothing more, so an accepted role created
 *       afterwards reads {@code unknown} until the resolution component exists. That component is
 *       slice 2, and this test is what makes the gap visible rather than surprising.</li>
 *   <li>{@link #deletingAManagerWhoseRoleHasAStatusEventIsRefused()} asserts an approved
 *       CONSEQUENCE. {@code employment_status_events.role_id} is {@code ON DELETE RESTRICT} by
 *       approved deviation from rev 268, which said CASCADE, so that a disputed employment's
 *       history cannot be erased by deleting the manager. The cost is that such a manager can no
 *       longer be deleted, which is the same property {@code role_claims} and
 *       {@code claim_resolution_events} already have.</li>
 *   <li>{@link #publicationBlockingIsUnchanged()} pins the thing that must NOT have happened. A
 *       status dispute is about when somebody worked somewhere and has no bearing on whether a
 *       rating may be published, so adding its kind to {@code kind_blocks_publication} would
 *       withhold ratings over an unrelated question.</li>
 * </ul>
 */
@Testcontainers
class EmploymentStatusMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_status_upgrade")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;

    /**
     * Events in the log the instant the migration finished, captured BEFORE any test runs.
     *
     * <p>Read in {@code @BeforeAll} rather than inside the test, because the other tests in this
     * class resolve statuses and every resolution writes an event. A test that counted the whole
     * table would then pass or fail on JUnit's method order, which is not a property of the
     * migration. This is what the backfill did, and nothing else can change it.
     */
    static int eventsAfterMigration;

    /** Accepted and ended: the backfill must resolve this one. */
    static final long ENDED_ROLE = 7001;
    /** Accepted with no end date: a null end date is the ABSENCE of information, never evidence. */
    static final long OPEN_ROLE = 7002;
    /** Proposed and ended: a proposed record establishes nothing, so it must be left alone. */
    static final long PROPOSED_ROLE = 7003;

    static final long BOSS = 8001;
    static final long OTHER_BOSS = 8002;
    static final long COMPANY = 9001;

    @BeforeAll
    static void upgradeTheWayProductionWill() throws Exception {
        /*
          V96 IS THE "BEFORE" BOUNDARY, because it is the version production actually has.

          This used to step to V111 and withhold V112. Both numbers are gone: the sixteen
          migrations V97 to V112 were consolidated into one file, so there is no boundary inside
          it to stop at and Flyway refuses a target that names one.

          That changes what this test can prove, and the change is honest rather than a
          workaround. A V96 database has no `state` column at all - V97 adds it, defaulting every
          existing row to 'accepted' - so a PROPOSED employment cannot predate the migration. The
          backfill therefore only ever sees accepted employment, which is exactly what production
          will present it with, and the proposed case moved to its own post-migration test below.
        */
        flyway().target(MigrationVersion.fromVersion("96")).load().migrate();

        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(3));

        assertFalse(columnExists("career_history", "employment_status"),
            "fixture: the employment-status model must not have run yet, or this test is "
            + "checking nothing");
        assertFalse(columnExists("career_history", "state"),
            "fixture: V96 has no state column, which is why a proposed role cannot predate the "
            + "migration and why that case is asserted after it instead");
        assertFalse(tableExists("employment_status_events"), "fixture: the log does not exist yet");

        // 2. Employment of the three shapes production holds, written by pre-V112 statements.
        exec("INSERT INTO companies (id, name, slug, status) VALUES ($1,'Ashgrove Health','ashgrove-health','approved')",
             Tuple.of(COMPANY));
        insertManager(BOSS, "Priya Raghunathan", "priya-raghunathan");
        insertManager(OTHER_BOSS, "Dana Whitfield", "dana-whitfield");

        insertPreMigrationRole(ENDED_ROLE, BOSS, "Engineering Manager", "2019-01-01", "2022-12-31");
        insertPreMigrationRole(OPEN_ROLE, OTHER_BOSS, "Director", "2020-01-01", null);

        // 3. The upgrade.
        flyway().load().migrate();

        /*
          The proposed role is written AFTER the upgrade, because `state` is one of the columns the
          upgrade adds. A V96 database cannot hold a proposed employment, so presenting one to the
          backfill is not a scenario production can produce; what it CAN produce is a role proposed
          after the migration, which is this row.
        */
        insertProposedRoleAfterMigration(PROPOSED_ROLE, BOSS, "Senior Manager",
                                         "2015-01-01", "2017-01-01");

        eventsAfterMigration = count("SELECT count(*) AS c FROM employment_status_events");
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── the backfill ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the backfill resolves accepted ended employment, and only that")
    void theBackfillResolvesAcceptedEndedRolesOnly() throws Exception {
        assertEquals("ended|end_date|2022-12-31", statusOf(ENDED_ROLE),
            "an accepted role that already carries an end date IS an ended employment, and the "
            + "evidence is the record itself. Left unresolved, the whole imported history would "
            + "read Unknown from the day this migration ran");

        assertEquals("unknown|(null)|(null)", statusOf(OPEN_ROLE),
            "a null end date is the ABSENCE of information and is never evidence of ongoing "
            + "employment. The asymmetry is deliberate: Current claims something about today and "
            + "needs evidence, while Former only claims every known episode finished");

    }

    @Test
    @DisplayName("a proposed employment carries no status, and could not have reached the backfill")
    void aProposedRoleCarriesNoStatus() throws Exception {
        /*
          The same product rule as before, asserted in the only place it can be: AFTER the
          migration.

          A proposed record establishes nothing, so it carries no status; publishing one would
          publish an employment the platform has not accepted. This used to be a backfill case,
          written as pre-migration data. It cannot be: `state` is a column V97 ADDS, and V97
          defaults every row it finds to 'accepted', so no V96 database can hand the backfill a
          proposed employment. The rule is unchanged and the row is now created where the concept
          exists.
        */
        assertEquals("unknown|(null)|(null)", statusOf(PROPOSED_ROLE),
            "a PROPOSED record establishes nothing, so it carries no status");
    }

    @Test
    @DisplayName("the backfill wrote no events, so the log begins at the migration")
    void theBackfillWroteNoEvents() throws Exception {
        assertEquals(0, eventsAfterMigration,
            "the backfill runs BEFORE the logging trigger is created, deliberately. One event per "
            + "backfilled row would be tens of thousands of rows all recording that the migration "
            + "ran, which is what the migration itself records");
    }

    @Test
    @DisplayName("GAP, by design: a role accepted after the migration is not resolved")
    void aRoleAcceptedAfterTheMigrationIsNotResolved() throws Exception {
        insertRole(7050, BOSS, "Staff Engineer", "2013-01-01", "2014-01-01", "accepted");

        assertEquals("unknown|(null)|(null)", statusOf(7050),
            "the backfill repairs history and nothing else. Until the resolution component exists "
            + "(slice 2), an accepted role carrying an end date reads unknown. This test exists so "
            + "that gap is recorded rather than discovered");

        assertEquals(1, count("""
                SELECT count(*) AS c FROM career_history
                 WHERE state = 'accepted' AND end_date IS NOT NULL
                   AND employment_status NOT IN ('ended','disputed')
                """),
            "and the drift query finds it, which is why that query ships with slice 2 rather than "
            + "later: a derived store needs a reconciliation routine, not a promise");
    }

    // ── the constraints ──────────────────────────────────────────────────────

    @Test
    @DisplayName("an assertion with no evidence date or basis is refused")
    void theGroundingCheckRefusesAnUngroundedAssertion() throws Exception {
        assertRefused("UPDATE career_history SET employment_status='ongoing' WHERE id=" + OPEN_ROLE,
            "employment_status_is_grounded",
            "a resolution that asserts something must say what it speaks to and where it came from");
    }

    @Test
    @DisplayName("a contributor-grounded status must name the claim it came from")
    void aContributorClaimBasisMustNameItsClaim() throws Exception {
        assertRefused("""
                UPDATE career_history
                   SET employment_status='ongoing', status_basis='contributor_claim',
                       status_as_of='2024-01-01', status_resolved_at=now()
                 WHERE id=%d
                """.formatted(OPEN_ROLE),
            "employment_status_claim_basis_pairs",
            "without the pointer the event log would record a resolution with no source");
    }

    @Test
    @DisplayName("a record-grounded status must NOT name a claim")
    void anEndDateBasisMustNotNameAClaim() throws Exception {
        assertRefused("""
                UPDATE career_history
                   SET employment_status='ended', status_basis='end_date',
                       status_as_of='2022-12-31', status_resolved_at=now(), status_claim_id=1
                 WHERE id=%d
                """.formatted(OPEN_ROLE),
            "employment_status_claim_basis_pairs",
            "'contributor_claim' is the only basis that names a claim, and only it may");
    }

    @Test
    @DisplayName("nothing can be re-verified before it was resolved")
    void verificationRequiresAResolution() throws Exception {
        assertRefused("UPDATE career_history SET status_verified_at=now() WHERE id=" + OPEN_ROLE,
            "employment_status_verified_after_resolution",
            "a verification sweep must not write a confirmation onto a row holding no evidence");
    }

    // ── the event log ────────────────────────────────────────────────────────

    @Test
    @DisplayName("the database writes the log, including on a replay and a re-verification")
    void theLogIsWrittenByTheDatabase() throws Exception {
        long role = 7060;
        insertRole(role, OTHER_BOSS, "Head of Platform", "2021-01-01", null, "accepted");
        long claim = insertClaim(OTHER_BOSS, "Head of Platform", "2024-06-30");

        exec("""
             UPDATE career_history
                SET employment_status='ongoing', status_basis='contributor_claim',
                    status_as_of='2024-06-30', status_resolved_at=now(), status_claim_id=$1
              WHERE id=$2
             """, Tuple.of(claim, role));

        assertEquals("unknown->ongoing|contributor_claim|2024-06-30|" + claim, newestEvent(role),
            "a resolution is logged by a trigger on the row, so no application path can change a "
            + "status without the log recording it");
        assertEquals(1, eventsOn(role));

        // A REPLAY. The same values written again is exactly what a retried submission produces.
        exec("""
             UPDATE career_history
                SET employment_status='ongoing', status_basis='contributor_claim',
                    status_as_of='2024-06-30', status_claim_id=$1
              WHERE id=$2
             """, Tuple.of(claim, role));
        assertEquals(1, eventsOn(role),
            "a write that changes nothing must log nothing. UPDATE OF fires when the column is in "
            + "the SET list whether or not the value changed, so the guard is on the VALUES");

        // A RE-VERIFICATION is a change, and an audit-worthy one.
        exec("UPDATE career_history SET status_verified_at=now() WHERE id=$1", Tuple.of(role));
        assertEquals(2, eventsOn(role), "somebody re-checking and finding the answer holds is an event");
        assertTrue(newestEvent(role).startsWith("ongoing->ongoing"),
            "a verification keeps the status it confirmed");
    }

    @Test
    @DisplayName("the log refuses UPDATE and DELETE")
    void theLogIsAppendOnly() throws Exception {
        long role = 7070;
        insertRole(role, BOSS, "Principal", "2016-01-01", "2018-01-01", "accepted");
        exec("""
             UPDATE career_history
                SET employment_status='ended', status_basis='end_date',
                    status_as_of='2018-01-01', status_resolved_at=now()
              WHERE id=$1
             """, Tuple.of(role));
        assertEquals(1, eventsOn(role), "fixture: there is an event to try to change");

        assertRefused("UPDATE employment_status_events SET to_status='ongoing' WHERE role_id=" + role,
            "append-only", "the disagreement is the finding, and a record that tidies itself up "
            + "cannot show one");
        assertRefused("DELETE FROM employment_status_events WHERE role_id=" + role,
            "append-only", "same reason, and DELETE is refused unconditionally");
    }

    @Test
    @DisplayName("a plain new role logs nothing; one born resolved logs its birth")
    void theDefaultIsNotAnEvent() throws Exception {
        insertRole(7080, BOSS, "Analyst", "2021-01-01", null, "accepted");
        assertEquals(0, eventsOn(7080),
            "an INSERT at the default 'unknown' with no basis asserts nothing and is not an event");

        exec("""
             INSERT INTO career_history
                   (id, manager_id, company, company_id, title, start_date, end_date,
                    period_known, source, state, employment_status, status_basis,
                    status_as_of, status_resolved_at)
             VALUES (7081,$1,'Ashgrove Health',$2,'Lead','2010-01-01','2012-01-01',
                     TRUE,'import','accepted','ended','end_date','2012-01-01',now())
             """, Tuple.of(BOSS, COMPANY));
        assertEquals(1, eventsOn(7081), "a role created already carrying a resolution IS an event");
        assertTrue(newestEvent(7081).startsWith("(null)->ended"),
            "and its from_status is null, because there was nothing before it");
    }

    // ── the rules ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("contradiction follows rev 251: incompatible periods, not differing answers")
    void theContradictionRuleFollowsRev251() throws Exception {
        // The design's own two examples, verbatim.
        assertFalse(contradicts("ongoing", "2021-06-01", "ended", "2024-12-31"),
            "A left in 2021 saying the manager was still there; B says the job ended in 2024. "
            + "BOTH TRUE. He was there in 2021 and left in 2024");
        assertTrue(contradicts("ongoing", "2025-01-15", "ended", "2024-12-31"),
            "A left in Jan 2025 saying the manager was still there; B says it ended Dec 2024. "
            + "He cannot have been employed after he left");

        assertTrue(contradicts("ended", "2024-12-31", "ongoing", "2025-01-15"),
            "the same incompatibility arriving from the other direction is the same dispute");
        assertFalse(contradicts("ongoing", "2021-01-01", "ongoing", "2024-01-01"),
            "two contributors who overlapped different years are not in conflict");
        assertFalse(contradicts("ongoing", null, "ended", "2020-01-01"),
            "an assertion with no date speaks to nothing, so it can contradict nothing");
    }

    @Test
    @DisplayName("freshness is applied at read time, to 'ongoing' only")
    void theFreshnessWindowIsAppliedAtReadTime() throws Exception {
        assertEquals("current", presented("ongoing", "now() - interval '17 months'", 18));
        assertEquals("unknown", presented("ongoing", "now() - interval '19 months'", 18),
            "beyond the window an ongoing assertion presents as Unknown. The stored row is NOT "
            + "rewritten: a sweep flipping it would destroy what was established and make the log lie");
        assertEquals("former", presented("ended", "now() - interval '10 years'", 18),
            "'ended' NEVER expires. A completed employment stays completed, and ageing it would "
            + "turn known history into ignorance");
        assertEquals("unknown", presented("disputed", "now()", 18),
            "while two accounts conflict the honest answer is that we do not know");
        assertEquals("unknown", presented("ongoing", "NULL", 18),
            "an ongoing resolution with no assertable date is not a Current with an invented one");
    }

    @Test
    @DisplayName("the tile label combines accepted roles only")
    void theTileLabelCombinesAcceptedRolesOnly() throws Exception {
        long manager = 8030;
        insertManager(manager, "Tomas Lindqvist", "tomas-lindqvist");

        assertEquals("unknown", label(manager, 18), "no accepted employment, nothing to say");

        insertRole(7090, manager, "Engineering Manager", "2015-01-01", "2018-01-01", "accepted");
        exec("""
             UPDATE career_history SET employment_status='ended', status_basis='end_date',
                    status_as_of='2018-01-01', status_resolved_at=now() WHERE id=$1
             """, Tuple.of(7090L));
        assertEquals("former", label(manager, 18), "one accepted episode, and it finished");

        // A second, open episode, asserted recently: Current if any record is current.
        long claim = insertClaim(manager, "Director", null);
        insertRole(7091, manager, "Director", "2019-01-01", null, "accepted");
        exec("""
             UPDATE career_history
                SET employment_status='ongoing', status_basis='contributor_claim',
                    status_as_of = now() - interval '6 months', status_resolved_at=now(),
                    status_claim_id=$1
              WHERE id=$2
             """, Tuple.of(claim, 7091L));
        assertEquals("current", label(manager, 18), "any record ongoing and fresh makes the tile Current");
        assertEquals("unknown", label(manager, 3),
            "the window is a parameter, not a literal, so the same rows present differently under a "
            + "three-month policy. A stale ongoing is Unknown, NOT Former: the episode might still "
            + "be running and we have simply stopped knowing");

        // A PROPOSED role must not influence the label at all.
        insertRole(7092, manager, "VP Engineering", "2024-01-01", null, "proposed");
        assertEquals("current", label(manager, 18),
            "a proposed record carries no status and must not change what the public reads");

        // One accepted record with no evidence is enough to prevent Former.
        exec("UPDATE career_history SET employment_status='unknown', status_basis=NULL, "
             + "status_as_of=NULL, status_resolved_at=NULL, status_verified_at=NULL, "
             + "status_claim_id=NULL WHERE id=$1", Tuple.of(7091L));
        assertEquals("unknown", label(manager, 18),
            "one indeterminate record prevents Former, because an episode we know nothing about "
            + "might still be running");
    }

    // ── the dispute kind ─────────────────────────────────────────────────────

    @Test
    @DisplayName("an employment-status dispute is accepted, non-blocking and deduplicated")
    void theDisputeKindIsNonBlockingAndDeduplicated() throws Exception {
        assertFalse(awaitBool(pool.query(
                "SELECT kind_blocks_publication('employment_status_dispute') AS b").execute()
            .map(rs -> rs.iterator().next().getBoolean("b"))),
            "a dispute about WHEN somebody worked somewhere says nothing about whether a rating "
            + "may be published, and blocking on it would withhold ratings over an unrelated question");

        assertFalse(awaitBool(pool.query("""
                INSERT INTO role_conflicts
                      (kind, manager_id, company_id, title_key, period_key,
                       manager_name, company_name, title)
                VALUES ('employment_status_dispute', 8001, 9001,
                        normalise_title('Engineering Manager'), '2019-01-01',
                        'Priya Raghunathan', 'Ashgrove Health', 'Engineering Manager')
                RETURNING blocks_publication
                """).execute().map(rs -> rs.iterator().next().getBoolean("blocks_publication"))),
            "blocks_publication is a STORED generated column, so leaving kind_blocks_publication "
            + "alone means every dispute row is born non-blocking with no backfill");

        assertRefused("""
                INSERT INTO role_conflicts
                      (kind, manager_id, company_id, title_key, period_key,
                       manager_name, company_name, title)
                VALUES ('employment_status_dispute', 8001, 9001,
                        normalise_title('Engineering Manager'), '2019-01-01',
                        'Priya Raghunathan', 'Ashgrove Health', 'Engineering Manager')
                """,
            "role_conflicts_one_open",
            "one OPEN task per disputed employment per kind, so a dispute raised twice is one item");

        exec("UPDATE role_conflicts SET resolution='status_determined', resolved_at=now() "
             + "WHERE kind='employment_status_dispute'");
    }

    @Test
    @DisplayName("publication blocking is unchanged: still exactly the four kinds")
    void publicationBlockingIsUnchanged() throws Exception {
        assertEquals(4, count("""
                SELECT count(*) AS c FROM (VALUES
                    ('end_date_disagreement'),('ambiguous_resolution'),('unknown_period_duplicate'),
                    ('overlapping_period'),('correction_candidate'),('late_edit_to_accepted'),
                    ('duplicate_rating_on_merge'),('possible_duplicate'),
                    ('cross_era_possible_duplicate'),('previously_rejected'),
                    ('employment_status_dispute')) k(x)
                 WHERE kind_blocks_publication(x)
                """),
            "exactly four kinds may hold a rating, and this migration adds none. The four are "
            + "ambiguous_resolution, possible_duplicate, duplicate_rating_on_merge and "
            + "cross_era_possible_duplicate");
    }

    // ── erasure, and the approved cost of RESTRICT ───────────────────────────

    @Test
    @DisplayName("erasure clears the actor and keeps the event")
    void erasureClearsTheActorAndKeepsTheEvent() throws Exception {
        UUID actor = UUID.randomUUID();
        exec("INSERT INTO users (id, auth0_id, username) VALUES ($1,$2,$3)",
             Tuple.of(actor, "auth0|" + actor, "probe-" + actor.toString().substring(0, 8)));

        long role = 7100;
        insertRole(role, BOSS, "Delivery Manager", "2017-01-01", "2019-01-01", "accepted");
        exec("""
             UPDATE career_history
                SET employment_status='ended', status_basis='end_date', status_as_of='2019-01-01',
                    status_resolved_at=now(), status_resolved_by=$1
              WHERE id=$2
             """, Tuple.of(actor, role));
        assertEquals(1, eventsOn(role), "fixture: one event, naming the actor");
        assertEquals(1, count("SELECT count(*) AS c FROM employment_status_events "
                              + "WHERE role_id=" + role + " AND \"by\" IS NOT NULL"));

        exec("DELETE FROM users WHERE id=$1", Tuple.of(actor));

        assertEquals(1, eventsOn(role),
            "the account goes and the event stays. ON DELETE SET NULL performs an UPDATE, which "
            + "fires the append-only trigger, and that trigger permits exactly this one shape");
        assertEquals(0, count("SELECT count(*) AS c FROM employment_status_events "
                              + "WHERE role_id=" + role + " AND \"by\" IS NOT NULL"),
            "and the actor is gone from it");
        assertEquals("ended|end_date|2019-01-01", statusOf(role),
            "the resolution itself is untouched: erasing who recorded a fact does not erase the fact");
    }

    @Test
    @DisplayName("APPROVED COST: a manager whose role has a status event cannot be deleted")
    void deletingAManagerWhoseRoleHasAStatusEventIsRefused() throws Exception {
        // No status event: deletion works, which is what makes this migration safe on its own.
        long deletable = 8040;
        insertManager(deletable, "Seed Two", "seed-two");
        insertRole(7110, deletable, "Lead", "2018-01-01", "2020-01-01", "accepted");
        exec("DELETE FROM managers WHERE id=$1", Tuple.of(deletable));
        assertEquals(0, count("SELECT count(*) AS c FROM managers WHERE id=" + deletable),
            "career_history cascades from managers, and with no event in the log nothing holds it. "
            + "After V112 and its backfill the log is EMPTY, so no manager becomes undeletable by "
            + "this migration alone");

        // With a status event, RESTRICT holds it. This is the approved deviation's cost.
        long held = 8041;
        insertManager(held, "Seed Three", "seed-three");
        insertRole(7111, held, "Lead", "2018-01-01", "2020-01-01", "accepted");
        exec("""
             UPDATE career_history SET employment_status='ended', status_basis='end_date',
                    status_as_of='2020-01-01', status_resolved_at=now() WHERE id=$1
             """, Tuple.of(7111L));

        assertRefused("DELETE FROM managers WHERE id=" + held,
            "employment_status_events_role_id_fkey",
            "rev 268 specified CASCADE and RESTRICT was approved instead, so that deleting a "
            + "manager cannot erase the record that their employment was ever resolved or disputed. "
            + "role_claims and claim_resolution_events already hold managers the same way. The one "
            + "live consumer is the seed-manager cleanup job, which must learn to skip these: that "
            + "is slice 2 work, and it cannot bite before then because the log starts empty");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations");
    }

    private static void insertManager(long id, String name, String slug) throws Exception {
        exec("""
             INSERT INTO managers (id, name, company, company_id, title, status, approval_status, slug)
             VALUES ($1,$2,'Ashgrove Health',$3,'Engineering Manager','active','approved',$4)
             """, Tuple.of(id, name, COMPANY).addValue(slug));
    }

    /**
     * Employment as a V96 database holds it, which is the only shape the backfill can be given.
     *
     * <p>`period_known`, `source` and `state` are deliberately absent: V97 adds all three, so a
     * statement written against V96 cannot set them. V97 defaults `state` to 'accepted', which is
     * precisely why every row production presents to the backfill is accepted employment.
     *
     * <p>{@link #insertRole} is its post-migration counterpart and takes a state, for the tests
     * that are about what the platform does once the model exists.
     */
    private static void insertPreMigrationRole(long id, long managerId, String title, String start,
                                               String end) throws Exception {
        exec("""
             INSERT INTO career_history (id, manager_id, company, company_id, title,
                                         start_date, end_date)
             VALUES ($1,$2,'Ashgrove Health',$3,$4,$5,$6)
             """,
             Tuple.of(id, managerId, COMPANY).addValue(title).addValue(midnightUtc(start))
                 .addValue(midnightUtc(end)));
    }

    /** Employment written AFTER the migration, where every column exists. */
    private static void insertRole(long id, long managerId, String title, String start,
                                   String end, String state) throws Exception {
        exec("""
             INSERT INTO career_history (id, manager_id, company, company_id, title,
                                         start_date, end_date, period_known, source, state)
             VALUES ($1,$2,'Ashgrove Health',$3,$4,$5,$6,TRUE,'import',$7)
             """,
             Tuple.of(id, managerId, COMPANY).addValue(title).addValue(midnightUtc(start))
                 .addValue(midnightUtc(end)).addValue(state));
    }

    /**
     * A proposed employment, written once the migration has supplied the column that expresses it.
     *
     * <p>Separate from {@link #insertRole} because the two belong to different schemas and to
     * different moments: one is what the backfill is handed, the other is what the platform does
     * afterwards.
     */
    private static void insertProposedRoleAfterMigration(long id, long managerId, String title,
                                                         String start, String end) throws Exception {
        insertRole(id, managerId, title, start, end, "proposed");
    }

    /**
     * A calendar day as the instant the contributor path stores, which is midnight UTC.
     *
     * <p>{@code career_history.start_date} is TIMESTAMPTZ and the client binds it as an
     * OffsetDateTime. Writing the date as a string and casting it in the SQL does not work: the
     * parameter's type comes from the prepared statement's description, so the cast is applied
     * AFTER the value has already failed to encode. V99's {@code start_day} generated column reads
     * these back as UTC calendar days, which is what makes an imported timestamp and a typed date
     * the same employment.
     */
    private static OffsetDateTime midnightUtc(String isoDate) {
        return isoDate == null ? null : OffsetDateTime.parse(isoDate + "T00:00:00Z");
    }

    private static long insertClaim(long managerId, String title, String end) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO role_claims (proposal_key, manager_id, company_id, title,
                                          start_date, end_date, period_known, still_there, source)
                VALUES (gen_random_uuid(),$1,$2,$3,'2020-01-01'::date,$4,TRUE,TRUE,'contributor')
                RETURNING id
                """)
            .execute(Tuple.of(managerId, COMPANY).addValue(title)
                .addValue(end == null ? null : LocalDate.parse(end)))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    /** status, basis and the evidence date, as one string, so a failure prints all three. */
    private static String statusOf(long roleId) throws Exception {
        return await(pool.preparedQuery("""
                SELECT employment_status || '|' || COALESCE(status_basis,'(null)') || '|'
                       || COALESCE(status_as_of::date::text,'(null)') AS s
                  FROM career_history WHERE id = $1
                """).execute(Tuple.of(roleId)).map(rs -> rs.iterator().next().getString("s")));
    }

    private static String newestEvent(long roleId) throws Exception {
        return await(pool.preparedQuery("""
                SELECT COALESCE(from_status,'(null)') || '->' || to_status || '|'
                       || COALESCE(basis,'(null)') || '|'
                       || COALESCE(as_of::date::text,'(null)') || '|'
                       || COALESCE(claim_id::text,'(null)') AS s
                  FROM employment_status_events WHERE role_id = $1
                 ORDER BY id DESC LIMIT 1
                """).execute(Tuple.of(roleId)).map(rs -> rs.iterator().next().getString("s")));
    }

    private static int eventsOn(long roleId) throws Exception {
        return count("SELECT count(*) AS c FROM employment_status_events WHERE role_id=" + roleId);
    }

    private static boolean contradicts(String standing, String standingAsOf,
                                       String incoming, String incomingAsOf) throws Exception {
        return awaitBool(pool.preparedQuery(
                "SELECT employment_status_contradicts($1,$2,$3,$4) AS b")
            .execute(Tuple.of(standing, midnightUtc(standingAsOf)).addValue(incoming)
                .addValue(midnightUtc(incomingAsOf)))
            .map(rs -> rs.iterator().next().getBoolean("b")));
    }

    /** The as_of is an SQL expression, because the interesting values are relative to now(). */
    private static String presented(String status, String asOfExpr, int months) throws Exception {
        return await(pool.preparedQuery(
                "SELECT employment_status_presented($1, " + asOfExpr + ", $2) AS s")
            .execute(Tuple.of(status, months))
            .map(rs -> rs.iterator().next().getString("s")));
    }

    private static String label(long managerId, int months) throws Exception {
        return await(pool.preparedQuery("SELECT employment_label($1,$2,$3) AS s")
            .execute(Tuple.of(managerId, COMPANY, months))
            .map(rs -> rs.iterator().next().getString("s")));
    }

    private static void exec(String sql) throws Exception {
        await(pool.query(sql).execute().mapEmpty());
    }

    private static void exec(String sql, Tuple args) throws Exception {
        await(pool.preparedQuery(sql).execute(args).mapEmpty());
    }

    /** Runs SQL that must fail, and insists the failure is the RIGHT one. */
    private static void assertRefused(String sql, String expectedInMessage, String why)
            throws Exception {
        try {
            await(pool.query(sql).execute());
            fail("this should have been refused, because " + why + ": " + sql);
        } catch (Exception err) {
            String message = String.valueOf(err.getMessage())
                + String.valueOf(err.getCause() == null ? "" : err.getCause().getMessage());
            assertTrue(message.contains(expectedInMessage),
                "refused, but not for the expected reason (" + expectedInMessage + "), which "
                + "means this test may be passing by accident. Got: " + message);
        }
    }

    private static int count(String sql) throws Exception {
        return await(pool.query(sql).execute()
            .map(rs -> rs.iterator().next().getInteger("c")));
    }

    private static boolean columnExists(String table, String column) throws Exception {
        return awaitBool(pool.preparedQuery("""
                SELECT EXISTS (SELECT 1 FROM information_schema.columns
                                WHERE table_schema='public' AND table_name=$1 AND column_name=$2) AS b
                """).execute(Tuple.of(table, column))
            .map(rs -> rs.iterator().next().getBoolean("b")));
    }

    private static boolean tableExists(String table) throws Exception {
        return awaitBool(pool.preparedQuery("""
                SELECT EXISTS (SELECT 1 FROM information_schema.tables
                                WHERE table_schema='public' AND table_name=$1) AS b
                """).execute(Tuple.of(table))
            .map(rs -> rs.iterator().next().getBoolean("b")));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }

    private static boolean awaitBool(Future<Boolean> future) throws Exception {
        return Boolean.TRUE.equals(await(future));
    }
}
