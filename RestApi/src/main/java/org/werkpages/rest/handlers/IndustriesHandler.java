package org.werkpages.rest.handlers;

import io.vertx.ext.web.RoutingContext;
import org.werkpages.service.IndustryService;

/**
 * Thin HTTP adapter for the public Industries endpoints (browse listing + single-industry profile).
 * Business logic lives in {@link IndustryService}; error mapping is shared with ManagersHandler.
 */
public class IndustriesHandler {

    private final IndustryService service;

    public IndustriesHandler(IndustryService service) {
        this.service = service;
    }

    // ── GET /api/industries/listing ───────────────────────────────────────────
    public void handleGetIndustryListing(RoutingContext ctx) {
        service.getIndustryListing()
            .onSuccess(json -> ctx.response().putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── GET /api/industries/by-slug/{slug} ────────────────────────────────────
    public void handleGetIndustryProfile(RoutingContext ctx) {
        String slug = ctx.pathParam("slug");
        service.getIndustryProfile(slug)
            .onSuccess(json -> ctx.response().putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }
}
