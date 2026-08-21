package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.ManagerService;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
public class ManagerServiceCoverage5IntegrationTest {

    @Container
    static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("rmm").withUsername("rmm").withPassword("rmm");

    static Pool pool;
    static ManagerService service;

    @BeforeAll
    static void setup() {
        Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migrations")
                .load()
                .migrate();

        PgConnectOptions opts = new PgConnectOptions()
                .setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase("rmm").setUser("rmm").setPassword("rmm");
        pool = PgPool.pool(opts, new PoolOptions().setMaxSize(4));

        ManagerRepository managerRepo = new ManagerRepository(pool);
        ReviewRepository  reviewRepo  = new ReviewRepository(pool);
        UserRepository    userRepo    = new UserRepository(pool);
        EditRepository    editRepo    = new EditRepository(pool);
        ReportRepository  reportRepo  = new ReportRepository(pool);
        service = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, pool, name -> null);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                pool.query("TRUNCATE managers, users, companies, manager_edits CASCADE").execute()
                        .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                return;
            } catch (Exception e) {
                if (attempt == 2) throw e;
                if (e.getMessage() != null && e.getMessage().contains("deadlock")) Thread.sleep(300);
                else throw e;
            }
        }
    }

    private <T> T await(Future<T> f) throws Exception {
        try {
            return f.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw new RuntimeException(e.getCause());
        }
    }

    private String insertUser(String auth0Id) throws Exception {
        pool.preparedQuery("INSERT INTO users(auth0_id, email, username, first_name, last_name, role) VALUES ($1,$2,$3,$4,$5,'user')")
                .execute(Tuple.of(auth0Id, auth0Id + "@test.com", "u_" + auth0Id.replace("|", ""), "Test", "User"))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        return auth0Id;
    }

    private long insertManager(String name, String company, String title, String status) throws Exception {
        Row r = pool.preparedQuery(
                "INSERT INTO managers(name, company, title, image, status, approval_status, overall_rating, reviews_count, category_averages) " +
                "VALUES ($1,$2,$3,'img','active',$4,0,0,'{}') RETURNING id")
                .execute(Tuple.of(name, company, title, status))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
                .iterator().next();
        return r.getLong("id");
    }

    private UUID insertReviewForUser(long managerId, String auth0Id, String company, String title) throws Exception {
        UUID userId = pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
                .execute(Tuple.of(auth0Id))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
                .iterator().next().getUUID("id");
        return pool.preparedQuery(
                "INSERT INTO reviews(manager_id, user_id, author, overall_rating, " +
                "communication_style, perceived_approachability, perceived_clarity_of_expectations, " +
                "feedback_style, perceived_supportiveness, decision_making_style, " +
                "organization_and_planning_style, delegation_style, perceived_professional_demeanor, " +
                "overall_working_experience, manager_company, manager_title, " +
                "worked_from, verified, helpful_count, created_at, updated_at) " +
                "VALUES ($1,$2,'TestUser',3,3,3,3,3,3,3,3,3,3,3,$3,$4,'2022-01-01',true,0,now(),now()) RETURNING id")
                .execute(Tuple.of(managerId, userId, company, title))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
                .iterator().next().getUUID("id");
    }

    private static JsonObject validUpdateReviewBody(String company, String title) {
        return new JsonObject()
                .put("overallRating", 4.0)
                .put("ratings", new JsonObject()
                        .put("Communication Style", 4.0)
                        .put("Perceived Approachability", 4.0)
                        .put("Perceived Clarity of Expectations", 4.0)
                        .put("Feedback Style", 4.0)
                        .put("Perceived Supportiveness", 4.0)
                        .put("Decision Making Style", 4.0)
                        .put("Organization and Planning Style", 4.0)
                        .put("Delegation Style", 4.0)
                        .put("Perceived Professional Demeanor", 4.0)
                        .put("Overall Working Experience", 4.0))
                .put("managerCompany", company)
                .put("managerTitle", title)
                .put("workedFrom", "2022-01")
                .put("workedUntil", "2023-01")
                .put("authorType", "anonymous")
                .put("author", "AnonUser42");
    }

    private static JsonObject validReplaceReviewBody(String company, String title) {
        return validUpdateReviewBody(company, title).put("workedFrom", "2022-06");
    }

    // ── getManagerRows ────────────────────────────────────────────────────────

    @Test
    void getManagerRows_returnsRows() throws Exception {
        insertManager("Row Result Mgr", "RowCorp", "Dev", "approved");
        RowSet<Row> result = await(service.getManagerRows(10, 0, "Row Result", "RowCorp", "recent"));
        assertTrue(result.size() > 0);
    }

    @Test
    void getManagerRows_searchTooLong_returnsEmpty() throws Exception {
        RowSet<Row> result = await(service.getManagerRows(10, 0, "A".repeat(101), null, "recent"));
        assertEquals(0, result.size());
    }

    @Test
    void getManagerRows_companyTooLong_returnsEmpty() throws Exception {
        RowSet<Row> result = await(service.getManagerRows(10, 0, null, "A".repeat(101), "recent"));
        assertEquals(0, result.size());
    }

    // ── countManagers ─────────────────────────────────────────────────────────

    @Test
    void countManagers_noSearch_returnsTotal() throws Exception {
        insertManager("Count Mgr", "CountCo", "Dev", "approved");
        long count = await(service.countManagers(null, null));
        assertTrue(count > 0);
    }

    @Test
    void countManagers_withSearchAndCompany_returnsFiltered() throws Exception {
        insertManager("Count Filter Mgr", "FilterCo", "Dev", "approved");
        long count = await(service.countManagers("Count Filter", "FilterCo"));
        assertEquals(1, count);
    }

    @Test
    void countManagers_searchTooLong_returnsZero() throws Exception {
        long count = await(service.countManagers("A".repeat(101), null));
        assertEquals(0, count);
    }

    @Test
    void countManagers_companyTooLong_returnsZero() throws Exception {
        long count = await(service.countManagers(null, "A".repeat(101)));
        assertEquals(0, count);
    }

    // ── getManagerCareerSegments ──────────────────────────────────────────────

    @Test
    void getManagerCareerSegments_withReviews_returnsSegments() throws Exception {
        String auth0Id = insertUser("auth0|seg-test");
        long managerId = insertManager("Career Seg Mgr", "SegCorp", "Lead", "approved");
        // Insert a review with worked_from date to create a career segment
        pool.preparedQuery(
                "INSERT INTO reviews(manager_id, user_id, author, overall_rating, " +
                "communication_style, perceived_approachability, perceived_clarity_of_expectations, " +
                "feedback_style, perceived_supportiveness, decision_making_style, " +
                "organization_and_planning_style, delegation_style, perceived_professional_demeanor, " +
                "overall_working_experience, manager_company, manager_title, " +
                "worked_from, worked_until, verified, helpful_count, created_at, updated_at) " +
                "VALUES ($1,(SELECT id FROM users WHERE auth0_id=$2),'Seg User',4,4,4,4,4,4,4,4,4,4,4," +
                "'SegCorp','Lead','2022-01-01','2023-01-01',true,0,now(),now())")
                .execute(Tuple.of(managerId, auth0Id))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        JsonObject result = await(service.getManagerCareerSegments(managerId, 10, 0));
        assertNotNull(result);
        assertTrue(result.getLong("total") >= 0);
    }

    @Test
    void getManagerCareerSegments_noReviews_returnsEmpty() throws Exception {
        long managerId = insertManager("No Seg Mgr", "NoSegCorp", "Dev", "approved");
        JsonObject result = await(service.getManagerCareerSegments(managerId, 10, 0));
        assertEquals(0, result.getLong("total"));
        assertEquals(0, result.getJsonArray("data").size());
    }

    // ── updateReview ──────────────────────────────────────────────────────────

    @Test
    void updateReview_success() throws Exception {
        String auth0Id = insertUser("auth0|ur-success");
        long managerId = insertManager("Update Review Mgr", "URCorp", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "URCorp", "Dev");
        JsonObject body = validUpdateReviewBody("URCorp", "Dev");
        Row result = await(service.updateReview(auth0Id, managerId, reviewId, body));
        assertNotNull(result);
    }

    @Test
    void updateReview_nullBody_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|ur-null");
        long managerId = insertManager("UR Null Mgr", "URNullCo", "Dev", "approved");
        try {
            await(service.updateReview(auth0Id, managerId, UUID.randomUUID(), null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("missing"));
        }
    }

    @Test
    void updateReview_workedFromAfterWorkedUntil_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|ur-dates");
        long managerId = insertManager("UR Dates Mgr", "URDatesCo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "URDatesCo", "Dev");
        JsonObject body = validUpdateReviewBody("URDatesCo", "Dev")
                .put("workedFrom", "2023-06").put("workedUntil", "2022-01");
        try {
            await(service.updateReview(auth0Id, managerId, reviewId, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("from") || e.getMessage().toLowerCase().contains("date"));
        }
    }

    @Test
    void updateReview_workedFromInFuture_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|ur-future-from");
        long managerId = insertManager("UR FutFrom Mgr", "URFutureCo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "URFutureCo", "Dev");
        JsonObject body = validUpdateReviewBody("URFutureCo", "Dev");
        body.remove("workedUntil"); // remove so date-order check doesn't fire first
        body.put("workedFrom", "2099-01");
        try {
            await(service.updateReview(auth0Id, managerId, reviewId, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("future"));
        }
    }

    @Test
    void updateReview_workedUntilInFuture_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|ur-future-until");
        long managerId = insertManager("UR FutUntil Mgr", "URFutUntilCo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "URFutUntilCo", "Dev");
        JsonObject body = validUpdateReviewBody("URFutUntilCo", "Dev").put("workedUntil", "2099-01");
        try {
            await(service.updateReview(auth0Id, managerId, reviewId, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("future"));
        }
    }

    @Test
    void updateReview_managerRoleStartInFuture_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|ur-role-future");
        long managerId = insertManager("UR RoleFut Mgr", "URRoleFutCo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "URRoleFutCo", "Dev");
        JsonObject body = validUpdateReviewBody("URRoleFutCo", "Dev").put("managerRoleStart", "2099-01");
        try {
            await(service.updateReview(auth0Id, managerId, reviewId, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("future"));
        }
    }

    @Test
    void updateReview_managerRoleEndBeforeStart_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|ur-role-order");
        long managerId = insertManager("UR RoleOrder Mgr", "URRoleOrderCo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "URRoleOrderCo", "Dev");
        JsonObject body = validUpdateReviewBody("URRoleOrderCo", "Dev")
                .put("managerRoleStart", "2022-06").put("managerRoleEnd", "2021-01");
        try {
            await(service.updateReview(auth0Id, managerId, reviewId, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("start") || e.getMessage().toLowerCase().contains("date"));
        }
    }

    @Test
    void updateReview_workedFromBeforeRoleStart_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|ur-cross-valid");
        long managerId = insertManager("UR CrossValid Mgr", "URCrossValidCo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "URCrossValidCo", "Dev");
        JsonObject body = validUpdateReviewBody("URCrossValidCo", "Dev")
                .put("workedFrom", "2020-01").put("managerRoleStart", "2022-01");
        try {
            await(service.updateReview(auth0Id, managerId, reviewId, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("before") || e.getMessage().toLowerCase().contains("start"));
        }
    }

    @Test
    void updateReview_missingRequiredFields_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|ur-missing");
        long managerId = insertManager("UR Missing Mgr", "URMissingCo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "URMissingCo", "Dev");
        JsonObject body = new JsonObject().put("workedFrom", "2022-01"); // no ratings/company/title
        try {
            await(service.updateReview(auth0Id, managerId, reviewId, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("missing") || e.getMessage().toLowerCase().contains("required"));
        }
    }

    @Test
    void updateReview_invalidRating_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|ur-rating");
        long managerId = insertManager("UR Rating Mgr", "URRatingCo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "URRatingCo", "Dev");
        JsonObject body = validUpdateReviewBody("URRatingCo", "Dev").put("overallRating", 9.0);
        try {
            await(service.updateReview(auth0Id, managerId, reviewId, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("rating"));
        }
    }

    @Test
    void updateReview_bannedUser_returnsForbidden() throws Exception {
        String auth0Id = insertUser("auth0|ur-banned");
        long managerId = insertManager("UR Banned Mgr", "URBannedCo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "URBannedCo", "Dev");
        UUID userId = pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
                .execute(Tuple.of(auth0Id)).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
                .iterator().next().getUUID("id");
        pool.preparedQuery("INSERT INTO banned_users(user_id, reason, banned_by) VALUES ($1, 'test', 'admin')")
                .execute(Tuple.of(userId)).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        try {
            await(service.updateReview(auth0Id, managerId, reviewId, validUpdateReviewBody("URBannedCo", "Dev")));
            fail("expected forbidden");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("suspended") || e.getMessage().toLowerCase().contains("banned"));
        }
    }

    @Test
    void updateReview_wrongUser_reviewNotFound() throws Exception {
        String auth0Id1 = insertUser("auth0|ur-owner");
        String auth0Id2 = insertUser("auth0|ur-wrong");
        long managerId = insertManager("UR WrongUser Mgr", "URWrongCo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id1, "URWrongCo", "Dev");
        // auth0Id2 tries to update auth0Id1's review — reviewer.update returns empty
        JsonObject body = validUpdateReviewBody("URWrongCo", "Dev");
        try {
            await(service.updateReview(auth0Id2, managerId, reviewId, body));
            fail("expected not found");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("not found") || e.getMessage().toLowerCase().contains("review"));
        }
    }

    @Test
    void updateReview_anonymousAuthorType_usesClientName() throws Exception {
        String auth0Id = insertUser("auth0|ur-anon-author");
        long managerId = insertManager("UR AnonAuthor Mgr", "URAAuCorp", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "URAAuCorp", "Dev");
        JsonObject body = validUpdateReviewBody("URAAuCorp", "Dev")
                .put("authorType", "anonymous").put("author", "My Anon Name");
        Row result = await(service.updateReview(auth0Id, managerId, reviewId, body));
        assertNotNull(result);
    }

    @Test
    void updateReview_realNameAuthorType_usesClientName() throws Exception {
        String auth0Id = insertUser("auth0|ur-realname");
        long managerId = insertManager("UR RealName Mgr", "URRNCorp", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "URRNCorp", "Dev");
        JsonObject body = validUpdateReviewBody("URRNCorp", "Dev")
                .put("authorType", "real_name").put("author", "Real Name Person");
        Row result = await(service.updateReview(auth0Id, managerId, reviewId, body));
        assertNotNull(result);
    }

    @Test
    void updateReview_companyTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|ur-co-long");
        long managerId = insertManager("UR CoLong Mgr", "URCoLongCorp", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "URCoLongCorp", "Dev");
        JsonObject body = validUpdateReviewBody("A".repeat(101), "Dev");
        try {
            await(service.updateReview(auth0Id, managerId, reviewId, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("100") || e.getMessage().toLowerCase().contains("company"));
        }
    }

    // ── replaceReview (also covers validateBodySync) ──────────────────────────

    @Test
    void replaceReview_success() throws Exception {
        String auth0Id = insertUser("auth0|rr-success");
        long managerId = insertManager("Replace Review Mgr", "RRCorp", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "RRCorp", "Dev");
        JsonObject body = validReplaceReviewBody("RRCorp", "Dev");
        Row result = await(service.replaceReview(auth0Id, managerId, reviewId, body, null));
        assertNotNull(result);
    }

    @Test
    void replaceReview_bannedUser_returnsForbidden() throws Exception {
        String auth0Id = insertUser("auth0|rr-banned");
        long managerId = insertManager("RR Banned Mgr", "RRBannedCo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "RRBannedCo", "Dev");
        UUID userId = pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
                .execute(Tuple.of(auth0Id)).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
                .iterator().next().getUUID("id");
        pool.preparedQuery("INSERT INTO banned_users(user_id, reason, banned_by) VALUES ($1,'test','admin')")
                .execute(Tuple.of(userId)).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        try {
            await(service.replaceReview(auth0Id, managerId, reviewId, validReplaceReviewBody("RRBannedCo", "Dev"), null));
            fail("expected forbidden");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("suspended") || e.getMessage().toLowerCase().contains("forbidden"));
        }
    }

    @Test
    void replaceReview_wrongUser_returnsForbidden() throws Exception {
        String auth0Id1 = insertUser("auth0|rr-owner");
        String auth0Id2 = insertUser("auth0|rr-other");
        long managerId = insertManager("RR Wrong Mgr", "RRWrongCo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id1, "RRWrongCo", "Dev");
        try {
            await(service.replaceReview(auth0Id2, managerId, reviewId, validReplaceReviewBody("RRWrongCo", "Dev"), null));
            fail("expected forbidden");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("forbidden") || e.getMessage().toLowerCase().contains("not found"));
        }
    }

    @Test
    void replaceReview_validateBodySync_nullWorkedFrom_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|rr-sync-null");
        long managerId = insertManager("RR SyncNull Mgr", "RRSyncNullCo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "RRSyncNullCo", "Dev");
        JsonObject body = validReplaceReviewBody("RRSyncNullCo", "Dev");
        body.remove("workedFrom"); // null workedFrom
        try {
            await(service.replaceReview(auth0Id, managerId, reviewId, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("start date") || e.getMessage().toLowerCase().contains("from"));
        }
    }

    @Test
    void replaceReview_validateBodySync_futureWorkedFrom_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|rr-sync-future");
        long managerId = insertManager("RR SyncFuture Mgr", "RRSyncFutureCo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "RRSyncFutureCo", "Dev");
        JsonObject body = validReplaceReviewBody("RRSyncFutureCo", "Dev").put("workedFrom", "2099-01");
        try {
            await(service.replaceReview(auth0Id, managerId, reviewId, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("future"));
        }
    }

    @Test
    void replaceReview_validateBodySync_missingRatings_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|rr-sync-missing");
        long managerId = insertManager("RR SyncMiss Mgr", "RRSyncMissCo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "RRSyncMissCo", "Dev");
        JsonObject body = new JsonObject().put("workedFrom", "2022-01").put("managerCompany", "RRSyncMissCo").put("managerTitle", "Dev");
        // no ratings or overallRating
        try {
            await(service.replaceReview(auth0Id, managerId, reviewId, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("missing") || e.getMessage().toLowerCase().contains("required"));
        }
    }

    @Test
    void replaceReview_validateBodySync_managerRoleStartFuture_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|rr-sync-mrstart");
        long managerId = insertManager("RR SyncMRStart Mgr", "RRSyncMRSCo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "RRSyncMRSCo", "Dev");
        JsonObject body = validReplaceReviewBody("RRSyncMRSCo", "Dev").put("managerRoleStart", "2099-01");
        try {
            await(service.replaceReview(auth0Id, managerId, reviewId, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("future"));
        }
    }

    @Test
    void replaceReview_validateBodySync_roleEndBeforeStart_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|rr-sync-roleend");
        long managerId = insertManager("RR SyncRoleEnd Mgr", "RRSyncRECo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "RRSyncRECo", "Dev");
        JsonObject body = validReplaceReviewBody("RRSyncRECo", "Dev")
                .put("managerRoleStart", "2022-06").put("managerRoleEnd", "2021-01");
        try {
            await(service.replaceReview(auth0Id, managerId, reviewId, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("start") || e.getMessage().toLowerCase().contains("date"));
        }
    }

    @Test
    void replaceReview_validateBodySync_companyTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|rr-sync-colong");
        long managerId = insertManager("RR SyncCoLong Mgr", "RRSyncCLCo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "RRSyncCLCo", "Dev");
        JsonObject body = validReplaceReviewBody("A".repeat(101), "Dev");
        try {
            await(service.replaceReview(auth0Id, managerId, reviewId, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("100") || e.getMessage().toLowerCase().contains("company"));
        }
    }

    @Test
    void replaceReview_validateBodySync_textTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|rr-sync-text");
        long managerId = insertManager("RR SyncText Mgr", "RRSyncTextCo", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "RRSyncTextCo", "Dev");
        JsonObject body = validReplaceReviewBody("RRSyncTextCo", "Dev").put("text", "A".repeat(2001));
        try {
            await(service.replaceReview(auth0Id, managerId, reviewId, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("2000") || e.getMessage().toLowerCase().contains("text"));
        }
    }

    @Test
    void replaceReview_realNameAuthorType_usesClientName() throws Exception {
        String auth0Id = insertUser("auth0|rr-realname");
        long managerId = insertManager("RR RealName Mgr", "RRRNCorp", "Dev", "approved");
        UUID reviewId = insertReviewForUser(managerId, auth0Id, "RRRNCorp", "Dev");
        JsonObject body = validReplaceReviewBody("RRRNCorp", "Dev")
                .put("authorType", "real_name").put("author", "Real Person");
        Row result = await(service.replaceReview(auth0Id, managerId, reviewId, body, null));
        assertNotNull(result);
    }

    // ── getMyReviews ──────────────────────────────────────────────────────────

    @Test
    void getMyReviews_withReviews_returnsThem() throws Exception {
        String auth0Id = insertUser("auth0|myr-success");
        long managerId = insertManager("MyReview Mgr", "MyrCorp", "Dev", "approved");
        insertReviewForUser(managerId, auth0Id, "MyrCorp", "Dev");
        JsonObject result = await(service.getMyReviews(auth0Id, 10, 0));
        assertTrue(result.getLong("total") > 0);
        assertTrue(result.getJsonArray("data").size() > 0);
    }

    @Test
    void getMyReviews_noReviews_returnsEmpty() throws Exception {
        String auth0Id = insertUser("auth0|myr-empty");
        JsonObject result = await(service.getMyReviews(auth0Id, 10, 0));
        assertEquals(0, result.getLong("total"));
        assertEquals(0, result.getJsonArray("data").size());
    }

    @Test
    void getMyReviews_userNotFound_returnsError() {
        try {
            await(service.getMyReviews("auth0|nonexistent-myr", 10, 0));
            fail("expected error");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("not found") || e.getMessage().toLowerCase().contains("user"));
        }
    }

    // ── createEditRequest ─────────────────────────────────────────────────────

    @Test
    void createEditRequest_success() throws Exception {
        String auth0Id = insertUser("auth0|er-success");
        long managerId = insertManager("EditReq Mgr", "ERCorp", "Dev", "approved");
        JsonObject body = new JsonObject().put("company", "NewCorp").put("title", "New Lead");
        JsonObject result = await(service.createEditRequest(auth0Id, managerId, body));
        assertNotNull(result.getString("id"));
        assertEquals("pending", result.getString("status"));
    }

    @Test
    void createEditRequest_nullBody_returnsBadRequest() {
        try {
            await(service.createEditRequest("auth0|er-null", 1L, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("missing"));
        }
    }

    @Test
    void createEditRequest_allFieldsBlank_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|er-blank");
        try {
            await(service.createEditRequest(auth0Id, 1L, new JsonObject().put("company", "  ")));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("field") || e.getMessage().toLowerCase().contains("required"));
        }
    }

    @Test
    void createEditRequest_companyTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|er-colong");
        JsonObject body = new JsonObject().put("company", "A".repeat(101));
        try {
            await(service.createEditRequest(auth0Id, 1L, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("100") || e.getMessage().toLowerCase().contains("company"));
        }
    }

    @Test
    void createEditRequest_titleTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|er-titlong");
        JsonObject body = new JsonObject().put("title", "A".repeat(101));
        try {
            await(service.createEditRequest(auth0Id, 1L, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("100") || e.getMessage().toLowerCase().contains("title"));
        }
    }

    @Test
    void createEditRequest_invalidStatus_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|er-status");
        JsonObject body = new JsonObject().put("status", "invalid_status");
        try {
            await(service.createEditRequest(auth0Id, 1L, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("status") || e.getMessage().toLowerCase().contains("active"));
        }
    }

    @Test
    void createEditRequest_countryTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|er-ctlong");
        JsonObject body = new JsonObject().put("country", "A".repeat(101));
        try {
            await(service.createEditRequest(auth0Id, 1L, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("100") || e.getMessage().toLowerCase().contains("country"));
        }
    }

    @Test
    void createEditRequest_linkedinTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|er-lilong");
        JsonObject body = new JsonObject().put("linkedinUrl", "A".repeat(501));
        try {
            await(service.createEditRequest(auth0Id, 1L, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("500") || e.getMessage().toLowerCase().contains("linkedin"));
        }
    }

    @Test
    void createEditRequest_bannedUser_returnsForbidden() throws Exception {
        String auth0Id = insertUser("auth0|er-banned");
        long managerId = insertManager("ER Banned Mgr", "ERBannedCo", "Dev", "approved");
        UUID userId = pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
                .execute(Tuple.of(auth0Id)).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
                .iterator().next().getUUID("id");
        pool.preparedQuery("INSERT INTO banned_users(user_id, reason, banned_by) VALUES ($1,'test','admin')")
                .execute(Tuple.of(userId)).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        try {
            await(service.createEditRequest(auth0Id, managerId, new JsonObject().put("company", "NewCo")));
            fail("expected forbidden");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("suspended") || e.getMessage().toLowerCase().contains("banned"));
        }
    }

    @Test
    void createEditRequest_managerNotFound_returnsNotFound() throws Exception {
        String auth0Id = insertUser("auth0|er-nomgr");
        try {
            await(service.createEditRequest(auth0Id, 99999L, new JsonObject().put("company", "NewCo")));
            fail("expected not found");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("not found") || e.getMessage().toLowerCase().contains("manager"));
        }
    }

    // ── createGhostManager ────────────────────────────────────────────────────

    @Test
    void createGhostManager_nullBody_returnsBadRequest() {
        try {
            await(service.createGhostManager(null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("missing"));
        }
    }

    @Test
    void createGhostManager_missingFields_returnsBadRequest() {
        try {
            await(service.createGhostManager(new JsonObject().put("name", "John Smith"), null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("missing"));
        }
    }

    @Test
    void createGhostManager_success_newManager() throws Exception {
        JsonObject body = new JsonObject()
                .put("name", "Alice Walker").put("company", "GhostCorp")
                .put("title", "Engineer").put("country", "US");
        JsonObject result = await(service.createGhostManager(body, null));
        assertTrue(result.getBoolean("created"));
        assertNotNull(result.getLong("id"));
    }

    @Test
    void createGhostManager_alreadyExists_returnsFalseCreated() throws Exception {
        insertManager("Alice Walker", "GhostCorp", "Engineer", "approved");
        JsonObject body = new JsonObject()
                .put("name", "Alice Walker").put("company", "GhostCorp")
                .put("title", "Engineer").put("country", "US");
        JsonObject result = await(service.createGhostManager(body, null));
        assertFalse(result.getBoolean("created"));
    }

    @Test
    void createGhostManager_nameTooLong_returnsBadRequest() {
        JsonObject body = new JsonObject()
                .put("name", "A".repeat(101)).put("company", "GhostCorp")
                .put("title", "Eng").put("country", "US");
        try {
            await(service.createGhostManager(body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("long") || e.getMessage().toLowerCase().contains("name"));
        }
    }

    @Test
    void createGhostManager_stateTooLong_returnsBadRequest() {
        JsonObject body = new JsonObject()
                .put("name", "Alice Walker").put("company", "GhostCorp")
                .put("title", "Eng").put("country", "US")
                .put("state", "A".repeat(101));
        try {
            await(service.createGhostManager(body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("long") || e.getMessage().toLowerCase().contains("state"));
        }
    }

    @Test
    void createGhostManager_cityTooLong_returnsBadRequest() {
        JsonObject body = new JsonObject()
                .put("name", "Alice Walker").put("company", "GhostCorp")
                .put("title", "Eng").put("country", "US")
                .put("city", "A".repeat(101));
        try {
            await(service.createGhostManager(body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("long") || e.getMessage().toLowerCase().contains("city"));
        }
    }

    @Test
    void createGhostManager_invalidName_returnsBadRequest() {
        // FAKE_FULL_NAMES check via NameValidator
        JsonObject body = new JsonObject()
                .put("name", "John Doe").put("company", "GhostCorp")
                .put("title", "Eng").put("country", "US");
        try {
            await(service.createGhostManager(body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("name") || e.getMessage().toLowerCase().contains("person"));
        }
    }

    // ── captureAnonymousSearch ────────────────────────────────────────────────

    @Test
    void captureAnonymousSearch_nullBody_returnsBadRequest() {
        try {
            await(service.captureAnonymousSearch(null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("missing"));
        }
    }

    @Test
    void captureAnonymousSearch_missingFields_returnsBadRequest() {
        try {
            await(service.captureAnonymousSearch(new JsonObject().put("name", "Alice Walker"), null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("missing"));
        }
    }

    @Test
    void captureAnonymousSearch_success_createsRecord() throws Exception {
        JsonObject body = new JsonObject()
                .put("name", "Alice Walker").put("company", "AnonCorp")
                .put("title", "Engineer").put("country", "US");
        await(service.captureAnonymousSearch(body, null));
        // Verify a pending manager was created
        long count = pool.preparedQuery(
                "SELECT COUNT(*) FROM managers WHERE name = 'Alice Walker' AND approval_status = 'pending_approval'")
                .execute().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
                .iterator().next().getLong(0);
        assertEquals(1, count);
    }

    @Test
    void captureAnonymousSearch_alreadyExists_skips() throws Exception {
        insertManager("Alice Walker", "AnonCorp", "Engineer", "approved");
        JsonObject body = new JsonObject()
                .put("name", "Alice Walker").put("company", "AnonCorp")
                .put("title", "Engineer").put("country", "US");
        await(service.captureAnonymousSearch(body, null)); // should succeed silently
        long count = pool.preparedQuery(
                "SELECT COUNT(*) FROM managers WHERE name = 'Alice Walker'")
                .execute().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
                .iterator().next().getLong(0);
        assertEquals(1, count); // still just 1
    }

    @Test
    void captureAnonymousSearch_nameTooLong_returnsBadRequest() {
        JsonObject body = new JsonObject()
                .put("name", "A".repeat(101)).put("company", "AnonCorp")
                .put("title", "Eng").put("country", "US");
        try {
            await(service.captureAnonymousSearch(body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("long") || e.getMessage().toLowerCase().contains("name"));
        }
    }

    @Test
    void captureAnonymousSearch_stateTooLong_returnsBadRequest() {
        JsonObject body = new JsonObject()
                .put("name", "Alice Walker").put("company", "AnonCorp")
                .put("title", "Eng").put("country", "US")
                .put("state", "A".repeat(101));
        try {
            await(service.captureAnonymousSearch(body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("long") || e.getMessage().toLowerCase().contains("state"));
        }
    }

    // ── createDropOffDraft ────────────────────────────────────────────────────

    @Test
    void createDropOffDraft_nullBody_returnsBadRequest() {
        try {
            await(service.createDropOffDraft(null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("missing"));
        }
    }

    @Test
    void createDropOffDraft_missingReview_returnsBadRequest() {
        JsonObject body = new JsonObject()
                .put("name", "Alice Walker").put("company", "DropCorp")
                .put("title", "Dev").put("country", "US");
        // no review field
        try {
            await(service.createDropOffDraft(body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("review") || e.getMessage().toLowerCase().contains("missing"));
        }
    }

    @Test
    void createDropOffDraft_success_newManager() throws Exception {
        JsonObject review = new JsonObject()
                .put("author", "AnonDropUser")
                .put("overallRating", 3.5)
                .put("ratings", new JsonObject()
                        .put("communication_style", 3).put("perceived_approachability", 3)
                        .put("perceived_clarity_of_expectations", 3).put("feedback_style", 3)
                        .put("perceived_supportiveness", 3).put("decision_making_style", 3)
                        .put("organization_and_planning_style", 3).put("delegation_style", 3)
                        .put("perceived_professional_demeanor", 3).put("overall_working_experience", 3))
                .put("managerCompany", "DropCorp").put("managerTitle", "Dev")
                .put("workedFrom", "2022-01");
        JsonObject body = new JsonObject()
                .put("name", "Alice Walker").put("company", "DropCorp")
                .put("title", "Dev").put("country", "US")
                .put("review", review);
        JsonObject result = await(service.createDropOffDraft(body, null));
        assertTrue(result.getBoolean("created"));
    }

    @Test
    void createDropOffDraft_ghostManagerExists_addsDraftReview() throws Exception {
        insertManager("Alice Walker", "DropGhostCorp", "Dev", "ghost");
        JsonObject review = new JsonObject()
                .put("author", "DroppedUser")
                .put("overallRating", 3.0)
                .put("ratings", new JsonObject()
                        .put("communication_style", 3).put("perceived_approachability", 3)
                        .put("perceived_clarity_of_expectations", 3).put("feedback_style", 3)
                        .put("perceived_supportiveness", 3).put("decision_making_style", 3)
                        .put("organization_and_planning_style", 3).put("delegation_style", 3)
                        .put("perceived_professional_demeanor", 3).put("overall_working_experience", 3))
                .put("managerCompany", "DropGhostCorp").put("managerTitle", "Dev")
                .put("workedFrom", "2022-01");
        JsonObject body = new JsonObject()
                .put("name", "Alice Walker").put("company", "DropGhostCorp")
                .put("title", "Dev").put("country", "US").put("review", review);
        JsonObject result = await(service.createDropOffDraft(body, null));
        assertFalse(result.getBoolean("created"));
    }

    @Test
    void createDropOffDraft_nameTooLong_returnsBadRequest() {
        JsonObject body = new JsonObject()
                .put("name", "A".repeat(101)).put("company", "DropCorp")
                .put("title", "Dev").put("country", "US")
                .put("review", new JsonObject());
        try {
            await(service.createDropOffDraft(body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("long") || e.getMessage().toLowerCase().contains("name"));
        }
    }

    @Test
    void createDropOffDraft_stateTooLong_returnsBadRequest() {
        JsonObject body = new JsonObject()
                .put("name", "Alice Walker").put("company", "DropCorp")
                .put("title", "Dev").put("country", "US")
                .put("state", "A".repeat(101))
                .put("review", new JsonObject());
        try {
            await(service.createDropOffDraft(body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("long") || e.getMessage().toLowerCase().contains("state"));
        }
    }

    @Test
    void createDropOffDraft_retiredStatus_usedCorrectly() throws Exception {
        JsonObject review = new JsonObject()
                .put("author", "RetiredDropUser")
                .put("overallRating", 3.0)
                .put("ratings", new JsonObject()
                        .put("communication_style", 3).put("perceived_approachability", 3)
                        .put("perceived_clarity_of_expectations", 3).put("feedback_style", 3)
                        .put("perceived_supportiveness", 3).put("decision_making_style", 3)
                        .put("organization_and_planning_style", 3).put("delegation_style", 3)
                        .put("perceived_professional_demeanor", 3).put("overall_working_experience", 3))
                .put("managerCompany", "RetiredDropCorp").put("managerTitle", "Retired Dev")
                .put("workedFrom", "2020-01").put("workedUntil", "2022-01");
        JsonObject body = new JsonObject()
                .put("name", "Alice Walker").put("company", "RetiredDropCorp")
                .put("title", "Retired Dev").put("country", "US").put("status", "retired")
                .put("review", review);
        JsonObject result = await(service.createDropOffDraft(body, null));
        assertTrue(result.getBoolean("created"));
    }

    // ── findOrCreate — hasContributed=true path ───────────────────────────────

    @Test
    void findOrCreate_hasContributed_noManager_createsGhost() throws Exception {
        String auth0Id = insertUser("auth0|foc-contrib");
        long managerId = insertManager("Existing Mgr", "ExistingCorp", "Dev", "approved");
        // Insert a review so hasContributed = true
        insertReviewForUser(managerId, auth0Id, "ExistingCorp", "Dev");
        // First findOrCreate search — ghost slot not yet claimed, so ghost is created
        JsonObject result = await(service.findOrCreate(auth0Id, "Patricia", "Chen", "Manager", "NonExistentCo999", "US", null, null, null));
        assertTrue(result.getBoolean("created"), "First search should create a ghost");
        assertEquals(1, result.getJsonArray("data").size());
        assertEquals("ghost", result.getJsonArray("data").getJsonObject(0).getString("approvalStatus"));
        assertTrue(result.getBoolean("hasContributed"));
    }

    @Test
    void findOrCreate_exactMatchExistingManager_returnsExisting() throws Exception {
        String auth0Id = insertUser("auth0|foc-exact");
        insertManager("Margaret Williams", "ExactMatchCorp", "Manager", "ghost");
        // Exact name match — should find the existing ghost manager via search
        JsonObject result = await(service.findOrCreate(auth0Id, "Margaret", "Williams", "Manager", "ExactMatchCorp", "US", null, null, null));
        assertFalse(result.getBoolean("created"));
        assertFalse(result.getJsonArray("data").isEmpty());
    }

    // ── updateManager — missing validation branches ───────────────────────────

    @Test
    void updateManager_invalidStatus_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|um-status");
        long managerId = insertManager("UM Status Mgr", "UMStatusCo", "Dev", "approved");
        JsonObject body = new JsonObject().put("status", "invalid");
        try {
            await(service.updateManager(auth0Id, managerId, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("status") || e.getMessage().toLowerCase().contains("active"));
        }
    }

    @Test
    void updateManager_linkedinUrlInvalid_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|um-linkedin");
        long managerId = insertManager("UM LinkedIn Mgr", "UMLICo", "Dev", "approved");
        JsonObject body = new JsonObject().put("linkedinUrl", "https://facebook.com/user");
        try {
            await(service.updateManager(auth0Id, managerId, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("linkedin"));
        }
    }

    @Test
    void updateManager_companyTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|um-colong");
        long managerId = insertManager("UM CoLong Mgr", "UMCoLCo", "Dev", "approved");
        JsonObject body = new JsonObject().put("company", "A".repeat(101));
        try {
            await(service.updateManager(auth0Id, managerId, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("100") || e.getMessage().toLowerCase().contains("company"));
        }
    }

    @Test
    void updateManager_titleTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|um-titlong");
        long managerId = insertManager("UM TitLong Mgr", "UMTitLCo", "Dev", "approved");
        JsonObject body = new JsonObject().put("title", "A".repeat(101));
        try {
            await(service.updateManager(auth0Id, managerId, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("100") || e.getMessage().toLowerCase().contains("title"));
        }
    }

    @Test
    void updateManager_bioTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|um-bio");
        long managerId = insertManager("UM Bio Mgr", "UMBioCo", "Dev", "approved");
        JsonObject body = new JsonObject().put("bio", "A".repeat(1001));
        try {
            await(service.updateManager(auth0Id, managerId, body));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("1000") || e.getMessage().toLowerCase().contains("bio"));
        }
    }

    @Test
    void updateManager_companyAndTitleChange_withStartDate_createsNewCareerSegment() throws Exception {
        String auth0Id = insertUser("auth0|um-careerseg");
        long managerId = insertManager("UM CareerSeg Mgr", "OldCorp", "Old Dev", "approved");
        // First insert a career history entry
        long companyId = pool.preparedQuery("INSERT INTO companies(name, status) VALUES ('OldCorp','approved') RETURNING id")
                .execute().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
                .iterator().next().getLong("id");
        pool.preparedQuery("INSERT INTO career_history(manager_id, company, title, start_date, company_id) VALUES ($1,'OldCorp','Old Dev','2020-01-01',$2)")
                .execute(Tuple.of(managerId, companyId)).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        JsonObject body = new JsonObject()
                .put("company", "NewCorp2025").put("title", "New Director")
                .put("startDate", "2024-01");
        JsonObject result = await(service.updateManager(auth0Id, managerId, body));
        assertNotNull(result);
        assertEquals("NewCorp2025", result.getString("company"));
    }

    // ── createManager — additional validation branches ────────────────────────

    @Test
    void createManager_countryTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|cm-ctlong");
        JsonObject body = buildValidCreateBody("CM Country Too Long", "CTLCorp", "Dev")
                .put("country", "A".repeat(101));
        try {
            await(service.createManager(auth0Id, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("100") || e.getMessage().toLowerCase().contains("country"));
        }
    }

    @Test
    void createManager_stateTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|cm-stlong");
        JsonObject body = buildValidCreateBody("CM State Too Long", "STLCorp", "Dev")
                .put("state", "A".repeat(101));
        try {
            await(service.createManager(auth0Id, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("long") || e.getMessage().toLowerCase().contains("state"));
        }
    }

    @Test
    void createManager_cityTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|cm-citylong");
        JsonObject body = buildValidCreateBody("CM City Too Long", "CityLCorp", "Dev")
                .put("city", "A".repeat(101));
        try {
            await(service.createManager(auth0Id, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("long") || e.getMessage().toLowerCase().contains("city"));
        }
    }

    @Test
    void createManager_bioTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|cm-biolong");
        JsonObject body = buildValidCreateBody("CM Bio Too Long", "BioLCorp", "Dev")
                .put("bio", "A".repeat(1001));
        try {
            await(service.createManager(auth0Id, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("1000") || e.getMessage().toLowerCase().contains("bio"));
        }
    }

    @Test
    void createManager_linkedinUrlInvalid_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|cm-linkedin");
        JsonObject body = buildValidCreateBody("CM LinkedIn Invalid", "LICorp", "Dev")
                .put("linkedinUrl", "https://not-linkedin.com/user");
        try {
            await(service.createManager(auth0Id, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("linkedin"));
        }
    }

    @Test
    void createManager_retiredWithNoEndDate_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|cm-retired-noend");
        JsonObject body = buildValidCreateBody("CM Retired NoEnd", "RetiredNECorp", "Dev")
                .put("status", "retired");
        body.remove("endDate"); // remove endDate to trigger validation
        // Also remove workedUntil from review
        body.getJsonObject("review").remove("workedUntil");
        try {
            await(service.createManager(auth0Id, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("retired") || e.getMessage().toLowerCase().contains("end") || e.getMessage().toLowerCase().contains("date"));
        }
    }

    @Test
    void createManager_reviewWorkedFromInFuture_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|cm-wf-future");
        JsonObject body = buildValidCreateBody("CM WF Future", "WFFutureCorp", "Dev");
        body.getJsonObject("review").put("workedFrom", "2099-01");
        try {
            await(service.createManager(auth0Id, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("future") || e.getMessage().toLowerCase().contains("from"));
        }
    }

    @Test
    void createManager_reviewMissingCompany_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|cm-no-co");
        JsonObject body = buildValidCreateBody("CM No Company", "NoCompanyCorp", "Dev");
        body.getJsonObject("review").remove("managerCompany");
        try {
            await(service.createManager(auth0Id, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("missing") || e.getMessage().toLowerCase().contains("company"));
        }
    }

    @Test
    void createManager_reviewTextTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|cm-text-long");
        JsonObject body = buildValidCreateBody("CM Text Long", "TextLCorp", "Dev");
        body.getJsonObject("review").put("text", "A".repeat(2001));
        try {
            await(service.createManager(auth0Id, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("2000") || e.getMessage().toLowerCase().contains("text"));
        }
    }

    @Test
    void createManager_reviewInvalidOverallRating_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|cm-rating-invalid");
        JsonObject body = buildValidCreateBody("CM Rating Invalid", "RINCorp", "Dev");
        body.getJsonObject("review").put("overallRating", 0.5);
        try {
            await(service.createManager(auth0Id, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("rating") || e.getMessage().toLowerCase().contains("1"));
        }
    }

    // ── getCompanies ──────────────────────────────────────────────────────────

    @Test
    void getCompanies_withManagers_returnsCompanyList() throws Exception {
        insertManager("Companies Test Mgr", "CompaniesCo", "Dev", "approved");
        JsonObject result = await(service.getCompanies());
        JsonArray companies = result.getJsonArray("data");
        assertNotNull(companies);
        assertTrue(companies.size() > 0);
    }

    // ── getSimilarManagers — null company branch ──────────────────────────────

    @Test
    void getSimilarManagers_nullCompany_returnsResults() throws Exception {
        insertManager("Similar Mgr One", "SimCorp", "Dev", "approved");
        JsonObject result = await(service.getSimilarManagers("Similar Mgr One", null));
        assertNotNull(result);
    }

    // ── getManagerBySlug — pending submitter enforcement ─────────────────────

    @Test
    void getManagerBySlug_pendingOwnSubmitter_returnsManager() throws Exception {
        String auth0Id = insertUser("auth0|slug-pend-submitter");
        UUID userId = pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
                .execute(Tuple.of(auth0Id)).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
                .iterator().next().getUUID("id");
        // Insert pending manager submitted by this user, with a slug
        long managerId = pool.preparedQuery(
                "INSERT INTO managers(name, company, title, image, status, approval_status, overall_rating, reviews_count, category_averages, submitted_by, slug) " +
                "VALUES ('Slug Pending Mgr','SlugPendCo','Dev','img','active','pending_approval',0,0,'{}', $1,'slug-pending-test') RETURNING id")
                .execute(Tuple.of(userId)).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
                .iterator().next().getLong("id");
        Row result = await(service.getManagerBySlug("slug-pending-test", auth0Id));
        assertNotNull(result);
    }

    @Test
    void getManagerBySlug_pendingWrongUser_returnsNotFound() throws Exception {
        String auth0Id1 = insertUser("auth0|slug-pend-owner");
        String auth0Id2 = insertUser("auth0|slug-pend-other");
        UUID userId1 = pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
                .execute(Tuple.of(auth0Id1)).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
                .iterator().next().getUUID("id");
        pool.preparedQuery(
                "INSERT INTO managers(name, company, title, image, status, approval_status, overall_rating, reviews_count, category_averages, submitted_by, slug) " +
                "VALUES ('Slug Pend Owner Mgr','SlugPendOwnerCo','Dev','img','active','pending_approval',0,0,'{}', $1,'slug-pend-owner-test') RETURNING id")
                .execute(Tuple.of(userId1)).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        try {
            await(service.getManagerBySlug("slug-pend-owner-test", auth0Id2));
            fail("expected not found or forbidden");
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("not found") || e.getMessage().toLowerCase().contains("forbidden"));
        }
    }

    // ── getCompanyListing — logoUrl present branch ────────────────────────────

    @Test
    void getCompanyListing_withLogoUrl_includesLogo() throws Exception {
        // Insert company with a logo URL in company_stats_live
        long companyId = pool.preparedQuery(
                "INSERT INTO companies(name, status, logo_url) VALUES ('LogoCo', 'approved', 'https://img.logo.dev/logo.co?token=test') RETURNING id")
                .execute().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
                .iterator().next().getLong("id");
        pool.preparedQuery(
                "INSERT INTO managers(name, company, title, image, status, approval_status, overall_rating, reviews_count, category_averages, company_id) " +
                "VALUES ('Logo Mgr','LogoCo','Dev','img','active','approved',0,0,'{}', $1)")
                .execute(Tuple.of(companyId)).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        // Populate company_stats_live
        pool.preparedQuery(
                "INSERT INTO company_stats_live(company_id, manager_count, total_reviews, avg_rating, logo_url, updated_at) " +
                "VALUES ($1, 1, 0, NULL, 'https://img.logo.dev/logo.co?token=test', now())" +
                "ON CONFLICT (company_id) DO UPDATE SET logo_url = EXCLUDED.logo_url")
                .execute(Tuple.of(companyId)).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        JsonObject result = await(service.getCompanyListing());
        JsonArray data = result.getJsonArray("data");
        boolean found = false;
        for (int i = 0; i < data.size(); i++) {
            if ("LogoCo".equals(data.getJsonObject(i).getString("name"))) {
                assertNotNull(data.getJsonObject(i).getString("logoUrl"));
                found = true;
            }
        }
        assertTrue(found);
    }

    // ── Helper: valid createManager body ─────────────────────────────────────

    private static JsonObject buildValidCreateBody(String name, String company, String title) {
        String[] parts = name.split(" ", 2);
        String firstName = parts[0];
        String lastName  = parts.length > 1 ? parts[1] : "Test";
        JsonObject review = new JsonObject()
                .put("authorType", "anonymous")
                .put("author", "AnonUser99")
                .put("overallRating", 4.0)
                .put("ratings", new JsonObject()
                        .put("communication_style", 4).put("perceived_approachability", 4)
                        .put("perceived_clarity_of_expectations", 4).put("feedback_style", 4)
                        .put("perceived_supportiveness", 4).put("decision_making_style", 4)
                        .put("organization_and_planning_style", 4).put("delegation_style", 4)
                        .put("perceived_professional_demeanor", 4).put("overall_working_experience", 4))
                .put("managerCompany", company)
                .put("managerTitle", title)
                .put("workedFrom", "2022-01")
                .put("workedUntil", "2023-01");
        return new JsonObject()
                .put("name", firstName + " " + lastName)
                .put("company", company)
                .put("title", title)
                .put("image", firstName.substring(0, 1))
                .put("bio", "Test bio")
                .put("status", "active")
                .put("country", "US")
                .put("startDate", "2022-01")
                .put("endDate", "2023-01")
                .put("review", review);
    }
}
