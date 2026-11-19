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
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.MergeSuggestionsRepository;
import org.werkpages.repository.NotificationRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.AdminService;
import org.werkpages.service.ManagerService;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE CAREER HISTORY EDITOR: one contribution that can correct a whole career.
 *
 * <h2>What was wrong with what this replaced</h2>
 *
 * <p>The old edit form asked about "the manager's current position" and could express exactly one.
 * {@code manager_edits} is a flat row - one {@code new_title}, one {@code new_company}, one pair of
 * dates - so somebody who wanted to end a role and record its successor had to submit, leave the
 * page and come back, and somebody who wanted to fix a historical title had no way to say which
 * employment they meant. The client guessed by company NAME, which picks the wrong row for a
 * manager who held two jobs at one employer.
 *
 * <h2>The distinction every test here defends</h2>
 *
 * <p>Correcting an existing position and recording a new one are different acts:
 *
 * <ul>
 *   <li>a CORRECTION keeps the employment, so every rating and claim hanging off it stays put;</li>
 *   <li>an ADDITION is a distinct employment and never ends another one, so two concurrent roles
 *       stay two concurrent roles;</li>
 *   <li>a SUPERSESSION replaces an employment whose EMPLOYER was wrong, because the employer is
 *       what ratings are attributed through.</li>
 * </ul>
 *
 * <p>Collapsing any two of them either loses ratings or invents a job change nobody reported.
 */
@Testcontainers
class CareerEditorIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("career_editor")
        .withUsername("test")
        .withPassword("test");

    static Pool             pool;
    static ManagerService   managerService;
    static AdminService     adminService;
    static EditRepository   editRepo;
    /* Held as a field so the gate tests can drive a real unlink rather than imitating one. */
    static ReviewRepository reviewRepo;

    long  companyId;
    long  otherCompanyId;
    long  managerId;
    UUID  contributorId;
    String contributor = "auth0|career-contributor";
    String adminAuth0  = "auth0|career-admin";

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

        ManagerRepository managerRepo = new ManagerRepository(pool);
        UserRepository    userRepo    = new UserRepository(pool);
        reviewRepo = new ReviewRepository(pool);
        editRepo = new EditRepository(pool);

        managerService = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo,
                                            new ReportRepository(pool), pool);
        adminService   = new AdminService(userRepo, managerRepo, reviewRepo, editRepo,
                                          new NotificationRepository(pool),
                                          new CompanyRepository(pool),
                                          new MergeSuggestionsRepository(pool), pool);
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) {
            pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE notifications, manager_url_history, company_stats_live").execute());
        await(pool.query("TRUNCATE reviews, role_claims, role_corrections, "
            + "employment_status_events, career_history, manager_edit_employments, manager_edits, "
            + "managers, companies, users CASCADE").execute());

        companyId      = insertCompany("Halverson Logistics", "halverson");
        otherCompanyId = insertCompany("Marlowe Freight", "marlowe");
        managerId      = insertManager("Priya Raghunathan", "priya-r");
        contributorId  = insertUser(contributor, "user");
        /*
          THE CONTRIBUTION THAT OPENS THE EDITING GATE.

          Correcting a career history now requires having rated a manager, so the fixture that has
          always been called `contributor` has to actually be one.

          RECORDED AS PARTICIPATION RATHER THAN AS A RATING, because a rating is not inert here. A
          `reviews` row would change the things most of these tests measure - reviews_count, the
          segment matching in SEGMENTS_SQL, the rating-date inference that reads MIN(worked_from),
          and the two tests that assert the whole table holds exactly one row. Participation is the
          column the gate reads for a contributor whose rating was later unlinked, so it is a real
          mechanism and not a trick; `aRealPublishedRatingAlsoOpensTheGate` covers the other branch.

          WHICH subject does not matter: the gate asks whether somebody has contributed to this
          corpus at all, not whether they contributed to the page they are looking at.
        */
        insertGateParticipation(contributorId);
        insertUser(adminAuth0, "admin");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // One session, many positions
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * The journey the old form could not do at all: end one role and record its successor without
     * submitting, leaving and coming back.
     */
    @Test
    void oneSubmissionCanEndARoleAndAddItsSuccessor() throws Exception {
        long oldRole = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject()
                .put("explanation", "They moved to the night shift team in March 2023.")
                .put("positions", new JsonArray()
                    .add(correction(oldRole, new JsonArray().add("employment_status").add("end_date"))
                        .put("employmentStatus", "ended").put("endDate", "2023-03"))
                    .add(addition("Night Operations Lead", companyId)
                        .put("startDate", "2023-04").put("employmentStatus", "ongoing")))));

        assertEquals("career_editor", res.getString("changeFormat"));
        assertEquals(2, (int) res.getInteger("positions"));
        // NOTHING IS APPLIED YET. The employment is untouched until a moderator decides.
        assertEquals("ongoing", roleRow(oldRole).getString("employment_status"));
        assertNull(roleRow(oldRole).getOffsetDateTime("end_date"));
        assertEquals(1, countCareerRows(), "no employment is created by submitting");

        await(adminService.approveEdit(adminAuth0, UUID.fromString(res.getString("id"))));

        Row ended = roleRow(oldRole);
        assertEquals("ended", ended.getString("employment_status"));
        assertEquals(LocalDate.parse("2023-03-01"), ended.getOffsetDateTime("end_date").toLocalDate());
        assertEquals(2, countCareerRows(), "the successor is a DISTINCT employment");
        assertEquals("Night Operations Lead", newestRole().getString("title"));
        assertEquals("accepted", newestRole().getString("state"));
    }

    /**
     * ADDING A CURRENT ROLE DOES NOT END ANOTHER ONE.
     *
     * <p>Somebody genuinely holding two jobs at once is recorded as holding two jobs at once.
     * Inferring the end of one role from the start of another is how a career history quietly
     * acquires an end date nobody ever stated - and the old form did exactly that, because its one
     * row had to mean "the current position".
     */
    @Test
    void addingACurrentRoleLeavesAnExistingCurrentRoleAlone() throws Exception {
        long existing = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject()
                .put("explanation", "They hold both roles; the second is a secondment.")
                .put("positions", new JsonArray()
                    .add(addition("Safety Officer", otherCompanyId)
                        .put("startDate", "2022-06").put("employmentStatus", "ongoing")))));
        await(adminService.approveEdit(adminAuth0, UUID.fromString(res.getString("id"))));

        Row untouched = roleRow(existing);
        assertEquals("ongoing", untouched.getString("employment_status"));
        assertNull(untouched.getOffsetDateTime("end_date"),
            "adding a role must never terminate another one");
        assertEquals(2, countCareerRows());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // A name-only correction
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * A SUBMISSION THAT TOUCHES NO EMPLOYMENT STILL TAKES EFFECT.
     *
     * <p>This is the case that breaks if approval decides the format by asking whether a change set
     * exists. A name correction touches nothing, so its change set is legitimately empty, and a
     * presence check would route it to the legacy path - which reads {@code new_title} and
     * {@code new_company}, finds them null, applies nothing, and marks the request approved. The
     * contributor is thanked and their correction is silently discarded.
     */
    @Test
    void aNameOnlyCorrectionIsApprovedAndApplied() throws Exception {
        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject()
                .put("name", "Priya Raghunathan-Iyer")
                .put("explanation", "She took her partner's surname in 2024; see her LinkedIn.")
                .put("positions", new JsonArray())));

        assertEquals(0, (int) res.getInteger("positions"));
        assertEquals("Priya Raghunathan", managerRow().getString("name"), "not applied on submit");

        JsonObject applied = await(adminService.approveEdit(adminAuth0,
            UUID.fromString(res.getString("id"))));

        assertTrue(applied.getBoolean("nameChanged"));
        assertEquals("Priya Raghunathan-Iyer", managerRow().getString("name"));
        assertEquals("approved", editRow(res).getString("status"));
    }

    @Test
    void aSubmissionThatChangesNothingIsRefused() throws Exception {
        Exception e = assertThrows(Exception.class, () ->
            await(managerService.createCareerEditRequest(contributor, managerId,
                new JsonObject().put("explanation", "nothing").put("positions", new JsonArray()))));
        assertTrue(message(e).contains("nothing to submit"), message(e));
    }

    @Test
    void anExplanationIsRequired() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");
        Exception e = assertThrows(Exception.class, () ->
            await(managerService.createCareerEditRequest(contributor, managerId, new JsonObject()
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title")).put("title", "Lead"))))));
        assertTrue(message(e).contains("explanation") || message(e).contains("how you know"),
            message(e));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Identifying the employment
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * TWO SPELLS AT ONE EMPLOYER, AND THE CORRECTION HITS THE RIGHT ONE.
     *
     * <p>Company name cannot distinguish these, and company plus title cannot either. Only the id
     * can, which is why the editor sends one and the server refuses a correction without one.
     */
    @Test
    void aCorrectionNamesTheExactEmploymentEvenWhenTwoShareAnEmployerAndTitle() throws Exception {
        long firstSpell  = insertRole("Depot Supervisor", "2014-01-01", companyId, "ended");
        long secondSpell = insertRole("Depot Supervisor", "2021-01-01", companyId, "ongoing");

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject()
                .put("explanation", "The second spell was as Senior Supervisor, not Supervisor.")
                .put("positions", new JsonArray()
                    .add(correction(secondSpell, new JsonArray().add("title"))
                        .put("title", "Senior Depot Supervisor")))));
        await(adminService.approveEdit(adminAuth0, UUID.fromString(res.getString("id"))));

        assertEquals("Senior Depot Supervisor", roleRow(secondSpell).getString("title"));
        assertEquals("Depot Supervisor", roleRow(firstSpell).getString("title"),
            "the other spell at the same employer, with the same title, must be untouched");
    }

    @Test
    void anEmploymentBelongingToADifferentManagerIsRefused() throws Exception {
        long otherManager = insertManager("Dominic Achebe", "dom-a");
        long theirRole = await(pool.preparedQuery(
                "INSERT INTO career_history (manager_id, company, title, company_id, state, source) "
                + "VALUES ($1,'Halverson Logistics','Their Role',$2,'accepted','contributor') "
                + "RETURNING id")
            .execute(Tuple.of(otherManager, companyId))
            .map(rs -> rs.iterator().next().getLong("id")));

        Exception e = assertThrows(Exception.class, () ->
            await(managerService.createCareerEditRequest(contributor, managerId, new JsonObject()
                .put("explanation", "crafted request")
                .put("positions", new JsonArray()
                    .add(correction(theirRole, new JsonArray().add("title")).put("title", "Hijacked"))))));
        assertTrue(message(e).contains("different manager"), message(e));
        assertEquals("Their Role", roleRow(theirRole).getString("title"));
    }

    @Test
    void correctingTheCompanyInPlaceIsRefusedBecauseItWouldMoveRatings() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");
        Exception e = assertThrows(Exception.class, () ->
            await(managerService.createCareerEditRequest(contributor, managerId, new JsonObject()
                .put("explanation", "wrong employer")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("company"))
                        .put("companyId", otherCompanyId))))));
        assertTrue(message(e).contains("replaces that employment"), message(e));
    }

    /**
     * "I'm not sure" is not an answer the form offers, and the service refuses it.
     *
     * <p>It produced a row with no stated end and no stated dates, which every display surface then
     * had to guess about - and the guesses disagreed, putting older roles above newer ones because
     * somebody did not know a date. `unknown` remains the column's DEFAULT and the state of every
     * row written before this; it is simply never proposed.
     */
    @Test
    void unknownIsNotAStatusAContributorMayAssert() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");
        Exception e = assertThrows(Exception.class, () ->
            await(managerService.createCareerEditRequest(contributor, managerId, new JsonObject()
                .put("explanation", "I do not know whether they are still there")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("employment_status"))
                        .put("employmentStatus", "unknown"))))));
        assertTrue(message(e).contains("either ongoing or ended"), message(e));
        assertEquals("ongoing", roleRow(role).getString("employment_status"));
    }

    /**
     * A row that already holds `unknown` is still readable and editable.
     *
     * <p>Removing the option from the form does not invalidate the data: `unknown` is the column
     * default, so most rows written before this carry it, and they must stay correctable.
     */
    @Test
    void anExistingUnknownRowIsStillOfferedAndCorrectable() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "unknown");

        JsonArray list = await(managerService.getEmploymentsForEditing(managerId));
        JsonObject offered = list.stream()
            .map(o -> (JsonObject) o)
            .filter(o -> role == (o.getLong("id") == null ? -1L : o.getLong("id")))
            .findFirst().orElseThrow();
        assertEquals("unknown", offered.getString("employmentStatus"), "the stored value is shown");
        assertTrue(offered.getBoolean("isCurrent"),
            "nothing says it ended, so the profile treats it as current, as it always did");

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", "They left in 2023.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("employment_status"))
                        .put("employmentStatus", "ended")))));
        await(adminService.approveEdit(adminAuth0, UUID.fromString(res.getString("id"))));
        assertEquals("ended", roleRow(role).getString("employment_status"));
    }

    @Test
    void disputedIsNotAStatusAContributorMayAssert() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");
        Exception e = assertThrows(Exception.class, () ->
            await(managerService.createCareerEditRequest(contributor, managerId, new JsonObject()
                .put("explanation", "I disagree with the other person")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("employment_status"))
                        .put("employmentStatus", "disputed"))))));
        assertTrue(message(e).contains("either ongoing or ended"), message(e));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Company supersession
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * CHANGING THE EMPLOYER PRESERVES EVERY RATING, AND KEEPS THEM PUBLISHED.
     *
     * <p>A claimed rating stores no company: {@code reviews_role_basis_stores_nothing} keeps
     * {@code attributed_company_id} NULL and the employer is derived through claim to employment to
     * company. So the claims are re-pointed at the replacement employment and the attribution
     * follows them, with no rating row touched.
     *
     * <p>Keeping them PUBLISHED is the part that needs proving.
     * {@code rating_role_is_publishable} requires the claim's resolved employment to be
     * {@code accepted}, so leaving the claims on a superseded row would unpublish every rating
     * hanging off it - a correction to a company name would have quietly emptied a manager's
     * profile.
     */
    @Test
    void supersedingAnEmploymentKeepsItsRatingsPresentAndPublished() throws Exception {
        long role  = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");
        long claim = insertResolvedClaim(role, contributorId);
        UUID rating = insertClaimedRating(claim);

        assertTrue(isPublishable(rating), "precondition: the rating is published");

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject()
                .put("explanation", "This was Marlowe Freight, not Halverson. Same job, same team.")
                .put("positions", new JsonArray()
                    .add(supersession(role, otherCompanyId)))));

        // STILL UNTOUCHED until approval, which is the point of a proposal.
        assertEquals("accepted", roleRow(role).getString("state"));

        JsonObject applied = await(adminService.approveEdit(adminAuth0,
            UUID.fromString(res.getString("id"))));

        JsonObject position = applied.getJsonArray("applied").getJsonObject(0);
        assertEquals("supersede", position.getString("intent"));
        assertEquals(1, (int) position.getInteger("claimsMoved"));

        // The rating still exists and is still published.
        assertEquals(1, countRows("reviews"), "no rating disappeared");
        assertTrue(isPublishable(rating), "the rating must stay published");

        // The old employment is retired, not deleted, and points at its replacement.
        Row old = roleRow(role);
        assertEquals("superseded", old.getString("state"));
        assertNotNull(old.getLong("superseded_by"));

        // The claim moved with it, and records where it came from, so the move is not silent.
        Row movedClaim = claimRow(claim);
        assertEquals(old.getLong("superseded_by"), movedClaim.getLong("resolved_role_id"));
        assertEquals(otherCompanyId, movedClaim.getLong("company_id"),
            "claim_resolved_role_matches spans company_id, so it has to move in the same breath");
        assertEquals(role, (long) movedClaim.getLong("canonicalized_from"));
        assertNotNull(movedClaim.getOffsetDateTime("canonicalized_at"));

        // The replacement carries the corrected employer and the values nobody questioned.
        Row replacement = roleRow(old.getLong("superseded_by"));
        assertEquals(otherCompanyId, replacement.getLong("company_id"));
        assertEquals("Depot Supervisor", replacement.getString("title"),
            "a company correction must not blank a title it never asked about");
        assertEquals("accepted", replacement.getString("state"));
    }

    /**
     * An employment whose ownership is already in doubt is not moved as a side effect.
     *
     * <p>An unresolved conflict that blocks publication means the platform does not yet know whose
     * employment this is. Superseding it would settle that question silently, so the approval is
     * refused and the moderator is sent to the conflict first.
     */
    @Test
    void supersessionIsRefusedWhileAConflictIsHoldingRatingsBack() throws Exception {
        long role  = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");
        long claim = insertResolvedClaim(role, contributorId);
        // blocks_publication is GENERATED from kind, so the kind is what decides it.
        // ambiguous_resolution is one that blocks; see kind_blocks_publication().
        /*
          ONE TRANSACTION, because conflict_claims_blocking_has_a_subject is a DEFERRED constraint
          trigger: a blocking conflict must name the claim it holds back by the time the
          transaction commits. Two separate statements fail, since the first commit finds no
          subject. blocks_publication is GENERATED from the kind and cannot be asserted here.
        */
        await(pool.withTransaction(tx -> tx.preparedQuery(
                "INSERT INTO role_conflicts (kind, manager_id, company_id, role_id, title, "
                + "title_key, period_key, manager_name, company_name) "
                + "SELECT 'ambiguous_resolution', ch.manager_id, ch.company_id, ch.id, ch.title, "
                + "ch.title_key, 'unknown', m.name, ch.company "
                + "FROM career_history ch JOIN managers m ON m.id = ch.manager_id "
                + "WHERE ch.id = $1 RETURNING id")
            .execute(Tuple.of(role))
            .map(rs -> rs.iterator().next().getLong("id"))
            .compose(conflictId -> tx.preparedQuery(
                    "INSERT INTO role_conflict_claims (conflict_id, claim_id, role) "
                    + "VALUES ($1, $2, 'subject')")
                .execute(Tuple.of(conflictId, claim)).mapEmpty())));

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", "wrong employer")
                .put("positions", new JsonArray().add(supersession(role, otherCompanyId)))));

        Exception e = assertThrows(Exception.class, () ->
            await(adminService.approveEdit(adminAuth0, UUID.fromString(res.getString("id")))));
        assertTrue(message(e).contains("unresolved conflict"), message(e));

        assertEquals("accepted", roleRow(role).getString("state"), "nothing moved");
        assertEquals("pending", editRow(res).getString("status"), "and the request is still pending");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Location
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * EACH EMPLOYMENT HAS ITS OWN LOCATION, AND THE MANAGER'S IS NOT TOUCHED.
     *
     * <p>The old form's location control wrote the MANAGER's declared location, so correcting where
     * a role was held in 2014 moved the manager's present-day location to 2014's city. V109 gave
     * {@code career_history} its own location columns precisely so the two can differ.
     */
    @Test
    void correctingAnEmploymentsLocationDoesNotMoveTheManager() throws Exception {
        long role = insertRole("Depot Supervisor", "2014-01-01", companyId, "ended");
        await(pool.preparedQuery("UPDATE managers SET declared_country='Canada', "
                + "declared_state='Ontario', declared_city='Toronto', declared_precision='city', "
                + "declared_source='corpus' WHERE id=$1")
            .execute(Tuple.of(managerId)));

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject()
                .put("explanation", "That role was based in Kitchener, not Toronto.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("location"))
                        .put("declaredCountry", "Canada").put("declaredState", "Ontario")
                        .put("declaredCity", "Kitchener").put("declaredPrecision", "city")
                        .put("declaredSource", "corpus")))));
        await(adminService.approveEdit(adminAuth0, UUID.fromString(res.getString("id"))));

        Row employment = roleRow(role);
        assertEquals("Kitchener", employment.getString("declared_city"));

        Row manager = managerRow();
        assertEquals("Toronto", manager.getString("declared_city"),
            "a historical employment's location must never overwrite the manager's own");
    }

    /**
     * An employment nobody has located stays unlocated.
     *
     * <p>Unknown is a real answer the product is required to keep, and a correction about a title
     * says nothing about where the work happened.
     */
    @Test
    void aTitleCorrectionLeavesAnUnknownLocationUnknown() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");
        assertNull(roleRow(role).getString("declared_city"), "precondition: no location");

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", "The title was Supervisor, not Manager.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title")).put("title", "Supervisor")))));
        await(adminService.approveEdit(adminAuth0, UUID.fromString(res.getString("id"))));

        Row after = roleRow(role);
        assertEquals("Supervisor", after.getString("title"));
        assertNull(after.getString("declared_city"));
        assertNull(after.getString("declared_country"));
        assertNull(after.getString("declared_precision"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Staleness
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * A PROPOSAL WRITTEN AGAINST AN OLDER VERSION IS NOT APPLIED OVER THE NEWER ONE.
     *
     * <p>Reading the employment at approval time cannot detect this: the proposal still looks like
     * a clean edit of the title it was written against. Only the snapshot taken at submission makes
     * the divergence visible, and approving regardless would revert whoever got there first with
     * nothing anywhere recording that it happened.
     */
    @Test
    void aProposalIsRefusedWhenSomebodyElseChangedTheSameFieldAfterItWasSubmitted() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", "It is Supervisor, not Manager.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title")).put("title", "Supervisor")))));

        // Somebody else gets there first, between submission and approval.
        await(pool.preparedQuery("UPDATE career_history SET title='Depot Lead' WHERE id=$1")
            .execute(Tuple.of(role)));

        Exception e = assertThrows(Exception.class, () ->
            await(adminService.approveEdit(adminAuth0, UUID.fromString(res.getString("id")))));
        assertTrue(message(e).contains("changed by somebody else"), message(e));

        assertEquals("Depot Lead", roleRow(role).getString("title"), "the newer value survives");
        assertEquals("pending", editRow(res).getString("status"));
    }

    /**
     * A change to a field the proposal says nothing about does not block it.
     *
     * <p>A contributor correcting a title has no opinion about the dates, so a date somebody else
     * changed is not a conflict with their submission. Treating every edit as a conflict would make
     * the queue unusable on any manager more than one person cares about.
     */
    @Test
    void aChangeToAnUnrelatedFieldDoesNotBlockTheProposal() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", "It is Supervisor, not Manager.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title")).put("title", "Supervisor")))));

        await(pool.preparedQuery(
                "UPDATE career_history SET start_date = '2018-06-01'::timestamptz WHERE id=$1")
            .execute(Tuple.of(role)));

        await(adminService.approveEdit(adminAuth0, UUID.fromString(res.getString("id"))));

        Row after = roleRow(role);
        assertEquals("Supervisor", after.getString("title"));
        assertEquals(LocalDate.parse("2018-06-01"), after.getOffsetDateTime("start_date").toLocalDate(),
            "the other person's date change is kept");
    }

    /** The moderator is shown the conflict while reading, not only when the approval fails. */
    @Test
    void theChangeSetReportsStalenessBeforeApprovalIsAttempted() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");
        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", "It is Supervisor, not Manager.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title")).put("title", "Supervisor")))));

        JsonObject before = await(adminService.editChangeSet(adminAuth0,
            UUID.fromString(res.getString("id"))));
        assertFalse(before.getJsonArray("positions").getJsonObject(0).getBoolean("stale"));

        await(pool.preparedQuery("UPDATE career_history SET title='Depot Lead' WHERE id=$1")
            .execute(Tuple.of(role)));

        JsonObject after = await(adminService.editChangeSet(adminAuth0,
            UUID.fromString(res.getString("id"))));
        JsonObject position = after.getJsonArray("positions").getJsonObject(0);
        assertTrue(position.getBoolean("stale"));
        // All three versions travel together, so a moderator can tell a correction from a revert.
        assertEquals("Depot Supervisor", position.getJsonObject("was").getString("title"));
        assertEquals("Supervisor",       position.getJsonObject("proposed").getString("title"));
        assertEquals("Depot Lead",       position.getJsonObject("current").getString("title"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The audit trail
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ONE EXPLANATION, REUSED AS EVERY FIELD'S REASON.
     *
     * <p>{@code role_corrections.reason} is NOT NULL with a non-blank check, so each corrected
     * field needs one. Asking per field would mean retyping the same sentence to fix five things,
     * so the editor asks once during Review Changes and that answer is recorded against each.
     */
    @Test
    void oneExplanationBecomesTheReasonOnEveryFieldItCorrected() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");
        String why = "I reported to them from 2020 and their title changed in the 2021 reshuffle.";

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", why)
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title").add("start_date"))
                        .put("title", "Senior Supervisor").put("startDate", "2018-04")
                        .put("periodKnown", true)))));
        await(adminService.approveEdit(adminAuth0, UUID.fromString(res.getString("id"))));

        JsonArray corrections = await(pool.preparedQuery(
                "SELECT field, was, now_is, reason, corrected_by FROM role_corrections "
                + "WHERE role_id=$1 ORDER BY field")
            .execute(Tuple.of(role))
            .map(rs -> {
                JsonArray out = new JsonArray();
                rs.forEach(r -> out.add(new JsonObject()
                    .put("field", r.getString("field"))
                    .put("reason", r.getString("reason"))
                    .put("by", r.getUUID("corrected_by").toString())));
                return out;
            }));

        assertEquals(2, corrections.size(), "one row per corrected field");
        for (int i = 0; i < corrections.size(); i++) {
            assertEquals(why, corrections.getJsonObject(i).getString("reason"));
            assertEquals(contributorId.toString(), corrections.getJsonObject(i).getString("by"),
                "the correction is the contributor's; the moderator approved it");
        }
    }

    @Test
    void aStatusChangeIsRecordedAsAStatusEventRatherThanAFieldCorrection() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", "They left in 2023.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("employment_status"))
                        .put("employmentStatus", "ended")))));
        await(adminService.approveEdit(adminAuth0, UUID.fromString(res.getString("id"))));

        Row event = await(pool.preparedQuery(
                "SELECT from_status, to_status, basis FROM employment_status_events "
                + "WHERE role_id=$1 ORDER BY id DESC LIMIT 1")
            .execute(Tuple.of(role)).map(rs -> rs.iterator().next()));
        assertEquals("ongoing", event.getString("from_status"));
        assertEquals("ended",   event.getString("to_status"));
        // NOT 'contributor_claim': employment_status_claim_basis_pairs makes that require a claim
        // row, and a correction has none. The authority is the moderator who approved it.
        assertEquals("moderator", event.getString("basis"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Backward compatibility
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * A REQUEST WRITTEN BY THE OLD FORM STILL APPROVES EXACTLY AS IT DID.
     *
     * <p>Proposals submitted before this shipped are still pending, and a deployment that made them
     * unapprovable would strand real contributions. They are told apart by {@code change_format},
     * which defaults to {@code legacy} for every row that already existed.
     */
    @Test
    void aLegacyEditRequestStillApprovesThroughTheOldPath() throws Exception {
        JsonObject created = await(managerService.createEditRequest(contributor, managerId,
            new JsonObject().put("title", "Regional Director")));

        Row stored = await(pool.preparedQuery(
                "SELECT change_format FROM manager_edits WHERE id=$1")
            .execute(Tuple.of(UUID.fromString(created.getString("id"))))
            .map(rs -> rs.iterator().next()));
        assertEquals("legacy", stored.getString("change_format"));

        await(adminService.approveEdit(adminAuth0, UUID.fromString(created.getString("id"))));
        assertEquals("Regional Director", managerRow().getString("title"));
    }

    /**
     * AN OLD BACKEND CANNOT APPROVE A NEW-FORMAT CONTRIBUTION.
     *
     * <p>Two backends share this database and deploy one at a time, so for a while one of them is
     * running code written before the career editor existed. Handed one of these it would read the
     * legacy columns, find them null, apply nothing and mark the request approved - and the change
     * set would still be sitting there, attached to a request that can never be approved again.
     *
     * <p>Application code cannot defend against that, because the code needing the fix is already
     * deployed. {@code manager_edits_format_guard} does, in the database both backends share. This
     * test is the old backend: it updates the row directly, without announcing the format.
     */
    @Test
    void anUnmarkedApprovalOfACareerEditorRequestIsRefusedByTheDatabase() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");
        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", "It is Supervisor, not Manager.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title")).put("title", "Supervisor")))));
        UUID editId = UUID.fromString(res.getString("id"));

        Exception e = assertThrows(Exception.class, () ->
            await(pool.preparedQuery("UPDATE manager_edits SET status='approved' WHERE id=$1")
                .execute(Tuple.of(editId))));
        assertTrue(message(e).contains("career history editor"), message(e));

        // THE WORK SURVIVES: still pending, change set intact, so a current backend can decide it.
        assertEquals("pending", editRow(res).getString("status"));
        assertEquals(1, countRows("manager_edit_employments"));
    }

    /** The same guard covers rejection, which an old screen could not read either. */
    @Test
    void anUnmarkedRejectionOfACareerEditorRequestIsAlsoRefused() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");
        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", "It is Supervisor, not Manager.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title")).put("title", "Supervisor")))));

        assertThrows(Exception.class, () ->
            await(pool.preparedQuery("UPDATE manager_edits SET status='rejected' WHERE id=$1")
                .execute(Tuple.of(UUID.fromString(res.getString("id"))))));
        assertEquals("pending", editRow(res).getString("status"));
    }

    /** Rejecting through the service works, because it announces the format. */
    @Test
    void rejectingACareerEditorRequestThroughTheServiceSucceeds() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");
        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", "It is Supervisor, not Manager.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title")).put("title", "Supervisor")))));

        await(adminService.rejectEdit(adminAuth0, UUID.fromString(res.getString("id"))));

        assertEquals("rejected", editRow(res).getString("status"));
        assertEquals("Depot Supervisor", roleRow(role).getString("title"), "and nothing was applied");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Atomicity
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * A CONTRIBUTION IS ONE TRANSACTION, SO A FAILURE LEAVES NOTHING HALF-APPLIED.
     *
     * <p>Here the second position is stale, which refuses the whole approval. The first position
     * must not have been applied, and the name must not have changed: a contribution approved in
     * pieces leaves a moderator with no way to know what ran.
     */
    @Test
    void aRefusedApprovalAppliesNoneOfTheContribution() throws Exception {
        long first  = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");
        long second = insertRole("Night Lead", "2015-01-01", companyId, "ended");

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject()
                .put("name", "Priya R. Iyer")
                .put("explanation", "Two corrections and a name fix.")
                .put("positions", new JsonArray()
                    .add(correction(first, new JsonArray().add("title")).put("title", "Supervisor"))
                    .add(correction(second, new JsonArray().add("title")).put("title", "Night Supervisor")))));

        // The second position goes stale.
        await(pool.preparedQuery("UPDATE career_history SET title='Night Manager' WHERE id=$1")
            .execute(Tuple.of(second)));

        assertThrows(Exception.class, () ->
            await(adminService.approveEdit(adminAuth0, UUID.fromString(res.getString("id")))));

        assertEquals("Depot Supervisor", roleRow(first).getString("title"),
            "the first position must not have been applied");
        assertEquals("Night Manager", roleRow(second).getString("title"));
        assertEquals("Priya Raghunathan", managerRow().getString("name"),
            "and the name must not have changed");
        assertEquals("pending", editRow(res).getString("status"));
    }

    /** A second submission replaces the first rather than queueing a second decision. */
    @Test
    void resubmittingReplacesThePendingContribution() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");

        await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", "First attempt.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title")).put("title", "Wrong")))));
        JsonObject second = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", "Second attempt, this one is right.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title")).put("title", "Supervisor")))));

        assertEquals(1, countRows("manager_edits"), "one pending contribution per contributor");
        assertEquals(1, countRows("manager_edit_employments"),
            "the abandoned draft's positions must not linger in the change set");

        await(adminService.approveEdit(adminAuth0, UUID.fromString(second.getString("id"))));
        assertEquals("Supervisor", roleRow(role).getString("title"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The editor's own read
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void theEditorListsEachEmploymentSeparatelyWithItsOwnId() throws Exception {
        // The manager record's own position is recorded here, so this test sees only real rows;
        // the synthetic entry has its own tests above.
        await(pool.preparedQuery("UPDATE managers SET title='Depot Supervisor' WHERE id=$1")
            .execute(Tuple.of(managerId)));
        long current  = insertRole("Depot Supervisor", "2021-01-01", companyId, "ongoing");
        long previous = insertRole("Night Lead", "2015-01-01", companyId, "ended");
        await(pool.preparedQuery("UPDATE career_history SET end_date='2019-12-01'::timestamptz "
                + "WHERE id=$1").execute(Tuple.of(previous)));

        JsonArray list = await(managerService.getEmploymentsForEditing(managerId));

        assertEquals(2, list.size());
        // Current first, which is the order the editor shows them in.
        assertEquals(current, (long) list.getJsonObject(0).getLong("id"));
        assertTrue(list.getJsonObject(0).getBoolean("isCurrent"));
        assertEquals(previous, (long) list.getJsonObject(1).getLong("id"));
        assertFalse(list.getJsonObject(1).getBoolean("isCurrent"));
        // Year and month, never a fabricated day.
        assertEquals("2021-01", list.getJsonObject(0).getString("startDate"));
    }

    @Test
    void aSupersededEmploymentIsNotOfferedForCorrection() throws Exception {
        // Likewise: the surviving row IS the manager record's position, so nothing is synthesised.
        await(pool.preparedQuery("UPDATE managers SET title='Depot Supervisor', company_id=$2 "
                + "WHERE id=$1").execute(Tuple.of(managerId, otherCompanyId)));
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");
        long replacement = insertRole("Depot Supervisor", "2019-01-01", otherCompanyId, "ongoing");
        await(pool.preparedQuery(
                "UPDATE career_history SET state='superseded', superseded_by=$2 WHERE id=$1")
            .execute(Tuple.of(role, replacement)));

        JsonArray list = await(managerService.getEmploymentsForEditing(managerId));
        assertEquals(1, list.size());
        assertEquals(replacement, (long) list.getJsonObject(0).getLong("id"));
    }


    // ══════════════════════════════════════════════════════════════════════════
    // Dates nobody recorded, taken from the ratings
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * AN EMPLOYMENT NOBODY HAS DATED STILL HAS DATES TO SHOW, AND SAYS WHERE THEY CAME FROM.
     *
     * <p>The editor opened with empty date fields for a position whose dates the profile was
     * displaying directly above it. The profile gets them from the ratings, because every rating
     * asks when the person worked with this manager, and {@code SEGMENTS_SQL} already does
     * {@code COALESCE(ch.start_date, MIN(worked_from))}. The form read the raw column and showed
     * nothing, so the same employment had dates on one surface and none on the other.
     *
     * <p>This asserts the span is the OUTER bound across every rating - earliest start, latest end
     * - and that both values are reported as inferred. That flag is the whole difference between
     * offering a derivation for correction and presenting it as something somebody stated.
     */
    @Test
    void anUndatedEmploymentTakesItsDatesFromTheRatingsAndSaysTheyAreEstimated() throws Exception {
        long role = insertRole("Lead", "2019-01-01", companyId, "unknown");
        // What a role recorded before anybody asked for dates looks like: a title, a company and
        // no period at all.
        await(pool.preparedQuery("UPDATE career_history SET start_date=NULL, end_date=NULL, "
                + "period_known=FALSE WHERE id=$1").execute(Tuple.of(role)));
        // Two overlapping spells, so the assertion is about the OUTER bound rather than about
        // whichever rating happens to be read first.
        insertRatingSpanning("Halverson Logistics", "Lead", "2012-05-01", "2013-06-01");
        insertRatingSpanning("Halverson Logistics", "Lead", "2013-01-01", "2014-02-01");

        JsonObject only = await(managerService.getEmploymentsForEditing(managerId)).getJsonObject(0);

        assertEquals("2012-05", only.getString("startDate"),
            "the earliest point anybody reported working with them in this role");
        assertEquals("2014-02", only.getString("endDate"), "and the latest");
        assertTrue(only.getBoolean("startDateInferred"), "it is a bound, not a record");
        assertTrue(only.getBoolean("endDateInferred"));
        /*
          AND THE INFERRED END DATE MARKS THE ROLE PREVIOUS.

          Deliberate product decision, 2026-11-17: a role nobody has reported working in since
          2014 is more likely finished than current. It is the better guess rather than a certain
          one, which is why it arrives labelled and editable instead of silently applied.
        */
        assertFalse(only.getBoolean("isCurrent"),
            "an end date, even an inferred one, means the role is not still held");
    }

    /**
     * A RECORDED DATE IS NEVER OVERWRITTEN BY AN INFERRED ONE, NOR LABELLED AS INFERRED.
     *
     * <p>The ratings here imply a completely different span from the one the employment records.
     * Somebody stated those dates, so they win outright: the inference exists to fill a gap, not
     * to correct a contribution. Getting this backwards would quietly rewrite dated history out of
     * rating snapshots.
     */
    @Test
    void anEmploymentWithItsOwnDatesIsNeverLabelledEstimated() throws Exception {
        long role = insertRole("Lead", "2019-01-01", companyId, "ended");
        await(pool.preparedQuery(
                "UPDATE career_history SET end_date='2021-03-01'::timestamptz WHERE id=$1")
            .execute(Tuple.of(role)));
        insertRatingSpanning("Halverson Logistics", "Lead", "2012-05-01", "2014-02-01");

        JsonObject only = await(managerService.getEmploymentsForEditing(managerId)).getJsonObject(0);

        assertEquals("2019-01", only.getString("startDate"), "what somebody actually recorded");
        assertEquals("2021-03", only.getString("endDate"));
        assertFalse(only.getBoolean("startDateInferred"));
        assertFalse(only.getBoolean("endDateInferred"));
    }

    /**
     * THE MANAGER RECORD'S OWN POSITION GETS THE SAME TREATMENT, WHICH IS THE CASE THAT MATTERS
     * MOST.
     *
     * <p>A manager created by somebody searching has a title and company on its own row and
     * nothing in {@code career_history} at all, so the ratings are the only dates in existence.
     * This is the shape the empty date fields were actually reported on.
     */
    @Test
    void theManagersOwnPositionTakesItsDatesFromTheRatingsToo() throws Exception {
        assertEquals(0, countCareerRows(), "precondition: nothing recorded");
        insertRatingSpanning("Halverson Logistics", "Lead", "2016-04-01", "2018-09-01");

        JsonObject first = await(managerService.getEmploymentsForEditing(managerId)).getJsonObject(0);

        assertTrue(first.getBoolean("fromManagerRecord"));
        assertEquals("2016-04", first.getString("startDate"));
        assertEquals("2018-09", first.getString("endDate"));
        assertTrue(first.getBoolean("startDateInferred"));
        assertTrue(first.getBoolean("endDateInferred"));
        // Still a read. A GET that writes rows lets anybody fill the table by loading pages.
        assertEquals(0, countCareerRows());
    }

    /**
     * A RATING FOR A DIFFERENT JOB CONTRIBUTES NO DATES TO THIS ONE.
     *
     * <p>The match is on the company and title each rating snapshotted, which is how
     * {@code SEGMENTS_SQL} does it. Matching on the manager alone would pool every rating the
     * person has ever received and hand each employment the same span, making the span of a
     * long career look like the span of one job.
     */
    @Test
    void ratingsForAnotherEmploymentDoNotDateThisOne() throws Exception {
        long role = insertRole("Lead", "2019-01-01", companyId, "unknown");
        await(pool.preparedQuery("UPDATE career_history SET start_date=NULL, end_date=NULL, "
                + "period_known=FALSE WHERE id=$1").execute(Tuple.of(role)));
        // Right manager, wrong job: a different title at the same employer.
        insertRatingSpanning("Halverson Logistics", "Night Lead", "2005-01-01", "2007-01-01");
        // And the right title at a different employer.
        insertRatingSpanning("Marlowe Freight", "Lead", "2001-01-01", "2003-01-01");

        JsonObject only = await(managerService.getEmploymentsForEditing(managerId)).getJsonObject(0);

        assertNull(only.getString("startDate"), "no evidence about THIS employment");
        assertNull(only.getString("endDate"));
        assertFalse(only.getBoolean("startDateInferred"));
        assertFalse(only.getBoolean("endDateInferred"));
        // With no end date and a status that is not 'ended', it is still treated as held.
        assertTrue(only.getBoolean("isCurrent"));
    }


    // ══════════════════════════════════════════════════════════════════════════
    // The position the manager record asserts
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * A MANAGER WITH A TITLE AND NO EMPLOYMENT ROW STILL HAS A FIRST POSITION TO EDIT.
     *
     * <p>Most managers are like this: one created by somebody searching for them carries a title
     * and company on its own row and nothing in {@code career_history}. The editor showed those
     * "No positions are recorded yet", which is wrong on its face, because the profile displays
     * that position directly above the form.
     */
    @Test
    void theEditorOffersTheManagersOwnPositionWhenNoEmploymentRowExists() throws Exception {
        assertEquals(0, countCareerRows(), "precondition: nothing recorded");

        JsonArray list = await(managerService.getEmploymentsForEditing(managerId));

        assertEquals(1, list.size());
        JsonObject first = list.getJsonObject(0);
        assertNull(first.getLong("id"), "it has no employment row yet, so it has no id");
        assertTrue(first.getBoolean("fromManagerRecord"));
        assertEquals("Lead", first.getString("title"));
        assertEquals("ongoing", first.getString("employmentStatus"));
        // AND READING IT WROTE NOTHING. A GET that creates rows lets anyone fill the table by
        // loading pages.
        assertEquals(0, countCareerRows());
    }

    @Test
    void theManagersOwnPositionIsNotOfferedTwiceOnceItIsRecorded() throws Exception {
        insertRole("Lead", "2019-01-01", companyId, "ongoing");

        JsonArray list = await(managerService.getEmploymentsForEditing(managerId));

        assertEquals(1, list.size(), "the recorded row IS that position, not a second one");
        assertNotNull(list.getJsonObject(0).getLong("id"));
        assertFalse(list.getJsonObject(0).containsKey("fromManagerRecord")
                    && list.getJsonObject(0).getBoolean("fromManagerRecord"));
    }

    /**
     * Correcting it writes the employment down and corrects it, rather than recording a duplicate
     * beside the one the profile already displays.
     */
    @Test
    void correctingTheManagersOwnPositionMaterialisesOneEmploymentAndFixesTheHeadline() throws Exception {
        await(pool.preparedQuery("UPDATE managers SET title='Enginering Manager' WHERE id=$1")
            .execute(Tuple.of(managerId)));

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", "The title is misspelled on the profile.")
                .put("positions", new JsonArray().add(new JsonObject()
                    .put("intent", "add").put("roleId", (Long) null)
                    .put("fromManagerRecord", true)
                    .put("changedFields", new JsonArray().add("title"))
                    .put("title", "Engineering Manager")))));
        await(adminService.approveEdit(adminAuth0, UUID.fromString(res.getString("id"))));

        assertEquals(1, countCareerRows(), "exactly one employment, not a duplicate");
        assertEquals("Engineering Manager", newestRole().getString("title"));
        // THE HEADLINE TOO. managers.title is what the profile header and every directory tile
        // show; leaving it behind would make the correction look as though it had not worked.
        assertEquals("Engineering Manager", managerRow().getString("title"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // What a correction does to existing ratings
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * A CORRECTED TITLE REACHES THE RATINGS THAT RECORDED THE OLD ONE.
     *
     * <p>{@code reviews.manager_title} is a snapshot taken when a rating was written, and three
     * things read it: the rating card, the career insights grouping, and
     * {@code EFFECTIVE_WORKED_UNTIL}, which matches career history to ratings on company PLUS
     * TITLE to cap a card that would otherwise say "Present" for a manager who has left.
     *
     * <p>So leaving the snapshot behind is not merely stale text. It breaks that match and the
     * card reverts to "Present" for a role that has ended, which is the defect the matching exists
     * to prevent.
     */
    @Test
    void correctingATitleUpdatesTheRatingsThatRecordedTheOldOne() throws Exception {
        long role  = insertRole("Enginering Manager", "2019-01-01", companyId, "ongoing");
        long claim = insertResolvedClaim(role, contributorId);
        UUID rating = insertClaimedRating(claim);
        await(pool.preparedQuery("UPDATE reviews SET manager_title='Enginering Manager' WHERE id=$1")
            .execute(Tuple.of(rating)));

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", "Spelling.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title"))
                        .put("title", "Engineering Manager")))));
        await(adminService.approveEdit(adminAuth0, UUID.fromString(res.getString("id"))));

        assertEquals("Engineering Manager", roleRow(role).getString("title"));
        assertEquals("Engineering Manager", reviewTitle(rating),
            "the rating card must not keep showing the misspelling the profile just corrected");
        assertEquals(1, countRows("reviews"), "and no rating was lost doing it");
    }

    /**
     * An ADDITION never rewrites an existing rating.
     *
     * <p>A promotion is a new employment, and the ratings written under the old title belong to the
     * role the reviewer actually described. Rewriting them would put a title in somebody's mouth
     * for a period when it was not theirs.
     */
    @Test
    void addingAPositionLeavesExistingRatingTitlesAlone() throws Exception {
        long role  = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");
        long claim = insertResolvedClaim(role, contributorId);
        UUID rating = insertClaimedRating(claim);
        await(pool.preparedQuery("UPDATE reviews SET manager_title='Depot Supervisor' WHERE id=$1")
            .execute(Tuple.of(rating)));

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", "They were promoted in 2023.")
                .put("positions", new JsonArray()
                    .add(addition("Regional Manager", companyId)
                        .put("startDate", "2023-01").put("employmentStatus", "ongoing")))));
        await(adminService.approveEdit(adminAuth0, UUID.fromString(res.getString("id"))));

        assertEquals("Depot Supervisor", reviewTitle(rating),
            "a rating belongs to the role it was written about");
    }

    /**
     * Two spells at one company with the same title are indistinguishable in a text snapshot, so
     * the sync refuses rather than rewriting the ratings of the other spell.
     */
    @Test
    void anAmbiguousTitleCorrectionLeavesRatingSnapshotsAlone() throws Exception {
        long first  = insertRole("Depot Supervisor", "2014-01-01", companyId, "ended");
        long second = insertRole("Depot Supervisor", "2021-01-01", companyId, "ongoing");
        long claim  = insertResolvedClaim(first, contributorId);
        UUID rating = insertClaimedRating(claim);
        await(pool.preparedQuery("UPDATE reviews SET manager_title='Depot Supervisor' WHERE id=$1")
            .execute(Tuple.of(rating)));

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", "The later spell was Senior.")
                .put("positions", new JsonArray()
                    .add(correction(second, new JsonArray().add("title"))
                        .put("title", "Senior Depot Supervisor")))));
        JsonObject applied = await(adminService.approveEdit(adminAuth0,
            UUID.fromString(res.getString("id"))));

        assertEquals("Senior Depot Supervisor", roleRow(second).getString("title"));
        assertEquals("Depot Supervisor", roleRow(first).getString("title"));
        // -1 reports the ambiguity rather than claiming a count.
        assertEquals(-1, (int) applied.getJsonArray("applied").getJsonObject(0)
            .getInteger("ratingTitlesUpdated"));
        assertEquals("Depot Supervisor", reviewTitle(rating),
            "the other spell's rating must not be rewritten");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The submitter's own unpublished manager
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * THE SUBMITTER'S OWN PENDING MANAGER APPLIES IMMEDIATELY, through the SAME applier.
     *
     * <p>A manager still pending approval is not on the directory, so there is nothing for a
     * moderator to protect and editing it has always written through at once. What must not happen
     * is a second implementation of "end this role, add that one" for that case.
     */
    @Test
    void aSubmittersOwnPendingManagerHasChangesAppliedImmediately() throws Exception {
        await(pool.preparedQuery(
                "UPDATE managers SET approval_status='pending_approval', submitted_by=$2 WHERE id=$1")
            .execute(Tuple.of(managerId, contributorId)));
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", "Fixing my own submission.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title"))
                        .put("title", "Supervisor")))));

        assertEquals("applied", res.getString("status"), "no moderation queue for an unpublished draft");
        assertEquals("Supervisor", roleRow(role).getString("title"), "and it took effect at once");
        assertEquals("approved", editRow(res).getString("status"));
    }

    /** Somebody else's pending manager is NOT theirs to apply to directly. */
    @Test
    void anotherPersonsPendingManagerStillGoesThroughModeration() throws Exception {
        UUID someoneElse = insertUser("auth0|other-submitter", "user");
        await(pool.preparedQuery(
                "UPDATE managers SET approval_status='pending_approval', submitted_by=$2 WHERE id=$1")
            .execute(Tuple.of(managerId, someoneElse)));
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject().put("explanation", "Not my submission.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title"))
                        .put("title", "Supervisor")))));

        assertEquals("pending", res.getString("status"));
        assertEquals("Depot Supervisor", roleRow(role).getString("title"), "nothing applied");
    }

    private String reviewTitle(UUID reviewId) throws Exception {
        return await(pool.preparedQuery("SELECT manager_title FROM reviews WHERE id=$1")
            .execute(Tuple.of(reviewId)).map(rs -> rs.iterator().next().getString("manager_title")));
    }


    /**
     * A CURRENT ROLE WITH NO LOCATION OF ITS OWN SHOWS THE MANAGER'S.
     *
     * <p>V109 backfilled nothing, so every employment recorded before it has no location - and the
     * editor showed a blank location field for the very role whose location the profile displays.
     * A blank field invites retyping, and a retyped location is a new assertion.
     */
    @Test
    void aCurrentRoleWithoutALocationOffersTheManagersOwn() throws Exception {
        await(pool.preparedQuery("UPDATE managers SET declared_country='Canada', "
                + "declared_state='Alberta', declared_city='Sherwood Park', "
                + "declared_precision='city', declared_source='corpus' WHERE id=$1")
            .execute(Tuple.of(managerId)));
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");

        JsonObject offered = employmentById(role);
        assertEquals("Sherwood Park", offered.getString("declaredCity"));
        assertEquals("city", offered.getString("declaredPrecision"));
        // Said plainly, so the editor can show where it came from rather than presenting an
        // inherited value as something somebody stated about this role.
        assertTrue(offered.getBoolean("locationFromManager"));
    }

    /**
     * A PREVIOUS ROLE INHERITS NOTHING, which is the earlier instruction and the whole point of
     * the declared/observed split: where somebody is based today is not evidence about where a job
     * was held years ago.
     */
    @Test
    void aPreviousRoleKeepsItsUnknownLocationUnknown() throws Exception {
        await(pool.preparedQuery("UPDATE managers SET declared_country='Canada', "
                + "declared_city='Sherwood Park', declared_precision='city', "
                + "declared_source='corpus' WHERE id=$1")
            .execute(Tuple.of(managerId)));
        long role = insertRole("Night Lead", "2012-01-01", companyId, "ended");

        JsonObject offered = employmentById(role);
        assertNull(offered.getString("declaredCity"));
        assertNull(offered.getString("declaredPrecision"));
        assertFalse(offered.getBoolean("locationFromManager"));
    }

    /** A role that states its own location keeps it, inheriting nothing. */
    @Test
    void aRoleWithItsOwnLocationIsNotOverwrittenByTheManagers() throws Exception {
        await(pool.preparedQuery("UPDATE managers SET declared_country='Canada', "
                + "declared_city='Sherwood Park', declared_precision='city', "
                + "declared_source='corpus' WHERE id=$1")
            .execute(Tuple.of(managerId)));
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");
        await(pool.preparedQuery("UPDATE career_history SET declared_country='Canada', "
                + "declared_city='Kitchener', declared_precision='city', declared_source='corpus' "
                + "WHERE id=$1").execute(Tuple.of(role)));

        JsonObject offered = employmentById(role);
        assertEquals("Kitchener", offered.getString("declaredCity"));
        assertFalse(offered.getBoolean("locationFromManager"));
    }

    private JsonObject employmentById(long roleId) throws Exception {
        JsonArray list = await(managerService.getEmploymentsForEditing(managerId));
        return list.stream().map(o -> (JsonObject) o)
            .filter(o -> o.getLong("id") != null && roleId == o.getLong("id"))
            .findFirst().orElseThrow(() -> new AssertionError("employment " + roleId + " not offered"));
    }


    /**
     * A MANAGER WHOSE LOCATION IS IN THE RESOLVED COLUMNS STILL SHOWS IT.
     *
     * <p>{@code managers} carries two location layers: the declared one somebody stated, and the
     * resolved one the platform holds in {@code country} / {@code state} / {@code city}. Location
     * is mandatory to add or rate a manager, so every manager has one - but the older flows wrote
     * the RESOLVED columns and left {@code declared_*} empty.
     *
     * <p>Reading only {@code declared_*} reported "No location set" for precisely the managers
     * whose profile was displaying a location. This is that case.
     */
    @Test
    void aManagerLocatedOnlyInTheResolvedColumnsStillShowsItsLocation() throws Exception {
        await(pool.preparedQuery("UPDATE managers SET country='Canada', state='Ontario', "
                + "city='Kitchener', declared_country=NULL, declared_state=NULL, "
                + "declared_city=NULL, declared_precision=NULL WHERE id=$1")
            .execute(Tuple.of(managerId)));

        JsonArray list = await(managerService.getEmploymentsForEditing(managerId));
        JsonObject first = list.getJsonObject(0);

        assertEquals("Kitchener", first.getString("declaredCity"));
        assertEquals("Ontario",   first.getString("declaredState"));
        assertEquals("Canada",    first.getString("declaredCountry"));
        // The precision rule mirrors visibleManagerLocation(): a city present means `city`.
        assertEquals("city", first.getString("declaredPrecision"));
    }

    /** Country only, which is what a coarse answer looks like, yields `country`. */
    @Test
    void aCountryOnlyManagerLocationYieldsCountryPrecision() throws Exception {
        await(pool.preparedQuery("UPDATE managers SET country='Canada', state=NULL, city=NULL, "
                + "declared_country=NULL, declared_precision=NULL WHERE id=$1")
            .execute(Tuple.of(managerId)));

        JsonObject first = await(managerService.getEmploymentsForEditing(managerId)).getJsonObject(0);
        assertEquals("Canada", first.getString("declaredCountry"));
        assertEquals("country", first.getString("declaredPrecision"));
    }

    /** An empty string in a resolved column is not an answer, and must not pick a precision. */
    @Test
    void blankResolvedLocationPartsAreNotTreatedAsAnswers() throws Exception {
        await(pool.preparedQuery("UPDATE managers SET country='Canada', state='', city='  ', "
                + "declared_country=NULL, declared_precision=NULL WHERE id=$1")
            .execute(Tuple.of(managerId)));

        JsonObject first = await(managerService.getEmploymentsForEditing(managerId)).getJsonObject(0);
        assertNull(first.getString("declaredCity"));
        assertNull(first.getString("declaredState"));
        assertEquals("country", first.getString("declaredPrecision"),
            "'' in city and state must not make this look city-precise");
    }

    /** A declared location still wins over the resolved one, which is the more specific statement. */
    @Test
    void aDeclaredManagerLocationTakesPrecedenceOverTheResolvedOne() throws Exception {
        await(pool.preparedQuery("UPDATE managers SET country='Canada', city='Kitchener', "
                + "declared_country='Canada', declared_city='Sherwood Park', "
                + "declared_precision='city', declared_source='corpus' WHERE id=$1")
            .execute(Tuple.of(managerId)));

        JsonObject first = await(managerService.getEmploymentsForEditing(managerId)).getJsonObject(0);
        assertEquals("Sherwood Park", first.getString("declaredCity"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Who is allowed to correct somebody else's career history
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * THE CONTRIBUTION GATE ON EDITING, which nothing enforced until this.
     *
     * <p><b>What was wrong.</b> Correcting a career history required only an account. Anybody who
     * had signed in once, and contributed nothing, could rewrite the employment record of a
     * manager they had never worked with: titles, employers, dates, and whether a role is still
     * held. The whole of {@code manager_edits} was open to any token holder.
     *
     * <p><b>Why an account alone is not enough.</b> An account costs one social sign-in. A rating
     * is the thing that cannot be mass-produced cheaply and the thing that identifies somebody as
     * a participant in this corpus rather than a passer-by, which is precisely why reading the
     * numbers is already gated on it. Writing to the record was the weaker gate of the two, which
     * is the wrong way round.
     *
     * <p><b>Why the test asserts a REFUSAL rather than a 403.</b> This is the service, and the
     * service's contract is a failed Future carrying the reason. The HTTP status is the handler's
     * business, and {@code AdminModerationJourneyIntegrationTest} is where the status is proved.
     */
    @Test
    void somebodyWhoHasNeverRatedAManagerCannotCorrectACareerHistory() throws Exception {
        String freeloader = "auth0|career-freeloader";
        insertUser(freeloader, "user");
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");

        Exception e = assertThrows(Exception.class, () ->
            await(managerService.createCareerEditRequest(freeloader, managerId, new JsonObject()
                .put("explanation", "I read it somewhere")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title")).put("title", "Lead"))))));
        assertTrue(message(e).contains("contribution_required"), message(e));

        // AND NOTHING WAS WRITTEN. A gate that refuses the response but leaves the proposal in
        // the moderation queue has not refused anything: a moderator would still see it and could
        // still approve it.
        assertEquals(0L, countRows("manager_edits"));
        assertEquals(0L, countRows("manager_edit_employments"));
    }

    /**
     * THE SAME RULE ON THE OLD FORM, because a rule enforced on one of two doors is decoration.
     *
     * <p>{@code POST /api/managers/:id/edit-requests} is the single-position form the career
     * editor replaced. The client stopped calling it, which is exactly why it is worth a test:
     * it is still mounted, still in the published OpenAPI document, and still writes to
     * {@code manager_edits}. Gating only the new path would leave the rule bypassable by anybody
     * who read the spec, and "the frontend does not call it" is the kind of protection section 24
     * of CLAUDE.md exists to forbid.
     */
    @Test
    void theLegacySinglePositionFormIsGatedToo() throws Exception {
        String freeloader = "auth0|legacy-freeloader";
        insertUser(freeloader, "user");

        Exception e = assertThrows(Exception.class, () ->
            await(managerService.createEditRequest(freeloader, managerId, new JsonObject()
                .put("title", "Regional Director")
                .put("company", "Halverson Logistics"))));
        assertTrue(message(e).contains("contribution_required"), message(e));
        assertEquals(0L, countRows("manager_edits"));
    }

    /**
     * The gate opens on a rating, and keeps working for the ordinary contributor.
     *
     * <p>Stated as its own test rather than left implicit in the other forty: if the gate were
     * accidentally inverted, or keyed on the wrong column, every one of those would fail with a
     * refusal and the cause would not be obvious from any of them. This one names it.
     */
    @Test
    void aContributorMayCorrectACareerHistory() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");

        JsonObject res = await(managerService.createCareerEditRequest(contributor, managerId,
            new JsonObject()
                .put("explanation", "I reported to them, and the title is wrong.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title")).put("title", "Lead")))));

        assertEquals("pending", res.getString("status"));
    }

    /**
     * The other branch of the gate: an ordinary live rating, with no participation row at all.
     *
     * <p>{@code hasContributed} reads two sources, and the shared fixture above exercises only
     * one of them. Without this test the gate could be keyed solely on {@code
     * rating_participation} - which is backfilled, and empty for anybody who rated before it
     * existed - and every test in this class would still pass while real contributors were
     * refused.
     */
    @Test
    void aRealPublishedRatingAlsoOpensTheGate() throws Exception {
        String rater = "auth0|career-real-rater";
        UUID   raterId = insertUser(rater, "user");
        long   elsewhere = insertManager("Someone Else", "someone-else");
        await(pool.preparedQuery(
                "INSERT INTO reviews (manager_id, user_id, author, overall_rating, "
                + "manager_company, manager_title, origin) "
                + "VALUES ($1, $2, 'real-rater', 4, 'Halverson Logistics', 'Lead', 'contributor')")
            .execute(Tuple.of(elsewhere, raterId)));

        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");
        JsonObject res = await(managerService.createCareerEditRequest(rater, managerId,
            new JsonObject()
                .put("explanation", "I worked under them for two years.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title")).put("title", "Lead")))));

        assertEquals("pending", res.getString("status"));
    }

    /**
     * AN ADMIN IS NOT ASKED FOR A RATING FIRST.
     *
     * <p>They may already approve, reject and rewrite every one of these fields through the
     * moderation tools, so the gate would protect nothing while building a moderation dead end -
     * the same reasoning that lets an admin see a pending profile at all.
     */
    @Test
    void anAdminMayCorrectACareerHistoryWithoutHavingRated() throws Exception {
        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");

        // adminAuth0 has no rating and no participation row: the @BeforeEach gives those to the
        // contributor only.
        JsonObject res = await(managerService.createCareerEditRequest(adminAuth0, managerId,
            new JsonObject()
                .put("explanation", "Corrected from the employment letter attached to the report.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title")).put("title", "Lead")))));

        assertEquals("pending", res.getString("status"));
    }

    /**
     * THE PERSON WHO SUBMITTED AN UNPUBLISHED PROFILE MAY FIX IT, HAVING RATED NOBODY.
     *
     * <p>This is the case that would have turned the gate into a trap, and it is not a rare one.
     * The rating somebody leaves when they ADD a manager is HELD until that manager is approved,
     * and {@code hasContributed} counts only live ratings - so whoever submitted a profile sixty
     * seconds ago is precisely the account the gate reads as having contributed nothing. The career
     * editor is now the only form offered for a pending manager, so refusing them would leave a
     * typo in their own submission with nothing anywhere that could correct it.
     *
     * <p>It is not somebody else's record either: the profile is not published, and the submitter
     * match is the same flag that makes the change apply immediately rather than queueing - which
     * is why this asserts {@code applied} and not {@code pending}.
     */
    @Test
    void theSubmitterOfAnUnpublishedManagerMayFixItWithoutHavingRated() throws Exception {
        String newcomer = "auth0|career-newcomer";
        UUID   newcomerId = insertUser(newcomer, "user");
        long   draftId = await(pool.preparedQuery(
                "INSERT INTO managers (name, company, title, status, approval_status, company_id, "
                + "slug, overall_rating, reviews_count, category_averages, submitted_by) "
                + "VALUES ('Unpublished Draft','Halverson Logistics','Lead','active',"
                + "'pending_approval',$1,'unpublished-draft',0,0,'{}'::jsonb,$2) RETURNING id")
            .execute(Tuple.of(companyId, newcomerId))
            .map(rs -> rs.iterator().next().getLong("id")));

        JsonObject res = await(managerService.createCareerEditRequest(newcomer, draftId,
            new JsonObject()
                .put("explanation", "I mistyped the start date when I added them.")
                .put("positions", new JsonArray()
                    .add(addition("Depot Supervisor", companyId)
                        .put("startDate", "2021-05").put("employmentStatus", "ongoing")))));

        assertEquals("applied", res.getString("status"),
            "their own unpublished draft writes through rather than queueing");
    }

    /**
     * And the exemption is NARROW: it is the submitter's own draft, not anybody's draft.
     *
     * <p>Without this, "pending_approval" on its own would read as a hole: an unrated stranger
     * could rewrite a submission they did not make. They cannot see such a profile - a pending row
     * is served only to its submitter and to admins - but visibility is not the authorization
     * boundary and must not be the only thing standing here.
     */
    @Test
    void aStrangerCannotUseSomebodyElsesUnpublishedDraftToGetRoundTheGate() throws Exception {
        UUID ownerId = insertUser("auth0|draft-owner", "user");
        long draftId = await(pool.preparedQuery(
                "INSERT INTO managers (name, company, title, status, approval_status, company_id, "
                + "slug, overall_rating, reviews_count, category_averages, submitted_by) "
                + "VALUES ('Somebody Elses Draft','Halverson Logistics','Lead','active',"
                + "'pending_approval',$1,'somebody-elses-draft',0,0,'{}'::jsonb,$2) RETURNING id")
            .execute(Tuple.of(companyId, ownerId))
            .map(rs -> rs.iterator().next().getLong("id")));

        String stranger = "auth0|draft-stranger";
        insertUser(stranger, "user");

        Exception e = assertThrows(Exception.class, () ->
            await(managerService.createCareerEditRequest(stranger, draftId, new JsonObject()
                .put("explanation", "Changing somebody else's unpublished submission")
                .put("positions", new JsonArray()
                    .add(addition("Invented Role", companyId)
                        .put("startDate", "2021-05").put("employmentStatus", "ongoing"))))));
        assertTrue(message(e).contains("contribution_required"), message(e));
        assertEquals(0L, countRows("manager_edits"));
    }

    /**
     * UNLINKING A RATING MUST NOT CLOSE THIS GATE.
     *
     * <p>Unlinking severs {@code reviews.user_id} - that severance IS the unlinking - so the
     * live-rating half of {@code hasContributed} goes blind the moment somebody uses it. The
     * rating stays published and keeps counting toward the manager's average throughout, so
     * closing the editing gate would take the contribution and the access both.
     *
     * <p>What carries the eligibility across is the participation record, which
     * {@code unlinkByOwner} writes in the same transaction as the severance and before it.
     * {@code UnlinkRatingIntegrationTest} already proves profile access survives; this proves the
     * career-editing gate does, which is a separate caller and the newer of the two.
     *
     * <p>Driven through the real severance on purpose. Inserting a participation row by hand would
     * prove the gate reads the table and nothing whatever about whether the unlink path fills it
     * in, which is the half that would actually break.
     */
    @Test
    void unlinkingARatingLeavesTheContributorAbleToCorrectACareerHistory() throws Exception {
        String unlinker   = "auth0|career-unlinker";
        UUID   unlinkerId = insertUser(unlinker, "user");
        long   elsewhere  = insertManager("Rated Then Released", "rated-then-released");

        UUID ratingId = await(pool.preparedQuery(
                "INSERT INTO reviews (manager_id, user_id, author, overall_rating, "
                + "manager_company, manager_title, gate_eligible, origin) "
                + "VALUES ($1, $2, 'unlinker', 4, 'Halverson Logistics', 'Lead', TRUE, "
                + "'contributor') RETURNING id")
            .execute(Tuple.of(elsewhere, unlinkerId))
            .map(rs -> rs.iterator().next().getUUID("id")));

        assertTrue(await(reviewRepo.unlinkByOwner(pool, ratingId, elsewhere, unlinkerId)),
            "the unlink should have happened");

        /*
          The ownership really is gone. Without this, a regression that left user_id in place would
          leave the test passing on the live-rating branch while proving nothing about the gate.
        */
        long stillOwned = await(pool.preparedQuery(
                "SELECT count(*) AS n FROM reviews WHERE user_id = $1")
            .execute(Tuple.of(unlinkerId))
            .map(rs -> rs.iterator().next().getLong("n")));
        assertEquals(0L, stillOwned, "unlinking is the severance of ownership");

        long role = insertRole("Depot Supervisor", "2019-01-01", companyId, "ongoing");
        JsonObject res = await(managerService.createCareerEditRequest(unlinker, managerId,
            new JsonObject()
                .put("explanation", "I reported to them before I let my rating go.")
                .put("positions", new JsonArray()
                    .add(correction(role, new JsonArray().add("title")).put("title", "Lead")))));

        assertEquals("pending", res.getString("status"));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private JsonObject correction(long roleId, JsonArray fields) {
        return new JsonObject().put("intent", "correct").put("roleId", roleId)
            .put("changedFields", fields);
    }

    private JsonObject addition(String title, long company) {
        return new JsonObject().put("intent", "add").put("roleId", (Long) null)
            .put("changedFields", new JsonArray().add("title").add("company")
                .add("employment_status").add("start_date"))
            .put("title", title).put("companyId", company);
    }

    private JsonObject supersession(long roleId, long company) {
        return new JsonObject().put("intent", "supersede").put("roleId", roleId)
            .put("changedFields", new JsonArray().add("company"))
            .put("companyId", company);
    }

    private long insertCompany(String name, String slug) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies (name, slug, status) VALUES ($1,$2,'ghost') RETURNING id")
            .execute(Tuple.of(name, slug))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertManager(String name, String slug) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO managers (name, company, title, status, approval_status, company_id, "
                + "slug, overall_rating, reviews_count, category_averages) "
                + "VALUES ($1,'Halverson Logistics','Lead','active','approved',$2,$3,0,0,'{}'::jsonb) "
                + "RETURNING id")
            .execute(Tuple.of(name, companyId, slug))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    /**
     * Records that this person has rated a manager, which is what the editing gate asks for.
     *
     * <p>{@code gate_eligible} is FALSE by default on this table, so it has to be said explicitly:
     * a held or rejected contribution is recorded too, and does not buy access.
     */
    private void insertGateParticipation(UUID userId) throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO rating_participation (user_id, subject_type, subject_id, "
                + "gate_eligible) VALUES ($1, 'manager', $2, TRUE)")
            .execute(Tuple.of(userId, managerId)));
    }

    private UUID insertUser(String auth0Id, String role) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO users (auth0_id, username, role) VALUES ($1,$2,$3) RETURNING id")
            .execute(Tuple.of(auth0Id, "u" + Math.abs(auth0Id.hashCode()), role))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    /**
     * A rating recording WHEN somebody worked with this manager, which is the only evidence the
     * editor has about an employment nobody has dated.
     *
     * <p>Matched to an employment on the COMPANY AND TITLE the rating snapshotted, the way
     * SEGMENTS_SQL matches it, rather than through a foreign key: a snapshot is all a rating
     * carries.
     */
    private void insertRatingSpanning(String company, String title, String fromIso, String untilIso)
            throws Exception {
        /*
          A FRESH AUTHOR FOR EACH RATING, because one is not allowed to rate the same role twice:
          idx_reviews_user_manager_role_legacy enforces it by account and
          idx_reviews_author_manager_role_legacy by display name, so both have to differ. Two
          spells of overlapping evidence come from two different people in reality, which is also
          the only way they come from here.
        */
        String who  = "rater-" + UUID.randomUUID().toString().substring(0, 8);
        UUID   rater = insertUser(who, "user");
        await(pool.preparedQuery(
                "INSERT INTO reviews (manager_id, user_id, author, overall_rating, "
                + "manager_company, manager_title, worked_from, worked_until, origin) "
                + "VALUES ($1, $2, $3, 4, $4, $5, $6::date, $7::date, 'contributor')")
            .execute(Tuple.of(managerId, rater, who, company, title,
                              LocalDate.parse(fromIso), LocalDate.parse(untilIso))));
    }

    private long insertRole(String title, String startIso, long company, String status)
            throws Exception {
        boolean grounded = !"unknown".equals(status);
        return await(pool.preparedQuery(
                "INSERT INTO career_history (manager_id, company, title, start_date, company_id, "
                + "period_known, state, source, employment_status, status_basis, status_as_of, "
                + "status_resolved_at) "
                + "SELECT $1, c.name, $2, ($3::date)::timestamp AT TIME ZONE 'UTC', $4, TRUE, "
                + "'accepted', 'contributor', $5, "
                + "CASE WHEN $6 THEN 'moderator' ELSE NULL END, "
                + "CASE WHEN $6 THEN now() ELSE NULL END, "
                + "CASE WHEN $6 THEN now() ELSE NULL END "
                + "FROM companies c WHERE c.id = $4 RETURNING id")
            .execute(Tuple.of(managerId, title, LocalDate.parse(startIso), company, status, grounded))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertResolvedClaim(long roleId, UUID user) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO role_claims (proposal_key, manager_id, company_id, title, source, "
                + "user_id, finalized_at, original_manager_id, selected_role_id, resolved_role_id, "
                + "resolved_at, resolution) "
                + "SELECT gen_random_uuid(), ch.manager_id, ch.company_id, ch.title, 'contributor', "
                + "$2, now(), ch.manager_id, ch.id, ch.id, now(), 'linked' "
                + "FROM career_history ch WHERE ch.id = $1 RETURNING id")
            .execute(Tuple.of(roleId, user))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private UUID insertClaimedRating(long claimId) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO reviews (manager_id, user_id, author, overall_rating, role_basis, "
                + "role_required, claim_id, attribution_basis, lineage_basis, manager_company, "
                + "manager_title) "
                + "SELECT rc.manager_id, rc.user_id, 'rater', 4, 'claimed', TRUE, rc.id, 'role', "
                + "'submitted', 'Halverson Logistics', rc.title "
                + "FROM role_claims rc WHERE rc.id = $1 RETURNING id")
            .execute(Tuple.of(claimId))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private boolean isPublishable(UUID reviewId) throws Exception {
        return await(pool.preparedQuery("SELECT rating_role_is_publishable($1) AS ok")
            .execute(Tuple.of(reviewId)).map(rs -> rs.iterator().next().getBoolean("ok")));
    }

    private Row roleRow(long id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM career_history WHERE id=$1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private Row claimRow(long id) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM role_claims WHERE id=$1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private Row managerRow() throws Exception {
        return await(pool.preparedQuery("SELECT * FROM managers WHERE id=$1")
            .execute(Tuple.of(managerId)).map(rs -> rs.iterator().next()));
    }

    private Row editRow(JsonObject created) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM manager_edits WHERE id=$1")
            .execute(Tuple.of(UUID.fromString(created.getString("id"))))
            .map(rs -> rs.iterator().next()));
    }

    private Row newestRole() throws Exception {
        return await(pool.preparedQuery(
                "SELECT * FROM career_history WHERE manager_id=$1 ORDER BY id DESC LIMIT 1")
            .execute(Tuple.of(managerId)).map(rs -> rs.iterator().next()));
    }

    private long countCareerRows() throws Exception {
        return await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM career_history WHERE manager_id=$1")
            .execute(Tuple.of(managerId)).map(rs -> rs.iterator().next().getLong("c")));
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
