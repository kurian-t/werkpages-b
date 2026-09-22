package org.werkpages.service;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link CorpusPlace} — a building chosen from the search corpus, before it exists as a row.
 *
 * <p>The behaviour worth protecting here is that a malformed or absent corpus place never turns a
 * valid submission into an error. Coarse geography is a complete answer; a broken suggestion
 * payload must not be able to take that away.
 */
class CorpusPlaceTest {

    private static JsonObject place(String id, String name) {
        return new JsonObject()
            .put("sourcePlaceId", id)
            .put("name", name)
            .put("street", "1400 Ottawa St S")
            .put("city", "Kitchener")
            .put("stateCode", "ON")
            .put("countryCode", "CA")
            .put("postalCode", "N2E 4E2");
    }

    @Test
    @DisplayName("a body with no corpusPlace yields null, not an error")
    void absentIsNull() {
        assertNull(CorpusPlace.fromBody(new JsonObject().put("declaredPrecision", "city")));
        assertNull(CorpusPlace.fromBody(new JsonObject()));
        assertNull(CorpusPlace.fromBody(null));
    }

    @Test
    @DisplayName("every field is copied off the payload")
    void readsAllFields() {
        CorpusPlace p = CorpusPlace.fromBody(
            new JsonObject().put("corpusPlace", place("gers-1", "Walmart")));

        assertNotNull(p);
        assertEquals("gers-1", p.sourcePlaceId());
        assertEquals("Walmart", p.displayName());
        assertEquals("1400 Ottawa St S", p.street());
        assertEquals("Kitchener", p.city());
        assertEquals("ON", p.stateCode());
        assertEquals("CA", p.countryCode());
        assertEquals("N2E 4E2", p.postalCode());
    }

    @Test
    @DisplayName("a place with no identity or no name is treated as absent, not as an error")
    void unusableIsAbsent() {
        // Deliberately null rather than a validation failure: the declaration alongside it may be
        // perfectly good coarse geography, and rejecting the whole submission over a broken
        // suggestion would lose a contribution somebody had already written.
        assertNull(CorpusPlace.fromBody(new JsonObject()
            .put("corpusPlace", place(null, "Walmart"))));
        assertNull(CorpusPlace.fromBody(new JsonObject()
            .put("corpusPlace", place("gers-1", null))));
    }

    @Test
    @DisplayName("blank strings are read as absent")
    void blanksAreNull() {
        CorpusPlace p = CorpusPlace.fromBody(new JsonObject().put("corpusPlace",
            place("gers-1", "Walmart").put("street", "   ").put("postalCode", "")));

        assertNotNull(p);
        assertNull(p.street());
        assertNull(p.postalCode());
    }

    @Test
    @DisplayName("a workplace without a city or country cannot be promoted")
    void requiresCityAndCountry() {
        // A building with no city cannot be rendered on a profile, grouped into a facet, or told
        // apart from another branch of the same chain.
        CorpusPlace noCity = CorpusPlace.fromBody(new JsonObject().put("corpusPlace",
            place("gers-1", "Walmart").putNull("city")));
        assertNotNull(noCity.validate());

        CorpusPlace noCountry = CorpusPlace.fromBody(new JsonObject().put("corpusPlace",
            place("gers-1", "Walmart").putNull("countryCode")));
        assertNotNull(noCountry.validate());
    }

    @Test
    @DisplayName("a complete place validates")
    void completeIsValid() {
        assertNull(CorpusPlace.fromBody(
            new JsonObject().put("corpusPlace", place("gers-1", "Walmart"))).validate());
    }

    @Test
    @DisplayName("declared keys are never read as a corpus place")
    void ignoresDeclaredKeys() {
        // The two namespaces stay separate for the same reason observed and declared do: nothing
        // should be able to turn one kind of location value into another by accident.
        assertNull(CorpusPlace.fromBody(new JsonObject()
            .put("declaredCountry", "Canada")
            .put("declaredState", "Ontario")
            .put("declaredCity", "Kitchener")
            .put("declaredPrecision", "city")));
    }
}
