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
    static InterviewService     interviews;
    static MaintenanceSweep     sweep;

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
        interviews     = new InterviewService(interviewRepo, companyRepo, userRepo);
        admin = new AdminService(userRepo, new ManagerRepository(pool), new ReviewRepository(pool),
                                 new EditRepository(pool), new NotificationRepository(pool),
                                 companyRepo, new MergeSuggestionsRepository(pool), pool);
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
                       + "companies, users CASCADE").execute().mapEmpty());
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
          The capture path inserts a fresh row every time somebody is bounced, so one form can hold
          several drafts sharing a token. Publishing one has to retire the rest, or they sit in the
          queue looking publishable and the next attempt is refused by a unique index rather than
          by anything an admin can act on.
        */
        insertCompany("Red Hat", "red-hat");
        String adminAuth = insertUser("auth0|dp-admin-7", "DpAdmin7", "admin");
        String token = UUID.randomUUID().toString();
        await(companyRatings.captureDraft("red-hat", ratingBody(4.0).put("draftToken", token)));
        await(companyRatings.captureDraft("red-hat", ratingBody(4.5).put("draftToken", token)));
        assertEquals(2L, count("SELECT count(*) AS c FROM captured_drafts"));

        await(admin.publishCapturedDraft(adminAuth, draftId()));

        assertEquals(1L, count("SELECT count(*) AS c FROM company_reviews"));
        assertEquals(0L, count("SELECT count(*) AS c FROM captured_drafts WHERE reviewed_at IS NULL"),
            "the duplicate capture must not be left in the queue as a second publishable copy");
        assertEquals(1L, count("SELECT count(*) AS c FROM captured_drafts WHERE resolution = 'superseded'"));
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
