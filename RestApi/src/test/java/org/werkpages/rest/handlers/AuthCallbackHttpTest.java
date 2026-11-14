package org.werkpages.rest.handlers;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.api.contract.RouterFactoryOptions;
import io.vertx.ext.web.api.contract.openapi3.OpenAPI3RouterFactory;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.werkpages.rest.ApiOrigins;
import org.werkpages.rest.IdTokenValidator;
import org.werkpages.repository.OAuthTransactionRepository;
import org.werkpages.repository.UserRepository;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE SOCIAL CALLBACK, END TO END OVER REAL HTTP.
 *
 * <h2>What this adds that nothing else had</h2>
 *
 * <p>Three suites already covered pieces of this flow and none of them covered the flow.
 * {@code OAuthLoginTransactionIntegrationTest} proves the binding's SQL against a real Postgres.
 * {@code IdTokenValidationTest} proves the validator refuses thirteen kinds of bad token.
 * {@code AuthCallbackBoundaryTest} reads the handler's source and proves the ORDER of its calls.
 *
 * <p>Order is not behaviour. None of them could show that a valid callback issues a session, that
 * a forged token issues nothing, that a 500 from Auth0 creates no account, or that a malformed
 * response produces a reply at all. That gap is the whole reason for this file: it drives
 * {@code POST /api/auth/callback} through the REAL router built from {@code openapi.yaml}, the
 * same {@link OpenAPI3RouterFactory} MainVerticle boots with, over a real HTTP connection, into
 * the real handler, which then talks real HTTP to a stub standing in for Auth0.
 *
 * <h2>Nothing contacts Auth0</h2>
 *
 * <p>The token and {@code /userinfo} endpoints are a local Vert.x server this class programs per
 * test. Signing keys are generated in process, so "Auth0's" JWKS is a list this test minted and
 * the validator is built from it directly. No network, no metered service, no secrets.
 *
 * <h2>What is stubbed, and what that costs</h2>
 *
 * <p>The two repositories are in-process stubs. The database's own guarantees, that
 * {@code consume} is one atomic DELETE, that a second caller gets nothing, that expiry is in the
 * WHERE clause, belong to {@code OAuthLoginTransactionIntegrationTest} and are proven there
 * against real Postgres. Repeating them here would prove only that this file's HashMap behaves
 * like a HashMap. What is proven HERE is what the HANDLER does with each answer, which is the part
 * no amount of SQL testing can reach.
 *
 * <h2>The assertion that repeats, deliberately</h2>
 *
 * <p>Almost every refusal asserts the same three things: no account was created, no session cookie
 * was issued, and for the early refusals, that Auth0 was never contacted at all. The reviewer's
 * requirement is "no user creation, linking or session issuance after ANY authentication failure",
 * and the only way to show "any" is to ask it of every failure separately. A suite that checked
 * the status code alone would pass while a failed login quietly created an account.
 */
class AuthCallbackHttpTest {

    private static final String CLIENT_ID = "test-client-id";
    private static final String AUDIENCE  = "https://api.test.example/";
    private static final String ISSUER    = "https://tenant.test.auth0.com/";
    private static final String DOMAIN    = "tenant.test.auth0.com";
    private static final String SUBJECT   = "oauth2|google-oauth2|1234567890";
    private static final String EMAIL     = "person@example.com";
    private static final String KID       = "test-key-1";

    /** The ONE value the exchange may present, derived server side and never from the request. */
    private static final String DERIVED_REDIRECT_URI =
        ApiOrigins.appOrigin(false) + "/auth/callback";

    static Vertx vertx;
    static KeyPair trusted;
    static KeyPair attacker;

    static HttpServer appServer;
    static HttpServer auth0Server;
    static WebClient client;
    static AuthHandler handler;
    static StubTransactions transactions;
    static StubUsers users;
    static StubAuth0 auth0;
    static Auth0Http realStubSeam;
    /** A port nothing is listening on, for the outage case. Bound once, then released. */
    static int deadPort;

    // ── boot ──────────────────────────────────────────────────────────────────

    @BeforeAll
    static void setUpAll() throws Exception {
        vertx = Vertx.vertx();

        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        trusted  = gen.generateKeyPair();
        attacker = gen.generateKeyPair();

        auth0 = new StubAuth0();
        auth0Server = startAuth0Stub();

        transactions = new StubTransactions();
        users        = new StubUsers();

        handler = new AuthHandler(users, transactions, DOMAIN, CLIENT_ID,
                                  "test-client-secret", AUDIENCE, "test-turnstile-secret", vertx);
        handler.setIdTokenValidator(IdTokenValidator.create(
            vertx, List.of(jwk((RSAPublicKey) trusted.getPublic(), KID)), CLIENT_ID, ISSUER));

        // THE SEAM. The handler's HTTP path now points at the local stub instead of
        // tenant.test.auth0.com:443. Everything else about the call is unchanged.
        realStubSeam = Auth0Http.plaintext(vertx, "localhost", auth0Server.actualPort());
        handler.setAuth0Http(realStubSeam);

        appServer = startApp();
        client = WebClient.create(vertx, new WebClientOptions()
            .setDefaultHost("localhost").setDefaultPort(appServer.actualPort()));

        // Bind and immediately release, so the number is known-free rather than guessed.
        HttpServer doomed = vertx.createHttpServer().requestHandler(r -> {}).listen(0)
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        deadPort = doomed.actualPort();
        doomed.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @AfterAll
    static void tearDownAll() {
        if (vertx != null) vertx.close();
    }

    @BeforeEach
    void reset() {
        auth0.reset();
        transactions.reset();
        users.reset();
        handler.setAuth0Http(realStubSeam);
        handler.setIdTokenValidator(IdTokenValidator.create(
            vertx, List.of(jwk((RSAPublicKey) trusted.getPublic(), KID)), CLIENT_ID, ISSUER));
    }

    /**
     * The real contract router, with the real handler bound to the real operationId.
     *
     * <p>Security handlers are off because the callback declares {@code security: []} and the
     * bearer handler needs a live JWKS and a database. The route under test is not behind it.
     */
    private static HttpServer startApp() throws Exception {
        CompletableFuture<Router> built = new CompletableFuture<>();
        OpenAPI3RouterFactory.create(vertx, "openapi.yaml", ar -> {
            if (ar.failed()) { built.completeExceptionally(ar.cause()); return; }
            OpenAPI3RouterFactory rf = ar.result();
            rf.setOptions(new RouterFactoryOptions().setRequireSecurityHandlers(false));
            rf.addHandlerByOperationId("socialAuthCallback", handler::handleCallback);
            built.complete(rf.getRouter());
        });
        Router router = built.get(30, TimeUnit.SECONDS);
        return vertx.createHttpServer().requestHandler(router).listen(0)
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static HttpServer startAuth0Stub() throws Exception {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.post("/oauth/token").handler(ctx -> {
            auth0.tokenRequests.add(ctx.getBodyAsJson());
            ctx.response().setStatusCode(auth0.tokenStatus)
                .putHeader("Content-Type", auth0.tokenContentType).end(auth0.tokenBody);
        });
        router.get("/userinfo").handler(ctx -> {
            auth0.userInfoBearers.add(ctx.request().getHeader("Authorization"));
            ctx.response().setStatusCode(auth0.userInfoStatus)
                .putHeader("Content-Type", auth0.userInfoContentType).end(auth0.userInfoBody);
        });
        return vertx.createHttpServer().requestHandler(router).listen(0)
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    // ── the happy path ────────────────────────────────────────────────────────

    /**
     * A NEW ACCOUNT IS CREATED WITH A PROVISIONAL USERNAME, and the flag says so.
     *
     * <p>Universal Login asks for an email and a password and never for a username, so the
     * callback generates {@code user_1a2b3c4d} and must mark it provisional - that flag is the
     * only thing that later tells profile completion who still owes a username, and
     * {@code ManagerService.looksProvisional} is the only thing keeping the placeholder out of
     * published work.
     *
     * <p>Asserted here because the handler is the single place that decides it. Passing
     * {@code false} would be invisible: the account would work, the person would never be asked
     * to choose a name, and their reviews would publish under {@code user_1a2b3c4d} for ever.
     */
    @Test
    void aNewAccountIsCreatedWithAProvisionalUsername() throws Exception {
        String state = transactions.give("browser-1", "the-code-verifier");
        auth0.respondWithTokens(accessToken(), idToken(goodIdToken()));
        auth0.respondWithProfile(SUBJECT, EMAIL);

        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertEquals(200, res.statusCode(), res.bodyAsString());
        assertFalse(users.provisionalOnCreate.isEmpty(),
            "the callback must have created an account for this first-time subject");
        assertTrue(users.provisionalOnCreate.stream().allMatch(Boolean::booleanValue),
            "every account the callback creates must be flagged provisional: Universal Login "
            + "never collected a username, so the generated one is a placeholder");
    }

    @Test
    void aBoundCallbackExchangesTheCodeAndIssuesASession() {
        String state = transactions.give("browser-1", "the-code-verifier");
        auth0.respondWithTokens(accessToken(), idToken(goodIdToken()));
        auth0.respondWithProfile(SUBJECT, EMAIL);

        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertEquals(200, res.statusCode(), "a correctly bound callback must succeed");
        JsonObject body = res.bodyAsJsonObject();
        assertEquals(EMAIL, body.getJsonObject("user").getString("email"));
        assertTrue(body.getBoolean("isNewUser"), "first social login creates the account");
        assertEquals(List.of(SUBJECT), users.created,
            "the account must be created for the VALIDATED subject");

        String cookie = res.getHeader("Set-Cookie");
        assertNotNull(cookie, "a successful login must issue the session cookie");
        assertTrue(cookie.startsWith("auth_token=" + auth0.issuedAccessToken),
            "the session must carry the access token, whose aud the router validates");
        assertTrue(cookie.contains("HttpOnly"), "the session cookie must not be script-readable");
        assertTrue(cookie.contains("SameSite=Lax"));
    }

    @Test
    void theExchangePresentsTheStoredVerifierAndTheDerivedRedirectUri() {
        String state = transactions.give("browser-1", "the-code-verifier");
        auth0.respondWithTokens(accessToken(), idToken(goodIdToken()));
        auth0.respondWithProfile(SUBJECT, EMAIL);

        callback(state, "browser-1", "the-authorization-code", "https://evil.example/steal");

        assertEquals(1, auth0.tokenRequests.size(), "exactly one exchange");
        JsonObject sent = auth0.tokenRequests.get(0);
        assertEquals("authorization_code", sent.getString("grant_type"));
        assertEquals("the-authorization-code", sent.getString("code"));
        assertEquals(CLIENT_ID, sent.getString("client_id"));
        assertEquals(AUDIENCE,  sent.getString("audience"));

        // PKCE. The verifier came from the consumed row and has never been in a browser.
        assertEquals("the-code-verifier", sent.getString("code_verifier"),
            "the exchange must present the verifier stored when the login began");

        // THE REDIRECT URI IS DERIVED. The body asked for an attacker's host and is ignored.
        assertEquals(DERIVED_REDIRECT_URI, sent.getString("redirect_uri"),
            "redirect_uri must be built server side; the body's copy must not be consulted");

        assertEquals(List.of("Bearer " + auth0.issuedAccessToken), auth0.userInfoBearers,
            "/userinfo must be read with the access token from this exchange");
    }

    @Test
    void anExistingAccountIsNotRecreated() {
        users.existing.add(SUBJECT);
        String state = transactions.give("browser-1", "v");
        auth0.respondWithTokens(accessToken(), idToken(goodIdToken()));
        auth0.respondWithProfile(SUBJECT, EMAIL);

        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertEquals(200, res.statusCode());
        assertFalse(res.bodyAsJsonObject().getBoolean("isNewUser"));
        assertEquals(List.of(), users.created, "a returning user must not be created again");
    }

    // ── the binding: refused before Auth0 is contacted at all ─────────────────

    @Test
    void aCallbackWithNoBrowserCookieIsRefusedWithoutContactingAuth0() {
        /*
          LOGIN CSRF, the attack PKCE does not close. The attacker starts their own login, gets a
          real code, and induces the victim's browser to complete it; without the cookie there is
          nothing to tell the two browsers apart, and the victim is signed in as the attacker.

          The refusal must come BEFORE the exchange. Exchanging first and refusing afterwards would
          still have spent the code and still have been the takeover.
        */
        String state = transactions.give("browser-1", "v");
        HttpResponse<Buffer> res = callbackWithoutCookie(state);

        assertRefusedBinding(res);
        assertNothingHappened();
    }

    @Test
    void aCallbackFromADifferentBrowserIsRefusedWithoutContactingAuth0() {
        String state = transactions.give("browser-1", "v");
        HttpResponse<Buffer> res = callback(state, "browser-2");

        assertRefusedBinding(res);
        assertNothingHappened();
    }

    @Test
    void aCallbackWithNoStateIsRefusedWithoutContactingAuth0() {
        transactions.give("browser-1", "v");
        HttpResponse<Buffer> res = callback(null, "browser-1");

        assertRefusedBinding(res);
        assertNothingHappened();
    }

    @Test
    void anUnknownStateIsRefusedWithoutContactingAuth0() {
        transactions.give("browser-1", "v");
        HttpResponse<Buffer> res = callback("a-state-nobody-issued", "browser-1");

        assertRefusedBinding(res);
        assertNothingHappened();
    }

    @Test
    void anExpiredTransactionIsRefusedWithoutContactingAuth0() {
        String state = transactions.giveExpired("browser-1", "v");
        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertRefusedBinding(res);
        assertNothingHappened();
    }

    @Test
    void twoCookiesOfTheSameNameAreRefusedWithoutContactingAuth0() {
        /*
          Cookie shadowing. __Host- is supposed to make a second cookie of that name impossible,
          but the prefix is enforced by the browser and the browser is the attacker's machine. A
          server that takes the first match lets the attacker choose which value it reads.
        */
        String state = transactions.give("browser-1", "v");
        HttpResponse<Buffer> res = post(
            new JsonObject().put("code", "c").put("redirectUri", DERIVED_REDIRECT_URI)
                            .put("state", state),
            AuthTokenUtils.BROWSER_COOKIE + "=browser-1; "
          + AuthTokenUtils.BROWSER_COOKIE + "=browser-2");

        assertRefusedBinding(res);
        assertNothingHappened();
    }

    @Test
    void aReplayedCallbackIsRefusedAndExchangesNothingASecondTime() {
        /*
          The row is gone after the first callback. This is the single-use guarantee, and it
          belongs to the database rather than to any cookie: consume() deleted the row, so the
          second presentation of the same state matches nothing.
        */
        String state = transactions.give("browser-1", "the-code-verifier");
        auth0.respondWithTokens(accessToken(), idToken(goodIdToken()));
        auth0.respondWithProfile(SUBJECT, EMAIL);

        assertEquals(200, callback(state, "browser-1").statusCode());
        HttpResponse<Buffer> replay = callback(state, "browser-1");

        assertRefusedBinding(replay);
        assertEquals(1, auth0.tokenRequests.size(),
            "a replayed state must not reach the token endpoint a second time");
    }

    // ── the identity token ────────────────────────────────────────────────────

    @Test
    void aTokenSignedByTheWrongKeyCreatesNothing() {
        /*
          THE ORIGINAL DEFECT, exercised over HTTP. The subject used to come from JWT.decode,
          which verifies nothing, so this token would have created an account for whatever subject
          it named. Here it must produce a 401 and leave no trace.
        */
        String forged = JWT.create().withKeyId(KID).withIssuer(ISSUER).withAudience(CLIENT_ID)
            .withSubject("oauth2|the-attacker")
            .withIssuedAt(Date.from(Instant.now().minusSeconds(5)))
            .withExpiresAt(Date.from(Instant.now().plusSeconds(300)))
            .sign(Algorithm.RSA256((RSAPublicKey) attacker.getPublic(),
                                   (RSAPrivateKey) attacker.getPrivate()));

        assertIdentityRefused(forged, "invalid_identity_token");
    }

    @Test
    void aTokenFromAnotherIssuerCreatesNothing() {
        assertIdentityRefused(idToken(goodIdToken().withIssuer("https://evil.example/")),
            "invalid_identity_token");
    }

    @Test
    void aTokenForAnotherClientCreatesNothing() {
        /*
          Both products share one Auth0 tenant and therefore one JWKS, so the other product's
          token validates on SIGNATURE here. The audience is what scopes a token to this client.
        */
        assertIdentityRefused(idToken(goodIdToken().withAudience("the-other-product")),
            "invalid_identity_token");
    }

    @Test
    void anExpiredTokenCreatesNothing() {
        Instant past = Instant.now().minusSeconds(3600);
        assertIdentityRefused(idToken(goodIdToken()
                .withIssuedAt(Date.from(past.minusSeconds(60)))
                .withExpiresAt(Date.from(past))),
            "invalid_identity_token");
    }

    @Test
    void theIdentityIsCheckedBeforeUserInfoIsRead() {
        String state = transactions.give("browser-1", "v");
        auth0.respondWithTokens(accessToken(), idToken(goodIdToken().withIssuer("https://evil.example/")));
        auth0.respondWithProfile(SUBJECT, EMAIL);

        callback(state, "browser-1");

        assertEquals(List.of(), auth0.userInfoBearers,
            "a profile must not be fetched for an identity nobody has verified");
    }

    @Test
    void aUserInfoSubjectThatDisagreesWithTheTokenCreatesNothing() {
        /*
          Two sources of identity that are never compared are two chances to be wrong about whose
          account is being opened. The subject comes from the token and the email from /userinfo,
          and until this was added nothing established that they described the same person.
        */
        String state = transactions.give("browser-1", "v");
        auth0.respondWithTokens(accessToken(), idToken(goodIdToken()));
        auth0.respondWithProfile("oauth2|somebody-else", EMAIL);

        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertEquals(401, res.statusCode());
        assertEquals("identity_mismatch", res.bodyAsJsonObject().getString("error"));
        assertNoAccountAndNoSession(res);
    }

    @Test
    void aCallbackWithNoIdTokenValidatorRefusesRatherThanDecoding() {
        /*
          The validator is set after the JWKS fetch at boot. If that fetch fails the field is null,
          and the tempting repair is a decode "just for now". It must refuse instead, so one
          transient network failure at boot cannot silently downgrade every login for the lifetime
          of the process.
        */
        handler.setIdTokenValidator(null);
        String state = transactions.give("browser-1", "v");
        auth0.respondWithTokens(accessToken(), idToken(goodIdToken()));
        auth0.respondWithProfile(SUBJECT, EMAIL);

        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertEquals(500, res.statusCode());
        assertNoAccountAndNoSession(res);
        assertEquals(List.of(), auth0.userInfoBearers);
    }

    // ── what Auth0 can do wrong ───────────────────────────────────────────────

    @Test
    void aRefusedExchangeCreatesNothingAndReadsNoProfile() {
        String state = transactions.give("browser-1", "v");
        auth0.tokenStatus = 401;
        auth0.tokenBody   = new JsonObject().put("error", "invalid_grant").encode();

        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertEquals(401, res.statusCode());
        assertEquals("token_exchange_failed", res.bodyAsJsonObject().getString("error"));
        assertEquals(List.of(), auth0.userInfoBearers);
        assertNoAccountAndNoSession(res);
    }

    @Test
    void aMalformedTokenResponseAnswersTheBrowserAndCreatesNothing() {
        /*
          THE HANG. bodyAsJsonObject() throws on anything that is not a JSON object, and it was
          called straight inside the response lambda. A throw there is caught by nothing that can
          answer the browser: Vert.x logs it on the context and the response is never completed, so
          the person's login hangs until their client gives up.

          A proxy error page or an HTML maintenance notice in front of the token endpoint is enough
          to cause it, which is why THE ASSERTION THAT MATTERS MOST HERE IS THAT A REPLY ARRIVES.
          Before the fix this test did not fail on a status code; it timed out.
        */
        String state = transactions.give("browser-1", "v");
        auth0.tokenStatus      = 200;
        auth0.tokenContentType = "text/html";
        auth0.tokenBody        = "<html><body>502 Bad Gateway</body></html>";

        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertNotEquals(200, res.statusCode(),
            "a token response that is not JSON must never be treated as a successful login");
        assertNoAccountAndNoSession(res);
    }

    @Test
    void aFailedUserInfoAnswersTheBrowserAndCreatesNothing() {
        /*
          The /userinfo status was not checked at all. A 401 or a 429 fell through to
          bodyAsJsonObject() on an error body, which either threw and hung the request or produced
          an object with no `sub`, and the second is worse, because the subject reconciliation
          would then refuse for the wrong reason and the real cause would never reach a log.
        */
        String state = transactions.give("browser-1", "v");
        auth0.respondWithTokens(accessToken(), idToken(goodIdToken()));
        auth0.userInfoStatus = 429;
        auth0.userInfoBody   = new JsonObject().put("error", "too_many_requests").encode();

        HttpResponse<Buffer> res = callback(state, "browser-1");

        // THE ERROR MUST NAME THE UPSTREAM FAILURE, and this is the assertion that actually
        // detects the missing check. Without it a 429 is still refused, because its JSON body
        // carries no `sub` and the subject reconciliation then fails - so a status-code-only
        // assertion passes against the defective code and proves nothing. What it refuses as is
        // "identity_mismatch", which in a log reads as somebody attacking the callback. The first
        // person to see it goes looking for a forged token instead of for Auth0 rate-limiting
        // this tenant.
        assertEquals(500, res.statusCode());
        assertEquals("internal_error", res.bodyAsJsonObject().getString("error"),
            "a failure fetching the profile must be reported as an upstream failure, never as a "
          + "mismatched identity");
        assertNoAccountAndNoSession(res);
    }

    @Test
    void aMalformedUserInfoResponseAnswersTheBrowserAndCreatesNothing() {
        String state = transactions.give("browser-1", "v");
        auth0.respondWithTokens(accessToken(), idToken(goodIdToken()));
        auth0.userInfoStatus      = 200;
        auth0.userInfoContentType = "text/plain";
        auth0.userInfoBody        = "not json at all";

        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertNotEquals(200, res.statusCode());
        assertNoAccountAndNoSession(res);
    }

    @Test
    void anAuth0OutageAnswersTheBrowserAndCreatesNothing() {
        /*
          Nothing listening at all: connection refused. The transaction has already been consumed
          by this point, so the person must retry the whole login, which is correct. What must NOT
          happen is a half-finished account or a session issued without a verified identity.
        */
        handler.setAuth0Http(Auth0Http.plaintext(vertx, "localhost", deadPort));
        String state = transactions.give("browser-1", "v");

        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertEquals(500, res.statusCode());
        assertNoAccountAndNoSession(res);
    }

    @Test
    void aProviderThatSharesNoEmailIsRefusedBeforeAnyAccountWork() {
        String state = transactions.give("browser-1", "v");
        auth0.respondWithTokens(accessToken(), idToken(goodIdToken()));
        auth0.userInfoBody = new JsonObject().put("sub", SUBJECT).put("name", "No Email").encode();

        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertEquals(400, res.statusCode());
        assertEquals("no_email", res.bodyAsJsonObject().getString("error"));
        assertNoAccountAndNoSession(res);
    }

    // ── the destination itself ────────────────────────────────────────────────

    @Test
    void theRealSeamUsesTlsOnPortFourFourThreeAtTheConfiguredTenant() {
        /*
          Every other test in this file points the handler at a plaintext stub, which is exactly
          the thing that could hide a production regression: a seam that forgot ssl(true) would
          pass all of them. So the production factory is asserted directly. Auth0's /authorize and
          /oauth/token must also reach the SAME host, or the PKCE challenge recorded by one is
          checked against nothing by the other.
        */
        Auth0Http production = Auth0Http.https(vertx, DOMAIN);
        assertEquals(DOMAIN, production.host(), "the exchange must reach the configured tenant");
        assertEquals(443, production.port());
        assertTrue(production.ssl(), "the token exchange carries the client secret; it must be TLS");
    }

    // ── email verification ────────────────────────────────────────────────────

    /*
      WHY THIS IS ENFORCED HERE AND NOT LEFT TO AUTH0.

      Nothing in either product has ever read `email_verified`. Not this validator, not the
      callback, not a handler. Enforcement was entirely Auth0's, applied by the database connection
      when it refused the password grant, and the ONLY trace of it in our code was a line in the
      password path checking whether Auth0's error BODY contained the word "verify" so the frontend
      could say "verify your email".

      Stage 4 deletes that line with the rest of the password path. If the hosted login page does
      not refuse an unverified account the same way the password grant did, an unverified person
      completes this callback, nothing looks at the claim, and they get an account and a session.

      Leaving the requirement to a connection setting also means a console toggle silently decides
      whether unverified people can contribute, with nothing in the codebase recording that it was
      ever required. The claim is on a token we have already verified the signature of. Checking it
      costs nothing.
    */

    @Test
    void anUnverifiedEmailIsRefusedBeforeTheProfileIsEverFetched() {
        String state = transactions.give("browser-1", "v");
        auth0.respondWithTokens(accessToken(), idToken(goodIdToken()
            .withClaim("email_verified", false)));
        auth0.respondWithProfile(SUBJECT, EMAIL);

        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertEquals(401, res.statusCode());
        assertEquals("email_unverified", res.bodyAsJsonObject().getString("error"),
            "this refusal is NAMED, unlike the identity failures: the person can do something "
          + "about it, and the frontend has to be able to tell them what");
        assertNoAccountAndNoSession(res);
        assertEquals(List.of(), auth0.userInfoBearers,
            "the refusal must precede /userinfo: an unverified identity's profile is not ours to "
          + "fetch, and a request made before a refusal is a request somebody will later use");
    }

    @Test
    void whenNeitherSourceVerifiesTheAddressTheLoginIsRefused() {
        /*
          FAIL CLOSED, with both sources silent: the token says nothing about verification and the
          profile says nothing either. Treating silence as verification is how a check like this
          gets quietly lost - a provider stops sending the claim, nobody notices, and the
          requirement evaporates with nothing failing.

          Which connections are actually silent is the thing the per-connection test tenant run has
          to establish. If one genuinely never asserts it anywhere, this is a lockout rather than a
          defence, and the answer is that connection's Auth0 attribute mapping, not a widening
          here.
        */
        String state = transactions.give("browser-1", "v");
        auth0.respondWithTokens(accessToken(), tokenNamingNoAddress());
        auth0.respondWithProfile(SUBJECT, EMAIL);          // no email_verified at all

        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertEquals(401, res.statusCode());
        assertEquals("email_unverified", res.bodyAsJsonObject().getString("error"));
        assertNoAccountAndNoSession(res);
    }

    @Test
    void theStringTrueIsRefusedBecauseTheSpecificationSaysBoolean() {
        /*
          OpenID Connect Core section 5.1 defines `email_verified` as a boolean, so `true` is the
          only accepted value.

          An earlier version of this accepted the string form, reasoning that some upstream
          provider might send it and that refusing would be a lockout caused by a type rather than
          by a policy. It was a compatibility exception with NO EVIDENCE behind it: no connection
          had been observed sending a string, and it widened what counts as verified to cover a
          provider nobody had checked. If a real connection turns out to send one, the fix is that
          connection's Auth0 attribute mapping, reviewed on its own, not a quiet widening here.
        */
        for (Object nonBoolean : List.of("true", "True", "yes", 1)) {
            String state = transactions.give("browser-1", "v");
            auth0.respondWithTokens(accessToken(), idToken(goodIdToken()
                .withClaim("email_verified", nonBoolean.toString())));
            auth0.respondWithProfile(SUBJECT, EMAIL);

            HttpResponse<Buffer> res = callback(state, "browser-1");

            assertEquals(401, res.statusCode(),
                "a non-boolean email_verified must be refused: " + nonBoolean);
            assertEquals("email_unverified", res.bodyAsJsonObject().getString("error"));
            assertNoAccountAndNoSession(res);
        }
    }

    // ── the verified flag must apply to the address actually used ──────────────

    /** A validated token that says nothing about an email address, in any form. */
    private static String tokenNamingNoAddress() {
        Instant now = Instant.now();
        return idToken(JWT.create().withKeyId(KID).withIssuer(ISSUER)
            .withAudience(CLIENT_ID).withSubject(SUBJECT)
            .withIssuedAt(Date.from(now.minusSeconds(5)))
            .withExpiresAt(Date.from(now.plusSeconds(300))));
    }

    @Test
    void withNoEmailClaimTheProfileMustVerifyTheAddressItself() {
        /*
          THE GAP THAT SUBJECT RECONCILIATION DOES NOT CLOSE.

          When the id_token names no address, its `email_verified` is true of something it never
          identified, and reconciling the SUBJECT establishes only that both responses concern the
          same Auth0 identity. It does not establish that the mailbox /userinfo returned - the one
          the account is created with - was ever verified.

          An earlier version let this through on the subject alone and logged a note. A note is not
          a control. /userinfo is asked instead, and it is a trustworthy answer because the
          response comes from the configured tenant, fetched with the access token from the same
          client-authenticated exchange, with its subject already reconciled.
        */
        String state = transactions.give("browser-1", "v");
        auth0.respondWithTokens(accessToken(), tokenNamingNoAddress());
        auth0.respondWithProfile(SUBJECT, EMAIL, true);

        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertEquals(200, res.statusCode(),
            "a profile that verifies the address it returned is a trusted association");
        assertEquals(List.of(SUBJECT), users.created);
    }

    @Test
    void withNoEmailClaimAndNoProfileVerificationTheLoginIsRefused() {
        for (Object profileFlag : new Object[] { false, null, "true", 1 }) {
            String state = transactions.give("browser-1", "v");
            auth0.respondWithTokens(accessToken(), tokenNamingNoAddress());
            auth0.respondWithProfile(SUBJECT, EMAIL, profileFlag);

            HttpResponse<Buffer> res = callback(state, "browser-1");

            assertEquals(401, res.statusCode(),
                "nothing trusted verifies this address, so it must be refused: " + profileFlag);
            assertEquals("email_unverified", res.bodyAsJsonObject().getString("error"));
            assertNoAccountAndNoSession(res);
        }
    }

    @Test
    void aTokenThatSaysUnverifiedIsRefusedEvenIfTheProfileDisagrees() {
        /*
          The profile is the fallback when the token is SILENT, not an override when it speaks. A
          provider that positively says "not verified" is not second-guessed by another endpoint of
          the same tenant, and the refusal happens before the profile is even fetched.
        */
        String state = transactions.give("browser-1", "v");
        auth0.respondWithTokens(accessToken(), idToken(goodIdToken()
            .withClaim("email_verified", false)));
        auth0.respondWithProfile(SUBJECT, EMAIL, true);

        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertEquals(401, res.statusCode());
        assertEquals("email_unverified", res.bodyAsJsonObject().getString("error"));
        assertEquals(List.of(), auth0.userInfoBearers,
            "a token that says unverified is refused before the profile is requested");
    }

    @Test
    void anEmailClaimThatDisagreesWithTheProfileIsRefused() {
        /*
          `email_verified` is read off the id_token; the address the account is created with comes
          from /userinfo. Nothing tied them together, so the flag could be trusted for one mailbox
          while the account was opened on another.

          Same shape as the subject reconciliation beside it, and refused the same way: two
          sources of identity that are never compared are two chances to be wrong about whose
          account is being opened.
        */
        String state = transactions.give("browser-1", "v");
        auth0.respondWithTokens(accessToken(), idToken(goodIdToken()
            .withClaim("email", "verified.elsewhere@example.com")));
        auth0.respondWithProfile(SUBJECT, EMAIL);

        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertEquals(401, res.statusCode());
        assertEquals("identity_mismatch", res.bodyAsJsonObject().getString("error"));
        assertNoAccountAndNoSession(res);
    }

    @Test
    void theEmailComparisonIgnoresCase() {
        // The local part is technically case-sensitive per RFC 5321 and no provider here treats it
        // that way. A refusal on case would be a lockout, not a defence.
        String state = transactions.give("browser-1", "v");
        auth0.respondWithTokens(accessToken(), idToken(goodIdToken()
            .withClaim("email", EMAIL.toUpperCase())));
        auth0.respondWithProfile(SUBJECT, EMAIL);

        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertEquals(200, res.statusCode(), "the same mailbox in different case is the same mailbox");
        assertEquals(List.of(SUBJECT), users.created);
    }

    @Test
    void aVerifiedFlagOverNoNamedAddressIsNotEnoughOnItsOwn() {
        /*
          `email_verified: true` with no `email` claim beside it is true of nothing in particular.
          It used to be accepted, because the flag was read without asking what it was a flag
          ABOUT. The profile has to carry the association instead, and here it does not.
        */
        Instant now = Instant.now();
        String verifiedButUnnamed = idToken(JWT.create().withKeyId(KID).withIssuer(ISSUER)
            .withAudience(CLIENT_ID).withSubject(SUBJECT)
            .withClaim("email_verified", true)
            .withIssuedAt(Date.from(now.minusSeconds(5)))
            .withExpiresAt(Date.from(now.plusSeconds(300))));
        String state = transactions.give("browser-1", "v");
        auth0.respondWithTokens(accessToken(), verifiedButUnnamed);
        auth0.respondWithProfile(SUBJECT, EMAIL);

        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertEquals(401, res.statusCode());
        assertEquals("email_unverified", res.bodyAsJsonObject().getString("error"));
        assertNoAccountAndNoSession(res);
    }

    // ── shared assertions ─────────────────────────────────────────────────────

    private static void assertRefusedBinding(HttpResponse<Buffer> res) {
        assertEquals(400, res.statusCode(), "a callback that fails the binding must be refused");
        assertEquals("invalid_login_transaction", res.bodyAsJsonObject().getString("error"),
            "every binding failure is refused identically; naming which half was wrong helps "
          + "only the forger");
    }

    /** Auth0 was never contacted, and nothing was written. For refusals before the exchange. */
    private static void assertNothingHappened() {
        assertEquals(List.of(), auth0.tokenRequests,
            "a refused callback must never reach the token endpoint");
        assertEquals(List.of(), auth0.userInfoBearers);
        assertEquals(List.of(), users.created);
    }

    private static void assertNoAccountAndNoSession(HttpResponse<Buffer> res) {
        assertEquals(List.of(), users.created,
            "a failed authentication must not create or link an account");
        String cookie = res.getHeader("Set-Cookie");
        assertTrue(cookie == null || !cookie.startsWith("auth_token="),
            "a failed authentication must not issue a session cookie");
    }

    private void assertIdentityRefused(String badIdToken, String expectedError) {
        String state = transactions.give("browser-1", "v");
        auth0.respondWithTokens(accessToken(), badIdToken);
        auth0.respondWithProfile(SUBJECT, EMAIL);

        HttpResponse<Buffer> res = callback(state, "browser-1");

        assertEquals(401, res.statusCode());
        assertEquals(expectedError, res.bodyAsJsonObject().getString("error"));
        assertNoAccountAndNoSession(res);
    }

    // ── driving the endpoint ──────────────────────────────────────────────────

    private static HttpResponse<Buffer> callback(String state, String browser) {
        return callback(state, browser, "the-authorization-code", DERIVED_REDIRECT_URI);
    }

    private static HttpResponse<Buffer> callback(
            String state, String browser, String code, String redirectUri) {
        JsonObject body = new JsonObject().put("code", code).put("redirectUri", redirectUri);
        if (state != null) body.put("state", state);
        return post(body, AuthTokenUtils.BROWSER_COOKIE + "=" + browser);
    }

    private static HttpResponse<Buffer> callbackWithoutCookie(String state) {
        return post(new JsonObject().put("code", "c").put("redirectUri", DERIVED_REDIRECT_URI)
                                    .put("state", state), null);
    }

    /**
     * One POST, with a bounded wait.
     *
     * <p>The timeout is not incidental. The malformed-response defect presented as a request that
     * was never answered, so a test that waited forever would have HUNG rather than failed, and a
     * hanging suite gets killed rather than read.
     */
    private static HttpResponse<Buffer> post(JsonObject body, String cookie) {
        var request = client.post("/api/auth/callback").timeout(5_000);
        if (cookie != null) request.putHeader("Cookie", cookie);
        try {
            return request.sendJsonObject(body)
                .toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(
                "POST /api/auth/callback produced no response within the timeout. An unanswered "
              + "request is the defect, not the symptom: the browser hangs on a blank page.", e);
        }
    }

    // ── minting tokens ────────────────────────────────────────────────────────

    /**
     * A token with every claim a legitimate one carries, which individual tests then spoil.
     *
     * <p>{@code email_verified} is part of "legitimate" as of the enforcement below. It was not
     * here before, because nothing looked at it: the claim arrived on every real token and was
     * ignored.
     */
    private static com.auth0.jwt.JWTCreator.Builder goodIdToken() {
        Instant now = Instant.now();
        return JWT.create().withKeyId(KID).withIssuer(ISSUER).withAudience(CLIENT_ID)
            .withSubject(SUBJECT)
            .withClaim("email_verified", true)
            // The address the flag applies to. The callback checks it against the one /userinfo
            // gives, which is the address the account is actually created with.
            .withClaim("email", EMAIL)
            .withIssuedAt(Date.from(now.minusSeconds(5)))
            .withExpiresAt(Date.from(now.plusSeconds(300)));
    }

    private static String idToken(com.auth0.jwt.JWTCreator.Builder builder) {
        return builder.sign(Algorithm.RSA256((RSAPublicKey) trusted.getPublic(),
                                             (RSAPrivateKey) trusted.getPrivate()));
    }

    /** An access token shaped like the real one: a JWT whose aud is the API identifier. */
    private static String accessToken() {
        Instant now = Instant.now();
        return JWT.create().withKeyId(KID).withIssuer(ISSUER).withAudience(AUDIENCE)
            .withSubject(SUBJECT)
            .withIssuedAt(Date.from(now.minusSeconds(5)))
            .withExpiresAt(Date.from(now.plusSeconds(300)))
            .sign(Algorithm.RSA256((RSAPublicKey) trusted.getPublic(),
                                   (RSAPrivateKey) trusted.getPrivate()));
    }

    private static JsonObject jwk(RSAPublicKey pub, String kid) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return new JsonObject()
            .put("kty", "RSA").put("alg", "RS256").put("use", "sig").put("kid", kid)
            .put("n", b64.encodeToString(unsigned(pub.getModulus())))
            .put("e", b64.encodeToString(unsigned(pub.getPublicExponent())));
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    // ── the stubs ─────────────────────────────────────────────────────────────

    /** Auth0's two endpoints, programmed per test and recording what it was asked. */
    static final class StubAuth0 {
        int tokenStatus;
        String tokenBody;
        String tokenContentType;
        int userInfoStatus;
        String userInfoBody;
        String userInfoContentType;
        String issuedAccessToken;
        final List<JsonObject> tokenRequests   = new ArrayList<>();
        final List<String>     userInfoBearers = new ArrayList<>();

        StubAuth0() { reset(); }

        void reset() {
            tokenStatus = 200;
            tokenBody = "{}";
            tokenContentType = "application/json";
            userInfoStatus = 200;
            userInfoBody = "{}";
            userInfoContentType = "application/json";
            issuedAccessToken = null;
            tokenRequests.clear();
            userInfoBearers.clear();
        }

        void respondWithTokens(String accessToken, String idToken) {
            issuedAccessToken = accessToken;
            tokenBody = new JsonObject()
                .put("access_token", accessToken)
                .put("id_token", idToken)
                .put("token_type", "Bearer")
                .put("expires_in", 86400).encode();
        }

        void respondWithProfile(String sub, String email) {
            userInfoBody = new JsonObject().put("sub", sub).put("email", email)
                .put("given_name", "Test").put("family_name", "Person").encode();
        }

        /**
         * A profile that answers the verification question itself, the way a real
         * {@code /userinfo} response does.
         *
         * <p>{@code emailVerified} is an Object so a test can send the string "true" and a number
         * as well as a boolean, which is the whole point of the strictness being asserted.
         */
        void respondWithProfile(String sub, String email, Object emailVerified) {
            JsonObject body = new JsonObject().put("sub", sub).put("email", email)
                .put("given_name", "Test").put("family_name", "Person");
            if (emailVerified != null) body.put("email_verified", emailVerified);
            userInfoBody = body.encode();
        }
    }

    /**
     * The pending-login store, in memory.
     *
     * <p>Models only what the handler can observe: a state plus the right browser yields the row
     * ONCE, and anything else yields nothing. The real SQL guarantees, one atomic DELETE, expiry
     * in the WHERE clause, a racing second caller getting nothing, are proven against real
     * Postgres in {@code OAuthLoginTransactionIntegrationTest}, and restating them against a
     * HashMap here would prove nothing about either.
     */
    static final class StubTransactions extends OAuthTransactionRepository {
        private final Map<String, Consumed> rows = new HashMap<>();
        private int counter;

        StubTransactions() { super(null); }

        void reset() { rows.clear(); }

        /** Registers a transaction and returns its state. */
        String give(String browserSecret, String codeVerifier) {
            String state = "state-" + (++counter);
            rows.put(key(state, browserSecret),
                     new Consumed(codeVerifier, "google-oauth2", "/"));
            return state;
        }

        /** A state that exists but is past its expiry: indistinguishable from absent, by design. */
        String giveExpired(String browserSecret, String codeVerifier) {
            return "state-expired-" + (++counter);
        }

        @Override
        public Future<Optional<Consumed>> consume(String state, String browserSecret) {
            return Future.succeededFuture(
                Optional.ofNullable(rows.remove(key(state, browserSecret))));
        }

        @Override
        public Future<Boolean> hasLivePendingLogin(String browserSecret) {
            return Future.succeededFuture(
                rows.keySet().stream().anyMatch(k -> k.endsWith("|" + browserSecret)));
        }

        private static String key(String state, String browserSecret) {
            return state + "|" + browserSecret;
        }
    }

    /** The account store, in memory, recording every creation so a refusal can assert none. */
    static final class StubUsers extends UserRepository {
        final List<String> created  = new ArrayList<>();
        /** Whether each created account was flagged provisional. Universal Login always flags. */
        final java.util.List<Boolean> provisionalOnCreate = new java.util.ArrayList<>();
        final Set<String>  existing = new HashSet<>();

        StubUsers() { super(null); }

        void reset() { created.clear(); existing.clear(); }

        @Override
        public Future<Optional<Row>> findByAuth0IdWithBan(String auth0Id) {
            return Future.succeededFuture(existing.contains(auth0Id)
                ? Optional.of(row(auth0Id)) : Optional.empty());
        }

        @Override
        public Future<RowSet<Row>> create(String auth0Id, String email, String username,
                                          String firstName, String lastName) {
            return create(auth0Id, email, username, firstName, lastName, false);
        }

        /**
         * The SIX-argument overload, which is the one the handler actually calls.
         *
         * <p>Overriding only the five-argument version was not enough and the failure was
         * instructive: the real `create` gained a `provisional` parameter when profile completion
         * landed, the five-argument version now delegates to it, and the handler calls the six.
         * So this stub overrode a method nothing called while the REAL repository ran against a
         * null pool - four tests failed with 500 where they expected 200 and 403.
         *
         * <p>That is the standing hazard with a hand-written stub of a concrete class: adding an
         * overload to the real class silently routes past the override. A compiler cannot catch
         * it, because overriding fewer methods than exist is legal.
         */
        @Override
        public Future<RowSet<Row>> create(String auth0Id, String email, String username,
                                          String firstName, String lastName, boolean provisional) {
            created.add(auth0Id);
            existing.add(auth0Id);
            provisionalOnCreate.add(provisional);
            return Future.succeededFuture(null);
        }

        @Override
        public Future<Boolean> hasContributed(UUID userId) { return Future.succeededFuture(false); }

        @Override
        public Future<Boolean> hasRatedCompany(UUID userId) { return Future.succeededFuture(false); }

        @Override
        public String decryptField(String value) { return value; }

        /**
         * A {@link Row} over a map, via a proxy.
         *
         * <p>Row is a wide interface and the handler reads seven columns from it. Implementing all
         * of it by hand would be a hundred lines of methods nothing calls, and the ones that do
         * get called would be lost among them.
         */
        private static Row row(String auth0Id) {
            Map<String, Object> values = new HashMap<>();
            values.put("id", UUID.nameUUIDFromBytes(auth0Id.getBytes()));
            values.put("auth0_id", auth0Id);
            values.put("email", EMAIL);
            values.put("username", "user_testperson");
            values.put("first_name", "Test");
            values.put("last_name", "Person");
            values.put("role", "user");
            values.put("is_banned", Boolean.FALSE);
            /*
              Selected by findByAuth0IdWithBan and findByAuth0IdForSignin since profile completion
              landed, and read by the handler to decide `profileComplete`.

              Omitting it made four tests in this class fail with 500 instead of their expected
              status, because a Vert.x Row throws on an unknown column rather than returning null.
              That is the stub's job: it stands in for a query, and a query that selects a column
              the handler reads must have it. The alternative - making the handler tolerate a
              missing flag - would mean defaulting it, and either default is wrong: "complete"
              skips profile completion for somebody who needs it, "incomplete" sends everybody
              through it for ever.
            */
            values.put("username_is_provisional", Boolean.FALSE);
            values.put("created_at", LocalDateTime.now());

            InvocationHandler answers = (proxy, method, args) -> {
                if (args != null && args.length == 1 && args[0] instanceof String column) {
                    return values.get(column);
                }
                if ("toString".equals(method.getName())) return "StubRow" + values;
                throw new UnsupportedOperationException(
                    "the stub Row was asked for " + method.getName()
                  + "; add it here rather than loosening the stub");
            };
            return (Row) Proxy.newProxyInstance(
                Row.class.getClassLoader(), new Class<?>[]{Row.class}, answers);
        }
    }
}
