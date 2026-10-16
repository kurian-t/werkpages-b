package org.werkpages.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A country match is not a city match.
 *
 * <p>"McDonalds Tokyo" resolved Japan correctly and then offered a McDonald's in KASUKABE, because
 * the remote buildings query constrains only by country partition. Ordering the scan by city helps
 * only where the two datasets spell a city alike, and for Japan they do not: Overture stores
 * Japanese city names while the geography corpus supplies romanised ones. So an unconfirmable city
 * is refused rather than disguised.
 */
class VerifyCityTest {

    private static JsonArray rows(String... cities) {
        JsonArray a = new JsonArray();
        for (String c : cities) a.add(new JsonObject().put("name", "McDonalds").put("city", c));
        return a;
    }

    @Test
    @DisplayName("a place in another city is not offered as a match for the one asked for")
    void unconfirmableCityIsDropped() {
        JsonArray kept = ManagerService.verifyCity(rows("春日部市"), "Tokyo", "Japan");
        assertEquals(0, kept.size(), "Kasukabe does not satisfy a search for Tokyo");
    }

    @Test
    @DisplayName("the city that was asked for is kept")
    void confirmedCitySurvives() {
        JsonArray kept = ManagerService.verifyCity(rows("Berlin", "Hamburg"), "Berlin", "Germany");
        assertEquals(1, kept.size());
        assertEquals("Berlin", kept.getJsonObject(0).getString("city"));
    }

    @Test
    @DisplayName("an accent is not a different city")
    void accentsDoNotRejectAMatch() {
        // Folded with the same rule the index uses, so the 55,654 places spelled "Montréal" are
        // not thrown away because somebody typed "montreal".
        assertEquals(1, ManagerService.verifyCity(rows("Montréal"), "Montreal", "Canada").size());
        assertEquals(1, ManagerService.verifyCity(rows("Évry-Courcouronnes"),
                                                  "Evry Courcouronnes", "France").size());
    }

    @Test
    @DisplayName("with no city named, nothing is filtered")
    void noCityMeansNoConstraint() {
        // An explicit country with no city is a country-wide search and always was.
        JsonArray all = rows("Hamburg", "Munich");
        assertEquals(2, ManagerService.verifyCity(all, null, "Germany").size());
        assertEquals(2, ManagerService.verifyCity(all, "  ", "Germany").size());
    }

    @Test
    @DisplayName("a row with no city of its own cannot confirm one")
    void rowWithoutACityIsNotAMatch() {
        JsonArray kept = ManagerService.verifyCity(rows((String) null), "Tokyo", "Japan");
        assertEquals(0, kept.size());
    }
}
