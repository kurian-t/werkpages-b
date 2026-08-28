package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import org.werkpages.repository.CompanyRepository;
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
    private static final Set<String> INTERVIEW_TYPES = Set.of("phone", "video", "onsite", "technical", "panel");
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

    public InterviewService(InterviewRepository interviewRepo, CompanyRepository companyRepo, UserRepository userRepo) {
        this.interviewRepo = interviewRepo;
        this.companyRepo   = companyRepo;
        this.userRepo      = userRepo;
    }

    // ── Create ────────────────────────────────────────────────────────────────

    public Future<JsonObject> createReview(String auth0Id, String companySlug, JsonObject body) {
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));

        return resolveUser(auth0Id).compose(userId ->
            resolveCompanyId(companySlug).compose(companyId -> {

                // Parse and validate before touching the database — a bad payload should cost one query.
                final BigDecimal overall  = requiredRating(body, "overallRating");
                final BigDecimal comm     = optionalRating(body, "communication");
                final BigDecimal respect  = optionalRating(body, "respectForTime");
                final BigDecimal clarity  = optionalRating(body, "roleClarity");
                final BigDecimal fairness = optionalRating(body, "processFairness");
                final BigDecimal nextStep = optionalRating(body, "nextStepTransparency");

                final Integer difficulty = optionalInt(body, "difficulty", 1, 5);
                final Integer rounds     = optionalInt(body, "rounds", 1, 10);

                final String outcome       = requiredEnum(body, "outcome", OUTCOMES);
                final String interviewType = optionalEnum(body, "interviewType", INTERVIEW_TYPES);
                final String processLength = optionalEnum(body, "processLength", PROCESS_LENGTHS);
                final String roleCategory  = optionalText(body, "roleCategory", 100);
                final int    interviewYear = requiredYear(body);

                return interviewRepo.countSubmittedTodayByUser(userId)
                    .compose(todayCount -> {
                        if (todayCount >= DAILY_LIMIT) {
                            return Future.failedFuture(ServiceException.tooManyRequests("daily_limit_reached"));
                        }
                        return interviewRepo.existsForYear(userId, companyId, interviewYear);
                    })
                    .compose(exists -> {
                        if (exists) {
                            return Future.failedFuture(ServiceException.conflict("interview_review_exists_for_year"));
                        }
                        return interviewRepo.create(companyId, userId, overall, comm, respect, clarity,
                                                    fairness, nextStep, difficulty, outcome, interviewType,
                                                    rounds, processLength, roleCategory, interviewYear);
                    })
                    .map(InterviewService::reviewToJson);
            }));
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public Future<Void> deleteReview(String auth0Id, String reviewId) {
        UUID id;
        try {
            id = UUID.fromString(reviewId);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(ServiceException.badRequest("Invalid review id"));
        }
        return resolveUser(auth0Id)
            .compose(userId -> interviewRepo.softDelete(id, userId))
            .compose(deleted -> deleted
                ? Future.succeededFuture()
                : Future.failedFuture(ServiceException.notFound("Interview review not found")));
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * The "Getting hired" panel for one company.
     *
     * @param outcome   restrict the breakdown to one outcome, or null for all
     * @param role      restrict to one role category, or null for all
     * @param sinceYear lowest interview year to include, or null for all time
     * @param auth0Id   viewer, or null when signed out — drives the contribution gate
     */
    public Future<JsonObject> getCompanyInterviews(String companySlug, String outcome, String role,
                                                   Integer sinceYear, String auth0Id) {
        if (outcome != null && !OUTCOMES.contains(outcome)) {
            return Future.failedFuture(ServiceException.badRequest("Unknown outcome filter"));
        }

        return resolveCompanyId(companySlug).compose(companyId ->
            Future.all(
                interviewRepo.findStats(companyId),
                interviewRepo.findBreakdown(companyId, outcome, role, sinceYear),
                interviewRepo.findOutcomeSplit(companyId, role, sinceYear),
                interviewRepo.findRoleCategories(companyId),
                isInterviewContributor(auth0Id)
            ).map(cf -> {
                Optional<Row> stats      = cf.resultAt(0);
                Row           breakdown  = cf.resultAt(1);
                RowSet<Row>   split      = cf.resultAt(2);
                RowSet<Row>   roles      = cf.resultAt(3);
                boolean       contributor = cf.resultAt(4);

                JsonObject out = new JsonObject()
                    .put("reviewCount",   stats.map(r -> r.getInteger("review_count")).orElse(0))
                    .put("avgRating",     stats.map(r -> numberOrNull(r, "avg_rating")).orElse(null))
                    .put("avgDifficulty", stats.map(r -> numberOrNull(r, "avg_difficulty")).orElse(null))
                    .put("medianRounds",  stats.map(r -> r.getValue("median_rounds")).orElse(null))
                    .put("outcomeSplit",  outcomeSplitJson(split))
                    .put("roleCategories", roleCategoriesJson(roles))
                    .put("hasContributed", contributor);

                // Category averages are the payoff for contributing, and the same gate the manager
                // and company profiles use. The headline count and rating stay public so the page
                // is still worth landing on from search.
                int filteredCount = countOf(breakdown, "review_count");
                out.put("filteredCount", filteredCount);

                if (!contributor) {
                    out.put("categoryAverages", (Object) null);
                    out.put("gated", true);
                } else if (filteredCount < MIN_REVIEWS_TO_SHOW_AVERAGES) {
                    // Not gated — just too thin to average honestly.
                    out.put("categoryAverages", (Object) null);
                    out.put("gated", false);
                    out.put("belowThreshold", true);
                } else {
                    out.put("categoryAverages", categoryAveragesJson(breakdown));
                    out.put("filteredOverall",  numberOrNull(breakdown, "overall_rating"));
                    out.put("filteredDifficulty", numberOrNull(breakdown, "difficulty"));
                    out.put("filteredMedianRounds", breakdown.getValue("median_rounds"));
                    out.put("gated", false);
                }
                return out;
            }));
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
            .put("difficulty",           row.getValue("difficulty"))
            .put("outcome",              row.getString("outcome"))
            .put("interviewType",        row.getString("interview_type"))
            .put("rounds",               row.getValue("rounds"))
            .put("processLength",        row.getString("process_length"))
            .put("roleCategory",         row.getString("role_category"))
            .put("interviewYear",        row.getValue("interview_year"));
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

    private static int requiredYear(JsonObject body) {
        Integer raw = body.getInteger("interviewYear");
        if (raw == null) throw ServiceException.badRequest("interviewYear is required");
        int currentYear = LocalDate.now().getYear();
        if (raw < OLDEST_YEAR || raw > currentYear) {
            throw ServiceException.badRequest("interviewYear must be between " + OLDEST_YEAR + " and " + currentYear);
        }
        return raw;
    }
}
