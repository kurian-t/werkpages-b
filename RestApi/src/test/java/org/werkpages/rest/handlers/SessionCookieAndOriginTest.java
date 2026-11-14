package org.werkpages.rest.handlers;

import org.junit.jupiter.api.Test;
import org.werkpages.rest.ApiOrigins;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The CSRF controls, pinned.
 *
 * <h2>Why these exist, when there is no CSRF token anywhere in this application</h2>
 *
 * <p>A state-changing endpoint authenticated by a cookie needs CSRF protection. This API has it,
 * through three layers that are invisible from any endpoint's own code:
 *
 * <ol>
 *   <li>the session cookie is {@code SameSite=Lax}, so a browser never attaches it to a cross-site
 *       PUT or DELETE;
 *   <li>the CORS handler refuses a disallowed {@code Origin} with 403 before any handler runs;
 *   <li>the OpenAPI contract rejects every content type an HTML form can send with 415.
 * </ol>
 *
 * <p>Because none of that is visible where the endpoints are written, somebody could loosen any of
 * it without noticing what they had removed. These tests fail if they do. The third layer is
 * asserted at the HTTP level in the integration verification rather than here, since it is a
 * property of the contract rather than of a constant.
 */
class SessionCookieAndOriginTest {

    // ── Layer 1: the cookie ───────────────────────────────────────────────────

    @Test
    void theSessionCookieIsHttpOnlySameSiteLaxAndHostOnly() {
        String cookie = AuthTokenUtils.sessionCookie("a.b.c", false);

        assertTrue(cookie.contains("HttpOnly"),
            "script must not be able to read the session, or XSS becomes account theft");
        assertTrue(cookie.contains("SameSite=Lax"),
            "this is the layer that stops a cross-site PUT carrying the session at all");
        assertFalse(cookie.contains("Domain"),
            "host-only, so no sibling subdomain can present it");
        assertTrue(cookie.startsWith("auth_token=a.b.c;"), "and it carries the token");
    }

    @Test
    void theSessionCookieIsSecureInProductionAndNotLocally() {
        assertTrue(AuthTokenUtils.sessionCookie("a.b.c", true).contains("; Secure"),
            "production is https, and a session cookie sent in the clear is not a session");
        assertFalse(AuthTokenUtils.sessionCookie("a.b.c", false).contains("; Secure"),
            "local development is plain http, where a Secure cookie would never be sent at all");
    }

    @Test
    void sameSiteIsNotNone() {
        /*
          SameSite=None would send the cookie on every cross-site subrequest, which is precisely
          the CSRF exposure the other two layers would then be carrying alone. Asserted separately
          because "contains SameSite" above would still pass if somebody changed Lax to None.
        */
        assertFalse(AuthTokenUtils.sessionCookie("a.b.c", true).contains("SameSite=None"));
    }

    // ── Layer 2: the origin allowlist ─────────────────────────────────────────

    @Test
    void theProductionAllowlistAcceptsOnlyTheRealOrigins() {
        Pattern p = Pattern.compile(ApiOrigins.allowedOriginPattern(true));

        assertTrue(p.matcher("https://werkpages.com").matches());
        assertTrue(p.matcher("https://www.werkpages.com").matches());
    }

    @Test
    void theProductionAllowlistRefusesLookalikeDomains() {
        /*
          A regex allowlist is one unescaped dot away from matching a lookalike, and one missing
          anchor away from matching anything that merely CONTAINS a real origin. Vert.x full-matches
          the Origin header, and the pattern escapes its dots; both of those are what these cases
          are checking, and either regressing would hand an attacker the cookie-bearing origin.
        */
        Pattern p = Pattern.compile(ApiOrigins.allowedOriginPattern(true));

        assertFalse(p.matcher("https://werkpages.com.evil.example").matches(),
            "a suffix must not be allowed in");
        assertFalse(p.matcher("https://evil.example/https://werkpages.com").matches(),
            "nor an origin embedded in a longer string");
        assertFalse(p.matcher("https://werkpagesXcom").matches(),
            "the dots are escaped, so a wildcard match is not available");
        assertFalse(p.matcher("http://werkpages.com").matches(),
            "and plain http is not the production origin");
        assertFalse(p.matcher("https://notwerkpages.com").matches());
    }

    @Test
    void theLocalAllowlistIsTheViteDevServerAndNothingElse() {
        Pattern p = Pattern.compile(ApiOrigins.allowedOriginPattern(false));

        assertTrue(p.matcher("http://localhost:8081").matches(),
            "which must track the Vite dev server port");
        assertFalse(p.matcher("http://localhost:80810").matches(),
            "a longer port is a different origin");
        assertFalse(p.matcher("http://localhost:8081.evil.example").matches());
        assertFalse(p.matcher("http://localhost:8080").matches(),
            "8080 is the other product's frontend, not this API's caller");
    }
}
