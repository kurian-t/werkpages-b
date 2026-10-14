package org.werkpages.service;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.werkpages.service.ManagerMatcher.*;

/**
 * The 2-3 character shared-initial surname rule is WEAK EVIDENCE, and must behave like it.
 *
 * <p>It exists so that "Daniel Pa" surfaces "Daniel Perovic" instead of minting a second Daniel.
 * The risk is the opposite failure: a common given name plus a two-letter surname matching so many
 * people that every such search drowns in candidates, or - worse - that legitimate new managers can
 * never be created because something always looks close enough.
 */
class WeakSurnameRuleTest {

    @Test
    @DisplayName("it produces CANDIDATES, never confirmed identity")
    void neverConfirms() {
        assertEquals(NameClass.PLAUSIBLE, classifyName("Daniel", "Pa", "Daniel Perovic"));
        assertNotEquals(NameClass.CONFIRMED, classifyName("Daniel", "Pa", "Daniel Perovic"),
            "sharing an initial is not identity and must never authorise anything");
    }

    @Test
    @DisplayName("a common first name with a short surname does not match everybody")
    void doesNotOverwhelm() {
        // 200 Johns with assorted surnames, 40 of them beginning with P
        List<String> db = new ArrayList<>();
        String[] surnames = {"Perovic","Patel","Price","Peters","Smith","Jones","Brown","Williams",
                             "Garcia","Li","Nguyen","Okafor","Murphy","Kowalski","Rossi","Tanaka"};
        for (int i = 0; i < 200; i++) db.add("John " + surnames[i % surnames.length]);

        long matched = db.stream()
            .filter(n -> classifyName("John", "Pa", n) != NameClass.NONE).count();
        long total   = db.size();

        // Only the P-surnames may match, and only via the weak rule
        assertTrue(matched > 0, "the rule should still surface the plausible ones");
        assertTrue(matched < total / 3,
            "a two-letter surname matched " + matched + " of " + total + " Johns - that is not a "
            + "candidate list, it is the whole directory");
        for (String n : db) {
            if (classifyName("John", "Pa", n) != NameClass.NONE)
                assertTrue(n.toLowerCase().contains(" p"),
                    "only surnames starting with P may match 'Pa', but matched: " + n);
        }
    }

    @Test
    @DisplayName("it does not block legitimate new managers at other employers")
    void doesNotBlockNewPeople() {
        // "John Pa..." exists. A genuinely different John Patel elsewhere must still be creatable
        // when company AND title differ - the weak rule only downgrades a CONFIRMED name decision,
        // and this name is not confirmed at all.
        assertEquals(NameClass.PLAUSIBLE, classifyName("John", "Pa", "John Perovic"));
        // but a full, different surname is simply not a match
        assertEquals(NameClass.NONE, classifyName("John", "Patel", "John Perovic"),
            "two complete, different surnames are two different people");
    }

    @Test
    @DisplayName("the rule is bounded to short typed surnames only")
    void onlyShortTypedSurnames() {
        assertEquals(NameClass.PLAUSIBLE, classifyName("Daniel", "Per", "Daniel Perovic")); // prefix anyway
        assertEquals(NameClass.NONE, classifyName("Daniel", "Pxyz", "Daniel Perovic"),
            "four characters is long enough to be judged on its merits, and 'Pxyz' is not Perovic");
        assertEquals(NameClass.NONE, classifyName("Daniel", "P", "Daniel Perovic"),
            "a single initial matches far too much to be evidence of anything");
    }
}
