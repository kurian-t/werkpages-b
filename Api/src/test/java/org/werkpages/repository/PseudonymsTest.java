package org.werkpages.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The two ways a stand-in byline is produced, and the properties each one has to have.
 *
 * <p>Both exist to stop one disclosure. A contribution with no byline is rendered by the clients
 * as "Anonymous employee" or "Anonymous candidate", strings worn by no live contribution, so the
 * absence marks out exactly the rows whose author is unknown - every rating written by somebody
 * who deleted their account among them. Reported from production on 2026-11-26.
 */
class PseudonymsTest {

    /** Adjective + Animal + two digits, e.g. KindPanda11. The shape a chosen handle has. */
    private static final Pattern HANDLE = Pattern.compile("[A-Z][a-z]+[A-Z][a-z]+[1-9][0-9]");

    @Test
    void aMintedHandleLooksLikeAHandleSomebodyChose() {
        for (int i = 0; i < 500; i++) {
            String minted = Pseudonyms.mint();
            assertTrue(HANDLE.matcher(minted).matches(),
                "a byline that announces itself as a stand-in - 'Anonymous User', 'anon-1821' - "
                + "discloses what the erasure exists to hide, so the form is the requirement: "
                + "got '" + minted + "'");
        }
    }

    @Test
    void mintingTwiceDoesNotKeepGivingTheSameName() {
        // ONE NAME PER ROW is the property that matters: a handle shared across one erased
        // account's ratings relinks them, so a reader could see that these four opinions are one
        // person. 500 draws from 18,900 names collapsing to one would mean mint() is not drawing.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) seen.add(Pseudonyms.mint());
        assertTrue(seen.size() > 100,
            "500 draws produced only " + seen.size() + " distinct handles");
    }

    @Test
    void aStableHandleIsTheSameEveryTimeForTheSameRow() {
        // The read-time name has the opposite requirement to the minted one. It is computed on
        // every request, so if it varied the same opinion would be signed by somebody new each
        // time the page loaded, which is more obviously invented than the label it replaces.
        String id = "6f1b5d64-1c1a-4a7f-9c9e-2b7a3f5d8e10";
        String first = Pseudonyms.stableFor(id);
        for (int i = 0; i < 50; i++) {
            assertEquals(first, Pseudonyms.stableFor(id), "the same id must give the same name");
        }
        assertTrue(HANDLE.matcher(first).matches(),
            "and it has to look like any other handle: got '" + first + "'");
    }

    @Test
    void differentRowsGetDifferentNames() {
        // Not a uniqueness guarantee - 18,900 names will collide, and two ratings sharing a handle
        // is legitimate. What would be a defect is every row getting the SAME name, which is what
        // a hash that ignores its input, or an index that collapses, would produce.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) seen.add(Pseudonyms.stableFor(UUID.randomUUID().toString()));
        assertTrue(seen.size() > 100,
            "500 distinct ids produced only " + seen.size() + " distinct handles");
    }

    @Test
    void aStableHandleNeverFailsOnAnIdWhoseHashIsNegative() {
        /*
          About half of all hashCode values are negative, and '%' on a negative number yields a
          negative index, which throws. A guard that works for some rows and throws for others is
          the kind of defect that reaches production, because the first few ids tried all worked.
          Math.floorMod is what makes this hold; these two ids hash negative.
        */
        assertTrue(HANDLE.matcher(Pseudonyms.stableFor("zzzzzzzzzzzz")).matches());
        assertTrue(HANDLE.matcher(Pseudonyms.stableFor("￿￿￿")).matches());
        for (int i = 0; i < 2000; i++) {
            String id = UUID.randomUUID().toString();
            assertTrue(HANDLE.matcher(Pseudonyms.stableFor(id)).matches(), id);
        }
    }

    @Test
    void anEmptyOrMissingIdStillProducesAName() {
        // The serializers fall back to "" when a row has no id to read. Returning null here would
        // put the client back on its "Anonymous employee" branch, which is the whole disclosure.
        assertTrue(HANDLE.matcher(Pseudonyms.stableFor("")).matches());
        assertTrue(HANDLE.matcher(Pseudonyms.stableFor(null)).matches());
    }

    @Test
    void noStandInEverContainsTheWordAnonymous() {
        // The requirement as the user stated it: no form of "Anonymous" survives anywhere in the
        // system. Asserted over both generators rather than trusting the word lists by eye.
        for (int i = 0; i < 500; i++) {
            assertNotEquals(true, Pseudonyms.mint().toLowerCase().contains("anon"));
            assertNotEquals(true,
                Pseudonyms.stableFor(UUID.randomUUID().toString()).toLowerCase().contains("anon"));
        }
    }
}
