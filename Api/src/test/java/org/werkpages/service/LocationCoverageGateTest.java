package org.werkpages.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which countries the local corpus index is allowed to answer for.
 *
 * <h2>Why this gate exists</h2>
 *
 * <p>The local path treats an EMPTY result as a final answer on purpose - falling through on a
 * genuine miss is what made one search take 26 seconds - which means letting the index answer for
 * a country it does not hold would return nothing and REPLACE the remote result that does cover it.
 * So a narrower index must never shadow a wider one, and the country is checked before the index is
 * consulted at all. The endpoint's {@code country} parameter was simply ignored by the local path
 * when it was first wired in.
 *
 * <h2>What changed, and why these assertions did</h2>
 *
 * <p>This used to assert that the United States was covered and every other country was not,
 * because a single United States release was all that existed. Coverage is now whatever is
 * published under the corpus root: {@code LocalCorpusRegistry} lists the country directories that
 * hold a release, so adding a country is a matter of publishing it rather than editing this code.
 *
 * <p>The old assertions could not be rewritten as "Canada is covered" either, because that depends
 * on what is published on the machine running the tests. What is invariant, and what is tested
 * here, is the SHAPE of the decision: no configured corpus means nothing is covered, an absent or
 * unparseable country is never covered, and the answer is driven by the registry rather than by a
 * hardcoded list. The per-country behaviour is covered against real indexes in
 * {@code LocalPlaceGeographyIntegrationTest}.
 */
class LocationCoverageGateTest {

    @Test
    @DisplayName("with no corpus configured, no country is covered")
    void nothingIsCoveredWithoutACorpus() {
        /*
          LOCATION_LOCAL_SEARCH is off by default and these tests configure no corpus root, so the
          registry is absent and every query must go to the remote path. This is also the
          production posture for the first deployment, where the flag ships disabled.
        */
        assertFalse(ManagerService.coveredByLocalIndex("United States"));
        assertFalse(ManagerService.coveredByLocalIndex("US"));
        assertFalse(ManagerService.coveredByLocalIndex("Canada"));
        assertFalse(ManagerService.coveredByLocalIndex("France"));
    }

    @Test
    @DisplayName("no country at all is never covered, and is not guessed at")
    void noCountryIsNotCovered() {
        /*
          An absent country is NOT taken to be the country of whichever index happens to be open,
          and is not inferred from the business name: a name containing a place is not a request to
          search that place. Those requests go to the remote corpus, which covers the world, and to
          the configured candidate list for buildings - see placesWithoutCountry, which exists
          because skipping buildings entirely here returned zero results for "Walmart".
        */
        assertFalse(ManagerService.coveredByLocalIndex(null));
        assertFalse(ManagerService.coveredByLocalIndex(""));
        assertFalse(ManagerService.coveredByLocalIndex("   "));
    }

    @Test
    @DisplayName("a country that is not a country is not covered")
    void nonsenseIsNotCovered() {
        // iso() returns null for these, and a null code must never be spliced into a partition
        // path or used to open a directory.
        assertFalse(ManagerService.coveredByLocalIndex("Freedonia"));
        assertFalse(ManagerService.coveredByLocalIndex("../../etc"));
        assertFalse(ManagerService.coveredByLocalIndex("U"));
        assertFalse(ManagerService.coveredByLocalIndex("USAA"));
    }
}
