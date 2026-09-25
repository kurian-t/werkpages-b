package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import org.werkpages.repository.CapturedDraftRepository;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.ConfidenceRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.MergeSuggestionsRepository;
import org.werkpages.repository.NotificationRepository;
import org.werkpages.repository.ProofChallengeRepository;
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

    /* Stateless, and constructed in place like the other collaborators here. */
    private final ReviewDisposition reviewDisposition = new ReviewDisposition();
    private final CapturedDraftRepository capturedDrafts;

    private final UserRepository              userRepo;
    private final ManagerRepository           managerRepo;
    private final ReviewRepository            reviewRepo;
    private final EditRepository              editRepo;
    private final NotificationRepository      notifRepo;
    private final CompanyRepository           companyRepo;
    private final org.werkpages.repository.AnonymousGhostSlotRepository ghostSlots;
    private final MergeSuggestionsRepository  mergeSuggestionsRepo;
    private final SqlClient                   db;
    private final ProofChallengeRepository    challengeRepo;
    private final ConfidenceRepository        confidenceRepo;

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
        // Guarded like challengeRepo below: several constructors are used by unit tests with no
        // client at all, and an unconditional construction would fail them on load.
        this.ghostSlots = db == null ? null
            : new org.werkpages.repository.AnonymousGhostSlotRepository(db);
        this.mergeSuggestionsRepo = mergeSuggestionsRepo;
        this.db                   = db;
        this.challengeRepo  = db == null ? null : new ProofChallengeRepository(db);
        this.confidenceRepo = db == null ? null : new ConfidenceRepository(db);
        // Guarded for the same reason as the two above.
        this.capturedDrafts = db == null ? null : new CapturedDraftRepository(db);
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

    /**
     * Live managers stuck on a company-suffixed slug whose plain name is now free.
     *
     * <p>Their slug was decided at INSERT against whatever held the name at that instant, and what
     * held it was usually invisible - a draft captured mid-typing, or a manager later rejected.
     * Freeing those namespaces makes the plain name available again, but nothing goes back to
     * check, so the real person stays on the suffixed URL for ever.
     *
     * <p>Listed rather than repaired automatically: moving a live slug changes a public, possibly
     * indexed URL, and that is a decision about timing rather than correctness.
     */
    public Future<JsonObject> listSlugReclaimCandidates(String auth0Id, int limit) {
        return requireAdmin(auth0Id)
            .compose(adminId -> managerRepo.findSlugReclaimCandidates(limit))
            .map(rows -> {
                JsonArray data = new JsonArray();
                for (Row row : rows) {
                    data.add(new JsonObject()
                        .put("id",           row.getLong("id"))
                        .put("name",         row.getString("name"))
                        .put("company",      row.getString("company"))
                        .put("currentSlug",  row.getString("slug"))
                        .put("cleanSlug",    row.getString("clean_slug"))
                        .put("reviewsCount", row.getInteger("reviews_count")));
                }
                return new JsonObject().put("data", data);
            });
    }

    /**
     * Moves one manager onto the plain name, recording the URL it is leaving.
     *
     * <p>The same operation a merge performs, reused rather than rewritten - it is conservative,
     * declines when the name is genuinely held, and writes manager_url_history first so links
     * already shared keep resolving.
     */
    public Future<JsonObject> reclaimManagerSlug(String auth0Id, long managerId) {
        return requireAdmin(auth0Id)
            .compose(adminId -> managerRepo.reclaimBaseSlug(managerId))
            .compose(moved -> managerRepo.findSlugs(managerId)
                .map(slugs -> new JsonObject()
                    .put("success", true)
                    .put("moved", moved)
                    .put("slug", slugs.map(r -> r.getString("slug")).orElse(null))));
    }

    /**
     * What approving this pending manager would do to its slug, before anything is approved.
     *
     * <p>A pending row lives in the {@code -pending} namespace precisely so it cannot squat on the
     * name a reader would expect. Approval moves it onto that name - unless somebody published is
     * already there, which is a judgement rather than a collision: the same person entered twice,
     * or two people who share a name. Only an admin can tell which, so the panel is shown the
     * holder and offered both ways out instead of the database quietly appending a company.
     */
    public Future<JsonObject> previewApprovalSlug(String auth0Id, long managerId) {
        return requireAdmin(auth0Id)
            .compose(adminId -> managerRepo.findById(managerId))
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Manager not found"));
                Row row = opt.get();
                String cleanSlug = managerRepo.cleanSlugFor(row.getString("name"));
                /*
                    Two different questions, and only asking the first one missed the common case.

                    "Does something hold this slug?" catches a duplicate whose name matches
                    exactly. It structurally cannot catch "Emma D" against a live "Emma Davis",
                    because those slugs do not collide - and a truncated name is precisely what a
                    half-finished form produces. Approving one silently forks the person in two.

                    So the likely-same-person question is asked as well, using the very matcher the
                    add form already shows people while they type: name containment, company as a
                    ranking signal rather than a filter, live rows only. Reused rather than
                    reimplemented - one definition of "looks like the same person" in the codebase.

                    Warned, never blocked. Two people really can share a name, and an admin who
                    cannot publish the second one has a worse problem than a duplicate.
                */
                String nameLike = "%" + row.getString("name").trim() + "%";
                String companyLike = row.getString("company") != null && !row.getString("company").isBlank()
                    ? "%" + row.getString("company").trim() + "%" : "%";
                return managerRepo.findSimilar(nameLike, companyLike).compose(similar ->
                       managerRepo.findLiveHolderOfSlug(cleanSlug).map(holder -> {
                    JsonObject out = new JsonObject()
                        .put("managerId",   managerId)
                        .put("currentSlug", row.getString("slug"))
                        .put("cleanSlug",   cleanSlug)
                        .put("available",   holder.isEmpty());
                    if (holder.isPresent()) {
                        Row h = holder.get();
                        out.put("heldBy", new JsonObject()
                            .put("id",             h.getLong("id"))
                            .put("name",           h.getString("name"))
                            .put("company",        h.getString("company"))
                            .put("slug",           h.getString("slug"))
                            .put("approvalStatus", h.getString("approval_status"))
                            .put("reviewsCount",   h.getInteger("reviews_count")));
                        // The alternative offered alongside "merge into that one".
                        out.put("suggestedSlug", managerRepo.cleanSlugFor(
                            row.getString("name") + " " + row.getString("company")));
                    }
                    JsonArray looksLike = new JsonArray();
                    for (Row cand : similar) {
                        if (cand.getLong("id") == managerId) continue;
                        looksLike.add(new JsonObject()
                            .put("id",      cand.getLong("id"))
                            .put("name",    cand.getString("name"))
                            .put("company", cand.getString("company"))
                            .put("title",   cand.getString("title")));
                    }
                    // Present even when the slug is free: that is the case the slug check misses.
                    out.put("looksLike", looksLike);
                    return out;
                }));
            });
    }

    public Future<JsonObject> approvePendingManager(String auth0Id, long managerId, String resolveLogoFn) {
        return approvePendingManager(auth0Id, managerId, resolveLogoFn, null);
    }

    /**
     * @param requestedSlug the slug an admin explicitly chose when the clean one was taken, or
     *                      null to take the clean slug if it is free and otherwise leave the row
     *                      where it is rather than inventing a name nobody asked for
     */
    public Future<JsonObject> approvePendingManager(String auth0Id, long managerId, String resolveLogoFn,
                                                    String requestedSlug) {
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
                /*
                    Leave the pending namespace.

                    The row has been approved, so it is now something a reader can open, and it
                    should be on the name they would expect rather than on scaffolding. An
                    explicitly requested slug wins - that is the admin having resolved a conflict.
                    Otherwise take the clean name if it is free, and if it is not, leave the row
                    where it is: a published manager already holds that name, and quietly
                    appending a company is how this went wrong in the first place.
                */
                String cleanSlug = managerRepo.cleanSlugFor(managerName);
                Future<Void> reslugged = requestedSlug != null && !requestedSlug.isBlank()
                    ? managerRepo.reslug(managerId, requestedSlug.trim())
                    : managerRepo.findLiveHolderOfSlug(cleanSlug).compose(holder -> holder.isEmpty()
                        ? managerRepo.reslug(managerId, cleanSlug)
                        : Future.succeededFuture());

                // Compute the real rating from submitted reviews now that the manager is live.
                // Awaited below, before the company sync: see the ordering note there.
                Future<Void> recalculated = reslugged.compose(v -> managerRepo.recalculate(managerId));
                JsonObject ok = new JsonObject()
                    .put("success", true)
                    .put("message", "Manager approved")
                    .put("_managerId", managerId)
                    .put("_needsLogo", existingLogo == null)
                    .put("_company", company);
                if (companyRepo == null) return recalculated.map(recalcDone -> ok);
                /*
                    Both awaited, and in this order. company_stats_live is derived from
                    managers.reviews_count and managers.overall_rating, so syncing before the
                    recalculation lands writes the company's figures from the pre-approval
                    numbers. The sync was already awaited; the recalculation it depends on was not.
                */
                return recalculated
                    .compose(v -> companyRepo.syncStatsForManager(managerId))
                    .map(statsDone -> ok);
            });
    }

    public Future<JsonObject> rejectPendingManager(String auth0Id, long managerId, String reason) {
        return requireAdmin(auth0Id)
            // reject() also sweeps up the ghost twins the submission left behind, in the same
            // statement, so a person cannot be rejected here and still appear under a half-typed
            // company name somewhere else.
            .compose(adminId -> managerRepo.reject(managerId))
            /*
              Free the name. A rejected manager is invisible everywhere, but it went on holding
              its slug for ever - so a name an admin had explicitly taken down could never be used
              by the real person it belonged to. Same defect as a pending row squatting on a clean
              name, one status along.
            */
            .compose(opt -> opt.isEmpty()
                ? io.vertx.core.Future.succeededFuture(opt)
                : managerRepo.parkSlugAsRejected(managerId).map(v -> opt))
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
                    // Same test, same reason, now also gating confidence. A ghost manager created
                    // by somebody's search on /find is not a submission: they typed a name into a
                    // search box. Rejecting it must cost them nothing, because a score they cannot
                    // see, appeal, or even know exists must never move on something we chose to
                    // keep silent. If we would not tell you about it, it cannot count against you.
                    if (confidenceRepo != null) {
                        confidenceRepo.apply(submittedBy, ConfidenceRepository.MANAGER_REJECTED_JUNK,
                                -20, "manager", String.valueOf(managerId))
                            .onFailure(err -> System.err.println(
                                "Confidence debit failed for manager " + managerId + ": " + err.getMessage()));
                    }
                    String msg = "Your submitted manager profile for " + managerName + " at " + managerCompany + " was not approved.";
                    if (reason != null && !reason.isBlank()) msg += " Reason: " + reason.trim();
                    notifRepo.sendAsync(submittedBy, "manager_rejected", "Manager Not Approved", msg);
                }
                return Future.succeededFuture(new JsonObject().put("success", true));
            });
    }

    // ── Pending edits ─────────────────────────────────────────────────────────

    // ── Proof challenges ──────────────────────────────────────────────────────

    /**
     * The proof queue: ratings held until somebody vouches for the person who wrote them.
     *
     * <p>Deliberately separate from {@link #getPendingManagers}, because "is this a real person who
     * belongs in the directory?" and "did <em>this author</em> work with them?" are different
     * questions decided on different evidence — and often only one of them exists. When the figure
     * is already in the directory there is no manager decision to make at all, only the held
     * rating.
     */
    public Future<JsonObject> getProofChallenges(String auth0Id, int limit, int offset) {
        return requireAdmin(auth0Id)
            .compose(adminId -> Future.all(
                    challengeRepo.findForAdmin(limit, offset),
                    challengeRepo.countForAdmin())
                .map(cf -> {
                    RowSet<Row> rows = cf.resultAt(0);
                    JsonArray data = new JsonArray();
                    for (Row r : rows) {
                        data.add(new JsonObject()
                            .put("id",              r.getUUID("id").toString())
                            .put("managerId",       r.getLong("manager_id"))
                            .put("managerName",     r.getString("manager_name"))
                            .put("managerCompany",  r.getString("manager_company"))
                            .put("reason",          r.getString("reason"))
                            .put("status",          r.getString("status"))
                            // What we thought of them when they wrote it, not what we think now.
                            .put("authorConfidence", r.getInteger("author_confidence"))
                            .put("workedFrom",      r.getLocalDate("worked_from") == null ? null
                                                    : r.getLocalDate("worked_from").toString())
                            .put("workedUntil",     r.getLocalDate("worked_until") == null ? null
                                                    : r.getLocalDate("worked_until").toString())
                            .put("claimedTitle",    r.getString("claimed_title"))
                            .put("claimedOrg",      r.getString("claimed_org"))
                            .put("relationship",    r.getString("relationship"))
                            .put("evidenceNote",    r.getString("evidence_note"))
                            // False on a submitted claim is the signal worth reading: the dates do
                            // not line up with the career history we already hold for this manager.
                            .put("claimCorroborated", r.getBoolean("claim_corroborated"))
                            .put("createdAt",       r.getOffsetDateTime("created_at").toString()));
                    }
                    return new JsonObject()
                        .put("data",  data)
                        .put("total", cf.<Long>resultAt(1))
                        .put("limit", limit)
                        .put("offset", offset);
                }));
    }

    /**
     * Decides one held rating.
     *
     * <p>Approving publishes it, credits the author and notifies. Rejecting keeps it hidden,
     * debits and notifies. Neither touches the manager row — that is
     * {@link #approvePendingManager}'s decision, and a rating becomes publicly visible only when
     * both have gone its way, which is a conjunction of two independent facts rather than a third
     * state anybody has to maintain.
     */
    public Future<JsonObject> resolveProofChallenge(String auth0Id, UUID challengeId,
                                                    boolean approve, String reason) {
        return requireAdmin(auth0Id)
            .compose(adminId -> challengeRepo.resolve(challengeId, adminId,
                                                      approve ? "approved" : "rejected")
                .compose(opt -> {
                    if (opt.isEmpty()) {
                        return Future.failedFuture(ServiceException.notFound("Challenge not found"));
                    }
                    Row c = opt.get();
                    UUID userId   = c.getUUID("user_id");
                    UUID reviewId = c.getUUID("review_id");

                    /*
                      Through ReviewDisposition: approving publishes the rating, so it starts
                      counting toward the location figures, and rejecting takes it back out.
                      Writing the column here directly is how releasing a held rating used to
                      leave it permanently uncounted.
                    */
                    Future<Void> disposition = reviewId == null
                        ? Future.succeededFuture()
                        : reviewDisposition.set(db, reviewId,
                            approve ? ReviewDisposition.LIVE : ReviewDisposition.REJECTED);

                    Future<Void> score = confidenceRepo.apply(userId,
                        approve ? ConfidenceRepository.CHALLENGE_APPROVED
                                : ConfidenceRepository.CHALLENGE_REJECTED,
                        approve ? 15 : -25,
                        "challenge", challengeId.toString());

                    return disposition.compose(v -> score).compose(v -> {
                        notifRepo.sendAsync(userId,
                            approve ? "proof_approved" : "proof_rejected",
                            approve ? "Rating published" : "Rating not published",
                            approve
                                ? "Thanks — we've confirmed your rating and it's now live."
                                : "We weren't able to confirm your rating, so it hasn't been published."
                                  + (reason != null && !reason.isBlank() ? " Reason: " + reason.trim() : ""),
                            c.getLong("manager_id"));
                        // Recalculate only on approval: a rejected rating never entered the cache.
                        // Awaited, so the admin's next screen reads the figure this decision
                        // produced rather than the one it replaced.
                        Future<Void> recalculated = approve
                            ? managerRepo.recalculate(c.getLong("manager_id"))
                            : Future.succeededFuture();
                        return recalculated.map(recalcDone -> new JsonObject()
                            .put("success", true)
                            .put("status", approve ? "approved" : "rejected"));
                    });
                }));
    }

    // ── Companies awaiting review ─────────────────────────────────────────────

    /**
     * Companies that exist only because somebody rated them.
     *
     * <p>A workplace rating creates its employer when we do not already hold it, so that the one
     * moment a person is willing to contribute is not met with "we have no page for that". Those
     * rows are held at {@code pending_approval} rather than going live, and this is where they
     * wait.
     */
    public Future<JsonObject> getPendingCompanies(String auth0Id, int limit, int offset) {
        return requireAdmin(auth0Id)
            .compose(adminId -> Future.all(
                    companyRepo.findPendingCompaniesForAdmin(limit, offset),
                    companyRepo.countPendingCompanies())
                .map(cf -> {
                    RowSet<Row> rows = cf.resultAt(0);
                    JsonArray data = new JsonArray();
                    for (Row r : rows) {
                        data.add(new JsonObject()
                            .put("id",          r.getLong("id"))
                            .put("name",        r.getString("name"))
                            .put("slug",        r.getString("slug"))
                            .put("domain",      r.getString("domain"))
                            .put("logoUrl",     r.getString("logo_url"))
                            // How much is actually behind it: one rating is a name somebody typed,
                            // several is a company.
                            .put("ratingCount",    r.getLong("rating_count"))
                            .put("managerCount",   r.getLong("manager_count"))
                            .put("interviewCount", r.getLong("interview_count"))
                            .put("createdAt",   r.getOffsetDateTime("created_at").toString()));
                    }
                    return new JsonObject()
                        .put("data", data).put("total", cf.<Long>resultAt(1))
                        .put("limit", limit).put("offset", offset);
                }));
    }

    /**
     * Lets a pending company into the directory, or refuses it.
     *
     * <p>Never touches the ratings themselves. A refused company keeps the rating attached to it -
     * the person did write it - it simply does not surface anywhere, which is the same shape the
     * rest of the product uses for held content.
     */
    public Future<JsonObject> decidePendingCompany(String auth0Id, long companyId, boolean approve) {
        return requireAdmin(auth0Id)
            .compose(adminId -> companyRepo.decidePendingCompany(companyId, approve))
            .compose(changed -> changed
                ? Future.succeededFuture(new JsonObject()
                    .put("success", true).put("status", approve ? "ghost" : "rejected"))
                // Already decided, or never pending. Saying so beats reporting a success that
                // changed nothing.
                : Future.failedFuture(ServiceException.notFound("No pending company with that id")));
    }

    /** The figures list, editable without a deploy because an anti-abuse list churns. */
    public Future<JsonArray> listHighProfileFigures(String auth0Id) {
        return requireAdmin(auth0Id)
            .compose(adminId -> challengeRepo.listFigures())
            .map(rows -> {
                JsonArray out = new JsonArray();
                for (Row r : rows) {
                    out.add(new JsonObject()
                        .put("id",          r.getLong("id"))
                        .put("managerId",   r.getLong("manager_id"))
                        .put("fullName",    r.getString("full_name"))
                        .put("companyId",   r.getLong("company_id"))
                        .put("companyName", r.getString("company_name"))
                        .put("note",        r.getString("note")));
                }
                return out;
            });
    }

    public Future<JsonObject> addHighProfileFigure(String auth0Id, Long managerId,
                                                   String fullName, Long companyId, String note) {
        // A name with no company identifies nobody: it would challenge every unrelated person who
        // happens to share it. The database enforces this too; failing here gives a usable message.
        if (managerId == null && (fullName == null || fullName.isBlank() || companyId == null)) {
            return Future.failedFuture(ServiceException.badRequest(
                "A figure needs either a manager, or both a name and a company"));
        }
        return requireAdmin(auth0Id)
            .compose(adminId -> challengeRepo.addFigure(managerId, fullName, companyId, note))
            .map(r -> new JsonObject().put("success", true).put("id", r.getLong("id")));
    }

    public Future<JsonObject> removeHighProfileFigure(String auth0Id, long id) {
        return requireAdmin(auth0Id)
            .compose(adminId -> challengeRepo.removeFigure(id))
            .compose(removed -> removed
                ? Future.succeededFuture(new JsonObject().put("success", true))
                : Future.failedFuture(ServiceException.notFound("Figure not found")));
    }

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
                                /*
                                    An end date means the role has finished. Which role, though,
                                    decides whether this is a correction or a new past position -
                                    and it always did the same thing, which was the bug.

                                    This branch INSERTED unconditionally. So editing the dates on
                                    the role a manager is already in added a second copy of it
                                    rather than amending the first: the original entry stayed open
                                    and kept driving the page, and every re-attempt added another
                                    row. That is why repeated edits appeared to do nothing.

                                    It also passed null for company, title and logo, and
                                    managerRepo.update builds its SET clause from non-null
                                    arguments - so the headline was never written even when the
                                    company had changed. The header stayed on the old employer
                                    however many times the edit was approved.

                                    Now: the same company and title as the open role means the
                                    admin is correcting that role, so it is updated in place.
                                    Anything else is a genuinely different position and is
                                    inserted. Either way the headline is rebuilt from the career
                                    history afterwards rather than passed along by hand.
                                */
                                return managerRepo.findOpenCareerEntry(managerId).compose(openOpt -> {
                                    boolean correctingCurrentRole = openOpt.isPresent()
                                        && effectiveCo.equalsIgnoreCase(openOpt.get().getString("company"))
                                        && effectiveTit.equalsIgnoreCase(openOpt.get().getString("title"));
                                    Future<?> entry = correctingCurrentRole
                                        ? managerRepo.updateCareerEntry(openOpt.get().getLong("id"), managerId,
                                              effectiveCo, effectiveTit, careerStart, newEndDate, newCompanyId)
                                        : managerRepo.insertCareerEntry(managerId, effectiveCo, effectiveTit,
                                              careerStart, newEndDate, newCompanyId);
                                    return entry.compose(v -> applyEditAndApprove(managerId, editId,
                                        newCompany, newCompanyLogoUrl, newTitle, newStatus, newCountry,
                                        newLinkedinUrl, effectiveCo, effectiveTit, adminId, now,
                                        proposedBy, managerName, newCompanyId));
                                });
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
            /*
              Career history has the last word on the headline.

              The fields above are the ones the form owns outright - country, LinkedIn, and the
              status the admin chose. Company, title and logo are derived, because they are also
              written by the career entries and two writers of one fact is how this page kept
              drifting: approving an edit could leave the header on a company the manager had
              already left.
            */
            .compose(opt -> managerRepo.syncHeadlineFromCareerHistory(managerId))
            .compose(v -> editRepo.approve(editId, adminId, reviewedAt))
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
            ? companyRepo.resolve(companyId, effCompany, null, null)
                // An admin correcting the capitalisation is correcting it for the company
                // too, not just this manager's copy of the text.
                .compose(row -> companyRepo.recaseName(row.getLong("id"), effCompany)
                    .map(ignored -> row.getLong("id")))
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
                // Keeps anything a person wrote: seeded placeholders are removed, real reviews
                // are soft-deleted, and the manager row is retired rather than deleted when it
                // holds any - because the cascade on reviews.manager_id would destroy them.
                return managerRepo.deleteOrRetire(managerId)
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
                // Neither side may already have been merged away. A merge retires the row it
                // absorbs rather than deleting it, so without this a retired manager stayed a
                // valid target - and merging into one took the survivor out of the directory too.
                return Future.all(managerRepo.isMergeable(keepId), managerRepo.isMergeable(mergeId))
                    .compose(ok -> {
                        if (!Boolean.TRUE.equals(ok.resultAt(0)))
                            return Future.failedFuture(ServiceException.conflict(
                                "The manager you are keeping has already been merged into another profile."));
                        if (!Boolean.TRUE.equals(ok.resultAt(1)))
                            return Future.failedFuture(ServiceException.conflict(
                                "That duplicate has already been merged."));
                        return Future.succeededFuture();
                    })
                    .compose(v -> doMerge(keepId, mergeId));
            })
            .compose(counts -> managerRepo.mergeInlineRecalculate(keepId).map(v -> counts))
            /*
              The duplicate held the plain name slug, or forced the survivor onto a
              collision-breaker; either way the merge is what frees it. Nothing recomputed slugs
              before, so survivors kept "<name>-<company>" for ever. Conservative and guarded -
              see ManagerRepository.reclaimBaseSlug - and the old URL keeps resolving.
            */
            .compose(counts -> managerRepo.reclaimBaseSlug(keepId).map(moved -> counts))
            .compose(counts -> {
                JsonObject ok = new JsonObject().put("success", true).put("keepId", keepId)
                    // Said out loud. A review that could not come across was set aside, not
                    // deleted, and an admin has to be told which happened.
                    .put("movedReviews",  counts.getInteger("moved"))
                    .put("parkedReviews", counts.getInteger("parked"));
                // Only now, with the merge committed. Marking it earlier would hide a suggestion
                // whose merge then refused, and the duplicate would never be offered again.
                Future<Void> resolved = mergeSuggestionsRepo == null
                    ? Future.succeededFuture()
                    : mergeSuggestionsRepo.markPairMerged(keepId, mergeId);
                return resolved.compose(v -> companyRepo == null
                    ? Future.succeededFuture(ok)
                    : companyRepo.syncStatsForManager(keepId).map(statsDone -> ok));
            });
    }

    /** Moves the reviews and retires the duplicate in one transaction, or refuses and leaves both
     *  untouched. It will not delete a review somebody wrote. */
    private Future<JsonObject> doMerge(long keepId, long mergeId) {
        return managerRepo.mergeInto(keepId, mergeId)
            .recover(err -> Future.failedFuture(err instanceof IllegalStateException
                ? ServiceException.conflict(err.getMessage())
                : err));
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
                // Pin the logo BEFORE the name changes. Without its own logo_url a company is drawn
                // from a logo.dev URL guessed from its name, so renaming re-guesses a domain that
                // usually does not exist and the card drops to a grey letter.
                return companyRepo.pinCurrentLogo(companyId)
                    .compose(v -> companyRepo.renameCompany(companyId, newName));
            })
            .compose(v -> companyRepo.updateCompanyStatsForCompany(companyId))
            .map(v -> new JsonObject().put("success", true));
    }

    /**
     * Reverses a completed merge.
     *
     * Restores exactly the rows the merge moved, from its manifest. Anything added to the surviving
     * company since the merge stays there, because it was never on the list.
     */
    public Future<JsonObject> undoCompanyMerge(String auth0Id, UUID mergeRecordId) {
        return requireAdmin(auth0Id)
            .compose(adminId -> companyRepo.undoMerge(mergeRecordId)
                .recover(err -> Future.failedFuture(ServiceException.badRequest(err.getMessage()))))
            // Both companies' cached figures are wrong until they are recomputed: one has just
            // lost data and the other has just got it back.
            .compose(result -> companyRepo.syncStatsForCompany(result.getLong("restoredCompanyId"))
                .compose(v -> companyRepo.syncStatsForCompany(result.getLong("targetCompanyId")))
                .map(v -> result));
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
     * Records a role that has no career_history row yet.
     *
     * <p>Only update and delete existed. A card on the trajectory can come from three places - a
     * career_history row, reviews grouped into a segment, or the manager record itself - and only
     * the first could be edited. Editing either of the others opened the manager panel, which has
     * no date fields, so the role's dates could never be written <em>anywhere</em>.
     *
     * <p>That is why a manager who had plainly left still showed "Present": with no row to carry
     * an end date, the trajectory fell back to the reviewer's own {@code worked_until}, and no
     * amount of editing could change it. This is the missing half of the pair.
     */
    public Future<JsonObject> adminCreateCareerEntry(String auth0Id, long managerId,
            String company, String title, String startDateStr, String endDateStr,
            Long pickedCompanyId, String pickedLogoUrl) {
        return requireAdmin(auth0Id)
            .compose(adminId -> {
                if (company == null || company.isBlank()) return Future.failedFuture(ServiceException.badRequest("company required"));
                if (title   == null || title.isBlank())   return Future.failedFuture(ServiceException.badRequest("title required"));
                if (startDateStr == null || startDateStr.isBlank()) return Future.failedFuture(ServiceException.badRequest("startDate required"));
                OffsetDateTime start;
                OffsetDateTime end = null;
                try {
                    start = OffsetDateTime.parse(startDateStr.length() == 4
                        ? startDateStr + "-01-01T00:00:00Z" : startDateStr + "-01T00:00:00Z");
                    if (endDateStr != null && !endDateStr.isBlank()) {
                        end = OffsetDateTime.parse(endDateStr.length() == 4
                            ? endDateStr + "-01-01T00:00:00Z" : endDateStr + "-01T00:00:00Z");
                    }
                } catch (Exception e) {
                    return Future.failedFuture(ServiceException.badRequest("Invalid date format"));
                }
                OffsetDateTime finalStart = start, finalEnd = end;
                // Resolve the company so the entry carries an id, not just text - the same reason
                // the update path does it.
                /*
                    The company the admin PICKED, not a name re-resolved from text.

                    Re-resolving by name is what created a second company and left the manager on
                    the wrong logo: two companies can share a name, and a name typed slightly
                    differently makes a new one. The picker already established identity, so the id
                    rides with the request and the name is only display text - the same rule the
                    manager editor and the edit-request flow already follow.
                */
                Future<Long> companyIdFuture = pickedCompanyId != null
                    ? Future.succeededFuture(pickedCompanyId)
                    : (companyRepo != null
                        ? companyRepo.resolve(null, company.trim(), null, pickedLogoUrl)
                            .map(row -> row.getLong("id"))
                            .otherwise((Long) null)
                        : Future.succeededFuture(null));
                return companyIdFuture.compose(companyId ->
                    managerRepo.insertCareerEntry(managerId, company.trim(), title.trim(),
                                                  finalStart, finalEnd, companyId)
                        // Career history owns the headline; a new role may well be the current one.
                        .compose(v -> managerRepo.syncHeadlineFromCareerHistory(managerId)));
            })
            .map(v -> new JsonObject().put("success", true).put("created", 1));
    }

    /**
     * Without a picked company, as callers that have only text still use.
     *
     * <p>Kept so the identity-carrying form is an addition rather than a migration: a caller with
     * nothing to pass sends nothing, and the name is resolved as before.
     */
    public Future<JsonObject> adminUpdateCareerEntry(String auth0Id, long managerId, long entryId,
            String company, String title, String startDateStr, String endDateStr) {
        return adminUpdateCareerEntry(auth0Id, managerId, entryId, company, title,
                                      startDateStr, endDateStr, null, null);
    }

    /** Without a picked company - see the update overload above. */
    public Future<JsonObject> adminCreateCareerEntry(String auth0Id, long managerId,
            String company, String title, String startDateStr, String endDateStr) {
        return adminCreateCareerEntry(auth0Id, managerId, company, title,
                                      startDateStr, endDateStr, null, null);
    }

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
                            .put("slug",    row.getString("slug_a"))
                            .put("reviews", row.getInteger("reviews_a")))
                        .put("managerB", new JsonObject()
                            .put("id",      row.getLong("id_b"))
                            .put("name",    row.getString("name_b"))
                            .put("company", row.getString("company_b"))
                            .put("title",   row.getString("title_b"))
                            .put("country", row.getString("country_b"))
                            .put("slug",    row.getString("slug_b"))
                            .put("reviews", row.getInteger("reviews_b")))
                        /*
                            What the surviving manager's URL will be after the merge.

                            The merge reclaims the plain name slug for whoever survives - the
                            retired duplicate is parked out of the way first - so the outcome is
                            decided before an admin clicks, and it was decided invisibly. Showing
                            it is the difference between choosing a merge and discovering one.

                            Both rows are the same person by definition here, so the clean slug is
                            the same whichever direction the merge runs; only the row that ends up
                            on it differs.
                        */
                        .put("resultingSlug", managerRepo.cleanSlugFor(row.getString("name_a")))
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
            String company, String title, String startDateStr, String endDateStr,
            Long pickedCompanyId, String pickedLogoUrl) {
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
                // Resolve the company to an id so the entry's FK moves with its name. Without this
                // an admin correcting the company on a career entry changes only the text, and the
                // manager keeps appearing under the old company - which is decided by the id.
                OffsetDateTime finalStart = start, finalEnd = end;
                /*
                    The company the admin PICKED, not a name re-resolved from text.

                    Re-resolving by name is what created a second company and left the manager on
                    the wrong logo: two companies can share a name, and a name typed slightly
                    differently makes a new one. The picker already established identity, so the id
                    rides with the request and the name is only display text - the same rule the
                    manager editor and the edit-request flow already follow.
                */
                Future<Long> companyIdFuture = pickedCompanyId != null
                    ? Future.succeededFuture(pickedCompanyId)
                    : (companyRepo != null
                        ? companyRepo.resolve(null, company.trim(), null, pickedLogoUrl)
                            .map(row -> row.getLong("id"))
                            .otherwise((Long) null)
                        : Future.succeededFuture(null));
                return companyIdFuture.compose(companyId ->
                    managerRepo.updateCareerEntry(entryId, managerId, company.trim(), title.trim(),
                                                  finalStart, finalEnd, companyId)
                        /*
                          The headline follows the roles. This wrote career_history and stopped, so
                          the Edit button on the career trajectory could move a manager's current
                          role while the header above it went on naming the old company, logo and
                          title - visibly wrong, and no amount of re-editing fixed it.
                        */
                        .compose(count -> managerRepo.syncHeadlineFromCareerHistory(managerId)
                            .map(v -> count)));
            })
            .map(count -> new JsonObject().put("success", true).put("updated", count));
    }

    public Future<JsonObject> adminDeleteCareerEntry(String auth0Id, long managerId, long entryId) {
        return requireAdmin(auth0Id)
            .compose(adminId -> managerRepo.deleteCareerEntry(entryId, managerId))
            // Same reason as the update above: removing the current role must not leave the header
            // advertising it.
            .compose(count -> managerRepo.syncHeadlineFromCareerHistory(managerId).map(v -> count))
            .map(count -> new JsonObject().put("success", true).put("deleted", count));
    }


    // ── Captured drafts ───────────────────────────────────────────────────────

    /**
     * Contribution forms somebody filled in but never submitted.
     *
     * <p>Only the forms with no domain row to create appear here. The manager forms capture into
     * real {@code pending_approval} rows, which already have their own queue — listing them twice
     * would mean reviewing the same submission in two places.
     */
    public Future<JsonObject> getCapturedDrafts(String auth0Id, int limit, int offset) {
        return requireAdmin(auth0Id)
            .compose(adminId -> Future.all(capturedDrafts.findUnreviewed(limit, offset),
                                           capturedDrafts.countUnreviewed()))
            .map(cf -> {
                JsonArray out = new JsonArray();
                for (Row row : cf.<RowSet<Row>>resultAt(0)) {
                    out.add(new JsonObject()
                        .put("id",          row.getLong("id"))
                        .put("kind",        row.getString("kind"))
                        .put("payload",     row.getJsonObject("payload"))
                        .put("companyId",   row.getLong("company_id"))
                        .put("companyName", row.getString("company_name"))
                        .put("companySlug", row.getString("company_slug"))
                        .put("managerId",   row.getLong("manager_id"))
                        .put("managerName", row.getString("manager_name"))
                        .put("author",      row.getString("author_username"))
                        .put("createdAt",   row.getOffsetDateTime("created_at").toString()));
                }
                return new JsonObject()
                    .put("data", out)
                    .put("total", cf.resultAt(1))
                    .put("limit", limit).put("offset", offset);
            });
    }

    /** Marks one draft dealt with. Kept rather than deleted, so the queue can be audited. */
    public Future<JsonObject> markDraftReviewed(String auth0Id, long draftId) {
        return requireAdmin(auth0Id)
            .compose(adminId -> capturedDrafts.markReviewed(draftId))
            .map(done -> new JsonObject().put("success", done));
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

    /**
     * Whether automatic manager creation is currently paused, and the numbers behind it.
     *
     * <p>Exists because a circuit breaker nobody can see is indistinguishable from the feature
     * quietly not working. When the ceiling holds, anonymous searches fall back to the pending
     * queue and no user is shown an error - so the admin panel is the only place this surfaces.
     *
     * <p>The counts double as a way to learn the real baseline: the defaults were chosen against
     * total daily volume from all sources, not against the anonymous-ghost slice specifically.
     */
    public Future<JsonObject> ghostCreationStatus(String auth0Id) {
        return requireAdmin(auth0Id)
            .compose(adminId -> ghostSlots == null
                ? Future.succeededFuture(
                    new org.werkpages.repository.AnonymousGhostSlotRepository.SiteWideRate(0, 0))
                : ghostSlots.siteWideRates())
            .map(rate -> new JsonObject()
                .put("lastHour",   rate.lastHour())
                .put("lastDay",    rate.lastDay())
                .put("maxPerHour", org.werkpages.repository.AnonymousGhostSlotRepository.MAX_PER_HOUR_SITE_WIDE)
                .put("maxPerDay",  org.werkpages.repository.AnonymousGhostSlotRepository.MAX_PER_DAY_SITE_WIDE)
                .put("paused",     !rate.withinCeiling())
                .put("trippedBy",  rate.trippedBy()));
    }
}
