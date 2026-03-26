package org.ratemymanager.rest.handlers;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.ratemymanager.service.ReportService;

/**
 * Thin HTTP adapter for the report endpoint.
 * All business logic lives in {@link ReportService}.
 */
public class ReportsHandler {

    private final ReportService service;

    public ReportsHandler(ReportService service) {
        this.service = service;
    }

    public void handleReportManager(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if (auth0Id == null) {
            ctx.response().setStatusCode(401).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Unauthorized").encode()); return;
        }
        long managerId;
        try {
            managerId = Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Invalid manager ID").encode()); return;
        }
        JsonObject body = ctx.getBodyAsJson();
        if (body == null) {
            ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Missing request body").encode()); return;
        }
        String reason  = body.getString("reason");
        String comment = body.getString("comment");

        service.reportManager(auth0Id, managerId, reason, comment)
            .onSuccess(json -> ctx.response().setStatusCode(201).putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }
}
