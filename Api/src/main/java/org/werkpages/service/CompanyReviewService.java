package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.CompanyReviewRepository;
import org.werkpages.repository.UserRepository;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Rating an employer, as opposed to rating a manager.
 *
 * <p>Its own service rather than more of ManagerService, which is already 2,500 lines and holds
 * five unrelated concerns. A new dataset is the one moment where keeping it separate costs
 * nothing.
 *
 * <p>Deliberately <em>not</em> gated on having rated a manager first. That gate was considered and
 * dropped: anyone can rate any manager, including a ghost they created a minute earlier, so it
 * bought friction rather than proof - and it shut out the people most likely to arrive, the ones
 * who searched for a company by name.
 */
public class CompanyReviewService {

    private final CompanyReviewRepository reviewRepo;
    private final CompanyRepository companyRepo;
    private final UserRepository userRepo;

    public CompanyReviewService(CompanyReviewRepository reviewRepo,
                                CompanyRepository companyRepo,
                                UserRepository userRepo) {
        this.reviewRepo  = reviewRepo;
        this.companyRepo = companyRepo;
        this.userRepo    = userRepo;
    }

    /**
     * Records or replaces this person's rating of a company.
     *
     * <p>All ten categories are required. There is no N/A: a corpus where half the ratings skipped
     * career growth cannot be sliced by career growth, and the interview form already makes the
     * same trade for the same reason.
     */
    public Future<JsonObject> submit(String auth0Id, String companySlug, JsonObject body) {
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));

        return resolveUser(auth0Id).compose(userId ->
            resolveCompany(companySlug).compose(company -> {
                long companyId = company.getLong("id");

                Double overall = body.getDouble("overallRating");
                if (overall == null) {
                    return Future.failedFuture(ServiceException.badRequest(
                        "Give the workplace an overall rating."));
                }
                if (overall < 0 || overall > 5) {
                    return Future.failedFuture(ServiceException.badRequest("Rating must be between 0 and 5."));
                }

                JsonObject ratings = body.getJsonObject("ratings");
                if (ratings == null) {
                    return Future.failedFuture(ServiceException.badRequest("Missing ratings."));
                }
                List<Double> values = new ArrayList<>();
                for (String category : CompanyReviewRepository.CATEGORIES) {
                    Double v = ratings.getDouble(category);
                    if (v == null) {
                        return Future.failedFuture(ServiceException.badRequest(
                            "Rate every category before submitting."));
                    }
                    if (v < 0 || v > 5) {
                        return Future.failedFuture(ServiceException.badRequest(
                            "Ratings must be between 0 and 5."));
                    }
                    values.add(v);
                }

                LocalDate from  = parseDate(body.getString("workedFrom"));
                LocalDate until = parseDate(body.getString("workedUntil"));
                LocalDate today = LocalDate.now();
                if (from == null) {
                    return Future.failedFuture(ServiceException.badRequest("When did you start working here?"));
                }
                if (from.isAfter(today)) {
                    return Future.failedFuture(ServiceException.badRequest("Your start date cannot be in the future."));
                }
                if (until != null && until.isAfter(today)) {
                    return Future.failedFuture(ServiceException.badRequest("Your end date cannot be in the future."));
                }
                if (until != null && until.isBefore(from)) {
                    return Future.failedFuture(ServiceException.badRequest(
                        "Your end date cannot be before your start date."));
                }

                return reviewRepo.upsert(companyId, userId, overall, values, from, until)
                    .map(CompanyReviewService::reviewToJson);
            }));
    }

    /** This person's own rating of a company, for pre-filling the form and showing the done state. */
    public Future<JsonObject> findMine(String auth0Id, String companySlug) {
        if (auth0Id == null) return Future.succeededFuture(new JsonObject().putNull("review"));
        return resolveUser(auth0Id).compose(userId ->
            resolveCompany(companySlug).compose(company ->
                reviewRepo.findByUserAndCompany(userId, company.getLong("id"))
                    .map(opt -> new JsonObject().put("review",
                        opt.map(CompanyReviewService::reviewToJson).orElse(null)))));
    }

    /** Withdraws this person's rating. */
    public Future<JsonObject> delete(String auth0Id, UUID reviewId) {
        return resolveUser(auth0Id).compose(userId ->
            reviewRepo.softDelete(reviewId, userId).compose(count -> count == 0
                // Ownership and existence are the same answer on purpose: telling someone their
                // id was real but not theirs confirms it exists.
                ? Future.failedFuture(ServiceException.notFound("Rating not found"))
                : Future.succeededFuture(new JsonObject().put("success", true))));
    }

    /**
     * The company's aggregate, for the profile page.
     *
     * <p>Returns null rather than zeroes when nobody has rated it. A page that prints 0.0 tells a
     * reader the workplace is terrible; a page that prints nothing tells them nobody has said yet.
     */
    public Future<JsonObject> aggregateFor(long companyId) {
        return reviewRepo.findCompanyAggregate(companyId).map(opt -> {
            if (opt.isEmpty()) return null;
            Row row = opt.get();
            JsonObject categories = new JsonObject();
            for (String c : CompanyReviewRepository.CATEGORIES) {
                categories.put(c, numberOrNull(row, c));
            }
            return new JsonObject()
                .put("ratingCount",   row.getLong("rating_count"))
                .put("overallRating", numberOrNull(row, "overall_rating"))
                .put("categories",    categories);
        });
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Future<UUID> resolveUser(String auth0Id) {
        if (auth0Id == null) return Future.failedFuture(ServiceException.unauthorized("Unauthorized"));
        return userRepo.findByAuth0IdWithBan(auth0Id).compose(opt -> {
            if (opt.isEmpty()) return Future.failedFuture(ServiceException.unauthorized("User not found"));
            if (Boolean.TRUE.equals(opt.get().getBoolean("is_banned"))) {
                return Future.failedFuture(ServiceException.forbidden("account_suspended"));
            }
            return Future.succeededFuture(opt.get().getUUID("id"));
        });
    }

    /**
     * A company rating never creates a company, for the same reason an interview review does not:
     * a company that exists only because somebody claims to have worked there has no anchor at
     * all, and the directory would fill with them.
     */
    private Future<Row> resolveCompany(String slug) {
        if (slug == null || slug.isBlank()) {
            return Future.failedFuture(ServiceException.badRequest("Company is required"));
        }
        return companyRepo.findBySlug(slug.trim()).compose(opt -> opt.isEmpty()
            ? Future.failedFuture(ServiceException.notFound("Company not found"))
            : Future.succeededFuture(opt.get()));
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            // "2021-04" from a month picker, or a full date.
            return raw.length() == 7 ? LocalDate.parse(raw + "-01") : LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static JsonObject reviewToJson(Row row) {
        JsonObject ratings = new JsonObject();
        for (String c : CompanyReviewRepository.CATEGORIES) ratings.put(c, numberOrNull(row, c));
        JsonObject json = new JsonObject()
            .put("id",            row.getUUID("id").toString())
            .put("companyId",     row.getLong("company_id"))
            .put("overallRating", numberOrNull(row, "overall_rating"))
            .put("ratings",       ratings)
            .put("workedFrom",    row.getLocalDate("worked_from") == null
                                    ? null : row.getLocalDate("worked_from").toString());
        LocalDate until = row.getLocalDate("worked_until");
        json.put("workedUntil", until == null ? null : until.toString());
        return json;
    }

    private static Double numberOrNull(Row row, String column) {
        java.math.BigDecimal v = row.getBigDecimal(column);
        return v == null ? null : v.doubleValue();
    }
}
