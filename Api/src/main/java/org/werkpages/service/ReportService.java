package org.werkpages.service;

import io.vertx.core.Future;
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

    private final UserRepository userRepo;
    private final ReportRepository reportRepo;

    public ReportService(UserRepository userRepo, ReportRepository reportRepo) {
        this.userRepo   = userRepo;
        this.reportRepo = reportRepo;
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
        if (comment != null && comment.length() > 500) {
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

    // ── Internal ──────────────────────────────────────────────────────────────

    private Future<Optional<UUID>> resolveOptionalUserId(String auth0Id) {
        if (auth0Id == null) return Future.succeededFuture(Optional.empty());
        return userRepo.findIdByAuth0Id(auth0Id).map(opt -> opt);
    }
}
