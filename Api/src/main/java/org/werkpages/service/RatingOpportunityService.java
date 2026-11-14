package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.util.OptionalInt;
import java.util.UUID;

/**
 * The rating allowance: two opportunities per contributor per subject, counted independently by
 * type and subject.
 *
 * <h2>What an opportunity is, and what it is not</h2>
 *
 * <p><b>An opportunity is consumed when one is OPENED, never when a rating is deleted.</b> That one
 * sentence is the whole policy, and everything awkward about this class follows from it. Counting
 * submissions would make editing, retrying and replacing all cost allowance; counting opportunities
 * means a replacement inside the three-day window is free and an edit is free, which is what was
 * asked for.
 *
 * <p>So the charge happens in exactly one place: a submission by an authenticated contributor that
 * legitimately opens a NEW opportunity. Not a retry of a failed submit, not an edit, not a
 * replacement of a rating still inside its window. Those reuse the opportunity already spent.
 *
 * <p><b>The cap is on opportunities and never on published ratings.</b> Retiring an opportunity
 * does not retire a rating: after a merge that retires over-cap rows, every historical rating
 * remains live and publicly counted. Conflating the two would corrupt the figures.
 *
 * <h2>Anonymous submissions charge nothing</h2>
 *
 * <p>An allowance is keyed on an authenticated contributor, so there is nothing to charge it to
 * until there is an account. <b>Adoption is the charging event.</b> A signed-out person fills in the
 * form, the draft is captured, and the opportunity is charged in the transaction where they adopt
 * that draft under a real account and the rating is written. A reconciliation branch that writes no
 * rating, because the contributor already holds one for that employment, charges nothing.
 *
 * <p>{@link #charge} refuses a null contributor rather than silently doing nothing, because "the
 * anonymous path quietly skipped the allowance" is indistinguishable from "the allowance was never
 * wired up" when you are reading a bug report six months later.
 *
 * <h2>Why this is a read-then-insert under a lock, and not an upsert</h2>
 *
 * <p>Rev 268 specifies the charge as an upsert with {@code ON CONFLICT ... WHERE used < 2}. That
 * text belongs to the superseded counter model, whose unique constraint was not deferrable, and it
 * cannot be carried over: {@code opportunity_one_per_ordinal} had to become DEFERRABLE so the merge
 * could move and renumber in one statement, and PostgreSQL refuses outright to use a deferrable
 * constraint as an upsert arbiter.
 *
 * <pre>
 * ERROR:  ON CONFLICT does not support deferrable unique constraints/exclusion
 *         constraints as arbiters
 * </pre>
 *
 * <p>The design never restates the charging statement after making that change, so this is the gap
 * being filled rather than a deviation from it. The replacement serialises on the subject row
 * instead, which is a lock the submit transaction already holds for its own reasons and which the
 * canonical lock order already places before {@code reviews}. Under that lock the read of the free
 * ordinal and the insert that takes it cannot interleave, so two concurrent submissions cannot both
 * take the last opportunity: the second waits, then finds no free ordinal and is refused.
 *
 * <p>The refusal is a zero-row result and not an exception, which matters: a cap that is reached is
 * an ordinary product outcome needing a message, not a 500.
 *
 * <h2>Privacy</h2>
 *
 * <p><b>No column of {@code rating_opportunities} may ever hold a rating id.</b> Two revisions put
 * one there and both were identity-reconstruction defects: a value equal to a review id, in a row
 * that also carries {@code user_id}, recovers the author of an anonymised rating in one join and
 * needs no foreign key to do it. This class therefore never writes, reads or accepts a review id,
 * and {@code originClaimId} is informational only.
 *
 * <p>Reads are restricted by policy to the two shapes here: the service's own authorization lookup
 * for one contributor and one subject, and aggregates carrying no contributor column. The table is
 * on the denylist for every export and analytics feed, and no administrative screen renders a
 * contributor-scoped row.
 */
public final class RatingOpportunityService {

    /** Two per contributor per subject. Enforced by the database, not by this number. */
    public static final int OPPORTUNITIES_PER_SUBJECT = 2;

    public static final String MANAGER   = "manager";
    public static final String COMPANY   = "company";
    public static final String INTERVIEW = "interview";

    /**
     * Lock the subject so a charge on it is serialised.
     *
     * <p>Call this before {@link #charge} in the same transaction, unless the transaction already
     * holds the row, which a manager submission does. It is {@code FOR UPDATE} on the subject's own
     * row and takes nothing else, so it respects the canonical order wherever it is called from: the
     * subject tables are {@code companies} and {@code managers}, both of which precede
     * {@code reviews}.
     *
     * <p>An interview subject is a company, because interview eligibility is scoped by company and
     * an interview subject follows its company through a merge.
     */
    public Future<Void> lockSubject(SqlConnection tx, String subjectType, long subjectId) {
        String sql = MANAGER.equals(subjectType)
            ? "SELECT id FROM managers WHERE id = $1 FOR UPDATE"
            : "SELECT id FROM companies WHERE id = $1 FOR UPDATE";
        return tx.preparedQuery(sql).execute(Tuple.of(subjectId)).mapEmpty();
    }

    /**
     * Take the next free opportunity, in the caller's transaction.
     *
     * <p><b>This must be in the same transaction as the rating insert it authorises</b>, so that the
     * charge and the thing charged for commit or roll back together. A charge that commits beside a
     * failed submission silently costs somebody half their lifetime allowance for a subject.
     *
     * @param originClaimId informational provenance, nullable. NOT the opportunity's identity: the
     *                      claim identifies the employment, which is deliberately singular, while
     *                      the opportunities on it are deliberately plural, so keying on it rejects
     *                      the second one.
     * @return the ordinal taken, or empty when the contributor has no opportunity left for this
     *         subject. Empty is a product outcome, not a failure.
     */
    public Future<OptionalInt> charge(SqlConnection tx, UUID userId, String subjectType,
                                      long subjectId, Long originClaimId) {
        if (userId == null) {
            return Future.failedFuture(new IllegalArgumentException(
                "An anonymous submission cannot charge a rating opportunity. Adoption is the "
                + "charging event, so charge in the transaction that writes the signed rating."));
        }
        if (!MANAGER.equals(subjectType) && !COMPANY.equals(subjectType)
            && !INTERVIEW.equals(subjectType)) {
            return Future.failedFuture(new IllegalArgumentException(
                "Unknown rating subject type: " + subjectType));
        }
        // The free ordinal and the insert that takes it, in ONE statement. Two statements would
        // leave a window even under the subject lock if a caller forgot to take it.
        return tx.preparedQuery("""
                WITH slots(n) AS (VALUES (1::SMALLINT),(2::SMALLINT)),
                     free AS (
                       SELECT MIN(s.n) AS n FROM slots s
                        WHERE NOT EXISTS (
                          SELECT 1 FROM rating_opportunities o
                           WHERE o.user_id      = $1
                             AND o.subject_type = $2
                             AND o.subject_id   = $3
                             AND o.ordinal      = s.n)
                     )
                INSERT INTO rating_opportunities
                       (user_id, subject_type, subject_id, ordinal, origin_claim_id)
                SELECT $1, $2, $3, free.n, $4 FROM free WHERE free.n IS NOT NULL
                RETURNING ordinal
                """)
            .execute(Tuple.of(userId, subjectType, subjectId, originClaimId))
            .map(rs -> {
                if (rs.rowCount() == 0) {
                    return OptionalInt.empty();
                }
                return OptionalInt.of(rs.iterator().next().get(Short.class, "ordinal").intValue());
            });
    }

    /**
     * How many opportunities this contributor has spent on this subject, 0 to 2.
     *
     * <p>The only other legitimate read of this table. It answers one contributor and one subject
     * and returns a count, which is what an authorization check needs and the least it can be given.
     */
    public Future<Integer> spent(SqlClient conn, UUID userId, String subjectType, long subjectId) {
        return conn.preparedQuery("""
                SELECT count(*)::INT AS n FROM rating_opportunities
                 WHERE user_id = $1 AND subject_type = $2 AND subject_id = $3
                """)
            .execute(Tuple.of(userId, subjectType, subjectId))
            .map(rs -> rs.iterator().next().getInteger("n"));
    }

    /** Whether a new opportunity could be opened. A replacement or edit does not need to ask. */
    public Future<Boolean> hasCapacity(SqlClient conn, UUID userId, String subjectType,
                                       long subjectId) {
        return spent(conn, userId, subjectType, subjectId)
            .map(n -> n < OPPORTUNITIES_PER_SUBJECT);
    }

    /**
     * Whether a failure is two submissions contending for the same ordinal rather than a defect.
     *
     * <p>Only reachable when a caller charged without holding the subject lock. It is worth
     * retrying the transaction rather than returning a 500, because on the retry the contributor
     * either gets the other ordinal or is properly told the cap is reached.
     */
    public static boolean isOrdinalContention(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof PgException pg
                && "23505".equals(pg.getSqlState())
                && pg.getErrorMessage() != null
                && pg.getErrorMessage().contains("opportunity_one_per_ordinal")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Opportunities spent on a subject by everybody, with no contributor column.
     *
     * <p>The sanctioned operational interface for capacity questions. Rev 268 sanctions a view for
     * this; this repository does not use views as application architecture, and that invariant wins,
     * so it is repository SQL instead. Deliberately returns no {@code user_id}: capacity questions
     * are answerable without identity, and anything that renders per-contributor rows is out of
     * policy.
     */
    public Future<Integer> openedForSubject(SqlClient conn, String subjectType, long subjectId) {
        return conn.preparedQuery("""
                SELECT count(*)::INT AS n FROM rating_opportunities
                 WHERE subject_type = $1 AND subject_id = $2
                """)
            .execute(Tuple.of(subjectType, subjectId))
            .map(rs -> rs.iterator().next().getInteger("n"));
    }
}
