package org.ratemymanager.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ratemymanager.repository.ReportRepository;
import org.ratemymanager.repository.UserRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportServiceTest {

    private static final String AUTH0_ID   = "auth0|test-user";
    private static final UUID   USER_ID    = UUID.randomUUID();
    private static final long   MANAGER_ID = 77L;

    private UserRepository   userRepo;
    private ReportRepository reportRepo;
    private ReportService    service;

    @BeforeEach
    void setUp() {
        userRepo   = mock(UserRepository.class);
        reportRepo = mock(ReportRepository.class);
        service    = new ReportService(userRepo, reportRepo);

        when(userRepo.findIdByAuth0Id(AUTH0_ID))
            .thenReturn(Future.succeededFuture(Optional.of(USER_ID)));
        when(reportRepo.managerExists(MANAGER_ID))
            .thenReturn(Future.succeededFuture(true));
        when(reportRepo.alreadyReported(MANAGER_ID, USER_ID))
            .thenReturn(Future.succeededFuture(false));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Input validation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void nullReason_returns400() {
        ServiceException ex = assertServiceFails(
            service.reportManager(AUTH0_ID, MANAGER_ID, null, null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void blankReason_returns400() {
        ServiceException ex = assertServiceFails(
            service.reportManager(AUTH0_ID, MANAGER_ID, "   ", null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void invalidReason_returns400() {
        ServiceException ex = assertServiceFails(
            service.reportManager(AUTH0_ID, MANAGER_ID, "made_up_reason", null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void commentTooLong_returns400() {
        ServiceException ex = assertServiceFails(
            service.reportManager(AUTH0_ID, MANAGER_ID, "other", "x".repeat(501)));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void commentAtExactLimit_succeeds() throws Exception {
        String comment = "x".repeat(500);
        Row created = reportRow(UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC));
        when(reportRepo.create(eq(MANAGER_ID), eq(USER_ID), eq("other"), eq(comment)))
            .thenReturn(Future.succeededFuture(created));

        JsonObject result = await(service.reportManager(AUTH0_ID, MANAGER_ID, "other", comment));
        assertTrue(result.getBoolean("success"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // All valid reasons are accepted
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void allValidReasons_accepted() throws Exception {
        Row created = reportRow(UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC));
        when(reportRepo.create(eq(MANAGER_ID), eq(USER_ID), anyString(), isNull()))
            .thenReturn(Future.succeededFuture(created));

        for (String reason : new String[]{
                "incorrect_person", "never_worked_here", "duplicate_profile",
                "incorrect_information", "other"}) {
            when(reportRepo.alreadyReported(MANAGER_ID, USER_ID)).thenReturn(Future.succeededFuture(false));
            JsonObject result = await(service.reportManager(AUTH0_ID, MANAGER_ID, reason, null));
            assertTrue(result.getBoolean("success"), "Should accept reason: " + reason);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Manager existence check
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void managerNotFound_returns404() {
        when(reportRepo.managerExists(MANAGER_ID)).thenReturn(Future.succeededFuture(false));
        ServiceException ex = assertServiceFails(
            service.reportManager(AUTH0_ID, MANAGER_ID, "other", null));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void validationRunsBeforeManagerCheck() {
        // Invalid reason should fail before even checking manager existence
        when(reportRepo.managerExists(MANAGER_ID)).thenReturn(Future.succeededFuture(false));
        ServiceException ex = assertServiceFails(
            service.reportManager(AUTH0_ID, MANAGER_ID, "bad_reason", null));
        assertEquals(400, ex.getStatusCode());
        verify(reportRepo, never()).managerExists(anyLong());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Authenticated user
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void authenticatedUser_alreadyReported_returns409() {
        when(reportRepo.alreadyReported(MANAGER_ID, USER_ID)).thenReturn(Future.succeededFuture(true));
        ServiceException ex = assertServiceFails(
            service.reportManager(AUTH0_ID, MANAGER_ID, "other", null));
        assertEquals(409, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("already_reported"));
    }

    @Test
    void authenticatedUser_success_returnsReportIdAndCreatedAt() throws Exception {
        UUID reportId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        Row created = reportRow(reportId, createdAt);
        when(reportRepo.create(MANAGER_ID, USER_ID, "incorrect_person", "some comment"))
            .thenReturn(Future.succeededFuture(created));

        JsonObject result = await(service.reportManager(AUTH0_ID, MANAGER_ID, "incorrect_person", "some comment"));
        assertTrue(result.getBoolean("success"));
        assertEquals(reportId.toString(), result.getString("reportId"));
        assertNotNull(result.getString("createdAt"));
    }

    @Test
    void authenticatedUser_nullComment_passesNullToRepo() throws Exception {
        Row created = reportRow(UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC));
        when(reportRepo.create(eq(MANAGER_ID), eq(USER_ID), eq("other"), isNull()))
            .thenReturn(Future.succeededFuture(created));

        await(service.reportManager(AUTH0_ID, MANAGER_ID, "other", null));
        verify(reportRepo).create(MANAGER_ID, USER_ID, "other", null);
    }

    @Test
    void authenticatedUser_userNotInDb_createsAnonymousReport() throws Exception {
        // auth0Id provided but not found in DB → treated as anonymous
        when(userRepo.findIdByAuth0Id(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.empty()));
        Row created = reportRow(UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC));
        when(reportRepo.create(eq(MANAGER_ID), isNull(), eq("other"), isNull()))
            .thenReturn(Future.succeededFuture(created));

        JsonObject result = await(service.reportManager(AUTH0_ID, MANAGER_ID, "other", null));
        assertTrue(result.getBoolean("success"));
        verify(reportRepo, never()).alreadyReported(anyLong(), any(UUID.class));
        verify(reportRepo).create(MANAGER_ID, null, "other", null);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Anonymous user (null auth0Id)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void anonymousUser_success_skipsAlreadyReportedCheck() throws Exception {
        UUID reportId = UUID.randomUUID();
        Row created = reportRow(reportId, OffsetDateTime.now(ZoneOffset.UTC));
        when(reportRepo.create(eq(MANAGER_ID), isNull(), eq("duplicate_profile"), isNull()))
            .thenReturn(Future.succeededFuture(created));

        JsonObject result = await(service.reportManager(null, MANAGER_ID, "duplicate_profile", null));
        assertTrue(result.getBoolean("success"));
        assertEquals(reportId.toString(), result.getString("reportId"));
        verify(reportRepo, never()).alreadyReported(anyLong(), any());
    }

    @Test
    void anonymousUser_withComment_passesCommentToRepo() throws Exception {
        Row created = reportRow(UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC));
        when(reportRepo.create(eq(MANAGER_ID), isNull(), eq("other"), eq("Looks fake")))
            .thenReturn(Future.succeededFuture(created));

        await(service.reportManager(null, MANAGER_ID, "other", "Looks fake"));
        verify(reportRepo).create(MANAGER_ID, null, "other", "Looks fake");
    }

    @Test
    void anonymousUser_invalidReason_returns400BeforeManagerCheck() {
        ServiceException ex = assertServiceFails(
            service.reportManager(null, MANAGER_ID, "nonsense", null));
        assertEquals(400, ex.getStatusCode());
        verify(reportRepo, never()).managerExists(anyLong());
    }

    @Test
    void anonymousUser_managerNotFound_returns404() {
        when(reportRepo.managerExists(MANAGER_ID)).thenReturn(Future.succeededFuture(false));
        ServiceException ex = assertServiceFails(
            service.reportManager(null, MANAGER_ID, "other", null));
        assertEquals(404, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private static Row reportRow(UUID id, OffsetDateTime createdAt) {
        Row row = mock(Row.class);
        when(row.getUUID("id")).thenReturn(id);
        when(row.getOffsetDateTime("created_at")).thenReturn(createdAt);
        return row;
    }

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

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
