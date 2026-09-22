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
}
