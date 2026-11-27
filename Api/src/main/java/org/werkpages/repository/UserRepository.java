package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import org.werkpages.service.EncryptionService;

import java.util.Optional;
import java.util.UUID;

/**
 * Data-access layer for the {@code users} and {@code banned_users} tables.
 * All methods return {@link Future} and perform no business logic.
 *
 * When an {@link EncryptionService} is supplied, email, first_name, and last_name
 * are encrypted at rest. Callers that read those fields from a returned {@link Row}
 * must call {@link #decryptField} before using the value.
 */
public class UserRepository {

    private final SqlClient       db;
    private final EncryptionService enc; // nullable — null means no encryption (dev / tests)

    /** No-encryption constructor — used by integration tests. */
    public UserRepository(SqlClient db) {
        this(db, null);
    }

    public UserRepository(SqlClient db, EncryptionService enc) {
        this.db  = db;
        this.enc = enc;
    }

    // ── Encryption helpers ────────────────────────────────────────────────────

    /** Encrypts {@code s} if encryption is enabled, otherwise returns {@code s} unchanged. */
    private String e(String s) { return enc != null ? enc.encrypt(s) : s; }

    /** Decrypts {@code s} if encryption is enabled, otherwise returns {@code s} unchanged. */
    private String d(String s) { return enc != null ? enc.decrypt(s) : s; }

    /** HMAC for blind-index lookup; returns {@code s} as-is when encryption is disabled. */
    private String h(String s) { return enc != null ? enc.hmac(s) : s; }

    /**
     * Decrypts a field value read from a returned {@link Row}.
     * Call this on any email / first_name / last_name value obtained via
     * {@code row.getString(...)}, e.g. {@code userRepo.decryptField(row.getString("email"))}.
     */
    public String decryptField(String value) { return d(value); }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** Full profile including ban status, used after JWT authentication. */
    public Future<Optional<Row>> findByAuth0IdWithBan(String auth0Id) {
        return db.preparedQuery("""
                SELECT u.id, u.auth0_id,
                       COALESCE(u.email_encrypted, u.email)           AS email,
                       u.username, u.username_is_provisional,
                       COALESCE(u.first_name_encrypted, u.first_name) AS first_name,
                       COALESCE(u.last_name_encrypted,  u.last_name)  AS last_name,
                       u.role, u.created_at, (b.id IS NOT NULL) AS is_banned
                FROM users u
                LEFT JOIN banned_users b ON b.user_id = u.id
                WHERE u.auth0_id = $1
                """)
            .execute(Tuple.of(auth0Id))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /** Minimal lookup — just id. */
    public Future<Optional<UUID>> findIdByAuth0Id(String auth0Id) {
        return db.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next().getUUID("id"))
                : Optional.empty());
    }

    /** Includes ban check — used for sign-in response. */
    public Future<Optional<Row>> findByAuth0IdForSignin(String auth0Id) {
        return db.preparedQuery("""
                SELECT u.id,
                       COALESCE(u.email_encrypted, u.email)           AS email,
                       u.username, u.username_is_provisional,
                       COALESCE(u.first_name_encrypted, u.first_name) AS first_name,
                       COALESCE(u.last_name_encrypted,  u.last_name)  AS last_name,
                       u.role, (b.id IS NOT NULL) AS is_banned
                FROM users u
                LEFT JOIN banned_users b ON b.user_id = u.id
                WHERE u.auth0_id = $1
                """)
            .execute(Tuple.of(auth0Id))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    public Future<Boolean> isBanned(String auth0Id) {
        return db.preparedQuery("""
                SELECT EXISTS(
                    SELECT 1 FROM banned_users b
                    JOIN users u ON b.user_id = u.id
                    WHERE u.auth0_id = $1
                )
                """)
            .execute(Tuple.of(auth0Id))
            .map(rows -> rows.iterator().next().getBoolean(0));
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    public Future<RowSet<Row>> create(String auth0Id, String email, String username,
                                      String firstName, String lastName) {
        return create(auth0Id, email, username, firstName, lastName, false);
    }

    /**
     * Creates the account, recording whether the username is a placeholder.
     *
     * <p>{@code provisional} is what makes V99's column mean anything. An account created through
     * Universal Login has no username yet - the person chose an email and a password on Auth0's
     * page and was never asked for one - so the callback generates {@code user_1a2b3c4d} and the
     * profile-completion step asks them to choose. Until they do, the flag says so.
     *
     * <p>Without the flag the generated name is indistinguishable from a chosen one, so nothing
     * can tell who still needs to complete their profile, and
     * {@code ManagerService.looksProvisional} would be the only guard standing between a
     * placeholder and a published review.
     *
     * <p>V99 constrains the pair: a flagged username must match {@code ^user_[0-9a-f]{8}$}. The
     * check is one-directional on purpose - choosing {@code user_deadbeef} unflagged is allowed,
     * because forbidding it would mean policing a name somebody is entitled to pick.
     */
    public Future<RowSet<Row>> create(String auth0Id, String email, String username,
                                      String firstName, String lastName, boolean provisional) {
        return db.preparedQuery("""
                INSERT INTO users (auth0_id, email_encrypted, email_hash, username,
                                   first_name_encrypted, last_name_encrypted,
                                   username_is_provisional)
                VALUES ($1, $2, $3, $4, $5, $6, $7)
                """)
            .execute(Tuple.of(auth0Id, e(email), h(email), username, e(firstName), e(lastName),
                              provisional));
    }

    /**
     * Claims a real username, clearing the provisional flag. ONE statement.
     *
     * <p>The uniqueness check and the write have to be the same statement. Checking availability
     * and then writing is a race two people hit at once, and V99's {@code users_username_ci} index
     * is what actually decides it - so the insert is attempted and a unique violation is reported
     * as "taken" rather than asked about first.
     *
     * <p>Refuses when the account's username is NOT provisional. Changing an established username
     * is a different feature with different consequences - old links, old mentions, impersonation
     * of somebody who released a name - and this endpoint must not become a back door to it.
     *
     * @return true when the username was claimed, false when the profile was already complete
     */
    public Future<Boolean> claimUsername(java.util.UUID userId, String username) {
        return claimUsername(userId, username, null, null);
    }

    /**
     * Claims the username AND the names, in one statement.
     *
     * <p>One statement because they were one form. The old signup page asked for a first name, a
     * last name, an email, a username and a password together; Universal Login takes the email and
     * the password, so everything else is asked for here instead - and a profile that is complete
     * except for a surname is not a state worth being able to reach.
     *
     * <p>A null name leaves whatever is there alone rather than blanking it. The social path
     * already fills names from the identity provider, so somebody completing a profile after a
     * Google sign-in must not have those wiped by a form that did not ask.
     */
    public Future<Boolean> claimUsername(java.util.UUID userId, String username,
                                         String firstName, String lastName) {
        return db.preparedQuery("""
                UPDATE users
                   SET username = $2,
                       username_is_provisional = FALSE,
                       first_name_encrypted = COALESCE($3, first_name_encrypted),
                       last_name_encrypted  = COALESCE($4, last_name_encrypted)
                 WHERE id = $1 AND username_is_provisional
                """)
            .execute(Tuple.of(userId, username,
                              firstName == null || firstName.isBlank() ? null : e(firstName),
                              lastName  == null || lastName.isBlank()  ? null : e(lastName)))
            .map(rows -> rows.rowCount() > 0);
    }

    /**
     * Anonymises everything the person wrote, then deletes the account. <b>One transaction.</b>
     *
     * <h2>Why this is now transactional, and what it was before</h2>
     *
     * <p>These statements used to be chained on the pool, with a comment saying that was
     * "acceptable given the soft anonymize-then-delete pattern". It was not, and the reason is the
     * order: <b>the delete is last</b>. When it fails, the anonymisation has already committed.
     *
     * <p>That end state was reached for real, not hypothesised: once a signed-in contributor owned
     * a {@code role_claims} row, {@code role_claims_user_id_fkey} refused the delete, and the
     * person was left <b>half-deleted</b> - their ratings severed from them and anonymous, their
     * account still present, and no way to put the identity back. A retry cannot repair it, because
     * the identity it would need is already gone.
     *
     * <p>So the three statements are one transaction. A failure at any point leaves the person
     * exactly as they were, which is the only state from which a deletion can be attempted again.
     *
     * <h2>Why the review anonymisation is load-bearing rather than cosmetic</h2>
     *
     * <p>{@code reviews_user_id_fkey} is <b>ON DELETE CASCADE</b>. Without the UPDATE that nulls
     * {@code user_id} first, deleting an account would <b>delete the person's ratings</b> instead of
     * anonymising them. The order here is the only thing standing between a deletion request and
     * the loss of every opinion they contributed.
     *
     * <h2>All three contribution types, not just one</h2>
     *
     * <p>{@code reviews}, {@code company_reviews} and {@code interview_reviews} were all
     * {@code ON DELETE CASCADE}, and only the first was protected: this method nulled
     * {@code reviews.user_id} first, so the cascade found nothing to delete. The other two had no
     * such protection, so <b>deleting an account destroyed every company rating and interview
     * experience that person had written</b> while their manager ratings survived. Both tables also
     * carry statement-level AFTER DELETE triggers that recalculate {@code company_stats_live}, so
     * the published figures for every company they had rated fell with them.
     *
     * <p>V105 makes all three {@code SET NULL}, so destruction is no longer reachable by any path
     * rather than being prevented by one method remembering to run a statement in the right order.
     * This method still severs the byline, which the schema cannot do.
     *
     * <h2>The retirement clear, which no cascade can do</h2>
     *
     * <p>{@code rating_opportunity_retirements} deliberately has <b>no foreign key</b> to
     * {@code users}: the row it names is gone and a constraint would make the record unwritable,
     * and its append-only trigger refuses DELETE so a cascade would have failed anyway. Nothing
     * therefore clears it automatically, and erasure was found incomplete in exactly this one table
     * for exactly that reason. It is cleared explicitly, and the trigger permits precisely this one
     * shape: {@code user_id} nulled, every other column byte-identical.
     */
    public Future<Void> deleteWithReviewAnonymization(UUID userId) {
        // The runtime client is the application pool; ManagerRepository.deleteOrRetire already
        // takes this same cast for the same reason. A test that passed a bare SqlClient would fail
        // loudly here rather than silently skipping the transaction.
        return ((io.vertx.sqlclient.Pool) db).withTransaction(tx ->
            // 1. MANAGER RATINGS. The byline is severed here; the row itself is protected by
            //    V105's SET NULL rather than by this statement running first.
            //
            //    dates_hidden = TRUE, AND THAT IS A PRIVACY REQUIREMENT RATHER THAN A TIDY-UP.
            //    An employment period identifies a person: "worked with this manager Mar 2023 to
            //    Jun 2023" narrows the candidates to very few at a small employer, and to exactly
            //    one often enough to matter. Severing the name while publishing the dates leaves
            //    the rating re-attributable by anybody who knows the team.
            //
            //    The same flag the contributor's own privacy toggle uses, so the whole masking path
            //    is the one already in production: buildReviewJson drops workedFrom, workedUntil
            //    AND effectiveWorkedUntil together, the last of these being the back door, since it
            //    is the value the profile actually renders. It is masked server-side, so the dates
            //    are absent from the response rather than hidden in the client.
            //
            //    isAuthor cannot rescue them: it requires a non-null viewer AND a match on
            //    user_id, which is now NULL, so no signed-out visitor and no signed-in account sees
            //    the period. The rating itself stays live and counted.
            //    author_erased = TRUE takes the row out of the legacy author dedup index. Without
            //    it, two people who rated the same manager under the same title and employer could
            //    not both delete their accounts: the second deletion collided with the first on
            //    'Anonymous User', so a stranger's earlier erasure blocked somebody from erasing
            //    themselves. V107, and the flag rather than a unique byline, because minting
            //    'anon-1821' would put an identifier back on the row the erasure exists to remove.
            //    THE BYLINE IS A PLAUSIBLE HANDLE, NOT 'Anonymous User'.
            //
            //    It was the literal string 'Anonymous User', and that string is a disclosure: it
            //    is worn by no live account, so every reader of a profile could tell which rating
            //    had been written by somebody who deleted themselves. Erasure is supposed to make
            //    an author unknowable, and labelling the row announced the one fact about them
            //    that remained.
            //
            //    A MINTED HANDLE IS NOT THE 'anon-1821' THIS DELIBERATELY AVOIDED. V107's note
            //    rejected minting a unique byline because it "would put an identifier back on the
            //    row the erasure exists to remove", and that objection is right about a name of
            //    that shape: anon-1821 still says "this row was anonymised", and the number gives
            //    a reader something to correlate on. Pseudonyms.mint() produces KindPanda11 -
            //    byte-identical in form to the handles live accounts carry and to the ones the
            //    rating forms already hand to anonymous contributors. It is drawn from nothing
            //    about the person, so it identifies nobody and it discloses nothing.
            //
            //    ONE NAME PER ROW, not one per account. Sharing a handle across an erased
            //    account's ratings would relink them: a reader could see that one person rated
            //    these four managers, which is a fact the erasure removed everywhere else. That is
            //    also why this is a batch rather than the single UPDATE it replaced - the old
            //    statement could set one literal for every row, and a per-row value cannot come
            //    from a single SET.
            //
            //    author_erased = TRUE still goes on, unchanged, and still does the work V107 gave
            //    it: it takes the row out of the legacy author dedup index, so two people who
            //    rated the same manager under the same title and employer can both erase
            //    themselves. The flag does that, not the byline, so varying the byline costs
            //    nothing there.
            mintBylines(tx, userId, "reviews",
                    ", dates_hidden = TRUE, author_erased = TRUE, updated_at = now()")
                // 2. COMPANY RATINGS. Were being DESTROYED: company_reviews.user_id was CASCADE and
                //    nothing anonymised them, so deleting an account silently deleted every company
                //    rating that person had written while their manager ratings survived. Worse, the
                //    table carries a statement-level AFTER DELETE trigger that recalculates
                //    company_stats_live, so the published figures for every company they had rated
                //    fell as a side effect of somebody closing their account.
                //
                //    THE BYLINE IS MINTED HERE TOO, and this is where that was got wrong.
                //
                //    This code set author = NULL, on the premise written one line above it: "a
                //    company rating is anonymous by construction and no author name is ever
                //    selected for display, so severing the identity is all that is needed." The
                //    premise is false. CompanyReviewService puts `author` in the response and
                //    InterviewService does the same, and both cards render a null one as a
                //    literal - "Anonymous employee" on a workplace rating, "Anonymous candidate"
                //    on an interview experience.
                //
                //    No live contribution wears either string, so nulling the column disclosed
                //    exactly what 'Anonymous User' used to disclose on `reviews`: which opinion
                //    on the page was written by somebody who had deleted their account. Reported
                //    from production on 2026-11-26, where a company profile showed one rating
                //    signed with an ordinary handle and the one beside it signed "Anonymous
                //    employee".
                //
                //    So all three tables now do the same thing, through the same helper: one
                //    freshly minted handle per row, drawn from the vocabulary live accounts and
                //    anonymous contributors already draw from. The fix that reached `reviews` in
                //    V107 simply never reached the other two.
                //
                //    dates_hidden stays: company_reviews carries the same column and the same
                //    worked_from / worked_until pair, and a period at one employer is no less
                //    identifying than a period under one manager. The self-deletion path already
                //    sets it, so this makes the two routes agree.
                //    updated_at IS DELIBERATELY NOT TOUCHED, and the original statement did not
                //    touch it either. The card renders "edited <n> days ago" from it, so bumping
                //    it would announce that every one of this person's ratings was edited at the
                //    same moment - which is the erasure, re-disclosed through a timestamp.
                .compose(ignored -> mintBylines(tx, userId, "company_reviews",
                    ", dates_hidden = TRUE"))
                // 3. INTERVIEW EXPERIENCES. Same defect, same fix, same reason.
                .compose(ignored -> mintBylines(tx, userId, "interview_reviews", ""))
                .compose(ignored -> tx.preparedQuery(
                        "UPDATE managers SET search_created_by_user_id = NULL "
                        + "WHERE search_created_by_user_id = $1")
                    .execute(Tuple.of(userId)))
                // 4. THE ACCOUNT. Everything that should follow it does so by cascade or by
                //    SET NULL, both of which V105 put in the schema rather than here.
                .compose(ignored -> tx.preparedQuery("DELETE FROM users WHERE id = $1")
                    .execute(Tuple.of(userId)))
                // 5. THE RETIREMENT AUDIT, LAST, and the order is load-bearing.
                //    rating_opportunity_retirements has no foreign key to users, so no cascade can
                //    clear it, and its append-only trigger now permits the clear only when the
                //    account is already gone. Running this before the DELETE would be refused by
                //    that guard, which is the point: nothing can erase an identity from history
                //    while the account it belongs to still exists.
                .compose(ignored -> tx.preparedQuery(
                        "UPDATE rating_opportunity_retirements SET user_id = NULL WHERE user_id = $1")
                    .execute(Tuple.of(userId)))
                .mapEmpty());
    }

    /**
     * Severs one table's authorship: every row this account wrote gets a freshly minted handle
     * and loses its {@code user_id}, in one batch.
     *
     * <p><b>One name per row, not one per account.</b> A handle shared across an erased account's
     * contributions relinks them: a reader could see that one person rated these four managers,
     * which is a fact the erasure removes everywhere else. A single {@code UPDATE ... SET author =
     * $1} cannot do that, which is why this is a batch.
     *
     * <p><b>Minted, never nulled and never a literal.</b> Both alternatives disclose. A fixed
     * string like {@code Anonymous User} is worn by no live account, and a null byline is rendered
     * by the clients as {@code Anonymous employee} or {@code Anonymous candidate}, which is the
     * same tell by a different route. {@link Pseudonyms#mint} produces {@code KindPanda11} -
     * indistinguishable in form from the handles live accounts carry and from the ones the rating
     * forms already hand to anonymous contributors - and it is drawn from nothing about the
     * person, so it identifies nobody.
     *
     * <p>All three contribution tables go through here so that there is one definition of what an
     * erased byline looks like. Three near-identical blocks is how one of them ends up different,
     * and one of them being different is the defect this replaces.
     *
     * @param table            a literal from this class, never anything a caller supplies: it is
     *                         interpolated into the statement because a table name cannot be a
     *                         bind parameter
     * @param extraAssignments further SET clauses for this table, beginning with a comma, or ""
     */
    private Future<Void> mintBylines(io.vertx.sqlclient.SqlConnection tx, UUID userId,
                                     String table, String extraAssignments) {
        return tx.preparedQuery("SELECT id FROM " + table + " WHERE user_id = $1")
            .execute(Tuple.of(userId))
            .compose(idRows -> {
                java.util.List<Tuple> batch = new java.util.ArrayList<>();
                for (Row idRow : idRows) {
                    // getValue, not getUUID or getLong: reviews keys on a bigint and the other two
                    // on a uuid, and naming either wrongly would break only on the erasure path.
                    batch.add(Tuple.of(Pseudonyms.mint(), idRow.getValue("id")));
                }
                // An account with no contributions of this kind is ordinary, and executeBatch on
                // an empty list is not: the driver rejects it.
                if (batch.isEmpty()) return Future.succeededFuture();
                return tx.preparedQuery(
                        "UPDATE " + table + " SET author = $1, user_id = NULL"
                        + extraAssignments + " WHERE id = $2")
                    .executeBatch(batch)
                    .mapEmpty();
            });
    }

    // ── Admin user management ─────────────────────────────────────────────────

    public Future<Optional<Row>> findByIdForAdmin(UUID userId) {
        return db.preparedQuery("""
                SELECT u.id, u.username,
                       COALESCE(u.first_name_encrypted, u.first_name) AS first_name,
                       COALESCE(u.last_name_encrypted,  u.last_name)  AS last_name,
                       u.role,
                       (SELECT b.id FROM banned_users b WHERE b.user_id = u.id LIMIT 1) AS ban_id
                FROM users u WHERE u.id = $1
                """)
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /**
     * Returns the (decrypted) email for a given username, used during sign-in
     * to resolve a username to the email expected by Auth0.
     */
    public Future<Optional<String>> findEmailByUsername(String username) {
        return db.preparedQuery("""
                SELECT COALESCE(email_encrypted, email) AS email
                FROM users WHERE lower(username) = lower($1)
                """)
            .execute(Tuple.of(username))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(d(rows.iterator().next().getString("email")))
                : Optional.empty());
    }

    public Future<String> findUsernameByAuth0Id(String auth0Id) {
        return db.preparedQuery("SELECT username FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rows -> rows.iterator().hasNext()
                ? rows.iterator().next().getString("username")
                : "admin");
    }

    public Future<RowSet<Row>> listNonAdminUsers(int limit, int offset) {
        return db.preparedQuery("""
                SELECT u.id, u.username,
                       COALESCE(u.first_name_encrypted, u.first_name) AS first_name,
                       COALESCE(u.last_name_encrypted,  u.last_name)  AS last_name,
                       u.role,
                       (SELECT b.id FROM banned_users b WHERE b.user_id = u.id LIMIT 1) AS ban_id
                FROM users u
                WHERE u.role != 'admin'
                ORDER BY u.username ASC
                LIMIT $1 OFFSET $2
                """)
            .execute(Tuple.of(limit, offset));
    }

    // ── Ban management ────────────────────────────────────────────────────────

    public Future<Boolean> banUser(UUID targetUserId, String reason, String bannedBy) {
        return db.preparedQuery("""
                INSERT INTO banned_users(user_id, reason, banned_by)
                VALUES ($1, $2, $3)
                ON CONFLICT (user_id) DO NOTHING
                RETURNING id
                """)
            .execute(Tuple.of(targetUserId, reason, bannedBy))
            .map(rows -> rows.iterator().hasNext()); // false = already banned
    }

    public Future<Boolean> unbanUser(UUID targetUserId) {
        return db.preparedQuery("DELETE FROM banned_users WHERE user_id = $1 RETURNING id")
            .execute(Tuple.of(targetUserId))
            .map(rows -> rows.iterator().hasNext()); // false = ban not found
    }

    public Future<RowSet<Row>> listBannedUsers(int limit, int offset) {
        return db.preparedQuery("""
                SELECT b.id, b.user_id, u.username, b.reason, b.banned_by, b.banned_at
                FROM banned_users b
                JOIN users u ON u.id = b.user_id
                ORDER BY b.banned_at DESC
                LIMIT $1 OFFSET $2
                """)
            .execute(Tuple.of(limit, offset));
    }

    // ── Auto-create tracking ──────────────────────────────────────────────────

    /**
     * Atomically claims the one-time ghost-creation slot for this user.
     * Returns true if the slot was successfully claimed (row updated from FALSE to TRUE),
     * false if it was already claimed (by this request or a concurrent one).
     * Using UPDATE...WHERE...RETURNING eliminates the TOCTOU race between read and write.
     */
    public Future<Boolean> claimAutoCreatedManagerSlot(UUID userId) {
        return db.preparedQuery(
                "UPDATE users SET has_auto_created_manager = TRUE WHERE id = $1 AND has_auto_created_manager = FALSE RETURNING id")
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().hasNext());
    }

    public Future<Void> resetAutoCreatedManagerSlot(UUID userId) {
        return db.preparedQuery("UPDATE users SET has_auto_created_manager = FALSE WHERE id = $1")
            .execute(Tuple.of(userId))
            .mapEmpty();
    }

    /**
     * Has this person rated a manager? The gate on manager data.
     *
     * <p>Counts only live, undeleted ratings, and asks for {@code gate_eligible} explicitly —
     * both facts, rather than the absence of the worst one. An earlier form of this query was the
     * whole vulnerability: a bare {@code EXISTS} over {@code reviews} meant any review, on any
     * manager, in any state opened the gate, so a junk rating written in ten seconds bought
     * exactly the access an honest one did.
     *
     * <p>Asking for {@code disposition <> 'rejected'} instead would be correct only while the two
     * columns stay synchronised, and the reason they are two columns is that one day they will
     * not. Reading the view inherits the publication rule instead of approximating it.
     */
    public Future<Boolean> hasContributed(UUID userId) {
        /*
          TWO SOURCES, because unlinking severs the only one this used to have.

          The original query asked `reviews.user_id = $1`. Unlinking sets that column to NULL by
          design - that IS the unlinking - so a contributor who unlinked their single rating went
          from eligible to ineligible and lost access to the profiles their contribution had paid
          for. The rating is still published and still counting toward the manager's average; only
          the person who wrote it was penalised.

          So the gate also reads rating_participation, which outlives the severance and records
          whether the contribution earned the gate. It records nothing else about it: no rating id,
          no score, no dates.

          The live query is KEPT rather than replaced. It still decides the ordinary case, and
          keeping it preserves the existing behaviour that a held or moderator-rejected rating does
          not buy access - participation is backfilled and written from the rating's live,
          gate-eligible state, so a rejected contribution carries gate_eligible = FALSE.

          `genuine` rather than `live`: a platform example has no user_id so it could never have
          matched, but saying so in the predicate means nobody has to work that out to be sure.
        */
        return db.preparedQuery(
                "SELECT ("
                + "  EXISTS(SELECT 1 FROM reviews r WHERE " + ReviewSql.genuine("r")
                + "         AND r.user_id = $1 AND r.gate_eligible = TRUE)"
                + "  OR EXISTS(SELECT 1 FROM rating_participation p"
                + "            WHERE p.user_id = $1 AND p.gate_eligible = TRUE)"
                + ") AS contributed")
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().next().getBoolean("contributed"));
    }

    /**
     * Has this person rated a company? The gate on workplace data.
     *
     * One gate per dataset, each opened by contributing to that dataset. Rating a manager tells us
     * nothing about a workplace, so it should not buy the workplace numbers - and the reverse is
     * equally true. Interview data already worked this way; this is the same rule for the third.
     *
     * Any company, not this company. The manager gate is global for the same reason: the point is
     * that somebody has contributed to the corpus they are reading, not that they have contributed
     * to the exact page they happen to be on.
     */
    public Future<Boolean> hasRatedCompany(UUID userId) {
        return db.preparedQuery(
                "SELECT EXISTS(SELECT 1 FROM company_reviews "
                + "WHERE user_id = $1 AND deleted_at IS NULL) AS rated")
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().next().getBoolean("rated"));
    }
}
