package org.ratemymanager.rest.handlers;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.ratemymanager.service.AdminService;
import org.ratemymanager.service.DeduplicationJob;

import java.util.UUID;

/**
 * Thin HTTP adapter for admin endpoints.
 * All business logic lives in {@link AdminService}.
 */
public class AdminHandler {

    private final AdminService     service;
    private final DeduplicationJob deduplicationJob;

    public AdminHandler(AdminService service) {
        this(service, null);
    }

    public AdminHandler(AdminService service, DeduplicationJob deduplicationJob) {
        this.service          = service;
        this.deduplicationJob = deduplicationJob;
    }

    // ── GET /api/admin/pending-managers ──────────────────────────────────────

    public void handleGetPendingManagers(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        int limit  = parseIntParam(ctx.request().getParam("limit"),  50, 1, 200);
        int offset = parseIntParam(ctx.request().getParam("offset"), 0,  0, Integer.MAX_VALUE);
        service.getPendingManagers(auth0Id, limit, offset)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── POST /api/admin/pending-managers/:managerId/approve ──────────────────

    public void handleApprovePendingManager(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        long managerId;
        try {
            managerId = Long.parseLong(ctx.pathParam("managerId"));
        } catch (Exception e) {
            bad(ctx, "Invalid manager ID"); return;
        }
        service.approvePendingManager(auth0Id, managerId, null)
            .onSuccess(result -> {
                // Backfill logo if needed (resolved here since CompanyLogoUtils is in RestApi)
                if (result.getBoolean("_needsLogo", false)) {
                    String company = result.getString("_company");
                    String logo = CompanyLogoUtils.resolveLogoUrl(company);
                    if (logo != null) {
                        long mgId = result.getLong("_managerId");
                        // fire-and-forget via service is not available without DB access; handler calls repo directly
                        // The service handles this via approveManager which updates logo in ManagerRepository
                    }
                }
                ok(ctx, new JsonObject().put("success", true).put("message", "Manager approved"));
            })
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── POST /api/admin/pending-managers/:managerId/reject ───────────────────

    public void handleRejectPendingManager(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        long managerId;
        try {
            managerId = Long.parseLong(ctx.pathParam("managerId"));
        } catch (Exception e) {
            bad(ctx, "Invalid manager ID"); return;
        }
        JsonObject body = ctx.getBodyAsJson();
        String reason = body != null ? body.getString("reason") : null;
        service.rejectPendingManager(auth0Id, managerId, reason)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── GET /api/admin/pending-edits ─────────────────────────────────────────

    public void handleGetPendingEdits(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        int limit  = parseIntParam(ctx.request().getParam("limit"),  50, 1, 200);
        int offset = parseIntParam(ctx.request().getParam("offset"), 0,  0, Integer.MAX_VALUE);
        service.getPendingEdits(auth0Id, limit, offset)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── POST /api/admin/pending-edits/:editId/approve ─────────────────────────

    public void handleApproveEdit(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        UUID editId;
        try {
            editId = UUID.fromString(ctx.pathParam("editId"));
        } catch (Exception e) {
            bad(ctx, "Invalid edit ID"); return;
        }
        service.approveEdit(auth0Id, editId)
            .onSuccess(json -> {
                String newCompany = json.getString("newCompany");
                if (newCompany != null) {
                    long managerId = json.getLong("managerId");
                    String logoUrl = CompanyLogoUtils.resolveLogoUrl(newCompany);
                    if (logoUrl != null) service.updateManagerLogo(managerId, logoUrl);
                }
                ok(ctx, json);
            })
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── POST /api/admin/pending-edits/:editId/reject ──────────────────────────

    public void handleRejectEdit(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        UUID editId;
        try {
            editId = UUID.fromString(ctx.pathParam("editId"));
        } catch (Exception e) {
            bad(ctx, "Invalid edit ID"); return;
        }
        service.rejectEdit(auth0Id, editId)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── GET /api/admin/users ──────────────────────────────────────────────────

    public void handleGetUsers(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        int limit  = parseIntParam(ctx.request().getParam("limit"),  50, 1, 200);
        int offset = parseIntParam(ctx.request().getParam("offset"), 0,  0, Integer.MAX_VALUE);
        service.getUsers(auth0Id, limit, offset)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── GET /api/admin/banned-users ───────────────────────────────────────────

    public void handleGetBannedUsers(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        int limit  = parseIntParam(ctx.request().getParam("limit"),  50, 1, 200);
        int offset = parseIntParam(ctx.request().getParam("offset"), 0,  0, Integer.MAX_VALUE);
        service.getBannedUsers(auth0Id, limit, offset)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── POST /api/admin/users/:userId/ban ─────────────────────────────────────

    public void handleBanUser(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        UUID targetUserId;
        try {
            targetUserId = UUID.fromString(ctx.pathParam("userId"));
        } catch (Exception e) {
            bad(ctx, "Invalid user ID"); return;
        }
        JsonObject body = ctx.getBodyAsJson();
        String reason = body != null ? body.getString("reason") : null;
        service.banUser(auth0Id, targetUserId, reason)
            .onSuccess(json -> ctx.response().setStatusCode(201).putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── DELETE /api/admin/users/:userId/ban ───────────────────────────────────

    public void handleUnbanUser(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        UUID targetUserId;
        try {
            targetUserId = UUID.fromString(ctx.pathParam("userId"));
        } catch (Exception e) {
            bad(ctx, "Invalid user ID"); return;
        }
        service.unbanUser(auth0Id, targetUserId)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── PUT /api/admin/managers/:managerId ───────────────────────────────────

    public void handleAdminEditManager(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        long managerId;
        try {
            managerId = Long.parseLong(ctx.pathParam("managerId"));
        } catch (Exception e) {
            bad(ctx, "Invalid manager ID"); return;
        }
        JsonObject body = ctx.getBodyAsJson();
        if (body == null) { bad(ctx, "Request body required"); return; }
        String name           = body.getString("name");
        String title          = body.getString("title");
        String company        = body.getString("company");
        String linkedinUrl    = body.getString("linkedinUrl");
        String companyLogoUrl = body.getString("companyLogoUrl");
        service.adminEditManager(auth0Id, managerId, name, title, company, linkedinUrl)
            .onSuccess(json -> {
                if (company != null && !company.isBlank()) {
                    String logoUrl = (companyLogoUrl != null && !companyLogoUrl.isBlank())
                        ? companyLogoUrl
                        : CompanyLogoUtils.resolveLogoUrl(company);
                    if (logoUrl != null) service.updateManagerLogo(managerId, logoUrl);
                }
                ok(ctx, json);
            })
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── DELETE /api/admin/managers/:managerId ─────────────────────────────────

    public void handleDeleteManager(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        long managerId;
        try {
            managerId = Long.parseLong(ctx.pathParam("managerId"));
        } catch (NumberFormatException e) {
            bad(ctx, "Invalid manager ID"); return;
        }
        service.deleteManager(auth0Id, managerId)
            .onSuccess(v -> ctx.response().setStatusCode(204).end())
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── GET /api/admin/companies ─────────────────────────────────────────────

    public void handleListCompanies(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        service.adminListCompanies(auth0Id)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── PUT /api/admin/companies/:companyId ──────────────────────────────────

    public void handleRenameCompany(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        long companyId;
        try { companyId = Long.parseLong(ctx.pathParam("companyId")); }
        catch (NumberFormatException e) { bad(ctx, "Invalid company ID"); return; }
        JsonObject body = ctx.body().asJsonObject();
        if (body == null) { bad(ctx, "Request body required"); return; }
        String newName = body.getString("name");
        service.adminRenameCompany(auth0Id, companyId, newName)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── POST /api/admin/companies/:keepId/merge/:mergeId ────────────────────

    public void handleMergeCompanies(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        long keepId, mergeId;
        try {
            keepId  = Long.parseLong(ctx.pathParam("keepId"));
            mergeId = Long.parseLong(ctx.pathParam("mergeId"));
        } catch (NumberFormatException e) {
            bad(ctx, "Invalid company ID"); return;
        }
        service.adminMergeCompanies(auth0Id, keepId, mergeId)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── POST /api/admin/managers/:keepId/merge/:mergeId ───────────────────────

    public void handleMergeManagers(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        long keepId, mergeId;
        try {
            keepId  = Long.parseLong(ctx.pathParam("keepId"));
            mergeId = Long.parseLong(ctx.pathParam("mergeId"));
        } catch (NumberFormatException e) {
            bad(ctx, "Invalid manager ID"); return;
        }
        service.mergeManagers(auth0Id, keepId, mergeId)
            .onSuccess(json -> ctx.response().putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── Deduplication job trigger ─────────────────────────────────────────────

    public void handleTriggerDeduplication(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if ("cron".equals(auth0Id)) {
            fireDeduplicationJob(ctx);
            return;
        }
        service.requireAdminPublic(auth0Id)
            .onSuccess(adminId -> fireDeduplicationJob(ctx))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    private void fireDeduplicationJob(RoutingContext ctx) {
        if (deduplicationJob == null) {
            ctx.response().setStatusCode(503).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Deduplication job not configured — ANTHROPIC_API_KEY missing").encode());
            return;
        }
        ctx.response().setStatusCode(202).putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("status", "started").encode());
        deduplicationJob.run();
    }

    // ── Merge suggestions ─────────────────────────────────────────────────────

    public void handleGetMergeSuggestions(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        int limit  = parseIntParam(ctx.queryParam("limit").stream().findFirst().orElse(null), 20, 1, 100);
        int offset = parseIntParam(ctx.queryParam("offset").stream().findFirst().orElse(null), 0, 0, Integer.MAX_VALUE);
        service.getMergeSuggestions(auth0Id, limit, offset)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    public void handleDismissMergeSuggestion(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        long suggestionId;
        try {
            suggestionId = Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            bad(ctx, "Invalid suggestion ID"); return;
        }
        service.dismissMergeSuggestion(auth0Id, suggestionId)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void ok(RoutingContext ctx, JsonObject body) {
        ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(body.encode());
    }

    private static void bad(RoutingContext ctx, String msg) {
        ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("error", msg).encode());
    }

    private static int parseIntParam(String raw, int defaultVal, int min, int max) {
        if (raw == null) return defaultVal;
        try { return Math.min(max, Math.max(min, Integer.parseInt(raw))); }
        catch (NumberFormatException e) { return defaultVal; }
    }
}
