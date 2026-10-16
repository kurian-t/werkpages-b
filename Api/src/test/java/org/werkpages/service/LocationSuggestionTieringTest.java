package org.werkpages.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which tier of the location picker gets searched, and which one leads.
 *
 * <h2>Why these are unit tests and not live probes</h2>
 *
 * <p>Every defect this file guards was found by querying the real corpus and fixed without a single
 * automated test, so the suite stayed green at exactly the same count through all of them. A live
 * probe proves today; it does not stop the bug coming back. These run with no S3 and no network.
 *
 * <h2>What each decision costs when it is wrong</h2>
 *
 * <p>{@code geographyAnswers} decides whether the 16.1M-row buildings file is read. Answer "yes"
 * too readily and workplaces become unfindable; answer "no" too readily and every keystroke pays
 * for a scan. Both failures have happened here, a day apart.
 */
class LocationSuggestionTieringTest {

    private static JsonArray geo(String... nameThenState) {
        JsonArray out = new JsonArray();
        for (int i = 0; i < nameThenState.length; i += 2) {
            out.add(new JsonObject().put("kind", "geo")
                .put("name", nameThenState[i])
                .put("stateName", nameThenState[i + 1])
                /*
                  A POPULATION, because a real corpus row carries one and the gate now reads it.

                  Worldwide geography made the gate unsafe without it: there is a hamlet somewhere
                  on earth beginning with almost any brand name, so "McDonalds" matched McDonalds
                  Corner in Papua New Guinea - population 0 - and business search was skipped
                  entirely. The corpus's population is the evidence that somebody typed a CITY.

                  These fixtures are inhabited places, so they carry a figure. The assertions below
                  are unchanged; a row without one would now represent something the corpus does not
                  return.
                */
                .put("population", 100_000L));
        }
        return out;
    }

    // ── the gate: is the coarse answer the thing that was typed ───────────────

    @Test
    @DisplayName("a city query is answered by geography, so the buildings file is skipped")
    void aCityQuerySkipsPlaces() {
        // The whole point of the gate. "Toronto" is answered by Toronto, and reading 16.1M rows to
        // offer a bar and grill nobody asked about is what made typing a city take seconds.
        assertTrue(ManagerService.geographyAnswers(geo("Toronto", "Ontario"), "Toronto, Ontario"));
        assertTrue(ManagerService.geographyAnswers(geo("Toronto", "Ontario"), "toronto"));
    }

    @Test
    @DisplayName("an employer sharing a word with a place does NOT count as answered")
    void anEmployerQueryStillSearchesPlaces() {
        /*
          The production failure. The gate used to be `geography.isEmpty()`, so the hamlet of Goose
          Cove matching "goose" suppressed the entire business search and the sixteen Canada Goose
          stores in the corpus - one at confidence 0.999 - were never looked for.
        */
        JsonArray gooseHamlets = geo("Goose Cove", "Newfoundland and Labrador",
                                     "Goose Creek", "Manitoba");
        assertFalse(ManagerService.geographyAnswers(gooseHamlets, "Canada Goose"));
    }

    @Test
    @DisplayName("a street address is never answered by a city row")
    void anAddressStillSearchesPlaces() {
        // "Elk Grove" is a real city AND the city in this address. The address is not answered by
        // it, so the building search has to run.
        assertFalse(ManagerService.geographyAnswers(
            geo("Elk Grove", "California"), "8581 Laguna Grove Drive, Elk Grove, California"));
    }

    @Test
    @DisplayName("a geography row with no label field still lets the gate work")
    void theGateReadsTheFieldsARawRowActuallyHas() {
        /*
          The regression test for a bug that shipped and was caught only by instrumentation.

          This read getString("label"), which is null on a raw corpus row - the label is composed
          later, when a suggestion is built. Every comparison was therefore against an empty string,
          the gate never fired, and EVERY keystroke read the buildings file. The benchmark still
          reported 0/14 correct, because reading too much hides nothing; only the per-request
          decision log showed it.
        */
        JsonArray raw = geo("Toronto", "Ontario");
        assertNull(raw.getJsonObject(0).getString("label"), "a raw corpus row has no label yet");
        assertTrue(ManagerService.geographyAnswers(raw, "Toronto"),
                   "the gate must read name and stateName, which is what a raw row carries");
    }

    @Test
    @DisplayName("a missing region does not throw")
    void aRowWithNoRegionIsHandled() {
        JsonArray countryRow = new JsonArray().add(new JsonObject()
            .put("kind", "geo").put("name", "Canada").put("population", 38_000_000L));
        assertTrue(ManagerService.geographyAnswers(countryRow, "Canada"));
        assertFalse(ManagerService.geographyAnswers(countryRow, "Canada Goose"));
    }

    @Test
    @DisplayName("nothing coarse at all means the buildings file must be read")
    void noGeographyMeansSearchPlaces() {
        assertFalse(ManagerService.geographyAnswers(new JsonArray(), "anything"));
        assertFalse(ManagerService.geographyAnswers(null, "anything"));
    }

    /**
     * The compromise, written down so it is a decision rather than a surprise.
     *
     * <p>A business named exactly after a city is not offered at the keystroke where the city name
     * is complete: the coarse row genuinely answers what was typed, so the buildings file is
     * skipped. One more character, and the gate stops matching and the employer appears.
     *
     * <p>This is a deliberate trade. Reading 16.1M rows on every keystroke that happens to spell a
     * city is what the gate exists to avoid. If this limitation ever has to go, the replacement is
     * not "always search places", it is a cheaper retrieval layout.
     */
    @Test
    @DisplayName("KNOWN LIMIT: a business named exactly after a city waits for one more character")
    void aBusinessNamedAfterACityIsDeferredNotLost() {
        JsonArray toronto = geo("Toronto", "Ontario");
        assertTrue(ManagerService.geographyAnswers(toronto, "Toronto"),
                   "at this keystroke the city answers, and places are skipped");
        assertFalse(ManagerService.geographyAnswers(toronto, "Toronto Dominion"),
                    "one more word and the business search runs");
    }

    // ── which tier leads ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a place matching the typed phrase makes this an employer query")
    void aPhraseMatchLeadsWithTheEmployer() {
        /*
          Canada Goose was the fourth row on a list eight long, under Goose Cove, Goose Creek and
          Goose River, because geography held the first three seats unconditionally. One more
          loosely matching hamlet would have pushed the employer off the list entirely.
        */
        JsonArray places = new JsonArray()
            .add(new JsonObject().put("name", "Goose Cove Diner").put("literalHit", false))
            .add(new JsonObject().put("name", "Canada Goose").put("literalHit", true));
        assertTrue(ManagerService.hasLiteralPlace(places));
    }

    @Test
    @DisplayName("places that merely matched the stripped reading do not claim the lead")
    void weakMatchesDoNotLead() {
        // "Northwest Face and Body" contains both words of "North Face" separately. That is not
        // evidence anybody meant it, which is why the signal is a phrase and not a token set.
        JsonArray weak = new JsonArray()
            .add(new JsonObject().put("name", "Northwest Face and Body").put("literalHit", false))
            .add(new JsonObject().put("name", "Face Foundrie").put("literalHit", false));
        assertFalse(ManagerService.hasLiteralPlace(weak));
        assertFalse(ManagerService.hasLiteralPlace(new JsonArray()));
        assertFalse(ManagerService.hasLiteralPlace(null));
    }

    @Test
    @DisplayName("comparison ignores punctuation and case, so a typed comma cannot decide it")
    void comparisonIsPunctuationInsensitive() {
        assertEquals("toronto ontario", ManagerService.collapse("  Toronto,  ONTARIO "));
        assertEquals("", ManagerService.collapse(null));
    }

    // ── a country named by an alias still leads with the country ─────────────

    @Test
    @DisplayName("an alias is a country query, so the country leads instead of businesses")
    void anAliasLeadsWithTheCountry() {
        /*
          THE REPORTED FAILURE, at the tier that caused the visible half of it.

          This method decides which tier takes the seats at the top. Compared literally, "usa" is
          not a prefix of "United States", so a query naming the country was classed as an
          EMPLOYER query - and the eight seats went to Canadian businesses containing those
          letters: USANA Health Sciences, Usang Kitchenware, usagisushi.ca. The country row was
          present and unreachable.

          Resolved through the same Countries table the corpus query uses, so the two halves of
          the feature cannot disagree about what was typed.
        */
        JsonArray unitedStates = new JsonArray().add(new JsonObject()
            .put("kind", "geo").put("name", "United States").put("population", 327_167_434L));

        assertTrue(ManagerService.geographyAnswers(unitedStates, "USA"));
        assertTrue(ManagerService.geographyAnswers(unitedStates, "usa"));
        assertTrue(ManagerService.geographyAnswers(unitedStates, "U.S.A."));
        assertTrue(ManagerService.geographyAnswers(unitedStates, "US"));
        assertTrue(ManagerService.geographyAnswers(unitedStates, "America"));
        assertTrue(ManagerService.geographyAnswers(unitedStates, "United States"));
    }

    @Test
    @DisplayName("UK leads with the United Kingdom, not with a place called Kingdom")
    void ukLeadsWithTheCountry() {
        /*
          "United Kingdom" previously returned Kingdom, Illinois; Kingdom City, Missouri; and
          Kingdom Come, Kentucky. That happened because an old process fell back to a CA/US/GB
          shortlist and, searching under the United States, stripped the country's own words from
          the query - leaving "kingdom". These assertions pin the tier decision for both the alias
          and the full name so the country is what leads either way.
        */
        JsonArray unitedKingdom = new JsonArray().add(new JsonObject()
            .put("kind", "geo").put("name", "United Kingdom").put("population", 66_488_991L));

        assertTrue(ManagerService.geographyAnswers(unitedKingdom, "UK"));
        assertTrue(ManagerService.geographyAnswers(unitedKingdom, "Britain"));
        assertTrue(ManagerService.geographyAnswers(unitedKingdom, "United Kingdom"));
    }

    @Test
    @DisplayName("a business whose name merely starts with an alias is still an employer query")
    void anEmployerNamedAfterACountryIsNotACountryQuery() {
        /*
          The other direction, and the one that would break employer search if alias resolution
          were loosened to substrings. "USA Today" is a newspaper and somebody typing it wants the
          newspaper; resolution is therefore all-or-nothing on the whole string.
        */
        JsonArray unitedStates = new JsonArray().add(new JsonObject()
            .put("kind", "geo").put("name", "United States").put("population", 327_167_434L));

        assertFalse(ManagerService.geographyAnswers(unitedStates, "USA Today"));
        assertFalse(ManagerService.geographyAnswers(unitedStates, "Bank of America"));
    }

    @Test
    @DisplayName("the corpus's own spelling of a country still counts as that country")
    void aCountryIsMatchedByIdentityNotSpelling() {
        /*
          The two datasets do not agree on names, and this is the bug that revealed it.

          The corpus calls NL "The Netherlands"; the JDK calls it "Netherlands". So resolving
          "Holland" to the JDK spelling prefix-matched nothing, and the suggestions led with
          Holland College - a real place in Prince Edward Island - while the country itself sat
          below a hamlet in Singapore called Holland Drive.

          The ISO code is what a corpus row and a typed query actually agree on, so that is what
          decides. Fixing this by editing the corpus would have been the wrong repair: the data is
          not wrong, the two names are just different.
        */
        JsonArray theNetherlands = new JsonArray().add(new JsonObject()
            .put("kind", "geo").put("geoKind", "country").put("name", "The Netherlands")
            .put("countryCode", "NL").put("population", 17_231_017L));

        assertTrue(ManagerService.geographyAnswers(theNetherlands, "Holland"));
        assertTrue(ManagerService.geographyAnswers(theNetherlands, "Netherlands"));
        assertTrue(ManagerService.geographyAnswers(theNetherlands, "NL"));

        // A business whose name merely begins with the alias is still an employer query.
        assertFalse(ManagerService.geographyAnswers(theNetherlands, "Holland College"));
    }
}
