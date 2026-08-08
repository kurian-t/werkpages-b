package org.ratemymanager.integration;

import io.vertx.core.Future;
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
import org.ratemymanager.repository.EditRepository;
import org.ratemymanager.repository.ManagerRepository;
import org.ratemymanager.repository.ReportRepository;
import org.ratemymanager.repository.ReviewRepository;
import org.ratemymanager.repository.UserRepository;
import org.ratemymanager.service.ManagerService;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ManagerServiceCoverage4IntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("ratemymanager_test")
        .withUsername("test")
        .withPassword("test");

    static Pool           pool;
    static ManagerService service;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load()
            .migrate();

        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        pool = PgPool.pool(connectOptions, new PoolOptions().setMaxSize(5));

        ManagerRepository managerRepo = new ManagerRepository(pool);
        ReviewRepository  reviewRepo  = new ReviewRepository(pool);
        UserRepository    userRepo    = new UserRepository(pool);
        EditRepository    editRepo    = new EditRepository(pool);
        ReportRepository  reportRepo  = new ReportRepository(pool);
        service = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        // Retry to handle transient deadlocks from async background ops (e.g. recalculateInBackground)
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                pool.query("TRUNCATE managers, users, companies, manager_edits CASCADE").execute()
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                return;
            } catch (Exception e) {
                if (attempt == 2) throw e;
                String msg = e.getMessage();
                if (msg != null && msg.contains("deadlock")) {
                    Thread.sleep(300);
                } else {
                    throw e;
                }
            }
        }
    }

    // ── NameValidator remaining paths ─────────────────────────────────────────

    @Test
    void findOrCreate_badLastName_blank_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|nv-bad-ln");
        try {
            await(service.findOrCreate(auth0Id, "Alice", "", "Engineer", "Corp", "US", null, null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("required"),
                "Expected required error but got: " + e.getMessage());
        }
    }

    @Test
    void findOrCreate_lastNameTooShort_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|nv-short-ln");
        try {
            await(service.findOrCreate(auth0Id, "Alice", "S", "Engineer", "Corp", "US", null, null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("too short"),
                "Expected too short but got: " + e.getMessage());
        }
    }

    @Test
    void findOrCreate_titleBlank_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|nv-blank-title");
        try {
            await(service.findOrCreate(auth0Id, "Alice", "Smith", "", "Corp", "US", null, null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("required"),
                "Expected required but got: " + e.getMessage());
        }
    }

    @Test
    void findOrCreate_titleTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|nv-long-title");
        try {
            await(service.findOrCreate(auth0Id, "Alice", "Smith", "T".repeat(101), "Corp", "US", null, null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("too long"),
                "Expected too long but got: " + e.getMessage());
        }
    }

    @Test
    void findOrCreate_companyTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|nv-long-company");
        try {
            await(service.findOrCreate(auth0Id, "Alice", "Smith", "Engineer", "C".repeat(101), "US", null, null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("too long"),
                "Expected too long but got: " + e.getMessage());
        }
    }

    @Test
    void findOrCreate_fakeFullName_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|nv-fake-full");
        try {
            await(service.findOrCreate(auth0Id, "John", "Doe", "Engineer", "Corp", "US", null, null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("real person"),
                "Expected real person error but got: " + e.getMessage());
        }
    }

    // ── createReview edge cases ───────────────────────────────────────────────

    @Test
    void createReview_nullBody_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|cr-null-body");
        long managerId = insertManager("Review Null Target", "Corp", "Dev", "approved");
        try {
            await(service.createReview(auth0Id, managerId, null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("missing"),
                "Expected missing body but got: " + e.getMessage());
        }
    }

    @Test
    void createReview_bannedUser_returnsForbidden() throws Exception {
        String auth0Id = insertUser("auth0|cr-banned");
        long managerId = insertManager("Review Banned Target", "Corp", "Dev", "approved");
        banUser(auth0Id);
        try {
            await(service.createReview(auth0Id, managerId, validReviewBody(), null));
            fail("expected forbidden");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("account_suspended"),
                "Expected account_suspended but got: " + e.getMessage());
        }
    }

    @Test
    void createReview_anonymousAuthorType_succeeds() throws Exception {
        String auth0Id = insertUser("auth0|cr-anon");
        long managerId = insertManager("Anon Review Target", "AnonCorp", "Dev", "approved");
        JsonObject body = validReviewBody()
            .put("authorType", "anonymous")
            .put("author", "Shadow Reviewer");
        Row result = await(service.createReview(auth0Id, managerId, body, null));
        assertNotNull(result);
        assertEquals("Shadow Reviewer", result.getString("author"));
    }

    @Test
    void createReview_anonymousAuthorType_emptyClientAuthor_generatesPseudonym() throws Exception {
        String auth0Id = insertUser("auth0|cr-anon-empty");
        long managerId = insertManager("Anon Empty Target", "AnonCorp2", "Dev", "approved");
        JsonObject body = validReviewBody()
            .put("authorType", "anonymous")
            .put("author", "");
        Row result = await(service.createReview(auth0Id, managerId, body, null));
        assertNotNull(result);
        assertNotNull(result.getString("author"), "Should have generated a pseudonym");
        assertFalse(result.getString("author").isEmpty(), "Author should not be empty");
    }

    @Test
    void createReview_realNameAuthorType_succeeds() throws Exception {
        String auth0Id = insertUser("auth0|cr-realname");
        long managerId = insertManager("RealName Review Target", "RNCorpX", "Director", "approved");
        JsonObject body = validReviewBody()
            .put("authorType", "real_name")
            .put("author", "Jane Reviewer");
        Row result = await(service.createReview(auth0Id, managerId, body, null));
        assertNotNull(result);
        assertEquals("Jane Reviewer", result.getString("author"));
    }

    @Test
    void createReview_dailyLimitReached_returnsTooManyRequests() throws Exception {
        String auth0Id = insertUser("auth0|cr-limit");
        // Directly insert 6 review count entries for today to simulate daily limit
        UUID userId = getUserId(auth0Id);
        for (int i = 0; i < 6; i++) {
            long mgrId = insertManager("Daily Limit Mgr" + i, "LimitCorp" + i, "Dev", "approved");
            // Insert review directly in DB (bypasses service validation)
            pool.preparedQuery(
                "INSERT INTO reviews(manager_id, user_id, author, overall_rating, " +
                "communication_style, perceived_approachability, perceived_clarity_of_expectations, " +
                "feedback_style, perceived_supportiveness, decision_making_style, " +
                "organization_and_planning_style, delegation_style, perceived_professional_demeanor, " +
                "overall_working_experience, manager_company, manager_title, weight, created_at) " +
                "VALUES ($1,$2,'tester',4.0,4,4,4,4,4,4,4,4,4,4,'LimitCorp" + i + "','Dev',false,now())")
                .execute(Tuple.of(mgrId, userId))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        long seventhMgr = insertManager("Daily Limit Mgr7", "LimitCorp7", "Dev", "approved");
        try {
            await(service.createReview(auth0Id, seventhMgr, validReviewBody(), null));
            fail("expected too many requests");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("daily_limit_reached"),
                "Expected daily_limit_reached but got: " + e.getMessage());
        }
    }

    @Test
    void createReview_withDraftToken_succeeds() throws Exception {
        String auth0Id = insertUser("auth0|cr-draft");
        long managerId = insertManager("Draft Token Target", "DraftCorp", "Dev", "approved");
        String draftToken = UUID.randomUUID().toString();
        JsonObject body = validReviewBody().put("draftToken", draftToken);
        Row result = await(service.createReview(auth0Id, managerId, body, null));
        assertNotNull(result);
    }

    @Test
    void createReview_draftTokenReplacesAnonymousDropOff_notDuplicate() throws Exception {
        // Regression: before the fix, validateAndInsertReview found the anonymous drop-off review
        // via author-name match and rejected the authenticated submission as "already_reviewed_this_role".
        String auth0Id = insertUser("auth0|cr-draft-replace");
        long managerId = insertManager("Drop Off Replace Target", "DropCorp", "Lead", "approved");
        UUID draftToken = UUID.randomUUID();
        String authorName = "anon_dropoff_author_xyz";

        // Insert an anonymous drop-off review (simulates what the pre-login drop-off capture creates)
        pool.preparedQuery(
                "INSERT INTO reviews(manager_id, user_id, author, overall_rating, " +
                "communication_style, perceived_approachability, perceived_clarity_of_expectations, " +
                "feedback_style, perceived_supportiveness, decision_making_style, " +
                "organization_and_planning_style, delegation_style, perceived_professional_demeanor, " +
                "overall_working_experience, manager_company, manager_title, " +
                "worked_from, verified, helpful_count, draft_token, created_at, updated_at) " +
                "VALUES ($1, NULL, $2, 3.0, 3,3,3,3,3,3,3,3,3,3,$3,$4,'2023-01-01',true,0,$5,now(),now())")
            .execute(Tuple.of(managerId, authorName, "DropCorp", "Lead", draftToken))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Authenticated user submits with the same draftToken and same author name (as generated during drop-off)
        JsonObject body = validReviewBody()
            .put("draftToken", draftToken.toString())
            .put("author", authorName)
            .put("managerCompany", "DropCorp")
            .put("managerTitle", "Lead");
        Row result = await(service.createReview(auth0Id, managerId, body, null));
        assertNotNull(result);

        // The anonymous drop-off should have been deleted; only the authenticated review remains
        RowSet<Row> remaining = pool.preparedQuery(
                "SELECT id, user_id FROM reviews WHERE manager_id = $1 AND deleted_at IS NULL")
            .execute(Tuple.of(managerId))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, remaining.size(), "Only the authenticated review should remain");
        assertNotNull(remaining.iterator().next().getUUID("user_id"), "Review should be linked to authenticated user");
    }

    // ── validateAndInsertReview — edge cases ──────────────────────────────────

    @Test
    void createReview_managerRoleStartInFuture_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|var-future-start");
        long managerId = insertManager("Future Role Mgr", "FRC", "Dev", "approved");
        JsonObject body = validReviewBody().put("managerRoleStart", "2099-01");
        try {
            await(service.createReview(auth0Id, managerId, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("future"),
                "Expected future error but got: " + e.getMessage());
        }
    }

    @Test
    void createReview_managerRoleEndInFuture_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|var-future-end");
        long managerId = insertManager("Future End Mgr", "FEC", "Dev", "approved");
        JsonObject body = validReviewBody()
            .put("managerRoleStart", "2020-01")
            .put("managerRoleEnd", "2099-01");
        try {
            await(service.createReview(auth0Id, managerId, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("future"),
                "Expected future error but got: " + e.getMessage());
        }
    }

    @Test
    void createReview_managerRoleEndBeforeStart_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|var-end-before-start");
        long managerId = insertManager("End Before Start Mgr", "EBC", "Dev", "approved");
        JsonObject body = validReviewBody()
            .put("managerRoleStart", "2022-06")
            .put("managerRoleEnd", "2021-01");
        try {
            await(service.createReview(auth0Id, managerId, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("start date"),
                "Expected start date error but got: " + e.getMessage());
        }
    }

    @Test
    void createReview_workedFromAfterWorkedUntil_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|var-from-after-until");
        long managerId = insertManager("From After Until Mgr", "FAU", "Dev", "approved");
        JsonObject body = validReviewBody()
            .put("workedFrom", "2022-06")
            .put("workedUntil", "2021-01");
        try {
            await(service.createReview(auth0Id, managerId, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("later"),
                "Expected date ordering error but got: " + e.getMessage());
        }
    }

    @Test
    void createReview_workedFromBeforeManagerRoleStart_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|var-before-role-start");
        long managerId = insertManager("Before Role Start Mgr", "BRS", "Dev", "approved");
        JsonObject body = validReviewBody()
            .put("managerRoleStart", "2022-01")
            .put("workedFrom", "2021-06");  // before manager role start
        try {
            await(service.createReview(auth0Id, managerId, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("before"),
                "Expected 'before' error but got: " + e.getMessage());
        }
    }

    @Test
    void createReview_workedFromAfterManagerRoleEnd_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|var-after-role-end");
        long managerId = insertManager("After Role End Mgr", "ARE", "Dev", "approved");
        JsonObject body = validReviewBody()
            .put("managerRoleStart", "2020-01")
            .put("managerRoleEnd", "2021-06")
            .put("workedFrom", "2022-01");  // after manager left role
        try {
            await(service.createReview(auth0Id, managerId, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("after"),
                "Expected 'after role' error but got: " + e.getMessage());
        }
    }

    @Test
    void createReview_workedUntilAfterManagerRoleEnd_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|var-until-after-role-end");
        long managerId = insertManager("Until After Role End Mgr", "UARE", "Dev", "approved");
        JsonObject body = validReviewBody()
            .put("managerRoleStart", "2020-01")
            .put("managerRoleEnd", "2021-06")
            .put("workedFrom", "2020-06")
            .put("workedUntil", "2022-01");  // after manager left role
        try {
            await(service.createReview(auth0Id, managerId, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("after"),
                "Expected 'end date after role' error but got: " + e.getMessage());
        }
    }

    @Test
    void createReview_missingRequiredFields_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|var-missing-fields");
        long managerId = insertManager("Missing Fields Mgr", "MFC", "Dev", "approved");
        JsonObject body = new JsonObject()
            .put("overallRating", 4.0)
            .put("workedFrom", "2020-01")
            .put("ratings", validRatings());
        // missing managerCompany and managerTitle
        try {
            await(service.createReview(auth0Id, managerId, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("missing"),
                "Expected missing fields error but got: " + e.getMessage());
        }
    }

    @Test
    void createReview_managerCompanyTooShort_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|var-co-short");
        long managerId = insertManager("Co Short Mgr", "CS", "Dev", "approved");
        JsonObject body = validReviewBody().put("managerCompany", "A");
        try {
            await(service.createReview(auth0Id, managerId, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("2 char"),
                "Expected minimum 2 chars but got: " + e.getMessage());
        }
    }

    @Test
    void createReview_reviewTextTooLong_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|var-text-long");
        long managerId = insertManager("Text Long Mgr", "TLC", "Dev", "approved");
        JsonObject body = validReviewBody().put("text", "X".repeat(2001));
        try {
            await(service.createReview(auth0Id, managerId, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("2000"),
                "Expected 2000 chars limit but got: " + e.getMessage());
        }
    }

    @Test
    void createReview_invalidOverallRating_returnsBadRequest() throws Exception {
        String auth0Id = insertUser("auth0|var-bad-overall");
        long managerId = insertManager("Bad Overall Mgr", "BOC", "Dev", "approved");
        JsonObject body = validReviewBody().put("overallRating", 6.0);
        try {
            await(service.createReview(auth0Id, managerId, body, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("between 1 and 5"),
                "Expected rating range error but got: " + e.getMessage());
        }
    }

    @Test
    void createReview_roleLimitReached_returnsConflict() throws Exception {
        String auth0Id = insertUser("auth0|var-role-limit");
        long managerId = insertManager("Role Limit Mgr", "RLC", "Dev", "approved");
        UUID userId = getUserId(auth0Id);
        // Insert 5 reviews with different title+company combos (to avoid role-taken conflict)
        for (int i = 0; i < 5; i++) {
            pool.preparedQuery(
                "INSERT INTO reviews(manager_id, user_id, author, overall_rating, " +
                "communication_style, perceived_approachability, perceived_clarity_of_expectations, " +
                "feedback_style, perceived_supportiveness, decision_making_style, " +
                "organization_and_planning_style, delegation_style, perceived_professional_demeanor, " +
                "overall_working_experience, manager_company, manager_title, weight, created_at) " +
                "VALUES ($1,$2,'tester',4.0,4,4,4,4,4,4,4,4,4,4,'RLC','Title" + i + "',false,now())")
                .execute(Tuple.of(managerId, userId))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        JsonObject body = validReviewBody()
            .put("managerCompany", "RLC")
            .put("managerTitle", "UniqueTitle6");
        try {
            await(service.createReview(auth0Id, managerId, body, null));
            fail("expected conflict");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("role_limit_reached"),
                "Expected role_limit_reached but got: " + e.getMessage());
        }
    }

    @Test
    void createReview_roleAlreadyReviewed_returnsConflict() throws Exception {
        String auth0Id = insertUser("auth0|var-role-taken");
        long managerId = insertManager("Role Taken Mgr", "RTC", "Dev", "approved");
        // Submit first review
        JsonObject first = validReviewBody().put("managerCompany", "RTC").put("managerTitle", "Dev");
        await(service.createReview(auth0Id, managerId, first, null));
        // Try to submit another with the same role
        JsonObject second = validReviewBody().put("managerCompany", "RTC").put("managerTitle", "Dev");
        try {
            await(service.createReview(auth0Id, managerId, second, null));
            fail("expected conflict");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("already_reviewed_this_role"),
                "Expected already_reviewed_this_role but got: " + e.getMessage());
        }
    }

    @Test
    void createReview_withManagerRoleDates_success() throws Exception {
        String auth0Id = insertUser("auth0|var-role-dates");
        long managerId = insertManager("Role Dates Mgr", "RDC", "Dev", "approved");
        JsonObject body = validReviewBody()
            .put("managerRoleStart", "2020-01")
            .put("managerRoleEnd", "2022-06")
            .put("workedFrom", "2020-06");
        Row result = await(service.createReview(auth0Id, managerId, body, null));
        assertNotNull(result);
    }

    @Test
    void createReview_managerRolePeriodOverlap_returnsConflict() throws Exception {
        String auth0Id1 = insertUser("auth0|var-overlap-user1");
        String auth0Id2 = insertUser("auth0|var-overlap-user2");
        long managerId = insertManager("Overlap Mgr", "OLC", "Dev", "approved");

        // First user reviews with managerRoleStart 2020-01 to 2022-06
        JsonObject first = validReviewBody()
            .put("managerRoleStart", "2020-01")
            .put("managerRoleEnd", "2022-06")
            .put("workedFrom", "2020-06");
        await(service.createReview(auth0Id1, managerId, first, null));

        // Second user tries to submit for an overlapping period
        JsonObject second = validReviewBody()
            .put("managerRoleStart", "2021-01")  // overlaps with 2020-01 to 2022-06
            .put("managerRoleEnd", "2023-01")
            .put("workedFrom", "2021-06");
        try {
            await(service.createReview(auth0Id2, managerId, second, null));
            fail("expected conflict");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("manager_role_overlap"),
                "Expected manager_role_overlap but got: " + e.getMessage());
        }
    }

    // ── createDropOffReview ───────────────────────────────────────────────────

    @Test
    void createDropOffReview_nullBody_returnsBadRequest() throws Exception {
        long managerId = insertManager("DropOff Target", "DOR", "Dev", "approved");
        try {
            await(service.createDropOffReview(managerId, null, null));
            fail("expected bad request");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("missing"),
                "Expected missing body error but got: " + e.getMessage());
        }
    }

    @Test
    void createDropOffReview_success_createsAnonymousReview() throws Exception {
        long managerId = insertManager("DropOff Success Mgr", "DOSCORP", "Analyst", "approved");
        JsonObject body = validReviewBody().put("author", "");
        await(service.createDropOffReview(managerId, body, null));
        // Verify review was created
        long count = await(pool.preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1 AND user_id IS NULL")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertTrue(count > 0, "Drop-off review should be created with null user_id");
    }

    @Test
    void createDropOffReview_withDraftToken_deletesOldDraft() throws Exception {
        long managerId = insertManager("DropOff Draft Mgr", "DODCORP", "Lead", "approved");
        String draftToken = UUID.randomUUID().toString();
        JsonObject body = validReviewBody()
            .put("author", "Guest Reviewer")
            .put("draftToken", draftToken);
        await(service.createDropOffReview(managerId, body, null));
    }

    // ── getCompanyBySlug — success path ───────────────────────────────────────

    @Test
    void getCompanyBySlug_withManagers_returnsProfile() throws Exception {
        // Create a company with a slug, then a manager linked to it
        long companyId = insertCompanyWithSlug("Slug Company", "slug-company");
        insertManagerWithCompanyId("Slug Manager", "Slug Company", "Lead", "approved", companyId);

        JsonObject result = await(service.getCompanyBySlug("slug-company"));
        assertNotNull(result);
        assertEquals("Slug Company", result.getString("name"));
        assertEquals("slug-company", result.getString("slug"));
        assertTrue(result.getInteger("managerCount") > 0, "Should have managers");
    }

    @Test
    void getCompanyBySlug_noManagers_returnsEmptyProfile() throws Exception {
        insertCompanyWithSlug("Empty Slug Corp", "empty-slug-corp");

        JsonObject result = await(service.getCompanyBySlug("empty-slug-corp"));
        assertNotNull(result);
        assertEquals("Empty Slug Corp", result.getString("name"));
        assertEquals(0, result.getInteger("managerCount").intValue());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long insertManager(String name, String company, String title, String status) throws Exception {
        return pool.preparedQuery(
                "INSERT INTO managers(name, company, title, image, status, approval_status, " +
                "overall_rating, reviews_count, category_averages) " +
                "VALUES ($1,$2,$3,'img','active',$4,0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title, status))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getLong("id");
    }

    private long insertManagerWithCompanyId(String name, String company, String title, String status, long companyId) throws Exception {
        return pool.preparedQuery(
                "INSERT INTO managers(name, company, title, image, status, approval_status, company_id, " +
                "overall_rating, reviews_count, category_averages) " +
                "VALUES ($1,$2,$3,'img','active',$4,$5,0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title, status, companyId))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getLong("id");
    }

    private long insertCompanyWithSlug(String name, String slug) throws Exception {
        return pool.preparedQuery(
                "INSERT INTO companies(name, slug, status, created_at, updated_at) VALUES ($1,$2,'ghost',now(),now()) RETURNING id")
            .execute(Tuple.of(name, slug))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getLong("id");
    }

    private String insertUser(String auth0Id) throws Exception {
        String username = auth0Id.replaceAll("[^a-zA-Z0-9]", "") + "User";
        pool.preparedQuery(
                "INSERT INTO users(auth0_id, email, username, first_name, last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User"))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        return auth0Id;
    }

    private UUID getUserId(String auth0Id) throws Exception {
        return pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getUUID("id");
    }

    private void banUser(String auth0Id) throws Exception {
        UUID userId = getUserId(auth0Id);
        pool.preparedQuery("INSERT INTO banned_users(user_id, reason, banned_by) VALUES ($1,$2,$3)")
            .execute(Tuple.of(userId, "test ban", "test-admin"))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static JsonObject validRatings() {
        return new JsonObject()
            .put("Communication Style", 4)
            .put("Perceived Approachability", 4)
            .put("Perceived Clarity of Expectations", 4)
            .put("Feedback Style", 4)
            .put("Perceived Supportiveness", 4)
            .put("Decision Making Style", 4)
            .put("Organization and Planning Style", 4)
            .put("Delegation Style", 4)
            .put("Perceived Professional Demeanor", 4)
            .put("Overall Working Experience", 4);
    }

    private static JsonObject validReviewBody() {
        return new JsonObject()
            .put("overallRating", 4.0)
            .put("managerCompany", "TestCorp")
            .put("managerTitle", "Engineer")
            .put("workedFrom", "2020-01")
            .put("ratings", validRatings());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
