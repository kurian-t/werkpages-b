package org.werkpages.integration;

import io.vertx.core.Future;
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
 * Rejecting a pending manager, and whether that costs the submitter anything.
 *
 * <p><b>The bug this was written for.</b> {@code rejectPendingManager} debited the submitter
 * {@value org.werkpages.repository.ConfidenceRepository#MANAGER_REJECTED_JUNK_DELTA} on
 * <em>every</em> rejection. It took a {@code reason} string, but used it only in the notification
 * email, so there was no way for a moderator to say WHY they were rejecting and no way to reject
 * without a penalty. The admin panel offered one button: Reject.
 *
 * <p>That meant a submission taken down because it duplicated a manager already in the directory,
 * or because an admin had corrected it by hand, cost its submitter exactly as much as typing
 * {@code iufsflk sfsfsf} into the add form. Over time it is the most active genuine contributors
 * who accumulate duplicates, so the accounts quietly pushed under {@code WATCH_BELOW} and then
 * {@code RESTRICTED_BELOW} were disproportionately the good ones, for doing nothing wrong.
 *
 * <p>The review side already got this right, and said so in its own documentation: the penalty is
 * applied "ONLY for the junk deletion reason. A duplicate, or an administrative correction, is
 * not the author's fault and must cost them nothing." The manager side contradicted the principle
 * stated one method away from it.
 *
 * <p>Why the assertions matter: confidence is never shown to the user and cannot be appealed by
 * them, so a debit they did not earn is invisible to the only person with an interest in
 * disputing it. That is precisely the kind of penalty that has to be deliberate.
 *
 * @see AdminReviewDeletionIntegrationTest the same decision for the other thing a person submits
 */
@Testcontainers
class AdminManagerRejectionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool         pool;
    static AdminService service;

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

        pool = PgPool.pool(opts, new PoolOptions().setMaxSize(5));
        service = new AdminService(new UserRepository(pool), new ManagerRepository(pool),
                                   new ReviewRepository(pool), new EditRepository(pool),
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
    // The penalty, and who does NOT get one
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void junk_debitsTheSubmittersConfidence() throws Exception {
        /*
          The half that already worked, kept honest. Removing the junk tidies the directory; the
          debit is what stops the same account doing it again tomorrow, because the next
          submission from an account below WATCH_BELOW is held rather than published.
        */
        String admin     = insertUser("auth0|amr-a1", "AmrA1", "admin");
        String submitter = insertUser("auth0|amr-junk", "AmrJunk", "user");
        UUID submitterId = findUserId(submitter);
        long managerId   = insertPendingManager("iufsflk sfsfsf", submitterId, null);

        int before = confidenceOf(submitterId);
        await(service.rejectPendingManager(admin, managerId, null, "junk"));

        awaitConfidence(submitterId, before + ConfidenceRepository.MANAGER_REJECTED_JUNK_DELTA,
            "gibberish typed into the add form must still cost the account that typed it");
    }

    @Test
    void duplicateCorrectionAndOther_costTheSubmitterNothing() throws Exception {
        /*
          THE REGRESSION. Before the fix every one of these debited 20, because the category was
          not consulted at all - a moderator had no way to express "this is not their fault".

          A duplicate is the common case and the damaging one: it is produced by people who
          contribute often, which is exactly the population a silent penalty must not select for.
        */
        String admin = insertUser("auth0|amr-a2", "AmrA2", "admin");

        for (String category : new String[] { "duplicate", "correction", "other" }) {
            String submitter = insertUser("auth0|amr-" + category, "Amr" + category, "user");
            UUID submitterId = findUserId(submitter);
            long managerId   = insertPendingManager("Real Person " + category, submitterId, null);

            int before = confidenceOf(submitterId);
            await(service.rejectPendingManager(admin, managerId, null, category));

            assertEquals(before, confidenceOf(submitterId),
                "rejecting as '" + category + "' must not touch the submitter's confidence");
            assertEquals(0, confidenceEventCount(submitterId),
                "and must not leave a ledger entry either - the score is a cache of the log");
        }
    }

    @Test
    void anAbsentCategory_costsTheSubmitterNothing() throws Exception {
        /*
          Deployment ordering. The backend ships before the frontend that learned to send a
          category, so for a window the only caller sends nothing. Defaulting that to a penalty
          is how the original bug behaved; defaulting it to no penalty means the worst a lagging
          deploy can do is under-punish, which is the right direction for a score nobody can see.
        */
        String admin     = insertUser("auth0|amr-a3", "AmrA3", "admin");
        String submitter = insertUser("auth0|amr-absent", "AmrAbsent", "user");
        UUID submitterId = findUserId(submitter);
        long managerId   = insertPendingManager("No Category Given", submitterId, null);

        int before = confidenceOf(submitterId);
        await(service.rejectPendingManager(admin, managerId, "some free text", null));

        assertEquals(before, confidenceOf(submitterId),
            "a penalty nobody deliberately chose must not be applied");
    }

    @Test
    void anUnrecognisedCategory_isRefused() throws Exception {
        /*
          Distinct from absent on purpose. Nothing at all is an old client; "junkk" is a bug or
          somebody probing, and silently treating it as no-penalty would hide both.
        */
        String admin     = insertUser("auth0|amr-a4", "AmrA4", "admin");
        String submitter = insertUser("auth0|amr-bogus", "AmrBogus", "user");
        long managerId   = insertPendingManager("Bogus Category", findUserId(submitter), null);

        ServiceException ex = assertServiceException(
            service.rejectPendingManager(admin, managerId, null, "junkk"));
        assertEquals(400, ex.getStatusCode());

        assertEquals("pending_approval", approvalStatusOf(managerId),
            "a refused call must not have rejected the manager on its way out");
    }

    @Test
    void aSearchCreatedManager_isNeverDebited_evenAsJunk() throws Exception {
        /*
          The pre-existing invariant, which the fix must not disturb. A ghost created by somebody
          typing into the /find search box is not a submission: they searched, and we chose to
          create a row. Rejecting it is silent - no notification - so a debit for it would be a
          penalty with no notice attached, for something the person never submitted.
        */
        String admin     = insertUser("auth0|amr-a5", "AmrA5", "admin");
        String searcher  = insertUser("auth0|amr-search", "AmrSearch", "user");
        UUID searcherId  = findUserId(searcher);
        long managerId   = insertPendingManager("Ghosty McGhost", searcherId, searcherId);

        int before = confidenceOf(searcherId);
        await(service.rejectPendingManager(admin, managerId, null, "junk"));

        assertEquals(before, confidenceOf(searcherId),
            "a search-created row costs nobody anything, whatever category is chosen");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // What the caller is told
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void theResponse_saysWhetherItPenalised() throws Exception {
        /*
          The admin panel tells the moderator what their click did, so it has to be told. Guessing
          from the category alone would be wrong for a search-created row, where junk is chosen
          and no debit happens.
        */
        String admin = insertUser("auth0|amr-a6", "AmrA6", "admin");

        String junkSub   = insertUser("auth0|amr-r1", "AmrR1", "user");
        long junkManager = insertPendingManager("Junk One", findUserId(junkSub), null);
        JsonObject junkResult = await(service.rejectPendingManager(admin, junkManager, null, "junk"));
        assertTrue(junkResult.getBoolean("confidencePenalty"), "junk penalised, and says so");

        String dupSub   = insertUser("auth0|amr-r2", "AmrR2", "user");
        long dupManager = insertPendingManager("Dup One", findUserId(dupSub), null);
        JsonObject dupResult = await(service.rejectPendingManager(admin, dupManager, null, "duplicate"));
        assertFalse(dupResult.getBoolean("confidencePenalty"), "duplicate did not, and says so");

        String ghostSub   = insertUser("auth0|amr-r3", "AmrR3", "user");
        UUID ghostSubId   = findUserId(ghostSub);
        long ghostManager = insertPendingManager("Ghost One", ghostSubId, ghostSubId);
        JsonObject ghostResult = await(service.rejectPendingManager(admin, ghostManager, null, "junk"));
        assertFalse(ghostResult.getBoolean("confidencePenalty"),
            "junk on a search-created row penalises nobody, and must not claim otherwise");
    }

    @Test
    void rejectingTwice_doesNotDebitTwice() throws Exception {
        /*
          A double-clicked button. The status guard in reject() means the second call finds no
          pending row and 404s, so it never reaches the debit - and the ledger's uniqueness
          constraint would stop it anyway. Both belts, because 40 for one piece of junk takes an
          account from normal to restricted in a single click.
        */
        String admin     = insertUser("auth0|amr-a7", "AmrA7", "admin");
        String submitter = insertUser("auth0|amr-twice", "AmrTwice", "user");
        UUID submitterId = findUserId(submitter);
        long managerId   = insertPendingManager("Twice Junk", submitterId, null);

        int before = confidenceOf(submitterId);
        await(service.rejectPendingManager(admin, managerId, null, "junk"));
        assertEquals(404, assertServiceException(
            service.rejectPendingManager(admin, managerId, null, "junk")).getStatusCode());

        awaitConfidence(submitterId, before + ConfidenceRepository.MANAGER_REJECTED_JUNK_DELTA,
            "one rejected manager, one debit");
        /*
          The score alone is not enough: polling stops the moment the first debit lands, so a
          second one arriving late would go unseen. The ledger is the truth the score caches, and
          exactly one row in it is what "not twice" actually means.
        */
        assertEquals(1, confidenceEventCount(submitterId),
            "the ledger must carry one debit, not two");
    }

    @Test
    void nonAdmin_cannotReject() throws Exception {
        String user      = insertUser("auth0|amr-u1", "AmrU1", "user");
        String submitter = insertUser("auth0|amr-u2", "AmrU2", "user");
        long managerId   = insertPendingManager("Not Yours", findUserId(submitter), null);

        assertEquals(403, assertServiceException(
            service.rejectPendingManager(user, managerId, null, "junk")).getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Waits for a fired-and-forgotten debit to land.
     *
     * <p>{@code rejectPendingManager} does NOT await {@code confidenceRepo.apply}: it fires the
     * debit, logs a failure to stderr, and returns. So the score is not readable the instant the
     * call resolves, and asserting on it directly is a race this test lost under full-suite load
     * while passing every time on an idle machine.
     *
     * <p>The review-side equivalent gets away with a direct read only by accident - it performs
     * two more round trips after firing its debit, which happens to leave enough slack. The
     * reject path returns immediately and has none.
     *
     * <p>Polling rather than sleeping, so a slow container costs patience instead of a false
     * failure, and a debit that never lands still fails inside the timeout.
     */
    private void awaitConfidence(UUID userId, int expected, String because) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        int seen = confidenceOf(userId);
        while (seen != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            seen = confidenceOf(userId);
        }
        assertEquals(expected, seen, because);
    }

    private int confidenceOf(UUID userId) throws Exception {
        return await(pool.preparedQuery("SELECT confidence FROM users WHERE id = $1")
            .execute(Tuple.of(userId))
            .map(rs -> rs.iterator().next().getInteger("confidence")));
    }

    private int confidenceEventCount(UUID userId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT count(*) AS n FROM user_confidence_events WHERE user_id = $1")
            .execute(Tuple.of(userId))
            .map(rs -> rs.iterator().next().getInteger("n")));
    }

    private String approvalStatusOf(long managerId) throws Exception {
        return await(pool.preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("approval_status")));
    }

    /** A pending submission. Pass {@code searchCreatedBy} to make it a /find ghost instead. */
    private long insertPendingManager(String name, UUID submittedBy, UUID searchCreatedBy)
            throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO managers(name,company,title,image,status,approval_status,"
              + "overall_rating,reviews_count,category_averages,submitted_by,"
              + "search_created_by_user_id) "
              + "VALUES ($1,'Acme','Manager','img','active','pending_approval',0,0,'{}',$2,$3) "
              + "RETURNING id")
            .execute(Tuple.of(name, submittedBy, searchCreatedBy))
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
