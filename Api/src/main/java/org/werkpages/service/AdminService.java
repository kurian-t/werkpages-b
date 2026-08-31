package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.MergeSuggestionsRepository;
import org.werkpages.repository.NotificationRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;

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
            .compose(opt -> {
                if (opt.isEmpty())
                    return Future.succeededFuture(new JsonObject().put("success", false).put("message", "Ghost manager not found"));
                JsonObject ok = new JsonObject().put("success", true).put("message", "Manager marked as reviewed");
                Long companyId = opt.get().getLong("company_id");
                if (companyId == null || companyRepo == null) return Future.succeededFuture(ok);
                // Awaited: the stats write must not outlive the request that triggered it.
                return companyRepo.syncStatsForManager(managerId).map(statsDone -> ok);
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
                JsonObject ok = new JsonObject()
                    .put("success", true)
                    .put("message", "Manager approved")
                    .put("_managerId", managerId)
                    .put("_needsLogo", existingLogo == null)
                    .put("_company", company);
                if (companyRepo == null) return Future.succeededFuture(ok);
                // Awaited: the stats write must not outlive the request that triggered it.
                return companyRepo.syncStatsForManager(managerId).map(statsDone -> ok);
            });
    }

    public Future<JsonObject> rejectPendingManager(String auth0Id, long managerId, String reason) {
        return requireAdmin(auth0Id)
            .compose(adminId -> managerRepo.reject(managerId))
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Pending manager not found"));
                Row row = opt.get();
                UUID submittedBy     = row.getUUID("submitted_by");
                UUID searchCreatedBy = row.getUUID("search_created_by_user_id");
                String managerName    = row.getString("name");
                String managerCompany = row.getString("company");
                boolean isSearchCreated = searchCreatedBy != null;
                // Only notify users who explicitly submitted — search-created managers must not
                // send rejection emails the user would find confusing (they just searched).
                if (submittedBy != null && !isSearchCreated) {
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

                    // Identity comes from what the user selected, never from re-resolving the name
                    // they typed. A request that carries no identity is one written before V56 (or
                    // by a client that did not send one), and it is refused rather than guessed:
                    // matching "Crumbl" to whichever row currently holds that string is the exact
                    // assumption that created duplicate companies. The admin resolves it once
                    // through the picker and re-approves.
                    Long requestedCompanyId = row.getLong("requested_company_id");
                    boolean companyChanging = newCompany != null
                        && !newCompany.equalsIgnoreCase(currentCompany == null ? "" : currentCompany);
                    if (companyChanging && requestedCompanyId == null) {
                        return Future.failedFuture(ServiceException.badRequest(
                            "This edit request was made before companies were identified by ID, so "
                            + "approving it cannot tell which \"" + newCompany + "\" is meant. "
                            + "Set the company on the manager directly, then reject this request."));
                    }
                    Future<Long> newCompanyIdFuture = requestedCompanyId != null
                        ? Future.succeededFuture(requestedCompanyId)
                        : Future.succeededFuture(currentCompanyId);

                    // Snapshot current slugs before update so we can record URL history if company changes
                    Future<Optional<Row>> slugsFuture = (newCompany != null)
                        ? managerRepo.findSlugs(managerId)
                        : Future.succeededFuture(Optional.empty());

                    return slugsFuture.compose(slugsOpt ->
                        newCompanyIdFuture.compose(newCompanyId -> {
                            if (newEndDate != null) {
                                // User is adding a PAST role (has an end date) — insert the segment
                                // without closing the current open career entry or changing manager.company.
                                return managerRepo.insertCareerEntry(managerId, effectiveCo, effectiveTit, careerStart, newEndDate, newCompanyId)
                                    .compose(v -> applyEditAndApprove(managerId, editId, null, null, null, newStatus, newCountry, newLinkedinUrl, effectiveCo, effectiveTit, adminId, now, proposedBy, managerName, null));
                            }
                            // No end date. Only treat this as a genuine *current* role change when the
                            // new role starts on/after the manager's existing current role. An older
                            // open-ended role must be archived as a past segment WITHOUT taking over the
                            // manager's headline company/title/logo (the most-recent role stays on top).
                            return managerRepo.findCurrentRoleStart(managerId).compose(curStartOpt -> {
                              OffsetDateTime currentStart = curStartOpt.orElse(row.getOffsetDateTime("manager_created_at"));
                              boolean isHistorical = newStartDate != null && currentStart != null && newStartDate.isBefore(currentStart);
                              if (isHistorical) {
                                  return managerRepo.insertCareerEntry(managerId, effectiveCo, effectiveTit, careerStart, currentStart, newCompanyId)
                                      .compose(v -> applyEditAndApprove(managerId, editId, null, null, null, newStatus, newCountry, newLinkedinUrl, effectiveCo, effectiveTit, adminId, now, proposedBy, managerName, null));
                              }
                              return managerRepo.closeOpenCareerEntry(managerId, careerStart)
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
                                        managerRepo.insertCareerEntry(managerId, effectiveCo, effectiveTit, careerStart, null, newCompanyId)
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
                                });
                            });
                        })
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
                Future<Void> statsFuture = companyRepo != null
                    ? companyRepo.syncStatsForManager(managerId)
                    : Future.succeededFuture();
                JsonObject result = new JsonObject().put("success", true).put("message", "Edit approved and applied")
                    .put("managerId", managerId);
                if (newCompany != null) {
                    result.put("newCompany", newCompany);
                    if (newCompanyLogoUrl != null) result.put("newCompanyLogoUrl", newCompanyLogoUrl);
                }
                return statsFuture.map(statsDone -> result);
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
                            "\n\nIf you believe this was a mistake, you may appeal by emailing contact@werkpages.com");
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

    /**
     * Overload for callers with no picker selection: identity is resolved from the company name,
     * as it was before company IDs existed.
     */
    public Future<JsonObject> adminEditManager(String auth0Id, long managerId,
                                               String name, String title,
                                               String company, String linkedinUrl) {
        return adminEditManager(auth0Id, managerId, name, title, company, linkedinUrl, null);
    }

    /**
     * @param companyId the company an admin picked from the typeahead, when they picked one.
     *                  Non-null means identity is settled and {@code company} is display text.
     *                  Without it an admin correcting a company name silently creates a duplicate,
     *                  which is the opposite of what an admin edit is usually trying to achieve.
     */
    public Future<JsonObject> adminEditManager(String auth0Id, long managerId,
                                               String name, String title,
                                               String company, String linkedinUrl,
                                               Long companyId) {
        if (name        != null && name.isBlank())        return Future.failedFuture(ServiceException.badRequest("Name cannot be blank"));
        if (title       != null && title.isBlank())       return Future.failedFuture(ServiceException.badRequest("Title cannot be blank"));
        if (company     != null && company.isBlank())     return Future.failedFuture(ServiceException.badRequest("Company cannot be blank"));
        final String effCompany     = company     != null ? company.trim()     : null;
        final String effName        = name        != null ? name.trim()        : null;
        final String effTitle       = title       != null ? title.trim()       : null;
        final String effLinkedinUrl = linkedinUrl != null ? linkedinUrl.trim() : null;
        // When company changes, ensure a companies row exists and link company_id
        Future<Long> companyIdFuture = (effCompany != null && companyRepo != null)
            ? companyRepo.resolve(companyId, effCompany, null, null).map(row -> row.getLong("id"))
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
                JsonObject ok = new JsonObject().put("success", true).put("keepId", keepId);
                if (companyRepo == null) return Future.succeededFuture(ok);
                return companyRepo.syncStatsForManager(keepId).map(statsDone -> ok);
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

    /** What a merge would move, and whether it can safely run. Reads only; writes nothing. */
    public Future<JsonObject> previewCompanyMerge(String auth0Id, long keepId, long mergeId) {
        return requireAdmin(auth0Id)
            .compose(adminId -> companyRepo.previewMerge(keepId, mergeId))
            .recover(err -> Future.failedFuture(ServiceException.badRequest(err.getMessage())));
    }

    // ── Corporate relationships ───────────────────────────────────────────────

    private static final java.util.Set<String> RELATIONSHIP_TYPES = java.util.Set.of(
        "SUBSIDIARY_OF", "BRAND_OF", "DIVISION_OF", "OWNED_BY", "FRANCHISE_OF", "JOINT_VENTURE_OF");

    /**
     * Records that one company is part of another. Not a merge: both keep their pages, managers
     * and ratings, and the child stays independently searchable.
     */
    public Future<JsonObject> setCompanyParent(String auth0Id, long childId, long parentId, String type) {
        if (childId == parentId)
            return Future.failedFuture(ServiceException.badRequest("A company cannot be part of itself"));
        String relationshipType = (type == null || type.isBlank()) ? "SUBSIDIARY_OF" : type.trim().toUpperCase();
        if (!RELATIONSHIP_TYPES.contains(relationshipType))
            return Future.failedFuture(ServiceException.badRequest("Unknown relationship type: " + relationshipType));

        return requireAdmin(auth0Id)
            .compose(adminId -> companyRepo.setCompanyParent(childId, parentId, relationshipType))
            // The loop check lives in a database trigger, so it fires for any writer. Translating
            // it here turns an opaque constraint violation into something an admin can act on.
            .recover(err -> {
                String msg = err.getMessage() == null ? "" : err.getMessage();
                if (msg.contains("loop in the ownership chain"))
                    return Future.failedFuture(ServiceException.badRequest(
                        "That would create a loop: the parent is already somewhere beneath this company."));
                return Future.failedFuture(err);
            })
            .map(v -> new JsonObject().put("success", true));
    }

    public Future<JsonObject> removeCompanyParent(String auth0Id, long childId) {
        return requireAdmin(auth0Id)
            .compose(adminId -> companyRepo.removeCompanyParent(childId))
            .map(removed -> new JsonObject().put("success", true).put("removed", removed));
    }

    public Future<JsonObject> adminMergeCompanies(String auth0Id, long keepId, long mergeId) {
        if (keepId == mergeId)
            return Future.failedFuture(ServiceException.badRequest("Cannot merge a company into itself"));
        return requireAdmin(auth0Id)
            .compose(adminId -> companyRepo.mergeCompanies(keepId, mergeId, adminId)
                // A refusal here is a decision the admin has to make, not a server fault: surface
                // it as a 400 with the reason rather than a 500.
                .recover(err -> Future.failedFuture(ServiceException.badRequest(err.getMessage()))))
            .compose(mergeUuid -> companyRepo.syncStatsForCompany(keepId).map(v -> mergeUuid))
            .map(mergeUuid -> new JsonObject()
                .put("success", true)
                .put("keepId", keepId)
                .put("mergeId", mergeUuid.toString()));
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

    // ── Career history admin ──────────────────────────────────────────────────

    public Future<JsonObject> adminUpdateCareerEntry(String auth0Id, long managerId, long entryId,
            String company, String title, String startDateStr, String endDateStr) {
        return requireAdmin(auth0Id)
            .compose(adminId -> {
                if (company == null || company.isBlank()) return Future.failedFuture(ServiceException.badRequest("company required"));
                if (title  == null || title.isBlank())   return Future.failedFuture(ServiceException.badRequest("title required"));
                if (startDateStr == null || startDateStr.isBlank()) return Future.failedFuture(ServiceException.badRequest("startDate required"));
                OffsetDateTime start;
                OffsetDateTime end = null;
                try {
                    start = OffsetDateTime.parse(startDateStr.length() == 4
                        ? startDateStr + "-01-01T00:00:00Z"
                        : startDateStr + "-01T00:00:00Z");
                    if (endDateStr != null && !endDateStr.isBlank()) {
                        end = OffsetDateTime.parse(endDateStr.length() == 4
                            ? endDateStr + "-01-01T00:00:00Z"
                            : endDateStr + "-01T00:00:00Z");
                    }
                } catch (Exception e) {
                    return Future.failedFuture(ServiceException.badRequest("Invalid date format"));
                }
                return managerRepo.updateCareerEntry(entryId, managerId, company.trim(), title.trim(), start, end);
            })
            .map(count -> new JsonObject().put("success", true).put("updated", count));
    }

    public Future<JsonObject> adminDeleteCareerEntry(String auth0Id, long managerId, long entryId) {
        return requireAdmin(auth0Id)
            .compose(adminId -> managerRepo.deleteCareerEntry(entryId, managerId))
            .map(count -> new JsonObject().put("success", true).put("deleted", count));
    }

    public Future<JsonObject> getCountryStats(String auth0Id) {
        if (db == null) return Future.failedFuture(ServiceException.forbidden("DB not configured"));
        return requireAdmin(auth0Id)
            .compose(adminId -> Future.all(
                db.preparedQuery("""
                    SELECT COALESCE(country, 'Unknown') AS country, COUNT(*) AS count
                    FROM managers
                    WHERE approval_status IN ('approved', 'ghost')
                      AND external_id IS NULL
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
                      AND m.external_id IS NULL
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
