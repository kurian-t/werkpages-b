package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.CapturedDraftRepository;
import org.werkpages.repository.InterviewRepository;
import org.werkpages.repository.UserRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Interview experience reviews, attached to a company rather than a manager.
 *
 * <p>Two decisions shape everything here:
 *
 * <ul>
 *   <li><b>Outcome is required.</b> Candidates who were rejected rate a process markedly lower than
 *       those who got the offer. An average that mixes them measures who was most annoyed, not how
 *       the company interviews — so every read path can slice by outcome, and the company page shows
 *       the split rather than one blended number.
 *   <li><b>Difficulty is not a rating.</b> A hard interview is not a bad interview. It is collected
 *       and displayed, but never folded into {@code overallRating} or any "top rated" logic.
 * </ul>
 */
public class InterviewService {

    /** Outcomes in the order the company page renders them; also the set accepted on write. */
    private static final List<String> OUTCOME_DISPLAY_ORDER = List.of("offer", "no_offer", "withdrew", "pending");

    private static final Set<String> OUTCOMES        = Set.copyOf(OUTCOME_DISPLAY_ORDER);
    /**
     * The formats a single round can take. A process is a list of these, in order — "phone screen,
     * then a panel, then a VP conversation" is the shape a candidate actually wants to know, and
     * one flat format field could not express it.
     */
    private static final Set<String> ROUND_TYPES = Set.of(
        "recruiter_screen", "phone", "video", "hiring_manager",
        "technical", "take_home", "pair_programming", "case_study",
        "panel", "onsite", "executive");

    /** A process longer than this is almost certainly a mistake, and the CHECK agrees. */
    private static final int MAX_ROUNDS = 10;
    private static final Set<String> PROCESS_LENGTHS = Set.of("under_1_week", "1_2_weeks", "2_4_weeks", "over_1_month");

    /** Matches the manager-review ceiling's intent, set lower: an interview takes weeks to have. */
    private static final int DAILY_LIMIT = 3;

    /** Below this, a company's averages are noise and are reported as a count only. */
    private static final int MIN_REVIEWS_TO_SHOW_AVERAGES = 3;

    /** Interview processes track the hiring market; anything older is history, not guidance. */
    private static final int OLDEST_YEAR = 2000;

    private final InterviewRepository interviewRepo;
    private final CompanyRepository   companyRepo;
    private final UserRepository      userRepo;
    /* Stateless, and constructed in place like the other collaborators in this package. */
    private final CapturedDraftRepository drafts;
    /* Stateless, and constructed in place like the other collaborators in this package. */
    private final DeclaredLocationResolver declaredLocations;

    public InterviewService(InterviewRepository interviewRepo, CompanyRepository companyRepo, UserRepository userRepo) {
        this.interviewRepo = interviewRepo;
        this.companyRepo   = companyRepo;
        this.userRepo      = userRepo;
        this.drafts        = new CapturedDraftRepository(interviewRepo.client());
        this.declaredLocations = new DeclaredLocationResolver(
            new org.werkpages.repository.CompanyLocationRepository(interviewRepo.client()));
    }


    /**
     * Keeps an interview experience somebody assembled but could not submit.
     *
     * <p>This form used to render a sign-in wall instead of its fields, so a logged-out person never
     * saw a single question — out of step with every other contribution form, and impossible to
     * capture from, because a form that never rendered has no answers to keep. Now they fill it in
     * and the account is asked for at submit, which is the moment this exists for.
     *
     * <p>Never published and never aggregated: a draft is one person's unfinished answer, not an
     * experience. It goes to the admin queue and nowhere else.
     */
    public Future<JsonObject> captureDraft(String companySlug, JsonObject body) {
        if (body == null || body.isEmpty()) {
            return Future.failedFuture(ServiceException.badRequest("Nothing to capture"));
        }
        UUID draftToken = parseDraftToken(body.getString("draftToken"));
        return companyRepo.findBySlug(companySlug)
            .map(opt -> opt.map(row -> row.getLong("id")).orElse(null))
            .compose(companyId -> drafts.capture(
                CapturedDraftRepository.INTERVIEW, companyId, null, null, draftToken,
                // The token lives in its own column; a copy in the payload could disagree with it.
                body.copy().put("companySlug", companySlug))
                .map(v -> new JsonObject().put("captured", true)));
    }

    private static UUID parseDraftToken(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw.trim()); } catch (IllegalArgumentException e) { return null; }
    }

    // ── Create ────────────────────────────────────────────────────────────────

    public Future<JsonObject> createReview(String auth0Id, String companySlug, JsonObject body) {
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));

        return resolveUser(auth0Id).compose(userId ->
            resolveCompanyId(companySlug).compose(companyId -> {
                Draft draft = parseDraft(body);

                return interviewRepo.countSubmittedTodayByUser(userId)
                    .compose(todayCount -> SubmissionLimits.checkDailyLimit(todayCount, SubmissionLimits.DAILY_INTERVIEWS))
                    .compose(v -> interviewRepo.findRecentDeletion(userId, companyId))
                    // Their earlier review is coming back anonymously; a replacement now would
                    // count the same person twice.
                    .compose(recentDeletion -> SubmissionLimits.checkCooldown(recentDeletion, "interview"))
                    .compose(v -> interviewRepo.existsForYear(userId, companyId, draft.interviewYear))
                    .compose(exists -> {
                        if (exists) {
                            return Future.failedFuture(ServiceException.conflict("interview_review_exists_for_year"));
                        }
                        /*
                          Where the interviewing happened, resolved against the company it was at.
                          interview_reviews has carried the ladder since V68 and nothing wrote to
                          it, so an interview could never be filed against the branch it happened
                          at while a manager review at the same company could.
                        */
                        return declaredLocations.resolve(interviewRepo.client(),
                                DeclaredLocation.fromBody(body),
                                CorpusPlace.fromBody(body.getJsonObject("corpusPlace")), companyId)
                            .compose(declared ->
                        interviewRepo.create(companyId, userId, draft.overall, draft.communication,
                            draft.respectForTime, draft.roleClarity, draft.processFairness,
                            draft.nextStepTransparency, draft.jobRelevance, draft.difficulty, draft.outcome,
                            draft.roundCount(), draft.processLength, draft.roleCategory,
                            draft.country, draft.city, draft.interviewYear, draft.author,
                            draft.interviewedFrom, draft.interviewedUntil, declared));
                    })
                    .compose(row -> interviewRepo
                        .insertRounds(row.getUUID("id"), draft.rounds)
                        .map(ignored -> row))
                    // The draft this came from is finished work now, not a queue item. Clearing it
                    // here means an admin never opens one whose author came back and completed it.
                    .compose(row -> drafts.clear(interviewRepo.client(),
                                                 parseDraftToken(body.getString("draftToken")))
                        .map(v -> row))
                    /*
                      The company's read-model row is recomputed as part of this write.

                      company_stats_live carries the interview average the tiles and the listing
                      read, and a projection nothing updates is not a cache - it is a second
                      source of truth that drifts. The sync awaits and swallows its own failure,
                      so a stats problem can never fail somebody's submission.
                    */
                    .compose(row -> companyRepo.syncStatsForCompany(row.getLong("company_id"))
                        .map(v -> row))
                    .map(InterviewService::reviewToJson);
            }));
    }

    /**
     * Replaces a review the caller wrote.
     *
     * <p>Ownership is enforced in the UPDATE itself rather than by reading the row first, so there
     * is no window between the check and the write in which the row could change hands.
     */
    public Future<JsonObject> updateReview(String auth0Id, String reviewId, JsonObject body) {
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));

        UUID id;
        try {
            id = UUID.fromString(reviewId);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(ServiceException.badRequest("Invalid review id"));
        }

        return resolveUser(auth0Id).compose(userId -> {
            Draft draft = parseDraft(body);
            /*
              The edit carries the location too.

              The form has always sent it and this path dropped it, so somebody who filed an
              experience against the wrong branch - or against no branch at all, before the field
              existed - could correct every other answer and never that one. The company is read
              first because a workplace belongs to exactly one company, and a request naming
              another company's building has to be refused rather than stored.
            */
            return interviewRepo.findCompanyIdFor(id, userId).compose(companyId -> {
                if (companyId.isEmpty()) {
                    return Future.<JsonObject>failedFuture(
                        ServiceException.notFound("Interview review not found"));
                }
                return declaredLocations.resolve(interviewRepo.client(),
                        DeclaredLocation.fromBody(body),
                        CorpusPlace.fromBody(body), companyId.get())
                    .compose(declared ->
            interviewRepo.update(id, userId, draft.overall, draft.communication,
                    draft.respectForTime, draft.roleClarity, draft.processFairness,
                    draft.nextStepTransparency, draft.jobRelevance, draft.difficulty, draft.outcome,
                    draft.roundCount(), draft.processLength, draft.roleCategory,
                    draft.country, draft.city, draft.interviewYear,
                    draft.interviewedFrom, draft.interviewedUntil, declared)
                .compose(updated -> {
                    if (updated.isEmpty()) {
                        return Future.failedFuture(ServiceException.notFound("Interview review not found"));
                    }
                    // Rounds are replaced wholesale: an edit supplies the full list, and merging
                    // would leave rounds from the old process stranded in the middle of the new one.
                    return interviewRepo.deleteRounds(id)
                        .compose(ignored -> interviewRepo.insertRounds(id, draft.rounds))
                        // An edited score moves the company's interview average, so the read
                        // model is recomputed as part of the write. See CLAUDE.md section 21.
                        .compose(ignored -> companyRepo.syncStatsForCompany(companyId.get()))
                        .map(ignored -> reviewToJson(updated.get()));
                }));
            });
        });
    }

    /** Everything a create or an edit needs, parsed and validated once. */
    private record Draft(BigDecimal overall, BigDecimal communication, BigDecimal respectForTime,
                         BigDecimal roleClarity, BigDecimal processFairness,
                         BigDecimal nextStepTransparency, BigDecimal jobRelevance, Integer difficulty, String outcome,
                         String processLength, String roleCategory, String country, String city,
                         int interviewYear, LocalDate interviewedFrom, LocalDate interviewedUntil,
                         List<String> rounds, String author) {
        /** The count follows the list, so the two cannot contradict each other. */
        Integer roundCount() {
            return rounds.isEmpty() ? null : rounds.size();
        }
    }

    private static Draft parseDraft(JsonObject body) {
        return new Draft(
            requiredRating(body, "overallRating"),
            optionalRating(body, "communication"),
            optionalRating(body, "respectForTime"),
            optionalRating(body, "roleClarity"),
            optionalRating(body, "processFairness"),
            optionalRating(body, "nextStepTransparency"),
            optionalRating(body, "jobRelevance"),
            optionalInt(body, "difficulty", 1, 5),
            requiredEnum(body, "outcome", OUTCOMES),
            optionalEnum(body, "processLength", PROCESS_LENGTHS),
            optionalText(body, "roleCategory", 100),
            // The country of the POSITION, not where the candidate lives: someone in Toronto
            // interviewing for a US role went through the US process.
            optionalText(body, "country", 100),
            optionalText(body, "city", 100),
            requiredYear(body),
            parseYearMonth(body, "interviewedFrom"),
            parseYearMonth(body, "interviewedUntil"),
            parseRounds(body),
            // The handle the author picked, the way a manager review is signed. Blank means none:
            // an empty string would put a nameless byline on the card rather than leaving it bare.
            optionalText(body, "author", 60));
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Removes a review the caller wrote.
     *
     * <p>Soft: the author is detached immediately, the row is hidden for three days, then it
     * returns as an anonymous data point. What someone wrote is theirs to take their name off; a
     * company should not be able to lose inconvenient feedback because one contributor was talked
     * into removing it.
     */
    public Future<Void> deleteReview(String auth0Id, String reviewId) {
        UUID id;
        try {
            id = UUID.fromString(reviewId);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(ServiceException.badRequest("Invalid review id"));
        }
        return resolveUser(auth0Id).compose(userId ->
            interviewRepo.softDelete(id, userId).compose(companyId -> {
                if (companyId.isEmpty()) {
                    return Future.failedFuture(ServiceException.notFound("Interview review not found"));
                }
                // Recorded so the same person cannot replace what is going to come back, which
                // would leave the company counting one contributor twice.
                return interviewRepo.recordDeletion(userId, companyId.get())
                    // A removed experience changes the company's interview average.
                    .compose(result -> companyRepo.syncStatsForCompany(companyId.get())
                        .map(v -> result));
            }));
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * The "Getting hired" panel for one company.
     *
     * <p>The summary half is deliberately unfilterable. A company's headline rating jumping from
     * 3.7 to 2.9 because someone clicked a filter reads as the page contradicting itself, so the
     * figures, the strongest and weakest areas, and the confidence sentence always describe every
     * interview on record.
     *
     * <p>Only {@code categoryComparison} responds to {@code role}. It carries all three series -
     * everyone, offers, rejections - at once, which is what makes the difference legible without
     * asking anyone to memorise a number and click again.
     *
     * @param role    restrict the comparison chart to one role category, or null for all
     * @param auth0Id viewer, or null when signed out - drives the contribution gate
     */
    public Future<JsonObject> getCompanyInterviews(String companySlug, String role, String country, String auth0Id) {
        return resolveCompanyId(companySlug).compose(companyId ->
            Future.all(
                interviewRepo.findStats(companyId),
                interviewRepo.findBreakdown(companyId),
                interviewRepo.findOutcomeSplit(companyId, null, null),
                interviewRepo.findRoleCategories(companyId),
                isInterviewContributor(auth0Id),
                interviewRepo.findTypicalRounds(companyId)
            ).compose(cf -> Future.all(
                interviewRepo.findCategoryComparison(companyId, role, country),
                interviewRepo.findCountries(companyId),
                findMine(auth0Id, companyId)
            ).map(inner -> {
                Row              comparison = inner.resultAt(0);
                RowSet<Row>      countries  = inner.resultAt(1);
                Optional<Row>    mine       = inner.resultAt(2);
                Optional<Row> stats       = cf.resultAt(0);
                Row           breakdown   = cf.resultAt(1);
                RowSet<Row>   split       = cf.resultAt(2);
                RowSet<Row>   roles       = cf.resultAt(3);
                boolean       contributor = cf.resultAt(4);
                RowSet<Row>   typicalRounds = cf.resultAt(5);

                int reviewCount = countOf(breakdown, "review_count");

                JsonObject out = new JsonObject()
                    .put("reviewCount",   stats.map(r -> r.getInteger("review_count")).orElse(0))
                    .put("avgRating",     stats.map(r -> numberOrNull(r, "avg_rating")).orElse(null))
                    .put("avgDifficulty", stats.map(r -> numberOrNull(r, "avg_difficulty")).orElse(null))
                    .put("medianRounds",  stats.map(r -> r.getValue("median_rounds")).orElse(null))
                    /*
                      How long it usually took, as one of the four answers the form offers.

                      Read from the breakdown, not the stats row: company_interview_stats is a
                      trigger-maintained table and adding a column to it would mean a migration for
                      a figure the unfiltered breakdown already has to hand.
                    */
                    .put("medianProcessLength", breakdown == null ? null : breakdown.getString("median_process_length"))
                    .put("outcomeSplit",  outcomeSplitJson(split))
                    .put("roleCategories", roleCategoriesJson(roles))
                    .put("countries", countriesJson(countries))
                    .put("typicalRounds", typicalRoundsJson(typicalRounds))
                    .put("hasContributed", contributor)
                    .put("role", role)
                    .put("country", country)
                    // Once you have contributed here, the page stops asking and starts offering
                    // to edit or remove what you wrote.
                    .put("myInterview", mine.map(InterviewService::reviewToJson).orElse(null));

                // Category data is the payoff for contributing, and that applies to both the
                // strongest/weakest summary and the comparison chart - one gate, not two.
                if (!contributor) {
                    out.put("categoryAverages", (Object) null);
                    out.put("categoryComparison", (Object) null);
                    out.put("gated", true);
                } else if (reviewCount < MIN_REVIEWS_TO_SHOW_AVERAGES) {
                    /*
                      A thin sample is shown, and said to be thin.

                      This used to withhold the breakdown entirely below the threshold, so a
                      company with two interviews answered "not enough to break down yet" - which
                      is a worse answer than the data plus a caveat. Nowhere else in the product
                      refuses to show a figure for being early: the manager profile and the
                      workplace tab both print theirs and tell the reader how much is behind it.
                      Two reports genuinely are two people's experience; the reader is capable of
                      weighing that once told.

                      belowThreshold still travels, because the caveat is driven by it.
                    */
                    out.put("categoryAverages", categoryAveragesJson(breakdown));
                    out.put("categoryComparison", categoryComparisonJson(comparison));
                    out.put("gated", false);
                    out.put("belowThreshold", true);
                } else {
                    out.put("categoryAverages", categoryAveragesJson(breakdown));
                    out.put("categoryComparison", categoryComparisonJson(comparison));
                    out.put("gated", false);
                }
                return out;
            })));
    }

    /** The caller's own review for this company, if they are signed in and have one. */
    private Future<Optional<Row>> findMine(String auth0Id, long companyId) {
        if (auth0Id == null || auth0Id.isBlank()) return Future.succeededFuture(Optional.empty());
        return userRepo.findByAuth0IdWithBan(auth0Id)
            .compose(opt -> opt.isEmpty()
                ? Future.succeededFuture(Optional.empty())
                : interviewRepo.findMineForCompany(opt.get().getUUID("id"), companyId));
    }

    /** Has this user ever filed an interview review? Gates the category breakdown. */
    public Future<JsonObject> hasContributed(String auth0Id) {
        return isInterviewContributor(auth0Id)
            .map(contributed -> new JsonObject().put("hasContributed", contributed));
    }

    private Future<Boolean> isInterviewContributor(String auth0Id) {
        if (auth0Id == null || auth0Id.isBlank()) return Future.succeededFuture(false);
        return userRepo.findByAuth0IdWithBan(auth0Id)
            .compose(opt -> opt.isEmpty()
                ? Future.succeededFuture(false)
                : interviewRepo.hasContributed(opt.get().getUUID("id")));
    }

    /** Industry-level interview averages, for the industry pages. */
    public Future<JsonArray> getIndustryAverages() {
        return interviewRepo.findIndustryAverages().map(rows -> {
            JsonArray arr = new JsonArray();
            for (Row r : rows) {
                arr.add(new JsonObject()
                    .put("industry",      r.getString("industry"))
                    .put("reviewCount",   countOf(r, "review_count"))
                    .put("avgRating",     numberOrNull(r, "avg_rating"))
                    .put("avgDifficulty", numberOrNull(r, "avg_difficulty")));
            }
            return arr;
        });
    }

    // ── Resolution helpers ────────────────────────────────────────────────────

    private Future<UUID> resolveUser(String auth0Id) {
        if (auth0Id == null || auth0Id.isBlank()) {
            return Future.failedFuture(ServiceException.unauthorized("Unauthorized"));
        }
        return userRepo.findByAuth0IdWithBan(auth0Id).compose(opt -> {
            if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("User not found"));
            Row row = opt.get();
            if (Boolean.TRUE.equals(row.getBoolean("is_banned"))) {
                return Future.failedFuture(ServiceException.forbidden("account_suspended"));
            }
            return Future.succeededFuture(row.getUUID("id"));
        });
    }

    /**
     * Interview reviews never create a company. Manager submission does that, deliberately — a
     * company that exists only because someone claims to have interviewed there has no verifiable
     * anchor at all, and the directory would fill with them.
     */
    /**
     * The individual interview experiences behind the averages.
     *
     * <p>Gated exactly as the category data is: sharing an experience is what buys the detail, and
     * a reader who has not is shown the count but not the accounts. One gate for the whole tab
     * rather than a second rule to keep in step with the first.
     *
     * <p>Each row carries what identifies an experience - the year, the role, the outcome, the
     * rounds - and no author, because an interview review has never had one.
     */
    public Future<JsonObject> listForCompany(String auth0Id, String companySlug, int limit, int offset) {
        final int cappedLimit = Math.max(1, Math.min(limit, 50));
        final int safeOffset  = Math.max(0, offset);
        return resolveCompanyId(companySlug).compose(companyId ->
            isInterviewContributor(auth0Id).compose(contributor -> {
                /*
                  The gate withholds the SCORES, not the experiences.

                  A locked reader gets the real cards - the role, the year, the outcome, the
                  rounds, who wrote it - with every number stripped out server-side, and the page
                  blurs the space where they would be. Returning an empty array instead left this
                  tab looking like a company nobody had interviewed at, which is both wrong and
                  the least persuasive thing it could say.

                  Stripped here rather than hidden there: a blur is a visual effect, and the
                  value would still be in the payload.
                */
                final boolean withholdScores = !contributor;
                return interviewRepo.findByCompany(companyId, cappedLimit, safeOffset).map(rows -> {
                    JsonArray data = new JsonArray();
                    for (Row row : rows) {
                        data.add(new JsonObject()
                            .put("id",            row.getUUID("id").toString())
                            .put("overallRating", withholdScores ? null : numberOrNull(row, "overall_rating"))
                            .put("difficulty",    withholdScores ? null : numberOrNull(row, "difficulty"))
                            // The per-part scores, so a card can open the way a workplace rating
                            // does. Already selected by the query; withholding them here just made
                            // the reader take the average on trust.
                            .put("categories",    withholdScores ? new JsonObject() : new JsonObject()
                                .put("communication",        numberOrNull(row, "communication"))
                                .put("respectForTime",       numberOrNull(row, "respect_for_time"))
                                .put("roleClarity",          numberOrNull(row, "role_clarity"))
                                .put("processFairness",      numberOrNull(row, "process_fairness"))
                                .put("nextStepTransparency", numberOrNull(row, "next_step_transparency"))
                                .put("jobRelevance",         numberOrNull(row, "job_relevance")))
                            .put("outcome",       row.getString("outcome"))
                            .put("rounds",        row.getInteger("rounds"))
                            .put("processLength", row.getString("process_length"))
                            .put("roleCategory",  row.getString("role_category"))
                            .put("interviewYear", row.getInteger("interview_year"))
                            .put("interviewedFrom",  ym(row, "interviewed_from"))
                            .put("interviewedUntil", ym(row, "interviewed_until"))
                            .put("country",       row.getString("country"))
                            // Null on experiences written before authors existed; those render
                            // without a byline, exactly as they always did.
                            .put("author",        row.getString("author"))
                            .put("createdAt",     row.getOffsetDateTime("created_at").toString())
                            .put("updatedAt",     row.getOffsetDateTime("updated_at") == null
                                                  ? null : row.getOffsetDateTime("updated_at").toString()));
                    }
                    return new JsonObject()
                        .put("data", data).put("gated", withholdScores)
                        .put("limit", cappedLimit).put("offset", safeOffset);
                });
            }));
    }

    private Future<Long> resolveCompanyId(String companySlug) {
        if (companySlug == null || companySlug.isBlank()) {
            return Future.failedFuture(ServiceException.badRequest("Company is required"));
        }
        return companyRepo.findBySlug(companySlug).compose(opt -> opt.isEmpty()
            ? Future.failedFuture(ServiceException.notFound("Company not found"))
            : Future.succeededFuture(opt.get().getLong("id")));
    }

    // ── JSON shaping ──────────────────────────────────────────────────────────

    private static JsonObject reviewToJson(Row row) {
        return new JsonObject()
            .put("id",                   row.getUUID("id").toString())
            .put("overallRating",        numberOrNull(row, "overall_rating"))
            .put("communication",        numberOrNull(row, "communication"))
            .put("respectForTime",       numberOrNull(row, "respect_for_time"))
            .put("roleClarity",          numberOrNull(row, "role_clarity"))
            .put("processFairness",      numberOrNull(row, "process_fairness"))
            .put("nextStepTransparency", numberOrNull(row, "next_step_transparency"))
            .put("jobRelevance", numberOrNull(row, "job_relevance"))
            .put("difficulty",           row.getValue("difficulty"))
            .put("outcome",              row.getString("outcome"))
            .put("rounds",               row.getValue("rounds"))
            .put("processLength",        row.getString("process_length"))
            .put("roleCategory",         row.getString("role_category"))
            .put("country",              row.getString("country"))
            .put("city",                 row.getString("city"))
            .put("interviewYear",        row.getValue("interview_year"))
            // The range the form now asks for. Null on rows written before it existed, where only
            // the year was ever recorded.
            .put("interviewedFrom",      ym(row, "interviewed_from"))
            .put("interviewedUntil",     ym(row, "interviewed_until"))
            // Where the interviewing happened, so the edit form opens with what is stored.
            .put("declaredCountry",      row.getString("declared_country"))
            .put("declaredState",        row.getString("declared_state"))
            .put("declaredCity",         row.getString("declared_city"))
            .put("declaredPrecision",    row.getString("declared_precision"))
            .put("companyLocationId",    row.getLong("company_location_id"));
    }

    /**
     * The three series, each as a category -> average map, plus how many reviews back each one.
     *
     * <p>A series with no reviews behind it still appears, with null averages: "nobody who was
     * rejected has reported here" is information, and an absent key would be read as zero.
     */
    private static JsonObject categoryComparisonJson(Row row) {
        return new JsonObject()
            .put("overall", seriesJson(row, "all"))
            .put("offer",   seriesJson(row, "offer"))
            .put("noOffer", seriesJson(row, "no_offer"));
    }

    private static JsonObject seriesJson(Row row, String suffix) {
        JsonObject series = new JsonObject()
            .put("count", countOf(row, suffix.equals("all") ? "all_count" : suffix + "_count"))
            .put("overallRating", numberOrNull(row, "overall_rating_" + suffix));
        for (String column : InterviewRepository.CATEGORIES) {
            series.put(toCamelCase(column), numberOrNull(row, column + "_" + suffix));
        }
        return series;
    }

    private static JsonObject categoryAveragesJson(Row row) {
        JsonObject out = new JsonObject();
        for (String column : InterviewRepository.CATEGORIES) {
            out.put(toCamelCase(column), numberOrNull(row, column));
        }
        return out;
    }

    /**
     * Every outcome is present in the response, including the ones nobody selected. An absent key
     * and a zero count read identically to a frontend that only checks truthiness, and they mean
     * very different things — "nobody reported a rejection" is a real signal about a company.
     */
    private static JsonObject outcomeSplitJson(RowSet<Row> rows) {
        JsonObject out = new JsonObject();
        for (String outcome : OUTCOME_DISPLAY_ORDER) {
            out.put(toCamelCase(outcome), new JsonObject().put("count", 0).putNull("avgRating"));
        }
        for (Row r : rows) {
            out.put(toCamelCase(r.getString("outcome")), new JsonObject()
                .put("count",     countOf(r, "review_count"))
                .put("avgRating", numberOrNull(r, "overall_rating")));
        }
        return out;
    }

    private static JsonArray typicalRoundsJson(RowSet<Row> rows) {
        JsonArray arr = new JsonArray();
        for (Row r : rows) {
            arr.add(new JsonObject()
                .put("round",      (int) r.getShort("round_number"))
                .put("type",       r.getString("round_type"))
                .put("reportedBy", countOf(r, "reported_by")));
        }
        return arr;
    }

    private static JsonArray countriesJson(RowSet<Row> rows) {
        JsonArray arr = new JsonArray();
        for (Row r : rows) {
            arr.add(new JsonObject()
                .put("country", r.getString("country"))
                .put("count",   countOf(r, "review_count")));
        }
        return arr;
    }

    private static JsonArray roleCategoriesJson(RowSet<Row> rows) {
        JsonArray arr = new JsonArray();
        for (Row r : rows) {
            arr.add(new JsonObject()
                .put("role",  r.getString("role_category"))
                .put("count", countOf(r, "review_count")));
        }
        return arr;
    }

    /** {@code COUNT(*)} comes back as a non-null Long; every count this API exposes fits in an int. */
    private static int countOf(Row row, String column) {
        return row.getLong(column).intValue();
    }

    private static Double numberOrNull(Row row, String column) {
        Object value = row.getValue(column);
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static String toCamelCase(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') { upper = true; continue; }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }

    /**
     * Reads the ordered list of round formats.
     *
     * <p>Accepts an absent list — a review that says nothing about the shape of the process is
     * still worth having — but rejects a malformed one rather than silently dropping rounds,
     * because a process recorded with a round missing is worse than one recorded with none.
     */
    private static List<String> parseRounds(JsonObject body) {
        JsonArray raw = body.getJsonArray("rounds");
        if (raw == null || raw.isEmpty()) return List.of();
        if (raw.size() > MAX_ROUNDS) {
            throw ServiceException.badRequest("A process cannot have more than " + MAX_ROUNDS + " rounds");
        }
        List<String> types = new java.util.ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Object value = raw.getValue(i);
            if (!(value instanceof String type) || !ROUND_TYPES.contains(type)) {
                throw ServiceException.badRequest("Unknown interview round type at position " + (i + 1));
            }
            types.add(type);
        }
        return types;
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private static BigDecimal requiredRating(JsonObject body, String field) {
        Double raw = body.getDouble(field);
        if (raw == null) throw ServiceException.badRequest(field + " is required");
        return toRating(raw, field);
    }

    private static BigDecimal optionalRating(JsonObject body, String field) {
        Double raw = body.getDouble(field);
        return raw == null ? null : toRating(raw, field);
    }

    private static BigDecimal toRating(double raw, String field) {
        if (raw < 0 || raw > 5) throw ServiceException.badRequest(field + " must be between 0 and 5");
        // The column is NUMERIC(2,1); rounding here rather than letting Postgres do it keeps the
        // value the API echoes back identical to the value it stored.
        return BigDecimal.valueOf(raw).setScale(1, RoundingMode.HALF_UP);
    }

    private static Integer optionalInt(JsonObject body, String field, int min, int max) {
        Integer raw = body.getInteger(field);
        if (raw == null) return null;
        if (raw < min || raw > max) {
            throw ServiceException.badRequest(field + " must be between " + min + " and " + max);
        }
        return raw;
    }

    private static String requiredEnum(JsonObject body, String field, Set<String> allowed) {
        String raw = body.getString(field);
        if (raw == null || raw.isBlank()) throw ServiceException.badRequest(field + " is required");
        if (!allowed.contains(raw)) throw ServiceException.badRequest("Unknown " + field);
        return raw;
    }

    private static String optionalEnum(JsonObject body, String field, Set<String> allowed) {
        String raw = body.getString(field);
        if (raw == null || raw.isBlank()) return null;
        if (!allowed.contains(raw)) throw ServiceException.badRequest("Unknown " + field);
        return raw;
    }

    private static String optionalText(JsonObject body, String field, int maxLength) {
        String raw = body.getString(field);
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > maxLength) {
            throw ServiceException.badRequest(field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    /**
     * A month, as the shared timeline control sends it: {@code "2024-06"}.
     *
     * <p>Month precision matches the manager and workplace forms — nobody remembers the day of an
     * interview, and asking for one invites invention.
     */
    private static LocalDate parseYearMonth(JsonObject body, String field) {
        String raw = body.getString(field);
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim() + "-01");
        } catch (Exception e) {
            throw ServiceException.badRequest(field + " must be a month, as YYYY-MM");
        }
    }

    /** A stored date as the form holds it: the month, without a day nobody supplied. */
    private static String ym(Row row, String column) {
        LocalDate d = row.getLocalDate(column);
        return d == null ? null : String.format("%04d-%02d", d.getYear(), d.getMonthValue());
    }

    private static int requiredYear(JsonObject body) {
        /*
          Derived from the range when the form sends one, so the same question is not asked twice.
          interview_year still backs the one-per-company-per-year rule and the existing filters, and
          rows written before the range existed have nothing else.
        */
        LocalDate from = parseYearMonth(body, "interviewedFrom");
        /*
          Both branches are Integer on purpose. A ternary mixing int and Integer unboxes the
          Integer, so `from != null ? from.getYear() : body.getInteger(...)` throws a
          NullPointerException on the very body this method exists to reject.
        */
        Integer raw = from != null ? Integer.valueOf(from.getYear()) : body.getInteger("interviewYear");
        if (raw == null) throw ServiceException.badRequest("interviewYear is required");
        int currentYear = LocalDate.now().getYear();
        if (raw < OLDEST_YEAR || raw > currentYear) {
            throw ServiceException.badRequest("interviewYear must be between " + OLDEST_YEAR + " and " + currentYear);
        }
        return raw;
    }
}
