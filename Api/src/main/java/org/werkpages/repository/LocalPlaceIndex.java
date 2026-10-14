package org.werkpages.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Local place search: token retrieval, eligibility by intersection, geographic constraint, ranking,
 * then hydration of the eight winners.
 *
 * <h2>Why a posting-list intersection rather than substring tests</h2>
 *
 * <p>Requiring every typed word used to mean {@code name_norm LIKE '%word%'} against text carried
 * on every token row. That cost 9.48 GB for the United States alone, which does not fit worldwide,
 * and it was not even exact: "ford" matched "Bradford". An intersection of posting lists is smaller
 * and precise, because a posting exists only for a whole token the build actually wrote.
 *
 * <h2>Geography comes from the corpus, not from a word list</h2>
 *
 * <p>Each posting records the field it came from. A token that appears as a {@code city} or
 * {@code state} posting is geographic evidence, so "Toronto" constrains the city without anybody
 * maintaining a list of city names, and "Company, Toronto, Ontario" disambiguates branches by
 * requiring both. Commas are a hint, never the test: people type "Starbucks Toronto" too.
 *
 * <p>An earlier attempt hard-coded regions and countries, which got "CA" wrong (discarded as Canada
 * inside a US address) and scored any Californian business as highly as the right one in Elk Grove.
 *
 * <h2>Exact beats weak, always</h2>
 *
 * <p>Ranking puts a whole-phrase match on the name or brand above a match that merely contains all
 * the words, and geographic agreement above its absence, before confidence is consulted at all.
 */
public final class LocalPlaceIndex {

    /**
     * THE tokenisation contract, shared with the corpus build and asserted against it by
     * {@code LocalPlaceIndexTokenizerContractTest}, which runs the build's expression through
     * DuckDB and this one through Java on the same fixtures.
     *
     * <p>{@code \p{M}} is deliberate: without it combining marks separate and Indic and Thai text
     * shatters, so "कॉफी हाउस" tokenised as [क, फ, ह, उस] and no Devanagari name was reachable.
     */
    public static final String TOKEN_SPLIT = "[^\\p{L}\\p{N}\\p{M}]+";

    /**
     * Key under which the half-typed final token's prefix totals are returned.
     *
     * <p>Underscores separate under {@link #TOKEN_SPLIT}, so no real token can ever be this and the
     * sentinel cannot collide with corpus data. It was a NUL byte first, which terminated the SQL
     * string literal it was embedded in and broke every query.
     */
    private static final String PREFIX_KEY = "__prefix__";


    /** Fields whose postings mean "this word is a place", used to recognise geographic intent. */
    private static final Set<String> GEO_FIELDS = Set.of("city", "state");

    /**
     * Street-type and directional words, relaxed ONLY inside an address-shaped query.
     *
     * <p>The corpus writes "Dr" where somebody types "Drive", and "S" for "South", so requiring the
     * typed spelling made a real dealership unfindable. They are never dropped globally: that broke
     * "Scotiabank Place" and "North Face", where the same words are the name.
     */
    private static final java.util.Map<String, List<String>> STREET_EQUIV = buildEquivalents(
        "street st", "road rd", "avenue ave av", "drive dr", "boulevard blvd", "lane ln",
        "court ct", "place pl", "crescent cres", "parkway pkwy", "highway hwy", "terrace terr",
        "circle cir", "square sq", "trail trl", "way", "suite ste", "unit", "floor fl",
        "north n", "south s", "east e", "west w",
        "northeast ne", "northwest nw", "southeast se", "southwest sw");

    private static java.util.Map<String, List<String>> buildEquivalents(String... groups) {
        java.util.Map<String, List<String>> m = new java.util.HashMap<>();
        for (String g : groups) {
            List<String> forms = List.of(g.split(" "));
            for (String f : forms) m.put(f, forms);
        }
        return java.util.Map.copyOf(m);
    }

    /**
     * Whether the caller's text is still being typed.
     *
     * <p>Request-scoped and passed in, never stored on this object. It was a mutable field for one
     * revision, which is a latent bug on anything shared between requests: one caller's completed
     * search could decide another caller's half-typed one.
     *
     * <p>{@link #PARTIAL} is autocomplete, where the last word is a prefix of something. {@link
     * #COMPLETE} is a submitted or selected query, where every word is meant as written and a
     * trailing prefix reading would invent matches the person did not ask for.
     */
    public enum Completion { PARTIAL, COMPLETE }

    public record Hit(String sourcePlaceId, String name, String brandName, String street,
                      String city, String stateCode, String postalCode, int rank) {}

    private final Connection conn;
    private final CorpusRelease release;

    /** Both files come from ONE resolved release; see {@link CorpusRelease}. */
    public LocalPlaceIndex(Connection conn, CorpusRelease release) {
        this.conn = conn;
        this.release = release;
    }

    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String norm = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        for (String t : norm.split(TOKEN_SPLIT)) if (!t.isEmpty()) out.add(t);
        return out;
    }

    /** What the corpus knows about one token: how many postings, and in which fields. */
    private record TokenInfo(String token, long count, Set<String> fields, boolean prefix) {}

    /**
     * Counts and fields for every query token, in ONE statement.
     *
     * <p>This used to be two statements per token: a count from {@code tok_freq}, then a
     * {@code SELECT DISTINCT field FROM tok}, which for a common word meant scanning its whole
     * posting list just to learn which fields it appears in. A three word query therefore cost
     * seven round trips, and measured p95 under four concurrent callers was 1.8 seconds.
     *
     * <p>{@code tok_freq} now carries the field, so one indexed read answers both questions for
     * all tokens at once.
     */
    private java.util.Map<String, TokenInfo> describeAll(List<String> toks, String last)
            throws Exception {
        java.util.Map<String, TokenInfo> out = new java.util.LinkedHashMap<>();
        if (toks.isEmpty()) return out;
        StringBuilder sql = new StringBuilder(
            "SELECT FALSE AS is_prefix, token, field, n FROM tok_freq WHERE token IN (");
        for (int i = 0; i < toks.size(); i++) sql.append(i == 0 ? "?" : ",?");
        sql.append(")");
        /*
          The final token may still be being typed, so its prefix range is asked for in the same
          statement. The rows are tagged, because a prefix hit has to be REMEMBERED as one: the
          posting lookup must then use LIKE rather than equality, and losing that tag made
          "Scotiabank Pl" return nothing.
        */
        sql.append(" UNION ALL SELECT TRUE, '" + PREFIX_KEY + "', field, n FROM tok_freq WHERE token LIKE ? || '%'");
        java.util.Map<String, TokenInfo> prefixHits = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            for (String t : toks) ps.setString(i++, t);
            ps.setString(i, last);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boolean isPrefix = rs.getBoolean(1);
                    String tok = rs.getString(2), field = rs.getString(3);
                    long n = rs.getLong(4);
                    java.util.Map<String, TokenInfo> target = isPrefix ? prefixHits : out;
                    TokenInfo prev = target.get(tok);
                    Set<String> fields = prev == null ? new LinkedHashSet<>() : new LinkedHashSet<>(prev.fields());
                    fields.add(field);
                    target.put(tok, new TokenInfo(tok, (prev == null ? 0 : prev.count()) + n,
                                                  fields, isPrefix));
                }
            }
        }
        // Both readings are returned; the caller decides, because an exact hit elsewhere
        // must not stop a half-typed word matching what it is the beginning of.
        out.putAll(prefixHits);
        return out;
    }

    /** One token's entry, resolved through equivalents and region aliases when it has none. */
    private TokenInfo describe(String token, boolean asPrefix) throws Exception {
        java.util.Map<String, TokenInfo> m = describeAll(List.of(token), asPrefix ? token : "\u0000");
        TokenInfo t = m.get(token);
        return t != null ? t : new TokenInfo(token, 0, Set.of(), asPrefix);
    }

    /**
     * A region name resolved to the code the corpus stores, or null.
     *
     * <p>Places carry {@code state_code}, so a person typing "Ontario" or "California" matches
     * nothing while "on" and "ca" match everything. The alias table is built from the corpus's own
     * geography data rather than kept by hand here, so it covers whatever regions the corpus knows
     * and does not drift when it changes.
     *
     * <p>Missing table means no aliases, not an error: a release built before this existed still
     * works, it simply cannot resolve region names.
     */
    private String regionCode(String name) {
        try (PreparedStatement ps = conn.prepareStatement(
                 "SELECT code FROM region_alias WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getString(1); }
        } catch (Exception ignored) {
            // No region_alias table in this release.
        }
        return null;
    }

    /**
     * The phrase the person meant as a place, or null when they named none.
     *
     * <p>Taken from the text after the first comma when there is one, because that is where people
     * put it, and otherwise from the trailing run of words the corpus knows only as places. The
     * comma is a strong hint, never the only one: "Starbucks Toronto" names a city too.
     *
     * <p>Returned as a phrase rather than as words, because "New York" and "Los Angeles" are single
     * names. Treating them as independent tokens let a search for York match New York.
     */
    /**
     * The places the person named, one per comma component, in the order typed.
     *
     * <p>A component keeps its words together: "New York" and "Los Angeles" are single names, and
     * splitting them let a search for York match New York. Components are kept apart because
     * "London, Ontario" is a city and a region, and matching the pair as one phrase found nothing.
     *
     * <p>With no comma, the trailing run of words the corpus knows only as places is taken, so
     * "Starbucks Toronto" still names a city.
     */
    /** Whether the corpus knows this phrase as a place, so a comma is geography and not a name. */
    private boolean isKnownPlace(String phrase) {
        if (phrase.isEmpty()) return false;
        if (regionCode(phrase) != null) return true;
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM geo_name WHERE name = ?")) {
            ps.setString(1, phrase);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (Exception ignored) {
            return false;   // no geo_name table in this release
        }
    }

    private List<String> geographicComponents(String query, List<TokenInfo> geoTokens) {
        List<String> out = new ArrayList<>();
        int comma = query.indexOf(',');
        if (comma >= 0) {
            /*
              A COMMA IS A HINT, AND THE CORPUS DECIDES.

              Treating everything after the first comma as a place turned "Smith, Jones &
              Associates" into a search for Smith in a city called Jones, and "Riverside Clinic,
              123 Main Street" into a search in a city called 123 Main Street. Both returned
              nothing. A component counts as geography only when the corpus actually holds it as a
              city or resolves it as a region; otherwise its words are part of the query.
            */
            List<String> parts = new ArrayList<>();
            for (String part : query.substring(comma + 1).split(",")) {
                String phrase = String.join(" ", tokens(part));
                if (!phrase.isEmpty()) parts.add(phrase);
            }
            for (String phrase : parts) if (isKnownPlace(phrase)) out.add(phrase);
            return out;
        }
        if (geoTokens.isEmpty()) return out;
        List<String> all = tokens(query);
        List<String> trailing = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            String t = all.get(i);
            if (geoTokens.stream().noneMatch(g -> g.token().equals(t))) break;
            trailing.add(0, t);
        }
        if (!trailing.isEmpty()) out.add(String.join(" ", trailing));
        return out;
    }

    /** A bare number anywhere means the query is an address, which relaxes street-type words. */
    private static boolean addressShaped(List<String> toks) {
        return toks.stream().anyMatch(t -> !t.isEmpty() && t.chars().allMatch(Character::isDigit));
    }

    /** Autocomplete: the last word may still be being typed. */
    public List<Hit> search(String query, int limit) throws Exception {
        return search(query, limit, Completion.PARTIAL);
    }

    public List<Hit> search(String query, int limit, Completion completion) throws Exception {
        return search(query, limit, completion, null);
    }

    /**
     * @param brandBias the company whose locations are being edited, or null.
     *
     * <p>Carries over the ranking the remote places query applies: a place whose brand IS this
     * company first, then any branded place, then the rest. Somebody filling in a location for
     * Starbucks means a Starbucks, and without this the nearest unrelated business outranked it
     * whenever it shared more name tokens.
     *
     * <p>Ignoring this parameter was a silent downgrade: the local index answered, so the remote
     * query that applied the bias never ran.
     */
    public List<Hit> search(String query, int limit, Completion completion, String brandBias)
            throws Exception {
        List<String> toks = new ArrayList<>(tokens(query));
        if (toks.isEmpty()) return List.of();
        /*
          A postal code typed with a space arrives as two tokens while the build stored it with the
          space removed, so "M5C 1G3" matched "m5c1g3" on neither half. The joined form is offered
          as an extra candidate token rather than replacing the pair.
        */
        /*
          A postal code typed with a space is ONE constraint written two ways, not two constraints.

          Appending the joined form while keeping the fragments made all three mandatory, so
          "M5X 1A9" required a place whose text contained "m5x", "1a9" AND "m5x1a9". The build
          stores the code with spaces removed, so nothing satisfied that. The fragments are
          replaced by the joined form when the corpus actually holds it.
        */
        for (int i = 0; i + 1 < toks.size(); i++) {
            String a = toks.get(i), b = toks.get(i + 1);
            if (a.length() + b.length() <= 10
                && a.chars().anyMatch(Character::isDigit) && b.chars().anyMatch(Character::isDigit)) {
                String joined = a + b;
                if (describe(joined, false).count() > 0) {
                    toks.set(i, joined);
                    toks.remove(i + 1);
                }
                break;
            }
        }
        String last = toks.get(toks.size() - 1);
        boolean address = addressShaped(toks);

        java.util.Map<String, TokenInfo> known = describeAll(toks, last);
        /*
          Which words sit in a geographic slot. Computed BEFORE the tokens are described, because
          a region NAME in such a slot has to be matched as its CODE and that decision cannot be
          made from the postings alone - see the alias note below.
        */
        Set<String> afterComma = new LinkedHashSet<>();
        for (String comp : geographicComponents(query, List.of())) afterComma.addAll(tokens(comp));

        // Tokens rewritten from a region name to its code, which belong with geography however
        // their postings happen to be distributed.
        Set<String> aliasedToCode = new LinkedHashSet<>();

        List<TokenInfo> infos = new ArrayList<>();
        for (String t : toks) {
            TokenInfo info = known.getOrDefault(t, new TokenInfo(t, 0, Set.of(), false));
            /*
              The LAST token is prefix-eligible whether or not it also matches exactly.

              Deciding by "exact match missing" was wrong: "New Yor" can have an exact "yor"
              somewhere in the corpus while still being a half-typed "York", and the exact reading
              would then exclude every York. Completeness of the input decides this, not whether
              some unrelated business happens to own the fragment.
            */
            if (t.equals(last) && completion == Completion.PARTIAL) {
                TokenInfo pfx = known.get(PREFIX_KEY);
                if (pfx != null && pfx.count() > 0) {
                    Set<String> merged = new LinkedHashSet<>(info.fields());
                    merged.addAll(pfx.fields());
                    info = new TokenInfo(t, Math.max(info.count(), pfx.count()), merged, true);
                }
            }
            /*
              A word with no postings of its own may still be a word the corpus knows under another
              spelling, and dropping it silently removed the constraint entirely. Two failures came
              from exactly that: "45 S Street" dropped "S" and then matched 45 North Street as
              readily as 45 South Street, and "London, Ontario" dropped "Ontario", because the
              corpus stores the region code "on", and returned the London in England.
            */
            if (info.count() == 0) {
                for (String form : STREET_EQUIV.getOrDefault(t, List.of())) {
                    TokenInfo alt = describe(form, false);
                    if (alt.count() > 0) { info = new TokenInfo(t, alt.count(), alt.fields(), false); break; }
                }
            }
            /*
              A REGION NAME IN A GEOGRAPHIC SLOT IS ITS CODE, even when the name is also a common
              business word.

              This translation only ran when the token had NO postings of its own, which made it
              useless for exactly the states people actually type. "california" has 19,377 name
              postings, 14,865 street and 1,802 city, so it looked like a perfectly good identity
              token and stayed one - a required constraint that no row in California can satisfy,
              because a place stores the code "ca" and never the word "california".

              The result was that the user's own address returned NOTHING:
              "8581 Laguna Grove Drive, Elk Grove, California" -> 0 rows, while the same query
              without the state found the dealership immediately. The fixture could not catch it:
              in 27 rows "california" appears nowhere else, so the old condition was satisfied and
              the alias was translated.

              Position decides. A word after the comma that names a region is a region, and the
              code is what the corpus stores.
            */
            String code = regionCode(t);
            if (code != null && (afterComma.contains(t) || info.count() == 0)) {
                TokenInfo alt = describe(code, false);
                if (alt.count() > 0) {
                    info = new TokenInfo(code, alt.count(), alt.fields(), false);
                    aliasedToCode.add(code);
                }
            }
            /*
              A word the corpus has never seen is a CONSTRAINT THAT CANNOT BE SATISFIED, not a
              word to ignore. Dropping it turned "Starbucks Fakecity" into a plain Starbucks search
              and returned a convincing list of branches in cities nobody asked about, which is
              worse than returning nothing because it looks like an answer.

              The exception is the final token: somebody mid-word has not yet typed something
              unknown, they have typed part of something. That case is handled by the prefix
              reading above, and only falls through to here if even the prefix matches nothing.
            */
            if (info.count() == 0) return List.of();
            infos.add(info);
        }
        if (infos.isEmpty()) return List.of();

        // Geographic tokens constrain place; the rest constrain identity. A token that is both
        // (Toronto the city, Toronto the company) stays unconstrained so neither reading is lost.
        /*
          A token is geographic when the corpus only ever uses it as a place, OR when the person
          put it where geography goes. "New York" is both a city and part of "New York Life", so
          classifying purely by field left "New York Life, New York" unconstrained and a San Jose
          office won on confidence. The comma is a hint about intent, not the whole test: a word
          after it that the corpus knows as a place is treated as one.
        */
        // afterComma is computed above, before the tokens are described.
        List<TokenInfo> geo = new ArrayList<>(), ident = new ArrayList<>();
        for (TokenInfo i : infos) {
            boolean onlyGeo  = !i.fields().isEmpty() && GEO_FIELDS.containsAll(i.fields());
            boolean placedAsGeo = afterComma.contains(i.token())
                               && i.fields().stream().anyMatch(GEO_FIELDS::contains);
            // A token rewritten from a region name no longer matches afterComma, which holds the
            // name rather than the code, so it is carried across explicitly.
            if (onlyGeo || placedAsGeo || aliasedToCode.contains(i.token())) geo.add(i);
            else ident.add(i);
        }
        /*
          A street type still GATES the query; it is only its spelling that is relaxed.

          Dropping these outright was wrong and the fixtures caught it: with "45 North Street" also
          in Halifax, "45 South Street" discarded "south" and returned the northern one on
          confidence. The word is required, but satisfied by any of its forms, so "South" and "S"
          select the same street and neither selects the other one.
        */
        List<TokenInfo> required = new ArrayList<>(ident);
        if (required.isEmpty() && !geo.isEmpty()) required = geo;   // a pure city search is valid
        if (required.isEmpty()) return List.of();

        StringBuilder sql = new StringBuilder();
        List<Object> binds = new ArrayList<>();
        /*
          ELIGIBILITY AND RANKING COME FROM THE POSTINGS; ONLY THE WINNERS ARE HYDRATED.

          The previous shape joined a 16.1M-row Parquet file to rank, and that join measured 105ms
          however small the candidate set was, because the whole file is scanned. It was the single
          largest part of a 361ms search. The postings now carry conf, brand and open_ok, so
          candidates are filtered and ordered without touching anything large, and `meta` is an
          in-index table rather than an external file: 0.04s against 0.09s for the same join.

          Ranking order is unchanged from the slower version, deliberately:
            1. the exact phrase, on the name or the brand;
            2. how much of the query landed in the NAME rather than scattered across address and
               city fields, which is what separates "The North Face" from a place that merely
               contains both words somewhere;
            3. a branded place over an unbranded one;
            4. confidence;
            5. place_id, so the order is stable.
        */
        sql.append("WITH ");
        for (int i = 0; i < required.size(); i++) {
            TokenInfo t = required.get(i);
            List<String> forms = address ? STREET_EQUIV.get(t.token()) : null;
            sql.append("p").append(i).append(" AS (SELECT DISTINCT place_id FROM tok WHERE open_ok AND ");
            if (forms != null) {
                sql.append("token IN (");
                for (int f = 0; f < forms.size(); f++) { sql.append(f == 0 ? "?" : ", ?"); binds.add(forms.get(f)); }
                sql.append(")");
            } else {
                sql.append("token ").append(t.prefix() ? "LIKE ? || '%'" : "= ?");
                binds.add(t.token());
            }
            sql.append("), ");
        }
        for (int i = 0; i < geo.size(); i++) {
            TokenInfo t = geo.get(i);
            sql.append("g").append(i).append(" AS (SELECT DISTINCT place_id FROM tok WHERE open_ok")
               .append(" AND field IN ('city','state') AND token ")
               .append(t.prefix() ? "LIKE ? || '%'" : "= ?").append("), ");
            binds.add(t.token());
        }
        sql.append("cand AS (SELECT place_id FROM p0");
        for (int i = 1; i < required.size(); i++) sql.append(" INTERSECT SELECT place_id FROM p").append(i);
        for (int i = 0; i < geo.size(); i++) sql.append(" INTERSECT SELECT place_id FROM g").append(i);
        sql.append("), ");

        // How much of the query landed in the name, plus the ranking fields, from the postings.
        sql.append("hits AS (SELECT c.place_id, max(t.conf) AS conf, bool_or(t.brand) AS brand, ")
           .append("sum(CASE WHEN t.field IN ('name','brand') THEN 1 ELSE 0 END) AS name_hits ")
           .append("FROM cand c JOIN tok t ON t.place_id = c.place_id AND t.token IN (");
        List<String> allTokens = new ArrayList<>();
        for (TokenInfo t : required) allTokens.add(t.token());
        for (TokenInfo t : geo) allTokens.add(t.token());
        for (int i = 0; i < allTokens.size(); i++) { sql.append(i == 0 ? "?" : ", ?"); binds.add(allTokens.get(i)); }
        sql.append(") GROUP BY c.place_id), ");

        List<String> components = geographicComponents(query, geo);
        StringBuilder geoFilter = new StringBuilder();
        List<String> geoBinds = new ArrayList<>();
        for (String comp : components) {
            String code = regionCode(comp);
            if (code != null) { geoFilter.append(" AND (m.state_norm = ? OR m.city_norm = ?)"); geoBinds.add(code); geoBinds.add(comp); }
            else { geoFilter.append(" AND m.city_norm = ?"); geoBinds.add(comp); }
        }
        String phrase = String.join(" ", toks);
        sql.append("ranked AS (SELECT h.place_id, h.name_hits, h.brand, h.conf, ")
           .append("CASE WHEN m.name_norm LIKE '%' || ? || '%' OR m.brand_norm LIKE '%' || ? || '%' ")
           .append("THEN 0 ELSE 1 END AS phr, ")
           // Mirrors the remote ORDER BY: this company's brand, then any brand, then neither.
           // brand_norm is coalesce(brand_name,''), so an unbranded place is the EMPTY STRING
           // and never NULL. "IS NOT NULL" here would be true for every row and flatten two of
           // these three ranks into one.
           .append("CASE WHEN ? <> '' AND m.brand_norm = ? THEN 0 ")
           .append("WHEN m.brand_norm <> '' THEN 1 ELSE 2 END AS brandfit ")
           .append("FROM hits h JOIN meta m ON m.place_id = h.place_id")
           .append(geoFilter).append("), ");
        binds.add(phrase); binds.add(phrase);
        // Bound where the placeholders sit in the TEXT: after the two phrase binds, before the
        // geographic ones, because the brandfit CASE is written between them.
        String bias = (brandBias == null || brandBias.isBlank()) ? ""
                    : java.text.Normalizer.normalize(brandBias.trim().toLowerCase(),
                                                     java.text.Normalizer.Form.NFC);
        binds.add(bias); binds.add(bias);
        binds.addAll(geoBinds);

        sql.append("top AS (SELECT place_id, row_number() OVER (ORDER BY phr, brandfit, name_hits DESC, ")
           .append("brand DESC, conf DESC NULLS LAST, place_id) AS pos FROM ranked ")
           .append("ORDER BY phr, brandfit, name_hits DESC, brand DESC, conf DESC NULLS LAST, place_id LIMIT ")
           .append(limit).append(") ")
           .append("SELECT t.pos, p.source_place_id, p.name, p.brand_name, p.street, p.city, ")
           .append("p.state_code, p.postal_code FROM top t JOIN read_parquet(?) p ")
           .append("ON p.place_id = t.place_id ORDER BY t.pos");
        binds.add(release.payloadPath().toString());

        List<Hit> hits = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < binds.size(); i++) ps.setString(i + 1, String.valueOf(binds.get(i)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hits.add(new Hit(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                                     rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(1)));
                }
            }
        }
        return hits;
    }
}
