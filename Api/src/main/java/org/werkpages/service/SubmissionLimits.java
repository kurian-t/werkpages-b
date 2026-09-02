package org.werkpages.service;

import io.vertx.core.Future;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * How much a person may contribute, and how long they must wait after deleting.
 *
 * Four paths enforce this - managers, reviews, edits and interviews - and until now each stated
 * the rule itself. They had drifted in every way a copied rule can: three used a bare {@code 6}
 * while the fourth had a named constant, the 30-day cooldown appeared as two SQL intervals and
 * two {@code plusDays(30)} expressions, and the reviews path re-checked in Java a window the
 * query had already applied while the interviews path trusted it.
 *
 * None of that was visible from any one site, which is the problem with a rule kept in four
 * places: each copy looks right on its own.
 *
 * The limits differ per surface on purpose - writing a review is not the same act as submitting a
 * manager - so they are separate named constants rather than one number.
 */
public final class SubmissionLimits {

    private SubmissionLimits() {}

    /** Managers a person may submit per day. */
    public static final int DAILY_MANAGERS = 6;

    /** Reviews a person may write per day. */
    public static final int DAILY_REVIEWS = 6;

    /** Edit requests a person may raise per day. */
    public static final int DAILY_EDITS = 6;

    /**
     * Interview experiences per day.
     *
     * Lower than the others deliberately: an interview review is about a company rather than a
     * named person, so there is far less that legitimately stops one person filing many.
     */
    public static final int DAILY_INTERVIEWS = 3;

    /**
     * How long after deleting before the same person may write about the same subject again.
     *
     * Not a punishment. A deleted review comes back anonymously after three days, so accepting a
     * replacement inside this window would end up counting one person twice.
     *
     * The single source of truth for this number - the repositories bind it into their queries
     * rather than writing their own interval.
     */
    public static final int COOLDOWN_DAYS = 30;

    /**
     * Fails when today's allowance is spent.
     *
     * Succeeds with null so it drops into a Vert.x chain as
     * {@code .compose(count -> checkDailyLimit(count, DAILY_REVIEWS)).compose(v -> ...)}.
     */
    public static Future<Void> checkDailyLimit(long todayCount, int limit) {
        if (todayCount >= limit) {
            return Future.failedFuture(ServiceException.tooManyRequests("daily_limit_reached"));
        }
        return Future.succeededFuture();
    }

    /**
     * Fails when a recent deletion is still inside the cooldown.
     *
     * The date is checked here rather than inferred from the row's presence. The repositories do
     * restrict their queries to {@link #COOLDOWN_DAYS}, so in production the two agree - but
     * trusting presence alone means the rule is only enforced where that query runs, and a
     * service-level test that hands over an expired deletion would see it blocked. Checking here
     * keeps one statement of the rule that is also an actual check.
     *
     * @param kind prefix for the error code the client reads, e.g. "review" or "interview".
     */
    public static Future<Void> checkCooldown(Optional<OffsetDateTime> recentDeletion, String kind) {
        if (recentDeletion.isPresent()) {
            OffsetDateTime cooldownEnd = recentDeletion.get().plusDays(COOLDOWN_DAYS);
            if (OffsetDateTime.now(ZoneOffset.UTC).isBefore(cooldownEnd)) {
                return Future.failedFuture(
                    ServiceException.conflict(kind + "_cooldown:" + cooldownEnd.toLocalDate()));
            }
        }
        return Future.succeededFuture();
    }
}
