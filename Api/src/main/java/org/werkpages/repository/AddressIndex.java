package org.werkpages.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Street addresses, from the source address string rather than from reassembled tokens.
 *
 * <h2>Why the source string</h2>
 *
 * <p>The corpus tokenises each address into the {@code tok} table, which records WHICH words
 * appeared but not their order. Rebuilding "55 Gould St" from {@code {55, gould, st}} looks
 * plausible and is not: measured against the authoritative strings, token reassembly reproduced
 * the real identity <b>2.9%</b> of the time. "10055 avenue papineau" came back as "10055 papineau
 * avenue", "324 8 ave sw" as "324 8 sw avenue". French word order and directional suffixes cannot
 * be recovered from an unordered set, and {@code avenue} is both an English and a French type word
 * so convention cannot decide it either.
 *
 * <p>The address string survives in the source payload and is preserved in the index, so none of
 * that guessing is necessary.
 *
 * <h2>Street identity is a search grouping, not a civic register</h2>
 *
 * <p>Addresses are grouped by a canonical key that folds case and accents and expands thoroughfare
 * abbreviations, so "Gould St" and "Gould Street" are one street while "Highway 7" and "Highway 7
 * E" stay two. The source carries no stable street identifier, so this grouping is explicitly a
 * SEARCH grouping: the display name is always a string the source actually contains, and nothing
 * claims the two spellings are the same civic entity beyond the evidence that they share a
 * canonical name, a locality and a region.
 */
public final class AddressIndex implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AddressIndex.class.getName());

    /** A leading house number, which is what separates an address query from a street query. */
    private static final Pattern HOUSE = Pattern.compile("^(\\d+[a-z]?)\\s+(.+)$");

    /**
     * Thoroughfare abbreviations, expanded for MATCHING only.
     *
     * <p>Never for display. The reader is shown what the source says.
     */
    private static final Map<String, String> ABBREV = Map.ofEntries(
        Map.entry("st", "street"),   Map.entry("str", "street"),  Map.entry("rd", "road"),
        Map.entry("ave", "avenue"),  Map.entry("av", "avenue"),   Map.entry("blvd", "boulevard"),
        Map.entry("boul", "boulevard"), Map.entry("dr", "drive"), Map.entry("ln", "lane"),
        Map.entry("cres", "crescent"), Map.entry("pl", "place"),  Map.entry("ct", "court"),
        Map.entry("hwy", "highway"), Map.entry("pkwy", "parkway"), Map.entry("sq", "square"));

    /**
     * One address.
     *
     * <p>{@code placeId} is the source's own GERS id, carried through the build unchanged.
     *
     * <p>It used to be the payload's {@code place_id}, a per-release integer surrogate, and that
     * was wrong in two ways that only show up later. It is renumbered by every rebuild, so a
     * stored provenance id would come to name a different address; and because promotion keys a
     * {@code company_locations} row on {@code (company_id, 'overture', source_place_id)}, two
     * countries' address indexes both counting from 1 would let one company's address number 1 in
     * Canada silently reuse the row created for its address number 1 somewhere else.
     */
    public record Address(String display, String city, String state, String country,
                          String placeId, boolean houseMatched) { }

    /** One street, derived by grouping the addresses on it. */
    public record Street(String display, String city, String state, String country, int addresses) { }

    private final String url;
    private final String country;
    private final ConnectionPool pool;

    public AddressIndex(String path, String countryCode, ConnectionPool pool) {
        this.url = "jdbc:sqlite:file:" + path + "?mode=ro&immutable=1";
        this.country = countryCode;
        this.pool = pool;
    }

    public String country() { return country; }

    /** The canonical matching key: folded, with thoroughfare abbreviations expanded. */
    public static String canonical(String text) {
        String folded = GeographyIndex.fold(text);
        if (folded.isEmpty()) return folded;
        StringBuilder b = new StringBuilder(folded.length());
        for (String part : folded.split(" ")) {
            if (part.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(ABBREV.getOrDefault(part, part));
        }
        return b.toString();
    }

    /**
     * Addresses matching a query that carries a house number.
     *
     * <p>Returns nothing when the query has no house number: that is a street or place query and
     * answering it from here would let an address masquerade as the answer to a different
     * question. The two are kept apart so that "exact address found" means what it says.
     */
    public List<Address> byAddress(String query, int limit) {
        String canon = canonical(query);
        Matcher m = HOUSE.matcher(canon);
        if (!m.matches()) return List.of();
        String house = m.group(1), rest = m.group(2);

        List<Address> hits = range(house + " " + rest, null, limit);
        if (!hits.isEmpty()) return hits;

        /*
          The trailing words may be the locality rather than part of the street: somebody typing
          "55 gould street toronto" has named a city, not a street called "gould street toronto".
          Each split is tried from the right, longest street first.
        */
        String[] parts = rest.split(" ");
        for (int cut = parts.length - 1; cut >= 1; cut--) {
            String street = String.join(" ", java.util.Arrays.copyOfRange(parts, 0, cut));
            String city   = String.join(" ", java.util.Arrays.copyOfRange(parts, cut, parts.length));
            hits = range(house + " " + street, city, limit);
            if (!hits.isEmpty()) return hits;
        }
        return List.of();
    }

    /** Streets whose canonical name starts with the query, for a query with no house number. */
    public List<Street> byStreet(String query, int limit) {
        String canon = canonical(query);
        if (canon.isEmpty() || HOUSE.matcher(canon).matches()) return List.of();
        String sql = "SELECT street_display, city_display, state_display, count(*) AS n "
                   + "FROM addr WHERE street_key >= ? AND street_key < ? "
                   + "GROUP BY street_key, city_key, state_key ORDER BY n DESC LIMIT ?";
        List<Street> out = new ArrayList<>();
        try (Connection c = pool.borrow(url); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, canon);
            ps.setString(2, GeographyIndex.upper(canon));
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Street(rs.getString(1), rs.getString(2), rs.getString(3),
                                       country, rs.getInt(4)));
                }
            }
        } catch (SQLException err) {
            LOG.log(Level.WARNING, "address index street query failed", err);
            throw new IllegalStateException("address index unavailable", err);
        }
        return out;
    }

    private List<Address> range(String addrKey, String cityKey, int limit) {
        StringBuilder sql = new StringBuilder(
            "SELECT address_display, city_display, state_display, source_place_id "
          + "FROM addr WHERE addr_key >= ? AND addr_key < ?");
        if (cityKey != null) sql.append(" AND city_key = ?");
        sql.append(" LIMIT ?");
        List<Address> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try (Connection c = pool.borrow(url); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setString(i++, addrKey);
            ps.setString(i++, GeographyIndex.upper(addrKey));
            if (cityKey != null) ps.setString(i++, cityKey);
            ps.setInt(i, limit * 4);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && out.size() < limit) {
                    String display = rs.getString(1), city = rs.getString(2);
                    String key = (display + "|" + city).toLowerCase(Locale.ROOT);
                    if (!seen.add(key)) continue;
                    out.add(new Address(display, city, rs.getString(3), country,
                                        rs.getString(4), true));
                }
            }
        } catch (SQLException err) {
            LOG.log(Level.WARNING, "address index query failed", err);
            throw new IllegalStateException("address index unavailable", err);
        }
        return out;
    }

    @Override public void close() { pool.closeAll(url); }
}
