package org.werkpages.rest.handlers;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.RoutingContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Caller identification on endpoints that permit anonymous access.
 *
 * <p>Several public endpoints gate part of their response on who is asking — {@code
 * categoryAverages} is withheld from users who have not contributed a review. The OpenAPI security
 * handler that normally populates {@code auth0Id} does not run on endpoints declared {@code
 * security: []}, so those endpoints have to identify the caller themselves.
 *
 * <p>Doing that by decoding the JWT without checking its signature would make the gate decorative:
 * a JWT is not encrypted, and anyone can mint one carrying any {@code sub} they like. Everything
 * here therefore verifies against the configured {@link JWTAuth} — the same JWKS-backed instance
 * the security handler uses — before returning a subject.
 */
public final class AuthTokenUtils {

    private AuthTokenUtils() {}

    /**
     * Resolves the verified caller, or null when the request is anonymous.
     *
     * <p>Never fails: an absent, malformed, expired, wrong-audience or forged token all resolve to
     * null, so the caller is simply treated as signed out. That is the correct behaviour for an
     * endpoint whose public half must keep working for everyone.
     */
    static Future<String> verifiedAuth0Id(RoutingContext ctx, JWTAuth jwtAuth) {
        // On endpoints that DO declare security, the bearerAuth handler already verified the token
        // and stashed the subject; re-verifying would be pure overhead.
        String alreadyVerified = ctx.get("auth0Id");
        if (alreadyVerified != null) return Future.succeededFuture(alreadyVerified);

        String token = extractToken(ctx);
        if (token == null || jwtAuth == null) return Future.succeededFuture(null);

        Promise<String> promise = Promise.promise();
        jwtAuth.authenticate(new JsonObject().put("token", token))
            .onSuccess(user -> promise.complete(user.principal().getString("sub")))
            .onFailure(err -> promise.complete(null));
        return promise.future();
    }

    /** Bearer header first, then the HttpOnly auth_token cookie — matching the security handler. */
    public static String extractToken(RoutingContext ctx) {
        String authHeader = ctx.request().getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring("Bearer ".length());
        }
        String cookieHeader = ctx.request().getHeader("Cookie");
        if (cookieHeader != null) {
            for (String part : cookieHeader.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith("auth_token=")) {
                    return trimmed.substring("auth_token=".length());
                }
            }
        }
        return null;
    }

    /**
     * The session cookie, built in ONE place.
     *
     * <p>This string carries three security properties that nothing near the endpoints can see,
     * and it used to be written out twice - once on the password sign-in path and once on the
     * social one. Two copies of a security decision are two copies that can drift, and the one
     * that drifted would be the one nobody tested.
     *
     * <ul>
     *   <li><b>HttpOnly</b> so script cannot read the session, which is what stops XSS turning
     *       into account theft.
     *   <li><b>SameSite=Lax</b> so a browser never attaches it to a cross-site PUT or DELETE.
     *       This is the first and strongest of the three CSRF layers, and the reason the draft
     *       routes need no CSRF token of their own.
     *   <li><b>no Domain</b> so the cookie is host-only and no sibling subdomain can present it.
     * </ul>
     *
     * <p>{@code Secure} is added in production only, because local development is plain http and
     * a Secure cookie would simply never be sent.
     */
    static String sessionCookie(String token, boolean isProduction) {
        return "auth_token=" + token
             + "; HttpOnly; Path=/; Max-Age=86400; SameSite=Lax"
             + (isProduction ? "; Secure" : "");
    }


    // The signed stateless pre-auth cookie that used to live here is GONE, deliberately.
    //
    // It could not be single-use: clearing a cookie is an instruction to the browser, not a
    // guarantee, and a captured value stayed valid until it expired. It also broke multi-tab
    // logins, because a second /start overwrote the first tab's cookie. Worst of all, consuming
    // it proved only that it had been used once, never WHICH browser used it, so login CSRF
    // stayed open.
    //
    // The verifier and the state now live in oauth_login_transactions, consumed atomically.
    // See OAuthTransactionRepository. Do not reintroduce a signing key here: nothing in the new
    // design needs one, which is also why the endpoint no longer fails when HMAC_KEY is unset.


    // ── The application session cookie ───────────────────────────────────────

    /**
     * The opaque application session, replacing the Auth0 id token as the thing a browser holds.
     *
     * <p>Why opaque rather than a JWT: a JWT cannot be withdrawn. Once issued it is valid until it
     * expires, so "sign out everywhere", "this account is compromised" and "an administrator has
     * suspended you" are all unimplementable - the best a server can do is ask the browser to
     * forget it, which an attacker who already has the token will not do. The session here is a
     * random 256-bit value whose SHA-256 is the primary key of {@code app_sessions}, so revocation
     * is one UPDATE and takes effect on the victim's very next request.
     *
     * <p>The name is product-scoped. WerkPages and RateMyManagers are separate origins in
     * production but the SAME origin in local development - {@code localhost} differing only by
     * port, and ports do not separate cookie jars. A single name would mean signing into one
     * product silently signed you out of the other on every developer's machine, and the sessions
     * themselves are product-scoped in the database, so a shared name would also hand each product
     * a cookie the other's {@code touch} refuses.
     *
     * <p>{@code __Host-} is required for the same reasons set out on {@link #BROWSER_COOKIE}: it
     * is the only browser-enforced defence against a sibling subdomain planting a cookie the apex
     * will receive. It works on {@code http://localhost}, which browsers treat as a trustworthy
     * origin.
     */
    public static final String SESSION_COOKIE = "__Host-wp_session";

    /**
     * {@code Max-Age} matches the IDLE lifetime, not the absolute one.
     *
     * <p>The database is the authority on both clocks; this number only decides when the browser
     * stops bothering to send the cookie. Setting it to the absolute lifetime would have browsers
     * presenting sessions that went idle-stale 76 days earlier, every one of them a pointless
     * round trip that can only end in a 401.
     */
    static final long SESSION_COOKIE_MAX_AGE =
        java.time.Duration.ofDays(14).toSeconds();

    public static String appSessionCookie(String opaqueId) {
        // Secure unconditionally: the __Host- prefix requires it and localhost accepts it, so
        // unlike auth_token there is no development exception to make here.
        return SESSION_COOKIE + "=" + opaqueId
             + "; HttpOnly; Secure; Path=/; Max-Age=" + SESSION_COOKIE_MAX_AGE + "; SameSite=Lax";
    }

    /**
     * The cookie that ends a session in the browser.
     *
     * <p>{@code Max-Age=0} rather than an absent value: an empty cookie with no age is still a
     * cookie, and some browsers keep it for the session. Every attribute must match the cookie
     * being replaced or the browser treats it as a different one and the original survives.
     *
     * <p>This is housekeeping, not the revocation. The revocation happened in the database before
     * this header was written, which is what makes sign-out real even when the browser ignores it.
     */
    /**
     * The session cookie's value, or null when the request carries none.
     *
     * @throws IllegalStateException when the cookie appears more than once, which is cookie
     *         shadowing rather than an accident; see {@link #soleCookieValue}.
     */
    public static String sessionCookieValue(RoutingContext ctx) {
        return soleCookieValue(ctx, SESSION_COOKIE);
    }

    public static String clearedSessionCookie() {
        return SESSION_COOKIE + "=; HttpOnly; Secure; Path=/; Max-Age=0; SameSite=Lax";
    }

    /**
     * Whether application sessions are issued.
     *
     * <p>Off by default, on purpose. Validation is live unconditionally - a session nobody holds
     * validates for nobody, and leaving the check in place means the flag cannot be flipped into a
     * half-built state. What the flag gates is ISSUANCE, so until an operator turns it on the
     * login flow is byte for byte what it is today.
     *
     * <p>Read per call rather than cached, so an integration test can set it without a restart.
     */
    public static boolean appSessionsEnabled() {
        return "true".equalsIgnoreCase(System.getenv("APP_SESSIONS_ENABLED"))
            || "true".equalsIgnoreCase(System.getProperty("app.sessions.enabled"));
    }

    /**
     * {@code returnTo} ends up in a redirect, so it is an open-redirect vector.
     *
     * <p>Accepted only as a site-relative path: one leading slash and not two. {@code //evil.test}
     * is a protocol-relative URL that browsers treat as absolute, which is the form that catches
     * people out; a backslash is normalised to a slash by some browsers so it is refused too; and
     * a newline would allow header splitting.
     *
     * <p>Validated on the way IN, at /api/auth/start, so an open redirect can never be stored in a
     * login transaction and replayed later.
     */
    static String safeReturnTo(String candidate, String fallback) {
        if (candidate == null || candidate.isBlank()) return fallback;
        if (!candidate.startsWith("/"))               return fallback;
        if (candidate.startsWith("//"))               return fallback;
        if (candidate.startsWith("/\\"))              return fallback;
        if (candidate.contains("\n") || candidate.contains("\r")) return fallback;
        return candidate;
    }

    // ── The browser-binding cookie ────────────────────────────────────────────

    /**
     * {@code __Host-} is load-bearing, not decoration.
     *
     * <p>A browser REFUSES to store a cookie with this prefix unless all of: {@code Secure} is
     * set, {@code Path=/}, and there is no {@code Domain} attribute. Those three together are what
     * stop a sibling subdomain such as {@code evil.example.com} planting a cookie the apex will
     * receive, and stop a second cookie of the same name being created at a narrower path. It is
     * the only browser-enforced defence against cookie planting that exists.
     *
     * <p>An earlier draft used {@code Path=/api/auth} so the cookie would not ride on every
     * request. That is incompatible with the prefix, and the bandwidth is worth far less than the
     * defence, so {@code Path=/} it is.
     *
     * <p>It works on {@code http://localhost}: browsers treat localhost as a trustworthy origin,
     * so a {@code Secure} cookie is accepted there and development is unaffected.
     */
    static final String BROWSER_COOKIE = "__Host-auth_tx";

    /** Ten minutes, refreshed by each start. Long enough for a login, short enough to bound a theft. */
    static final long BROWSER_COOKIE_MAX_AGE = 600;

    static String browserCookie(String secret, boolean isProduction) {
        // Secure unconditionally: the prefix requires it, and localhost accepts it.
        return BROWSER_COOKIE + "=" + secret
             + "; HttpOnly; Secure; Path=/; Max-Age=" + BROWSER_COOKIE_MAX_AGE + "; SameSite=Lax";
    }

    /**
     * The cookie's value, or null — and a REFUSAL when the name appears more than once.
     *
     * <p>Cookie shadowing works precisely because servers take the first match: an attacker who
     * can get a second cookie of that name into the jar then chooses which value the server reads.
     * {@code __Host-} is supposed to make that impossible, but a prefix is enforced by the browser
     * and the browser is the attacker's machine, so this does not rely on it alone.
     *
     * <p>Throws rather than returning null, because the two cases are not the same and must not be
     * handled the same way by accident: no cookie is an ordinary signed-out request, two cookies is
     * an attack in progress.
     *
     * @throws IllegalStateException when the name occurs more than once
     */
    public static String soleCookieValue(RoutingContext ctx, String name) {
        String header = ctx.request().getHeader("Cookie");
        if (header == null) return null;
        String prefix = name + "=";
        String found = null;
        for (String part : header.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.startsWith(prefix)) continue;
            if (found != null) {
                throw new IllegalStateException("duplicate cookie: " + name);
            }
            found = trimmed.substring(prefix.length());
        }
        return found;
    }

}
