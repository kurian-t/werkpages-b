package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PreparedQuery;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ManagerService review submission logic.
 * All repositories and the DB pool are mocked; no network or database required.
 *
 * Key Mockito rule followed throughout: never call when(mock.x()) inside the
 * argument of another when(...).thenReturn(...) chain. All mock objects are
 * constructed and stubbed BEFORE being passed to outer when/thenReturn calls.
 */
@SuppressWarnings("unchecked")
class ManagerServiceValidationTest {

    private static final String AUTH0_ID   = "auth0|test-user";
    private static final long   MANAGER_ID = 42L;
    private static final UUID   USER_ID    = UUID.randomUUID();
    private static final String USERNAME   = "TestUser99";

    private UserRepository    userRepo;
    private ReviewRepository  reviewRepo;
    private ManagerRepository managerRepo;
    private EditRepository    editRepo;
    private ReportRepository  reportRepo;
    private CompanyRepository companyRepo;
    private Pool              pool;
    private ManagerService    service;

    @BeforeEach
    void setUp() {
        userRepo    = mock(UserRepository.class);
        reviewRepo  = mock(ReviewRepository.class);
        managerRepo = mock(ManagerRepository.class);
        editRepo    = mock(EditRepository.class);
        reportRepo  = mock(ReportRepository.class);
        companyRepo = mock(CompanyRepository.class);
        pool        = mock(Pool.class);
        when(companyRepo.refreshCompanyStats()).thenReturn(Future.succeededFuture());
        when(companyRepo.updateCompanyStatsForManager(anyLong())).thenReturn(Future.succeededFuture());
        // The stats write is awaited now rather than fired and forgotten, so the mock has
        // to answer with a real future instead of Mockito's default null.
        when(companyRepo.syncStatsForManager(anyLong())).thenReturn(Future.succeededFuture());
        service     = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, companyRepo, pool, company -> null);

        // Build mock data BEFORE any when() chains to avoid nested stubbing
        Row defaultUser        = userRow(USER_ID, USERNAME, false);
        RowSet<Row> emptyRs    = rowSetOf();

        when(userRepo.findByAuth0IdWithBan(AUTH0_ID))
            .thenReturn(Future.succeededFuture(Optional.of(defaultUser)));
        when(reviewRepo.countSubmittedTodayByUser(USER_ID))
            .thenReturn(Future.succeededFuture(0L));
        when(reviewRepo.findRecentDeletion(USER_ID, MANAGER_ID))
            .thenReturn(Future.succeededFuture(Optional.empty()));
        when(reviewRepo.findByUserForValidation(eq(USER_ID), anyString()))
            .thenReturn(Future.succeededFuture(emptyRs));
        doNothing().when(managerRepo).recalculateInBackground(anyLong());
        when(reviewRepo.deleteSeedReview(anyLong())).thenReturn(Future.succeededFuture());
    }

    // ── User / auth checks ────────────────────────────────────────────────────

    @Test
    void userNotFound_returns404() {
        when(userRepo.findByAuth0IdWithBan(AUTH0_ID))
            .thenReturn(Future.succeededFuture(Optional.empty()));

        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, validBody(), null));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void bannedUser_returns403() {
        Row banned = userRow(USER_ID, USERNAME, true);
        when(userRepo.findByAuth0IdWithBan(AUTH0_ID))
            .thenReturn(Future.succeededFuture(Optional.of(banned)));

        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, validBody(), null));
        assertEquals(403, ex.getStatusCode());
        assertEquals("account_suspended", ex.getMessage());
    }

    // ── Rate-limiting checks ──────────────────────────────────────────────────

    @Test
    void dailyLimitExceeded_returns429() {
        when(reviewRepo.countSubmittedTodayByUser(USER_ID))
            .thenReturn(Future.succeededFuture(6L));

        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, validBody(), null));
        assertEquals(429, ex.getStatusCode());
        assertEquals("daily_limit_reached", ex.getMessage());
    }

    @Test
    void cooldownActive_returns409WithCooldownDate() {
        when(reviewRepo.findRecentDeletion(USER_ID, MANAGER_ID))
            .thenReturn(Future.succeededFuture(Optional.of(OffsetDateTime.now().minusDays(1))));

        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, validBody(), null));
        assertEquals(409, ex.getStatusCode());
        assertTrue(ex.getMessage().startsWith("review_cooldown:"),
            "Expected cooldown message, got: " + ex.getMessage());
    }

    @Test
    void cooldownExpired_doesNotBlock() throws Exception {
        when(reviewRepo.findRecentDeletion(USER_ID, MANAGER_ID))
            .thenReturn(Future.succeededFuture(Optional.of(OffsetDateTime.now().minusDays(31))));
        stubHappyPathTransaction();

        assertDoesNotThrow(() -> await(service.createReview(AUTH0_ID, MANAGER_ID, validBody(), null)));
    }

    // ── Field-level validation ────────────────────────────────────────────────

    @Test
    void nullBody_returns400() {
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, null, null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void missingRatings_returns400() {
        JsonObject body = validBody().putNull("ratings");
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void missingManagerCompany_returns400() {
        JsonObject body = validBody().putNull("managerCompany");
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void missingManagerTitle_returns400() {
        JsonObject body = validBody().putNull("managerTitle");
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void companyTooLong_returns400() {
        JsonObject body = validBody().put("managerCompany", "A".repeat(101));
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("company"));
    }

    @Test
    void titleTooLong_returns400() {
        JsonObject body = validBody().put("managerTitle", "A".repeat(101));
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("title"));
    }

    @Test
    void reviewTextTooLong_returns400() {
        JsonObject body = validBody().put("text", "A".repeat(2001));
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("2000"));
    }

    // ── Date validation ───────────────────────────────────────────────────────

    @Test
    void workedFromMissing_returns400() {
        JsonObject body = validBody().putNull("workedFrom");
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("start date"));
    }

    @Test
    void workedFromInFuture_returns400() {
        JsonObject body = validBody().put("workedFrom", nextMonth());
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("future"));
    }

    @Test
    void workedUntilInFuture_returns400() {
        JsonObject body = validBody().put("workedUntil", nextMonth());
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("future"));
    }

    @Test
    void workedFromAfterWorkedUntil_returns400() {
        JsonObject body = validBody().put("workedFrom", "2023-06").put("workedUntil", "2022-01");
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void managerRoleStartInFuture_returns400() {
        JsonObject body = validBody().put("managerRoleStart", nextMonth());
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("future"));
    }

    @Test
    void managerRoleEndBeforeStart_returns400() {
        JsonObject body = validBody()
            .put("managerRoleStart", "2022-01")
            .put("managerRoleEnd",   "2021-01");
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
    }

    // ── Rating validation ─────────────────────────────────────────────────────

    @Test
    void overallRatingTooLow_returns400() {
        JsonObject body = validBody().put("overallRating", 0.5);
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("rating"));
    }

    @Test
    void overallRatingTooHigh_returns400() {
        JsonObject body = validBody().put("overallRating", 5.5);
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("rating"));
    }

    @Test
    void categoryRatingOutOfRange_returns400() {
        JsonObject ratings = validRatings().put("Communication Style", 6.0);
        JsonObject body    = validBody().put("ratings", ratings);
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("Communication Style"));
    }

    // ── Business rule checks (after DB validation query) ─────────────────────

    @Test
    void roleCapReached_returns409() {
        // Build 5 existing review rows BEFORE the when() chain
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(reviewRow(MANAGER_ID, "Role " + i, "Corp"));
        RowSet<Row> capRs = rowSetOf(rows.toArray(new Row[0]));

        when(reviewRepo.findByUserForValidation(eq(USER_ID), anyString()))
            .thenReturn(Future.succeededFuture(capRs));

        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, validBody(), null));
        assertEquals(409, ex.getStatusCode());
        assertEquals("role_limit_reached", ex.getMessage());
    }

    @Test
    void duplicateRoleTitleAndCompany_returns409() {
        Row existing = reviewRow(MANAGER_ID, "Engineering Manager", "Acme Corp");
        RowSet<Row> dupRs = rowSetOf(existing);

        when(reviewRepo.findByUserForValidation(eq(USER_ID), anyString()))
            .thenReturn(Future.succeededFuture(dupRs));

        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, validBody(), null));
        assertEquals(409, ex.getStatusCode());
        assertEquals("already_reviewed_this_role", ex.getMessage());
    }

    @Test
    void duplicateRoleCaseInsensitive_returns409() {
        Row existing = reviewRow(MANAGER_ID, "ENGINEERING MANAGER", "ACME CORP");
        RowSet<Row> dupRs = rowSetOf(existing);

        when(reviewRepo.findByUserForValidation(eq(USER_ID), anyString()))
            .thenReturn(Future.succeededFuture(dupRs));

        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, validBody(), null));
        assertEquals(409, ex.getStatusCode());
        assertEquals("already_reviewed_this_role", ex.getMessage());
    }

    @Test
    void differentCompany_sameTitleAllowed() throws Exception {
        Row existing  = reviewRow(MANAGER_ID, "Engineering Manager", "Other Corp");
        RowSet<Row> rs = rowSetOf(existing);

        when(reviewRepo.findByUserForValidation(eq(USER_ID), anyString()))
            .thenReturn(Future.succeededFuture(rs));
        stubHappyPathTransaction();

        assertDoesNotThrow(() -> await(service.createReview(AUTH0_ID, MANAGER_ID, validBody(), null)));
    }

    @Test
    void managerRoleOverlap_returns409() {
        RowSet<Row> emptyUserRs = rowSetOf();
        Row overlapRow = rolePeriodRow(
            "Other Manager", "Other Corp",
            LocalDate.of(2021, 1, 1), LocalDate.of(2023, 12, 31));
        RowSet<Row> rolePeriodRs = rowSetOf(overlapRow);

        when(reviewRepo.findByUserForValidation(eq(USER_ID), anyString()))
            .thenReturn(Future.succeededFuture(emptyUserRs));
        when(reviewRepo.findRolePeriodsForManager(MANAGER_ID))
            .thenReturn(Future.succeededFuture(rolePeriodRs));

        JsonObject body = validBody()
            .put("managerRoleStart", "2022-01")
            .put("managerRoleEnd",   "2023-06");

        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(409, ex.getStatusCode());
        assertTrue(ex.getMessage().startsWith("manager_role_overlap:"));
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void happyPath_returnsReviewRow() throws Exception {
        stubHappyPathTransaction();

        Row result = await(service.createReview(AUTH0_ID, MANAGER_ID, validBody(), "https://logo.test"));

        assertNotNull(result);
        verify(managerRepo).recalculateInBackground(MANAGER_ID);
    }

    @Test
    void happyPath_managerUpdateCalledWhenReviewIsMostCurrent() throws Exception {
        SqlConnection conn = mock(SqlConnection.class);
        UUID newId = UUID.randomUUID();

        Row insertedRow = insertedReviewRow(newId, MANAGER_ID, "Acme Corp", "Engineering Manager");
        Row currentRow  = mostCurrentRow(newId); // same ID = is the most current
        PreparedQuery<RowSet<Row>> updatePq = mock(PreparedQuery.class);

        stubConnInsert(conn, insertedRow);
        stubConnSelectCurrent(conn, currentRow);
        stubConnCompany(conn);
        RowSet<Row> updateRs = rowSetOf();
        when(updatePq.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(updateRs));
        when(conn.preparedQuery(argThat(s -> s != null && s.contains("UPDATE managers")))).thenReturn(updatePq);

        when(pool.withTransaction(any())).thenAnswer(inv -> {
            Function<SqlConnection, Future<Row>> fn = inv.getArgument(0);
            return fn.apply(conn);
        });

        await(service.createReview(AUTH0_ID, MANAGER_ID, validBody(), "https://logo.test"));

        verify(updatePq).execute(any(Tuple.class));
    }

    @Test
    void happyPath_differentMostCurrentReview_managerUpdatedWithCurrentData() throws Exception {
        SqlConnection conn  = mock(SqlConnection.class);
        UUID newId          = UUID.randomUUID();
        UUID newerReviewId  = UUID.randomUUID(); // a newer review already exists

        Row insertedRow = insertedReviewRow(newId, MANAGER_ID, "Acme Corp", "Engineering Manager");
        // The most-current review belongs to someone else — different company/title
        Row currentRow  = mostCurrentRow(newerReviewId, "Newer Corp", "Senior Lead");
        PreparedQuery<RowSet<Row>> updatePq = mock(PreparedQuery.class);
        RowSet<Row> updateRs = rowSetOf();
        when(updatePq.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(updateRs));

        stubConnInsert(conn, insertedRow);
        stubConnSelectCurrent(conn, currentRow);
        stubConnCompany(conn);
        when(conn.preparedQuery(argThat(s -> s != null && s.contains("UPDATE managers")))).thenReturn(updatePq);

        when(pool.withTransaction(any())).thenAnswer(inv -> {
            Function<SqlConnection, Future<Row>> fn = inv.getArgument(0);
            return fn.apply(conn);
        });

        await(service.createReview(AUTH0_ID, MANAGER_ID, validBody(), "https://logo.test"));

        // Manager profile is always synced from the most-current review — even if it's not the new one
        verify(updatePq).execute(any(Tuple.class));
    }

    @Test
    void transactionRollback_onManagerUpdateFailure_propagatesError() {
        SqlConnection conn  = mock(SqlConnection.class);
        UUID newId = UUID.randomUUID();
        PreparedQuery<RowSet<Row>> updatePq = mock(PreparedQuery.class);

        stubConnInsert(conn, insertedReviewRow(newId, MANAGER_ID, "Acme Corp", "Engineering Manager"));
        stubConnSelectCurrent(conn, mostCurrentRow(newId));
        stubConnCompany(conn);
        when(updatePq.execute(any(Tuple.class)))
            .thenReturn(Future.failedFuture(new RuntimeException("simulated DB error")));
        when(conn.preparedQuery(argThat(s -> s != null && s.contains("UPDATE managers")))).thenReturn(updatePq);

        when(pool.withTransaction(any())).thenAnswer(inv -> {
            Function<SqlConnection, Future<Row>> fn = inv.getArgument(0);
            return fn.apply(conn); // withTransaction propagates failures
        });

        Throwable cause = assertThrowsAsync(service.createReview(AUTH0_ID, MANAGER_ID, validBody(), null));
        assertNotNull(cause);
        assertEquals("simulated DB error", cause.getMessage());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * RowSet<Row> backed by a fixed list.
     * iterator() returns a fresh RowIterator<Row> mock each call, configured with
     * hasNext/next answers — avoiding the ClassCastException from returning a plain
     * java.util.Iterator where Vert.x expects a RowIterator.
     * Uses doAnswer (not thenReturn) so no Mockito pending-stub context is entered.
     */
    private static RowSet<Row> rowSetOf(Row... rows) {
        List<Row> list = new ArrayList<>(Arrays.asList(rows));
        RowSet<Row> rs = mock(RowSet.class);
        doAnswer(inv -> {
            AtomicInteger idx = new AtomicInteger(0);
            RowIterator<Row> ri = mock(RowIterator.class);
            when(ri.hasNext()).thenAnswer(i -> idx.get() < list.size());
            when(ri.next()).thenAnswer(i -> list.get(idx.getAndIncrement()));
            return ri;
        }).when(rs).iterator();
        when(rs.size()).thenReturn(list.size());
        return rs;
    }

    private static Row userRow(UUID id, String username, boolean banned) {
        Row row = mock(Row.class);
        when(row.getUUID("id")).thenReturn(id);
        when(row.getString("username")).thenReturn(username);
        when(row.getBoolean("is_banned")).thenReturn(banned);
        return row;
    }

    private static Row reviewRow(long managerId, String title, String company) {
        Row row = mock(Row.class);
        when(row.getLong("manager_id")).thenReturn(managerId);
        when(row.getString("manager_title")).thenReturn(title);
        when(row.getString("manager_company")).thenReturn(company);
        when(row.getLocalDate("worked_from")).thenReturn(null);
        when(row.getLocalDate("worked_until")).thenReturn(null);
        when(row.getLocalDate("manager_role_start")).thenReturn(null);
        when(row.getLocalDate("manager_role_end")).thenReturn(null);
        return row;
    }

    private static Row rolePeriodRow(String title, String company, LocalDate start, LocalDate end) {
        Row row = mock(Row.class);
        when(row.getString("manager_title")).thenReturn(title);
        when(row.getString("manager_company")).thenReturn(company);
        when(row.getLocalDate("manager_role_start")).thenReturn(start);
        when(row.getLocalDate("manager_role_end")).thenReturn(end);
        return row;
    }

    private static Row insertedReviewRow(UUID id, long managerId, String company, String title) {
        Row row = mock(Row.class);
        when(row.getUUID("id")).thenReturn(id);
        when(row.getLong("manager_id")).thenReturn(managerId);
        when(row.getString("manager_company")).thenReturn(company);
        when(row.getString("manager_title")).thenReturn(title);
        return row;
    }

    private static Row mostCurrentRow(UUID id) {
        return mostCurrentRow(id, "Acme Corp", "Engineering Manager");
    }

    private static Row mostCurrentRow(UUID id, String company, String title) {
        Row row = mock(Row.class);
        when(row.getUUID("id")).thenReturn(id);
        when(row.getString("manager_company")).thenReturn(company);
        when(row.getString("manager_title")).thenReturn(title);
        return row;
    }

    private static JsonObject validRatings() {
        JsonObject r = new JsonObject();
        for (String key : new String[]{
                "Communication Style", "Perceived Approachability",
                "Perceived Clarity of Expectations", "Feedback Style",
                "Perceived Supportiveness", "Decision Making Style",
                "Organization and Planning Style", "Delegation Style",
                "Perceived Professional Demeanor", "Overall Working Experience"}) {
            r.put(key, 3.0);
        }
        return r;
    }

    private static JsonObject validBody() {
        return new JsonObject()
            .put("overallRating",  3.0)
            .put("ratings",        validRatings())
            .put("managerCompany", "Acme Corp")
            .put("managerTitle",   "Engineering Manager")
            .put("workedFrom",     "2022-01")
            .put("workedUntil",    "2023-06")
            .put("author",         "AnonUser42")
            .put("authorType",     "anonymous");
    }

    private static String nextMonth() {
        LocalDate d = LocalDate.now().plusMonths(1);
        return d.getYear() + "-" + String.format("%02d", d.getMonthValue());
    }

    /**
     * Stubs the Pool to run the transaction function against a mocked SqlConnection
     * that successfully inserts a review, finds it as the most current, and updates
     * the manager profile.
     */
    private void stubHappyPathTransaction() {
        SqlConnection conn = mock(SqlConnection.class);
        UUID newId = UUID.randomUUID();

        RowSet<Row> updateRs = rowSetOf(); // pre-create before any when/thenReturn
        PreparedQuery<RowSet<Row>> updatePq = mock(PreparedQuery.class);
        when(updatePq.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(updateRs));
        when(conn.preparedQuery(argThat(s -> s != null && s.contains("UPDATE managers"))))
            .thenReturn(updatePq);

        stubConnInsert(conn, insertedReviewRow(newId, MANAGER_ID, "Acme Corp", "Engineering Manager"));
        stubConnSelectCurrent(conn, mostCurrentRow(newId));
        stubConnCompany(conn);

        when(pool.withTransaction(any())).thenAnswer(inv -> {
            Function<SqlConnection, Future<Row>> fn = inv.getArgument(0);
            return fn.apply(conn);
        });
    }

    private void stubConnInsert(SqlConnection conn, Row row) {
        RowSet<Row> rs = rowSetOf(row);
        PreparedQuery<RowSet<Row>> pq = mock(PreparedQuery.class);
        when(pq.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(rs));
        when(conn.preparedQuery(argThat(s -> s != null && s.contains("INSERT INTO reviews"))))
            .thenReturn(pq);
    }

    private void stubConnCompany(SqlConnection conn) {
        // SELECT-first pattern: return empty RowSet so the code falls through to INSERT
        RowSet<Row> emptyRs = rowSetOf();
        PreparedQuery<RowSet<Row>> selectPq = mock(PreparedQuery.class);
        when(selectPq.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(emptyRs));
        when(conn.preparedQuery(argThat(s -> s != null && s.contains("SELECT id FROM companies"))))
            .thenReturn(selectPq);

        Row companyRow = mock(Row.class);
        when(companyRow.getLong("id")).thenReturn(1L);
        RowSet<Row> rs = rowSetOf(companyRow);
        PreparedQuery<RowSet<Row>> pq = mock(PreparedQuery.class);
        when(pq.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(rs));
        when(conn.preparedQuery(argThat(s -> s != null && s.contains("INSERT INTO companies"))))
            .thenReturn(pq);
    }

    private void stubConnSelectCurrent(SqlConnection conn, Row row) {
        RowSet<Row> rs = rowSetOf(row);
        PreparedQuery<RowSet<Row>> pq = mock(PreparedQuery.class);
        when(pq.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(rs));
        when(conn.preparedQuery(argThat(s -> s != null && s.contains("SELECT id, manager_company"))))
            .thenReturn(pq);
    }

    /** Awaits a Future, asserting it fails with a ServiceException and returning it. */
    private static ServiceException assertServiceFails(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            fail("Expected future to fail but it succeeded");
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ServiceException se) return se;
            fail("Expected ServiceException but got: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            return null;
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
            return null;
        }
    }

    /** Awaits a Future, asserts it fails, and returns the failure cause. */
    private static Throwable assertThrowsAsync(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            fail("Expected future to fail but it succeeded");
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
            return null;
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // createReview — additional edge cases
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void ratingAtMinimumBoundary_accepted() throws Exception {
        JsonObject body = validBody().put("overallRating", 1.0).put("ratings", allRatings(1.0));
        stubHappyPathTransaction();
        assertDoesNotThrow(() -> await(service.createReview(AUTH0_ID, MANAGER_ID, body, null)));
    }

    @Test
    void ratingAtMaximumBoundary_accepted() throws Exception {
        JsonObject body = validBody().put("overallRating", 5.0).put("ratings", allRatings(5.0));
        stubHappyPathTransaction();
        assertDoesNotThrow(() -> await(service.createReview(AUTH0_ID, MANAGER_ID, body, null)));
    }

    @Test
    void ratingJustBelowMinimum_returns400() {
        JsonObject body = validBody().put("overallRating", 0.99);
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void ratingJustAboveMaximum_returns400() {
        JsonObject body = validBody().put("overallRating", 5.01);
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void categoryRatingAtMinimum_accepted() throws Exception {
        JsonObject body = validBody().put("ratings", allRatings(1.0));
        stubHappyPathTransaction();
        assertDoesNotThrow(() -> await(service.createReview(AUTH0_ID, MANAGER_ID, body, null)));
    }

    @Test
    void categoryRatingAtMaximum_accepted() throws Exception {
        JsonObject body = validBody().put("ratings", allRatings(5.0));
        stubHappyPathTransaction();
        assertDoesNotThrow(() -> await(service.createReview(AUTH0_ID, MANAGER_ID, body, null)));
    }

    @Test
    void malformedWorkedFrom_treatedAsMissing_returns400() {
        JsonObject body = validBody().put("workedFrom", "not-a-date");
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("start date"));
    }

    @Test
    void managerRoleEndInFuture_returns400() {
        JsonObject body = validBody().put("managerRoleStart", "2022-01").put("managerRoleEnd", nextMonth());
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("future"));
    }

    @Test
    void userDateBeforeManagerRoleStart_returns400() {
        JsonObject body = validBody().put("workedFrom", "2021-01").put("managerRoleStart", "2022-01");
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("before the manager started"));
    }

    @Test
    void userDateAfterManagerRoleEnd_returns400() {
        // workedFrom 2023-01 is after managerRoleEnd 2022-12; no workedUntil so the
        // "from > to" check doesn't fire first
        JsonObject body = new JsonObject()
            .put("overallRating",  3.0)
            .put("ratings",        validRatings())
            .put("managerCompany", "Acme Corp")
            .put("managerTitle",   "Engineering Manager")
            .put("workedFrom",     "2023-01")
            .put("author",         "AnonUser42")
            .put("authorType",     "anonymous")
            .put("managerRoleStart", "2021-01")
            .put("managerRoleEnd",   "2022-12");
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("after the manager left"));
    }

    @Test
    void userEndDateAfterManagerRoleEnd_returns400() {
        JsonObject body = validBody()
            .put("workedFrom",        "2022-06")
            .put("workedUntil",       "2024-01")
            .put("managerRoleStart",  "2022-01")
            .put("managerRoleEnd",    "2023-01");
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("after the manager left"));
    }

    @Test
    void workedFromEqualsWorkedUntil_accepted() throws Exception {
        JsonObject body = validBody().put("workedFrom", "2022-01").put("workedUntil", "2022-01");
        stubHappyPathTransaction();
        assertDoesNotThrow(() -> await(service.createReview(AUTH0_ID, MANAGER_ID, body, null)));
    }

    @Test
    void reviewsForDifferentManager_doNotCountTowardCap() throws Exception {
        long OTHER_MANAGER = MANAGER_ID + 1;
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(reviewRow(OTHER_MANAGER, "Role " + i, "Corp"));
        RowSet<Row> rs = rowSetOf(rows.toArray(new Row[0]));
        when(reviewRepo.findByUserForValidation(eq(USER_ID), anyString())).thenReturn(Future.succeededFuture(rs));
        stubHappyPathTransaction();
        assertDoesNotThrow(() -> await(service.createReview(AUTH0_ID, MANAGER_ID, validBody(), null)));
    }

    @Test
    void roleOverlap_withOpenEndedExistingRole_returns409() {
        Row openRole = rolePeriodRow("Current Role", "Corp", LocalDate.of(2020, 1, 1), null);
        RowSet<Row> emptyRs    = rowSetOf();           // pre-create before when/thenReturn
        RowSet<Row> openRoleRs = rowSetOf(openRole);   // pre-create before when/thenReturn
        when(reviewRepo.findByUserForValidation(eq(USER_ID), anyString())).thenReturn(Future.succeededFuture(emptyRs));
        when(reviewRepo.findRolePeriodsForManager(MANAGER_ID)).thenReturn(Future.succeededFuture(openRoleRs));

        JsonObject body = validBody().put("managerRoleStart", "2022-01").put("managerRoleEnd", "2023-12");
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(409, ex.getStatusCode());
        assertTrue(ex.getMessage().startsWith("manager_role_overlap:"));
    }

    @Test
    void roleOverlap_adjacentRolesNotOverlapping_allowed() throws Exception {
        // Existing: 2020-01 to 2021-12; new: 2022-01 onward — adjacent months, no overlap
        Row adjacent = rolePeriodRow("Old Role", "Corp", LocalDate.of(2020, 1, 1), LocalDate.of(2021, 12, 31));
        RowSet<Row> emptyRs    = rowSetOf();            // pre-create before when/thenReturn
        RowSet<Row> adjacentRs = rowSetOf(adjacent);    // pre-create before when/thenReturn
        when(reviewRepo.findByUserForValidation(eq(USER_ID), anyString())).thenReturn(Future.succeededFuture(emptyRs));
        when(reviewRepo.findRolePeriodsForManager(MANAGER_ID)).thenReturn(Future.succeededFuture(adjacentRs));
        stubHappyPathTransaction();

        JsonObject body = validBody().put("managerRoleStart", "2022-01").put("managerRoleEnd", "2023-12");
        assertDoesNotThrow(() -> await(service.createReview(AUTH0_ID, MANAGER_ID, body, null)));
    }

    @Test
    void selectCurrentEmpty_reviewInserted_managerNotUpdated() throws Exception {
        SqlConnection conn = mock(SqlConnection.class);
        UUID newId = UUID.randomUUID();

        stubConnInsert(conn, insertedReviewRow(newId, MANAGER_ID, "Acme Corp", "Engineering Manager"));

        RowSet<Row> emptyRs = rowSetOf();
        PreparedQuery<RowSet<Row>> selectPq = mock(PreparedQuery.class);
        when(selectPq.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(emptyRs));
        when(conn.preparedQuery(argThat(s -> s != null && s.contains("SELECT id, manager_company"))))
            .thenReturn(selectPq);

        PreparedQuery<RowSet<Row>> updatePq = mock(PreparedQuery.class);
        when(conn.preparedQuery(argThat(s -> s != null && s.contains("UPDATE managers")))).thenReturn(updatePq);

        when(pool.withTransaction(any())).thenAnswer(inv -> {
            Function<SqlConnection, Future<Row>> fn = inv.getArgument(0);
            return fn.apply(conn);
        });

        Row result = await(service.createReview(AUTH0_ID, MANAGER_ID, validBody(), null));
        assertNotNull(result);
        verify(updatePq, never()).execute(any(Tuple.class));
    }

    @Test
    void cooldownMessage_containsValidIsoDate() {
        OffsetDateTime deletedAt = OffsetDateTime.now().minusDays(10);
        when(reviewRepo.findRecentDeletion(USER_ID, MANAGER_ID))
            .thenReturn(Future.succeededFuture(Optional.of(deletedAt)));

        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, validBody(), null));
        assertEquals(409, ex.getStatusCode());
        String msg = ex.getMessage();
        assertTrue(msg.startsWith("review_cooldown:"), "Expected cooldown prefix, got: " + msg);
        String datePart = msg.substring("review_cooldown:".length());
        assertDoesNotThrow(() -> java.time.LocalDate.parse(datePart),
            "Expected ISO date after prefix, got: " + datePart);
    }

    @Test
    void authorFallsBackToUsername_whenAuthorTypeNotRecognised() throws Exception {
        JsonObject body = validBody().put("authorType", "other_type").put("author", "CustomName");
        stubHappyPathTransaction();
        // Should succeed (falls back to dbUsername); we just verify no error
        assertDoesNotThrow(() -> await(service.createReview(AUTH0_ID, MANAGER_ID, body, null)));
    }

    @Test
    void authorFallsBackToUsername_whenClientAuthorTooLong() throws Exception {
        JsonObject body = validBody()
            .put("authorType", "anonymous")
            .put("author", "A".repeat(101));
        stubHappyPathTransaction();
        assertDoesNotThrow(() -> await(service.createReview(AUTH0_ID, MANAGER_ID, body, null)));
    }

    @Test
    void missingOverallRating_returns400() {
        JsonObject body = validBody().putNull("overallRating");
        ServiceException ex = assertServiceFails(service.createReview(AUTH0_ID, MANAGER_ID, body, null));
        assertEquals(400, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // updateReview
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void updateReview_userNotFound_returns401() {
        when(userRepo.findByAuth0IdWithBan(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.empty()));
        ServiceException ex = assertServiceFails(service.updateReview(AUTH0_ID, MANAGER_ID, UUID.randomUUID(), validBody()));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void updateReview_bannedUser_returns403() {
        Row banned = userRow(USER_ID, USERNAME, true);
        when(userRepo.findByAuth0IdWithBan(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.of(banned)));
        ServiceException ex = assertServiceFails(service.updateReview(AUTH0_ID, MANAGER_ID, UUID.randomUUID(), validBody()));
        assertEquals(403, ex.getStatusCode());
        assertEquals("account_suspended", ex.getMessage());
    }

    @Test
    void updateReview_missingRequiredFields_returns400() {
        // overallRating null → synchronous check before any repo call
        JsonObject body = validBody().putNull("overallRating");
        ServiceException ex = assertServiceFails(service.updateReview(AUTH0_ID, MANAGER_ID, UUID.randomUUID(), body));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void updateReview_workedFromAfterWorkedUntil_returns400() {
        JsonObject body = validBody().put("workedFrom", "2023-06").put("workedUntil", "2022-01");
        ServiceException ex = assertServiceFails(service.updateReview(AUTH0_ID, MANAGER_ID, UUID.randomUUID(), body));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void updateReview_ratingOutOfRange_returns400() {
        JsonObject body = validBody().put("overallRating", 5.5);
        ServiceException ex = assertServiceFails(service.updateReview(AUTH0_ID, MANAGER_ID, UUID.randomUUID(), body));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void updateReview_managerRoleStartInFuture_returns400() {
        JsonObject body = validBody().put("managerRoleStart", nextMonth());
        ServiceException ex = assertServiceFails(service.updateReview(AUTH0_ID, MANAGER_ID, UUID.randomUUID(), body));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("future"));
    }

    @Test
    void updateReview_managerRoleEndBeforeStart_returns400() {
        JsonObject body = validBody()
            .put("managerRoleStart", "2022-01")
            .put("managerRoleEnd",   "2021-01");
        ServiceException ex = assertServiceFails(service.updateReview(AUTH0_ID, MANAGER_ID, UUID.randomUUID(), body));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void updateReview_companyTooLong_returns400() {
        JsonObject body = validBody().put("managerCompany", "A".repeat(101));
        ServiceException ex = assertServiceFails(service.updateReview(AUTH0_ID, MANAGER_ID, UUID.randomUUID(), body));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("company"));
    }

    @Test
    void updateReview_duplicateRole_sameTitleCompany_differentId_returns409() {
        UUID reviewId    = UUID.randomUUID();
        UUID otherId     = UUID.randomUUID();
        Row existing     = validationReviewRow(otherId, MANAGER_ID, "Engineering Manager", "Acme Corp");
        RowSet<Row> rs   = rowSetOf(existing); // pre-create before when/thenReturn
        when(reviewRepo.findByUserForValidation(eq(USER_ID), anyString())).thenReturn(Future.succeededFuture(rs));

        ServiceException ex = assertServiceFails(service.updateReview(AUTH0_ID, MANAGER_ID, reviewId, validBody()));
        assertEquals(409, ex.getStatusCode());
        assertEquals("already_reviewed_this_role", ex.getMessage());
    }

    @Test
    void updateReview_duplicateRole_sameId_allowed() throws Exception {
        UUID reviewId  = UUID.randomUUID();
        Row existing   = validationReviewRow(reviewId, MANAGER_ID, "Engineering Manager", "Acme Corp");
        RowSet<Row> rs = rowSetOf(existing); // pre-create before when/thenReturn
        when(reviewRepo.findByUserForValidation(eq(USER_ID), anyString())).thenReturn(Future.succeededFuture(rs));

        Row updatedRow = mock(Row.class);
        when(reviewRepo.update(
                any(), anyLong(), any(), any(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Future.succeededFuture(Optional.of(updatedRow)));

        Row result = await(service.updateReview(AUTH0_ID, MANAGER_ID, reviewId, validBody()));
        assertNotNull(result);
    }

    @Test
    void updateReview_reviewNotFound_returns404() {
        UUID reviewId = UUID.randomUUID();
        when(reviewRepo.update(
                any(), anyLong(), any(), any(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Future.succeededFuture(Optional.empty()));

        ServiceException ex = assertServiceFails(service.updateReview(AUTH0_ID, MANAGER_ID, reviewId, validBody()));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void updateReview_success_recalculatesManager() throws Exception {
        UUID reviewId  = UUID.randomUUID();
        Row updatedRow = mock(Row.class);
        when(reviewRepo.update(
                any(), anyLong(), any(), any(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Future.succeededFuture(Optional.of(updatedRow)));

        Row result = await(service.updateReview(AUTH0_ID, MANAGER_ID, reviewId, validBody()));
        assertNotNull(result);
        verify(managerRepo).recalculateInBackground(MANAGER_ID);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // deleteReview
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void deleteReview_userNotFound_returns401() {
        when(userRepo.findByAuth0IdWithBan(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.empty()));
        ServiceException ex = assertServiceFails(service.deleteReview(AUTH0_ID, MANAGER_ID, UUID.randomUUID()));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void deleteReview_bannedUser_returns403() {
        Row banned = userRow(USER_ID, USERNAME, true);
        when(userRepo.findByAuth0IdWithBan(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.of(banned)));
        ServiceException ex = assertServiceFails(service.deleteReview(AUTH0_ID, MANAGER_ID, UUID.randomUUID()));
        assertEquals(403, ex.getStatusCode());
        assertEquals("account_suspended", ex.getMessage());
    }

    @Test
    void deleteReview_reviewNotFound_returns404() {
        UUID reviewId = UUID.randomUUID();
        when(reviewRepo.findOwnerUserId(reviewId, MANAGER_ID))
            .thenReturn(Future.succeededFuture(Optional.empty()));

        ServiceException ex = assertServiceFails(service.deleteReview(AUTH0_ID, MANAGER_ID, reviewId));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void deleteReview_notOwner_returns403() {
        UUID reviewId   = UUID.randomUUID();
        UUID otherUser  = UUID.randomUUID();
        when(reviewRepo.findOwnerUserId(reviewId, MANAGER_ID))
            .thenReturn(Future.succeededFuture(Optional.of(otherUser)));

        ServiceException ex = assertServiceFails(service.deleteReview(AUTH0_ID, MANAGER_ID, reviewId));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void deleteReview_success_recordsCooldownAndRecalculates() throws Exception {
        UUID reviewId = UUID.randomUUID();
        when(reviewRepo.findOwnerUserId(reviewId, MANAGER_ID))
            .thenReturn(Future.succeededFuture(Optional.of(USER_ID)));
        when(reviewRepo.delete(reviewId, MANAGER_ID)).thenReturn(Future.succeededFuture(null));
        when(reviewRepo.recordDeletion(USER_ID, MANAGER_ID)).thenReturn(Future.succeededFuture(null));

        JsonObject result = (JsonObject) await(service.deleteReview(AUTH0_ID, MANAGER_ID, reviewId));

        assertTrue(result.getBoolean("success"));
        verify(reviewRepo).recordDeletion(USER_ID, MANAGER_ID);
        verify(managerRepo).recalculateInBackground(MANAGER_ID);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // replaceReview
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void replaceReview_userNotFound_returns401() {
        when(userRepo.findByAuth0IdWithBan(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.empty()));
        ServiceException ex = assertServiceFails(
            service.replaceReview(AUTH0_ID, MANAGER_ID, UUID.randomUUID(), validBody(), null));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void replaceReview_bannedUser_returns403() {
        Row banned = userRow(USER_ID, USERNAME, true);
        when(userRepo.findByAuth0IdWithBan(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.of(banned)));
        ServiceException ex = assertServiceFails(
            service.replaceReview(AUTH0_ID, MANAGER_ID, UUID.randomUUID(), validBody(), null));
        assertEquals(403, ex.getStatusCode());
        assertEquals("account_suspended", ex.getMessage());
    }

    @Test
    void replaceReview_reviewNotFound_returns404() {
        UUID oldId = UUID.randomUUID();
        when(reviewRepo.findOwnerUserId(oldId, MANAGER_ID))
            .thenReturn(Future.succeededFuture(Optional.empty()));

        ServiceException ex = assertServiceFails(
            service.replaceReview(AUTH0_ID, MANAGER_ID, oldId, validBody(), null));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void replaceReview_notOwner_returns403() {
        UUID oldId     = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        when(reviewRepo.findOwnerUserId(oldId, MANAGER_ID))
            .thenReturn(Future.succeededFuture(Optional.of(otherUser)));

        ServiceException ex = assertServiceFails(
            service.replaceReview(AUTH0_ID, MANAGER_ID, oldId, validBody(), null));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void replaceReview_success_noCooldownRecorded() throws Exception {
        UUID oldId = UUID.randomUUID();
        when(reviewRepo.findOwnerUserId(oldId, MANAGER_ID))
            .thenReturn(Future.succeededFuture(Optional.of(USER_ID)));
        when(reviewRepo.delete(oldId, MANAGER_ID)).thenReturn(Future.succeededFuture(null));
        stubHappyPathTransaction();

        Row result = await(service.replaceReview(AUTH0_ID, MANAGER_ID, oldId, validBody(), null));

        assertNotNull(result);
        verify(reviewRepo, never()).recordDeletion(any(UUID.class), anyLong());
    }

    @Test
    void replaceReview_bodyValidationStillApplied() {
        UUID oldId = UUID.randomUUID();
        when(reviewRepo.findOwnerUserId(oldId, MANAGER_ID))
            .thenReturn(Future.succeededFuture(Optional.of(USER_ID)));
        when(reviewRepo.delete(oldId, MANAGER_ID)).thenReturn(Future.succeededFuture(null));

        JsonObject badBody = validBody().put("overallRating", 0.0);
        ServiceException ex = assertServiceFails(
            service.replaceReview(AUTH0_ID, MANAGER_ID, oldId, badBody, null));
        assertEquals(400, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // createEditRequest
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void createEditRequest_nullBody_returns400() {
        ServiceException ex = assertServiceFails(service.createEditRequest(AUTH0_ID, MANAGER_ID, null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void createEditRequest_allFieldsBlank_returns400() {
        JsonObject body = new JsonObject().put("company", "").put("title", "  ").put("status", "").put("linkedinUrl", "");
        ServiceException ex = assertServiceFails(service.createEditRequest(AUTH0_ID, MANAGER_ID, body));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void createEditRequest_companyTooLong_returns400() {
        JsonObject body = new JsonObject().put("company", "A".repeat(101));
        ServiceException ex = assertServiceFails(service.createEditRequest(AUTH0_ID, MANAGER_ID, body));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("company"));
    }

    @Test
    void createEditRequest_titleTooLong_returns400() {
        JsonObject body = new JsonObject().put("title", "A".repeat(101));
        ServiceException ex = assertServiceFails(service.createEditRequest(AUTH0_ID, MANAGER_ID, body));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("title"));
    }

    @Test
    void createEditRequest_invalidStatus_returns400() {
        JsonObject body = new JsonObject().put("status", "unknown");
        ServiceException ex = assertServiceFails(service.createEditRequest(AUTH0_ID, MANAGER_ID, body));
        assertEquals(400, ex.getStatusCode());
        assertTrue(ex.getMessage().toLowerCase().contains("status"));
    }

    @Test
    void createEditRequest_linkedinUrlTooLong_returns400() {
        JsonObject body = new JsonObject().put("linkedinUrl", "https://www.linkedin.com/" + "x".repeat(480));
        ServiceException ex = assertServiceFails(service.createEditRequest(AUTH0_ID, MANAGER_ID, body));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void createEditRequest_userNotFound_returns401() {
        when(userRepo.findByAuth0IdWithBan(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.empty()));
        JsonObject body = new JsonObject().put("company", "NewCo");
        ServiceException ex = assertServiceFails(service.createEditRequest(AUTH0_ID, MANAGER_ID, body));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void createEditRequest_bannedUser_returns403() {
        Row banned = userRow(USER_ID, USERNAME, true);
        when(userRepo.findByAuth0IdWithBan(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.of(banned)));
        JsonObject body = new JsonObject().put("company", "NewCo");
        ServiceException ex = assertServiceFails(service.createEditRequest(AUTH0_ID, MANAGER_ID, body));
        assertEquals(403, ex.getStatusCode());
        assertEquals("account_suspended", ex.getMessage());
    }

    @Test
    void createEditRequest_dailyLimitReached_returns429() {
        when(editRepo.countSubmittedTodayByUser(USER_ID)).thenReturn(Future.succeededFuture(6L));
        JsonObject body = new JsonObject().put("company", "NewCo");
        ServiceException ex = assertServiceFails(service.createEditRequest(AUTH0_ID, MANAGER_ID, body));
        assertEquals(429, ex.getStatusCode());
        assertEquals("daily_limit_reached", ex.getMessage());
    }

    @Test
    void createEditRequest_managerNotFound_returns404() {
        when(editRepo.countSubmittedTodayByUser(USER_ID)).thenReturn(Future.succeededFuture(0L));
        when(managerRepo.findById(MANAGER_ID)).thenReturn(Future.succeededFuture(Optional.empty()));
        JsonObject body = new JsonObject().put("company", "NewCo");
        ServiceException ex = assertServiceFails(service.createEditRequest(AUTH0_ID, MANAGER_ID, body));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void createEditRequest_success_returnsEditData() throws Exception {
        UUID editId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now();

        when(editRepo.countSubmittedTodayByUser(USER_ID)).thenReturn(Future.succeededFuture(0L));
        Row managerRow = mock(Row.class);
        when(managerRepo.findById(MANAGER_ID)).thenReturn(Future.succeededFuture(Optional.of(managerRow)));
        Row editRow = mock(Row.class);
        when(editRow.getUUID("id")).thenReturn(editId);
        when(editRow.getOffsetDateTime("created_at")).thenReturn(createdAt);
        // 11-arg overload: the edit request now carries the picked company's ID.
        when(editRepo.upsert(eq(MANAGER_ID), eq(USER_ID), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Future.succeededFuture(editRow));

        JsonObject result = (JsonObject) await(
            service.createEditRequest(AUTH0_ID, MANAGER_ID, new JsonObject().put("company", "NewCo")));

        assertNotNull(result);
        assertEquals(editId.toString(), result.getString("id"));
        assertEquals(MANAGER_ID, (long) result.getLong("managerId"));
        assertEquals("NewCo", result.getString("newCompany"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getPendingEditsForManager
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getPendingEditsForManager_nullAuth0Id_returnsEmptyData() throws Exception {
        JsonObject result = (JsonObject) await(service.getPendingEditsForManager(MANAGER_ID, null));
        assertNotNull(result);
        assertEquals(0, result.getJsonArray("data").size());
    }

    @Test
    void getPendingEditsForManager_userNotFound_returnsEmptyData() throws Exception {
        when(userRepo.findIdByAuth0Id(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.empty()));
        JsonObject result = (JsonObject) await(service.getPendingEditsForManager(MANAGER_ID, AUTH0_ID));
        assertEquals(0, result.getJsonArray("data").size());
    }

    @Test
    void getPendingEditsForManager_success_returnsEdits() throws Exception {
        RowSet<Row> emptyRs = rowSetOf(); // pre-create before when/thenReturn
        when(userRepo.findIdByAuth0Id(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.of(USER_ID)));
        when(editRepo.findPendingByManagerAndUser(MANAGER_ID, USER_ID))
            .thenReturn(Future.succeededFuture(emptyRs));

        JsonObject result = (JsonObject) await(service.getPendingEditsForManager(MANAGER_ID, AUTH0_ID));
        assertNotNull(result);
        assertNotNull(result.getJsonArray("data"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getMyReviews
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getMyReviews_userNotFound_returns404() {
        when(userRepo.findIdByAuth0Id(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.empty()));
        ServiceException ex = assertServiceFails(service.getMyReviews(AUTH0_ID, 50, 0));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void getMyReviews_noReviews_returnsEmptyArray() throws Exception {
        RowSet<Row> emptyRs = rowSetOf(); // pre-create before when/thenReturn
        when(userRepo.findIdByAuth0Id(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.of(USER_ID)));
        when(reviewRepo.findByUser(eq(USER_ID), anyInt(), anyInt())).thenReturn(Future.succeededFuture(emptyRs));
        when(reviewRepo.countByUser(USER_ID)).thenReturn(Future.succeededFuture(0L));

        JsonObject result = (JsonObject) await(service.getMyReviews(AUTH0_ID, 50, 0));
        assertNotNull(result);
        assertEquals(0, result.getJsonArray("data").size());
        assertEquals(0, (int) result.getInteger("total"));
    }

    @Test
    void getMyReviews_returnsPaginationFields() throws Exception {
        RowSet<Row> emptyRs = rowSetOf();
        when(userRepo.findIdByAuth0Id(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.of(USER_ID)));
        when(reviewRepo.findByUser(eq(USER_ID), anyInt(), anyInt())).thenReturn(Future.succeededFuture(emptyRs));
        when(reviewRepo.countByUser(USER_ID)).thenReturn(Future.succeededFuture(3L));

        JsonObject result = (JsonObject) await(service.getMyReviews(AUTH0_ID, 50, 0));
        assertNotNull(result);
        assertEquals(3, (long) result.getLong("total"));
        assertEquals(50, (int) result.getInteger("limit"));
        assertEquals(0, (int) result.getInteger("offset"));
    }

    @Test
    void getMyReviews_limitCappedAt50() throws Exception {
        RowSet<Row> emptyRs = rowSetOf();
        when(userRepo.findIdByAuth0Id(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.of(USER_ID)));
        when(reviewRepo.findByUser(eq(USER_ID), eq(50), eq(0))).thenReturn(Future.succeededFuture(emptyRs));
        when(reviewRepo.countByUser(USER_ID)).thenReturn(Future.succeededFuture(0L));

        // Requesting 200 should be silently capped to 50
        JsonObject result = (JsonObject) await(service.getMyReviews(AUTH0_ID, 200, 0));
        assertNotNull(result);
        assertEquals(50, (int) result.getInteger("limit"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getMySubmittedManagers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getMySubmittedManagers_userNotFound_returns401() {
        when(userRepo.findIdByAuth0Id(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.empty()));
        ServiceException ex = assertServiceFails(service.getMySubmittedManagers(AUTH0_ID));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void getMySubmittedManagers_success_returnsManagerRows() throws Exception {
        RowSet<Row> rs = rowSetOf();
        when(userRepo.findIdByAuth0Id(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.of(USER_ID)));
        when(managerRepo.findPendingByUser(USER_ID)).thenReturn(Future.succeededFuture(rs));

        RowSet<?> result = (RowSet<?>) await(service.getMySubmittedManagers(AUTH0_ID));
        assertNotNull(result);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getManagerById / enforceSubmitterAccess
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getManagerById_notFound_returns404() {
        when(managerRepo.findById(MANAGER_ID)).thenReturn(Future.succeededFuture(Optional.empty()));
        ServiceException ex = assertServiceFails(service.getManagerById(MANAGER_ID, null));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void getManagerById_approved_returnsRow() throws Exception {
        Row managerRow = mock(Row.class);
        when(managerRow.getString("approval_status")).thenReturn("approved");
        when(managerRepo.findById(MANAGER_ID)).thenReturn(Future.succeededFuture(Optional.of(managerRow)));

        Row result = await(service.getManagerById(MANAGER_ID, null));
        assertNotNull(result);
    }

    @Test
    void getManagerById_pendingApproval_submitterAllowed() throws Exception {
        Row managerRow = mock(Row.class);
        when(managerRow.getString("approval_status")).thenReturn("pending_approval");
        when(managerRow.getUUID("submitted_by")).thenReturn(USER_ID);
        when(managerRepo.findById(MANAGER_ID)).thenReturn(Future.succeededFuture(Optional.of(managerRow)));
        when(userRepo.findIdByAuth0Id(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.of(USER_ID)));

        Row result = await(service.getManagerById(MANAGER_ID, AUTH0_ID));
        assertNotNull(result);
    }

    @Test
    void getManagerById_pendingApproval_differentUser_returns404() {
        Row managerRow = mock(Row.class);
        when(managerRow.getString("approval_status")).thenReturn("pending_approval");
        when(managerRow.getUUID("submitted_by")).thenReturn(UUID.randomUUID());
        when(managerRepo.findById(MANAGER_ID)).thenReturn(Future.succeededFuture(Optional.of(managerRow)));
        when(userRepo.findIdByAuth0Id(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.of(USER_ID)));

        ServiceException ex = assertServiceFails(service.getManagerById(MANAGER_ID, AUTH0_ID));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void getManagerById_pendingApproval_nullAuth0Id_returns404() {
        Row managerRow = mock(Row.class);
        when(managerRow.getString("approval_status")).thenReturn("pending_approval");
        when(managerRow.getUUID("submitted_by")).thenReturn(USER_ID);
        when(managerRepo.findById(MANAGER_ID)).thenReturn(Future.succeededFuture(Optional.of(managerRow)));

        ServiceException ex = assertServiceFails(service.getManagerById(MANAGER_ID, null));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void getManagerById_rejected_submitterReturns404() {
        Row managerRow = mock(Row.class);
        when(managerRow.getString("approval_status")).thenReturn("rejected");
        when(managerRow.getUUID("submitted_by")).thenReturn(USER_ID);
        when(managerRepo.findById(MANAGER_ID)).thenReturn(Future.succeededFuture(Optional.of(managerRow)));

        ServiceException ex = assertServiceFails(service.getManagerById(MANAGER_ID, AUTH0_ID));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void getManagerById_rejected_anonymousReturns404() {
        Row managerRow = mock(Row.class);
        when(managerRow.getString("approval_status")).thenReturn("rejected");
        when(managerRepo.findById(MANAGER_ID)).thenReturn(Future.succeededFuture(Optional.of(managerRow)));

        ServiceException ex = assertServiceFails(service.getManagerById(MANAGER_ID, null));
        assertEquals(404, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getManagerCareerSegments
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getManagerCareerSegments_returnsPaginationFields() throws Exception {
        RowSet<Row> emptyRs = rowSetOf();
        when(reviewRepo.findCareerSegmentsByManager(MANAGER_ID, 20, 0))
            .thenReturn(Future.succeededFuture(emptyRs));
        when(reviewRepo.countCareerSegmentsByManager(MANAGER_ID))
            .thenReturn(Future.succeededFuture(5L));

        JsonObject result = (JsonObject) await(service.getManagerCareerSegments(MANAGER_ID, 20, 0));
        assertNotNull(result);
        assertNotNull(result.getJsonArray("data"));
        assertEquals(5L, (long) result.getLong("total"));
        assertEquals(20, (int) result.getInteger("limit"));
        assertEquals(0, (int) result.getInteger("offset"));
    }

    @Test
    void getManagerCareerSegments_limitCappedAt50() throws Exception {
        RowSet<Row> emptyRs = rowSetOf();
        when(reviewRepo.findCareerSegmentsByManager(eq(MANAGER_ID), eq(50), eq(0)))
            .thenReturn(Future.succeededFuture(emptyRs));
        when(reviewRepo.countCareerSegmentsByManager(MANAGER_ID))
            .thenReturn(Future.succeededFuture(0L));

        JsonObject result = (JsonObject) await(service.getManagerCareerSegments(MANAGER_ID, 999, 0));
        assertNotNull(result);
        assertEquals(50, (int) result.getInteger("limit"));
    }

    @Test
    void getManagerCareerSegments_offsetNormalisedToZeroWhenNegative() throws Exception {
        RowSet<Row> emptyRs = rowSetOf();
        when(reviewRepo.findCareerSegmentsByManager(eq(MANAGER_ID), anyInt(), eq(0)))
            .thenReturn(Future.succeededFuture(emptyRs));
        when(reviewRepo.countCareerSegmentsByManager(MANAGER_ID))
            .thenReturn(Future.succeededFuture(0L));

        JsonObject result = (JsonObject) await(service.getManagerCareerSegments(MANAGER_ID, 20, -5));
        assertNotNull(result);
        assertEquals(0, (int) result.getInteger("offset"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // hasReported
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void hasReported_nullAuth0Id_returnsFalse() throws Exception {
        assertFalse(await(service.hasReported(MANAGER_ID, null)));
    }

    @Test
    void hasReported_userNotFound_returnsFalse() throws Exception {
        when(userRepo.findIdByAuth0Id(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.empty()));
        assertFalse(await(service.hasReported(MANAGER_ID, AUTH0_ID)));
    }

    @Test
    void hasReported_userHasReported_returnsTrue() throws Exception {
        when(userRepo.findIdByAuth0Id(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.of(USER_ID)));
        when(reportRepo.alreadyReported(MANAGER_ID, USER_ID)).thenReturn(Future.succeededFuture(true));
        assertTrue(await(service.hasReported(MANAGER_ID, AUTH0_ID)));
    }

    @Test
    void hasReported_userHasNotReported_returnsFalse() throws Exception {
        when(userRepo.findIdByAuth0Id(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.of(USER_ID)));
        when(reportRepo.alreadyReported(MANAGER_ID, USER_ID)).thenReturn(Future.succeededFuture(false));
        assertFalse(await(service.hasReported(MANAGER_ID, AUTH0_ID)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getSimilarManagers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getSimilarManagers_blankName_returns400() {
        ServiceException ex = assertServiceFails(service.getSimilarManagers("   ", "Corp"));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void getSimilarManagers_nullName_returns400() {
        ServiceException ex = assertServiceFails(service.getSimilarManagers(null, "Corp"));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void getSimilarManagers_success_returnsResults() throws Exception {
        RowSet<Row> emptyRs = rowSetOf(); // pre-create before when/thenReturn
        when(managerRepo.findSimilar(any(), any())).thenReturn(Future.succeededFuture(emptyRs));
        JsonObject result = (JsonObject) await(service.getSimilarManagers("Alice", "Acme"));
        assertNotNull(result);
        assertNotNull(result.getJsonArray("data"));
        assertEquals(0, result.getJsonArray("data").size());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Additional helpers
    // ══════════════════════════════════════════════════════════════════════════

    /** All 10 category ratings set to the same value. */
    private static JsonObject allRatings(double value) {
        JsonObject r = new JsonObject();
        for (String key : new String[]{
                "Communication Style", "Perceived Approachability",
                "Perceived Clarity of Expectations", "Feedback Style",
                "Perceived Supportiveness", "Decision Making Style",
                "Organization and Planning Style", "Delegation Style",
                "Perceived Professional Demeanor", "Overall Working Experience"}) {
            r.put(key, value);
        }
        return r;
    }

    /** Row suitable for findByUserForValidation results (has id, manager_id, title, company, role dates). */
    private static Row validationReviewRow(UUID id, long managerId, String title, String company) {
        Row row = mock(Row.class);
        when(row.getUUID("id")).thenReturn(id);
        when(row.getLong("manager_id")).thenReturn(managerId);
        when(row.getString("manager_title")).thenReturn(title);
        when(row.getString("manager_company")).thenReturn(company);
        when(row.getLocalDate("manager_role_start")).thenReturn(null);
        when(row.getLocalDate("manager_role_end")).thenReturn(null);
        return row;
    }
}
