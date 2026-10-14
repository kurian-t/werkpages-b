package org.werkpages.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Build and query tokenisation must agree, asserted by running BOTH.
 *
 * <p>An earlier version of this test compared Java against expected values copied by hand from a
 * DuckDB session. That cannot catch the failure that matters: somebody changing the corpus build's
 * expression. A token the build never wrote can never be looked up, so the index would simply stop
 * answering for whatever the change affected, with every test still green.
 *
 * <p>So this runs the build's real expression through DuckDB over JDBC, in memory, and compares it
 * with {@link LocalPlaceIndex#tokens} on the same fixtures. No corpus, no network, no artifact, so
 * it runs anywhere the suite runs.
 *
 * <p>Two bugs this contract has already caught:
 * <ul>
 *   <li>the build used POSIX {@code [:alnum:]}, which split accented words and dropped CJK
 *       entirely, so "Café Du Monde" was only ever matching on "monde";
 *   <li>omitting {@code \p{M}} made combining marks separators, shattering Devanagari.
 * </ul>
 */
class LocalPlaceIndexTokenizerContractTest {

    /** The fixtures both sides must agree on, chosen to cover the ways this has broken. */
    private static final String[] FIXTURES = {
        "Café Du Monde",            // accented Latin, composed
        "Café",               // the same word decomposed; NFC must fold them together
        "Müller",
        "東京",                      // CJK, dropped entirely by the old expression
        "東京タワー",
        "مطعم الشام",               // Arabic
        "कॉफी हाउस",                 // Devanagari: shattered without \p{M}
        "ร้านกาแฟ",                  // Thai
        "Smith_Jones",              // underscore separates here, unlike Python's \w
        "7-Eleven",
        "AT&T",
        "8581 Laguna Grove Dr",
        "M5C 1G3",                  // postal code with a space
        "   ,, -- ",                // nothing but separators
    };

    private static List<String> duckdbTokens(Connection c, String input) throws Exception {
        // The build's expression, verbatim, with the regex taken from the shared constant.
        String sql = "SELECT unnest(string_split_regex(lower(nfc_normalize(?)), ?))";
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, input);
            ps.setString(2, LocalPlaceIndex.TOKEN_SPLIT.replace("\\\\", "\\"));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String t = rs.getString(1);
                    if (t != null && !t.isEmpty()) out.add(t);
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("the build tokenizer and the query tokenizer produce identical tokens")
    void buildAndQueryAgree() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            for (String fixture : FIXTURES) {
                List<String> fromDuckdb = duckdbTokens(c, fixture);
                List<String> fromJava   = LocalPlaceIndex.tokens(fixture);
                assertEquals(fromDuckdb, fromJava,
                    "build and query disagree on \"" + fixture + "\"; an index built with one and "
                  + "queried with the other cannot answer for this input");
            }
        }
    }

    @Test
    @DisplayName("the fixtures actually exercise the scripts they claim to")
    void fixturesAreMeaningful() throws Exception {
        // Guards against the contract passing because both sides return nothing. A test that
        // compares two empty lists proves only that both are broken the same way.
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            assertEquals(List.of("東京"), duckdbTokens(c, "東京"));
            assertEquals(List.of("कॉफी", "हाउस"), duckdbTokens(c, "कॉफी हाउस"));
            assertEquals(List.of("café"), duckdbTokens(c, "Café"));
        }
    }
}
