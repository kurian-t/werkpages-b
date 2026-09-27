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
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.ConfidenceRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.MergeSuggestionsRepository;
import org.werkpages.repository.NotificationRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.AdminService;
import org.werkpages.service.ServiceException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A moderator removing a rating, and what that costs its author.
 *
 * <p>Admins could not delete a rating at all: the only delete path checked author ownership, so a
 * fake 5-star written in ten seconds to get past the contribution gate could be seen and not
 * removed.
 */
@Testcontainers
class AdminReviewDeletionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool             pool;
    static AdminService     service;
    static ReviewRepository reviewRepo;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load()
            .migrate();

        PgConnectOptions opts = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        pool       = PgPool.pool(opts, new PoolOptions().setMaxSize(5));
        reviewRepo = new ReviewRepository(pool);
        service = new AdminService(new UserRepository(pool), new ManagerRepository(pool),
                                   reviewRepo, new EditRepository(pool),
                                   new NotificationRepository(pool), new CompanyRepository(pool),
                                   new MergeSuggestionsRepository(pool), pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE notifications, manager_url_history, company_stats_live").execute());
        await(pool.query("TRUNCATE managers, users, companies CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Authorisation and input
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void nonAdmin_cannotDeleteSomebodyElsesRating() throws Exception {
        String userAuth = insertUser("auth0|ard-user", "ArdUser", "user");
        UUID reviewId   = insertReview(insertManager("A", "Acme", "Manager"), null, 5.0);

        ServiceException ex = assertServiceException(
            service.adminDeleteReview(userAuth, reviewId, "junk"));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void aReasonIsRequired() throws Exception {
        String admin  = insertUser("auth0|ard-a1", "ArdA1", "admin");
        UUID reviewId = insertReview(insertManager("B", "Acme", "Manager"), null, 5.0);

        assertEquals(400, assertServiceException(
            service.adminDeleteReview(admin, reviewId, null)).getStatusCode());
        assertEquals(400, assertServiceException(
            service.adminDeleteReview(admin, reviewId, "because-i-said-so")).getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The penalty, and who does NOT get one
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void junk_debitsTheAuthorsConfidence() throws Exception {
        /*
          The loop this whole feature exists to close. Removing the junk cleans the average; the
          debit is what stops the same account doing it again tomorrow, because the next
          submission from an account below WATCH_BELOW is held rather than published.
        */
        String admin  = insertUser("auth0|ard-a2", "ArdA2", "admin");
        String author = insertUser("auth0|ard-author", "ArdAuthor", "user");
        UUID authorId = findUserId(author);
        long managerId = insertManager("C", "Acme", "Manager");
        UUID reviewId  = insertReview(managerId, authorId, 5.0);

        int before = confidenceOf(authorId);
        await(service.adminDeleteReview(admin, reviewId, "junk"));

        assertEquals(before + ConfidenceRepository.REVIEW_DELETED_JUNK_DELTA, confidenceOf(authorId),
            "deleting junk must cost its author exactly what a rejected junk manager costs");
    }

    @Test
    void duplicateAndCorrection_costTheAuthorNothing() throws Exception {
        /*
          Not every deletion is the author's fault. A duplicate, or a data correction after a
          manager merge, is our problem and not theirs - and a score somebody cannot see or appeal
          must never move for something they did not do.
        */
        String admin = insertUser("auth0|ard-a3", "ArdA3", "admin");

        for (String reason : new String[] { "duplicate", "correction", "other" }) {
            String author  = insertUser("auth0|ard-" + reason, "Ard" + reason, "user");
            UUID authorId  = findUserId(author);
            UUID reviewId  = insertReview(insertManager("M" + reason, "Acme", "Manager"), authorId, 4.0);

            int before = confidenceOf(authorId);
            await(service.adminDeleteReview(admin, reviewId, reason));
            assertEquals(before, confidenceOf(authorId), reason + " must not debit the author");
        }
    }

    @Test
    void deletingTwice_doesNotDebitTwice() throws Exception {
        /*
          A double-clicked button, or a retried request. Debiting 20 twice for one piece of junk
          silently destroys an account's standing.
        */
        String admin   = insertUser("auth0|ard-a4", "ArdA4", "admin");
        String author  = insertUser("auth0|ard-twice", "ArdTwice", "user");
        UUID authorId  = findUserId(author);
        UUID reviewId  = insertReview(insertManager("D", "Acme", "Manager"), authorId, 5.0);

        int before = confidenceOf(authorId);
        await(service.adminDeleteReview(admin, reviewId, "junk"));

        // The rating is gone, so the second attempt has nothing to act on and says so.
        assertEquals(404, assertServiceException(
            service.adminDeleteReview(admin, reviewId, "junk")).getStatusCode());

        assertEquals(before + ConfidenceRepository.REVIEW_DELETED_JUNK_DELTA, confidenceOf(authorId),
            "one piece of junk, one debit");
    }

    @Test
    void anAnonymousRating_isRemovedWithoutDebitingAnybody() throws Exception {
        String admin  = insertUser("auth0|ard-a5", "ArdA5", "admin");
        UUID reviewId = insertReview(insertManager("E", "Acme", "Manager"), null, 5.0);

        await(service.adminDeleteReview(admin, reviewId, "junk"));
        assertNotNull(deletedAtOf(reviewId), "it still has to go");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Permanence — the trap that would have undone all of this
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void aModeratedDeletion_isNeverRestoredByTheDailySweep() throws Exception {
        /*
          reviews.deleted_at is a THREE DAY soft delete, and MaintenanceSweep un-deletes anything
          older than that every day - correct for an author who withdrew their rating and may
          change their mind.

          Applied to a moderation decision it silently undoes the entire feature: the junk comes
          back, live and counting toward the manager's average, three days later, while the
          confidence debit for it stands. Nothing on the page would show it had ever been actioned.
        */
        String admin  = insertUser("auth0|ard-a6", "ArdA6", "admin");
        UUID reviewId = insertReview(insertManager("F", "Acme", "Manager"), null, 5.0);
        await(service.adminDeleteReview(admin, reviewId, "junk"));

        await(pool.preparedQuery("UPDATE reviews SET deleted_at = now() - INTERVAL '4 days' WHERE id = $1")
            .execute(Tuple.of(reviewId)).mapEmpty());
        await(reviewRepo.restoreExpiredDeletions());

        assertNotNull(deletedAtOf(reviewId),
            "a rating a moderator removed must stay removed");
    }

    @Test
    void anAuthorsOwnWithdrawal_stillRestoresAfterThreeDays() throws Exception {
        /*
          The other half: the sweep still has to do its original job. Guarding moderated
          deletions by skipping the sweep entirely would have quietly ended the three-day
          grace period for everybody.
        */
        UUID reviewId = insertReview(insertManager("G", "Acme", "Manager"), null, 4.0);
        await(pool.preparedQuery(
                "UPDATE reviews SET deleted_at = now() - INTERVAL '4 days' WHERE id = $1")
            .execute(Tuple.of(reviewId)).mapEmpty());

        await(reviewRepo.restoreExpiredDeletions());

        assertNull(deletedAtOf(reviewId),
            "a withdrawal with no moderation reason still comes back anonymously");
    }

    @Test
    void deletionRecordsWhoDidItAndWhy() throws Exception {
        /*
          The audit trail. Without it, an account sitting at 50 six months from now is
          unexplainable - and an unexplainable penalty is one you cannot defend to the person
          carrying it.
        */
        String admin   = insertUser("auth0|ard-a7", "ArdA7", "admin");
        String author  = insertUser("auth0|ard-audit", "ArdAudit", "user");
        UUID authorId  = findUserId(author);
        UUID reviewId  = insertReview(insertManager("H", "Acme", "Manager"), authorId, 5.0);

        await(service.adminDeleteReview(admin, reviewId, "junk"));

        Row row = await(pool.preparedQuery(
                "SELECT deleted_reason, deleted_by, user_id FROM reviews WHERE id = $1")
            .execute(Tuple.of(reviewId)).map(rs -> rs.iterator().next()));

        assertEquals("junk", row.getString("deleted_reason"));
        assertEquals(findUserId(admin), row.getUUID("deleted_by"), "which admin decided");
        assertEquals(authorId, row.getUUID("user_id"),
            "moderation keeps the author link - an author's OWN deletion clears it, and doing "
          + "that here would destroy the only record of whose rating was penalised");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private int confidenceOf(UUID userId) throws Exception {
        return await(pool.preparedQuery("SELECT confidence FROM users WHERE id = $1")
            .execute(Tuple.of(userId))
            .map(rs -> rs.iterator().next().getInteger("confidence")));
    }

    private java.time.OffsetDateTime deletedAtOf(UUID reviewId) throws Exception {
        return await(pool.preparedQuery("SELECT deleted_at FROM reviews WHERE id = $1")
            .execute(Tuple.of(reviewId))
            .map(rs -> rs.iterator().next().getOffsetDateTime("deleted_at")));
    }

    private UUID insertReview(long managerId, UUID userId, double rating) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO reviews(manager_id, user_id, author, overall_rating, manager_company, "
              + "manager_title, worked_from, created_at, updated_at) "
              + "VALUES ($1,$2,'SomeAuthor',$3,'Acme','Manager','2024-01-01',now(),now()) RETURNING id")
            .execute(Tuple.of(managerId, userId, java.math.BigDecimal.valueOf(rating)))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private long insertManager(String name, String company, String title) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO managers(name,company,title,image,status,approval_status,"
              + "overall_rating,reviews_count,category_averages) "
              + "VALUES ($1,$2,$3,'img','active','approved',0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private String insertUser(String auth0Id, String username, String role) throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO users(auth0_id,email,username,first_name,last_name,role) "
              + "VALUES ($1,$2,$3,$4,$5,$6)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User", role)));
        return auth0Id;
    }

    private UUID findUserId(String auth0Id) throws Exception {
        return await(pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private static ServiceException assertServiceException(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ServiceException se) return se;
            throw new AssertionError("Expected ServiceException, got " + e.getCause());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("Expected a failure, but the call succeeded");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
