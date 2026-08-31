package org.werkpages.rest.handlers;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.werkpages.service.AdminService;
import org.werkpages.service.DeduplicationJob;
import org.werkpages.service.IndustryClassificationJob;
import org.werkpages.service.RoleService;

import java.util.UUID;

/**
 * Thin HTTP adapter for admin endpoints.
 * All business logic lives in {@link AdminService}.
 */
public class AdminHandler {

    private final AdminService              service;
    private final DeduplicationJob          deduplicationJob;
    private final IndustryClassificationJob industryJob;
    private final RoleService               roleService;

    public AdminHandler(AdminService service) {
        this(service, null, null);
    }

    public AdminHandler(AdminService service, DeduplicationJob deduplicationJob) {
        this(service, deduplicationJob, null);
    }

    public AdminHandler(AdminService service, DeduplicationJob deduplicationJob,
                        IndustryClassificationJob industryJob) {
        this(service, deduplicationJob, industryJob, null);
    }

    public AdminHandler(AdminService service, DeduplicationJob deduplicationJob,
                        IndustryClassificationJob industryJob, RoleService roleService) {
        this.service          = service;
        this.deduplicationJob = deduplicationJob;
        this.industryJob      = industryJob;
        this.roleService      = roleService;
    }

    // ── GET /api/admin/ghost-managers ────────────────────────────────────────

    public void handleGetGhostManagers(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        int limit  = parseIntParam(ctx.request().getParam("limit"),  50, 1, 200);
        int offset = parseIntParam(ctx.request().getParam("offset"), 0,  0, Integer.MAX_VALUE);
        service.getGhostManagers(auth0Id, limit, offset)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── POST /api/admin/ghost-managers/:managerId/mark-reviewed ──────────────

    public void handleMarkGhostReviewed(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        long managerId;
        try {
            managerId = Long.parseLong(ctx.pathParam("managerId"));
        } catch (Exception e) {
            bad(ctx, "Invalid manager ID"); return;
        }
        service.markGhostReviewed(auth0Id, managerId)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
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
                    String storedLogoUrl = json.getString("newCompanyLogoUrl");
                    String logoUrl = storedLogoUrl != null ? storedLogoUrl : CompanyLogoUtils.resolveLogoUrl(newCompany);
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
        // Present when the admin picked the company from the typeahead rather than retyping it.
        Long companyId = body.getLong("companyId");
        service.adminEditManager(auth0Id, managerId, name, title, company, linkedinUrl, companyId)
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

    // ── PUT/DELETE /api/admin/companies/:childId/parent ───────────────────────

    public void handleSetCompanyParent(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        long childId;
        try {
            childId = Long.parseLong(ctx.pathParam("childId"));
        } catch (NumberFormatException e) {
            bad(ctx, "Invalid company ID"); return;
        }
        JsonObject body = ctx.getBodyAsJson();
        if (body == null) { bad(ctx, "Request body required"); return; }
        Long parentId = body.getLong("parentId");
        if (parentId == null) { bad(ctx, "parentId is required"); return; }

        service.setCompanyParent(auth0Id, childId, parentId, body.getString("relationshipType"))
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    public void handleRemoveCompanyParent(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        long childId;
        try {
            childId = Long.parseLong(ctx.pathParam("childId"));
        } catch (NumberFormatException e) {
            bad(ctx, "Invalid company ID"); return;
        }
        service.removeCompanyParent(auth0Id, childId)
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

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

    /**
     * GET /api/roles/suggest — title suggestions for the add-manager form.
     *
     * <p>Public, and deliberately not an admin route despite living beside them: it is the part of
     * normalization that stops new spellings being created, so it has to be available wherever a
     * manager is added. Job titles are already public on every manager card.
     */
    public void handleSuggestRoles(RoutingContext ctx) {
        roleService.suggestTitles(ctx.queryParams().get("query"))
            .onSuccess(arr -> ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "application/json").end(arr.encode()))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── Role normalization ────────────────────────────────────────────────────

    /** GET /api/admin/roles — the alias table in frequency order, plus coverage. */
    public void handleListRoleAliases(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        int limit  = parseIntOr(ctx.queryParams().get("limit"), 50);
        int offset = parseIntOr(ctx.queryParams().get("offset"), 0);

        service.requireAdminPublic(auth0Id)
            .compose(adminId -> roleService.listAliases(limit, offset))
            .onSuccess(json -> ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    /**
     * POST /api/admin/roles/classify — classify one batch of not-yet-seen titles.
     *
     * <p>Unlike the AI jobs this is synchronous: the rules are pure string work over at most a few
     * hundred titles, so it finishes in well under a request timeout, and returning the resulting
     * coverage is far more useful than a fire-and-forget acknowledgement.
     */
    public void handleClassifyRoles(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        Future<String> authorized = "cron".equals(auth0Id)
            ? Future.succeededFuture(auth0Id)
            : service.requireAdminPublic(auth0Id).map(String::valueOf);

        authorized
            .compose(ignored -> roleService.classifyPending())
            .onSuccess(json -> ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "application/json").end(json.encode()))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    /** PUT /api/admin/roles — correct one mapping by hand; wins over every automated pass. */
    public void handleSetRoleAlias(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        JsonObject body = ctx.body().asJsonObject();
        if (body == null) {
            ctx.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("message", "Missing request body").encode());
            return;
        }

        service.requireAdminPublic(auth0Id)
            .compose(adminId -> roleService.classifyManually(
                body.getString("titleNormalized"),
                body.getString("roleFamily"),
                body.getString("seniority")))
            .onSuccess(v -> ctx.response().setStatusCode(204).end())
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    private static int parseIntOr(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException e) { return fallback; }
    }

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

    // ── POST /api/admin/industries/classify ──────────────────────────────────

    public void handleClassifyIndustries(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        if ("cron".equals(auth0Id)) { fireIndustryJob(ctx); return; }
        service.requireAdminPublic(auth0Id)
            .onSuccess(adminId -> fireIndustryJob(ctx))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    private void fireIndustryJob(RoutingContext ctx) {
        if (industryJob == null) {
            ctx.response().setStatusCode(503).putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", "Industry classification not configured — ANTHROPIC_API_KEY missing").encode());
            return;
        }
        // Fire-and-forget: back-filling can take minutes for thousands of companies, so we
        // return immediately and log the outcome. Admins can re-check via the industries listing.
        ctx.response().setStatusCode(202).putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("status", "started").encode());
        industryJob.run()
            .onSuccess(summary -> System.out.println("✓ Industry classification: " + summary.encode()))
            .onFailure(err -> System.err.println("⚠ Industry classification failed: " + err.getMessage()));
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

    // ── PUT /api/admin/managers/:managerId/career-history/:entryId ───────────

    public void handleUpdateCareerEntry(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        long managerId, entryId;
        try {
            managerId = Long.parseLong(ctx.pathParam("managerId"));
            entryId   = Long.parseLong(ctx.pathParam("entryId"));
        } catch (NumberFormatException e) { bad(ctx, "Invalid ID"); return; }
        JsonObject body = ctx.getBodyAsJson();
        if (body == null) { bad(ctx, "Body required"); return; }
        service.adminUpdateCareerEntry(auth0Id, managerId, entryId,
                body.getString("company"), body.getString("title"),
                body.getString("startDate"), body.getString("endDate"))
            .onSuccess(json -> ok(ctx, json))
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── DELETE /api/admin/managers/:managerId/career-history/:entryId ────────

    public void handleDeleteCareerEntry(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        long managerId, entryId;
        try {
            managerId = Long.parseLong(ctx.pathParam("managerId"));
            entryId   = Long.parseLong(ctx.pathParam("entryId"));
        } catch (NumberFormatException e) { bad(ctx, "Invalid ID"); return; }
        service.adminDeleteCareerEntry(auth0Id, managerId, entryId)
            .onSuccess(json -> ctx.response().setStatusCode(204).end())
            .onFailure(err -> ManagersHandler.handleError(ctx, err));
    }

    // ── GET /api/admin/country-stats ─────────────────────────────────────────

    public void handleGetCountryStats(RoutingContext ctx) {
        String auth0Id = ctx.get("auth0Id");
        service.getCountryStats(auth0Id)
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
