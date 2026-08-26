package org.werkpages.rest;

import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RateMyManagers and Werkpages share one Auth0 tenant, which means they share a JWKS: a token
 * minted for the other site's API is signed by the same key and passes signature validation here.
 * The {@code aud} claim is the only thing separating the two, so these tests sign real RS256
 * tokens against a locally generated key and assert the boundary actually holds.
 *
 * <p>The array case matters most. When /authorize carries both an audience and the {@code openid}
 * scope, Auth0 returns {@code aud} as an array — API identifier plus the userinfo endpoint —
 * rather than a bare string. Vert.x has to match membership, not equality, or every social login
 * breaks in production while the string-shaped password logins keep working.
 */
class JwtAuthFactoryTest {

    private static final String THIS_API  = "https://api.werkpages.com/";
    private static final String OTHER_API = "https://api.ratemymanager.com/";
    private static final String USERINFO  = "https://ratemymanager.ca.auth0.com/userinfo";
    private static final String KID       = "test-key-1";

    private static Vertx vertx;
    private static RSAPublicKey publicKey;
    private static RSAPrivateKey privateKey;
    private static List<JsonObject> jwks;

    @BeforeAll
    static void setUp() throws Exception {
        vertx = Vertx.vertx();

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        publicKey  = (RSAPublicKey) pair.getPublic();
        privateKey = (RSAPrivateKey) pair.getPrivate();

        // Same JWK shape MainVerticle receives from /.well-known/jwks.json.
        jwks = List.of(new JsonObject()
            .put("kty", "RSA")
            .put("alg", "RS256")
            .put("use", "sig")
            .put("kid", KID)
            .put("n", base64Url(publicKey.getModulus()))
            .put("e", base64Url(publicKey.getPublicExponent())));
    }

    @AfterAll
    static void tearDown() {
        vertx.close();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void acceptsTokenIssuedForThisApi() throws Exception {
        assertTrue(authenticates(JwtAuthFactory.create(vertx, jwks, THIS_API), sign(THIS_API)),
            "a token whose aud is this API must be accepted");
    }

    @Test
    void rejectsTokenIssuedForTheOtherSitesApi() throws Exception {
        assertFalse(authenticates(JwtAuthFactory.create(vertx, jwks, THIS_API), sign(OTHER_API)),
            "a RateMyManagers token is signed by the shared tenant — only aud keeps it out");
    }

    @Test
    void acceptsAudienceArrayContainingThisApi() throws Exception {
        // What a social login actually carries: audience + openid scope yields an array.
        assertTrue(
            authenticates(JwtAuthFactory.create(vertx, jwks, THIS_API), sign(THIS_API, USERINFO)),
            "aud must be matched by membership, not equality, or social logins break");
    }

    @Test
    void rejectsAudienceArrayWithoutThisApi() throws Exception {
        assertFalse(
            authenticates(JwtAuthFactory.create(vertx, jwks, THIS_API), sign(OTHER_API, USERINFO)),
            "an array that omits this API must not be accepted");
    }

    @Test
    void rejectsTokenWithNoAudienceClaim() throws Exception {
        assertFalse(authenticates(JwtAuthFactory.create(vertx, jwks, THIS_API), sign()),
            "a token with no aud at all must not slip through");
    }

    @Test
    void rejectsExpiredTokenForThisApi() throws Exception {
        String expired = com.auth0.jwt.JWT.create()
            .withKeyId(KID)
            .withSubject("auth0|test-user")
            .withAudience(THIS_API)
            .withExpiresAt(new Date(System.currentTimeMillis() - 60_000))
            .sign(Algorithm.RSA256(publicKey, privateKey));

        assertFalse(authenticates(JwtAuthFactory.create(vertx, jwks, THIS_API), expired),
            "expiry must still be enforced alongside the audience check");
    }

    @Test
    void rejectsTokenSignedByAnotherKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair foreign = generator.generateKeyPair();

        String forged = com.auth0.jwt.JWT.create()
            .withKeyId(KID)
            .withSubject("auth0|attacker")
            .withAudience(THIS_API)
            .withExpiresAt(new Date(System.currentTimeMillis() + 3_600_000))
            .sign(Algorithm.RSA256((RSAPublicKey) foreign.getPublic(),
                                   (RSAPrivateKey) foreign.getPrivate()));

        assertFalse(authenticates(JwtAuthFactory.create(vertx, jwks, THIS_API), forged),
            "a correct aud must not rescue a token the tenant did not sign");
    }

    @Test
    void blankAudienceSkipsTheCheck() throws Exception {
        // Documents the deliberate fallback in JwtAuthFactory: an unset AUTH0_AUDIENCE logs a
        // warning and stops scoping tokens, rather than locking every user out on a bad deploy.
        assertTrue(authenticates(JwtAuthFactory.create(vertx, jwks, ""), sign(OTHER_API)),
            "a blank audience must disable the check, not silently reject everything");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Signs an RS256 token carrying the given audience values; none means no aud claim. */
    private static String sign(String... audiences) {
        JWTCreator.Builder builder = com.auth0.jwt.JWT.create()
            .withKeyId(KID)
            .withSubject("auth0|test-user")
            .withIssuer("https://ratemymanager.ca.auth0.com/")
            .withExpiresAt(new Date(System.currentTimeMillis() + 3_600_000));
        if (audiences.length > 0) {
            builder.withAudience(audiences);
        }
        return builder.sign(Algorithm.RSA256(publicKey, privateKey));
    }

    /** Runs the same authenticate() call MainVerticle's auth handler makes. */
    private static boolean authenticates(JWTAuth auth, String token) throws Exception {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        auth.authenticate(new JsonObject().put("token", token),
            ar -> result.complete(ar.succeeded()));
        return result.get(10, TimeUnit.SECONDS);
    }

    /** JWK modulus/exponent encoding: unsigned big-endian, base64url, no padding. */
    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        // BigInteger prepends a zero byte to keep the value positive; JWK wants it unsigned.
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
