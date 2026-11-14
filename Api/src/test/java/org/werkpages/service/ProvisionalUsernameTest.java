package org.werkpages.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE GUARD THAT KEEPS A PLACEHOLDER USERNAME OUT OF PUBLISHED WORK.
 *
 * <p>An account created through Universal Login arrives with a generated name of the shape
 * {@code user_1a2b3c4d} and no username of its own, because Universal Login asks for an email and
 * a password and never for a username. Publishing that name was an explicit condition of the
 * approval not to do: it identifies nobody, it reads as a bug on a profile page, and
 * {@code reviews.author} is a snapshot rather than a join - so choosing a real username later does
 * NOT go back and fix work already published under the placeholder.
 *
 * <p>{@code ManagerService.looksProvisional} is what stands between the two, and it is matched on
 * SHAPE rather than on the {@code username_is_provisional} flag. That is a deliberate trade: the
 * flag is the authority on whether a profile is complete, but reading it would add a query to the
 * submission path, and V99's constraint guarantees the two agree in the direction that matters -
 * a flagged username must look like this, so everything flagged is caught.
 */
class ProvisionalUsernameTest {

    @Test
    void theGeneratedPlaceholderShapeIsRecognised() {
        assertTrue(ManagerService.looksProvisional("user_1a2b3c4d"));
        assertTrue(ManagerService.looksProvisional("user_00000000"));
        assertTrue(ManagerService.looksProvisional("user_ffffffff"));
    }

    /**
     * Exactly eight lowercase hex characters, which is what the callback generates.
     *
     * <p>Each of these is a near miss, and each would be a different bug: too short or too long
     * means the real placeholder stops being caught if the generator ever changes length; uppercase
     * and non-hex mean an ordinary username that merely starts with {@code user_} gets suppressed.
     */
    @Test
    void nearMissesAreNotTreatedAsPlaceholders() {
        assertFalse(ManagerService.looksProvisional("user_1a2b3c4"),   "seven characters");
        assertFalse(ManagerService.looksProvisional("user_1a2b3c4d5"), "nine characters");
        assertFalse(ManagerService.looksProvisional("user_1A2B3C4D"),  "uppercase is not generated");
        assertFalse(ManagerService.looksProvisional("user_zzzzzzzz"),  "not hex");
        assertFalse(ManagerService.looksProvisional("user_1a2b3c4d "), "trailing space");
    }

    /**
     * An ordinary username that happens to begin with {@code user} is NOT suppressed.
     *
     * <p>This is the assertion that stops the guard being over-broad. "user", "username",
     * "user_friendly" and "superuser" are names somebody may legitimately hold, and publishing a
     * pseudonym instead of them would be a silent, permanent substitution they could never
     * diagnose.
     */
    @Test
    void ordinaryUsernamesAreLeftAlone() {
        for (String ordinary : new String[]{
                "user", "username", "user_friendly", "superuser", "user_", "user_x",
                "alice", "bob_the_builder", "a.b-c_d"}) {
            assertFalse(ManagerService.looksProvisional(ordinary),
                "'" + ordinary + "' is a name somebody may hold and must be published as written");
        }
    }

    @Test
    void nullIsNotAPlaceholder() {
        // The author path can reach here with no stored username at all; it must not throw, and
        // it must not claim a missing name is a placeholder.
        assertFalse(ManagerService.looksProvisional(null));
    }
}
