package org.werkpages.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proof that the routing and address indexes are INVOKED, not merely present.
 *
 * <p>This exists because of a defect that compiled cleanly: Werkpages had the geography lookup
 * method and the constructor that builds it, and no call site. The file contained every symbol
 * anyone would grep for, the build was green, and the index was never used. Asserting that a class
 * exists proves nothing; these tests assert that work happened.
 */
class LocationIndexWiringTest {

    private static LocationCorpusRepository repo;

    @BeforeAll
    static void open() {
        String dir = System.getenv("LOCATION_INDEX_SET");
        Assumptions.assumeTrue(dir != null && Files.isDirectory(Path.of(dir)),
            "no published index set; point LOCATION_INDEX_SET at one to run these");
        repo = new LocationCorpusRepository("unused-bucket", "ca-central-1", "256MB",
            Path.of(dir, "geography.sqlite").toString(),
            Path.of(dir, "routing.sqlite").toString(),
            Path.of(dir, "addresses").toString());
    }

    @AfterAll
    static void shutDown() { if (repo != null) repo.close(); }

    private static JsonArray addresses(String q, String visitor) throws Exception {
        CompletableFuture<JsonArray> done = new CompletableFuture<>();
        repo.suggestAddresses(q, visitor, 5).onSuccess(done::complete).onFailure(done::completeExceptionally);
        return done.get(30, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("all three indexes report themselves active")
    void allThreeActive() {
        assertTrue(repo.geographyIndexActive(), "geography index not wired");
        assertTrue(repo.routingIndexActive(), "routing index not wired");
        assertFalse(repo.addressCoverage().isEmpty(), "no address index loaded");
    }

    @Test
    @DisplayName("routing selects countries from the index, not from the visitor alone")
    void routingIsInvoked() {
        /*
          "tabusintac" is a Canadian place name rare enough to identify the country on its own.
          If routing were not consulted, the answer would be whatever the visitor's country is.
        */
        List<String> noVisitor = repo.routeCountries("tabusintac", null, 3);
        assertFalse(noVisitor.isEmpty(), "routing returned nothing with no visitor to fall back on");
        assertTrue(noVisitor.contains("CA"), "expected Canada from the text alone, got " + noVisitor);
    }

    @Test
    @DisplayName("the visitor's country is consulted first but never exclusively")
    void visitorIsAPreferenceNotAPrison() {
        List<String> fromBritain = repo.routeCountries("tabusintac", "GB", 3);
        assertEquals_first(fromBritain, "GB");
        assertTrue(fromBritain.contains("CA"),
            "an explicit textual signal must survive a different visitor country: " + fromBritain);
    }

    private static void assertEquals_first(List<String> order, String expected) {
        assertFalse(order.isEmpty(), "routing returned nothing");
        org.junit.jupiter.api.Assertions.assertEquals(expected, order.get(0),
            "the visitor's own partition should be read first, order was " + order);
    }

    @Test
    @DisplayName("an address query reaches the address index and returns the real address")
    void addressLookupIsInvoked() throws Exception {
        JsonArray rows = addresses("55 Gould Street, Toronto", "CA");
        assertNotNull(repo.lastAddressLookupThread, "the address index was never called");
        assertTrue(repo.lastAddressLookupThread.startsWith("location-corpus"),
            "address work ran on " + repo.lastAddressLookupThread + ", not the corpus worker");
        assertFalse(rows.isEmpty(), "no address returned for a known Canadian address");
        JsonObject first = rows.getJsonObject(0);
        assertTrue(first.getString("name").toLowerCase().contains("gould"));
        assertTrue(first.getString("city").toLowerCase().contains("toronto"));
        org.junit.jupiter.api.Assertions.assertEquals("CA", first.getString("country"));
        /*
          THE KEYS, not just the values.

          An address row joins the existing places pipeline, so it has to use that pipeline's
          field names. addCorpusPlaces builds its detail line from "stateCode", and
          CorpusPlace.fromBody promotes a chosen row to company_locations only when it carries
          "sourcePlaceId" and "name". A row naming them "state" and "placeId" instead renders a
          perfectly plausible tile, loses the region from the detail line, and then silently
          declines to promote on submit: fromBody returns null and the contribution is stored as
          coarse geography. Nothing throws, so only an assertion on the key names catches it.
        */
        assertNotNull(first.getValue("sourcePlaceId"),
            "the source identity must travel under the key promotion reads: " + first);
        assertNotNull(first.getValue("stateCode"),
            "the region must travel under the key the detail line reads: " + first);
    }

    @Test
    @DisplayName("a query with no house number is not answered with an address")
    void streetQueryIsNotAnAddress() throws Exception {
        /*
          Address and street are different questions. Letting a street answer an address query is
          how "exact address found" stops meaning anything.
        */
        assertTrue(addresses("Gould Street", "CA").isEmpty(),
            "a street query must not be answered from the address index");
    }

    @Test
    @DisplayName("a country with no address index is a documented gap, not an error")
    void missingCoverageIsExplicit() throws Exception {
        /*
          Routing can legitimately choose Japan while V1 ships addresses for Canada only. That must
          return nothing and say so through addressCoverage(), rather than throwing or inventing a
          result.
        */
        assertFalse(repo.addressCoverage().contains("JP"),
            "this assertion documents V1 coverage; update it when Japan ships");
        assertTrue(addresses("1 Chome-1 Marunouchi, Chiyoda City", "JP").isEmpty());
    }
}
