package org.werkpages.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The real repository method the API calls, served from the SQLite geography index.
 *
 * <p>This is the integration the whole exercise is for: not the index class in isolation, but
 * {@link LocationCorpusRepository#suggestGeography} returning the JSON the handlers already
 * expect, from the index, on the existing worker.
 */
class LocationSearchIntegrationTest {

    private static LocationCorpusRepository repo;
    private static String indexPath;

    @BeforeAll
    static void open() {
        indexPath = System.getenv("GEOGRAPHY_INDEX");
        Assumptions.assumeTrue(indexPath != null && Files.exists(Path.of(indexPath)),
            "no geography index available; set GEOGRAPHY_INDEX to run these");
        repo = new LocationCorpusRepository("unused-bucket", "ca-central-1", "256MB", indexPath);
    }

    @AfterAll
    static void shutDown() { if (repo != null) repo.close(); }

    private static JsonArray search(String q, String country, String state) throws Exception {
        CompletableFuture<JsonArray> done = new CompletableFuture<>();
        repo.suggestGeography(q, country, state)
            .onSuccess(done::complete)
            .onFailure(done::completeExceptionally);
        return done.get(30, TimeUnit.SECONDS);
    }

    private static List<String> names(JsonArray a) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) out.add(a.getJsonObject(i).getString("name"));
        return out;
    }

    @Test
    @DisplayName("the index is actually the one serving this repository")
    void indexIsActive() {
        assertTrue(repo.geographyIndexActive(),
            "the test must exercise the indexed path, not fall through to the corpus");
    }

    @Test
    @DisplayName("the JSON contract the handlers already depend on is unchanged")
    void contractIsPreserved() throws Exception {
        JsonArray rows = search("Utah", null, null);
        assertFalse(rows.isEmpty());
        JsonObject first = rows.getJsonObject(0);
        // Every field the existing callers read. Changing this shape silently breaks the frontend.
        assertEquals("geo", first.getString("kind"));
        assertNotNull(first.getString("name"));
        assertNotNull(first.getString("geoKind"));
        assertNotNull(first.getString("countryCode"));
        assertTrue(first.containsKey("stateName"));
        assertTrue(first.containsKey("stateCode"));
        assertTrue(first.containsKey("population"));
    }

    @Test
    @DisplayName("a region outranks the hamlets that share its name")
    void regionsRankAboveSameNamedHamlets() throws Exception {
        JsonObject top = search("Utah", null, null).getJsonObject(0);
        assertEquals("Utah", top.getString("name"));
        assertEquals("region", top.getString("geoKind"));
        assertEquals("US", top.getString("countryCode"));
    }

    @Test
    @DisplayName("partial input resolves to the significant place")
    void partialInput() throws Exception {
        assertEquals("France", search("Fra", null, null).getJsonObject(0).getString("name"));
        assertEquals("Toronto", search("Tor", null, null).getJsonObject(0).getString("name"));
        assertEquals("Utah", search("Uta", null, null).getJsonObject(0).getString("name"));
    }

    @Test
    @DisplayName("aliases and ISO codes resolve to their canonical entity")
    void aliases() throws Exception {
        assertEquals("US", search("USA", null, null).getJsonObject(0).getString("countryCode"));
        assertEquals("GB", search("UK", null, null).getJsonObject(0).getString("countryCode"));
        assertEquals("US", search("U.S.A.", null, null).getJsonObject(0).getString("countryCode"));
    }

    @Test
    @DisplayName("an explicit country scope confines the search")
    void explicitScopeConfines() throws Exception {
        JsonArray rows = search("Paris", "US", null);
        assertFalse(rows.isEmpty());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals("US", rows.getJsonObject(i).getString("countryCode"),
                "a US-scoped search must never return France");
        }
    }

    @Test
    @DisplayName("misspellings recover the prominent destination")
    void typoRecovery() throws Exception {
        assertTrue(names(search("Tornto", null, null)).contains("Toronto"));
        assertTrue(names(search("Melborne", null, null)).contains("Melbourne"));
    }

    @Test
    @DisplayName("an exact match is not displaced by a more prominent guess")
    void exactMatchKeepsPrecedence() throws Exception {
        /*
          A stable sort only protects EQUAL scores, so exact-match evidence has to be scored
          explicitly or a prominent guess can displace a real match.

          "Melborne" is genuinely the name of two American hamlets AND a misspelling of Melbourne.
          Both readings must be offered: the correction leads because Melbourne is vastly more
          prominent, and the exact matches must still be present rather than squeezed out.
        */
        List<String> names = names(search("Melborne", null, null));
        assertTrue(names.contains("Melbourne"), "the correction should be offered");
        assertTrue(names.stream().anyMatch(n -> n.equalsIgnoreCase("Melborne")),
            "the exact match must not be dropped in favour of the guess: " + names);
    }

    @Test
    @DisplayName("a guess never escapes an explicitly requested country")
    void scopedSearchIsNotWidenedByGuesses() throws Exception {
        /*
          An explicit restriction is not advice. Before this, fuzzy candidates were fetched
          without the scope, so a search confined to the United States could be answered with a
          near miss from another country.
        */
        JsonArray rows = search("Melborne", "US", null);
        for (int i = 0; i < rows.size(); i++) {
            assertEquals("US", rows.getJsonObject(i).getString("countryCode"),
                "a scoped search returned a result from outside its scope");
        }
    }

    @Test
    @DisplayName("international names survive normalisation end to end")
    void internationalNames() throws Exception {
        // The regression guard for the ASCII-only key defect: Aydın was indexed as "ayd n".
        assertTrue(names(search("Aydın", null, null)).stream().anyMatch(n -> n.startsWith("Ayd")));
        assertTrue(names(search("Montréal", null, null)).stream().anyMatch(n -> n.startsWith("Montr")));
        assertTrue(names(search("Montreal", null, null)).stream().anyMatch(n -> n.startsWith("Montr")),
            "the unaccented spelling must reach the accented name");
    }

    @Test
    @DisplayName("a query that matches nothing returns empty, not an error")
    void legitimateMiss() throws Exception {
        assertTrue(search("Zzqqxwv", null, null).isEmpty());
    }

    @Test
    @DisplayName("blocking index work never runs on the calling thread")
    void doesNotBlockTheCaller() throws Exception {
        /*
          The dispatch guarantee. Vert.x event loops must not be blocked by SQLite calls, and the
          repository already owns a three-thread worker for exactly this. If the lookup ran inline
          it would complete on the caller's thread.
        */
        String callerThread = Thread.currentThread().getName();
        search("Toronto", null, null);
        /*
          The WORK thread, not the completion thread. Vert.x runs a handler inline when the future
          is already resolved, so asserting on the completion thread tests Vert.x's semantics
          rather than our dispatch.
        */
        String workThread = repo.lastIndexLookupThread;
        assertNotNull(workThread, "the indexed lookup did not run");
        assertFalse(callerThread.equals(workThread),
            "SQLite work ran on the calling thread: " + workThread);
        assertTrue(workThread.startsWith("location-corpus"),
            "expected the existing corpus worker, ran on: " + workThread);
    }

    @Test
    @DisplayName("a missing index degrades to the corpus path rather than to nothing")
    void missingIndexIsNotAnEmptyResult() {
        /*
          An index that is absent and a place that does not exist must never look the same. With
          no path configured the repository keeps the previous behaviour instead of reporting that
          every location on earth has ceased to exist.
        */
        LocationCorpusRepository none =
            new LocationCorpusRepository("unused-bucket", "ca-central-1", "256MB", "");
        assertFalse(none.geographyIndexActive());
        none.close();
    }
}
