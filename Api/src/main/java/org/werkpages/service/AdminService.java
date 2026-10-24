package org.werkpages.service;

import io.vertx.core.Future;
import org.werkpages.repository.ManagerSearchRequestRepository;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import org.werkpages.repository.CapturedDraftRepository;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.PickedBrand;
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
    /**
     * The two services that own the tables a captured draft can become.
     *
     * <p>Publishing one writes a real rating, and the rules for a real rating live with the form
     * that normally produces it. Reimplementing them here is how an admin path ends up accepting
     * something the public path would refuse, which for these tables means public data nothing can
     * repair. Guarded like the repositories below: several constructors are used by unit tests
     * with no client at all.
     */
    private final CompanyReviewService companyRatings;
    private final InterviewService     interviewReviews;

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
        boolean canPublish = db != null && companyRepo != null && userRepo != null;
        this.companyRatings = !canPublish ? null : new CompanyReviewService(
            new org.werkpages.repository.CompanyReviewRepository(db), companyRepo, userRepo);
        this.interviewReviews = !canPublish ? null : new InterviewService(
            new org.werkpages.repository.InterviewRepository(db), companyRepo, userRepo);
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
                /*
                  No stats write from here. approveGhost changes managers.approval_status and the managers trigger fires on it, so company_stats_live follows inside that same statement (V89).
                */
                return Future.succeededFuture(ok);
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
                        /*
                          Where the manager works.

                          An admin reviewing a submission could not see the location and therefore
                          could not correct one before approving it into the public directory. The
                          whole location travels, not just the country, because the edit form sets
                          the whole thing.

                          Unlike the public projections this is NOT reduced for an unconfirmed
                          location: an admin deciding whether to publish a row needs to see what
                          the row actually holds, including a city inferred from a searcher's IP
                          that they may well want to clear.
                        */
                        .put("country", row.getString("country"))
                        .put("state", row.getString("state"))
                        .put("city", row.getString("city"))
                        .put("companyLocationId", row.getLong("company_location_id"))
                        .put("locationName", row.getString("location_name"))
                        .put("locationStreet", row.getString("location_street"))
                        .put("locationSource", row.getString("location_source"))
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
                /*
                  A real profile has gone live, so one seeded stand-in can retire.

                  This only happened when somebody SUBMITTED through the add form - a pending row
                  that might still be rejected - and nowhere at all when a manager actually became
                  live. So "Fake Profiles Left" sat unchanged however many real managers arrived
                  by approval or by search, which is the count an admin is watching to know when
                  the seeded set can be retired.

                  Retiring on the transition to LIVE is also the correct trade: a fake profile is
                  replaced by a real one, not by a submission that may never publish.
                */
                managerRepo.deleteFakeManagerInBackground();

                Future<Void> recalculated = reslugged.compose(v -> managerRepo.recalculate(managerId));
                JsonObject ok = new JsonObject()
                    .put("success", true)
                    .put("message", "Manager approved")
                    .put("_managerId", managerId)
                    .put("_needsLogo", existingLogo == null)
                    .put("_company", company);
                /*
                  The recalculation is still awaited, and that is now the whole of it: it writes
                  managers.reviews_count and managers.overall_rating, and the managers trigger
                  fires on it, so company_stats_live follows inside that same statement (V89).
                */
                return recalculated.map(recalcDone -> ok);
            });
    }

    /** Why a moderator took a pending submission down. Only the first costs the submitter. */
    public static final java.util.Set<String> MANAGER_REJECT_CATEGORIES =
        java.util.Set.of("junk", "duplicate", "correction", "other");

    /**
     * Rejects without saying why, and therefore without penalising anybody.
     *
     * <p>Kept so that a caller predating the category cannot be turned into a 400 by a deploy.
     * Prefer {@link #rejectPendingManager(String, long, String, String)}: a moderator who has
     * decided to reject something has also decided whether it was the submitter's fault, and
     * this overload throws that answer away.
     */
    public Future<JsonObject> rejectPendingManager(String auth0Id, long managerId, String reason) {
        return rejectPendingManager(auth0Id, managerId, reason, null);
    }

    /**
     * A moderator rejects a pending manager, and says whether it was the submitter's fault.
     *
     * <p>This used to debit {@value ConfidenceRepository#MANAGER_REJECTED_JUNK_DELTA} on every
     * rejection. The {@code reason} it already took was free text for the notification email and
     * was never consulted, so a submission taken down as a duplicate of one already in the
     * directory cost its submitter exactly as much as typing gibberish into the add form. Since
     * duplicates come from the people who contribute most, the accounts quietly pushed under
     * {@link ConfidenceRepository#WATCH_BELOW} were disproportionately the good ones.
     *
     * <p>{@code category} decides the consequence, exactly as the reason does for
     * {@link #adminDeleteReview}: only {@code junk} debits anybody. {@code reason} keeps its old
     * job of explaining the decision to the submitter, because the two answer different
     * questions - one is for them to read, one is for us to act on.
     *
     * <p>An absent category means no penalty rather than a default one. The frontend always sends
     * it, so absent means a caller that predates this parameter, and the worst a lagging deploy
     * can then do is under-punish. A confidence score is invisible to the person carrying it and
     * cannot be appealed by them, which makes a debit nobody deliberately chose indefensible.
     *
     * @param reason   free text shown to the submitter; may be null
     * @param category one of {@link #MANAGER_REJECT_CATEGORIES}, or null for no penalty
     */
    public Future<JsonObject> rejectPendingManager(String auth0Id, long managerId, String reason,
                                                   String category) {
        String cleaned = category == null ? "" : category.trim().toLowerCase();
        /*
          Absent is allowed; wrong is not. "junkk" is a bug or somebody probing, and quietly
          treating it as no-penalty would hide both behind a successful-looking rejection.
        */
        if (!cleaned.isEmpty() && !MANAGER_REJECT_CATEGORIES.contains(cleaned)) {
            return Future.failedFuture(ServiceException.badRequest(
                "Unrecognised rejection category: junk, duplicate, correction or other"));
        }
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

                /*
                  A ghost manager created by somebody's search on /find is not a submission: they
                  typed a name into a search box. It is silent - no notification - so a score they
                  cannot see, appeal, or even know exists must never move on it. If we would not
                  tell you about it, it cannot count against you.
                */
                boolean theirSubmission = submittedBy != null && !isSearchCreated;
                boolean penalise = theirSubmission
                                && "junk".equals(cleaned)
                                && confidenceRepo != null;

                if (penalise) {
                    confidenceRepo.apply(submittedBy, ConfidenceRepository.MANAGER_REJECTED_JUNK,
                            ConfidenceRepository.MANAGER_REJECTED_JUNK_DELTA,
                            "manager", String.valueOf(managerId))
                        .onFailure(err -> System.err.println(
                            "Confidence debit failed for manager " + managerId + ": " + err.getMessage()));
                }

                /*
                  Notified for every category, not only the penalised one. Somebody whose
                  submission duplicated an existing manager still needs to know it will not
                  appear, and telling them only when we are also penalising them would make the
                  notification itself an accusation.
                */
                if (theirSubmission) {
                    String msg = "Your submitted manager profile for " + managerName + " at " + managerCompany + " was not approved.";
                    if (reason != null && !reason.isBlank()) msg += " Reason: " + reason.trim();
                    notifRepo.sendAsync(submittedBy, "manager_rejected", "Manager Not Approved", msg);
                }

                return Future.succeededFuture(new JsonObject()
                    .put("success", true)
                    .put("category", cleaned.isEmpty() ? null : cleaned)
                    // Told rather than inferred: the admin panel cannot work this out from the
                    // category alone, because junk on a search-created row penalises nobody.
                    .put("confidencePenalty", penalise));
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
                        .put("newState", row.getString("new_declared_state"))
                        .put("newCity", row.getString("new_declared_city"))
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
                    // The location the editor actually gave. Approving used to apply the country
                    // and drop the rest, so an approved "Kitchener, Ontario" kept the old city.
                    String newState         = row.getString("new_declared_state");
                    String newCity          = row.getString("new_declared_city");
                    Long   newCompanyLocationId = row.getLong("new_company_location_id");
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
                                        newState, newCity, newCompanyLocationId,
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
                                      .compose(v -> applyEditAndApprove(managerId, editId, null, null, null, newStatus, newCountry, newState, newCity, newCompanyLocationId, newLinkedinUrl, effectiveCo, effectiveTit, adminId, now, proposedBy, managerName, null));
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
                                .compose(v -> applyEditAndApprove(managerId, editId, newCompany, newCompanyLogoUrl, newTitle, newStatus, newCountry, newState, newCity, newCompanyLocationId, newLinkedinUrl, effectiveCo, effectiveTit, adminId, now, proposedBy, managerName, newCompanyId))
                                .compose(result -> {
                                    if (newCompany != null) {
                                        /*
                                          No stats write for the old company. Moving a manager
                                          changes managers.company_id, and the trigger recomputes
                                          BOTH sides of that move (V47, kept by V89). This was
                                          also the last fire-and-forget stats write.
                                        */
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
                                                     String newState, String newCity, Long newCompanyLocationId,
                                                     String newLinkedinUrl, String effectiveCo, String effectiveTit,
                                                     UUID adminId, OffsetDateTime reviewedAt,
                                                     UUID proposedBy, String managerName, Long newCompanyId) {
        return managerRepo.update(managerId, newCompany, newTitle, null, null, newStatus, newCountry,
                                  newState, newCity, newCompanyLocationId, newLinkedinUrl, null, newCompanyId)
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
                JsonObject result = new JsonObject().put("success", true).put("message", "Edit approved and applied")
                    .put("managerId", managerId);
                if (newCompany != null) {
                    result.put("newCompany", newCompany);
                    if (newCompanyLogoUrl != null) result.put("newCompanyLogoUrl", newCompanyLogoUrl);
                }
                // Applying an edit writes the manager row, and the managers trigger fires on it, so company_stats_live follows inside that same statement (V89).
                return Future.succeededFuture(result);
            });
    }

    /**
     * Applies an admin's logo to the manager snapshot AND, where it is real identity, to the
     * company itself.
     *
     * <h2>Why the company row too</h2>
     *
     * <p>This wrote {@code managers.company_logo_url} only - the FALLBACK. Every profile reads
     * the company row as the authority, so an admin picked the right mark, saved successfully, and
     * the page went on showing the old one. Re-editing did nothing, because the pick had nowhere
     * to land. That is the same defect {@code resolveCompanyForCareerEntry} was fixed for, on a
     * path that never got the fix.
     *
     * <h2>Why not always</h2>
     *
     * <p>The url here is sometimes a GUESS built from the company name, and writing a guess with
     * ADMIN authority would stamp it as human-confirmed - permanently outranking the resolvers
     * that would otherwise have corrected it. So the company is only updated when the url
     * actually encodes a domain, which is true exactly when it came from a real pick.
     */
    public Future<Void> updateManagerLogo(long managerId, String logoUrl) {
        // updateLogoUrl writes managers.company_logo_url, and the managers trigger fires on it, so company_stats_live follows inside that same statement (V89).
        Future<Void> snapshot = managerRepo.updateLogoUrl(managerId, logoUrl).mapEmpty();
        String domain = org.werkpages.repository.PickedBrand.embeddedDomain(
            logoUrl == null ? "" : logoUrl);
        if (domain == null || companyRepo == null) return snapshot;
        var picked = org.werkpages.repository.PickedBrand.of(domain, logoUrl);
        if (picked.isEmpty()) return snapshot;
        return snapshot.compose(v -> managerRepo.findById(managerId)
            .compose(opt -> {
                Long companyId = opt.map(r -> r.getLong("company_id")).orElse(null);
                if (companyId == null) return Future.<Void>succeededFuture();
                return companyRepo.applyPickedBrand(companyId, picked.get(),
                        org.werkpages.repository.CompanyRepository.BrandAuthority.ADMIN)
                    .<Void>mapEmpty();
            })
            // The manager snapshot is already written; a company update that fails must not undo
            // the admin's action or fail their request.
            .otherwise((Void) null));
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
    /** Without location - the long-standing form. */
    public Future<JsonObject> adminEditManager(String auth0Id, long managerId, String name,
            String title, String company, String linkedinUrl, Long companyId) {
        return adminEditManager(auth0Id, managerId, name, title, company, linkedinUrl, companyId,
                                null, null, null, null, null);
    }

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
                                               Long companyId,
                                              String newCountry, String newState, String newCity,
                                              String newCompanyLogoUrl,
                                              Long newCompanyLocationId) {
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
            .compose(newCompanyId -> managerRepo.adminEdit(managerId, effName, effTitle, effCompany,
                                                           effLinkedinUrl, newCompanyId,
                                                           newCountry, newState, newCity,
                                                           newCompanyLogoUrl, newCompanyLocationId))
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
                // A DELETE, or an approval_status change; the trigger fires on both (V89).
                return managerRepo.deleteOrRetire(managerId).mapEmpty();
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
                // The merge moved managers.company_id and reviews, both triggered (V89).
                return resolved.map(v -> ok);
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
        return adminRenameCompany(auth0Id, companyId, newName, null, null);
    }

    /**
     * Renames a company and, when the admin supplied one, records their choice of brand.
     *
     * <p><b>Why an admin can set a logo at all.</b> logo.dev answers a correctly resolved domain
     * with the OWNER's mark: knotts.com returns Six Flags, because Knott's Berry Farm's owner
     * Cedar Fair merged into it, and knottsberryfarmfoods.com returns Smucker's. No domain we
     * resolve produces the right image, so resolution cannot fix it and a person has to decide.
     * {@code domain_source = 'ADMIN'} already exists for exactly that, and every automated writer
     * in CompanyRepository refuses to overwrite it.
     *
     * <p><b>Why the client validation does not apply here.</b> {@link PickedBrand#of} requires a
     * logo URL to structurally encode its own domain, because it is given untrusted input from a
     * browser. That rule would defeat this: correcting Knott's means keeping knotts.com and
     * choosing a different image. An authenticated admin is the authority the ladder defers to,
     * so the record is built directly, with the domain still shape-checked.
     */
    public Future<JsonObject> adminRenameCompany(String auth0Id, long companyId, String newName,
                                                 String domain, String logoUrl) {
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
            .compose(v -> applyAdminBrand(companyId, domain, logoUrl))
            /*
              No stats write: a rename touches companies.name, and nothing in company_stats_live
              is derived from the name.
            */
            .map(v -> new JsonObject().put("success", true));
    }

    /**
     * The admin's chosen brand, or nothing at all when they supplied none.
     *
     * <p>Absent means leave it: a rename must never clear a logo as a side effect of touching the
     * name. A blank domain is rejected rather than treated as a clear, because clearing a brand
     * and correcting one are different intentions and only one of them was asked for here.
     */
    private Future<Void> applyAdminBrand(long companyId, String domain, String logoUrl) {
        if (domain == null || domain.isBlank()) return Future.succeededFuture();
        String host = domain.trim().toLowerCase();
        if (!host.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")) {
            return Future.failedFuture(ServiceException.badRequest(
                "\"" + domain + "\" is not a domain"));
        }
        String url = (logoUrl == null || logoUrl.isBlank()) ? null : logoUrl.trim();
        if (url != null && !url.startsWith("https://")) {
            return Future.failedFuture(ServiceException.badRequest("A logo URL must be https"));
        }
        return companyRepo.applyPickedBrand(companyId, new PickedBrand(host, url),
                                            CompanyRepository.BrandAuthority.ADMIN)
            .mapEmpty();
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
            /*
              Both companies are recomputed by the undo itself: it moves managers, reviews and
              interview rows back, and every one of those columns is triggered (V89).
            */
            ;
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
                Future<Long> companyIdFuture = resolveCompanyForCareerEntry(
                    pickedCompanyId, company, pickedLogoUrl);
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
     * The moderation queue behind the admin Ratings tab.
     *
     * <p>Exists because there was nowhere to act. Deleting a junk rating meant knowing which
     * manager it was on and navigating to their profile - so the only ratings a moderator could
     * find were the ones they already knew about, which is the wrong way round.
     *
     * <p>{@code firstEverRating} is computed here rather than left to the client: it is the single
     * strongest signal for the abuse this was built for - an account whose first and only act is a
     * rating that unlocks the site.
     */
    public Future<JsonObject> getRecentReviews(String auth0Id, int limit, int offset) {
        int cappedLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset  = Math.max(0, offset);
        return requireAdmin(auth0Id)
            .compose(adminId -> io.vertx.core.Future.all(
                reviewRepo.listRecentForModeration(cappedLimit, safeOffset),
                reviewRepo.countForModeration()))
            .map(cf -> {
                io.vertx.sqlclient.RowSet<Row> rows = cf.resultAt(0);
                long total = cf.resultAt(1);
                JsonArray data = new JsonArray();
                for (Row row : rows) {
                    Long totalByAuthor = row.getLong("author_total_ratings");
                    data.add(new JsonObject()
                        .put("id",              row.getUUID("id").toString())
                        .put("managerId",       row.getLong("manager_id"))
                        .put("managerName",     row.getString("manager_name"))
                        .put("managerSlug",     row.getString("manager_slug"))
                        .put("companySlug",     row.getString("company_slug"))
                        .put("managerCompany",  row.getString("manager_company"))
                        .put("managerTitle",    row.getString("manager_title"))
                        .put("author",          row.getString("author"))
                        .put("overallRating",   row.getBigDecimal("overall_rating"))
                        .put("createdAt",       row.getOffsetDateTime("created_at").toString())
                        .put("disposition",     row.getString("disposition"))
                        .put("gateEligible",    row.getBoolean("gate_eligible"))
                        // Standing when it was written, and standing now - they diverge once a
                        // moderator has acted, and the difference is the audit.
                        .put("authorConfidenceAtSubmission", row.getInteger("author_confidence"))
                        .put("authorConfidenceNow",          row.getInteger("author_confidence_now"))
                        .put("authorTotalRatings",           totalByAuthor)
                        .put("firstEverRating",              totalByAuthor != null && totalByAuthor <= 1));
                }
                return new JsonObject()
                    .put("data",   data)
                    .put("total",  total)
                    .put("limit",  cappedLimit)
                    .put("offset", safeOffset);
            });
    }

    /** Why a moderator removed a rating. Only the first costs the author anything. */
    public static final java.util.Set<String> REVIEW_DELETE_REASONS =
        java.util.Set.of("junk", "duplicate", "correction", "other");

    /**
     * A moderator removes a rating, and says why.
     *
     * <p>The reason is required and not a free-text note, because it decides something: only
     * {@code junk} debits the author's confidence. A duplicate, or an administrative correction,
     * is not their fault and must cost them nothing. A bare "delete + penalise?" checkbox would
     * have recorded the decision nowhere, and an unexplained debit six months on is indefensible
     * to the person carrying it.
     *
     * <p>Soft delete, never destroy - the standing rule for this data. The rating stops counting
     * immediately: the manager's cached rating and the company projection are both recomputed
     * before this resolves, so the average corrects itself the moment the decision is made rather
     * than at the next sweep.
     *
     * <p>The debit is keyed on the review id, so a double-clicked delete cannot charge twice -
     * and the row-count guard means the second call finds nothing to delete and never reaches
     * the debit at all.
     */
    public Future<JsonObject> adminDeleteReview(String auth0Id, UUID reviewId, String reason) {
        String cleaned = reason == null ? "" : reason.trim().toLowerCase();
        if (!REVIEW_DELETE_REASONS.contains(cleaned)) {
            return Future.failedFuture(ServiceException.badRequest(
                "A deletion reason is required: junk, duplicate, correction or other"));
        }
        return requireAdmin(auth0Id).compose(adminUserId ->
            reviewRepo.findAuthorAndManager(reviewId).compose(found -> {
                if (found.isEmpty()) {
                    return Future.failedFuture(ServiceException.notFound("Rating not found"));
                }
                UUID authorId  = found.get().getUUID("user_id");
                long managerId = found.get().getLong("manager_id");

                return reviewRepo.adminDelete(reviewId, cleaned, adminUserId).compose(deleted -> {
                    if (!deleted) {
                        /*
                          Two moderators clicking at once. Sequentially the second call never
                          reaches here - the lookup above already filters deleted rows and 404s -
                          so this is the concurrent race only, and emphatically not a second
                          debit for one piece of junk.
                        */
                        return Future.succeededFuture(new JsonObject()
                            .put("success", true).put("alreadyDeleted", true));
                    }

                    /*
                      Junk only, and only when we know whose it was. An anonymous rating has
                      nobody to debit - and a score the person cannot see or appeal must never
                      move on a guess about who wrote it.
                    */
                    if ("junk".equals(cleaned) && authorId != null && confidenceRepo != null) {
                        confidenceRepo.apply(authorId, ConfidenceRepository.REVIEW_DELETED_JUNK,
                                ConfidenceRepository.REVIEW_DELETED_JUNK_DELTA,
                                "review", reviewId.toString())
                            .onFailure(err -> System.err.println(
                                "Confidence debit failed for review " + reviewId + ": " + err.getMessage()));
                    }

                    // recalculate writes reviews_count and overall_rating; the managers trigger fires on it, so company_stats_live follows inside that same statement (V89).
                    return managerRepo.recalculate(managerId)
                        .map(v -> new JsonObject()
                            .put("success", true)
                            .put("reason", cleaned)
                            .put("confidencePenalty", "junk".equals(cleaned) && authorId != null));
                });
            }));
    }

    /**
     * The company a career entry belongs to, and the logo the admin chose for it.
     *
     * <p>Identity comes from the picked id - re-resolving by name is what created a second
     * company and left the manager on the wrong logo, since two companies can share a name and a
     * name typed slightly differently makes a new one.
     *
     * <p><b>The picked LOGO used to be dropped on exactly that branch.</b> It was passed only to
     * {@code resolve}, which is reached only when nothing was picked - and {@code resolve} with a
     * known id merely SELECTs, while {@code findOrCreate} writes {@code logo_url} on INSERT only.
     * So no path in the system could ever change an existing company's logo: the admin picked the
     * right mark, saved, and every surface went on showing the old one. Re-editing did nothing,
     * because the pick had nowhere to land.
     *
     * <p>The company row is the single source of truth for a company's logo, so this writes it
     * there rather than copying it onto the career entry. It is a company's logo, not one
     * manager's, and a second copy would be a second thing to disagree.
     *
     * <p>Both career-entry paths use this, so create and update cannot drift apart again.
     */
    private Future<Long> resolveCompanyForCareerEntry(Long pickedCompanyId, String company,
                                                      String pickedLogoUrl) {
        if (pickedCompanyId != null) {
            if (companyRepo == null) return Future.succeededFuture(pickedCompanyId);
            return companyRepo.updateLogoUrl(pickedCompanyId, pickedLogoUrl)
                .map(v -> pickedCompanyId)
                // A logo is cosmetic; the entry still belongs on the company that was picked.
                .otherwise(pickedCompanyId);
        }
        if (companyRepo == null) return Future.succeededFuture(null);
        return companyRepo.resolve(null, company.trim(), null, pickedLogoUrl)
            .map(row -> row.getLong("id"))
            .otherwise((Long) null);
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
            // mergeCompanies moves managers, company_reviews and interview_reviews; all triggered (V89).
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
                Future<Long> companyIdFuture = resolveCompanyForCareerEntry(
                    pickedCompanyId, company, pickedLogoUrl);
                return companyIdFuture.compose(companyId ->
                    /*
                      THE RATINGS FOLLOW THE CORRECTION.

                      A rating snapshots the company and title it was written about, and the career
                      trajectory groups by that pair. Updating career_history alone therefore split
                      the trajectory in two - the corrected entry with no ratings, and the ratings
                      still filed under the old wording - which reads as "the edit did not save",
                      and no amount of re-editing fixed it.

                      Read FIRST, because the old pair is what identifies the ratings to move and
                      the update is about to overwrite it. An entry that has vanished between the
                      two calls simply moves nothing: the update below will report zero rows and
                      the caller learns the truth either way.
                    */
                    managerRepo.findCareerEntry(entryId, managerId).compose(before ->
                        managerRepo.updateCareerEntry(entryId, managerId, company.trim(), title.trim(),
                                                      finalStart, finalEnd, companyId)
                            .compose(count -> {
                                if (before.isEmpty()) return Future.succeededFuture(count);
                                String wasCompany = before.get().getString("company");
                                String wasTitle   = before.get().getString("title");
                                if (wasCompany == null || wasTitle == null) {
                                    return Future.succeededFuture(count);
                                }
                                return managerRepo.moveReviewsBetweenRoles(
                                        managerId, wasCompany, wasTitle,
                                        company.trim(), title.trim())
                                    .map(moved -> count);
                            }))
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


    /**
     * Resolves a career stage that should not be on the profile.
     *
     * <h2>Why this exists separately from adminDeleteCareerEntry</h2>
     *
     * <p>That one deletes a {@code career_history} row, and the Delete control on the trajectory
     * was therefore shown only on stages that had one. Most stages do not. The chart groups a
     * manager's reviews by {@code reviews.manager_company}, so a stage appears wherever a review
     * names a company, recorded role or not.
     *
     * <p>The consequence was a real one on a live profile: a placeholder review had captured a
     * half-typed company name, and that fragment rendered as a complete career stage with its own
     * rating, next to the manager's real employer. The admin had an Edit button and no way at all
     * to remove it, because there was no row for the existing endpoint to delete. Fixing it took
     * hand-written SQL against production.
     *
     * <h2>The two actions, and why both are needed</h2>
     *
     * <ul>
     *   <li>{@code delete} removes the stage: its {@code career_history} row if it has one, and the
     *       reviews that put it on the chart. For a stage invented by a placeholder this is the
     *       whole repair.
     *   <li>{@code reassign} moves the stage's reviews onto another role and removes the empty
     *       stage. For when the company is wrong but the opinions are real: deleting them would
     *       destroy somebody's genuine review to tidy up a chart.
     * </ul>
     *
     * <p>A delete that would remove genuine reviews is refused unless {@code confirmRealReviews} is
     * set. The caller has to have been told what it is about to destroy, and say so. Placeholders
     * carry no such guard because nobody wrote them.
     */
    public Future<JsonObject> adminResolveCareerStage(String auth0Id, long managerId, JsonObject body) {
        String action  = body.getString("action");
        String company = body.getString("company");
        if (company == null || company.isBlank())
            return Future.failedFuture(ServiceException.badRequest("company is required"));
        if (!"delete".equals(action) && !"reassign".equals(action))
            return Future.failedFuture(ServiceException.badRequest("action must be 'delete' or 'reassign'"));

        String toCompany = body.getString("toCompany");
        String toTitle   = body.getString("toTitle");
        if ("reassign".equals(action) && (toCompany == null || toCompany.isBlank()))
            return Future.failedFuture(ServiceException.badRequest("toCompany is required to reassign"));
        if ("reassign".equals(action) && toCompany.trim().equalsIgnoreCase(company.trim()))
            return Future.failedFuture(ServiceException.badRequest("Cannot reassign a stage onto itself"));

        boolean confirmReal = Boolean.TRUE.equals(body.getBoolean("confirmRealReviews"));

        return requireAdmin(auth0Id)
            .compose(adminId -> managerRepo.describeCareerStage(managerId, company))
            .compose(stage -> {
                int realReviews = stage.getInteger("real_reviews");
                if ("delete".equals(action) && realReviews > 0 && !confirmReal) {
                    /*
                      Refused rather than silently obeyed. The count travels in the message so the
                      caller can put a number in front of the person pressing the button: "this
                      removes 2 real reviews" is a decision, "are you sure?" is not.
                    */
                    return Future.failedFuture(ServiceException.badRequest(
                        "This stage holds " + realReviews + " real review"
                      + (realReviews == 1 ? "" : "s")
                      + ". Reassign them to another role, or confirm the deletion."));
                }
                return managerRepo.findCareerEntryIdByCompany(managerId, company)
                    .compose(entryId -> {
                        Future<Integer> reviewWork = "delete".equals(action)
                            ? managerRepo.deleteCareerStageReviews(managerId, company)
                            : managerRepo.reassignCareerStageReviews(managerId, company, toCompany, toTitle);
                        return reviewWork.compose(affected -> {
                            /*
                              The row goes in both cases. After a reassign the stage has no reviews
                              left, so a surviving career_history row would redraw the empty stage
                              on the chart, which is the thing being removed.
                            */
                            Future<Integer> entryWork = entryId.isPresent()
                                ? managerRepo.deleteCareerEntry(entryId.get(), managerId)
                                : Future.succeededFuture(0);
                            return entryWork.map(deletedEntries -> new int[]{ affected, deletedEntries });
                        });
                    });
            })
            // The cached rating and count are not maintained by any trigger, so they are rebuilt
            // here. Without this the header keeps quoting a figure that included what was removed.
            .compose(counts -> managerRepo.recalculate(managerId).map(v -> counts))
            // Removing the current role must not leave the header advertising it, exactly as the
            // single-entry delete above does.
            .compose(counts -> managerRepo.syncHeadlineFromCareerHistory(managerId).map(v -> counts))
            .map(counts -> new JsonObject()
                .put("success", true)
                .put("action", action)
                .put("reviewsAffected", counts[0])
                .put("entriesDeleted", counts[1]));
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
                    // Decided once per row: the validator is pure, but running it twice to answer
                    // the same question invites the two answers to disagree.
                    Optional<String> blocked = publicationBlocker(row);
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
                        .put("createdAt",   row.getOffsetDateTime("created_at").toString())
                        /*
                          Whether this one could be published, decided by the SAME validator the
                          public form runs, so the panel cannot offer a button that then fails or
                          withhold one from a draft that would have worked. The reason travels too:
                          an admin looking at a half-finished rating should be able to see which
                          answer is missing without opening the payload.
                        */
                        .put("publishable", blocked.isEmpty())
                        .put("blockedReason", blocked.orElse(null)));
                }
                return new JsonObject()
                    .put("data", out)
                    .put("total", cf.resultAt(1))
                    .put("limit", limit).put("offset", offset);
            });
    }

    /** Marks one draft read and not published. Kept rather than deleted, so it can be audited. */
    public Future<JsonObject> markDraftReviewed(String auth0Id, long draftId) {
        return requireAdmin(auth0Id)
            .compose(adminId -> capturedDrafts.dismiss(draftId, adminId))
            .map(done -> new JsonObject().put("success", done));
    }

    /**
     * Why a captured draft cannot be published, or empty when it can be.
     *
     * <p>Delegates to the owning form's own validator. A draft of a kind nothing can publish
     * reports that plainly rather than appearing publishable and failing later.
     */
    private static Optional<String> publicationBlocker(Row draftRow) {
        JsonObject payload = draftRow.getJsonObject("payload");
        if (payload == null) return Optional.of("This draft holds no answers.");
        if (draftRow.getLong("company_id") == null) {
            /*
              A capture can arrive with no company: the form names one that does not exist yet,
              and the daily company-creation cap deliberately lets the capture through with
              company_id null rather than losing the answers. There is nothing to attach a rating
              to, and creating the company here would be an admin walking around that cap.
            */
            return Optional.of("The company this was about was never created.");
        }
        return switch (draftRow.getString("kind")) {
            case CapturedDraftRepository.COMPANY_RATING -> CompanyReviewService.publicationBlocker(payload);
            case CapturedDraftRepository.INTERVIEW      -> InterviewService.publicationBlocker(payload);
            default -> Optional.of("This kind of draft cannot be published.");
        };
    }

    /**
     * Turns a complete captured form into a real, anonymous rating.
     *
     * <p>The ending the manager forms have always had. A manager rating dropped off at the
     * sign-in wall becomes a real {@code reviews} row with {@code user_id} null straight away, and
     * an admin publishes it by approving the manager it is about. A workplace rating or an
     * interview experience had no equivalent: it sat in this queue, readable and unpublishable,
     * however complete it was.
     *
     * <h2>One transaction, and what each part of it is for</h2>
     *
     * <ol>
     *   <li><b>the draft row is locked</b>, which is what serialises this against its author
     *       returning to submit the same form while an admin is looking at it. Both writers decide
     *       the same question and share no other row;
     *   <li><b>the completeness check runs the public validator</b>, so nothing reaches a public
     *       aggregate that the form itself would have refused;
     *   <li><b>the rating is written</b> with no user attached;
     *   <li><b>the draft is resolved</b> as published, naming the rating it became, so a second
     *       admin cannot publish it again and the queue can account for what it created;
     *   <li><b>siblings are retired.</b> The capture path inserts a fresh row every time somebody
     *       is bounced, so one form can hold several drafts. Without this the duplicates stay in
     *       the queue and the next publish attempt is refused by the unique index rather than by
     *       anything an admin can understand.
     * </ol>
     *
     * <p>Any failure rolls back all of it. A published rating whose draft is still queued is the
     * one state that produces a duplicate, so it cannot be a state this method can end in.
     */
    public Future<JsonObject> publishCapturedDraft(String auth0Id, long draftId) {
        if (db == null || companyRatings == null || interviewReviews == null) {
            return Future.failedFuture(ServiceException.forbidden("DB not configured"));
        }
        return requireAdmin(auth0Id).compose(adminId ->
            ((io.vertx.sqlclient.Pool) db).withTransaction(conn ->
                capturedDrafts.findForPublication(conn, draftId).compose(opt -> {
                    if (opt.isEmpty()) {
                        return Future.failedFuture(ServiceException.notFound("Draft not found"));
                    }
                    Row draft = opt.get();
                    if (draft.getOffsetDateTime("reviewed_at") != null) {
                        /*
                          Already dealt with, by another admin or by its author coming back. A
                          conflict rather than a 404: the draft exists, and saying so is how the
                          panel can refresh and show what happened to it instead of claiming the
                          row is gone.
                        */
                        return Future.failedFuture(ServiceException.conflict("draft_already_resolved"));
                    }
                    Optional<String> blocked = publicationBlocker(draft);
                    if (blocked.isPresent()) {
                        return Future.failedFuture(ServiceException.badRequest(blocked.get()));
                    }

                    long companyId   = draft.getLong("company_id");
                    JsonObject payload = draft.getJsonObject("payload");
                    UUID draftToken  = draft.getUUID("draft_token");
                    String kind      = draft.getString("kind");

                    /*
                      Dispatched on the kind explicitly, with no fallback. The blocker above
                      already refuses any other kind, so this is the second lock on the same door:
                      a future edit that loosens the blocker must not quietly start publishing an
                      unknown kind of draft as a workplace rating.
                    */
                    Future<Row> written = switch (kind) {
                        case CapturedDraftRepository.COMPANY_RATING ->
                            companyRatings.publishAnonymously(conn, companyId, payload, draftToken);
                        case CapturedDraftRepository.INTERVIEW ->
                            interviewReviews.publishAnonymously(conn, companyId, payload, draftToken);
                        default -> Future.failedFuture(
                            ServiceException.badRequest("This kind of draft cannot be published."));
                    };

                    return written.compose(row -> {
                        UUID ratingId = row.getUUID("id");
                        return capturedDrafts.resolve(conn, draftId,
                                    CapturedDraftRepository.PUBLISHED, ratingId, adminId)
                            .compose(done -> done
                                ? Future.succeededFuture()
                                : Future.failedFuture(ServiceException.conflict("draft_already_resolved")))
                            .compose(v -> capturedDrafts.supersedeSiblings(conn, draftToken, draftId, ratingId))
                            /*
                              And forget who dropped it off. The rating carries no user_id, but it
                              carries the token, and while the draft names an account that token is
                              a join back to them. A dropped-off manager rating has no such row
                              anywhere; these now match.
                            */
                            .compose(v -> capturedDrafts.forgetSubmitter(conn, draftId))
                            .map(v -> new JsonObject()
                                .put("success", true)
                                .put("kind", kind)
                                .put("ratingId", ratingId.toString())
                                .put("companyId", companyId));
                    });
                })));
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

    // ── Manager search requests: the review queue ─────────────────────────────

    /**
     * The queue, most-searched first.
     *
     * <p>Ordered by weight of evidence rather than age: a manager fifty people have looked for
     * matters more than one somebody typed once in March. {@code seen_count} is what carries that,
     * and it exists because the unique constraint folds repeats into one row instead of letting
     * the queue fill with copies.
     */
    public Future<JsonArray> listSearchRequests(String auth0Id, String status, int limit, int offset) {
        return requireAdmin(auth0Id).compose(adminId -> {
            ManagerSearchRequestRepository repo = new ManagerSearchRequestRepository(db);
            Future<RowSet<Row>> rows = "dismissed".equals(status)
                ? repo.findDismissedButRecurring(limit)
                : repo.findOpen(limit, offset);
            return rows.map(rs -> {
                JsonArray out = new JsonArray();
                for (Row r : rs) out.add(searchRequestToJson(r));
                return out;
            });
        });
    }

    /**
     * "This is a genuinely new person." Creates the manager from what was searched.
     *
     * <p>Publication is an admin act here, so the company IS resolved or created - the same rule
     * every deliberate submission follows. The request keeps the resulting manager id, so the
     * decision is auditable afterwards rather than vanishing into a status change.
     */
    public Future<JsonObject> createManagerFromRequest(String auth0Id, long requestId,
                                                       String resolvedLogoUrl) {
        ManagerSearchRequestRepository repo = new ManagerSearchRequestRepository(db);
        return requireAdmin(auth0Id).compose(adminId ->
            /*
              CLAIMED BEFORE ANYTHING IS CREATED.

              Reading the status and then checking it in Java let two admins act on one request at
              the same time, and this is the action where that costs most: two managers published
              for one person. The claim is a conditional UPDATE, so exactly one caller gets the
              row and the other is told it has already been reviewed.
            */
            repo.claim(requestId, "resolved", "new_manager", adminId).compose(req -> {
                if (req == null) return findRequestOrFail(repo, requestId);

                String fullName = req.getString("first_name") + " " + req.getString("last_name");
                String company  = req.getString("company_text");
                String title    = req.getString("title_text");

                return companyRepo.resolve(req.getLong("resolved_company_id"), company, null, resolvedLogoUrl)
                    .compose(cRow -> managerRepo.createApprovedFromRequest(
                        fullName, company, title, cRow.getLong("id"), adminId))
                    .compose(created -> repo.setResolvedManager(requestId, created.getLong("id"))
                        .map(v -> new JsonObject()
                            .put("managerId", created.getLong("id"))
                            .put("name", fullName)
                            .put("resolution", "new_manager")))
                    // The claim was taken on the promise of a manager. Without one, it goes back.
                    .recover(err -> repo.reopen(requestId).compose(v -> Future.failedFuture(err)));
            }));
    }

    /**
     * "This is somebody we already have, in a job we did not know about."
     *
     * <p>Adds the searched employment to an EXISTING manager rather than creating a second one.
     * Dates come from the admin, because a search tells us nothing about when somebody held a
     * role, and guessing would write fiction into a career history people read.
     *
     * <p>Never automatic. A search is evidence that somebody typed a name, not that two records
     * describe one person.
     */
    public Future<JsonObject> attachRequestToManager(String auth0Id, long requestId, long managerId,
                                                     String startDate, String endDate) {
        ManagerSearchRequestRepository repo = new ManagerSearchRequestRepository(db);
        return requireAdmin(auth0Id).compose(adminId ->
            // Claimed first, for the same reason as above: two admins attaching the same searched
            // job would write it into one career history twice.
            repo.claim(requestId, "resolved", "existing_role", adminId).compose(req -> {
                if (req == null) return findRequestOrFail(repo, requestId);

                // Reuses the ordinary career-entry path, so company resolution, validation and
                // date handling behave exactly as they do when an admin adds a role by hand.
                return adminCreateCareerEntry(auth0Id, managerId,
                        req.getString("company_text"), req.getString("title_text"),
                        startDate, endDate, req.getLong("resolved_company_id"), null)
                    .compose(entry -> repo.setResolvedManager(requestId, managerId)
                        .map(v -> new JsonObject()
                            .put("managerId", managerId)
                            .put("resolution", "existing_role")))
                    .recover(err -> repo.reopen(requestId).compose(v -> Future.failedFuture(err)));
            }));
    }

    /**
     * "Not verifiable." The request stays, with the decision recorded.
     *
     * <p>Nothing is deleted, and {@code seen_since_review} resets to zero so the queue can show
     * "dismissed, and searched eleven times since" later. That is deliberately not a cooldown: a
     * timer is a guess about how long, whereas a count is a fact about how many, and only the
     * second one tells an admin they may have been wrong.
     */
    public Future<JsonObject> dismissSearchRequest(String auth0Id, long requestId) {
        ManagerSearchRequestRepository repo = new ManagerSearchRequestRepository(db);
        return requireAdmin(auth0Id).compose(adminId ->
            // Also claimed, so a second dismissal cannot reset seen_since_review and destroy the
            // "dismissed, and searched eleven times since" signal the counter exists to carry.
            repo.claim(requestId, "dismissed", "not_verifiable", adminId).compose(req ->
                req == null
                    ? findRequestOrFail(repo, requestId)
                    : Future.succeededFuture(
                        new JsonObject().put("id", requestId).put("status", "dismissed"))));
    }

    /**
     * Why a claim failed: the request is gone, or somebody else decided it first.
     *
     * <p>Told apart so an admin whose click lost a race sees "already reviewed" rather than a
     * "not found" that makes it look as though the row vanished.
     */
    private static <T> Future<T> findRequestOrFail(ManagerSearchRequestRepository repo, long requestId) {
        return repo.findById(requestId).compose(existing -> Future.failedFuture(
            existing == null
                ? ServiceException.notFound("Request not found")
                : ServiceException.badRequest("Request already reviewed")));
    }

    private static JsonObject searchRequestToJson(Row r) {
        JsonArray candidates = new JsonArray();
        Long[] ids = r.getArrayOfLongs("candidate_manager_ids");
        if (ids != null) for (Long id : ids) candidates.add(id);
        return new JsonObject()
            .put("id", r.getLong("id"))
            .put("firstName", r.getString("first_name"))
            .put("lastName", r.getString("last_name"))
            .put("company", r.getString("company_text"))
            .put("title", r.getString("title_text"))
            .put("resolvedCompanyId", r.getLong("resolved_company_id"))
            .put("candidateManagerIds", candidates)
            .put("reason", r.getString("reason"))
            .put("nameMatchClass", r.getString("name_match_class"))
            .put("companyClass", r.getString("best_company_class"))
            .put("titleClass", r.getString("best_title_class"))
            .put("status", r.getString("status"))
            .put("resolution", r.getString("resolution"))
            .put("seenCount", r.getInteger("seen_count"))
            .put("seenSinceReview", r.getInteger("seen_since_review"))
            .put("firstSeenAt", String.valueOf(r.getOffsetDateTime("first_seen_at")))
            .put("lastSeenAt", String.valueOf(r.getOffsetDateTime("last_seen_at")));
    }
}
