package org.werkpages.service;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.werkpages.service.ManagerMatcher.*;

/**
 * The decision must come from CLASSIFICATIONS, never from raw token overlap.
 *
 * <p>An earlier design gated ghost creation on
 * {@code EXISTS(manager_name_tokens(name) && ARRAY['john','smith'])}, which is true whenever ANY
 * token overlaps. "John Williams" shares "john" and "Sarah Smith" shares "smith", yet neither is a
 * credible John Smith: the matcher classifies both NONE. Gating on overlap would therefore have
 * refused to publish a legitimate new manager and queued a review instead - failing to create real
 * people, which is the mirror image of the duplication problem this work exists to fix.
 *
 * <p>These cases are the ones named in review, and they run against the classifier, not the index.
 */
class ManagerDecisionSpecTest {

    /** Builds the classified input the decision takes: name class -> each manager's employments. */
    private static Map<NameClass, List<List<Employment>>> classified(String typedFirst, String typedLast,
                                                                     Map<String, List<Employment>> db) {
        Map<NameClass, List<List<Employment>>> out = new EnumMap<>(NameClass.class);
        db.forEach((name, employments) -> {
            NameClass c = classifyName(typedFirst, typedLast, name);
            if (c != NameClass.NONE)
                out.computeIfAbsent(c, k -> new ArrayList<>()).add(employments);
        });
        return out;
    }

    private static List<Employment> at(String company, String title) {
        return List.of(new Employment(null, company, title));
    }

    @Test
    @DisplayName("token-sharing strangers do NOT block a legitimate ghost")
    void sharedTokenIsNotACandidate() {
        // Both share exactly one token with "John Smith" and neither is a credible match.
        Map<String, List<Employment>> db = new LinkedHashMap<>();
        db.put("John Williams", at("Acme", "Director"));
        db.put("Sarah Smith",   at("Globex", "Analyst"));

        assertEquals(NameClass.NONE, classifyName("John", "Smith", "John Williams"));
        assertEquals(NameClass.NONE, classifyName("John", "Smith", "Sarah Smith"));

        Decision d = decide(classified("John", "Smith", db), Set.of(), "Initech", "Engineer");
        assertEquals(Outcome.CREATE_GHOST, d.outcome(),
            "a raw token EXISTS check would see John Williams and Sarah Smith and refuse to "
            + "publish. Neither is a John Smith, so a genuinely new manager must still be created.");
        assertEquals("no_name_match", d.reason());
    }

    @Test
    @DisplayName("hundreds of token-sharing strangers still do not block it")
    void manySharedTokensStillDoNotBlock() {
        Map<String, List<Employment>> db = new LinkedHashMap<>();
        for (int i = 0; i < 300; i++) db.put("John Sur" + i,  at("Co" + i, "Manager"));
        for (int i = 0; i < 300; i++) db.put("Given" + i + " Smith", at("Co" + i, "Analyst"));

        Decision d = decide(classified("John", "Smith", db), Set.of(), "Initech", "Engineer");
        assertEquals(Outcome.CREATE_GHOST, d.outcome(),
            "600 managers share a token with the search and not one of them is a John Smith");
    }

    @Test
    @DisplayName("a real same-name person does block it, and is returned")
    void realSameNameBlocks() {
        Map<String, List<Employment>> db = new LinkedHashMap<>();
        db.put("John Williams", at("Acme", "Director"));       // noise
        db.put("John Smith",    at("Globex", "Controller"));   // the real one

        Map<NameClass, List<List<Employment>>> c = classified("John", "Smith", db);
        assertEquals(1, c.get(NameClass.CONFIRMED).size(), "only the real John Smith is confirmed");

        // different company AND title -> policy permits a ghost alongside the existing tile
        assertEquals(Outcome.CREATE_GHOST, decide(c, Set.of(), "Initech", "Engineer").outcome());
        // same company, different title -> uncertainty, queue instead
        assertEquals(Outcome.QUEUE_REVIEW, decide(c, Set.of(), "Globex", "Director").outcome());
    }

    @Test
    @DisplayName("Aron Yip with BOTH Aron Yip and Aaron Yip present")
    void exactAndFuzzyTogether() {
        Map<String, List<Employment>> db = new LinkedHashMap<>();
        db.put("Aron Yip",  at("Acme", "Controller"));     // exact
        db.put("Aaron Yip", at("QuadReal", "Controller")); // plausible

        Map<NameClass, List<List<Employment>>> c = classified("Aron", "Yip", db);
        assertEquals(1, c.get(NameClass.CONFIRMED).size(), "Aron Yip is the confirmed match");
        assertEquals(1, c.get(NameClass.PLAUSIBLE).size(),
            "Aaron Yip must ALSO be surfaced - an exact match never suppresses a credible fuzzy one");
    }

    @Test
    @DisplayName("a plausible-only match never publishes, whatever the employment")
    void plausibleOnlyNeverPublishes() {
        Map<String, List<Employment>> db = Map.of("Aaron Yip", at("QuadReal", "Controller"));
        Decision d = decide(classified("Aaron", "Yi", db), Set.of(), "Totally Different", "Chef");
        assertEquals(Outcome.QUEUE_REVIEW, d.outcome());
        assertEquals("fuzzy_name", d.reason());
    }

    @Test
    @DisplayName("a new name sharing one token with existing managers is still new")
    void newNameSharingOneToken() {
        Map<String, List<Employment>> db = Map.of(
            "Priya Raghavan", at("Shopify", "Manager"),
            "Omar Raghavan",  at("Shopify", "Director"));
        // shares the surname with both, but "Nadia" matches neither given name
        Decision d = decide(classified("Nadia", "Raghavan", db), Set.of(), "Shopify", "Manager");
        assertEquals(Outcome.CREATE_GHOST, d.outcome(),
            "sharing a surname with two colleagues does not make somebody an existing manager");
    }
}
