package org.ratemymanager.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import org.ratemymanager.repository.EditRepository;
import org.ratemymanager.repository.ManagerRepository;
import org.ratemymanager.repository.ReportRepository;
import org.ratemymanager.repository.ReviewRepository;
import org.ratemymanager.repository.UserRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for managers and their reviews.
 * Coordinates ManagerRepository, ReviewRepository, UserRepository, and EditRepository.
 */
public class ManagerService {

    private static final String[] RATING_KEYS = {
        "Communication Style", "Perceived Approachability", "Perceived Clarity of Expectations",
        "Feedback Style", "Perceived Supportiveness", "Decision Making Style",
        "Organization and Planning Style", "Delegation Style",
        "Perceived Professional Demeanor", "Overall Working Experience"
    };
    private static final String[] RATING_KEYS_SNAKE = {
        "communication_style", "perceived_approachability", "perceived_clarity_of_expectations",
        "feedback_style", "perceived_supportiveness", "decision_making_style",
        "organization_and_planning_style", "delegation_style",
        "perceived_professional_demeanor", "overall_working_experience"
    };

    private final ManagerRepository managerRepo;
    private final ReviewRepository  reviewRepo;
    private final UserRepository    userRepo;
    private final EditRepository    editRepo;
    private final ReportRepository  reportRepo;
    private final SqlClient         db; // needed for transactions

    public ManagerService(ManagerRepository managerRepo, ReviewRepository reviewRepo,
                          UserRepository userRepo, EditRepository editRepo,
                          ReportRepository reportRepo, SqlClient db) {
        this.managerRepo = managerRepo;
        this.reviewRepo  = reviewRepo;
        this.userRepo    = userRepo;
        this.editRepo    = editRepo;
        this.reportRepo  = reportRepo;
        this.db          = db;
    }

    // ── GET managers list ─────────────────────────────────────────────────────

    public Future<JsonObject> getManagers(int limit, int offset, String search, String company,
                                           String logoUrlResolver) {
        // logoUrlResolver is a Function<String,String> but we pass it in as a string
        // because CompanyLogoUtils is in RestApi. The handler passes resolved logos.
        // Actually we'll resolve in the handler; here we just return raw rows.
        int effectiveLimit  = Math.min(limit, 100);
        int effectiveOffset = Math.max(offset, 0);
        String searchPattern  = (search  != null && !search.isBlank())  ? "%" + search.trim()  + "%" : null;
        String companyPattern = (company != null && !company.isBlank()) ? "%" + company.trim() + "%" : null;

        if (searchPattern != null && search.trim().length() > 100) {
            return Future.failedFuture(ServiceException.badRequest("Search query too long"));
        }

        Future<Long>         totalFuture = managerRepo.count(searchPattern, companyPattern);
        Future<RowSet<Row>>  dataFuture  = managerRepo.search(effectiveLimit, effectiveOffset, searchPattern, companyPattern);

        return Future.all(totalFuture, dataFuture)
            .map(cf -> new JsonObject()
                .put("_rows", dataFuture.result())   // raw for handler to map
                .put("total",  totalFuture.result())
                .put("limit",  effectiveLimit)
                .put("offset", effectiveOffset)
            );
    }

    /** Returns raw RowSet so the handler can resolve logos. */
    public Future<RowSet<Row>> getManagerRows(int limit, int offset, String search, String company) {
        int effectiveLimit  = Math.min(Math.max(limit, 1), 100);
        int effectiveOffset = Math.max(offset, 0);
        String searchPattern  = (search  != null && !search.isBlank())  ? "%" + search.trim()  + "%" : null;
        String companyPattern = (company != null && !company.isBlank()) ? "%" + company.trim() + "%" : null;
        return managerRepo.search(effectiveLimit, effectiveOffset, searchPattern, companyPattern);
    }

    public Future<Long> countManagers(String search, String company) {
        String searchPattern  = (search  != null && !search.isBlank())  ? "%" + search.trim()  + "%" : null;
        String companyPattern = (company != null && !company.isBlank()) ? "%" + company.trim() + "%" : null;
        return managerRepo.count(searchPattern, companyPattern);
    }

    // ── GET manager by ID ─────────────────────────────────────────────────────

    public Future<Row> getManagerById(long managerId, String auth0Id) {
        return managerRepo.findById(managerId)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Manager not found"));
                Row row = opt.get();
                String approvalStatus = row.getString("approval_status");
                if ("pending_approval".equals(approvalStatus) || "rejected".equals(approvalStatus)) {
                    return enforceSubmitterAccess(row, auth0Id);
                }
                return Future.succeededFuture(row);
            });
    }

    private Future<Row> enforceSubmitterAccess(Row row, String auth0Id) {
        UUID submittedBy = row.getUUID("submitted_by");
        if (auth0Id == null || submittedBy == null) {
            return Future.failedFuture(ServiceException.notFound("Manager not found"));
        }
        return userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty() || !opt.get().equals(submittedBy)) {
                    return Future.failedFuture(ServiceException.notFound("Manager not found"));
                }
                return Future.succeededFuture(row);
            });
    }

    /** Returns whether the given user has reported this manager. */
    public Future<Boolean> hasReported(long managerId, String auth0Id) {
        if (auth0Id == null) return Future.succeededFuture(false);
        return userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.succeededFuture(false);
                return reportRepo.alreadyReported(managerId, opt.get());
            });
    }

    // ── GET companies ─────────────────────────────────────────────────────────

    public Future<JsonObject> getCompanies() {
        return managerRepo.findAllCompanies()
            .map(rows -> {
                JsonArray companies = new JsonArray();
                for (Row row : rows) {
                    String c = row.getString("company");
                    if (c != null && !c.isBlank()) companies.add(c);
                }
                return new JsonObject().put("data", companies);
            });
    }

    // ── GET similar managers ──────────────────────────────────────────────────

    public Future<JsonObject> getSimilarManagers(String name, String company) {
        if (name == null || name.isBlank()) {
            return Future.failedFuture(ServiceException.badRequest("name parameter is required"));
        }
        String nameLike    = "%" + name.trim() + "%";
        String companyLike = (company != null && !company.isBlank()) ? "%" + company.trim() + "%" : "%";
        return managerRepo.findSimilar(nameLike, companyLike)
            .map(rows -> {
                JsonArray results = new JsonArray();
                for (Row row : rows) {
                    results.add(new JsonObject()
                        .put("id", row.getLong("id"))
                        .put("name", row.getString("name"))
                        .put("company", row.getString("company"))
                        .put("title", row.getString("title"))
                        .put("overallRating", row.getBigDecimal("overall_rating"))
                        .put("companyLogoUrl", row.getString("company_logo_url"))
                        .put("approvalStatus", row.getString("approval_status"))
                    );
                }
                return new JsonObject().put("data", results);
            });
    }

    // ── GET stats ─────────────────────────────────────────────────────────────

    public Future<JsonObject> getStats() {
        Future<Long> managersFuture = managerRepo.countApproved();
        Future<Long> reviewsFuture = db.query("SELECT COUNT(*) FROM reviews r JOIN managers m ON r.manager_id = m.id WHERE m.approval_status = 'approved'")
            .execute()
            .map(rows -> rows.iterator().next().getLong(0));
        return Future.all(managersFuture, reviewsFuture)
            .map(cf -> new JsonObject()
                .put("totalManagers", managersFuture.result())
                .put("totalReviews",  reviewsFuture.result())
            );
    }

    // ── GET my submitted managers ─────────────────────────────────────────────

    public Future<RowSet<Row>> getMySubmittedManagers(String auth0Id) {
        return userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.unauthorized("User not found"));
                return managerRepo.findPendingByUser(opt.get());
            });
    }

    // ── CREATE manager ────────────────────────────────────────────────────────

    /** All validation and business logic for POST /api/managers. Returns the created manager row. */
    public Future<Row> createManager(String auth0Id, JsonObject body, String resolvedLogoUrl) {
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));

        String name    = body.getString("name");
        String company = body.getString("company");
        String title   = body.getString("title");
        String image   = body.getString("image");
        if (isBlank(name) || isBlank(company) || isBlank(title) || isBlank(image)) {
            return Future.failedFuture(ServiceException.badRequest("Missing required fields"));
        }
        if (name.length() > 100)    return Future.failedFuture(ServiceException.badRequest("Manager name must be at most 100 characters"));
        if (company.length() > 100) return Future.failedFuture(ServiceException.badRequest("Company must be at most 100 characters"));
        if (title.length() > 100)   return Future.failedFuture(ServiceException.badRequest("Title must be at most 100 characters"));

        String bio         = body.getString("bio");
        String linkedinUrl = body.getString("linkedinUrl");
        if (bio != null && bio.length() > 1000) return Future.failedFuture(ServiceException.badRequest("Bio must be at most 1000 characters"));
        if (!isBlank(linkedinUrl)) {
            if (linkedinUrl.length() > 500) return Future.failedFuture(ServiceException.badRequest("LinkedIn URL must be at most 500 characters"));
            if (!isValidLinkedinUrl(linkedinUrl)) return Future.failedFuture(ServiceException.badRequest("LinkedIn URL must be a valid linkedin.com URL"));
        }

        String submittedStatus = body.getString("status");
        if (submittedStatus == null || (!submittedStatus.equals("active") && !submittedStatus.equals("retired"))) {
            submittedStatus = "active";
        }
        boolean isRetired = "retired".equals(submittedStatus);
        LocalDate today = LocalDate.now();

        LocalDate startDateLocal = parseYearMonth(body.getString("startDate"));
        if (startDateLocal == null) return Future.failedFuture(ServiceException.badRequest("Manager start date is required"));
        if (startDateLocal.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Manager start date cannot be in the future"));

        LocalDate endDateLocal = parseYearMonth(body.getString("endDate"));
        if (isRetired) {
            if (endDateLocal == null) return Future.failedFuture(ServiceException.badRequest("End date is required for a retired manager"));
            if (endDateLocal.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Manager end date cannot be in the future"));
            if (endDateLocal.isBefore(startDateLocal)) return Future.failedFuture(ServiceException.badRequest("Manager end date must be on or after the start date"));
        } else {
            endDateLocal = null;
        }

        JsonObject reviewBody = body.getJsonObject("review");
        if (reviewBody == null) return Future.failedFuture(ServiceException.badRequest("A review is required when submitting a manager"));

        // Validate review fields
        Double overallRating = reviewBody.getDouble("overallRating");
        JsonObject ratings   = reviewBody.getJsonObject("ratings");
        String managerCompany = reviewBody.getString("managerCompany");
        String managerTitle   = reviewBody.getString("managerTitle");
        String reviewText     = reviewBody.getString("text");
        LocalDate workedFrom  = parseYearMonth(reviewBody.getString("workedFrom"));
        LocalDate workedUntil = parseYearMonth(reviewBody.getString("workedUntil"));

        if (overallRating == null || ratings == null || isBlank(managerCompany) || isBlank(managerTitle)) {
            return Future.failedFuture(ServiceException.badRequest("Review is missing required fields"));
        }
        if (workedFrom == null) return Future.failedFuture(ServiceException.badRequest("Your start date working with this manager is required"));
        if (workedFrom.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("The 'from' date cannot be in the future"));
        if (workedUntil != null && workedUntil.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("The 'to' date cannot be in the future"));
        if (isRetired && workedUntil == null) return Future.failedFuture(ServiceException.badRequest("A retired manager cannot have a current reviewer — end date is required"));
        if (workedUntil != null && workedUntil.isBefore(workedFrom)) return Future.failedFuture(ServiceException.badRequest("The 'to' date cannot be before the 'from' date"));
        if (workedFrom.isBefore(startDateLocal)) return Future.failedFuture(ServiceException.badRequest("You cannot have worked with this manager before they started in this role"));
        if (endDateLocal != null) {
            if (workedFrom.isAfter(endDateLocal)) return Future.failedFuture(ServiceException.badRequest("Your 'from' date cannot be after the manager's end date"));
            if (workedUntil != null && workedUntil.isAfter(endDateLocal)) return Future.failedFuture(ServiceException.badRequest("Your 'to' date cannot be after the manager's end date"));
        }
        if (managerCompany.length() > 100) return Future.failedFuture(ServiceException.badRequest("Manager company must be at most 100 characters"));
        if (managerTitle.length() > 100)   return Future.failedFuture(ServiceException.badRequest("Manager title must be at most 100 characters"));
        if (reviewText != null && reviewText.length() > 2000) return Future.failedFuture(ServiceException.badRequest("Review text must be at most 2000 characters"));
        if (!isValidRating(overallRating)) return Future.failedFuture(ServiceException.badRequest("Overall rating must be between 1 and 5"));
        for (int i = 0; i < RATING_KEYS.length; i++) {
            Double v = getRating(ratings, i);
            if (!isValidRating(v)) return Future.failedFuture(ServiceException.badRequest("Rating for '" + RATING_KEYS[i] + "' must be between 1 and 5"));
        }

        final String   fStatus               = submittedStatus;
        final LocalDate fStartDate           = startDateLocal;
        final String   fReviewAuthorType     = reviewBody.getString("authorType", "username");
        final String   fReviewClientAuthor   = reviewBody.getString("author", "").trim();
        final LocalDate fEndDate      = endDateLocal;
        final String   fBio           = bio;
        final String   fLinkedinUrl   = linkedinUrl;
        final String   fReviewText    = reviewText;
        final Double   fOverallRating = overallRating;
        final JsonObject fRatings     = ratings;
        final String   fMgrCompany    = managerCompany;
        final String   fMgrTitle      = managerTitle;
        final LocalDate fWorkedFrom   = workedFrom;
        final LocalDate fWorkedUntil  = workedUntil;

        return userRepo.findByAuth0IdWithBan(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.unauthorized("User not found"));
                Row userRow = opt.get();
                if (userRow.getBoolean("is_banned")) return Future.failedFuture(ServiceException.forbidden("account_suspended"));
                UUID userId = userRow.getUUID("id");
                String dbUsername = userRow.getString("username");
                String author = ("real_name".equals(fReviewAuthorType) || "anonymous".equals(fReviewAuthorType))
                    && !fReviewClientAuthor.isEmpty() && fReviewClientAuthor.length() <= 100
                    ? fReviewClientAuthor : dbUsername;
                return managerRepo.countSubmittedTodayByUser(userId)
                    .compose(todayCount -> {
                        if (todayCount >= 6) return Future.failedFuture(ServiceException.tooManyRequests("daily_limit_reached"));
                        // Transactional insert
                        OffsetDateTime startDt = fStartDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
                        OffsetDateTime endDt   = fEndDate != null ? fEndDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime() : null;
                        return ((Pool) db).withTransaction(conn ->
                            conn.preparedQuery("""
                                INSERT INTO managers
                                (name, company, title, image, bio, status, approval_status, linkedin_url,
                                 company_logo_url, overall_rating, reviews_count, category_averages, created_at, submitted_by)
                                VALUES ($1,$2,$3,$4,$5,$6,'pending_approval',$7,$8,0,0,'{}'::jsonb,now(),$9)
                                RETURNING *
                                """)
                                .execute(Tuple.of(name, company, title, image, fBio, fStatus, fLinkedinUrl, resolvedLogoUrl, userId))
                                .compose(managerResult -> {
                                    Row managerRow = managerResult.iterator().next();
                                    long managerId = managerRow.getLong("id");
                                    conn.preparedQuery("INSERT INTO career_history(manager_id, company, title, start_date, end_date) VALUES ($1,$2,$3,$4,$5)")
                                        .execute(Tuple.of(managerId, company, title, startDt, endDt), ignored -> {});
                                    return conn.preparedQuery("""
                                        INSERT INTO reviews (
                                            manager_id, user_id, author, overall_rating,
                                            communication_style, perceived_approachability, perceived_clarity_of_expectations,
                                            feedback_style, perceived_supportiveness, decision_making_style,
                                            organization_and_planning_style, delegation_style, perceived_professional_demeanor,
                                            overall_working_experience, manager_company, manager_title, text,
                                            worked_from, worked_until, verified, helpful_count, created_at, updated_at
                                        )
                                        VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,true,0,now(),now())
                                        RETURNING id
                                        """)
                                        .execute(Tuple.of(
                                            managerId, userId, author, fOverallRating,
                                            getRating(fRatings, 0), getRating(fRatings, 1), getRating(fRatings, 2),
                                            getRating(fRatings, 3), getRating(fRatings, 4), getRating(fRatings, 5),
                                            getRating(fRatings, 6), getRating(fRatings, 7), getRating(fRatings, 8),
                                            getRating(fRatings, 9), fMgrCompany, fMgrTitle, fReviewText,
                                            fWorkedFrom, fWorkedUntil
                                        ))
                                        .map(ignored -> managerRow);
                                })
                        ).onSuccess(managerRow -> managerRepo.recalculateInBackground(managerRow.getLong("id")));
                    });
            });
    }

    // ── UPDATE manager ────────────────────────────────────────────────────────

    public Future<JsonObject> updateManager(String auth0Id, long managerId, JsonObject body) {
        if (body == null || body.isEmpty()) return Future.failedFuture(ServiceException.badRequest("Nothing to update"));

        String newCompany     = body.getString("company");
        String newTitle       = body.getString("title");
        String newImage       = body.getString("image");
        String newBio         = body.getString("bio");
        String newStatus      = body.getString("status");
        String newLinkedinUrl = body.getString("linkedinUrl");
        String newLogoUrl     = body.getString("resolvedLogoUrl");
        String startDateStr   = body.getString("startDate");

        if (newCompany == null && newTitle == null && newImage == null && newBio == null && newStatus == null && newLinkedinUrl == null) {
            return Future.failedFuture(ServiceException.badRequest("Nothing to update"));
        }
        if (newCompany != null && (newCompany.isBlank() || newCompany.length() > 100)) return Future.failedFuture(ServiceException.badRequest("Company must be between 1 and 100 characters"));
        if (newTitle   != null && (newTitle.isBlank()   || newTitle.length()   > 100)) return Future.failedFuture(ServiceException.badRequest("Title must be between 1 and 100 characters"));
        if (newBio     != null && newBio.length() > 1000) return Future.failedFuture(ServiceException.badRequest("Bio must be at most 1000 characters"));
        if (newStatus  != null && !newStatus.equals("active") && !newStatus.equals("retired")) return Future.failedFuture(ServiceException.badRequest("Status must be 'active' or 'retired'"));
        if (!isBlank(newLinkedinUrl)) {
            if (newLinkedinUrl.length() > 500) return Future.failedFuture(ServiceException.badRequest("LinkedIn URL must be at most 500 characters"));
            if (!isValidLinkedinUrl(newLinkedinUrl)) return Future.failedFuture(ServiceException.badRequest("LinkedIn URL must be a valid linkedin.com URL"));
        }

        return userRepo.isBanned(auth0Id)
            .compose(isBanned -> {
                if (isBanned) return Future.failedFuture(ServiceException.forbidden("account_suspended"));
                return managerRepo.findById(managerId);
            })
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Manager not found"));
                Row current = opt.get();
                String currentCompany = current.getString("company");
                String currentTitle   = current.getString("title");

                boolean companyChanged = newCompany != null && !newCompany.equals(currentCompany);
                boolean titleChanged   = newTitle   != null && !newTitle.equals(currentTitle);

                if (companyChanged || titleChanged) {
                    // Update career history, then update manager
                    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                    String effectiveCo  = newCompany != null ? newCompany : currentCompany;
                    String effectiveTit = newTitle   != null ? newTitle   : currentTitle;
                    LocalDate oldStartLocal = parseYearMonth(startDateStr);
                    OffsetDateTime newPosStart = oldStartLocal != null
                        ? oldStartLocal.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime() : now;

                    return managerRepo.closeOpenCareerEntry(managerId, now)
                        .compose(closedRows -> {
                            Future<Void> archiveOld;
                            if (closedRows == 0) {
                                OffsetDateTime oldStart = oldStartLocal != null
                                    ? oldStartLocal.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()
                                    : current.getOffsetDateTime("created_at");
                                archiveOld = managerRepo.insertCareerEntry(managerId, currentCompany, currentTitle, oldStart, now);
                            } else {
                                archiveOld = Future.succeededFuture();
                            }
                            return archiveOld.compose(v ->
                                managerRepo.insertCareerEntry(managerId, effectiveCo, effectiveTit, newPosStart, null)
                            );
                        })
                        .compose(v -> doUpdate(managerId, newCompany, newTitle, newImage, newBio, newStatus, newLinkedinUrl, newLogoUrl));
                } else {
                    return doUpdate(managerId, newCompany, newTitle, newImage, newBio, newStatus, newLinkedinUrl, newLogoUrl);
                }
            });
    }

    private Future<JsonObject> doUpdate(long managerId, String newCompany, String newTitle,
                                         String newImage, String newBio, String newStatus, String newLinkedinUrl,
                                         String newLogoUrl) {
        return managerRepo.update(managerId, newCompany, newTitle, newImage, newBio, newStatus, newLinkedinUrl, newLogoUrl)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Manager not found"));
                Row row = opt.get();
                return managerRepo.getCareerHistory(managerId)
                    .map(chRows -> buildManagerUpdateJson(row, chRows));
            });
    }

    // ── CREATE review ─────────────────────────────────────────────────────────

    public Future<Row> createReview(String auth0Id, long managerId, JsonObject body) {
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));

        return userRepo.findByAuth0IdWithBan(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("User not found"));
                Row userRow = opt.get();
                if (userRow.getBoolean("is_banned")) return Future.failedFuture(ServiceException.forbidden("account_suspended"));
                UUID userId = userRow.getUUID("id");
                String dbUsername = userRow.getString("username");

                String authorType = body.getString("authorType", "username");
                String author;
                if ("real_name".equals(authorType) || "anonymous".equals(authorType)) {
                    String clientAuthor = body.getString("author", "").trim();
                    author = (clientAuthor.isEmpty() || clientAuthor.length() > 100) ? dbUsername : clientAuthor;
                } else {
                    author = dbUsername;
                }

                return reviewRepo.countSubmittedTodayByUser(userId)
                    .compose(todayCount -> {
                        if (todayCount >= 6) return Future.failedFuture(ServiceException.tooManyRequests("daily_limit_reached"));
                        return reviewRepo.findRecentDeletion(userId, managerId);
                    })
                    .compose(recentDeletion -> {
                        if (recentDeletion.isPresent()) {
                            OffsetDateTime deletedAt = recentDeletion.get();
                            OffsetDateTime cooldownEnd = deletedAt.plusDays(30);
                            String cooldownEndStr = cooldownEnd.toLocalDate().toString(); // YYYY-MM-DD
                            return Future.failedFuture(ServiceException.conflict("review_cooldown:" + cooldownEndStr));
                        }
                        return validateAndInsertReview(body, managerId, userId, author);
                    });
            });
    }

    private Future<Row> validateAndInsertReview(JsonObject body, long managerId, UUID userId, String author) {
        Double overallRating      = body.getDouble("overallRating");
        JsonObject ratings        = body.getJsonObject("ratings");
        String managerCompany     = body.getString("managerCompany");
        String managerTitle       = body.getString("managerTitle");
        String text               = body.getString("text");
        LocalDate workedFrom      = parseYearMonth(body.getString("workedFrom"));
        LocalDate workedUntil     = parseYearMonth(body.getString("workedUntil"));
        LocalDate managerRoleStart = parseYearMonth(body.getString("managerRoleStart"));
        LocalDate managerRoleEnd   = parseYearMonth(body.getString("managerRoleEnd")); // null = still in role
        LocalDate today = LocalDate.now();

        // ── Manager role date validation (optional — not all reviewers know manager tenure) ─
        if (managerRoleStart != null) {
            if (managerRoleStart.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Manager role start date cannot be in the future"));
            if (managerRoleEnd != null) {
                if (managerRoleEnd.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Manager role end date cannot be in the future"));
                if (managerRoleEnd.isBefore(managerRoleStart)) return Future.failedFuture(ServiceException.badRequest("Manager role end date must be on or after the start date"));
            }
        }

        // ── User work date validation ─────────────────────────────────────────────
        if (workedFrom == null) return Future.failedFuture(ServiceException.badRequest("Your start date working with this manager is required"));
        if (workedFrom.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Your 'from' date cannot be in the future"));
        if (workedUntil != null && workedUntil.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Your 'to' date cannot be in the future"));
        if (workedUntil != null && workedFrom.isAfter(workedUntil)) return Future.failedFuture(ServiceException.badRequest("Your 'from' date cannot be later than your 'to' date"));

        // ── Cross-validation: user dates vs manager role period (only when provided) ─
        if (managerRoleStart != null) {
            if (workedFrom.isBefore(managerRoleStart)) return Future.failedFuture(ServiceException.badRequest("Your start date cannot be before the manager started this role (" + formatYM(managerRoleStart) + ")"));
            if (managerRoleEnd != null && workedFrom.isAfter(managerRoleEnd)) return Future.failedFuture(ServiceException.badRequest("Your start date cannot be after the manager left this role (" + formatYM(managerRoleEnd) + ")"));
            if (managerRoleEnd != null && workedUntil != null && workedUntil.isAfter(managerRoleEnd)) return Future.failedFuture(ServiceException.badRequest("Your end date cannot be after the manager left this role (" + formatYM(managerRoleEnd) + ")"));
        }

        if (overallRating == null || ratings == null || isBlank(managerCompany) || isBlank(managerTitle)) return Future.failedFuture(ServiceException.badRequest("Missing required fields"));
        if (managerCompany.length() > 100) return Future.failedFuture(ServiceException.badRequest("Manager company must be at most 100 characters"));
        if (managerTitle.length()   > 100) return Future.failedFuture(ServiceException.badRequest("Manager title must be at most 100 characters"));
        if (text != null && text.length() > 2000) return Future.failedFuture(ServiceException.badRequest("Review text must be at most 2000 characters"));
        if (!isValidRating(overallRating)) return Future.failedFuture(ServiceException.badRequest("Overall rating must be between 1 and 5"));
        for (int i = 0; i < RATING_KEYS.length; i++) {
            Double v = getRating(ratings, i);
            if (!isValidRating(v)) return Future.failedFuture(ServiceException.badRequest("Rating for '" + RATING_KEYS[i] + "' must be between 1 and 5"));
        }

        // Fetch all existing reviews by this user (lightweight — no JOINs)
        return reviewRepo.findByUserForValidation(userId)
            .compose(existingRows -> {
                List<Row> existing = new ArrayList<>();
                existingRows.forEach(existing::add);

                // ── 1. Cap: max 5 reviews for a single manager per user ───────────────
                long reviewsForThisManager = existing.stream()
                    .filter(r -> r.getLong("manager_id") == managerId)
                    .count();
                if (reviewsForThisManager >= 5) {
                    return Future.failedFuture(ServiceException.conflict("role_limit_reached"));
                }

                // ── 2. Role duplicate: same normalised title+company under same manager ─
                String normTitle   = managerTitle.trim().toLowerCase();
                String normCompany = managerCompany.trim().toLowerCase();
                boolean roleTaken = existing.stream()
                    .filter(r -> r.getLong("manager_id") == managerId)
                    .anyMatch(r -> {
                        String t = r.getString("manager_title");
                        String c = r.getString("manager_company");
                        return t != null && c != null
                            && t.trim().equalsIgnoreCase(normTitle)
                            && c.trim().equalsIgnoreCase(normCompany);
                    });
                if (roleTaken) {
                    return Future.failedFuture(ServiceException.conflict("already_reviewed_this_role"));
                }

                // ── 3. Manager role period overlap (only when role dates were provided) ─
                if (managerRoleStart == null) {
                    return reviewRepo.create(managerId, userId, author, overallRating,
                            getRating(ratings, 0), getRating(ratings, 1), getRating(ratings, 2),
                            getRating(ratings, 3), getRating(ratings, 4), getRating(ratings, 5),
                            getRating(ratings, 6), getRating(ratings, 7), getRating(ratings, 8),
                            getRating(ratings, 9), managerCompany, managerTitle, text,
                            workedFrom, workedUntil, null, null)
                        .onSuccess(row -> managerRepo.recalculateInBackground(managerId));
                }
                return reviewRepo.findRolePeriodsForManager(managerId)
                    .compose(allRoleRows -> {
                        LocalDate newRoleEnd = managerRoleEnd != null ? managerRoleEnd : LocalDate.of(9999, 12, 31);
                        for (Row r : allRoleRows) {
                            LocalDate existRoleStart = r.getLocalDate("manager_role_start");
                            LocalDate existRoleEndRaw = r.getLocalDate("manager_role_end");
                            LocalDate existRoleEnd = existRoleEndRaw != null ? existRoleEndRaw : LocalDate.of(9999, 12, 31);
                            boolean overlaps = !managerRoleStart.isAfter(existRoleEnd) && !existRoleStart.isAfter(newRoleEnd);
                            if (overlaps) {
                                String existTitle   = r.getString("manager_title");
                                String existCompany = r.getString("manager_company");
                                return Future.failedFuture(ServiceException.conflict(
                                    "manager_role_overlap:" + existTitle + ":" + existCompany + ":" +
                                    formatYM(existRoleStart) + ":" + (existRoleEndRaw != null ? formatYM(existRoleEndRaw) : "present")));
                            }
                        }

                        return reviewRepo.create(managerId, userId, author, overallRating,
                                getRating(ratings, 0), getRating(ratings, 1), getRating(ratings, 2),
                                getRating(ratings, 3), getRating(ratings, 4), getRating(ratings, 5),
                                getRating(ratings, 6), getRating(ratings, 7), getRating(ratings, 8),
                                getRating(ratings, 9), managerCompany, managerTitle, text,
                                workedFrom, workedUntil, managerRoleStart, managerRoleEnd)
                            .onSuccess(row -> managerRepo.recalculateInBackground(managerId));
                    });  // closes allRoleRows compose
            });  // closes existingRows compose
    }

    // ── GET manager reviews ───────────────────────────────────────────────────

    public Future<JsonObject> getManagerReviews(long managerId, int limit, int offset,
                                                  String sortBy, UUID userIdFilter) {
        Future<Long>        totalFuture = reviewRepo.countByManager(managerId, userIdFilter);
        Future<RowSet<Row>> dataFuture  = reviewRepo.findByManager(managerId, limit, offset, sortBy, userIdFilter);

        return Future.all(totalFuture, dataFuture)
            .map(cf -> {
                JsonArray data = new JsonArray();
                for (Row row : dataFuture.result()) {
                    data.add(buildReviewJson(row));
                }
                return new JsonObject()
                    .put("data",   data)
                    .put("total",  totalFuture.result())
                    .put("limit",  limit)
                    .put("offset", offset);
            });
    }

    // ── GET manager career segments ───────────────────────────────────────────

    public Future<JsonObject> getManagerCareerSegments(long managerId) {
        return reviewRepo.findCareerSegmentsByManager(managerId)
            .map(rows -> {
                JsonArray segments = new JsonArray();
                for (Row row : rows) {
                    boolean isCurrent   = Boolean.TRUE.equals(row.getBoolean("is_current"));
                    LocalDate endRaw    = row.getLocalDate("end_date");
                    LocalDate startRaw  = row.getLocalDate("start_date");

                    JsonObject categoryAverages = new JsonObject()
                        .put("Communication Style",               r1(row.getBigDecimal("communication_style")))
                        .put("Perceived Approachability",         r1(row.getBigDecimal("perceived_approachability")))
                        .put("Perceived Clarity of Expectations", r1(row.getBigDecimal("perceived_clarity_of_expectations")))
                        .put("Feedback Style",                    r1(row.getBigDecimal("feedback_style")))
                        .put("Perceived Supportiveness",          r1(row.getBigDecimal("perceived_supportiveness")))
                        .put("Decision Making Style",             r1(row.getBigDecimal("decision_making_style")))
                        .put("Organization and Planning Style",   r1(row.getBigDecimal("organization_and_planning_style")))
                        .put("Delegation Style",                  r1(row.getBigDecimal("delegation_style")))
                        .put("Perceived Professional Demeanor",   r1(row.getBigDecimal("perceived_professional_demeanor")))
                        .put("Overall Working Experience",        r1(row.getBigDecimal("overall_working_experience")));

                    LocalDate mrStart = row.getLocalDate("manager_role_start");
                    LocalDate mrEnd   = row.getLocalDate("manager_role_end");
                    segments.add(new JsonObject()
                        .put("company",           row.getString("company"))
                        .put("role",              row.getString("role"))
                        .put("startDate",         startRaw  != null ? startRaw.toString() : null)
                        .put("endDate",           isCurrent ? null : (endRaw != null ? endRaw.toString() : null))
                        .put("isCurrent",         isCurrent)
                        .put("averageRating",     r1(row.getBigDecimal("avg_rating")))
                        .put("reviewCount",       row.getLong("review_count").intValue())
                        .put("categoryAverages",  categoryAverages)
                        .put("managerRoleStart",  mrStart != null ? mrStart.toString() : null)
                        .put("managerRoleEnd",    mrEnd   != null ? mrEnd.toString()   : null));
                }
                return new JsonObject().put("data", segments);
            });
    }

    private static double r1(BigDecimal v) {
        if (v == null) return 0.0;
        return Math.round(v.doubleValue() * 10.0) / 10.0;
    }

    // ── UPDATE review ─────────────────────────────────────────────────────────

    public Future<Row> updateReview(String auth0Id, long managerId, UUID reviewId, JsonObject body) {
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));

        Double overallRating       = body.getDouble("overallRating");
        JsonObject ratings         = body.getJsonObject("ratings");
        String managerCompany      = body.getString("managerCompany");
        String managerTitle        = body.getString("managerTitle");
        String text                = body.getString("text");
        String authorType          = body.getString("authorType", "username");
        String clientAuthor        = body.getString("author", "").trim();
        LocalDate workedFrom       = parseYearMonth(body.getString("workedFrom"));
        LocalDate workedUntil      = parseYearMonth(body.getString("workedUntil"));
        LocalDate managerRoleStart = parseYearMonth(body.getString("managerRoleStart")); // optional for legacy edits
        LocalDate managerRoleEnd   = parseYearMonth(body.getString("managerRoleEnd"));
        LocalDate today = LocalDate.now();

        // ── User work date validation ─────────────────────────────────────────────
        if (workedFrom != null && workedUntil != null && workedFrom.isAfter(workedUntil)) return Future.failedFuture(ServiceException.badRequest("Your 'from' date cannot be later than your 'to' date"));
        if (workedFrom != null && workedFrom.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Your 'from' date cannot be in the future"));
        if (workedUntil != null && workedUntil.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Your 'to' date cannot be in the future"));

        // ── Manager role date validation (if provided) ───────────────────────────
        if (managerRoleStart != null) {
            if (managerRoleStart.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Manager role start date cannot be in the future"));
            if (managerRoleEnd != null) {
                if (managerRoleEnd.isAfter(today)) return Future.failedFuture(ServiceException.badRequest("Manager role end date cannot be in the future"));
                if (managerRoleEnd.isBefore(managerRoleStart)) return Future.failedFuture(ServiceException.badRequest("Manager role end date must be on or after the start date"));
            }
            // Cross-validation: user dates must fall within the manager's role period
            if (workedFrom != null && workedFrom.isBefore(managerRoleStart)) return Future.failedFuture(ServiceException.badRequest("Your start date cannot be before the manager started this role (" + formatYM(managerRoleStart) + ")"));
            if (managerRoleEnd != null && workedFrom != null && workedFrom.isAfter(managerRoleEnd)) return Future.failedFuture(ServiceException.badRequest("Your start date cannot be after the manager left this role (" + formatYM(managerRoleEnd) + ")"));
            if (managerRoleEnd != null && workedUntil != null && workedUntil.isAfter(managerRoleEnd)) return Future.failedFuture(ServiceException.badRequest("Your end date cannot be after the manager left this role (" + formatYM(managerRoleEnd) + ")"));
        }

        if (overallRating == null || ratings == null || isBlank(managerCompany) || isBlank(managerTitle)) return Future.failedFuture(ServiceException.badRequest("Missing required fields"));
        if (!isValidRating(overallRating)) return Future.failedFuture(ServiceException.badRequest("Overall rating must be between 1 and 5"));
        if (managerCompany.length() > 100) return Future.failedFuture(ServiceException.badRequest("Manager company must be at most 100 characters"));
        if (managerTitle.length()   > 100) return Future.failedFuture(ServiceException.badRequest("Manager title must be at most 100 characters"));
        if (text != null && text.length() > 2000) return Future.failedFuture(ServiceException.badRequest("Review text must be at most 2000 characters"));
        for (String key : RATING_KEYS) {
            if (!isValidRating(ratings.getDouble(key))) return Future.failedFuture(ServiceException.badRequest("Rating for '" + key + "' must be between 1 and 5"));
        }

        return userRepo.findByAuth0IdWithBan(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.unauthorized("Unauthorized"));
                Row callerRow = opt.get();
                if (callerRow.getBoolean("is_banned")) return Future.failedFuture(ServiceException.forbidden("account_suspended"));
                UUID callerId = callerRow.getUUID("id");
                String dbUsername = callerRow.getString("username");
                String author = ("real_name".equals(authorType) || "anonymous".equals(authorType))
                    && !clientAuthor.isEmpty() && clientAuthor.length() <= 100
                    ? clientAuthor : dbUsername;

                return reviewRepo.findByUserForValidation(callerId)
                    .compose(existingRows -> {
                        List<Row> existing = new ArrayList<>();
                        existingRows.forEach(existing::add);

                        // ── Role duplicate (exclude current review) ───────────────────────
                        String normTitle   = managerTitle.trim().toLowerCase();
                        String normCompany = managerCompany.trim().toLowerCase();
                        boolean roleTaken = existing.stream()
                            .filter(r -> !r.getUUID("id").equals(reviewId))
                            .filter(r -> r.getLong("manager_id") == managerId)
                            .anyMatch(r -> {
                                String t = r.getString("manager_title");
                                String c = r.getString("manager_company");
                                return t != null && c != null
                                    && t.trim().equalsIgnoreCase(normTitle)
                                    && c.trim().equalsIgnoreCase(normCompany);
                            });
                        if (roleTaken) {
                            return Future.failedFuture(ServiceException.conflict("already_reviewed_this_role"));
                        }

                        // ── Manager role period overlap (exclude current review) ──────────
                        if (managerRoleStart != null) {
                            LocalDate newRoleEnd = managerRoleEnd != null ? managerRoleEnd : LocalDate.of(9999, 12, 31);
                            for (Row r : existing) {
                                if (r.getUUID("id").equals(reviewId)) continue; // skip self
                                if (r.getLong("manager_id") != managerId) continue;
                                LocalDate existRoleStart = r.getLocalDate("manager_role_start");
                                if (existRoleStart == null) continue;
                                LocalDate existRoleEndRaw = r.getLocalDate("manager_role_end");
                                LocalDate existRoleEnd = existRoleEndRaw != null ? existRoleEndRaw : LocalDate.of(9999, 12, 31);
                                boolean overlaps = !managerRoleStart.isAfter(existRoleEnd) && !existRoleStart.isAfter(newRoleEnd);
                                if (overlaps) {
                                    String existTitle   = r.getString("manager_title");
                                    String existCompany = r.getString("manager_company");
                                    return Future.failedFuture(ServiceException.conflict(
                                        "manager_role_overlap:" + existTitle + ":" + existCompany + ":" +
                                        formatYM(existRoleStart) + ":" + (existRoleEndRaw != null ? formatYM(existRoleEndRaw) : "present")));
                                }
                            }
                        }

                        return reviewRepo.update(reviewId, managerId, callerId, author, overallRating,
                                ratings.getDouble("Communication Style"), ratings.getDouble("Perceived Approachability"),
                                ratings.getDouble("Perceived Clarity of Expectations"), ratings.getDouble("Feedback Style"),
                                ratings.getDouble("Perceived Supportiveness"), ratings.getDouble("Decision Making Style"),
                                ratings.getDouble("Organization and Planning Style"), ratings.getDouble("Delegation Style"),
                                ratings.getDouble("Perceived Professional Demeanor"), ratings.getDouble("Overall Working Experience"),
                                managerCompany, managerTitle, text, workedFrom, workedUntil,
                                managerRoleStart, managerRoleEnd)
                            .compose(rowOpt -> {
                                if (rowOpt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Review not found"));
                                managerRepo.recalculateInBackground(managerId);
                                return Future.succeededFuture(rowOpt.get());
                            });
                    });
            });
    }

    // ── DELETE review ─────────────────────────────────────────────────────────

    public Future<JsonObject> deleteReview(String auth0Id, long managerId, UUID reviewId) {
        return userRepo.findByAuth0IdWithBan(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.unauthorized("Unauthorized"));
                Row callerRow = opt.get();
                if (callerRow.getBoolean("is_banned")) return Future.failedFuture(ServiceException.forbidden("account_suspended"));
                UUID userId = callerRow.getUUID("id");
                return reviewRepo.findOwnerUserId(reviewId, managerId)
                    .compose(ownerOpt -> {
                        if (ownerOpt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Review not found"));
                        if (!ownerOpt.get().equals(userId)) return Future.failedFuture(ServiceException.forbidden("Forbidden"));
                        return reviewRepo.delete(reviewId, managerId)
                            .compose(v -> reviewRepo.recordDeletion(userId, managerId))
                            .map(v -> {
                                managerRepo.recalculateInBackground(managerId);
                                return new JsonObject().put("success", true).put("message", "Review deleted");
                            });
                    });
            });
    }

    // ── REPLACE review (delete old + create new, no cooldown recorded) ────────

    public Future<Row> replaceReview(String auth0Id, long managerId, UUID oldReviewId, JsonObject body) {
        return userRepo.findByAuth0IdWithBan(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.unauthorized("Unauthorized"));
                Row userRow = opt.get();
                if (userRow.getBoolean("is_banned")) return Future.failedFuture(ServiceException.forbidden("account_suspended"));
                UUID userId = userRow.getUUID("id");
                String dbUsername = userRow.getString("username");

                String authorType = body.getString("authorType", "username");
                String author;
                if ("real_name".equals(authorType) || "anonymous".equals(authorType)) {
                    String clientAuthor = body.getString("author", "").trim();
                    author = (clientAuthor.isEmpty() || clientAuthor.length() > 100) ? dbUsername : clientAuthor;
                } else {
                    author = dbUsername;
                }

                return reviewRepo.findOwnerUserId(oldReviewId, managerId)
                    .compose(ownerOpt -> {
                        if (ownerOpt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Review not found"));
                        if (!ownerOpt.get().equals(userId)) return Future.failedFuture(ServiceException.forbidden("Forbidden"));
                        // Delete without recording cooldown, then create new review
                        return reviewRepo.delete(oldReviewId, managerId)
                            .compose(v -> validateAndInsertReview(body, managerId, userId, author))
                            .onSuccess(row -> managerRepo.recalculateInBackground(managerId));
                    });
            });
    }

    // ── GET my reviews ────────────────────────────────────────────────────────

    public Future<JsonObject> getMyReviews(String auth0Id) {
        return userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.notFound("User not found"));
                return reviewRepo.findByUser(opt.get());
            })
            .map(rows -> {
                JsonArray data = new JsonArray();
                for (Row row : rows) data.add(buildMyReviewJson(row));
                return new JsonObject().put("data", data);
            });
    }

    // ── Edit requests ─────────────────────────────────────────────────────────

    public Future<JsonObject> createEditRequest(String auth0Id, long managerId, JsonObject body) {
        if (body == null) return Future.failedFuture(ServiceException.badRequest("Missing request body"));
        String newCompany     = body.getString("company");
        String newTitle       = body.getString("title");
        String newStatus      = body.getString("status");
        String newLinkedinUrl = body.getString("linkedinUrl");
        String startDateStr   = body.getString("startDate");
        String endDateStr     = body.getString("endDate");

        if (isBlank(newCompany) && isBlank(newTitle) && isBlank(newStatus) && isBlank(newLinkedinUrl)
                && isBlank(startDateStr) && isBlank(endDateStr)) {
            return Future.failedFuture(ServiceException.badRequest("At least one field is required"));
        }
        if (newCompany    != null && newCompany.length() > 100)    return Future.failedFuture(ServiceException.badRequest("Company must be at most 100 characters"));
        if (newTitle      != null && newTitle.length()   > 100)    return Future.failedFuture(ServiceException.badRequest("Title must be at most 100 characters"));
        if (newStatus     != null && !newStatus.equals("active") && !newStatus.equals("retired")) return Future.failedFuture(ServiceException.badRequest("Status must be 'active' or 'retired'"));
        if (newLinkedinUrl != null && newLinkedinUrl.length() > 500) return Future.failedFuture(ServiceException.badRequest("LinkedIn URL must be at most 500 characters"));

        LocalDate startDateLocal = parseYearMonth(startDateStr);
        LocalDate endDateLocal   = parseYearMonth(endDateStr);
        OffsetDateTime newStartDate = startDateLocal != null ? startDateLocal.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime() : null;
        OffsetDateTime newEndDate   = endDateLocal   != null ? endDateLocal.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()   : null;

        String effectiveCompany     = toNullIfBlank(newCompany);
        String effectiveTitle       = toNullIfBlank(newTitle);
        String effectiveStatus      = toNullIfBlank(newStatus);
        String effectiveLinkedinUrl = toNullIfBlank(newLinkedinUrl);

        return userRepo.findByAuth0IdWithBan(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.failedFuture(ServiceException.unauthorized("User not found"));
                Row userRow = opt.get();
                if (userRow.getBoolean("is_banned")) return Future.failedFuture(ServiceException.forbidden("account_suspended"));
                UUID userId = userRow.getUUID("id");
                return editRepo.countSubmittedTodayByUser(userId)
                    .compose(todayEdits -> {
                        if (todayEdits >= 6) return Future.failedFuture(ServiceException.tooManyRequests("daily_limit_reached"));
                        return managerRepo.findById(managerId)
                            .compose(mgrOpt -> {
                                if (mgrOpt.isEmpty()) return Future.failedFuture(ServiceException.notFound("Manager not found"));
                                return editRepo.upsert(managerId, userId, effectiveCompany, effectiveTitle, effectiveStatus, effectiveLinkedinUrl, newStartDate, newEndDate)
                                    .map(row -> new JsonObject()
                                        .put("id", row.getUUID("id").toString())
                                        .put("managerId", managerId)
                                        .put("newCompany", effectiveCompany)
                                        .put("newTitle", effectiveTitle)
                                        .put("newStatus", effectiveStatus)
                                        .put("newLinkedinUrl", effectiveLinkedinUrl)
                                        .put("status", "pending")
                                        .put("createdAt", row.getOffsetDateTime("created_at").toString())
                                    );
                            });
                    });
            });
    }

    public Future<JsonObject> getPendingEditsForManager(long managerId, String auth0Id) {
        if (auth0Id == null) return Future.succeededFuture(new JsonObject().put("data", new JsonArray()));
        return userRepo.findIdByAuth0Id(auth0Id)
            .compose(opt -> {
                if (opt.isEmpty()) return Future.succeededFuture(new JsonObject().put("data", new JsonArray()));
                return editRepo.findPendingByManagerAndUser(managerId, opt.get())
                    .map(rows -> {
                        JsonArray result = new JsonArray();
                        for (Row row : rows) {
                            result.add(new JsonObject()
                                .put("id", row.getUUID("id").toString())
                                .put("newCompany", row.getString("new_company"))
                                .put("newTitle", row.getString("new_title"))
                                .put("newStatus", row.getString("new_status"))
                                .put("newLinkedinUrl", row.getString("new_linkedin_url"))
                                .put("createdAt", row.getOffsetDateTime("created_at").toString())
                            );
                        }
                        return new JsonObject().put("data", result);
                    });
            });
    }

    // ── JSON builders ─────────────────────────────────────────────────────────

    public static JsonObject buildReviewJson(Row row) {
        JsonObject ratings = new JsonObject()
            .put("Communication Style",               row.getBigDecimal("communication_style"))
            .put("Perceived Approachability",         row.getBigDecimal("perceived_approachability"))
            .put("Perceived Clarity of Expectations", row.getBigDecimal("perceived_clarity_of_expectations"))
            .put("Feedback Style",                    row.getBigDecimal("feedback_style"))
            .put("Perceived Supportiveness",          row.getBigDecimal("perceived_supportiveness"))
            .put("Decision Making Style",             row.getBigDecimal("decision_making_style"))
            .put("Organization and Planning Style",   row.getBigDecimal("organization_and_planning_style"))
            .put("Delegation Style",                  row.getBigDecimal("delegation_style"))
            .put("Perceived Professional Demeanor",   row.getBigDecimal("perceived_professional_demeanor"))
            .put("Overall Working Experience",        row.getBigDecimal("overall_working_experience"));
        return new JsonObject()
            .put("id",            row.getUUID("id"))
            .put("managerId",     row.getLong("manager_id"))
            .put("author",        row.getString("author"))
            .put("overallRating", row.getBigDecimal("overall_rating"))
            .put("ratings",       ratings)
            .put("managerCompany", row.getString("manager_company"))
            .put("managerTitle",  row.getString("manager_title"))
            .put("text",          row.getString("text"))
            .put("verified",      row.getBoolean("verified"))
            .put("helpfulCount",  row.getInteger("helpful_count"))
            .put("createdAt",     row.getOffsetDateTime("created_at").toString())
            .put("updatedAt",     row.getOffsetDateTime("updated_at").toString())
            .put("workedFrom",    row.getLocalDate("worked_from")  != null ? row.getLocalDate("worked_from").toString()  : null)
            .put("workedUntil",   row.getLocalDate("worked_until") != null ? row.getLocalDate("worked_until").toString() : null);
    }

    private JsonObject buildMyReviewJson(Row row) {
        JsonObject ratings = new JsonObject()
            .put("Communication Style",               row.getBigDecimal("communication_style"))
            .put("Perceived Approachability",         row.getBigDecimal("perceived_approachability"))
            .put("Perceived Clarity of Expectations", row.getBigDecimal("perceived_clarity_of_expectations"))
            .put("Feedback Style",                    row.getBigDecimal("feedback_style"))
            .put("Perceived Supportiveness",          row.getBigDecimal("perceived_supportiveness"))
            .put("Decision Making Style",             row.getBigDecimal("decision_making_style"))
            .put("Organization and Planning Style",   row.getBigDecimal("organization_and_planning_style"))
            .put("Delegation Style",                  row.getBigDecimal("delegation_style"))
            .put("Perceived Professional Demeanor",   row.getBigDecimal("perceived_professional_demeanor"))
            .put("Overall Working Experience",        row.getBigDecimal("overall_working_experience"));
        return new JsonObject()
            .put("id",            row.getUUID("id").toString())
            .put("managerId",     row.getLong("manager_id"))
            .put("managerName",   row.getString("manager_name"))
            .put("managerImage",  row.getString("manager_image"))
            .put("managerStatus", row.getString("manager_status"))
            .put("author",        row.getString("author"))
            .put("overallRating", row.getBigDecimal("overall_rating"))
            .put("ratings",       ratings)
            .put("managerCompany", row.getString("manager_company"))
            .put("managerTitle",  row.getString("manager_title"))
            .put("text",          row.getString("text"))
            .put("verified",      row.getBoolean("verified"))
            .put("helpfulCount",  row.getInteger("helpful_count"))
            .put("createdAt",     row.getOffsetDateTime("created_at").toString())
            .put("updatedAt",     row.getOffsetDateTime("updated_at").toString())
            .put("workedFrom",         row.getLocalDate("worked_from")        != null ? row.getLocalDate("worked_from").toString()        : null)
            .put("workedUntil",        row.getLocalDate("worked_until")       != null ? row.getLocalDate("worked_until").toString()       : null)
            .put("managerRoleStart",   row.getLocalDate("manager_role_start") != null ? row.getLocalDate("manager_role_start").toString() : null)
            .put("managerRoleEnd",     row.getLocalDate("manager_role_end")   != null ? row.getLocalDate("manager_role_end").toString()   : null);
    }

    private JsonObject buildManagerUpdateJson(Row row, RowSet<Row> chRows) {
        JsonArray careerHistory = new JsonArray();
        for (Row r : chRows) {
            careerHistory.add(new JsonObject()
                .put("company",   r.getString("company"))
                .put("title",     r.getString("title"))
                .put("startDate", r.getOffsetDateTime("start_date").toString())
                .put("endDate",   r.getOffsetDateTime("end_date") != null ? r.getOffsetDateTime("end_date").toString() : null)
            );
        }
        return new JsonObject()
            .put("id",             row.getLong("id"))
            .put("name",           row.getString("name"))
            .put("company",        row.getString("company"))
            .put("title",          row.getString("title"))
            .put("image",          row.getString("image"))
            .put("overallRating",  row.getBigDecimal("overall_rating"))
            .put("reviews",        row.getInteger("reviews_count"))
            .put("bio",            row.getString("bio"))
            .put("status",         row.getString("status"))
            .put("approvalStatus", row.getString("approval_status"))
            .put("categoryAverages", row.getJsonObject("category_averages"))
            .put("linkedinUrl",    row.getString("linkedin_url"))
            .put("createdAt",      row.getOffsetDateTime("created_at").toString())
            .put("careerHistory",  careerHistory);
    }

    // ── Validation helpers ────────────────────────────────────────────────────

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static boolean isValidRating(Double v) { return v != null && v >= 1 && v <= 5; }

    private static boolean isValidLinkedinUrl(String url) {
        return url != null && (url.startsWith("https://www.linkedin.com/") || url.startsWith("https://linkedin.com/"));
    }

    private static String formatYM(LocalDate d) {
        return d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " + d.getYear();
    }

    private static LocalDate parseYearMonth(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            return LocalDate.parse(str + "-01", DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String toNullIfBlank(String s) {
        return (s != null && !s.isBlank()) ? s.trim() : null;
    }

    /** Returns the rating value, trying the pretty key first then the snake_case fallback. */
    private static double getRating(JsonObject ratings, int index) {
        Double v = ratings.getDouble(RATING_KEYS[index]);
        if (v == null) v = ratings.getDouble(RATING_KEYS_SNAKE[index]);
        return v != null ? v : 0.0;
    }
}
