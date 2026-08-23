package org.werkpages.rest.handlers;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ManagersHandler#bodyStr(JsonObject, String)}.
 *
 * <p>Regression guard for the find-or-create 500: an explicit JSON {@code null} for
 * {@code state}/{@code city} (sent by the frontend when geolocation can't resolve them) must NOT
 * cause an NPE. Vert.x's {@code getString(key, default)} returns the default only for an ABSENT
 * key — a present-but-null key returns {@code null}, so a naive {@code .trim()} blows up. This is
 * the handler-layer counterpart to the OpenAPI {@code nullable: true} contract test.
 */
class ManagersHandlerBodyStrTest {

    @Test
    void explicitNull_returnsEmpty() {
        JsonObject body = new JsonObject().putNull("state");
        assertEquals("", ManagersHandler.bodyStr(body, "state"),
            "an explicit JSON null must yield \"\" — not NPE (frontend sends state/city as null)");
    }

    @Test
    void absentKey_returnsEmpty() {
        assertEquals("", ManagersHandler.bodyStr(new JsonObject(), "city"));
    }

    @Test
    void presentValue_isTrimmed() {
        JsonObject body = new JsonObject().put("company", "  Google  ");
        assertEquals("Google", ManagersHandler.bodyStr(body, "company"));
    }

    @Test
    void blankValue_returnsEmpty() {
        JsonObject body = new JsonObject().put("state", "   ");
        assertEquals("", ManagersHandler.bodyStr(body, "state"));
    }
}
