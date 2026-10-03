package org.werkpages.repository;

import io.vertx.core.json.JsonArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocationCorpusRepository} — the parts that need no corpus.
 *
 * <p>These cover the two properties that matter most when the corpus is <em>not</em> reachable,
 * which is the normal state in CI and on any developer machine without AWS credentials: country
 * resolution, and silent failure. The ranking and collapsing behaviour is exercised against real
 * data in the integration suite.
 */
class LocationCorpusRepositoryTest {

    /** Points at a bucket that does not exist, so nothing here can reach S3. */
    private static LocationCorpusRepository unreachable() {
        return new LocationCorpusRepository("bucket-that-does-not-exist-" + System.nanoTime(),
                                            "ca-central-1", "64MB");
    }

    @Test
    @DisplayName("an ISO code passes through, in either case")
    void acceptsIsoCodes() {
        assertEquals("CA", LocationCorpusRepository.iso("CA"));
        assertEquals("CA", LocationCorpusRepository.iso("ca"));
        assertEquals("GB", LocationCorpusRepository.iso(" gb "));
    }

    @Test
    @DisplayName("a display name resolves to its code")
    void resolvesDisplayNames() {
        // The forms hold what somebody confirmed - "Canada" - while the corpus partitions on "CA".
        // Both arrive here, and both have to work.
        assertEquals("CA", LocationCorpusRepository.iso("Canada"));
        assertEquals("GB", LocationCorpusRepository.iso("United Kingdom"));
        assertEquals("US", LocationCorpusRepository.iso("United States"));
        assertEquals("CA", LocationCorpusRepository.iso("canada"));
    }

    @Test
    @DisplayName("an unrecognised country yields null rather than throwing")
    void unknownCountryIsNull() {
        // Null means "offer no corpus suggestions", which is the same soft outcome as an
        // unreachable bucket. Throwing would turn an unfamiliar country name into a failed form.
        assertNull(LocationCorpusRepository.iso("Freedonia"));
        assertNull(LocationCorpusRepository.iso(""));
        assertNull(LocationCorpusRepository.iso("   "));
        assertNull(LocationCorpusRepository.iso(null));
    }

    @Test
    @DisplayName("an unreachable corpus returns no suggestions instead of failing")
    void unreachableCorpusIsSilent() throws Exception {
        /*
          The budget is generous on purpose, and it is not the assertion.

          What this test checks is the OUTCOME - empty results, no exception. Getting there costs
          a one-off `INSTALL httpfs`, which downloads the extension on any machine that has not
          cached it in ~/.duckdb. That is free on a developer box and real work on a clean CI
          runner: the same class runs in 0.3s here with the extension cached and 11s without it,
          and this test failed in CI at sixty seconds because the download plus DuckDB's default
          three S3 retries did not fit.

          Raising the number is right because the wait is a cold engine install, not the behaviour
          under test. The failure path itself is now bounded in LocationCorpusRepository - see the
          http_retries and http_timeout settings there - so this should complete in seconds once
          the engine is up.
        */
        LocationCorpusRepository repo = unreachable();

        JsonArray geo = repo.suggestGeography("kitchener", "CA", null)
            .toCompletionStage().toCompletableFuture().get(180, TimeUnit.SECONDS);
        JsonArray places = repo.suggestPlaces("Walmart", "walmart", "CA")
            .toCompletionStage().toCompletableFuture().get(180, TimeUnit.SECONDS);

        // The whole point: a location field that cannot load suggestions must still let somebody
        // type a city and submit. No corpus is a degraded form, never a broken one.
        assertTrue(geo.isEmpty());
        assertTrue(places.isEmpty());
    }

    @Test
    @DisplayName("a query shorter than two characters never reaches the corpus")
    void tooShortIsEmpty() throws Exception {
        LocationCorpusRepository repo = unreachable();

        assertTrue(repo.suggestGeography("k", "CA", null)
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).isEmpty());
        assertTrue(repo.suggestGeography(null, "CA", null)
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).isEmpty());
        // No country means no partition to read, so there is nothing to ask for.
        assertTrue(repo.suggestPlaces("Walmart", "walmart", null)
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).isEmpty());
    }

    // ── Parsing the query ─────────────────────────────────────────────────────

    /*
      Reported from the running site: typing "toronto" offered "Toronto, Ontario, Canada", and
      typing "toronto, ontario, canada" - the complete, natural thing to write - offered no
      geography at all.

      Two independent causes, both here.
    */

    @Test
    @DisplayName("a comma separates words rather than becoming part of one")
    void commasAreSeparators() {
        /*
          Splitting on whitespace alone made "Toronto, Ontario" the tokens "toronto," and
          "ontario". Each token is matched with LIKE against search_text, which holds no commas,
          so the first matched nothing and the city disappeared - while "toronto ontario" typed
          without punctuation worked perfectly.

          Worse, this is the format the field itself produces: it collapses to
          "Toronto, Ontario, Canada" and reopens the editor holding exactly that, so typing one
          more character into a value we wrote emptied the list.
        */
        assertEquals(List.of("toronto", "ontario", "canada"),
                     LocationCorpusRepository.tokens("toronto, ontario, canada"));
        assertEquals(List.of("toronto", "ontario"),
                     LocationCorpusRepository.tokens("toronto,ontario"));
        // The behaviour that already worked, unchanged.
        assertEquals(List.of("walmart", "kitchener"),
                     LocationCorpusRepository.tokens("walmart kitchener"));
    }

    @Test
    @DisplayName("the country already being searched is not required to appear in the row")
    void theCountryWordIsDropped() {
        /*
          The second cause. Both datasets are partitioned by country and the query reads one
          partition, so every row it can return is in that country - but the country is in neither
          search_text. A locality's is "toronto ontario"; a place's is name, brand, street and
          city. Requiring "canada" to match meant the complete answer returned nothing.
        */
        assertEquals(List.of("toronto", "ontario"),
                     LocationCorpusRepository.withoutCountryWord(
                         List.of("toronto", "ontario", "canada"), "Canada"));

        // Every word of a country written in several, not just the first.
        assertEquals(List.of("austin"),
                     LocationCorpusRepository.withoutCountryWord(
                         List.of("austin", "united", "states"), "United States"));
    }

    @Test
    @DisplayName("REGRESSION: the country word is dropped whether the country is a name or a code")
    void theCountryWordIsDroppedForAnIsoCodeToo() {
        /*
          Two callers, two spellings. The forms pass a display name; the default-country fallback -
          used when the visitor's geography is unknown, which is every local developer since the
          location prefill was removed - passes an ISO code.

          Only the name was recognised, so "kitchener, ontario, canada" searched under "CA" kept
          "canada" as a required substring of a search_text that never contains a country. The city
          did not match and the list led with a car dealership called Kitchener Ford.
        */
        assertEquals(List.of("kitchener", "ontario"),
                     LocationCorpusRepository.withoutCountryWord(
                         List.of("kitchener", "ontario", "canada"), "CA"));
        assertEquals(List.of("austin"),
                     LocationCorpusRepository.withoutCountryWord(
                         List.of("austin", "united", "states"), "US"));
        // Lower case, and the name form, must behave identically.
        assertEquals(List.of("kitchener"),
                     LocationCorpusRepository.withoutCountryWord(List.of("kitchener", "canada"), "ca"));
    }

    @Test
    @DisplayName("a query that is only the country still searches for it")
    void aBareCountryIsStillAQuery() {
        // Somebody who typed just "Canada" is asking for the country, and the country row is
        // matched on its own name. Dropping every token would have asked for everything.
        assertEquals(List.of("canada"),
                     LocationCorpusRepository.withoutCountryWord(List.of("canada"), "Canada"));
    }

    @Test
    @DisplayName("a region word is a qualifier for the places search, not a substring to require")
    void placesDropTheRegionWord() {
        /*
          Reported from the running site: "Google Toronto" offered five offices, and
          "Google Toronto, ontario, canada" offered nothing at all - the more complete and more
          careful the person was, the worse the answer they got.

          A place's search_text is name, brand, street and city. The downtown office reads
          "google google 65 king st e, toronto, on m5c 1g3, canada toronto": the street says "ON",
          never "Ontario". So the region word could not match any row, and requiring it emptied
          the list. The country picks the partition and the city is matched as a word; the region
          is honoured by both rather than required as text.
        */
        Set<String> ontario = Set.of("ontario");

        assertEquals(List.of("google", "toronto"),
                     LocationCorpusRepository.withoutQualifiers(
                         List.of("google", "toronto", "ontario", "canada"), "Canada", ontario));
    }

    @Test
    @DisplayName("a query that is only a region still searches for it")
    void aBareRegionIsStillAQuery() {
        // Stripping every word would ask for everything. Somebody who typed "Ontario" means it.
        assertEquals(List.of("ontario"),
                     LocationCorpusRepository.withoutQualifiers(
                         List.of("ontario"), "Canada", Set.of("ontario")));
    }

    @Test
    @DisplayName("geography keeps the region word, because that is what disambiguates a city")
    void geographyKeepsTheRegion() {
        /*
          The asymmetry is the point, and it follows from what each dataset holds. A locality's
          search_text IS "toronto ontario", and dropping the region there would stop
          "Toronto, Ontario" distinguishing itself from Toronto, Prince Edward Island - both of
          which are real, and both of which the corpus returns.
        */
        assertEquals(List.of("toronto", "ontario"),
                     LocationCorpusRepository.withoutCountryWord(
                         List.of("toronto", "ontario", "canada"), "Canada"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The anchored predicate
    // ══════════════════════════════════════════════════════════════════════════

    private static String clause(int tokens, boolean anchored) throws Exception {
        var m = LocationCorpusRepository.class.getDeclaredMethod("tokensClause", int.class, boolean.class);
        m.setAccessible(true);
        return (String) m.invoke(unreachable(), tokens, anchored);
    }

    @Test
    @DisplayName("an unsorted release keeps the contains predicate it was built for")
    void unsortedUsesContains() throws Exception {
        /*
          The anchored form is only worth using when the corpus is sorted on search_text, because
          anchoring is what lets row-group statistics prune. On an unsorted release an anchored MISS
          still reads the whole partition AND then pays for the contains fallback, which is slower
          than simply doing the contains once.

          So a release that does not declare itself sorted must produce byte-for-byte the query it
          produced before this existed.
        */
        assertEquals("search_text LIKE ?", clause(1, false));
        assertEquals("search_text LIKE ? AND search_text LIKE ?", clause(2, false));
    }

    @Test
    @DisplayName("a sorted release anchors the leading token and only the leading token")
    void sortedAnchorsTheLeadingToken() throws Exception {
        /*
          The SQL is the same shape either way - the difference is in the bound pattern, "denver%"
          rather than "%denver%". What this pins is that the clause is built for the right number of
          tokens in both modes, so the binder and the clause cannot drift apart and produce a
          parameter-count mismatch at runtime.
        */
        assertEquals("search_text LIKE ?", clause(1, true));
        assertEquals("search_text LIKE ? AND search_text LIKE ?", clause(2, true));
        assertEquals("search_text LIKE ? AND search_text LIKE ? AND search_text LIKE ?", clause(3, true));
    }

    @Test
    @DisplayName("no tokens is a query, not a syntax error")
    void zeroTokensIsValidSql() throws Exception {
        // withoutCountryWord can in principle strip everything; "WHERE " with nothing after it is
        // not a query, so the anchored builder answers TRUE rather than the empty string.
        assertEquals("TRUE", clause(0, true));
    }

    @Test
    @DisplayName("an unreachable corpus is treated as unsorted, so it degrades to today's query")
    void unreachableDefaultsToUnsorted() throws Exception {
        /*
          searchTextSorted is read from the release manifest when the connection is established. A
          corpus that was never reached has no manifest, so the flag must stay false - anything else
          would have an unreachable bucket silently change how queries are built.
        */
        var field = LocationCorpusRepository.class.getDeclaredField("searchTextSorted");
        field.setAccessible(true);
        assertEquals(false, field.get(unreachable()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // A house number we do not have must not blank the street
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("REGRESSION: a street number is dropped so the street can still be found")
    void dropsTheHouseNumber() {
        /*
          Reported as "an actual street address just fails to populate all together".

          Overture lists businesses, not every address. The corpus holds 142 and 143 Cedarhill
          Crescent; it does not hold 45. Every token has to match, no row contains "45", and the
          search returned nothing at all - so somebody typing their real address was told their
          street does not exist, with the street sitting right there in the data.
        */
        assertEquals("Cedarhill Crescent", LocationCorpusRepository.stripLeadingNumbers("45 Cedarhill Crescent"));
        assertEquals("Ottawa St N",        LocationCorpusRepository.stripLeadingNumbers("1005 Ottawa St N"));
        // A unit suffix is still a number.
        assertEquals("King St",            LocationCorpusRepository.stripLeadingNumbers("12b King St"));
    }

    @Test
    @DisplayName("nothing is retried when there was no number to drop")
    void noNumberMeansNoRetry() {
        // Null means "this retry would ask the same question again", and the caller skips it
        // rather than paying for a second identical scan.
        assertNull(LocationCorpusRepository.stripLeadingNumbers("Cedarhill Crescent"));
        assertNull(LocationCorpusRepository.stripLeadingNumbers("Toronto"));
    }

    @Test
    @DisplayName("a query that is ONLY a number is not reduced to nothing")
    void aBareNumberIsNotStrippedToNothing() {
        // Searching "45" would otherwise become an empty query, which matches every row.
        assertNull(LocationCorpusRepository.stripLeadingNumbers("45"));
        assertNull(LocationCorpusRepository.stripLeadingNumbers("45 12"));
    }

    @Test
    @DisplayName("a postcode is not mistaken for a house number")
    void postcodesSurvive() {
        // "N2E" and "4E2" contain digits but are not house numbers; dropping them would turn a
        // precise search into a vague one.
        assertNull(LocationCorpusRepository.stripLeadingNumbers("N2E 4E2"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // What the ranking compares against
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * "Wisconsin, United States" could not be selected at all.
     *
     * <p>The WHERE clause matched on the country-stripped words, because no {@code search_text}
     * contains a country. The ORDER BY exact/prefix boost was bound the RAW needle instead, so
     * {@code lower(name) = 'wisconsin united states'} was false for every row and the whole
     * result set fell through to the {@code population DESC NULLS LAST} tie-break.
     *
     * <p>Region rows carry NULL population - all 51 US states do - so the state somebody had
     * just typed in full sorted BELOW every city sharing its prefix, including Wisconsin
     * Junction, population 0. It was returned 4th of 4 and nobody scrolls a picker for the thing
     * they typed exactly.
     *
     * <p>"Ontario, Canada" hid this for months purely because exactly one Canadian row matches
     * {@code ontario%}, so the broken ordering had nothing to get wrong.
     */
    @Test
    @DisplayName("the ranking text drops the country, like the matching text does")
    void rankingNeedleDropsTheCountry() {
        assertEquals("wisconsin",
                     LocationCorpusRepository.rankingNeedle("wisconsin united states", "United States"),
                     "an exactly-typed state has to be comparable to the region row's name");
        // The same query arriving with an ISO code rather than a display name, which is what the
        // default-country fallback passes.
        assertEquals("wisconsin",
                     LocationCorpusRepository.rankingNeedle("wisconsin us", "US"));
        assertEquals("ontario",
                     LocationCorpusRepository.rankingNeedle("ontario canada", "Canada"));
    }

    @Test
    @DisplayName("a query that is only a country keeps its words, so the country row still ranks")
    void rankingNeedleKeepsABareCountry() {
        /*
          withoutCountryWord drops the country only when something else remains. Somebody who
          typed just "Canada" is asking for the country, and the country row is matched on its own
          name - so stripping here would leave an empty string that prefix-matches everything and
          ranks nothing.
        */
        assertEquals("canada", LocationCorpusRepository.rankingNeedle("canada", "Canada"));
    }

    @Test
    @DisplayName("a city plus its region is left alone")
    void rankingNeedleLeavesACityAndRegion() {
        // No country word to drop, so this is unchanged and still ranked by population.
        assertEquals("madison wisconsin",
                     LocationCorpusRepository.rankingNeedle("madison wisconsin", "United States"));
    }

    /**
     * "Montreal, Quebec, Canada" returned no city at all.
     *
     * <p>The corpus stores names as they are spelled - "Montréal", "Québec", "Trois-Rivières" -
     * and {@code normalise} only lowercased. So the thing everybody types, and the exact string
     * this field displays back to them, matched no geography row whatsoever. The query fell
     * through to the places dataset and offered "Montreal South KOA Journey" and "Montreal
     * Martial Arts" instead of Canada's second-largest city.
     *
     * <p>Folded on BOTH sides, so somebody who does type the accent is not then excluded by the
     * very change that helps the people who do not.
     */
    @Test
    @DisplayName("accents are folded, in both directions")
    void foldsAccents() {
        assertEquals("montreal", LocationCorpusRepository.stripAccents("Montréal").toLowerCase());
        assertEquals("quebec",   LocationCorpusRepository.stripAccents("Québec").toLowerCase());
        assertEquals("trois-rivieres",
                     LocationCorpusRepository.stripAccents("Trois-Rivières").toLowerCase());
        // Already plain: unchanged, so the common case cannot be damaged by folding it twice.
        assertEquals("montreal", LocationCorpusRepository.stripAccents("montreal"));
        assertNull(LocationCorpusRepository.stripAccents(null));
    }
}
