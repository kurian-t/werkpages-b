package org.werkpages.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GeographyIndex} against a real index file.
 *
 * <p>Skipped when no index is present, because the artifact is built offline and published rather
 * than committed. Point {@code GEOGRAPHY_INDEX} at one to run these.
 */
class GeographyIndexTest {

    private static GeographyIndex idx;

    @BeforeAll
    static void open() {
        String p = System.getenv("GEOGRAPHY_INDEX");
        Assumptions.assumeTrue(p != null && Files.exists(Path.of(p)),
            "no geography index available; set GEOGRAPHY_INDEX to run these");
        idx = new GeographyIndex(p);
    }

    // ── normalisation, which the index build and every query must share ──────

    @Test
    @DisplayName("Latin accents fold, and other scripts keep their marks")
    void foldIsScriptAware() {
        assertEquals("montreal", GeographyIndex.fold("Montréal"));
        assertEquals("munchen",  GeographyIndex.fold("München"));
        // fold() normalises; it does NOT expand abbreviations. Street-type expansion is a
        // separate, deliberate step so that "st" is not rewritten inside unrelated words.
        assertEquals("gould st", GeographyIndex.fold("Gould St."));
        /*
          Thai vowel marks are MARKS, not diacritics. Stripping combining characters globally
          deleted Japanese, Korean, Thai and Arabic names outright: 13.5% of Japanese place
          records collapsed into one empty key holding 214,960 unrelated places.
        */
        assertFalse(GeographyIndex.fold("กรุงเทพมหานคร").isEmpty(), "Thai must survive folding");
        assertFalse(GeographyIndex.fold("東京都").isEmpty(), "Japanese must survive folding");
        assertFalse(GeographyIndex.fold("서울").isEmpty(), "Korean must survive folding");
        assertEquals("東京都", GeographyIndex.fold("東京都"));
    }

    // ── edit distance, before it is trusted at scale ─────────────────────────

    @Test
    @DisplayName("bounded edit distance handles the cases typo recovery depends on")
    void editDistance() {
        assertEquals(0, GeographyIndex.editDistance("toronto", "toronto", 2));
        assertEquals(1, GeographyIndex.editDistance("tornto", "toronto", 2));
        assertEquals(1, GeographyIndex.editDistance("califronia", "california", 2));  // transposition
        assertEquals(1, GeographyIndex.editDistance("otronto", "toronto", 2));        // at the START
        assertEquals(1, GeographyIndex.editDistance("massachusets", "massachusetts", 2));
        assertEquals(1, GeographyIndex.editDistance("melborne", "melbourne", 2));
        assertTrue(GeographyIndex.editDistance("xyz", "abcdefgh", 2) > 2, "far apart exceeds the cap");
        assertEquals(GeographyIndex.editDistance("ab", "ba", 2),
                     GeographyIndex.editDistance("ba", "ab", 2), "distance is symmetric");
    }

    // ── retrieval ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a prefix finds the significant place, not the shortest name")
    void prefixRanksBySignificance() {
        List<GeographyIndex.Hit> hits = idx.byPrefix("tor", null, null, 5);
        assertFalse(hits.isEmpty());
        /*
          "Torba" in Vanuatu is a province of 1,221 people and a shorter name; Toronto has 2.6
          million. Ranking administrative kind above population put the province first, which is
          why prominence is derived from population for every kind.
        */
        assertEquals("Toronto", hits.get(0).name());
    }

    @Test
    @DisplayName("a region outranks hamlets that share its name")
    void regionBeatsSameNamedHamlets() {
        // Utah the state has a NULL population in the source; the hamlets called Utah have zero.
        List<GeographyIndex.Hit> hits = idx.byPrefix("utah", null, null, 5);
        assertEquals("region", hits.get(0).kind());
        assertEquals("US", hits.get(0).countryCode());
    }

    @Test
    @DisplayName("an explicit country scope confines the search to it")
    void scopeConfines() {
        for (GeographyIndex.Hit h : idx.byPrefix("paris", "US", null, 10)) {
            assertEquals("US", h.countryCode(), "a US-scoped search must not return France");
        }
    }

    @Test
    @DisplayName("aliases resolve to the canonical entity")
    void aliasesResolve() {
        assertEquals("US", idx.byAlias("usa", null, 1).get(0).countryCode());
        assertEquals("GB", idx.byAlias("uk",  null, 1).get(0).countryCode());
        // A region's own abbreviation, derived from its ISO subdivision code at build time.
        assertEquals("region", idx.byAlias("ut", "US", 1).get(0).kind());
    }

    @Test
    @DisplayName("typo recovery finds the prominent destination, bounded")
    void typoRecovery() {
        assertTrue(idx.byTypo("tornto", null, null, 5).stream().anyMatch(h -> h.name().equals("Toronto")));
        assertTrue(idx.byTypo("melborne", null, null, 5).stream().anyMatch(h -> h.name().equals("Melbourne")));
        assertTrue(idx.byTypo("tor", null, null, 5).isEmpty(), "input too short to guess at");
        /*
          AN EXPLICIT SCOPE BINDS GUESSES TOO. A reader who asked for the United States must not
          be answered with a near-miss from somewhere else; a restriction is not advice.
        */
        for (GeographyIndex.Hit h : idx.byTypo("melborne", "US", null, 5)) {
            assertEquals("US", h.countryCode(), "a scoped typo search escaped its scope");
        }
    }

    // ── Unicode prefix ranges ────────────────────────────────────────────────

    @Test
    @DisplayName("the prefix upper bound works for every script, not just ASCII")
    void prefixUpperBoundIsUnicodeSafe() {
        /*
          U+007F was the old bound. Every CJK, Thai, Korean and Cyrillic character sorts above it,
          so "東京" matched 0 rows instead of 2 while ASCII kept working - a defect that would
          have shipped because the English cases all passed.
        */
        assertTrue(GeographyIndex.upper("東京").compareTo("東京都") > 0,
            "東京都 must fall inside the range for the prefix 東京");
        assertTrue(GeographyIndex.upper("서울").compareTo("서울특별시") > 0);
        assertTrue(GeographyIndex.upper("กรุงเทพ").compareTo("กรุงเทพมหานคร") > 0);
        assertTrue(GeographyIndex.upper("tor").compareTo("toronto") > 0);
        // and the bound must still EXCLUDE anything not sharing the prefix
        assertTrue(GeographyIndex.upper("tor").compareTo("tp") < 0);
        assertTrue(GeographyIndex.upper("東京").compareTo("東海") < 0);
    }

    // ── normalisation must agree with the index that was built ───────────────

    @Test
    @DisplayName("Java folding reproduces the normalised keys stored in the index")
    void foldMatchesTheIndexedKeys() {
        /*
          THE TEST THAT MATTERS for international search. An isolated normalisation unit test
          cannot catch a mismatch between the query and the index: both have to produce the same
          key. NFKD would decompose fullwidth digits, so a Japanese address query would normalise
          to "5" while the index holds "５" and match nothing.
        */
        int checked = 0, mismatched = 0;
        StringBuilder examples = new StringBuilder();
        for (String seed : new String[]{"a","b","m","s","t","k","p","montr","zur","sao","krak"}) {
            for (GeographyIndex.Hit h : idx.byPrefix(seed, null, null, 300)) {
                checked++;
                String refolded = GeographyIndex.fold(h.name());
                if (!refolded.equals(h.norm())) {
                    mismatched++;
                    if (examples.length() < 400) {
                        examples.append(String.format("%n  name=%s indexed=%s java=%s",
                                        h.name(), h.norm(), refolded));
                    }
                }
            }
        }
        assertTrue(checked > 500, "expected a meaningful sample, got " + checked);
        assertEquals(0, mismatched,
            "Java folding disagrees with the index for " + mismatched + " of " + checked
            + " rows; query and index must normalise identically:" + examples);
    }
}
