package org.werkpages.rest;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STAGE 2: the ID token must be VALIDATED, and the identity it carries reconciled with /userinfo.
 *
 * <h2>What was broken, and how it was observed</h2>
 *
 * <p>{@code AuthHandler} established the account identity like this:
 *
 * <pre>{@code
 * auth0Id = com.auth0.jwt.JWT.decode(idToken != null ? idToken : accessToken).getSubject();
 * }</pre>
 *
 * <p>{@code JWT.decode} parses. It does not verify. No signature, no issuer, no audience, no
 * expiry. The email then came from a separate {@code /userinfo} call, and a grep of both products
 * for {@code info.getString("sub")} returned nothing, so the two sources of identity were never
 * compared. The account lookup, the account creation and the session cookie all hung off a value
 * nobody had checked.
 *
 * <p>The mitigation in place was indirect and is an argument about transport rather than a check:
 * the token arrived straight from Auth0 over TLS in response to a client-authenticated request.
 * That is not nothing, but it is not validation, and it sits on the path that creates accounts.
 *
 * <h2>Why these tests are all refusals</h2>
 *
 * <p>A validator is only ever observed by what it REJECTS. Every assertion here is a forged,
 * mis-scoped or stale token that must not produce a subject. A suite of valid tokens passes
 * equally well against {@code JWT.decode}, which is precisely how this survived.
 *
 * <p>Signing keys are generated in-process and the tokens are minted here, so nothing contacts
 * Auth0 or any other metered service.
 */
class IdTokenValidationTest {

    private static final String CLIENT_ID = "test-client-id";
    private static final String ISSUER    = "https://tenant.example.auth0.com/";
    private static final String SUBJECT   = "auth0|the-real-person";
    private static final String KID       = "test-key-1";

    static Vertx vertx;
    static KeyPair trusted;
    static KeyPair attacker;
    static IdTokenValidator validator;

    @BeforeAll
    static void setUpAll() throws Exception {
        vertx = Vertx.vertx();
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        trusted  = gen.generateKeyPair();
        attacker = gen.generateKeyPair();
        validator = IdTokenValidator.create(
            vertx, List.of(jwk((RSAPublicKey) trusted.getPublic(), KID)), CLIENT_ID, ISSUER);
    }

    @AfterAll
    static void tearDownAll() {
        if (vertx != null) vertx.close();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** The public half of a key pair as a JWKS entry, the shape Auth0 publishes. */
    private static JsonObject jwk(RSAPublicKey pub, String kid) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return new JsonObject()
            .put("kty", "RSA").put("alg", "RS256").put("use", "sig").put("kid", kid)
            .put("n", b64.encodeToString(unsigned(pub.getModulus())))
            .put("e", b64.encodeToString(unsigned(pub.getPublicExponent())));
    }

    /** BigInteger.toByteArray() prepends a sign byte; JWKS wants the magnitude alone. */
    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }


    /** A token with every claim correct, which individual tests then spoil one at a time. */
    private static com.auth0.jwt.JWTCreator.Builder goodToken() {
        Instant now = Instant.now();
        return JWT.create()
            .withKeyId(KID)
            .withIssuer(ISSUER)
            .withAudience(CLIENT_ID)
            .withSubject(SUBJECT)
            .withIssuedAt(Date.from(now.minusSeconds(5)))
            .withExpiresAt(Date.from(now.plusSeconds(300)));
    }

    private static String signedBy(com.auth0.jwt.JWTCreator.Builder builder, KeyPair pair) {
        return builder.sign(Algorithm.RSA256((RSAPublicKey) pair.getPublic(),
                                             (RSAPrivateKey) pair.getPrivate()));
    }

    private static String subjectOf(String token) {
        try {
            return validator.verifiedSubject(token)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private static void assertRefused(String token, String why) {
        assertNull(subjectOf(token), why);
    }

    // ── the one acceptance, so the refusals mean something ────────────────────

    @Test
    void aProperlySignedAndScopedTokenYieldsItsSubject() {
        assertEquals(SUBJECT, subjectOf(signedBy(goodToken(), trusted)),
            "a token this tenant signed, for this client, unexpired, must be accepted");
    }

    // ── signature ─────────────────────────────────────────────────────────────

    @Test
    void aTokenSignedByTheWrongKeyIsRefused() {
        /*
          THE CENTRAL ONE. An attacker can mint a JWT claiming any subject they like, because a
          JWT is not encrypted and anyone can write one. The only thing that distinguishes a real
          token from an invented one is the signature against the tenant's published keys.

          Against JWT.decode this token is indistinguishable from a genuine one, and it would have
          created or logged into an account for whatever subject it named.
        */
        assertRefused(signedBy(goodToken().withSubject("auth0|attacker"), attacker),
            "a token signed by a key outside the JWKS must never yield a subject");
    }

    @Test
    void anUnsignedTokenIsRefused() {
        // alg=none. The oldest JWT attack there is, and decode() is perfectly happy with it.
        assertRefused(goodToken().sign(Algorithm.none()),
            "an unsigned token must be refused");
    }

    @Test
    void aMalformedTokenIsRefused() {
        assertRefused("not.a.jwt", "garbage must be refused, not parsed into an identity");
        assertRefused("", "an empty token must be refused");
    }

    // ── scope: issuer and audience ────────────────────────────────────────────

    @Test
    void aTokenFromAnotherIssuerIsRefused() {
        assertRefused(signedBy(goodToken().withIssuer("https://evil.example.com/"), trusted),
            "the issuer must be the exact expected one");
    }

    @Test
    void aTokenForAnotherClientIsRefused() {
        /*
          Werkpages and RateMyManagers share one Auth0 tenant, so they share a JWKS. A token minted
          for the other product's client validates on SIGNATURE here. The audience is what scopes a
          token to this client, which is why it cannot be skipped.
        */
        assertRefused(signedBy(goodToken().withAudience("some-other-client"), trusted),
            "a token issued for another client of the same tenant must be refused");
    }

    @Test
    void aMultiAudienceTokenWithTheWrongAuthorizedPartyIsRefused() {
        /*
          OIDC: when a token carries several audiences, azp names the client it was actually issued
          to. Checking only that our client id appears SOMEWHERE in aud would accept a token minted
          for a different party that happens to list us too.
        */
        assertRefused(
            signedBy(goodToken().withAudience(CLIENT_ID, "another-api").withClaim("azp", "someone-else"),
                     trusted),
            "a multi-audience token whose azp is another client must be refused");
    }

    @Test
    void aMultiAudienceTokenAuthorizedToUsIsAccepted() {
        assertEquals(SUBJECT, subjectOf(
            signedBy(goodToken().withAudience(CLIENT_ID, "another-api").withClaim("azp", CLIENT_ID),
                     trusted)),
            "a multi-audience token issued to this client is legitimate");
    }

    // ── time ──────────────────────────────────────────────────────────────────

    @Test
    void anExpiredTokenIsRefused() {
        Instant past = Instant.now().minusSeconds(3600);
        assertRefused(signedBy(goodToken()
                .withIssuedAt(Date.from(past.minusSeconds(60)))
                .withExpiresAt(Date.from(past)), trusted),
            "an expired token must be refused however well signed");
    }

    @Test
    void aTokenIssuedInTheFutureIsRefused() {
        Instant future = Instant.now().plusSeconds(3600);
        assertRefused(signedBy(goodToken()
                .withIssuedAt(Date.from(future))
                .withExpiresAt(Date.from(future.plusSeconds(300))), trusted),
            "a token claiming to be issued in the future is either a clock problem or a forgery");
    }

    // ── identity reconciliation ───────────────────────────────────────────────

    @Test
    void aUserInfoSubjectThatDisagreesWithTheTokenIsRefused() {
        /*
          The identity came from the token and the email came from /userinfo, and nothing ever
          checked they described the same person. Two sources of identity that are never compared
          are two chances to be wrong about whose account is being opened.
        */
        assertTrue(await(IdTokenValidator.requireSameSubject(SUBJECT, "auth0|somebody-else")).failed(),
            "a /userinfo subject that differs from the validated token subject must fail");
    }

    @Test
    void anAbsentUserInfoSubjectIsRefused() {
        assertTrue(await(IdTokenValidator.requireSameSubject(SUBJECT, null)).failed(),
            "a missing subject must not be treated as agreement");
        assertTrue(await(IdTokenValidator.requireSameSubject(SUBJECT, "")).failed(),
            "an empty subject must not be treated as agreement");
    }

    @Test
    void matchingSubjectsAreAccepted() {
        assertTrue(await(IdTokenValidator.requireSameSubject(SUBJECT, SUBJECT)).succeeded(),
            "the ordinary case must still work");
    }

    private static Future<Void> await(Future<Void> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // The assertions inspect the Future's state, so a failure here is a result, not an error.
        }
        return future;
    }
}
