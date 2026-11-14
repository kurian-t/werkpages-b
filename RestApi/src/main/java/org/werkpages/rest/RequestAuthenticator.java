package org.werkpages.rest;

import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.RoutingContext;
import org.werkpages.rest.handlers.AuthTokenUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * THE ONE PLACE A REQUEST IS AUTHENTICATED.
 *
 * <p>This is the {@code bearerAuth} security handler every secured operation in
 * {@code openapi.yaml} runs through. It used to be a lambda in the middle of MainVerticle's boot
 * method, which made the most security-critical code in the product the only code no test could
 * reach: it could not be constructed without booting a verticle, a database, a JWKS fetch and an
 * OpenAPI router. Extracting it is what lets a test drive it over real HTTP and assert what it
 * actually does, which is the whole point of having it.
 *
 * <p>Its only output is {@code auth0Id} on the routing context. Nothing downstream looks at
 * {@code RoutingContext.user()} - authorization is decided by services that look the subject up in
 * the database, {@code requireAdminPublic} among them - so a credential authenticates a request by
 * establishing WHO is asking and nothing more. That is why a session can authenticate a request
 * without a JWT existing anywhere.
 *
 * <h2>The order of the three credentials, which matters</h2>
 *
 * <ol>
 *   <li><b>{@code X-Cron-Secret}</b>, compared in constant time. Unchanged, and first, because the
 *       scheduled jobs that use it hold no session and no token and would otherwise have to be
 *       given one. It identifies itself as {@code "cron"} rather than as any user.
 *   <li><b>The application session cookie.</b> Before the token, deliberately: the session is the
 *       credential that can be WITHDRAWN, so checking the token first would let a revoked session
 *       keep working for as long as its holder also kept an unexpired token - exactly the failure
 *       revocation exists to prevent.
 *   <li><b>The Auth0 id token</b>, from the {@code Authorization} header or the {@code auth_token}
 *       cookie. The legacy path, kept until the session has replaced it everywhere, and removed
 *       only then.
 * </ol>
 */
public final class RequestAuthenticator implements Handler<RoutingContext> {

    /**
     * Everything authentication needs from sessions, and nothing else.
     *
     * <p>Narrower than {@code SessionRepository} on purpose. The repository can mint, revoke,
     * list and sweep; authentication must do exactly one of those, and a type that says so cannot
     * grow a second responsibility by accident. It also makes the handler testable without a
     * database, which a concrete final class with a connection pool in its constructor is not.
     *
     * <p>In production this is {@code sessionRepo::touch}. There is no second implementation.
     */
    @FunctionalInterface
    public interface SessionLookup {
        io.vertx.core.Future<java.util.Optional<io.vertx.sqlclient.Row>> touch(String opaqueId);
    }

    private final String cronSecret;
    private final SessionLookup sessions;
    private final JWTAuth jwtAuth;

    /**
     * @param cronSecret null disables the cron credential entirely, which is correct when it is
     *                   not configured: a null secret must never be comparable to anything.
     * @param sessions   null disables session validation, for a deployment that has not wired it.
     * @param jwtAuth    the JWKS-backed verifier; null disables the legacy token path.
     */
    public RequestAuthenticator(String cronSecret, SessionLookup sessions, JWTAuth jwtAuth) {
        this.cronSecret = cronSecret;
        this.sessions   = sessions;
        this.jwtAuth    = jwtAuth;
    }

    @Override
    public void handle(RoutingContext ctx) {
        if (authenticatedAsCron(ctx)) {
            ctx.put("auth0Id", "cron");
            ctx.next();
            return;
        }

        String sessionId;
        try {
            sessionId = AuthTokenUtils.sessionCookieValue(ctx);
        } catch (IllegalStateException duplicated) {
            // Two cookies of the same name is cookie shadowing, not a mistake. Refuse rather than
            // pick one, because picking one means the attacker chooses which.
            ctx.fail(401);
            return;
        }
        if (sessionId != null) {
            authenticateSession(ctx, sessionId);
            return;
        }

        authenticateToken(ctx);
    }

    /**
     * Constant time, and only when a secret is configured.
     *
     * <p>{@code MessageDigest.isEqual} rather than {@code String.equals} because the latter
     * returns on the first differing byte, which leaks the length of the matching prefix to
     * anybody willing to time it.
     */
    private boolean authenticatedAsCron(RoutingContext ctx) {
        String presented = ctx.request().getHeader("X-Cron-Secret");
        if (cronSecret == null || presented == null) return false;
        return MessageDigest.isEqual(
            cronSecret.getBytes(StandardCharsets.UTF_8),
            presented.getBytes(StandardCharsets.UTF_8));
    }

    private void authenticateSession(RoutingContext ctx, String sessionId) {
        if (sessions == null) { ctx.fail(401); return; }
        sessions.touch(sessionId)
            .onSuccess(found -> {
                if (found.isPresent()) {
                    ctx.put("auth0Id",       found.get().getString("auth0_id"));
                    ctx.put("sessionUserId", found.get().getUUID("user_id"));
                    ctx.next();
                } else {
                    /*
                      Unknown, revoked, idle-expired, absolutely expired, or belonging to the other
                      product. The holder is told none of which, because there is nothing different
                      they could do about any of them, and the difference between "no such session"
                      and "that session was revoked" is information worth having only to somebody
                      testing stolen cookies.

                      Cleared on the way out so the browser stops presenting a dead session on
                      every subsequent request.
                    */
                    ctx.response().putHeader("Set-Cookie", AuthTokenUtils.clearedSessionCookie());
                    ctx.fail(401);
                }
            })
            .onFailure(err -> {
                // A database failure must not become an authentication success, and must not be
                // reported as a rejected credential either: 503 says the credential could not be
                // checked, which is the truth and is retryable.
                System.err.println("session validation failed: " + err.getMessage());
                ctx.fail(503);
            });
    }

    private void authenticateToken(RoutingContext ctx) {
        String token = AuthTokenUtils.extractToken(ctx);
        if (token == null || jwtAuth == null) {
            ctx.fail(401);
            return;
        }
        jwtAuth.authenticate(new JsonObject().put("token", token), res -> {
            if (res.failed()) {
                ctx.fail(401);
                return;
            }
            ctx.setUser(res.result());
            /*
              The subject is taken from the VERIFIED principal, not from a fresh unverified decode.

              It used to call JWT.decode on the raw string and swallow any exception, which meant a
              token whose signature verified but whose body would not re-parse left auth0Id unset
              and the request proceeding as authenticated-but-anonymous. Reading the principal the
              verifier produced cannot disagree with what was verified.
            */
            String subject = res.result().principal().getString("sub");
            if (subject == null || subject.isBlank()) {
                ctx.fail(401);
                return;
            }
            ctx.put("auth0Id", subject);
            ctx.next();
        });
    }
}
