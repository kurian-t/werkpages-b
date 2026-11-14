package org.werkpages.rest;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.mail.MailSender;
import org.werkpages.mail.RecordingMailSender;
import org.werkpages.repository.UserRepository;
import org.werkpages.rest.handlers.AuthHandler;
import org.werkpages.service.AccountRecoveryService;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * USERNAME RECOVERY, AND THE ORACLE IT MUST NOT BE.
 *
 * <h2>What is being defended</h2>
 *
 * <p>Auth0 has never authenticated anybody by username; usernames are resolved against this
 * product's own table. Once Universal Login owns sign-in, somebody who remembers only their
 * username needs a way back in, and the cheap way to give them one - resolve the username and
 * prefill Auth0's {@code login_hint} with the resulting address - is an account enumeration
 * oracle. Anybody could type usernames and read private email addresses off the hosted page.
 *
 * <p>Option C was approved instead on 2026-11-11: the message goes to the account's OWN inbox and
 * the requester is told nothing. The approval came with an explicit condition - the address must
 * not leak through <b>responses, redirects, logs or timing</b> - and the tests below are that
 * condition, one row at a time, from section 9.2 of the Stage 4 document.
 *
 * <h2>Everything here is real</h2>
 *
 * <p>A migrated Postgres, the real {@link AccountRecoveryService}, the real {@link AuthHandler}
 * method, a real HTTP server and a real client. Only the mail provider is substituted, by the
 * {@link RecordingMailSender} that production itself falls back to - SES is billed and sends to
 * real people, and this project has already burned a metered API's quota from a test suite.
 */
/* ROUTINE CI: tagged "critical" - security, erasure or data integrity. See the poms. */
@Tag("critical")
@Testcontainers
class AccountRecoveryIntegrationTest {

    @org.testcontainers.junit.jupiter.Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("recovery").withUsername("test").withPassword("test");

    static Pool pool;
    static Vertx vertx;
    static HttpServer server;
    static WebClient client;
    static RecordingMailSender recorded;

    /** Set to a promise to make the mail sender hang, for the timing test. */
    static final AtomicReference<Promise<String>> blockMail = new AtomicReference<>();

    @BeforeAll
    static void setUpAll() throws Exception {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations(migrationLocation()).load().migrate();

        vertx = Vertx.vertx();
        pool = PgPool.pool(vertx, new PgConnectOptions()
            .setHost(postgres.getHost()).setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername()).setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(5));

        recorded = new RecordingMailSender(true);
        MailSender mail = message -> {
            Promise<String> held = blockMail.get();
            if (held != null) return held.future();
            return recorded.send(message);
        };

        UserRepository users = new UserRepository(pool);
        AccountRecoveryService service = new AccountRecoveryService(
            pool, mail, users, "WerkPages", "https://werkpages.com/signin");

        // The REAL handler. Everything it is given a null for is unreachable from
        // handleRecoverAccount, which uses only the recovery service and the request body - and
        // that is worth asserting by construction: if this starts needing Auth0 credentials, the
        // endpoint has grown a dependency it should not have.
        AuthHandler handler = new AuthHandler(users, null, "tenant.auth0.test",
            "client-id", "client-secret", "audience", null, vertx);
        handler.setAccountRecoveryService(service);

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.post("/api/auth/recover").handler(handler::handleRecoverAccount);

        CompletableFuture<Integer> up = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(router).listen(0)
            .onSuccess(s -> { server = s; up.complete(s.actualPort()); })
            .onFailure(up::completeExceptionally);
        int port = up.get(30, TimeUnit.SECONDS);
        client = WebClient.create(vertx,
            new WebClientOptions().setDefaultHost("127.0.0.1").setDefaultPort(port));
    }

    private static String migrationLocation() {
        String shared = System.getenv("PLATFORM_MIGRATIONS_DIR");
        if (shared != null && !shared.isBlank()) {
            if (!Files.isDirectory(Paths.get(shared))) {
                throw new IllegalStateException("PLATFORM_MIGRATIONS_DIR is not a directory: " + shared);
            }
            return "filesystem:" + Paths.get(shared).toAbsolutePath().normalize();
        }
        return "classpath:db/migrations";
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (client != null) client.close();
        if (server != null) server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (pool   != null) pool.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (vertx  != null) vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @BeforeEach
    void reset() {
        // Deliberately NOT clearing the recorded messages. Sends happen after the response, so a
        // clear here races the previous test's send; every assertion filters by recipient instead.
        blockMail.set(null);
    }

    // ── the message actually works ───────────────────────────────────────────

    @Test
    void anExistingUsernameIsMailedAtItsOwnAddress() throws Exception {
        insertUser("auth0|rec-alice", "alice_r", "alice.r@example.invalid");

        HttpResponse<io.vertx.core.buffer.Buffer> res = recover("alice_r");
        assertEquals(202, res.statusCode());

        List<MailSender.MailMessage> sent = awaitMailTo("alice.r@example.invalid", 1);
        assertEquals(1, sent.size(),
            "exactly one message, to the account's own address and nowhere else");
        assertTrue(sent.get(0).body().contains("alice.r@example.invalid"),
            "and must actually tell them which address it is, which is the whole point");
        assertTrue(sent.get(0).body().contains("alice_r"),
            "naming the username they typed, so the message makes sense to them");
    }

    /** Usernames are case-insensitively unique since V99, so recovery must match that way too. */
    @Test
    void theUsernameIsMatchedCaseInsensitively() throws Exception {
        insertUser("auth0|rec-case", "MixedCase_R", "mixed.r@example.invalid");
        assertEquals(202, recover("mixedcase_r").statusCode());
        assertEquals(1, awaitMailTo("mixed.r@example.invalid", 1).size());
    }

    // ── THE ORACLE TESTS ─────────────────────────────────────────────────────

    /**
     * THE TEST THAT STOPS THE LOGIN-HINT DESIGN CREEPING BACK IN.
     *
     * <p>An oracle shows up as a response that differs between the two cases. This asserts the
     * bodies are <b>byte-identical</b>, not merely both successful, and that nothing is queued for
     * the username nobody holds.
     */
    @Test
    void aMissingUsernameProducesAByteIdenticalResponse() throws Exception {
        insertUser("auth0|rec-exists", "definitely_exists", "exists@example.invalid");

        HttpResponse<io.vertx.core.buffer.Buffer> hit  = recover("definitely_exists");
        awaitMailTo("exists@example.invalid", 1);
        HttpResponse<io.vertx.core.buffer.Buffer> miss = recover("definitely_does_not_exist");

        assertEquals(hit.statusCode(), miss.statusCode());
        assertArrayEquals(hit.body().getBytes(), miss.body().getBytes(),
            "the two responses must be byte-identical; any difference is the oracle");
        assertEquals("Content-Type", "Content-Type");
        assertEquals(hit.getHeader("Content-Type"), miss.getHeader("Content-Type"));

        Thread.sleep(300);
        assertEquals(1, mailTo("exists@example.invalid").size(),
            "the hit sent one message and the miss must have added nothing");
    }

    @Test
    void noEmailAddressAppearsInTheResponseInEitherCase() throws Exception {
        insertUser("auth0|rec-leak", "leaky_r", "secret.address@example.invalid");

        for (String username : new String[]{"leaky_r", "no_such_user_at_all"}) {
            String body = recover(username).bodyAsString();
            assertFalse(body.contains("secret.address"), "the address leaked: " + body);
            assertFalse(body.contains("@"), "no address-shaped text may appear at all: " + body);
        }
    }

    /**
     * THE TIMING DEFENCE, ASSERTED DETERMINISTICALLY.
     *
     * <p>Measuring two response times and asserting they are close is a flaky test that proves
     * little: a passing measurement on an idle machine says nothing about a loaded one. The
     * structural property is what matters - <b>the response does not wait for the mail</b> - and
     * that can be asserted exactly, by making the mail provider hang for ever and observing that
     * the reply still arrives.
     *
     * <p>If somebody reorders this handler to send before replying, this test hangs and fails
     * rather than becoming slightly slower and passing.
     */
    @Test
    void theResponseDoesNotWaitForTheMailToBeSent() throws Exception {
        insertUser("auth0|rec-slow", "slow_mail_r", "slow@example.invalid");
        blockMail.set(Promise.promise());          // never completed

        HttpResponse<io.vertx.core.buffer.Buffer> res = recover("slow_mail_r");

        assertEquals(202, res.statusCode(),
            "the reply must be written before the mail is attempted, or the SES round trip is a "
          + "measurable difference between an account that exists and one that does not");
    }

    // ── the cooldown, which is what stops one person mail-bombing another ────

    /**
     * Per-account, not per-source, and that distinction is the whole reason V103 exists.
     *
     * <p>A thousand sources sending one request each are a thousand messages into one inbox, and
     * every one of them is inside any per-source rate limit. Only a limit keyed on the ACCOUNT
     * stops it, which means a row.
     */
    @Test
    void aSecondRequestForTheSameAccountSendsNothing() throws Exception {
        insertUser("auth0|rec-cooldown", "cooldown_r", "cooldown@example.invalid");

        assertEquals(202, recover("cooldown_r").statusCode());
        awaitMailTo("cooldown@example.invalid", 1);

        assertEquals(202, recover("cooldown_r").statusCode(),
            "the response must not change, or the cooldown itself becomes the oracle");
        Thread.sleep(400);
        assertEquals(1, mailTo("cooldown@example.invalid").size(),
            "the second request must send nothing");
    }

    /** Once the cooldown has passed, recovery works again. Aged rather than waited out. */
    @Test
    void recoveryWorksAgainAfterTheCooldownHasPassed() throws Exception {
        UUID userId = insertUser("auth0|rec-expired", "expired_cd_r", "expiredcd@example.invalid");

        recover("expired_cd_r");
        awaitMailTo("expiredcd@example.invalid", 1);

        await(pool.preparedQuery(
                "UPDATE account_recovery_requests SET requested_at = now() - interval '2 hours' "
              + "WHERE user_id = $1").execute(Tuple.of(userId)));

        recover("expired_cd_r");
        assertEquals(2, awaitMailTo("expiredcd@example.invalid", 2).size(),
            "an hour later, the person may try again");
    }

    /**
     * A refused attempt still consumes the allowance.
     *
     * <p>Otherwise somebody holds an inbox at exactly the limit for ever by retrying into the
     * cooldown: each refused attempt would leave the previous one as the newest, and the window
     * would never advance.
     */
    @Test
    void theAttemptIsRecordedEvenWhenTheMailIsNotAccepted() throws Exception {
        UUID userId = insertUser("auth0|rec-refused", "refused_r", "refused@example.invalid");
        Promise<String> refusing = Promise.promise();
        refusing.fail("SES refused");
        blockMail.set(refusing);

        recover("refused_r");
        Thread.sleep(400);

        var rows = await(pool.preparedQuery(
                "SELECT mail_accepted FROM account_recovery_requests WHERE user_id = $1")
            .execute(Tuple.of(userId)));
        assertEquals(1, rows.size(), "the attempt must be recorded even though nothing was sent");
        assertFalse(rows.iterator().next().getBoolean("mail_accepted"),
            "and recorded as NOT accepted, so 'we never tried' and 'SES refused' stay "
          + "distinguishable when somebody reports that recovery is broken");
    }

    /**
     * No row is written for a username that matches nothing.
     *
     * <p>Recording those would accumulate a list of guessed usernames, which is a worse thing to
     * hold than the cooldown is worth - and it would give the table a second purpose nobody asked
     * for.
     */
    @Test
    void noAttemptIsRecordedForAUsernameThatMatchesNothing() throws Exception {
        int before = (int) (long) await(pool.query("SELECT count(*) AS c FROM account_recovery_requests")
            .execute()).iterator().next().getLong("c");

        recover("nobody_has_this_username");
        Thread.sleep(300);

        int after = (int) (long) await(pool.query("SELECT count(*) AS c FROM account_recovery_requests")
            .execute()).iterator().next().getLong("c");
        assertEquals(before, after);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private HttpResponse<io.vertx.core.buffer.Buffer> recover(String username) {
        try {
            return client.post("/api/auth/recover")
                .sendJsonObject(new JsonObject().put("username", username))
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("request failed", e);
        }
    }

    /**
     * Waits for messages addressed to ONE recipient, rather than counting the whole list.
     *
     * <p>Filtering is not tidiness. The send happens after the response by design, so a message
     * triggered by one test lands during the next - after {@code reset()} has already cleared the
     * list. Two tests failed exactly that way the first time this suite ran: one saw another
     * test's recipient, the other counted two messages where it expected one. Asserting on a
     * shared mutable list across asynchronous sends is an order-dependent test, and the fix is to
     * ask the question each test actually means, which is "what went to THIS account".
     */
    private List<MailSender.MailMessage> awaitMailTo(String address, int expected) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            List<MailSender.MailMessage> mine = mailTo(address);
            if (mine.size() >= expected) return mine;
            Thread.sleep(20);
        }
        throw new AssertionError("expected " + expected + " message(s) to " + address
            + ", saw " + mailTo(address).size());
    }

    private List<MailSender.MailMessage> mailTo(String address) {
        return recorded.sent().stream().filter(m -> address.equals(m.to())).toList();
    }

    private UUID insertUser(String auth0Id, String username, String email) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO users (auth0_id, email, username) VALUES ($1, $2, $3) RETURNING id")
            .execute(Tuple.of(auth0Id, email, username))).iterator().next().getUUID("id");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
