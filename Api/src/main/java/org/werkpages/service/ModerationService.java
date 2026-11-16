package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import org.werkpages.repository.EmploymentStatusResolver;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The moderation queue: the work the role-aware contribution flow creates, and the decisions that
 * clear it.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Phase 4B stopped the platform guessing which human a rating is about. The cost is that some
 * submissions now wait for a person: a contributor who proposes an employment we do not hold, or
 * two who describe what might be one job, produce a question rather than an answer. <b>Those
 * ratings are held, not refused</b> - nothing a person wrote is discarded - but a held rating that
 * nobody can find is a contribution lost by neglect rather than by decision.
 *
 * <h2>The rule this service does not get to interpret</h2>
 *
 * <p><b>Publication eligibility is one predicate in the database.</b>
 * {@code rating_role_is_publishable} is the same rule the submission path uses and the same rule
 * five triggers quote, and it is asked here rather than restated. A second copy of "may this be
 * public" in moderation code would drift from the one in the submission path, and the direction it
 * drifted would decide whether somebody's rating appeared.
 *
 * <p>Likewise the writes go through {@code ReviewDisposition}, which keeps
 * {@code disposition}, {@code gate_eligible} and {@code live_since} coherent and keeps the location
 * projection correct. Writing the column directly is how that projection broke before.
 *
 * <h2>Lock order</h2>
 *
 * <p>Every operation takes the manager first, then the role, then the claims, then the reviews:
 * the canonical order the merge uses. A moderator accepting a role while a merge of the same
 * manager runs must serialise, not deadlock.
 */
public final class ModerationService {

    private final Pool db;
    private final AdminService admin;
    private final ManagerMergeService merges;
    private final ReviewDisposition dispositions;

    public ModerationService(Pool db, AdminService admin) {
        this(db, admin, new ManagerMergeService(db), new ReviewDisposition());
    }

    public ModerationService(Pool db, AdminService admin, ManagerMergeService merges,
                             ReviewDisposition dispositions) {
        this.db = db;
        this.admin = admin;
        this.merges = merges;
        this.dispositions = dispositions;
    }

    /**
     * The guard, in the service and not only in the handler.
     *
     * <p>Every public method here begins with it, which is the rule the rest of the admin surface
     * already follows. Authorization checked in the HTTP adapter is authorization one forgotten
     * route registration away from being absent, and an endpoint that moderates other people's
     * contributions is not a good place to find that out. A caller who hand-builds the request gets
     * the same 401 or 403 as one who clicks the button.
     */
    private Future<UUID> requireAdmin(String auth0Id) {
        if (admin == null) {
            return Future.failedFuture(ServiceException.forbidden("Moderation is unavailable."));
        }
        return admin.requireAdminPublic(auth0Id);
    }

    // ── the queue ────────────────────────────────────────────────────────────

    /**
     * What is waiting, and how long the oldest has waited.
     *
     * <p>The age matters more than the count. A queue of forty that turns over daily is healthy; a
     * queue of three where the oldest has waited five weeks means somebody's rating has been
     * invisible for five weeks, and only one of those numbers shows it.
     */
    public Future<JsonObject> queueSummary(String auth0Id) {
        return requireAdmin(auth0Id).compose(adminId -> queueSummaryImpl());
    }

    private Future<JsonObject> queueSummaryImpl() {
        return db.query("""
                SELECT
                  (SELECT count(*) FROM career_history WHERE state = 'proposed')      AS pending_employments,
                  (SELECT min(created_at) FROM career_history WHERE state = 'proposed') AS oldest_employment,
                  (SELECT count(*) FROM role_conflicts WHERE resolved_at IS NULL)     AS open_conflicts,
                  (SELECT count(*) FROM role_conflicts
                    WHERE resolved_at IS NULL AND blocks_publication)                 AS open_blocking_conflicts,
                  (SELECT min(detected_at) FROM role_conflicts WHERE resolved_at IS NULL) AS oldest_conflict,
                  (SELECT count(*) FROM reviews
                    WHERE disposition = 'held' AND deleted_at IS NULL)                AS held_ratings,
                  (SELECT min(created_at) FROM reviews
                    WHERE disposition = 'held' AND deleted_at IS NULL)                AS oldest_held_rating
                """).execute()
            .map(rs -> {
                Row r = rs.iterator().next();
                return new JsonObject()
                    .put("pendingEmployments",     r.getInteger("pending_employments"))
                    .put("oldestEmploymentAt",     iso(r, "oldest_employment"))
                    .put("openConflicts",          r.getInteger("open_conflicts"))
                    .put("openBlockingConflicts",  r.getInteger("open_blocking_conflicts"))
                    .put("oldestConflictAt",       iso(r, "oldest_conflict"))
                    .put("heldRatings",            r.getInteger("held_ratings"))
                    .put("oldestHeldRatingAt",     iso(r, "oldest_held_rating"));
            });
    }

    /**
     * Proposed employments awaiting a decision, with the evidence for each.
     *
     * <h3>Corroboration, counted honestly</h3>
     *
     * <p>Two independent people describing the same employment is the strongest signal a moderator
     * can have that it is real. So the count is here rather than left to be inferred from a list of
     * claims.
     *
     * <p><b>Authenticated and anonymous contributors are counted separately, and the anonymous ones
     * are never described as distinct people.</b> Several anonymous claims may be one person who
     * cleared their cookies, so presenting "3 contributors" when two of them might be the same
     * visitor would manufacture corroboration out of nothing - and corroboration is exactly what a
     * moderator is about to act on. `DISTINCT user_id` is a real count of accounts; anonymous
     * claims get a claim count and a plain label.
     */
    public Future<JsonArray> pendingEmployments(String auth0Id, int limit, int offset) {
        return requireAdmin(auth0Id).compose(adminId -> pendingEmploymentsImpl(limit, offset));
    }

    private Future<JsonArray> pendingEmploymentsImpl(int limit, int offset) {
        return db.preparedQuery("""
                SELECT ch.id, ch.manager_id, ch.company_id, ch.company, ch.title,
                       ch.start_date, ch.end_date, ch.period_known, ch.source, ch.created_at,
                       m.name  AS manager_name,
                       m.slug  AS manager_slug,
                       m.approval_status AS manager_status,
                       c.name  AS company_name,
                       (SELECT count(DISTINCT rc.user_id) FROM role_claims rc
                         WHERE rc.resolved_role_id = ch.id AND rc.user_id IS NOT NULL)
                         AS corroborating_accounts,
                       (SELECT count(*) FROM role_claims rc
                         WHERE rc.resolved_role_id = ch.id AND rc.user_id IS NULL)
                         AS anonymous_claims,
                       (SELECT count(*) FROM role_claims rc WHERE rc.resolved_role_id = ch.id)
                         AS total_claims,
                       (SELECT count(*) FROM reviews r
                          JOIN role_claims rc2 ON rc2.id = r.claim_id
                         WHERE rc2.resolved_role_id = ch.id
                           AND r.disposition = 'held' AND r.deleted_at IS NULL)
                         AS held_ratings,
                       (SELECT count(*) FROM role_conflict_claims rcc
                          JOIN role_conflicts cf ON cf.id = rcc.conflict_id
                          JOIN role_claims rc3 ON rc3.id = rcc.claim_id
                         WHERE rc3.resolved_role_id = ch.id
                           AND rcc.role = 'subject'
                           AND cf.blocks_publication AND cf.resolved_at IS NULL)
                         AS blocking_conflicts
                  FROM career_history ch
                  JOIN managers m   ON m.id = ch.manager_id
                  LEFT JOIN companies c ON c.id = ch.company_id
                 WHERE ch.state = 'proposed'
                 ORDER BY ch.created_at
                 LIMIT $1 OFFSET $2
                """)
            .execute(Tuple.of(limit, offset))
            .map(rs -> {
                JsonArray out = new JsonArray();
                for (Row r : rs) out.add(employmentRowJson(r));
                return out;
            });
    }

    private JsonObject employmentRowJson(Row r) {
        return new JsonObject()
            .put("roleId",        r.getLong("id"))
            .put("managerId",     r.getLong("manager_id"))
            .put("managerName",   r.getString("manager_name"))
            .put("managerSlug",   r.getString("manager_slug"))
            .put("managerStatus", r.getString("manager_status"))
            .put("companyId",     r.getLong("company_id"))
            .put("company",       r.getString("company_name") != null
                                  ? r.getString("company_name") : r.getString("company"))
            .put("title",         r.getString("title"))
            .put("startDate",     r.getLocalDate("start_date") == null
                                  ? null : r.getLocalDate("start_date").toString())
            .put("endDate",       r.getLocalDate("end_date") == null
                                  ? null : r.getLocalDate("end_date").toString())
            .put("periodKnown",   r.getBoolean("period_known"))
            // Where it came from: a contributor proposed it, or an import produced it. A moderator
            // weighs those differently and the column already records which.
            .put("provenance",    r.getString("source"))
            .put("proposedAt",    iso(r, "created_at"))
            .put("corroboratingAccounts", r.getInteger("corroborating_accounts"))
            .put("anonymousClaims",       r.getInteger("anonymous_claims"))
            .put("totalClaims",           r.getInteger("total_claims"))
            .put("heldRatings",           r.getInteger("held_ratings"))
            // Accepting the employment will NOT publish these: a blocking conflict is a separate
            // hold with its own decision, and saying otherwise would promise a moderator an
            // outcome their click cannot deliver.
            .put("blockingConflicts",     r.getInteger("blocking_conflicts"));
    }

    /**
     * One proposed employment, with everything needed to decide without opening a database client.
     *
     * <p>Includes <b>the manager's existing employment history</b>, because the question a moderator
     * is actually answering is "is this a job we already hold under another description". Deciding
     * that from the proposal alone is guesswork, and it is the guesswork this phase exists to stop
     * being necessary.
     */
    public Future<JsonObject> employmentDetail(String auth0Id, long roleId) {
        return requireAdmin(auth0Id).compose(adminId -> employmentDetailImpl(roleId));
    }

    private Future<JsonObject> employmentDetailImpl(long roleId) {
        return db.preparedQuery("""
                SELECT ch.*, m.name AS manager_name, m.slug AS manager_slug,
                       m.approval_status AS manager_status, c.name AS company_name
                  FROM career_history ch
                  JOIN managers m ON m.id = ch.manager_id
                  LEFT JOIN companies c ON c.id = ch.company_id
                 WHERE ch.id = $1
                """)
            .execute(Tuple.of(roleId))
            .compose(rs -> {
                if (rs.rowCount() == 0) {
                    return Future.failedFuture(ServiceException.notFound(
                        "No such employment: " + roleId));
                }
                Row role = rs.iterator().next();
                long managerId = role.getLong("manager_id");
                return Future.all(
                        claimsFor(roleId),
                        heldRatingsForRole(roleId),
                        otherEmploymentsOf(managerId, roleId))
                    .map(all -> new JsonObject()
                        .put("employment",        detailRoleJson(role))
                        .put("claims",            all.resultAt(0))
                        .put("heldRatings",       all.resultAt(1))
                        .put("managerHistory",    all.resultAt(2)));
            });
    }

    private JsonObject detailRoleJson(Row r) {
        return new JsonObject()
            .put("roleId",        r.getLong("id"))
            .put("managerId",     r.getLong("manager_id"))
            .put("managerName",   r.getString("manager_name"))
            .put("managerSlug",   r.getString("manager_slug"))
            .put("managerStatus", r.getString("manager_status"))
            .put("company",       r.getString("company_name") != null
                                  ? r.getString("company_name") : r.getString("company"))
            .put("companyId",     r.getLong("company_id"))
            .put("title",         r.getString("title"))
            .put("state",         r.getString("state"))
            .put("periodKnown",   r.getBoolean("period_known"))
            .put("startDate",     r.getLocalDate("start_date") == null
                                  ? null : r.getLocalDate("start_date").toString())
            .put("endDate",       r.getLocalDate("end_date") == null
                                  ? null : r.getLocalDate("end_date").toString())
            .put("provenance",    r.getString("source"))
            /*
              WHERE A REOPENED PROPOSAL CAME FROM, so the rejection it replaces stays visible from
              the new row rather than only from the audit timeline. Null for every ordinary
              employment, and the pair (reopenedFrom, supersededBy) is what lets a moderator walk
              backwards through what happened to this job.
            */
            .put("reopenedFrom",  r.getLong("reopened_from"))
            .put("supersededBy",  r.getLong("superseded_by"))
            .put("proposedAt",    iso(r, "created_at"));
    }

    private Future<JsonArray> claimsFor(long roleId) {
        return db.preparedQuery("""
                SELECT rc.id, rc.title, rc.start_date, rc.end_date, rc.period_known,
                       rc.still_there, rc.resolution, rc.finalized_at,
                       rc.user_id IS NOT NULL AS from_account,
                       (SELECT count(*) FROM reviews r WHERE r.claim_id = rc.id) AS ratings
                  FROM role_claims rc
                 WHERE rc.resolved_role_id = $1
                 ORDER BY rc.finalized_at NULLS LAST, rc.id
                """)
            .execute(Tuple.of(roleId))
            .map(rs -> {
                JsonArray out = new JsonArray();
                for (Row r : rs) {
                    out.add(new JsonObject()
                        .put("claimId",     r.getLong("id"))
                        // What the contributor ASSERTED, kept beside what the platform resolved.
                        // A moderator must be able to see the two differ without the difference
                        // being hidden, because the assertion is the evidence.
                        .put("assertedTitle", r.getString("title"))
                        .put("assertedStart", r.getLocalDate("start_date") == null
                                              ? null : r.getLocalDate("start_date").toString())
                        .put("assertedEnd",   r.getLocalDate("end_date") == null
                                              ? null : r.getLocalDate("end_date").toString())
                        .put("periodKnown", r.getBoolean("period_known"))
                        .put("stillThere",  r.getBoolean("still_there"))
                        .put("resolution",  r.getString("resolution"))
                        .put("finalizedAt", iso(r, "finalized_at"))
                        // NOT the contributor's identity: whether an account stands behind it.
                        // Who they are is never needed to moderate an employment, so it is not sent.
                        .put("fromAccount", r.getBoolean("from_account"))
                        .put("ratings",     r.getInteger("ratings")));
                }
                return out;
            });
    }

    private Future<JsonArray> heldRatingsForRole(long roleId) {
        return db.preparedQuery("""
                SELECT r.id, r.created_at, r.overall_rating, r.manager_title, r.manager_company,
                       r.disposition,
                       rating_role_is_publishable(r.id) AS would_publish
                  FROM reviews r
                 WHERE (r.claim_id IN (SELECT rc.id FROM role_claims rc
                                        WHERE rc.resolved_role_id = $1)
                        OR r.withdrawn_role_id = $1)
                   AND r.disposition = 'held' AND r.deleted_at IS NULL
                 ORDER BY r.created_at
                """)
            .execute(Tuple.of(roleId))
            .map(rs -> {
                JsonArray out = new JsonArray();
                for (Row r : rs) {
                    out.add(new JsonObject()
                        .put("reviewId",      r.getUUID("id").toString())
                        .put("submittedAt",   iso(r, "created_at"))
                        .put("overallRating", r.getBigDecimal("overall_rating"))
                        .put("title",         r.getString("manager_title"))
                        .put("company",       r.getString("manager_company"))
                        // Asked of the database, not computed here. It is the answer to "will
                        // accepting this actually release the rating", and it is already false
                        // when an independent blocking conflict remains.
                        .put("wouldPublishNow", r.getBoolean("would_publish")));
                }
                return out;
            });
    }

    private Future<JsonArray> otherEmploymentsOf(long managerId, long excludeRoleId) {
        return db.preparedQuery("""
                SELECT id, company, title, state, start_date, end_date, period_known
                  FROM career_history
                 WHERE manager_id = $1 AND id <> $2
                 ORDER BY start_date NULLS LAST, id
                """)
            .execute(Tuple.of(managerId, excludeRoleId))
            .map(rs -> {
                JsonArray out = new JsonArray();
                for (Row r : rs) {
                    out.add(new JsonObject()
                        .put("roleId",      r.getLong("id"))
                        .put("company",     r.getString("company"))
                        .put("title",       r.getString("title"))
                        .put("state",       r.getString("state"))
                        .put("periodKnown", r.getBoolean("period_known"))
                        .put("startDate",   r.getLocalDate("start_date") == null
                                            ? null : r.getLocalDate("start_date").toString())
                        .put("endDate",     r.getLocalDate("end_date") == null
                                            ? null : r.getLocalDate("end_date").toString()));
                }
                return out;
            });
    }

    // ── decisions ────────────────────────────────────────────────────────────

    /**
     * Accept a proposed employment, and release the ratings that become publishable.
     *
     * <p><b>Releasing is not "publish everything attached".</b> Each rating is asked of
     * {@code rating_role_is_publishable} individually, so one that is also the subject of an open
     * blocking conflict stays held. Accepting an employment answers one question; it does not
     * answer the others, and promoting on the strength of this decision alone would publish a
     * rating whose attribution is still in doubt.
     *
     * <p>Idempotent: a second accept finds the role already accepted and reports it rather than
     * failing, because a double-click is not an error.
     */
    public Future<JsonObject> acceptEmployment(String auth0Id, long roleId) {
        return requireAdmin(auth0Id).compose(adminId -> acceptEmploymentImpl(roleId, adminId));
    }

    private Future<JsonObject> acceptEmploymentImpl(long roleId, UUID adminId) {
        return db.withTransaction(tx -> lockRoleAndManager(tx, roleId)
            .compose(role -> {
                String state = role.getString("state");
                if ("accepted".equals(state)) {
                    return Future.succeededFuture(new JsonObject()
                        .put("roleId", roleId).put("state", "accepted")
                        .put("alreadyDone", true).put("ratingsPublished", 0));
                }
                if (!"proposed".equals(state)) {
                    return Future.failedFuture(ServiceException.badRequest(
                        "Only a proposed employment can be accepted; this one is " + state));
                }
                return tx.preparedQuery(
                        "UPDATE career_history SET state = 'accepted', updated_at = now() "
                        + "WHERE id = $1")
                    .execute(Tuple.of(roleId))
                    /*
                      ACCEPTANCE IS WHEN A STATUS BECOMES ANSWERABLE, so it is resolved here, in
                      this transaction, before anything is released. A proposed role carries no
                      status by design: the platform has not accepted that the employment existed,
                      so it has nothing to say about whether it is current.

                      Acceptance is still not CURRENCY. A moderator accepting that somebody worked
                      somewhere establishes the employment, not its present-day status, so an
                      accepted role with no end date and no contributor assertion resolves to
                      unknown, which is a normal and correct row.
                    */
                    .compose(v -> EmploymentStatusResolver.resolveRole(tx, roleId))
                    .compose(v -> releasePublishableRatings(tx, roleId))
                    .compose(published -> recalculate(tx, role.getLong("manager_id"))
                        .map(v -> new JsonObject()
                            .put("roleId", roleId).put("state", "accepted")
                            .put("alreadyDone", false)
                            .put("ratingsPublished", published)));
            }));
    }

    /**
     * Reject a proposed employment. The ratings stay held, recoverable, and are never deleted.
     *
     * <p><b>A live rating must be held BEFORE the state changes</b>, not after.
     * {@code roles_live_is_resolved} is a deferred constraint trigger: it checks at commit that no
     * live rating depends on an unpublishable role, so rejecting first and tidying later fails the
     * whole transaction. Holding first is the order the database requires, which is also the order
     * that never leaves a rating live against a refused employment.
     *
     * <p>In practice it finds none, and the reason is worth knowing rather than assuming: a live
     * rating against a PROPOSED employment cannot be committed in the first place, because
     * {@code reviews_live_is_resolved} is also deferred and fires on INSERT as well as UPDATE.
     * Since only a proposed employment can be rejected, the hold is defence rather than everyday
     * work - and it stops being theoretical the day a correction path allows rejecting an accepted
     * one. It traverses both routes to a role's ratings for the same reason
     * {@code assert_roles_ratings} does.
     *
     * <p>A rejection is not deletion and is not permanent in effect: the claim and its rating
     * survive, and a moderator who later links the claim elsewhere releases them.
     */
    public Future<JsonObject> rejectEmployment(String auth0Id, long roleId, String reason) {
        return requireAdmin(auth0Id).compose(adminId -> rejectEmploymentImpl(roleId, adminId, reason));
    }

    private Future<JsonObject> rejectEmploymentImpl(long roleId, UUID adminId, String reason) {
        return db.withTransaction(tx -> lockRoleAndManager(tx, roleId)
            .compose(role -> {
                String state = role.getString("state");
                if ("rejected".equals(state)) {
                    return Future.succeededFuture(new JsonObject()
                        .put("roleId", roleId).put("state", "rejected")
                        .put("alreadyDone", true).put("ratingsHeld", 0));
                }
                if (!"proposed".equals(state)) {
                    return Future.failedFuture(ServiceException.badRequest(
                        "Only a proposed employment can be rejected; this one is " + state));
                }
                return holdLiveRatingsFor(tx, roleId)
                    .compose(held -> tx.preparedQuery(
                            "UPDATE career_history SET state = 'rejected', updated_at = now() "
                            + "WHERE id = $1")
                        .execute(Tuple.of(roleId))
                        .compose(v -> recalculate(tx, role.getLong("manager_id")))
                        .map(v -> new JsonObject()
                            .put("roleId", roleId).put("state", "rejected")
                            .put("alreadyDone", false)
                            .put("ratingsHeld", held)));
            }));
    }

    /**
     * Point a claim at an existing canonical employment, because the moderator judged them the
     * same job. This is the resolution that releases a rating without accepting a duplicate role.
     *
     * <p><b>{@code selected_role_id} is never touched.</b> What the contributor chose stays exactly
     * as they left it and {@code resolved_role_id} carries where the platform put it, because
     * {@code claim_selection_is_honoured_or_canonicalized} requires that a divergence from an
     * explicit choice be recorded as a canonicalization with an actor and a time rather than
     * silently overwritten.
     *
     * <p><b>The schema distinguishes two operations here and so does this method.</b> Treating
     * them alike is what {@code claim_canonicalization_is_complete} refuses:
     *
     * <ul>
     *   <li><b>Redirecting</b> a claim already resolved to some role: that is a canonicalization,
     *       and {@code canonicalized_from} is the role it moves OFF. The audit trigger compares
     *       exactly that column against the old value to classify the event as
     *       {@code redirected}, so any other value would mislabel the history.</li>
     *   <li><b>Resolving</b> a claim that has no role yet: there is nothing to move off, so a
     *       canonicalization would have a null {@code canonicalized_from} and violate the check.
     *       It is recorded as a resolution instead. If the contributor had selected a DIFFERENT
     *       role, the divergence is still recorded, with the selection as what it was corrected
     *       from.</li>
     * </ul>
     */
    public Future<JsonObject> linkClaimToRole(String auth0Id, long claimId, long targetRoleId) {
        return requireAdmin(auth0Id).compose(adminId -> linkClaimToRoleImpl(claimId, targetRoleId, adminId));
    }

    private Future<JsonObject> linkClaimToRoleImpl(long claimId, long targetRoleId, UUID adminId) {
        return db.withTransaction(tx -> markAdminDecision(tx)
            // THE MANAGER FIRST, and that is why this reads the claim twice.
            //
            // The canonical order is managers -> career_history -> role_claims, and the merge takes
            // it in that order. Taking the claim's row lock before the manager's would be the exact
            // inversion that deadlocks a moderator against a merge of the same person, so the
            // first read is unlocked and serves only to find out which manager to lock.
            .compose(v -> tx.preparedQuery(
                    "SELECT manager_id FROM role_claims WHERE id = $1")
                .execute(Tuple.of(claimId)))
            .compose(rs -> {
                if (rs.rowCount() == 0) {
                    return Future.failedFuture(ServiceException.notFound(
                        "No such claim: " + claimId));
                }
                long managerBeforeLock = rs.iterator().next().getLong("manager_id");
                return lockManagerOf(tx, managerBeforeLock)
                    .compose(v -> tx.preparedQuery("""
                        SELECT rc.id, rc.manager_id, rc.resolved_role_id, rc.selected_role_id,
                               rc.finalized_at, rc.resolution
                          FROM role_claims rc WHERE rc.id = $1 FOR UPDATE
                        """).execute(Tuple.of(claimId)))
                    .compose(locked -> {
                        if (locked.rowCount() == 0) {
                            return Future.failedFuture(ServiceException.notFound(
                                "No such claim: " + claimId));
                        }
                        Row c = locked.iterator().next();
                        if (c.getLong("manager_id") != managerBeforeLock) {
                            // A merge moved this claim to another manager while we waited for the
                            // lock, so the manager now held is the wrong one. Refusing is correct:
                            // the moderator was looking at a different person's queue, and
                            // retrying under the right lock is their decision, not a silent one.
                            return Future.failedFuture(ServiceException.conflict(
                                "That claim moved to another manager while you were deciding. "
                                + "Reload and look again."));
                        }
                        return linkLockedClaim(tx, c, claimId, targetRoleId, adminId);
                    });
            }));
    }

    private Future<JsonObject> linkLockedClaim(SqlConnection tx, Row claim, long claimId,
                                               long targetRoleId, UUID adminId) {
        return Future.succeededFuture().compose(ignored -> {
                if (claim.getOffsetDateTime("finalized_at") == null) {
                    // An unfinalized claim is an abandoned draft with no rating behind it, and
                    // claim_resolved_is_finalized refuses to resolve one. There is nothing here to
                    // moderate.
                    return Future.failedFuture(ServiceException.badRequest(
                        "That claim was never finalized, so there is nothing to link."));
                }
                Long from = claim.getLong("resolved_role_id");
                if (from != null && from == targetRoleId) {
                    return Future.succeededFuture(new JsonObject()
                        .put("claimId", claimId).put("toRoleId", targetRoleId)
                        .put("alreadyDone", true).put("ratingsPublished", 0));
                }
                // The manager is already locked by the caller, in the canonical order.
                return tx.preparedQuery("""
                        SELECT id FROM career_history
                         WHERE id = $1 AND manager_id = $2 AND state IN ('proposed','accepted')
                         FOR SHARE
                        """).execute(Tuple.of(targetRoleId, claim.getLong("manager_id")))
                    .compose(target -> {
                        if (target.rowCount() == 0) {
                            // The target has to belong to the SAME manager. Linking a claim to
                            // another person's employment would move somebody's rating between
                            // two humans, which is the single thing this design exists to prevent.
                            return Future.failedFuture(ServiceException.badRequest(
                                "That employment does not belong to this manager, or is not "
                                + "available to link to."));
                        }
                        Future<Void> write;
                        if (from != null) {
                            write = tx.preparedQuery("""
                                    UPDATE role_claims
                                       SET resolved_role_id   = $2,
                                           canonicalized_at   = now(),
                                           canonicalized_by   = $3,
                                           canonicalized_from = $4,
                                           updated_at         = now()
                                     WHERE id = $1
                                    """)
                                .execute(Tuple.of(claimId, targetRoleId, adminId, from))
                                .mapEmpty();
                        } else {
                            Long selected = claim.getLong("selected_role_id");
                            // Only a divergence from an EXPLICIT choice has to be recorded as a
                            // canonicalization. Resolving a claim that selected nothing, or that
                            // selected this very role, honours the selection rather than overriding
                            // it, and claim_canonicalization_is_complete wants all three columns
                            // null in that case.
                            Long canonicalizedFrom =
                                selected != null && !selected.equals(targetRoleId) ? selected : null;
                            write = tx.preparedQuery("""
                                    UPDATE role_claims
                                       SET resolved_role_id      = $2,
                                           resolution            = 'linked',
                                           resolved_at           = now(),
                                           resolved_by           = $3,
                                           -- Moving off 'duplicate' has to release its target, or
                                           -- claim_duplicate_names_its_target refuses the row.
                                           duplicate_of_claim_id = NULL,
                                           -- now() is the TRANSACTION's timestamp, so this pairs
                                           -- with the actor rather than drifting from it. The
                                           -- actor and the source role are passed explicitly and
                                           -- cast: an untyped NULL inside a CASE left the
                                           -- parameter's type to be guessed, and it was guessed as
                                           -- text against a uuid column.
                                           canonicalized_at   = CASE WHEN $4::BIGINT IS NULL
                                                                     THEN NULL ELSE now() END,
                                           canonicalized_by   = $5::UUID,
                                           canonicalized_from = $4::BIGINT,
                                           updated_at         = now()
                                     WHERE id = $1
                                    """)
                                .execute(Tuple.of(claimId, targetRoleId, adminId,
                                                  canonicalizedFrom,
                                                  canonicalizedFrom == null ? null : adminId))
                                .mapEmpty();
                        }
                        return write
                            .compose(v -> releasePublishableRatingsForClaim(tx, claimId))
                            .compose(published -> recalculate(tx, claim.getLong("manager_id"))
                                .map(v -> new JsonObject()
                                    .put("claimId", claimId)
                                    .put("fromRoleId", from)
                                    .put("toRoleId", targetRoleId)
                                    .put("alreadyDone", false)
                                    .put("ratingsPublished", published)));
                    });
            });
    }

    /**
     * Merge one employment into another on the same manager.
     *
     * <p><b>This delegates to {@link ManagerMergeService#mergeRoles}. There is deliberately no
     * second merge algorithm here.</b> That one already defers the composite constraints in the
     * right order, redirects claims with the canonicalization audited, marks a contributor's
     * duplicate claim and holds its rating, follows withdrawn ratings through the redirection and
     * reconciles rating opportunities. A moderation-flavoured reimplementation would get some
     * subset of that right and would diverge from the merge the rest of the system trusts.
     *
     * <p>Its own doc said this was phase 4's work: "It is the operation the moderation queues will
     * call in phase 4, and wiring a UI to it now is phase 4's work." This is that wiring.
     */
    public Future<JsonObject> mergeRoles(String auth0Id, long managerId, long keepRoleId,
                                         long supersededRoleId) {
        return requireAdmin(auth0Id).compose(adminId -> {
            if (merges == null) {
                return Future.failedFuture(ServiceException.forbidden("Merging is unavailable."));
            }
            if (keepRoleId == supersededRoleId) {
                return Future.failedFuture(ServiceException.badRequest(
                    "A role cannot be merged into itself."));
            }
            return merges.mergeRoles(managerId, keepRoleId, supersededRoleId, adminId)
                .recover(err -> Future.failedFuture(
                    err instanceof IllegalArgumentException
                        ? ServiceException.badRequest(err.getMessage()) : err));
        });
    }

    // ── conflicts ────────────────────────────────────────────────────────────

    /** Open conflicts, newest decision first, with what each one is holding up. */
    public Future<JsonArray> openConflicts(String auth0Id, int limit, int offset) {
        return requireAdmin(auth0Id).compose(adminId -> openConflictsImpl(limit, offset));
    }

    private Future<JsonArray> openConflictsImpl(int limit, int offset) {
        return db.preparedQuery("""
                SELECT cf.id, cf.kind, cf.manager_id, cf.company_id, cf.manager_name,
                       cf.company_name, cf.title, cf.period, cf.detected_at,
                       cf.blocks_publication, cf.role_id, cf.other_role_id,
                       (SELECT count(*) FROM role_conflict_claims rcc
                         WHERE rcc.conflict_id = cf.id AND rcc.role = 'subject') AS subject_claims,
                       (SELECT count(*) FROM role_conflict_claims rcc
                         WHERE rcc.conflict_id = cf.id AND rcc.role <> 'subject') AS evidence_claims,
                       (SELECT count(*) FROM reviews r
                          JOIN role_conflict_claims rcc ON rcc.claim_id = r.claim_id
                         WHERE rcc.conflict_id = cf.id AND rcc.role = 'subject'
                           AND r.disposition = 'held' AND r.deleted_at IS NULL) AS held_ratings
                  FROM role_conflicts cf
                 WHERE cf.resolved_at IS NULL
                 ORDER BY cf.blocks_publication DESC, cf.detected_at
                 LIMIT $1 OFFSET $2
                """)
            .execute(Tuple.of(limit, offset))
            .map(rs -> {
                JsonArray out = new JsonArray();
                for (Row r : rs) {
                    out.add(new JsonObject()
                        .put("conflictId",  r.getLong("id"))
                        .put("kind",        r.getString("kind"))
                        // The distinction that decides whether a rating is held at all. Being
                        // cited as EVIDENCE about somebody else's claim is not being in doubt,
                        // and treating it as doubt withdrew a rating the merge had kept live.
                        .put("blocksPublication", r.getBoolean("blocks_publication"))
                        .put("managerId",   r.getLong("manager_id"))
                        .put("managerName", r.getString("manager_name"))
                        .put("companyName", r.getString("company_name"))
                        .put("title",       r.getString("title"))
                        .put("period",      r.getString("period"))
                        .put("detectedAt",  iso(r, "detected_at"))
                        .put("roleId",      r.getLong("role_id"))
                        .put("otherRoleId", r.getLong("other_role_id"))
                        .put("subjectClaims",  r.getInteger("subject_claims"))
                        .put("evidenceClaims", r.getInteger("evidence_claims"))
                        .put("heldRatings",    r.getInteger("held_ratings")));
                }
                return out;
            });
    }

    /** One conflict, with every claim attached and its role in the dispute. */
    public Future<JsonObject> conflictDetail(String auth0Id, long conflictId) {
        return requireAdmin(auth0Id).compose(adminId -> conflictDetailImpl(conflictId));
    }

    private Future<JsonObject> conflictDetailImpl(long conflictId) {
        return db.preparedQuery("SELECT * FROM role_conflicts WHERE id = $1")
            .execute(Tuple.of(conflictId))
            .compose(rs -> {
                if (rs.rowCount() == 0) {
                    return Future.failedFuture(ServiceException.notFound(
                        "No such conflict: " + conflictId));
                }
                Row cf = rs.iterator().next();
                return db.preparedQuery("""
                        SELECT rcc.claim_id, rcc.role, rcc.added_at,
                               rc.title, rc.start_date, rc.end_date, rc.period_known,
                               rc.resolved_role_id, rc.resolution,
                               rc.user_id IS NOT NULL AS from_account,
                               -- A WITHDRAWN claim no longer grounds anything, and a dispute whose
                               -- evidence has all been withdrawn is a different decision from one
                               -- whose evidence stands. The row stays visible because the dispute
                               -- was raised on it; what it no longer does is support a status.
                               rc.withdrawn_at IS NOT NULL AS withdrawn,
                               /*
                                 WHAT THE EVIDENCE SAYS NOW, which is the latest statement and not
                                 necessarily the one the claim was created with.

                                 The same CASE as EmploymentStatusResolver's detection, deliberately
                                 rather than COALESCE: COALESCE would fall back to the original
                                 assertion where the latest statement says NULL, and the moderator
                                 would be shown a contradiction nobody is making any more. The
                                 claim's own frozen assertion goes out alongside it, as
                                 assertedStart and assertedEnd, so both are available and neither
                                 is overwritten by the other.
                               */
                               CASE WHEN t.claim_id IS NOT NULL THEN t.still_there
                                    ELSE rc.still_there END AS still_there,
                               CASE WHEN t.claim_id IS NOT NULL THEN t.end_date
                                    ELSE rc.end_date    END AS left_on,
                               t.said_at                     AS said_at,
                               -- FALSE, not NULL, for a claim with no series: nothing was
                               -- restated. Claims predating V111 have no series at all.
                               COALESCE(t.occasion = 'repeat', FALSE) AS restated,
                               (SELECT count(*) FROM reviews r
                                 WHERE r.claim_id = rc.id AND r.disposition = 'held'
                                   AND r.deleted_at IS NULL) AS held_ratings
                          FROM role_conflict_claims rcc
                          JOIN role_claims rc ON rc.id = rcc.claim_id
                          LEFT JOIN LATERAL (
                               SELECT ct.claim_id, ct.still_there, ct.end_date, ct.said_at,
                                      ct.occasion
                                 FROM claim_testimony ct
                                WHERE ct.claim_id = rc.id
                                ORDER BY ct.said_at DESC, ct.id DESC
                                LIMIT 1) t ON TRUE
                         WHERE rcc.conflict_id = $1
                         ORDER BY rcc.role, rcc.added_at
                        """).execute(Tuple.of(conflictId))
                    .map(claims -> {
                        JsonArray arr = new JsonArray();
                        for (Row r : claims) {
                            arr.add(new JsonObject()
                                .put("claimId",       r.getLong("claim_id"))
                                .put("role",          r.getString("role"))
                                .put("assertedTitle", r.getString("title"))
                                .put("assertedStart", r.getLocalDate("start_date") == null
                                                      ? null : r.getLocalDate("start_date").toString())
                                .put("assertedEnd",   r.getLocalDate("end_date") == null
                                                      ? null : r.getLocalDate("end_date").toString())
                                .put("periodKnown",   r.getBoolean("period_known"))
                                .put("resolvedRoleId", r.getLong("resolved_role_id"))
                                .put("resolution",    r.getString("resolution"))
                                .put("fromAccount",   r.getBoolean("from_account"))
                                .put("withdrawn",     r.getBoolean("withdrawn"))
                                // What this contributor says now about whether the manager was
                                // still there when they left, and the date that speaks to.
                                .put("stillThere",    r.getBoolean("still_there"))
                                .put("leftOn",        r.getLocalDate("left_on") == null
                                                      ? null : r.getLocalDate("left_on").toString())
                                .put("saidAt",        iso(r, "said_at"))
                                .put("restated",      r.getBoolean("restated"))
                                .put("heldRatings",   r.getInteger("held_ratings")));
                        }
                        return arr;
                    })
                    .compose(arr -> caseEmployments(cf).map(cases -> new JsonObject()
                            .put("conflictId", conflictId)
                            .put("kind",       cf.getString("kind"))
                            .put("blocksPublication", cf.getBoolean("blocks_publication"))
                            .put("managerId",  cf.getLong("manager_id"))
                            .put("managerName", cf.getString("manager_name"))
                            .put("companyName", cf.getString("company_name"))
                            .put("title",      cf.getString("title"))
                            .put("period",     cf.getString("period"))
                            .put("detectedAt", iso(cf, "detected_at"))
                            .put("resolvedAt", iso(cf, "resolved_at"))
                            .put("resolution", cf.getString("resolution"))
                            .put("note",       cf.getString("note"))
                            .put("roleId",     cf.getLong("role_id"))
                            .put("otherRoleId", cf.getLong("other_role_id"))
                            .put("claims",     arr)
                            // Empty for every other kind, because every other kind decides the
                            // role the queue item names and nothing else.
                            .put("caseEmployments", cases)));
            });
    }

    /**
     * The roles one determination reaches: the case the conflict is keyed on.
     *
     * <p>$1 manager, $2 employer, $3 normalised title, $4 period key - {@code role_conflicts}'
     * own four columns, compared the way {@code case_key} is generated from them.
     *
     * <p><b>One fragment, used by the determination and by the detail a moderator reads before
     * making it.</b> Two copies of this predicate would eventually disagree about which rows a
     * click affects, and then the screen would promise one thing while the write did another.
     */
    private static final String CASE_ROLES = """
             WHERE ch.state = 'accepted'
               AND ch.manager_id IS NOT DISTINCT FROM $1
               AND ch.company_id IS NOT DISTINCT FROM $2
               AND ch.title_key = $3
               AND COALESCE(ch.start_day::text,'unknown') = $4
            """;

    /**
     * Every accepted employment record in the dispute's case, with what each currently says.
     *
     * <p>This exists because of the one way this queue item differs from every other: the
     * determination applies to the whole case, so a moderator about to answer one needs to see
     * that it is about three records and not the one named in the row. {@code willBeDetermined}
     * comes from the SQL rather than from a second judgement in the browser, and is the same
     * {@code employment_status = 'disputed'} the write selects on.
     *
     * <p>Siblings that are NOT disputed are listed too, precisely because the determination leaves
     * them alone. One may already carry somebody else's determination, and a moderator should be
     * able to see that before concluding the case is unanimous.
     *
     * <p>Empty for every other conflict kind: nothing else is keyed on a case, and running this
     * query for a duplicate conflict would return rows no decision there touches.
     */
    private Future<JsonArray> caseEmployments(Row cf) {
        if (!"employment_status_dispute".equals(cf.getString("kind"))) {
            return Future.succeededFuture(new JsonArray());
        }
        return db.preparedQuery("""
                SELECT ch.id, ch.title, ch.start_date, ch.end_date, ch.period_known,
                       ch.employment_status, ch.status_as_of, ch.status_basis,
                       ch.employment_status = 'disputed' AS will_be_determined
                  FROM career_history ch
                """ + CASE_ROLES + " ORDER BY ch.id")
            .execute(Tuple.of(cf.getLong("manager_id"), cf.getLong("company_id"),
                              cf.getString("title_key"), cf.getString("period_key")))
            .map(rs -> {
                JsonArray out = new JsonArray();
                for (Row r : rs) {
                    out.add(new JsonObject()
                        .put("roleId",      r.getLong("id"))
                        .put("title",       r.getString("title"))
                        .put("startDate",   r.getLocalDate("start_date") == null
                                            ? null : r.getLocalDate("start_date").toString())
                        .put("endDate",     r.getLocalDate("end_date") == null
                                            ? null : r.getLocalDate("end_date").toString())
                        .put("periodKnown", r.getBoolean("period_known"))
                        .put("employmentStatus", r.getString("employment_status"))
                        .put("statusAsOf",  iso(r, "status_as_of"))
                        .put("statusBasis", r.getString("status_basis"))
                        .put("willBeDetermined", r.getBoolean("will_be_determined")));
                }
                return out;
            });
    }

    /**
     * Record a decision on a conflict, and release what it was holding.
     *
     * <p><b>No contributor's rating is discarded and no assertion is rewritten.</b> A resolution
     * closes the dispute and, where the hold was the only thing in the way, the ratings become
     * public. Where it was not, they stay held and the response says how many did not move, because
     * a moderator who clicks "resolve" and sees nothing happen deserves to know why.
     *
     * <p>Idempotent: an already-resolved conflict is reported, not re-resolved. Re-resolving would
     * overwrite the first decision's author and time, which is history.
     */
    public Future<JsonObject> resolveConflict(String auth0Id, long conflictId, String resolution, String note) {
        return requireAdmin(auth0Id).compose(adminId -> resolveConflictImpl(conflictId, resolution, note, adminId));
    }

    private Future<JsonObject> resolveConflictImpl(long conflictId, String resolution, String note,
                                              UUID adminId) {
        List<String> allowed = List.of("merged", "kept_separate", "corrected", "dismissed",
                                       "superseded_the_legacy");
        if (!allowed.contains(resolution)) {
            return Future.failedFuture(ServiceException.badRequest(
                "Unknown resolution: " + resolution));
        }
        return db.withTransaction(tx -> tx.preparedQuery(
                "SELECT id, resolved_at, manager_id FROM role_conflicts WHERE id = $1 FOR UPDATE")
            .execute(Tuple.of(conflictId))
            .compose(rs -> {
                if (rs.rowCount() == 0) {
                    return Future.failedFuture(ServiceException.notFound(
                        "No such conflict: " + conflictId));
                }
                Row cf = rs.iterator().next();
                if (cf.getOffsetDateTime("resolved_at") != null) {
                    return Future.succeededFuture(new JsonObject()
                        .put("conflictId", conflictId).put("alreadyDone", true)
                        .put("ratingsPublished", 0).put("ratingsStillHeld", 0));
                }
                return tx.preparedQuery("""
                        UPDATE role_conflicts
                           SET resolved_at = now(), resolution = $2, resolved_by = $3, note = $4
                         WHERE id = $1
                        """).execute(Tuple.of(conflictId, resolution, adminId, note))
                    .compose(v -> releaseRatingsForConflict(tx, conflictId))
                    .compose(counts -> {
                        Long managerId = cf.getLong("manager_id");
                        return (managerId == null
                                ? Future.<Void>succeededFuture()
                                : recalculate(tx, managerId))
                            .map(x -> new JsonObject()
                                .put("conflictId", conflictId)
                                .put("alreadyDone", false)
                                .put("ratingsPublished", counts.getInteger("published"))
                                .put("ratingsStillHeld", counts.getInteger("stillHeld")));
                    });
            }));
    }

    // ── the moderator's determination ───────────────────────────────────────

    /**
     * The statuses a moderator may determine.
     *
     * <p>{@code disputed} is the state being left, not an answer to it, and offering it would let
     * somebody close a queue item by restating the problem.
     */
    private static final List<String> DETERMINABLE = List.of("ongoing", "ended", "unknown");

    /**
     * Answer an employment-status dispute, and take the employment out of {@code disputed}.
     *
     * <h2>Why this is keyed on the CONFLICT and not on a role</h2>
     *
     * <p>{@code role_conflicts} is keyed on the disputed EMPLOYMENT: manager, employer, normalised
     * title and period. {@code career_unique_known_employment} only stops two live roles sharing
     * that key when the period is known, so an unknown-period employment can legitimately be
     * several rows, and detection will have disputed each of them under one queue item.
     *
     * <p>So the determination applies to <b>every disputed role in the case</b>. Resolving the
     * queue item while leaving a sibling row disputed would be the worst of both: the moderator
     * believes they have answered, the task is gone, and a role presents as Unknown for ever with
     * nothing left to point at. Derivation cannot rescue it either, because a disputed role is
     * deliberately inert.
     *
     * <p>Roles in the case that are NOT disputed are left exactly as they are. One may already
     * carry an earlier determination, and silently overwriting somebody else's decision is not
     * this operation's business.
     *
     * <h2>What it writes</h2>
     *
     * <p>Basis {@code moderator}, the evidence date they give, and their identity in
     * {@code status_resolved_by}. This is the one path that names a human, because it is the one
     * where a human decided: everything else in the model is the resolution component acting on a
     * record or an assertion, and naming an admin for those would be a fabrication.
     *
     * <p>The evidence stays on the conflict. {@code status_claim_id} is null because
     * {@code employment_status_claim_basis_pairs} reserves it for a contributor-grounded status,
     * and a determination is not grounded in one claim: the moderator read the whole case, and
     * {@code role_conflict_claims} is what records what they were looking at.
     *
     * <p>Idempotent twice over. An already-resolved conflict reports {@code alreadyDone} rather
     * than failing, because a double-click is not an error; and each role's UPDATE is guarded on
     * the values actually changing, so a repeat writes no row and the trigger logs no event.
     *
     * <p><b>Publication is untouched.</b> An employment-status dispute never held a rating, so
     * there is nothing to release here and no rating changes state. That is not an omission: it
     * is the whole point of the kind being absent from {@code kind_blocks_publication}.
     */
    public Future<JsonObject> determineEmploymentStatus(String auth0Id, long conflictId,
                                                        String status, LocalDate asOf,
                                                        String note) {
        return requireAdmin(auth0Id).compose(adminId ->
            determineEmploymentStatusImpl(conflictId, status, asOf, note, adminId));
    }

    private Future<JsonObject> determineEmploymentStatusImpl(long conflictId, String status,
                                                             LocalDate asOf, String note,
                                                             UUID adminId) {
        if (status == null || !DETERMINABLE.contains(status)) {
            return Future.failedFuture(ServiceException.badRequest(
                "A determination must be one of " + DETERMINABLE + "; got: " + status));
        }
        // An asserting status needs the date its evidence speaks to. This is the same rule
        // employment_status_is_grounded enforces in the database, refused here so the moderator
        // gets a sentence rather than a constraint violation.
        if (!"unknown".equals(status) && asOf == null) {
            return Future.failedFuture(ServiceException.badRequest(
                "A determination of '" + status + "' must say what date the evidence speaks to."));
        }
        // And 'unknown' must not carry one, because it asserts nothing. A date on it would read
        // as "we know nothing, as of March", which is not a fact about anybody's employment.
        if ("unknown".equals(status) && asOf != null) {
            return Future.failedFuture(ServiceException.badRequest(
                "A determination of 'unknown' asserts nothing and cannot carry an evidence date."));
        }
        if (asOf != null && asOf.isAfter(LocalDate.now())) {
            return Future.failedFuture(ServiceException.badRequest(
                "Evidence cannot speak to a future date."));
        }
        if (note == null || note.isBlank()) {
            return Future.failedFuture(ServiceException.badRequest(
                "A determination overrides what contributors said and must record why."));
        }
        final String reason = note.trim();

        return db.withTransaction(tx -> tx.preparedQuery("""
                SELECT id, kind, resolved_at, manager_id, company_id, title_key, period_key
                  FROM role_conflicts WHERE id = $1 FOR UPDATE
                """).execute(Tuple.of(conflictId))
            .compose(rs -> {
                if (rs.rowCount() == 0) {
                    return Future.failedFuture(ServiceException.notFound(
                        "No such conflict: " + conflictId));
                }
                Row cf = rs.iterator().next();
                if (!"employment_status_dispute".equals(cf.getString("kind"))) {
                    return Future.failedFuture(ServiceException.badRequest(
                        "Conflict " + conflictId + " is a " + cf.getString("kind")
                        + ", not an employment-status dispute. Use the conflict resolution "
                        + "endpoint for that."));
                }
                if (cf.getOffsetDateTime("resolved_at") != null) {
                    return Future.succeededFuture(new JsonObject()
                        .put("conflictId", conflictId).put("alreadyDone", true)
                        .put("rolesDetermined", 0));
                }
                return determineAcrossTheCase(tx, cf, status, asOf, adminId)
                    .compose(determined -> tx.preparedQuery("""
                            UPDATE role_conflicts
                               SET resolved_at = now(), resolution = 'status_determined',
                                   resolved_by = $2, note = $3
                             WHERE id = $1 AND resolved_at IS NULL
                            """).execute(Tuple.of(conflictId, adminId, reason))
                        .map(v -> new JsonObject()
                            .put("conflictId", conflictId)
                            .put("alreadyDone", false)
                            .put("status", status)
                            .put("asOf", asOf == null ? null : asOf.toString())
                            .put("rolesDetermined", determined)));
            }));
    }

    /**
     * Apply one determination to every disputed role under the conflict's case.
     *
     * <p>Locked in id order, so two moderators answering overlapping cases at the same time take
     * the same locks in the same sequence rather than deadlocking against each other.
     */
    private Future<Integer> determineAcrossTheCase(SqlConnection tx, Row conflict, String status,
                                                    LocalDate asOf, UUID adminId) {
        return tx.preparedQuery("SELECT ch.id FROM career_history ch\n" + CASE_ROLES
                + "   AND ch.employment_status = 'disputed' ORDER BY ch.id FOR UPDATE")
            .execute(Tuple.of(conflict.getLong("manager_id"), conflict.getLong("company_id"),
                              conflict.getString("title_key"), conflict.getString("period_key")))
            .compose(rows -> {
                List<Long> roleIds = new ArrayList<>();
                for (Row r : rows) roleIds.add(r.getLong("id"));
                Future<Integer> chain = Future.succeededFuture(0);
                for (Long roleId : roleIds) {
                    chain = chain.compose(sofar -> tx.preparedQuery("""
                            UPDATE career_history ch
                               SET employment_status  = $3,
                                   status_basis       = 'moderator',
                                   status_as_of       = v.as_of,
                                   status_claim_id    = NULL,
                                   status_resolved_at = now(),
                                   status_resolved_by = $4,
                                   status_verified_at = NULL,
                                   updated_at         = now()
                              FROM (SELECT ($2::date)::timestamp AT TIME ZONE 'UTC' AS as_of) v
                             -- No guard on the values changing, deliberately. The selection above
                             -- takes only rows whose status is 'disputed' and holds their locks,
                             -- and no determination is 'disputed', so a selected row always
                             -- changes. A guard here would read as protection while being
                             -- unreachable, which a mutation run proved: removing it broke
                             -- nothing. Idempotency lives where it is reachable, on the conflict's
                             -- resolved_at.
                             WHERE ch.id = $1
                            """)
                        .execute(Tuple.of(roleId, asOf).addValue(status).addValue(adminId))
                        .map(res -> sofar + res.rowCount()));
                }
                return chain;
            });
    }

    // ── held ratings ─────────────────────────────────────────────────────────

    /**
     * Every held rating, why it is held, and what would release it.
     *
     * <p>The waiting time is reported because it is the number that makes neglect visible. A
     * rating held for six weeks is somebody's contribution that nobody has looked at, and a count
     * alone hides that.
     */
    public Future<JsonArray> heldRatings(String auth0Id, int limit, int offset) {
        return heldRatings(auth0Id, limit, offset, null);
    }

    /**
     * The same queue, optionally narrowed to one manager.
     *
     * <p>{@code managerId} is what lets the manager's own profile carry this section inline. The
     * queue and the profile must answer from ONE query or they will disagree: a moderator who sees
     * "nothing is blocking it any more" on the profile and a different reason in the queue has no
     * way to tell which is current, and the reason is computed, not stored.
     */
    public Future<JsonArray> heldRatings(String auth0Id, int limit, int offset, Long managerId) {
        return requireAdmin(auth0Id).compose(adminId -> heldRatingsImpl(limit, offset, managerId));
    }

    private Future<JsonArray> heldRatingsImpl(int limit, int offset) {
        return heldRatingsImpl(limit, offset, null);
    }

    private Future<JsonArray> heldRatingsImpl(int limit, int offset, Long managerId) {
        return db.preparedQuery("""
                SELECT r.id, r.created_at, r.manager_id, r.manager_title, r.manager_company,
                       r.overall_rating, r.role_basis,
                       m.name AS manager_name, m.slug AS manager_slug,
                       rc.id  AS claim_id, rc.resolution,
                       ch.id  AS role_id, ch.state AS role_state, ch.title AS role_title,
                       rating_role_is_publishable(r.id) AS would_publish,
                       (SELECT cf.id FROM role_conflict_claims rcc
                          JOIN role_conflicts cf ON cf.id = rcc.conflict_id
                         WHERE rcc.claim_id = rc.id AND rcc.role = 'subject'
                           AND cf.blocks_publication AND cf.resolved_at IS NULL
                         LIMIT 1) AS blocking_conflict_id,
                       (SELECT cf.kind FROM role_conflict_claims rcc
                          JOIN role_conflicts cf ON cf.id = rcc.conflict_id
                         WHERE rcc.claim_id = rc.id AND rcc.role = 'subject'
                           AND cf.blocks_publication AND cf.resolved_at IS NULL
                         LIMIT 1) AS blocking_conflict_kind
                  FROM reviews r
                  JOIN managers m ON m.id = r.manager_id
                  LEFT JOIN role_claims rc ON rc.id = r.claim_id
                  -- BOTH ROUTES, as everywhere else here. A withdrawn rating has no claim and
                  -- keeps withdrawn_role_id instead, so joining only through the claim reported
                  -- "no employment claim and no employment record" and told the moderator nothing
                  -- here could help - when accepting the employment it kept is exactly what
                  -- releases it.
                  LEFT JOIN career_history ch
                         ON ch.id = COALESCE(rc.resolved_role_id, r.withdrawn_role_id)
                 WHERE r.disposition = 'held' AND r.deleted_at IS NULL
                   AND ($3::bigint IS NULL OR r.manager_id = $3)
                 ORDER BY r.created_at
                 LIMIT $1 OFFSET $2
                """)
            .execute(Tuple.of(limit, offset, managerId))
            .map(rs -> {
                JsonArray out = new JsonArray();
                for (Row r : rs) out.add(heldRatingJson(r));
                return out;
            });
    }

    private JsonObject heldRatingJson(Row r) {
        Long conflictId = r.getLong("blocking_conflict_id");
        String roleState = r.getString("role_state");
        String resolution = r.getString("resolution");

        // WHY, and WHAT TO DO, in the order the publication predicate checks them, so the reason
        // given matches the reason the database would give. Reporting a second-order cause sends a
        // moderator to the wrong screen, which is the failure the one-function-answers-why rule in
        // V103 exists to prevent.
        String why;
        String action;
        if ("legacy".equals(r.getString("role_basis"))) {
            // A legacy rating is exempt from the role requirement entirely, so nothing in these
            // queues is holding it. Saying "accept the employment" would send a moderator to a
            // screen that cannot help.
            why = "It predates employment claims, so no employment is holding it.";
            action = "Nothing here can release it; it is held for another reason.";
        } else if (r.getLong("claim_id") == null && r.getLong("role_id") == null) {
            why = "It has no employment claim and no employment record.";
            action = "Nothing here can release it; it needs looking at directly.";
        } else if (r.getLong("claim_id") == null) {
            // A withdrawn rating: no claim, but it kept the employment it was attributed to.
            why = "The employment it kept is " + roleState + ".";
            action = "accepted".equals(roleState)
                ? "Nothing; check for an open conflict."
                : "Accept that employment.";
        } else if (resolution == null) {
            why = "Its employment claim has not been resolved yet.";
            action = "Resolve the claim, or link it to an existing employment.";
        } else if (!List.of("linked", "created").contains(resolution)) {
            why = "Its employment claim was resolved as " + resolution + ".";
            action = "Link the claim to an employment to release it.";
        } else if (r.getLong("role_id") == null) {
            why = "Its claim resolves to no employment record.";
            action = "Link the claim to an employment.";
        } else if (!"accepted".equals(roleState)) {
            why = "The employment it names is " + roleState + ".";
            // A rejected or superseded employment will never be accepted, so the only route is to
            // point the claim at one that exists. Telling a moderator to "accept" a rejected row
            // sends them to a button the service refuses.
            action = "proposed".equals(roleState)
                ? "Accept the employment."
                : "Link the claim to a different employment.";
        } else if (conflictId != null) {
            why = "It is the subject of an open " + r.getString("blocking_conflict_kind")
                  + " conflict.";
            action = "Resolve that conflict.";
        } else {
            // The predicate says publishable while the row is still held: a moderation action
            // elsewhere has already cleared the way and nothing has released it yet.
            why = "Nothing is blocking it any more.";
            action = "It can be published now.";
        }

        return new JsonObject()
            .put("reviewId",      r.getUUID("id").toString())
            .put("submittedAt",   iso(r, "created_at"))
            .put("managerId",     r.getLong("manager_id"))
            .put("managerName",   r.getString("manager_name"))
            .put("managerSlug",   r.getString("manager_slug"))
            .put("title",         r.getString("manager_title"))
            .put("company",       r.getString("manager_company"))
            .put("overallRating", r.getBigDecimal("overall_rating"))
            .put("claimId",       r.getLong("claim_id"))
            .put("roleId",        r.getLong("role_id"))
            .put("roleState",     roleState)
            .put("roleTitle",     r.getString("role_title"))
            .put("blockingConflictId",   conflictId)
            .put("blockingConflictKind", r.getString("blocking_conflict_kind"))
            .put("whyHeld",       why)
            .put("actionToRelease", action)
            // The database's own answer, so a moderator is never promised a release the predicate
            // would refuse.
            .put("wouldPublishNow", r.getBoolean("would_publish"));
    }


    /**
     * Publishes ONE held rating, and only if the database says it may be.
     *
     * <p><b>Why this had to exist.</b> The held-ratings queue already computed a last branch
     * reading "Nothing is blocking it any more." / "It can be published now.", and nothing could
     * do it. Every release until now was a side effect of some other decision - accepting an
     * employment, linking a claim, resolving a conflict - so a rating whose obstacle had been
     * cleared by one of those, without the release running or the rating being in its scope, sat
     * held indefinitely while the queue told a moderator it was ready. That branch is not
     * hypothetical: {@code releaseRatingsForConflict} reports {@code stillHeld} for exactly the
     * ratings it could not take, and a later action can clear what was blocking them.
     *
     * <p><b>{@code rating_role_is_publishable} is the only authority, and it is asked inside the
     * transaction.</b> The rule is never restated here. Asking before opening the transaction
     * would be asking a question whose answer another moderator can change in the gap, and the
     * deferred constraints would then abort the commit with a database message instead of this
     * method's explanation.
     *
     * <p>Refuses with 409 and points at the queue rather than restating its reason. The reason is
     * derived by {@code heldRatingJson} from several joins this statement does not make, and
     * computing a second, shorter version of it here is how the two come to disagree. 409 rather
     * than letting the write through: the deferred role assertion would otherwise abort the commit
     * with a constraint name, which tells a moderator nothing they can act on.
     */
    public Future<JsonObject> publishHeldRating(String auth0Id, UUID reviewId) {
        return requireAdmin(auth0Id).compose(adminId -> db.withTransaction(tx -> tx
            .preparedQuery("""
                SELECT r.id, r.manager_id, r.disposition, r.deleted_at,
                       rating_role_is_publishable(r.id) AS ok
                  FROM reviews r
                 WHERE r.id = $1
                 FOR UPDATE OF r
                """)
            .execute(Tuple.of(reviewId))
            .compose(rs -> {
                if (!rs.iterator().hasNext()) {
                    return Future.failedFuture(ServiceException.notFound("Rating not found"));
                }
                Row row = rs.iterator().next();
                if (row.getOffsetDateTime("deleted_at") != null) {
                    return Future.failedFuture(ServiceException.notFound("Rating not found"));
                }
                if (!"held".equals(row.getString("disposition"))) {
                    // Not an error worth a 409's ceremony either way, but it must not report
                    // success: two moderators on the same queue is the ordinary case, and
                    // "published" for a rating this call did not publish is a lie in an audit log.
                    return Future.failedFuture(ServiceException.conflict(
                        "That rating is already " + row.getString("disposition") + "."));
                }
                if (!Boolean.TRUE.equals(row.getBoolean("ok"))) {
                    return Future.failedFuture(ServiceException.conflict(
                        "That rating cannot be published yet. Something is still holding it; "
                      + "the held-ratings list says what."));
                }
                long managerId = row.getLong("manager_id");
                return dispositions.set(tx, reviewId, "live")
                    // The manager cache, and through the V89 trigger on managers every company and
                    // industry figure that reads it. A publish that moved no number would leave
                    // the rating visible in the list and absent from the average above it.
                    .compose(v -> recalculate(tx, managerId))
                    .map(v -> new JsonObject()
                        .put("reviewId",  reviewId.toString())
                        .put("managerId", managerId)
                        .put("published", true));
            })));
    }

    // ── shared internals ─────────────────────────────────────────────────────

    /**
     * The canonical lock order: manager, then the role.
     *
     * <p>Not the other way round. A moderator accepting a role while a merge of the same manager
     * runs has to serialise with it, and two transactions taking the same two rows in opposite
     * orders is the definition of a deadlock.
     */
    // ── corrections, history and reopening ──────────────────────────────────

    /**
     * What a correction may change, with PRESENCE separate from value.
     *
     * <p>A null end date is a real correction - "no recorded end date" - and is not the same as
     * "leave the end date alone", so a plain nullable field cannot express both. Each pair is a
     * given flag and a value, so the service never has to guess which was meant.
     *
     * <p>company_id is absent on purpose. {@code reviews_withdrawn_role_is_the_managers} ties
     * (withdrawn_role_id, manager_id) to career_history without company_id, so changing a role's
     * employer would silently re-attribute any rating held against it with nothing objecting.
     * Employer changes belong to the supersession design.
     */
    public record Correction(boolean titleGiven, String title,
                             boolean startGiven, LocalDate startDate,
                             boolean endGiven, LocalDate endDate,
                             boolean periodKnownGiven, boolean periodKnown) {

        public boolean isEmpty() {
            return !titleGiven && !startGiven && !endGiven && !periodKnownGiven;
        }
    }

    /** Correct a canonical employment, leaving every contributor's assertion untouched. */
    public Future<JsonObject> correctEmployment(String auth0Id, long roleId,
                                                Correction patch, String reason) {
        return requireAdmin(auth0Id)
            .compose(adminId -> correctEmploymentImpl(roleId, patch, reason, adminId, false));
    }

    /**
     * Correct and accept in ONE transaction.
     *
     * <p>Two calls would leave a window in which the employment is live with the values the
     * moderator had just decided were wrong, and a failed accept would strand a correction that
     * nothing had agreed to. The point of the workflow is that a legitimate employment is not
     * rejected merely because the contributor mistyped a date.
     */
    public Future<JsonObject> correctAndAcceptEmployment(String auth0Id, long roleId,
                                                         Correction patch, String reason) {
        return requireAdmin(auth0Id)
            .compose(adminId -> correctEmploymentImpl(roleId, patch, reason, adminId, true));
    }

    private Future<JsonObject> correctEmploymentImpl(long roleId, Correction patch, String reason,
                                                     UUID adminId, boolean thenAccept) {
        if (reason == null || reason.isBlank()) {
            return Future.failedFuture(ServiceException.badRequest(
                "A correction needs a reason: the history has to say why, not only what."));
        }
        if (patch.isEmpty() && !thenAccept) {
            return Future.failedFuture(ServiceException.badRequest(
                "Nothing was changed, so there is nothing to record."));
        }
        return db.withTransaction(tx -> markAdminDecision(tx)
            .compose(v -> lockRoleForCorrection(tx, roleId))
            .compose(role -> {
                String state = role.getString("state");
                if (!"proposed".equals(state) && !"accepted".equals(state)) {
                    return Future.failedFuture(ServiceException.badRequest(
                        "Only a proposed or accepted employment can be corrected; this one is "
                        + state + "."));
                }
                if (thenAccept && !"proposed".equals(state) && !"accepted".equals(state)) {
                    return Future.failedFuture(ServiceException.badRequest(
                        "That employment cannot be accepted; it is " + state + "."));
                }
                List<String[]> changes = diff(role, patch);
                String problem = validate(role, patch);
                if (problem != null) {
                    return Future.failedFuture(ServiceException.badRequest(problem));
                }
                if (changes.isEmpty()) {
                    return finishCorrection(tx, role, roleId, 0, thenAccept, state);
                }
                return applyCorrection(tx, roleId, patch)
                    .compose(v -> recordCorrections(tx, roleId, changes, reason, adminId))
                    .compose(v -> finishCorrection(tx, role, roleId, changes.size(),
                                                   thenAccept, state))
                    /*
                      career_unique_known_employment is
                      (manager_id, company_id, title_key, start_day) WHERE period_known AND
                      state IN ('proposed','accepted'). A corrected title or start day can land on
                      an employment that already exists, and the database is right to refuse: on
                      those four columns they ARE one employment. Translated here rather than left
                      to surface as a driver error, and it names the merge as the way out.
                    */
                    .recover(err -> collision(err)
                        ? Future.failedFuture(ServiceException.conflict(
                            "That correction would duplicate an employment this manager already "
                            + "has at the same company, with the same title and start date. Merge "
                            + "the two instead."))
                        : Future.failedFuture(err));
            }));
    }

    /**
     * The prior values, read UNDER the lock.
     *
     * <p>Not from anything the browser sent: an audit whose "was" came from a stale form records
     * a change that never happened, which is worse than recording nothing.
     */
    private Future<Row> lockRoleForCorrection(SqlConnection tx, long roleId) {
        return tx.preparedQuery("""
                SELECT m.id FROM managers m
                 WHERE m.id = (SELECT manager_id FROM career_history WHERE id = $1)
                 FOR UPDATE
                """).execute(Tuple.of(roleId))
            .compose(locked -> {
                if (locked.rowCount() == 0) {
                    return Future.failedFuture(ServiceException.notFound(
                        "No such employment: " + roleId));
                }
                return tx.preparedQuery("""
                        SELECT id, manager_id, company_id, state, title,
                               (start_date AT TIME ZONE 'UTC')::date AS start_day,
                               (end_date   AT TIME ZONE 'UTC')::date AS end_day,
                               period_known
                          FROM career_history WHERE id = $1 FOR UPDATE
                        """).execute(Tuple.of(roleId))
                    .map(rs -> rs.iterator().next());
            });
    }

    /** Field, was, now: one entry per column that actually changes. */
    private List<String[]> diff(Row role, Correction patch) {
        List<String[]> out = new java.util.ArrayList<>();
        if (patch.titleGiven()) {
            String was = role.getString("title");
            if (!java.util.Objects.equals(was, patch.title())) {
                out.add(new String[] { "title", was, patch.title() });
            }
        }
        if (patch.startGiven()) {
            String was = dayText(role, "start_day");
            String now = patch.startDate() == null ? null : patch.startDate().toString();
            if (!java.util.Objects.equals(was, now)) {
                out.add(new String[] { "start_date", was, now });
            }
        }
        if (patch.endGiven()) {
            String was = dayText(role, "end_day");
            String now = patch.endDate() == null ? null : patch.endDate().toString();
            if (!java.util.Objects.equals(was, now)) {
                out.add(new String[] { "end_date", was, now });
            }
        }
        if (patch.periodKnownGiven()) {
            boolean was = role.getBoolean("period_known");
            if (was != patch.periodKnown()) {
                out.add(new String[] { "period_known",
                                       String.valueOf(was), String.valueOf(patch.periodKnown()) });
            }
        }
        return out;
    }

    /**
     * What the database would refuse anyway, refused here with something a reader can act on.
     *
     * <p>UNKNOWN DATES AND AN OPEN-ENDED EMPLOYMENT ARE DIFFERENT THINGS and stay that way:
     * period_known FALSE means nobody knows when this was, while period_known TRUE with no end
     * date means it is on the record as still running. There is deliberately no "currently
     * employed" flag, because the platform has no verified statement that anyone works anywhere
     * today - employment_status in the design is not implemented, and role_claims.still_there is
     * one contributor's account of when THEY left.
     */
    private String validate(Row role, Correction patch) {
        LocalDate start = patch.startGiven() ? patch.startDate() : day(role, "start_day");
        LocalDate end   = patch.endGiven()   ? patch.endDate()   : day(role, "end_day");
        boolean known   = patch.periodKnownGiven() ? patch.periodKnown()
                                                   : role.getBoolean("period_known");
        if (known && start == null) {
            return "A known employment period needs a start date. Leave the period unknown "
                 + "instead if the dates are not established.";
        }
        if (start != null && end != null && end.isBefore(start)) {
            return "The employment cannot end before it starts.";
        }
        if (end != null && !known) {
            return "An unknown period cannot carry an end date. Either record the dates or "
                 + "clear them.";
        }
        return null;
    }

    private Future<Void> applyCorrection(SqlConnection tx, long roleId, Correction patch) {
        List<String> sets = new java.util.ArrayList<>();
        List<Object> args = new java.util.ArrayList<>();
        args.add(roleId);
        if (patch.titleGiven()) {
            sets.add("title = $" + (args.size() + 1));
            args.add(patch.title());
        }
        if (patch.startGiven()) {
            sets.add("start_date = ($" + (args.size() + 1) + "::date)::timestamp AT TIME ZONE 'UTC'");
            args.add(patch.startDate());
        }
        if (patch.endGiven()) {
            sets.add("end_date = ($" + (args.size() + 1) + "::date)::timestamp AT TIME ZONE 'UTC'");
            args.add(patch.endDate());
        }
        if (patch.periodKnownGiven()) {
            sets.add("period_known = $" + (args.size() + 1));
            args.add(patch.periodKnown());
        }
        if (sets.isEmpty()) return Future.succeededFuture();
        return tx.preparedQuery("UPDATE career_history SET " + String.join(", ", sets)
                                + ", updated_at = now() WHERE id = $1")
            .execute(Tuple.tuple(args)).mapEmpty();
    }

    private Future<Void> recordCorrections(SqlConnection tx, long roleId, List<String[]> changes,
                                           String reason, UUID adminId) {
        List<Tuple> rows = new java.util.ArrayList<>();
        for (String[] c : changes) {
            rows.add(Tuple.of(roleId, c[0], c[1], c[2], reason.trim(), adminId));
        }
        return tx.preparedQuery("""
                INSERT INTO role_corrections (role_id, field, was, now_is, reason, corrected_by)
                VALUES ($1,$2,$3,$4,$5,$6)
                """).executeBatch(rows).mapEmpty();
    }

    /**
     * Accept if asked, then ask the DATABASE which ratings may go public.
     *
     * <p>Never a rule restated here: {@code rating_role_is_publishable} is the only authority, so a
     * rating also held by an open blocking conflict stays held even though its employment is now
     * accepted and correct.
     */
    private Future<JsonObject> finishCorrection(SqlConnection tx, Row role, long roleId,
                                                int fieldsChanged, boolean thenAccept,
                                                String stateBefore) {
        Future<String> state = thenAccept && "proposed".equals(stateBefore)
            ? tx.preparedQuery("UPDATE career_history SET state = 'accepted', updated_at = now() "
                               + "WHERE id = $1")
                .execute(Tuple.of(roleId)).map(v -> "accepted")
            : Future.succeededFuture(stateBefore);
        /*
          A CORRECTION CAN CHANGE THE EVIDENCE, which is why this resolves even when the role was
          already accepted and nothing was accepted here. applyPatch may set an end date, change
          one, or clear one, and the stored status follows the record: clearing an end date must
          take the row back off 'ended' rather than leaving it asserting an employment that the
          correction just said did not finish.
        */
        return state.compose(newState -> EmploymentStatusResolver.resolveRole(tx, roleId)
            .compose(ignored -> releasePublishableRatings(tx, roleId))
            .compose(published -> recalculate(tx, role.getLong("manager_id"))
                .map(v -> new JsonObject()
                    .put("roleId", roleId)
                    .put("state", newState)
                    .put("fieldsCorrected", fieldsChanged)
                    .put("accepted", thenAccept)
                    .put("ratingsPublished", published))));
    }

    /**
     * Reopen a rejected employment as a FRESH proposal.
     *
     * <p>No new transition and no schema change: V99 added {@code reopened_from} for exactly this,
     * and {@code career_unique_known_employment} deliberately excludes rejected rows so "a rejected
     * record must not occupy the key and silently block a fresh proposal for the same job". The
     * rejection stays on the record and the new row says what it came from.
     *
     * <p>NOTHING IS REPUBLISHED. The new row carries no claims, so no rating is attached to it and
     * {@code rating_role_is_publishable} has nothing to say yes to. Releasing the ratings held
     * against the old rejection is a separate, deliberate act: linking each claim to the new
     * employment.
     */
    public Future<JsonObject> reopenRejectedEmployment(String auth0Id, long roleId, String reason) {
        return requireAdmin(auth0Id).compose(adminId -> {
            if (reason == null || reason.isBlank()) {
                return Future.failedFuture(ServiceException.badRequest(
                    "Reopening a rejection needs a reason."));
            }
            return db.withTransaction(tx -> markAdminDecision(tx)
                .compose(v -> lockRoleForCorrection(tx, roleId))
                .compose(role -> {
                    if (!"rejected".equals(role.getString("state"))) {
                        return Future.failedFuture(ServiceException.badRequest(
                            "Only a rejected employment can be reopened; this one is "
                            + role.getString("state") + "."));
                    }
                    return tx.preparedQuery("""
                            INSERT INTO career_history
                                   (manager_id, company, title, company_id, start_date, end_date,
                                    period_known, state, source, reopened_from)
                            SELECT manager_id, company, title, company_id, start_date, end_date,
                                   period_known, 'proposed', source, id
                              FROM career_history WHERE id = $1
                            RETURNING id
                            """).execute(Tuple.of(roleId))
                        .map(rs -> rs.iterator().next().getLong("id"))
                        /*
                          THE WORK THAT IS LEFT, returned with the new row.
                          Creating the replacement is half the job: the claims and held ratings
                          are still attached to the rejection, and nothing moves them. Handing
                          them back means the moderator can reconcile each one through
                          linkClaimToRole without going to SQL to find out what is outstanding.
                          Not transferred automatically, because which claims belong to the
                          reopened employment is a judgement, and moving them would publish
                          ratings nobody had agreed to publish.
                        */
                        .compose(fresh -> claimsFor(roleId)
                            .compose(claims -> heldRatingsForRole(roleId)
                                .compose(held -> recalculate(tx, role.getLong("manager_id"))
                                    .map(v -> new JsonObject()
                                        .put("reopenedFrom", roleId)
                                        .put("roleId", fresh)
                                        .put("state", "proposed")
                                        .put("ratingsPublished", 0)
                                        .put("claimsToReconcile", claims)
                                        .put("heldRatings", held)))))
                        .recover(err -> collision(err)
                            ? Future.failedFuture(ServiceException.conflict(
                                "This manager already has a live employment at that company with "
                                + "the same title and start date, so reopening would duplicate it."))
                            : Future.failedFuture(err));
                }));
        });
    }

    /**
     * Everything that has happened to this employment, in one timeline.
     *
     * <p>Six audit tables are written today and none was readable through any endpoint: a moderator
     * could act and then had no way to review what had been done. Unified rather than six lists
     * because the question is "what happened to this job", and the answer is chronological.
     *
     * <p>NO CONTRIBUTOR IDENTITY LEAVES THIS. The actor on every row is an administrator, resolved
     * to a username, and no claim's user_id is selected at all. An erased actor reads as withdrawn
     * rather than as absent, so "nobody did this" is never what the history appears to say.
     */
    public Future<JsonObject> employmentHistory(String auth0Id, long roleId,
                                                String cursor, int limit) {
        return requireAdmin(auth0Id)
            .compose(adminId -> employmentHistoryImpl(roleId, cursor, limit));
    }

    /** One page of a timeline, with the cursor to the next and an honest account of both bounds. */
    private Future<JsonObject> employmentHistoryImpl(long roleId, String rawCursor, int limit) {
        Cursor cursor;
        try {
            cursor = Cursor.parse(rawCursor);
        } catch (RuntimeException bogus) {
            return Future.failedFuture(ServiceException.badRequest(
                "That history cursor is not one this endpoint issued."));
        }
        int page = Math.max(1, Math.min(limit, 200));
        return db.preparedQuery("""
                WITH RECURSIVE scope AS (
                  /*
                    LINEAGE FOLLOWS A MERGE. A corrected employment can later be absorbed, and its
                    corrections must not vanish from the timeline because the moderator is now
                    looking at the survivor. superseded_by points from the absorbed row to the one
                    that replaced it, so walking it backwards collects everything that became this
                    employment, however many merges deep and however the chain branches.

                    CYCLE, NOT A DEPTH LIMIT. A bound like "depth < 16" stops infinite recursion
                    and lies while doing it: a legitimate chain of seventeen merges is silently
                    truncated and the lineage still presents itself as complete, which is worse
                    than refusing to answer. SQL:1999 cycle detection stops at the first repeat
                    instead, flags it, and traverses every valid row - so the only lineage that is
                    ever incomplete is a genuinely cyclic one, and the response says so.
                  */
                  SELECT id FROM career_history WHERE id = $1
                  UNION ALL
                  SELECT ch.id
                    FROM career_history ch JOIN scope s ON ch.superseded_by = s.id
                ) CYCLE id SET is_cycle USING cycle_path
                , roles AS (
                  SELECT DISTINCT id FROM scope WHERE NOT is_cycle
                ), meta AS (
                  SELECT COALESCE(bool_or(is_cycle), FALSE) AS cyclic,
                         (SELECT count(*) FROM roles) AS roles_in_scope
                    FROM scope
                ), claims AS (
                  SELECT rc.id, COALESCE(rc.resolved_role_id, rc.selected_role_id) AS role_id
                    FROM role_claims rc
                   WHERE rc.resolved_role_id IN (SELECT id FROM roles)
                      OR rc.selected_role_id IN (SELECT id FROM roles)
                ), ctx AS (
                  SELECT manager_id, company_id FROM career_history WHERE id = $1
                ), events AS (
                  -- An administrator's determination about the canonical employment.
                  SELECT 'role_correction' AS kind, rc.id::text AS source_id, rc.role_id,
                         rc.corrected_at AS at, rc.field AS what, rc.was, rc.now_is, rc.reason,
                         CASE WHEN rc.actor_withdrawn THEN 'withdrawn' ELSE 'admin' END
                           AS actor_kind,
                         rc.corrected_by AS actor, NULL::text AS detail
                    FROM role_corrections rc WHERE rc.role_id IN (SELECT id FROM roles)
                  UNION ALL
                  -- An administrator's determination about one contributor's claim.
                  SELECT 'claim_correction', cc.id::text, c.role_id, cc.corrected_at, cc.field, cc.was,
                         cc.now_is, cc.reason,
                         CASE WHEN cc.actor_withdrawn THEN 'withdrawn' ELSE 'admin' END,
                         cc.corrected_by, 'claim ' || cc.claim_id
                    FROM claim_corrections cc JOIN claims c ON c.id = cc.claim_id
                  UNION ALL
                  /*
                    A NULL actor here is the record, not a gap: record_claim_resolution() writes
                    resolved_by or canonicalized_by, both administrators, and sets reason
                    'automatic' or 'manager_merge' when nobody decided. Reporting that as an
                    unknown or withdrawn person would invent a human who never existed.
                  */
                  SELECT 'claim_resolution', e.id::text, c.role_id, e.at, e.event, NULL, e.resolution,
                         e.reason,
                         CASE WHEN e."by" IS NULL THEN 'platform' ELSE 'admin' END,
                         e."by", 'claim ' || e.claim_id
                    FROM claim_resolution_events e JOIN claims c ON c.id = e.claim_id
                  UNION ALL
                  SELECT 'claim_resolution', e.id::text, e.to_role_id, e.at, e.event, NULL, e.resolution,
                         e.reason,
                         CASE WHEN e."by" IS NULL THEN 'platform' ELSE 'admin' END,
                         e."by", NULL
                    FROM claim_resolution_events e
                   WHERE (e.from_role_id IN (SELECT id FROM roles)
                          OR e.to_role_id IN (SELECT id FROM roles))
                     AND e.claim_id NOT IN (SELECT id FROM claims)
                  UNION ALL
                  SELECT 'manager_merge', me.id::text, NULL::bigint, me.merged_at, 'merge',
                         me.absorbed_name, me.survivor_name, NULL,
                         CASE WHEN me.actor_withdrawn THEN 'withdrawn' ELSE 'admin' END,
                         me.merged_by,
                         'roles moved ' || me.roles_moved || ', superseded ' || me.roles_superseded
                    FROM manager_merge_events me, ctx
                   WHERE me.survivor_id = ctx.manager_id OR me.absorbed_id = ctx.manager_id
                  UNION ALL
                  /*
                    THE ACTOR TYPE DIFFERS BY CAUSE. supersession_author_reclaimed constrains
                    'author_reclaimed' to by IS NULL AND claim_id IS NULL deliberately: "the cause
                    already says the actor was the author, so recording who adds nothing except
                    the link that must not survive". So that cause reads as the author, with no
                    identity to resolve, and never as an administrator or a missing one.
                  */
                  SELECT 'review_supersession', s.id::text, c.role_id, s.at, s.cause, NULL, NULL, NULL,
                         CASE WHEN s.cause = 'author_reclaimed' THEN 'author'
                              WHEN s."by" IS NULL                THEN 'platform'
                              ELSE 'admin' END,
                         s."by", 'rating superseded'
                    FROM review_supersessions s JOIN claims c ON c.id = s.claim_id
                  UNION ALL
                  SELECT 'company_merge', cm.id::text, NULL::bigint, cm.merged_at, 'company merge',
                         NULL, NULL, NULL,
                         CASE WHEN cm.actor_withdrawn THEN 'withdrawn' ELSE 'admin' END,
                         cm.merged_by, cm.status
                    FROM company_merges cm, ctx
                   WHERE cm.source_company_id = ctx.company_id
                      OR cm.target_company_id = ctx.company_id
                  UNION ALL
                  -- An unresolved conflict was opened by the sweep, with nobody deciding yet.
                  SELECT 'conflict', c.id::text, c.role_id, c.detected_at, c.kind, NULL, c.resolution,
                         c.note,
                         CASE WHEN c.resolved_by IS NULL THEN 'platform' ELSE 'admin' END,
                         c.resolved_by, NULL
                    FROM role_conflicts c
                   WHERE c.role_id IN (SELECT id FROM roles)
                      OR c.other_role_id IN (SELECT id FROM roles)
                )
                SELECT m.cyclic, m.roles_in_scope,
                       e.kind, e.source_id, e.role_id, e.at, e.what, e.was, e.now_is, e.reason,
                       e.actor_kind, e.detail,
                       CASE WHEN e.actor_kind = 'admin' THEN u.username END AS actor_name
                  /*
                    meta has exactly one row and the join is LEFT, so the completeness answer
                    survives an employment with no history at all. Those rows arrive with a null
                    kind and are skipped.

                      KEYSET, NOT OFFSET. Every one of these seven timestamps is NOT NULL, so
                    (at, kind, source_id) is a total order and a cursor over it cannot skip or
                    repeat a row while new events arrive. An OFFSET would do both. The tiebreakers
                    are what make it deterministic: timestamps collide readily, because one
                    correction writes a row per field inside a single transaction and they all
                    share now().

                    source_id IS TEXT because these keys are not one type: six of the seven
                    tables use BIGINT and company_merges.id is UUID, so a UNION ALL over the raw
                    columns does not even execute. Text sorts lexicographically rather than
                    numerically, which is fine - the column is a tiebreaker for deterministic
                    paging, not a thing anybody reads in order.

                    NOT A SNAPSHOT. Between two pages the lineage can change: a merge can absorb
                    another role and bring its events into scope. The keyset guarantees no event
                    is skipped or repeated WITHIN the order it defines, which is not the same as
                    the timeline being frozen, and this does not claim it is.
                  */
                  FROM meta m
                  LEFT JOIN events e
                         ON $2::timestamptz IS NULL
                         OR (e.at, e.kind, e.source_id) < ($2::timestamptz, $3::text, $4::text)
                  LEFT JOIN users u ON u.id = e.actor
                 ORDER BY e.at DESC, e.kind DESC, e.source_id DESC
                 LIMIT $5
                """).execute(Tuple.of(roleId, cursor.at(), cursor.kind(), cursor.id(), page + 1))
            .map(rs -> {
                JsonArray events = new JsonArray();
                boolean cyclic = false;
                int rolesInScope = 1;
                String lastAt = null, lastKind = null, lastId = null;
                int kept = 0;
                boolean more = false;
                for (Row r : rs) {
                    cyclic = Boolean.TRUE.equals(r.getBoolean("cyclic"));
                    Integer scoped = r.getInteger("roles_in_scope");
                    if (scoped != null) rolesInScope = scoped;
                    if (r.getString("kind") == null) continue;   // the no-history row
                    if (kept == page) { more = true; break; }    // the extra row we asked for
                    /*
                      The ORIGINAL role id, so an event that happened to an absorbed employment
                      still says which one rather than being silently reattributed to the survivor
                      the moderator happens to be looking at. Null where the event belongs to the
                      manager or the company rather than to one employment.
                    */
                    Long on = r.getLong("role_id");
                    events.add(new JsonObject()
                        .put("kind", r.getString("kind"))
                        .put("roleId", on)
                        .put("onThisRole", on != null && on == roleId)
                        .put("at", iso(r, "at"))
                        .put("what", r.getString("what"))
                        .put("was", r.getString("was"))
                        .put("nowIs", r.getString("now_is"))
                        .put("reason", r.getString("reason"))
                        .put("detail", r.getString("detail"))
                        .put("actorKind", r.getString("actor_kind"))
                        .put("by", r.getString("actor_name")));
                    lastAt = iso(r, "at");
                    lastKind = r.getString("kind");
                    lastId = r.getString("source_id");
                    kept++;
                }
                return new JsonObject()
                    .put("roleId", roleId)
                    .put("events", events)
                    /*
                      TWO DIFFERENT GUARANTEES, never conflated.

                      lineageComplete answers "did we find every predecessor employment", which
                      CYCLE decides. hasMore answers "did we return every event", which the page
                      size decides. Reporting one as the other is how somebody concludes a
                      correction never happened: a role with 250 events would have claimed a
                      complete history while silently omitting the oldest 50.
                    */
                    .put("employmentsInScope", rolesInScope)
                    .put("lineageComplete", !cyclic)
                    .put("cycleDetected", cyclic)
                    .put("hasMore", more)
                    .put("nextCursor", more && lastAt != null
                                       ? Cursor.encode(lastAt, lastKind, lastId) : null);
            });
    }

    /**
     * A position in the timeline, opaque to the caller.
     *
     * <p>Opaque because it is a keyset over three columns rather than a page number, and a caller
     * that built one by hand would be constructing an ordering contract it cannot see.
     */
    private record Cursor(java.time.OffsetDateTime at, String kind, String id) {

        static final Cursor NONE = new Cursor(null, null, null);

        /**
         * Parsed and VALIDATED here, not at the database.
         *
         * <p>A cursor whose timestamp is not a timestamp is syntactically fine and semantically
         * nonsense. Passed through, {@code $2::timestamptz} raises 22007 inside the query and the
         * caller gets a 500 with a PostgreSQL message in it. Parsing the instant here turns that
         * into the 400 it always was.
         */
        static Cursor parse(String raw) {
            if (raw == null || raw.isBlank()) return NONE;
            String[] parts = new String(
                java.util.Base64.getUrlDecoder().decode(raw),
                java.nio.charset.StandardCharsets.UTF_8).split("\\|", 3);
            if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) {
                throw new IllegalArgumentException("not a cursor");
            }
            /*
              PARSED TO AN INSTANT, not carried as text. $2 is cast ::timestamptz, so the Vert.x
              client infers OffsetDateTime for it and REFUSES a String outright:
                Parameter at position[1] with class = [java.lang.String] ... can not be coerced
                to the expected class = [java.time.OffsetDateTime] for encoding
              Which means every second page failed while the first worked, and no amount of
              running the SQL in psql could show it: the fault is in the parameter binding, not
              the statement. Parsing here also turns a cursor whose timestamp is not a timestamp
              into the 400 it always was, instead of a 22007 surfacing as a 500.
            */
            return new Cursor(java.time.OffsetDateTime.parse(parts[0]), parts[1], parts[2]);
        }

        static String encode(String at, String kind, String id) {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                (at + "|" + kind + "|" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** True when the failure is the live-employment uniqueness index rather than anything else. */
    private static boolean collision(Throwable err) {
        String m = String.valueOf(err.getMessage());
        return m.contains("career_unique_known_employment");
    }

    private static String dayText(Row r, String column) {
        LocalDate d = day(r, column);
        return d == null ? null : d.toString();
    }

    private static LocalDate day(Row r, String column) {
        return r.getLocalDate(column);
    }

    private Future<Void> lockManagerOf(SqlConnection tx, long managerId) {
        return tx.preparedQuery("SELECT id FROM managers WHERE id = $1 FOR UPDATE")
            .execute(Tuple.of(managerId)).mapEmpty();
    }

    /**
     * Name the actor in the audit trail.
     *
     * <p>The resolution trigger reads this GUC for {@code claim_resolution_events.reason}, and
     * without it an admin decision is recorded with a null reason, indistinguishable at a glance
     * from one the platform made by itself.
     */
    private Future<Void> markAdminDecision(SqlConnection tx) {
        return tx.query("SELECT set_config('rmm.resolution_reason', 'admin_moderation', true)")
            .execute().mapEmpty();
    }

    private Future<Row> lockRoleAndManager(SqlConnection tx, long roleId) {
        return tx.preparedQuery("""
                SELECT m.id FROM managers m
                 WHERE m.id = (SELECT manager_id FROM career_history WHERE id = $1)
                 FOR UPDATE
                """).execute(Tuple.of(roleId))
            .compose(locked -> {
                if (locked.rowCount() == 0) {
                    return Future.failedFuture(ServiceException.notFound(
                        "No such employment: " + roleId));
                }
                return tx.preparedQuery(
                        "SELECT id, manager_id, state FROM career_history WHERE id = $1 FOR UPDATE")
                    .execute(Tuple.of(roleId))
                    .map(rs -> rs.iterator().next());
            });
    }

    /**
     * Promote every held rating on this role that the DATABASE says may now be public.
     *
     * <p>Both routes again: a withdrawn rating waiting on this employment is released by accepting
     * it just as a claimed one is, and {@code rating_role_is_publishable} already knows how to
     * answer for either.
     */
    private Future<Integer> releasePublishableRatings(SqlConnection tx, long roleId) {
        return tx.preparedQuery("""
                SELECT r.id FROM reviews r
                 WHERE (r.claim_id IN (SELECT rc.id FROM role_claims rc
                                        WHERE rc.resolved_role_id = $1)
                        OR r.withdrawn_role_id = $1)
                   AND r.disposition = 'held' AND r.deleted_at IS NULL
                   AND rating_role_is_publishable(r.id)
                 ORDER BY r.id
                """).execute(Tuple.of(roleId))
            .compose(rs -> publishAll(tx, idsOf(rs)));
    }

    private Future<Integer> releasePublishableRatingsForClaim(SqlConnection tx, long claimId) {
        return tx.preparedQuery("""
                SELECT r.id FROM reviews r
                 WHERE r.claim_id = $1
                   AND r.disposition = 'held' AND r.deleted_at IS NULL
                   AND rating_role_is_publishable(r.id)
                 ORDER BY r.id
                """).execute(Tuple.of(claimId))
            .compose(rs -> publishAll(tx, idsOf(rs)));
    }

    /** After a conflict closes: what moved, and what is still waiting on something else. */
    private Future<JsonObject> releaseRatingsForConflict(SqlConnection tx, long conflictId) {
        return tx.preparedQuery("""
                SELECT r.id, rating_role_is_publishable(r.id) AS ok
                  FROM reviews r
                  JOIN role_conflict_claims rcc ON rcc.claim_id = r.claim_id
                 WHERE rcc.conflict_id = $1 AND rcc.role = 'subject'
                   AND r.disposition = 'held' AND r.deleted_at IS NULL
                 ORDER BY r.id
                """).execute(Tuple.of(conflictId))
            .compose(rs -> {
                List<UUID> publishable = new ArrayList<>();
                int stillHeld = 0;
                for (Row r : rs) {
                    if (Boolean.TRUE.equals(r.getBoolean("ok"))) publishable.add(r.getUUID("id"));
                    else stillHeld++;
                }
                final int held = stillHeld;
                return publishAll(tx, publishable)
                    .map(n -> new JsonObject().put("published", n).put("stillHeld", held));
            });
    }

    /**
     * Hold every LIVE rating on this role, so the deferred role trigger can pass at commit.
     *
     * <p><b>Two routes reach a role's ratings, not one.</b> A withdrawn rating has no claim - its
     * {@code claim_id} is null - and keeps {@code withdrawn_role_id} instead, which is exactly the
     * hole {@code assert_roles_ratings} was widened to close. Holding only the claimed ones would
     * leave a withdrawn rating live against a refused employment, and the deferred assertion would
     * then abort the whole rejection at commit.
     */
    private Future<Integer> holdLiveRatingsFor(SqlConnection tx, long roleId) {
        return tx.preparedQuery("""
                SELECT r.id FROM reviews r
                 WHERE (r.claim_id IN (SELECT rc.id FROM role_claims rc
                                        WHERE rc.resolved_role_id = $1)
                        OR r.withdrawn_role_id = $1)
                   AND r.disposition = 'live' AND r.deleted_at IS NULL
                 ORDER BY r.id
                """).execute(Tuple.of(roleId))
            .compose(rs -> {
                List<UUID> ids = idsOf(rs);
                Future<Void> chain = Future.succeededFuture();
                for (UUID id : ids) {
                    chain = chain.compose(v -> dispositions.set(tx, id, "held"));
                }
                return chain.map(v -> ids.size());
            });
    }

    private Future<Integer> publishAll(SqlConnection tx, List<UUID> ids) {
        Future<Void> chain = Future.succeededFuture();
        for (UUID id : ids) {
            // Through ReviewDisposition, never a direct column write: it keeps gate_eligible and
            // live_since coherent and keeps the location projection correct.
            chain = chain.compose(v -> dispositions.set(tx, id, "live"));
        }
        return chain.map(v -> ids.size());
    }

    private static List<UUID> idsOf(RowSet<Row> rs) {
        List<UUID> ids = new ArrayList<>();
        for (Row r : rs) ids.add(r.getUUID("id"));
        return ids;
    }

    /**
     * The same recalculation every other path uses, on this connection, inside this transaction.
     *
     * <p><b>{@code origin = 'contributor'} is not optional here.</b> It was missing, and the claim
     * in the line above was therefore false: {@code ManagerRepository.recalculate} excludes
     * platform examples by provenance, this one counted them, and whichever ran last decided what
     * the manager's profile said. The reachable case is not exotic - a merge holds a manager's
     * ratings, the manager still carries its example because it has no live genuine rating, and
     * accepting the employment published the rating and recomputed the average as the mean of a
     * genuine rating and a fabricated one. That is the exact defect {@code reviews.origin} was
     * added to end.
     *
     * <p>Not expressed through {@code ReviewSql.genuine} only because this statement already names
     * {@code deleted_at} and {@code disposition} unaliased inside a subquery over {@code reviews};
     * the predicate is the same one, clause for clause.
     */
    private Future<Void> recalculate(SqlConnection tx, long managerId) {
        return tx.preparedQuery("""
                UPDATE managers SET
                    reviews_count     = sub.cnt,
                    overall_rating    = sub.overall_rating,
                    category_averages = sub.cats::jsonb
                FROM (
                    SELECT
                        COUNT(*)::INTEGER AS cnt,
                        ROUND(AVG(overall_rating)::NUMERIC, 1) AS overall_rating,
                        json_build_object(
                            'Communication Style',               ROUND(AVG(communication_style)::NUMERIC,1),
                            'Perceived Approachability',         ROUND(AVG(perceived_approachability)::NUMERIC,1),
                            'Perceived Clarity of Expectations', ROUND(AVG(perceived_clarity_of_expectations)::NUMERIC,1),
                            'Feedback Style',                    ROUND(AVG(feedback_style)::NUMERIC,1),
                            'Perceived Supportiveness',          ROUND(AVG(perceived_supportiveness)::NUMERIC,1),
                            'Decision Making Style',             ROUND(AVG(decision_making_style)::NUMERIC,1),
                            'Organization and Planning Style',   ROUND(AVG(organization_and_planning_style)::NUMERIC,1),
                            'Delegation Style',                  ROUND(AVG(delegation_style)::NUMERIC,1),
                            'Perceived Professional Demeanor',   ROUND(AVG(perceived_professional_demeanor)::NUMERIC,1),
                            'Overall Working Experience',        ROUND(AVG(overall_working_experience)::NUMERIC,1)
                        )::text AS cats
                    FROM reviews
                     WHERE manager_id = $1 AND deleted_at IS NULL AND disposition = 'live'
                       AND origin = 'contributor'
                ) sub
                WHERE managers.id = $1
                """).execute(Tuple.of(managerId)).mapEmpty();
    }

    private static String iso(Row r, String column) {
        java.time.OffsetDateTime t = r.getOffsetDateTime(column);
        return t == null ? null : t.toString();
    }
}
