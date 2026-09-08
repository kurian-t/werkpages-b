package org.werkpages.service;

import io.vertx.core.Future;
import org.werkpages.repository.ConfidenceRepository;
import org.werkpages.repository.ProofChallengeRepository;

import java.util.UUID;

/**
 * How much scrutiny one manager submission earns.
 *
 * <p>Five outcomes, evaluated in a fixed order, first match winning. The ordering is the design:
 * a flagged author is checked <em>before</em> the figures list so somebody with proof outstanding
 * cannot re-enter the self-serve affiliation path, and everything else falls through to
 * {@link #LIVE}, which is the overwhelming majority and must stay frictionless.
 *
 * <p>The contribution gate is what this protects. It opens on any review at all, so a junk rating
 * written in ten seconds buys exactly the access an honest one buys — and the cheapest key anyone
 * could cut was a famous name printed in our own form placeholder.
 */
public enum SubmissionTier {

    /** Publishes, opens the gate, nothing changes. */
    LIVE(null),

    /** Publishes and opens the gate, but the queue is told: a listed name at another company. */
    LIVE_FLAGGED(null),

    /** Held. A listed figure — affiliation or a person clears it. */
    HIGH_PROFILE("high_profile"),

    /** Held. First name equals last name; a person decides. */
    SUSPICIOUS_NAME("suspicious_name"),

    /** Held. This author has proof outstanding, so nothing they write counts until it lands. */
    FLAGGED_USER("flagged_user");

    private final String challengeReason;

    SubmissionTier(String challengeReason) {
        this.challengeReason = challengeReason;
    }

    /** The {@code manager_proof_challenges.reason} this tier opens, or null when it opens none. */
    public String challengeReason() {
        return challengeReason;
    }

    /** Whether a rating in this tier is withheld from the site and from the gate. */
    public boolean isHeld() {
        return challengeReason != null;
    }

    /**
     * Classifies one submission.
     *
     * <p>Note what is <em>not</em> here: nothing is stored on the manager row, and nothing is keyed
     * to a session. The rules are a pure function of the figure, the name and the author, which is
     * what makes the cross-account behaviour fall out for free — a new account rating a listed
     * figure matches the list again, and a new account rating "Thomas Thomas" matches the
     * repetition rule again. It is also why one bad author cannot poison a legitimate manager:
     * there is no author-derived state on that row to poison.
     *
     * @param managerId the existing manager row when there is one, else null
     */
    public static Future<SubmissionTier> classify(ProofChallengeRepository challenges,
                                                  ConfidenceRepository confidence,
                                                  UUID userId, Long managerId,
                                                  String firstName, String lastName,
                                                  Long companyId) {
        String fullName = ((firstName == null ? "" : firstName.trim()) + " "
                         + (lastName  == null ? "" : lastName.trim())).trim();

        return challenges.hasUnresolvedChallenge(userId)
            .compose(flagged -> {
                if (flagged) return Future.succeededFuture(FLAGGED_USER);

                return confidence.current(userId).compose(score -> {
                    if (score < ConfidenceRepository.RESTRICTED_BELOW) {
                        return Future.succeededFuture(FLAGGED_USER);
                    }
                    return challenges.isHighProfile(managerId, fullName, companyId)
                        .compose(listed -> {
                            if (listed) return Future.succeededFuture(HIGH_PROFILE);
                            if (NameValidator.isSuspiciousName(firstName, lastName)) {
                                return Future.succeededFuture(SUSPICIOUS_NAME);
                            }
                            // Last: a listed name at a company that is not theirs. Publishes,
                            // because holding it would challenge every unrelated Tim Cook — but
                            // the queue is told, because misspelling the company is otherwise a
                            // one-line dodge.
                            return challenges.isNameOnlyMatch(fullName, companyId)
                                .map(nearMiss -> nearMiss ? LIVE_FLAGGED : LIVE);
                        });
                });
            });
    }
}
