package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.UserRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for manager reports.
 */
public class ReportService {

    private static final List<String> VALID_REASONS = List.of(
        "incorrect_person",
        "never_worked_here",
        "duplicate_profile",
        "incorrect_information",
        "other"
    );

    /** The states a report can be listed by, which are the states V114 allows it to hold. */
    private static final List<String> VALID_STATUSES = List.of("open", "resolved", "dismissed");

    /** What an admin may decide. Neither deletes the report; both take it out of the queue. */
    private static final List<String> VALID_ACTIONS = List.of("resolved", "dismissed");

    private static final int MAX_COMMENT = 500;
    private static final int MAX_PAGE    = 200;

    private final UserRepository userRepo;
    private final ReportRepository reportRepo;
    private final AdminService admin;

    public ReportService(UserRepository userRepo, ReportRepository reportRepo) {
        this(userRepo, reportRepo, null);
    }

    /**
     * @param admin the authorization collaborator for the queue. Null leaves the submit path fully
     *              working and makes every admin operation refuse, which is the same guard
     *              {@link ModerationService} carries: a service wired without its admin dependency
     *              must not run an unauthorized moderation call, and saying so is better than a
     *              {@link NullPointerException} arriving as a 500.
     */
    public ReportService(UserRepository userRepo, ReportRepository reportRepo, AdminService admin) {
        this.userRepo   = userRepo;
        this.reportRepo = reportRepo;
        this.admin      = admin;
    }

    public Future<JsonObject> reportManager(String auth0Id, long managerId,
                                             String reason, String comment) {
        // Validate input
        if (reason == null || reason.isBlank()) {
            return Future.failedFuture(ServiceException.badRequest("reason is required"));
        }
        if (!VALID_REASONS.contains(reason)) {
            return Future.failedFuture(ServiceException.badRequest("Invalid reason"));
        }
        if (comment != null && comment.length() > MAX_COMMENT) {
            return Future.failedFuture(ServiceException.badRequest("Comment must be at most 500 characters"));
        }

        return reportRepo.managerExists(managerId)
            .compose(exists -> {
                if (!exists) return Future.failedFuture(ServiceException.notFound("Manager not found"));
                return resolveOptionalUserId(auth0Id);
            })
            .compose(optUserId -> {
                if (optUserId.isPresent()) {
                    UUID userId = optUserId.get();
                    return reportRepo.alreadyReported(managerId, userId)
                        .compose(alreadyReported -> {
                            if (alreadyReported) return Future.failedFuture(ServiceException.conflict("already_reported"));
                            return reportRepo.create(managerId, userId, reason, comment);
                        });
                } else {
                    return reportRepo.create(managerId, null, reason, comment);
                }
            })
            .map(row -> new JsonObject()
                .put("success", true)
                .put("reportId", row.getUUID("id").toString())
                .put("createdAt", row.getOffsetDateTime("created_at").toString())
            );
    }

    // ── the queue (V114) ─────────────────────────────────────────────────────

    /**
     * What is waiting, for an admin.
     *
     * <p>This is the half of the feature that did not exist. The Report button wrote rows nobody
     * could read: the only {@code SELECT} against {@code reports} was the duplicate check, so a
     * report accumulated unseen while the profile page thanked the reporter and promised a review.
     * It is the correction route the Terms and the Privacy Policy point a rated person at, which is
     * why it is not merely a missing admin convenience.
     */
    public Future<JsonObject> listReports(String auth0Id, String status, int limit, int offset) {
        String wanted = (status == null || status.isBlank()) ? "open" : status;
        if (!VALID_STATUSES.contains(wanted)) {
            return Future.failedFuture(ServiceException.badRequest("Invalid status"));
        }
        int page = Math.min(Math.max(limit, 1), MAX_PAGE);
        int from = Math.max(offset, 0);

        return requireAdmin(auth0Id)
            .compose(adminId -> reportRepo.countByStatus(wanted)
                .compose(total -> reportRepo.listByStatus(wanted, page, from)
                    .map(rows -> {
                        JsonArray data = new JsonArray();
                        for (Row row : rows) data.add(toJson(row));
                        return new JsonObject()
                            .put("data", data)
                            .put("total", total)
                            .put("status", wanted);
                    })));
    }

    /**
     * Decides one report.
     *
     * <p>Three outcomes an admin must be able to tell apart: no such report (404), a report
     * somebody already decided (409), and a decision this call made (200). Collapsing the middle
     * one into success is how a report gets decided twice, the second time by somebody who cannot
     * tell the first decision happened.
     *
     * <p>The row is never deleted. A report is evidence about a profile and about the account that
     * filed it, and both outlive the decision.
     */
    public Future<JsonObject> resolveReport(String auth0Id, UUID reportId,
                                             String action, String note) {
        if (action == null || !VALID_ACTIONS.contains(action)) {
            return Future.failedFuture(ServiceException.badRequest("Invalid action"));
        }
        String trimmed = (note == null || note.isBlank()) ? null : note.trim();
        if (trimmed != null && trimmed.length() > MAX_COMMENT) {
            return Future.failedFuture(ServiceException.badRequest("Note must be at most 500 characters"));
        }

        return requireAdmin(auth0Id)
            .compose(adminId -> reportRepo.findStatus(reportId)
                .compose(current -> {
                    if (current.isEmpty()) {
                        return Future.failedFuture(ServiceException.notFound("Report not found"));
                    }
                    if (!"open".equals(current.get())) {
                        return Future.failedFuture(ServiceException.conflict("already_decided"));
                    }
                    return reportRepo.resolve(reportId, action, adminId, trimmed)
                        .compose(decided -> decided
                            // Lost the race to another admin between the read and the write.
                            ? Future.succeededFuture(new JsonObject()
                                .put("success", true)
                                .put("id", reportId.toString())
                                .put("status", action))
                            : Future.failedFuture(ServiceException.conflict("already_decided")));
                }));
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private Future<UUID> requireAdmin(String auth0Id) {
        if (admin == null) {
            return Future.failedFuture(ServiceException.forbidden("The report queue is unavailable."));
        }
        return admin.requireAdminPublic(auth0Id);
    }

    private static JsonObject toJson(Row row) {
        return new JsonObject()
            .put("id",             row.getUUID("id").toString())
            .put("managerId",      row.getLong("manager_id"))
            .put("managerName",    row.getString("manager_name"))
            .put("managerSlug",    row.getString("manager_slug"))
            .put("reason",         row.getString("reason"))
            .put("comment",        row.getString("comment"))
            .put("status",         row.getString("status"))
            .put("createdAt",      row.getOffsetDateTime("created_at").toString())
            /*
              The reporter's handle, and whether there is an account behind it at all.

              `reports.user_id` is ON DELETE SET NULL, so a null here means the account is gone -
              or, for a report filed with a credential that had no user row, that there never was
              one. The queue cannot tell those two apart and does not pretend to: it says only
              whether a reporter is known, which is the part an admin can act on.
            */
            .put("reporter",       row.getString("reporter_username"))
            .put("reporterKnown",  row.getBoolean("reporter_known"))
            .put("resolvedAt",     row.getOffsetDateTime("resolved_at") == null
                                     ? null : row.getOffsetDateTime("resolved_at").toString())
            .put("resolvedBy",     row.getString("resolved_by_username"))
            .put("resolutionNote", row.getString("resolution_note"));
    }

    private Future<Optional<UUID>> resolveOptionalUserId(String auth0Id) {
        if (auth0Id == null) return Future.succeededFuture(Optional.empty());
        return userRepo.findIdByAuth0Id(auth0Id).map(opt -> opt);
    }
}
