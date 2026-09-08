package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Proof challenges, and the figures that trigger them.
 *
 * <p>A challenge holds a rating until we know its author actually worked there. Held means saved,
 * visible to its author, in the admin queue, and not counting toward the contribution gate.
 */
public class ProofChallengeRepository {

    /** How long an untouched challenge waits before it counts as walked away from. */
    public static final int ABANDON_AFTER_DAYS = 7;

    private final SqlClient db;

    public ProofChallengeRepository(SqlClient db) {
        this.db = db;
    }

    // ── The tier inputs ─────────────────────────────────────────────────────────────────────

    /**
     * Is this a listed figure? Identity, never a bare name.
     *
     * <p>Matching on the name alone would challenge a site manager genuinely called Tim Cook, at a
     * construction firm with a team of eleven, to prove he knows himself — which breaks the one
     * rule the whole feature is built around. So a match needs either the exact manager row or the
     * name <em>and</em> the company.
     *
     * @param managerId the row being rated, when it already exists; may be null
     */
    public Future<Boolean> isHighProfile(Long managerId, String fullName, Long companyId) {
        String normalised = fullName == null ? "" : fullName.trim().toLowerCase();
        return db.preparedQuery("""
                SELECT EXISTS(
                    SELECT 1 FROM high_profile_figures
                    WHERE (manager_id IS NOT NULL AND manager_id = $1)
                       OR (full_name = $2 AND company_id IS NOT NULL AND company_id = $3)
                ) AS hit
                """)
            .execute(Tuple.of(managerId, normalised, companyId))
            .map(rows -> rows.iterator().next().getBoolean("hit"));
    }

    /**
     * The near miss: a listed name, at a company that is not theirs.
     *
     * <p>Deliberately not a hold. Holding it would reinstate the Tim Cook problem exactly, so the
     * rating publishes and the admin queue is told instead. The dodge works once, visibly, and a
     * second one from the same account walks that person down the confidence ladder.
     */
    public Future<Boolean> isNameOnlyMatch(String fullName, Long companyId) {
        String normalised = fullName == null ? "" : fullName.trim().toLowerCase();
        return db.preparedQuery("""
                SELECT EXISTS(
                    SELECT 1 FROM high_profile_figures
                    WHERE full_name = $1
                      AND (company_id IS DISTINCT FROM $2)
                ) AS hit
                """)
            .execute(Tuple.of(normalised, companyId))
            .map(rows -> rows.iterator().next().getBoolean("hit"));
    }

    /**
     * Does this author have proof outstanding?
     *
     * <p>All three unresolved states flag. Evidence under review is not yet proof, and walking
     * away is not a way out — without that, "challenge a famous name, abandon it, then submit the
     * junk you actually wanted" is the whole bypass restored in three clicks.
     */
    public Future<Boolean> hasUnresolvedChallenge(UUID userId) {
        return db.preparedQuery("""
                SELECT EXISTS(
                    SELECT 1 FROM manager_proof_challenges
                    WHERE user_id = $1
                      AND status IN ('open', 'admin_review', 'abandoned')
                ) AS flagged
                """)
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().next().getBoolean("flagged"));
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────────────────

    /** Opens a challenge, or leaves the existing one alone if this pair already has a live one. */
    public Future<Optional<Row>> open(UUID userId, long managerId, UUID reviewId, String reason) {
        return db.preparedQuery("""
                INSERT INTO manager_proof_challenges (user_id, manager_id, review_id, reason)
                VALUES ($1, $2, $3, $4)
                ON CONFLICT DO NOTHING
                RETURNING *
                """)
            .execute(Tuple.of(userId, managerId, reviewId, reason))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    public Future<Optional<Row>> findById(UUID challengeId) {
        return db.preparedQuery("SELECT * FROM manager_proof_challenges WHERE id = $1")
            .execute(Tuple.of(challengeId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /** This author's live challenge on this manager, for rendering the proof screen. */
    public Future<Optional<Row>> findLive(UUID userId, long managerId) {
        return db.preparedQuery("""
                SELECT * FROM manager_proof_challenges
                WHERE user_id = $1 AND manager_id = $2
                  AND status IN ('open', 'admin_review', 'abandoned')
                """)
            .execute(Tuple.of(userId, managerId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /**
     * Records the relationship claim and sends it to a person.
     *
     * <p>Never publishes, whatever the claim says and whatever the career-history cross-check
     * finds. A contradiction is surfaced to the admin; the absence of one only means nothing looks
     * wrong, which is not the same as verified.
     */
    public Future<Optional<Row>> submitEvidence(UUID challengeId, UUID userId,
                                                LocalDate workedFrom, LocalDate workedUntil,
                                                String claimedTitle, String claimedOrg,
                                                String relationship, String evidenceNote) {
        return db.preparedQuery("""
                UPDATE manager_proof_challenges
                SET status = 'admin_review',
                    worked_from = $3, worked_until = $4,
                    claimed_title = $5, claimed_org = $6,
                    relationship = $7, evidence_note = $8,
                    submitted_at = now()
                WHERE id = $1 AND user_id = $2
                  AND status IN ('open', 'abandoned')
                RETURNING *
                """)
            .execute(Tuple.of(challengeId, userId, workedFrom, workedUntil,
                              claimedTitle, claimedOrg, relationship, evidenceNote))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /** Marks a challenge resolved. {@code approved}, {@code rejected} or {@code verified}. */
    public Future<Optional<Row>> resolve(UUID challengeId, UUID adminId, String status) {
        return db.preparedQuery("""
                UPDATE manager_proof_challenges
                SET status = $3, resolved_at = now(), resolved_by = $2
                WHERE id = $1
                  AND status IN ('open', 'admin_review', 'abandoned')
                RETURNING *
                """)
            .execute(Tuple.of(challengeId, adminId, status))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /**
     * Ages out challenges nobody supplied anything for.
     *
     * <p>The debit is applied separately and keyed on the challenge id, so a rerun of the daily
     * sweep cannot charge the same person twice — the uniqueness lives in the ledger, not in this
     * query being careful.
     */
    public Future<RowSet<Row>> markAbandoned() {
        return db.preparedQuery("""
                UPDATE manager_proof_challenges
                SET status = 'abandoned'
                WHERE status = 'open'
                  AND created_at <= now() - make_interval(days => $1)
                RETURNING id, user_id
                """)
            .execute(Tuple.of(ABANDON_AFTER_DAYS));
    }

    // ── Admin queue ─────────────────────────────────────────────────────────────────────────

    /**
     * Everything waiting on a person, with what they need to judge it.
     *
     * <p>Covers {@code open} and {@code abandoned} as well as submitted evidence. Without that, a
     * challenge somebody walked away from would flag its author forever while never appearing in
     * front of anyone who could lift it — a lock with no key.
     *
     * <p>The career-history join is the cross-check: a claim to have reported to someone in 2019
     * when we have them at a different company then is a contradiction in our own data, and the
     * admin should see it rather than have to know it.
     */
    public Future<RowSet<Row>> findForAdmin(int limit, int offset) {
        return db.preparedQuery("""
                SELECT c.*, m.name AS manager_name, m.company AS manager_company,
                       u.confidence AS author_confidence,
                       EXISTS (
                           SELECT 1 FROM career_history ch
                           WHERE ch.manager_id = c.manager_id
                             AND c.worked_from IS NOT NULL
                             AND ch.start_date <= c.worked_from
                             AND (ch.end_date IS NULL OR ch.end_date >= c.worked_from)
                       ) AS claim_corroborated
                FROM manager_proof_challenges c
                JOIN managers m ON m.id = c.manager_id
                JOIN users    u ON u.id = c.user_id
                WHERE c.status IN ('open', 'admin_review', 'abandoned')
                ORDER BY (c.status = 'admin_review') DESC,
                         COALESCE(c.submitted_at, c.created_at) DESC
                LIMIT $1 OFFSET $2
                """)
            .execute(Tuple.of(limit, offset));
    }

    public Future<Long> countForAdmin() {
        return db.query("""
                SELECT COUNT(*) AS cnt FROM manager_proof_challenges
                WHERE status IN ('open', 'admin_review', 'abandoned')
                """)
            .execute()
            .map(rows -> rows.iterator().next().getLong("cnt"));
    }

    // ── The figures list ────────────────────────────────────────────────────────────────────

    public Future<RowSet<Row>> listFigures() {
        return db.query("""
                SELECT f.id, f.manager_id, f.full_name, f.company_id, f.note, f.created_at,
                       c.name AS company_name
                FROM high_profile_figures f
                LEFT JOIN companies c ON c.id = f.company_id
                ORDER BY f.created_at DESC
                """)
            .execute();
    }

    public Future<Row> addFigure(Long managerId, String fullName, Long companyId, String note) {
        String normalised = fullName == null ? null : fullName.trim().toLowerCase();
        return db.preparedQuery("""
                INSERT INTO high_profile_figures (manager_id, full_name, company_id, note)
                VALUES ($1, $2, $3, $4)
                RETURNING *
                """)
            .execute(Tuple.of(managerId, normalised, companyId, note))
            .map(rows -> rows.iterator().next());
    }

    public Future<Boolean> removeFigure(long id) {
        return db.preparedQuery("DELETE FROM high_profile_figures WHERE id = $1")
            .execute(Tuple.of(id))
            .map(rows -> rows.rowCount() > 0);
    }
}
