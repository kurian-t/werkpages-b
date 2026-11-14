package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.EmploymentStatusResolver;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.ManagerService;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The seven derivation cases, as the public company profile actually answers them.
 *
 * <h2>Why this tests the API and not the function</h2>
 *
 * <p>{@code employment_label} is already tested directly, and it would keep passing if nothing
 * called it, if the label were dropped on the way into the response, or if the response carried
 * the evidence date alongside it. The question here is what a reader receives, so every assertion
 * goes through {@code getCompanyProfile} and reads the serialised JSON.
 *
 * <h2>The seven cases, and where each one is</h2>
 *
 * <ol>
 *   <li>no end date is Unknown, unless a claim asserts still_there:
 *       {@link #caseOne_noEndDateIsUnknownUnlessAssertedOngoing()}</li>
 *   <li>confirmed ongoing is Current: {@link #caseTwo_confirmedOngoingIsCurrent()}</li>
 *   <li>several periods at one company are one answer:
 *       {@link #caseThree_severalPeriodsAtOneCompanyCombine()}</li>
 *   <li>left and returned: {@link #caseFour_leftAndReturned()}</li>
 *   <li>incomplete or conflicting dates: {@link #caseFive_incompleteOrConflicting()}</li>
 *   <li>history corrected or merged: {@link #caseSix_supersededIsNotCountedTwice()}</li>
 *   <li>only legacy or ghost information: {@link #caseSeven_legacyOrGhostOnlyIsUnknown()}</li>
 * </ol>
 */
@Testcontainers
class EmploymentStatusPresentationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("status_presentation")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static ManagerService service;

    long companyId;

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
            new PoolOptions().setMaxSize(4));
        service = new ManagerService(new ManagerRepository(pool), new ReviewRepository(pool),
            new UserRepository(pool), new EditRepository(pool), new ReportRepository(pool),
            new CompanyRepository(pool), pool, company -> null);
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE employment_status_events, role_claims CASCADE").execute());
        await(pool.query("TRUNCATE career_history, managers, users, companies CASCADE").execute());
        companyId = await(pool.preparedQuery(
                "INSERT INTO companies (name, slug, status) VALUES ('Acme Corp','acme-corp',"
                + "'approved') RETURNING id").execute()
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    // ── the seven cases ─────────────────────────────────────────────────────

    @Test
    @DisplayName("case 1: no end date is Unknown, and never inferred, unless a claim asserts it")
    void caseOne_noEndDateIsUnknownUnlessAssertedOngoing() throws Exception {
        long notAsserted = manager("Ingrid Holm", "ingrid-holm");
        role(notAsserted, "Depot Lead", "2020-01-01", null, "accepted");

        assertEquals("unknown", labelFor("Ingrid Holm"),
            "an open-ended record is the absence of an end date, which is not evidence that the "
            + "employment is current. Current claims something about today and needs evidence for "
            + "it; Former only claims every known episode finished, which is why the asymmetry is "
            + "deliberate");

        long asserted = manager("Yusuf Demir", "yusuf-demir");
        long role = role(asserted, "Depot Lead", "2020-01-01", null, "accepted");
        claimAsserting(asserted, role, "2025-06-30");

        assertEquals("current", labelFor("Yusuf Demir"),
            "and with a contributor saying the manager was still there when they left, it is");
    }

    @Test
    @DisplayName("case 2: a confirmed ongoing employment is Current")
    void caseTwo_confirmedOngoingIsCurrent() throws Exception {
        long m = manager("Priya Raghunathan", "priya-r");
        long role = role(m, "Depot Lead", "2019-01-01", null, "accepted");
        determined(role, "ongoing", LocalDate.now().minusMonths(3));

        assertEquals("current", labelFor("Priya Raghunathan"));
    }

    @Test
    @DisplayName("case 3: several periods at one company are one answer, not several")
    void caseThree_severalPeriodsAtOneCompanyCombine() throws Exception {
        long m = manager("Dana Whitfield", "dana-w");
        long first  = role(m, "Analyst", "2014-01-01", "2016-01-01", "accepted");
        long second = role(m, "Depot Lead", "2016-01-01", "2019-01-01", "accepted");
        determined(first,  "ended", LocalDate.parse("2016-01-01"));
        determined(second, "ended", LocalDate.parse("2019-01-01"));

        assertEquals(1, tilesFor("Dana Whitfield"),
            "two employment records, ONE tile. The reader is asking about the person");
        assertEquals("former", labelFor("Dana Whitfield"),
            "every known episode finished, so Former");

        long third = role(m, "Operations Manager", "2019-01-01", null, "accepted");
        determined(third, "ongoing", LocalDate.now().minusMonths(2));

        assertEquals("current", labelFor("Dana Whitfield"),
            "and any ongoing episode makes the person Current, however many finished ones there "
            + "are beside it");
    }

    @Test
    @DisplayName("case 4: left and returned, read from the record rather than guessed from dates")
    void caseFour_leftAndReturned() throws Exception {
        long m = manager("Tomas Lindqvist", "tomas-l");
        long left = role(m, "Depot Lead", "2015-01-01", "2018-01-01", "accepted");
        determined(left, "ended", LocalDate.parse("2018-01-01"));
        long returned = role(m, "Depot Lead", "2022-01-01", null, "accepted");

        assertEquals("unknown", labelFor("Tomas Lindqvist"),
            "the later episode is open and nothing asserts it, so it is indeterminate, and ONE "
            + "indeterminate record is enough to prevent Former: an episode we know nothing about "
            + "might still be running");

        // Fresh, deliberately: a departure eighteen months ago would present as Unknown and
        // this test would be measuring the freshness rule rather than the return.
        claimAsserting(m, returned, LocalDate.now().minusMonths(3).toString());
        assertEquals("current", labelFor("Tomas Lindqvist"),
            "and asserted, the open later episode makes them Current. No special case: the "
            + "combination rule handles a return the same way it handles anything else");
    }

    @Test
    @DisplayName("case 5: incomplete dates are indeterminate, and a dispute presents as Unknown")
    void caseFive_incompleteOrConflicting() throws Exception {
        long m = manager("Aisha Bello", "aisha-b");
        await(pool.preparedQuery("""
                INSERT INTO career_history (manager_id, company, title, start_date, company_id,
                                             period_known, state, source)
                VALUES ($1,'Acme Corp','Depot Lead',NULL,$2,FALSE,'accepted','contributor')
                """).execute(Tuple.of(m, companyId)));

        assertEquals("unknown", labelFor("Aisha Bello"),
            "a record whose period is not known is indeterminate by definition");

        long disputedManager = manager("Niall Byrne", "niall-b");
        long role = role(disputedManager, "Depot Lead", "2019-01-01", "2024-06-30", "accepted");
        await(pool.preparedQuery("""
                UPDATE career_history SET employment_status = 'disputed', status_basis = NULL,
                       status_as_of = NULL, status_resolved_at = NULL WHERE id = $1
                """).execute(Tuple.of(role)));

        assertEquals("unknown", labelFor("Niall Byrne"),
            "and while two accounts conflict the honest answer is that we do not know. A disputed "
            + "employment presents as Unknown until a moderator determines it, and never as the "
            + "answer one side happened to give");
    }

    @Test
    @DisplayName("case 6: a superseded record is not counted, so a merge does not double it")
    void caseSix_supersededIsNotCountedTwice() throws Exception {
        long m = manager("Rosa Iglesias", "rosa-i");
        long kept = role(m, "Depot Lead", "2018-01-01", null, "accepted");
        long dupe = role(m, "Depot Lead", "2018-01-02", null, "accepted");
        determined(kept, "ongoing", LocalDate.now().minusMonths(1));
        // The merge's shape: the equivalent row is kept and pointed at its replacement.
        await(pool.preparedQuery("UPDATE career_history SET state = 'superseded', "
                + "superseded_by = $2 WHERE id = $1").execute(Tuple.of(dupe, kept)));

        assertEquals("current", labelFor("Rosa Iglesias"));
        assertEquals(1, tilesFor("Rosa Iglesias"));

        // And the superseded row cannot drag the answer back: its own status is irrelevant.
        await(pool.preparedQuery("UPDATE career_history SET employment_status = 'unknown', "
                + "status_basis = NULL, status_as_of = NULL, status_resolved_at = NULL "
                + "WHERE id = $1").execute(Tuple.of(dupe)));
        assertEquals("current", labelFor("Rosa Iglesias"),
            "a superseded record's successor carries the answer, so the superseded one is excluded "
            + "rather than counted as a second, indeterminate employment");
    }

    @Test
    @DisplayName("case 7: a manager known only from legacy or ghost information is Unknown")
    void caseSeven_legacyOrGhostOnlyIsUnknown() throws Exception {
        // Reaches the page by the company STRING alone: no career_history row at all.
        long ghost = await(pool.preparedQuery("""
                INSERT INTO managers (name, company, title, status, approval_status, country,
                                      slug, overall_rating, reviews_count, category_averages)
                VALUES ('Marcus Opoku','Acme Corp','Depot Lead','active','ghost','CA',
                        'marcus-o',0,0,'{}'::jsonb)
                RETURNING id
                """).execute().map(rs -> rs.iterator().next().getLong("id")));

        assertEquals("unknown", labelFor("Marcus Opoku"),
            "a ghost's company string and a legacy rating's manager_company put a tile on this "
            + "page and establish nothing. Such a manager has no accepted employment, so the "
            + "answer is Unknown BY CONSTRUCTION rather than by a special case for them");
        assertTrue(ghost > 0);
    }

    // ── the freshness boundary, through the API ─────────────────────────────

    @Test
    @DisplayName("an ongoing assertion inside the window is Current, and outside it is Unknown")
    void theFreshnessBoundaryIsAppliedAtReadTime() throws Exception {
        long m = manager("Elena Varga", "elena-v");
        long role = role(m, "Depot Lead", "2015-01-01", null, "accepted");

        determined(role, "ongoing", LocalDate.now().minusMonths(
            EmploymentStatusResolver.FRESHNESS_MONTHS - 1));
        assertEquals("current", labelFor("Elena Varga"), "seventeen months old is still Current");

        determined(role, "ongoing", LocalDate.now().minusMonths(
            EmploymentStatusResolver.FRESHNESS_MONTHS + 1));
        assertEquals("unknown", labelFor("Elena Varga"),
            "nineteen months old presents as Unknown, and NOT as Former: the employment may well "
            + "still be running and we have simply stopped knowing");

        assertEquals("ongoing", await(pool.preparedQuery(
                "SELECT employment_status FROM career_history WHERE id = $1")
            .execute(Tuple.of(role))
            .map(rs -> rs.iterator().next().getString("employment_status"))),
            "and the STORED row still says ongoing. Freshness is applied when the status is read, "
            + "never by a sweep: rewriting the row would destroy the record of what was once "
            + "established and make the event log lie");
    }

    @Test
    @DisplayName("ended never ages out of Former")
    void endedNeverDecays() throws Exception {
        long m = manager("Bram de Vries", "bram-d");
        long role = role(m, "Depot Lead", "2001-01-01", "2003-01-01", "accepted");
        determined(role, "ended", LocalDate.parse("2003-01-01"));

        assertEquals("former", labelFor("Bram de Vries"),
            "twenty years on and still Former. A completed employment is a historical fact and "
            + "ageing it would turn known history into ignorance");
    }

    @Test
    @DisplayName("only accepted records influence the label")
    void onlyAcceptedRecordsCount() throws Exception {
        long m = manager("Kofi Mensah", "kofi-m");
        long proposed = role(m, "Depot Lead", "2020-01-01", null, "proposed");
        await(pool.preparedQuery("""
                UPDATE career_history SET employment_status = 'ongoing', status_basis = 'moderator',
                       status_as_of = now(), status_resolved_at = now() WHERE id = $1
                """).execute(Tuple.of(proposed)));

        assertEquals("unknown", labelFor("Kofi Mensah"),
            "a proposed record establishes nothing, so even a status written onto it must not "
            + "reach the public label. The platform has not accepted that this employment existed");

        long rejected = role(m, "Yard Lead", "2018-01-01", "2019-01-01", "rejected");
        await(pool.preparedQuery("""
                UPDATE career_history SET employment_status = 'ended', status_basis = 'end_date',
                       status_as_of = now(), status_resolved_at = now() WHERE id = $1
                """).execute(Tuple.of(rejected)));

        assertEquals("unknown", labelFor("Kofi Mensah"),
            "and a rejected one is not evidence of anything either");
    }

    // ── privacy at the serialisation boundary ───────────────────────────────

    @Test
    @DisplayName("the response carries the label and NONE of the resolution data behind it")
    void theResponseExposesTheLabelAndNothingElse() throws Exception {
        long m = manager("Sophie Tremblay", "sophie-t");
        long role = role(m, "Depot Lead", "2019-01-01", null, "accepted");
        // A distinctive evidence date and a real moderator, so their absence is checkable rather
        // than vacuously true.
        java.util.UUID admin = await(pool.preparedQuery(
                "INSERT INTO users (auth0_id, username, role) VALUES ('auth0|a','adm','admin') "
                + "RETURNING id").execute().map(rs -> rs.iterator().next().getUUID("id")));
        // Inside the freshness window so the label is Current, and an unusual day of the month
        // so that finding the string in the response could only mean the date leaked.
        LocalDate evidenceDate = LocalDate.now().minusMonths(2).withDayOfMonth(17);
        await(pool.preparedQuery("""
                UPDATE career_history
                   SET employment_status = 'ongoing', status_basis = 'moderator',
                       status_as_of = ($3::date)::timestamp AT TIME ZONE 'UTC',
                       status_resolved_at = now(), status_resolved_by = $2,
                       status_verified_at = now()
                 WHERE id = $1
                """).execute(Tuple.of(role, admin).addValue(evidenceDate)));

        JsonObject profile = await(service.getCompanyProfile("Acme Corp"));
        String serialised = profile.encode();

        assertEquals("current", labelFrom(profile, "Sophie Tremblay"),
            "the label is there, because that is the whole of what a reader needs");

        for (String leak : java.util.List.of("statusAsOf", "status_as_of",
                                             "statusBasis", "status_basis",
                                             "statusClaimId", "status_claim_id",
                                             "statusResolvedBy", "status_resolved_by",
                                             "statusVerifiedAt", "status_verified_at",
                                             "employment_status")) {
            assertFalse(serialised.contains(leak),
                "the response must not carry " + leak + ". Where the basis is a contributor's "
                + "claim, status_as_of IS that contributor's own departure date, which the "
                + "employment-date privacy contract makes never public. One rule for all bases, "
                + "because a rule that published it for some is one mistaken call site from a "
                + "leak and the mistaken value is the convenient one");
        }
        assertFalse(serialised.contains(evidenceDate.toString()),
            "and not the date itself under any name. This is the assertion that would catch it "
            + "being embedded in a label, a tooltip or a sort key rather than a field");
        assertFalse(serialised.contains(admin.toString()),
            "nor who decided it. A moderator's identity is administrative data");
    }

    @Test
    @DisplayName("the historical record stays public even when the status does not")
    void theRecordItselfIsStillPublic() throws Exception {
        long m = manager("Hugo Almeida", "hugo-a");
        long role = role(m, "Depot Lead", "2017-03-01", "2021-08-31", "accepted");
        determined(role, "ended", LocalDate.parse("2021-08-31"));

        JsonObject profile = await(service.getCompanyProfile("Acme Corp"));
        assertEquals("former", labelFrom(profile, "Hugo Almeida"));
        assertEquals(1, profile.getJsonArray("managers").size(),
            "the manager is still listed. Employment status is display-only: it changes no "
            + "average, no count, no company figure and nobody's inclusion in this list. "
            + "career_history.start_date and end_date are the MANAGER's record and stay public, "
            + "which is the distinction the whole model rests on");
    }

    // ── the OTHER route onto the same page ──────────────────────────────────

    /**
     * The by-slug route, which is the one a reader actually arrives on from a link.
     *
     * <p>This page has TWO tile builders, by-name and by-slug, which is a pre-existing
     * duplication. A mutation run proved the point: exposing the evidence date through the by-slug
     * builder broke nothing, because every assertion here went through the by-name one. Two copies
     * of a serialiser need two copies of the privacy test, or the untested one is where the leak
     * will be.
     */
    @Test
    @DisplayName("the by-slug route carries the same label and the same absences")
    void theBySlugRouteAgrees() throws Exception {
        long m = manager("Sophie Tremblay", "sophie-t");
        long role = role(m, "Depot Lead", "2019-01-01", null, "accepted");
        java.util.UUID admin = await(pool.preparedQuery(
                "INSERT INTO users (auth0_id, username, role) VALUES ('auth0|b','adm2','admin') "
                + "RETURNING id").execute().map(rs -> rs.iterator().next().getUUID("id")));
        LocalDate evidenceDate = LocalDate.now().minusMonths(2).withDayOfMonth(17);
        await(pool.preparedQuery("""
                UPDATE career_history
                   SET employment_status = 'ongoing', status_basis = 'moderator',
                       status_as_of = ($3::date)::timestamp AT TIME ZONE 'UTC',
                       status_resolved_at = now(), status_resolved_by = $2,
                       status_verified_at = now()
                 WHERE id = $1
                """).execute(Tuple.of(role, admin).addValue(evidenceDate)));

        JsonObject profile = await(service.getCompanyBySlug("acme-corp"));
        String serialised = profile.encode();

        assertEquals("current", labelFrom(profile, "Sophie Tremblay"),
            "the same label, because a reader arriving by link is the same reader");
        for (String leak : java.util.List.of("statusAsOf", "status_as_of", "statusBasis",
                                             "status_basis", "statusClaimId", "status_claim_id",
                                             "statusResolvedBy", "status_resolved_by",
                                             "statusVerifiedAt", "status_verified_at",
                                             "employment_status")) {
            assertFalse(serialised.contains(leak), "the by-slug response must not carry " + leak);
        }
        assertFalse(serialised.contains(evidenceDate.toString()),
            "nor the evidence date, which is a contributor's own departure date wherever the basis "
            + "is a claim");
        assertFalse(serialised.contains(admin.toString()), "nor who decided it");
    }

    @Test
    @DisplayName("the by-slug route applies the freshness window too")
    void theBySlugRouteAgesTheSameWay() throws Exception {
        long m = manager("Elena Varga", "elena-v");
        long role = role(m, "Depot Lead", "2015-01-01", null, "accepted");

        determined(role, "ongoing", LocalDate.now().minusMonths(
            EmploymentStatusResolver.FRESHNESS_MONTHS - 1));
        assertEquals("current",
            labelFrom(await(service.getCompanyBySlug("acme-corp")), "Elena Varga"));

        determined(role, "ongoing", LocalDate.now().minusMonths(
            EmploymentStatusResolver.FRESHNESS_MONTHS + 1));
        assertEquals("unknown",
            labelFrom(await(service.getCompanyBySlug("acme-corp")), "Elena Varga"),
            "one window, one constant, both routes. A page that aged its labels differently "
            + "depending on which link you followed would be worse than one that did not age them");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private long manager(String name, String slug) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO managers (name, company, company_id, title, status, approval_status,
                                      country, slug, overall_rating, reviews_count,
                                      category_averages)
                VALUES ($1,'Acme Corp',$2,'Depot Lead','active','approved','CA',$3,0,0,
                        '{}'::jsonb)
                RETURNING id
                """).execute(Tuple.of(name, companyId).addValue(slug))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long role(long managerId, String title, String startIso, String endIso, String state)
            throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO career_history (manager_id, company, title, start_date, end_date,
                                             company_id, period_known, state, source)
                VALUES ($1,'Acme Corp',$2,($3::date)::timestamp AT TIME ZONE 'UTC',
                        ($4::date)::timestamp AT TIME ZONE 'UTC',$5,TRUE,$6,'contributor')
                RETURNING id
                """)
            .execute(Tuple.of(managerId, title, LocalDate.parse(startIso))
                .addValue(endIso == null ? null : LocalDate.parse(endIso))
                .addValue(companyId).addValue(state))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    /** A moderator's determination, which is the shortest way to put a role in a known state. */
    private void determined(long roleId, String status, LocalDate asOf) throws Exception {
        await(pool.preparedQuery("""
                UPDATE career_history
                   SET employment_status = $2, status_basis = 'moderator',
                       status_as_of = ($3::date)::timestamp AT TIME ZONE 'UTC',
                       status_resolved_at = now(), status_verified_at = NULL
                 WHERE id = $1
                """).execute(Tuple.of(roleId, status).addValue(asOf)));
    }

    /** A contributor who says the manager was still there when they left on the given date. */
    private void claimAsserting(long managerId, long roleId, String leftOn) throws Exception {
        java.util.UUID who = await(pool.preparedQuery(
                "INSERT INTO users (auth0_id, username) VALUES ($1,$2) RETURNING id")
            .execute(Tuple.of("auth0|" + java.util.UUID.randomUUID(),
                              "u" + System.nanoTime() % 100000))
            .map(rs -> rs.iterator().next().getUUID("id")));
        await(pool.preparedQuery("""
                INSERT INTO role_claims (proposal_key, manager_id, company_id, title, start_date,
                                          end_date, period_known, still_there, source, user_id,
                                          finalized_at, original_manager_id, resolved_role_id,
                                          resolution, resolved_at)
                VALUES (gen_random_uuid(),$1,$2,'Depot Lead','2019-01-01'::date,$3,TRUE,TRUE,
                        'contributor',$4,now(),$1,$5,'linked',now())
                """).execute(Tuple.of(managerId, companyId, LocalDate.parse(leftOn))
                .addValue(who).addValue(roleId)));
        await(EmploymentStatusResolver.resolveRoleAtomically(pool, roleId));
    }

    private String labelFor(String managerName) throws Exception {
        return labelFrom(await(service.getCompanyProfile("Acme Corp")), managerName);
    }

    private String labelFrom(JsonObject profile, String managerName) {
        JsonArray managers = profile.getJsonArray("managers");
        for (int i = 0; i < managers.size(); i++) {
            JsonObject mgr = managers.getJsonObject(i);
            if (managerName.equals(mgr.getString("name"))) {
                return mgr.getString("employmentStatus");
            }
        }
        return fail("no tile for " + managerName + " in " + profile.encode());
    }

    private int tilesFor(String managerName) throws Exception {
        JsonArray managers = await(service.getCompanyProfile("Acme Corp"))
            .getJsonArray("managers");
        int n = 0;
        for (int i = 0; i < managers.size(); i++) {
            if (managerName.equals(managers.getJsonObject(i).getString("name"))) n++;
        }
        return n;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
