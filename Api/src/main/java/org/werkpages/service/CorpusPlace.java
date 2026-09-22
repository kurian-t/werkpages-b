package org.werkpages.service;

import io.vertx.core.json.JsonObject;

/**
 * A workplace chosen from the search corpus, on its way to becoming a {@code company_locations} row.
 *
 * <h2>Why this type exists at all</h2>
 *
 * At {@code exact} precision a contribution points at a {@code company_locations} row by id. A place
 * offered from the corpus has no such id — it lives as Parquet in S3, not in the database — so
 * something has to carry its identity from the suggestion list to the submit, where it is promoted.
 *
 * <p>Promotion happens on <b>submit</b>, never on suggestion. Creating a row for every place
 * somebody scrolled past would fill {@code company_locations} with buildings nobody worked at, and
 * every location facet is counted from that table.
 *
 * <h2>Identity</h2>
 *
 * {@code sourcePlaceId} is Overture's GERS id. It is provenance and a deduplication key — two
 * people choosing the same store must reach the same row, not two rows that split the store's
 * ratings in half — but it is never the primary key. The corpus is replaceable; the location is
 * ours.
 *
 * <h2>Why every field is copied rather than referenced</h2>
 *
 * The promoted row is a snapshot. A corpus rebuild six months from now must not be able to rewrite
 * where somebody said they worked, and rendering a profile must never depend on an S3 read.
 */
public record CorpusPlace(String sourcePlaceId, String displayName, String street, String city,
                          String stateCode, String countryCode, String postalCode,
                          String country, String state) {

    /** Overture is the only corpus today; stored so a future one can be told apart. */
    public static final String SOURCE = "overture";

    /**
     * Reads the {@code corpusPlace} object from a request body, or null when there is none.
     *
     * <p>A body with no {@code corpusPlace} is the normal case: coarse geography, or an exact pick
     * of a building already in the database.
     */
    public static CorpusPlace fromBody(JsonObject body) {
        if (body == null) return null;
        JsonObject place = body.getJsonObject("corpusPlace");
        if (place == null) return null;

        String sourcePlaceId = trimToNull(place.getString("sourcePlaceId"));
        String displayName   = trimToNull(place.getString("name"));
        // Without an identity and a name there is nothing to promote and nothing to show. Treating
        // that as "no place" rather than as an error keeps a malformed payload from blocking a
        // submission that is otherwise perfectly valid coarse geography.
        if (sourcePlaceId == null || displayName == null) return null;

        return new CorpusPlace(
            sourcePlaceId,
            displayName,
            trimToNull(place.getString("street")),
            trimToNull(place.getString("city")),
            trimToNull(place.getString("stateCode")),
            trimToNull(place.getString("countryCode")),
            trimToNull(place.getString("postalCode")),
            trimToNull(place.getString("country")),
            trimToNull(place.getString("state")));
    }

    /**
     * Whether this carries enough to be a workplace somebody could later find again.
     *
     * <p>A city is the floor. A building with no city cannot be rendered on a profile, cannot be
     * grouped into a facet, and cannot be told apart from another branch of the same chain.
     */
    public String validate() {
        if (Fields.isBlank(city))        return "A workplace must have a city";
        if (Fields.isBlank(countryCode)) return "A workplace must have a country";
        return Fields.firstProblem(
            Fields.maxLength(displayName, "Workplace name"),
            Fields.maxLength(street,      "Workplace street"),
            Fields.maxLength(city,        "Workplace city"));
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
