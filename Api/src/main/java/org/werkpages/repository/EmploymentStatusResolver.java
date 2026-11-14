package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;

/**
 * The ONE writer of {@code career_history}'s employment status, and the only place the rules live.
 *
 * <h2>The three layers, which are never collapsed</h2>
 *
 * <p>Rev 268: {@code role_claims} holds what each contributor asserted, {@code career_history} holds
 * what the platform has resolved, and {@code employment_status_events} holds how that resolution
 * changed. This class is the middle layer's only author. The event log is written by a trigger on
 * the row, so nothing here writes it and nothing anywhere can change a status without it being
 * recorded.
 *
 * <h2>The observation the whole model rests on</h2>
 *
 * <p>{@code still_there} is not a claim about today. A contributor answers it as they leave, so it
 * means "my manager was still employed at the moment I stopped working with them". Someone who left
 * in 2021 ticking that box is evidence about 2021 and about nothing since. So the status carries
 * {@code status_as_of}, the date the EVIDENCE speaks to, and never the date it was recorded, and
 * freshness is applied when the status is READ rather than by any sweep over these rows.
 *
 * <h2>Why the resolution is one statement</h2>
 *
 * <p>Deriving in Java and then writing would mean comparing timestamps in Java to decide whether
 * anything changed, and an {@code OffsetDateTime} read back from PostgreSQL does not reliably equal
 * the one that was written: the offset representation differs and the comparison silently becomes a
 * rewrite. A rewrite is not harmless here, because the trigger would log an event for it and a
 * replayed request would append an event saying nothing changed.
 *
 * <p>So the derivation, the comparison and the write are a single UPDATE whose WHERE clause is the
 * comparison. Identical evidence updates zero rows, which is exactly the idempotency a retried
 * submission needs, and the event log stays a record of changes rather than of traffic.
 *
 * <h2>What is deliberately NOT done here</h2>
 *
 * <ul>
 *   <li><b>No human actor is invented.</b> A claim resolving and an end date being accepted are the
 *       resolution component acting, not a person, so {@code status_resolved_by} stays null and
 *       {@code status_basis} carries the provenance. Slice 4 adds the moderator's determination,
 *       which has a real actor to name.</li>
 *   <li><b>A moderator's determination is never overwritten.</b> Rev 268: a new claim "may not
 *       quietly replace a moderator's determination". The target predicate excludes
 *       {@code status_basis = 'moderator'}, so such a row is immune to everything here.</li>
 *   <li><b>A dispute is left alone.</b> {@code 'disputed'} is excluded for the same reason, and
 *       raising one is slice 3's work, not this slice's.</li>
 *   <li><b>Nothing is published.</b> This writes a status; publication eligibility is
 *       {@code rating_is_publishable}'s question and is untouched.</li>
 * </ul>
 */
public final class EmploymentStatusResolver {

    private EmploymentStatusResolver() { }

    /**
     * How old an {@code ongoing} assertion may be before it presents as Unknown.
     *
     * <p>Rev 268's freshness policy, and a judgement rather than a round number: longer than the
     * contribution cadence, so a label does not flicker between Current and Unknown while nothing
     * about the employment has changed, and shorter than typical management tenure, so it does not
     * assert Current for people who have already left.
     *
     * <p>One named constant in one place, as {@code SubmissionLimits.COOLDOWN_DAYS} is. It is a
     * PARAMETER to {@code employment_status_presented} rather than a literal inside it, which is
     * what makes the policy revisable and its boundary testable without waiting eighteen months.
     */
    public static final int FRESHNESS_MONTHS = 18;

    /**
     * Resolve one role, inside the caller's transaction.
     *
     * <p>Two statements, both inside that transaction. The lock is taken FIRST and separately:
     * without it two concurrent resolutions could each derive from a snapshot taken before the
     * other's claim was linked, and the second would overwrite the first with staler evidence. With
     * it the derivation is serialised per role, which is the only granularity that matters here.
     */
    public static Future<Void> resolveRole(SqlConnection tx, long roleId) {
        return tx.preparedQuery("SELECT id FROM career_history WHERE id = $1 FOR UPDATE")
            .execute(Tuple.of(roleId))
            .compose(locked -> locked.rowCount() == 0
                ? Future.succeededFuture()
                : tx.preparedQuery(RESOLVE).execute(Tuple.of(roleId))
                    // Resolve, THEN ask whether the answer is contradicted. In that order,
                    // because the thing a contributor can contradict is what the platform has
                    // just concluded, and because the resolution is what establishes the 'ended'
                    // assertion the comparison is against.
                    .compose(v -> disputeIfContradicted(tx, roleId)));
    }

    /**
     * Resolve every accepted role of one manager, inside the caller's transaction.
     *
     * <p>Used where the flow changed employment without the role id to hand: closing an open entry
     * affects however many rows were open, and the legacy add-manager path inserts its first role
     * without reading the id back. Resolving the manager is correct in both cases and idempotent in
     * every case, so it is the safe shape rather than a guess at which row moved.
     *
     * <p>Locked in id order. Two flows touching one manager at the same time then take the same
     * locks in the same sequence, which is what stops them deadlocking against each other.
     */
    public static Future<Void> resolveManager(SqlConnection tx, long managerId) {
        return tx.preparedQuery("""
                SELECT id FROM career_history
                 WHERE manager_id = $1 AND state = 'accepted'
                 ORDER BY id
                 FOR UPDATE
                """)
            .execute(Tuple.of(managerId))
            .compose(rows -> {
                List<Long> ids = new ArrayList<>();
                for (Row r : rows) ids.add(r.getLong("id"));
                Future<Void> chain = Future.succeededFuture();
                // Sequential, not a composite: these share a transaction, and a Vert.x pooled
                // connection runs one statement at a time anyway.
                for (Long id : ids) {
                    chain = chain.compose(v -> tx.preparedQuery(RESOLVE).execute(Tuple.of(id))
                        .compose(ignored -> disputeIfContradicted(tx, id)));
                }
                return chain;
            });
    }

    /**
     * Resolve one role in a transaction of its own, for a caller that has no connection.
     *
     * <p>The status must land with the write that caused it or not at all: a committed acceptance
     * with an unresolved status is the drift the reconciliation query exists to find, and creating
     * it deliberately would be odd. Callers that already hold a transaction use
     * {@link #resolveRole(SqlConnection, long)} and keep one transaction for the whole operation.
     */
    public static Future<Void> resolveRoleAtomically(SqlClient db, long roleId) {
        return ((Pool) db).withTransaction(tx -> resolveRole(tx, roleId));
    }

    /** As above, for every accepted role of one manager. */
    public static Future<Void> resolveManagerAtomically(SqlClient db, long managerId) {
        return ((Pool) db).withTransaction(tx -> resolveManager(tx, managerId));
    }

    /**
     * Accepted employment whose stored status does not follow from the record itself.
     *
     * <p>The reconciliation routine CLAUDE.md section 21 requires of anything derived: an accepted
     * role that carries an end date IS ended employment, so a row saying otherwise means a writing
     * path reached {@code career_history} without resolving, and that is a defect to find rather
     * than a state to tolerate.
     *
     * <p>It deliberately does NOT flag an accepted role with no end date and no status. That is the
     * commonest row in the table and it is correct: acceptance is not currency, and a moderator
     * accepting that somebody worked somewhere establishes the employment, not its present-day
     * status.
     *
     * <p>{@code 'disputed'} is excluded because a dispute is a deliberate refusal to answer, and
     * {@code 'moderator'} because a human determination outranks the record by design.
     */
    public static Future<List<Long>> drifting(SqlClient db) {
        return db.query("""
                SELECT id FROM career_history
                 WHERE state = 'accepted'
                   AND end_date IS NOT NULL
                   AND employment_status <> 'ended'
                   AND employment_status <> 'disputed'
                   AND status_basis IS DISTINCT FROM 'moderator'
                 ORDER BY id
                """).execute()
            .map(rows -> {
                List<Long> out = new ArrayList<>();
                for (Row r : rows) out.add(r.getLong("id"));
                return out;
            });
    }

    /**
     * Raise an employment-status dispute when a contributor contradicts the standing answer.
     *
     * <h2>What counts as a contradiction, and what does not</h2>
     *
     * <p>Rev 251, and the rule is narrow on purpose: an {@code ongoing} assertion as of T
     * contradicts an {@code ended} assertion whose end date is EARLIER than T. Nothing else does.
     *
     * <pre>
     *   A left 2021 saying the manager was still there, record says ended 2024
     *           -&gt; COMPATIBLE. Both true. He was there in 2021 and left in 2024.
     *   A left Jan 2025 saying the manager was still there, record says ended Dec 2024
     *           -&gt; CONTRADICTION. He cannot have been employed after he left.
     * </pre>
     *
     * <p>Differing dates alone, differing checkbox answers alone, and two contributors who simply
     * overlapped different years are NOT contradictions, and a dispute raised for any of those
     * would be a moderator's time spent on arithmetic the system can do itself.
     *
     * <h2>Why it reads the testimony and not only the claim</h2>
     *
     * <p>A claim's assertion columns are frozen at finalization: {@code refuse_assertion_change}
     * refuses to rewrite what somebody said. A contributor who returns two years later and
     * remembers it differently writes a {@code claim_testimony} row instead, and rev 268 is
     * explicit that such a repeat "never changes employment_status by itself" but "may raise a
     * moderation item when it CONTRADICTS the standing resolution".
     *
     * <p>So resolution reads the claim and detection reads the LATEST thing each contributor
     * said. A repeat that merely differs changes nothing and raises nothing; a repeat that puts
     * the manager in the job after the record says they left raises a dispute. The latest
     * statement is taken whole, never column by column: mixing a new date with an old checkbox
     * would invent a statement nobody made.
     *
     * <h2>What it does, and deliberately does not do</h2>
     *
     * <p>Both claims are preserved untouched, the role becomes {@code disputed}, and a
     * {@code role_conflicts} row of kind {@code employment_status_dispute} appears in the queue.
     * {@code disputed} presents as Unknown, because while two accounts conflict the honest answer
     * is that we do not know.
     *
     * <p>The claims are attached as <b>evidence</b>, never as subject, and the kind is absent from
     * {@code kind_blocks_publication}. A dispute about WHEN somebody worked somewhere says nothing
     * about whether a rating may be published, and holding ratings over it would withhold opinions
     * for a reason that has no bearing on them.
     *
     * <p>It does not resolve the dispute, pick a side, or write a moderator basis. That is slice 4.
     */
    private static Future<Void> disputeIfContradicted(SqlConnection tx, long roleId) {
        return tx.preparedQuery(DETECT).execute(Tuple.of(roleId)).compose(found -> {
            if (found.rowCount() == 0) return Future.succeededFuture();
            // The record-versus-determination arm names no claim, because no contributor made
            // that assertion. The dispute still stands on its own; there is simply nothing to
            // attach, and role_conflict_claims.claim_id is NOT NULL.
            List<Long> evidence = new ArrayList<>();
            for (Row r : found) {
                Long claimId = r.getLong("claim_id");
                if (claimId != null) evidence.add(claimId);
            }

            // Idempotent by the database, not by a prior read: role_conflicts_one_open is UNIQUE
            // on (case_key, kind) while unresolved, so a second raise is DO NOTHING rather than a
            // duplicate queue item or an error. Two transactions racing to raise the same dispute
            // therefore produce one, and the loser carries on.
            return tx.preparedQuery(RAISE).execute(Tuple.of(roleId))
                .compose(v -> tx.preparedQuery(OPEN_DISPUTE).execute(Tuple.of(roleId)))
                .compose(open -> {
                    if (open.rowCount() == 0) {
                        // Only reachable if the dispute was resolved between the two statements.
                        // Leaving the status alone is right: a moderator has just answered.
                        return Future.succeededFuture();
                    }
                    long conflictId = open.iterator().next().getLong("id");
                    Future<Void> links = Future.succeededFuture();
                    for (Long claimId : evidence) {
                        links = links.compose(v -> tx.preparedQuery(
                                "INSERT INTO role_conflict_claims (conflict_id, claim_id, role) "
                                + "VALUES ($1,$2,'evidence') ON CONFLICT DO NOTHING")
                            .execute(Tuple.of(conflictId, claimId)).mapEmpty());
                    }
                    // The status LAST, so no reader ever sees a disputed role with no dispute.
                    // basis, as_of and the evidence pointer are cleared because a disputed row
                    // asserts nothing: the conflict holds the context and the event log holds
                    // what the answer was before.
                    return links.compose(v -> tx.preparedQuery("""
                            UPDATE career_history
                               SET employment_status = 'disputed', status_basis = NULL,
                                   status_as_of = NULL, status_claim_id = NULL,
                                   status_verified_at = NULL, updated_at = now()
                             WHERE id = $1 AND employment_status <> 'disputed'
                            """).execute(Tuple.of(roleId)).mapEmpty());
                });
        });
    }

    /**
     * The contradicting assertions for one role, one row per contributor who made one.
     *
     * <p>Only an {@code ended} standing can be contradicted, and the basis does not matter: a
     * moderator's determination is contradicted by new testimony exactly as the record is, which
     * is what rev 268 means by a claim that "may also contradict it". A role already
     * {@code disputed} is excluded, because the question is already open.
     */
    private static final String DETECT = """
            WITH subject AS (
              SELECT ch.id, ch.employment_status, ch.status_as_of,
                     ch.status_basis, ch.status_resolved_at
                FROM career_history ch
               WHERE ch.id = $1
                 AND ch.state = 'accepted'
                 -- Only an ENDED standing can be contradicted by a contributor saying the manager
                 -- was still there later. The opposite direction is the second arm below.
                 AND ch.employment_status = 'ended'
            ), said AS (
              SELECT rc.id AS claim_id,
                     CASE WHEN t.claim_id IS NOT NULL THEN t.still_there
                          ELSE rc.still_there END AS still_there,
                     CASE WHEN t.claim_id IS NOT NULL THEN t.end_date
                          ELSE rc.end_date    END AS left_on,
                     /*
                       WHEN THIS EVIDENCE REACHED THIS ROLE.

                       The latest statement's own timestamp, or, for a claim nobody has restated,
                       when it became evidence by being resolved onto this role.

                       And never earlier than canonicalized_at, which is when a merge moved the
                       claim HERE. The statement itself may be years old, but a moderator cannot
                       have weighed evidence that was attached to another profile's role when they
                       decided, so a merge delivering it counts as the moment it arrived. Without
                       this, a merge could carry a contradicting claim onto a determined role and
                       the determination would silently outrank evidence nobody had seen.
                     */
                     GREATEST(COALESCE(t.said_at, rc.resolved_at), rc.canonicalized_at) AS said_at
                FROM role_claims rc
                LEFT JOIN LATERAL (
                     SELECT ct.claim_id, ct.still_there, ct.end_date, ct.said_at
                       FROM claim_testimony ct
                      WHERE ct.claim_id = rc.id
                      ORDER BY ct.said_at DESC, ct.id DESC
                      LIMIT 1) t ON TRUE
               WHERE rc.resolved_role_id = $1
                 AND rc.resolution IN ('linked','created')
                 AND rc.withdrawn_at IS NULL
            )
            SELECT said.claim_id
              FROM subject s
              JOIN said ON TRUE
             WHERE said.still_there
               AND said.left_on IS NOT NULL
               AND employment_status_contradicts(
                     s.employment_status, s.status_as_of,
                     'ongoing', (said.left_on::timestamp AT TIME ZONE 'UTC'))
               /*
                 A DETERMINATION ANSWERS THE EVIDENCE THAT EXISTED WHEN IT WAS MADE.

                 Without this the slice eats itself. A moderator reads a dispute whose evidence is
                 a contributor saying the manager stayed until 2025, determines that the job ended
                 in 2024, and the next resolution finds that same claim still saying 2025 and
                 disputes the role again. Determine, re-dispute, determine, for ever, and the
                 queue item they just answered comes straight back.
                 .
                 So an assertion older than the determination has already been weighed: the
                 moderator saw it and decided anyway, and that is what a determination IS. An
                 assertion NEWER than the determination has not been weighed, and reopens the
                 question, which is how a contributor's later recollection still reaches a human.
                 .
                 A status with no determination behind it has no status_resolved_at to compare
                 against and is contradicted by any qualifying evidence, as before.
               */
               AND (s.status_basis IS DISTINCT FROM 'moderator'
                    OR s.status_resolved_at IS NULL
                    OR said.said_at > s.status_resolved_at)
             UNION ALL
            /*
              THE OTHER DIRECTION, and it exists because a determination can go stale.

              A moderator determines 'ongoing' as of some date, and an administrator later
              corrects an end date onto the record that falls BEFORE it. Derivation cannot notice:
              it skips a moderator-grounded row by design, so the row would go on presenting as
              Current while the employment record it sits on says the job had already finished.
              Two public statements about the same manager, disagreeing.

              The same single rule decides it, with the record's own end date as the opposing
              assertion, and the answer is the same: the question is open again. No claim is named
              because no contributor made this one.
            */
             SELECT NULL::bigint AS claim_id
               FROM career_history ch
              WHERE ch.id = $1
                AND ch.state = 'accepted'
                AND ch.employment_status = 'ongoing'
                AND ch.end_date IS NOT NULL
                AND employment_status_contradicts(
                      ch.employment_status, ch.status_as_of, 'ended', ch.end_date)
                -- And the same rule: a determination made while that end date was already on the
                -- record has already accounted for it. Only a record changed AFTER the
                -- determination is new information. updated_at is what the correction path moves.
                AND (ch.status_basis IS DISTINCT FROM 'moderator'
                     OR ch.status_resolved_at IS NULL
                     OR ch.updated_at > ch.status_resolved_at)
            """;

    /** The queue item, keyed on the disputed EMPLOYMENT so one employment holds one open task. */
    private static final String RAISE = """
            INSERT INTO role_conflicts (kind, manager_id, company_id, title_key, period_key,
                                        role_id, manager_name, company_name, title, period)
            SELECT 'employment_status_dispute', ch.manager_id, ch.company_id, ch.title_key,
                   COALESCE(ch.start_day::text,'unknown'), ch.id, m.name, c.name, ch.title,
                   COALESCE(ch.start_day::text,'unknown')
              FROM career_history ch
              JOIN managers  m ON m.id = ch.manager_id
              JOIN companies c ON c.id = ch.company_id
             WHERE ch.id = $1
            ON CONFLICT (case_key, kind) WHERE resolved_at IS NULL DO NOTHING
            """;

    /**
     * The open dispute for this role's employment, whether this transaction raised it or not.
     *
     * <p>Matched on the four columns {@code case_key} is generated from rather than on
     * {@code case_key} itself, so the key's definition stays in one place: the migration.
     */
    private static final String OPEN_DISPUTE = """
            SELECT cf.id
              FROM role_conflicts cf, career_history ch
             WHERE ch.id = $1
               AND cf.kind = 'employment_status_dispute'
               AND cf.resolved_at IS NULL
               AND cf.manager_id IS NOT DISTINCT FROM ch.manager_id
               AND cf.company_id IS NOT DISTINCT FROM ch.company_id
               AND cf.title_key  = ch.title_key
               AND cf.period_key = COALESCE(ch.start_day::text,'unknown')
             LIMIT 1
            """;

    /**
     * The resolution itself: derive, compare and write, in one statement.
     *
     * <h2>The precedence, which is rev 268's and not negotiable</h2>
     *
     * <ol>
     *   <li>an end date on the ACCEPTED record: {@code ended}, basis {@code end_date}, speaking to
     *       the end date itself. The record outranks every claim, because it is what the platform
     *       has resolved about the employment;</li>
     *   <li>otherwise a resolved claim asserting {@code still_there} WHOSE OWN END DATE IS KNOWN:
     *       {@code ongoing}, basis {@code contributor_claim}, speaking to the date that contributor
     *       left. <b>This is the rule that earns the whole model.</b> A {@code still_there} claim
     *       with no end date of its own gives nothing assertable, so it resolves to
     *       {@code unknown} rather than to a Current with an invented date;</li>
     *   <li>otherwise {@code unknown}, basis {@code no_evidence}. Asserting nothing, and saying so
     *       explicitly, is different from never having looked.</li>
     * </ol>
     *
     * <p>Among several assertable claims the LATEST departure wins, because it is evidence about
     * the later date; ties break on the higher id so the choice is deterministic. A withdrawn claim
     * is excluded: the tombstone exists to stop the same person rating the employment twice, not to
     * keep asserting on their behalf after they withdrew. Revival clears {@code withdrawn_at}, and
     * because this is a pure derivation the next resolution picks the claim up again with no
     * special case.
     *
     * <h2>What the three CASE expressions in the SET clause do</h2>
     *
     * <p>Identical evidence never reaches them: the WHERE clause's {@code IS DISTINCT FROM} filters
     * it out, zero rows are updated and no event is logged. What remains is two shapes, and they
     * mean different things:
     *
     * <ul>
     *   <li>the status, basis and date all agree and only the CLAIM differs: a second contributor
     *       has independently asserted the same thing, which is a CONFIRMATION. The first
     *       resolution's {@code status_resolved_at} is kept, because that is when it was
     *       established, and {@code status_verified_at} records that somebody re-checked;</li>
     *   <li>anything else is a new resolution: {@code status_resolved_at} moves to now and
     *       {@code status_verified_at} is cleared, because nothing has yet re-checked THIS answer.</li>
     * </ul>
     */
    private static final String RESOLVE = """
            WITH target AS (
              SELECT ch.id, ch.end_date, ch.employment_status, ch.status_basis,
                     ch.status_as_of, ch.status_claim_id
                FROM career_history ch
               WHERE ch.id = $1
                 AND ch.state = 'accepted'
                 AND ch.status_basis IS DISTINCT FROM 'moderator'
                 AND ch.employment_status <> 'disputed'
            ), evidence AS (
              SELECT t.id,
                     CASE WHEN t.end_date  IS NOT NULL THEN 'ended'
                          WHEN c.claim_id  IS NOT NULL THEN 'ongoing'
                          ELSE 'unknown' END AS status,
                     CASE WHEN t.end_date  IS NOT NULL THEN 'end_date'
                          WHEN c.claim_id  IS NOT NULL THEN 'contributor_claim'
                          ELSE 'no_evidence' END AS basis,
                     CASE WHEN t.end_date  IS NOT NULL THEN t.end_date
                          WHEN c.claim_id  IS NOT NULL THEN c.claim_as_of END AS as_of,
                     CASE WHEN t.end_date  IS NULL     THEN c.claim_id END AS claim_id
                FROM target t
                LEFT JOIN LATERAL (
                     SELECT rc.id AS claim_id,
                            (rc.end_date::timestamp AT TIME ZONE 'UTC') AS claim_as_of
                       FROM role_claims rc
                      WHERE rc.resolved_role_id = t.id
                        AND rc.resolution IN ('linked','created')
                        AND rc.still_there
                        AND rc.end_date IS NOT NULL
                        AND rc.withdrawn_at IS NULL
                      ORDER BY rc.end_date DESC, rc.id DESC
                      LIMIT 1) c ON TRUE
            )
            UPDATE career_history ch
               SET employment_status  = e.status,
                   status_basis       = e.basis,
                   status_as_of       = e.as_of,
                   status_claim_id    = e.claim_id,
                   status_resolved_at = CASE
                       WHEN ROW(ch.employment_status, ch.status_basis, ch.status_as_of)
                            IS NOT DISTINCT FROM ROW(e.status, e.basis, e.as_of)
                       THEN ch.status_resolved_at ELSE now() END,
                   status_verified_at = CASE
                       WHEN ROW(ch.employment_status, ch.status_basis, ch.status_as_of)
                            IS NOT DISTINCT FROM ROW(e.status, e.basis, e.as_of)
                       THEN now() ELSE NULL END,
                   updated_at         = now()
              FROM evidence e
             WHERE ch.id = e.id
               AND ROW(ch.employment_status, ch.status_basis, ch.status_as_of, ch.status_claim_id)
                   IS DISTINCT FROM ROW(e.status, e.basis, e.as_of, e.claim_id)
            """;
}
