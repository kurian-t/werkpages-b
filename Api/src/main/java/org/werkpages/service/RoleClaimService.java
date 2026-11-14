package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import org.werkpages.repository.EmploymentStatusResolver;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The role-claim lifecycle: what a contributor asserted about an employment, and how the platform
 * resolved it.
 *
 * <h2>Why this is one component</h2>
 *
 * <p>Rev 268: <i>"One component owns claim resolution and conflict detection, called from every
 * write path rather than reimplemented in each."</i> The four review-insert sites per application
 * are the reason. <b>A rule enforced at the call site is a rule the fifth call site will not
 * have.</b>
 *
 * <h2>The two paths, and only one of them looks at descriptions</h2>
 *
 * <p><b>Selection.</b> The contributor picked a specific employment from a list. The row they
 * picked is read and validated; nothing is matched. No descriptive comparison happens on this path
 * at all, because the contributor made an identity decision and the system's job is to record it.
 *
 * <p><b>Proposal.</b> The employment is missing, so one is created. Nothing is linked by
 * resemblance: a known period deduplicates on an exact employment key, and an unknown period never
 * deduplicates at all and raises a conflict for a human instead.
 *
 * <p>There is deliberately no resolution matrix. An earlier revision decided linkage by comparing
 * manager, employer, normalised title and both dates across seven rows; that table was deleted
 * rather than revised, because it was exactly the inference this design exists to remove. Every row
 * of it is now one of two rules: <b>a selection is validated and honoured</b>, or <b>a proposal is
 * created and, if something resembles it, flagged for a human</b>.
 *
 * <h2>What this component does NOT do</h2>
 *
 * <p>It never decides whether a rating is publishable. That is one predicate in the database,
 * {@code rating_is_publishable}, quoted by five triggers, and {@link #settle} asks it rather than
 * reimplementing it. A Java copy of that rule would be a second source of truth for the one
 * question this redesign exists to answer correctly.
 *
 * <p>It never charges a rating opportunity. That is {@code RatingOpportunityService}, and the
 * submission path calls both in one transaction.
 */
public final class RoleClaimService {

    /**
     * The whole role-aware flow, off until 4A, 4B, 4C and 4D are complete and verified.
     *
     * <p>Off means the legacy submission path is untouched, including the fuzzy name attachment
     * this eventually replaces. <b>The matcher must not be removed before the selection UI that
     * replaces it ships:</b> removing it earlier means every submission for an existing manager
     * creates a new provisional row, because the old form sends no manager id and nothing may
     * guess.
     */
    public static boolean roleAwareSubmissionEnabled() {
        return "true".equalsIgnoreCase(System.getenv("ROLE_AWARE_SUBMISSION_ENABLED"));
    }

    // -- the draft ------------------------------------------------------------

    /** What the contributor has asserted so far. Every field is editable until finalization. */
    public record Draft(
        UUID proposalKey,
        long managerId,
        long companyId,
        String title,
        LocalDate startDate,
        LocalDate endDate,
        boolean periodKnown,
        Boolean stillThere,
        Long selectedRoleId,
        String source,
        UUID userId,
        long revision
    ) {}

    /** The claim as it stands after the upsert. */
    public record DraftResult(
        long id,
        long managerId,
        Long selectedRoleId,
        OffsetDateTime finalizedAt,
        long revision,
        OffsetDateTime resolvedAt,
        Long resolvedRoleId,
        boolean created
    ) {
        public boolean isFinalized() { return finalizedAt != null; }
        public boolean isResolved()  { return resolvedAt != null; }
    }

    /**
     * Create or update the draft claim. <b>One statement, and no prior read.</b>
     *
     * <p>{@code SELECT ... FOR UPDATE} first was wrong, and the reason is worth keeping: <b>a row
     * that does not exist cannot be locked.</b> Two concurrent requests carrying a new proposal key
     * both read nothing, neither holds a lock, both insert, and one fails on the unique constraint.
     * The preliminary read gave the appearance of serialisation and provided none.
     *
     * <p><b>The guard is per column</b>, written out because PostgreSQL has no LET: a field is
     * overwritten only while the claim is still a draft AND the incoming revision is strictly
     * newer. That is what makes autosave safe to retry and safe to race: an autosave in flight when
     * a newer one lands cannot win on arrival.
     *
     * <p><b>{@code manager_id} is among the guarded columns on purpose.</b> A contributor who
     * changes the person they picked must change it here, or the claim and the eventual rating
     * disagree about whom the rating is even about.
     *
     * <p><b>{@code original_manager_id} is deliberately absent</b>, and that is load-bearing rather
     * than tidy. {@code claim_finalization_sets_lineage} pairs it to {@code finalized_at}, so a
     * draft that set it would be refused by the constraint on every insert. It is written by
     * {@link #finalize} and nowhere else.
     */
    public Future<DraftResult> saveDraft(SqlClient tx, Draft d) {
        return tx.preparedQuery("""
                INSERT INTO role_claims
                  (proposal_key, manager_id, company_id, title,
                   start_date, end_date, period_known, still_there, selected_role_id,
                   source, user_id, revision)
                VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
                ON CONFLICT (proposal_key) DO UPDATE SET
                  manager_id       = CASE WHEN role_claims.finalized_at IS NULL
                                           AND EXCLUDED.revision > role_claims.revision
                                          THEN EXCLUDED.manager_id       ELSE role_claims.manager_id       END,
                  company_id       = CASE WHEN role_claims.finalized_at IS NULL
                                           AND EXCLUDED.revision > role_claims.revision
                                          THEN EXCLUDED.company_id       ELSE role_claims.company_id       END,
                  title            = CASE WHEN role_claims.finalized_at IS NULL
                                           AND EXCLUDED.revision > role_claims.revision
                                          THEN EXCLUDED.title            ELSE role_claims.title            END,
                  start_date       = CASE WHEN role_claims.finalized_at IS NULL
                                           AND EXCLUDED.revision > role_claims.revision
                                          THEN EXCLUDED.start_date       ELSE role_claims.start_date       END,
                  end_date         = CASE WHEN role_claims.finalized_at IS NULL
                                           AND EXCLUDED.revision > role_claims.revision
                                          THEN EXCLUDED.end_date         ELSE role_claims.end_date         END,
                  period_known     = CASE WHEN role_claims.finalized_at IS NULL
                                           AND EXCLUDED.revision > role_claims.revision
                                          THEN EXCLUDED.period_known     ELSE role_claims.period_known     END,
                  still_there      = CASE WHEN role_claims.finalized_at IS NULL
                                           AND EXCLUDED.revision > role_claims.revision
                                          THEN EXCLUDED.still_there      ELSE role_claims.still_there      END,
                  selected_role_id = CASE WHEN role_claims.finalized_at IS NULL
                                           AND EXCLUDED.revision > role_claims.revision
                                          THEN EXCLUDED.selected_role_id ELSE role_claims.selected_role_id END,
                  revision         = GREATEST(role_claims.revision, EXCLUDED.revision),
                  updated_at       = now()
                RETURNING id, manager_id, selected_role_id, finalized_at, revision,
                          resolved_at, resolved_role_id, (xmax = 0) AS created
                """)
            .execute(Tuple.of(d.proposalKey(), d.managerId(), d.companyId(), d.title())
                .addValue(d.startDate()).addValue(d.endDate())
                // COALESCED, not passed through. still_there is NOT NULL DEFAULT FALSE, and an
                // explicit null in an INSERT defeats a column default rather than falling back to
                // it, so a form that simply did not ask the question failed the whole submission.
                // FALSE is the right default and not a fudge: "they were still there when I left"
                // and "I do not know when it ended" are different assertions, and only the first is
                // evidence. Not asserting it must not read as asserting it.
                .addValue(d.periodKnown())
                .addValue(d.stillThere() != null && d.stillThere())
                .addValue(d.selectedRoleId())
                .addValue(d.source() == null ? "contributor" : d.source())
                .addValue(d.userId()).addValue(d.revision()))
            .map(rs -> toDraftResult(rs.iterator().next()));
    }

    private static DraftResult toDraftResult(Row r) {
        return new DraftResult(
            r.getLong("id"),
            r.getLong("manager_id"),
            r.getLong("selected_role_id"),
            r.getOffsetDateTime("finalized_at"),
            r.getLong("revision"),
            r.getOffsetDateTime("resolved_at"),
            r.getLong("resolved_role_id"),
            r.getBoolean("created"));
    }

    // ── Contributor testimony (V111) ──────────────────────────────────────────

    /**
     * The statement that created this claim, written as the first entry in its series.
     *
     * <p><b>It duplicates the claim's own assertion columns on purpose.</b> The claim keeps the
     * statement it was built from so that nothing has to join to read it; the series keeps every
     * statement so that a later, differing one has somewhere to go. Rev 268's first testimony test
     * is that the two agree.
     *
     * <p><b>Nothing here is authoritative.</b> Testimony is the evidence a resolution is drawn
     * FROM. The platform's determination lives in {@code claim_corrections}, which no contributor
     * path writes, and the resolution itself in {@code claim_resolution_events}. This table is
     * append-only: {@code claim_testimony_append_only} refuses UPDATE and DELETE outright, and
     * permits exactly one shape - {@code user_id} cleared when the account is already gone.
     *
     * <p>Guarded by its own emptiness rather than by the caller, so calling it twice for one claim
     * writes one row. In practice {@code finalizeClaim} already succeeds once.
     */
    public Future<Boolean> recordInitialTestimony(SqlClient tx, long claimId, UUID userId,
                                                  String title, String companyName,
                                                  LocalDate startDate, LocalDate endDate,
                                                  boolean periodKnown, Boolean stillThere) {
        return tx.preparedQuery("""
                INSERT INTO claim_testimony
                      (claim_id, user_id, title, company_name, start_date, end_date,
                       period_known, still_there, occasion)
                SELECT $1, $2, $3, $4, $5, $6, $7, $8, 'initial'
                 WHERE NOT EXISTS (SELECT 1 FROM claim_testimony t WHERE t.claim_id = $1)
                RETURNING id
                """)
            .execute(Tuple.of(claimId, userId, title, companyName, startDate)
                .addValue(endDate).addValue(periodKnown).addValue(stillThere))
            .map(rs -> rs.rowCount() == 1);
    }

    /**
     * A later statement by the same contributor about the same employment.
     *
     * <p><b>This is not a correction, and the distinction is the reason the table exists.</b> In
     * 2024 somebody says they worked under this manager until 2022; in 2026 the same person says
     * until 2023. Both are evidence and neither makes the other false. Filing the second as a
     * correction would assert that the platform had decided when nobody has, and would discard the
     * first as superseded. So a repeat writes a row here, leaves every assertion column alone -
     * {@code claim_assertion_is_immutable} would refuse it anyway - and changes no resolution.
     *
     * <p><b>Only a DIFFERING statement is recorded</b>, which is also what makes this safe on the
     * replay path. A retry of a request that already committed arrives with identical values and
     * writes nothing; without that, every retried submission would append another identical row
     * and the series would record network behaviour instead of testimony.
     *
     * <p>The comparison is against the LATEST entry, not against the claim's frozen assertion.
     * Those agree for the first repeat, and for the second they do not: somebody who revises their
     * answer twice has said three things, and comparing against the original would record the
     * second revision again.
     *
     * <p><b>The title cannot differ here.</b> {@code RoleAwareSubmissionService.replay} refuses a
     * submission whose title names a different job under the same proposal key, because that is a
     * key collision rather than a recollection (issue 34). What a repeat can change is the period.
     *
     * @return true when something new was actually said
     */
    public Future<Boolean> recordRepeatTestimony(SqlClient tx, long claimId, UUID userId,
                                                 String title, String companyName,
                                                 LocalDate startDate, LocalDate endDate,
                                                 boolean periodKnown, Boolean stillThere) {
        return tx.preparedQuery("""
                INSERT INTO claim_testimony
                      (claim_id, user_id, title, company_name, start_date, end_date,
                       period_known, still_there, occasion)
                SELECT $1, $2, $3, $4, $5, $6, $7, $8, 'repeat'
                 WHERE NOT EXISTS (
                   SELECT 1 FROM (
                     SELECT t.title, t.company_name, t.start_date, t.end_date,
                            t.period_known, t.still_there
                       FROM claim_testimony t
                      WHERE t.claim_id = $1
                      ORDER BY t.said_at DESC, t.id DESC
                      LIMIT 1) latest
                    WHERE ROW(latest.title, latest.company_name, latest.start_date,
                              latest.end_date, latest.period_known, latest.still_there)
                      IS NOT DISTINCT FROM ROW($3::text, $4::text, $5::date, $6::date,
                                               $7::boolean, $8::boolean))
                RETURNING id
                """)
            .execute(Tuple.of(claimId, userId, title, companyName, startDate)
                .addValue(endDate).addValue(periodKnown).addValue(stillThere))
            .map(rs -> rs.rowCount() == 1);
    }

    /**
     * Attach an anonymous claim to the account that has just signed in.
     *
     * <p>A signed-out contributor's claim carries no {@code user_id}. When they return, sign in and
     * submit the same form, the authenticated submission attaches to that claim rather than making
     * a second one.
     *
     * <p><b>An identity may be added once and removed once, and never transferred.</b> The
     * {@code user_id IS NULL} predicate is that rule: adding is adoption, removing is account
     * deletion, and transferring is the only one of the three that is always an attack.
     *
     * @return false when the claim has already been adopted, which means this is a replay rather
     *         than a defect. The caller treats it as "already mine" only after checking it really
     *         is: see the submission path, which compares the claim's contributor with the one
     *         submitting.
     */
    public Future<Boolean> adopt(SqlClient tx, long claimId, UUID userId) {
        return tx.preparedQuery(
                "UPDATE role_claims SET user_id = $2, updated_at = now() "
                + "WHERE id = $1 AND user_id IS NULL RETURNING id")
            .execute(Tuple.of(claimId, userId))
            .map(rs -> rs.rowCount() == 1);
    }

    // -- resolution -----------------------------------------------------------

    /** How a claim came to point at an employment, and whether a human needs to look. */
    public record Resolution(long roleId, String resolution, String conflictKind, Long conflictId) {
        public boolean needsModeration() { return conflictKind != null; }
    }

    /** Thrown when the contributor's selection no longer exists or no longer fits the claim. */
    public static final class StaleSelectionException extends RuntimeException {
        public StaleSelectionException(String message) { super(message); }
    }

    /**
     * The contributor picked an existing employment. Validate it and honour it.
     *
     * <p><b>The predicates are in this statement, not in a constraint.</b> An earlier revision said
     * a constraint refused a role belonging to another manager or employer. No such constraint
     * exists and it deliberately cannot: {@code selected_role_id} references {@code career_history}
     * by id ALONE, so that a selection survives a merge that moves the role.
     *
     * <p><b>{@code FOR SHARE} in the same statement that validates</b> is what makes it safe. It
     * conflicts with the {@code FOR UPDATE} any role state change or merge takes, so once this
     * returns a row the role's manager, company and state cannot change under this transaction.
     * Validating first and locking second would leave a window; locking first and validating in a
     * second statement would be correct but redundant.
     *
     * <p>Zero rows is one of four things and the API does not distinguish them beyond a 409: no
     * such role, a role belonging to somebody else, a role at another employer, or a role already
     * rejected or superseded. Every one of them means the list they chose from is stale, so the
     * response names the selection and asks them to choose again.
     */
    public Future<Resolution> resolveBySelection(SqlConnection tx, long claimId, long managerId,
                                                 long companyId, long selectedRoleId) {
        return tx.preparedQuery("""
                SELECT id, state, manager_id, company_id
                  FROM career_history
                 WHERE id         = $1
                   AND manager_id = $2
                   AND company_id = $3
                   AND state IN ('proposed','accepted')
                 FOR SHARE
                """)
            .execute(Tuple.of(selectedRoleId, managerId, companyId))
            .compose(rs -> {
                if (rs.rowCount() == 0) {
                    return Future.failedFuture(new StaleSelectionException(
                        "The employment you selected is no longer available. Choose again."));
                }
                /*
                  ALREADY RATED THIS JOB: A DUPLICATE, NOT A FAILURE.

                  `role_claims_one_per_contributor_per_role` is one rating per person per role, and
                  it was the only thing enforcing the rule. Nothing checked first, so a second
                  submission reached the index, came back as a raw 23505, and surfaced as "We
                  couldn't save your rating" - which tells somebody the platform is broken and
                  throws away work they had just finished typing.

                  V101 says what should happen instead, in as many words: the second claim
                  "resolves as duplicate and its rating is held: preserved, not refused". So that
                  is what this does. The claim names the one it duplicates, keeps resolved_role_id
                  NULL as `claim_unlinked_has_no_role` requires, and the rating is held for a
                  moderator exactly like any other held contribution.

                  The check is inside the same transaction as the FOR SHARE above, so two
                  submissions racing cannot both pass it; and if one somehow did, the index is
                  still there to refuse the second.
                */
                return tx.preparedQuery("""
                        SELECT other.id
                          FROM role_claims me
                          JOIN role_claims other
                            ON other.user_id         = me.user_id
                           AND other.resolved_role_id = $2
                           AND other.id              <> me.id
                         WHERE me.id = $1
                           AND me.user_id IS NOT NULL
                         LIMIT 1
                        """)
                    .execute(Tuple.of(claimId, selectedRoleId))
                    .compose(dup -> {
                        if (dup.rowCount() == 1) {
                            long original = dup.iterator().next().getLong("id");
                            return markDuplicate(tx, claimId, original)
                                .map(v -> new Resolution(selectedRoleId, "duplicate", null, null));
                        }
                        // 'linked' either way. A proposed role links exactly as an accepted one
                        // does; the difference is only whether the rating can be live yet, and
                        // that is settle()'s question, not this one.
                        return link(tx, claimId, selectedRoleId, "linked")
                            .map(v -> new Resolution(selectedRoleId, "linked", null, null));
                    });
            });
    }

    /**
     * The employment is missing, so the contributor proposed it.
     *
     * <p><b>A known period deduplicates on an exact employment key and nothing else.</b> What the
     * index asserts is a fact about employment, not an inference about identity: the same person
     * cannot begin the same job at the same employer on the same date twice. Two people who worked
     * for one manager in one job produce two claims, two ratings and <b>one</b> role.
     *
     * <p><b>An unknown period is outside that index, so no deduplication applies at all</b> and
     * this always creates its own role. That is exactly where two genuine episodes could hide
     * behind identical fields, and it is handled by creating both and raising a conflict rather
     * than by guessing.
     *
     * <p>Two things must match {@code career_unique_known_employment} exactly or PostgreSQL raises
     * 42P10 and infers nothing: the conflict target names {@code start_day}, and the predicate is
     * the index's predicate verbatim. An earlier revision still said {@code state <> 'superseded'}
     * after the index had been narrowed to {@code state IN ('proposed','accepted')}, which is a
     * different predicate and would not have inferred.
     *
     * <p>The date is stored as midnight UTC rather than cast with the session's zone, so the
     * generated {@code start_day} reads back the same calendar day it was typed as.
     */
    public Future<Resolution> resolveByProposal(SqlConnection tx, long claimId, long managerId,
                                                long companyId, String title, LocalDate startDate,
                                                boolean periodKnown) {
        if (!periodKnown) {
            return tx.preparedQuery("""
                    INSERT INTO career_history
                      (manager_id, company_id, company, title, start_date, period_known,
                       source, state)
                    SELECT $1, $2, c.name, $3, NULL, FALSE, 'contributor', 'proposed'
                      FROM companies c WHERE c.id = $2
                    RETURNING id
                    """)
                .execute(Tuple.of(managerId, companyId, title))
                .map(rs -> rs.iterator().next().getLong("id"))
                .compose(roleId -> link(tx, claimId, roleId, "created")
                    .compose(v -> raiseIfAnythingResembles(tx, claimId, roleId, managerId,
                        companyId, title, null, "unknown_period_duplicate")));
        }
        if (startDate == null) {
            return Future.failedFuture(new IllegalArgumentException(
                "A known period needs a start date."));
        }
        return tx.preparedQuery("""
                INSERT INTO career_history
                  (manager_id, company_id, company, title, start_date, period_known, source, state)
                SELECT $1, $2, c.name, $3, ($4::date)::timestamp AT TIME ZONE 'UTC',
                       TRUE, 'contributor', 'proposed'
                  FROM companies c WHERE c.id = $2
                ON CONFLICT (manager_id, company_id, title_key, start_day)
                  WHERE period_known AND state IN ('proposed','accepted')
                  DO NOTHING
                RETURNING id
                """)
            .execute(Tuple.of(managerId, companyId, title, startDate))
            .compose(rs -> {
                if (rs.rowCount() == 1) {
                    long roleId = rs.iterator().next().getLong("id");
                    return link(tx, claimId, roleId, "created")
                        .compose(v -> raiseIfAnythingResembles(tx, claimId, roleId, managerId,
                            companyId, title, startDate, "possible_duplicate"));
                }
                // DO NOTHING returned nothing, which means a canonical role for this exact
                // employment key already exists: created a moment ago by a concurrent transaction,
                // or a year ago by somebody else. Re-select it and link to it. This is the one case
                // where rows are shared, and it is exact agreement on a key that identifies an
                // employment episode by definition, not a judgement about whether two descriptions
                // probably mean the same thing.
                return tx.preparedQuery("""
                        SELECT id FROM career_history
                         WHERE manager_id = $1 AND company_id = $2
                           AND title_key = normalise_title($3)
                           AND start_day = $4::date
                           AND period_known AND state IN ('proposed','accepted')
                         FOR SHARE
                        """)
                    .execute(Tuple.of(managerId, companyId, title, startDate))
                    .compose(existing -> {
                        if (existing.rowCount() == 0) {
                            // Only reachable if the row left the index between the two statements,
                            // which a state change can do. Refusing is right: the employment the
                            // contributor would be attached to is no longer one the platform
                            // recognises, and inventing a second one would be the guess this whole
                            // path exists to avoid.
                            return Future.failedFuture(new StaleSelectionException(
                                "That employment changed while you were submitting. Try again."));
                        }
                        long roleId = existing.iterator().next().getLong("id");
                        return link(tx, claimId, roleId, "linked")
                            .map(v -> new Resolution(roleId, "linked", null, null));
                    });
            });
    }


    /**
     * Resolve a claim as a duplicate of one this contributor already has on the same role.
     *
     * <p>No {@code resolved_role_id}: {@code claim_unlinked_has_no_role} forbids one on a duplicate,
     * and that is the point rather than a technicality. The role already has this person's rating;
     * this claim records that they tried again and what it duplicates, so a moderator can see both.
     */
    private Future<Void> markDuplicate(SqlConnection tx, long claimId, long originalClaimId) {
        return tx.preparedQuery("""
                UPDATE role_claims
                   SET resolution            = 'duplicate',
                       duplicate_of_claim_id = $2,
                       resolved_at           = now(),
                       updated_at            = now()
                 WHERE id = $1 AND resolved_at IS NULL
                """)
            .execute(Tuple.of(claimId, originalClaimId))
            .mapEmpty();
    }

    /**
     * Record the platform's answer on the claim.
     *
     * <p>{@code selected_role_id} is NEVER written here. It is what the contributor chose;
     * {@code resolved_role_id} is what the platform did about it, and
     * {@code claim_selection_is_honoured_or_canonicalized} is the constraint that stops a
     * descriptive match overriding an explicit choice. Collapsing the two columns would erase the
     * distinction the whole redesign rests on.
     */
    private Future<Void> link(SqlConnection tx, long claimId, long roleId, String resolution) {
        return tx.preparedQuery("""
                UPDATE role_claims
                   SET resolved_role_id = $2, resolution = $3, resolved_at = now(),
                       updated_at = now()
                 WHERE id = $1 AND resolved_at IS NULL
                """)
            .execute(Tuple.of(claimId, roleId, resolution))
            /*
              A LINKED CLAIM IS EVIDENCE, so the role's status is re-derived here. This is the one
              funnel both resolution paths go through, by selection and by proposal, which is why
              the hook is here rather than in each of them.

              A proposed role resolves to nothing: the resolver only touches accepted rows, so the
              claim waits and the status arrives when a moderator accepts the employment. That
              ordering is the point. Nothing a contributor says publishes a status on an employment
              the platform has not accepted.
            */
            .compose(rs -> rs.rowCount() == 1
                ? EmploymentStatusResolver.resolveRole(tx, roleId)
                : Future.<Void>failedFuture(new IllegalStateException(
                    "Claim " + claimId + " is already resolved; resolving twice would rewrite "
                    + "what the platform decided about a saved observation.")));
    }

    /**
     * Raise a conflict when something already resembles the role just created.
     *
     * <p>Resemblance is the same manager, employer and normalised title. It deliberately does NOT
     * decide anything: the new role stands either way, and the conflict is a request for a human
     * to look. {@code possible_duplicate} blocks publication, because two canonical employments
     * that may be one is exactly the ambiguity a public rating must not be attributed through.
     * {@code unknown_period_duplicate} does not block, because an unknown period is a gap in
     * evidence rather than a dispute about identity.
     *
     * <p>The claim is attached as {@code subject}: it is the contribution in doubt, not evidence
     * about somebody else's. That distinction is load-bearing, and getting it wrong withdrew a
     * rating the merge had deliberately kept live.
     */
    private Future<Resolution> raiseIfAnythingResembles(SqlConnection tx, long claimId, long roleId,
                                                        long managerId, long companyId,
                                                        String title, LocalDate startDate,
                                                        String kind) {
        return tx.preparedQuery("""
                SELECT ch.id FROM career_history ch
                 WHERE ch.manager_id = $1 AND ch.company_id = $2
                   AND ch.title_key = normalise_title($3)
                   AND ch.id <> $4
                   AND ch.state IN ('proposed','accepted')
                 ORDER BY ch.id LIMIT 1
                """)
            .execute(Tuple.of(managerId, companyId, title, roleId))
            .compose(rs -> {
                if (rs.rowCount() == 0) {
                    return Future.succeededFuture(new Resolution(roleId, "created", null, null));
                }
                long otherRoleId = rs.iterator().next().getLong("id");
                return tx.preparedQuery("""
                        INSERT INTO role_conflicts
                              (kind, manager_id, company_id, title_key, period_key,
                               role_id, other_role_id, manager_name, company_name, title, period)
                        SELECT $1, $2, $3, normalise_title($4),
                               COALESCE($5::date::text,'unknown'), $6, $7,
                               m.name, c.name, $4, COALESCE($5::date::text,'unknown')
                          FROM managers m, companies c
                         WHERE m.id = $2 AND c.id = $3
                        RETURNING id
                        """)
                    .execute(Tuple.of(kind, managerId, companyId, title)
                        .addValue(startDate).addValue(roleId).addValue(otherRoleId))
                    .compose(ins -> {
                        long conflictId = ins.iterator().next().getLong("id");
                        return tx.preparedQuery(
                                "INSERT INTO role_conflict_claims (conflict_id, claim_id, role) "
                                + "VALUES ($1,$2,'subject')")
                            .execute(Tuple.of(conflictId, claimId))
                            .map(v -> new Resolution(roleId, "created", kind, conflictId));
                    });
            });
    }

    // -- finalization ---------------------------------------------------------

    /**
     * Seal the claim, naming the revision it is sealing.
     *
     * <p><b>Folding this into the upsert was wrong.</b> {@code finalized_at = COALESCE(...)} would
     * finalize a draft from a stale request whose field updates the revision guard had just
     * rejected: the request would have had no effect on the content and yet would have sealed it.
     *
     * <p><b>Zero rows means refuse the submission.</b> The draft moved under the request: a newer
     * autosave landed, or the contributor changed the person or the role in another tab. The client
     * resyncs and resubmits rather than the server sealing a version nobody confirmed.
     *
     * <p>{@code IS NOT DISTINCT FROM} and not {@code =}, because {@code selected_role_id} is
     * legitimately null when the contributor proposed a role, and {@code null = null} is not true.
     *
     * <p>This runs in the same transaction as the review insert, so a rating and the finalization
     * of the claim it is about commit together or not at all.
     */
    public Future<Optional<Row>> finalizeClaim(SqlConnection tx, long claimId, long expectedRevision,
                                               long expectedManagerId, Long expectedSelectedRoleId) {
        return tx.preparedQuery("""
                UPDATE role_claims
                   SET finalized_at        = now(),
                       original_manager_id = manager_id,
                       updated_at          = now()
                 WHERE id = $1
                   AND finalized_at IS NULL
                   AND revision = $2
                   AND manager_id = $3
                   AND selected_role_id IS NOT DISTINCT FROM $4
                RETURNING id, manager_id, original_manager_id, selected_role_id, revision,
                          resolved_role_id, resolution
                """)
            .execute(Tuple.of(claimId, expectedRevision, expectedManagerId)
                .addValue(expectedSelectedRoleId))
            .map(rs -> rs.rowCount() == 1
                ? Optional.of(rs.iterator().next())
                : Optional.empty());
    }

    // -- publication ----------------------------------------------------------

    /**
     * Promote a freshly inserted rating to live if, and only if, the database says it may be.
     *
     * <p><b>The rating is always inserted held and promoted here.</b> That is deliberate: the rule
     * for whether a claimed rating may be public has three parts (its claim resolved, its role
     * accepted, and no open blocking conflict naming its claim as subject), it lives in
     * {@code rating_is_publishable}, and five triggers already quote it. Deciding it a second time
     * in Java would be a second source of truth for the exact question this redesign exists to
     * answer correctly, and the two would drift.
     *
     * <p>Going through {@code ReviewDisposition} rather than writing {@code disposition} directly
     * keeps {@code gate_eligible} and {@code live_since} coherent and keeps the location projection
     * correct. Writing the column by hand here is how that projection broke before.
     *
     * @return true when the rating is now live, false when it stays held for a moderator.
     */
    public Future<Boolean> settle(SqlConnection tx, UUID reviewId, ReviewDisposition dispositions) {
        return tx.preparedQuery("SELECT rating_role_is_publishable($1) AS ok")
            .execute(Tuple.of(reviewId))
            .compose(rs -> {
                // IS TRUE, not merely the boolean. The function is total by construction and the
                // call sites assert it anyway, so that a future edit reintroducing a nullable
                // branch still fails closed rather than quietly publishing.
                if (!Boolean.TRUE.equals(rs.iterator().next().getBoolean("ok"))) {
                    return Future.succeededFuture(false);
                }
                return dispositions.set(tx, reviewId, "live").map(v -> true);
            });
    }

    /**
     * Why a LIVE rating should not be live, in the database's own words.
     *
     * <p><b>This is a diagnostic for an invalid state, not the eligibility question</b>, and the
     * difference caught a real bug here. Both {@code rating_is_publishable} and
     * {@code rating_publication_problem} are guards on the live state: their first branch is
     * {@code NOT (disposition = 'live' AND deleted_at IS NULL)}, which for a HELD rating makes the
     * predicate trivially TRUE and the problem trivially NULL.
     *
     * <p>So asking either of them "may this held rating go live" answers yes, always. A
     * freshly-inserted held rating promoted on that answer goes live against a proposed role, and
     * the trigger then refuses the whole submission:
     *
     * <pre>
     * ERROR:  claim 4 leaves review 1c2d8c85... live: its role is proposed (P0001)
     * </pre>
     *
     * <p>{@link #settle} therefore asks {@code rating_role_is_publishable}, which is the same rule
     * with no disposition guard in front of it, and is the actual question.
     *
     * <p>This method stays because it is the right tool for its own job: a rating that is live and
     * should not be, which is what the moderation queue and the sweep want explained.
     */
    public Future<String> publicationProblem(SqlClient conn, UUID reviewId) {
        return conn.preparedQuery("SELECT rating_publication_problem($1) AS problem")
            .execute(Tuple.of(reviewId))
            .map(rs -> rs.iterator().next().getString("problem"));
    }

    /**
     * What is standing between a held rating and publication, for the contributor and the queue.
     *
     * <p>Built from the state the resolution already established rather than re-deriving the
     * publication rule in a second place. A copy of that CASE ladder would be a second source of
     * truth for the one question this redesign exists to answer correctly, and it would drift.
     */
    public Future<String> heldReason(SqlClient conn, UUID reviewId) {
        return conn.preparedQuery("""
                SELECT c.resolution, ch.state AS role_state,
                       (SELECT cf.kind FROM role_conflict_claims rcc
                          JOIN role_conflicts cf ON cf.id = rcc.conflict_id
                         WHERE rcc.claim_id = c.id AND rcc.role = 'subject'
                           AND cf.blocks_publication AND cf.resolved_at IS NULL
                         LIMIT 1) AS blocking_kind
                  FROM reviews r
                  LEFT JOIN role_claims    c  ON c.id  = r.claim_id
                  LEFT JOIN career_history ch ON ch.id = c.resolved_role_id
                 WHERE r.id = $1
                """)
            .execute(Tuple.of(reviewId))
            .map(rs -> {
                Row r = rs.iterator().next();
                if (r.getString("blocking_kind") != null) {
                    return "it is the subject of an open " + r.getString("blocking_kind")
                           + " conflict, which a moderator has to settle";
                }
                String resolution = r.getString("resolution");
                if (resolution == null) {
                    return "its employment claim has not been resolved yet";
                }
                String roleState = r.getString("role_state");
                if (roleState == null) {
                    return "its employment claim resolves to no employment record";
                }
                if (!"accepted".equals(roleState)) {
                    return "the employment it names is " + roleState
                           + ", so it waits for a moderator to accept it";
                }
                return null;
            });
    }
}
