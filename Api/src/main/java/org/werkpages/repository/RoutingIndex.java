package org.werkpages.repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Which country partitions are worth opening for a query.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The places corpus is 248 per-country DuckDB partitions. Opening all of them on a keystroke is
 * impossible, and opening only the visitor's country is a geographic prison: somebody in Ontario
 * searching for a London pub must still reach the United Kingdom.
 *
 * <p>So routing is a bounded, indexed lookup that answers "where does this text occur, and how
 * much". Measured against 1,408 real places across twelve countries, routing by match count alone
 * put the correct country first only <b>31.6%</b> of the time and missed it entirely from the top
 * three <b>half</b> the time. Adding the visitor as a PREFERENCE lifted that to 71.4% / 86.8%,
 * and with a wrong visitor country it held at 30.7% rather than collapsing - which is the property
 * that makes it a preference rather than a filter.
 *
 * <h2>Two tiers, because autocomplete is mostly half-typed words</h2>
 *
 * <p>A complete word is looked up in the token tier. A word still being typed cannot be: "goul" is
 * not a token anywhere, so the token tier returns nothing and a search from Ontario offered no
 * streets at all. The prefix tier answers those. The tiers are tried in that order and the prefix
 * tier is not a fallback - it is half the design.
 *
 * <h2>Scoring</h2>
 *
 * <p>Evidence is summed per country as {@code idf * log1p(occurrences)}, so a rare token carries
 * far more weight than a common one: "tabusintac" identifies Canada, "street" identifies nothing.
 */
public final class RoutingIndex implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RoutingIndex.class.getName());

    /** Total rows in the token tier, used as the corpus size for the idf term. */
    private static final double CORPUS_TOKENS = 20_000_000d;

    /**
     * How much the visitor's own country is favoured, as a fraction of the best evidence seen.
     *
     * <p>Deliberately relative rather than absolute: a fixed bonus either swamps strong textual
     * evidence on rare words or vanishes against common ones.
     */
    private static final double VISITOR_PREFERENCE = 0.30;

    private final String url;
    private final ConnectionPool pool;

    public RoutingIndex(String path, ConnectionPool pool) {
        this.url = "jdbc:sqlite:file:" + path + "?mode=ro&immutable=1";
        this.pool = pool;
    }

    /**
     * The countries worth searching, best first.
     *
     * @param tokens  the folded query tokens
     * @param visitor the visitor's country, or null; a preference, never a restriction
     * @param limit   how many partitions the caller is prepared to open
     */
    public List<String> route(List<String> tokens, String visitor, int limit) {
        Map<String, Double> score = score(tokens, true);
        if (score.isEmpty()) score = score(tokens, false);   // still-typing: prefix tier
        if (score.isEmpty()) return visitor == null ? List.of() : List.of(visitor);

        double best = score.values().stream().mapToDouble(Double::doubleValue).max().orElse(1d);
        if (visitor != null) score.merge(visitor, VISITOR_PREFERENCE * best, Double::sum);

        final Map<String, Double> ranked = score;
        List<String> order = new ArrayList<>(ranked.keySet());
        order.sort(Comparator.comparingDouble((String cc) -> -ranked.get(cc)));
        if (order.size() > limit) order = new ArrayList<>(order.subList(0, limit));

        /*
          The visitor's partition is READ FIRST, not merely preferred.

          Being ranked third is not the same as being consulted: the American partition answered
          "Gould Street" first, retrieval stopped on a good-enough match, and the Toronto street
          was never looked for. Every other routed partition is still read immediately after.
        */
        if (visitor != null) {
            order.remove(visitor);
            order.add(0, visitor);
            if (order.size() > limit) order = new ArrayList<>(order.subList(0, limit));
        }
        return order;
    }

    private Map<String, Double> score(List<String> tokens, boolean wholeWords) {
        Map<String, Double> score = new HashMap<>();
        for (String tok : tokens) {
            if (tok == null || tok.length() < 3) continue;
            Map<String, Long> hits = wholeWords ? exact(tok) : prefix(tok);
            if (hits.isEmpty()) continue;
            long total = hits.values().stream().mapToLong(Long::longValue).sum();
            double idf = Math.log(1 + CORPUS_TOKENS / Math.max(total, 1));
            for (Map.Entry<String, Long> e : hits.entrySet()) {
                score.merge(e.getKey(), idf * Math.log1p(e.getValue()), Double::sum);
            }
        }
        return score;
    }

    private Map<String, Long> exact(String token) {
        return lookup("SELECT cc, n FROM ft WHERE token = ?", ps -> ps.setString(1, token));
    }

    private Map<String, Long> prefix(String token) {
        return lookup("SELECT cc, sum(n) FROM ft WHERE token >= ? AND token < ? GROUP BY cc",
            ps -> { ps.setString(1, token); ps.setString(2, GeographyIndex.upper(token)); });
    }

    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    private Map<String, Long> lookup(String sql, Binder binder) {
        Map<String, Long> out = new HashMap<>();
        try (Connection c = pool.borrow(url);
             PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getLong(2));
            }
        } catch (SQLException err) {
            /*
              Reported, never silently empty. A routing failure that returns no countries is
              indistinguishable from "this place exists nowhere", and the reader is told a real
              place does not exist.
            */
            LOG.log(Level.WARNING, "routing index query failed", err);
            throw new IllegalStateException("routing index unavailable", err);
        }
        return out;
    }

    @Override public void close() { pool.closeAll(url); }
}
