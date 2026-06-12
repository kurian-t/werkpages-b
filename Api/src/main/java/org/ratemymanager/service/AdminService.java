package org.ratemymanager.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import org.ratemymanager.repository.CompanyRepository;
import org.ratemymanager.repository.EditRepository;
import org.ratemymanager.repository.ManagerRepository;
import org.ratemymanager.repository.NotificationRepository;
import org.ratemymanager.repository.ReviewRepository;
import org.ratemymanager.repository.UserRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Business logic for all admin operations.
 */
public class AdminService {

    private final UserRepository         userRepo;
    private final ManagerRepository      managerRepo;
    private final ReviewRepository       reviewRepo;
    private final EditRepository         editRepo;
    private final NotificationRepository notifRepo;
    private final CompanyRepository      companyRepo;

    public AdminService(UserRepository userRepo, ManagerRepository managerRepo,
                        ReviewRepository reviewRepo, EditRepository editRepo,
                        NotificationRepository notifRepo) {
        this(userRepo, managerRepo, reviewRepo, editRepo, notifRepo, null);
    }

    public AdminService(UserRepository userRepo, ManagerRepository managerRepo,
                        ReviewRepository reviewRepo, EditRepository editRepo,
                        NotificationRepository notifRepo, CompanyRepository companyRepo) {
        this.userRepo    = userRepo;
        this.managerRepo = managerRepo;
        this.reviewRepo  = reviewRepo;
        this.editRepo    = editRepo;
        this.notifRepo   = notifRepo;
        this.companyRepo = companyRepo;
    }

    // ── Guard: verify admin ───────────────────────────────────────────────────

    /**
     * Resolves the caller to an admin UUID or fails with 401/403.
     * All public methods call this first.
     */
    private Future<UUID> requireAdmin(String auth0Id) {
        if (auth0Id == null) return Future.failedFuture(ServiceException.unauthorized("Unauthorized"));
        return userRepo.findByAuth0IdWithBan(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.unauthorized("User not found"));
                Row row = opt.get();
                if (!"admin".equals(row.getString("role"))) return Future.failedFuture(ServiceException.forbidden("Forbidden"));
                return Future.succeededFuture(row.getUUID("id"));
            });
    }

    // ── Pending managers ──────────────────────────────────────────────────────

    public Future<JsonObject> getPendingManagers(String auth0Id, int limit, int offset) {
        return requireAdmin(auth0Id)
            .compose(adminId -> managerRepo.findPendingForAdmin(limit, offset))
            .map(rows -> {
                JsonArray result = new JsonArray();
                for (Row row : rows) {
                    result.add(new JsonObject()
                        .put("id", row.getLong("id"))
                        .put("name", row.getString("name"))
                        .put("company", row.getString("company"))
                        .put("title", row.getString("title"))
                        .put("image", row.getString("image"))
                        .put("submittedBy", row.getString("submitted_by_username"))
                        .put("createdAt", row.getOffsetDateTime("created_at").toString())
                        .put("isAutoCreated", row.getBoolean("is_auto_created") == Boolean.TRUE)
                    );
                }
                return new JsonObject().put("data", result).put("limit", limit).put("offset", offset);
            });
    }

    public Future<JsonObject> approvePendingManager(String auth0Id, long managerId, String resolveLogoFn) {
        return requireAdmin(auth0Id)
            .compose(adminId -> managerRepo.approve(managerId))
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Pending manager not found"));
                Row row = opt.get();
                String company      = row.getString("company");
                String existingLogo = row.getString("company_logo_url");
                UUID   submittedBy  = row.getUUID("submitted_by");
                String managerName  = row.getString("name");

                // The handler provides the logo URL resolution since CompanyLogoUtils is in RestApi
                // We use the resolveLogoFn token to signal this needs resolution
                // Notify submitter
                if (submittedBy != null) {
                    notifRepo.sendAsync(submittedBy, "manager_approved",
                        "Manager Approved",
                        "Your manager profile for " + managerName +
                        " has been approved and is now live on the platform.",
                        managerId);
                }
                if (companyRepo != null) companyRepo.refreshCompanyStats()
                    .onFailure(err -> System.err.println("company_stats refresh failed: " + err.getMessage()));
                return Future.succeededFuture(new JsonObject()
                    .put("success", true)
                    .put("message", "Manager approved")
                    .put("_managerId", managerId)
                    .put("_needsLogo", existingLogo == null)
                    .put("_company", company)
                );
            });
    }

    public Future<JsonObject> rejectPendingManager(String auth0Id, long managerId, String reason) {
        return requireAdmin(auth0Id)
            .compose(adminId -> managerRepo.reject(managerId))
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Pending manager not found"));
                Row row = opt.get();
                UUID submittedBy = row.getUUID("submitted_by");
                String managerName = row.getString("name");
                String managerCompany = row.getString("company");
                if (submittedBy != null) {
                    String msg = "Your submitted manager profile for " + managerName + " at " + managerCompany + " was not approved.";
                    if (reason != null && !reason.isBlank()) msg += " Reason: " + reason.trim();
                    notifRepo.sendAsync(submittedBy, "manager_rejected", "Manager Not Approved", msg);
                }
                return Future.succeededFuture(new JsonObject().put("success", true));
            });
    }

    // ── Pending edits ─────────────────────────────────────────────────────────

    public Future<JsonObject> getPendingEdits(String auth0Id, int limit, int offset) {
        return requireAdmin(auth0Id)
            .compose(adminId -> editRepo.findPendingForAdmin(limit, offset))
            .map(rows -> {
                JsonArray result = new JsonArray();
                for (Row row : rows) {
                    result.add(new JsonObject()
                        .put("id", row.getUUID("id").toString())
                        .put("managerId", row.getLong("manager_id"))
                        .put("managerName", row.getString("manager_name"))
                        .put("currentCompany", row.getString("current_company"))
                        .put("currentTitle", row.getString("current_title"))
                        .put("requestedBy", row.getString("requested_by"))
                        .put("newCompany", row.getString("new_company"))
                        .put("newTitle", row.getString("new_title"))
                        .put("newStatus", row.getString("new_status"))
                        .put("newCountry", row.getString("new_country"))
                        .put("newLinkedinUrl", row.getString("new_linkedin_url"))
                        .put("status", row.getString("status"))
                        .put("createdAt", row.getOffsetDateTime("created_at").toString())
                    );
                }
                return new JsonObject().put("data", result).put("limit", limit).put("offset", offset);
            });
    }

    public Future<JsonObject> approveEdit(String auth0Id, UUID editId) {
        return requireAdmin(auth0Id)
            .compose(adminId -> editRepo.findByIdWithManager(editId)
                .compose(opt -> {
                    if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Edit request not found"));
                    Row row = opt.get();
                    if (!"pending".equals(row.getString("status"))) return Future.failedFuture(ServiceException.conflict("Edit request is not pending"));

                    long   managerId        = row.getLong("manager_id");
                    String currentCompany   = row.getString("current_company");
                    String currentTitle     = row.getString("current_title");
                    Long   currentCompanyId = row.getLong("current_company_id");
                    String newCompany       = row.getString("new_company");
                    String newTitle         = row.getString("new_title");
                    String newStatus        = row.getString("new_status");
                    String newCountry       = row.getString("new_country");
                    String newLinkedinUrl   = row.getString("new_linkedin_url");
                    String effectiveCo      = newCompany != null ? newCompany : currentCompany;
                    String effectiveTit     = newTitle   != null ? newTitle   : currentTitle;
                    UUID   proposedBy       = row.getUUID("proposed_by");
                    String managerName      = row.getString("manager_name");
                    OffsetDateTime now      = OffsetDateTime.now(ZoneOffset.UTC);

                    Future<Long> newCompanyIdFuture = (companyRepo != null)
                        ? companyRepo.findOrCreate(effectiveCo, null, null).map(r -> r.getLong("id"))
                        : Future.succeededFuture(currentCompanyId);

                    return newCompanyIdFuture.compose(newCompanyId ->
                        managerRepo.closeOpenCareerEntry(managerId, now)
                            .compose(closed -> {
                                Future<Void> archiveOld;
                                if (closed == 0) {
                                    OffsetDateTime oldStart = row.getOffsetDateTime("manager_created_at");
                                    archiveOld = managerRepo.insertCareerEntry(managerId, currentCompany, currentTitle, oldStart, now, currentCompanyId);
                                } else {
                                    archiveOld = Future.succeededFuture();
                                }
                                return archiveOld.compose(v ->
                                    managerRepo.insertCareerEntry(managerId, effectiveCo, effectiveTit, now, null, newCompanyId)
                                );
                            })
                            .compose(v -> applyEditAndApprove(managerId, editId, newCompany, newTitle, newStatus, newCountry, newLinkedinUrl, effectiveCo, effectiveTit, adminId, now, proposedBy, managerName, newCompanyId))
                    );
                })
            );
    }

    private Future<JsonObject> applyEditAndApprove(long managerId, UUID editId,
                                                     String newCompany, String newTitle, String newStatus, String newCountry,
                                                     String newLinkedinUrl, String effectiveCo, String effectiveTit,
                                                     UUID adminId, OffsetDateTime reviewedAt,
                                                     UUID proposedBy, String managerName, Long newCompanyId) {
        return managerRepo.update(managerId, newCompany, newTitle, null, null, newStatus, newCountry, newLinkedinUrl, null, newCompanyId)
            .compose(opt -> editRepo.approve(editId, adminId, reviewedAt))
            .compose(v -> {
                if (proposedBy != null) {
                    notifRepo.sendAsync(proposedBy, "review_accepted",
                        "Edit Request Approved",
                        "Your edit request for " + managerName + " has been approved. The manager's profile has been updated.",
                        managerId);
                }
                if (companyRepo != null) companyRepo.refreshCompanyStats()
                    .onFailure(err -> System.err.println("company_stats refresh failed: " + err.getMessage()));
                JsonObject result = new JsonObject().put("success", true).put("message", "Edit approved and applied")
                    .put("managerId", managerId);
                if (newCompany != null) result.put("newCompany", newCompany);
                return Future.succeededFuture(result);
            });
    }

    public Future<Void> updateManagerLogo(long managerId, String logoUrl) {
        return managerRepo.updateLogoUrl(managerId, logoUrl).mapEmpty();
    }

    public Future<JsonObject> rejectEdit(String auth0Id, UUID editId) {
        return requireAdmin(auth0Id)
            .compose(adminId -> editRepo.findPendingById(editId)
                .compose(opt -> {
                    if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Pending edit not found"));
                    Row fetchRow = opt.get();
                    if (!"pending".equals(fetchRow.getString("status"))) return Future.failedFuture(ServiceException.notFound("Pending edit not found"));
                    UUID proposedBy  = fetchRow.getUUID("proposed_by");
                    String managerName = fetchRow.getString("manager_name");
                    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                    return editRepo.reject(editId, adminId, now)
                        .compose(rowOpt -> {
                            if (rowOpt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Pending edit not found"));
                            if (proposedBy != null) {
                                notifRepo.sendAsync(proposedBy, "review_rejected", "Edit Request Rejected",
                                    "Your edit request for " + managerName + " was not approved.");
                            }
                            return Future.succeededFuture(new JsonObject().put("success", true));
                        });
                })
            );
    }

    // ── Users ─────────────────────────────────────────────────────────────────

    public Future<JsonObject> getUsers(String auth0Id, int limit, int offset) {
        return requireAdmin(auth0Id)
            .compose(adminId -> userRepo.listNonAdminUsers(limit, offset))
            .map(rows -> {
                JsonArray result = new JsonArray();
                for (Row row : rows) {
                    result.add(new JsonObject()
                        .put("id", row.getUUID("id").toString())
                        .put("username", row.getString("username"))
                        .put("firstName", row.getString("first_name"))
                        .put("lastName", row.getString("last_name"))
                        .put("isBanned", row.getUUID("ban_id") != null)
                    );
                }
                return new JsonObject().put("data", result).put("limit", limit).put("offset", offset);
            });
    }

    public Future<JsonObject> getBannedUsers(String auth0Id, int limit, int offset) {
        return requireAdmin(auth0Id)
            .compose(adminId -> userRepo.listBannedUsers(limit, offset))
            .map(rows -> {
                JsonArray result = new JsonArray();
                for (Row row : rows) {
                    result.add(new JsonObject()
                        .put("id", row.getUUID("id").toString())
                        .put("userId", row.getUUID("user_id").toString())
                        .put("username", row.getString("username"))
                        .put("reason", row.getString("reason"))
                        .put("bannedBy", row.getString("banned_by"))
                        .put("bannedAt", row.getOffsetDateTime("banned_at").toString())
                    );
                }
                return new JsonObject().put("data", result).put("limit", limit).put("offset", offset);
            });
    }

    public Future<JsonObject> banUser(String auth0Id, UUID targetUserId, String reason) {
        if (reason == null || reason.isBlank()) return Future.failedFuture(ServiceException.badRequest("Ban reason is required"));
        if (reason.length() > 500) return Future.failedFuture(ServiceException.badRequest("Reason must be at most 500 characters"));
        final String trimmed = reason.trim();
        return requireAdmin(auth0Id)
            .compose(adminId -> userRepo.findUsernameByAuth0Id(auth0Id))
            .compose(adminUsername ->
                userRepo.banUser(targetUserId, trimmed, adminUsername)
                    .compose(success -> {
                        if (!success) return Future.failedFuture(ServiceException.conflict("User is already banned"));
                        notifRepo.sendAsync(targetUserId, "user_banned",
                            "Account Suspended",
                            "Your account has been suspended. Reason: " + trimmed +
                            "\n\nIf you believe this was a mistake, you may appeal by emailing contact@ratemymanagers.ca");
                        return Future.succeededFuture(new JsonObject().put("success", true));
                    })
            );
    }

    public Future<JsonObject> unbanUser(String auth0Id, UUID targetUserId) {
        return requireAdmin(auth0Id)
            .compose(adminId -> userRepo.unbanUser(targetUserId))
            .compose(found -> {
                if (!found) return Future.failedFuture(ServiceException.notFound("Ban not found"));
                return Future.succeededFuture(new JsonObject().put("success", true));
            });
    }

    // ── Admin direct edit ────────────────────────────────────────────────────

    public Future<JsonObject> adminEditManager(String auth0Id, long managerId,
                                               String name, String title,
                                               String company, String linkedinUrl) {
        if (name        != null && name.isBlank())        return Future.failedFuture(ServiceException.badRequest("Name cannot be blank"));
        if (title       != null && title.isBlank())       return Future.failedFuture(ServiceException.badRequest("Title cannot be blank"));
        if (company     != null && company.isBlank())     return Future.failedFuture(ServiceException.badRequest("Company cannot be blank"));
        final String effCompany     = company     != null ? company.trim()     : null;
        final String effName        = name        != null ? name.trim()        : null;
        final String effTitle       = title       != null ? title.trim()       : null;
        final String effLinkedinUrl = linkedinUrl != null ? linkedinUrl.trim() : null;
        // When company changes, ensure a companies row exists and link company_id
        Future<Long> companyIdFuture = (effCompany != null && companyRepo != null)
            ? companyRepo.findOrCreate(effCompany, null, null).map(row -> row.getLong("id"))
            : Future.succeededFuture(null);
        return requireAdmin(auth0Id)
            .compose(adminId -> companyIdFuture)
            .compose(newCompanyId -> managerRepo.adminEdit(managerId, effName, effTitle, effCompany, effLinkedinUrl, newCompanyId))
            .compose(opt -> opt.isPresent()
                ? Future.succeededFuture(opt.get())
                : Future.failedFuture(ServiceException.notFound("Manager not found")));
    }

    // ── Merge managers ────────────────────────────────────────────────────────

    public Future<Void> deleteManager(String auth0Id, long managerId) {
        return requireAdmin(auth0Id)
            .compose(adminId -> managerRepo.countExistingById(new Long[]{managerId}))
            .compose(count -> {
                if (count < 1) return Future.failedFuture(ServiceException.notFound("Manager not found"));
                return reviewRepo.deleteByManager(managerId);
            })
            .compose(v -> managerRepo.delete(managerId));
    }

    public Future<JsonObject> mergeManagers(String auth0Id, long keepId, long mergeId) {
        if (keepId == mergeId) return Future.failedFuture(ServiceException.badRequest("Cannot merge a manager into itself"));
        return requireAdmin(auth0Id)
            .compose(adminId -> managerRepo.countExistingById(new Long[]{keepId, mergeId}))
            .compose(count -> {
                if (count < 2) return Future.failedFuture(ServiceException.notFound("One or both managers not found"));
                return reviewRepo.moveToManager(mergeId, keepId);
            })
            .compose(moved -> reviewRepo.deleteByManager(mergeId))
            .compose(v -> managerRepo.delete(mergeId))
            .compose(v -> managerRepo.mergeInlineRecalculate(keepId))
            .compose(v -> {
                if (companyRepo != null) companyRepo.refreshCompanyStats()
                    .onFailure(err -> System.err.println("company_stats refresh failed: " + err.getMessage()));
                return Future.succeededFuture(new JsonObject().put("success", true).put("keepId", keepId));
            });
    }

    // ── Company admin operations ──────────────────────────────────────────────

    public Future<JsonObject> adminListCompanies(String auth0Id) {
        return requireAdmin(auth0Id)
            .compose(adminId -> companyRepo.findAllForAdmin())
            .map(rows -> {
                JsonArray data = new JsonArray();
                for (Row row : rows) {
                    data.add(new JsonObject()
                        .put("id",           row.getLong("id"))
                        .put("name",         row.getString("name"))
                        .put("status",       row.getString("status"))
                        .put("managerCount", row.getLong("manager_count")));
                }
                return new JsonObject().put("data", data);
            });
    }

    public Future<JsonObject> adminRenameCompany(String auth0Id, long companyId, String newName) {
        if (newName == null || newName.isBlank())
            return Future.failedFuture(ServiceException.badRequest("Company name is required"));
        return requireAdmin(auth0Id)
            .compose(adminId -> companyRepo.findByName(newName))
            .compose(existing -> {
                if (existing.isPresent() && existing.get().getLong("id") != companyId)
                    return Future.failedFuture(ServiceException.conflict(
                        "A company named \"" + newName.trim() + "\" already exists — use the merge tool instead"));
                return companyRepo.renameCompany(companyId, newName);
            })
            .compose(v -> companyRepo.refreshCompanyStats())
            .map(v -> new JsonObject().put("success", true));
    }

    public Future<JsonObject> adminMergeCompanies(String auth0Id, long keepId, long mergeId) {
        if (keepId == mergeId)
            return Future.failedFuture(ServiceException.badRequest("Cannot merge a company into itself"));
        return requireAdmin(auth0Id)
            .compose(adminId -> companyRepo.mergeCompanies(keepId, mergeId))
            .compose(v -> companyRepo.refreshCompanyStats())
            .map(v -> new JsonObject().put("success", true).put("keepId", keepId));
    }
}
