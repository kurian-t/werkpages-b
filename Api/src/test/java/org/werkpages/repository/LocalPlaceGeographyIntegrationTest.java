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
 * How a query's GEOGRAPHY is recognised, resolved and ranked.
 *
 * <p>Separate from {@link LocalPlaceSearchIntegrationTest} because these cover the schema-6
 * alignment tables - {@code city_alias}, {@code geo_place} and the voted {@code region_alias} -
 * and the reader's decision about which words are a business and which are a place. Every case
 * here is a defect that was observed against the real United States and Canadian indexes.
 */
class LocalPlaceGeographyIntegrationTest {

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
    private static List<String> ids(String q) throws Exception {
        return find(q).stream().map(LocalPlaceIndex.Hit::sourcePlaceId).toList();
    }

    @Test
    @DisplayName("the geography corpus's name for a city finds the spelling the index uses")
    void geographyNameDiffersFromTheIndexSpelling() throws Exception {
        /*
          OBSERVED IN PRODUCTION DATA: "Walmart New York City" returned NOTHING.

          The geography corpus calls the city "New York City". The places index spells it
          "new york" for 245,580 rows and "new york city" for 225, so the phrase as typed reached
          0.09% of the city, and "city" became a required identity word that no Walmart carries in
          any field. city_alias links the two names, so either one finds the place.
        */
        assertEquals("p-northface", topId("The North Face, New York City"));
        assertEquals("p-northface", topId("The North Face, New York"));
    }

    @Test
    @DisplayName("the same words are a business in one position and a place in another")
    void sameWordsAsBothNameAndPlace() throws Exception {
        /*
          OBSERVED: "New York Life, New York" returned New Life Baptist Church and Life Pharmacy,
          and New York Life itself was ABSENT.

          Roles were decided per distinct token, so the two occurrences of "new york" collapsed
          into one classification and left "life" as the only identity constraint. Position decides
          now: the head is the business, the component after the comma is the place.
        */
        assertEquals("p-nylife", topId("New York Life, New York"));
        assertFalse(ids("New York Life, New York").contains("p-nyl-ca"),
                    "the San Jose branch is in another city and was excluded by the comma");
        // The no-comma form must reach the same place rather than being a different feature.
        assertEquals("p-nylife", topId("New York Life New York"));
    }

    @Test
    @DisplayName("a STATED place filters, a GUESSED trailing place only ranks")
    void statedGeographyFiltersAndGuessedGeographyRanks() throws Exception {
        /*
          The difference between the two is the comma, and it matters because a trailing place name
          is not proof of geographic intent: "Hotel California", "Paris Baguette" and "London Drugs"
          are business names. Filtering on a guess would delete the only answer anybody wanted, so
          a guess ranks instead, and both readings are searched.

          York Roasters exists in York and in New York, which makes the contrast testable: "York"
          after a comma is stated and excludes the other branch, while the same word merely
          trailing keeps it and puts the named city first.
        */
        assertEquals("p-york-uk", topId("York Roasters, York"));
        assertFalse(ids("York Roasters, York").contains("p-york-us"),
                    "a place written after a comma was stated, so it filters");

        List<String> guessed = ids("York Roasters York");
        assertEquals("p-york-uk", guessed.isEmpty() ? null : guessed.get(0),
                     "the named city still ranks first");
        assertTrue(guessed.contains("p-york-us"),
                   "a guessed trailing place must not discard the literal business match");
    }

    @Test
    @DisplayName("two cities of one name are ordered by the corpus's population")
    void prominenceOrdersSameNamedCities() throws Exception {
        /*
          The fixture holds a Starbucks in London, England (8,961,989) and one in London, Ontario
          (422,324). Neither is more confident than the other and both match the name equally, so
          without prominence the order came down to row order.

          Population comes from the corpus and nothing is invented. geo_place is keyed by every
          ALIAS as well as the corpus's own name, because keyed by the name alone the figure was
          unreachable from what people type: New York City's 8,804,190 sat under "new york city"
          while the query said "new york".
        */
        assertEquals("p-sbux-lon2", topId("Starbucks London"));
        assertEquals("p-sbux-lon2", topId("Starbucks, London"));
    }

    @Test
    @DisplayName("a region is found by its name and by the code the places actually store")
    void regionResolvesByNameAndByCode() throws Exception {
        /*
          region_alias used to be derived from the geography corpus's own region codes, which is
          right for the United States by coincidence and wrong for most of the world: Canada's
          geography codes are NUMERIC, so "ontario" resolved to "08" while every Canadian row
          stores "on", and naming a province filtered to ZERO rows. It is now voted out of the
          data, and carries the bare code too, because "Toronto ON" is how people write it.
        */
        assertTrue(ids("Starbucks, Ontario").contains("p-sbux-tor"));
        assertFalse(ids("Starbucks, Ontario").contains("p-sbux-van"),
                    "Vancouver is in British Columbia");
        assertFalse(ids("Starbucks, Ontario").contains("p-sbux-lon2"),
                    "the London in England is not in Ontario");
        // The code itself, which only resolves because region_alias now carries identity rows.
        assertTrue(ids("Starbucks, ON").contains("p-sbux-tor"));
    }

    @Test
    @DisplayName("an address with a city still finds the building, not just the city")
    void addressSearchSurvivesGeographicRecognition() throws Exception {
        /*
          Business relevance is measured over the IDENTITY words only, so that a place is not
          rewarded for containing the city name. That must not cost address search: an address has
          nothing in a name or a brand, and requiring identity words to appear in those fields
          would refuse it outright.
        */
        assertEquals("p-subaru-eg", topId("8581 Laguna Grove Dr, Elk Grove"));
        assertEquals("p-subaru-eg", topId("8581 Laguna Grove Drive Elk Grove"));
        assertEquals("p-southst",   topId("45 South Street, Halifax"));
    }

    @Test
    @DisplayName("city names fold to one place across spelling and punctuation")
    void foldingUnitesSpellingsOfOneCity() throws Exception {
        /*
          The fold in geo_align.sql and {@link LocalPlaceIndex#fold} must agree exactly, or an
          alias built there matches nothing here. The build carries a cities_unreachable check for
          that, and this asserts the reader's half: accents and punctuation are folded, so the
          names the index holds remain reachable however they are typed.

          Measured on the real data: "montreal" reaches 8,720 places and "montréal" 55,654, so a
          reader that did not fold found one seventh of the city.
        */
        assertEquals("", LocalPlaceIndex.fold(null));
        assertEquals("montreal", LocalPlaceIndex.fold("Montréal"));
        assertEquals("quebec", LocalPlaceIndex.fold("Québec"));
        assertEquals("chicago heights il", LocalPlaceIndex.fold("Chicago Heights, IL"));
        assertEquals("evry courcouronnes", LocalPlaceIndex.fold("Évry-Courcouronnes"));
        // Non-Latin scripts are kept. Folding to ASCII would erase every name in them, and this
        // index is built for every country.
        assertEquals("東京", LocalPlaceIndex.fold("東京"));
        assertEquals("st john s", LocalPlaceIndex.fold("St. John's"));
    }
}
