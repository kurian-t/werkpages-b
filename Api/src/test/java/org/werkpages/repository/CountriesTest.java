package org.werkpages.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a location field is allowed to call a country.
 *
 * <p>These are regression tests for a reported failure, not a specification exercise. Searching
 * <b>USA</b> in the location field returned eight Canadian businesses - USANA Health Sciences,
 * Usang Kitchenware, usagisushi.ca - and no way to pick the country, although the geography corpus
 * holds a United States row with a population of 327 million. The corpus was never the problem:
 * nothing turned "USA" into "United States" before the query ran.
 */
class CountriesTest {

    // ── the names people type in full ────────────────────────────────────────

    @Test
    @DisplayName("a country's full English name resolves, including the ones reported broken")
    void fullNamesResolve() {
        /*
          Every one of these was named in the bug report. "United States" and "United Kingdom" are
          the two that matter most and the two that failed worst: both are two words, and the old
          resolver compared the whole string against JDK display names only, so a search that
          matched neither fell through to a CA/US/GB shortlist which stripped the country's own
          words from the query. "United Kingdom" became "kingdom" and returned Kingdom, Illinois.
        */
        assertEquals("US", Countries.iso("United States"));
        assertEquals("GB", Countries.iso("United Kingdom"));
        assertEquals("CA", Countries.iso("Canada"));
        assertEquals("FR", Countries.iso("France"));
        assertEquals("DE", Countries.iso("Germany"));
        assertEquals("JM", Countries.iso("Jamaica"));
        assertEquals("GD", Countries.iso("Grenada"));
        assertEquals("IN", Countries.iso("India"));
    }

    @Test
    @DisplayName("case, accents and punctuation do not decide whether a country is recognised")
    void spellingVariationsResolve() {
        assertEquals("US", Countries.iso("united states"));
        assertEquals("US", Countries.iso("  UNITED   STATES  "));
        assertEquals("US", Countries.iso("U.S.A."));
        assertEquals("US", Countries.iso("U S A"));
        assertEquals("TR", Countries.iso("Türkiye"), "accents are folded, not deleted");
    }

    // ── the aliases ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the abbreviations people actually type resolve to the right country")
    void aliasesResolve() {
        assertEquals("US", Countries.iso("USA"));
        assertEquals("US", Countries.iso("US"));
        assertEquals("US", Countries.iso("America"));
        assertEquals("GB", Countries.iso("GB"));
        assertEquals("GB", Countries.iso("Britain"));
        assertEquals("GB", Countries.iso("Great Britain"));
        assertEquals("AE", Countries.iso("UAE"));
        assertEquals("AE", Countries.iso("Emirates"));
        assertEquals("NL", Countries.iso("Holland"));
        assertEquals("KR", Countries.iso("South Korea"));
        assertEquals("CZ", Countries.iso("Czech Republic"));
        assertEquals("CI", Countries.iso("Ivory Coast"));
    }

    @Test
    @DisplayName("UK resolves to GB, and never to the non-country 'UK'")
    void ukIsNotAnIsoCountry() {
        /*
          THE BUG THIS CLASS EXISTS FOR, and it was worse than returning nothing.

          The old resolver accepted ANY two letters as an ISO code without checking that the code
          exists. "UK" therefore resolved to "UK" and the search addressed a country partition of
          that name. There is no such ISO country - the United Kingdom is GB - so the lookup
          reported success and then searched data that does not exist, which is why a UK search
          failed in a way that looked like a ranking problem rather than a lookup one.
        */
        assertEquals("GB", Countries.iso("UK"));
        assertEquals("GB", Countries.iso("uk"));
        assertEquals("GB", Countries.iso("U.K."));
        assertTrue(Set.of(Locale.getISOCountries()).contains(Countries.iso("UK")),
            "whatever UK resolves to must be a real ISO country");
    }

    @Test
    @DisplayName("an alias never shadows a country that owns the name itself")
    void aliasesDoNotOverrideRealCountries() {
        /*
          Georgia is a country AND a US state. The country owns the name, so the alias table must
          not be able to take that key - which is why aliases are merged with putIfAbsent rather
          than put. Asserted because the failure mode is silent: a shadowed name resolves to the
          wrong country and every search under it is quietly wrong.
        */
        assertEquals("GE", Countries.iso("Georgia"));
        assertEquals("IN", Countries.iso("India"));
    }

    @Test
    @DisplayName("every alias points at a real ISO country")
    void everyAliasTargetIsReal() {
        /*
          Guards the table itself against a typo. An entry pointing at "XX" would resolve happily
          here and then address a partition that cannot exist, which is exactly the failure the UK
          case above describes - so the table is checked rather than trusted.
        */
        Set<String> real = Set.of(Locale.getISOCountries());
        for (String alias : new String[] { "USA", "US", "America", "UK", "GB", "Britain",
                "Great Britain", "UAE", "Emirates", "Holland", "South Korea", "North Korea",
                "Czechia", "Russia", "Turkey", "Burma", "Ivory Coast", "Cape Verde", "Laos",
                "Syria", "Iran", "Vietnam", "Taiwan", "Palestine", "Vatican", "DRC" }) {
            String code = Countries.iso(alias);
            assertNotNull(code, alias + " must resolve");
            assertTrue(real.contains(code), alias + " resolved to a non-ISO code: " + code);
        }
    }

    // ── the canonical name, which is what makes an alias searchable ──────────

    @Test
    @DisplayName("an alias becomes the name the corpus itself stores")
    void displayNameIsTheCorpusName() {
        /*
          This is the whole mechanism, and it is why no geography rows had to be added. The corpus
          row for the United States is named exactly "United States", so resolving "usa" to that
          string is what lets the ordinary exact-match ranking put the country first.
        */
        assertEquals("United States", Countries.displayName("usa"));
        assertEquals("United Kingdom", Countries.displayName("uk"));
        assertEquals("United Arab Emirates", Countries.displayName("UAE"));
        assertEquals("Netherlands", Countries.displayName("Holland"));
        assertEquals("Canada", Countries.displayName("CA"));
    }

    // ── what must NOT be read as a country ───────────────────────────────────

    @Test
    @DisplayName("text that merely contains a country name is not a country")
    void partialMatchesAreNotCountries() {
        /*
          Resolution is all-or-nothing on the WHOLE string. "USA Today" is a newspaper and
          "Bank of America" is an employer; reading either as a country would hand a business
          search to the geography tier and bury the thing somebody was looking for.
        */
        assertNull(Countries.iso("USA Today"));
        assertNull(Countries.iso("Bank of America"));
        assertNull(Countries.iso("United States Postal Service"));
        assertNull(Countries.iso("Toronto"));
    }

    @Test
    @DisplayName("nothing in, nothing out")
    void unknownTextYieldsNull() {
        assertNull(Countries.iso(null));
        assertNull(Countries.iso(""));
        assertNull(Countries.iso("   "));
        assertNull(Countries.iso("Freedonia"));
        assertNull(Countries.displayName("Freedonia"));
    }
}
