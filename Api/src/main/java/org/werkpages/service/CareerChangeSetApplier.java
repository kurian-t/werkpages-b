package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.werkpages.repository.EditRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Applies a career-editor change set.
 *
 * <h2>Why this is one component with two callers</h2>
 *
 * <p>A change set is applied in exactly two situations, and they must do the SAME thing:
 *
 * <ul>
 *   <li>a moderator approves a contribution about a published manager;</li>
 *   <li>a contributor edits a manager THEY submitted which is still pending approval, where there
 *       is nothing to moderate because nothing has been published.</li>
 * </ul>
 *
 * <p>Those were two forms and would have become two implementations of "end this role, add that
 * one, correct this title" - which is the competing employment-resolution system the design
 * forbids. One of them would eventually have learned something the other had not, and which one
 * somebody got would depend on whether their manager happened to be approved yet.
 *
 * <p>So the apply path lives here and both callers hand it a transaction. The caller decides
 * whether the actor is a moderator or the contributor; everything after that is identical.
 */
public final class CareerChangeSetApplier {

    private final EditRepository editRepo;

    public CareerChangeSetApplier(EditRepository editRepo) {
        this.editRepo = editRepo;
    }

    // ── APPROVING A CAREER-EDITOR CONTRIBUTION ──────────────────────────────

    /**
     * Apply a whole change set, in ONE transaction.
     *
     * <h2>Why this is separate from the legacy branch and not a rewrite of it</h2>
     *
     * <p>Contributions written through the old form are still pending and must stay approvable
     * exactly as they are, so {@code approveEdit} reads {@code change_format} and sends each shape
     * to the code that understands it. Nothing about the legacy path changes.
     *
     * <h2>What one transaction has to cover</h2>
     *
     * <p>A contribution can rename the manager, correct three employments, end a fourth and add
     * two more. Applied across several transactions, a failure halfway leaves the manager renamed
     * and the employments untouched, with the contribution marked approved and no way to tell what
     * ran. So the name, every position, every audit row and the decision on the parent commit
     * together or not at all.
     *
     * <h2>Staleness</h2>
     *
     * <p>Each position carries the employment as it stood when the contributor submitted. Before
     * anything is applied, that snapshot is compared with the row as it stands now, for the changed
     * fields only. A divergence means somebody edited this employment in between, and the
     * contribution is refused rather than applied: approving it would revert the newer value with
     * nothing recording that it happened. The moderator is told which employment and which field,
     * and the contributor's submission stays pending so it can be reviewed against what is there
     * now.
     */
    /**
     * Apply a whole change set and record the decision, inside the caller's transaction.
     *
     * @param actorId whoever is accountable for the decision: the moderator who approved it, or
     *                the contributor themselves when they are editing their OWN manager that has
     *                not been published yet and there is nothing to moderate.
     * @param fix     corrections supplied at approval time. Empty for a self-edit.
     */
    public Future<JsonObject> applyAndApprove(SqlConnection tx, long managerId, UUID editId,
                                              String explanation, UUID proposedBy, UUID actorId,
                                              String effectiveName, JsonObject fix) {
        return editRepo.markCareerEditApply(tx)
            .compose(v -> tx.preparedQuery("SELECT id, name FROM managers WHERE id = $1 FOR UPDATE")
                .execute(Tuple.of(managerId)))
            .compose(found -> {
                if (found.rowCount() == 0) {
                    return Future.failedFuture(ServiceException.notFound("Manager not found"));
                }
                return editRepo.findChangeSet(tx, editId);
            })
            .compose(changeSet -> {
                List<Row> positions = new ArrayList<>();
                changeSet.forEach(positions::add);

                String stale = firstStalePosition(positions);
                if (stale != null) return Future.failedFuture(ServiceException.conflict(stale));

                JsonArray applied = new JsonArray();
                Future<Void> chain = Future.succeededFuture();
                for (Row p : positions) {
                    chain = chain.compose(v ->
                        applyPosition(tx, managerId, p, explanation, proposedBy, actorId, fix)
                            .map(summary -> { applied.add(summary); return null; }));
                }
                return chain.compose(v -> applyManagerName(tx, managerId, effectiveName, explanation))
                    .map(renamed -> new JsonObject()
                        .put("positions", applied)
                        .put("renamed", renamed));
            })
            .compose(result -> editRepo.approve(tx, editId, actorId,
                                                OffsetDateTime.now(ZoneOffset.UTC))
                .map(v -> result));
    }

    /**
     * Whether any position was written against an employment that has since moved.
     *
     * <p>Compared field by field, and only for the fields the position actually proposes. A
     * contributor who corrects a title has no opinion about the dates, so a date that changed in
     * the meantime is not a conflict with their submission and must not block it.
     *
     * @return a message naming the first divergence, or null when every position is still current.
     */
    private String firstStalePosition(List<Row> positions) {
        for (Row p : positions) {
            if (p.getLong("role_id") == null) continue;   // an addition was written against nothing

            if (p.getString("current_state") == null) {
                return "One of the employments in this request no longer exists, so the request "
                     + "cannot be applied as submitted.";
            }
            // The employment itself has moved on: superseded, rejected or merged away. A field
            // edit on it is no longer meaningful whatever the fields say.
            if (!Objects.equals(p.getString("was_state"), p.getString("current_state"))) {
                return "An employment in this request was " + p.getString("was_state")
                     + " when it was submitted and is now " + p.getString("current_state")
                     + ", so the request cannot be applied as submitted. Review it against the "
                     + "profile as it stands.";
            }
            String[][] watched = {
                {"title",             "was_title",             "current_title"},
                {"start_date",        "was_start_date",        "current_start_date"},
                {"end_date",          "was_end_date",          "current_end_date"},
                {"period_known",      "was_period_known",      "current_period_known"},
                {"employment_status", "was_employment_status", "current_employment_status"},
            };
            for (String[] w : watched) {
                if (!changes(p, w[0])) continue;
                if (!Objects.equals(p.getValue(w[1]), p.getValue(w[2]))) {
                    return "The " + w[0].replace('_', ' ') + " of an employment in this request "
                         + "has been changed by somebody else since it was submitted. Approving it "
                         + "would undo that change, so the request is left pending for you to "
                         + "compare.";
                }
            }
            if (changes(p, "location") && locationMoved(p)) {
                return "The location of an employment in this request has been changed by somebody "
                     + "else since it was submitted. Approving it would undo that change, so the "
                     + "request is left pending for you to compare.";
            }
        }
        return null;
    }

    public static boolean locationMoved(Row p) {
        return !Objects.equals(p.getValue("was_declared_country"),   p.getValue("current_declared_country"))
            || !Objects.equals(p.getValue("was_declared_state"),     p.getValue("current_declared_state"))
            || !Objects.equals(p.getValue("was_declared_city"),      p.getValue("current_declared_city"))
            || !Objects.equals(p.getValue("was_declared_precision"), p.getValue("current_declared_precision"))
            || !Objects.equals(p.getValue("was_company_location_id"),p.getValue("current_company_location_id"));
    }

    public static boolean changes(Row p, String field) {
        String[] fields = p.getArrayOfStrings("changed_fields");
        if (fields == null) return false;
        for (String f : fields) if (field.equals(f)) return true;
        return false;
    }

    /**
     * The manager's own name, which is the only thing in a contribution that is not an employment.
     *
     * <p>Deliberately the last thing applied and deliberately independent: a submission that
     * changes only the name has no positions at all, and must still take effect. That is why the
     * format is read from {@code change_format} rather than inferred from whether a change set
     * exists.
     */
    private Future<Boolean> applyManagerName(SqlConnection tx, long managerId, String newName,
                                             String explanation) {
        if (newName == null || newName.isBlank()) return Future.succeededFuture(false);
        return tx.preparedQuery("""
                UPDATE managers SET name = $2, updated_at = now()
                WHERE id = $1 AND name IS DISTINCT FROM $2
                """)
            .execute(Tuple.of(managerId, newName.trim()))
            .map(rs -> rs.rowCount() > 0);
    }

    /**
     * Apply one position from a change set.
     *
     * <p>The three intents are three different operations on purpose, and the difference is the
     * whole design: a correction changes a record, an addition creates a distinct employment, and a
     * supersession replaces an employment whose employer was wrong. Collapsing any two of them
     * would either lose ratings or invent a job change nobody reported.
     */
    private Future<JsonObject> applyPosition(SqlConnection tx, long managerId, Row p,
                                             String explanation, UUID proposedBy, UUID actorId,
                                             JsonObject fix) {
        String intent = p.getString("intent");
        return switch (intent) {
            case "correct"   -> applyCorrect(tx, p, explanation, proposedBy);
            case "add"       -> applyAdd(tx, managerId, p, actorId, fix);
            case "supersede" -> applySupersede(tx, managerId, p, explanation, proposedBy, actorId, fix);
            default -> Future.failedFuture(ServiceException.badRequest(
                "Unknown change intent: " + intent));
        };
    }

    /**
     * Correct an employment's own record, in place.
     *
     * <p><b>The same employment.</b> Its id does not change, so every rating claimed through it,
     * every claim resolved to it and every attribution derived from it is untouched. That is the
     * reason correcting and superseding are separate: this path cannot move a rating even by
     * accident, because it never changes the employer.
     *
     * <p>Only the fields in {@code changed_fields} are written. A contributor who fixed a title
     * said nothing about the dates, and a correction that quietly cleared them would destroy
     * information nobody questioned.
     */
    private Future<JsonObject> applyCorrect(SqlConnection tx, Row p, String explanation,
                                            UUID proposedBy) {
        long roleId = p.getLong("role_id");
        // -1 means "ambiguous, left alone"; see syncRatingTitleSnapshots.
        final int[] snapshotsMoved = { 0 };
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        args.add(roleId);                       // $1 is always the id
        JsonArray changed = new JsonArray();

        if (changes(p, "title")) {
            sets.add("title = $" + (args.size() + 1));
            args.add(p.getString("new_title"));
            changed.add("title");
        }
        if (changes(p, "start_date")) {
            sets.add("start_date = $" + (args.size() + 1));
            args.add(p.getOffsetDateTime("new_start_date"));
            changed.add("start_date");
        }
        if (changes(p, "end_date")) {
            sets.add("end_date = $" + (args.size() + 1));
            args.add(p.getOffsetDateTime("new_end_date"));
            changed.add("end_date");
        }
        if (changes(p, "period_known")) {
            sets.add("period_known = $" + (args.size() + 1));
            args.add(p.getBoolean("new_period_known"));
            changed.add("period_known");
        }
        if (changes(p, "location")) {
            /*
              THE EMPLOYMENT'S LOCATION, AND ONLY THE EMPLOYMENT'S.

              `managers` carries its own declared location and nothing here writes it. Correcting
              where a role was held in 2014 must not move the manager's present-day location, which
              is exactly what the old form did by pointing its location control at the manager row.
            */
            sets.add("declared_country = $"    + (args.size() + 1)); args.add(p.getString("new_declared_country"));
            sets.add("declared_state = $"      + (args.size() + 1)); args.add(p.getString("new_declared_state"));
            sets.add("declared_city = $"       + (args.size() + 1)); args.add(p.getString("new_declared_city"));
            sets.add("declared_precision = $"  + (args.size() + 1)); args.add(p.getString("new_declared_precision"));
            sets.add("company_location_id = $" + (args.size() + 1)); args.add(p.getLong("new_company_location_id"));
            sets.add("declared_source = $"     + (args.size() + 1)); args.add(p.getString("new_declared_source"));
            sets.add("declared_text = $"       + (args.size() + 1)); args.add(p.getString("new_declared_text"));
            changed.add("location");
        }

        String newStatus = changes(p, "employment_status") ? p.getString("new_employment_status") : null;
        if (newStatus != null) {
            /*
              status_basis = 'moderator', NOT 'contributor_claim'.

              employment_status_claim_basis_pairs makes 'contributor_claim' require a
              status_claim_id, and a correction has no claim behind it: the contributor is fixing a
              record, not asserting they worked there. The authority for this status is the
              moderator who approved the contribution, and 'moderator' is the basis that says so
              without a claim.

              'unknown' needs no grounding at all under employment_status_is_grounded, and gets
              'no_evidence', which is what "I'm not sure" actually means.
            */
            boolean grounded = !"unknown".equals(newStatus);
            sets.add("employment_status = $" + (args.size() + 1));  args.add(newStatus);
            sets.add("status_basis = $"      + (args.size() + 1));  args.add(grounded ? "moderator" : "no_evidence");
            sets.add("status_as_of = now()");
            sets.add("status_resolved_at = now()");
            sets.add("status_resolved_by = $" + (args.size() + 1)); args.add(proposedBy);
            changed.add("employment_status");
        }

        if (sets.isEmpty()) {
            return Future.succeededFuture(new JsonObject()
                .put("intent", "correct").put("roleId", roleId).put("changed", changed));
        }
        sets.add("updated_at = now()");

        return tx.preparedQuery("UPDATE career_history SET " + String.join(", ", sets)
                                + " WHERE id = $1")
            .execute(Tuple.wrap(args.toArray()))
            .compose(rs -> recordCorrections(tx, p, explanation, proposedBy))
            .compose(v -> changes(p, "title")
                ? syncRatingTitleSnapshots(tx, p, roleId).map(n -> { snapshotsMoved[0] = n; return null; })
                : Future.<Void>succeededFuture())
            .compose(v -> changes(p, "title")
                ? syncManagerHeadline(tx, p, roleId)
                : Future.<Void>succeededFuture())
            .compose(v -> newStatus == null
                ? Future.<Void>succeededFuture()
                : recordStatusEvent(tx, roleId, p.getString("was_employment_status"), newStatus,
                                    "unknown".equals(newStatus) ? "no_evidence" : "moderator",
                                    proposedBy))
            .map(v -> new JsonObject()
                .put("intent", "correct").put("roleId", roleId).put("changed", changed)
                .put("ratingTitlesUpdated", snapshotsMoved[0]));
    }

    /**
     * Write the append-only record of what this correction changed.
     *
     * <p>One row per field, which is the shape {@code role_corrections} already has.
     *
     * <p>THE REASON IS NORMALLY NULL, and V112 made the column nullable to say so. The career
     * editor stopped asking "How do you know?" on 2026-11-18, so a submission from it carries no
     * explanation and there is nothing to record as the reason. A placeholder was deliberately not
     * invented: the moderation history renders this column as the contributor's own words, and a
     * sentence no human wrote would read there like evidence. The LEGACY single-position form still
     * collects an explanation and still writes it here, which is why the parameter stays.
     *
     * <p>A field whose value did not actually move writes nothing, because
     * {@code role_correction_changed_something} requires {@code was IS DISTINCT FROM now_is} and
     * an audit row saying nothing changed is noise the history has to be read through.
     */
    private Future<Void> recordCorrections(SqlConnection tx, Row p, String explanation,
                                           UUID proposedBy) {
        long roleId = p.getLong("role_id");
        List<Tuple> rows = new ArrayList<>();
        addCorrection(rows, roleId, "title", changes(p, "title"),
                      p.getString("was_title"), p.getString("new_title"), explanation, proposedBy);
        addCorrection(rows, roleId, "start_date", changes(p, "start_date"),
                      text(p.getOffsetDateTime("was_start_date")),
                      text(p.getOffsetDateTime("new_start_date")), explanation, proposedBy);
        addCorrection(rows, roleId, "end_date", changes(p, "end_date"),
                      text(p.getOffsetDateTime("was_end_date")),
                      text(p.getOffsetDateTime("new_end_date")), explanation, proposedBy);
        addCorrection(rows, roleId, "period_known", changes(p, "period_known"),
                      text(p.getBoolean("was_period_known")),
                      text(p.getBoolean("new_period_known")), explanation, proposedBy);
        addCorrection(rows, roleId, "location", changes(p, "location"),
                      locationText(p, "was_"), locationText(p, "new_"), explanation, proposedBy);

        if (rows.isEmpty()) return Future.succeededFuture();
        return tx.preparedQuery("""
                INSERT INTO role_corrections (role_id, field, was, now_is, reason, corrected_by)
                VALUES ($1, $2, $3, $4, $5, $6)
                """)
            .executeBatch(rows)
            .mapEmpty();
    }

    private static void addCorrection(List<Tuple> rows, long roleId, String field, boolean asked,
                                      String was, String nowIs, String reason, UUID by) {
        if (!asked) return;
        if (Objects.equals(was, nowIs)) return;
        rows.add(Tuple.of(roleId, field, was, nowIs, reason, by));
    }

    /** A location as one readable line, for the audit trail's {@code was} and {@code now_is}. */
    public static String locationText(Row p, String prefix) {
        List<String> parts = new ArrayList<>();
        for (String part : List.of("declared_city", "declared_state", "declared_country")) {
            String v = p.getString(prefix + part);
            if (v != null && !v.isBlank()) parts.add(v);
        }
        Long locationId = p.getLong(prefix + "company_location_id");
        if (locationId != null) parts.add("location #" + locationId);
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    public static String text(Object v) {
        if (v == null) return null;
        if (v instanceof OffsetDateTime t) return t.toLocalDate().toString();
        return String.valueOf(v);
    }

    private Future<Void> recordStatusEvent(SqlConnection tx, long roleId, String from, String to,
                                           String basis, UUID by) {
        return tx.preparedQuery("""
                INSERT INTO employment_status_events (role_id, from_status, to_status, basis,
                                                      as_of, by)
                VALUES ($1, $2, $3, $4, now(), $5)
                """)
            .execute(Tuple.of(roleId, from, to, basis, by))
            .mapEmpty();
    }

    /**
     * Record an employment nobody had recorded.
     *
     * <h2>An addition is a DISTINCT employment, and never ends another one</h2>
     *
     * <p>Adding a current role does not touch any existing role's end date. Somebody who genuinely
     * holds two jobs at once is recorded as holding two jobs at once, and somebody who changed jobs
     * says so by ending the old role in the same submission - which is a separate position in the
     * change set, visible to the moderator as its own line. Inferring the end of one role from the
     * start of another is how a career history quietly acquires dates nobody ever stated.
     */
    private Future<JsonObject> applyAdd(SqlConnection tx, long managerId, Row p, UUID actorId,
                                        JsonObject fix) {
        Long companyId = resolvedCompanyId(p, fix);
        if (companyId == null) {
            return Future.failedFuture(ServiceException.badRequest(
                "This request adds an employment at \"" + displayCompany(p) + "\", which has no "
                + "company record yet, so approving it cannot tell which company is meant. "
                + "Resolve the company through the picker, then approve."));
        }
        String status   = p.getString("new_employment_status");
        boolean grounded = status != null && !"unknown".equals(status);

        return tx.preparedQuery("""
                INSERT INTO career_history
                    (manager_id, company_id, company, title, start_date, end_date, period_known,
                     source, state, employment_status,
                     status_basis, status_as_of, status_resolved_at, status_resolved_by,
                     declared_country, declared_state, declared_city, declared_precision,
                     company_location_id, declared_source, declared_text)
                SELECT $1, $2, c.name, $3, $4, $5, COALESCE($6, FALSE),
                       'contributor', 'accepted', COALESCE($7, 'unknown'),
                       $8, CASE WHEN $8::text IS NULL THEN NULL ELSE now() END,
                           CASE WHEN $8::text IS NULL THEN NULL ELSE now() END, $9,
                       $10, $11, $12, $13, $14, $15, $16
                  FROM companies c WHERE c.id = $2
                ON CONFLICT (manager_id, company_id, title_key, start_day)
                  WHERE period_known AND state IN ('proposed','accepted')
                DO NOTHING
                RETURNING id
                """)
            .execute(Tuple.wrap(new Object[] {
                managerId, companyId, p.getString("new_title"),
                p.getOffsetDateTime("new_start_date"), p.getOffsetDateTime("new_end_date"),
                p.getBoolean("new_period_known"),
                status,
                status == null ? null : (grounded ? "moderator" : "no_evidence"),
                grounded ? actorId : null,
                p.getString("new_declared_country"), p.getString("new_declared_state"),
                p.getString("new_declared_city"), p.getString("new_declared_precision"),
                p.getLong("new_company_location_id"), p.getString("new_declared_source"),
                p.getString("new_declared_text")
            }))
            .compose(rs -> {
                if (rs.rowCount() == 0) {
                    /*
                      career_unique_known_employment already holds this exact employment. Somebody
                      recorded it between submission and approval, or the contributor added one
                      that was already there. Either way the record now says what they wanted it to
                      say, so this is reported as applied rather than failed: refusing the whole
                      contribution would discard every other position in it over a position whose
                      goal is already met.
                    */
                    return Future.succeededFuture(new JsonObject()
                        .put("intent", "add")
                        .put("alreadyPresent", true)
                        .put("title", p.getString("new_title")));
                }
                long newId = rs.iterator().next().getLong("id");
                Future<Void> event = status == null
                    ? Future.succeededFuture()
                    : recordStatusEvent(tx, newId, null, status,
                                        grounded ? "moderator" : "no_evidence", actorId);
                return event.map(v -> new JsonObject()
                    .put("intent", "add").put("roleId", newId)
                    .put("title", p.getString("new_title")));
            });
    }

    /**
     * Replace an employment whose EMPLOYER was wrong.
     *
     * <h2>Why this is not a correction</h2>
     *
     * <p>{@code role_corrections.field} deliberately excludes the company, and
     * {@code claim_resolved_role_matches} is a foreign key on
     * {@code (resolved_role_id, manager_id, company_id)} - so the database itself refuses to let an
     * employment's employer change underneath the claims resolved to it. That is not an obstacle to
     * work around; it is the protection that stops a typo in a company name from silently
     * re-attributing every rating hung off that employment.
     *
     * <h2>What happens to the ratings, precisely</h2>
     *
     * <p>A claimed rating stores no company at all: {@code reviews_role_basis_stores_nothing}
     * keeps {@code attributed_company_id} NULL and the employer is derived through
     * claim to employment to company. So re-pointing the claims at the replacement employment
     * moves the attribution with them, with no rating row touched, nothing deleted and nothing
     * hidden. The claims are re-pointed rather than recreated, so every contributor keeps their
     * claim and the ratings stay published: {@code rating_role_is_publishable} requires the
     * resolved employment to be {@code accepted}, and the replacement is.
     *
     * <p><b>This does move the ratings to the corrected employer, and it is not silent.</b> It is
     * the point of a company correction: the employment is the same real job and the employer label
     * on it was wrong, so a rating about that job belongs to the corrected employer. Every claim
     * re-pointed is recorded on the claim itself as a canonicalization naming the employment it
     * came from, the moderator is shown the affected count before approving, and the superseded
     * employment remains in the history pointing at its replacement.
     *
     * <p>A LEGACY rating is left completely alone. Its company is stored on the rating and reached
     * no employment, so moving it would be an inference about a rating nobody has questioned.
     *
     * <h2>When attribution is ambiguous</h2>
     *
     * <p>An unresolved conflict that blocks publication on this employment means the platform
     * already does not know whose employment it is. Superseding it would decide that question as a
     * side effect of a company correction, so the approval is refused and the moderator is sent to
     * the conflict first.
     */
    private Future<JsonObject> applySupersede(SqlConnection tx, long managerId, Row p,
                                              String explanation, UUID proposedBy, UUID actorId,
                                              JsonObject fix) {
        long oldRoleId = p.getLong("role_id");
        Long newCompanyId = resolvedCompanyId(p, fix);
        if (newCompanyId == null) {
            return Future.failedFuture(ServiceException.badRequest(
                "This request moves an employment to \"" + displayCompany(p) + "\", which has no "
                + "company record yet, so approving it cannot tell which company is meant. "
                + "Resolve the company through the picker, then approve."));
        }
        if (Objects.equals(newCompanyId, p.getLong("was_company_id"))) {
            return Future.failedFuture(ServiceException.badRequest(
                "This request replaces an employment with one at the same company, which would "
                + "change nothing and retire a live employment."));
        }

        return blockingConflictOn(tx, oldRoleId)
            .compose(conflictId -> {
                if (conflictId != null) {
                    return Future.failedFuture(ServiceException.conflict(
                        "This employment has an unresolved conflict (#" + conflictId + ") that is "
                        + "holding ratings back, so who held it is still in question. Resolve the "
                        + "conflict before moving it to another company."));
                }
                String status = p.getString("new_employment_status") != null
                    ? p.getString("new_employment_status")
                    : p.getString("current_employment_status");
                boolean grounded = status != null && !"unknown".equals(status);

                // The replacement carries the proposal's values where it gave them and the
                // superseded employment's values where it did not, so a company correction does
                // not quietly blank a title or a date nobody asked about.
                return tx.preparedQuery("""
                        INSERT INTO career_history
                            (manager_id, company_id, company, title, start_date, end_date,
                             period_known, source, state, employment_status,
                             status_basis, status_as_of, status_resolved_at, status_resolved_by,
                             declared_country, declared_state, declared_city, declared_precision,
                             company_location_id, declared_source, declared_text)
                        SELECT old.manager_id, $2, c.name,
                               COALESCE($3, old.title),
                               COALESCE($4, old.start_date),
                               COALESCE($5, old.end_date),
                               COALESCE($6, old.period_known),
                               'contributor', 'accepted',
                               COALESCE($7, old.employment_status),
                               $8, CASE WHEN $8::text IS NULL THEN NULL ELSE now() END,
                                   CASE WHEN $8::text IS NULL THEN NULL ELSE now() END, $9,
                               COALESCE($10, old.declared_country),
                               COALESCE($11, old.declared_state),
                               COALESCE($12, old.declared_city),
                               COALESCE($13, old.declared_precision),
                               COALESCE($14, old.company_location_id),
                               COALESCE($15, old.declared_source),
                               COALESCE($16, old.declared_text)
                          FROM career_history old
                          JOIN companies c ON c.id = $2
                         WHERE old.id = $1
                        RETURNING id
                        """)
                    .execute(Tuple.wrap(new Object[] {
                        oldRoleId, newCompanyId,
                        p.getString("new_title"),
                        p.getOffsetDateTime("new_start_date"),
                        p.getOffsetDateTime("new_end_date"),
                        p.getBoolean("new_period_known"),
                        p.getString("new_employment_status"),
                        status == null ? null : (grounded ? "moderator" : "no_evidence"),
                        grounded ? actorId : null,
                        p.getString("new_declared_country"), p.getString("new_declared_state"),
                        p.getString("new_declared_city"), p.getString("new_declared_precision"),
                        p.getLong("new_company_location_id"), p.getString("new_declared_source"),
                        p.getString("new_declared_text")
                    }))
                    .compose(rs -> {
                        long newRoleId = rs.iterator().next().getLong("id");
                        return tx.preparedQuery("""
                                UPDATE career_history
                                   SET state = 'superseded', superseded_by = $2, updated_at = now()
                                 WHERE id = $1
                                """)
                            .execute(Tuple.of(oldRoleId, newRoleId))
                            .compose(v -> repointClaims(tx, oldRoleId, newRoleId, newCompanyId, actorId))
                            .compose(claimsMoved -> syncRatingCompanySnapshots(tx, oldRoleId, newRoleId)
                                .map(n -> claimsMoved))
                            .compose(claimsMoved -> repointWithdrawnRatings(tx, oldRoleId, newRoleId)
                                .map(withdrawnMoved -> new JsonObject()
                                    .put("intent", "supersede")
                                    .put("supersededRoleId", oldRoleId)
                                    .put("roleId", newRoleId)
                                    .put("companyId", newCompanyId)
                                    .put("claimsMoved", claimsMoved)
                                    .put("withdrawnRatingsMoved", withdrawnMoved)))
                            .compose(summary -> recordStatusEvent(tx, newRoleId,
                                        p.getString("current_employment_status"),
                                        status == null ? "unknown" : status,
                                        grounded ? "moderator" : "no_evidence", actorId)
                                    .map(v -> summary));
                    });
            });
    }

    /**
     * Move every resolved claim from the superseded employment to its replacement.
     *
     * <p>{@code claim_resolved_role_matches} spans {@code (resolved_role_id, manager_id,
     * company_id)}, so the claim's company has to move in the same statement as its role or the
     * foreign key is violated. {@code claim_selection_is_honoured_or_canonicalized} then requires
     * either that the resolved role still equals the role the contributor selected, or that the
     * claim is marked canonicalized - which is exactly what has happened, so
     * {@code canonicalized_from} records the employment it was moved off.
     *
     * <p>Only {@code linked} and {@code created} claims move. A rejected or duplicate claim
     * resolves to no employment and {@code claim_unlinked_has_no_role} keeps it that way.
     */
    private Future<Integer> repointClaims(SqlConnection tx, long oldRoleId, long newRoleId,
                                          long newCompanyId, UUID actorId) {
        return tx.preparedQuery("""
                UPDATE role_claims
                   SET resolved_role_id  = $2,
                       company_id        = $3,
                       canonicalized_at  = now(),
                       canonicalized_from = $1,
                       canonicalized_by  = $4,
                       updated_at        = now()
                 WHERE resolved_role_id = $1
                   AND resolution IN ('linked', 'created')
                """)
            .execute(Tuple.of(oldRoleId, newRoleId, newCompanyId, actorId))
            .map(rs -> rs.rowCount());
    }

    /**
     * A withdrawn rating reaches its employment directly rather than through a claim, so it has to
     * follow the supersession too.
     *
     * <p>{@code rating_role_is_publishable} checks {@code withdrawn_role_id}'s state for these,
     * and leaving them pointing at a superseded row would unpublish ratings whose contributor had
     * already withdrawn but whose rating the product deliberately keeps.
     */
    private Future<Integer> repointWithdrawnRatings(SqlConnection tx, long oldRoleId, long newRoleId) {
        return tx.preparedQuery(
                "UPDATE reviews SET withdrawn_role_id = $2 WHERE withdrawn_role_id = $1")
            .execute(Tuple.of(oldRoleId, newRoleId))
            .map(rs -> rs.rowCount());
    }

    /** An unresolved conflict on this employment that is holding ratings back, if there is one. */
    private Future<Long> blockingConflictOn(SqlConnection tx, long roleId) {
        return tx.preparedQuery("""
                SELECT id FROM role_conflicts
                 WHERE (role_id = $1 OR other_role_id = $1)
                   AND blocks_publication
                   AND resolved_at IS NULL
                 LIMIT 1
                """)
            .execute(Tuple.of(roleId))
            .map(rs -> rs.rowCount() == 0 ? null : rs.iterator().next().getLong("id"));
    }

    /**
     * The company this position means, which comes from an id and never from re-resolving a name.
     *
     * <p>Matching a typed company name to whichever row currently holds that string is the exact
     * assumption that created duplicate companies, and {@code approveEdit} already refuses to do
     * it. An administrator resolves the company once through the picker and the id arrives in the
     * overrides.
     */
    private Long resolvedCompanyId(Row p, JsonObject fix) {
        /*
          PER POSITION FIRST, then the flat override.

          A change set can carry two positions that each name a company with no record yet, and one
          `companyId` in the overrides cannot answer for both - applied to both it would file an
          employment under the wrong employer, which is precisely the mistake the identity refusal
          exists to prevent. So the moderator resolves each position and the id arrives keyed by the
          change row it belongs to.

          The flat key is still honoured, for a single-position change set and for the legacy
          screen that only ever had one company to resolve.
        */
        JsonObject perPosition = fix.getJsonObject("positions");
        if (perPosition != null) {
            JsonObject mine = perPosition.getJsonObject(String.valueOf(p.getLong("id")));
            if (mine != null && mine.getLong("companyId") != null) {
                return mine.getLong("companyId");
            }
        }
        Long override = fix.getLong("companyId");
        if (override != null) return override;
        return p.getLong("new_company_id");
    }

    public static String displayCompany(Row p) {
        String resolved = p.getString("new_company_resolved_name");
        if (resolved != null) return resolved;
        String typed = p.getString("new_company_name");
        return typed != null ? typed : "(unnamed)";
    }


    /**
     * Carry a corrected title across to the ratings that recorded the old one.
     *
     * <h2>Why a correction has to touch ratings at all</h2>
     *
     * <p>{@code reviews.manager_title} and {@code manager_company} are SNAPSHOTS written when a
     * rating was submitted, not references to the employment. Three things read that text:
     *
     * <ul>
     *   <li>the rating card, which shows the role somebody was rated under;</li>
     *   <li>{@code EFFECTIVE_WORKED_UNTIL}, which matches career history to ratings on company
     *       plus title to cap a card that would otherwise say "Present" for a manager who has
     *       left;</li>
     *   <li>the career insights grouping, which builds segments from the same pair.</li>
     * </ul>
     *
     * <p>So leaving the snapshots alone after correcting a misspelled title does not merely leave
     * stale text on a card. It BREAKS the match, and the card reverts to "Present" for a role that
     * has ended - the defect that matching was written to fix.
     *
     * <h2>Why only a correction, never an addition</h2>
     *
     * <p>A correction says the record was wrong, so the snapshot of it was wrong too. A promotion
     * is an ADDITION, and those ratings belong to the role the person actually described: rewriting
     * them would put a title in a reviewer's mouth for a period when it was not theirs.
     *
     * <h2>When it refuses</h2>
     *
     * <p>A snapshot is text, so two spells at one company with the same title are
     * indistinguishable in it. Where more than one employment at that company carries the old
     * title, this does nothing and says so, rather than rewriting ratings that belong to the other
     * spell. The moderator sees the count and can act on it.
     */
    private Future<Integer> syncRatingTitleSnapshots(SqlConnection tx, Row p, long roleId) {
        String wasTitle = p.getString("was_title");
        String nowTitle = p.getString("new_title");
        if (wasTitle == null || nowTitle == null || wasTitle.equalsIgnoreCase(nowTitle)) {
            return Future.succeededFuture(0);
        }
        /*
          EXCLUDING THIS EMPLOYMENT, because its own title has ALREADY been updated by the time
          this runs - the correction is applied before the audit and the sync. Counting it in made
          the guard order-dependent: it found one row still wearing the old title instead of two,
          concluded there was no ambiguity, and rewrote the OTHER spell's ratings. Any other
          employment at this company still carrying the old title is the ambiguity.
        */
        return tx.preparedQuery("""
                SELECT COUNT(*) AS c
                  FROM career_history ch
                 WHERE ch.manager_id = (SELECT manager_id FROM career_history WHERE id = $1)
                   AND ch.company_id = (SELECT company_id FROM career_history WHERE id = $1)
                   AND ch.id <> $1
                   AND ch.title_key  = normalise_title($2)
                   AND ch.state IN ('accepted', 'proposed')
                """)
            .execute(Tuple.of(roleId, wasTitle))
            .compose(rs -> {
                long sharing = rs.iterator().next().getLong("c");
                if (sharing > 0) {
                    // Ambiguous: more than one employment at this company wore that title, and a
                    // text snapshot cannot say which spell a rating belongs to.
                    return Future.succeededFuture(-1);
                }
                return tx.preparedQuery("""
                        UPDATE reviews r
                           SET manager_title = $3
                          FROM career_history ch
                         WHERE ch.id = $1
                           AND r.manager_id = ch.manager_id
                           AND LOWER(TRIM(r.manager_company)) = LOWER(TRIM(ch.company))
                           AND LOWER(TRIM(r.manager_title))   = LOWER(TRIM($2))
                        """)
                    .execute(Tuple.of(roleId, wasTitle, nowTitle))
                    .map(u -> u.rowCount());
            });
    }

    /**
     * The same carry-across for a corrected EMPLOYER, after a supersession.
     *
     * <p>Same reasoning: the snapshot said a company the person did not work for, and the
     * date-capping match is on company as well as title. Scoped to the ratings that recorded the
     * superseded employment's company and title, so a rating about a different employer is never
     * touched.
     */
    private Future<Integer> syncRatingCompanySnapshots(SqlConnection tx, long oldRoleId,
                                                       long newRoleId) {
        return tx.preparedQuery("""
                UPDATE reviews r
                   SET manager_company = replacement.company
                  FROM career_history old, career_history replacement
                 WHERE old.id = $1
                   AND replacement.id = $2
                   AND r.manager_id = old.manager_id
                   AND LOWER(TRIM(r.manager_company)) = LOWER(TRIM(old.company))
                   AND LOWER(TRIM(r.manager_title))   = LOWER(TRIM(old.title))
                """)
            .execute(Tuple.of(oldRoleId, newRoleId))
            .map(u -> u.rowCount());
    }

    /**
     * Keep the profile headline in step when the employment it mirrors is corrected.
     *
     * <p>{@code managers.title} and {@code managers.company} are what the profile header and every
     * directory tile display. For most managers that pair IS their current employment, and for
     * many it is the only record of it.
     *
     * <p>So correcting the title of the employment the manager record asserts has to move the
     * headline too, or the correction appears not to have worked: the card would read correctly
     * while the heading above it kept the old title.
     *
     * <p>Guarded on the headline still saying what the correction was written against. If the
     * header has since been changed to something else, this is not the employment it mirrors any
     * more and rewriting it would clobber a newer value - the same reasoning as the staleness
     * check, applied to the one field that lives outside the change set.
     */
    private Future<Void> syncManagerHeadline(SqlConnection tx, Row p, long roleId) {
        String wasTitle = p.getString("was_title");
        String nowTitle = p.getString("new_title");
        if (wasTitle == null || nowTitle == null || wasTitle.equalsIgnoreCase(nowTitle)) {
            return Future.succeededFuture();
        }
        return tx.preparedQuery("""
                UPDATE managers m
                   SET title = $3, updated_at = now()
                  FROM career_history ch
                 WHERE ch.id = $1
                   AND m.id = ch.manager_id
                   AND m.company_id IS NOT DISTINCT FROM ch.company_id
                   AND LOWER(TRIM(m.title)) = LOWER(TRIM($2))
                """)
            .execute(Tuple.of(roleId, wasTitle, nowTitle))
            .mapEmpty();
    }
}
