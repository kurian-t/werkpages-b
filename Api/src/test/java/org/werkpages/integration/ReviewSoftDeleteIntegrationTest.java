package org.werkpages.integration;

import io.vertx.core.Future;
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
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.ManagerService;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ReviewSoftDeleteIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static ManagerService service;
    static UserRepository userRepo;
    static ReviewRepository reviewRepo;
    static ManagerRepository managerRepo;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load()
            .migrate();

        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        pool = PgPool.pool(connectOptions, new PoolOptions().setMaxSize(5));
        userRepo    = new UserRepository(pool);
        reviewRepo  = new ReviewRepository(pool);
        managerRepo = new ManagerRepository(pool);
        EditRepository   editRepo   = new EditRepository(pool);
        ReportRepository reportRepo = new ReportRepository(pool);
        service = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, users CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void delete_setsDeletedAtAndNullifiesUserId() throws Exception {
        long managerId = insertManager("Delete Target Mgr", "Corp", "Title");
        String auth0Id = insertUser("auth0|softdel-1", "SoftDelUser1");
        UUID userId    = findUserId(auth0Id);

        Row review = await(service.createReview(auth0Id, managerId,
            validBody("Corp", "Title", "2022-01", null), null));
        UUID reviewId = review.getUUID("id");

        await(reviewRepo.delete(reviewId, managerId));

        Row raw = await(pool
            .preparedQuery("SELECT deleted_at, user_id FROM reviews WHERE id = $1")
            .execute(Tuple.of(reviewId))
            .map(rs -> rs.iterator().next()));

        assertNotNull(raw.getOffsetDateTime("deleted_at"), "deleted_at must be set after soft delete");
        assertNull(raw.getUUID("user_id"), "user_id must be nullified immediately on soft delete");
    }

    @Test
    void delete_hidesReviewFromFindByManager() throws Exception {
        long managerId = insertManager("Hidden Review Mgr", "Corp", "Title");
        String auth0Id = insertUser("auth0|softdel-2", "SoftDelUser2");

        Row review = await(service.createReview(auth0Id, managerId,
            validBody("Corp", "Title", "2022-01", null), null));
        UUID reviewId = review.getUUID("id");

        long countBefore = await(reviewRepo.countByManager(managerId, null));
        assertEquals(1L, countBefore, "review must be visible before deletion");

        await(reviewRepo.delete(reviewId, managerId));

        long countAfter = await(reviewRepo.countByManager(managerId, null));
        assertEquals(0L, countAfter, "soft-deleted review must be excluded from count");

        long rowCount = await(reviewRepo.findByManager(managerId, 10, 0, "recent", null)
            .map(rs -> (long) rs.size()));
        assertEquals(0L, rowCount, "soft-deleted review must not appear in findByManager");
    }

    @Test
    void restoreExpiredDeletions_restoresOldSoftDeletes() throws Exception {
        long managerId = insertManager("Restore Expired Mgr", "Corp", "Title");
        String auth0Id = insertUser("auth0|softdel-3", "SoftDelUser3");

        Row review = await(service.createReview(auth0Id, managerId,
            validBody("Corp", "Title", "2022-01", null), null));
        UUID reviewId = review.getUUID("id");

        // Simulate deletion that happened 4 days ago (past the 3-day threshold)
        await(pool.preparedQuery("UPDATE reviews SET deleted_at = $1, user_id = NULL WHERE id = $2")
            .execute(Tuple.of(OffsetDateTime.now().minusDays(4), reviewId)));

        int restored = await(reviewRepo.restoreExpiredDeletions());
        assertEquals(1, restored, "one expired deletion must be restored");

        long countAfter = await(reviewRepo.countByManager(managerId, null));
        assertEquals(1L, countAfter, "restored review must be publicly visible again");

        Row raw = await(pool
            .preparedQuery("SELECT deleted_at, user_id FROM reviews WHERE id = $1")
            .execute(Tuple.of(reviewId))
            .map(rs -> rs.iterator().next()));
        assertNull(raw.getOffsetDateTime("deleted_at"), "deleted_at must be cleared after restore");
        assertNull(raw.getUUID("user_id"), "user_id stays null — review is anonymous after restore");
    }

    @Test
    void restoreExpiredDeletions_doesNotRestoreRecentDeletes() throws Exception {
        long managerId = insertManager("Dont Restore Mgr", "Corp", "Title");
        String auth0Id = insertUser("auth0|softdel-4", "SoftDelUser4");

        Row review = await(service.createReview(auth0Id, managerId,
            validBody("Corp", "Title", "2022-01", null), null));
        UUID reviewId = review.getUUID("id");

        // Simulate deletion that happened 1 day ago (within the 3-day window)
        await(pool.preparedQuery("UPDATE reviews SET deleted_at = $1, user_id = NULL WHERE id = $2")
            .execute(Tuple.of(OffsetDateTime.now().minusDays(1), reviewId)));

        int restored = await(reviewRepo.restoreExpiredDeletions());
        assertEquals(0, restored, "recent soft-delete must not be restored yet");

        long count = await(reviewRepo.countByManager(managerId, null));
        assertEquals(0L, count, "review within 3-day window must remain hidden");
    }

    // ── A replaced rating must stay replaced (issues 37 and 38) ───────────────

    /**
     * ISSUE 37. In production, before the fix, a rating its author replaced came back three days
     * later - live, anonymous and counted beside its own replacement.
     *
     * <p><b>How it happened.</b> {@code ManagerService.replaceReview} is what runs when somebody
     * who already rated a manager rates them again; {@code BossProfile.tsx} calls it and the form
     * says "Rating again replaces what you wrote before". It soft-deleted the original through the
     * same {@link ReviewRepository#delete} an author's own withdrawal uses, which sets no
     * {@code deleted_reason} because a withdrawal has none - a reason means an administrator
     * decided. {@link ReviewRepository#restoreExpiredDeletions} then restored every expired row
     * carrying no reason, and the replaced original matched exactly. By then {@code user_id} was
     * gone, so nothing downstream could tell the two rows came from one person, and nothing on
     * the page showed that one had been superseded.
     *
     * <p><b>Why the sweep's predicate was not the thing to change.</b> {@code deleted_reason IS
     * NULL} is the only thing separating an author's withdrawal from a moderator's removal, and
     * that distinction was itself a fix: without it the sweep resurrected junk an admin had
     * actioned, which {@code AdminReviewDeletionIntegrationTest} holds. A replaced original simply
     * is not a withdrawal, and nothing marked it as anything else. V111 adds
     * {@code replaced_by_author} and {@link ReviewRepository#deleteAsReplacedByAuthor} writes it.
     *
     * <p><b>Why the assertion matters.</b> Rating counts and averages are published figures, so
     * one person's opinion counting twice is a data-integrity failure. It is also a broken
     * promise, and the returning copy is anonymous, so it cannot be traced back to them to be
     * removed.
     *
     * <p>The replacement here keeps the manager's title and employer, which is the ordinary case:
     * the form prefills both from the profile. That is also the case that could not be inserted at
     * all before V111 - see the next test.
     *
     * <p>Only the clock is faked. The replacement runs through the real service path.
     */
    @Test
    void anAuthorsReplacedRatingMustNotComeBackThreeDaysLater() throws Exception {
        long managerId = insertManager("Replace And Wait Mgr", "Corp", "Title");
        String auth0Id = insertUser("auth0|softdel-replace", "ReplaceUser");

        Row original = await(service.createReview(auth0Id, managerId,
            validBody("Halverson Logistics", "Dispatch Lead", "2021-01", "2021-12"), null));
        UUID originalId = original.getUUID("id");

        await(service.replaceReview(auth0Id, managerId, originalId,
            validBody("Halverson Logistics", "Dispatch Lead", "2021-01", "2021-12"), null));

        assertEquals(1L, await(reviewRepo.countByManager(managerId, null)),
            "immediately after a replacement there is one rating, which is what the form promised");

        // Four days later, with nothing else changed.
        await(pool.preparedQuery("UPDATE reviews SET deleted_at = $1 WHERE id = $2")
            .execute(Tuple.of(OffsetDateTime.now().minusDays(4), originalId)));

        await(reviewRepo.restoreExpiredDeletions());

        Row raw = await(pool
            .preparedQuery("SELECT deleted_at, deleted_reason FROM reviews WHERE id = $1")
            .execute(Tuple.of(originalId))
            .map(rs -> rs.iterator().next()));

        assertNotNull(raw.getOffsetDateTime("deleted_at"),
            "the replaced original must still be deleted: it was superseded by the author's own "
            + "later rating, not withdrawn, so the restoration sweep has no business reviving it");
        assertEquals("replaced_by_author", raw.getString("deleted_reason"),
            "and the reason is what the sweep reads, so it has to be the specific one rather than "
            + "any value that merely keeps the row out");
        assertEquals(1L, await(reviewRepo.countByManager(managerId, null)),
            "exactly one rating may count toward this manager after a replacement. Two means one "
            + "person's opinion is published twice, and the anonymous copy cannot be traced back "
            + "to them to be removed");
    }

    /**
     * ISSUE 38, which the test above found by failing on it first.
     *
     * <p>{@code idx_reviews_author_manager_role_legacy} is unique on the author, manager, title
     * and employer of an indexed legacy rating, and had <b>no {@code deleted_at} predicate</b>, so
     * a soft-deleted row kept occupying its slot. Replacing a rating without changing the
     * manager's title or employer - the ordinary case - collided with the very row it was
     * replacing:
     *
     * <pre>duplicate key value violates unique constraint "idx_reviews_author_manager_role_legacy"</pre>
     *
     * <p>That was never live: V97 to V103 were undeployed, so production had no such index and got
     * the restoration defect above instead. It was a deployment blocker for V102.
     *
     * <p><b>Why the index does not simply exclude deleted rows.</b> A withdrawn rating returns
     * after three days. If it left the index while deleted, an identical rating could be inserted
     * meanwhile and the restoration sweep - one bulk UPDATE - would abort on the collision for
     * every row in its batch. The slot has to stay held for a row that is coming back. Only a row
     * that can NEVER come back may leave, which is exactly a row carrying a {@code deleted_reason},
     * so V111 narrows the index on that and not on {@code deleted_at}. The reservation that
     * remains is asserted in {@code ManagerMergeIntegrationTest
     * .aWithdrawnRatingAwaitingRestorationStillReservesItsDedupSlot}.
     *
     * <p>Which is why parts 3 and 4 are one change: the replacement frees its own slot by naming
     * itself {@code replaced_by_author}, in the same statement that deletes the original, inside
     * the same transaction as the insert.
     */
    @Test
    void anIdenticalReplacementSucceedsBecauseTheDeadOriginalLeavesTheDedupIndex() throws Exception {
        long managerId = insertManager("Identical Replace Mgr", "Corp", "Title");
        String auth0Id = insertUser("auth0|softdel-replace-same", "ReplaceSameUser");

        Row original = await(service.createReview(auth0Id, managerId,
            validBody("Halverson Logistics", "Dispatch Lead", "2021-01", "2021-12"), null));

        Row replacement = await(service.replaceReview(auth0Id, managerId, original.getUUID("id"),
            validBody("Halverson Logistics", "Dispatch Lead", "2022-01", "2022-12"), null));

        assertNotEquals(original.getUUID("id"), replacement.getUUID("id"),
            "a replacement is a new row, not an edit of the old one");
        assertEquals(1L, await(reviewRepo.countByManager(managerId, null)),
            "and exactly one of them is public");
    }

    /**
     * If the replacement cannot be written, the author keeps the rating they had.
     *
     * <p>The delete and the insert used to be two statements on the pool. The synchronous
     * pre-validation narrowed the window - that is what it was added for - but it did not close
     * it, because the insert runs further checks of its own. A failure between the two left
     * somebody with no rating at all and nothing to recover it from: the original was soft-deleted
     * and anonymised, so they could not even edit it back.
     *
     * <p>This body passes the synchronous pass and fails inside the insert: a one-character
     * employer is not blank, so {@code validateBodySync} accepts it, and the insert's own
     * minimum-length check rejects it. The original must be untouched afterwards - still live,
     * still theirs, still carrying its dates.
     */
    @Test
    void aFailedReplacementLeavesTheOriginalLiveAndUnchanged() throws Exception {
        long managerId = insertManager("Rollback Replace Mgr", "Corp", "Title");
        String auth0Id = insertUser("auth0|softdel-replace-fail", "ReplaceFailUser");
        UUID userId    = findUserId(auth0Id);

        Row original = await(service.createReview(auth0Id, managerId,
            validBody("Halverson Logistics", "Dispatch Lead", "2021-01", "2021-12"), null));
        UUID originalId = original.getUUID("id");

        assertThrows(Exception.class, () ->
            await(service.replaceReview(auth0Id, managerId, originalId,
                validBody("X", "Dispatch Lead", "2022-01", "2022-12"), null)),
            "a one-character employer passes the synchronous pass and fails inside the insert");

        Row raw = await(pool.preparedQuery(
                "SELECT deleted_at, deleted_reason, user_id, dates_hidden FROM reviews WHERE id = $1")
            .execute(Tuple.of(originalId)).map(rs -> rs.iterator().next()));

        assertNull(raw.getOffsetDateTime("deleted_at"),
            "the original must not be deleted when its replacement never committed");
        assertNull(raw.getString("deleted_reason"));
        assertEquals(userId, raw.getUUID("user_id"),
            "and it must still belong to them, or they cannot edit or withdraw it themselves");
        assertFalse(raw.getBoolean("dates_hidden"),
            "their period must still be published, because nothing was anonymised");
        assertEquals(1L, await(reviewRepo.countByManager(managerId, null)),
            "one rating, the one they already had");
    }

    /**
     * Two replacements of one rating at once supersede it once, and leave one rating standing.
     *
     * <p>{@link ReviewRepository#lockForReplacement} takes {@code FOR UPDATE} before anything is
     * written, so the second attempt waits rather than reading a row the first is about to
     * supersede. When the lock lifts it finds {@code deleted_at} already set,
     * {@link ReviewRepository#deleteAsReplacedByAuthor} matches no row, and it aborts with
     * nothing written.
     *
     * <p>Without the lock both would supersede the same original and insert a replacement each,
     * which is the duplicate this whole area exists to prevent - arrived at from the other
     * direction, and without a three-day wait to notice it.
     */
    @Test
    void twoSimultaneousReplacementsSupersedeTheOriginalExactlyOnce() throws Exception {
        long managerId = insertManager("Racing Replace Mgr", "Corp", "Title");
        String auth0Id = insertUser("auth0|softdel-replace-race", "ReplaceRaceUser");

        Row original = await(service.createReview(auth0Id, managerId,
            validBody("Halverson Logistics", "Dispatch Lead", "2021-01", "2021-12"), null));
        UUID originalId = original.getUUID("id");

        Future<Row> a = service.replaceReview(auth0Id, managerId, originalId,
            validBody("Halverson Logistics", "Dispatch Lead", "2022-01", "2022-12"), null);
        Future<Row> b = service.replaceReview(auth0Id, managerId, originalId,
            validBody("Halverson Logistics", "Dispatch Lead", "2023-01", "2023-12"), null);

        int succeeded = 0;
        for (Future<Row> f : java.util.List.of(a, b)) {
            try { await(f); succeeded++; } catch (Exception expected) { /* one of them loses */ }
        }

        assertEquals(1, succeeded, "exactly one replacement may commit");
        assertEquals(1L, await(reviewRepo.countByManager(managerId, null)),
            "and the manager carries one rating, not two");
        assertEquals("replaced_by_author", await(pool
            .preparedQuery("SELECT deleted_reason FROM reviews WHERE id = $1")
            .execute(Tuple.of(originalId))
            .map(rs -> rs.iterator().next().getString("deleted_reason"))),
            "the original is superseded once, by whichever attempt won");
    }

    // ── The daily allowance is not refundable ─────────────────────────────────

    @Test
    void deletingAReviewDoesNotRefundTheDailyAllowance() throws Exception {
        // The bypass. Soft delete nullifies user_id and sets deleted_at, so a deleted review is
        // invisible to a count that only looks at live rows - and the allowance resets. The
        // 30-day cooldown does not close it, because that is keyed per manager: review one
        // manager, delete, review the next, and the limit never binds.
        long managerId = insertManager("Refund Target", "Corp", "Title");
        String auth0Id = insertUser("auth0|refund-1", "RefundUser1");
        UUID userId    = findUserId(auth0Id);

        Row review = await(service.createReview(auth0Id, managerId,
            validBody("Corp", "Title", "2022-01", null), null));
        assertEquals(1L, await(reviewRepo.countSubmittedTodayByUser(userId)),
            "the submission counts");

        await(service.deleteReview(auth0Id, managerId, review.getUUID("id")));

        assertEquals(1L, await(reviewRepo.countSubmittedTodayByUser(userId)),
            "and it still counts after being deleted - otherwise the day's limit refunds itself");
    }

    @Test
    void aDeletionOnAnEarlierDayDoesNotCountAgainstToday() throws Exception {
        // The other half: the count is today's allowance, not a permanent tally. A deletion from
        // last week must not consume any of it.
        long managerId = insertManager("Old Deletion Mgr", "Corp", "Title");
        String auth0Id = insertUser("auth0|refund-2", "RefundUser2");
        UUID userId    = findUserId(auth0Id);

        await(pool.preparedQuery(
                "INSERT INTO review_deletions (user_id, manager_id, deleted_at) VALUES ($1, $2, now() - INTERVAL '7 days')")
            .execute(Tuple.of(userId, managerId)).mapEmpty());

        assertEquals(0L, await(reviewRepo.countSubmittedTodayByUser(userId)),
            "a deletion from another day is not part of today's count");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long insertManager(String name, String company, String title) throws Exception {
        return await(pool
            .preparedQuery("INSERT INTO managers(name,company,title,image,status,overall_rating,reviews_count,category_averages) " +
                           "VALUES ($1,$2,$3,'img','active',0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private String insertUser(String auth0Id, String username) throws Exception {
        await(pool
            .preparedQuery("INSERT INTO users(auth0_id,email,username,first_name,last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User")));
        return auth0Id;
    }

    private UUID findUserId(String auth0Id) throws Exception {
        return await(pool
            .preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private static io.vertx.core.json.JsonObject validBody(
            String company, String title, String workedFrom, String workedUntil) {
        io.vertx.core.json.JsonObject ratings = new io.vertx.core.json.JsonObject();
        for (String key : new String[]{
                "Communication Style", "Perceived Approachability",
                "Perceived Clarity of Expectations", "Feedback Style",
                "Perceived Supportiveness", "Decision Making Style",
                "Organization and Planning Style", "Delegation Style",
                "Perceived Professional Demeanor", "Overall Working Experience"}) {
            ratings.put(key, 4.0);
        }
        io.vertx.core.json.JsonObject body = new io.vertx.core.json.JsonObject()
            .put("overallRating",  4.0)
            .put("ratings",        ratings)
            .put("managerCompany", company)
            .put("managerTitle",   title)
            .put("workedFrom",     workedFrom)
            .put("author",         "AnonTester")
            .put("authorType",     "anonymous");
        if (workedUntil != null) body.put("workedUntil", workedUntil);
        return body;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
