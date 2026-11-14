package org.werkpages.rest;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

/**
 * Establishes WHO is signing in, by checking rather than by trusting.
 *
 * <h2>What this replaces</h2>
 *
 * <p>{@code AuthHandler} used to do:
 *
 * <pre>{@code
 * auth0Id = com.auth0.jwt.JWT.decode(idToken != null ? idToken : accessToken).getSubject();
 * }</pre>
 *
 * <p>{@code JWT.decode} parses; it does not verify. No signature, no issuer, no audience, no
 * expiry. The email then came from a separate {@code /userinfo} call and the two subjects were
 * never compared. Account lookup, account creation and the session cookie all hung off a value
 * nobody had checked.
 *
 * <h2>No cryptography is implemented here</h2>
 *
 * <p>Signature verification, JWKS key selection, {@code exp} and {@code nbf} are all done by
 * {@link JWTAuth}, the maintained Vert.x module this stack already uses for session cookies. What
 * this class adds is the OIDC checks that live above the signature: the exact issuer, the audience
 * scoped to THIS client, {@code azp} for multi-audience tokens, and a sanity bound on {@code iat}.
 *
 * <h2>Why a second validator, and not the session one</h2>
 *
 * <p>{@link JwtAuthFactory} builds the validator for session cookies, whose {@code aud} is the
 * API identifier. An ID token's {@code aud} is the <b>client id</b> instead, so the two cannot
 * share an instance: configuring one to accept the other's audience would widen both.
 *
 * <p>{@link JwtAuthFactory} also deliberately does not enforce the issuer, because the password
 * and social paths historically reached different Auth0 domains. That reasoning does not apply
 * here: an ID token on this path always comes from the one domain the exchange calls, so the
 * issuer is enforced, exactly.
 *
 * <h2>Nonce</h2>
 *
 * <p>Not checked, because the authorization request does not send one. RFC 9700 accepts PKCE or
 * {@code nonce} as alternatives for code-injection protection and this flow uses PKCE, bound to a
 * server-side transaction. If a {@code nonce} is ever added to the authorize request it must be
 * stored on the transaction and compared here; a claim nobody checks is worse than no claim.
 */
public final class IdTokenValidator {

    /**
     * Tolerance for clock skew between this host and Auth0, in seconds.
     *
     * <p>Applies to both ends. Without it a host a few seconds fast rejects freshly minted tokens
     * as issued in the future, which presents as intermittent sign-in failure that nobody can
     * reproduce.
     */
    private static final long CLOCK_SKEW_SECONDS = 60;

    private final JWTAuth jwtAuth;
    private final String clientId;

    private IdTokenValidator(JWTAuth jwtAuth, String clientId) {
        this.jwtAuth  = jwtAuth;
        this.clientId = clientId;
    }

    /**
     * @param jwks     the tenant's signing keys, the same list the session validator is built from
     * @param clientId this application's Auth0 client id, which an ID token's {@code aud} must be
     * @param issuer   the exact expected {@code iss}, normally {@code https://<domain>/}
     */
    public static IdTokenValidator create(Vertx vertx, List<JsonObject> jwks,
                                          String clientId, String issuer) {
        JWTOptions options = new JWTOptions()
            .setLeeway((int) CLOCK_SKEW_SECONDS)
            .setIssuer(issuer)
            .addAudience(clientId);
        return new IdTokenValidator(
            JWTAuth.create(vertx, new JWTAuthOptions().setJwks(jwks).setJWTOptions(options)),
            clientId);
    }

    /**
     * The subject of a fully validated ID token, or a failed future.
     *
     * <p>Fails for every defect identically. The caller turns that into one refusal, because the
     * person can do nothing different about any of them and telling an attacker which check
     * rejected their forgery helps only the attacker.
     */
    public Future<String> verifiedSubject(String idToken) {
        return verifiedClaims(idToken).map(claims -> claims.getString("sub"));
    }

    /**
     * Every claim of a fully validated ID token, for the caller that needs more than the subject.
     *
     * <p>Added for {@code email_verified}. The alternative was for the callback to decode the
     * token a second time to read one claim, which is how an unverified decode gets reintroduced
     * next to a verified one: the two calls look alike, and only one of them checks anything.
     * There is one validation, and it hands back everything it validated.
     */
    public Future<JsonObject> verifiedClaims(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            return Future.failedFuture(new IllegalArgumentException("no id_token"));
        }
        return jwtAuth.authenticate(new JsonObject().put("token", idToken))
            .compose(user -> {
                JsonObject claims = user.principal();

                // AZP, for a token that names more than one audience.
                //
                // Checking only that our client id appears somewhere in `aud` would accept a token
                // minted FOR a different party that happens to list us too. `azp` names the client
                // the token was actually issued to, and OIDC requires it be checked whenever the
                // audience is plural.
                Object aud = claims.getValue("aud");
                if (aud instanceof JsonArray audiences && audiences.size() > 1) {
                    String azp = claims.getString("azp");
                    if (!clientId.equals(azp)) {
                        return Future.failedFuture(new IllegalStateException(
                            "id_token has multiple audiences and is not authorized to this client"));
                    }
                }

                // IAT in the future.
                //
                // Vert.x enforces `exp` and `nbf`; a token claiming to have been issued later than
                // now is either a clock problem or a forgery, and accepting one would let a token
                // be minted with an effectively unbounded life ahead of its expiry window.
                Long issuedAt = claims.getLong("iat");
                if (issuedAt != null
                        && issuedAt > Instant.now().getEpochSecond() + CLOCK_SKEW_SECONDS) {
                    return Future.failedFuture(
                        new IllegalStateException("id_token was issued in the future"));
                }

                String subject = claims.getString("sub");
                if (subject == null || subject.isBlank()) {
                    return Future.failedFuture(
                        new IllegalStateException("id_token carries no subject"));
                }
                return Future.succeededFuture(claims);
            });
    }

    /**
     * Whether a validated token says the address has been verified.
     *
     * <h2>Why this is a policy question and not a getter</h2>
     *
     * <p><b>Absent is unverified.</b> A missing claim is not consent, and treating silence as
     * verification is how a check like this gets quietly lost: a provider stops sending it, nobody
     * notices, and the requirement evaporates without anything failing.
     *
     * <p><b>A JSON boolean, and nothing else.</b> OpenID Connect Core section 5.1 defines
     * {@code email_verified} as a boolean, so {@code true} is the only accepted value: not the
     * string {@code "true"}, not {@code 1}, not {@code "yes"}.
     *
     * <p>An earlier version of this method accepted the string form, on the reasoning that some
     * upstream provider might send it and refusing would be a lockout caused by a type rather
     * than by a policy. That was wrong, and the reason is worth keeping: <b>it was a
     * compatibility exception with no evidence behind it.</b> No connection had been observed
     * sending a string; the exception widened what counts as verified to cover a provider nobody
     * had checked. If a real connection turns out to return a non-standard representation, the
     * fix is its Auth0 attribute mapping, investigated and reviewed, not a quiet widening here.
     *
     * <p><b>What this cannot tell you</b> is how each connection behaves in the real tenant.
     * Database connections set it false until the person follows the link; Google asserts the
     * address itself; and this product's Microsoft connection is {@code windowslive}, the personal
     * account social connection rather than an Entra enterprise one, whose claim behaviour has to
     * be observed rather than assumed. On this policy an absent claim is refused, so every enabled
     * connection has to be walked through a test tenant before this reaches production, or the
     * defence becomes an outage.
     */
    public static boolean emailIsVerified(JsonObject claims) {
        if (claims == null) return false;
        return Boolean.TRUE.equals(claims.getValue("email_verified"));
    }

    /**
     * Whether the token positively says the address is NOT verified.
     *
     * <p>Distinct from {@code !emailIsVerified(...)}, and the distinction carries the whole
     * policy. "The provider says unverified" is a refusal on the spot. "The token says nothing
     * about it" is not an answer yet, and the answer is then {@link #profileSaysVerified}: a
     * provider that omits the claim from the id_token may still assert it on {@code /userinfo},
     * and refusing before asking would lock out that connection for a claim it does supply.
     *
     * <p>A present non-boolean lands here, so a string {@code "true"} is a refusal rather than a
     * deferral. Something sent a value in a shape the specification does not define, and widening
     * on a guess about what it meant is exactly the exception that was removed from this class.
     */
    public static boolean assertsUnverified(JsonObject claims) {
        if (claims == null) return false;
        Object value = claims.getValue("email_verified");
        return value != null && !Boolean.TRUE.equals(value);
    }

    /**
     * Whether {@code /userinfo} itself says the address it returned is verified.
     *
     * <h2>Why this exists, and why the subject alone was not enough</h2>
     *
     * <p>When the id_token carries no {@code email} claim, its {@code email_verified} applies to
     * no named address: the flag is true of something the token never identified. The address the
     * account is then created with comes from {@code /userinfo}, and reconciling the SUBJECT
     * establishes only that both responses concern the same Auth0 identity. It does not establish
     * that this particular mailbox was ever verified.
     *
     * <p>So the profile has to say so itself. The response is fetched with the access token from
     * the same client-authenticated exchange, over TLS, from the configured tenant, and its
     * subject has already been reconciled with the validated token, which is what makes it a
     * trustworthy source rather than another unverified input.
     *
     * <p>Boolean {@code true} and nothing else, for the same reason as above.
     */
    public static boolean profileSaysVerified(JsonObject profile) {
        if (profile == null) return false;
        return Boolean.TRUE.equals(profile.getValue("email_verified"));
    }

    /**
     * The {@code email} the validated token asserts, or null when it carries none.
     *
     * <p>Exists so the caller can check that the address it is about to create or update an
     * account with is the one whose verification status it just trusted. Two sources of identity
     * that are never compared are two chances to be wrong about whose account is being opened,
     * which is the same reasoning as {@link #requireSameSubject} and the same defect shape: the
     * subject came from the token, the email came from {@code /userinfo}, and nothing established
     * that the verified flag applied to the address actually used.
     */
    public static String assertedEmail(JsonObject claims) {
        if (claims == null) return null;
        String email = claims.getString("email");
        return email == null || email.isBlank() ? null : email.trim();
    }

    /**
     * Whether two addresses describe the same mailbox, for the comparison above.
     *
     * <p>Case-insensitive on the whole address. The local part is technically case-sensitive per
     * RFC 5321, and no provider this product talks to treats it that way; a refusal on case would
     * be a lockout, not a defence.
     */
    public static boolean sameEmail(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    /**
     * The connection an Auth0 subject came from: the part before the first {@code |}.
     *
     * <p>For logging a refusal usefully. {@code auth0}, {@code google-oauth2} and
     * {@code windowslive} say which policy refused somebody; the identifier after the separator is
     * the person and is never logged.
     */
    public static String connectionOf(String subject) {
        if (subject == null) return "unknown";
        int bar = subject.indexOf('|');
        return bar > 0 ? subject.substring(0, bar) : "unknown";
    }

    /**
     * The {@code /userinfo} response must describe the same person as the validated token.
     *
     * <p>Two sources of identity that are never compared are two chances to be wrong about whose
     * account is being opened. This has to pass BEFORE any account is found, created or linked and
     * before any session cookie is issued, which is why it returns a failure rather than a boolean
     * the caller might forget to read.
     *
     * <p>Absent is a failure, not agreement: a {@code /userinfo} response with no subject tells us
     * nothing, and treating silence as a match is how this kind of check gets quietly lost.
     */
    public static Future<Void> requireSameSubject(String validatedSubject, String userInfoSubject) {
        if (validatedSubject == null || validatedSubject.isBlank()) {
            return Future.failedFuture(new IllegalStateException("no validated subject"));
        }
        if (userInfoSubject == null || userInfoSubject.isBlank()) {
            return Future.failedFuture(new IllegalStateException("/userinfo carried no subject"));
        }
        // Constant time. The values are not secret, but the habit is: a comparison that returns
        // early is the one somebody later copies somewhere it does matter.
        if (!MessageDigest.isEqual(validatedSubject.getBytes(StandardCharsets.UTF_8),
                                   userInfoSubject.getBytes(StandardCharsets.UTF_8))) {
            return Future.failedFuture(new IllegalStateException(
                "/userinfo subject does not match the validated id_token subject"));
        }
        return Future.succeededFuture();
    }
}
