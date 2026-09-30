package org.werkpages.rest.handlers;

import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A ghost manager's location must not leave the server below country level.
 *
 * <h2>Why this is tested here and not only in the page</h2>
 *
 * <p>The React profile was changed first, to render only the country for a ghost. That is display,
 * not anonymity: the API kept returning {@code state}, {@code city}, {@code locationStreet} and
 * {@code companyLocationId} in the same JSON the browser fetches, so the suppressed city was one
 * devtools tab — or one {@code curl} — away, and equally available to anything crawling the API.
 *
 * <p>Hiding a value the response still carries is the failure mode CLAUDE.md §24 names explicitly.
 * The fix belongs in the projection every read goes through, and this asserts it there.
 *
 * <h2>The discriminator is provenance, not approval status</h2>
 *
 * <p>Nobody typed a search-created manager's location. {@code createAutoApproved} stamps the
 * SEARCHER's Cloudflare geography onto the manager they were looking for — frequently a different
 * person in a different city — and the person named has never seen it, let alone agreed to it.
 *
 * <p>This was first written against {@code approval_status = 'ghost'}, which is wrong in both
 * directions and was caught by the question "so a ghost location CAN be updated if somebody edits
 * it, right?". It could not: editing a ghost's location leaves it a ghost, so the edit saved and
 * was never displayable. And a {@code pending_approval} row can equally carry visitor geo, which
 * that rule published.
 *
 * <p>So the test is {@code location_source}, exactly as V72 defines it — and every edit path sets
 * it to {@code contributor_declared}, which is what makes a corrected location appear.
 */
class GhostLocationPrivacyTest {

    /** The projection helpers are private statics on the handler; this drives them directly. */
    private static String location(Row row, String column) throws Exception {
        Method m = ManagersHandler.class.getDeclaredMethod("managerLocation", Row.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, row, column);
    }

    private static Long locationId(Row row) throws Exception {
        Method m = ManagersHandler.class.getDeclaredMethod("managerLocationId", Row.class);
        m.setAccessible(true);
        return (Long) m.invoke(null, row);
    }

    /**
     * A Row backed by a map, via a proxy.
     *
     * <p>RestApi has no mocking library and this is not the place to add a dependency, so the
     * three methods the projection actually calls are answered from {@code values} and everything
     * else returns a type default.
     */
    private static Row rowOf(Map<String, Object> values) {
        return (Row) Proxy.newProxyInstance(
            GhostLocationPrivacyTest.class.getClassLoader(),
            new Class<?>[] { Row.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getColumnIndex" -> values.containsKey((String) args[0]) ? 1 : -1;
                case "getString"      -> (String) values.get((String) args[0]);
                case "getLong"        -> (Long)   values.get((String) args[0]);
                case "toString"       -> "Row" + values;
                case "hashCode"       -> values.hashCode();
                case "equals"         -> proxy == args[0];
                default -> method.getReturnType().isPrimitive() ? 0 : null;
            });
    }

    /** Every column the projection asks about is present; suppression is what must remove them. */
    private static Row rowWith(String locationSource) {
        return rowOf(Map.of(
            "location_source",       locationSource,
            "country",               "Canada",
            "state",                 "Ontario",
            "city",                  "Kitchener",
            "location_street",       "45 Cedarhill Crescent",
            "location_city",         "Kitchener",
            "location_state",        "Ontario",
            "location_postal_code",  "N2E 4E2",
            "company_location_id",   382L));
    }

    @Test
    void anInferredLocationKeepsItsCountry() throws Exception {
        /*
          V72 is explicit that country stays: it is "already displayed on every manager profile
          today, ghost or not, and already correctable through the edit-request flow - so recording
          it as declared publishes nothing new". It is also what the directory filters on.
        */
        assertEquals("Canada", location(rowWith("legacy_visitor_inferred"), "country"));
    }

    @Test
    void anInferredLocationGivesUpEverythingBelowCountry() throws Exception {
        Row ghost = rowWith("legacy_visitor_inferred");
        assertNull(location(ghost, "state"),                "province must not be published when inferred");
        assertNull(location(ghost, "city"),                 "city must not be published when inferred");
        assertNull(location(ghost, "location_street"),      "a street address is the most identifying of all");
        assertNull(location(ghost, "location_city"),        "the exact place's city leaks the same fact");
        assertNull(location(ghost, "location_state"),       "the exact place's province leaks the same fact");
        assertNull(location(ghost, "location_postal_code"), "a postal code is finer than the city it sits in");
        assertNull(locationId(ghost),                       "the workplace id resolves to the address");
    }

    @Test
    void aFormConfirmedLocationIsReturnedInFull() throws Exception {
        Row approved = rowWith("legacy_form_confirmed");
        assertEquals("Canada",                 location(approved, "country"));
        assertEquals("Ontario",                location(approved, "state"));
        assertEquals("Kitchener",              location(approved, "city"));
        assertEquals("45 Cedarhill Crescent",  location(approved, "location_street"));
        assertEquals(382L,                     locationId(approved));
    }

    @Test
    void anEditedLocationBecomesVisible() throws Exception {
        /*
          THE REGRESSION THIS FILE EXISTS FOR.

          Every edit path - the approved edit-request and the admin edit - now writes
          location_source = 'contributor_declared' whenever it touches a location. So a manager
          that arrived as a ghost with inferred geography, whose city somebody has since corrected,
          shows that city. Under the old approval_status rule it never could.
        */
        Row edited = rowWith("contributor_declared");
        assertEquals("Ontario",   location(edited, "state"));
        assertEquals("Kitchener", location(edited, "city"));
        assertEquals(382L,        locationId(edited));
    }

    @Test
    void aLocationTakenFromAPickedBuildingIsReturnedInFull() throws Exception {
        // company_location means the value was derived from a workplace row somebody chose.
        assertEquals("Kitchener", location(rowWith("company_location"), "city"));
    }

    @Test
    void nothingDeclaredMeansNothingBelowCountry() throws Exception {
        /*
          V72 only labelled rows that already had a declared_precision, so a null source means
          nothing was ever declared. Treated as unconfirmed, which costs nothing: such a row has no
          sub-country detail to withhold in the first place.
        */
        // The column is SELECTED and its value is NULL - distinct from the column being absent,
        // which is the fail-open case below. HashMap because Map.of rejects null values.
        java.util.Map<String, Object> values = new java.util.HashMap<>();
        values.put("location_source", null);
        values.put("country", "Canada");
        values.put("city", "Kitchener");
        Row unlabelled = rowOf(values);
        assertEquals("Canada", location(unlabelled, "country"));
        assertNull(location(unlabelled, "city"));
    }

    @Test
    void anUnrecognisedSourceIsTreatedAsUnconfirmed() throws Exception {
        // A source added later and not considered here must not publish by default.
        assertNull(location(rowWith("some_future_source"), "city"));
    }

    @Test
    void aQueryThatDidNotSelectTheSourceFailsOpen() throws Exception {
        /*
          The silent-failure guard, and the reason it fails OPEN rather than closed.

          Several projections are fed by queries that do not select location_source. Treating a
          MISSING column like an unconfirmed one would quietly blank the location on every such
          endpoint - a regression that shows up as absent data rather than as an error, which is
          the hardest kind to notice. A missing column means "this read cannot judge", so it does
          not. The three public manager reads do select it.
        */
        assertEquals("Kitchener", location(rowOf(Map.of("city", "Kitchener")), "city"));
    }
}
