package org.ratemymanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LevenshteinUtilTest {

    // ── identity ──────────────────────────────────────────────────────────────

    @Test
    void sameString_distanceIsZero() {
        assertEquals(0, LevenshteinUtil.distance("hello", "hello"));
    }

    @Test
    void emptyStrings_distanceIsZero() {
        assertEquals(0, LevenshteinUtil.distance("", ""));
    }

    @Test
    void emptyVsNonEmpty_distanceIsLength() {
        assertEquals(5, LevenshteinUtil.distance("", "hello"));
        assertEquals(5, LevenshteinUtil.distance("hello", ""));
    }

    // ── single edits ──────────────────────────────────────────────────────────

    @Test
    void oneSubstitution_distanceIsOne() {
        assertEquals(1, LevenshteinUtil.distance("cat", "bat"));
    }

    @Test
    void oneDeletion_distanceIsOne() {
        assertEquals(1, LevenshteinUtil.distance("hello", "hell"));
    }

    @Test
    void oneInsertion_distanceIsOne() {
        assertEquals(1, LevenshteinUtil.distance("hell", "hello"));
    }

    // ── multiple edits ────────────────────────────────────────────────────────

    @ParameterizedTest(name = "distance(\"{0}\", \"{1}\") == {2}")
    @CsvSource({
        "kitten,    sitting,   3",
        "saturday,  sunday,    3",
        "intention, execution, 5",
        "abc,       xyz,       3",
        "abcdef,    azced,     3",
    })
    void knownDistances(String a, String b, int expected) {
        assertEquals(expected, LevenshteinUtil.distance(a, b));
    }

    // ── case insensitivity ────────────────────────────────────────────────────

    @Test
    void caseInsensitive_upperLowerSameString_distanceIsZero() {
        assertEquals(0, LevenshteinUtil.distance("Hello", "hello"));
        assertEquals(0, LevenshteinUtil.distance("HELLO", "hello"));
        assertEquals(0, LevenshteinUtil.distance("HeLLo", "hElLO"));
    }

    @Test
    void caseInsensitive_differentContent_sameAsFoldedDistance() {
        // "John Smith" vs "john smith" → same after lowercasing → 0
        assertEquals(0, LevenshteinUtil.distance("John Smith", "john smith"));
        // "John Smith" vs "Jon Smith" → 1 deletion
        assertEquals(1, LevenshteinUtil.distance("John Smith", "Jon Smith"));
    }

    // ── whitespace handling (trimmed internally) ──────────────────────────────

    @Test
    void leadingTrailingWhitespace_trimmed() {
        assertEquals(0, LevenshteinUtil.distance("  hello  ", "hello"));
        assertEquals(0, LevenshteinUtil.distance("hello", "  hello  "));
    }

    // ── symmetry ─────────────────────────────────────────────────────────────

    @Test
    void distanceIsSymmetric() {
        assertEquals(
            LevenshteinUtil.distance("kitten", "sitting"),
            LevenshteinUtil.distance("sitting", "kitten")
        );
        assertEquals(
            LevenshteinUtil.distance("abc", "xyz"),
            LevenshteinUtil.distance("xyz", "abc")
        );
    }

    // ── manager-name realistic cases ─────────────────────────────────────────

    @ParameterizedTest(name = "manager duplicate detection: \"{0}\" vs \"{1}\" ≤ 2 edits")
    @CsvSource({
        "Tim Cook,     Tim Cook,   0",
        "Tim Cook,     Tim Cook,   0",
        "Jennifer Lee, Jenifer Lee, 1",  // typo
        "Mike Chen,    Mike Chan,   1",  // close
        "Robert Brown, Bob Brown,   3",  // nickname
    })
    void managerNameSimilarity(String a, String b, int maxExpectedDistance) {
        int dist = LevenshteinUtil.distance(a, b);
        assertTrue(dist <= maxExpectedDistance + 1,
            "distance(" + a + ", " + b + ") = " + dist + " — unexpectedly large");
    }

    private void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
