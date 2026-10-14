package org.werkpages.repository;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The location search regression set, against a real DuckDB index and real Parquet payloads.
 *
 * <p>Every case here corresponds to a defect found in this area, most of them in production or in
 * review. They run against {@link FixtureCorpus}, which is small enough to rebuild per test class
 * and real enough to fail for the right reason.
 */
class LocalPlaceSearchIntegrationTest {

    static CorpusRelease release;
    static Connection conn;

    @BeforeAll
    static void setUp(@TempDir Path root) throws Exception {
        release = FixtureCorpus.publish(root);
        conn = DriverManager.getConnection(release.jdbcUrl());
        try (Statement s = conn.createStatement()) {
            s.execute("SET threads=2");
            s.execute("SET memory_limit='512MB'");
        }
    }

    @AfterAll
    static void tearDown() throws Exception { if (conn != null) conn.close(); }

    private static List<LocalPlaceIndex.Hit> find(String q) throws Exception {
        return new LocalPlaceIndex(conn, release).search(q, 8);
    }
    private static String topId(String q) throws Exception {
        List<LocalPlaceIndex.Hit> h = find(q);
        return h.isEmpty() ? null : h.get(0).sourcePlaceId();
    }

    // ── business names ────────────────────────────────────────────────────────

    @Test @DisplayName("a business name finds its place, in either word order")
    void businessName() throws Exception {
        assertEquals("p-subaru-eg", topId("Elk Grove Subaru"));
        assertEquals("p-subaru-eg", topId("Subaru Elk Grove"));
    }

    @Test @DisplayName("a name that IS a street type is not stripped away")
    void scotiabankPlace() throws Exception {
        // Stripping "place" everywhere turned this search into "scotiabank" and returned branches.
        // A plain "Scotiabank" branch now exists at higher confidence, so dropping "place"
        // would return that instead.
        assertEquals("p-scotia", topId("Scotiabank Place"));
        assertEquals("p-scotia2", topId("Scotiabank Toronto"));
    }

    @Test @DisplayName("a leading article does not hide a chain")
    void northFace() throws Exception {
        assertEquals("p-northface", topId("North Face"));
        assertEquals("p-northface", topId("The North Face"));
    }

    @Test @DisplayName("a region word inside a name does not remove the name")
    void regionWordInName() throws Exception {
        // "new" and "york" are region words; stripping them searched for "life".
        // A New York Life office in San Jose now exists at higher confidence, so this asserts
        // the phrase ranking, not just that the words appear somewhere.
        assertEquals("p-nylife", topId("New York Life, New York"));
    }

    // ── addresses ─────────────────────────────────────────────────────────────

    @Test @DisplayName("a full address finds the business, with Drive or Dr")
    void addressBothSpellings() throws Exception {
        assertEquals("p-subaru-eg", topId("8581 Laguna Grove Drive"));
        assertEquals("p-subaru-eg", topId("8581 Laguna Grove Dr"));
    }

    @Test @DisplayName("South and S are the same street")
    void directionalAbbreviation() throws Exception {
        // 45 North Street now also exists in Halifax with HIGHER confidence, so these can only
        // pass if the directional word actually selects the right street rather than being
        // dropped and letting confidence decide.
        assertEquals("p-southst", topId("45 South Street"));
        assertEquals("p-southst", topId("45 S Street"));
        assertEquals("p-northst", topId("45 North Street"));
    }

    @Test @DisplayName("a partial address still finds it")
    void partialAddress() throws Exception {
        // 8581 Laguna Beach Rd in San Diego also matches "8581 laguna", at higher confidence.
        assertEquals("p-subaru-eg", topId("8581 Laguna Grove"));
    }

    // ── postal codes ──────────────────────────────────────────────────────────

    @Test @DisplayName("a postal code works spaced or unspaced")
    void postalCodeBothForms() throws Exception {
        assertEquals("p-sbux-tor", topId("M5X 1A9"));
        assertEquals("p-sbux-tor", topId("M5X1A9"));
    }

    // ── geography and competing branches ──────────────────────────────────────

    @Test @DisplayName("a city narrows to that city's branch, it does not merely rank it higher")
    void geographyConstrainsBranches() throws Exception {
        assertEquals("p-sbux-tor", topId("Starbucks Toronto"));
        assertEquals("p-sbux-van", topId("Starbucks Vancouver"));
        assertEquals("p-sbux-tor", topId("Starbucks, Toronto"));
        // Every result must be in the named city; a branch elsewhere must not leak through.
        assertTrue(find("Starbucks Vancouver").stream()
                       .allMatch(h -> "Vancouver".equalsIgnoreCase(h.city())));
    }

    @Test @DisplayName("city plus province disambiguates two cities of the same name")
    void cityPlusRegion() throws Exception {
        // London, Ontario is in the fixture; the London in England is not, so Ontario must pick it.
        // There is now a Starbucks in London, England too, at higher confidence. Only the
        // province can separate them.
        assertEquals("p-sbux-lon", topId("Starbucks, London, Ontario"));
    }

    @Test @DisplayName("a bare city search returns that city's places")
    void bareCity() throws Exception {
        assertFalse(find("Vancouver").isEmpty());
        assertTrue(find("Vancouver").stream().allMatch(h -> "Vancouver".equalsIgnoreCase(h.city())));
    }

    // ── international ─────────────────────────────────────────────────────────

    @Test @DisplayName("non-Latin names are searchable")
    void nonLatinNames() throws Exception {
        assertEquals("p-tokyo", topId("東京タワー"));
        assertEquals("p-dev",   topId("कॉफी हाउस"));
        assertEquals("p-thai",  topId("ร้านกาแฟ"));
    }

    @Test @DisplayName("accented input matches, composed or decomposed")
    void accents() throws Exception {
        assertEquals("p-cafe", topId("Café Du Monde"));
        assertEquals("p-cafe", topId("Café Du Monde"));
    }

    // ── adversarial: the ways a search can look right and be wrong ────────────

    @Test @DisplayName("an unknown word makes the search fail, it is not quietly ignored")
    void unknownTokenDoesNotBecomeAGenericSearch() throws Exception {
        /*
          The dangerous failure. Dropping a word the corpus does not know turned
          "Starbucks Fakecity" into a plain "Starbucks" search and returned a confident list of
          branches in cities nobody asked about. An empty result is honest; a plausible wrong one
          is not.
        */
        assertTrue(find("Starbucks Fakecityville").isEmpty(),
                   "an unmatched word must not be dropped, leaving a generic search");
        assertTrue(find("Elk Grove Subaru Zzzqqx").isEmpty());
    }

    @Test @DisplayName("a half-typed word still matches even when it exists exactly elsewhere")
    void prefixWorksEvenWhenTheFragmentIsAlsoARealToken() throws Exception {
        /*
          "North Fac" must reach The North Face. The fixture also contains a business whose name
          contains the whole word "fac", so deciding to use a prefix only when the exact token is
          missing would have excluded every Face.
        */
        assertEquals("p-northface", topId("North Fac"));
        assertEquals("p-northface", topId("North Fa"));
    }

    @Test @DisplayName("a postal code is one constraint, not three")
    void postalFragmentsAreNotAllRequired() throws Exception {
        // Appending the joined form while keeping the halves required m5x AND 1a9 AND m5x1a9,
        // which nothing satisfies because the build stores the code with the space removed.
        assertEquals("p-sbux-tor", topId("M5X 1A9"));
        assertEquals("p-sbux-tor", topId("M5X1A9"));
        assertEquals("p-sbux-tor", topId("Starbucks M5X 1A9"));
    }

    @Test @DisplayName("the same region code in two senses picks the right branch")
    void ambiguousRegionCodes() throws Exception {
        /*
          "Pacific Imports" exists in San Jose (state ca) and Toronto (state on). "ca" is both the
          California region code and Canada's country code, so a naive reading could return the
          Toronto branch for a Californian query or vice versa.
        */
        assertEquals("p-amb-ca-us",  topId("Pacific Imports, California"));
        assertEquals("p-amb-ca-ont", topId("Pacific Imports, Ontario"));
        assertEquals("p-amb-ca-us",  topId("Pacific Imports, San Jose"));
        assertEquals("p-amb-ca-ont", topId("Pacific Imports, Toronto"));
        // The wrong branch must be EXCLUDED, not merely ranked lower.
        assertTrue(find("Pacific Imports, Toronto").stream()
                       .noneMatch(h -> "p-amb-ca-us".equals(h.sourcePlaceId())),
                   "a branch in another region must not appear at all");
    }

    @Test @DisplayName("the same city name in two places is separated by its region")
    void duplicateCityNames() throws Exception {
        // York Roasters exists in York and in New York. The city alone is ambiguous because
        // "York" is a prefix of "New York" and a city in its own right.
        assertEquals("p-york-uk", topId("York Roasters, York"));
        assertEquals("p-york-us", topId("York Roasters, New York"));
        assertTrue(find("York Roasters, York").stream()
                       .noneMatch(h -> "p-york-us".equals(h.sourcePlaceId())));
    }

    @Test @DisplayName("a multiword city is one place name, not two loose words")
    void multiwordGeography() throws Exception {
        /*
          "New York" must constrain the city as a unit. Intersecting "new" and "york" separately
          would also admit a place in York whose name merely contains "new", and would let the
          geography steal words that belong to the business name.
        */
        assertEquals("p-york-us", topId("York Roasters, New York"));
        // And the company whose NAME is New York Life is still found, with its own city, without
        // the geographic reading consuming the name.
        assertEquals("p-nylife", topId("New York Life, New York"));
    }

    @Test @DisplayName("a comma inside a business name is not geography")
    void commaInsideABusinessName() throws Exception {
        /*
          Reading everything after the first comma as a place turned "Smith, Jones & Associates"
          into a search for a company called Smith in a city called Jones. The comma is a hint
          about geography, and a hint has to be checked against what the corpus actually knows.
        */
        assertEquals("p-smith", topId("Smith, Jones & Associates"));
    }

    @Test @DisplayName("a comma before a street address is not geography")
    void commaBeforeAnAddress() throws Exception {
        assertEquals("p-mainst", topId("Riverside Clinic, 123 Main Street"));
    }

    @Test @DisplayName("a city named without any comma still constrains")
    void geographyWithoutAComma() throws Exception {
        assertEquals("p-sbux-van", topId("Starbucks Vancouver"));
        assertTrue(find("Starbucks Vancouver").stream()
                       .allMatch(h -> "Vancouver".equalsIgnoreCase(h.city())));
    }

    // ── eligibility and progressive typing ────────────────────────────────────

    @Test @DisplayName("a closed business is never suggested")
    void closedExcluded() throws Exception {
        assertTrue(find("Closed Cafe").isEmpty(), "a closed place is not a workplace to rate");
    }

    @Test @DisplayName("results appear before the whole name is typed")
    void progressiveTyping() throws Exception {
        assertEquals("p-scotia", topId("Scotiabank Pl"));
        assertEquals("p-northface", topId("North Fac"));
    }

    @Test @DisplayName("a genuine miss is empty, not a wrong guess")
    void genuineMiss() throws Exception {
        assertTrue(find("Zzzqqx Nonexistent Holdings").isEmpty());
        assertTrue(find("").isEmpty());
    }

    @Test
    @DisplayName("the company being edited biases the ranking toward its own brand")
    void brandBiasFollowsTheCompany() throws Exception {
        /*
          The endpoint's companyName parameter was IGNORED by the local index, which was a silent
          downgrade: the remote query it replaced ranks a place whose brand IS this company ahead of
          any other branded place, and somebody filling in a location for Starbucks means a
          Starbucks.

          Two fixture rows sit on King St W in Toronto, both branded, matching the same four tokens.
          Nothing but confidence separates them, and Scotiabank has the higher value, so it wins
          whenever the company is not taken into account. That makes the parameter's effect
          observable: the same query must return a different leader depending on who is asking.
        */
        String query = "king st w toronto";

        assertEquals("p-scotia2", topIdFor(query, null),
            "without a company, confidence should decide");

        assertEquals("p-sbux-tor", topIdFor(query, "Starbucks"),
            "the Starbucks branch should lead for Starbucks");
        assertEquals("p-sbux-tor", topIdFor(query, "starbucks"),
            "the bias must not be case-sensitive");

        assertEquals("p-scotia2", topIdFor(query, "Scotiabank"),
            "the Scotiabank branch should lead for Scotiabank");

        // A company with no place in the corpus must not reorder anything or lose results.
        assertEquals("p-scotia2", topIdFor(query, "Nonexistent Holdings Ltd"));
    }

    private static String topIdFor(String query, String companyName) throws Exception {
        List<LocalPlaceIndex.Hit> hits = new LocalPlaceIndex(conn, release)
            .search(query, 8, LocalPlaceIndex.Completion.PARTIAL, companyName);
        return hits.isEmpty() ? null : hits.get(0).sourcePlaceId();
    }

    @Test
    @DisplayName("a typed state NAME still finds the address, even though it is also a business word")
    void stateNameInAGeographicSlotIsMatchedAsItsCode() throws Exception {
        /*
          THE SECOND HALF OF THE ORIGINAL BUG REPORT.

          "8581 Laguna Grove Drive, Elk Grove, California - nothing shows up". The Drive/Dr half was
          fixed; this half was not. A place stores the region CODE ("ca") and never the word
          "california", so the typed word has to be translated. That translation only ran when the
          word had no postings of its own, and "california" has 19,377 in business names, 14,865 in
          streets and 1,802 in city names - so it looked like a perfectly ordinary identity token,
          stayed a required constraint, and no row in California could satisfy it.

          Verified against the real 16.1M-row release: the full address returned 0 rows while the
          same address without the state returned the dealership immediately.

          The fixture needs California to appear in business text for this to reproduce, which is
          what p-calpizza is for. Without it the token has no postings, the old condition is
          satisfied for the wrong reason, and the bug is invisible.
        */
        assertEquals("p-laguna2", topId("8581 Laguna Beach Rd, San Diego, California"),
            "a typed state name must not exclude every place in that state");

        // The code spelling keeps working, and so does the query without any state.
        assertEquals("p-laguna2", topId("8581 Laguna Beach Rd, San Diego, CA"));
        assertEquals("p-laguna2", topId("8581 Laguna Beach Rd, San Diego"));

        // And the state is still a CONSTRAINT, not a word that gets dropped: this address is in
        // California, so naming a different state must find nothing rather than ignore it.
        assertTrue(find("8581 Laguna Beach Rd, San Diego, Texas").isEmpty(),
            "the state has to keep constraining, or it may as well not be typed");

        // A business whose own name contains a state name is still findable by that name.
        assertEquals("p-calpizza", topId("California Pizza Kitchen"));
    }
}
