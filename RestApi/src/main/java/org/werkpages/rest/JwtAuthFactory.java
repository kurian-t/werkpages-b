package org.werkpages.rest;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;

import java.util.List;

/**
 * Builds the {@link JWTAuth} that validates session cookies.
 *
 * <p>Signature validation alone stopped being sufficient once RateMyManagers and Werkpages began
 * sharing one Auth0 tenant: a shared tenant means a shared JWKS, so a token minted for the other
 * site's API validates here on signature. The {@code aud} claim is what scopes a token to this
 * API, so it is enforced.
 *
 * <p>The issuer is deliberately <em>not</em> checked. Password sign-in calls {@code /oauth/token}
 * on the raw tenant domain while social login goes through the Auth0 custom domain, so the two
 * paths carry different {@code iss} values, and {@link JWTOptions#setIssuer} accepts only one —
 * enforcing it would reject one of the two login paths. JWKS already pins validation to this
 * tenant's signing keys, so the issuer adds no boundary that is not already enforced.
 */
public final class JwtAuthFactory {

    private JwtAuthFactory() {}

    /**
     * @param jwks     the tenant's signing keys, fetched from /.well-known/jwks.json
     * @param audience this API's Auth0 identifier; when blank the audience check is skipped and a
     *                 warning is logged, so a misconfigured deploy is loud rather than silently open
     */
    public static JWTAuth create(Vertx vertx, List<JsonObject> jwks, String audience) {
        JWTOptions options = new JWTOptions();
        if (audience != null && !audience.isBlank()) {
            options.addAudience(audience);
        } else {
            System.err.println(
                "⚠ AUTH0_AUDIENCE is not set — accepting any token this tenant signed, "
                + "including tokens issued for another site's API.");
        }
        return JWTAuth.create(vertx, new JWTAuthOptions().setJwks(jwks).setJWTOptions(options));
    }
}
