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
    @DisplayName("the index counts as worldwide geography, or every request waits two seconds")
    void theIndexSatisfiesTheReadinessPredicate() {
        /*
          THE TWO SECONDS, and it was not a query.

          geographyIsWorldwide() answered "has the geography PARQUET been copied here", because
          that was once the only worldwide source. With the index active the parquet is never
          materialised, so it returned false, and the service's no-country branch waited out the
          whole GEOGRAPHY_WAIT_MS before carrying on.

          Measured through the HTTP endpoint in the production configuration: 2,207ms total
          against 201ms of actual tier work. Pure waiting, invisible to every per-query timer.
        */
        assertTrue(repo.geographyIsWorldwide(),
            "a published geography index IS worldwide geography; saying otherwise costs the "
            + "caller the full readiness timeout on every request that names no country");
    }

    @Test
    @DisplayName("a country named by the QUERY outranks the country held by the form")
    void aNamedCountryIsNotScopedAway() throws Exception {
        /*
          THE PRODUCTION DEFECT, exactly.

          Somebody in Canada typing "USA" has named the United States, and the United States is
          not in Canada - so a scoped lookup returns nothing and they cannot select it at all. The
          DuckDB path this replaces never had that hole: it admits the named country by its ISO
          code in its own branch, because the corpus stores that row's search text as "united
          states" and no token of "usa" reaches it either.

          Observed in production as "USA" returning Jerusalem and Rusagonis - "Jer-usa-lem",
          "R-usa-gonis" - with United States ranked below them or missing.
        */
        for (String typed : new String[]{"USA", "U.S.A.", "United States"}) {
            JsonArray scoped = search(typed, "Canada", null);
            assertFalse(scoped.isEmpty(), typed + " returned nothing under a Canadian scope");
            assertEquals("US", scoped.getJsonObject(0).getString("countryCode"),
                typed + " names the United States; a form holding Canada must not hide it: "
                + names(scoped));
        }
        assertEquals("GB", search("UK", "Canada", null).getJsonObject(0).getString("countryCode"));
    }

    @Test
    @DisplayName("a country scope ranks results without excluding the rest of the world")
    void aScopeIsAPreferenceNotAPrison() throws Exception {
        /*
          "Tokyo" with Canada on the form returned NO geography at all, so the suggestion list led
          with a Canadian restaurant called Tokyo Express and the city of 14 million was never
          offered. Somebody recording a job in Tokyo could not say where it was.

          The scope still decides the ORDER - that part is wanted, and the next assertion pins it.
        */
        assertTrue(names(search("Tokyo", "Canada", null)).stream().anyMatch(n -> n.startsWith("Tokyo")),
            "a Canadian scope must not hide Tokyo");
        assertTrue(names(search("Osaka", "Canada", null)).stream().anyMatch(n -> n.startsWith("Osaka")),
            "nor Osaka");
    }

    @Test
    @DisplayName("and the preferred country still leads where it has a match")
    void thePreferredCountryStillLeads() throws Exception {
        /*
          The other half, and the reason widening appends rather than replaces: London, Ontario
          must still come first for somebody whose form says Canada. Widening that broke this
          would be a worse bug than the one it fixed.
        */
        JsonObject top = search("London", "Canada", null).getJsonObject(0);
        assertEquals("CA", top.getString("countryCode"),
            "the country on the form must still rank first where it has a match");
        assertEquals("London", top.getString("name"));
    }

    @Test
    @DisplayName("geography answers in single-digit milliseconds, which is the point of the index")
    void geographyIsFast() throws Exception {
        /*
          A LATENCY ASSERTION, because correctness alone let a two-second regression ship.

          The DuckDB parquet scan this replaces was measured at 2,100-2,600ms per keystroke
          against the live production host. The budget here is deliberately loose - 250ms covers a
          cold page cache and a loaded CI machine - and still fails by an order of magnitude if the
          index is bypassed or the readiness predicate regresses.
        */
        for (String q : new String[]{"Toronto", "Pen", "London", "Tokyo", "USA"}) {
            search(q, null, null);                       // warm the cache the first call pays for
        }
        long worst = 0;
        String worstQuery = null;
        for (String q : new String[]{"Toronto", "Pen", "London", "Tokyo", "USA", "Montréal"}) {
            long started = System.nanoTime();
            search(q, "Canada", null);
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (ms > worst) { worst = ms; worstQuery = q; }
        }
        assertTrue(worst < 250,
            "geography took " + worst + "ms for " + worstQuery + "; the index exists to keep this "
            + "in single digits, and the path it replaced took over two seconds");
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
