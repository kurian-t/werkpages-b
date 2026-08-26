package org.werkpages.rest.handlers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * handleCallback puts the access token in the session cookie so its {@code aud} can be checked
 * against this API. Auth0 only returns a JWT access token when /authorize named an audience —
 * a frontend build that omits it gets an opaque string instead, which the router cannot decode.
 * This guard is what decides whether to fall back to the id_token, so a wrong answer either
 * breaks every social login or puts an undecodable value in the cookie.
 */
class AuthHandlerTokenShapeTest {

    @Test
    void recognisesAThreeSegmentJwt() {
        assertTrue(AuthHandler.looksLikeJwt("header.payload.signature"));
    }

    @Test
    void rejectsAnOpaqueAuth0AccessToken() {
        // What Auth0 hands back when the authorization request named no audience.
        assertFalse(AuthHandler.looksLikeJwt("v1DkT2sT0kEnOpAqUeStRiNg"));
    }

    @Test
    void rejectsNull() {
        assertFalse(AuthHandler.looksLikeJwt(null));
    }

    @Test
    void rejectsBlank() {
        assertFalse(AuthHandler.looksLikeJwt("   "));
    }

    @Test
    void rejectsTooFewSegments() {
        assertFalse(AuthHandler.looksLikeJwt("header.payload"));
    }

    @Test
    void rejectsTooManySegments() {
        assertFalse(AuthHandler.looksLikeJwt("a.b.c.d"));
    }
}
