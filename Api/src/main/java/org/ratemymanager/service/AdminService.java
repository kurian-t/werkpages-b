package org.ratemymanager.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import org.ratemymanager.repository.CompanyRepository;
import org.ratemymanager.repository.EditRepository;
import org.ratemymanager.repository.ManagerRepository;
import org.ratemymanager.repository.MergeSuggestionsRepository;
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

    private final UserRepository              userRepo;
    private final ManagerRepository           managerRepo;
    private final ReviewRepository            reviewRepo;
    private final EditRepository              editRepo;
    private final NotificationRepository      notifRepo;
    private final CompanyRepository           companyRepo;
    private final MergeSuggestionsRepository  mergeSuggestionsRepo;
    private final SqlClient                   db;

    public AdminService(UserRepository userRepo, ManagerRepository managerRepo,
                        ReviewRepository reviewRepo, EditRepository editRepo,
                        NotificationRepository notifRepo) {
        this(userRepo, managerRepo, reviewRepo, editRepo, notifRepo, null, null, null);
    }

    public AdminService(UserRepository userRepo, ManagerRepository managerRepo,
                        ReviewRepository reviewRepo, EditRepository editRepo,
                        NotificationRepository notifRepo, CompanyRepository companyRepo) {
        this(userRepo, managerRepo, reviewRepo, editRepo, notifRepo, companyRepo, null, null);
    }

    public AdminService(UserRepository userRepo, ManagerRepository managerRepo,
                        ReviewRepository reviewRepo, EditRepository editRepo,
                        NotificationRepository notifRepo, CompanyRepository companyRepo,
                        MergeSuggestionsRepository mergeSuggestionsRepo) {
        this(userRepo, managerRepo, reviewRepo, editRepo, notifRepo, companyRepo, mergeSuggestionsRepo, null);
    }

    public AdminService(UserRepository userRepo, ManagerRepository managerRepo,
                        ReviewRepository reviewRepo, EditRepository editRepo,
                        NotificationRepository notifRepo, CompanyRepository companyRepo,
                        MergeSuggestionsRepository mergeSuggestionsRepo, SqlClient db) {
        this.userRepo             = userRepo;
        this.managerRepo          = managerRepo;
        this.reviewRepo           = reviewRepo;
        this.editRepo             = editRepo;
        this.notifRepo            = notifRepo;
        this.companyRepo          = companyRepo;
        this.mergeSuggestionsRepo = mergeSuggestionsRepo;
        this.db                   = db;
    }

    // ── Guard: verify admin ───────────────────────────────────────────────────

    /**
     * Resolves the caller to an admin UUID or fails with 401/403.
     * All public methods call this first.
     */
    public Future<UUID> requireAdminPublic(String auth0Id) { return requireAdmin(auth0Id); }

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

    public Future<JsonObject> getGhostManagers(String auth0Id, int limit, int offset) {
        return requireAdmin(auth0Id)
            .compose(adminId -> managerRepo.findGhostForAdmin(limit, offset))
            .map(rows -> {
                JsonArray result = new JsonArray();
                for (Row row : rows) {
                    result.add(new JsonObject()
                        .put("id",           row.getLong("id"))
                        .put("name",         row.getString("name"))
                        .put("company",      row.getString("company"))
                        .put("title",        row.getString("title"))
                        .put("logoUrl",      row.getString("company_logo_url"))
                        .put("overallRating", row.getBigDecimal("overall_rating"))
                        .put("reviewsCount", row.getInteger("reviews_count"))
                        .put("createdAt",    row.getOffsetDateTime("created_at").toString())
                    );
                }
                return new JsonObject().put("data", result).put("limit", limit).put("offset", offset);
            });
    }

    public Future<JsonObject> markGhostReviewed(String auth0Id, long managerId) {
        return requireAdmin(auth0Id)
            .compose(adminId -> managerRepo.approveGhost(managerId))
            .map(opt -> {
                if (opt.isEmpty()) return new JsonObject().put("success", false).put("message", "Ghost manager not found");
                Long companyId = opt.get().getLong("company_id");
                if (companyId != null && companyRepo != null)
                    companyRepo.updateCompanyStatsForManager(managerId)
                        .onFailure(err -> System.err.println("company_stats_live update failed: " + err.getMessage()));
                return new JsonObject().put("success", true).put("message", "Manager marked as reviewed");
            });
    }

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
                UUID   submittedBy         = row.getUUID("submitted_by");
                UUID   searchCreatedBy     = row.getUUID("search_created_by_user_id");
                String managerName         = row.getString("name");
                boolean isSearchCreated    = searchCreatedBy != null;

                // Only notify users who purposefully submitted a manager, not those whose
                // search silently created one — notifying them would reveal the capture.
                if (submittedBy != null && !isSearchCreated) {
                    notifRepo.sendAsync(submittedBy, "manager_approved",
                        "Manager Approved",
                        "Your manager profile for " + managerName +
                        " has been approved and is now live on the platform.",
                        managerId);
                }
                // Compute the real rating from submitted reviews now that the manager is live.
                managerRepo.recalculateInBackground(managerId);
                if (companyRepo != null)
                    companyRepo.updateCompanyStatsForManager(managerId)
                        .onFailure(err -> System.err.println("company_stats_live update failed: " + err.getMessage()));
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

                    long   managerId           = row.getLong("manager_id");
                    String currentCompany      = row.getString("current_company");
                    String currentTitle        = row.getString("current_title");
                    Long   currentCompanyId    = row.getLong("current_company_id");
                    String newCompany          = row.getString("new_company");
                    String newCompanyLogoUrl   = row.getString("new_company_logo_url");
                    String newTitle            = row.getString("new_title");
                    String newStatus        = row.getString("new_status");
                    String newCountry       = row.getString("new_country");
                    String newLinkedinUrl   = row.getString("new_linkedin_url");
                    OffsetDateTime newStartDate = row.getOffsetDateTime("new_start_date");
                    OffsetDateTime newEndDate   = row.getOffsetDateTime("new_end_date");
                    String effectiveCo      = newCompany != null ? newCompany : currentCompany;
                    String effectiveTit     = newTitle   != null ? newTitle   : currentTitle;
                    UUID   proposedBy       = row.getUUID("proposed_by");
                    String managerName      = row.getString("manager_name");
                    OffsetDateTime now      = OffsetDateTime.now(ZoneOffset.UTC);
                    // Use the user-specified start date for the new career entry; fall back to now.
                    OffsetDateTime careerStart = newStartDate != null ? newStartDate : now;

                    Future<Long> newCompanyIdFuture = (companyRepo != null)
                        ? companyRepo.findOrCreate(effectiveCo, null, null).map(r -> r.getLong("id"))
                        : Future.succeededFuture(currentCompanyId);

                    // Snapshot current slugs before update so we can record URL history if company changes
                    Future<Optional<Row>> slugsFuture = (newCompany != null)
                        ? managerRepo.findSlugs(managerId)
                        : Future.succeededFuture(Optional.empty());

                    return slugsFuture.compose(slugsOpt ->
                        newCompanyIdFuture.compose(newCompanyId ->
                            managerRepo.closeOpenCareerEntry(managerId, careerStart)
                                .compose(closed -> {
                                    Future<Void> archiveOld;
                                    if (closed == 0) {
                                        OffsetDateTime oldStart = row.getOffsetDateTime("manager_created_at");
                                        // Only archive the implicit initial entry if careerStart is at or after oldStart.
                                        // If careerStart < oldStart the new position predates the manager record; skip archival
                                        // to avoid violating the CHECK (end_date >= start_date) constraint.
                                        if (oldStart != null && !careerStart.isBefore(oldStart)) {
                                            archiveOld = managerRepo.insertCareerEntry(managerId, currentCompany, currentTitle, oldStart, careerStart, currentCompanyId);
                                        } else {
                                            archiveOld = Future.succeededFuture();
                                        }
                                    } else {
                                        archiveOld = Future.succeededFuture();
                                    }
                                    return archiveOld.compose(v ->
                                        managerRepo.insertCareerEntry(managerId, effectiveCo, effectiveTit, careerStart, newEndDate, newCompanyId)
                                    );
                                })
                                .compose(v -> applyEditAndApprove(managerId, editId, newCompany, newCompanyLogoUrl, newTitle, newStatus, newCountry, newLinkedinUrl, effectiveCo, effectiveTit, adminId, now, proposedBy, managerName, newCompanyId))
                                .compose(result -> {
                                    if (newCompany != null) {
                                        // Fire-and-forget: refresh old company's stats so its logo/counts stay accurate
                                        if (currentCompanyId != null && companyRepo != null)
                                            companyRepo.updateCompanyStatsForCompany(currentCompanyId)
                                                .onFailure(err -> System.err.println("old company stats update failed: " + err.getMessage()));
                                        // Fire-and-forget: record old URL so external/crawled links can resolve
                                        if (slugsOpt.isPresent()) {
                                            String oldCompanySlug = slugsOpt.get().getString("company_slug");
                                            String managerSlug    = slugsOpt.get().getString("slug");
                                            if (oldCompanySlug != null && managerSlug != null) {
                                                managerRepo.recordUrlHistory(managerId, oldCompanySlug, managerSlug)
                                                    .onFailure(err -> System.err.println("recordUrlHistory failed: " + err.getMessage()));
                                            }
                                        }
                                    }
                                    return Future.succeededFuture(result);
                                })
                        )
                    );
                })
            );
    }

    private Future<JsonObject> applyEditAndApprove(long managerId, UUID editId,
                                                     String newCompany, String newCompanyLogoUrl, String newTitle, String newStatus, String newCountry,
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
                if (companyRepo != null)
                    companyRepo.updateCompanyStatsForManager(managerId)
                        .onFailure(err -> System.err.println("company_stats_live update failed: " + err.getMessage()));
                JsonObject result = new JsonObject().put("success", true).put("message", "Edit approved and applied")
                    .put("managerId", managerId);
                if (newCompany != null) {
                    result.put("newCompany", newCompany);
                    if (newCompanyLogoUrl != null) result.put("newCompanyLogoUrl", newCompanyLogoUrl);
                }
                return Future.succeededFuture(result);
            });
    }

    public Future<Void> updateManagerLogo(long managerId, String logoUrl) {
        return managerRepo.updateLogoUrl(managerId, logoUrl)
            .compose(ignored -> companyRepo.updateCompanyStatsForManager(managerId));
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
                        .put("firstName", userRepo.decryptField(row.getString("first_name")))
                        .put("lastName",  userRepo.decryptField(row.getString("last_name")))
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
            .compose(adminId -> managerRepo.findById(managerId))
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Manager not found"));
                Long companyId = opt.get().getLong("company_id");
                return reviewRepo.deleteByManager(managerId)
                    .compose(v -> managerRepo.delete(managerId))
                    .compose(v -> {
                        if (companyId != null && companyRepo != null) {
                            return companyRepo.updateCompanyStatsForCompany(companyId);
                        }
                        return Future.succeededFuture();
                    });
            });
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
                if (companyRepo != null)
                    companyRepo.updateCompanyStatsForManager(keepId)
                        .onFailure(err -> System.err.println("company_stats_live update failed: " + err.getMessage()));
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
            .compose(v -> companyRepo.updateCompanyStatsForCompany(companyId))
            .map(v -> new JsonObject().put("success", true));
    }

    public Future<JsonObject> adminMergeCompanies(String auth0Id, long keepId, long mergeId) {
        if (keepId == mergeId)
            return Future.failedFuture(ServiceException.badRequest("Cannot merge a company into itself"));
        return requireAdmin(auth0Id)
            .compose(adminId -> companyRepo.mergeCompanies(keepId, mergeId))
            .compose(v -> companyRepo.updateCompanyStatsForCompany(keepId))
            .map(v -> new JsonObject().put("success", true).put("keepId", keepId));
    }

    // ── Merge suggestions ─────────────────────────────────────────────────────

    public Future<JsonObject> getMergeSuggestions(String auth0Id, int limit, int offset) {
        return requireAdmin(auth0Id)
            .compose(adminId -> Future.all(
                mergeSuggestionsRepo.findPending(limit, offset),
                mergeSuggestionsRepo.countPending()
            ))
            .map(cf -> {
                var rows  = cf.<io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>>resultAt(0);
                int total = cf.<Integer>resultAt(1);
                var data  = new JsonArray();
                for (var row : rows) {
                    data.add(new JsonObject()
                        .put("id",         row.getLong("id"))
                        .put("confidence", row.getString("confidence"))
                        .put("reason",     row.getString("reason"))
                        .put("status",     row.getString("status"))
                        .put("managerA", new JsonObject()
                            .put("id",      row.getLong("id_a"))
                            .put("name",    row.getString("name_a"))
                            .put("company", row.getString("company_a"))
                            .put("title",   row.getString("title_a"))
                            .put("country", row.getString("country_a"))
                            .put("reviews", row.getInteger("reviews_a")))
                        .put("managerB", new JsonObject()
                            .put("id",      row.getLong("id_b"))
                            .put("name",    row.getString("name_b"))
                            .put("company", row.getString("company_b"))
                            .put("title",   row.getString("title_b"))
                            .put("country", row.getString("country_b"))
                            .put("reviews", row.getInteger("reviews_b")))
                    );
                }
                return new JsonObject().put("data", data).put("total", total);
            });
    }

    public Future<JsonObject> dismissMergeSuggestion(String auth0Id, long suggestionId) {
        return requireAdmin(auth0Id)
            .compose(adminId -> mergeSuggestionsRepo.updateStatus(suggestionId, "dismissed"))
            .map(v -> new JsonObject().put("success", true));
    }

    public Future<JsonObject> getCountryStats(String auth0Id) {
        if (db == null) return Future.failedFuture(ServiceException.forbidden("DB not configured"));
        return requireAdmin(auth0Id)
            .compose(adminId -> Future.all(
                db.preparedQuery("""
                    SELECT COALESCE(country, 'Unknown') AS country, COUNT(*) AS count
                    FROM managers
                    WHERE approval_status IN ('approved', 'ghost')
                    GROUP BY country
                    ORDER BY count DESC
                    """).execute(),
                db.preparedQuery("""
                    SELECT COALESCE(m.country, 'Unknown') AS country, COUNT(*) AS count
                    FROM reviews r
                    JOIN managers m ON r.manager_id = m.id
                    WHERE r.weight = FALSE
                      AND r.deleted_at IS NULL
                      AND m.approval_status IN ('approved', 'ghost')
                    GROUP BY m.country
                    ORDER BY count DESC
                    """).execute()
            ))
            .map(cf -> {
                JsonArray managers = new JsonArray();
                for (Row row : (io.vertx.sqlclient.RowSet<Row>) cf.resultAt(0)) {
                    managers.add(new JsonObject()
                        .put("country", row.getString("country"))
                        .put("count",   row.getLong("count")));
                }
                JsonArray reviews = new JsonArray();
                for (Row row : (io.vertx.sqlclient.RowSet<Row>) cf.resultAt(1)) {
                    reviews.add(new JsonObject()
                        .put("country", row.getString("country"))
                        .put("count",   row.getLong("count")));
                }
                return new JsonObject()
                    .put("managers", managers)
                    .put("reviews",  reviews);
            });
    }
}
