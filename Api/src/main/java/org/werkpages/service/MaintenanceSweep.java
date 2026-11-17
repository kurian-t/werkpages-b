package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Row;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.CompanyReviewRepository;
import org.werkpages.repository.ConfidenceRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ProofChallengeRepository;
import org.werkpages.repository.ReviewRepository;
import java.util.List;

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

    /** How long a rating form may sit untouched before the approval queue may show it. */
    private static final int DRAFT_IDLE_MINUTES = 30;

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
    /**
     * Optional, set after construction.
     *
     * <p>A setter rather than another constructor argument: this class already has two overloads
     * and every existing caller and test would have had to change to add a dependency only one
     * step uses. Null simply means that step does not run.
     */
    private CompanyReviewRepository companyReviewRepo;
    /** Optional, set after construction, for the same reason as {@link #companyReviewRepo}. */
    private org.werkpages.repository.InterviewRepository interviewRepo;
    /** Optional, set after construction, for the same reason as the two above. */
    private org.werkpages.repository.CapturedDraftRepository draftRepo;
    /** Private drafts of signed-in contributors. Null when nothing wired it. */
    private org.werkpages.repository.ContributionDraftRepository contributionDraftRepo;
    private final ProofChallengeRepository proofChallengeRepo;
    private final ConfidenceRepository     confidenceRepo;

    /** Optional: absent when no Brandfetch client id is configured. */
    private final BrandfetchClient brandfetch;

    /**
     * One run at a time, however long a run takes.
     *
     * <p>{@code setPeriodic} does not wait: if a sweep outlives its own period - a slow logo
     * provider and a few thousand companies is all it takes - the timer fires again on top of it.
     * Most steps here are idempotent, but the resolution steps are metered calls to Brandfetch
     * and logo.dev, and paying twice for the same work is the cheapest of the possible outcomes.
     *
     * <p>In-process, deliberately, NOT a session-level advisory lock. There is exactly one
     * scheduler for this sweep now - see the comment in RestApi's MainVerticle and section 1 of
     * the shared platform rules - so cross-process overlap is not the risk being guarded against.
     * A session lock held across a long multi-statement job introduces a worse failure than the
     * one it prevents: a connection that dies mid-sweep leaves the lock held, and the sweep then
     * never runs again until somebody notices. {@code CompanyStatsRebuilder} can use an advisory
     * lock safely because its work is one transaction; this is not.
     */
    private final java.util.concurrent.atomic.AtomicBoolean running =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Optional: identity resolution by agreement. Absent means no identities are written. */
    private DomainResolver resolver;

    /** Supplies the resolver after construction, so existing call sites keep their signature. */
    public MaintenanceSweep withResolver(DomainResolver resolver) {
        this.resolver = resolver;
        return this;
    }

    /** How many companies one nightly pass resolves. Deliberately modest - see resolveBrands. */
    private static final int BRAND_RESOLVE_BATCH = 50;

    /** How many companies one deliberate resolution pass touches unless told otherwise. */
    private static final int DEFAULT_RESOLUTION_LIMIT = 10;

    /** A ceiling no environment variable can raise - the Brand API free tier is about 100. */
    private static final int MAX_RESOLUTION_LIMIT = 25;

    /** Refresh a signed icon URL this far before it lapses, rather than serving a dying one. */
    private static final java.time.Duration ICON_MARGIN = java.time.Duration.ofHours(1);

    public MaintenanceSweep(ReviewRepository reviewRepo, ManagerRepository managerRepo,
                            CompanyRepository companyRepo,
                            ProofChallengeRepository proofChallengeRepo,
                            ConfidenceRepository confidenceRepo) {
        this(reviewRepo, managerRepo, companyRepo, proofChallengeRepo, confidenceRepo, null);
    }

    /**
     * With brand resolution. Optional because it needs a third-party client id, and every
     * other step here must keep running without one.
     */
    public MaintenanceSweep(ReviewRepository reviewRepo, ManagerRepository managerRepo,
                            CompanyRepository companyRepo,
                            ProofChallengeRepository proofChallengeRepo,
                            ConfidenceRepository confidenceRepo,
                            BrandfetchClient brandfetch) {
        this.brandfetch = brandfetch;
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
        vertx.setPeriodic(initialDelayMs, periodMs, timerId -> {
            // See `running`: a sweep that outlives its period must not be started on top of
            // itself. runOnce() stays unguarded, because a direct caller means to run it.
            if (!running.compareAndSet(false, true)) {
                System.out.println("\u00b7 maintenance sweep still running, skipping this tick");
                return;
            }
            runOnce().onComplete(ignored -> running.set(false));
        });
    }

    /**
     * Runs every step once. The returned future completes when all of them have settled.
     *
     * <p>Callable directly, which is what makes any of this testable.
     */
    /** Wires in the workplace-rating restore. Chainable, like {@code withResolver}. */
    public MaintenanceSweep withCompanyReviews(CompanyReviewRepository repo) {
        this.companyReviewRepo = repo;
        return this;
    }

    /** Wires in draft housekeeping: abandonment and retention. Chainable. */
    public MaintenanceSweep withCapturedDrafts(org.werkpages.repository.CapturedDraftRepository repo) {
        this.draftRepo = repo;
        return this;
    }

    /**
     * Wires in retention for a signed-in contributor's PRIVATE drafts. Chainable.
     *
     * <p>Not the same housekeeping as {@link #withCapturedDrafts}, and the difference is the
     * point: an anonymous capture is kept for ever because a partial rating does not go stale and
     * the aggregate is why it exists. A contribution draft is an IDENTIFIED half-formed opinion
     * belonging to a named account, so it is not kept indefinitely, and the retention period is
     * disclosed to the person it belongs to.
     */
    public MaintenanceSweep withContributionDrafts(
            org.werkpages.repository.ContributionDraftRepository repo) {
        this.contributionDraftRepo = repo;
        return this;
    }

    /** Wires in the interview restore. Chainable, like {@code withCompanyReviews}. */
    public MaintenanceSweep withInterviews(org.werkpages.repository.InterviewRepository repo) {
        this.interviewRepo = repo;
        return this;
    }

    /**
     * Accepted employment whose stored status does not follow from the record.
     *
     * <p>The reconciliation routine CLAUDE.md section 21 requires of anything derived. It repairs
     * nothing, deliberately: a drifting row means a writing path reached {@code career_history}
     * without resolving, and silently fixing it would hide the defect while making the symptom go
     * away. The job of this step is to make such a path impossible to miss.
     *
     * <p>Reports the ids, because "three roles are wrong" sends somebody looking and
     * "roles 41, 88 and 107 are wrong" tells them where.
     */
    private Future<Void> checkEmploymentStatusDrift() {
        return managerRepo.driftingEmploymentStatuses()
            .onSuccess(ids -> {
                if (!ids.isEmpty()) {
                    System.err.println("\u26a0 " + ids.size() + " accepted employment role(s) carry "
                        + "an end date but are not resolved as ended: " + ids
                        + " - a writing path reached career_history without resolving its status");
                }
            })
            .onFailure(err -> System.err.println(
                "\u26a0 Employment status drift check failed: " + err.getMessage()))
            .otherwiseEmpty()
            .mapEmpty();
    }

    public Future<Void> runOnce() {
        // A List rather than the varargs form: Future.join tops out at six, and the seventh step
        // is a compile error that reads as though the method does not exist.
        return Future.join(List.of(
                restoreExpiredDeletions(),
                restoreExpiredCompanyDeletions(),
                restoreExpiredInterviewDeletions(),
                sweepCapturedDrafts(),
                sweepContributionDrafts(),
                ageOutAbandonedChallenges(),
                awardStandingCredit(),
                refreshExpiredPlaceholders(),
                checkEmploymentStatusDrift(),
                resolveBrands()))
            .mapEmpty();
    }

    /**
     * Fills in the real domain and logo for companies that have never been resolved.
     *
     * <p>Once per company, ever - not once per page view. Logos used to be found by guessing a
     * domain from the company name in every visitor's browser on every render, which both spent
     * a metered third-party allowance on ordinary traffic and got the wrong company whenever the
     * name was not the domain. Resolving here writes the answer onto the row, and the page then
     * renders from our own data.
     *
     * <p>Batched and sequential rather than parallel: this is somebody else's rate limit, and
     * there is no deadline. A few hundred companies resolve over a few nights, and a company
     * added today still gets its logo from the fallback chain in the meantime.
     */
    /**
     * DISABLED until domain resolution is gated on consensus. Deliberately not deleted.
     *
     * <p>Run once against real data, a single-resolver pass wrote {@code microsoft.com} and
     * Microsoft's logo onto a company called "Mi" - Brandfetch returned that match with
     * {@code qualityScore} 1.00 and {@code verified} true. "Go" became google.com on the same
     * terms, and "Ju" became ju.st. Their score measures how well a result matches the QUERY
     * STRING, not whether it is the right company, so no threshold on it can make a lone
     * resolver safe: every one of those cleared a 0.70 bar comfortably.
     *
     * <p>A wrong logo is not a cosmetic defect. It is a confident, authoritative claim that one
     * company is another, which is the exact failure the guessed-domain chain was removed for.
     *
     * <p>{@link #refreshBrandfetchAssets} remains callable with {@code dryRun = true} so the
     * proposed writes can be reviewed, and becomes safe to schedule once a resolution requires
     * two independent resolvers to agree.
     */
    /**
     * OFF unless {@code COMPANY_RESOLUTION_ENABLED=true}. Deliberately opt-in.
     *
     * <p>These steps call {@code api.brandfetch.io/v2/search} - the BRAND API, whose free tier is
     * about <b>100 requests</b>. This sweep processes 50 companies per pass and runs 60 seconds
     * after every boot, so leaving it on would spend the entire allowance on the first restart
     * after a deploy. That is precisely how logo.dev's month was lost, against a quota 5,000
     * times larger.
     *
     * <p>Nothing renders worse for having this off. Logo rendering is served by the LOGO API
     * (cdn.brandfetch.io), a different product with a 1M/month allowance, built from the domain
     * in the browser with no backend involvement at all. This sweep exists for a separate
     * problem - correcting domains that were GUESSED from a company name, such as lime.com
     * resolving to the wrong company - and that is a data-quality job to run deliberately, with
     * a dry run first, not a thing to leave firing on every deploy.
     */
    private Future<Void> resolveBrands() {
        if (!"true".equalsIgnoreCase(System.getenv("COMPANY_RESOLUTION_ENABLED"))) {
            return Future.succeededFuture();
        }
        /*
          Enabling is not the same as authorising writes.

          One environment variable must not be able to spend the Brand API's ~100 free requests,
          so switching this on gives a DRY RUN: it reports what it would change and calls nothing
          that writes. Actually writing needs a second, separate flag, and the batch is small and
          explicitly capped either way.

          The failure this guards against is somebody - including me - setting one variable on a
          deploy and discovering the cost afterwards. That has already happened once today with
          logo.dev, against a quota 5,000 times larger.
        */
        boolean write = "true".equalsIgnoreCase(System.getenv("COMPANY_RESOLUTION_WRITE"));
        int limit = resolutionLimit();
        return resolveCompanyIdentities(limit, !write)
            .compose(v -> write ? fetchMissingLogos(limit) : Future.succeededFuture(0))
            .mapEmpty();
    }

    /** Small by default, explicitly set, and hard-capped - see resolveBrands. */
    private static int resolutionLimit() {
        int limit = DEFAULT_RESOLUTION_LIMIT;
        String raw = System.getenv("COMPANY_RESOLUTION_LIMIT");
        if (raw != null && !raw.isBlank()) {
            try { limit = Integer.parseInt(raw.trim()); } catch (NumberFormatException ignored) { }
        }
        return Math.max(1, Math.min(limit, MAX_RESOLUTION_LIMIT));
    }

    /**
     * Establishes company identity by AGREEMENT, then stores the logo that belongs to it.
     *
     * <p>A single resolver cannot be trusted to do this. Brandfetch returns {@code microsoft.com}
     * for a company called "Mi" with a quality score of 1.00 and its verified flag set; "Go"
     * becomes google.com on the same terms. Their score measures how well a result matches the
     * QUERY STRING, not whether it is the right company, so no threshold separates those from a
     * correct answer. Two resolvers independently reaching the same domain does.
     *
     * <p>Outcomes:
     * <ul>
     *   <li><b>agreement</b> - the domain is stored, with the Brandfetch icon when Brandfetch was
     *       one of the parties that agreed;</li>
     *   <li><b>disagreement or a lone answer</b> - nothing is stored, the company is queued for
     *       review, and its tile shows a letter. A letter is the honest answer to "we do not know
     *       who this is"; a confident wrong logo is not;</li>
     *   <li><b>nothing found</b> - left alone to be retried.</li>
     * </ul>
     *
     * <p>Every candidate is recorded either way, so a reviewer sees what each resolver said
     * rather than a verdict with no evidence behind it.
     *
     * @param dryRun report what would be written, and write nothing.
     */
    public Future<Integer> resolveCompanyIdentities(int limit, boolean dryRun) {
        if (resolver == null || !resolver.canReachConsensus()) return Future.succeededFuture(0);

        return companyRepo.findCompaniesNeedingIdentity(limit).compose(rows -> {
            Future<Integer> chain = Future.succeededFuture(0);
            if (dryRun) {
                System.out.println(String.format("%-26s %-24s %-24s %s",
                    "Company", "Stored domain", "Agreed domain", "Outcome"));
                System.out.println("-".repeat(92));
            }
            for (Row row : rows) {
                long   id     = row.getLong("id");
                String name   = row.getString("name");
                String stored = "CLEARBIT".equals(row.getString("domain_source"))
                              ? row.getString("domain") : null;

                chain = chain.compose(n -> resolver.resolve(name, stored).compose(res -> {
                    Future<Void> evidence = Future.succeededFuture();
                    for (DomainResolver.Candidate c : res.candidates()) {
                        evidence = evidence.compose(v -> companyRepo.recordDomainCandidate(
                            id, c.source().name(), c.domain(), c.brandId(), c.iconUrl(),
                            c.confidence()));
                    }
                    if (dryRun) {
                        System.out.println(String.format("%-26s %-24s %-24s %s",
                            cut(name), cut(stored), cut(res.domain()), describe(res)));
                        return evidence.map(n + 1);
                    }
                    if (res.outcome() == DomainResolver.Outcome.CONSENSUS) {
                        // The icon may come from either Brandfetch path - the name search or
                        // the verified-guess search - but only when its domain is the agreed one.
                        var bf = res.candidates().stream()
                            .filter(c -> c.iconUrl() != null)
                            .filter(c -> DomainResolver.normalize(c.domain()) != null
                                      && DomainResolver.normalize(c.domain()).equals(res.domain()))
                            .findFirst();
                        return evidence
                            .compose(v -> companyRepo.storeConsensusDomain(id, res.domain(),
                                bf.map(DomainResolver.Candidate::brandId).orElse(null),
                                bf.map(DomainResolver.Candidate::iconUrl).orElse(null),
                                bf.map(c -> BrandfetchClient.expiryOf(c.iconUrl())).orElse(null),
                                res.domainSource()))
                            .map(n + 1);
                    }
                    if (res.outcome() == DomainResolver.Outcome.NEEDS_REVIEW) {
                        return evidence.compose(v -> companyRepo.markDomainNeedsReview(id)).map(n);
                    }
                    return evidence.map(n);
                }));
            }
            return chain;
        })
        .onSuccess(n -> { if (n > 0) System.out.println(
            (dryRun ? "✓ (dry run) would resolve " : "✓ Resolved ") + n + " company identity(ies)"); })
        .onFailure(err -> System.err.println("⚠ Identity resolution failed: " + err.getMessage()))
        .otherwise(0);
    }

    /**
     * Fetches the logo for companies that already have a trusted domain but no icon.
     *
     * <p>Separate from identity resolution because the hard question is already answered. A
     * company whose domain came from the picker, an admin, or an earlier agreement does not need
     * its identity re-litigated - it needs an asset. Without this they stay letter tiles forever,
     * because the resolver only looks at UNRESOLVED rows.
     *
     * <p>Searches the DOMAIN, not the name, and accepts only an exact echo: asking for
     * {@code discord.com} must come back as Discord whose canonical domain is discord.com. That
     * is a lookup of something we already know, not a guess about a name - which is why it needs
     * no second opinion. A near miss is refused and the tile keeps its letter.
     */
    public Future<Integer> fetchMissingLogos(int limit) {
        if (resolver == null || brandfetch == null || !brandfetch.isConfigured()) {
            return Future.succeededFuture(0);
        }
        return companyRepo.findCompaniesNeedingBrandfetch(limit, ICON_MARGIN).compose(rows -> {
            Future<Integer> chain = Future.succeededFuture(0);
            for (Row row : rows) {
                long   id     = row.getLong("id");
                String domain = DomainResolver.normalize(row.getString("domain"));
                if (domain == null) continue;   // no trusted identity yet - leave to the resolver
                chain = chain.compose(n -> brandfetch.resolve(domain).compose(match -> {
                    if (match.isEmpty()) return Future.succeededFuture(n);
                    BrandfetchClient.Brand b = match.get();
                    if (!domain.equals(DomainResolver.normalize(b.domain()))) {
                        // Brandfetch knows a different canonical domain for this one. Refuse
                        // rather than attach a logo whose identity disagrees with ours.
                        return Future.succeededFuture(n);
                    }
                    return companyRepo.storeBrandfetchAsset(id, null, b.brandId(),
                            b.iconUrl(), b.iconExpiresAt()).map(n + 1);
                }));
            }
            return chain;
        })
        .onSuccess(n -> { if (n > 0) System.out.println("✓ Fetched " + n + " company logo(s)"); })
        .onFailure(err -> System.err.println("⚠ Logo fetch failed: " + err.getMessage()))
        .otherwise(0);
    }

    private static String describe(DomainResolver.Resolution r) {
        return switch (r.outcome()) {
            case CONSENSUS    -> "agreed by " + r.candidates().size() + " resolver(s) - store";
            case NEEDS_REVIEW -> "no agreement - review, letter tile";
            case UNRESOLVED   -> "nobody recognised it";
        };
    }

    private static String cut(String s) {
        if (s == null || s.isBlank()) return "(none)";
        return s.length() > 22 ? s.substring(0, 21) + "\u2026" : s;
    }

    /**
     * Fetches or refreshes the Brandfetch logo for companies that need one.
     *
     * <p>Deliberately independent of domain consensus. Putting a correct logo on the page and
     * establishing a company's canonical identity are different problems of very different
     * difficulty, and making the easy one wait for the hard one is why the site spent a day
     * showing letter tiles and DuckDuckGo placeholders.
     *
     * <p>Covers both "never had one" and "the signature is about to lapse" - Brandfetch signs
     * its icon URLs with roughly a 24h expiry, so this is a cache with a refresh, not a
     * one-time backfill.
     *
     * @param dryRun when true, reports what it WOULD write and writes nothing. Run this first:
     *               it is the last chance to spot a systematic parent/subsidiary mistake - such
     *               as "Google DeepMind" resolving to google.com - before it becomes stored data
     *               across hundreds of companies.
     * @return how many companies were processed
     */
    public Future<Integer> refreshBrandfetchAssets(int limit, boolean dryRun) {
        if (brandfetch == null || !brandfetch.isConfigured()) {
            return Future.succeededFuture(0);
        }
        return companyRepo.findCompaniesNeedingBrandfetch(limit, ICON_MARGIN)
            .compose(rows -> {
                Future<Integer> chain = Future.succeededFuture(0);
                if (dryRun) {
                    System.out.println(String.format("%-34s %-28s %-28s %s",
                        "Company", "Current domain", "Brandfetch domain", "Action"));
                    System.out.println("-".repeat(104));
                }
                for (Row row : rows) {
                    long   id      = row.getLong("id");
                    String name    = row.getString("name");
                    String current = row.getString("domain");
                    chain = chain.compose(count -> brandfetch.resolve(name).compose(match -> {
                        if (match.isEmpty()) {
                            if (dryRun) System.out.println(String.format("%-34s %-28s %-28s %s",
                                trim(name), trim(current), "(none)", "leave as letter tile"));
                            return Future.succeededFuture(count);
                        }
                        BrandfetchClient.Brand b = match.get();
                        if (dryRun) {
                            String action = current == null || current.isBlank()
                                ? "set domain + icon"
                                : (b.domain().equalsIgnoreCase(current) ? "icon only (domain agrees)"
                                                                        : "icon only (domain differs - kept)");
                            System.out.println(String.format("%-34s %-28s %-28s %s",
                                trim(name), trim(current), trim(b.domain()), action));
                            return Future.succeededFuture(count + 1);
                        }
                        return companyRepo.storeBrandfetchAsset(
                                id, b.domain(), b.brandId(), b.iconUrl(), b.iconExpiresAt())
                            .map(v -> count + 1);
                    }));
                }
                return chain;
            })
            .onSuccess(n -> { if (n > 0) System.out.println(
                (dryRun ? "✓ (dry run) would update " : "✓ Updated ") + n + " company logo(s)"); })
            .onFailure(err -> System.err.println("⚠ Brandfetch refresh failed: " + err.getMessage()))
            .otherwise(0);
    }

    private static String trim(String s) {
        if (s == null || s.isBlank()) return "(none)";
        return s.length() > 26 ? s.substring(0, 25) + "\u2026" : s;
    }

    /** Soft-deleted reviews resurface as anonymous once their window has passed. */
    private Future<Void> restoreExpiredDeletions() {
        return reviewRepo.restoreExpiredDeletions()
            .onSuccess(n -> { if (n > 0) System.out.println("✓ Restored " + n + " anonymised review(s)"); })
            .onFailure(err -> System.err.println("⚠ Review restore job failed: " + err.getMessage()))
            .otherwiseEmpty()
            .mapEmpty();
    }

    /**
     * The workplace-rating half of the same rule.
     *
     * <p>Withdrawing a rating is a three-day soft delete on either form, so a rating of a company
     * resurfaces anonymous exactly as a rating of a manager does. Its author link was already
     * dropped at delete time; all that happens here is the row becoming visible again.
     *
     * <p>Each restored row changes a company average, so the read model is told which companies to
     * recompute. Skipping that is how company_stats_live ends up reporting a figure that no row
     * supports.
     */
    private Future<Void> restoreExpiredCompanyDeletions() {
        if (companyReviewRepo == null) return Future.succeededFuture();
        return companyReviewRepo.restoreExpiredDeletions()
            .compose(companyIds -> {
                if (companyIds.isEmpty()) return Future.succeededFuture();
                System.out.println("✓ Restored " + companyIds.size() + " anonymised workplace rating(s)");
                /*
                  No stats pass needed: the restore clears company_reviews.deleted_at, which is
                  triggered, so each company was refreshed as its rows were restored (V89).
                */
                return Future.<Void>succeededFuture();
            })
            .onFailure(err -> System.err.println("⚠ Workplace rating restore job failed: " + err.getMessage()))
            .otherwiseEmpty()
            .mapEmpty();
    }

    /**
     * Withdrawn interview experiences resurface as anonymous once their window has passed.
     *
     * <p><b>This step did not exist.</b> {@code InterviewRepository.restoreExpiredDeletions} was
     * written, documented and never called, so for every release since V52 a withdrawn interview
     * experience was gone permanently while a withdrawn manager rating and a withdrawn workplace
     * rating both came back after three days. The three forms are meant to behave identically on
     * deletion and two of them did.
     *
     * <p>Only safe alongside V96, which gave the table {@code deleted_reason}. A sweep that cannot
     * tell an author's withdrawal from a moderator's removal would resurrect whatever an admin had
     * taken down, counting toward the company's averages again, which is worse than not sweeping.
     *
     * <p>No stats pass needed: clearing {@code deleted_at} is triggered, so each company was
     * recomputed as its rows came back (V89, V93).
     */
    private Future<Void> restoreExpiredInterviewDeletions() {
        if (interviewRepo == null) return Future.succeededFuture();
        return interviewRepo.restoreExpiredDeletions()
            .onSuccess(n -> {
                if (n > 0) System.out.println("\u2713 Restored " + n + " anonymised interview experience(s)");
            })
            .onFailure(err -> System.err.println("\u26a0 Interview restore job failed: " + err.getMessage()))
            .otherwiseEmpty()
            .mapEmpty();
    }

    /**
     * Draft housekeeping: what has gone quiet, and what has aged out.
     *
     * <p><b>Abandonment is reversible and is not a decision.</b> A form silent for the window is
     * moved out of 'active' so the approval queue can show it; a contributor who returns to the
     * same token moves it straight back. Without this step an admin would watch items appear and
     * change under them every few seconds as somebody typed.
     *
     * <p><b>Nothing is ever deleted here.</b> Drafts are kept indefinitely: a partial rating is
     * evidence about an employer and does not stop being true with age, and the aggregate it
     * feeds is worth less every time its older half is thrown away.
     */
    /**
     * Deletes a signed-in contributor's unfinished rating once the retention period has passed.
     *
     * <p>UNLIKE the anonymous capture sweep above, this one really does delete. An anonymous
     * partial rating is kept for ever because nobody is identified by it and the aggregate is the
     * whole purpose. A contribution draft names an account, so keeping it indefinitely would mean
     * holding somebody's half-formed opinion about their employer with no end date, which is the
     * opposite of what the feature is for.
     *
     * <p>The period is disclosed with every draft the API returns, and submitting or discarding
     * ends it sooner. Scheduled here rather than from a new timer: this is the daily housekeeping
     * and a second scheduler would be a second thing to forget to start.
     */
    private Future<Void> sweepContributionDrafts() {
        if (contributionDraftRepo == null) return Future.succeededFuture();
        return contributionDraftRepo.deleteStale(
                org.werkpages.service.ContributionDraftService.RETENTION_DAYS)
            .onSuccess(removed -> {
                if (removed > 0) {
                    System.out.println("\u2713 " + removed + " private draft(s) past retention removed");
                }
            })
            .onFailure(err -> System.err.println(
                "\u26a0 Contribution draft retention sweep failed: " + err.getMessage()))
            .otherwiseEmpty()
            .mapEmpty();
    }

    private Future<Void> sweepCapturedDrafts() {
        if (draftRepo == null) return Future.succeededFuture();
        /*
          DRAFTS ARE KEPT FOR EVER. There is no expiry step here and that is deliberate.

          This used to delete unresolved drafts after 180 days, with a comment claiming the period
          matched the privacy policy. It did not: the policy says incomplete responses "may be
          automatically saved and retained" and names no period, so the deletion was a limit
          nothing asked for, quietly destroying the exact data the capture exists to collect.

          A partial rating does not go stale. Somebody started rating this employer, got this far
          and stopped, and that is as true in two years as it was on the day. The whole point of
          keeping them is the aggregate, and an aggregate that silently loses its older half is
          worse than no aggregate.

          What remains is the abandonment step, which only decides when a row is VISIBLE in the
          queue. It deletes nothing.
        */
        return draftRepo.markAbandoned(DRAFT_IDLE_MINUTES)
            .onSuccess(moved -> {
                if (moved > 0) System.out.println("\u2713 " + moved + " draft(s) went quiet");
            })
            .onFailure(err -> System.err.println("\u26a0 Draft sweep failed: " + err.getMessage()))
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
        Two pieces of housekeeping that no request path can do, chained in a fixed order.

        FIRST, reconcile any manager whose cached rating count disagrees with the ratings they
        actually hold. Drift of this kind is caused by something not happening - an interrupted
        write, a correction applied by hand, a deployment whose arithmetic predated V106 - so there
        is no event to hang the repair on. The query asks the cache's own question and
        recalculate writes exactly that answer, so the pass converges: a manager it has fixed
        cannot come back, and the sweep is silent on a normal day.

        THEN delete any unclassified legacy placeholder whose countdown has run out. The order is
        load-bearing, as it always was: a row the first step still needs to see must not be
        deleted before it has seen it. Deleting changes no number anybody reads - an example is
        already outside every statistic by provenance - it removes the row, which nothing else does.
    */
    private Future<Void> refreshExpiredPlaceholders() {
        return managerRepo.findManagersWithStaleRatingCache()
            .compose(rows -> {
                Future<Void> chain = Future.succeededFuture();
                int stale = 0;
                for (Row row : rows) {
                    long managerId = row.getLong("id");
                    stale++;
                    // recalculate writes reviews_count and overall_rating, both triggered (V89).
                    chain = chain.compose(v -> managerRepo.recalculate(managerId));
                }
                final int total = stale;
                return chain.map(v -> total);
            })
            .onSuccess(n -> { if (n > 0) System.out.println("✓ Recalculated " + n + " manager(s) whose cached rating count had drifted"); })
            .onFailure(err -> System.err.println("⚠ Stale rating-cache reconciliation failed: " + err.getMessage()))
            /*
              RECOVERED BEFORE THE CHAIN CONTINUES, and this is a fix rather than tidying.

              onFailure only observes; the future stays failed, so compose used to short-circuit
              and the deletion never ran. This class's own contract says every step is independent
              and swallows its own failure "because they are unrelated pieces of maintenance that
              happen to share a timer", and these two did not honour it.

              Observed directly: a syntax error in the query above reported BOTH steps as failed,
              with the second one quoting the first one's error message about a statement it never
              executed. A reader would have been hunting a bug in the deletion that was not there.
            */
            .otherwise(0)
            .compose(ignored -> reviewRepo.deleteExpiredSeedReviews())
            .onSuccess(n -> { if (n > 0) System.out.println("✓ Deleted " + n + " expired placeholder review(s)"); })
            .onFailure(err -> System.err.println("⚠ Expired placeholder deletion failed: " + err.getMessage()))
            .otherwiseEmpty()
            .mapEmpty();
    }
}
