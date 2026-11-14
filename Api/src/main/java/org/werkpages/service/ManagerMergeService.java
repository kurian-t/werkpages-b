package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import org.werkpages.repository.EmploymentStatusResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Merging two manager profiles, and merging two employment roles.
 *
 * <h2>What this replaces, and why it had to be replaced before role-bearing ratings go live</h2>
 *
 * <p>{@code ManagerRepository.mergeInto} repointed {@code reviews.manager_id} at the survivor,
 * skipped any rating that would collide on company, title and user-or-author, soft-deleted those,
 * and reported them only as a count of "parked". It touched {@code career_history} zero times. So
 * it moved ratings, abandoned jobs, and took real opinions out of public view over a conflict
 * nobody was ever shown.
 *
 * <p>It also becomes actively unsafe the moment any rating carries a claim.
 * {@code reviews_claim_is_the_managers} is DEFERRABLE INITIALLY IMMEDIATE, and that merge updated
 * {@code reviews.manager_id} while touching neither {@code role_claims.manager_id} nor
 * {@code career_history.manager_id}. Every moved rating would then name a claim whose manager is
 * still the absorbed profile, the key would refuse it, and the merge would fail outright. That is
 * why the merge is rebuilt in phase 3, while nothing yet writes a claim: there is nothing to
 * reconcile, so the rebuild can be proved against constructed data rather than discovered against
 * real data.
 *
 * <h2>The promise</h2>
 *
 * <p><b>Nothing a person wrote is deleted, and nothing is silently removed from view.</b> A rating
 * that cannot stay live is <em>held</em> and marked with what it collided with; the row moves to
 * the survivor with everything else. A role that has an equivalent on the survivor is superseded
 * and <em>moved</em>, because it is still that person's history. A claim keeps
 * {@code selected_role_id} and {@code original_manager_id} for ever: what the contributor chose,
 * and the profile they chose it against, survive any number of merges.
 *
 * <h2>Why one transaction, and why constraints are deferred rather than ordered around</h2>
 *
 * <p>There is no update order whose intermediate state satisfies the composite keys: whichever
 * side moves first leaves rows whose parent does not yet carry the new {@code manager_id}.
 * Deferring is the correct answer rather than a workaround, and the three composites are set
 * DEFERRED for the length of the transaction so the end state is what gets checked.
 *
 * <h2>The lock protocol</h2>
 *
 * <p>Acquisition follows the canonical order - companies, managers, career_history, role_claims,
 * captured_drafts, capture_attempts, reviews, role_conflicts, role_conflict_claims, then the
 * aggregates - and ascending id within a table. Ascending id alone is not enough: it stops two
 * transactions of the same shape from deadlocking and says nothing about two that visit the same
 * tables in a different sequence. A submission that upserted its claim and then locked the role
 * deadlocked against a merge that locked roles and then claims, and neither transaction was wrong
 * on its own.
 *
 * <p>Roles are taken FOR UPDATE, which is the state-change side of the protocol and conflicts with
 * the FOR SHARE a publication takes, so a merge and a publication of the same role serialise.
 *
 * <p>A deadlock or serialization failure retries the whole transaction, at most three times, with
 * jittered backoff. Nothing is partially applied because each attempt is one transaction. After
 * the final attempt the caller gets the error: the one thing never done is catching it and
 * continuing, which would leave the roles moved and the ratings behind.
 */
public final class ManagerMergeService {

    /** Rev 268: "at most three times, with jittered backoff". */
    private static final int MAX_ATTEMPTS = 3;

    private static final String DEADLOCK_DETECTED      = "40P01";
    private static final String SERIALIZATION_FAILURE  = "40001";

    private final Pool db;

    /**
     * Disposition is NEVER written here in raw SQL, and that is not a style preference.
     * {@link ReviewDisposition} owns the column together with {@code gate_eligible},
     * {@code live_since} and the location-statistics projection. Three files used to write the
     * column directly and none of them met the projection obligation, so holding a rating left it
     * counted in the location figures it had just been removed from. A merge that held ratings in
     * raw SQL would reintroduce exactly that, on the path that holds the most ratings at once.
     */
    private final ReviewDisposition dispositions = new ReviewDisposition();

    public ManagerMergeService(SqlClient db) {
        this.db = (Pool) db;
    }

    // ── Manager merge ─────────────────────────────────────────────────────────

    /**
     * Merges {@code absorbedId} into {@code survivorId}, or changes nothing.
     *
     * @return the manifest: what moved, what was superseded, what was redirected, what is held.
     *         An admin has to be able to say which happened, because a merge that silently picks
     *         a winner between two ratings looks exactly like a merge that lost one.
     */
    public Future<JsonObject> mergeManagers(long survivorId, long absorbedId, UUID adminId) {
        if (survivorId == absorbedId) {
            return Future.failedFuture(new IllegalArgumentException(
                "A manager cannot be merged into itself"));
        }
        return withRetry(() -> db.withTransaction(conn ->
            mergeManagersOnce(conn, survivorId, absorbedId, adminId)), 1);
    }

    private Future<JsonObject> mergeManagersOnce(SqlConnection conn, long survivor, long absorbed,
                                                 UUID adminId) {
        Counts n = new Counts();
        return deferTheComposites(conn)
            // 2. managers, BOTH identities, ascending id, so two concurrent merges over an
            //    overlapping pair cannot deadlock. FOR UPDATE and not FOR SHARE: this transaction
            //    ends by rewriting both managers' cached aggregates, and acquiring share early to
            //    upgrade later is its own deadlock.
            .compose(v -> conn.preparedQuery("""
                    SELECT id, name, slug FROM managers
                     WHERE id IN ($1, $2) ORDER BY id FOR UPDATE
                    """).execute(Tuple.of(survivor, absorbed)))
            .compose(locked -> {
                if (locked.rowCount() < 2) {
                    return Future.failedFuture(new IllegalStateException(
                        "One or both managers no longer exist, so the merge was cancelled."));
                }
                String survivorName = null, absorbedName = null, absorbedSlug = null;
                for (Row r : locked) {
                    if (r.getLong("id") == survivor) survivorName = r.getString("name");
                    else { absorbedName = r.getString("name"); absorbedSlug = r.getString("slug"); }
                }
                // 2a. THE EVENT IS WRITTEN NOW, not at the end, because
                //     claim_resolution_events.merge_event_id references it and the audit trigger
                //     fires during the claim redirection below. Writing it last left that foreign
                //     key unresolvable. Its counts start at zero and are rewritten at the end:
                //     they are NOT NULL, and nothing knows them yet.
                return conn.preparedQuery("""
                        INSERT INTO manager_merge_events
                            (survivor_id, absorbed_id, survivor_name, absorbed_name, absorbed_slug,
                             roles_moved, roles_superseded, claims_moved, claims_redirected,
                             ratings_moved, ratings_held, merged_by)
                        VALUES ($1,$2,$3,$4,$5,0,0,0,0,0,0,$6) RETURNING id
                        """)
                    .execute(Tuple.of(survivor, absorbed, survivorName,
                                      absorbedName, absorbedSlug == null ? "" : absorbedSlug,
                                      adminId))
                    .map(rs -> rs.iterator().next().getLong("id"));
            })
            .compose(eventId -> {
                n.eventId = eventId;
                // Read by record_claim_resolution(), which stamps every event it writes during
                // this transaction with the merge that caused it.
                return conn.preparedQuery("SELECT set_config('rmm.merge_event_id', $1, true)")
                    .execute(Tuple.of(String.valueOf(eventId)));
            })
            // 3, 4. career_history then role_claims, the canonical order, ascending id.
            .compose(v -> conn.preparedQuery("""
                    SELECT id FROM career_history WHERE manager_id IN ($1, $2)
                     ORDER BY id FOR UPDATE
                    """).execute(Tuple.of(survivor, absorbed)))
            .compose(v -> conn.preparedQuery("""
                    SELECT id FROM role_claims WHERE manager_id IN ($1, $2)
                     ORDER BY id FOR UPDATE
                    """).execute(Tuple.of(survivor, absorbed)))
            .compose(v -> planEquivalentRoles(conn, survivor, absorbed))
            // 4. EVERY claim moves, resolved or not. An unresolved claim on the absorbed manager
            //    is still an assertion about that person, and that person is now the survivor.
            //    original_manager_id and selected_role_id are not touched anywhere in here.
            .compose(v -> conn.preparedQuery(
                    "UPDATE role_claims SET manager_id = $1, updated_at = now() WHERE manager_id = $2")
                .execute(Tuple.of(survivor, absorbed)))
            .compose(moved -> { n.claimsMoved = moved.rowCount(); return moveRoles(conn, survivor, absorbed, n); })
            .compose(v -> redirectClaims(conn, adminId, n))
            // 4f. THE RATING ALLOWANCE, at role_claims' position in the canonical order, which is
            //     where rev 268 puts it. It must run while opportunity_one_per_ordinal is deferred.
            .compose(v -> reconcileOpportunities(conn, survivor, absorbed, n))
            .compose(v -> moveDraftsAndAttempts(conn, survivor, absorbed))
            .compose(v -> reconcileLegacyCollisions(conn, survivor, absorbed, adminId, n))
            // 6b. THE SYNTHETIC SEED, retired BEFORE anything moves. The order is the whole point:
            //     the losing seed is retired while it still belongs to the absorbed manager, so the
            //     narrowed unique index is already free when the reviews move below.
            .compose(v -> retireAbsorbedSeed(conn, survivor, absorbed, n))
            // 7. reviews. Legacy ratings with no claim move by manager_id, as they always did.
            .compose(v -> conn.preparedQuery(
                    "UPDATE reviews SET manager_id = $1 WHERE manager_id = $2")
                .execute(Tuple.of(survivor, absorbed)))
            .compose(moved -> { n.ratingsMoved = moved.rowCount(); return holdDuplicateClaimRatings(conn, n); })
            .compose(v -> reconcileConflicts(conn, survivor, absorbed, adminId, n))
            .compose(v -> openDuplicateRatingTasks(conn, survivor, adminId, n))
            .compose(v -> resolveMergedEmployment(conn))
            // Identity. Kept, never deleted: reviews.manager_id cascades, and deleting the row is
            // what used to destroy everything that could not move.
            .compose(v -> conn.preparedQuery("""
                    UPDATE managers SET approval_status = 'rejected', merged_into = $2,
                                        updated_at = now()
                     WHERE id = $1
                    """).execute(Tuple.of(absorbed, survivor)))
            // 10. AGGREGATES, for BOTH managers, through the same recalculation every other path
            //     uses, on THIS connection. The manager rows are already held FOR UPDATE from
            //     step 2, so this takes no new lock and cannot reorder against anything.
            .compose(v -> recalculate(conn, survivor))
            .compose(v -> recalculate(conn, absorbed))
            .compose(v -> conn.preparedQuery("""
                    UPDATE manager_merge_events
                       SET roles_moved = $2, roles_superseded = $3, claims_moved = $4,
                           claims_redirected = $5, ratings_moved = $6, ratings_held = $7
                     WHERE id = $1
                    """).execute(Tuple.of(n.eventId, n.rolesMoved, n.rolesSuperseded,
                                          n.claimsMoved, n.claimsRedirected, n.ratingsMoved,
                                          n.ratingsHeld)))
            .map(v -> n.toJson());
    }

    /**
     * SET CONSTRAINTS for the four keys a merge legitimately passes through an invalid intermediate
     * state for.
     *
     * <p>FOUR, as rev 268 lists. Three are the composite foreign keys that tie a rating's claim and
     * withdrawn role to its manager. The fourth, {@code opportunity_one_per_ordinal}, is different
     * in kind: it is a unique constraint, and it is deferred because the opportunity
     * move-and-renumber is checked DURING the move rather than after it. A contributor holding
     * ordinal 1 on both subjects collides the instant the first row moves, so "move then renumber"
     * cannot execute at all without this.
     *
     * <p>It arrived with V104 (phase 4). Phase 3 shipped with three because the table did not yet
     * exist and deferring a constraint that does not exist is an error.
     */
    private Future<RowSet<Row>> deferTheComposites(SqlConnection conn) {
        return conn.query("""
                SET CONSTRAINTS reviews_claim_is_the_managers,
                                reviews_withdrawn_role_is_the_managers,
                                claim_resolved_role_matches,
                                opportunity_one_per_ordinal DEFERRED
                """).execute();
    }

    /**
     * The rating allowance, reconciled across both profiles.
     *
     * <p><b>A merge must not hand anybody a fresh allowance, and duplicate profiles are exactly how
     * that would happen.</b> Two opportunities per contributor per subject is the policy, so a
     * contributor who exercised two on each of what turns out to be one person is capped at two,
     * and the excess is recorded rather than deleted.
     *
     * <p><b>Why the survivor's own rows are in the plan.</b> The ranking is by when each opportunity
     * was actually opened, not by which profile happened to win the merge, so a survivor
     * opportunity can be retired in favour of an earlier absorbed one. That is what makes 2+1 and
     * 2+2 correct: the policy counts the two earliest exercises, not the two that sat on the
     * surviving row.
     *
     * <p><b>Why this is not an UPDATE with a renumber afterwards.</b> That was specified and it is
     * not executable: the unique constraint is checked during the move. It is deferred above, which
     * lets intermediate duplicates exist so that only the committed state is checked, and the move
     * and the renumber then happen in ONE statement.
     *
     * <p><b>Why a temp table rather than a CTE.</b> Three statements need the same ranking, and a
     * CTE lives for exactly one statement.
     *
     * <p><b>Idempotency, by construction rather than by a provenance column.</b> Re-running after a
     * successful merge matches only the survivor's rows, reproduces the same {@code rn} from the
     * same {@code (opened_at, id)}, finds nothing above 2 to retire, and rewrites the values
     * already present. The retirement table is append-only, so a second run cannot duplicate an
     * audit row either, because it inserts none. This is why the table needs no identity column,
     * and it is why the three columns that were proposed as one are absent: each held a rating id
     * next to a contributor id, which recovers the author of an anonymised rating in a single join.
     *
     * <p><b>Retiring an opportunity does not retire a rating.</b> Stated because conflating them
     * would corrupt the figures. Every historical rating stays exactly as it is and remains
     * publicly counted against the survivor; this table governs only how many times somebody may
     * submit in future.
     */
    private Future<Void> reconcileOpportunities(SqlConnection conn, long survivor, long absorbed,
                                                Counts n) {
        return conn.query("""
                CREATE TEMP TABLE opp_plan (
                  opp_id  BIGINT PRIMARY KEY,
                  user_id UUID   NOT NULL,
                  rn      INT    NOT NULL
                ) ON COMMIT DROP
                """).execute()
            // 1. PLAN. The whole candidate set per contributor across BOTH subjects, ranked by the
            //    order the opportunities were actually opened.
            .compose(v -> conn.preparedQuery("""
                    INSERT INTO opp_plan (opp_id, user_id, rn)
                    SELECT o.id, o.user_id,
                           row_number() OVER (PARTITION BY o.user_id
                                                  ORDER BY o.opened_at, o.id)
                      FROM rating_opportunities o
                     WHERE o.subject_type = 'manager'
                       AND o.subject_id IN ($1, $2)
                    """).execute(Tuple.of(survivor, absorbed)))
            // 2. RECORD what will not fit, BEFORE removing it. The event id is passed explicitly
            //    rather than read from the GUC: this statement is the merge's own, so the value is
            //    in hand, and an empty GUC cast to BIGINT is the defect phase 3 had to correct in
            //    record_claim_resolution.
            .compose(v -> conn.preparedQuery("""
                    INSERT INTO rating_opportunity_retirements
                           (user_id, subject_type, former_subject_id, surviving_subject_id,
                            former_ordinal, opened_at, manager_merge_event_id)
                    SELECT o.user_id, o.subject_type, o.subject_id, $1,
                           o.ordinal, o.opened_at, $2
                      FROM rating_opportunities o JOIN opp_plan p ON p.opp_id = o.id
                     WHERE p.rn > 2
                    """).execute(Tuple.of(survivor, n.eventId)))
            .compose(retired -> {
                n.opportunitiesRetired = retired.rowCount();
                return conn.query("""
                        DELETE FROM rating_opportunities o USING opp_plan p
                         WHERE o.id = p.opp_id AND p.rn > 2
                        """).execute();
            })
            // 3. MOVE AND RENUMBER IN ONE STATEMENT. Safe only because the constraint is deferred.
            .compose(v -> conn.preparedQuery("""
                    UPDATE rating_opportunities o
                       SET subject_id = $1,
                           ordinal    = p.rn
                      FROM opp_plan p
                     WHERE o.id = p.opp_id AND p.rn <= 2
                    """).execute(Tuple.of(survivor)))
            .compose(moved -> {
                n.opportunitiesMoved = moved.rowCount();
                // The temp table is ON COMMIT DROP, and a retry runs in a new transaction, so
                // there is nothing to clean up here. Dropped explicitly all the same, because a
                // merge of roles can run on the same pooled connection within one transaction.
                return conn.query("DROP TABLE IF EXISTS opp_plan").execute();
            })
            /*
              4. AND THE PARTICIPATION RECORDS, which must move or the merge hands back a rating
                 the contributor already relinquished.

              The failure without this: a contributor unlinks their rating of manager A, so they
              may never rate A again. An administrator then merges A into B. The participation row
              still names A, which no longer exists as a subject anybody can rate, and nothing
              names B - so the contributor may now rate B, which IS the manager they relinquished.
              Duplicate prevention would survive exactly until the next merge.

              Simpler than the opportunity move above because there is no ordinal to renumber: the
              key is (user, subject_type, subject_id) and the only question on collision is which
              flags survive.

                gate_eligible  OR, because either contribution earning the gate is enough
                unlinked_at    the EARLIEST non-null, because relinquishment is not undone by
                               having also participated elsewhere, and the first forfeit is the
                               one that happened
                first_rated_at the earliest, same reasoning
            */
            .compose(v -> conn.preparedQuery("""
                    INSERT INTO rating_participation
                           (user_id, subject_type, subject_id, first_rated_at, gate_eligible, unlinked_at)
                    SELECT p.user_id, 'manager', $1, p.first_rated_at, p.gate_eligible, p.unlinked_at
                      FROM rating_participation p
                     WHERE p.subject_type = 'manager' AND p.subject_id = $2
                    ON CONFLICT (user_id, subject_type, subject_id) DO UPDATE
                       SET gate_eligible  = rating_participation.gate_eligible OR excluded.gate_eligible,
                           unlinked_at    = LEAST(rating_participation.unlinked_at, excluded.unlinked_at),
                           first_rated_at = LEAST(rating_participation.first_rated_at, excluded.first_rated_at)
                    """).execute(Tuple.of(survivor, absorbed)))
            // The absorbed subject's rows are now redundant: the survivor carries the merged fact.
            .compose(v -> conn.preparedQuery(
                    "DELETE FROM rating_participation WHERE subject_type = 'manager' AND subject_id = $1")
                .execute(Tuple.of(absorbed)))
            .mapEmpty();
    }

    /**
     * Which absorbed roles have an equivalent on the survivor.
     *
     * <p><b>Equivalence is exactly what {@code career_unique_known_employment} would refuse</b>:
     * the same employer, the same normalised title and the same canonical calendar day, among rows
     * the index actually covers - a known period, in state proposed or accepted. That is a
     * deliberate choice of the narrowest defensible rule. Two unknown-period roles at one employer
     * under one title are NOT equivalent, because an unknown date is not evidence of sameness and
     * the index lets them coexist; calling them equivalent would merge employment records the
     * database is content to keep apart, which is the kind of guess this whole design exists to
     * remove.
     *
     * <p>A temp table rather than a CTE: four later statements read this mapping, and a CTE lives
     * for exactly one statement.
     */
    private Future<Void> planEquivalentRoles(SqlConnection conn, long survivor, long absorbed) {
        return conn.query("""
                CREATE TEMP TABLE role_plan (
                  absorbed_role   BIGINT PRIMARY KEY,
                  equivalent_role BIGINT NOT NULL
                ) ON COMMIT DROP
                """).execute()
            .compose(v -> conn.preparedQuery("""
                    INSERT INTO role_plan (absorbed_role, equivalent_role)
                    SELECT a.id, s.id
                      FROM career_history a
                      JOIN career_history s
                        ON s.manager_id = $1 AND a.manager_id = $2
                       AND s.company_id = a.company_id
                       AND s.title_key  = a.title_key
                       AND s.start_day  = a.start_day
                       AND s.period_known AND a.period_known
                       AND s.state IN ('proposed','accepted')
                       AND a.state IN ('proposed','accepted')
                    """).execute(Tuple.of(survivor, absorbed)))
            .mapEmpty();
    }

    private Future<Void> moveRoles(SqlConnection conn, long survivor, long absorbed, Counts n) {
        // 4a. A role with no equivalent on the survivor: it moves, unchanged.
        return conn.preparedQuery("""
                UPDATE career_history SET manager_id = $1, updated_at = now()
                 WHERE manager_id = $2
                   AND id NOT IN (SELECT absorbed_role FROM role_plan)
                """).execute(Tuple.of(survivor, absorbed))
            .compose(moved -> {
                n.rolesMoved = moved.rowCount();
                // 4b. An equivalent exists. The absorbed role is retired AND MOVED. Retiring it
                //     without moving manager_id would leave it owned by the absorbed profile while
                //     the claims pointing at it have moved to the survivor, which
                //     claim_resolved_role_matches refuses. A retired role is still that person's
                //     history, and the person is now the survivor.
                return conn.preparedQuery("""
                        UPDATE career_history
                           SET manager_id = $1, state = 'superseded',
                               superseded_by = p.equivalent_role, updated_at = now()
                          FROM role_plan p
                         WHERE career_history.id = p.absorbed_role
                        """).execute(Tuple.of(survivor));
            })
            .map(superseded -> { n.rolesSuperseded = superseded.rowCount(); return null; });
    }

    /**
     * Re-derive the employment status of every role this merge redirected evidence onto.
     *
     * <h2>Why a merge has to do this at all</h2>
     *
     * <p>A merge moves claims. {@link #redirectClaims} points a contributor's resolved claim at
     * the survivor's equivalent role, and the duplicate handling below unlinks the ones that
     * could not move. Both change what the surviving role's employment is grounded IN, and a
     * status resolved before the merge was resolved without that evidence.
     *
     * <p>Leaving it is a stale assertion about somebody's present employment: the role goes on
     * saying what it said while a resolved claim sits pointing straight at it, until some
     * unrelated acceptance or correction happens to touch the same row. That is the failure the
     * evidence-dated model exists to prevent, and a merge is exactly the operation that creates
     * it, because it is the one that moves evidence between roles.
     *
     * <p>It can also produce a CONTRADICTION that did not exist before: the survivor's record
     * says the job ended, and the claim that just arrived says the manager was still there later.
     * Re-deriving raises the dispute, which is the only honest outcome. A merge does not get to
     * pick a side, and nor does it get to leave the question unasked.
     *
     * <h2>Which roles</h2>
     *
     * <p>Every {@code equivalent_role} in {@code role_plan}, which is the plan both merges build:
     * those are the roles that gained or lost claims. A role that merely MOVED keeps its own
     * evidence and its own answer, so it is deliberately not re-derived.
     *
     * <p>Last, after the conflict reconciliation, so a dispute raised here is a new question
     * about the merged state rather than something the reconciliation then tries to fold.
     */
    private Future<Void> resolveMergedEmployment(SqlConnection conn) {
        return conn.query("SELECT DISTINCT equivalent_role FROM role_plan ORDER BY 1")
            .execute()
            .compose(rows -> {
                Future<Void> chain = Future.succeededFuture();
                for (Row r : rows) {
                    long roleId = r.getLong("equivalent_role");
                    chain = chain.compose(v -> EmploymentStatusResolver.resolveRole(conn, roleId));
                }
                return chain;
            });
    }

    private Future<Void> redirectClaims(SqlConnection conn, UUID adminId, Counts n) {
        // 4c. Redirect the claims that resolved to a now-superseded role. This is a
        //     CANONICALIZATION, so it is audited rather than forbidden:
        //     claim_selection_is_honoured_or_canonicalized would otherwise refuse a claim that
        //     selected role #10 resolving to role #20, and setting the three canonicalized_*
        //     columns is what makes it legal. selected_role_id stays exactly as it was, so the
        //     contributor's own choice and the platform's current answer are both readable with
        //     the link between them explicit.
        //
        //     Set-based over role_plan rather than rev 268's per-pair statement, which is the same
        //     statement run once per equivalence instead of once per merge.
        //
        //     The exclusion is the contributor who ALREADY holds a claim on the equivalent role:
        //     redirecting theirs would violate role_claims_one_per_contributor_per_role. Those are
        //     handled immediately below.
        return conn.preparedQuery("""
                UPDATE role_claims c
                   SET resolved_role_id   = p.equivalent_role,
                       canonicalized_at   = now(),
                       canonicalized_by   = $1,
                       canonicalized_from = p.absorbed_role,
                       updated_at         = now()
                  FROM role_plan p
                 WHERE c.resolved_role_id = p.absorbed_role
                   AND (c.user_id IS NULL OR NOT EXISTS (
                         SELECT 1 FROM role_claims t
                          WHERE t.resolved_role_id = p.equivalent_role
                            AND t.user_id = c.user_id
                            AND t.id <> c.id))
                """).execute(Tuple.of(adminId))
            .compose(redirected -> {
                n.claimsRedirected = redirected.rowCount();
                // 4c-bis. WITHDRAWN RATINGS FOLLOW CANONICALIZATION TOO. They have no claim, so
                //     the statement above cannot reach them, and one left pointing at a superseded
                //     role is attributed to a role nothing else uses. The role trigger would then
                //     hold it - correctly, but for the wrong reason.
                return conn.query("""
                        UPDATE reviews r
                           SET withdrawn_role_id = p.equivalent_role
                          FROM role_plan p
                         WHERE r.withdrawn_role_id = p.absorbed_role
                        """).execute();
            })
            .compose(v -> conn.query("""
                    CREATE TEMP TABLE dup_claims (claim_id BIGINT PRIMARY KEY) ON COMMIT DROP
                    """).execute())
            // 4e. A claim that could not be redirected is marked duplicate and UNLINKED, so
            //     nothing points at a superseded role. IT MUST ALSO NAME ITS TARGET:
            //     claim_duplicate_names_its_target refuses resolution 'duplicate' with a null
            //     duplicate_of_claim_id outright, so the earlier version of this statement could
            //     not run at all. The target is by construction the same contributor's claim on
            //     the equivalent role - the one that blocked the redirect above.
            //
            //     The audit row is written by record_claim_resolution(), which DERIVES the event
            //     kind from the transition: a source role and no destination is 'unlinked'. That
            //     derivation is what lets a merge unlink a duplicate without failing the audit
            //     that records it.
            .compose(v -> conn.preparedQuery("""
                    WITH leftovers AS (
                      SELECT x.id,
                             (SELECT k.id FROM role_claims k
                               WHERE k.resolved_role_id = p.equivalent_role
                                 AND k.user_id = x.user_id AND k.id <> x.id
                               ORDER BY k.id LIMIT 1) AS keeper
                        FROM role_claims x
                        JOIN role_plan p ON x.resolved_role_id = p.absorbed_role
                    ), upd AS (
                      UPDATE role_claims a
                         SET resolved_role_id      = NULL,
                             resolution            = 'duplicate',
                             duplicate_of_claim_id = l.keeper,
                             resolved_at           = now(),
                             resolved_by           = $1,
                             updated_at            = now()
                        FROM leftovers l
                       WHERE a.id = l.id
                       RETURNING a.id
                    )
                    INSERT INTO dup_claims (claim_id) SELECT id FROM upd
                    """).execute(Tuple.of(adminId)))
            .mapEmpty();
    }

    /** 5, 6. Drafts and attempts, BEFORE reviews, to satisfy the canonical order. */
    /**
     * Retire the absorbed profile's synthetic seed when the survivor already has an active one.
     *
     * <p><b>A seed is a placeholder, not an opinion.</b> It is system-generated, carries no
     * contributor, and exists to give a new ghost profile something to show until real ratings
     * arrive. So unlike every other row this merge touches, a redundant one may stop being public.
     * What may never happen is a real contribution being hidden, and this statement cannot touch
     * one: it requires {@code weight = TRUE}, which only {@code createSeedReview} ever sets.
     *
     * <p><b>Why the survivor's is preferred.</b> It is the one the surviving profile has been
     * showing, and its expiry clock is the one that matches that profile's real-review history.
     * {@code weight_expires_on} starts once, when the first real rating arrives, so adopting the
     * absorbed profile's seed would import a countdown measured against a different history.
     *
     * <p><b>Why it is retired rather than deleted, and never demoted.</b> Deleting would destroy
     * the record that the profile was ever seeded. Demoting it with {@code weight = FALSE} would be
     * worse than either: that is exactly how this schema says "a real contributor rating", and
     * {@code describeCareerStage} counts {@code real_reviews} as
     * {@code NOT (user_id IS NULL AND weight = TRUE)} with no {@code deleted_at} filter, so a
     * demoted seed would be counted as a genuine opinion on the admin screen even while
     * soft-deleted. <b>A synthetic rating is never converted into a real one.</b>
     *
     * <p><b>Why the reason is not optional.</b> The restoration sweep restores exactly those rows
     * with {@code deleted_reason IS NULL}. Without a reason this seed would be republished by the
     * next sweep and would then collide with the survivor's on the narrowed index, which is the
     * original defect returning by a slower route.
     *
     * <p>Idempotent: a second merge finds the absorbed seed already soft-deleted, and the
     * {@code deleted_at IS NULL} predicate excludes it, so nothing is retired twice.
     */
    private Future<Void> retireAbsorbedSeed(SqlConnection conn, long survivor, long absorbed,
                                            Counts n) {
        return conn.preparedQuery(
                "UPDATE reviews a "
                + "   SET deleted_at = now(), "
                + "       deleted_reason = 'superseded_seed_on_merge', "
                + "       dates_hidden = TRUE "
                + " WHERE a.manager_id = $2 "
                + "   AND a.weight = TRUE "
                + "   AND a.deleted_at IS NULL "
                + "   AND EXISTS (SELECT 1 FROM reviews s "
                + "                WHERE s.manager_id = $1 "
                + "                  AND s.weight = TRUE "
                + "                  AND s.deleted_at IS NULL)")
            .execute(Tuple.of(survivor, absorbed))
            .map(rs -> { n.seedsRetired = rs.rowCount(); return (Void) null; });
    }

    private Future<Void> moveDraftsAndAttempts(SqlConnection conn, long survivor, long absorbed) {
        return conn.preparedQuery(
                "UPDATE captured_drafts SET manager_id = $1 WHERE manager_id = $2")
            .execute(Tuple.of(survivor, absorbed))
            .compose(v -> conn.preparedQuery(
                    "UPDATE capture_attempts SET manager_id = $1 WHERE manager_id = $2")
                .execute(Tuple.of(survivor, absorbed)))
            .mapEmpty();
    }

    /**
     * 6a. LEGACY UNIQUENESS, reconciled BEFORE the move.
     *
     * <p>The two narrowed legacy indexes do not mention disposition, so holding a rating does not
     * take it out of them: two legacy ratings by one contributor, of two profiles that turn out to
     * be one person, collide the moment {@code manager_id} moves, and the move fails before
     * anything can reconcile them. {@code legacy_dedup_state} is the mark that excludes the loser
     * from the index while keeping the row.
     *
     * <p><b>Both contributions are preserved.</b> The loser is ALWAYS the absorbed side, never
     * whichever is older: a merge must never retract a rating already published on the surviving
     * profile.
     */
    private Future<Void> reconcileLegacyCollisions(SqlConnection conn, long survivor, long absorbed,
                                                   UUID adminId, Counts n) {
        return conn.preparedQuery("""
                WITH edges AS (
                  -- ONE ROW PER (absorbed rating, survivor rating) COLLISION, not per pair of
                  -- equals. One absorbed rating can collide with DIFFERENT survivor ratings
                  -- through the two indexes at once: through the user index with one and the
                  -- author index with another.
                  SELECT a.id AS loser_id, b.id AS keeper_id, b.created_at AS keeper_created_at,
                         -- HOW STRONG THE EVIDENCE IS. The same account is proof of one person.
                         -- A shared display name is not, and a moderator has to tell them apart.
                         (a.user_id IS NOT NULL AND a.user_id = b.user_id) AS same_account
                    FROM reviews a
                    JOIN reviews b
                      ON a.manager_id = $2 AND b.manager_id = $1
                     -- role_basis, NOT role_required. role_required = FALSE is true of a WITHDRAWN
                     -- rating too, and the legacy indexes were narrowed to role_basis = 'legacy'
                     -- for exactly that reason. A detector keyed differently from the indexes it
                     -- enforces would hold a withdrawn rating against a legacy one it cannot
                     -- collide with in either index.
                     AND a.role_basis = 'legacy' AND b.role_basis = 'legacy'
                     AND a.weight = FALSE AND b.weight = FALSE
                     AND a.legacy_dedup_state = 'indexed'
                     AND b.legacy_dedup_state = 'indexed'
                     -- THE INDEX'S REMAINING TWO PREDICATES. A detector keyed differently from
                     -- the index it enforces holds a rating against one it cannot collide with,
                     -- and holding takes a published rating off the surface and files a
                     -- review_supersessions row saying it was a duplicate on merge. Neither of
                     -- these was here:
                     --
                     --   author_erased   V107 added it to the index so two people who rated one
                     --                   manager identically could both delete their accounts.
                     --                   An erased rating is OUT of the index, so pairing a live
                     --                   rating with one retracted a rating for nothing. This
                     --                   was a real mismatch, found by the V111 parity check.
                     --   deleted_reason  V111 adds it, so a permanently dead rating leaves the
                     --                   index. A WITHDRAWN row (no reason) stays indexed and
                     --                   stays eligible here, because it is coming back in three
                     --                   days and can genuinely collide after a merge.
                     AND NOT a.author_erased AND NOT b.author_erased
                     AND a.deleted_reason IS NULL AND b.deleted_reason IS NULL
                     AND lower(trim(coalesce(a.manager_title,  '')))
                       = lower(trim(coalesce(b.manager_title,  '')))
                     AND lower(trim(coalesce(a.manager_company,'')))
                       = lower(trim(coalesce(b.manager_company,'')))
                     AND (
                            -- index 1, signed-in only: the same account
                            (a.user_id IS NOT NULL AND a.user_id = b.user_id)
                            -- index 2, EVERY legacy row: the same author string. This is the half
                            -- an earlier detector missed - that index has no user_id predicate,
                            -- so two different accounts sharing a display name collide, and so do
                            -- a signed-in and an anonymous rating sharing one.
                         OR (a.author IS NOT NULL AND b.author IS NOT NULL
                             AND lower(trim(a.author)) = lower(trim(b.author)))
                     )
                ), chosen AS (
                  -- Deterministic, so a replayed merge picks the same keeper: the earliest
                  -- surviving rating the loser collides with, and same_account true when ANY of
                  -- its collisions is account-level evidence.
                  SELECT loser_id,
                         (array_agg(keeper_id ORDER BY keeper_created_at, keeper_id))[1] AS keeper_id,
                         bool_or(same_account) AS same_account
                    FROM edges GROUP BY loser_id
                )
                UPDATE reviews r
                   SET merge_duplicate_of = c.keeper_id,
                       legacy_dedup_state = 'held_duplicate'
                  FROM chosen c WHERE r.id = c.loser_id
                 RETURNING r.id, c.keeper_id, c.same_account
                """).execute(Tuple.of(survivor, absorbed))
            .compose(marked -> {
                List<UUID> losers = new ArrayList<>();
                for (Row r : marked) losers.add(r.getUUID("id"));
                n.ratingsHeld += losers.size();
                // Held through ReviewDisposition, one at a time, on this connection: it owns
                // gate_eligible, live_since and the location projection alongside the column.
                return holdAll(conn, losers);
            })
            // The record of what happened to it, which is the moderator's evidence. 'KEPT' is the
            // point: the row survives, held and marked, and a human decides whether the shared
            // author string meant one person.
            .compose(v -> conn.preparedQuery("""
                    INSERT INTO review_supersessions
                        (cause, superseded_review_id, superseded_row_kept, replaced_by,
                         merge_event_id, by)
                    SELECT 'duplicate_on_merge', r.id, TRUE, r.merge_duplicate_of, $1, $2
                      FROM reviews r
                     WHERE r.legacy_dedup_state = 'held_duplicate'
                       AND r.manager_id IN ($3, $4)
                       AND NOT EXISTS (SELECT 1 FROM review_supersessions s
                                        WHERE s.superseded_review_id = r.id)
                    """).execute(Tuple.of(n.eventId, adminId, survivor, absorbed)))
            .map(inserted -> { n.supersessionsRecorded = inserted.rowCount(); return null; });
    }

    /** Step 7's second half: a rating whose claim became a duplicate cannot stay live. */
    private Future<Void> holdDuplicateClaimRatings(SqlConnection conn, Counts n) {
        // Keyed on the claims this transaction actually marked, captured in dup_claims, rather
        // than on rev 268's "resolved_by = admin AND resolved_at >= merge started" window. The
        // window is a heuristic that would also catch a claim the same admin resolved by hand in
        // another transaction a moment earlier; the temp table is exact.
        return conn.query("""
                SELECT r.id FROM reviews r
                 WHERE r.claim_id IN (SELECT claim_id FROM dup_claims)
                   AND r.disposition = 'live'
                """).execute()
            .compose(rows -> {
                List<UUID> ids = new ArrayList<>();
                for (Row r : rows) ids.add(r.getUUID("id"));
                n.ratingsHeld += ids.size();
                return holdAll(conn, ids);
            });
    }

    /**
     * 8a. The absorbed manager's existing conflict tasks.
     *
     * <p>{@code case_key} is generated from {@code manager_id}, so an open task left on the
     * absorbed id describes an employment nobody can reach, while the next submission against the
     * survivor opens a SECOND task for the same dispute: a stale queue on one side and a duplicate
     * on the other. Resolved tasks are repointed too - they are history, and the denormalised
     * {@code manager_name} keeps the name that was decided about, but their {@code case_key} has to
     * follow the survivor or the sweep's suppression rule stops finding them and reopens disputes a
     * moderator has closed.
     */
    private Future<Void> reconcileConflicts(SqlConnection conn, long survivor, long absorbed,
                                            UUID adminId, Counts n) {
        // A TEMP TABLE, not a CTE: the three statements below all read this mapping.
        return conn.query("""
                CREATE TEMP TABLE colliding (
                  absorbed_task BIGINT PRIMARY KEY, surviving_task BIGINT NOT NULL
                ) ON COMMIT DROP
                """).execute()
            .compose(v -> conn.preparedQuery("""
                    INSERT INTO colliding (absorbed_task, surviving_task)
                    SELECT a.id, min(s.id)
                      FROM role_conflicts a
                      JOIN role_conflicts s
                        ON s.manager_id = $1 AND a.manager_id = $2
                       AND s.resolved_at IS NULL AND a.resolved_at IS NULL
                       AND s.kind = a.kind
                       AND s.company_id IS NOT DISTINCT FROM a.company_id
                       AND s.title_key = a.title_key AND s.period_key = a.period_key
                     GROUP BY a.id
                    """).execute(Tuple.of(survivor, absorbed)))
            // Memberships move to the surviving task. SUBJECT WINS: if a claim is the subject of
            // either task it is the subject of the merged one, because the stronger statement
            // about a rating must not be weakened by a merge.
            .compose(v -> conn.query("""
                    INSERT INTO role_conflict_claims (conflict_id, claim_id, role)
                    SELECT c.surviving_task, rcc.claim_id, rcc.role
                      FROM colliding c
                      JOIN role_conflict_claims rcc ON rcc.conflict_id = c.absorbed_task
                        ON CONFLICT (conflict_id, claim_id) DO UPDATE
                           SET role = CASE WHEN role_conflict_claims.role = 'subject'
                                             OR EXCLUDED.role = 'subject' THEN 'subject'
                                           ELSE 'evidence' END
                    """).execute())
            .compose(v -> conn.query("""
                    INSERT INTO role_conflict_reviews (conflict_id, review_id)
                    SELECT c.surviving_task, rcr.review_id
                      FROM colliding c
                      JOIN role_conflict_reviews rcr ON rcr.conflict_id = c.absorbed_task
                        ON CONFLICT DO NOTHING
                    """).execute())
            // The folded task closes, saying where it went. Its own memberships are NOT deleted:
            // role_conflict_claims is RESTRICT on purpose and the evidence of what was once asked
            // outlives the queue item.
            .compose(v -> conn.preparedQuery("""
                    UPDATE role_conflicts
                       SET resolved_at = now(), resolution = 'merged', resolved_by = $1,
                           superseded_by_conflict_id = c.surviving_task, manager_id = $2
                      FROM colliding c WHERE role_conflicts.id = c.absorbed_task
                    """).execute(Tuple.of(adminId, survivor)))
            .compose(folded -> {
                n.conflictsFolded = folded.rowCount();
                // 8a.ii Everything else just moves. The partial unique index covers open tasks
                //       only, so repointing closed ones cannot collide.
                return conn.preparedQuery(
                        "UPDATE role_conflicts SET manager_id = $1 WHERE manager_id = $2")
                    .execute(Tuple.of(survivor, absorbed));
            })
            .map(moved -> { n.conflictsMoved = moved.rowCount(); return null; });
    }

    /**
     * 8b. One moderation task per employment disputed by a duplicated CLAIM.
     *
     * <p>Subject and evidence are what make this transaction able to commit at all. The absorbed
     * contributor's claim, whose rating is now held, is the SUBJECT; the keeper claim, whose rating
     * stays live, is EVIDENCE. {@code duplicate_rating_on_merge} is a publication-blocking kind, so
     * linking the keeper as a subject would make {@code rating_is_publishable()} refuse its
     * still-live rating and the merge would fail at COMMIT having done everything right.
     *
     * <p><b>This covers the claim-level duplicates from step 4e and NOT the legacy collisions from
     * step 6a, and that is a resolution of a contradiction in rev 268 rather than an omission.</b>
     * Step 8b there says to open this task "per disputed employment from step 6a" and to link "the
     * absorbed claim" as its subject - but step 6a is narrowed to {@code role_basis = 'legacy'} on
     * both sides, and a legacy rating has no claim by definition. A blocking task with no subject
     * claim is refused outright by {@code assert_blocking_has_a_subject}, and
     * {@code role_conflict_reviews} is evidence-only and deliberately cannot hold a rating. So the
     * task is opened where there is a claim to be its subject; a legacy collision's record is the
     * held rating, its {@code merge_duplicate_of} marker and its {@code review_supersessions} row,
     * which is exactly the provenance that table's {@code duplicate_on_merge} cause describes.
     */
    private Future<Void> openDuplicateRatingTasks(SqlConnection conn, long survivor, UUID adminId,
                                                  Counts n) {
        // The disputed employments first, then one upsert each.
        //
        // Per row rather than one set-based INSERT ... RETURNING, deliberately: joining the
        // returned rows back to their inputs would mean reconstructing case_key in application
        // SQL, and a generated column's expression duplicated anywhere is an expression that can
        // drift. On a drift the join would silently match nothing and no task would be opened,
        // which is the kind of failure that looks like "there was no conflict". The set here is
        // the duplicate claims of one merge, so the loop costs nothing.
        return conn.query("""
                SELECT d.claim_id              AS subject_claim,
                       a.duplicate_of_claim_id AS evidence_claim,
                       ch.id                   AS role_id,
                       a.company_id, a.title_key, a.title,
                       COALESCE(ch.start_day::text, 'unknown') AS period_key,
                       m.name  AS manager_name,
                       co.name AS company_name
                  FROM dup_claims d
                  JOIN role_claims a     ON a.id  = d.claim_id
                  JOIN role_claims k     ON k.id  = a.duplicate_of_claim_id
                  JOIN career_history ch ON ch.id = k.resolved_role_id
                  JOIN managers  m       ON m.id  = a.manager_id
                  JOIN companies co      ON co.id = a.company_id
                """).execute()
            .compose(rows -> {
                Future<Void> chain = Future.succeededFuture();
                List<Row> disputed = new ArrayList<>();
                for (Row r : rows) disputed.add(r);
                n.conflictsOpened = disputed.size();
                for (Row d : disputed) {
                    chain = chain.compose(v -> conn.preparedQuery("""
                            INSERT INTO role_conflicts
                                (kind, manager_id, company_id, title_key, period_key, role_id,
                                 manager_name, company_name, title, note)
                            VALUES ('duplicate_rating_on_merge', $1, $2, $3, $4, $5, $6, $7, $8,
                                    'Opened by a manager merge: one contributor holds two claims '
                                    || 'on this employment. The held rating is the subject; the '
                                    || 'live one is evidence.')
                                -- One OPEN task per disputed employment per kind. Two absorbed
                                -- claims duplicated onto one role share a case_key, so the second
                                -- is a no-op upsert rather than a unique violation, and the
                                -- RETURNING hands back the task that already exists.
                                ON CONFLICT (case_key, kind) WHERE resolved_at IS NULL
                                DO UPDATE SET note = role_conflicts.note
                             RETURNING id
                            """)
                        .execute(Tuple.of(survivor, d.getLong("company_id"),
                                          d.getString("title_key"), d.getString("period_key"),
                                          d.getLong("role_id"), d.getString("manager_name"),
                                          d.getString("company_name"), d.getString("title")))
                        .map(rs -> rs.iterator().next().getLong("id"))
                        .compose(conflictId -> conn.preparedQuery("""
                                INSERT INTO role_conflict_claims (conflict_id, claim_id, role)
                                VALUES ($1, $2, 'subject')
                                    ON CONFLICT (conflict_id, claim_id)
                                    DO UPDATE SET role = 'subject'
                                """)
                            .execute(Tuple.of(conflictId, d.getLong("subject_claim")))
                            .compose(x -> d.getLong("evidence_claim") == null
                                ? Future.succeededFuture()
                                : conn.preparedQuery("""
                                    INSERT INTO role_conflict_claims (conflict_id, claim_id, role)
                                    VALUES ($1, $2, 'evidence')
                                        ON CONFLICT (conflict_id, claim_id) DO NOTHING
                                    """)
                                    .execute(Tuple.of(conflictId, d.getLong("evidence_claim")))
                                    .mapEmpty())
                            .mapEmpty()));
                }
                return chain;
            });
    }

    // ── Role merge ────────────────────────────────────────────────────────────

    /**
     * Merges one employment role into another on the SAME manager.
     *
     * <p>Rev 268: "Same rules, smaller scope: two roles on one manager, one supersedes the other,
     * claims and ratings repoint, duplicate-contributor collisions recorded. This is also how the
     * ambiguous-resolution and unknown-period conflict queues are worked."
     *
     * <p>Every rule from the manager merge applies here with the manager held constant: the
     * superseded role is kept and pointed at its replacement, claims are redirected with the
     * canonicalization audited, a contributor who already holds a claim on the keeper has theirs
     * marked duplicate and its rating held, and withdrawn ratings follow the redirection.
     *
     * <p>There is no new endpoint for this. It is the operation the moderation queues will call in
     * phase 4, and wiring a UI to it now is phase 4's work.
     */
    public Future<JsonObject> mergeRoles(long managerId, long keepRoleId, long supersededRoleId,
                                         UUID adminId) {
        if (keepRoleId == supersededRoleId) {
            return Future.failedFuture(new IllegalArgumentException(
                "A role cannot be merged into itself"));
        }
        return withRetry(() -> db.withTransaction(conn ->
            mergeRolesOnce(conn, managerId, keepRoleId, supersededRoleId, adminId)), 1);
    }

    private Future<JsonObject> mergeRolesOnce(SqlConnection conn, long managerId, long keepRoleId,
                                              long supersededRoleId, UUID adminId) {
        Counts n = new Counts();
        return deferTheComposites(conn)
            // The canonical order, starting at managers even though this changes no manager row:
            // step 10 rewrites its aggregates, and taking the strongest mode at first acquisition
            // is what stops two of these from deadlocking on the upgrade.
            .compose(v -> conn.preparedQuery(
                    "SELECT id FROM managers WHERE id = $1 FOR UPDATE")
                .execute(Tuple.of(managerId)))
            .compose(v -> conn.preparedQuery("""
                    SELECT id, manager_id, company_id, state FROM career_history
                     WHERE id IN ($1, $2) ORDER BY id FOR UPDATE
                    """).execute(Tuple.of(keepRoleId, supersededRoleId)))
            .compose(roles -> {
                if (roles.rowCount() < 2) {
                    return Future.failedFuture(new IllegalStateException(
                        "One or both roles no longer exist, so nothing was merged."));
                }
                Long keepCompany = null, goneCompany = null;
                for (Row r : roles) {
                    if (r.getLong("manager_id") != managerId) {
                        return Future.failedFuture(new IllegalStateException(
                            "Both roles must belong to the same manager."));
                    }
                    if (r.getLong("id") == keepRoleId) keepCompany = r.getLong("company_id");
                    else goneCompany = r.getLong("company_id");
                }
                // The employer is part of an employment's identity, and claim_resolved_role_matches
                // keys on it. Merging across employers would silently move a rating's attribution
                // to a different company, which is the failure the whole analytics correction
                // exists to prevent.
                if (!keepCompany.equals(goneCompany)) {
                    return Future.failedFuture(new IllegalStateException(
                        "These roles are at different employers and cannot be merged. Correct the "
                        + "employer on one of them first."));
                }
                return conn.preparedQuery("""
                        SELECT id FROM role_claims WHERE manager_id = $1 ORDER BY id FOR UPDATE
                        """).execute(Tuple.of(managerId));
            })
            // The same plan the manager merge builds, with one hand-made row: this caller has
            // already decided which role supersedes which, so there is nothing to detect.
            .compose(v -> conn.query("""
                    CREATE TEMP TABLE role_plan (
                      absorbed_role   BIGINT PRIMARY KEY,
                      equivalent_role BIGINT NOT NULL
                    ) ON COMMIT DROP
                    """).execute())
            .compose(v -> conn.preparedQuery(
                    "INSERT INTO role_plan (absorbed_role, equivalent_role) VALUES ($1, $2)")
                .execute(Tuple.of(supersededRoleId, keepRoleId)))
            .compose(v -> conn.preparedQuery("""
                    UPDATE career_history
                       SET state = 'superseded', superseded_by = $2, updated_at = now()
                     WHERE id = $1
                    """).execute(Tuple.of(supersededRoleId, keepRoleId)))
            .compose(superseded -> { n.rolesSuperseded = superseded.rowCount();
                                     return redirectClaims(conn, adminId, n); })
            .compose(v -> holdDuplicateClaimRatings(conn, n))
            .compose(v -> conn.query("""
                    CREATE TEMP TABLE colliding (
                      absorbed_task BIGINT PRIMARY KEY, surviving_task BIGINT NOT NULL
                    ) ON COMMIT DROP
                    """).execute())
            .compose(v -> openDuplicateRatingTasks(conn, managerId, adminId, n))
            .compose(v -> resolveMergedEmployment(conn))
            .compose(v -> recalculate(conn, managerId))
            .map(v -> n.toJson());
    }

    // ── Shared plumbing ───────────────────────────────────────────────────────

    private Future<Void> holdAll(SqlConnection conn, List<UUID> reviewIds) {
        Future<Void> chain = Future.succeededFuture();
        for (UUID id : reviewIds) {
            chain = chain.compose(v -> dispositions.set(conn, id, ReviewDisposition.HELD));
        }
        return chain;
    }

    /**
     * The cached aggregates, rewritten from the ratings that are now live on the manager.
     *
     * <p>Rev 268 writes this as {@code PERFORM recalc_managers(ARRAY[...])}. No such function
     * exists in this database: the recalculation lives in application code, and this is the same
     * statement {@code ManagerRepository.mergeInlineRecalculate} runs - run here on the merge's own
     * connection, and for BOTH managers rather than only the survivor.
     */
    private Future<Void> recalculate(SqlConnection conn, long managerId) {
        return conn.preparedQuery("""
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
     * Retries the whole transaction on a deadlock or serialization failure, at most three times.
     *
     * <p>Nothing is partially applied, because each attempt is one transaction. After the final
     * attempt the caller gets the error rather than a success: the one thing never done is catching
     * the failure and continuing, which would leave the roles moved and the ratings behind.
     *
     * <p>The backoff is jittered so two merges that deadlocked do not retry in lockstep and
     * deadlock again. It is scheduled on the Vert.x context rather than slept on, because this runs
     * on an event loop; with no context - a plain test thread - it retries immediately, which is
     * correct but unjittered.
     */
    private Future<JsonObject> withRetry(java.util.function.Supplier<Future<JsonObject>> attempt,
                                         int attemptNo) {
        return attempt.get().recover(err -> {
            if (attemptNo >= MAX_ATTEMPTS || !isRetriable(err)) return Future.failedFuture(err);
            long backoffMs = (1L << (attemptNo - 1)) * 20L
                           + ThreadLocalRandom.current().nextLong(20L);
            io.vertx.core.Context ctx = io.vertx.core.Vertx.currentContext();
            if (ctx == null) return withRetry(attempt, attemptNo + 1);
            io.vertx.core.Promise<JsonObject> retried = io.vertx.core.Promise.promise();
            ctx.owner().setTimer(backoffMs, t ->
                withRetry(attempt, attemptNo + 1).onComplete(retried));
            return retried.future();
        });
    }

    private static boolean isRetriable(Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof PgException pg
                && (DEADLOCK_DETECTED.equals(pg.getSqlState())
                 || SERIALIZATION_FAILURE.equals(pg.getSqlState()))) {
                return true;
            }
        }
        return false;
    }

    /** The manifest. Every number here is something an admin may have to be told. */
    private static final class Counts {
        long eventId;
        int rolesMoved, rolesSuperseded, claimsMoved, claimsRedirected;
        int ratingsMoved, ratingsHeld, supersessionsRecorded;
        int conflictsFolded, conflictsMoved, conflictsOpened;
        int opportunitiesMoved, opportunitiesRetired;
        int seedsRetired;

        JsonObject toJson() {
            return new JsonObject()
                .put("mergeEventId",      eventId)
                // 'moved' and 'parked' keep the names the previous merge reported, because the
                // admin client reads them. 'parked' no longer means soft-deleted: it is the count
                // of ratings held and marked, every one of which is still there.
                .put("moved",             ratingsMoved)
                .put("parked",            ratingsHeld)
                .put("rolesMoved",        rolesMoved)
                .put("rolesSuperseded",   rolesSuperseded)
                .put("claimsMoved",       claimsMoved)
                .put("claimsRedirected",  claimsRedirected)
                .put("ratingsHeld",       ratingsHeld)
                .put("supersessions",     supersessionsRecorded)
                .put("conflictsFolded",   conflictsFolded)
                .put("conflictsMoved",    conflictsMoved)
                .put("conflictsOpened",   conflictsOpened)
                .put("opportunitiesMoved",   opportunitiesMoved)
                .put("opportunitiesRetired", opportunitiesRetired)
                .put("seedsRetired",         seedsRetired);
        }
    }
}
