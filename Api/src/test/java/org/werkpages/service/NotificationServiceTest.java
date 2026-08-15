package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.RowSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.werkpages.repository.NotificationRepository;
import org.werkpages.repository.UserRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class NotificationServiceTest {

    private static final String AUTH0_ID = "auth0|test-user";
    private static final UUID   USER_ID  = UUID.randomUUID();

    private UserRepository         userRepo;
    private NotificationRepository notifRepo;
    private NotificationService    service;

    @BeforeEach
    void setUp() {
        userRepo  = mock(UserRepository.class);
        notifRepo = mock(NotificationRepository.class);
        service   = new NotificationService(userRepo, notifRepo);

        when(userRepo.findIdByAuth0Id(AUTH0_ID))
            .thenReturn(Future.succeededFuture(Optional.of(USER_ID)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // resolveUserId guard — all four methods return 401 when user not found
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getNotifications_userNotFound_returns401() {
        when(userRepo.findIdByAuth0Id(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.empty()));
        ServiceException ex = assertServiceFails(service.getNotifications(AUTH0_ID));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void getUnreadCount_userNotFound_returns401() {
        when(userRepo.findIdByAuth0Id(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.empty()));
        ServiceException ex = assertServiceFails(service.getUnreadCount(AUTH0_ID));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void markAllRead_userNotFound_returns401() {
        when(userRepo.findIdByAuth0Id(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.empty()));
        ServiceException ex = assertServiceFails(service.markAllRead(AUTH0_ID));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void markRead_userNotFound_returns401() {
        when(userRepo.findIdByAuth0Id(AUTH0_ID)).thenReturn(Future.succeededFuture(Optional.empty()));
        ServiceException ex = assertServiceFails(service.markRead(AUTH0_ID, UUID.randomUUID()));
        assertEquals(401, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getNotifications
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getNotifications_emptyList_returnsEmptyData() throws Exception {
        RowSet<Row> emptyRs = rowSetOf();
        when(notifRepo.findByUser(USER_ID)).thenReturn(Future.succeededFuture(emptyRs));

        JsonObject result = await(service.getNotifications(AUTH0_ID));
        assertNotNull(result.getJsonArray("data"));
        assertEquals(0, result.getJsonArray("data").size());
    }

    @Test
    void getNotifications_withManagerId_includesManagerIdField() throws Exception {
        UUID notifId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        RowSet<Row> rs = rowSetOf(notifRow(notifId, "manager_approved", "Approved",
            "Your manager was approved", false, createdAt, 42L));
        when(notifRepo.findByUser(USER_ID)).thenReturn(Future.succeededFuture(rs));

        JsonObject result = await(service.getNotifications(AUTH0_ID));
        JsonObject item = result.getJsonArray("data").getJsonObject(0);
        assertEquals(notifId.toString(), item.getString("id"));
        assertEquals("manager_approved", item.getString("type"));
        assertEquals("Approved", item.getString("title"));
        assertEquals("Your manager was approved", item.getString("message"));
        assertFalse(item.getBoolean("read"));
        assertEquals(42L, item.getLong("managerId"));
        assertNotNull(item.getString("createdAt"));
    }

    @Test
    void getNotifications_nullManagerId_omitsManagerIdField() throws Exception {
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        RowSet<Row> rs = rowSetOf(notifRow(UUID.randomUUID(), "user_banned",
            "Suspended", "Account suspended", true, createdAt, null));
        when(notifRepo.findByUser(USER_ID)).thenReturn(Future.succeededFuture(rs));

        JsonObject result = await(service.getNotifications(AUTH0_ID));
        JsonObject item = result.getJsonArray("data").getJsonObject(0);
        assertFalse(item.containsKey("managerId"));
        assertTrue(item.getBoolean("read"));
    }

    @Test
    void getNotifications_multipleRows_returnsAll() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        RowSet<Row> rs = rowSetOf(
            notifRow(UUID.randomUUID(), "t1", "Title1", "Msg1", false, now, null),
            notifRow(UUID.randomUUID(), "t2", "Title2", "Msg2", true,  now, 1L),
            notifRow(UUID.randomUUID(), "t3", "Title3", "Msg3", false, now, null)
        );
        when(notifRepo.findByUser(USER_ID)).thenReturn(Future.succeededFuture(rs));

        JsonObject result = await(service.getNotifications(AUTH0_ID));
        assertEquals(3, result.getJsonArray("data").size());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getUnreadCount
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getUnreadCount_returnsCount() throws Exception {
        when(notifRepo.countUnread(USER_ID)).thenReturn(Future.succeededFuture(5L));
        JsonObject result = await(service.getUnreadCount(AUTH0_ID));
        assertEquals(5L, result.getLong("unreadCount"));
    }

    @Test
    void getUnreadCount_zero_returnsZero() throws Exception {
        when(notifRepo.countUnread(USER_ID)).thenReturn(Future.succeededFuture(0L));
        JsonObject result = await(service.getUnreadCount(AUTH0_ID));
        assertEquals(0L, result.getLong("unreadCount"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // markAllRead
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void markAllRead_success_returnsSuccessAndCallsRepo() throws Exception {
        when(notifRepo.markAllRead(USER_ID)).thenReturn(Future.succeededFuture());

        JsonObject result = await(service.markAllRead(AUTH0_ID));
        assertTrue(result.getBoolean("success"));
        verify(notifRepo).markAllRead(USER_ID);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // markRead
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void markRead_success_returnsSuccessAndCallsRepo() throws Exception {
        UUID notifId = UUID.randomUUID();
        when(notifRepo.markRead(notifId, USER_ID)).thenReturn(Future.succeededFuture());

        JsonObject result = await(service.markRead(AUTH0_ID, notifId));
        assertTrue(result.getBoolean("success"));
        verify(notifRepo).markRead(notifId, USER_ID);
    }

    @Test
    void markRead_passesCorrectUserIdToRepo() throws Exception {
        UUID notifId = UUID.randomUUID();
        when(notifRepo.markRead(notifId, USER_ID)).thenReturn(Future.succeededFuture());

        await(service.markRead(AUTH0_ID, notifId));
        verify(notifRepo).markRead(notifId, USER_ID);
        verify(notifRepo, never()).markRead(notifId, UUID.randomUUID());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
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

    private static Row notifRow(UUID id, String type, String title, String message,
                                 boolean read, OffsetDateTime createdAt, Long managerId) {
        Row row = mock(Row.class);
        when(row.getUUID("id")).thenReturn(id);
        when(row.getString("type")).thenReturn(type);
        when(row.getString("title")).thenReturn(title);
        when(row.getString("message")).thenReturn(message);
        when(row.getBoolean("read")).thenReturn(read);
        when(row.getOffsetDateTime("created_at")).thenReturn(createdAt);
        when(row.getLong("manager_id")).thenReturn(managerId);
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
