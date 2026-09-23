package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Row;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.ConfidenceRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ProofChallengeRepository;
import org.werkpages.repository.ReviewRepository;

/**
 * The once-a-day housekeeping, in one callable place.
 *
 * <p>This used to be an anonymous lambda inside {@code MainVerticle}'s startup chain, which made
 * it impossible to test: the only way to observe it was to boot the whole verticle and wait. It
 * had also been silently dead in production for a long time, and nothing noticed - see
 * {@link #schedule(Vertx)}.
 *
 * <p>Every step is independent and every step swallows its own failure, deliberately. One sweep
 * failing must not stop the others: they are unrelated pieces of maintenance that happen to share
 * a timer, and a bad day for one is not a reason to skip the rest.
 */
public class MaintenanceSweep {

    /**
     * How long after boot the first sweep runs.
     *
     * <p><b>This constant is the bug fix.</b> The schedule was {@code setPeriodic(PERIOD_MS, ...)},
     * whose one-argument form fires for the first time a <em>full period</em> after boot. A daily
     * sweep therefore only ever ran on a process that had already been up for 24 hours - and every
     * deploy restarts the process and resets the timer. Deploy more than once a day, as this
     * project routinely does, and the sweep never ran at all.
     *
     * <p>A minute's grace so it does not compete with startup.
     */
    public static final long INITIAL_DELAY_MS = 60_000L;

    /** How often it repeats once it has started. */
    public static final long PERIOD_MS = 86_400_000L;

    private final ReviewRepository         reviewRepo;
    private final ManagerRepository        managerRepo;
    private final CompanyRepository        companyRepo;
    private final ProofChallengeRepository proofChallengeRepo;
    private final ConfidenceRepository     confidenceRepo;

    public MaintenanceSweep(ReviewRepository reviewRepo, ManagerRepository managerRepo,
                            CompanyRepository companyRepo,
                            ProofChallengeRepository proofChallengeRepo,
                            ConfidenceRepository confidenceRepo) {
        this.reviewRepo         = reviewRepo;
        this.managerRepo        = managerRepo;
        this.companyRepo        = companyRepo;
        this.proofChallengeRepo = proofChallengeRepo;
        this.confidenceRepo     = confidenceRepo;
    }

    /** Starts the sweep on its timer, with the production delays. */
    public void schedule(Vertx vertx) {
        schedule(vertx, INITIAL_DELAY_MS, PERIOD_MS);
    }

    /**
     * Starts the sweep, with the delays given.
     *
     * <p>The three-argument {@code setPeriodic} is the whole point: the two-argument form defers
     * the first run by a full period. A test passing a small {@code initialDelayMs} and a large
     * {@code periodMs} will hang on the two-argument form and pass on this one, which is what
     * makes the regression detectable at all.
     */
    public void schedule(Vertx vertx, long initialDelayMs, long periodMs) {
        vertx.setPeriodic(initialDelayMs, periodMs, timerId -> runOnce());
    }

    /**
     * Runs every step once. The returned future completes when all of them have settled.
     *
     * <p>Callable directly, which is what makes any of this testable.
     */
    public Future<Void> runOnce() {
        return Future.join(
                restoreExpiredDeletions(),
                ageOutAbandonedChallenges(),
                awardStandingCredit(),
                refreshExpiredPlaceholders())
            .mapEmpty();
    }

    /** Soft-deleted reviews resurface as anonymous once their window has passed. */
    private Future<Void> restoreExpiredDeletions() {
        return reviewRepo.restoreExpiredDeletions()
            .onSuccess(n -> { if (n > 0) System.out.println("✓ Restored " + n + " anonymised review(s)"); })
            .onFailure(err -> System.err.println("⚠ Review restore job failed: " + err.getMessage()))
            .otherwiseEmpty()
            .mapEmpty();
    }

    /*
        Abandoning is not a way out, so an untouched challenge ages into 'abandoned' - which still
        flags its author and still sits in the admin queue, because a flag nobody can lift is a
        lock with no key.

        Re-running cannot double-charge anyone: the debit is keyed on the challenge id, and that
        uniqueness is a constraint rather than this loop being careful.
    */
    private Future<Void> ageOutAbandonedChallenges() {
        return proofChallengeRepo.markAbandoned()
            .compose(rows -> {
                Future<Void> chain = Future.succeededFuture();
                for (Row r : rows) {
                    chain = chain.compose(v -> confidenceRepo.apply(
                            r.getUUID("user_id"), ConfidenceRepository.CHALLENGE_ABANDONED, -15,
                            "challenge", r.getUUID("id").toString())
                        .otherwiseEmpty().mapEmpty());
                }
                if (rows.rowCount() > 0)
                    System.out.println("✓ Aged out " + rows.rowCount() + " unanswered challenge(s)");
                return chain;
            })
            .onFailure(err -> System.err.println("⚠ Challenge sweep failed: " + err.getMessage()))
            .otherwiseEmpty()
            .mapEmpty();
    }

    /*
        Thirty days continuously live, not thirty days since it was written: a rating held for
        twenty-nine days and approved yesterday has stood for a day.
    */
    private Future<Void> awardStandingCredit() {
        return confidenceRepo.findReviewsDueStandingCredit(500)
            .compose(rows -> {
                Future<Void> chain = Future.succeededFuture();
                for (Row r : rows) {
                    chain = chain.compose(v -> confidenceRepo.apply(
                            r.getUUID("user_id"), ConfidenceRepository.REVIEW_STOOD_30D, 2,
                            "review", r.getUUID("id").toString())
                        .otherwiseEmpty().mapEmpty());
                }
                return chain;
            })
            .onFailure(err -> System.err.println("⚠ Standing credit sweep failed: " + err.getMessage()))
            .otherwiseEmpty()
            .mapEmpty();
    }

    /*
        A placeholder review stops counting 14 days after a real one arrives, but that happens
        because a date passes, not because anything writes. No request fires, so nothing
        recalculated the manager and the cached rating and review count stayed at yesterday's
        numbers indefinitely while the reviews list had already stopped showing those reviews.

        The query returns only managers whose cached count actually disagrees with reality, so on
        a normal day it finds nothing.

        The deletion is chained AFTER the recalculation, never beside it: that query finds managers
        by EXISTS(expired placeholder), so deleting first would hide every manager whose cached
        count had not caught up yet and leave their figures wrong for good. Deleting itself changes
        no visible number - an expired placeholder is already out of the rating, the count and the
        list - it removes the row, which nothing did before.
    */
    private Future<Void> refreshExpiredPlaceholders() {
        return managerRepo.findManagersWithExpiredWeights()
            .compose(rows -> {
                Future<Void> chain = Future.succeededFuture();
                int stale = 0;
                for (Row row : rows) {
                    long managerId = row.getLong("id");
                    stale++;
                    chain = chain
                        .compose(v -> managerRepo.recalculate(managerId))
                        .compose(v -> companyRepo.syncStatsForManager(managerId));
                }
                final int total = stale;
                return chain.map(v -> total);
            })
            .onSuccess(n -> { if (n > 0) System.out.println("✓ Recalculated " + n + " manager(s) whose placeholder reviews expired"); })
            .onFailure(err -> System.err.println("⚠ Expired-weight recalculation failed: " + err.getMessage()))
            .compose(ignored -> reviewRepo.deleteExpiredSeedReviews())
            .onSuccess(n -> { if (n > 0) System.out.println("✓ Deleted " + n + " expired placeholder review(s)"); })
            .onFailure(err -> System.err.println("⚠ Expired placeholder deletion failed: " + err.getMessage()))
            .otherwiseEmpty()
            .mapEmpty();
    }
}
