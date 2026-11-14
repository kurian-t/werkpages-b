package org.werkpages.rest;

/**
 * Which browser origins may call this API.
 *
 * <p>Its own class, and public, because {@code MainVerticle} needs it and a test needs it, and the
 * alternative was widening the visibility of a security utility that nothing outside its package
 * should reach. One definition either way: an allowlist written out twice is an allowlist that can
 * drift, and the copy that drifted would be the one nobody tested.
 */
public final class ApiOrigins {

    private ApiOrigins() {}

    /**
     * The CORS allowlist, built in ONE place, as a regex of exact origins.
     *
     * <p>Vert.x full-matches this against the {@code Origin} header, so a lookalike such as
     * {@code https://werkpages.com.evil.example} does not match and is refused with 403 before any
     * handler runs. The dots are escaped deliberately: unescaped, {@code .} would match any
     * character and {@code https://werkpagesXcom} would be allowed in.
     */
    public static String allowedOriginPattern(boolean isProduction) {
        return isProduction
            ? "https://werkpages\\.com|https://www\\.werkpages\\.com"
            : "http://localhost:8081";
    }

    private static final String PROD_ORIGIN = "https://werkpages.com";
    private static final String DEV_ORIGIN  = "http://localhost:8081";

    /**
     * The ONE canonical browser origin of this product's app, for building a redirect_uri.
     *
     * <p>Server-side and never taken from the request. {@code redirect_uri} is what Auth0 sends
     * the authorization code back to, so a client-supplied one is an invitation to have codes
     * delivered somewhere else. The old flow built it in the browser from
     * {@code window.location.origin}, which was safe only because Auth0's own allowlist was doing
     * the work; this removes the question entirely.
     *
     * <p>Deliberately the bare apex and not the {@code www} alias, because Auth0 matches
     * redirect URIs exactly and one of the two would always be the one nobody registered.
     */
    public static String appOrigin(boolean isProduction) {
        return isProduction ? PROD_ORIGIN : DEV_ORIGIN;
    }

    /*
      The per-product callback allowlist that used to live here is GONE.

      It existed for one reason: during the rollout an older bundle still sent its own
      redirect_uri, and exchanging a code against an arbitrary URI is an invitation to have codes
      delivered somewhere else. Nothing reaches the exchange without a consumed transaction now,
      and every one of those was authorized against appOrigin(), so the body's copy is never read
      and there is nothing left to allowlist.
    */
}
