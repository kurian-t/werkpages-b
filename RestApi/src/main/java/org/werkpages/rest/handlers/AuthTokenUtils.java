package org.werkpages.rest.handlers;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.RoutingContext;

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
final class AuthTokenUtils {

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
    static String extractToken(RoutingContext ctx) {
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
}
