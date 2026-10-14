package org.werkpages.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which countries the local corpus index is allowed to answer for.
 *
 * <h2>Why this gate exists</h2>
 *
 * <p>The release is built from {@code places/country_code=US} only. The local path treats an EMPTY
 * result as a final answer on purpose - falling through on a genuine miss is what made one search
 * take 26 seconds - which means letting the index answer a Canadian query would return nothing and
 * REPLACE the remote result that does cover Canada.
 *
 * <p>So a narrower index must never shadow a wider one, and the country is checked before the index
 * is consulted at all. The endpoint's {@code country} parameter was simply ignored by the local
 * path when it was first wired in.
 *
 * <p>Matched on the DISPLAY name because that is what this pipeline passes around: the corpus
 * fallback list is widened to display names precisely so a suggestion does not end up labelled
 * "Toronto, Ontario, CA" and stored as "CA" while every other row says "Canada".
 */
class LocationCoverageGateTest {

    @Test
    @DisplayName("the United States is covered, by display name or code")
    void unitedStatesIsCovered() {
        assertTrue(ManagerService.coveredByLocalIndex("United States"));
        assertTrue(ManagerService.coveredByLocalIndex("united states"));
        assertTrue(ManagerService.coveredByLocalIndex("  United States  "));
        assertTrue(ManagerService.coveredByLocalIndex("US"));
        assertTrue(ManagerService.coveredByLocalIndex("us"));
        assertTrue(ManagerService.coveredByLocalIndex("USA"));
        assertTrue(ManagerService.coveredByLocalIndex("United States of America"));
    }

    @Test
    @DisplayName("every other country falls to the remote path")
    void othersAreNotCovered() {
        assertFalse(ManagerService.coveredByLocalIndex("Canada"));
        assertFalse(ManagerService.coveredByLocalIndex("CA"));
        assertFalse(ManagerService.coveredByLocalIndex("United Kingdom"));
        assertFalse(ManagerService.coveredByLocalIndex("GB"));
        assertFalse(ManagerService.coveredByLocalIndex("Ireland"));
        assertFalse(ManagerService.coveredByLocalIndex("Australia"));
    }

    @Test
    @DisplayName("no country at all leaves the decision to the widening path")
    void noCountryIsNotCovered() {
        // appendCorpusSuggestions widens across the configured country list when it has none, and
        // pre-empting that with a US-only answer would hide every non-US city.
        assertFalse(ManagerService.coveredByLocalIndex(null));
        assertFalse(ManagerService.coveredByLocalIndex(""));
        assertFalse(ManagerService.coveredByLocalIndex("   "));
    }
}
