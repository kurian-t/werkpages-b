package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.ContributionDraftRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.ContributionDraftService;
import org.werkpages.service.ContributionDraftService.Written;
import org.werkpages.service.ServiceException;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Private restoration of a signed-in contributor's unfinished rating (V110).
 *
 * <h2>What this file is defending</h2>
 *
 * <p>Two things that are easy to get wrong and expensive to get wrong:
 *
 * <ol>
 *   <li><b>Nobody but the owner can see a draft.</b> Not another account, not moderation, not
 *       somebody holding a draft token. The whole product promise is that a signed-in person's
 *       unfinished words stay private until they choose to publish them.
 *   <li><b>No save silently destroys another save.</b> An interrupted request, a second device
 *       and a stale tab each have a defined outcome, and none of them is "the newest answers
 *       disappear".
 * </ol>
 *
 * <p>The assertions below are written against the SERVICE rather than the repository, because the
 * ownership rule lives there as well as in SQL and a repository-only test would pass while the
 * service handed a draft to the wrong account.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContributionDraftIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("wp_drafts");

    static Vertx vertx;
    static Pool pool;
    static ContributionDraftService service;
    static ContributionDraftRepository repo;
    static org.werkpages.service.CompanyReviewService companyRatings;
    static org.werkpages.service.MaintenanceSweep sweep;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations").load().migrate();

        vertx = Vertx.vertx();
        pool = PgPool.pool(vertx, new PgConnectOptions()
            .setHost(postgres.getHost()).setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername()).setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(5));

        repo = new ContributionDraftRepository(pool);
        service = new ContributionDraftService(repo, new UserRepository(pool));

        /*
          The submission path and the daily housekeeping, wired exactly as MainVerticle wires
          them. Constructed here so the two lifecycle integrations are tested through the same
          setters production uses rather than by calling the repository directly.
        */
        companyRatings = new org.werkpages.service.CompanyReviewService(
                new org.werkpages.repository.CompanyReviewRepository(pool),
                new org.werkpages.repository.CompanyRepository(pool),
                new UserRepository(pool))
            .withContributionDrafts(repo);
        sweep = new org.werkpages.service.MaintenanceSweep(
                new org.werkpages.repository.ReviewRepository(pool),
                new org.werkpages.repository.ManagerRepository(pool),
                new org.werkpages.repository.CompanyRepository(pool),
                new org.werkpages.repository.ProofChallengeRepository(pool),
                new org.werkpages.repository.ConfidenceRepository(pool))
            .withContributionDrafts(repo);
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null)  pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE contribution_drafts, captured_drafts, managers, companies, "
                       + "users CASCADE").execute().mapEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Ownership: the promise the whole feature rests on
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void aDraftIsVisibleOnlyToTheAccountThatWroteIt() throws Exception {
        String mine   = user("auth0|cd-1", "CdOne");
        String theirs = user("auth0|cd-2", "CdTwo");
        long company  = company("Red Hat", "red-hat");

        Written w = await(service.save(mine, body(company, 1, answers(4), null, UUID.randomUUID())));
        assertEquals(200, w.status());
        String id = w.body().getString("id");

        assertTrue(await(service.list(theirs)).getJsonArray("drafts").isEmpty(),
            "another account's Unfinished Ratings must not contain mine");

        ServiceException e = assertThrows(ServiceException.class, () -> awaitCause(service.get(theirs, id)));
        assertEquals(404, e.getStatusCode(),
            "404 rather than 403: a different answer would tell a stranger which draft ids exist");
    }

    @Test
    void anotherAccountCannotDiscardMyDraft() throws Exception {
        String mine   = user("auth0|cd-3", "CdThree");
        String theirs = user("auth0|cd-4", "CdFour");
        long company  = company("Red Hat", "red-hat");

        String id = await(service.save(mine, body(company, 1, answers(4), null, UUID.randomUUID())))
            .body().getString("id");

        assertThrows(ServiceException.class, () -> awaitCause(service.discard(theirs, id)));
        assertEquals(1L, count("SELECT count(*) AS c FROM contribution_drafts"),
            "it is still there, because it was never theirs to remove");
    }

    @Test
    void ownershipCannotBeTransferredEvenDirectlyInTheDatabase() throws Exception {
        String mine   = user("auth0|cd-5", "CdFive");
        String theirs = user("auth0|cd-6", "CdSix");
        long company  = company("Red Hat", "red-hat");
        await(service.save(mine, body(company, 1, answers(4), null, UUID.randomUUID())));
        UUID other = one("SELECT id FROM users WHERE auth0_id = 'auth0|cd-6'").getUUID("id");

        /*
          The application has no transfer path, so this asserts the layer BELOW the application:
          if a future repository, a migration or an operator tried it, the database refuses. The
          privacy guarantee cannot rest on every future query remembering to filter.
        */
        Exception e = assertThrows(Exception.class, () -> awaitCause(
            pool.preparedQuery("UPDATE contribution_drafts SET owner_id = $1, version = version + 1, "
                             + "write_id = gen_random_uuid()")
                .execute(Tuple.of(other)).mapEmpty()));
        assertTrue(String.valueOf(e.getMessage()).contains("cannot be transferred"),
            "the trigger should say so plainly, but said: " + e.getMessage());
        assertEquals(theirs, theirs);  // keeps the second account meaningful to the reader
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Versioning, and the three outcomes a write can have
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void anAcceptedWriteAdvancesTheVersionByExactlyOne() throws Exception {
        String me = user("auth0|cd-7", "CdSeven");
        long company = company("Red Hat", "red-hat");

        Written first = await(service.save(me, body(company, 1, answers(4), null, UUID.randomUUID())));
        assertEquals(1L, first.body().getLong("version"));

        Written second = await(service.save(me, body(company, 1, answers(5), 1L, UUID.randomUUID())));
        assertEquals(200, second.status());
        assertEquals(2L, second.body().getLong("version"));
    }

    @Test
    void aStaleBaseVersionIsRefusedAndChangesNothing() throws Exception {
        String me = user("auth0|cd-8", "CdEight");
        long company = company("Red Hat", "red-hat");

        await(service.save(me, body(company, 1, answers(4), null, UUID.randomUUID())));
        await(service.save(me, body(company, 1, answers(5), 1L, UUID.randomUUID())));

        // A second device still believes version 1.
        Written conflict = await(service.save(me, body(company, 1, answers(2), 1L, UUID.randomUUID())));

        assertEquals(409, conflict.status(), "another device is ahead");
        assertTrue(conflict.body().getBoolean("conflict"));
        assertEquals(2L, conflict.body().getLong("version"), "the server says where it actually is");
        assertEquals(5, conflict.body().getJsonObject("payload").getInteger("overall"),
            "and hands back the newer answers, so a person can choose rather than lose them");
        assertEquals(5, one("SELECT payload FROM contribution_drafts")
                .getJsonObject("payload").getInteger("overall"),
            "nothing was written: a refused write must not half-apply");
        assertEquals(2L, count("SELECT version AS c FROM contribution_drafts"),
            "and the version did not move either");
    }

    @Test
    void aReplayedWriteIsAcknowledgedRatherThanReportedAsAConflict() throws Exception {
        /*
          THE CASE THIS MECHANISM EXISTS FOR, and it is not a rare race.

          An unload save goes out with fetch keepalive. It can commit after the page is gone, so
          its acknowledgement is not merely sometimes lost - on that path it is never available.
          The client therefore comes back believing version N while the server holds N+1.

          Without idempotency the retry carries a stale baseVersion and is answered 409: a
          cross-device conflict reported to somebody who has only ever used one device, whose
          answers are already safely stored. With it, the same writeId is recognised and answered
          from the stored version.
        */
        String me = user("auth0|cd-9", "CdNine");
        long company = company("Red Hat", "red-hat");
        UUID write = UUID.randomUUID();

        Written committed = await(service.save(me, body(company, 1, answers(4), null, write)));
        assertEquals(1L, committed.body().getLong("version"));

        // The same attempt again, because the client never saw the answer.
        Written replay = await(service.save(me, body(company, 1, answers(4), null, write)));

        assertEquals(200, replay.status(), "a replay is a success, not a conflict");
        assertTrue(replay.body().getBoolean("replayed"));
        assertEquals(1L, replay.body().getLong("version"), "answered from what is stored");
        assertEquals(1L, count("SELECT version AS c FROM contribution_drafts"),
            "and applied nothing a second time");
    }

    @Test
    void aReplayAfterTheVersionMovedOnIsStillNotAConflict() throws Exception {
        /*
          The awkward ordering: the lost acknowledgement is for version 2, and by the time the
          retry arrives the client has already managed a third save. The retry must still not be
          reported as a conflict, because the write it names is the one that produced the current
          version.
        */
        String me = user("auth0|cd-10", "CdTen");
        long company = company("Red Hat", "red-hat");
        UUID second = UUID.randomUUID();

        await(service.save(me, body(company, 1, answers(4), null, UUID.randomUUID())));
        await(service.save(me, body(company, 1, answers(5), 1L, second)));

        Written replay = await(service.save(me, body(company, 1, answers(5), 1L, second)));
        assertEquals(200, replay.status());
        assertTrue(replay.body().getBoolean("replayed"));
        assertEquals(2L, replay.body().getLong("version"));
    }

    @Test
    void aMissingWriteIdIsRefused() throws Exception {
        String me = user("auth0|cd-11", "CdEleven");
        long company = company("Red Hat", "red-hat");

        JsonObject noWriteId = body(company, 1, answers(4), null, UUID.randomUUID())
            .put("writeId", (String) null);

        ServiceException e = assertThrows(ServiceException.class,
            () -> awaitCause(service.save(me, noWriteId)));
        assertEquals(400, e.getStatusCode());
        assertEquals(0L, count("SELECT count(*) AS c FROM contribution_drafts"),
            "inventing one server-side would make every retry a new write, which is the bug");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Identity: one draft per person per subject per slot
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void aSecondSaveForOneSubjectUpdatesRatherThanDuplicates() throws Exception {
        String me = user("auth0|cd-12", "CdTwelve");
        long company = company("Red Hat", "red-hat");

        await(service.save(me, body(company, 1, answers(4), null, UUID.randomUUID())));
        await(service.save(me, body(company, 1, answers(5), 1L, UUID.randomUUID())));

        assertEquals(1L, count("SELECT count(*) AS c FROM contribution_drafts"));
    }

    @Test
    void theSecondRatingSlotIsADraftOfItsOwn() throws Exception {
        /*
          Somebody may hold TWO rating opportunities for one subject (opportunity_ordinal_bounded),
          so they may legitimately be drafting the second. A uniqueness key that stopped at the
          subject would have made the second draft overwrite the first.
        */
        String me = user("auth0|cd-13", "CdThirteen");
        long company = company("Red Hat", "red-hat");

        await(service.save(me, body(company, 1, answers(4), null, UUID.randomUUID())));
        await(service.save(me, body(company, 2, answers(2), null, UUID.randomUUID())));

        assertEquals(2L, count("SELECT count(*) AS c FROM contribution_drafts"));
        assertEquals(2, await(service.list(me)).getJsonArray("drafts").size());
    }

    @Test
    void aThirdSlotIsRefused() throws Exception {
        String me = user("auth0|cd-14", "CdFourteen");
        long company = company("Red Hat", "red-hat");

        ServiceException e = assertThrows(ServiceException.class, () -> awaitCause(
            service.save(me, body(company, 3, answers(4), null, UUID.randomUUID()))));
        assertEquals(400, e.getStatusCode());
    }

    @Test
    void anUnknownSubjectTypeIsRefused() throws Exception {
        String me = user("auth0|cd-15", "CdFifteen");
        JsonObject bad = body(1L, 1, answers(4), null, UUID.randomUUID())
            .put("subjectType", "workplace");
        assertThrows(ServiceException.class, () -> awaitCause(service.save(me, bad)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The subject changing underneath a draft
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void aDraftSurvivesItsSubjectAndSaysItCannotBeSubmitted() throws Exception {
        /*
          The product decision is that a draft for a subject that can no longer be rated stays
          VISIBLE and marked unavailable, rather than vanishing. An earlier revision of the schema
          put ON DELETE CASCADE on the subject, which would have deleted exactly these.
        */
        String me = user("auth0|cd-16", "CdSixteen");
        long manager = manager("Dana Scully", "Red Hat");
        await(service.save(me, managerBody(manager, answers(4), UUID.randomUUID())));

        await(pool.preparedQuery("DELETE FROM managers WHERE id = $1").execute(Tuple.of(manager)).mapEmpty());

        JsonArray list = await(service.list(me)).getJsonArray("drafts");
        assertEquals(1, list.size(), "the contributor's work is not collateral damage");
        assertFalse(list.getJsonObject(0).getBoolean("available"));
        assertNotNull(list.getJsonObject(0).getString("unavailableReason"),
            "and it says why, because an entry that cannot be used must explain itself");
    }

    @Test
    void aMergedManagerLeavesTheDraftWithSomewhereToGo() throws Exception {
        String me = user("auth0|cd-17", "CdSeventeen");
        long absorbed = manager("Dana Scully", "Red Hat");
        long survivor = manager("Dana Katherine Scully", "Red Hat");
        await(service.save(me, managerBody(absorbed, answers(4), UUID.randomUUID())));

        // What a merge actually leaves behind: rejected, pointed at the survivor.
        await(pool.preparedQuery(
                "UPDATE managers SET approval_status = 'rejected', merged_into = $1 WHERE id = $2")
            .execute(Tuple.of(survivor, absorbed)).mapEmpty());

        JsonObject d = await(service.list(me)).getJsonArray("drafts").getJsonObject(0);
        assertFalse(d.getBoolean("available"));
        assertEquals(survivor, d.getLong("redirectTo"),
            "read from the subject at read time, so a second merge cannot leave it stale");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Lifecycle: discarding, erasure, retention
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void theContributorCanDiscardTheirOwn() throws Exception {
        String me = user("auth0|cd-18", "CdEighteen");
        long company = company("Red Hat", "red-hat");
        String id = await(service.save(me, body(company, 1, answers(4), null, UUID.randomUUID())))
            .body().getString("id");

        assertTrue(await(service.discard(me, id)).getBoolean("discarded"));
        assertEquals(0L, count("SELECT count(*) AS c FROM contribution_drafts"));
    }

    @Test
    void deletingTheAccountDeletesTheDraftsAndLeavesAnonymousCapturesAlone() throws Exception {
        /*
          Two different lifecycles, deliberately. An identified unfinished rating has no reader
          once its author is gone, so it is deleted outright with no tombstone. An anonymous
          capture was never associated with an account and is moderation's record, so it stays.
        */
        String me = user("auth0|cd-19", "CdNineteen");
        long company = company("Red Hat", "red-hat");
        await(service.save(me, body(company, 1, answers(4), null, UUID.randomUUID())));
        await(pool.preparedQuery(
                "INSERT INTO captured_drafts (kind, company_id, payload, draft_token) "
              + "VALUES ('company_rating', $1, '{}'::jsonb, gen_random_uuid())")
            .execute(Tuple.of(company)).mapEmpty());

        await(pool.query("DELETE FROM users WHERE auth0_id = 'auth0|cd-19'").execute().mapEmpty());

        assertEquals(0L, count("SELECT count(*) AS c FROM contribution_drafts"),
            "deleted outright, because moderation never held it");
        assertEquals(1L, count("SELECT count(*) AS c FROM captured_drafts"),
            "and the anonymous capture lifecycle is untouched by this feature");
    }

    @Test
    void aDraftOlderThanTheRetentionPeriodIsSweptAndAFreshOneIsNot() throws Exception {
        String me = user("auth0|cd-20", "CdTwenty");
        long stale = company("Red Hat", "red-hat");
        long fresh = company("Canonical", "canonical");
        await(service.save(me, body(fresh, 1, answers(4), null, UUID.randomUUID())));
        UUID owner = one("SELECT id FROM users WHERE auth0_id = 'auth0|cd-20'").getUUID("id");

        /*
          The stale row is INSERTED with an old updated_at rather than backdated with an UPDATE,
          because the identity trigger refuses any update that does not advance the version - which
          is deliberate: a versioned write is the only way to change a draft's contents, and
          maintenance that wants to change a draft without one is maintenance worth noticing. The
          sweep only ever DELETEs, so it is unaffected.
        */
        await(pool.preparedQuery("""
                INSERT INTO contribution_drafts
                    (owner_id, subject_type, subject_id, payload, write_id, created_at, updated_at)
                VALUES ($1, 'company', $2, '{"overall":4}'::jsonb, gen_random_uuid(),
                        now() - interval '120 days', now() - interval '91 days')
                """)
            .execute(Tuple.of(owner, stale)).mapEmpty());

        assertEquals(1, await(service.sweepExpired()),
            "one was past " + ContributionDraftService.RETENTION_DAYS + " days");
        assertEquals(1L, count("SELECT count(*) AS c FROM contribution_drafts"));
        assertEquals(fresh, one("SELECT subject_id FROM contribution_drafts").getLong("subject_id"));
    }

    @Test
    void aDraftSaysWhenItWillExpireRatherThanLeavingItToBeDiscovered() throws Exception {
        String me = user("auth0|cd-21", "CdTwentyOne");
        long company = company("Red Hat", "red-hat");
        await(service.save(me, body(company, 1, answers(4), null, UUID.randomUUID())));

        JsonObject listed = await(service.list(me));
        assertEquals(ContributionDraftService.RETENTION_DAYS, listed.getInteger("retentionDays"));
        assertNotNull(listed.getJsonArray("drafts").getJsonObject(0).getString("expiresAt"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Moderation isolation, asserted rather than assumed
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void anUnfinishedSignedInRatingNeverReachesTheModerationQueue() throws Exception {
        /*
          The invariant the two-table design exists to make structural. Asserted by inserting one
          and reading what moderation reads, because "no query joins that table" is a property of
          today's code and this has to stay true of tomorrow's.
        */
        String me = user("auth0|cd-22", "CdTwentyTwo");
        long company = company("Red Hat", "red-hat");
        await(service.save(me, body(company, 1, answers(4), null, UUID.randomUUID())));

        assertEquals(0L, count("SELECT count(*) AS c FROM captured_drafts"),
            "a signed-in draft is not a capture, and moderation reads captures");
        assertEquals(0L, count("SELECT count(*) AS c FROM company_reviews"),
            "nor is it a rating: submission is the boundary, and nobody has submitted");
    }

    @Test
    void aDraftDoesNotCarryModerationStateOrItsCaptureToken() throws Exception {
        String me = user("auth0|cd-23", "CdTwentyThree");
        long company = company("Red Hat", "red-hat");
        UUID capture = UUID.randomUUID();
        await(service.save(me, body(company, 1, answers(4), null, UUID.randomUUID())
            .put("captureToken", capture.toString())));

        JsonObject d = await(service.list(me)).getJsonArray("drafts").getJsonObject(0);
        assertFalse(d.containsKey("captureToken"),
            "a write resolution key is not a handle, and echoing it invites it to be used as one");
        assertFalse(d.containsKey("resolution"), "a contributor is not reading the review queue");
    }

    @Test
    void anAnonymousCaptureIsNotReadableThroughTheContributorRoutes() throws Exception {
        String me = user("auth0|cd-24", "CdTwentyFour");
        long company = company("Red Hat", "red-hat");
        await(pool.preparedQuery(
                "INSERT INTO captured_drafts (kind, company_id, payload, draft_token) "
              + "VALUES ('company_rating', $1, '{\"overall\":5}'::jsonb, gen_random_uuid())")
            .execute(Tuple.of(company)).mapEmpty());

        assertTrue(await(service.list(me)).getJsonArray("drafts").isEmpty(),
            "the only draft-reading route a contributor has must not see captures at all");
    }

    @Test
    void twoAccountsCannotBothContinueOneAnonymousCapture() throws Exception {
        String first  = user("auth0|cd-25", "CdTwentyFive");
        String second = user("auth0|cd-26", "CdTwentySix");
        long company  = company("Red Hat", "red-hat");
        UUID capture  = UUID.randomUUID();

        await(service.save(first, body(company, 1, answers(4), null, UUID.randomUUID())
            .put("captureToken", capture.toString())));

        assertThrows(Exception.class, () -> awaitCause(service.save(second,
            body(company, 1, answers(3), null, UUID.randomUUID())
                .put("captureToken", capture.toString()))));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Submission cleanup, and the transaction it has to happen inside
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void submittingTheRatingRemovesThePrivateDraft() throws Exception {
        /*
          A draft that outlived its own published rating would be offered back as unfinished work,
          and resuming it would look to its author as though their rating had reverted.
        */
        String me = user("auth0|cd-30", "CdThirty");
        long company = company("Red Hat", "red-hat");
        await(service.save(me, body(company, 1, answers(4), null, UUID.randomUUID())));
        assertEquals(1L, count("SELECT count(*) AS c FROM contribution_drafts"));

        // Every category, named from the repository's own list rather than typed out here: a
        // partial rating is refused, and a hand-written list goes stale the day one is added.
        JsonObject ratings = new JsonObject();
        for (String c : org.werkpages.repository.CompanyReviewRepository.CATEGORIES) ratings.put(c, 4.0);
        await(companyRatings.submit(me, "red-hat", new JsonObject()
            .put("overallRating", 4.0)
            .put("ratings", ratings)
            .put("author", "CdThirty")
            .put("workedFrom", "2021-04")
            .putNull("workedUntil")
            .put("country", "Canada")));

        assertEquals(0L, count("SELECT count(*) AS c FROM contribution_drafts"),
            "the finished rating replaces the unfinished one");
        assertEquals(1L, count("SELECT count(*) AS c FROM company_reviews"));
    }

    @Test
    void aRolledBackSubmissionPutsThePrivateDraftBack() throws Exception {
        /*
          THE REASON THE DELETE IS INSIDE THE TRANSACTION rather than after it.

          A submission that failed after the draft had been removed would take somebody's
          unfinished answers with it and give them nothing in return - the worst possible outcome,
          because they would have neither a rating nor the work they had done towards one.

          Asserted at the layer the guarantee lives at: the delete runs, the transaction then
          fails, and the draft must still be there.
        */
        String me = user("auth0|cd-31", "CdThirtyOne");
        long company = company("Red Hat", "red-hat");
        await(service.save(me, body(company, 1, answers(4), null, UUID.randomUUID())));
        UUID owner = one("SELECT id FROM users WHERE auth0_id = 'auth0|cd-31'").getUUID("id");

        assertThrows(Exception.class, () -> awaitCause(
            pool.withTransaction(conn ->
                repo.deleteOnSubmission(conn, owner, "company", company)
                    .compose(v -> conn.query("SELECT 1/0").execute().mapEmpty()))));

        assertEquals(1L, count("SELECT count(*) AS c FROM contribution_drafts"),
            "a failed submission must not cost somebody their unfinished answers");
    }

    @Test
    void anotherAccountsSubmissionLeavesMyDraftAlone() throws Exception {
        String mine   = user("auth0|cd-32", "CdThirtyTwo");
        String theirs = user("auth0|cd-33", "CdThirtyThree");
        long company  = company("Red Hat", "red-hat");
        await(service.save(mine, body(company, 1, answers(4), null, UUID.randomUUID())));
        UUID other = one("SELECT id FROM users WHERE auth0_id = 'auth0|cd-33'").getUUID("id");

        await(pool.withTransaction(conn ->
            repo.deleteOnSubmission(conn, other, "company", company)));

        assertEquals(1L, count("SELECT count(*) AS c FROM contribution_drafts"),
            "cleanup is scoped to the submitter, as every query here is");
        assertEquals(theirs, theirs);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Retention, through the schedule that actually runs it
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void theDailyHousekeepingEnforcesRetention() throws Exception {
        /*
          Having a sweepExpired method is not the same as having retention. This runs the DAILY
          HOUSEKEEPING - the same MaintenanceSweep.runOnce that the scheduler calls - and asserts
          that it is the thing which removes a stale draft. A sweep nothing invokes enforces
          nothing, and that was the state this test was added to end.
        */
        String me = user("auth0|cd-34", "CdThirtyFour");
        long stale = company("Red Hat", "red-hat");
        long fresh = company("Canonical", "canonical");
        await(service.save(me, body(fresh, 1, answers(4), null, UUID.randomUUID())));
        UUID owner = one("SELECT id FROM users WHERE auth0_id = 'auth0|cd-34'").getUUID("id");
        await(pool.preparedQuery("""
                INSERT INTO contribution_drafts
                    (owner_id, subject_type, subject_id, payload, write_id, created_at, updated_at)
                VALUES ($1, 'company', $2, '{"overall":4}'::jsonb, gen_random_uuid(),
                        now() - interval '200 days', now() - interval '91 days')
                """)
            .execute(Tuple.of(owner, stale)).mapEmpty());

        await(sweep.runOnce());

        assertEquals(1L, count("SELECT count(*) AS c FROM contribution_drafts"),
            "the stale one went and the fresh one stayed");
        assertEquals(fresh, one("SELECT subject_id FROM contribution_drafts").getLong("subject_id"));
    }

    @Test
    void theHousekeepingLeavesAnonymousCapturesAlone() throws Exception {
        /*
          The two sweeps sit side by side and do opposite things, so this pins the contrast. An
          anonymous capture is kept for ever: nobody is identified by it and the aggregate is why
          it exists. Unifying the two would quietly start deleting the data the capture feature
          was built to collect.
        */
        long company = company("Red Hat", "red-hat");
        await(pool.preparedQuery(
                "INSERT INTO captured_drafts (kind, company_id, payload, draft_token, created_at, updated_at) "
              + "VALUES ('company_rating', $1, '{}'::jsonb, gen_random_uuid(), "
              + "now() - interval '400 days', now() - interval '400 days')")
            .execute(Tuple.of(company)).mapEmpty());

        await(sweep.runOnce());

        assertEquals(1L, count("SELECT count(*) AS c FROM captured_drafts"),
            "a 400-day-old anonymous capture is still a real partial rating");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private static JsonObject answers(int overall) {
        return new JsonObject().put("overall", overall)
            .put("ratings", new JsonObject().put("work_life_balance", overall));
    }

    private static JsonObject body(long companyId, int ordinal, JsonObject payload,
                                   Long baseVersion, UUID writeId) {
        JsonObject b = new JsonObject()
            .put("subjectType", "company")
            .put("subjectId", companyId)
            .put("ordinal", ordinal)
            .put("payload", payload)
            .put("writeId", writeId.toString());
        if (baseVersion != null) b.put("baseVersion", baseVersion);
        return b;
    }

    private static JsonObject managerBody(long managerId, JsonObject payload, UUID writeId) {
        return new JsonObject()
            .put("subjectType", "manager")
            .put("subjectId", managerId)
            .put("payload", payload)
            .put("writeId", writeId.toString());
    }

    private long company(String name, String slug) throws Exception {
        return one("INSERT INTO companies (name, slug) VALUES ('" + name + "', '" + slug
                 + "') RETURNING id").getLong("id");
    }

    private long manager(String name, String company) throws Exception {
        return one("INSERT INTO managers (name, title, company, status, slug, approval_status) "
                 + "VALUES ('" + name + "', 'Manager', '" + company + "', 'active', '"
                 + name.toLowerCase().replace(' ', '-') + "', 'ghost') RETURNING id").getLong("id");
    }

    private String user(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO users (auth0_id, username, email, role) VALUES ($1, $2, $3, 'user')")
            .execute(Tuple.of(auth0Id, username, username + "@example.com")).mapEmpty());
        return auth0Id;
    }

    private Row one(String sql) throws Exception {
        return await(pool.query(sql).execute().map(rs -> rs.iterator().next()));
    }

    private long count(String sql) throws Exception {
        return await(pool.query(sql).execute().map(rs -> rs.iterator().next().getLong("c")));
    }

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static <T> T awaitCause(Future<T> f) throws Throwable {
        try {
            return f.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause();
        }
    }
}
