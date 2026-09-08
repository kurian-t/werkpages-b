package org.werkpages.rest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.werkpages.config.SecretsConfig;
import org.werkpages.db.Database;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.NotificationRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.InterviewRepository;
import org.werkpages.repository.RoleAliasRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.ProofChallengeRepository;
import org.werkpages.repository.ConfidenceRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.rest.handlers.AdminHandler;
import org.werkpages.rest.handlers.AuthHandler;
import org.werkpages.rest.handlers.ManagersHandler;
import org.werkpages.rest.handlers.NotificationsHandler;
import org.werkpages.rest.handlers.RateLimitHandler;
import org.werkpages.rest.handlers.ReportsHandler;
import org.werkpages.rest.handlers.ResumesHandler;
import org.werkpages.rest.handlers.IndustriesHandler;
import org.werkpages.rest.handlers.InterviewsHandler;
import org.werkpages.rest.handlers.CompanyRatingsHandler;
import org.werkpages.rest.handlers.ProofChallengesHandler;
import org.werkpages.service.AdminService;
import org.werkpages.service.AnthropicClient;
import org.werkpages.rest.handlers.CompanyLogoUtils;
import org.werkpages.service.DeduplicationJob;
import org.werkpages.service.EncryptionService;
import org.werkpages.service.ManagerService;
import org.werkpages.service.NotificationService;
import org.werkpages.service.ReportService;
import org.werkpages.service.ResumeService;
import org.werkpages.service.IndustryService;
import org.werkpages.service.InterviewService;
import org.werkpages.service.ProofChallengeService;
import org.werkpages.service.RoleService;
import org.werkpages.service.IndustryClassificationJob;
import org.werkpages.service.SitemapService;
import org.werkpages.repository.MergeSuggestionsRepository;
import org.werkpages.repository.ResumeRepository;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.api.contract.openapi3.OpenAPI3RouterFactory;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;

public class MainVerticle extends AbstractVerticle {

    // Loaded once at startup from AWS Secrets Manager
    private static SecretsConfig secrets;

    /**
     * Multiplier applied to every rate limit, from RATE_LIMIT_MULTIPLIER. Defaults to 1 so
     * production is unchanged, and refuses values below 1 so this can only ever loosen limits
     * locally, never tighten them by accident.
     */
    private static int rateLimitMultiplier() {
        String raw = System.getenv("RATE_LIMIT_MULTIPLIER");
        if (raw == null || raw.isBlank()) return 1;
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            System.err.println("⚠ RATE_LIMIT_MULTIPLIER is not a number: " + raw + " — using 1");
            return 1;
        }
    }

    public static void main(String[] args) {
        // Load secrets BEFORE Vert.x starts — intentionally blocking
        secrets = SecretsConfig.load();

        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new MainVerticle());
    }

    @SuppressWarnings("deprecation")
    @Override
    public void start() {
        WebClient client = WebClient.create(vertx);
        String jwksUrl = "https://" + secrets.effectiveAuthDomain() + "/.well-known/jwks.json";

        client.getAbs(jwksUrl).timeout(10_000).send(ar -> {
            if (ar.succeeded()) {
                JsonObject responseBody = ar.result().bodyAsJsonObject();

                List<JsonObject> keys = responseBody.getJsonArray("keys")
                    .stream()
                    .map(obj -> JsonObject.mapFrom(obj))
                    .toList();

                JWTAuth jwtAuth = JwtAuthFactory.create(vertx, keys, secrets.auth0Audience);

                OpenAPI3RouterFactory.create(vertx, "openapi.yaml", routerFactoryAr -> {
                    if (routerFactoryAr.succeeded()) {
                        OpenAPI3RouterFactory routerFactory = routerFactoryAr.result();

                        Database.init(vertx, secrets, () -> {

                        // ── Encryption ────────────────────────────────────────────────────────
                        EncryptionService enc = (secrets.encryptionKey != null && secrets.hmacKey != null)
                            ? EncryptionService.from(secrets.encryptionKey, secrets.hmacKey)
                            : null;

                        // ── Repositories ──────────────────────────────────────────────────────
                        UserRepository         userRepo    = new UserRepository(Database.getClient(), enc);
                        ManagerRepository      managerRepo = new ManagerRepository(Database.getClient());
                        ReviewRepository       reviewRepo  = new ReviewRepository(Database.getClient());
                        NotificationRepository notifRepo   = new NotificationRepository(Database.getClient());
                        ReportRepository       reportRepo  = new ReportRepository(Database.getClient());
                        EditRepository         editRepo    = new EditRepository(Database.getClient());
                        CompanyRepository      companyRepo = new CompanyRepository(Database.getClient());
                        ResumeRepository       resumeRepo  = new ResumeRepository(Database.getClient());
                        InterviewRepository    interviewRepo = new InterviewRepository(Database.getClient());
                        ProofChallengeRepository proofChallengeRepo = new ProofChallengeRepository(Database.getClient());
                        ConfidenceRepository   confidenceRepo = new ConfidenceRepository(Database.getClient());
                        RoleAliasRepository    roleAliasRepo = new RoleAliasRepository(Database.getClient());

                        // ── Services ──────────────────────────────────────────────────────────
                        MergeSuggestionsRepository mergeSuggestionsRepo = new MergeSuggestionsRepository(Database.getClient());
                        ManagerService      managerService = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, companyRepo, Database.getClient(), CompanyLogoUtils::resolveLogoUrl);
                        AdminService        adminService   = new AdminService(userRepo, managerRepo, reviewRepo, editRepo, notifRepo, companyRepo, mergeSuggestionsRepo, Database.getClient());
                        NotificationService notifService   = new NotificationService(userRepo, notifRepo);
                        ReportService       reportService  = new ReportService(userRepo, reportRepo);
                        ResumeService       resumeService  = new ResumeService(userRepo, resumeRepo, companyRepo);
                        IndustryService     industryService = new IndustryService(companyRepo, CompanyLogoUtils::resolveLogoUrl);
                        InterviewService    interviewService = new InterviewService(interviewRepo, companyRepo, userRepo);
                        org.werkpages.repository.CompanyReviewRepository companyReviewRepo =
                            new org.werkpages.repository.CompanyReviewRepository(Database.getClient());
                        org.werkpages.service.CompanyReviewService companyReviewService =
                            new org.werkpages.service.CompanyReviewService(companyReviewRepo, companyRepo, userRepo);
                        RoleService         roleService      = new RoleService(roleAliasRepo);

                        // ── Sitemap ───────────────────────────────────────────────────────────
                        SitemapService sitemapService = new SitemapService(Database.getClient());

                        // ── Soft-delete restore job (runs daily) ──────────────────────────────
                        final CompanyRepository companyRepoForWeights = companyRepo;
                        vertx.setPeriodic(86_400_000L, timerId -> {
                            reviewRepo.restoreExpiredDeletions()
                                .onSuccess(n -> { if (n > 0) System.out.println("✓ Restored " + n + " anonymised review(s)"); })
                                .onFailure(err -> System.err.println("⚠ Review restore job failed: " + err.getMessage()));
                            interviewRepo.restoreExpiredDeletions()
                                .onSuccess(n -> { if (n > 0) System.out.println("✓ Restored " + n + " anonymised interview review(s)"); })
                                .onFailure(err -> System.err.println("⚠ Interview restore job failed: " + err.getMessage()));

                            // ── Proof challenges nobody answered ──────────────────────────
                            //
                            // Abandoning is not a way out, so an untouched challenge ages into
                            // 'abandoned' — which still flags its author and still sits in the
                            // admin queue, because a flag nobody can lift is a lock with no key.
                            //
                            // Re-running this cannot double-charge anyone: the debit is keyed on
                            // the challenge id in user_confidence_events, and that uniqueness is a
                            // constraint rather than this loop being careful.
                            proofChallengeRepo.markAbandoned()
                                .onSuccess(rows -> {
                                    for (io.vertx.sqlclient.Row r : rows) {
                                        confidenceRepo.apply(r.getUUID("user_id"),
                                                ConfidenceRepository.CHALLENGE_ABANDONED, -15,
                                                "challenge", r.getUUID("id").toString())
                                            .onFailure(err -> System.err.println(
                                                "⚠ Abandonment debit failed: " + err.getMessage()));
                                    }
                                    if (rows.rowCount() > 0)
                                        System.out.println("✓ Aged out " + rows.rowCount() + " unanswered challenge(s)");
                                })
                                .onFailure(err -> System.err.println("⚠ Challenge sweep failed: " + err.getMessage()));

                            // ── Standing credit ───────────────────────────────────────────
                            //
                            // Thirty days continuously live, not thirty days since it was written:
                            // a rating held for twenty-nine days and approved yesterday has stood
                            // for a day. The query reads live_since, which resets whenever a
                            // rating re-enters the live state.
                            confidenceRepo.findReviewsDueStandingCredit(500)
                                .onSuccess(rows -> {
                                    for (io.vertx.sqlclient.Row r : rows) {
                                        confidenceRepo.apply(r.getUUID("user_id"),
                                                ConfidenceRepository.REVIEW_STOOD_30D, 2,
                                                "review", r.getUUID("id").toString())
                                            .onFailure(err -> System.err.println(
                                                "⚠ Standing credit failed: " + err.getMessage()));
                                    }
                                })
                                .onFailure(err -> System.err.println("⚠ Standing credit sweep failed: " + err.getMessage()));

                            // ── Expired placeholder weights ───────────────────────────────
                            //
                            // A placeholder review stops counting 14 days after it is written, but
                            // that happens because a date passes, not because anything writes. No
                            // request fires, so nothing recalculated the manager, and the cached
                            // rating and review count stayed at yesterday's numbers indefinitely
                            // while the reviews list had already stopped showing those reviews.
                            //
                            // This closes that gap once a day. The query returns only managers
                            // whose cached count actually disagrees with reality, so on a normal
                            // day it finds nothing and does nothing.
                            managerRepo.findManagersWithExpiredWeights()
                                .compose(rows -> {
                                    io.vertx.core.Future<Void> chain = io.vertx.core.Future.succeededFuture();
                                    int stale = 0;
                                    for (io.vertx.sqlclient.Row row : rows) {
                                        long managerId = row.getLong("id");
                                        stale++;
                                        chain = chain
                                            .compose(v -> managerRepo.recalculate(managerId))
                                            .compose(v -> companyRepoForWeights.syncStatsForManager(managerId));
                                    }
                                    final int total = stale;
                                    return chain.map(v -> total);
                                })
                                .onSuccess(n -> { if (n > 0) System.out.println("✓ Recalculated " + n + " manager(s) whose placeholder reviews expired"); })
                                .onFailure(err -> System.err.println("⚠ Expired-weight recalculation failed: " + err.getMessage()));
                        });

                        // ── company_stats matview refresh (safety net — primary updates go through
                        //    updateCompanyStatsForManager/Company on each mutation) ──────────────
                        final CompanyRepository companyRepoForScheduler = companyRepo;
                        final AtomicBoolean statsRefreshRunning = new AtomicBoolean(false);
                        vertx.setPeriodic(6 * 3_600_000L, timerId -> {
                            if (statsRefreshRunning.compareAndSet(false, true)) {
                                companyRepoForScheduler.refreshCompanyStats()
                                    .onSuccess(v -> System.out.println("✓ company_stats matview refreshed"))
                                    .onFailure(err -> System.err.println("⚠ company_stats matview refresh failed: " + err.getMessage()))
                                    .onComplete(ignored -> statsRefreshRunning.set(false));
                            }
                        });

                        // ── AI jobs (deduplication + industry classification) ──────────────────
                        DeduplicationJob          deduplicationJob;
                        IndustryClassificationJob industryJob;
                        if (secrets.anthropicApiKey != null && !secrets.anthropicApiKey.isBlank()) {
                            AnthropicClient anthropicClient = new AnthropicClient(vertx, secrets.anthropicApiKey);
                            deduplicationJob = new DeduplicationJob(mergeSuggestionsRepo, anthropicClient);
                            industryJob      = new IndustryClassificationJob(companyRepo, anthropicClient);
                            companyRepo.setClassifier(anthropicClient); // classify each new company on creation
                            System.out.println("✓ AI jobs ready (dedup: POST /api/admin/deduplication/run, industries: POST /api/admin/industries/classify)");
                        } else {
                            deduplicationJob = null;
                            industryJob      = null;
                            System.out.println("⚠ ANTHROPIC_API_KEY not set — AI dedup + industry classification disabled");
                        }

                        // ── Handlers ──────────────────────────────────────────────────────────
                        ManagersHandler      managersHandler      = new ManagersHandler(managerService, vertx, jwtAuth);
                        ReportsHandler       reportsHandler       = new ReportsHandler(reportService);
                        AdminHandler         adminHandler         = new AdminHandler(adminService, deduplicationJob, industryJob, roleService);
                        NotificationsHandler notificationsHandler = new NotificationsHandler(notifService);
                        ResumesHandler       resumesHandler       = new ResumesHandler(resumeService);
                        IndustriesHandler    industriesHandler    = new IndustriesHandler(industryService);
                        InterviewsHandler    interviewsHandler    = new InterviewsHandler(interviewService, jwtAuth);
                        CompanyRatingsHandler companyRatingsHandler = new CompanyRatingsHandler(companyReviewService);
                        ProofChallengeService proofChallengeService = new ProofChallengeService(proofChallengeRepo, userRepo);
                        ProofChallengesHandler proofChallengesHandler = new ProofChallengesHandler(proofChallengeService, adminService);
                        // The profile endpoint reports the workplace rating beside the manager one.
                        managerService.setCompanyReviewRepo(companyReviewRepo);

                        routerFactory.addHandlerByOperationId("getManagers",           managersHandler::handleGetManagers);
                        routerFactory.addHandlerByOperationId("getManagerById",        managersHandler::handleGetManagerById);
                        routerFactory.addHandlerByOperationId("createManager",         managersHandler::handleCreateManager);
                        routerFactory.addHandlerByOperationId("updateManager",         managersHandler::handleUpdateManager);
                        routerFactory.addHandlerByOperationId("createManagerReview",   managersHandler::handleCreateManagerReview);
                        routerFactory.addHandlerByOperationId("getManagerReviews",         managersHandler::handleGetManagerReviews);
                        routerFactory.addHandlerByOperationId("getManagerCareerSegments", managersHandler::handleGetManagerCareerSegments);
                        routerFactory.addHandlerByOperationId("updateManagerReview",   managersHandler::handleUpdateReview);
                        routerFactory.addHandlerByOperationId("deleteManagerReview",   managersHandler::handleDeleteManagerReview);
                        routerFactory.addHandlerByOperationId("replaceManagerReview",  managersHandler::handleReplaceManagerReview);
                        routerFactory.addHandlerByOperationId("getMyReviews",          managersHandler::handleGetMyReviews);
                        routerFactory.addHandlerByOperationId("getMySubmittedManagers", managersHandler::handleGetMySubmittedManagers);
                        routerFactory.addHandlerByOperationId("hasContributed",         managersHandler::handleHasContributed);
                        routerFactory.addHandlerByOperationId("reportManager",         reportsHandler::handleReportManager);
                        routerFactory.addHandlerByOperationId("getStats",               managersHandler::handleGetStats);
                        routerFactory.addHandlerByOperationId("getCompanyListing",      managersHandler::handleGetCompanyListing);
                        routerFactory.addHandlerByOperationId("getCompanyProfile",      managersHandler::handleGetCompanyProfile);
                        routerFactory.addHandlerByOperationId("getCompanyBySlug",       managersHandler::handleGetCompanyBySlug);
                        routerFactory.addHandlerByOperationId("getIndustryListing",     industriesHandler::handleGetIndustryListing);
                        routerFactory.addHandlerByOperationId("getIndustryProfile",     industriesHandler::handleGetIndustryProfile);
                        routerFactory.addHandlerByOperationId("getCompanyInterviews",   interviewsHandler::handleGetCompanyInterviews);
                        routerFactory.addHandlerByOperationId("createInterviewReview",  interviewsHandler::handleCreateInterviewReview);
                        routerFactory.addHandlerByOperationId("deleteInterviewReview",  interviewsHandler::handleDeleteInterviewReview);
                        routerFactory.addHandlerByOperationId("updateInterviewReview",  interviewsHandler::handleUpdateInterviewReview);
                        routerFactory.addHandlerByOperationId("hasInterviewContributed", interviewsHandler::handleHasInterviewContributed);
                        routerFactory.addHandlerByOperationId("getIndustryInterviewAverages", interviewsHandler::handleGetIndustryInterviewAverages);

                        // Company ratings. Every operationId in the spec must be routed or the
                        // router factory refuses to boot, which is why these three go in together.
                        routerFactory.addHandlerByOperationId("submitCompanyRating", companyRatingsHandler::handleSubmit);
                        routerFactory.addHandlerByOperationId("getMyCompanyRating",  companyRatingsHandler::handleGetMine);

                        // Proof of work. Every operationId in the spec must be routed here or
                        // OpenAPI3RouterFactory refuses to start, which is the behaviour that
                        // keeps the spec and the server from drifting apart.
                        routerFactory.addHandlerByOperationId("getMyProofChallenge",          proofChallengesHandler::handleGetMine);
                        routerFactory.addHandlerByOperationId("submitProofEvidence",          proofChallengesHandler::handleSubmitEvidence);
                        routerFactory.addHandlerByOperationId("adminGetProofChallenges",      proofChallengesHandler::handleAdminList);
                        routerFactory.addHandlerByOperationId("adminResolveProofChallenge",   proofChallengesHandler::handleAdminResolve);
                        routerFactory.addHandlerByOperationId("adminListHighProfileFigures",  proofChallengesHandler::handleListFigures);
                        routerFactory.addHandlerByOperationId("adminAddHighProfileFigure",    proofChallengesHandler::handleAddFigure);
                        routerFactory.addHandlerByOperationId("adminRemoveHighProfileFigure", proofChallengesHandler::handleRemoveFigure);
                        routerFactory.addHandlerByOperationId("deleteCompanyRating", companyRatingsHandler::handleDelete);
                        routerFactory.addHandlerByOperationId("getManagerBySlug",       managersHandler::handleGetManagerBySlug);
                        routerFactory.addHandlerByOperationId("getCompanies",           managersHandler::handleGetCompanies);
                        routerFactory.addHandlerByOperationId("createCompany",           managersHandler::handleCreateCompany);
                        routerFactory.addHandlerByOperationId("suggestCompanies",       managersHandler::handleSuggestCompanies);
                        routerFactory.addHandlerByOperationId("getGeo",                 managersHandler::handleGetGeo);
                        routerFactory.addHandlerByOperationId("getSimilarManagers",     managersHandler::handleGetSimilarManagers);
                        routerFactory.addHandlerByOperationId("findOrCreateManager",    managersHandler::handleFindOrCreate);
                        routerFactory.addHandlerByOperationId("createGhostManager",     managersHandler::handleCreateGhostManager);
                        routerFactory.addHandlerByOperationId("captureAnonymousSearch", managersHandler::handleAnonymousCapture);
                        routerFactory.addHandlerByOperationId("createDropOffDraft",      managersHandler::handleDropOffDraft);
                        routerFactory.addHandlerByOperationId("createDropOffReview",     managersHandler::handleDropOffReview);
                        routerFactory.addHandlerByOperationId("createManagerEditRequest", managersHandler::handleCreateEditRequest);
                        routerFactory.addHandlerByOperationId("getManagerPendingEdits",   managersHandler::handleGetPendingEditsForManager);
                        routerFactory.addHandlerByOperationId("getAdminPendingEdits",     adminHandler::handleGetPendingEdits);
                        routerFactory.addHandlerByOperationId("approveManagerEdit",       adminHandler::handleApproveEdit);
                        routerFactory.addHandlerByOperationId("rejectManagerEdit",        adminHandler::handleRejectEdit);
                        routerFactory.addHandlerByOperationId("getAdminUsers",            adminHandler::handleGetUsers);
                        routerFactory.addHandlerByOperationId("getAdminBannedUsers",      adminHandler::handleGetBannedUsers);
                        routerFactory.addHandlerByOperationId("banUser",                  adminHandler::handleBanUser);
                        routerFactory.addHandlerByOperationId("unbanUser",                adminHandler::handleUnbanUser);
                        routerFactory.addHandlerByOperationId("getAdminGhostManagers",    adminHandler::handleGetGhostManagers);
                        routerFactory.addHandlerByOperationId("markGhostManagerReviewed", adminHandler::handleMarkGhostReviewed);
                        routerFactory.addHandlerByOperationId("getAdminPendingManagers",  adminHandler::handleGetPendingManagers);
                        routerFactory.addHandlerByOperationId("approvePendingManager",    adminHandler::handleApprovePendingManager);
                        routerFactory.addHandlerByOperationId("rejectPendingManager",     adminHandler::handleRejectPendingManager);
                        routerFactory.addHandlerByOperationId("adminEditManager",          adminHandler::handleAdminEditManager);
                        routerFactory.addHandlerByOperationId("adminDeleteManager",       adminHandler::handleDeleteManager);
                        routerFactory.addHandlerByOperationId("mergeManagers",            adminHandler::handleMergeManagers);
                        routerFactory.addHandlerByOperationId("adminListCompanies",       adminHandler::handleListCompanies);
                        routerFactory.addHandlerByOperationId("adminRenameCompany",       adminHandler::handleRenameCompany);
                        routerFactory.addHandlerByOperationId("adminMergeCompanies",      adminHandler::handleMergeCompanies);
                        routerFactory.addHandlerByOperationId("previewCompanyMerge",      adminHandler::handlePreviewCompanyMerge);
                        routerFactory.addHandlerByOperationId("undoCompanyMerge",         adminHandler::handleUndoCompanyMerge);
                        routerFactory.addHandlerByOperationId("setCompanyParent",         adminHandler::handleSetCompanyParent);
                        routerFactory.addHandlerByOperationId("removeCompanyParent",      adminHandler::handleRemoveCompanyParent);
                        routerFactory.addHandlerByOperationId("getMergeSuggestions",      adminHandler::handleGetMergeSuggestions);
                        routerFactory.addHandlerByOperationId("dismissMergeSuggestion",   adminHandler::handleDismissMergeSuggestion);
                        routerFactory.addHandlerByOperationId("triggerDeduplication",     adminHandler::handleTriggerDeduplication);
                        routerFactory.addHandlerByOperationId("classifyIndustries",       adminHandler::handleClassifyIndustries);
                        routerFactory.addHandlerByOperationId("suggestRoles",             adminHandler::handleSuggestRoles);
                        routerFactory.addHandlerByOperationId("listRoleAliases",          adminHandler::handleListRoleAliases);
                        routerFactory.addHandlerByOperationId("classifyRoles",            adminHandler::handleClassifyRoles);
                        routerFactory.addHandlerByOperationId("setRoleAlias",             adminHandler::handleSetRoleAlias);
                        routerFactory.addHandlerByOperationId("adminUpdateCareerEntry",   adminHandler::handleUpdateCareerEntry);
                        routerFactory.addHandlerByOperationId("adminDeleteCareerEntry",   adminHandler::handleDeleteCareerEntry);
                        routerFactory.addHandlerByOperationId("getAdminCountryStats",     adminHandler::handleGetCountryStats);
                        routerFactory.addHandlerByOperationId("getMyResume",         resumesHandler::handleGetResume);
                        routerFactory.addHandlerByOperationId("saveMyResume",        resumesHandler::handleSaveResume);
                        routerFactory.addHandlerByOperationId("getResumePrefill",    resumesHandler::handleGetPrefill);
                        routerFactory.addHandlerByOperationId("getNotifications",             notificationsHandler::handleGetNotifications);
                        routerFactory.addHandlerByOperationId("getNotificationsUnreadCount",  notificationsHandler::handleGetUnreadCount);
                        routerFactory.addHandlerByOperationId("markAllNotificationsRead",     notificationsHandler::handleMarkAllAsRead);
                        routerFactory.addHandlerByOperationId("markNotificationRead",         notificationsHandler::handleMarkAsRead);
                        
                        
                        AuthHandler authHandler = new AuthHandler(
                            userRepo,
                            secrets.auth0Domain,          // original tenant domain — /dbconnections/signup and /oauth/token must use this, not the custom domain
                            secrets.auth0ClientId,
                            secrets.auth0ClientSecret,
                            secrets.auth0Audience,
                            secrets.turnstileSecretKey,
                            vertx
                        );
                        routerFactory.addHandlerByOperationId("checkUsername",     authHandler::handleCheckUsername);
                        routerFactory.addHandlerByOperationId("signup",            authHandler::handleSignup);
                        routerFactory.addHandlerByOperationId("signin",            authHandler::handleSignin);
                        routerFactory.addHandlerByOperationId("socialAuthCallback",authHandler::handleCallback);
                        routerFactory.addHandlerByOperationId("me",                authHandler::handleMe);
                        routerFactory.addHandlerByOperationId("signout",           authHandler::handleSignout);
                        routerFactory.addHandlerByOperationId("deleteMe",          authHandler::handleDeleteMe);
                        
                        routerFactory.addSecurityHandler("bearerAuth", routingContext -> {
                            // Cron job authentication via X-Cron-Secret header (constant-time compare)
                            String cronHeader = routingContext.request().getHeader("X-Cron-Secret");
                            if (secrets.cronSecret != null && cronHeader != null) {
                                byte[] expected = secrets.cronSecret.getBytes(StandardCharsets.UTF_8);
                                byte[] received = cronHeader.getBytes(StandardCharsets.UTF_8);
                                if (MessageDigest.isEqual(expected, received)) {
                                    routingContext.put("auth0Id", "cron");
                                    routingContext.next();
                                    return;
                                }
                            }

                            // Accept token from Authorization header OR HttpOnly cookie
                            String token = null;
                            String authHeader = routingContext.request().getHeader("Authorization");
                            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                                token = authHeader.substring(7);
                            } else {
                                String cookieHeader = routingContext.request().getHeader("Cookie");
                                if (cookieHeader != null) {
                                    for (String part : cookieHeader.split(";")) {
                                        String trimmed = part.trim();
                                        if (trimmed.startsWith("auth_token=")) {
                                            token = trimmed.substring("auth_token=".length());
                                            break;
                                        }
                                    }
                                }
                            }
                            if (token == null) {
                                routingContext.fail(401);
                                return;
                            }
                            final String finalToken = token;
                            jwtAuth.authenticate(new JsonObject().put("token", finalToken), res -> {
                                if (res.succeeded()) {
                                    routingContext.setUser(res.result());
                                    try {
                                        com.auth0.jwt.interfaces.DecodedJWT decoded = com.auth0.jwt.JWT.decode(finalToken);
                                        routingContext.put("auth0Id", decoded.getSubject());
                                    } catch (Exception ignored) {}
                                    routingContext.next();
                                } else {
                                    routingContext.fail(401);
                                }
                            });
                        });

                        Router apiRouter = routerFactory.getRouter();
                        Router router    = Router.router(vertx);

                        // 64 KB body size limit — applied before the API sub-router
                        router.route().handler(BodyHandler.create().setBodyLimit(65_536L));

                        Set<String> allowedHeaders = new HashSet<>();
                        allowedHeaders.add("Authorization");
                        allowedHeaders.add("Content-Type");
                        allowedHeaders.add("Accept");
                        allowedHeaders.add("Cookie");

                        Set<HttpMethod> allowedMethods = new HashSet<>();
                        allowedMethods.add(HttpMethod.GET);
                        allowedMethods.add(HttpMethod.POST);
                        allowedMethods.add(HttpMethod.PUT);
                        allowedMethods.add(HttpMethod.DELETE);
                        allowedMethods.add(HttpMethod.OPTIONS);

                        // CORS — updated to production domain
                        // Dev origin is 8081 — it must track the Vite dev server port in
                        // werkpages/vite.config.ts, which moved off 8080 so RateMyManagers'
                        // frontend can run alongside this one.
                        String allowedOrigin = org.werkpages.config.AppEnv.current().isProduction()
                        	    ? "https://werkpages\\.com|https://www\\.werkpages\\.com"
                        	    : "http://localhost:8081";
                        
                        router.route().handler(
                            CorsHandler.create(allowedOrigin)
                                .allowedHeaders(allowedHeaders)
                                .allowedMethods(allowedMethods)
                                .allowCredentials(true)
                        );

                        // Rate limiting — applied in order before the API sub-router
                        // Production values. Locally every browser tab, curl and Playwright run
                        // shares 127.0.0.1, so a test suite alone exhausts the global budget and
                        // the app starts 429ing mid-browse — indistinguishable from a real outage.
                        // RATE_LIMIT_MULTIPLIER scales all three for local dev; unset means 1x.
                        int rateMultiplier = rateLimitMultiplier();
                        RateLimitHandler globalLimiter = new RateLimitHandler(200 * rateMultiplier, 60_000); // 200 req/min per IP
                        RateLimitHandler authLimiter   = new RateLimitHandler(10  * rateMultiplier, 60_000); // 10 req/min  per IP on auth mutations
                        RateLimitHandler writeLimiter  = new RateLimitHandler(30  * rateMultiplier, 60_000); // 30 req/min  per IP on writes

                        router.route().handler(globalLimiter::handle);
                        // Only apply the strict auth limiter to mutation endpoints (signin/signup/signout).
                        // GET /api/auth/me is a lightweight session check — leave it on the global limiter only.
                        router.post("/api/auth/*").handler(authLimiter::handle);
                        router.post("/api/*").handler(writeLimiter::handle);
                        router.put("/api/*").handler(writeLimiter::handle);
                        router.delete("/api/*").handler(writeLimiter::handle);

                        // Security headers
                        router.route().handler(secCtx -> {
                            secCtx.response()
                                .putHeader("X-Content-Type-Options", "nosniff")
                                .putHeader("X-Frame-Options", "DENY")
                                .putHeader("X-XSS-Protection", "0")
                                .putHeader("Referrer-Policy", "strict-origin-when-cross-origin");
                            boolean isProd = org.werkpages.config.AppEnv.current().isProduction();
                            if (isProd) {
                                secCtx.response().putHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
                            }
                            secCtx.next();
                        });

                        router.errorHandler(500, ctx -> {
                            Throwable t = ctx.failure();
                            if (t != null) {
                                System.err.println("Unhandled 500 on " + ctx.request().method() + " " + ctx.request().path());
                                t.printStackTrace(System.err);
                            }
                            if (!ctx.response().ended()) {
                                ctx.response().setStatusCode(500).putHeader("Content-Type", "application/json")
                                    .end("{\"error\":\"internal_error\",\"message\":\"An unexpected error occurred.\"}");
                            }
                        });

                        /*
                          Optional authentication.

                          Most read endpoints declare `security: []` so anonymous visitors can read
                          the site, which also means the bearerAuth handler never runs on them and
                          nothing knows who is asking - even when a signed-in person is asking.
                          That was fine while every rating was public. It stopped being fine once a
                          rating could be withheld: the author of a held rating saw an empty page
                          where their own contribution should be, and an admin had to moderate by
                          reading the queue and the profile side by side.

                          So: verify a token when one is offered, identify the caller, and never
                          require it. The token is verified, not merely decoded - trusting an
                          unverified subject here would let anyone claim to be an admin by editing
                          a cookie.
                         */
                        router.route("/api/*").handler(routingContext -> {
                            if (routingContext.get("auth0Id") != null) { routingContext.next(); return; }
                            String token = null;
                            String authHeader = routingContext.request().getHeader("Authorization");
                            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                                token = authHeader.substring(7);
                            } else {
                                String cookieHeader = routingContext.request().getHeader("Cookie");
                                if (cookieHeader != null) {
                                    for (String part : cookieHeader.split(";")) {
                                        String trimmed = part.trim();
                                        if (trimmed.startsWith("auth_token=")) {
                                            token = trimmed.substring("auth_token=".length());
                                            break;
                                        }
                                    }
                                }
                            }
                            if (token == null) { routingContext.next(); return; }
                            final String finalToken = token;
                            jwtAuth.authenticate(new JsonObject().put("token", finalToken), res -> {
                                if (res.succeeded()) {
                                    try {
                                        routingContext.put("auth0Id",
                                            com.auth0.jwt.JWT.decode(finalToken).getSubject());
                                    } catch (Exception ignored) {}
                                }
                                // A bad or expired token is not an error on a public route. It just
                                // means we carry on not knowing who this is.
                                routingContext.next();
                            });
                        });

                        router.mountSubRouter("/", apiRouter);

                        boolean isProdEnv = org.werkpages.config.AppEnv.current().isProduction();
                        if (!isProdEnv) {
                            router.route("/swagger/*")
                                .handler(StaticHandler.create().setCachingEnabled(false).setWebRoot("swagger"));
                            router.route("/swagger/webjars/*")
                                .handler(StaticHandler.create().setCachingEnabled(false).setWebRoot("META-INF/resources/webjars"));
                            router.get("/")
                                .handler(ctx -> ctx.response().putHeader("Location", "/swagger/index.html").setStatusCode(302).end());
                            router.get("/openapi.yaml")
                                .handler(ctx -> ctx.response().putHeader("Content-Type", "application/yaml").sendFile("openapi.yaml"));
                        }
                        router.get("/logo.png")
                            .handler(ctx -> ctx.response()
                                .putHeader("Content-Type", "image/png")
                                .putHeader("Cache-Control", "public, max-age=86400")
                                .sendFile("logo.png"));

                        router.get("/sitemap.xml").handler(ctx ->
                            sitemapService.generate()
                                .onSuccess(xml -> ctx.response()
                                    .putHeader("Content-Type", "application/xml; charset=UTF-8")
                                    .putHeader("Cache-Control", "public, max-age=3600")
                                    .end(xml))
                                .onFailure(err -> {
                                    System.err.println("Sitemap generation failed: " + err.getMessage());
                                    ctx.fail(500);
                                })
                        );

                        // Defaults to 8888, unchanged from what production has always used.
                        // HTTP_PORT overrides it wherever the two backends share a host or network
                        // namespace — local dev for certain, and production too if the containers
                        // publish 8888 straight onto the EC2 host rather than remapping it.
                        int httpPort = Integer.parseInt(
                            System.getenv().getOrDefault("HTTP_PORT", "8888"));

                        vertx.createHttpServer()
                            .requestHandler(router)
                            .listen(httpPort)
                            .onSuccess(server -> System.out.println("✓ HTTP server started on port " + httpPort))
                            .onFailure(err -> System.err.println("✗ Failed to start server: " + err.getMessage()));

                        }); // end Database.init onReady
                    } else {
                        System.err.println("✗ Failed to create RouterFactory: " + routerFactoryAr.cause());
                    }
                });
            } else {
                System.err.println("✗ Failed to fetch JWKS from Auth0: " + ar.cause());
            }
        });
    }
}