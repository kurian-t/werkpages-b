package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.werkpages.repository.ContributionDraftRepository;
import org.werkpages.repository.ContributionDraftRepository.Outcome;
import org.werkpages.repository.UserRepository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Private restoration of a signed-in contributor's unfinished rating.
 *
 * <h2>The privacy boundary this exists to hold</h2>
 *
 * <p>An anonymous abandoned contribution follows the established moderation capture rules. A
 * signed-in person's unfinished work stays private until they choose to submit it: nothing here
 * is readable by moderation, aggregated, or reachable by anybody but its owner.
 *
 * <h2>Ownership comes from the session, always</h2>
 *
 * <p>Every method takes the authenticated identity and resolves it to a user id. No route accepts
 * an owner, and no draft token grants a read. The database enforces the same thing from below:
 * {@code owner_id} is immutable and every query filters on it.
 */
public class ContributionDraftService {

    /**
     * HOW LONG AN UNFINISHED RATING IS KEPT, from the last accepted save.
     *
     * <p>An identifiable half-formed opinion is not kept indefinitely. The period is returned with
     * every draft so the contributor is told rather than surprised, and submitting or discarding
     * ends it sooner.
     */
    public static final int RETENTION_DAYS = 90;

    private static final Set<String> SUBJECT_TYPES = Set.of("manager", "company", "interview");

    private final ContributionDraftRepository drafts;
    private final UserRepository users;

    public ContributionDraftService(ContributionDraftRepository drafts, UserRepository users) {
        this.drafts = drafts;
        this.users  = users;
    }

    /** The one write route. Returns the body to send, with the HTTP status it implies. */
    public record Written(int status, JsonObject body) {}

    public Future<Written> save(String auth0Id, JsonObject body) {
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));

        String subjectType = body.getString("subjectType");
        if (subjectType == null || !SUBJECT_TYPES.contains(subjectType)) {
            return Future.failedFuture(ServiceException.badRequest(
                "subjectType must be one of manager, company, interview"));
        }
        Long subjectId = body.getLong("subjectId");
        if (subjectId == null) {
            return Future.failedFuture(ServiceException.badRequest("Missing subjectId"));
        }
        /*
          Two slots, because somebody may hold two rating opportunities for one subject and so may
          legitimately be drafting the second. The bound matches opportunity_ordinal_bounded rather
          than inventing a second rule.
        */
        int ordinal = body.getInteger("ordinal", 1);
        if (ordinal != 1 && ordinal != 2) {
            return Future.failedFuture(ServiceException.badRequest("ordinal must be 1 or 2"));
        }
        JsonObject payload = body.getJsonObject("payload");
        if (payload == null || payload.isEmpty()) {
            return Future.failedFuture(ServiceException.badRequest("Missing payload"));
        }
        /*
          A writeId identifies ONE immutable write: one payload, however many times it is retried.
          Answers that changed are a different write and need a different id, or a delayed response
          would acknowledge answers it never carried. The server cannot verify that the client kept
          that promise, so it does the one thing it can: refuse a request that does not name a
          write at all, rather than inventing an id and silently making every retry a new write.
        */
        UUID writeId = uuidOrNull(body.getString("writeId"));
        if (writeId == null) {
            return Future.failedFuture(ServiceException.badRequest(
                "Missing writeId, which must identify one payload across its retries"));
        }
        UUID captureToken = uuidOrNull(body.getString("captureToken"));
        Long baseVersion  = body.getLong("baseVersion");

        return resolveUser(auth0Id).compose(ownerId ->
            drafts.save(ownerId, subjectType, subjectId, (short) ordinal, payload,
                        baseVersion, writeId, captureToken)
                .map(result -> {
                    JsonObject out = new JsonObject()
                        .put("id", result.id().toString())
                        .put("version", result.version())
                        .put("retentionDays", RETENTION_DAYS);
                    if (result.outcome() == Outcome.CONFLICT) {
                        /*
                          409 carries the server's answers so the contributor can choose. Nothing
                          is merged: an unfinished rating is one coherent answer set, and merging
                          two would invent an opinion neither device gave.
                        */
                        return new Written(409, out
                            .put("conflict", true)
                            .put("payload", result.payload()));
                    }
                    // A replay is a success. The write already happened; saying otherwise would
                    // report a conflict that never occurred.
                    return new Written(200, out.put("replayed", result.outcome() == Outcome.REPLAY));
                }));
    }

    /** The contributor's Unfinished Ratings, with what can still be done with each. */
    public Future<JsonObject> list(String auth0Id) {
        return resolveUser(auth0Id)
            .compose(drafts::listForOwner)
            .compose(rows -> availability(rows).map(verdicts -> {
                JsonArray out = new JsonArray();
                for (Row r : rows) out.add(present(r, verdicts));
                return new JsonObject()
                    .put("drafts", out)
                    .put("retentionDays", RETENTION_DAYS);
            }));
    }

    public Future<JsonObject> get(String auth0Id, String rawId) {
        UUID id = uuidOrNull(rawId);
        if (id == null) return Future.failedFuture(ServiceException.badRequest("Malformed draft id"));
        return resolveUser(auth0Id)
            .compose(ownerId -> drafts.findForOwner(ownerId, id))
            .compose(opt -> {
                // Somebody else's draft and a draft that does not exist answer identically, so a
                // stranger cannot learn which ids are real.
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("No such draft"));
                return availability(List.of(opt.get())).map(v -> present(opt.get(), v));
            });
    }

    public Future<JsonObject> discard(String auth0Id, String rawId) {
        UUID id = uuidOrNull(rawId);
        if (id == null) return Future.failedFuture(ServiceException.badRequest("Malformed draft id"));
        return resolveUser(auth0Id)
            .compose(ownerId -> drafts.deleteForOwner(ownerId, id))
            .compose(removed -> removed
                ? Future.succeededFuture(new JsonObject().put("discarded", true))
                : Future.failedFuture(ServiceException.notFound("No such draft")));
    }

    /** The retention sweep, for whatever schedules it. */
    public Future<Integer> sweepExpired() {
        return drafts.deleteStale(RETENTION_DAYS);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Presentation
    // ──────────────────────────────────────────────────────────────────────────

    /** What the subject is now, so a draft can say why it cannot be submitted. */
    private record Verdict(boolean available, String reason, Long redirectTo,
                           String name, String slug) {}

    private JsonObject present(Row r, Map<String, Verdict> verdicts) {
        String type = r.getString("subject_type");
        long sid = r.getLong("subject_id");
        Verdict v = verdicts.getOrDefault(type + ":" + sid,
            new Verdict(false, "This subject is no longer listed.", null, null, null));
        OffsetDateTime updated = r.getOffsetDateTime("updated_at");
        JsonObject out = new JsonObject()
            .put("id", r.getUUID("id").toString())
            .put("subjectType", type)
            .put("subjectId", sid)
            .put("ordinal", (int) r.getShort("ordinal"))
            .put("payload", r.getJsonObject("payload"))
            .put("version", r.getLong("version"))
            .put("savedAt", updated.toString())
            .put("expiresAt", updated.plus(Duration.ofDays(RETENTION_DAYS)).toString())
            .put("available", v.available());
        /*
          Deliberately absent: anything about moderation. A contributor reading their own draft is
          not reading the moderation queue, and capture_token is not returned either - it is a
          write resolution key and echoing it would invite somebody to treat it as a handle.
        */
        if (!v.available()) out.put("unavailableReason", v.reason());
        if (v.redirectTo() != null) out.put("redirectTo", v.redirectTo());
        /*
          The subject's name and slug, so the account page can say WHICH employer a half-finished
          rating is about and link to it. Without them the list reads "Workplace rating" over and
          over, which tells somebody they have unfinished work and not where any of it is.
        */
        if (v.name() != null) out.put("subjectName", v.name());
        if (v.slug() != null) out.put("subjectSlug", v.slug());
        return out;
    }

    /**
     * Whether each draft's subject can still be rated.
     *
     * <p>Two queries for the whole page rather than one per draft: the list is the account's own
     * and is short, but an N+1 here would be an N+1 on a page somebody opens to be reassured their
     * work is safe.
     *
     * <p>A merge does not delete a manager, it marks it rejected and sets {@code merged_into}, so
     * the redirect is read from the subject rather than stored on the draft, which would go stale
     * the moment a second merge happened.
     */
    private Future<Map<String, Verdict>> availability(List<Row> rows) {
        Set<Long> managerIds = new HashSet<>();
        Set<Long> companyIds = new HashSet<>();
        for (Row r : rows) {
            long id = r.getLong("subject_id");
            if ("manager".equals(r.getString("subject_type"))) managerIds.add(id);
            else companyIds.add(id);   // company and interview are both about a company
        }
        Map<String, Verdict> out = new HashMap<>();
        Future<Void> managers = managerIds.isEmpty() ? Future.succeededFuture()
            : drafts.client().preparedQuery(
                    "SELECT id, name, slug, approval_status, merged_into FROM managers "
                  + "WHERE id = ANY($1)")
                .execute(Tuple.of(managerIds.toArray(new Long[0])))
                .map(rs -> {
                    rs.forEach(m -> {
                        Long merged = m.getLong("merged_into");
                        String status = m.getString("approval_status");
                        boolean live = "approved".equals(status) || "ghost".equals(status);
                        out.put("manager:" + m.getLong("id"), live
                            ? new Verdict(true, null, null, m.getString("name"), m.getString("slug"))
                            : new Verdict(false, merged != null
                                ? "This profile was merged into another."
                                : "This profile is no longer listed.", merged,
                                m.getString("name"), m.getString("slug")));
                    });
                    return null;
                });
        Future<Void> companies = companyIds.isEmpty() ? Future.succeededFuture()
            : drafts.client().preparedQuery("SELECT id, name, slug FROM companies WHERE id = ANY($1)")
                .execute(Tuple.of(companyIds.toArray(new Long[0])))
                .map(rs -> {
                    rs.forEach(c -> {
                        Verdict v = new Verdict(true, null, null,
                                                c.getString("name"), c.getString("slug"));
                        out.put("company:" + c.getLong("id"), v);
                        out.put("interview:" + c.getLong("id"), v);
                    });
                    return null;
                });
        return Future.all(List.of(managers, companies)).map(v -> out);
    }

    // ──────────────────────────────────────────────────────────────────────────

    private Future<UUID> resolveUser(String auth0Id) {
        if (auth0Id == null) return Future.failedFuture(ServiceException.unauthorized("Unauthorized"));
        return users.findByAuth0IdWithBan(auth0Id).compose(opt -> {
            if (opt.isEmpty()) return Future.failedFuture(ServiceException.unauthorized("User not found"));
            if (Boolean.TRUE.equals(opt.get().getBoolean("is_banned"))) {
                return Future.failedFuture(ServiceException.forbidden("account_suspended"));
            }
            return Future.succeededFuture(opt.get().getUUID("id"));
        });
    }

    private static UUID uuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s.trim()); } catch (IllegalArgumentException e) { return null; }
    }
}
