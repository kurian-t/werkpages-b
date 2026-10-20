package org.werkpages.repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Countries, regions and localities, served from an immutable SQLite file.
 *
 * <h2>Why SQLite and not DuckDB</h2>
 *
 * <p>Every query this index answers is a PREFIX RANGE over a sorted key: somebody has typed
 * "uta" and we need the rows whose normalised name starts with it. SQLite serves that from a
 * B-tree index - {@code EXPLAIN QUERY PLAN} reports {@code SEARCH ... USING INDEX} - while DuckDB
 * answers the same predicate with a sequential scan, because its ART index does not cover LIKE or
 * range patterns on strings. Measured on the same 5.2M rows: <b>0.009ms against 18.8ms</b>.
 *
 * <p>DuckDB is unchanged for the per-country places partitions, where columnar compression makes
 * it roughly half the size of the equivalent SQLite file. The two engines are used where each
 * wins, which is why both are present.
 *
 * <h2>Prefix search is a RANGE, never a LIKE</h2>
 *
 * <p>{@code norm >= 'uta' AND norm < 'uta' || X'7F'} uses the index; {@code norm LIKE 'uta%'}
 * does not reliably, and a leading-wildcard LIKE never can. The upper bound is the key plus a
 * high code point, which is why this never interpolates the pattern into SQL.
 *
 * <h2>Prominence does not come from this class</h2>
 *
 * <p>{@code importance} is computed when the index is BUILT, from population, with a region's
 * taken from the localities inside it and rescaled to its country's real population. Every region
 * row in the source has a null population, which is why ranking by population alone put Utah below
 * hamlets called Utah, and why coverage differences made Ethiopian regions rank below Australian
 * ones. None of that belongs in a query.
 */
public final class GeographyIndex implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(GeographyIndex.class.getName());

    /** One row. A geographic entity has no stable id in the corpus, so the natural key identifies it. */
    public record Hit(String name, String kind, String countryCode, String stateCode,
                      String stateName, long population, double importance, String norm,
                      long id, boolean viaAlias, int editDistance) {

        /** The identity assertions use: kind, country, subdivision and name. */
        public String identity() { return kind + "|" + countryCode + "|" + stateCode + "|" + name; }
    }

    private final String url;
    /*
      One connection per thread, opened lazily and never shared.

      A SQLite connection is not safe to use from two threads at once. The file is read-only and
      immutable, so there is no writer to contend with and no locking cost: every reader sees the
      same bytes for the life of the release.
    */
    private final ThreadLocal<Connection> perThread = new ThreadLocal<>();
    private final List<Connection> opened = new ArrayList<>();

    public GeographyIndex(String path) {
        this.url = "jdbc:sqlite:file:" + path + "?mode=ro&immutable=1";
    }

    private Connection conn() throws SQLException {
        Connection c = perThread.get();
        if (c == null || c.isClosed()) {
            /*
              Read-only comes from the URL (mode=ro), not from setReadOnly().

              sqlite-jdbc rejects setReadOnly() after a connection exists - "Cannot change
              read-only flag after establishing a connection" - so calling it threw on every
              query and surfaced as "index unavailable".
            */
            c = DriverManager.getConnection(url);
            perThread.set(c);
            synchronized (opened) { opened.add(c); }
        }
        return c;
    }

    /**
     * The one normalisation, shared by the index build and every query.
     *
     * <p><b>Script-aware on purpose.</b> Accents are folded only for Latin text; every other script
     * keeps its letters, digits and combining marks. Stripping marks globally deleted Japanese,
     * Korean, Thai and Arabic names outright - measured at 13.5% of Japanese place records
     * collapsing into a single empty key, with one bogus group holding 214,960 unrelated places.
     * Thai vowel marks are marks, not diacritics, and removing them changes the word.
     */
    public static String fold(String s) {
        if (s == null) return "";
        /*
          Latin is decided by SCRIPT, not by a code point ceiling.

          The ceiling was U+024F, which puts Latin Extended Additional outside it: "Al Aḩmadī"
          (U+1E29) was classified as non-Latin, kept its marks, and produced "al aḩmadī" while the
          index held "al ahmadi". 55 of 3,110 sampled rows disagreed that way, and every one was a
          Latin name with an extended diacritic - exactly the transliterated Arabic, Turkish,
          Vietnamese and Polish place names this has to get right.
        */
        boolean latinOnly = true;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            if (sc != Character.UnicodeScript.LATIN
                    && sc != Character.UnicodeScript.COMMON
                    && sc != Character.UnicodeScript.INHERITED) {
                latinOnly = false; break;
            }
        }
        /*
          The SAME form the index builder used, which is NOT NFKD.

          The builder applies nfc_normalize, then strips accents for Latin only. NFKD additionally
          decomposes COMPATIBILITY characters: fullwidth ５ becomes 5 and ﬁ becomes fi. Japanese
          addresses are written with fullwidth digits, so a query normalised with NFKD produces
          "5" while the index holds "５" and the lookup silently matches nothing.

          Latin text is decomposed (NFD) so its accents can be removed; everything else is
          composed (NFC), exactly as the builder leaves it.
        */
        String n = Normalizer.normalize(s, latinOnly ? Normalizer.Form.NFD : Normalizer.Form.NFC);
        StringBuilder b = new StringBuilder(n.length());
        boolean space = false;
        for (int i = 0; i < n.length(); ) {
            int cp = n.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            boolean combining = type == Character.NON_SPACING_MARK
                             || type == Character.COMBINING_SPACING_MARK
                             || type == Character.ENCLOSING_MARK;
            if (combining) {
                if (latinOnly) continue;           // a Latin accent: fold it away
                b.appendCodePoint(cp); space = false; continue;   // a Thai vowel: keep it
            }
            if (Character.isLetterOrDigit(cp)) {
                b.appendCodePoint(Character.toLowerCase(cp)); space = false;
            } else if (!space) {
                b.append(' '); space = true;
            }
        }
        return b.toString().trim();
    }

    private static boolean isCommonPunctuation(int cp) {
        int t = Character.getType(cp);
        return t == Character.SPACE_SEPARATOR || t == Character.DASH_PUNCTUATION
            || t == Character.CONNECTOR_PUNCTUATION || t == Character.OTHER_PUNCTUATION;
    }

    /**
     * The exclusive upper bound of a prefix range, for any script.
     *
     * <p>This appended {@code U+007F}, which works only for ASCII. Every CJK, Thai, Korean,
     * Arabic and Cyrillic character sorts ABOVE U+007F, so the range excluded exactly the rows it
     * was meant to find. Measured on a fixture: the prefix 東京 returned <b>0 rows</b> instead of
     * 2, and 서울 returned 0 instead of 1, while ASCII prefixes were unaffected - which is why
     * this would have shipped.
     *
     * <p>Incrementing the last CODE POINT (not the last char, which may be half a surrogate pair)
     * gives the correct bound, because UTF-8 byte order follows code point order and SQLite's
     * default BINARY collation compares bytes.
     */
    static String upper(String prefix) {
        int last = prefix.codePointBefore(prefix.length());
        int cut  = prefix.length() - Character.charCount(last);
        int next = last + 1;
        // Surrogate code points are not characters; step over the whole block.
        if (next >= Character.MIN_SURROGATE && next <= Character.MAX_SURROGATE) {
            next = Character.MAX_SURROGATE + 1;
        }
        if (next > Character.MAX_CODE_POINT) return prefix + "\uDBFF\uDFFF";
        return prefix.substring(0, cut) + new String(Character.toChars(next));
    }

    private static final String COLS =
        "name, kind, country_code, state_code, state_name, population, importance, norm, id";

    /**
     * Rows whose name starts with {@code prefix}, optionally confined to a country or subdivision.
     *
     * <p>The scope is applied only when the QUERY named one. A visitor's own country is never
     * passed here as a filter: an existing location must never prevent somebody searching for a
     * different one, which is the defect that made a field holding "London, Ontario" unable to
     * reach the United Kingdom.
     */
    public List<Hit> byPrefix(String prefix, String countryScope, String stateScope, int limit) {
        if (prefix == null || prefix.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder(
            "SELECT " + COLS + " FROM entity WHERE norm >= ? AND norm < ?");
        if (countryScope != null) sql.append(" AND country_code = ?");
        if (stateScope   != null) sql.append(" AND state_code = ?");
        sql.append(" ORDER BY importance DESC, population DESC LIMIT ?");
        return query(sql.toString(), ps -> {
            int i = 1;
            ps.setString(i++, prefix);
            ps.setString(i++, upper(prefix));
            if (countryScope != null) ps.setString(i++, countryScope);
            if (stateScope   != null) ps.setString(i++, stateScope);
            ps.setInt(i, limit);
        }, false, 0);
    }

    /** Rows reached through an alias: ISO codes, "USA", "UK", a region's own abbreviation. */
    /**
     * One country's own row, by ISO code, with no scope applied.
     *
     * <h2>Why unscoped is the point</h2>
     *
     * <p>This exists for the country a QUERY names rather than the country a form holds. Somebody
     * in Canada typing "USA" has named the United States, and scoping that lookup to Canada
     * returns nothing: the United States row is not in Canada. Every other lookup here is
     * deliberately scoped, and this one deliberately is not.
     *
     * <p>Without it the index was strictly worse than the DuckDB path it replaces. That path
     * admits the named country by its CODE, in its own branch of the predicate, precisely because
     * the corpus stores the country's search text as "united states" and no token of "usa" can
     * reach it. Omitting the equivalent here made a Canadian visitor unable to select the United
     * States at all - a preference turned into a prison.
     */
    public Optional<Hit> country(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) return Optional.empty();
        return Optional.ofNullable(countries().get(countryCode.toUpperCase(Locale.ROOT)));
    }

    /**
     * Every country row, read once and kept.
     *
     * <h2>Why cached rather than queried</h2>
     *
     * <p>There is no index on {@code (kind, country_code)}, so looking one country up scanned the
     * 5.2 million row entity table. Measured through the HTTP endpoint, that put every
     * country-alias query - "USA", "UK", "United States", the most common things anybody types
     * into a location field - at 149-274ms while an ordinary prefix lookup took 0-1ms.
     *
     * <p>There are 252 of them and they are immutable for the life of the published index, so the
     * whole set costs one scan at first use and nothing afterwards. An index on the table would
     * work too and would make the artifact larger for a lookup that is better off not touching
     * disk at all.
     */
    private Map<String, Hit> countries() {
        Map<String, Hit> cached = countryCache;
        if (cached != null) return cached;
        synchronized (this) {
            if (countryCache == null) {
                Map<String, Hit> loaded = new HashMap<>();
                for (Hit h : query("SELECT " + COLS + " FROM entity WHERE kind = 'country'",
                                   ps -> { }, false, 0)) {
                    if (h.countryCode() != null) loaded.put(h.countryCode().toUpperCase(Locale.ROOT), h);
                }
                countryCache = Map.copyOf(loaded);
                LOG.info("geography index: " + countryCache.size() + " country rows cached");
            }
            return countryCache;
        }
    }

    private volatile Map<String, Hit> countryCache;

    public List<Hit> byAlias(String alias, String countryScope, int limit) {
        if (alias == null || alias.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder(
            "SELECT " + COLS + " FROM entity e JOIN alias a ON a.entity_id = e.id "
          + "WHERE a.alias_norm = ?");
        if (countryScope != null) sql.append(" AND e.country_code = ?");
        sql.append(" ORDER BY e.importance DESC LIMIT ?");
        return query(sql.toString().replace("SELECT " + COLS, "SELECT e.name, e.kind, "
                + "e.country_code, e.state_code, e.state_name, e.population, e.importance, "
                + "e.norm, e.id"), ps -> {
            int i = 1;
            ps.setString(i++, alias);
            if (countryScope != null) ps.setString(i++, countryScope);
            ps.setInt(i, limit);
        }, true, 0);
    }

    /**
     * Typo recovery, bounded by a three-character prefix bucket and an edit-distance cap.
     *
     * <p>Deliberately NOT a worldwide fuzzy scan: only the most prominent few hundred rows sharing
     * the first three characters are considered. The caller must rank these below any exact or
     * prefix match, because a correct spelling must never be displaced by a near miss.
     */
    public List<Hit> byTypo(String target, String countryScope, String stateScope, int limit) {
        if (target == null || target.length() < 5) return List.of();
        String pre = target.substring(0, 3);
        int cap = target.length() >= 7 ? 2 : 1;
        /*
          THE SCOPE APPLIES TO GUESSES TOO.

          Without this, a search explicitly restricted to one country could be answered with a
          near-miss from another: the reader asked for Paris in the United States and a fuzzy
          match in France would satisfy it. An explicit restriction is not advice.
        */
        StringBuilder sql = new StringBuilder(
            "SELECT " + COLS + " FROM entity WHERE norm >= ? AND norm < ? "
          + "AND kind IN ('country','region','locality')");
        if (countryScope != null) sql.append(" AND country_code = ?");
        if (stateScope   != null) sql.append(" AND state_code = ?");
        sql.append(" ORDER BY importance DESC LIMIT 600");
        List<Hit> bucket = query(sql.toString(), ps -> {
            int i = 1;
            ps.setString(i++, pre);
            ps.setString(i++, upper(pre));
            if (countryScope != null) ps.setString(i++, countryScope);
            if (stateScope   != null) ps.setString(i, stateScope);
        }, false, 0);
        List<Hit> out = new ArrayList<>();
        for (Hit h : bucket) {
            int d = editDistance(target, h.norm(), cap);
            if (d > 0 && d <= cap) {
                out.add(new Hit(h.name(), h.kind(), h.countryCode(), h.stateCode(), h.stateName(),
                                h.population(), h.importance(), h.norm(), h.id(), false, d));
            }
        }
        out.sort((a, b) -> a.editDistance() != b.editDistance()
                ? Integer.compare(a.editDistance(), b.editDistance())
                : Double.compare(b.importance(), a.importance()));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /**
     * Damerau-Levenshtein distance, abandoned once every cell in a row exceeds {@code cap}.
     *
     * <p>The row minimum includes the first cell deliberately. Excluding it would abandon while a
     * valid path still exists, which trades a correctness bug for a small amount of speed.
     */
    static int editDistance(String a, String b, int cap) {
        if (a.equals(b)) return 0;
        if (Math.abs(a.length() - b.length()) > cap) return cap + 1;
        int[] prev2 = null, prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            int best = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                if (prev2 != null && i > 1 && j > 1
                        && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    cur[j] = Math.min(cur[j], prev2[j - 2] + 1);
                }
                best = Math.min(best, cur[j]);
            }
            if (best > cap) return cap + 1;
            prev2 = prev; prev = cur;
        }
        return prev[b.length()];
    }

    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    private List<Hit> query(String sql, Binder binder, boolean viaAlias, int distance) {
        List<Hit> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Hit(rs.getString(1), rs.getString(2), rs.getString(3),
                                    rs.getString(4), rs.getString(5), rs.getLong(6),
                                    rs.getDouble(7), rs.getString(8), rs.getLong(9),
                                    viaAlias, distance));
                }
            }
        } catch (SQLException err) {
            /*
              An unreadable index is reported, never turned into "no such place".

              A failure that looks like an empty result is the defect this whole area keeps
              producing: the reader is told a place does not exist when in fact nothing was asked.
            */
            LOG.log(Level.WARNING, "geography index query failed", err);
            throw new IllegalStateException("geography index unavailable", err);
        }
        return out;
    }

    @Override public void close() {
        synchronized (opened) {
            for (Connection c : opened) {
                try { c.close(); } catch (SQLException ignored) { }
            }
            opened.clear();
        }
    }
}
