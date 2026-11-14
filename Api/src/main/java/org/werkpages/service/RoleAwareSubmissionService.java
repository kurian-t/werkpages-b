package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One rating, one claim, one opportunity, one transaction.
 *
 * <h2>What this replaces</h2>
 *
 * <p>The legacy path takes a typed name and a typed company, finds candidates by exact company,
 * runs a Levenshtein comparison over their names, and <b>attaches the rating to whichever one is
 * within an edit of what was typed</b>. That is a machine deciding which human a person's opinion
 * is about, and it is the single thing this redesign exists to remove.
 *
 * <p>Here the contributor names the person and the employment explicitly, and the platform records
 * the decision rather than making one. Fuzzy matching may still help somebody FIND a manager; it
 * may never decide a manager's identity or attach a rating.
 *
 * <p><b>The legacy path is untouched and still live.</b> This runs only behind
 * {@link RoleClaimService#roleAwareSubmissionEnabled()}, and the matcher must not be removed before
 * the selection UI that replaces it ships: removing it earlier means every submission for an
 * existing manager creates a new provisional row, because the old form sends no manager id and
 * nothing may guess.
 *
 * <h2>Why all of it is one transaction</h2>
 *
 * <p>The claim's finalization, the rating, and the opportunity charge are three writes that are one
 * logical act. Splitting them produces three specific failures, each of which has happened to
 * somebody's data somewhere:
 *
 * <ul>
 *   <li>a finalized claim with no rating is an employment assertion nobody made;</li>
 *   <li>a rating whose claim was not finalized can still be edited underneath it;</li>
 *   <li>a charge that commits beside a failed rating silently costs somebody half their lifetime
 *       allowance for that subject, and they have nothing to show for it.</li>
 * </ul>
 *
 * <h2>Lock order</h2>
 *
 * <p>The manager is locked first, before anything else is read or written, which is the canonical
 * order ({@code companies, managers, career_history, role_claims, ..., reviews}) and the same order
 * the merge takes. That is what makes a submission and a merge of the same manager serialise rather
 * than deadlock, and it is also what makes the opportunity charge safe: the read of the free
 * ordinal and the insert that takes it cannot interleave while this transaction holds the subject.
 */
public final class RoleAwareSubmissionService {

    private final Pool db;
    private final RoleClaimService claims;
    private final RatingOpportunityService opportunities;
    private final ReviewDisposition dispositions;
    private final boolean enabled;

    public RoleAwareSubmissionService(Pool db) {
        this(db, RoleClaimService.roleAwareSubmissionEnabled());
    }

    /**
     * Seam for tests, which cannot set an environment variable in their own process.
     *
     * <p>The env read stays in {@link RoleClaimService#roleAwareSubmissionEnabled()} and is still
     * the only thing production consults; this constructor only lets a test say which side of the
     * gate it is exercising. A test that passes {@code false} is testing that the gate holds.
     */
    /** Only for path 3's manager creation, which has to happen inside the submission's transaction. */
    private final org.werkpages.repository.ManagerRepository managers;

    public RoleAwareSubmissionService(Pool db, boolean enabled) {
        this(db, enabled, new RoleClaimService(), new RatingOpportunityService(),
             new ReviewDisposition());
    }

    public RoleAwareSubmissionService(Pool db, boolean enabled, RoleClaimService claims,
                                      RatingOpportunityService opportunities,
                                      ReviewDisposition dispositions) {
        this.db = db;
        this.enabled = enabled;
        this.claims = claims;
        this.opportunities = opportunities;
        this.dispositions = dispositions;
        this.managers = new org.werkpages.repository.ManagerRepository(db);
    }

    /**
     * What the contributor filled in.
     *
     * @param proposalKey    the form's own identity, stable across autosaves and retries. It is
     *                       what makes a repeated submission idempotent rather than duplicated.
     * @param selectedRoleId non-null means they picked an existing employment; null means they are
     *                       proposing one, and the period fields describe it.
     */
    public record Submission(
        UUID proposalKey,
        long revision,
        long managerId,
        long companyId,
        String title,
        Long selectedRoleId,
        LocalDate startDate,
        LocalDate endDate,
        boolean periodKnown,
        Boolean stillThere,
        String managerCompany,
        String author,
        UUID userId,
        double overall,
        double[] categories,
        OffsetDateTime workedFrom
    ) {
        public boolean isSelection() { return selectedRoleId != null; }
        public boolean isAnonymous() { return userId == null; }
    }

    /**
     * @param live        whether the rating is public now. False is normal and not a failure: a
     *                    proposed employment is held until a moderator accepts it.
     * @param heldBecause the database's own words for why, so the moderation queue and the
     *                    contributor are told the same thing.
     * @param replayed    true when this submission had already been recorded and nothing new was
     *                    written.
     */
    public record Outcome(
        UUID reviewId,
        long claimId,
        /**
         * The manager this rating is now against, whichever path it took.
         *
         * <p>On the propose-manager path it is the row this submission created, and the client
         * has no other way to learn it: it sent a null manager id precisely because there was
         * nobody to name. Without it the form had nowhere to send the contributor afterwards and
         * navigated to {@code /manager/} with no id, which is a 404 at the end of a completed
         * contribution.
         */
        long managerId,
        long roleId,
        String resolution,
        boolean live,
        String heldBecause,
        Integer opportunityOrdinal,
        String conflictKind,
        boolean replayed
    ) {}

    /** Raised when the contributor has used both of their opportunities for this subject. */
    public static final class NoOpportunityException extends RuntimeException {
        public NoOpportunityException(String m) { super(m); }
    }

    /** Raised when the draft moved under the request and the client must resync. */
    public static final class StaleDraftException extends RuntimeException {
        public StaleDraftException(String m) { super(m); }
    }

    public Future<Outcome> submit(Submission s) {
        if (!enabled) {
            return Future.failedFuture(new IllegalStateException(
                "Role-aware submission is disabled. The legacy path owns submissions until the "
                + "public selection UI, the moderation queue and the end-to-end tests are done."));
        }
        return withRetry(() -> db.withTransaction(tx -> submitOnce(tx, s)), 2);
    }

    /**
     * What a contributor typed when they said "none of these people is the person".
     *
     * <p>Only the fields needed to create them. The rating itself travels in the Submission.
     */
    public record PendingManager(
        String name, String company, String title, String status,
        String country, String state, Long companyId,
        /**
         * Who added them, so the profile is readable by the person who just created it.
         *
         * <p>{@code enforceSubmitterAccess} refuses a {@code pending_approval} row to everybody
         * except its submitter. Leaving this null therefore ended the redesigned journey on
         * "Manager not found": the contributor finished a rating, was sent to the profile they
         * had just created, and the server refused to serve it to them.
         *
         * <p>Null for an anonymous submission, which keeps the ownerless row refused to everyone
         * until a moderator approves it.
         */
        UUID submittedBy
    ) {}

    /**
     * PATH 3, where the manager does not exist yet. One transaction for all four writes.
     *
     * <h2>The duplicate this replaces</h2>
     *
     * <p>The manager used to be created before this service was called, on the pool, in its own
     * transaction. So a retried submission created a SECOND pending person before anything could
     * recognise the retry: one rating and one opportunity charge, as intended, and two people in
     * the moderation queue. Issue 34.
     *
     * <p>The fix needs no new schema, because the durable mechanism already exists.
     * {@code role_claims.proposal_key} carries a UNIQUE index and the claim row already records
     * {@code manager_id}, so the question "has this form already created somebody?" has one
     * authoritative answer in the database. This reads it first and only creates a manager when
     * there is none.
     *
     * <h2>Why that is safe under concurrency without any further locking</h2>
     *
     * <p>Two identical submissions can both find no claim and both insert a pending manager. Only
     * one of them can then insert the claim, because the proposal key is unique; the loser's
     * transaction fails and takes ITS manager with it. {@link #withRetry} then runs it again, the
     * claim now exists, and the retry resolves to the winner's manager. The unique index is doing
     * the work, which is what makes the guarantee durable rather than a check somebody can forget.
     */
    public Future<Outcome> submitProposingManager(PendingManager pending,
                                                  java.util.function.LongFunction<Submission> build) {
        if (!enabled) {
            return Future.failedFuture(new IllegalStateException(
                "Role-aware submission is disabled. The legacy path owns submissions until the "
                + "public selection UI, the moderation queue and the end-to-end tests are done."));
        }
        return withRetry(() -> db.withTransaction(tx ->
            resolveSubject(tx, build.apply(0L).proposalKey(), pending)
                .compose(subjectId -> submitOnce(tx, build.apply(subjectId)))), 2);
    }

    /**
     * The manager this form is about: the one it already created, or a new one.
     *
     * <p>Inside the caller's transaction, so a failure anywhere below takes the manager with it.
     */
    private Future<Long> resolveSubject(SqlConnection tx, UUID proposalKey, PendingManager pending) {
        /*
          SERIALISED ON THE FORM, before anything is read.

          Without this, two identical submissions both find no claim and both create a pending
          manager. Only one can then insert the claim, because proposal_key is unique - but the
          loser fails on the SLUG first, which is not a retriable state, so the second contributor
          saw an error instead of their rating. Measured: "duplicate key value violates unique
          constraint managers_slug_idx".

          A transaction-scoped advisory lock keyed on the form makes the second attempt WAIT, find
          the claim the first one wrote, and reuse its manager. Deterministic, rather than relying
          on a retry to clean up after a collision.

          Taken BEFORE the manager row lock in lockSubject, consistently, so the ordering cannot
          deadlock. Paths 1 and 2 never take this lock at all, so they cannot be the other half of
          a cycle.
        */
        return tx.preparedQuery("SELECT pg_advisory_xact_lock(hashtext($1::text)::bigint)")
            .execute(Tuple.of(proposalKey.toString()))
            .compose(v -> tx.preparedQuery("""
                    SELECT c.manager_id, m.name
                      FROM role_claims c JOIN managers m ON m.id = c.manager_id
                     WHERE c.proposal_key = $1
                    """)
                .execute(Tuple.of(proposalKey)))
            .compose(rs -> {
                if (rs.iterator().hasNext()) {
                    Row existing = rs.iterator().next();
                    /*
                      THE SAME FORM MUST STILL BE ABOUT THE SAME PERSON.

                      The claim records the manager, the company and the title, but not the name
                      somebody typed - so a submission reusing this key while naming a DIFFERENT
                      person would quietly resolve to the first person and replay their rating.
                      That reads as success and is about the wrong human, so it is refused here,
                      where the two names can still be compared.
                    */
                    String had = existing.getString("name");
                    if (pending.name() != null && had != null
                        && !had.trim().equalsIgnoreCase(pending.name().trim())) {
                        return Future.failedFuture(new StaleDraftException(
                            "This submission names a different person from the one already "
                            + "recorded under it. Reload the form and send it again."));
                    }
                    // This form has already created somebody. Never a second one.
                    return Future.succeededFuture(existing.getLong("manager_id"));
                }
                return managers.createPending(tx, pending.name(), pending.company(),
                        pending.title(), pending.status(), pending.country(), pending.state(),
                        null, pending.companyId(), pending.submittedBy())
                    .map(row -> row.getLong("id"));
            });
    }

    private Future<Outcome> submitOnce(SqlConnection tx, Submission s) {
        // 1. THE SUBJECT, first and FOR UPDATE. Everything below depends on this: the serialisation
        //    that makes the opportunity charge safe, and the ordering that keeps this and a merge
        //    of the same manager from deadlocking.
        return opportunities.lockSubject(tx, RatingOpportunityService.MANAGER, s.managerId())
            // 2. The draft, upserted at this revision. Safe to replay: an older revision changes
            //    nothing, and a finalized claim is immutable to it.
            .compose(v -> claims.saveDraft(tx, new RoleClaimService.Draft(
                s.proposalKey(), s.managerId(), s.companyId(), s.title(),
                s.startDate(), s.endDate(), s.periodKnown(), s.stillThere(),
                s.selectedRoleId(), "contributor", s.userId(), s.revision())))
            .compose(draft -> {
                if (draft.isFinalized()) {
                    // Already submitted. Either a retry of a request that committed, or a second
                    // attempt at a claim that is now a saved observation. Both answer the same way:
                    // return what exists and write nothing.
                    return replay(tx, draft.id(), s);
                }
                return carryOn(tx, s, draft);
            });
    }

    private Future<Outcome> carryOn(SqlConnection tx, Submission s,
                                    RoleClaimService.DraftResult draft) {
        long claimId = draft.id();
        return adoptIfNeeded(tx, claimId, s)
            // 3. FINALIZE, BEFORE RESOLVING, and the order is not interchangeable.
            //    claim_resolved_is_finalized is CHECK (resolved_at IS NULL OR finalized_at IS NOT
            //    NULL): a draft cannot carry a resolution, because a resolution is the platform's
            //    answer to a submitted assertion and a draft has not been submitted yet. Resolving
            //    first fails the constraint outright.
            //
            //    It reads backwards and it is right: the finalize asserts WHAT IT IS SEALING, and
            //    resolution then acts on sealed values. Rev 268 lists this among five contradictions
            //    it had to correct in itself, for exactly this reason.
            //
            //    Zero rows means the draft moved under this request: a newer autosave landed, or
            //    they changed the person or the role in another tab. The server does not seal a
            //    version nobody confirmed.
            .compose(v -> claims.finalizeClaim(tx, claimId, draft.revision(), s.managerId(),
                                               s.selectedRoleId()))
            .compose(sealed -> {
                if (sealed.isEmpty()) {
                    return Future.failedFuture(new StaleDraftException(
                        "This form changed while it was being submitted. Reload it and send it "
                        + "again so nothing is saved that you did not confirm."));
                }
                // 4. THE FIRST THING THEY SAID, kept as the first entry in the claim's
                //    series. The claim's own assertion columns say the same thing by
                //    construction, which is deliberate: the claim keeps the statement it was
                //    built from so nothing has to join to read it, and the series keeps every
                //    statement so a later, differing one has somewhere to go. Evidence, never
                //    authoritative.
                return claims.recordInitialTestimony(tx, claimId, s.userId(), s.title(),
                        s.managerCompany(), s.startDate(), s.endDate(), s.periodKnown(),
                        s.stillThere())
                    // 5. RESOLUTION, on the sealed values.
                    .compose(v -> resolve(tx, claimId, s))
                    .compose(resolution -> insertRatingAndCharge(tx, s, claimId, resolution));
            });
    }

    /**
     * Attach an anonymous claim to the account submitting it.
     *
     * <p>The rating's author and the claim's contributor must agree, and they are set in the same
     * transaction, so a rating attributed to an account cannot hang off a claim attributed to a
     * different one. A claim already owned by somebody ELSE is refused outright: that is the
     * identity transfer the whole lifecycle forbids.
     */
    private Future<Void> adoptIfNeeded(SqlConnection tx, long claimId, Submission s) {
        if (s.isAnonymous()) {
            return Future.succeededFuture();
        }
        return claims.adopt(tx, claimId, s.userId())
            .compose(adopted -> adopted
                ? Future.succeededFuture()
                : tx.preparedQuery("SELECT user_id FROM role_claims WHERE id = $1")
                    .execute(Tuple.of(claimId))
                    .compose(rs -> {
                        UUID owner = rs.iterator().next().getUUID("user_id");
                        if (s.userId().equals(owner)) {
                            return Future.succeededFuture();   // already ours: a replay
                        }
                        return Future.failedFuture(new StaleDraftException(
                            "This draft belongs to a different account."));
                    }))
            .mapEmpty();
    }

    private Future<RoleClaimService.Resolution> resolve(SqlConnection tx, long claimId,
                                                        Submission s) {
        return s.isSelection()
            ? claims.resolveBySelection(tx, claimId, s.managerId(), s.companyId(),
                                        s.selectedRoleId())
            : claims.resolveByProposal(tx, claimId, s.managerId(), s.companyId(), s.title(),
                                       s.startDate(), s.periodKnown());
    }

    /**
     * The rating, the charge, and the publication decision.
     *
     * <p><b>Inserted HELD, then settled.</b> Never inserted live and corrected afterwards: a live
     * rating that is not publishable is refused by the triggers, so guessing wrong would fail the
     * whole submission rather than hold the rating, which is the opposite of what should happen to
     * somebody's contribution.
     */
    private Future<Outcome> insertRatingAndCharge(SqlConnection tx, Submission s, long claimId,
                                                  RoleClaimService.Resolution resolution) {
        double[] c = s.categories();
        return tx.preparedQuery("""
                INSERT INTO reviews (
                    manager_id, user_id, author, overall_rating,
                    communication_style, perceived_approachability,
                    perceived_clarity_of_expectations, feedback_style, perceived_supportiveness,
                    decision_making_style, organization_and_planning_style, delegation_style,
                    perceived_professional_demeanor, overall_working_experience,
                    manager_company, manager_title, worked_from,
                    claim_id, role_basis, role_required, attribution_basis,
                    disposition, gate_eligible, live_since,
                    verified, helpful_count, weight, created_at, updated_at
                )
                VALUES ($1,$2,$3,$4,
                        $5,$6,$7,$8,$9,$10,$11,$12,$13,$14,
                        $15,$16,$17,
                        $18,'claimed',TRUE,'role',
                        'held',FALSE,NULL,
                        TRUE,0,FALSE,now(),now())
                RETURNING id
                """)
            .execute(Tuple.of(s.managerId(), s.userId(), s.author(), s.overall())
                .addValue(c[0]).addValue(c[1]).addValue(c[2]).addValue(c[3]).addValue(c[4])
                .addValue(c[5]).addValue(c[6]).addValue(c[7]).addValue(c[8]).addValue(c[9])
                .addValue(s.managerCompany()).addValue(s.title()).addValue(s.workedFrom())
                .addValue(claimId))
            .map(rs -> rs.iterator().next().getUUID("id"))
            .compose(reviewId -> charge(tx, s, claimId)
                .compose(ordinal -> claims.settle(tx, reviewId, dispositions)
                    .compose(live -> (live
                            ? recalculate(tx, s.managerId())
                            : Future.<Void>succeededFuture())
                        .compose(v -> live
                            ? Future.<String>succeededFuture(null)
                            : claims.heldReason(tx, reviewId))
                        .map(problem -> new Outcome(
                            reviewId, claimId, s.managerId(),
                            resolution.roleId(), resolution.resolution(),
                            live, live ? null : problem,
                            ordinal.isPresent() ? ordinal.getAsInt() : null,
                            resolution.conflictKind(), false)))));
    }

    /**
     * Charge an opportunity, for an authenticated submission that is opening a new one.
     *
     * <p><b>An anonymous submission charges nothing</b>, because an allowance is keyed on an
     * account and there is no account yet. Adoption is the charging event: when they come back,
     * sign in and submit, that transaction charges it.
     *
     * <p>A retry charges nothing either, because a retry never reaches here: a finalized claim
     * replays instead. Nor does an edit or a replacement inside the three-day window, neither of
     * which opens a new opportunity.
     */
    private Future<OptionalInt> charge(SqlConnection tx, Submission s, long claimId) {
        if (s.isAnonymous()) {
            return Future.succeededFuture(OptionalInt.empty());
        }
        return opportunities.charge(tx, s.userId(), RatingOpportunityService.MANAGER,
                                    s.managerId(), claimId)
            .compose(ordinal -> ordinal.isPresent()
                ? Future.succeededFuture(ordinal)
                : Future.failedFuture(new NoOpportunityException(
                    "You have already used both of your ratings for this manager.")));
    }

    /**
     * A submission that had already been recorded.
     *
     * <p>Writes nothing and returns what exists. The alternative, refusing, turns a dropped
     * response or a double-tap into a lost contribution, and the one after that turns it into a
     * duplicate: {@code reviews_one_per_claim} would refuse the second insert anyway, as an error
     * the contributor cannot act on.
     */
    private Future<Outcome> replay(SqlConnection tx, long claimId, Submission s) {
        return tx.preparedQuery("""
                SELECT r.id, r.disposition, r.user_id, c.resolved_role_id, c.resolution,
                       c.manager_id, c.company_id, c.title
                  FROM role_claims c LEFT JOIN reviews r ON r.claim_id = c.id
                 WHERE c.id = $1
                """)
            .execute(Tuple.of(claimId))
            .compose(rs -> {
                Row r = rs.iterator().next();
                /*
                  THE SAME KEY MUST NOT MEAN A DIFFERENT PERSON.

                  A proposal key identifies one form. If one arrives again describing somebody
                  else, that is not a retry: either a key was reused by mistake or something
                  generated a collision. Replaying would hand back the FIRST rating as though it
                  were the answer to this one, which reads as success and is silently about the
                  wrong human.

                  So it is refused. The contributor can submit again, and a fresh form carries a
                  fresh key.
                */
                if (r.getLong("manager_id") != s.managerId()
                    || !java.util.Objects.equals(r.getString("title"), s.title())) {
                    return Future.failedFuture(new StaleDraftException(
                        "This submission describes a different person or job from the one already "
                        + "recorded under it. Reload the form and send it again."));
                }
                UUID reviewId = r.getUUID("id");
                if (reviewId == null) {
                    // Finalized with no rating. Not reachable through this service, because the
                    // two are written together, so it means something else finalized the claim.
                    return Future.failedFuture(new StaleDraftException(
                        "This employment has already been submitted without a rating."));
                }
                UUID owner = r.getUUID("user_id");
                if (owner != null && s.userId() != null && !owner.equals(s.userId())) {
                    return Future.failedFuture(new StaleDraftException(
                        "This draft belongs to a different account."));
                }
                boolean live = "live".equals(r.getString("disposition"));
                /*
                  WHAT THEY SAY THIS TIME, if it is not what they said last time.

                  The rating is not rewritten and no assertion column moves - claim_assertion_is_
                  immutable would refuse that anyway - but a contributor who now remembers the
                  period differently has said a second thing, and it belongs on the record. An
                  identical retry says nothing new and writes nothing, which is what keeps this
                  safe on a path whose whole job is to be replayable.

                  It never writes claim_corrections. That is an administrator's determination,
                  and a contributor remembering it differently is not one.
                */
                return claims.recordRepeatTestimony(tx, claimId, s.userId(), s.title(),
                        s.managerCompany(), s.startDate(), s.endDate(), s.periodKnown(),
                        s.stillThere())
                    .compose(said -> live ? Future.<String>succeededFuture(null)
                                          : claims.heldReason(tx, reviewId))
                    .map(problem -> new Outcome(
                        /*
                          The manager this claim is against, read from the row.

                          It cannot disagree with s.managerId() here: a replay of the
                          propose-manager path resolves to the manager the first attempt created
                          before a Submission is built, which is what makes "has this form already
                          created somebody?" have one answer. A mutation swapping the two proved
                          exactly that by surviving. The row is still the authoritative source and
                          costs nothing, but nobody should read a protection into it.
                        */
                        reviewId, claimId, r.getLong("manager_id"),
                        r.getLong("resolved_role_id"),
                        r.getString("resolution"), live, problem,
                        null, null, true));
            });
    }

    /** The same recalculation every other path uses, on this connection, inside this transaction. */
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
                ) sub
                WHERE managers.id = $1
                """).execute(Tuple.of(managerId)).mapEmpty();
    }

    /**
     * Retry a serialisation failure or a deadlock, and nothing else.
     *
     * <p>Safe to retry because the whole thing is one transaction keyed on {@code proposal_key}: a
     * retry either redoes work that rolled back, or finds the claim finalized and replays. It is
     * deliberately NOT a retry of a business refusal. A contributor out of opportunities, or a
     * stale draft, gets the same answer however many times it is asked.
     */
    private Future<Outcome> withRetry(java.util.function.Supplier<Future<Outcome>> attempt,
                                      int attemptsLeft) {
        return attempt.get().recover(err -> {
            if (attemptsLeft <= 0 || !isRetriable(err)) {
                return Future.failedFuture(err);
            }
            long backoff = ThreadLocalRandom.current().nextLong(20, 80);
            return Future.future(p -> new java.util.Timer(true).schedule(
                new java.util.TimerTask() {
                    @Override public void run() { p.complete(); }
                }, backoff))
                .compose(v -> withRetry(attempt, attemptsLeft - 1));
        });
    }

    private static boolean isRetriable(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof PgException pg) {
                String state = pg.getSqlState();
                if ("40P01".equals(state) || "40001".equals(state)) return true;
            }
            if (RatingOpportunityService.isOrdinalContention(c)) return true;
        }
        return false;
    }
}
