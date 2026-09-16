package org.werkpages.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One rung of a location hierarchy, as a projection target.
 *
 * <p>A contribution at 1005 Ottawa St N is simultaneously a contribution in Kitchener, in Ontario,
 * in Canada, and at the company overall. {@link #expand} turns one declared location into every
 * scope it counts toward, and that list is the only place the hierarchy is written down — a second
 * copy is how "All locations" and the sum of its parts start disagreeing.
 *
 * <p><b>Company-wide is a scope.</b> Not a special case computed some other way: the same row
 * shape, the same arithmetic, the same projector. If the overall number came from a different
 * mechanism than the filtered ones, the first question anybody asked would be why they disagree.
 *
 * <p>Keys are <b>normalized tokens</b>, not display strings: lower-cased, with punctuation and
 * spacing collapsed, so "St. John's" and "st johns" cannot become two scopes.
 *
 * <p>They are deliberately not ISO codes. The contribution ladder stores display names
 * ({@code Canada}, {@code Ontario}) because that is what the forms collect and what the legacy
 * backfill had available; only {@code company_locations} carries {@code country_code} /
 * {@code state_code}. Claiming ISO identity here would mean inventing codes for values that never
 * had them. If the ladder gains code columns later, this is the one place that changes — and a
 * rebuild regenerates every key from source.
 */
public record LocationScope(String type, String key) {

    public static final String COMPANY = "company";
    public static final String COUNTRY = "country";
    public static final String STATE   = "state";
    public static final String CITY    = "city";
    public static final String PLACE   = "place";

    /** The company-wide scope. Every contribution counts toward it, whatever it declared. */
    public static final LocationScope COMPANY_WIDE = new LocationScope(COMPANY, "");

    /**
     * Every scope a contribution counts toward, coarsest first.
     *
     * <p>Always includes {@link #COMPANY_WIDE}, so a contribution that declared nothing still
     * counts somewhere: an unlocated opinion is not a missing opinion.
     *
     * <p>The hierarchy is walked top-down and stops at the first gap. A row with a city but no
     * state is not evidence of a city — it is evidence of a bug — and silently inventing a
     * {@code city} scope with no parent would put a number on a page that no filter can reach.
     *
     * <p>Deeper keys embed their parent, so {@code city} is unique across provinces that share a
     * city name — London, Ontario and London, England are different scopes.
     *
     * @param country    declared country, as stored; null or blank stops the walk
     * @param state      declared state/province, as stored
     * @param city       declared city, as stored
     * @param locationId the chosen workplace, or null
     */
    public static List<LocationScope> expand(String country, String state,
                                             String city, Long locationId) {
        List<LocationScope> scopes = new ArrayList<>(5);
        scopes.add(COMPANY_WIDE);

        String countryKey = normalizeToken(country);
        if (countryKey == null) return scopes;
        scopes.add(new LocationScope(COUNTRY, countryKey));

        String stateKey = normalizeToken(state);
        if (stateKey == null) return scopes;
        scopes.add(new LocationScope(STATE, countryKey + ":" + stateKey));

        String cityKey = normalizeToken(city);
        if (cityKey == null) return scopes;
        scopes.add(new LocationScope(CITY, countryKey + ":" + stateKey + ":" + cityKey));

        if (locationId != null) scopes.add(new LocationScope(PLACE, String.valueOf(locationId)));
        return scopes;
    }

    /**
     * A place name reduced to a stable key.
     *
     * <p>Lower-cased; apostrophes and abbreviating periods <em>removed</em> rather than treated as
     * separators; every remaining run of non-alphanumerics collapsed to a single hyphen.
     *
     * <p>That distinction is the whole job. Treating an apostrophe as a separator turns
     * "St. John's" into {@code st-john-s} while "st johns" becomes {@code st-johns}, and one city
     * quietly becomes two scopes whose numbers each look plausible. Those characters sit inside
     * words, not between them.
     */
    public static String normalizeToken(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[\u0027\u2018\u2019.]", "")
            .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", "-")
            .replaceAll("^-+|-+$", "");
        return t.isEmpty() ? null : t;
    }
}
