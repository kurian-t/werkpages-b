package org.werkpages.service;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.werkpages.service.ManagerMatcher.*;

/** The agreed thresholds, as unit tests. No database, no I/O. */
class ManagerMatcherSpecTest {

    @Test @DisplayName("name classes behave as specified")
    void names() {
        assertEquals(NameClass.CONFIRMED, classifyName("Aaron", "Yip", "Aaron Yip"));
        assertEquals(NameClass.CONFIRMED, classifyName("Hélène", "Savard", "Helene Savard"));
        assertEquals(NameClass.CONFIRMED, classifyName("Louise", "O'Dell", "Louise ODell"));
        assertEquals(NameClass.CONFIRMED, classifyName("Jean-Luc", "Picard", "Jean Luc Picard"));
        assertEquals(NameClass.CONFIRMED, classifyName("Wei", "Li", "Wei Li"));
        assertEquals(NameClass.CONFIRMED, classifyName("Ng", "Wu", "Ng Wu"));

        assertEquals(NameClass.PLAUSIBLE, classifyName("Aar", "Yip", "Aaron Yip"));
        assertEquals(NameClass.PLAUSIBLE, classifyName("Aaron", "Yi", "Aaron Yip"));
        assertEquals(NameClass.PLAUSIBLE, classifyName("Aron", "Yip", "Aaron Yip"));
        assertEquals(NameClass.PLAUSIBLE, classifyName("John", "Smythe", "John Smith"));
        assertEquals(NameClass.PLAUSIBLE, classifyName("John", "Smithers", "John Smith"));
        assertEquals(NameClass.PLAUSIBLE, classifyName("Al", "Smith", "Alexander Smith"));

        // distinct people must stay distinct
        assertEquals(NameClass.NONE, classifyName("Michael", "Adams", "Michelle Adams"));
        assertEquals(NameClass.NONE, classifyName("Jon", "Smith", "Jane Smythe"));   // both fuzzy
        assertEquals(NameClass.NONE, classifyName("A", "Smith", "Aaron Smith"));     // 1 char
        assertEquals(NameClass.NONE, classifyName("John", "Smith", "Jane Smith"));
    }

    @Test @DisplayName("titles: equivalent, plausible and genuinely different")
    void titles() {
        assertEquals(TitleClass.EQUIVALENT, classifyTitle("Dir", "Director"));
        assertEquals(TitleClass.EQUIVALENT, classifyTitle("Eng Mgr", "Engineering Manager"));
        assertEquals(TitleClass.EQUIVALENT, classifyTitle("Sr Engineer", "Senior Engineer"));
        assertEquals(TitleClass.EQUIVALENT, classifyTitle("VP Sales", "Vice President Sales"));
        assertEquals(TitleClass.EQUIVALENT, classifyTitle("Prodct Manager", "Product Manager"));

        assertEquals(TitleClass.PLAUSIBLE, classifyTitle("Controller", "Divisional Controller"));
        assertEquals(TitleClass.PLAUSIBLE, classifyTitle("Controller", "Controller, Property Accounting"));
        assertEquals(TitleClass.PLAUSIBLE, classifyTitle("Manager", "Senior Manager"));

        // the trap: same word, different job
        assertEquals(TitleClass.DIFFERENT, classifyTitle("Engineering Manager", "Engineering Director"));
        assertEquals(TitleClass.DIFFERENT, classifyTitle("Engineering Director", "Engineering Manager"));
        assertEquals(TitleClass.DIFFERENT, classifyTitle("Controller", "Software Engineer"));
    }

    @Test @DisplayName("company: verified identity is not plausible similarity")
    void companies() {
        assertEquals(CompanyClass.VERIFIED, classifyCompany(Set.of(5390L), "anything", 5390L, "QuadReal Property Group"));
        assertEquals(CompanyClass.VERIFIED, classifyCompany(Set.of(), "Acme Group", null, "Acme"));
        assertEquals(CompanyClass.PLAUSIBLE, classifyCompany(Set.of(), "Warrington", 5383L, "Warrington PCI Management"));
        assertEquals(CompanyClass.PLAUSIBLE, classifyCompany(Set.of(), "QuadReal", 5390L, "QuadReal Property Group"));
        assertEquals(CompanyClass.DIFFERENT, classifyCompany(Set.of(), "Acme Logistics", 1L, "QuadReal Property Group"));
    }

    // Aaron Yip's real employment records
    private static final List<Employment> AARON = List.of(
        new Employment(5390L, "QuadReal Property Group", "Controller, Property Accounting"),
        new Employment(5383L, "Warrington PCI Management", "Divisional Controller"));

    private static Decision forSearch(String company, String title, Set<Long> verified) {
        return decide(Map.of(NameClass.CONFIRMED, List.of(AARON)), verified, company, title);
    }

    @Test @DisplayName("the four Aaron Yip searches")
    void aaronYip() {
        // the reported bug: a partial title at a former employer must NOT publish
        assertEquals(Outcome.QUEUE_REVIEW, forSearch("Warrington", "Controller", Set.of()).outcome());
        // exact pair -> nothing created
        assertEquals(Outcome.RETURN_EXISTING,
            forSearch("Warrington PCI Management", "Divisional Controller", Set.of(5383L)).outcome());
        // sloppier company name, partial title -> review, not a ghost
        assertEquals(Outcome.QUEUE_REVIEW, forSearch("QuadReal", "Controller", Set.of()).outcome());
        // genuinely different on BOTH axes -> a ghost is permitted
        assertEquals(Outcome.CREATE_GHOST, forSearch("Acme Logistics", "Software Engineer", Set.of()).outcome());
    }

    @Test @DisplayName("a plausible name never publishes, whatever the employment")
    void fuzzyNameNeverPublishes() {
        Decision d = decide(Map.of(NameClass.PLAUSIBLE, List.of(AARON)), Set.of(),
                            "Acme Logistics", "Software Engineer");
        assertEquals(Outcome.QUEUE_REVIEW, d.outcome());
        assertEquals("fuzzy_name", d.reason());
    }

    @Test @DisplayName("Acme/Globex: a company from one job never pairs with a title from another")
    void employmentPairsAreNotRecombined() {
        List<Employment> two = List.of(new Employment(1L, "Acme", "Software Engineer"),
                                       new Employment(2L, "Globex", "Director"));
        Decision d = decide(Map.of(NameClass.CONFIRMED, List.of(two)), Set.of(1L), "Acme", "Director");
        assertNotEquals(Outcome.RETURN_EXISTING, d.outcome(),
            "Acme came from one employment record and Director from another - that pair never existed");
        assertEquals(Outcome.QUEUE_REVIEW, d.outcome());
    }

    @Test @DisplayName("nobody by that name at all -> ghost is permitted")
    void unknownNamePermitsGhost() {
        assertEquals(Outcome.CREATE_GHOST, decide(Map.of(), Set.of(), "Acme", "Engineer").outcome());
    }

    @Test @DisplayName("compound given names and compound surnames both resolve")
    void compoundNames() {
        // compound SURNAME: the split must fall after the given name
        assertEquals(NameClass.CONFIRMED, classifyName("Maria", "De La Cruz", "Maria De La Cruz"));
        assertEquals(NameClass.CONFIRMED, classifyName("Maria", "de la Cruz", "Maria De La Cruz"));
        // compound GIVEN name: the split must fall after both given tokens
        assertEquals(NameClass.CONFIRMED, classifyName("Jean-Luc", "Picard", "Jean Luc Picard"));
        assertEquals(NameClass.CONFIRMED, classifyName("Mary Ann", "Smith", "Mary Ann Smith"));
        // BOTH compound - the case that disproved the earlier "provably sufficient" claim
        assertEquals(NameClass.CONFIRMED, classifyName("Mary Ann", "De La Cruz", "Mary Ann De La Cruz"));
        // a stored middle name makes the given name longer, so the search is a candidate not a match
        assertEquals(NameClass.PLAUSIBLE, classifyName("Aaron", "Yip", "Aaron K Yip"));
        // short legitimate names still work
        assertEquals(NameClass.CONFIRMED, classifyName("Wei", "Li", "Wei Li"));
        // and a genuinely different person is still not a match
        assertEquals(NameClass.NONE, classifyName("Maria", "Cruz", "Pedro De La Cruz"));
    }

    @Test @DisplayName("candidate tokens retrieve every match, including compound names")
    void candidateTokensAreSufficient() {
        assertEquals(List.of("mary", "cruz"), candidateTokens("Mary Ann", "De La Cruz"));
        assertEquals(List.of("jean", "picard"), candidateTokens("Jean-Luc", "Picard"));
        assertEquals(List.of("aaron", "yip"), candidateTokens("Aaron", "Yip"));
    }
}
