package org.werkpages.rest;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AUTHENTICATION OVER REAL HTTP, THROUGH THE HANDLER THE ROUTER ACTUALLY USES.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>{@code SessionRepositoryIntegrationTest} proves the session table against a real Postgres:
 * the two clocks, the revocation, the product scoping, the hashing. Every one of those assertions
 * calls the repository directly.
 *
 * <p>None of them could show that a <b>request</b> carrying a session cookie is authenticated,
 * that a request carrying a revoked one is refused, that the cron header still works, or that the
 * session is preferred over a token - which is the property that makes revocation mean anything.
 * Those are properties of {@link RequestAuthenticator}, and until it was extracted from
 * MainVerticle's boot method no test could construct it.
 *
 * <p>So this drives real HTTP requests through the real handler, mounted the way the router mounts
 * it, and asserts on what comes back.
 *
 * <h2>What is real and what is not</h2>
 *
 * <p>Real: the HTTP server, the client, the connection, the cookie header parsing, the handler, its
 * ordering, its failure codes, the {@code Set-Cookie} it writes.
 *
 * <p>Stubbed: the session repository and the JWT verifier, both in process. The database's own
 * guarantees are proven in the integration suite against Postgres and repeating them here would
 * test a stub. What is stubbed is programmed per test so the handler sees each answer the real one
 * can give, including a failure.
 *
 * <p>Nothing contacts Auth0 and nothing needs a database.
 */
class RequestAuthenticatorHttpTest {

    private static Vertx vertx;
    private static HttpServer server;
    private static WebClient client;
    private static int port;

    /** What the stub session repository will answer with, set per test. */
    private static final AtomicReference<Future<Optional<Row>>> touchAnswer = new AtomicReference<>();
    /** Every opaque id the handler asked about, so preference between credentials is observable. */
    private static final AtomicReference<List<String>> touched = new AtomicReference<>();
    /** Whether the stub JWT verifier accepts, and the subject it produces when it does. */
    private static final AtomicReference<String> jwtSubject = new AtomicReference<>();

    private static final String CRON_SECRET = "cron-secret-under-test";
    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeAll
    static void boot() throws Exception {
        vertx = Vertx.vertx();

        Router router = Router.router(vertx);
        /*
          The real router has OpenAPI's own failure handling behind it. Without an equivalent here
          ctx.fail(401) still produces a 401, but Vert.x logs "Unhandled exception in router" at
          SEVERE for every refusal - nine alarming lines in a passing run, which is how real
          failures get skimmed past.
        */
        router.route().failureHandler(ctx -> ctx.response()
            .setStatusCode(ctx.statusCode() > 0 ? ctx.statusCode() : 500).end());
        // Mounted exactly as the OpenAPI router mounts it: the security handler first, then the
        // operation. The echo handler reports what the authenticator decided, which is the handler's
        // entire contract - auth0Id on the context and nothing else.
        router.route("/secured")
            .handler(new RequestAuthenticator(CRON_SECRET, stubSessions(), stubJwt()))
            .handler(ctx -> ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("auth0Id", (String) ctx.get("auth0Id"))
                    .put("sessionUserId", ctx.get("sessionUserId") == null
                        ? null : String.valueOf((UUID) ctx.get("sessionUserId")))
                    .encode()));

        CompletableFuture<Integer> listening = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(router).listen(0)
            .onSuccess(s -> { server = s; listening.complete(s.actualPort()); })
            .onFailure(listening::completeExceptionally);
        port = listening.get(20, TimeUnit.SECONDS);
        client = WebClient.create(vertx, new WebClientOptions().setDefaultPort(port)
            .setDefaultHost("127.0.0.1"));
    }

    @AfterAll
    static void shutdown() throws Exception {
        if (client != null) client.close();
        if (server != null) server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (vertx  != null) vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @BeforeEach
    void reset() {
        touchAnswer.set(Future.succeededFuture(Optional.empty()));
        touched.set(new java.util.ArrayList<>());
        jwtSubject.set(null);
    }

    // ── The cron credential, which must survive all of this ──────────────────

    /**
     * The scheduled jobs hold no session and no token. If this breaks they stop silently, and the
     * first symptom is statistics that quietly stop being maintained.
     */
    @Test
    void theCronSecretStillAuthenticates() {
        HttpResponse<io.vertx.core.buffer.Buffer> res = get(req ->
            req.putHeader("X-Cron-Secret", CRON_SECRET));
        assertEquals(200, res.statusCode());
        assertEquals("cron", res.bodyAsJsonObject().getString("auth0Id"));
    }

    @Test
    void aWrongCronSecretIsRefused() {
        assertEquals(401, get(req -> req.putHeader("X-Cron-Secret", "nope")).statusCode());
    }

    /**
     * A cron header one byte short of correct must not pass. Asserted separately from the wholly
     * wrong one because a prefix comparison is exactly what a constant-time compare prevents, and
     * a careless rewrite to {@code startsWith} or {@code equals} would pass the test above.
     */
    @Test
    void aCronSecretThatIsAPrefixOfTheRealOneIsRefused() {
        assertEquals(401, get(req ->
            req.putHeader("X-Cron-Secret", CRON_SECRET.substring(0, CRON_SECRET.length() - 1)))
            .statusCode());
    }

    /** The cron credential is checked before the session, so it needs no cookie at all. */
    @Test
    void theCronSecretIsCheckedBeforeTheSession() {
        touchAnswer.set(Future.succeededFuture(Optional.empty()));   // a dead session
        HttpResponse<io.vertx.core.buffer.Buffer> res = get(req -> req
            .putHeader("X-Cron-Secret", CRON_SECRET)
            .putHeader("Cookie", "__Host-wp_session=whatever"));
        assertEquals(200, res.statusCode(), "the cron header must win over a dead session cookie");
        assertEquals("cron", res.bodyAsJsonObject().getString("auth0Id"));
        assertTrue(touched.get().isEmpty(), "the session should not even have been looked up");
    }

    // ── The session ──────────────────────────────────────────────────────────

    @Test
    void aLiveSessionAuthenticatesTheRequest() {
        touchAnswer.set(Future.succeededFuture(Optional.of(sessionRow("auth0|alice"))));
        HttpResponse<io.vertx.core.buffer.Buffer> res = get(req ->
            req.putHeader("Cookie", "__Host-wp_session=opaque-id-here"));
        assertEquals(200, res.statusCode());
        assertEquals("auth0|alice", res.bodyAsJsonObject().getString("auth0Id"));
        assertEquals(USER_ID.toString(), res.bodyAsJsonObject().getString("sessionUserId"));
        assertEquals(List.of("opaque-id-here"), touched.get(),
            "the cookie's value must reach touch() unmodified");
    }

    /**
     * A dead session is a 401 AND a cleared cookie.
     *
     * <p>The clearing is not cosmetic: without it the browser presents the same dead session on
     * every subsequent request, so a person whose session expired sees the whole application fail
     * rather than being returned to a signed-out state they can act on.
     */
    @Test
    void aRevokedOrExpiredSessionIsRefusedAndTheCookieIsCleared() {
        touchAnswer.set(Future.succeededFuture(Optional.empty()));
        HttpResponse<io.vertx.core.buffer.Buffer> res = get(req ->
            req.putHeader("Cookie", "__Host-wp_session=revoked-id"));
        assertEquals(401, res.statusCode());
        String setCookie = res.getHeader("Set-Cookie");
        assertNotNull(setCookie, "a dead session must be cleared from the browser");
        assertTrue(setCookie.contains("__Host-wp_session="), setCookie);
        assertTrue(setCookie.contains("Max-Age=0"), setCookie);
    }

    /**
     * THE PROPERTY THAT MAKES REVOCATION WORTH HAVING.
     *
     * <p>A browser that holds a revoked session and an unexpired id token must be refused. If the
     * token were checked first, revoking a session would do nothing until the token expired - up
     * to a day during which "sign out everywhere" and an administrator's suspension both
     * silently failed.
     */
    @Test
    void aRevokedSessionIsNotRescuedByAStillValidToken() {
        touchAnswer.set(Future.succeededFuture(Optional.empty()));
        jwtSubject.set("auth0|alice");                       // the token would have been accepted
        HttpResponse<io.vertx.core.buffer.Buffer> res = get(req -> req
            .putHeader("Cookie", "__Host-wp_session=revoked-id; auth_token=a.valid.token"));
        assertEquals(401, res.statusCode(),
            "presenting a session means being judged on the session");
    }

    /**
     * A database failure is a 503, never a 401 and never a pass.
     *
     * <p>401 would be a lie - the credential was not rejected, it could not be checked - and would
     * send a signed-in person to the sign-in page during an outage, where signing in would also
     * fail. A pass would be an authentication bypass triggered by a database blip.
     */
    @Test
    void aDatabaseFailureRefusesWithoutClaimingTheCredentialWasBad() {
        touchAnswer.set(Future.failedFuture(new RuntimeException("connection refused")));
        assertEquals(503, get(req ->
            req.putHeader("Cookie", "__Host-wp_session=anything")).statusCode());
    }

    /**
     * Two session cookies is cookie shadowing, and the handler must refuse rather than choose.
     *
     * <p>Whoever can plant a second cookie of the same name chooses which value a server that
     * takes the first match will read. {@code __Host-} is meant to make that impossible, but the
     * browser enforcing it is the attacker's own, so the server does not rely on it.
     */
    @Test
    void twoSessionCookiesAreRefusedRatherThanOneBeingPicked() {
        touchAnswer.set(Future.succeededFuture(Optional.of(sessionRow("auth0|alice"))));
        HttpResponse<io.vertx.core.buffer.Buffer> res = get(req -> req
            .putHeader("Cookie", "__Host-wp_session=mine; __Host-wp_session=planted"));
        assertEquals(401, res.statusCode());
        assertTrue(touched.get().isEmpty(), "neither value may be looked up");
    }

    // ── The legacy token path, which must keep working until it is removed ───

    @Test
    void aBearerTokenStillAuthenticates() {
        jwtSubject.set("auth0|bob");
        HttpResponse<io.vertx.core.buffer.Buffer> res = get(req ->
            req.putHeader("Authorization", "Bearer a.valid.token"));
        assertEquals(200, res.statusCode());
        assertEquals("auth0|bob", res.bodyAsJsonObject().getString("auth0Id"));
    }

    @Test
    void theLegacyAuthTokenCookieStillAuthenticates() {
        jwtSubject.set("auth0|bob");
        HttpResponse<io.vertx.core.buffer.Buffer> res = get(req ->
            req.putHeader("Cookie", "auth_token=a.valid.token"));
        assertEquals(200, res.statusCode());
        assertEquals("auth0|bob", res.bodyAsJsonObject().getString("auth0Id"));
    }

    @Test
    void anUnverifiableTokenIsRefused() {
        jwtSubject.set(null);                                 // the stub verifier rejects
        assertEquals(401, get(req ->
            req.putHeader("Authorization", "Bearer forged")).statusCode());
    }

    /**
     * A token that verifies but carries no subject must be refused.
     *
     * <p>The handler this replaced decoded the raw string a second time inside a {@code catch
     * (Exception ignored)}, so a token whose signature verified but whose body would not re-parse
     * left {@code auth0Id} unset and let the request through as authenticated-but-anonymous. Every
     * downstream handler happens to check for null, so nothing was exploitable - but the
     * authenticator was relying on its callers to fail closed on its behalf.
     */
    @Test
    void aVerifiedTokenWithNoSubjectIsRefused() {
        jwtSubject.set("");
        assertEquals(401, get(req ->
            req.putHeader("Authorization", "Bearer subjectless")).statusCode());
    }

    @Test
    void noCredentialAtAllIsRefused() {
        assertEquals(401, get(req -> req).statusCode());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private HttpResponse<io.vertx.core.buffer.Buffer> get(
            java.util.function.UnaryOperator<io.vertx.ext.web.client.HttpRequest<io.vertx.core.buffer.Buffer>> decorate) {
        try {
            return decorate.apply(client.get("/secured"))
                .send().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("request failed", e);
        }
    }

    /** A Row carrying only the two columns the handler reads. */
    private static Row sessionRow(String auth0Id) {
        return (Row) Proxy.newProxyInstance(Row.class.getClassLoader(), new Class<?>[]{Row.class},
            (proxy, method, args) -> {
                if ("getString".equals(method.getName()) && "auth0_id".equals(args[0])) return auth0Id;
                if ("getUUID".equals(method.getName())   && "user_id".equals(args[0]))  return USER_ID;
                return null;
            });
    }

    /**
     * The session lookup, programmable per test.
     *
     * <p>A lambda rather than a mock of the repository: {@link RequestAuthenticator.SessionLookup}
     * is one method, so there is nothing left to fake. It also records every id it is asked about,
     * which is how the tests above can assert that a credential was never even looked up.
     */
    private static RequestAuthenticator.SessionLookup stubSessions() {
        return opaqueId -> {
            touched.get().add(opaqueId);
            return touchAnswer.get();
        };
    }

    private static JWTAuth stubJwt() {
        return (JWTAuth) Proxy.newProxyInstance(JWTAuth.class.getClassLoader(),
            new Class<?>[]{JWTAuth.class}, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    if (!"authenticate".equals(method.getName())) return null;
                    String subject = jwtSubject.get();
                    @SuppressWarnings("unchecked")
                    io.vertx.core.Handler<io.vertx.core.AsyncResult<User>> handler =
                        (io.vertx.core.Handler<io.vertx.core.AsyncResult<User>>) args[1];
                    if (subject == null) {
                        handler.handle(Future.failedFuture("rejected"));
                    } else {
                        handler.handle(Future.succeededFuture(
                            User.create(new JsonObject().put("sub", subject.isEmpty() ? null : subject))));
                    }
                    return null;
                }
            });
    }
}
