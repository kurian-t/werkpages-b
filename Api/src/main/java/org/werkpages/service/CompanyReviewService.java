package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.CapturedDraftRepository;
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
    /* Stateless, and constructed in place like the other collaborators in this package. */
    private final CapturedDraftRepository drafts;
    /* Stateless, and constructed in place like the other collaborators in this package. */
    private final DeclaredLocationResolver declaredLocations;

    public CompanyReviewService(CompanyReviewRepository reviewRepo,
                                CompanyRepository companyRepo,
                                UserRepository userRepo) {
        this.reviewRepo  = reviewRepo;
        this.companyRepo = companyRepo;
        this.userRepo    = userRepo;
        this.drafts      = new CapturedDraftRepository(reviewRepo.client());
        this.declaredLocations = new DeclaredLocationResolver(
            new org.werkpages.repository.CompanyLocationRepository(reviewRepo.client()));
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

        /*
          Only the write path may create. Reading a company that does not exist stays a 404 - a
          rating creates its employer, a page view must not.
        */
        String newCompanyName = body.getString("companyName");
        return resolveUser(auth0Id).compose(userId ->
            resolveCompany(companySlug, newCompanyName).compose(company -> {
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

                /*
                  The handle the author picked. Trimmed and capped, and blank means none - an
                  empty string would put a nameless byline on the rating rather than leaving it
                  anonymous, which is a different thing.
                */
                String author = body.getString("author");
                if (author != null) {
                    author = author.trim();
                    if (author.isEmpty()) author = null;
                    else if (author.length() > 60) {
                        return Future.failedFuture(ServiceException.badRequest("That name is too long."));
                    }
                }

                /*
                  Where the work happened, resolved against the company being rated.

                  company_reviews has carried the ladder since V68 and nothing wrote to it, so a
                  workplace rating could only ever be filed against the company as a whole - while
                  a manager review or an interview at the same company could name the branch. Ten
                  Walmarts in one city can be ten different places to work, which is the entire
                  reason the ladder exists.

                  Resolution is what makes an exact pick trustworthy: it rejects a building
                  belonging to another company, promotes one chosen from the corpus, and derives
                  the coarse rungs from the location row rather than from whatever the form sent.
                */
                final String signedAs = author;
                return declaredLocations.resolve(reviewRepo.client(),
                        DeclaredLocation.fromBody(body),
                        CorpusPlace.fromBody(body), companyId)
                    .compose(declared ->
                       reviewRepo.upsert(companyId, userId, overall, values, from, until, signedAs,
                                         declared))
                    // The draft this rating came from is finished work now, not a queue item.
                    // Clearing it here rather than on a schedule means an admin never opens one
                    // whose author came back a minute later.
                    .compose(row -> drafts.clear(reviewRepo.client(), parseUuid(body.getString("draftToken")))
                        .map(v -> row))
                    /*
                      The company's read-model row is recomputed as part of this write.

                      company_stats_live carries the workplace average that the tiles and the
                      listing read, and a projection nothing updates is not a cache - it is a
                      second source of truth that drifts. syncStatsForCompany awaits the write
                      and swallows its failure, so a stats problem can never fail somebody's
                      rating; the reconciler is what catches it if it does.
                    */
                    .compose(row -> companyRepo.syncStatsForCompany(companyId).map(v -> row))
                    .map(CompanyReviewService::reviewToJson);
            }));
    }

    /** This person's own rating of a company, for pre-filling the form and showing the done state. */

    /**
     * Keeps a workplace rating somebody assembled but could not submit.
     *
     * <p>The form lets an unauthenticated person fill in every answer and then sends them to sign
     * in. Many do not come back, and until this existed everything they wrote was discarded at
     * exactly the moment it was complete enough to be worth something — the manager forms had
     * captured that moment for a long time, and this one silently did not.
     *
     * <p>Never published and never aggregated: a draft is one person's unfinished answer, not a
     * rating. It goes to the admin queue and nowhere else.
     *
     * <p>Failure is swallowed by the caller, not here. Capturing is a courtesy to a person who is
     * already leaving; it must never be the reason a redirect to sign-in does not happen.
     */
    public Future<JsonObject> captureDraft(String companySlug, JsonObject body) {
        if (body == null || body.isEmpty()) {
            return Future.failedFuture(ServiceException.badRequest("Nothing to capture"));
        }
        UUID draftToken = parseUuid(body.getString("draftToken"));
        return companyRepo.findBySlug(companySlug)
            .map(opt -> opt.map(row -> row.getLong("id")).orElse(null))
            .compose(companyId -> drafts.capture(
                CapturedDraftRepository.COMPANY_RATING, companyId, null, null, draftToken,
                // The token is stored in its own column; keeping a copy in the payload would let
                // the two disagree.
                body.copy().put("companySlug", companySlug))
                .map(v -> new JsonObject().put("captured", true)));
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw.trim()); } catch (IllegalArgumentException e) { return null; }
    }

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
            reviewRepo.softDelete(reviewId, userId).compose(companyId -> companyId.isEmpty()
                // Ownership and existence are the same answer on purpose: telling someone their
                // id was real but not theirs confirms it exists.
                ? Future.failedFuture(ServiceException.notFound("Rating not found"))
                // A removed rating changes the company's average, so the read model is told.
                : companyRepo.syncStatsForCompany(companyId.get())
                    .map(v -> new JsonObject().put("success", true))));
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

    /**
     * The individual ratings behind a company's average.
     *
     * <p>Anonymous, with one exception: the caller's own rating is marked so the page can show it
     * back to them expanded. That mark is derived from the token, never from anything the client
     * sends, so nobody can ask which rating belongs to somebody else.
     *
     * <p><b>Gated on the workplace gate, server-side.</b> Rating a workplace is what buys the
     * individual accounts, exactly as sharing an interview buys the interview ones - one gate per
     * dataset, {@code hasRatedCompany}, the same one the tab's figures use.
     *
     * <p>This endpoint used to hand every rating to anybody who asked. Nothing leaked visibly,
     * because the page simply did not mount the list for a locked reader - which is frontend
     * hiding standing in for access control, and the rows were one devtools tab away the whole
     * time. The gate belongs here; the page now always renders the section and says it is locked.
     */
    public Future<JsonObject> listFor(String auth0Id, String companySlug, int limit, int offset) {
        int cappedLimit  = Math.min(Math.max(limit, 1), 50);
        int safeOffset   = Math.max(offset, 0);

        Future<UUID> viewer = auth0Id == null
            ? Future.succeededFuture((UUID) null)
            : resolveUser(auth0Id).otherwise((UUID) null);

        return resolveCompany(companySlug).compose(company -> {
            long companyId = company.getLong("id");
            return viewer.compose(viewerId -> contributedToWorkplaceData(viewerId).compose(unlocked -> {
                /*
                  The gate withholds the SCORES, not the ratings themselves.

                  A locked reader gets the real cards - who wrote it, their tenure, when - with
                  every number stripped out server-side, and the page blurs the space where they
                  would be. That is what the manager profile does, so the three tabs finally
                  read the same way, and it is a better ask than an empty column: you can see
                  that eleven people rated this workplace and that you cannot see what they said.

                  Stripped here rather than hidden there. A blur is a visual effect - the value
                  would still be in the payload, still readable in devtools - so a withheld
                  number must never leave the server in the first place.
                */
                return reviewRepo.findByCompany(companyId, cappedLimit, safeOffset).map(rows -> {
                    boolean withholdScores = !unlocked;
                    JsonArray data = new JsonArray();
                    for (Row row : rows) {
                        JsonObject categories = new JsonObject();
                        if (!withholdScores) {
                            for (String c : CompanyReviewRepository.CATEGORIES) {
                                categories.put(c, numberOrNull(row, c));
                            }
                        }
                        UUID author = row.getUUID("user_id");
                        data.add(new JsonObject()
                            .put("id",            row.getUUID("id").toString())
                            .put("overallRating", withholdScores ? null : numberOrNull(row, "overall_rating"))
                            .put("categories",    categories)
                            .put("workedFrom",    row.getLocalDate("worked_from") == null
                                                  ? null : row.getLocalDate("worked_from").toString())
                            .put("workedUntil",   row.getLocalDate("worked_until") == null
                                                  ? null : row.getLocalDate("worked_until").toString())
                            // Still employed there, said as a fact rather than a missing field.
                            .put("current",       row.getLocalDate("worked_until") == null)
                            .put("createdAt",     row.getOffsetDateTime("created_at").toString())
                            // So the card can say "edited 3 days ago" rather than implying the
                            // rating has said the same thing since the day it was written.
                            .put("updatedAt",     row.getOffsetDateTime("updated_at") == null
                                                  ? null : row.getOffsetDateTime("updated_at").toString())
                            // Null for ratings written before authors existed; the page renders
                            // those as "Anonymous employee", exactly as it always did.
                            .put("author",        row.getString("author"))
                            .put("mine",          viewerId != null && viewerId.equals(author)));
                    }
                    return new JsonObject().put("data", data).put("gated", withholdScores)
                        .put("limit", cappedLimit).put("offset", safeOffset);
                });
            }));
        });
    }

    /**
     * Has this reader earned the workplace detail? Anonymous never has.
     *
     * <p>Any company, not this one: the point is that somebody has contributed to the corpus they
     * are reading, not to the exact page they happen to be on. Same rule as the manager gate.
     */
    private Future<Boolean> contributedToWorkplaceData(UUID viewerId) {
        if (viewerId == null) return Future.succeededFuture(false);
        return userRepo.hasRatedCompany(viewerId);
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
     * The company being rated, created if we do not have it yet.
     *
     * <p>This used to refuse: a company that exists only because somebody claims to have worked
     * there has no anchor, and the directory could fill with them. The refusal was the wrong end
     * of that trade. Somebody sitting in front of the form has an employer to tell us about, and
     * answering "we don't have a page for that" turns a contribution into a dead end - the one
     * moment the person is willing to write something is the worst moment to say no.
     *
     * <p>Created as {@code pending_approval}, not {@code ghost}. Ghost means auto-created and
     * publicly live; a company that exists only because somebody typed its name into a rating form
     * has not earned the directory yet. It is held for an admin, and every public surface filters
     * on an allowlist that does not include it, so it stays out of them by construction.
     *
     * <p>Lookup is by slug first because that is what the URL carries; a name only appears when
     * the form sends one for a company the directory does not have.
     */
    private Future<Row> resolveCompany(String slug) {
        return resolveCompany(slug, null);
    }

    private Future<Row> resolveCompany(String slug, String fallbackName) {
        boolean haveSlug = slug != null && !slug.isBlank();
        if (!haveSlug && (fallbackName == null || fallbackName.isBlank())) {
            return Future.failedFuture(ServiceException.badRequest("Company is required"));
        }
        if (!haveSlug) {
            return companyRepo.findOrCreatePending(fallbackName.trim(), null, null);
        }
        return companyRepo.findBySlug(slug.trim()).compose(opt -> {
            if (opt.isPresent()) return Future.succeededFuture(opt.get());
            if (fallbackName != null && !fallbackName.isBlank()) {
                return companyRepo.findOrCreatePending(fallbackName.trim(), null, null);
            }
            return Future.failedFuture(ServiceException.notFound("Company not found"));
        });
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
        json.put("author", row.getString("author"));
        /*
          Handed back so the form opens on the location already filed, the same way it opens on the
          ratings and the period already given. Editing a rating means changing an answer, and a
          location field that came back empty over a stored one reads as the answer having been
          thrown away.
        */
        json.put("declaredCountry",   row.getString("declared_country"))
            .put("declaredState",     row.getString("declared_state"))
            .put("declaredCity",      row.getString("declared_city"))
            .put("declaredPrecision", row.getString("declared_precision"))
            .put("companyLocationId", row.getLong("company_location_id"));
        return json;
    }

    private static Double numberOrNull(Row row, String column) {
        java.math.BigDecimal v = row.getBigDecimal(column);
        return v == null ? null : v.doubleValue();
    }
}
