package org.werkpages.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The tokenisation contract, asserted against what the BUILD actually produces.
 *
 * <p>Build and query must agree exactly or the index is unusable: a token the build never wrote
 * can never be looked up. These expectations are the measured output of the corpus build's
 * {@code string_split_regex(lower(nfc_normalize(x)), '[^\p{L}\p{N}\p{M}]+')} on the same inputs,
 * so a change on either side that breaks agreement fails here.
 *
 * <p>Two bugs this already caught:
 * <ul>
 *   <li>the build used a POSIX {@code [:alnum:]} class, which split accented words and dropped CJK
 *       entirely, so no Japanese business was indexable and "Café Du Monde" matched only on
 *       "monde";
 *   <li>omitting {@code \p{M}} made combining marks act as separators, shattering Devanagari into
 *       single consonants.
 * </ul>
 */
class LocalPlaceIndexTokenizerTest {

    @Test
    @DisplayName("Latin with accents, composed and decomposed, agree with the build")
    void latinAccents() {
        assertEquals(List.of("café", "du", "monde"), LocalPlaceIndex.tokens("Café Du Monde"));
        // Decomposed input must normalise to the same token the build stored.
        assertEquals(List.of("café"), LocalPlaceIndex.tokens("Café"));
        assertEquals(List.of("müller"), LocalPlaceIndex.tokens("Müller"));
    }

    @Test
    @DisplayName("CJK, Arabic, Devanagari and Thai survive")
    void nonLatinScripts() {
        assertEquals(List.of("東京"), LocalPlaceIndex.tokens("東京"));
        assertEquals(List.of("مطعم", "الشام"), LocalPlaceIndex.tokens("مطعم الشام"));
        // Would be [क, फ, ह, उस] without \p{M}.
        assertEquals(List.of("कॉफी", "हाउस"), LocalPlaceIndex.tokens("कॉफी हाउस"));
        assertEquals(List.of("ร้านกาแฟ"), LocalPlaceIndex.tokens("ร้านกาแฟ"));
    }

    @Test
    @DisplayName("an underscore separates, which is where this differs from Python's \\w")
    void underscoreSeparates() {
        // Recorded deliberately: Python's \w KEEPS underscore, so a prototype written in Python
        // and this contract disagree on "Smith_Jones". The build splits, so this splits.
        assertEquals(List.of("smith", "jones"), LocalPlaceIndex.tokens("Smith_Jones"));
    }

    @Test
    @DisplayName("punctuation and digits behave as the build does")
    void punctuationAndDigits() {
        assertEquals(List.of("7", "eleven"), LocalPlaceIndex.tokens("7-Eleven"));
        assertEquals(List.of("at", "t"), LocalPlaceIndex.tokens("AT&T"));
        assertEquals(List.of(), LocalPlaceIndex.tokens("   ,, -- "));
        assertEquals(List.of(), LocalPlaceIndex.tokens(null));
    }
}
