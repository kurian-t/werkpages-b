package org.werkpages.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How buildings from several candidate countries are combined when the caller named no country.
 *
 * <p>This is the merge that decides what somebody sees on the FIRST keystroke, before any location
 * is selected, so a bug here is a bug in the most common state of the form.
 */
class PlacesWithoutCountryMergeTest {

    private static JsonArray rows(String country, String... ids) {
        JsonArray a = new JsonArray();
        for (String id : ids) {
            a.add(new JsonObject().put("sourcePlaceId", id).put("name", id).put("country", country));
        }
        return a;
    }

    private static List<String> idsOf(JsonArray merged) {
        return merged.stream().map(o -> ((JsonObject) o).getString("sourcePlaceId")).toList();
    }

    @Test
    @DisplayName("a place offered by two countries takes only one slot")
    void duplicatesDoNotConsumeTheBudget() {
        /*
          The first implementation took a share from each country and then went round again to fill
          the unused slots, WITHOUT excluding what it had already taken. Three candidates returning
          two each filled eight slots with six distinct places, so two suggestions were wasted on
          repeats and the search work behind them was wasted too.
        */
        JsonArray merged = ManagerService.interleave(List.of(
            rows("Canada",        "p1", "p2", "p3"),
            rows("United States", "p1", "p4", "p5")));   // p1 offered by both
        assertEquals(List.of("p1", "p4", "p2", "p5", "p3"), idsOf(merged));
        assertEquals(5, merged.size(), "p1 appears once, not twice");
    }

    @Test
    @DisplayName("no single country takes the whole list while another has results")
    void everyCandidateIsRepresented() {
        /*
          Filling from the first candidate in order meant the configured order decided the answer
          outright: with CA,US,GB configured, "Walmart" with no country returned eight Canadian
          Walmarts and not one American. Nobody whose country we cannot see is served by that.
        */
        JsonArray merged = ManagerService.interleave(List.of(
            rows("Canada",        "c1","c2","c3","c4","c5","c6","c7","c8","c9"),
            rows("United States", "u1","u2","u3")));
        List<String> ids = idsOf(merged);
        assertTrue(ids.contains("u1") && ids.contains("u2") && ids.contains("u3"),
                   "the second country must not be squeezed out: " + ids);
        assertEquals("c1", ids.get(0));
        assertEquals("u1", ids.get(1), "round robin, not one country then the other");
    }

    @Test
    @DisplayName("the list is never longer than the suggestion limit")
    void theLimitHolds() {
        JsonArray merged = ManagerService.interleave(List.of(
            rows("Canada",        "c1","c2","c3","c4","c5","c6","c7","c8","c9","c10"),
            rows("United States", "u1","u2","u3","u4","u5","u6","u7","u8","u9","u10")));
        assertEquals(8, merged.size());
        assertEquals(8, idsOf(merged).stream().distinct().count(), "and every one of them distinct");
    }

    @Test
    @DisplayName("a country that returned nothing does not cost a slot or break the rotation")
    void emptyCandidatesAreSkipped() {
        JsonArray merged = ManagerService.interleave(List.of(
            rows("Canada"),                       // no local release, or a genuine miss
            rows("United States", "u1", "u2")));
        assertEquals(List.of("u1", "u2"), idsOf(merged));
    }

    @Test
    @DisplayName("a row with no identifier is still offered")
    void rowsWithoutAnIdSurvive() {
        // The remote path has always carried source_place_id, but a row that somehow lacks one
        // must not be silently dropped: an unidentified suggestion is better than none.
        JsonArray anonymous = new JsonArray().add(new JsonObject().put("name", "Somewhere"));
        JsonArray merged = ManagerService.interleave(List.of(anonymous));
        assertEquals(1, merged.size());
    }
}
