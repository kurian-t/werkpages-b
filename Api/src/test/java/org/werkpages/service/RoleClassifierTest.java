package org.werkpages.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rules that turn a normalized job title into a family and a seniority.
 *
 * <p>Input here is always what {@code normalize_role_title()} would have produced — lower case,
 * punctuation gone, abbreviations expanded. The classifier never normalizes, so these tests feed
 * it normalized strings directly.
 *
 * <p>The cases that matter most are the ones where a careless implementation goes wrong: titles
 * that look similar but mean different things, short tokens that appear inside longer words, and
 * seniority words that nest inside one another.
 */
class RoleClassifierTest {

    // ══════════════════════════════════════════════════════════════════════════
    // Family
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void readsTheObviousFamilies() {
        assertEquals("engineering", RoleClassifier.family("engineering manager").orElseThrow());
        assertEquals("product",     RoleClassifier.family("product manager").orElseThrow());
        assertEquals("design",      RoleClassifier.family("design director").orElseThrow());
        assertEquals("sales",       RoleClassifier.family("regional sales manager").orElseThrow());
        assertEquals("finance",     RoleClassifier.family("finance manager").orElseThrow());
        assertEquals("hr",          RoleClassifier.family("human resources manager").orElseThrow());
        assertEquals("legal",       RoleClassifier.family("legal counsel").orElseThrow());
        assertEquals("operations",  RoleClassifier.family("operations manager").orElseThrow());
    }

    @Test
    void keepsProductAndProjectApart() {
        // One character apart, completely different jobs. Any fuzzy matching merges these and
        // silently corrupts every number computed downstream — which is why matching is exact.
        assertEquals("product",            RoleClassifier.family("product manager").orElseThrow());
        assertEquals("project_management", RoleClassifier.family("project manager").orElseThrow());
    }

    @Test
    void doesNotMatchShortTokensInsideLongerWords() {
        // "it" inside "unit", "lead" inside "leadership". Substring matching on short tokens is
        // how a classifier starts producing nonsense.
        assertNotEquals("it", RoleClassifier.family("business unit manager").orElse(null));
        assertEquals("manager", RoleClassifier.seniority("business unit manager").orElseThrow());
    }

    @Test
    void prefersTheMoreSpecificFamilyWhenTwoCouldApply() {
        // "engineering program manager" mentions both engineering and program. Engineering is
        // listed first because the domain is the more useful of the two facts.
        assertEquals("engineering", RoleClassifier.family("engineering program manager").orElseThrow());
    }

    @Test
    void classifiesTitlesThatOnlyStateSeniorityAsGeneral() {
        // "Senior Manager" is a real, classifiable role that happens to name no domain. That is
        // "general" — not unknown, which would put it on the work list for an AI pass forever.
        assertEquals("general", RoleClassifier.family("senior manager").orElseThrow());
        assertEquals("general", RoleClassifier.family("director").orElseThrow());
    }

    @Test
    void givesUpOnTitlesItCannotRead() {
        assertTrue(RoleClassifier.family("wizard of light bulb moments").isEmpty());
        assertTrue(RoleClassifier.family("").isEmpty());
        assertTrue(RoleClassifier.family(null).isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Seniority
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void readsTheLadder() {
        assertEquals("lead",           RoleClassifier.seniority("team lead").orElseThrow());
        assertEquals("manager",        RoleClassifier.seniority("engineering manager").orElseThrow());
        assertEquals("senior_manager", RoleClassifier.seniority("senior manager").orElseThrow());
        assertEquals("director",       RoleClassifier.seniority("director of engineering").orElseThrow());
        assertEquals("vp",             RoleClassifier.seniority("vice president of sales").orElseThrow());
        assertEquals("executive",      RoleClassifier.seniority("chief executive officer").orElseThrow());
    }

    @Test
    void doesNotReadVicePresidentAsAnExecutive() {
        // "president" sits inside "vice president". Checking executive first without excluding
        // this would promote every VP to the C-suite.
        assertEquals("vp", RoleClassifier.seniority("vice president").orElseThrow());
        assertEquals("vp", RoleClassifier.seniority("senior vice president of operations").orElseThrow());
        assertEquals("vp", RoleClassifier.seniority("assistant vice president").orElseThrow());
        assertEquals("executive", RoleClassifier.seniority("president").orElseThrow());
    }

    @Test
    void prefersTheMoreSeniorReadingWhenTwoWordsAppear() {
        // "senior director" contains "director" and "senior manager" — director wins because the
        // rules run most senior first.
        assertEquals("director", RoleClassifier.seniority("senior director").orElseThrow());
        assertEquals("executive", RoleClassifier.seniority("managing director").orElseThrow());
    }

    @Test
    void separatesSeniorManagerFromManager() {
        assertEquals("manager",        RoleClassifier.seniority("manager").orElseThrow());
        assertEquals("senior_manager", RoleClassifier.seniority("senior manager").orElseThrow());
        assertEquals("senior_manager", RoleClassifier.seniority("general manager").orElseThrow());
    }

    @Test
    void treatsHeadOfAsDirectorLevel() {
        assertEquals("director", RoleClassifier.seniority("head of engineering").orElseThrow());
    }

    @Test
    void givesUpOnTitlesWithNoSeniorityWord() {
        assertTrue(RoleClassifier.seniority("software engineer").isEmpty());
        assertTrue(RoleClassifier.seniority("").isEmpty());
        assertTrue(RoleClassifier.seniority(null).isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Family and seniority are independent
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void readsBothAxesFromOneTitle() {
        // The reason they are separate columns: this title carries two facts, and one combined
        // enum would need every family multiplied by every level to express it.
        String title = "senior engineering manager";
        assertEquals("engineering",    RoleClassifier.family(title).orElseThrow());
        assertEquals("senior_manager", RoleClassifier.seniority(title).orElseThrow());
    }

    @Test
    void readsAFamilyWithNoSeniority() {
        assertEquals("engineering", RoleClassifier.family("software engineer").orElseThrow());
        assertTrue(RoleClassifier.seniority("software engineer").isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Unclassifiable
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void flagsOnlyTitlesWhereBothAxesFail() {
        assertTrue(RoleClassifier.isUnclassifiable("wizard of light bulb moments"));
        assertFalse(RoleClassifier.isUnclassifiable("engineering manager"));
        assertFalse(RoleClassifier.isUnclassifiable("software engineer"), "family alone is enough");
        assertFalse(RoleClassifier.isUnclassifiable("senior manager"), "seniority alone is enough");
    }

    @Test
    void toleratesUntrimmedInput() {
        assertEquals("engineering", RoleClassifier.family("  engineering manager  ").orElseThrow());
        assertEquals("manager", RoleClassifier.seniority("  engineering manager  ").orElseThrow());
    }
}
