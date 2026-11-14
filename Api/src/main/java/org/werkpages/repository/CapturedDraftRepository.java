package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.Optional;
import java.util.UUID;

/**
 * Partial submissions, kept for an admin to read and, when they are complete, to publish.
 *
 * <p>Only for the forms that have no domain row to create when somebody walks away. The manager
 * forms capture into real {@code pending_approval} rows, which an admin already reviews; duplicating
 * them here would put the same submission in two queues.
 *
 * <h2>Resolution</h2>
 *
 * <p>A draft leaves the queue in one of three ways, and which one it was matters enough to store:
 * {@code published} means an admin turned it into an anonymous rating, {@code dismissed} means
 * they read it and did not, and {@code superseded} means its work reached the corpus by another
 * route, because the author came back and submitted it or because a sibling draft holding the same
 * form was published. Rows are never deleted on resolution, so the queue can be audited.
 */
public class CapturedDraftRepository {

    public static final String COMPANY_RATING = "company_rating";
    public static final String INTERVIEW      = "interview";
    public static final String MANAGER_REVIEW = "manager_review";

    public static final String PUBLISHED  = "published";
    public static final String DISMISSED  = "dismissed";
    public static final String SUPERSEDED = "superseded";

    private final SqlClient db;

    public CapturedDraftRepository(SqlClient db) {
        this.db = db;
    }

    public static final String ACTIVE    = "active";
    public static final String ABANDONED = "abandoned";
    public static final String RESOLVED  = "resolved";

    /**
     * Records what a form holds, now, and again every few seconds while it is being filled in.
     *
     * <p><b>An upsert on the form's token, and that is the whole deduplication story.</b> Autosave
     * fires on a debounce, so a single rating produces dozens of these. Without a unique token the
     * queue would fill with one row per pause in typing, and the aggregate statistics this data
     * exists to feed would count one person's single opinion dozens of times over.
     *
     * <p>Three conditions on the update, each refusing a specific way this could corrupt data:
     *
     * <ul>
     *   <li><b>{@code revision <}</b> - an autosave delayed in flight cannot replace newer answers
     *       with older ones. Two tabs on one form share a token, so without this the tab that
     *       happened to arrive last would win, which is not the same as the newest answer winning.
     *   <li><b>{@code resolution IS NULL}</b> - a draft an admin published or dismissed carries
     *       their decision and the record of what it became. A late autosave must not quietly
     *       reopen it.
     *   <li><b>{@code state} set back to active</b> - somebody who returns to an unfinished form
     *       takes it out of the approval queue again. Abandonment is an observation about silence,
     *       not a verdict.
     * </ul>
     *
     * <p>No row is touched when none of those hold, and the caller is told nothing, deliberately:
     * a refused autosave is not an error anybody can act on, and the person is still typing.
     *
     * <p>{@code revision} comes from the browser and is trusted for ordering alone. Nothing about
     * correctness depends on its honesty: the worst a bad value does is make one contributor's own
     * later answers lose to their earlier ones.
     */
    public Future<Void> autosave(String kind, Long companyId, Long managerId, UUID userId,
                                 UUID draftToken, JsonObject payload, long revision) {
        if (draftToken == null) return Future.succeededFuture();
        return db.preparedQuery("""
                INSERT INTO captured_drafts
                    (kind, company_id, manager_id, user_id, draft_token, payload, revision, state)
                VALUES ($1, $2, $3, $4, $5, $6, $7, 'active')
                ON CONFLICT (draft_token) DO UPDATE
                   SET payload    = EXCLUDED.payload,
                       revision   = EXCLUDED.revision,
                       company_id = COALESCE(EXCLUDED.company_id, captured_drafts.company_id),
                       manager_id = COALESCE(EXCLUDED.manager_id, captured_drafts.manager_id),
                       user_id    = COALESCE(EXCLUDED.user_id,    captured_drafts.user_id),
                       state      = 'active',
                       updated_at = now()
                 WHERE captured_drafts.revision < EXCLUDED.revision
                   AND captured_drafts.resolution IS NULL
                """)
            .execute(Tuple.of(kind, companyId, managerId, userId, draftToken, payload, revision))
            .mapEmpty();
    }

    /**
     * Moves drafts nobody has touched lately out of 'active' so the queue can show them.
     *
     * <p>Run from the maintenance sweep. The window is an inactivity timeout rather than a
     * judgement: a contributor who comes back resumes the same token and {@link #autosave} puts it
     * straight back to active.
     *
     * <p>Leaves resolved rows alone, so an admin's decision is never relabelled.
     */
    public Future<Integer> markAbandoned(int minutesIdle) {
        return db.preparedQuery("""
                UPDATE captured_drafts
                   SET state = 'abandoned'
                 WHERE state = 'active'
                   AND resolution IS NULL
                   AND updated_at < now() - make_interval(mins => $1)
                """)
            .execute(Tuple.of(minutesIdle))
            .map(io.vertx.sqlclient.SqlResult::rowCount);
    }

    /**
     * Records what a form held, in one shot, at submit.
     *
     * <p>A later capture of the same form REPLACES the unreviewed one before it. This used to be a
     * plain insert, which was tolerable while the form captured once, at submit; it is not now
     * that it captures on every attempt so a partial answer survives. One person's single opinion
     * must be one row in the queue.
     */
    public Future<Void> capture(String kind, Long companyId, Long managerId,
                                UUID userId, UUID draftToken, JsonObject payload) {
        /*
          THE SAME UPSERT AUTOSAVE USES, and for the same reasons.

          This used to delete the unreviewed row and insert a fresh one. That worked while the
          token index was non-unique; V97 made it unique, and a capture arriving after an admin
          had published the draft then hit a duplicate key, because the delete correctly skipped
          the resolved row and the insert collided with it.

          An upsert guarded on `resolution IS NULL` is the honest behaviour: a form session that
          has already become a published or dismissed rating is finished, and a late capture under
          its token is ignored rather than being allowed to reopen it. The client mints a new
          token for a genuinely new attempt, because submitting clears the stored draft.

          Capture carries no revision of its own - it IS the newest state by definition, arriving
          at submit - so it takes the clock, which is always ahead of any autosave before it.
        */
        return db.preparedQuery("""
                INSERT INTO captured_drafts
                    (kind, company_id, manager_id, user_id, draft_token, payload, revision, state)
                VALUES ($1, $2, $3, $4, $5, $6, $7, 'abandoned')
                ON CONFLICT (draft_token) DO UPDATE
                   SET payload    = EXCLUDED.payload,
                       revision   = EXCLUDED.revision,
                       company_id = COALESCE(EXCLUDED.company_id, captured_drafts.company_id),
                       manager_id = COALESCE(EXCLUDED.manager_id, captured_drafts.manager_id),
                       user_id    = COALESCE(EXCLUDED.user_id,    captured_drafts.user_id),
                       state      = 'abandoned',
                       updated_at = now()
                 WHERE captured_drafts.resolution IS NULL
                """)
            .execute(Tuple.of(kind, companyId, managerId, userId, draftToken, payload,
                              System.currentTimeMillis()))
            .mapEmpty();
    }

    /**
     * Takes the row lock on every draft holding this token, so two writers cannot act on one form.
     *
     * <p><b>This is the whole of the concurrency rule.</b> An admin publishing a draft and its
     * author returning to submit it properly are two transactions that both decide what happens to
     * the same captured form, and nothing else they touch is shared: the admin writes an anonymous
     * rating, the author writes their own, and each then removes what the other would have left
     * behind. Without a common lock both can read "no publication exists yet" and both can write
     * one.
     *
     * <p>A null token cannot be coordinated and does not need to be: the author's return finds a
     * published row by token, so a draft that never carried one can never be matched to a
     * submission, and there is no pair of writers to serialise.
     *
     * <p>{@code FOR UPDATE} on a SELECT that may match nothing is still correct. It locks whatever
     * is there now, which is what both paths read before deciding.
     */
    public Future<Void> lockByToken(SqlClient conn, UUID draftToken) {
        if (draftToken == null) return Future.succeededFuture();
        return conn.preparedQuery("SELECT id FROM captured_drafts WHERE draft_token = $1 FOR UPDATE")
            .execute(Tuple.of(draftToken))
            .mapEmpty();
    }

    /**
     * Loads one draft for publication, holding its row lock until the transaction ends.
     *
     * <p>Empty means no such draft. A draft that is already resolved comes back anyway, so the
     * caller can refuse it with a reason rather than a 404 it cannot explain.
     */
    public Future<Optional<Row>> findForPublication(SqlClient conn, long id) {
        return conn.preparedQuery("""
                SELECT id, kind, company_id, manager_id, user_id, draft_token, payload,
                       reviewed_at, resolution
                  FROM captured_drafts
                 WHERE id = $1
                 FOR UPDATE
                """)
            .execute(Tuple.of(id))
            .map(rs -> rs.iterator().hasNext() ? Optional.of(rs.iterator().next()) : Optional.empty());
    }

    /**
     * Records what one draft became.
     *
     * <p>Guarded on {@code reviewed_at IS NULL}, so a draft resolved by somebody else between the
     * read and the write reports false rather than having its outcome overwritten. The caller is
     * inside a transaction holding this row's lock, which makes that a backstop rather than the
     * protection, but a resolution that can be silently replaced is not an audit trail.
     *
     * @param resolvedId the rating it became, or null when it became nothing
     */
    public Future<Boolean> resolve(SqlClient conn, long id, String resolution,
                                   UUID resolvedId, UUID resolvedBy) {
        return conn.preparedQuery("""
                UPDATE captured_drafts
                   SET reviewed_at = now(), resolution = $2, resolved_id = $3, resolved_by = $4,
                       state = 'resolved', updated_at = now()
                 WHERE id = $1 AND reviewed_at IS NULL
                """)
            .execute(Tuple.of(id, resolution, resolvedId, resolvedBy))
            .map(rs -> rs.rowCount() > 0);
    }

    /**
     * Removes the drafts a finished submission came from, and only the untouched ones.
     *
     * <p>Called on the real submit. Without it an admin spends their time reading drafts whose
     * authors came back a minute later and completed the form.
     *
     * <p><b>{@code reviewed_at IS NULL} is the part that matters.</b> This used to delete every row
     * holding the token, which was harmless while the only outcome was "an admin read it" and is
     * not any more: a draft an admin PUBLISHED carries the record of what it became and who
     * decided, and the author coming back afterwards would have destroyed exactly the row that
     * explains where an anonymous rating on the page came from.
     */
    public Future<Void> clear(SqlClient conn, UUID draftToken) {
        if (draftToken == null) return Future.succeededFuture();
        return conn.preparedQuery(
                "DELETE FROM captured_drafts WHERE draft_token = $1 AND reviewed_at IS NULL")
            .execute(Tuple.of(draftToken))
            .mapEmpty();
    }

    /**
     * Retires the other unresolved drafts of a form that has just been published.
     *
     * <p>The capture path inserts a fresh row every time somebody is bounced, so one form can hold
     * several drafts. Without this they stay in the queue, and the next attempt to publish one is
     * refused by a unique index rather than by anything an admin can act on.
     *
     * <p>Skips the row being published, which the caller resolves itself, and skips anything
     * already resolved, so an earlier decision is never rewritten.
     */
    public Future<Void> supersedeSiblings(SqlClient conn, UUID draftToken, long publishedId,
                                          UUID resolvedId) {
        if (draftToken == null) return Future.succeededFuture();
        return conn.preparedQuery("""
                UPDATE captured_drafts
                   SET reviewed_at = now(), resolution = 'superseded', resolved_id = $3,
                       state = 'resolved', updated_at = now()
                 WHERE draft_token = $1 AND id <> $2 AND reviewed_at IS NULL
                """)
            .execute(Tuple.of(draftToken, publishedId, resolvedId))
            .mapEmpty();
    }

    /**
     * Points a published draft at the rating that now represents it.
     *
     * <p>For the one sequence where both halves of this feature fire: an admin publishes a draft,
     * and its author then returns, signs in and submits the same form. The anonymous publication
     * is removed in favour of theirs, which would leave the draft's trail naming a rating that no
     * longer exists. It was still published, and this is what it is now, so the outcome stays
     * {@code published} and only the id moves.
     */
    public Future<Void> repointPublished(SqlClient conn, UUID draftToken, UUID resolvedId) {
        if (draftToken == null) return Future.succeededFuture();
        return conn.preparedQuery("""
                UPDATE captured_drafts SET resolved_id = $2
                 WHERE draft_token = $1 AND resolution = 'published'
                """)
            .execute(Tuple.of(draftToken, resolvedId))
            .mapEmpty();
    }

    /**
     * Forgets who dropped a draft off, at the moment it becomes a public rating.
     *
     * <p><b>The published rating must not be traceable to a person, by any route.</b> The rating
     * itself carries no {@code user_id}, but it does carry the {@code draft_token} that identifies
     * the form, and while this column holds an account that token is a join back to them. A
     * dropped-off manager rating has no such row anywhere, and these are meant to behave the same
     * way.
     *
     * <p>Timed deliberately. The admin can see who dropped it off while they are DECIDING, which
     * is when that fact is worth having: a draft from a known troublemaker is a different thing to
     * read. Once they have published it, the same fact only serves to re-identify the author of a
     * public rating, so it goes.
     *
     * <p>Only on publication. A dismissed draft keeps its submitter, because no public rating
     * exists to tie back to and the record is still useful to a moderator.
     */
    public Future<Void> forgetSubmitter(SqlClient conn, long id) {
        return conn.preparedQuery("UPDATE captured_drafts SET user_id = NULL WHERE id = $1")
            .execute(Tuple.of(id))
            .mapEmpty();
    }

    /**
     * The queue: drafts nobody is editing right now, newest activity first.
     *
     * <p><b>'active' is excluded, and that is the point of the state column.</b> Forms autosave
     * every few seconds, so without this an admin would watch new items appear and change under
     * them as somebody typed, and would be reviewing a rating that was still being written.
     *
     * <p>Ordered by {@code updated_at} rather than {@code created_at}: when a draft last changed
     * is what says how stale it is, and a resumed form that has just gone quiet again is more
     * interesting than one started first.
     */
    public Future<RowSet<Row>> findUnreviewed(int limit, int offset) {
        return db.preparedQuery("""
                SELECT d.id, d.kind, d.payload, d.created_at, d.updated_at, d.state,
                       d.company_id, c.name AS company_name, c.slug AS company_slug,
                       d.manager_id, m.name AS manager_name,
                       u.username AS author_username
                  FROM captured_drafts d
                  LEFT JOIN companies c ON c.id = d.company_id
                  LEFT JOIN managers  m ON m.id = d.manager_id
                  LEFT JOIN users     u ON u.id = d.user_id
                 WHERE d.reviewed_at IS NULL
                   AND d.state <> 'active'
                 ORDER BY d.updated_at DESC
                 LIMIT $1 OFFSET $2
                """)
            .execute(Tuple.of(limit, offset));
    }

    public Future<Long> countUnreviewed() {
        return db.preparedQuery(
                "SELECT count(*) AS c FROM captured_drafts "
              + "WHERE reviewed_at IS NULL AND state <> 'active'")
            .execute()
            .map(rs -> rs.iterator().next().getLong("c"));
    }

    /**
     * Marks one draft read and not published. Kept rather than deleted, so the queue can be audited.
     *
     * <p>Records who decided. A draft holding a complete rating that an admin chose not to publish
     * is a judgement call, and the trail should say whose.
     */
    public Future<Boolean> dismiss(long id, UUID adminUserId) {
        return db.preparedQuery("""
                UPDATE captured_drafts
                   SET reviewed_at = now(), resolution = 'dismissed', resolved_by = $2,
                       state = 'resolved', updated_at = now()
                 WHERE id = $1 AND reviewed_at IS NULL
                """)
            .execute(Tuple.of(id, adminUserId))
            .map(rs -> rs.rowCount() > 0);
    }
}
