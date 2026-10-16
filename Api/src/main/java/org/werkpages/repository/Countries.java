package org.werkpages.repository;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The ONE place a country becomes an ISO alpha-2 code, whatever form it arrived in.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Resolution used to be a few lines inside {@link LocationCorpusRepository}: accept any two
 * letters as a code, otherwise compare the text against every JDK display name. That rejected the
 * forms people actually type into a location field.
 *
 * <p>Searching <b>USA</b> matched no country at all. It is three letters, so the code branch did
 * not apply, and it is not a JDK display name, so the name branch did not either. The geography
 * corpus holds a row for the United States with a population of 327 million, and the query could
 * not reach it: the field answered with eight Canadian businesses whose names happen to contain
 * the letters - USANA Health Sciences, Usang Kitchenware, usagisushi.ca - and offered no way to
 * pick the country.
 *
 * <p><b>UK was worse than unmatched.</b> Two letters were accepted as a code without checking that
 * the code exists, so "UK" resolved to the partition {@code UK}, which is not an ISO country: the
 * United Kingdom is {@code GB}. The lookup reported success and then addressed a country that is
 * not in the data.
 *
 * <h2>What this does not do</h2>
 *
 * <p><b>It adds no rows to the corpus.</b> All 252 country rows already exist, each with the
 * population that ranks it. The gap was never the data; it was that nothing turned "USA" into
 * "United States" before the query ran.
 *
 * <p><b>It holds no conditions about search.</b> Callers ask it what a country is called and get
 * an answer or null. Ranking, matching and filtering stay where they were.
 *
 * <p><b>It does not guess.</b> Text that resolves to nothing yields null, and the caller then
 * offers no country - the same soft failure as an unreachable bucket, not an error on a form that
 * is otherwise fine.
 */
public final class Countries {

    private Countries() {}

    /**
     * Forms the JDK does not recognise, mapped to ISO alpha-2.
     *
     * <p>One table, here, rather than conditions spread through the search code. Every entry is
     * something somebody types in a location field: an abbreviation, a short name, or a former
     * name still in common use.
     *
     * <p>Keys are the COLLAPSED form, so punctuation and spacing need no entries of their own:
     * "U.S.A.", "U S A" and "usa" all arrive here as {@code usa}.
     *
     * <p><b>England, Scotland and Wales are deliberately absent.</b> They are constituent
     * countries of GB, not ISO countries, and they exist in the corpus as REGIONS - "London,
     * England" resolves through the region path, where England is a state_name. Mapping them to GB
     * here would also hijack England, Arkansas and Wales, Alaska, which are real localities people
     * search for.
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
        // What people type for the two countries this field sees most.
        Map.entry("usa",                                  "US"),
        Map.entry("united states of america",             "US"),
        Map.entry("america",                              "US"),
        Map.entry("uk",                                   "GB"),
        Map.entry("britain",                              "GB"),
        Map.entry("great britain",                        "GB"),
        Map.entry("united kingdom of great britain and northern ireland", "GB"),
        // Abbreviations and short names.
        Map.entry("uae",                                  "AE"),
        Map.entry("emirates",                             "AE"),
        Map.entry("holland",                              "NL"),
        Map.entry("south korea",                          "KR"),
        Map.entry("republic of korea",                     "KR"),
        Map.entry("north korea",                          "KP"),
        Map.entry("dprk",                                 "KP"),
        Map.entry("drc",                                  "CD"),
        Map.entry("democratic republic of congo",         "CD"),
        Map.entry("congo kinshasa",                       "CD"),
        Map.entry("congo brazzaville",                    "CG"),
        Map.entry("republic of congo",                    "CG"),
        // Names that changed, where both forms are still typed.
        Map.entry("russia",                               "RU"),
        Map.entry("russian federation",                   "RU"),
        Map.entry("czechia",                              "CZ"),
        Map.entry("czech republic",                       "CZ"),
        Map.entry("turkey",                               "TR"),
        Map.entry("turkiye",                              "TR"),
        Map.entry("macedonia",                            "MK"),
        Map.entry("swaziland",                            "SZ"),
        Map.entry("eswatini",                             "SZ"),
        Map.entry("burma",                                "MM"),
        Map.entry("ivory coast",                          "CI"),
        Map.entry("cape verde",                           "CV"),
        Map.entry("zaire",                                "CD"),
        // Short forms of longer official names.
        Map.entry("vatican",                              "VA"),
        Map.entry("vatican city",                         "VA"),
        Map.entry("holy see",                             "VA"),
        Map.entry("laos",                                 "LA"),
        Map.entry("syria",                                "SY"),
        Map.entry("iran",                                 "IR"),
        Map.entry("venezuela",                            "VE"),
        Map.entry("bolivia",                              "BO"),
        Map.entry("tanzania",                             "TZ"),
        Map.entry("vietnam",                              "VN"),
        Map.entry("viet nam",                             "VN"),
        Map.entry("brunei",                               "BN"),
        Map.entry("micronesia",                           "FM"),
        Map.entry("moldova",                              "MD"),
        Map.entry("palestine",                            "PS"),
        Map.entry("taiwan",                               "TW"),
        Map.entry("dominican republic",                   "DO")
    );

    /**
     * Every recognised form, collapsed, to ISO alpha-2.
     *
     * <p>Built once from {@link Locale} - the ISO codes themselves and their English names - and
     * then the alias table. The JDK is the authority for the two canonical forms so that a new
     * JDK's renaming of a country does not need an edit here.
     */
    private static final Map<String, String> BY_TEXT = build();

    private static Map<String, String> build() {
        Map<String, String> map = new HashMap<>();
        for (String code : Locale.getISOCountries()) {
            map.put(collapse(code), code);
            String name = englishName(code);
            if (name != null) map.put(collapse(name), code);
        }
        /*
          Aliases never override a country's own code or name.

          "Georgia" is a country, and the US state of the same name must not take that key. Nor may
          a short name shadow a different country's real one, so the JDK's answers win and the
          table below fills only what the JDK leaves empty.
        */
        ALIASES.forEach(map::putIfAbsent);
        return Map.copyOf(map);
    }

    private static String englishName(String code) {
        String name = Locale.of("", code).getDisplayCountry(Locale.ENGLISH);
        return (name == null || name.isBlank() || name.equalsIgnoreCase(code)) ? null : name;
    }

    /**
     * The ISO alpha-2 code for a country named by its code, its English name or a known alias.
     *
     * @return the code, or null when the text names no country
     */
    public static String iso(String text) {
        if (text == null) return null;
        String key = collapse(text);
        if (key.isEmpty()) return null;
        String code = BY_TEXT.get(key);
        if (code != null) return code;
        /*
          AN INITIALISM WRITTEN OUT WITH SEPARATORS.

          "U.S.A." collapses to "u s a", which is a different key from "usa" - so the table
          answered for one spelling of the same abbreviation and not the other. Joining a run of
          single letters is the general rule behind that, and it covers "U.K.", "U.A.E." and
          "U.S." without an entry each: adding four more keys would have left the fifth spelling
          broken in the same way.
        */
        String joined = key.replace(" ", "");
        return joined.length() == key.replaceAll("[^a-z0-9]", "").length()
               && key.matches("(?:[a-z0-9] )+[a-z0-9]")
            ? BY_TEXT.get(joined)
            : null;
    }

    /**
     * The English name the corpus itself uses for a country, from any recognised form of it.
     *
     * <p>This is what makes an alias searchable without duplicating any data: "usa" becomes
     * "United States", which is exactly the {@code name} on the corpus row.
     *
     * @return the English name, or null when the text names no country
     */
    public static String displayName(String text) {
        String code = iso(text);
        return code == null ? null : englishName(code);
    }

    /** Whether this text, in its entirety, names a country. */
    public static boolean namesCountry(String text) {
        return iso(text) != null;
    }

    /**
     * Lower-cased, accent-free, punctuation-free, single-spaced.
     *
     * <p>Accents are folded so "T&uuml;rkiye" and "Turkiye" are one key, and punctuation becomes
     * spacing so "U.S.A." needs no entry of its own.
     */
    static String collapse(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", " ")
            .trim();
    }
}
