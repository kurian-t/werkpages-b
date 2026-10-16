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
     * Folded EXACTLY as {@code geo_align.sql} folded the names it indexed: NFC, lowercased,
     * accents removed, and every run of non-letter non-digit characters collapsed to one space.
     *
     * <p>Any divergence from the build's fold is invisible here and matches nothing there, so the
     * build carries a {@code cities_unreachable} validation that fails if a single city the index
     * holds cannot be reached by its own folded name.
     *
     * <p>Accents are folded because the two datasets disagree about them: the places index spells
     * Montreal "montréal" for 55,654 places and "montreal" for 8,720, so matching the typed form
     * literally found one seventh of the city. Punctuation is folded because the reader asks about
     * "chicago heights il" while the index stores "chicago heights, il".
     *
     * <p>The character classes are Unicode and never {@code [a-z0-9]}: this index is built for
     * every country, and stripping to ASCII would erase every non-Latin name in it.
     */
    public static String fold(String text) {
        if (text == null) return "";
        String lower = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String bare  = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return bare.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /**
     * Every region code a region name resolves to, most-used first.
     *
     * <p>Places carry a region code, so a person typing "Ontario" or "California" matches nothing
     * while "on" and "ca" match everything. The alias table is built from the corpus's own data
     * rather than kept by hand, so it covers whatever regions the corpus knows.
     *
     * <p>This used to return ONE code read from an unordered {@code SELECT}, which was wrong twice
     * over. The table was derived from the geography corpus's own region codes, correct for the
     * United States by coincidence - geography says US.NY and places say "ny" - and wrong for most
     * of the world: Canada's geography codes are numeric, so "ontario" resolved to "08" while
     * every Canadian row stores "on", and naming a province filtered to ZERO rows. It is now voted
     * out of the data at build time. The list is returned whole because the index holds dirty
     * region values beside clean ones (566,404 Ontario rows say "on" and 356 say "ontario"), and
     * both are real places somebody can be looking for.
     *
     * <p>Missing table means no aliases, not an error: a release built before this existed still
     * works, it simply cannot resolve region names.
     */
    private List<String> regionCodes(String name) {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                 "SELECT code FROM region_alias WHERE name = ? ORDER BY places DESC")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getString(1)); }
        } catch (Exception ignored) {
            // No region_alias table, or one built before it carried a support count.
        }
        return out;
    }

    /**
     * The city spellings a folded phrase actually denotes, commonest first.
     *
     * <p>This is the step that was missing, and the reason a correctly recognised place could
     * still match almost nothing. Recognising "New York City" is not the same as finding it: the
     * geography corpus uses that name, and the places index spells the city "new york" for 245,580
     * places and "new york city" for 225, so filtering on the phrase as typed returned 0.09% of
     * the city. The alias table is built by folding spellings together and by linking a name to
     * an unclaimed longer or shorter form of itself, both decided from the data. See
     * {@code geo_align.sql} for how, and for the guard that keeps Springfield Gardens out of
     * Springfield.
     */
    private List<String> cityValues(String foldedPhrase, boolean prefix) {
        List<String> out = new ArrayList<>();
        /*
          A PARTIAL LAST WORD STILL NAMES A PLACE, which exact lookup alone cannot see.

          Somebody typing "walmart new yor" has named New York; they have simply not finished. With
          only the exact reading the phrase resolved to nothing, no geography was applied, and the
          words fell through to the identity constraints where "new" and "yor" matched any field -
          which is how a Walmart on "S New York Ave" in Alamogordo answered a search for New York.

          Bounded deliberately. The match is capped, and ordered by how many places each spelling
          carries, so one keystroke cannot expand into every city on the continent: "n" would
          otherwise name thousands. Two characters are required for the same reason.
        */
        if (prefix && foldedPhrase.length() < 2) return out;
        String sql = prefix
            ? "SELECT city_norm FROM city_alias WHERE alias LIKE ? || '%' "
            + "GROUP BY city_norm ORDER BY max(places) DESC LIMIT 40"
            : "SELECT city_norm FROM city_alias WHERE alias = ? "
            + "GROUP BY city_norm ORDER BY max(places) DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, foldedPhrase);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getString(1)); }
        } catch (Exception ignored) {
            return List.of();   // no city_alias table in this release
        }
        return out;
    }

    /**
     * How prominent a place of this name is, from the corpus's population figure, or 0.
     *
     * <p>Used only to order places that a query names ambiguously. Nothing is invented: a locality
     * the corpus gives no population keeps 0 and sorts below any it does.
     */
    private long population(String foldedPhrase, boolean prefix) {
        try (PreparedStatement ps = conn.prepareStatement(prefix
                 ? "SELECT max(population) FROM geo_place WHERE name LIKE ? || '%'"
                 : "SELECT max(population) FROM geo_place WHERE name = ?")) {
            ps.setString(1, foldedPhrase);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getLong(1); }
        } catch (Exception ignored) {
            // no geo_place table in this release
        }
        return 0L;
    }

    /**
     * A place the query named: how it was written, and what the index calls it.
     *
     */
    private record Place(String phrase, List<String> cityValues, List<String> regionCodes,
                         long population) {}

    /**
     * The place this phrase names, or null when the corpus knows no such place.
     *
     * @param partial whether the phrase ends in a word still being typed, in which case an exact
     *                reading is tried first and a prefix reading only if that finds nothing.
     */
    private Place resolvePlace(String phrase, boolean partial) {
        String key = fold(phrase);
        if (key.isEmpty()) return null;
        List<String> cities = cityValues(key, false);
        List<String> codes  = regionCodes(key);
        if (!cities.isEmpty() || !codes.isEmpty()) {
            return new Place(key, cities, codes, population(key, false));
        }
        if (!partial) return null;
        cities = cityValues(key, true);
        if (cities.isEmpty()) return null;
        return new Place(key, cities, List.of(), population(key, true));
    }

    /**
     * A geographic reading of the query: the places it named, and the words left over.
     *
     * <p>{@code head} holds the words that are NOT the place, BY POSITION. That distinction is the
     * whole point of this record. Roles used to be decided per distinct token, so
     * "New York Life, New York" collapsed both occurrences into one classification and left
     * "life" as the only identity constraint: the search returned New Life Baptist Church and
     * Life Pharmacy, and New York Life itself was absent. The same words can be a business name in
     * one position and a place in another, and nothing except position can tell them apart.
     */
    private record GeoRead(List<Place> places, List<String> head, boolean explicit) {}

    /**
     * Reads the query as "something, somewhere", without insisting that it is.
     *
     * <p>A comma is a strong hint and never the only one: "Starbucks Toronto" names a city too.
     * But treating everything after the first comma as a place turned "Smith, Jones &amp;
     * Associates" into a search for Smith in a city called Jones, and "Riverside Clinic, 123 Main
     * Street" into a search in a city called 123 Main Street, so a component counts as geography
     * only when the corpus actually holds it. A component that does not is handed back to the
     * query as ordinary words rather than dropped.
     *
     * <p>Without a comma, the LONGEST trailing run of words that names a place is taken, and the
     * whole query is tried first. Trying shorter runs first was wrong in a way worth recording:
     * "new york" would match the trailing "york" (a real city in Pennsylvania) and search for
     * "new" in York. The longest reading is the specific one.
     *
     * <p>This decides nothing on its own. A geographic reading is only ever an ADDITIONAL
     * interpretation here: the caller searches the literal reading alongside it and ranks them,
     * because "Hotel California" and "Paris Baguette" are business names that merely end in a
     * place, and a trailing place name is not proof of geographic intent.
     */
    private GeoRead readGeography(String query, List<String> toks, boolean partial) {
        int comma = query.indexOf(',');
        if (comma >= 0) {
            List<String> head = new ArrayList<>(tokens(query.substring(0, comma)));
            List<Place> places = new ArrayList<>();
            String[] parts = query.substring(comma + 1).split(",");
            for (int i = 0; i < parts.length; i++) {
                List<String> words = tokens(parts[i]);
                if (words.isEmpty()) continue;
                // Only the LAST component can hold a word still being typed.
                Place p = resolvePlace(String.join(" ", words), partial && i == parts.length - 1);
                if (p != null) places.add(p);
                else head.addAll(words);
            }
            return new GeoRead(places, head, !places.isEmpty());
        }
        for (int start = 0; start < toks.size(); start++) {
            Place p = resolvePlace(String.join(" ", toks.subList(start, toks.size())), partial);
            if (p != null) {
                return new GeoRead(List.of(p), new ArrayList<>(toks.subList(0, start)), false);
            }
        }
        return new GeoRead(List.of(), new ArrayList<>(toks), false);
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
          THE GEOGRAPHIC READING IS COMPUTED FIRST, because it decides which words are identity
          constraints at all, and it is kept as an ALTERNATIVE rather than imposed.

          Both halves of that matter, and each fixes a reported failure:

            * Deciding roles per DISTINCT TOKEN collapsed the two occurrences in "New York Life,
              New York" into one classification and left "life" as the only identity constraint.
              The search returned New Life Baptist Church and Life Pharmacy; New York Life was
              absent. Roles now come from POSITION, so the same words constrain identity in the
              head and place in the tail.

            * Imposing the geographic reading would break every business whose name ends in a
              place. "Hotel California", "Paris Baguette" and "London Drugs" are names, not
              requests to search California, Paris or London. So the literal reading is searched
              ALONGSIDE the geographic one and the two are ranked, which is also why a wrong
              trailing-place guess is survivable rather than fatal.
        */
        GeoRead geoRead = readGeography(query, toks, completion == Completion.PARTIAL);
        List<Place> places = geoRead.places();
        List<String> head = geoRead.head();

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
              spelling, and dropping it silently removed the constraint entirely: "45 S Street"
              dropped "S" and then matched 45 North Street as readily as 45 South Street.
            */
            if (info.count() == 0) {
                for (String form : STREET_EQUIV.getOrDefault(t, List.of())) {
                    TokenInfo alt = describe(form, false);
                    if (alt.count() > 0) { info = new TokenInfo(t, alt.count(), alt.fields(), false); break; }
                }
            }
            infos.add(info);
        }

        /*
          A word the corpus has never seen is a CONSTRAINT THAT CANNOT BE SATISFIED, not a word to
          ignore. Dropping it turned "Starbucks Fakecity" into a plain Starbucks search and
          returned a convincing list of branches in cities nobody asked about, which is worse than
          returning nothing because it looks like an answer.

          It no longer kills the whole query outright, because a word can now be unknown AS A TOKEN
          while naming a place perfectly well. "Walmart New York City" returned nothing for exactly
          that reason: "city" became a required identity token, and no Walmart has it in any field.
          An unknown word in the HEAD still refuses the query - that is the Fakecity guard, and the
          head is what both readings require - while an unknown word the corpus resolved as part of
          a place name simply rules out the literal reading.
        */
        java.util.Map<String, TokenInfo> byToken = new java.util.LinkedHashMap<>();
        for (TokenInfo i : infos) byToken.put(i.token(), i);

        List<TokenInfo> headInfos = new ArrayList<>();
        for (String t : head) {
            TokenInfo i = byToken.get(t);
            if (i == null || i.count() == 0) return List.of();
            headInfos.add(i);
        }
        /*
          WHETHER THE LITERAL READING IS KEPT DEPENDS ON HOW THE GEOGRAPHY WAS ARRIVED AT.

          A place written AFTER A COMMA is stated, not guessed, and it filters: "York Roasters,
          York" must not offer the branch in New York at all, and "Pacific Imports, Toronto" must
          not offer the one in San Jose. Ranking the wrong region lower is not enough, because the
          person has already told us where they mean.

          A trailing place with no comma IS a guess, and there the literal reading must survive:
          "Hotel California", "Paris Baguette", "London Drugs" and "New York Life" are business
          names that merely end in a place, and filtering on that guess would delete the only
          answer anybody wanted. Both readings are searched and ranked, so a wrong guess costs
          position rather than the result.
        */
        boolean geographic = !places.isEmpty();
        boolean literal = infos.stream().allMatch(i -> i.count() > 0)
                       && !(geographic && geoRead.explicit());
        if (!literal && !geographic) return List.of();
        if (!geographic && headInfos.isEmpty()) return List.of();

        /*
          WHAT THE INDEX CALLS THE PLACES THAT WERE NAMED.

          cityValues comes from the alias table, so a recognised place is matched by every spelling
          the index actually uses for it rather than by the one that was typed. Recognising the
          place and finding it are different problems: "new york city" is a name the geography
          corpus uses and the places index spells "new york" for 245,580 rows and "new york city"
          for 225, so the phrase as typed reached 0.09% of the city.
        */
        List<String> cityFilter = new ArrayList<>(), regionFilter = new ArrayList<>();
        Set<String> placeTokens = new LinkedHashSet<>();
        for (Place p : places) {
            cityFilter.addAll(p.cityValues());
            regionFilter.addAll(p.regionCodes());
            for (String v : p.cityValues()) placeTokens.addAll(tokens(v));
            placeTokens.addAll(p.regionCodes());
        }

        StringBuilder sql = new StringBuilder();
        List<Object> binds = new ArrayList<>();

        /*
          ELIGIBILITY AND RANKING COME FROM THE POSTINGS; ONLY THE WINNERS ARE HYDRATED.

          The previous shape joined a 16.1M-row Parquet file to rank, and that join measured 105ms
          however small the candidate set was, because the whole file is scanned. The postings now
          carry conf, brand and open_ok, so candidates are filtered and ordered without touching
          anything large, and `meta` is an in-index table rather than an external file.

          Ranking order, in full:
            1. phr      - the literal reading first: the WHOLE query as a name or brand, then the
                          head of it, then neither. This is what keeps "Hotel California" the
                          hotel of that name ahead of hotels in California.
            2. geofit   - a place that satisfies the geography the query named, ahead of one that
                          does not. Below phr deliberately, and above everything else.
            3. brandfit - this company's brand, then any brand, then neither.
            4. name_hits- how much of the query landed in the NAME rather than scattered across
                          address and city fields.
            5. geopop   - the corpus's population, which settles a place the query named
                          ambiguously: "Paris" is Paris, Texas before Paris, Tennessee.
            6. brand, conf, place_id - as before, place_id last so the order is stable.
        */
        sql.append("WITH ");

        // The literal reading: every word the person typed, in any field. Any field matters, and
        // is what lets an ADDRESS be searched at all - "123 Main Street Toronto" has nothing in a
        // name or a brand, and restricting identity words to those fields would refuse it.
        /*
          ONE CTE PER DISTINCT WORD, referenced by whichever branch needs it.

          The two readings overlap by construction - the head words are a subset of all the words -
          so emitting a posting lookup per branch scanned the same word twice. For
          "starbucks brooklyn" that was the 35,265 "starbucks" postings read once for the literal
          reading and again for the geographic one, and the duplicate was invisible because the two
          had different aliases. CPU per query is what bounds sustained throughput on this host, so
          a scan nobody needed is the thing worth removing first.
        */
        java.util.Map<String, String> cteFor = new java.util.LinkedHashMap<>();
        java.util.List<TokenInfo> needed = new ArrayList<>();
        if (literal) needed.addAll(infos);
        if (geographic) needed.addAll(headInfos);
        for (TokenInfo t : needed) {
            String key = t.token() + "|" + t.prefix();
            if (cteFor.containsKey(key)) continue;
            String name = "t" + cteFor.size();
            cteFor.put(key, name);
            appendTokenCte(sql, binds, name, t, address);
        }
        // The geographic reading: the head words, narrowed to the places that were named.
        if (geographic) {
            /*
              RETRIEVAL IS NARROWED BY THE RESOLVED PLACE NAMES, AS A UNION, and made exact
              afterwards against meta.

              Requiring every typed geographic word to be a city or state posting is what made
              "Walmart New York City" return nothing: no Walmart in New York carries the posting
              "city", so intersecting new AND york AND city eliminated the whole city. These are
              the words of the names the index itself uses, combined with OR, so the set is a
              superset of the answer and the exact filter below removes the rest.
            */
            sql.append("gc AS (SELECT DISTINCT place_id FROM tok WHERE open_ok")
               .append(" AND field IN ('city','state') AND token IN (");
            int n = 0;
            for (String t : placeTokens) { sql.append(n++ == 0 ? "?" : ", ?"); binds.add(t); }
            sql.append(")), ");
        }

        if (literal) {
            sql.append("lit AS (");
            for (int i = 0; i < infos.size(); i++) {
                if (i > 0) sql.append(" INTERSECT ");
                sql.append("SELECT place_id FROM ")
                   .append(cteFor.get(infos.get(i).token() + "|" + infos.get(i).prefix()));
            }
            sql.append("), ");
        }
        if (geographic) {
            sql.append("geo AS (SELECT c.place_id FROM (SELECT place_id FROM gc");
            for (TokenInfo t : headInfos) {
                sql.append(" INTERSECT SELECT place_id FROM ")
                   .append(cteFor.get(t.token() + "|" + t.prefix()));
            }
            sql.append(") c JOIN meta m ON m.place_id = c.place_id WHERE ").append(placePredicate(cityFilter, regionFilter));
            binds.addAll(cityFilter);
            binds.addAll(regionFilter);
            sql.append("), ");
        }

        sql.append("cand AS (");
        if (literal)                sql.append("SELECT place_id FROM lit");
        if (literal && geographic)  sql.append(" UNION ");
        if (geographic)             sql.append("SELECT place_id FROM geo");
        sql.append("), ");

        // How much of the query landed in the name, plus the ranking fields, from the postings.
        sql.append("hits AS (SELECT c.place_id, max(t.conf) AS conf, bool_or(t.brand) AS brand, ")
           .append("sum(CASE WHEN t.field IN ('name','brand') THEN 1 ELSE 0 END) AS name_hits ")
           .append("FROM cand c JOIN tok t ON t.place_id = c.place_id AND t.token IN (");
        /*
          NAME RELEVANCE IS MEASURED OVER THE IDENTITY WORDS ONLY.

          Counting the geographic words too let a place be rewarded for containing them, which is
          not evidence that it is the business being looked for. "Walgreens, New York" ranked New
          York Presbyterian Hospital above every actual Walgreens: the hospital has a Walgreens
          pharmacy counter, so it carried the brand posting, and then "new" and "york" in its own
          NAME counted twice more. Three name hits against the two a shop called Walgreens can
          have, and the hospital won a search for a pharmacy chain.

          When the query named no place, the identity words are the whole query and this is the
          behaviour it always had.
        */
        List<String> nameTokens = new ArrayList<>();
        for (TokenInfo t : (geographic && !headInfos.isEmpty() ? headInfos : infos)) nameTokens.add(t.token());
        for (int i = 0; i < nameTokens.size(); i++) { sql.append(i == 0 ? "?" : ", ?"); binds.add(nameTokens.get(i)); }
        sql.append(") GROUP BY c.place_id), ");

        String fullPhrase = String.join(" ", toks);
        String headPhrase = String.join(" ", head);
        sql.append("ranked AS (SELECT h.place_id, h.name_hits, h.brand, h.conf, ")
           .append("CASE WHEN m.name_norm LIKE '%' || ? || '%' OR m.brand_norm LIKE '%' || ? || '%' THEN 0 ")
           .append("WHEN ? <> '' AND (m.name_norm LIKE '%' || ? || '%' OR m.brand_norm LIKE '%' || ? || '%') THEN 1 ")
           .append("ELSE 2 END AS phr, ");
        binds.add(fullPhrase); binds.add(fullPhrase);
        binds.add(headPhrase); binds.add(headPhrase); binds.add(headPhrase);

        /*
          A CITY MATCH OUTRANKS A REGION MATCH, because a name can be both.

          "New York" is a city and a state, and matching them as one flat OR made the state win on
          volume: a search for Walmart in New York returned Greece, Springville and Ogdensburg -
          all genuinely in New York State, none of them what was asked for. Three levels keep both
          readings available and put the specific one first, rather than discarding the region
          reading that "Starbucks Ontario" depends on.
        */
        /*
          FOUR TIERS, because a name can denote a city, a region, or a city in some other region,
          and collapsing them loses the answer.

            0  the city of that name INSIDE the region of that name - the namesake
            1  anywhere in the region of that name
            2  a city of that name in a DIFFERENT region - a coincidental namesake
            3  neither

          Every tier is still searched; only the order changes. Two failures came from having
          fewer tiers. Matching city and region as one flat OR let the region win on volume, so
          Walmart in New York returned Greece, Springville and Ogdensburg - all in New York State,
          none of them New York. Then ranking any city above the region sent a search for Starbucks
          in California to a town of 11,857 in MARYLAND, ahead of the state of thirty-nine million.

          Asking whether the city is in its own region separates them with no threshold and no list
          of exceptions: New York City is in New York, and California, Maryland is not.
        */
        if (geographic) {
            boolean c = !cityFilter.isEmpty(), r = !regionFilter.isEmpty();
            sql.append("CASE ");
            if (c && r) {
                sql.append("WHEN ").append(in("m.city_norm", cityFilter))
                   .append(" AND ").append(in("m.state_norm", regionFilter)).append(" THEN 0 ");
                binds.addAll(cityFilter); binds.addAll(regionFilter);
            }
            if (r) {
                sql.append("WHEN ").append(in("m.state_norm", regionFilter)).append(" THEN 1 ");
                binds.addAll(regionFilter);
            }
            if (c) {
                sql.append("WHEN ").append(in("m.city_norm", cityFilter)).append(" THEN 2 ");
                binds.addAll(cityFilter);
            }
            sql.append("ELSE 3 END AS geofit, ");
        } else {
            sql.append("0 AS geofit, ");
        }

        // Mirrors the remote ORDER BY: this company's brand, then any brand, then neither.
        // brand_norm is coalesce(brand_name,''), so an unbranded place is the EMPTY STRING and
        // never NULL. "IS NOT NULL" here would be true for every row and flatten two of these
        // three ranks into one.
        sql.append("CASE WHEN ? <> '' AND m.brand_norm = ? THEN 0 ")
           .append("WHEN m.brand_norm <> '' THEN 1 ELSE 2 END AS brandfit");
        /*
          NORMALISED EXACTLY AS THE BUILD NORMALISED brand_norm, or this never matches.

          Two differences from the build were here, and both are silent. toLowerCase() without a
          locale uses the JVM's default: on a Turkish-locale host "I" lowercases to the dotless
          "ı", so every brand containing an I stops equalling its indexed form and brand
          ranking quietly dies for them. The build uses DuckDB's lower(), which has no locale.

          The order was inverted too. The build is lower(nfc_normalize(x)) - compose, then
          lowercase. Doing it the other way round is not guaranteed to agree, because lowercasing
          can yield sequences NFC then composes differently.
        */
        String bias = (brandBias == null || brandBias.isBlank()) ? ""
                    : java.text.Normalizer.normalize(brandBias.trim(), java.text.Normalizer.Form.NFC)
                          .toLowerCase(java.util.Locale.ROOT);
        binds.add(bias); binds.add(bias);

        // PROMINENCE, FROM THE CORPUS'S OWN POPULATION FIGURE. Only ever a tie-break among places
        // the query named ambiguously, and never a substitute for the geography it did name: it
        // sits BELOW geofit, so an explicitly named place always outranks a more famous one.
        if (geographic) {
            sql.append(", coalesce(gp.population, 0) AS geopop ")
               .append("FROM hits h JOIN meta m ON m.place_id = h.place_id ")
               .append("LEFT JOIN geo_place gp ON gp.name = ? AND gp.region = m.state_norm), ");
            binds.add(places.get(0).phrase());
        } else {
            sql.append(", 0 AS geopop FROM hits h JOIN meta m ON m.place_id = h.place_id), ");
        }

        String order = "phr, geofit, brandfit, name_hits DESC, geopop DESC, "
                     + "brand DESC, conf DESC NULLS LAST, place_id";
        sql.append("top AS (SELECT place_id, row_number() OVER (ORDER BY ").append(order)
           .append(") AS pos FROM ranked ORDER BY ").append(order).append(" LIMIT ").append(limit).append(") ")
           .append("SELECT t.pos, p.source_place_id, p.name, p.brand_name, p.street, p.city, ")
           .append("p.state_code, p.postal_code FROM top t JOIN read_parquet(?) p ")
           .append("ON p.place_id = t.place_id ORDER BY t.pos");
        binds.add(release.payloadLocation());

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

    /**
     * One required word as a posting lookup.
     *
     * <p>A street type still GATES the query; it is only its spelling that is relaxed. Dropping
     * these outright was wrong and the fixtures caught it: with "45 North Street" also in Halifax,
     * "45 South Street" discarded "south" and returned the northern one on confidence. The word is
     * required, but satisfied by any of its forms, so "South" and "S" select the same street and
     * neither selects the other one.
     */
    private static void appendTokenCte(StringBuilder sql, List<Object> binds, String name,
                                       TokenInfo t, boolean address) {
        List<String> forms = address ? STREET_EQUIV.get(t.token()) : null;
        sql.append(name).append(" AS (SELECT DISTINCT place_id FROM tok WHERE open_ok AND ");
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

    /**
     * Whether a row sits in one of the places the query named.
     *
     * <p>A city OR a region, because a place can be either and "London, Ontario" names one of
     * each. Written once and used twice, for retrieval and for ranking, so the two can never
     * disagree about what satisfying the geography means.
     */
    /** An IN list with one placeholder per value; the caller binds them in the same order. */
    private static String in(String column, List<String> values) {
        StringBuilder b = new StringBuilder(column).append(" IN (");
        for (int i = 0; i < values.size(); i++) b.append(i == 0 ? "?" : ", ?");
        return b.append(")").toString();
    }

    private static String placePredicate(List<String> cities, List<String> regions) {
        StringBuilder p = new StringBuilder("(");
        if (!cities.isEmpty()) {
            p.append("m.city_norm IN (");
            for (int i = 0; i < cities.size(); i++) p.append(i == 0 ? "?" : ", ?");
            p.append(")");
        }
        if (!cities.isEmpty() && !regions.isEmpty()) p.append(" OR ");
        if (!regions.isEmpty()) {
            p.append("m.state_norm IN (");
            for (int i = 0; i < regions.size(); i++) p.append(i == 0 ? "?" : ", ?");
            p.append(")");
        }
        return p.append(")").toString();
    }
}