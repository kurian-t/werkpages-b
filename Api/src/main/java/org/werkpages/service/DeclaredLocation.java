package org.werkpages.service;

import io.vertx.core.json.JsonObject;

/**
 * Where a contributor said the work happened.
 *
 * <p>The declared half of the location model, and the only half that is ever public. It is a
 * separate type from {@link org.werkpages.repository.GeoObservation} on purpose: observed values
 * arrive under {@code country}/{@code state}/{@code city} and declared ones under
 * {@code declared*}, and nothing converts between them. That split is what makes it impossible for
 * a Cloudflare header to reach a displayed field — not a rule someone has to remember, but a fact
 * about which keys each layer can read.
 *
 * <p>A prefilled value the person saw on the form and submitted unchanged <em>is</em> declared.
 * Confirmation is the test, not whether they typed it.
 *
 * <h2>Precision</h2>
 * Stated rather than inferred from which fields happen to be filled. Without it, "Kitchener, use
 * city only" and "we only ever knew the city" are the same row, and the server has to guess which
 * one it is looking at.
 */
public record DeclaredLocation(String country, String state, String city,
                               String precision, Long companyLocationId) {

    public static final String COUNTRY = "country";
    public static final String STATE   = "state";
    public static final String CITY    = "city";
    public static final String EXACT   = "exact";

    /** Nothing was declared — the form carried no location, or this is a path that never accepts one. */
    public static final DeclaredLocation NONE = new DeclaredLocation(null, null, null, null, null);

    public boolean isEmpty() { return precision == null; }

    /**
     * Reads the declared keys, and only those.
     *
     * <p>Never consults a request header, and never falls back to the observed {@code country} /
     * {@code state} / {@code city} keys that {@code GeoUtils.stampGeo} writes. A body carrying only
     * those yields {@link #NONE}, which is exactly what should happen on the search paths.
     */
    public static DeclaredLocation fromBody(JsonObject body) {
        if (body == null) return NONE;
        String precision = trimToNull(body.getString("declaredPrecision"));
        if (precision == null) return NONE;
        return new DeclaredLocation(
            trimToNull(body.getString("declaredCountry")),
            trimToNull(body.getString("declaredState")),
            trimToNull(body.getString("declaredCity")),
            precision,
            body.getLong("companyLocationId"));
    }

    /**
     * Whether the parts present match the precision claimed.
     *
     * @return the problem, or null when the shape is consistent
     */
    public String validate() {
        if (isEmpty()) return null;
        switch (precision) {
            case COUNTRY -> {
                if (Fields.isBlank(country)) return "A declared country is required at country precision";
            }
            case STATE -> {
                if (Fields.isBlank(country) || Fields.isBlank(state))
                    return "A declared country and state are required at state precision";
            }
            case CITY -> {
                if (Fields.isBlank(country) || Fields.isBlank(state) || Fields.isBlank(city))
                    return "A declared country, state and city are required at city precision";
            }
            case EXACT -> {
                // The coarse values are not validated here because they are not trusted here: at
                // exact precision they are overwritten from the location row, so whatever the
                // client sent is irrelevant. See withCoarseFrom.
                if (companyLocationId == null)
                    return "A workplace must be selected at exact precision";
            }
            default -> {
                return "Unknown location precision: " + precision;
            }
        }
        String tooLong = Fields.firstProblem(
            Fields.maxLength(country, "Declared country"),
            Fields.maxLength(state,   "Declared state"),
            Fields.maxLength(city,    "Declared city"));
        return tooLong;
    }

    /**
     * Replaces the coarse values with the ones on the chosen location row.
     *
     * <p>At {@code exact} precision the client's country/state/city are ignored entirely. They are
     * redundant — the location already knows where it is — and trusting them would let a form that
     * had drifted out of sync publish a city the selected building is not in.
     *
     * <p>They are still <em>stored</em>, rather than left null, so that a query can group by city
     * or state without joining and parsing an address.
     */
    public DeclaredLocation withCoarseFrom(String locCountry, String locState, String locCity) {
        return new DeclaredLocation(locCountry, locState, locCity, EXACT, companyLocationId);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
