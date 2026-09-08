package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import org.werkpages.repository.ProofChallengeRepository;
import org.werkpages.repository.UserRepository;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * What the person who wrote a held rating can see and do about it.
 *
 * <p>Two operations only: look at the challenge standing against a rating you wrote, and tell us
 * how you worked with the person. There is deliberately no third — no withdrawing, no clearing,
 * nothing an author can do alone that lifts their own flag. Any such action is a laundering step:
 * challenge a famous name, clear it, come out clean, then submit whatever you actually wanted.
 */
public class ProofChallengeService {

    /**
     * The relationships we ask about. Free text here would be a claim nobody can compare.
     *
     * <p>{@code other} exists because the reporting line is not the only shape a working
     * relationship takes: contractors, secondees and people on a year-long joint project all
     * genuinely worked with somebody without appearing anywhere on their org chart. Making them
     * pick the nearest wrong answer would hide the very thing that explains the overlap.
     */
    private static final Set<String> RELATIONSHIPS =
        Set.of("direct_report", "skip_level", "other_team", "other");

    private static final int MAX_NOTE = 2000;

    private final ProofChallengeRepository challenges;
    private final UserRepository userRepo;

    public ProofChallengeService(ProofChallengeRepository challenges, UserRepository userRepo) {
        this.challenges = challenges;
        this.userRepo   = userRepo;
    }

    /**
     * The challenge standing against this person's rating of this manager, if any.
     *
     * <p>Returns {@code {"challenge": null}} rather than a 404 when there is none: "you are not
     * being asked for anything" is a perfectly good answer, and the proof screen asks this on
     * every visit.
     */
    public Future<JsonObject> findMine(String auth0Id, long managerId) {
        return requireUser(auth0Id)
            .compose(userId -> challenges.findLive(userId, managerId))
            .map(opt -> new JsonObject()
                .put("challenge", opt.map(ProofChallengeService::toJson).orElse(null)));
    }

    /**
     * Records the relationship claim and sends it to a person.
     *
     * <p>Never publishes, whatever the claim says. The career-history cross-check the admin sees
     * is a hint, not a verdict: a contradiction is worth surfacing, but the absence of one only
     * means nothing looks wrong, which is not the same as verified.
     */
    public Future<JsonObject> submitEvidence(String auth0Id, UUID challengeId, JsonObject body) {
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Body required"));

        String relationship = body.getString("relationship");
        if (relationship == null || !RELATIONSHIPS.contains(relationship)) {
            return Future.failedFuture(ServiceException.badRequest(
                "Tell us how you worked with them"));
        }

        LocalDate workedFrom;
        LocalDate workedUntil;
        try {
            workedFrom  = parseMonth(body.getString("workedFrom"));
            workedUntil = body.getString("workedUntil") == null
                ? null : parseMonth(body.getString("workedUntil"));
        } catch (Exception e) {
            return Future.failedFuture(ServiceException.badRequest("Dates should look like 2019-03"));
        }
        if (workedFrom == null) {
            return Future.failedFuture(ServiceException.badRequest("When did you work with them?"));
        }
        if (workedUntil != null && workedUntil.isBefore(workedFrom)) {
            return Future.failedFuture(ServiceException.badRequest("The end date is before the start"));
        }
        // The dates are the load-bearing field, so a claim to have worked with somebody in the
        // future is rejected here rather than quietly reaching an admin as noise.
        if (workedFrom.isAfter(LocalDate.now())) {
            return Future.failedFuture(ServiceException.badRequest("That start date is in the future"));
        }

        String claimedTitle = trimTo(body.getString("claimedTitle"), 100);
        String claimedOrg   = trimTo(body.getString("claimedOrg"), 100);
        String note         = trimTo(body.getString("evidenceNote"), MAX_NOTE);
        if (claimedTitle == null) {
            return Future.failedFuture(ServiceException.badRequest("What was your title then?"));
        }

        final LocalDate from = workedFrom;
        final LocalDate until = workedUntil;
        return requireUser(auth0Id)
            .compose(userId -> challenges.submitEvidence(challengeId, userId, from, until,
                                                         claimedTitle, claimedOrg, relationship, note))
            .compose(opt -> {
                if (opt.isEmpty()) {
                    // Either it is not theirs, or it has already been decided. Both are "nothing
                    // to submit here", and distinguishing them would tell a stranger which
                    // challenge ids exist.
                    return Future.failedFuture(ServiceException.notFound("No open challenge found"));
                }
                return Future.succeededFuture(new JsonObject()
                    .put("challenge", toJson(opt.get()))
                    // Said plainly, because the wait is the product here. Nothing about this
                    // publishes on its own.
                    .put("message", "Thanks — someone on our team is reviewing this. "
                                  + "We'll let you know either way."));
            });
    }

    private static JsonObject toJson(Row r) {
        return new JsonObject()
            .put("id",          r.getUUID("id").toString())
            .put("managerId",   r.getLong("manager_id"))
            .put("reason",      r.getString("reason"))
            .put("status",      r.getString("status"))
            // The affiliation path needs the company's domain to check against; it is filled in
            // once a mail provider exists.
            .put("emailDomain", r.getString("email_domain"))
            .put("submittedAt", r.getOffsetDateTime("submitted_at") == null
                                ? null : r.getOffsetDateTime("submitted_at").toString());
    }

    /** "2019-03" is what the form sends: nobody remembers the day, and asking invites invention. */
    private static LocalDate parseMonth(String value) {
        if (value == null || value.isBlank()) return null;
        return LocalDate.parse(value.trim().length() == 7 ? value.trim() + "-01" : value.trim());
    }

    private static String trimTo(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String t = value.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private Future<UUID> requireUser(String auth0Id) {
        if (auth0Id == null) return Future.failedFuture(ServiceException.unauthorized("Unauthorized"));
        return userRepo.findByAuth0IdWithBan(auth0Id).compose(opt -> {
            if (opt.isEmpty()) return Future.failedFuture(ServiceException.unauthorized("User not found"));
            return Future.succeededFuture(opt.get().getUUID("id"));
        });
    }
}
