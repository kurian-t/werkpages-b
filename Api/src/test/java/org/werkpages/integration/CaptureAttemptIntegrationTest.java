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
import org.werkpages.repository.CaptureAttemptRepository;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.ManagerService;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PHASE 1 of the manager-identity redesign: a captured draft is adopted by the attempt that made
 * it, and by nothing else.
 *
 * <h2>The bug these tests hold down</h2>
 *
 * <p>The add-manager form posts a capture as soon as its first step is valid, which is before the
 * company box has been finished. One attempt to add "Marcus Whitlow at Lumenwerx Systems"
 * therefore left a captured draft at "Lumen", and the submission that followed created a second
 * row: two managers for one person, differing only by how much had been typed when the capture
 * fired.
 *
 * <p>That was fixed by having the submission adopt whatever row <em>resembled</em> it:
 *
 * <pre>
 *   the same name
 *   the captured company a prefix of the submitted one
 *   no submitter yet
 *   captured within the hour
 * </pre>
 *
 * <p>The predicate is tight about the right race and it is still a guess, because it also
 * describes <b>a different person of the same name</b> whom somebody else half-typed at a company
 * whose name starts the same way, minutes earlier. That stranger's draft was rewritten to the
 * adopter's company and handed to the adopter's account, and the person who typed it first lost
 * their work with nothing recording that it had happened.
 *
 * <p>So the client now mints a token when step one first posts and presents the same token at
 * submit. {@code capture_attempts} is the ledger of attempts and {@code managers.capture_token}
 * the reverse pointer, which is what adoption matches on. Nothing is recognised; a row is
 * identified or it is not touched.
 *
 * <h2>Why these assertions, and not the responses</h2>
 *
 * <p>Every one of these reads the stored rows back. The capture endpoint is fire-and-forget - the
 * client discards its response entirely - so a test that asserted on what came back would be
 * asserting on something no part of the product reads, and would have passed against the defect
 * in every case below.
 *
 * <p>{@link #aStrangersLookalikeDraftIsNeverAdopted()} is the one that fails against the previous
 * behaviour: the old predicate matched that draft on every clause.
 *
 * @see org.werkpages.repository.ManagerRepository#adoptCaptureByToken
 * @see org.werkpages.repository.CaptureAttemptRepository
 */
@Testcontainers
class CaptureAttemptIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool                     pool;
    static ManagerService           service;
    static ManagerRepository        managerRepo;
    static CompanyRepository        companyRepo;
    static CaptureAttemptRepository attempts;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load()
            .migrate();

        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(5));

        managerRepo = new ManagerRepository(pool);
        companyRepo = new CompanyRepository(pool);
        attempts    = new CaptureAttemptRepository(pool);

        service = new ManagerService(managerRepo, new ReviewRepository(pool), new UserRepository(pool),
            new EditRepository(pool), new ReportRepository(pool), pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE notifications, manager_url_history, company_stats_live").execute());
        // capture_attempts and managers reference each other, so they go together.
        await(pool.query("TRUNCATE capture_attempts, managers, companies, users CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── Successful adoption ───────────────────────────────────────────────────

    @Test
    void theSubmissionAdoptsTheDraftItsOwnAttemptLeftBehind() throws Exception {
        UUID token = UUID.randomUUID();
        capture(token, "Marcus Whitlow", "Lumen", "Engineer");
        long draftId = managerFor(token);

        assertEquals("pending_approval", stringOf(draftId, "approval_status"),
            "fixture: a capture is held for an admin, never published");
        assertNull(uuidOf(draftId, "submitted_by"), "fixture: nobody has submitted it yet");

        submit(token, "auth0|p1-adopt", "Marcus Whitlow", "Lumenwerx Systems", "Director");

        assertEquals(1, countManagersNamed("Marcus Whitlow"),
            "one attempt, one person, one row: adopting the draft is what stops the second");
        assertEquals(draftId, idOfManagerNamed("Marcus Whitlow"),
            "and it is the SAME row the capture wrote, not a replacement for it");
        assertEquals("Lumenwerx Systems", stringOf(draftId, "company"),
            "the finished company name replaces the fragment the capture caught mid-typing");
        assertNotNull(uuidOf(draftId, "submitted_by"),
            "the draft now belongs to the person who submitted it, or the profile page refuses "
            + "them their own pending row");
        assertEquals(1, countRealReviews(draftId), "and their rating is attached to it");
    }

    @Test
    void submittingResolvesTheAttemptAsSubmitted() throws Exception {
        UUID token = UUID.randomUUID();
        capture(token, "Marcus Whitlow", "Lumen", "Engineer");
        assertNull(outcomeOf(token), "fixture: an attempt is unresolved while the form is open");

        submit(token, "auth0|p1-resolve", "Marcus Whitlow", "Lumenwerx Systems", "Director");

        assertEquals("submitted", outcomeOf(token),
            "the attempt is over, and a replay of its token must now create nothing");
        assertNotNull(resolvedAtOf(token));
    }

    // ── The regression: resemblance is not identity ───────────────────────────

    @Test
    void aStrangersLookalikeDraftIsNeverAdopted() throws Exception {
        /*
         * THE TEST THAT FAILS AGAINST THE PREVIOUS BEHAVIOUR.
         *
         * Somebody else started adding a different Marcus Whitlow and got as far as "Lumen" before
         * leaving. Minutes later an unrelated person submits their own Marcus Whitlow at Lumenwerx
         * Systems. Every clause of the old predicate is satisfied - same name, captured company a
         * prefix of the submitted one, no submitter, under an hour old - so that draft was
         * rewritten to this submitter's company, stamped with their account, and had their rating
         * attached. The first person's work became somebody else's, silently.
         *
         * This submission carries a token of its own, and that token identifies no draft, so it
         * adopts nothing.
         */
        UUID strangers = UUID.randomUUID();
        capture(strangers, "Marcus Whitlow", "Lumen", "Engineer");
        long strangersDraft = managerFor(strangers);

        submit(UUID.randomUUID(), "auth0|p1-stranger", "Marcus Whitlow", "Lumenwerx Systems", "Director");

        assertNull(uuidOf(strangersDraft, "submitted_by"),
            "a stranger's half-finished draft is not handed to whoever submits next");
        assertEquals("Lumen", stringOf(strangersDraft, "company"),
            "nor is it rewritten to a company that person never typed");
        assertEquals("Engineer", stringOf(strangersDraft, "title"),
            "nor retitled");
        assertNull(outcomeOf(strangers),
            "and their attempt is still open: somebody else's submission did not end it");
        assertEquals(0, countRealReviews(strangersDraft),
            "and carries no rating, because nobody rated it");

        /*
         * The duplicate this leaves is real and it is the deliberate trade: two pending rows in
         * the admin queue, which the merge tools exist for, instead of one person's contribution
         * silently becoming another's.
         */
        assertEquals(2, countManagersNamed("Marcus Whitlow"),
            "the submission wrote its own row rather than taking the stranger's");
    }

    @Test
    void aPendingManagerFromBeforeTheLedgerIsNeverAdopted() throws Exception {
        /*
         * Backward compatibility, stated as an assertion. Every manager that existed when V98 ran
         * has capture_token null, and so does every row written by any path other than the add
         * form's capture. A submission carrying a token must not adopt one of them.
         *
         * In SQL that holds because null = null is not true, which is easy to write and easy to
         * break with a COALESCE somebody adds to make a query "work".
         */
        long companyId = await(companyRepo.findOrCreate("Lumen", null, null)).getLong("id");
        long legacy = await(managerRepo.createCapturedDraft(
            "Marcus Whitlow", "Lumen", "Engineer", "US", null, null, null, companyId)).getLong("id");
        assertNull(uuidOf(legacy, "capture_token"), "fixture: a pre-ledger row carries no token");

        submit(UUID.randomUUID(), "auth0|p1-legacy", "Marcus Whitlow", "Lumenwerx Systems", "Director");

        assertNull(uuidOf(legacy, "submitted_by"), "an untokened row belongs to no attempt");
        assertEquals("Lumen", stringOf(legacy, "company"));
    }

    @Test
    void anExistingPendingManagerCanStillBeSubmittedAgainstThroughTheOrdinaryPath() throws Exception {
        /*
         * The positive control for the one above, and the reason the fuzzy matcher stays until
         * phase 4. A submission that carries no usable token still finds an existing manager at
         * the same company by name and attaches to it, exactly as before - otherwise removing
         * name-based ADOPTION would have quietly become removing name-based MATCHING, and every
         * submission for somebody already in the queue would file a second row.
         */
        long companyId = await(companyRepo.findOrCreate("Bramworth Logistics", null, null)).getLong("id");
        long existing = await(managerRepo.createCapturedDraft(
            "Dana Whitfield", "Bramworth Logistics", "Engineer", "US", null, null, null, companyId)).getLong("id");

        submit(null, "auth0|p1-ordinary", "Dana Whitfield", "Bramworth Logistics", "Director");

        assertEquals(1, countManagersNamed("Dana Whitfield"),
            "attached to the row we held, not duplicated");
        assertEquals(1, countRealReviews(existing));
    }

    // ── Tokens that must create and adopt nothing ─────────────────────────────

    @Test
    void anUnknownTokenAdoptsNothingAndInventsNoLedgerRow() throws Exception {
        UUID neverCaptured = UUID.randomUUID();

        submit(neverCaptured, "auth0|p1-unknown", "Fenella Oyelaran", "Ashgrove Health", "Director");

        assertEquals(1, countManagersNamed("Fenella Oyelaran"),
            "a submission whose capture never reached us still creates its manager");
        assertFalse(attemptExists(neverCaptured),
            "and records no attempt, because none was ever seen: the ledger is evidence about "
            + "requests, and inventing a row here would record one that never arrived");
    }

    @Test
    void aSpentTokenAdoptsNothingAndItsFirstOutcomeStands() throws Exception {
        UUID token = UUID.randomUUID();
        capture(token, "Marcus Whitlow", "Lumen", "Engineer");
        long draftId = managerFor(token);
        await(attempts.resolve(null, token, CaptureAttemptRepository.OUTCOME_ABANDONED));

        submit(token, "auth0|p1-spent", "Marcus Whitlow", "Lumenwerx Systems", "Director");

        assertNull(uuidOf(draftId, "submitted_by"),
            "a spent attempt does not adopt again: a retry of one attempt is not a new attempt");
        assertEquals("Lumen", stringOf(draftId, "company"));
        assertEquals("abandoned", outcomeOf(token),
            "and the first outcome stands - the resolve is guarded on resolved_at IS NULL, so a "
            + "later one cannot overwrite what already happened");
    }

    @Test
    void aSpentTokenNeverRecreatesAManager() throws Exception {
        /*
         * The resurrection the ledger exists to prevent. The attempt resolved and its manager is
         * gone - deleted by an admin, or absorbed by a merge - so manager_id is null. A late
         * capture carrying that token must not mint a replacement.
         */
        UUID token = UUID.randomUUID();
        capture(token, "Marcus Whitlow", "Lumen", "Engineer");
        long draftId = managerFor(token);
        await(attempts.resolve(null, token, CaptureAttemptRepository.OUTCOME_SUBMITTED));
        await(managerRepo.delete(draftId));
        assertFalse(attemptExists(token) && managerForOrNull(token) != null,
            "fixture: deleting the manager leaves the attempt and nulls its pointer");

        capture(token, "Marcus Whitlow", "Lumenwerx Systems", "Engineer");

        assertEquals(0, countManagersNamed("Marcus Whitlow"),
            "the attempt is spent, so nothing is recreated for it");
        assertTrue(attemptExists(token), "and the record that it happened is never deleted");
    }

    // ── One attempt, one manager ──────────────────────────────────────────────

    @Test
    void twoCapturesCarryingOneTokenProduceOneManager() throws Exception {
        UUID token = UUID.randomUUID();

        JsonObject first  = capture(token, "Marcus Whitlow", "Lumen", "Engineer");
        JsonObject second = capture(token, "Marcus Whitlow", "Lumenwerx Systems", "Engineer");

        assertEquals(1, countManagersNamed("Marcus Whitlow"),
            "a reload, a retry or a second tab is the same attempt, not a second person");
        assertEquals(first.getLong("id"), second.getLong("id"),
            "and the second request is handed the draft the first one made");
        assertFalse(second.getBoolean("published"),
            "neither is public: a captured draft is never servable, so no tile may be built "
            + "from this response (CLAUDE.md section 41)");
    }

    @Test
    void concurrentCapturesCarryingOneTokenProduceOneManager() throws Exception {
        /*
         * Both requests execute the claim. One inserts and holds the row lock; the other's
         * DO UPDATE waits on it, then reads the winner's committed row with manager_id set and
         * created = false, and creates nothing.
         *
         * No advisory lock, no read-then-write, no retry loop. A preliminary SELECT ... FOR UPDATE
         * would not have helped: a row that does not exist cannot be locked, so both would have
         * read nothing, both inserted, and one failed on the primary key.
         */
        UUID token = UUID.randomUUID();

        Future<JsonObject> a = service.createGhostManager(
            captureBody(token, "Marcus Whitlow", "Lumen", "Engineer"), null);
        Future<JsonObject> b = service.createGhostManager(
            captureBody(token, "Marcus Whitlow", "Lumen", "Engineer"), null);

        JsonObject first  = await(a);
        JsonObject second = await(b);

        assertEquals(1, countManagersNamed("Marcus Whitlow"),
            "two requests carrying one token create one manager between them");
        assertEquals(first.getLong("id"), second.getLong("id"),
            "and both are told about the same row");
        assertEquals(1, countAttempts(), "one attempt, one ledger row");
    }

    // ── Failure must not consume anything ─────────────────────────────────────

    @Test
    void anAbortedCreationLeavesNoLedgerRowAndNoManager() throws Exception {
        /*
         * The property the ORDER inside the transaction buys. The token is claimed first, so a
         * failure cannot leave an orphan manager; and the claim is in the same transaction as the
         * insert, so a failure cannot leave the token consumed with nothing behind it. An earlier
         * design wrote the ledger row independently and had exactly that defect: a later failure
         * spent the token for ever, through the mechanism built to prevent duplicates.
         *
         * The insert here names a company that does not exist, so the foreign key refuses it and
         * takes the whole transaction with it.
         */
        UUID token = UUID.randomUUID();

        assertThrows(Exception.class, () -> await(pool.withTransaction(conn ->
            attempts.claim(conn, token)
                .compose(claimed -> managerRepo.insertCapturedDraft(conn,
                    "Rollback Person", "Nowhere Ltd", "Engineer", "US", null, null, null,
                    9_999_999L, "rollback-person-pending", token)))),
            "fixture: the insert must genuinely fail, or this test proves nothing");

        assertFalse(attemptExists(token),
            "the ledger row went with the transaction, so the token is not spent");
        assertEquals(0, countManagersNamed("Rollback Person"), "and no orphan manager is left");

        // And the retry is therefore a clean first attempt rather than a dead token.
        capture(token, "Rollback Person", "Nowhere Ltd", "Engineer");
        assertEquals(1, countManagersNamed("Rollback Person"),
            "a retry after an aborted attempt still creates the manager");
        assertTrue(managerForOrNull(token) != null, "and the attempt now points at it");
    }

    @Test
    void aSubmissionThatFailsLeavesTheAttemptUnresolved() throws Exception {
        /*
         * The same property on the submission side. The attempt is resolved in the transaction
         * that writes the rating, so a submission that does not complete does not spend the
         * token - and the retry can still adopt its own draft.
         *
         * The refusal used here is a real one: the reviewer says they worked with the manager
         * before the manager started in the role.
         */
        UUID token = UUID.randomUUID();
        capture(token, "Marcus Whitlow", "Lumen", "Engineer");
        long draftId = managerFor(token);

        JsonObject refused = submission("Marcus Whitlow", "Lumenwerx Systems", "Director")
            .put("captureToken", token.toString())
            .put("startDate", "2023-01");
        refused.getJsonObject("review").put("workedFrom", "2020-01");
        assertThrows(Exception.class,
            () -> await(service.createManager(user("auth0|p1-failed"), refused, null)),
            "fixture: this submission has to be refused, or the test proves nothing");

        assertNull(outcomeOf(token),
            "a submission that did not happen does not end the attempt");

        submit(token, "auth0|p1-failed-retry", "Marcus Whitlow", "Lumenwerx Systems", "Director");

        assertEquals(draftId, idOfManagerNamed("Marcus Whitlow"),
            "so the retry still adopts the draft this attempt made");
        assertEquals("submitted", outcomeOf(token));
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** The add-form capture: step one is valid, nothing has been submitted, nothing is published. */
    private JsonObject capture(UUID token, String name, String company, String title) throws Exception {
        return await(service.createGhostManager(captureBody(token, name, company, title), null));
    }

    private static JsonObject captureBody(UUID token, String name, String company, String title) {
        return new JsonObject()
            .put("name",    name)
            .put("company", company)
            .put("title",   title)
            .put("country", "US")
            // No fromSearch: this is the add form, not a search. The two intents this endpoint
            // serves are deliberately different (CLAUDE.md section 41).
            .put("captureToken", token.toString());
    }

    private void submit(UUID token, String auth0Id, String name, String company, String title) throws Exception {
        JsonObject body = submission(name, company, title);
        if (token != null) body.put("captureToken", token.toString());
        await(service.createManager(user(auth0Id), body, null));
    }

    private static JsonObject submission(String name, String company, String title) {
        String[] parts = name.split(" ", 2);
        JsonObject ratings = new JsonObject();
        for (String key : new String[]{
                "Communication Style", "Perceived Approachability",
                "Perceived Clarity of Expectations", "Feedback Style",
                "Perceived Supportiveness", "Decision Making Style",
                "Organization and Planning Style", "Delegation Style",
                "Perceived Professional Demeanor", "Overall Working Experience"}) {
            ratings.put(key, 4.0);
        }
        JsonObject review = new JsonObject()
            .put("overallRating",  4.0)
            .put("ratings",        ratings)
            .put("managerCompany", company)
            .put("managerTitle",   title)
            .put("workedFrom",     "2022-01")
            .put("authorType",     "anonymous")
            .put("author",         "AnonTester99");
        return new JsonObject()
            .put("name",      name)
            .put("firstName", parts[0])
            .put("lastName",  parts.length > 1 ? parts[1] : "X")
            .put("company",   company)
            .put("title",     title)
            .put("image",     "img")
            .put("country",   "US")
            .put("status",    "active")
            .put("startDate", "2020-01")
            .put("review",    review);
    }

    private String user(String auth0Id) throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id,email,username,first_name,last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, auth0Id + "@test.com", "u" + Math.abs(auth0Id.hashCode()),
                              "Test", "User")));
        return auth0Id;
    }

    // ── Reading the database back ─────────────────────────────────────────────

    private long managerFor(UUID token) throws Exception {
        Long id = managerForOrNull(token);
        assertNotNull(id, "fixture: the attempt was expected to have produced a manager");
        return id;
    }

    private Long managerForOrNull(UUID token) throws Exception {
        return await(pool.preparedQuery("SELECT manager_id FROM capture_attempts WHERE token = $1")
            .execute(Tuple.of(token))
            .map(rs -> rs.iterator().hasNext() ? rs.iterator().next().getLong("manager_id") : null));
    }

    private boolean attemptExists(UUID token) throws Exception {
        return await(pool.preparedQuery("SELECT 1 FROM capture_attempts WHERE token = $1")
            .execute(Tuple.of(token)).map(rs -> rs.iterator().hasNext()));
    }

    private String outcomeOf(UUID token) throws Exception {
        return await(pool.preparedQuery("SELECT outcome FROM capture_attempts WHERE token = $1")
            .execute(Tuple.of(token))
            .map(rs -> rs.iterator().hasNext() ? rs.iterator().next().getString("outcome") : null));
    }

    private Object resolvedAtOf(UUID token) throws Exception {
        return await(pool.preparedQuery("SELECT resolved_at FROM capture_attempts WHERE token = $1")
            .execute(Tuple.of(token))
            .map(rs -> rs.iterator().next().getOffsetDateTime("resolved_at")));
    }

    private int countAttempts() throws Exception {
        return await(pool.query("SELECT COUNT(*) AS c FROM capture_attempts").execute()
            .map(rs -> rs.iterator().next().getInteger("c")));
    }

    private String stringOf(long managerId, String column) throws Exception {
        return await(pool.preparedQuery("SELECT " + column + " AS v FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId)).map(rs -> rs.iterator().next().getString("v")));
    }

    private UUID uuidOf(long managerId, String column) throws Exception {
        return await(pool.preparedQuery("SELECT " + column + " AS v FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId)).map(rs -> rs.iterator().next().getUUID("v")));
    }

    private int countManagersNamed(String name) throws Exception {
        return await(pool.preparedQuery("SELECT COUNT(*) AS c FROM managers WHERE name = $1")
            .execute(Tuple.of(name)).map(rs -> rs.iterator().next().getInteger("c")));
    }

    private long idOfManagerNamed(String name) throws Exception {
        return await(pool.preparedQuery("SELECT id FROM managers WHERE name = $1 ORDER BY id")
            .execute(Tuple.of(name)).map(rs -> rs.iterator().next().getLong("id")));
    }

    /** Real opinions only: a capture carries a weighted placeholder that is not one. */
    private int countRealReviews(long managerId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM reviews WHERE manager_id = $1 AND weight = FALSE "
                + "AND deleted_at IS NULL")
            .execute(Tuple.of(managerId)).map(rs -> rs.iterator().next().getInteger("c")));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
