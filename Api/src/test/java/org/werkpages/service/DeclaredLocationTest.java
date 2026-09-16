package org.werkpages.service;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The declared half of the location model.
 *
 * <p>Two things matter here above the rest: that a body carrying only the observed keys declares
 * nothing, and that "city only" is a complete, valid answer rather than an incomplete one.
 */
class DeclaredLocationTest {

    // ── The key namespace is the whole safety property ────────────────────────

    @Test
    void observedKeysAloneDeclareNothing() {
        /*
          GeoUtils.stampGeo fills country/state/city on a request body from Cloudflare headers. If
          those keys could also be read as declared, every search would publish the searcher's
          location - which is the bug this design exists to make impossible.
        */
        JsonObject body = new JsonObject()
            .put("country", "Canada").put("state", "Ontario").put("city", "Toronto");

        assertTrue(DeclaredLocation.fromBody(body).isEmpty());
    }

    @Test
    void declaredKeysAreIgnoredWithoutAPrecision() {
        // Precision is what says somebody answered. Values without it are not a statement.
        JsonObject body = new JsonObject()
            .put("declaredCountry", "Canada").put("declaredCity", "Toronto");

        assertTrue(DeclaredLocation.fromBody(body).isEmpty());
    }

    @Test
    void nullBodyDeclaresNothing() {
        assertTrue(DeclaredLocation.fromBody(null).isEmpty());
    }

    // ── Shape has to match the claim ──────────────────────────────────────────

    @Test
    void cityPrecisionNeedsAllThree() {
        assertNull(declared("Canada", "Ontario", "Kitchener", DeclaredLocation.CITY, null).validate());
        assertNotNull(declared("Canada", "Ontario", null, DeclaredLocation.CITY, null).validate());
        assertNotNull(declared("Canada", null, "Kitchener", DeclaredLocation.CITY, null).validate());
    }

    @Test
    void statePrecisionNeedsCountryAndState() {
        assertNull(declared("Canada", "Ontario", null, DeclaredLocation.STATE, null).validate());
        assertNotNull(declared("Canada", null, null, DeclaredLocation.STATE, null).validate());
    }

    @Test
    void countryPrecisionNeedsOnlyACountry() {
        assertNull(declared("Canada", null, null, DeclaredLocation.COUNTRY, null).validate());
        assertNotNull(declared(null, null, null, DeclaredLocation.COUNTRY, null).validate());
    }

    @Test
    void cityOnlyIsAValidAnswer_withNoWorkplaceSelected() {
        /*
          "Kitchener, use city only" is a complete answer from somebody who knows the city and not
          the branch. Requiring a workplace whenever a city is present would reject the most common
          honest response.
        */
        DeclaredLocation d = declared("Canada", "Ontario", "Kitchener", DeclaredLocation.CITY, null);
        assertNull(d.validate());
        assertNull(d.companyLocationId());
    }

    @Test
    void exactPrecisionNeedsAWorkplace() {
        assertNotNull(declared("Canada", "Ontario", "Kitchener", DeclaredLocation.EXACT, null).validate());
        assertNull(declared(null, null, null, DeclaredLocation.EXACT, 382L).validate());
    }

    @Test
    void unknownPrecisionIsRejected() {
        assertNotNull(declared("Canada", null, null, "street", null).validate());
    }

    @Test
    void overlongValuesAreRejected() {
        String tooLong = "x".repeat(200);
        assertNotNull(declared("Canada", "Ontario", tooLong, DeclaredLocation.CITY, null).validate());
    }

    // ── At exact precision the location row wins ──────────────────────────────

    @Test
    void exactPrecisionTakesCoarseValuesFromTheLocation_notTheClient() {
        /*
          The client's coarse values are redundant at exact precision and are not trusted: a form
          that had drifted out of sync could otherwise publish a city the selected building is not
          in. They are still stored so analytics can group by city or state without parsing an
          address.
        */
        DeclaredLocation fromClient =
            declared("Canada", "Ontario", "Waterloo", DeclaredLocation.EXACT, 382L);

        DeclaredLocation resolved = fromClient.withCoarseFrom("Canada", "Ontario", "Kitchener");

        assertEquals("Kitchener", resolved.city(), "the location row decides, not the request");
        assertEquals(382L, resolved.companyLocationId());
        assertEquals(DeclaredLocation.EXACT, resolved.precision());
    }

    @Test
    void roundTripsFromABodyThatDeclaresEverything() {
        JsonObject body = new JsonObject()
            .put("declaredCountry", " Canada ")
            .put("declaredState", "Ontario")
            .put("declaredCity", "Kitchener")
            .put("declaredPrecision", DeclaredLocation.CITY);

        DeclaredLocation d = DeclaredLocation.fromBody(body);

        assertEquals("Canada", d.country(), "surrounding whitespace is not part of the answer");
        assertEquals("Kitchener", d.city());
        assertEquals(DeclaredLocation.CITY, d.precision());
        assertNull(d.validate());
    }

    private static DeclaredLocation declared(String country, String state, String city,
                                             String precision, Long locationId) {
        return new DeclaredLocation(country, state, city, precision, locationId);
    }
}
