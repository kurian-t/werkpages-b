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
import org.werkpages.repository.*;
import org.werkpages.service.*;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Publishing a captured form, and the deletion lifecycle the published row then has.
 *
 * <h2>What this is for</h2>
 *
 * <p>A contribution form that an unauthenticated person fills in completely and then loses at the
 * sign-in wall is kept in {@code captured_drafts}. For the manager forms that moment has always
 * produced a real anonymous {@code reviews} row, reviewed through the manager's own
 * pending_approval gate. For a workplace rating and an interview experience it produced a queue
 * item an admin could read and nothing else, however complete it was.
 *
 * <p>These tests cover the ending those two forms now have, and they are weighted towards the ways
 * it could produce a DUPLICATE rather than towards the happy path. A published rating is public
 * data attributed to nobody: a second copy of one cannot be spotted by the person who wrote it,
 * because they never had an account to see it from.
 */
@Testcontainers
class CapturedDraftPublicationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("draft_pub_test").withUsername("test").withPassword("test");

    static Pool                 pool;
    static Vertx                vertx;
    static AdminService         admin;
    static CompanyReviewService companyRatings;
    static ManagerService       managers;
    static InterviewService     interviews;
    static MaintenanceSweep     sweep;
    static CapturedDraftRepository drafts;

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

        CompanyRepository       companyRepo   = new CompanyRepository(pool);
        UserRepository          userRepo      = new UserRepository(pool);
        CompanyReviewRepository companyReviews = new CompanyReviewRepository(pool);
        InterviewRepository     interviewRepo = new InterviewRepository(pool);

        companyRatings = new CompanyReviewService(companyReviews, companyRepo, userRepo);
        managers = new ManagerService(new ManagerRepository(pool), new ReviewRepository(pool),
                                      userRepo, new EditRepository(pool),
                                      new ReportRepository(pool), companyRepo, pool,
                                      company -> null);
        interviews     = new InterviewService(interviewRepo, companyRepo, userRepo);
        admin = new AdminService(userRepo, new ManagerRepository(pool), new ReviewRepository(pool),
                                 new EditRepository(pool), new NotificationRepository(pool),
                                 companyRepo, new MergeSuggestionsRepository(pool), pool);
        drafts = new CapturedDraftRepository(pool);
        sweep = new MaintenanceSweep(new ReviewRepository(pool), new ManagerRepository(pool),
                                     companyRepo, new ProofChallengeRepository(pool),
                                     new ConfidenceRepository(pool))
            .withCompanyReviews(companyReviews)
            .withInterviews(interviewRepo);
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null)  pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE captured_drafts, company_reviews, interview_reviews, "
                       + "reviews, managers, companies, users CASCADE").execute().mapEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Publishing a MANAGER rating
    //
    // The queue could read a manager_review draft and never publish it, however complete it was.
    // A manager rating dropped off at the SIGN-IN wall has always become a real review at once;
    // the gap was the other case, a form abandoned part-way, autosaved, and complete after all.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void aCompleteManagerDraftBecomesARealReviewBelongingToNobody() throws Exception {
        long managerId = insertManager("Dana Fox", "Red Hat");
        String adminAuth = insertUser("auth0|mgr-pub-1", "MgrPub1", "admin");
        String token = UUID.randomUUID().toString();
        await(managers.autosaveReviewDraft(managerId,
            managerReviewBody(4.0).put("author", "BraveHeron19").put("draftToken", token)));
        // Only an idle form reaches the queue, and the publish must not care which it was.
        await(drafts.markAbandoned(0));

        JsonObject result = await(admin.publishCapturedDraft(adminAuth, draftId()));

        assertTrue(result.getBoolean("success"));
        assertEquals("manager_review", result.getString("kind"));
        Row review = one("SELECT user_id, overall_rating, author, draft_token, disposition, "
                       + "deleted_at, manager_company, manager_title FROM reviews");
        /*
          An ORDINARY review row, which is the whole point. A second class of "admin-approved
          rating" would have to be understood by every read path, every aggregate and every
          moderation tool; this one is understood by all of them already.
        */
        assertNull(review.getUUID("user_id"),
            "nobody signed in, so there is no account to attach - inventing one is fabrication");
        assertEquals(4.0, review.getDouble("overall_rating"));
        assertEquals("live", review.getString("disposition"));
        assertNull(review.getOffsetDateTime("deleted_at"));
        assertEquals("Red Hat", review.getString("manager_company"));
        // A DIFFERENT handle: the person watched the form offer them one and never pressed submit.
        String byline = review.getString("author");
        assertNotNull(byline, "a published review is signed, like every other review on the page");
        assertNotEquals("BraveHeron19", byline,
            "NEVER the handle the draft carried - that is the one its author would recognise");
        assertEquals(token, review.getUUID("draft_token").toString(),
            "identified by the form it came from, which is what stops a second publication");
    }

    @Test
    void anIncompleteManagerDraftIsRefusedAndNamesWhatIsMissing() throws Exception {
        long managerId = insertManager("Dana Fox", "Red Hat");
        String adminAuth = insertUser("auth0|mgr-pub-2", "MgrPub2", "admin");
        /*
          One category answered is exactly what autosave exists to keep, and nowhere near the bar
          for a public opinion. The queue has to say so BEFORE anything is written, not discover it
          by attempting the insert.
        */
        await(managers.autosaveReviewDraft(managerId, new JsonObject()
            .put("draftToken", UUID.randomUUID().toString())
            .put("ratings", new JsonObject().put("Communication Style", 4.0))));
        await(drafts.markAbandoned(0));

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitCause(admin.publishCapturedDraft(adminAuth, draftId())));

        assertEquals(400, refused.getStatusCode());
        assertEquals(0L, count("SELECT count(*) AS c FROM reviews"),
            "a refused publication writes NOTHING - that is what the transaction is for");
        assertNull(one("SELECT resolution FROM captured_drafts").getString("resolution"),
            "and the draft is still waiting, not marked as dealt with");
    }

    @Test
    void aManagerDraftWhoseManagerIsGoneCannotBePublished() throws Exception {
        long managerId = insertManager("Dana Fox", "Red Hat");
        String adminAuth = insertUser("auth0|mgr-pub-3", "MgrPub3", "admin");
        await(managers.autosaveReviewDraft(managerId,
            managerReviewBody(4.0).put("draftToken", UUID.randomUUID().toString())));
        await(drafts.markAbandoned(0));
        /*
          The manager_id is nulled rather than the row deleted, because that is what a deletion
          leaves behind. Reading it as a primitive long is how this threw a NullPointerException
          instead of refusing politely, which compiles and fails only when somebody clicks.
        */
        await(pool.query("UPDATE captured_drafts SET manager_id = NULL").execute().mapEmpty());

        /*
          A ServiceException, NOT a NullPointerException. Reading manager_id as a primitive long
          above the dispatch is how this threw one: it compiles perfectly and fails only at the
          moment an admin presses the button.
        */
        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitCause(admin.publishCapturedDraft(adminAuth, draftId())));

        assertEquals(400, refused.getStatusCode());
        assertEquals(0L, count("SELECT count(*) AS c FROM reviews"));
    }

    @Test
    void submittingTheFormRemovesItsDraftFromTheQueue() throws Exception {
        long managerId = insertManager("Dana Fox", "Red Hat");
        String userAuth = insertUser("auth0|dup-author", "DupAuthor", "user");
        String token = UUID.randomUUID().toString();

        /*
          THE DUPLICATE AN ADMIN WOULD OTHERWISE PUBLISH.

          Somebody fills the manager form in, is bounced to sign in, and the autosave keeps what
          they had. They come back, sign in, and submit it properly. The rating now exists and
          belongs to them.

          The draft used to STAY in Unfinished Forms. Harmless while nothing could publish a
          manager draft; now that something can, it left an admin holding a publishable copy of a
          rating that already existed, and approving it would have produced the same opinion twice
          from the same person with nothing to show they were the same.

          The workplace and interview forms have always cleared theirs here. This one never did.
        */
        await(managers.autosaveReviewDraft(managerId,
            managerReviewBody(4.0).put("author", "DupAuthor").put("draftToken", token)));
        assertEquals(1L, count("SELECT count(*) AS c FROM captured_drafts"),
            "the autosave kept their answers, which is the point of it");

        // They come back and submit it themselves.
        await(managers.createReview("auth0|dup-author", managerId,
            managerReviewBody(4.0).put("author", "DupAuthor").put("draftToken", token), null));

        assertEquals(0L, count("SELECT count(*) AS c FROM captured_drafts"),
            "the form is finished, so there is nothing left for an admin to review");
        assertEquals(1L, count("SELECT count(*) AS c FROM reviews"),
            "and exactly ONE rating exists - the one its author submitted");
        Row review = one("SELECT user_id FROM reviews");
        assertNotNull(review.getUUID("user_id"),
            "it belongs to them, because they signed in and submitted it themselves");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Publishing a workplace rating
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void aCompleteDraftBecomesARealRatingBelongingToNobody() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String adminAuth = insertUser("auth0|dp-admin-1", "DpAdmin1", "admin");
        String token = UUID.randomUUID().toString();
        await(companyRatings.captureDraft("red-hat",
            ratingBody(4.0).put("author", "QuietOtter77").put("draftToken", token)));

        JsonObject result = await(admin.publishCapturedDraft(adminAuth, draftId()));

        assertTrue(result.getBoolean("success"));
        Row rating = one("SELECT user_id, overall_rating, author, draft_token, deleted_at "
                       + "FROM company_reviews");
        /*
          The whole point, and the thing a second class of "admin-approved rating" would have got
          wrong. This is an ORDINARY row: it counts in every aggregate, it is served by every read
          path, and nothing downstream has to know where it came from.
        */
        assertNull(rating.getUUID("user_id"),
            "nobody signed in, so there is no account to attach - inventing one is fabrication");
        assertEquals(4.0, rating.getDouble("overall_rating"));
        /*
          A DIFFERENT HANDLE, and this assertion has now been three things in one afternoon.

          It started out keeping the draft's handle, on the reasoning that the author chose it.
          They did not choose to publish it: they watched the form show them that name and then
          never pressed submit, so seeing it on a public rating tells them the rating was theirs.
          Dropping it entirely was the next attempt and left the card nameless, unlike every other
          rating on the page. A freshly minted handle is both: it looks like its neighbours and it
          is not the one anybody saw.
        */
        String byline = rating.getString("author");
        assertNotNull(byline, "a published rating is signed, like every other rating on the page");
        assertNotEquals("QuietOtter77", byline,
            "NEVER the handle the draft carried - that is the one its author would recognise");
        assertTrue(byline.matches("[A-Za-z]+[0-9]{2}x?"),
            "and it is a handle in the house format rather than anything carried over: " + byline);
        assertEquals(token, rating.getUUID("draft_token").toString(),
            "the publication is identified by the form it came from, which is what stops a second one");
        assertNull(rating.getOffsetDateTime("deleted_at"));
    }

    @Test
    void publishingCountsTowardsTheCompanyAverage() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String adminAuth = insertUser("auth0|dp-admin-2", "DpAdmin2", "admin");
        await(companyRatings.captureDraft("red-hat", ratingBody(5.0)));

        await(admin.publishCapturedDraft(adminAuth, draftId()));

        /*
          company_stats_live is maintained by the database on any company_reviews write (V89), so
          this is really asking whether the publish went through the ordinary insert rather than
          some side door that skips the trigger.
        */
        Row stats = one("SELECT workplace_count, workplace_avg_rating FROM company_stats_live "
                      + "WHERE company_id = " + companyId("red-hat"));
        assertEquals(1L, stats.getLong("workplace_count"),
            "a published rating is a rating: the read model must count it");
        assertEquals(5.0, stats.getBigDecimal("workplace_avg_rating").doubleValue(), 0.001);
    }

    @Test
    void publishingForgetsWhoDroppedItOff() throws Exception {
        /*
          THE RATING MUST NOT BE TRACEABLE TO A PERSON BY ANY ROUTE.

          It carries no user_id, but it does carry the draft_token that identifies the form, so
          while the draft names an account that token is a join straight back to them. A manager
          rating dropped off at the sign-in wall has no such row anywhere, and the ask was that
          these behave the same way.

          Deliberately timed: an admin can see who dropped it off while they are DECIDING, which
          is when that fact is worth having. Afterwards it only serves to re-identify the author
          of something public.
        */
        insertCompany("Red Hat", "red-hat");
        String adminAuth  = insertUser("auth0|dp-admin-17", "DpAdmin17", "admin");
        String dropper    = insertUser("auth0|dp-dropper-1", "DpDropper1", "user");
        await(companyRatings.captureDraft("red-hat", ratingBody(4.0)));
        UUID dropperId = await(pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(dropper)).map(rs -> rs.iterator().next().getUUID("id")));
        await(pool.preparedQuery("UPDATE captured_drafts SET user_id = $1")
            .execute(Tuple.of(dropperId)).mapEmpty());
        assertNotNull(one("SELECT user_id FROM captured_drafts").getUUID("user_id"),
            "the admin sees who it was from while the draft is still in the queue");

        await(admin.publishCapturedDraft(adminAuth, draftId()));

        assertNull(one("SELECT user_id FROM captured_drafts").getUUID("user_id"),
            "once it is public, nothing may say whose it was");
        assertNull(one("SELECT user_id FROM company_reviews").getUUID("user_id"));
        assertEquals(0L, count("""
                SELECT count(*) AS c FROM company_reviews cr
                  JOIN captured_drafts d ON d.resolved_id = cr.id
                 WHERE d.user_id IS NOT NULL
                """), "and no join from the rating reaches an account");
    }

    @Test
    void theDraftRecordsWhatItBecameAndWhoDecided() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String adminAuth = insertUser("auth0|dp-admin-3", "DpAdmin3", "admin");
        await(companyRatings.captureDraft("red-hat", ratingBody(4.0)));

        await(admin.publishCapturedDraft(adminAuth, draftId()));

        Row draft = one("SELECT resolution, resolved_id, resolved_by, reviewed_at FROM captured_drafts");
        assertEquals("published", draft.getString("resolution"));
        assertNotNull(draft.getUUID("resolved_id"), "the trail has to name the rating it created");
        assertNotNull(draft.getUUID("resolved_by"), "and who decided to create it");
        assertNotNull(draft.getOffsetDateTime("reviewed_at"), "and it must leave the queue");
    }

    @Test
    void anIncompleteDraftIsRefusedAndWritesNothing() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String adminAuth = insertUser("auth0|dp-admin-4", "DpAdmin4", "admin");
        /*
          Nine of the ten categories. The form itself refuses this, and the admin path has to
          refuse it for the same reason and in the same words: a corpus where some ratings skipped
          career growth cannot be sliced by career growth, and a published row with a hole in it
          cannot be repaired afterwards because there is nobody to ask.
        */
        JsonObject partial = ratingBody(4.0);
        partial.getJsonObject("ratings").remove("career_growth");
        await(companyRatings.captureDraft("red-hat", partial));

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitCause(admin.publishCapturedDraft(adminAuth, draftId())));
        assertEquals(400, refused.getStatusCode());
        assertEquals(0L, count("SELECT count(*) AS c FROM company_reviews"));
        assertNull(one("SELECT reviewed_at FROM captured_drafts").getOffsetDateTime("reviewed_at"),
            "a refused publish must leave the draft in the queue, not quietly resolved");
    }

    @Test
    void aDraftWithNoCompanyCannotBePublished() throws Exception {
        String adminAuth = insertUser("auth0|dp-admin-5", "DpAdmin5", "admin");
        /*
          Captures arrive with company_id null: the form names a company that does not exist yet,
          and the daily company-creation cap deliberately lets the answers through rather than
          losing them. There is nothing to attach a rating to, and creating the company here would
          be an admin walking around that cap.
        */
        await(companyRatings.captureDraft("nothing-here", ratingBody(4.0)));
        assertNull(one("SELECT company_id FROM captured_drafts").getLong("company_id"));

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitCause(admin.publishCapturedDraft(adminAuth, draftId())));
        assertEquals(400, refused.getStatusCode());
        assertEquals(0L, count("SELECT count(*) AS c FROM company_reviews"));
    }

    @Test
    void thesSameDraftCannotBePublishedTwice() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String adminAuth = insertUser("auth0|dp-admin-6", "DpAdmin6", "admin");
        await(companyRatings.captureDraft("red-hat", ratingBody(4.0)));
        long id = draftId();

        await(admin.publishCapturedDraft(adminAuth, id));
        ServiceException second = assertThrows(ServiceException.class,
            () -> awaitCause(admin.publishCapturedDraft(adminAuth, id)));

        assertEquals(409, second.getStatusCode(),
            "the draft exists and has been dealt with, which is a conflict rather than a 404");
        assertEquals(1L, count("SELECT count(*) AS c FROM company_reviews"),
            "two admins on the queue at once must not produce two ratings");
    }

    @Test
    void severalCapturesOfOneFormProduceOneRating() throws Exception {
        /*
          REWRITTEN when capture began superseding, and worth saying why rather than quietly
          editing the numbers.

          It used to assert that two captures left TWO queue rows, and that publishing one retired
          the other as 'superseded'. That was true while capture was a plain insert. It is not now:
          the form captures on every submit attempt so a partial answer survives, and a plain
          insert would have put one row in the queue per keystroke-fixing attempt.

          The guarantee the test is named for is unchanged and is simply reached earlier: one form
          becomes one rating. The deduplication now happens at capture rather than at publication.

          supersedeSiblings still exists and is still called. It is a backstop for a race between
          two captures rather than the ordinary path, which is the right place for it.
        */
        insertCompany("Red Hat", "red-hat");
        String adminAuth = insertUser("auth0|dp-admin-7", "DpAdmin7", "admin");
        String token = UUID.randomUUID().toString();
        await(companyRatings.captureDraft("red-hat", ratingBody(4.0).put("draftToken", token)));
        await(companyRatings.captureDraft("red-hat", ratingBody(4.5).put("draftToken", token)));
        assertEquals(1L, count("SELECT count(*) AS c FROM captured_drafts"),
            "superseded at capture: one form is one queue item");

        await(admin.publishCapturedDraft(adminAuth, draftId()));

        assertEquals(1L, count("SELECT count(*) AS c FROM company_reviews"));
        assertEquals(0L, count("SELECT count(*) AS c FROM captured_drafts WHERE reviewed_at IS NULL"),
            "and nothing is left in the queue pretending to be a second copy");
    }

    @Test
    void aDraftWithNoTokenPublishesOnceAndCannotBeRepeated() throws Exception {
        /*
          The form generates a token for every session, so a capture without one is an older client
          or a hand-made request. Worth pinning down because the unique index cannot help here:
          NULLs are distinct, so nothing at the database layer would stop a second publication.

          What stops it is the other half of the design. The draft row is locked and then resolved
          in the same transaction, so that row can only ever be published once, and a token-less
          draft cannot be recognised as the same form as any other row by anything.
        */
        insertCompany("Red Hat", "red-hat");
        String adminAuth = insertUser("auth0|dp-admin-16", "DpAdmin16", "admin");
        await(companyRatings.captureDraft("red-hat", ratingBody(4.0)));
        long id = draftId();

        await(admin.publishCapturedDraft(adminAuth, id));
        ServiceException second = assertThrows(ServiceException.class,
            () -> awaitCause(admin.publishCapturedDraft(adminAuth, id)));

        assertEquals(409, second.getStatusCode());
        assertEquals(1L, count("SELECT count(*) AS c FROM company_reviews"));
        assertNull(one("SELECT draft_token FROM company_reviews").getUUID("draft_token"),
            "nothing is invented: a publication with no form token simply has none, and its "
            + "author's return has no token to match it with either");
    }

    @Test
    void capturingTheSameFormTwiceLeavesOneQueueItem() throws Exception {
        /*
          The form captures on every submit attempt now, including attempts that fail validation,
          which is how a partial answer survives at all. Somebody who presses submit, fixes a
          category, presses it again and then gives up is ONE person with ONE opinion, and the
          queue must say so.

          This is the manager queue's defect in a new place: "Cepe", "Cepep" and "Cégep de
          Saint-Laurent" were three rows describing one search, and that queue has not recovered
          from it.
        */
        insertCompany("Red Hat", "red-hat");
        String token = UUID.randomUUID().toString();
        JsonObject partial = ratingBody(4.0);
        partial.getJsonObject("ratings").remove("career_growth");

        await(companyRatings.captureDraft("red-hat", partial.put("draftToken", token)));
        await(companyRatings.captureDraft("red-hat", ratingBody(4.5).put("draftToken", token)));

        assertEquals(1L, count("SELECT count(*) AS c FROM captured_drafts"),
            "one form, one row, however many times they pressed submit");
        assertEquals(4.5, one("SELECT payload FROM captured_drafts")
                .getJsonObject("payload").getDouble("overallRating"),
            "and it holds the LATEST answers, not the first attempt");
    }

    @Test
    void aCaptureNeverOverwritesADecisionAnAdminAlreadyMade() throws Exception {
        /*
          REWRITTEN when V97 made the token unique, and the change is behavioural rather than
          cosmetic, so it is written down.

          It used to assert that a later capture sat BESIDE the published row as a second record.
          That is no longer possible, and should not be: one form session is one row, and a
          session that has already become a published rating is finished. A late capture under
          its token is now ignored outright rather than reopening or duplicating it.

          The guarantee in the name is unchanged and is in fact stronger: the admin's decision,
          and the record of what it became, survive untouched.
        */
        insertCompany("Red Hat", "red-hat");
        String adminAuth = insertUser("auth0|dp-cap-1", "DpCap1", "admin");
        String token = UUID.randomUUID().toString();
        await(companyRatings.captureDraft("red-hat", ratingBody(4.0).put("draftToken", token)));
        await(admin.publishCapturedDraft(adminAuth, draftId()));
        UUID published = one("SELECT resolved_id FROM captured_drafts").getUUID("resolved_id");

        await(companyRatings.captureDraft("red-hat", ratingBody(2.0).put("draftToken", token)));

        assertEquals(1L, count("SELECT count(*) AS c FROM captured_drafts"),
            "one session, one row - the late capture does not create a second");
        Row draft = one("SELECT resolution, state, resolved_id, payload FROM captured_drafts");
        assertEquals("published", draft.getString("resolution"), "the decision survives");
        assertEquals("resolved", draft.getString("state"));
        assertEquals(published, draft.getUUID("resolved_id"), "still pointing at what it became");
        assertEquals(4.0, draft.getJsonObject("payload").getDouble("overallRating"),
            "and holding what was actually published, not the late capture's answers");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Autosave: answers kept while the form is still being filled in
    // ══════════════════════════════════════════════════════════════════════════

    /** The form's own payload shape, with only the categories somebody has actually answered. */
    // ── out-of-order autosaves ──────────────────────────────────────────────
    //
    // The ordering guarantee itself is covered by anOutOfOrderAutosaveCannotOverwriteNewerAnswers
    // and by the increasing-revision loop above, both of which predate these. Verified by
    // deleting "WHERE captured_drafts.revision < EXCLUDED.revision" from autosave(), which fails
    // them. What was missing is below: equal revisions, and a late autosave meeting a resolved
    // draft.

    /**
     * EQUAL REVISIONS ARE REFUSED, which is what makes the beacon's duplicate harmless.
     *
     * <p>The in-flight fix can send the same payload twice: once by the aborted request and again
     * by the flush on unmount, both carrying the revision they were built with. The guard is
     * strictly {@code <}, so the second is a no-op rather than a second row or a rewrite, and that
     * is why re-sending is safe rather than merely tolerable.
     */
    @Test
    void anIdenticalResendIsANoOpRatherThanASecondRow() throws Exception {
        insertCompany("Halverson Logistics", "halverson");
        long company = companyId("halverson");
        UUID token = UUID.randomUUID();
        JsonObject body = partial(java.util.Map.of("communication", 4.0, "fairness", 4.0), 7);

        await(drafts.autosave("company_rating", company, null, null, token, body, 7));
        Row first = one("SELECT payload, revision, updated_at FROM captured_drafts");
        await(drafts.autosave("company_rating", company, null, null, token, body, 7));

        assertEquals(1L, count("SELECT count(*) AS c FROM captured_drafts"),
            "the resend created a second row");
        Row second = one("SELECT payload, revision, updated_at FROM captured_drafts");
        assertEquals(first.getOffsetDateTime("updated_at"), second.getOffsetDateTime("updated_at"),
            "the resend rewrote the row it should have left alone");
    }

    /**
     * A LATE AUTOSAVE MUST NOT REOPEN A DRAFT AN ADMINISTRATOR HAS ALREADY DECIDED.
     *
     * <p>The upsert's second condition, {@code resolution IS NULL}, and also untested. A draft
     * that was published or dismissed carries that decision and the record of what it became; an
     * autosave arriving afterwards would quietly put it back in the queue.
     */
    @Test
    void aLateAutosaveCannotReopenAResolvedDraft() throws Exception {
        insertCompany("Halverson Logistics", "halverson");
        long company = companyId("halverson");
        UUID token = UUID.randomUUID();
        await(drafts.autosave("company_rating", company, null, null, token,
            partial(java.util.Map.of("communication", 4.0), 1), 1));
        await(pool.query("""
                UPDATE captured_drafts
                   SET resolution = 'dismissed', state = 'abandoned'
                """).execute().mapEmpty());

        await(drafts.autosave("company_rating", company, null, null, token,
            partial(java.util.Map.of("communication", 1.0, "fairness", 1.0), 9), 9));

        Row row = one("SELECT payload, revision, state, resolution FROM captured_drafts");
        assertEquals("dismissed", row.getString("resolution"), "the decision was overwritten");
        assertEquals("abandoned", row.getString("state"), "the draft was put back in the queue");
        assertEquals(1L, row.getLong("revision"), "the late save was applied anyway");
    }

    private static JsonObject partial(java.util.Map<String, Double> answered, long revision) {
        JsonObject ratings = new JsonObject();
        answered.forEach(ratings::put);
        return new JsonObject().put("ratings", ratings).put("revision", revision);
    }

    @Test
    void anEmptyFormIsNotWorthARow() throws Exception {
        /*
          The floor. Without one the table fills with rows describing somebody who opened a page,
          and the aggregate statistics this data exists to feed would count them as participants.
        */
        insertCompany("Red Hat", "red-hat");
        String token = UUID.randomUUID().toString();

        await(companyRatings.autosaveDraft("red-hat",
            partial(java.util.Map.of(), 1).put("draftToken", token)));

        assertEquals(0L, count("SELECT count(*) AS c FROM captured_drafts"));
    }

    @Test
    void oneAnsweredCategoryIsKeptWithoutPressingSubmit() throws Exception {
        /*
          THE POINT OF THE WHOLE FEATURE. Four numbers from somebody who closed the tab are four
          real observations about an employer, and until autosave existed every one was lost.
        */
        insertCompany("Red Hat", "red-hat");
        String token = UUID.randomUUID().toString();

        await(companyRatings.autosaveDraft("red-hat",
            partial(java.util.Map.of("work_life_balance", 3.0), 100).put("draftToken", token)));

        Row draft = one("SELECT payload, state, revision FROM captured_drafts");
        assertEquals("active", draft.getString("state"), "being edited, so not in the queue yet");
        assertEquals(3.0, draft.getJsonObject("payload").getJsonObject("ratings")
                .getDouble("work_life_balance"));
    }

    @Test
    void unansweredCategoriesStayMissingRatherThanBecomingZero() throws Exception {
        /*
          A zero is a rating. An absent key is a question nobody answered. Conflating them would
          put invented bottom scores into the dataset and defame employers at scale.
        */
        insertCompany("Red Hat", "red-hat");
        await(companyRatings.autosaveDraft("red-hat",
            partial(java.util.Map.of("work_life_balance", 4.0), 100)
                .put("draftToken", UUID.randomUUID().toString())));

        JsonObject ratings = one("SELECT payload FROM captured_drafts")
            .getJsonObject("payload").getJsonObject("ratings");
        assertEquals(1, ratings.size(), "only what was answered");
        assertNull(ratings.getValue("career_growth"), "absent, not zero");
    }

    @Test
    void manyAutosavesLeaveExactlyOneDraft() throws Exception {
        /*
          Autosave fires every few seconds. Without an upsert on the token the queue fills with a
          row per pause in typing, and one person's single opinion is counted dozens of times in
          any statistic built on this table.
        */
        insertCompany("Red Hat", "red-hat");
        String token = UUID.randomUUID().toString();
        for (int i = 1; i <= 6; i++) {
            await(companyRatings.autosaveDraft("red-hat",
                partial(java.util.Map.of("work_life_balance", (double) i), i * 100L)
                    .put("draftToken", token)));
        }

        assertEquals(1L, count("SELECT count(*) AS c FROM captured_drafts"));
        assertEquals(6.0, one("SELECT payload FROM captured_drafts").getJsonObject("payload")
                .getJsonObject("ratings").getDouble("work_life_balance"),
            "and it holds the newest answer");
    }

    @Test
    void anOutOfOrderAutosaveCannotOverwriteNewerAnswers() throws Exception {
        /*
          Two tabs, or one request delayed in flight. Without the revision comparison the write
          that ARRIVED last would win, which is not the same as the newest answer winning, and the
          contributor would watch their earlier answers silently replace their later ones.
        */
        insertCompany("Red Hat", "red-hat");
        String token = UUID.randomUUID().toString();
        await(companyRatings.autosaveDraft("red-hat",
            partial(java.util.Map.of("work_life_balance", 5.0), 200).put("draftToken", token)));

        await(companyRatings.autosaveDraft("red-hat",
            partial(java.util.Map.of("work_life_balance", 1.0), 50).put("draftToken", token)));

        assertEquals(5.0, one("SELECT payload FROM captured_drafts").getJsonObject("payload")
                .getJsonObject("ratings").getDouble("work_life_balance"),
            "the stale save is ignored outright");
    }

    @Test
    void twoContributorsDoNotOverwriteEachOther() throws Exception {
        /*
          Deduplication is keyed on a random per-session token and NEVER on an address. Two people
          on one office network are two contributors.
        */
        insertCompany("Red Hat", "red-hat");
        await(companyRatings.autosaveDraft("red-hat",
            partial(java.util.Map.of("work_life_balance", 1.0), 100)
                .put("draftToken", UUID.randomUUID().toString())));
        await(companyRatings.autosaveDraft("red-hat",
            partial(java.util.Map.of("work_life_balance", 5.0), 100)
                .put("draftToken", UUID.randomUUID().toString())));

        assertEquals(2L, count("SELECT count(*) AS c FROM captured_drafts"));
    }

    @Test
    void aLateAutosaveCannotReopenADraftAnAdminResolved() throws Exception {
        /*
          A request already in flight when the admin pressed Publish, or a retry behind it. The
          row carries their decision and what it became; reopening it would silently undo that.
        */
        insertCompany("Red Hat", "red-hat");
        String adminAuth = insertUser("auth0|dp-auto-1", "DpAuto1", "admin");
        String token = UUID.randomUUID().toString();
        await(companyRatings.captureDraft("red-hat", ratingBody(4.0).put("draftToken", token)));
        await(admin.publishCapturedDraft(adminAuth, draftId()));

        await(companyRatings.autosaveDraft("red-hat",
            partial(java.util.Map.of("work_life_balance", 1.0), 999999).put("draftToken", token)));

        Row draft = one("SELECT resolution, state FROM captured_drafts");
        assertEquals("published", draft.getString("resolution"), "the decision survives");
        assertEquals("resolved", draft.getString("state"));
    }

    @Test
    void anActivelyEditedDraftStaysOutOfTheApprovalQueue() throws Exception {
        /*
          Do not flood the queue. A form being typed into is not an abandoned rating, and an admin
          watching items appear and mutate every few seconds cannot review anything.
        */
        insertCompany("Red Hat", "red-hat");
        String adminAuth = insertUser("auth0|dp-auto-2", "DpAuto2", "admin");
        await(companyRatings.autosaveDraft("red-hat",
            partial(java.util.Map.of("work_life_balance", 3.0), 100)
                .put("draftToken", UUID.randomUUID().toString())));

        JsonArray queue = await(admin.getCapturedDrafts(adminAuth, 50, 0)).getJsonArray("data");
        assertEquals(0, queue.size(), "active drafts are invisible to the queue");
    }

    @Test
    void goingQuietPutsADraftInTheQueue_andReturningTakesItOutAgain() throws Exception {
        /*
          Abandonment is an observation about silence, not a verdict, so it has to be reversible:
          somebody who comes back to an unfinished form must not still be sitting in the queue.
        */
        insertCompany("Red Hat", "red-hat");
        String adminAuth = insertUser("auth0|dp-auto-3", "DpAuto3", "admin");
        String token = UUID.randomUUID().toString();
        await(companyRatings.autosaveDraft("red-hat",
            partial(java.util.Map.of("work_life_balance", 3.0), 100).put("draftToken", token)));

        await(pool.query("UPDATE captured_drafts SET updated_at = now() - INTERVAL '45 minutes'")
            .execute().mapEmpty());
        await(drafts.markAbandoned(30));
        assertEquals(1, await(admin.getCapturedDrafts(adminAuth, 50, 0)).getJsonArray("data").size(),
            "gone quiet, so the admin may look at it");

        await(companyRatings.autosaveDraft("red-hat",
            partial(java.util.Map.of("work_life_balance", 4.0), 200).put("draftToken", token)));

        assertEquals(0, await(admin.getCapturedDrafts(adminAuth, 50, 0)).getJsonArray("data").size(),
            "they came back, so it leaves the queue again");
    }

    @Test
    void anIncompleteDraftNeverReachesAPublicAverage() throws Exception {
        /*
          The line this feature must never cross. Partial answers are an internal dataset; they
          are not a rating and cannot be made into one by an admin pressing a button.
        */
        insertCompany("Red Hat", "red-hat");
        String adminAuth = insertUser("auth0|dp-auto-4", "DpAuto4", "admin");
        await(companyRatings.autosaveDraft("red-hat",
            partial(java.util.Map.of("work_life_balance", 2.0), 100)
                .put("draftToken", UUID.randomUUID().toString())));
        await(pool.query("UPDATE captured_drafts SET state = 'abandoned'").execute().mapEmpty());

        assertThrows(ServiceException.class,
            () -> awaitCause(admin.publishCapturedDraft(adminAuth, draftId())));
        assertEquals(0L, count("SELECT count(*) AS c FROM company_reviews"));
        assertEquals(0L, count("SELECT coalesce(sum(workplace_count),0) AS c FROM company_stats_live"),
            "and nothing of it reaches the public read model");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The author coming back, which is where a duplicate would come from
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void anAuthorWhoReturnsReplacesThePublicationRatherThanDoublingIt() throws Exception {
        /*
          THE ASSERTION THIS WHOLE FEATURE TURNS ON.

          Somebody fills in the rating form, is sent to sign in, and does not come back that day.
          An admin publishes it. The next morning they DO come back, sign in, and submit.

          Two writers, one opinion. If both land, the company carries the same rating twice, one
          anonymous and one signed, and nothing can tell they are the same: the author sees only
          their own, so the duplicate is invisible from every side.
        */
        insertCompany("Red Hat", "red-hat");
        String adminAuth  = insertUser("auth0|dp-admin-8", "DpAdmin8", "admin");
        String authorAuth = insertUser("auth0|dp-author-1", "DpAuthor1", "user");
        String token = UUID.randomUUID().toString();

        await(companyRatings.captureDraft("red-hat", ratingBody(4.0).put("draftToken", token)));
        await(admin.publishCapturedDraft(adminAuth, draftId()));
        assertEquals(1L, count("SELECT count(*) AS c FROM company_reviews"));

        await(companyRatings.submit(authorAuth, "red-hat", ratingBody(3.0).put("draftToken", token)));

        assertEquals(1L, count("SELECT count(*) AS c FROM company_reviews"),
            "one person, one opinion, one row - whichever order the two writes happened in");
        Row surviving = one("SELECT user_id, overall_rating, draft_token FROM company_reviews");
        assertNotNull(surviving.getUUID("user_id"),
            "theirs is the one that survives: it is the same opinion with an account behind it");
        assertEquals(3.0, surviving.getDouble("overall_rating"),
            "and the answers they actually submitted, not the ones the draft held");
        assertNull(surviving.getUUID("draft_token"),
            "a signed-in rating must not hold the token, or it blocks the next publication of "
            + "that form and makes itself a target for the deletion above");
    }

    @Test
    void aPublishedDraftKeepsItsTrailWhenItsAuthorReturns() throws Exception {
        /*
          The old clear() deleted every draft row holding the token, which was harmless while the
          only outcome was "an admin read it". Once a draft can be PUBLISHED, that row is the only
          record of where an anonymous rating on the page came from and who decided to put it
          there, and the author coming back would have destroyed exactly that.
        */
        insertCompany("Red Hat", "red-hat");
        String adminAuth  = insertUser("auth0|dp-admin-9", "DpAdmin9", "admin");
        String authorAuth = insertUser("auth0|dp-author-2", "DpAuthor2", "user");
        String token = UUID.randomUUID().toString();

        await(companyRatings.captureDraft("red-hat", ratingBody(4.0).put("draftToken", token)));
        await(admin.publishCapturedDraft(adminAuth, draftId()));
        await(companyRatings.submit(authorAuth, "red-hat", ratingBody(3.0).put("draftToken", token)));

        Row draft = one("SELECT resolution, resolved_id, resolved_by FROM captured_drafts");
        assertEquals("published", draft.getString("resolution"),
            "it was published, and that remains true");
        assertNotNull(draft.getUUID("resolved_by"), "and the admin who decided is still named");
        UUID surviving = one("SELECT id FROM company_reviews").getUUID("id");
        assertEquals(surviving, draft.getUUID("resolved_id"),
            "pointed at the rating that now represents it, rather than at a row that is gone");
    }

    @Test
    void anUntouchedDraftIsStillRemovedWhenItsAuthorFinishesTheForm() throws Exception {
        /*
          Unchanged behaviour, asserted because the change above is one condition away from it. A
          draft nobody has acted on is noise the moment its author completes the form, and leaving
          it in the queue means an admin reads work that was finished a minute later.
        */
        insertCompany("Red Hat", "red-hat");
        String authorAuth = insertUser("auth0|dp-author-3", "DpAuthor3", "user");
        String token = UUID.randomUUID().toString();

        await(companyRatings.captureDraft("red-hat", ratingBody(4.0).put("draftToken", token)));
        await(companyRatings.submit(authorAuth, "red-hat", ratingBody(4.0).put("draftToken", token)));

        assertEquals(0L, count("SELECT count(*) AS c FROM captured_drafts"));
    }

    @Test
    void aModeratedPublicationIsNotRepublishedByItsAuthorReturning() throws Exception {
        /*
          An admin publishes a draft, then takes the rating down. Its author then comes back and
          submits. The deletion of the anonymous row is deliberately scoped to LIVE rows, so the
          moderated one stays deleted and keeps its token; their own submission is a separate row
          and is theirs to make.

          The failure this guards is the opposite: a delete that ignored deleted_at would erase the
          moderator's decision, and the restore sweep would then have nothing to leave alone.
        */
        insertCompany("Red Hat", "red-hat");
        String adminAuth  = insertUser("auth0|dp-admin-10", "DpAdmin10", "admin");
        String authorAuth = insertUser("auth0|dp-author-4", "DpAuthor4", "user");
        String token = UUID.randomUUID().toString();

        await(companyRatings.captureDraft("red-hat", ratingBody(4.0).put("draftToken", token)));
        await(admin.publishCapturedDraft(adminAuth, draftId()));
        await(pool.query("UPDATE company_reviews SET deleted_at = now(), deleted_reason = 'junk'")
            .execute().mapEmpty());

        await(companyRatings.submit(authorAuth, "red-hat", ratingBody(3.0).put("draftToken", token)));

        assertEquals(1L, count("SELECT count(*) AS c FROM company_reviews "
                             + "WHERE deleted_at IS NOT NULL AND deleted_reason = 'junk'"),
            "a moderator's decision is not undone by the author coming back");
        assertEquals(1L, count("SELECT count(*) AS c FROM company_reviews WHERE deleted_at IS NULL"),
            "and their own rating is still theirs to write");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The queue itself
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void theQueueSaysWhichDraftsCanBePublishedAndWhyNot() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String adminAuth = insertUser("auth0|dp-admin-11", "DpAdmin11", "admin");
        JsonObject partial = ratingBody(4.0);
        partial.getJsonObject("ratings").remove("flexibility");
        await(companyRatings.captureDraft("red-hat", partial));

        JsonArray queue = await(admin.getCapturedDrafts(adminAuth, 50, 0)).getJsonArray("data");

        JsonObject only = queue.getJsonObject(0);
        assertFalse(only.getBoolean("publishable"),
            "the panel must not offer a button that then fails");
        assertNotNull(only.getString("blockedReason"),
            "and an admin should be able to see which answer is missing without reading the payload");
    }

    @Test
    void dismissingRecordsThatSomebodyDecidedNotToPublish() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String adminAuth = insertUser("auth0|dp-admin-12", "DpAdmin12", "admin");
        await(companyRatings.captureDraft("red-hat", ratingBody(4.0)));

        await(admin.markDraftReviewed(adminAuth, draftId()));

        Row draft = one("SELECT resolution, resolved_by FROM captured_drafts");
        assertEquals("dismissed", draft.getString("resolution"));
        assertNotNull(draft.getUUID("resolved_by"),
            "declining to publish a complete rating is a judgement, and the trail should say whose");
        assertEquals(0L, count("SELECT count(*) AS c FROM company_reviews"));
    }

    @Test
    void onlyAnAdminCanPublish() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String ordinary = insertUser("auth0|dp-user-1", "DpUser1", "user");
        await(companyRatings.captureDraft("red-hat", ratingBody(4.0)));

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitCause(admin.publishCapturedDraft(ordinary, draftId())));
        assertEquals(403, refused.getStatusCode());
        assertEquals(0L, count("SELECT count(*) AS c FROM company_reviews"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Interview experiences
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void aCompleteInterviewDraftBecomesARealExperienceWithItsRounds() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String adminAuth = insertUser("auth0|dp-admin-13", "DpAdmin13", "admin");
        String token = UUID.randomUUID().toString();
        await(interviews.captureDraft("red-hat", new JsonObject()
            .put("overallRating", 4.0)
            .put("outcome", "offer")
            .put("interviewYear", 2024)
            .put("draftToken", token)
            .put("rounds", new JsonArray().add("recruiter_screen").add("technical"))));

        await(admin.publishCapturedDraft(adminAuth, draftId()));

        Row review = one("SELECT user_id, overall_rating, rounds, draft_token FROM interview_reviews");
        assertNull(review.getUUID("user_id"));
        assertEquals(4.0, review.getBigDecimal("overall_rating").doubleValue(), 0.001);
        assertEquals(2, review.getInteger("rounds"),
            "the rounds are written in the same transaction, so a review cannot claim a process "
            + "shape that no rounds describe");
        assertEquals(token, review.getUUID("draft_token").toString(),
            "identified by the form it came from, the same way a workplace rating is");
    }

    @Test
    void anInterviewDraftWithNoOutcomeIsRefused() throws Exception {
        insertCompany("Red Hat", "red-hat");
        String adminAuth = insertUser("auth0|dp-admin-14", "DpAdmin14", "admin");
        // The public form requires it, so the admin path requires it.
        await(interviews.captureDraft("red-hat", new JsonObject()
            .put("overallRating", 4.0).put("interviewYear", 2024)));

        assertThrows(ServiceException.class,
            () -> awaitCause(admin.publishCapturedDraft(adminAuth, draftId())));
        assertEquals(0L, count("SELECT count(*) AS c FROM interview_reviews"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Withdrawing a published rating, which is the second half of the ask
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void aWithdrawnInterviewExperienceComesBackAnonymousAfterThreeDays() throws Exception {
        /*
          REGRESSION. InterviewRepository.restoreExpiredDeletions was written, documented and never
          called by anything: the sweep restored manager ratings and workplace ratings and simply
          did not know about interviews. So for every release since V52 a withdrawn interview
          experience was gone permanently while the other two forms both came back after three
          days, and the three are meant to behave identically on deletion.

          Verified by grep as much as by this test: before the fix the method had no callers at
          all, which no integration test could have observed, because the defect was in the wiring
          rather than in the query.
        */
        insertCompany("Red Hat", "red-hat");
        String authorAuth = insertUser("auth0|dp-int-1", "DpInt1", "user");
        await(interviews.createReview(authorAuth, "red-hat", new JsonObject()
            .put("overallRating", 4.0).put("outcome", "offer").put("interviewYear", 2024)));
        UUID reviewId = one("SELECT id FROM interview_reviews").getUUID("id");

        await(interviews.deleteReview(authorAuth, reviewId.toString()));
        assertEquals(0L, count("SELECT count(*) AS c FROM interview_reviews WHERE deleted_at IS NULL"),
            "withdrawn means gone from the page, immediately");

        await(pool.query("UPDATE interview_reviews SET deleted_at = now() - INTERVAL '4 days'")
            .execute().mapEmpty());
        await(sweep.runOnce());

        Row restored = one("SELECT deleted_at, user_id FROM interview_reviews");
        assertNull(restored.getOffsetDateTime("deleted_at"),
            "three days on, the experience comes back: it is still true, and the corpus is still "
            + "better for it");
        assertNull(restored.getUUID("user_id"),
            "and anonymous - the link to the person was dropped when they withdrew it");
    }

    @Test
    void aModeratedInterviewExperienceIsNeverRestored() throws Exception {
        /*
          The condition that makes the sweep above safe to run at all. A reason means a person
          decided; no reason means an author withdrew. Without this test the exemption is one
          forgotten clause away from a sweep that resurrects whatever an admin removed, live and
          counting towards the company's averages, with nothing on the page to show it was
          actioned.

          There is no moderator deletion path for interview experiences yet. deleted_reason is set
          directly here because that is precisely what such a path would write, and the exemption
          has to be in place BEFORE one exists rather than after.
        */
        insertCompany("Red Hat", "red-hat");
        String authorAuth = insertUser("auth0|dp-int-2", "DpInt2", "user");
        await(interviews.createReview(authorAuth, "red-hat", new JsonObject()
            .put("overallRating", 1.0).put("outcome", "no_offer").put("interviewYear", 2024)));

        await(pool.query("UPDATE interview_reviews SET deleted_at = now() - INTERVAL '9 days', "
                       + "deleted_reason = 'abusive'").execute().mapEmpty());
        await(sweep.runOnce());

        assertNotNull(one("SELECT deleted_at FROM interview_reviews").getOffsetDateTime("deleted_at"),
            "a moderator's deletion is not a countdown");
    }

    @Test
    void aPublishedRatingCannotBeDeletedByAnyUser() throws Exception {
        /*
          Asked for explicitly: these must never really be deleted by a user. They cannot be, and
          not by a rule that could be edited away - softDelete matches on user_id, and an
          anonymous publication has none, so there is no account the delete endpoint could match
          it to. The only way one leaves the page is a moderator taking it down.
        */
        insertCompany("Red Hat", "red-hat");
        String adminAuth = insertUser("auth0|dp-admin-15", "DpAdmin15", "admin");
        String other     = insertUser("auth0|dp-user-2", "DpUser2", "user");
        await(companyRatings.captureDraft("red-hat", ratingBody(4.0)));
        await(admin.publishCapturedDraft(adminAuth, draftId()));
        UUID ratingId = one("SELECT id FROM company_reviews").getUUID("id");

        assertThrows(ServiceException.class,
            () -> awaitCause(companyRatings.delete(other, ratingId)));
        assertEquals(1L, count("SELECT count(*) AS c FROM company_reviews WHERE deleted_at IS NULL"));
    }

    @Test
    void aWithdrawnWorkplaceRatingStillComesBackAfterThreeDays() throws Exception {
        /*
          Unchanged, and asserted here beside the interview one because the ask was that all three
          forms behave the same way on deletion. This is the half that already worked.
        */
        insertCompany("Red Hat", "red-hat");
        String authorAuth = insertUser("auth0|dp-wp-1", "DpWp1", "user");
        await(companyRatings.submit(authorAuth, "red-hat", ratingBody(4.0)));
        UUID ratingId = one("SELECT id FROM company_reviews").getUUID("id");

        await(companyRatings.delete(authorAuth, ratingId));
        await(pool.query("UPDATE company_reviews SET deleted_at = now() - INTERVAL '4 days'")
            .execute().mapEmpty());
        await(sweep.runOnce());

        Row restored = one("SELECT deleted_at, user_id FROM company_reviews");
        assertNull(restored.getOffsetDateTime("deleted_at"));
        assertNull(restored.getUUID("user_id"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** A manager-rating body with all ten categories answered, as the form submits one. */
    private static JsonObject managerReviewBody(double overall) {
        JsonObject ratings = new JsonObject();
        for (String key : new String[] {
                "Communication Style", "Perceived Approachability",
                "Perceived Clarity of Expectations", "Feedback Style", "Perceived Supportiveness",
                "Decision Making Style", "Organization and Planning Style", "Delegation Style",
                "Perceived Professional Demeanor", "Overall Working Experience" }) {
            ratings.put(key, overall);
        }
        return new JsonObject()
            .put("overallRating", overall)
            .put("ratings", ratings)
            .put("managerCompany", "Red Hat")
            .put("managerTitle", "Engineering Manager")
            .put("workedFrom", "2022-01");
    }

    private long insertManager(String name, String company) throws Exception {
        await(pool.preparedQuery(
                // status is NOT NULL on managers; 'active' is what the add form sends.
                "INSERT INTO managers (name, company, title, approval_status, slug, image, status) "
              + "VALUES ($1, $2, 'Engineering Manager', 'approved', $3, 'D', 'active')")
            .execute(io.vertx.sqlclient.Tuple.of(name, company,
                     name.toLowerCase().replace(' ', '-'))).mapEmpty());
        return one("SELECT id FROM managers WHERE name = '" + name + "'").getLong("id");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ISSUE 33: a capture token resolves ONE subject's publication, not any
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void aCaptureTokenCannotRemoveAPublicationForADifferentCompany() throws Exception {
        /*
          deleteAnonymousByToken removed the anonymous publication keyed on the TOKEN ALONE. Its
          two guards were user_id IS NULL, so a real person's rating could not be destroyed, and
          deleted_at IS NULL, so a moderator's removal stayed removed. Neither said anything about
          WHICH COMPANY was being rated, and the write is a hard DELETE.

          So a token arriving on a submission for a different company deleted the wrong
          publication. Client-minted UUIDs make an accidental collision negligible, but nothing
          prevented a reused one, and issue 32 had already shown this form's token handling was not
          what its comments claimed.

          Red Hat's published anonymous rating has nothing to do with somebody rating Canonical.
        */
        insertCompany("Red Hat", "red-hat");
        insertCompany("Canonical", "canonical");
        String adminAuth  = insertUser("auth0|dp-admin-33", "DpAdmin33", "admin");
        String authorAuth = insertUser("auth0|dp-author-33", "DpAuthor33", "user");
        String token = UUID.randomUUID().toString();

        await(companyRatings.captureDraft("red-hat", ratingBody(4.0).put("draftToken", token)));
        await(admin.publishCapturedDraft(adminAuth, draftId()));
        UUID published = one("SELECT id FROM company_reviews").getUUID("id");

        await(companyRatings.submit(authorAuth, "canonical",
            ratingBody(3.0).put("draftToken", token)));

        assertEquals(1L,
            count("SELECT count(*) AS c FROM company_reviews WHERE id = '" + published + "'"),
            "a rating of Canonical must not delete Red Hat's publication");
        assertEquals(2L, count("SELECT count(*) AS c FROM company_reviews"),
            "both stand: the published anonymous one, and the author's own for another company");
    }

    @Test
    void aCaptureTokenCannotRemoveAnInterviewPublicationForADifferentCompany() throws Exception {
        /*
          The same defect, in InterviewRepository, which carries the same statement shape. Written
          separately because one fix that happens to cover both is still two code paths, and the
          one nobody tested is the one that regresses.
        */
        insertCompany("Red Hat", "red-hat");
        insertCompany("Canonical", "canonical");
        String adminAuth  = insertUser("auth0|dp-admin-34", "DpAdmin34", "admin");
        String authorAuth = insertUser("auth0|dp-author-34", "DpAuthor34", "user");
        String token = UUID.randomUUID().toString();

        await(interviews.captureDraft("red-hat", new JsonObject()
            .put("overallRating", 4.0).put("outcome", "offer").put("interviewYear", 2024)
            .put("draftToken", token)
            .put("rounds", new JsonArray().add("recruiter_screen"))));
        await(admin.publishCapturedDraft(adminAuth, draftId()));
        UUID published = one("SELECT id FROM interview_reviews").getUUID("id");

        await(interviews.createReview(authorAuth, "canonical", new JsonObject()
            .put("overallRating", 3.0).put("outcome", "no_offer").put("interviewYear", 2024)
            .put("draftToken", token)
            .put("rounds", new JsonArray().add("technical"))));

        assertEquals(1L,
            count("SELECT count(*) AS c FROM interview_reviews WHERE id = '" + published + "'"),
            "an interview at Canonical must not delete Red Hat's publication");
    }

    private static JsonObject ratingBody(double overall) {
        JsonObject ratings = new JsonObject();
        for (String c : CompanyReviewRepository.CATEGORIES) ratings.put(c, overall);
        return new JsonObject()
            .put("overallRating", overall)
            .put("ratings", ratings)
            .put("workedFrom", "2021-04")
            .putNull("workedUntil");
    }

    private long draftId() throws Exception {
        return await(pool.query("SELECT id FROM captured_drafts WHERE reviewed_at IS NULL "
                              + "ORDER BY created_at, id LIMIT 1")
            .execute().map(rs -> rs.iterator().next().getLong("id")));
    }

    private Row one(String sql) throws Exception {
        return await(pool.query(sql).execute().map(rs -> rs.iterator().next()));
    }

    private long count(String sql) throws Exception {
        return await(pool.query(sql).execute().map(rs -> rs.iterator().next().getLong("c")));
    }

    private void insertCompany(String name, String slug) throws Exception {
        await(pool.preparedQuery("INSERT INTO companies(name, slug, status) VALUES ($1,$2,'approved')")
            .execute(Tuple.of(name, slug)).mapEmpty());
    }

    private long companyId(String slug) throws Exception {
        return await(pool.preparedQuery("SELECT id FROM companies WHERE slug = $1")
            .execute(Tuple.of(slug)).map(rs -> rs.iterator().next().getLong("id")));
    }

    private String insertUser(String auth0Id, String username, String role) throws Exception {
        await(pool.preparedQuery("INSERT INTO users(auth0_id,email,username,role) VALUES ($1,$2,$3,$4)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, role)).mapEmpty());
        return auth0Id;
    }

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    /** Unwraps the ExecutionException so a test can assert on the ServiceException itself. */
    private static <T> T awaitCause(Future<T> f) throws Throwable {
        try {
            return await(f);
        } catch (ExecutionException e) {
            throw e.getCause();
        }
    }
}
