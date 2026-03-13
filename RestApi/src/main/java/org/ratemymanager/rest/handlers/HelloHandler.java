package org.ratemymanager.rest.handlers;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

public class HelloHandler {

    public static void handleHello(RoutingContext ctx) {
        // Example JSON response
        JsonObject response = new JsonObject()
                .put("message", "Hello from separate handler!");

        ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(response.encodePrettily());
    }
}
