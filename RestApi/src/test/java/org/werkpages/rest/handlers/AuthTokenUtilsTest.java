package org.werkpages.rest.handlers;

import com.auth0.jwt.algorithms.Algorithm;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.werkpages.rest.JwtAuthFactory;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards caller identification on endpoints that allow anonymous access.
 *
 * <p>These endpoints gate part of their response on who is asking — {@code categoryAverages} is
 * withheld from users who have not contributed a review. They used to identify the caller by
 * decoding the JWT without verifying its signature, which made the gate decorative: a JWT is not
 * encrypted and anyone can mint one carrying any {@code sub}. These tests sign real RS256 tokens,
 * serve them over a real HTTP request, and assert that only genuinely valid ones yield a subject.
 *
 * <p>The other half of the contract matters just as much: an invalid token must resolve to
 * anonymous rather than failing the request. The public half of these endpoints has to keep
 * working for signed-out visitors and for anyone carrying a stale cookie.
 */
class AuthTokenUtilsTest {

    private static final String AUDIENCE = "https://api.werkpages.com/";
    private static final String KID      = "test-key-1";
    private static final String SUBJECT  = "auth0|real-user";

    private static Vertx vertx;
    private static WebClient client;
    private static int port;

    private static RSAPublicKey  publicKey;
    private static RSAPrivateKey privateKey;
    /** A second key pair, standing in for a token an attacker signed themselves. */
    private static RSAPublicKey  forgedPublicKey;
    private static RSAPrivateKey forgedPrivateKey;

    @BeforeAll
    static void setUp() throws Exception {
        vertx = Vertx.vertx();

        KeyPair real = generateKeyPair();
        publicKey  = (RSAPublicKey) real.getPublic();
        privateKey = (RSAPrivateKey) real.getPrivate();

        KeyPair forged = generateKeyPair();
        forgedPublicKey  = (RSAPublicKey) forged.getPublic();
        forgedPrivateKey = (RSAPrivateKey) forged.getPrivate();

        List<JsonObject> jwks = List.of(new JsonObject()
            .put("kty", "RSA").put("alg", "RS256").put("use", "sig").put("kid", KID)
            .put("n", base64Url(publicKey.getModulus()))
            .put("e", base64Url(publicKey.getPublicExponent())));

        JWTAuth jwtAuth = JwtAuthFactory.create(vertx, jwks, AUDIENCE);

        Router router = Router.router(vertx);

        // Stands in for a public endpoint with an optional-auth gate.
        router.get("/whoami").handler(ctx ->
            AuthTokenUtils.verifiedAuth0Id(ctx, jwtAuth)
                .onSuccess(id -> ctx.response().end(id == null ? "anonymous" : id))
                .onFailure(err -> ctx.response().setStatusCode(500).end("failed: " + err)));

        // Stands in for an endpoint whose security handler already ran and verified the token.
        router.get("/already-verified").handler(ctx -> {
            ctx.put("auth0Id", "auth0|from-security-handler");
            AuthTokenUtils.verifiedAuth0Id(ctx, jwtAuth)
                .onSuccess(id -> ctx.response().end(id == null ? "anonymous" : id));
        });

        // Stands in for a deployment where JWKS never loaded and no JWTAuth exists.
        router.get("/no-jwt-auth").handler(ctx ->
            AuthTokenUtils.verifiedAuth0Id(ctx, null)
                .onSuccess(id -> ctx.response().end(id == null ? "anonymous" : id)));

        CompletableFuture<Integer> started = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(router).listen(0)
            .onSuccess(server -> started.complete(server.actualPort()))
            .onFailure(started::completeExceptionally);
        port = started.get(10, TimeUnit.SECONDS);

        client = WebClient.create(vertx);
    }

    @AfterAll
    static void tearDown() {
        client.close();
        vertx.close();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Valid callers are identified
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void bearerHeader_withValidToken_yieldsTheSubject() throws Exception {
        assertEquals(SUBJECT, whoami("Authorization", "Bearer " + validToken()));
    }

    @Test
    void authTokenCookie_withValidToken_yieldsTheSubject() throws Exception {
        assertEquals(SUBJECT, whoami("Cookie", "auth_token=" + validToken()),
            "the site authenticates browsers with an HttpOnly cookie, not a header");
    }

    @Test
    void authTokenCookie_isFoundAmongOtherCookies() throws Exception {
        assertEquals(SUBJECT, whoami("Cookie",
            "theme=dark; auth_token=" + validToken() + "; consent=1"));
    }

    @Test
    void bearerHeaderWins_whenBothHeaderAndCookieArePresent() throws Exception {
        // Two credentials on one request: the header is what the security handler reads, so this
        // must agree with it rather than silently preferring the cookie.
        String valid = validToken();
        CompletableFuture<String> result = new CompletableFuture<>();
        client.get(port, "localhost", "/whoami")
            .putHeader("Authorization", "Bearer " + valid)
            .putHeader("Cookie", "auth_token=" + forgedToken())
            .send()
            .onSuccess(res -> result.complete(res.bodyAsString()))
            .onFailure(result::completeExceptionally);
        assertEquals(SUBJECT, result.get(10, TimeUnit.SECONDS));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Everything else is anonymous — never an error
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void noCredentials_isAnonymous() throws Exception {
        assertEquals("anonymous", whoami(null, null));
    }

    @Test
    void forgedToken_isAnonymous() throws Exception {
        assertEquals("anonymous", whoami("Authorization", "Bearer " + forgedToken()),
            "this is the whole point: a self-signed token must not unlock the gate");
    }

    @Test
    void tokenForAnotherApi_isAnonymous() throws Exception {
        String otherAudience = signed(privateKey, publicKey, "https://api.ratemymanager.com/",
                                      new Date(System.currentTimeMillis() + 3_600_000));
        assertEquals("anonymous", whoami("Authorization", "Bearer " + otherAudience),
            "the two sites share a tenant and a JWKS — only aud separates them");
    }

    @Test
    void expiredToken_isAnonymous() throws Exception {
        String expired = signed(privateKey, publicKey, AUDIENCE,
                                new Date(System.currentTimeMillis() - 60_000));
        assertEquals("anonymous", whoami("Authorization", "Bearer " + expired),
            "a stale cookie must degrade to signed-out, not break the page");
    }

    @Test
    void malformedToken_isAnonymous() throws Exception {
        assertEquals("anonymous", whoami("Authorization", "Bearer not-a-jwt"));
        assertEquals("anonymous", whoami("Cookie", "auth_token=%%%broken%%%"));
    }

    @Test
    void nonBearerAuthorizationScheme_isIgnored() throws Exception {
        assertEquals("anonymous", whoami("Authorization", "Basic dXNlcjpwYXNz"));
    }

    @Test
    void cookieHeaderWithoutAuthToken_isAnonymous() throws Exception {
        assertEquals("anonymous", whoami("Cookie", "theme=dark; consent=1"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Short-circuits
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void alreadyVerifiedSubject_isTrustedWithoutReverifying() throws Exception {
        // The security handler verified this token already; re-verifying would be pure overhead.
        CompletableFuture<String> result = new CompletableFuture<>();
        client.get(port, "localhost", "/already-verified").send()
            .onSuccess(res -> result.complete(res.bodyAsString()))
            .onFailure(result::completeExceptionally);
        assertEquals("auth0|from-security-handler", result.get(10, TimeUnit.SECONDS));
    }

    @Test
    void missingJwtAuth_isAnonymousRatherThanACrash() throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        client.get(port, "localhost", "/no-jwt-auth")
            .putHeader("Authorization", "Bearer " + validToken())
            .send()
            .onSuccess(res -> result.complete(res.bodyAsString()))
            .onFailure(result::completeExceptionally);
        assertEquals("anonymous", result.get(10, TimeUnit.SECONDS),
            "with no verifier configured, nobody is identified — the gate closes rather than opens");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private static String whoami(String header, String value) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        var request = client.get(port, "localhost", "/whoami");
        if (header != null) request.putHeader(header, value);
        request.send()
            .onSuccess(res -> result.complete(res.bodyAsString()))
            .onFailure(result::completeExceptionally);
        return result.get(10, TimeUnit.SECONDS);
    }

    private static String validToken() {
        return signed(privateKey, publicKey, AUDIENCE, new Date(System.currentTimeMillis() + 3_600_000));
    }

    private static String forgedToken() {
        return signed(forgedPrivateKey, forgedPublicKey, AUDIENCE,
                      new Date(System.currentTimeMillis() + 3_600_000));
    }

    private static String signed(RSAPrivateKey priv, RSAPublicKey pub, String audience, Date expiresAt) {
        return com.auth0.jwt.JWT.create()
            .withKeyId(KID)
            .withSubject(SUBJECT)
            .withAudience(audience)
            .withExpiresAt(expiresAt)
            .sign(Algorithm.RSA256(pub, priv));
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
