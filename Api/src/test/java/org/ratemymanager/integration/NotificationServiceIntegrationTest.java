package org.ratemymanager.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ratemymanager.repository.NotificationRepository;
import org.ratemymanager.repository.UserRepository;
import org.ratemymanager.service.NotificationService;
import org.ratemymanager.service.ServiceException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class NotificationServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("ratemymanager_test")
        .withUsername("test")
        .withPassword("test");

    static Pool                pool;
    static NotificationService service;

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
        UserRepository         userRepo  = new UserRepository(pool);
        NotificationRepository notifRepo = new NotificationRepository(pool);
        service = new NotificationService(userRepo, notifRepo);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, users CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // resolveUserId guard
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getNotifications_userNotFound_returns401() {
        ServiceException ex = assertServiceException(service.getNotifications("auth0|nobody"));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void getUnreadCount_userNotFound_returns401() {
        ServiceException ex = assertServiceException(service.getUnreadCount("auth0|nobody"));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void markAllRead_userNotFound_returns401() {
        ServiceException ex = assertServiceException(service.markAllRead("auth0|nobody"));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void markRead_userNotFound_returns401() {
        ServiceException ex = assertServiceException(service.markRead("auth0|nobody", UUID.randomUUID()));
        assertEquals(401, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getNotifications
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getNotifications_noNotifications_returnsEmptyData() throws Exception {
        String auth0Id = insertUser("auth0|notif-user01", "NotifUser01");

        JsonObject result = await(service.getNotifications(auth0Id));
        assertNotNull(result.getJsonArray("data"));
        assertEquals(0, result.getJsonArray("data").size());
    }

    @Test
    void getNotifications_returnsAllNotifications() throws Exception {
        String auth0Id = insertUser("auth0|notif-user02", "NotifUser02");
        UUID   userId  = findUserId(auth0Id);

        insertNotification(userId, "manager_approved", "Approved", "Your manager was approved", null);
        insertNotification(userId, "user_banned",      "Suspended", "Account suspended",         null);

        JsonObject result = await(service.getNotifications(auth0Id));
        assertEquals(2, result.getJsonArray("data").size());
    }

    @Test
    void getNotifications_mapsAllFields() throws Exception {
        String auth0Id = insertUser("auth0|notif-user03", "NotifUser03");
        UUID   userId  = findUserId(auth0Id);
        insertNotification(userId, "manager_approved", "Approved", "Your manager was approved", null);

        JsonObject result = await(service.getNotifications(auth0Id));
        JsonObject item = result.getJsonArray("data").getJsonObject(0);

        assertNotNull(item.getString("id"));
        assertEquals("manager_approved", item.getString("type"));
        assertEquals("Approved",         item.getString("title"));
        assertEquals("Your manager was approved", item.getString("message"));
        assertFalse(item.getBoolean("read"));
        assertNotNull(item.getString("createdAt"));
    }

    @Test
    void getNotifications_withManagerId_includesManagerIdField() throws Exception {
        String auth0Id   = insertUser("auth0|notif-user04", "NotifUser04");
        UUID   userId    = findUserId(auth0Id);
        long   managerId = insertManager("Notif Test Manager");
        insertNotificationWithManager(userId, "manager_approved", "Approved", "msg", managerId);

        JsonObject result = await(service.getNotifications(auth0Id));
        JsonObject item = result.getJsonArray("data").getJsonObject(0);
        assertEquals(managerId, item.getLong("managerId"));
    }

    @Test
    void getNotifications_withoutManagerId_omitsManagerIdField() throws Exception {
        String auth0Id = insertUser("auth0|notif-user05", "NotifUser05");
        UUID   userId  = findUserId(auth0Id);
        insertNotification(userId, "user_banned", "Suspended", "msg", null);

        JsonObject result = await(service.getNotifications(auth0Id));
        JsonObject item = result.getJsonArray("data").getJsonObject(0);
        assertFalse(item.containsKey("managerId"));
    }

    @Test
    void getNotifications_onlyReturnsOwnNotifications() throws Exception {
        String auth0A = insertUser("auth0|notif-userA", "NotifUserA");
        String auth0B = insertUser("auth0|notif-userB", "NotifUserB");
        UUID   userAId = findUserId(auth0A);
        UUID   userBId = findUserId(auth0B);

        insertNotification(userAId, "t1", "T1", "M1", null);
        insertNotification(userBId, "t2", "T2", "M2", null);

        JsonObject resultA = await(service.getNotifications(auth0A));
        assertEquals(1, resultA.getJsonArray("data").size());
        assertEquals("T1", resultA.getJsonArray("data").getJsonObject(0).getString("title"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getUnreadCount
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getUnreadCount_noNotifications_returnsZero() throws Exception {
        String auth0Id = insertUser("auth0|count-user01", "CountUser01");
        JsonObject result = await(service.getUnreadCount(auth0Id));
        assertEquals(0L, result.getLong("unreadCount"));
    }

    @Test
    void getUnreadCount_mixedReadUnread_returnsUnreadOnly() throws Exception {
        String auth0Id = insertUser("auth0|count-user02", "CountUser02");
        UUID   userId  = findUserId(auth0Id);

        insertNotification(userId, "t1", "T1", "M1", null);          // unread
        insertNotification(userId, "t2", "T2", "M2", null);          // unread
        insertReadNotification(userId, "t3", "T3", "M3", null);      // read

        JsonObject result = await(service.getUnreadCount(auth0Id));
        assertEquals(2L, result.getLong("unreadCount"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // markAllRead
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void markAllRead_marksAllUnreadAsRead() throws Exception {
        String auth0Id = insertUser("auth0|markall-user01", "MarkAllUser01");
        UUID   userId  = findUserId(auth0Id);

        insertNotification(userId, "t1", "T1", "M1", null);
        insertNotification(userId, "t2", "T2", "M2", null);

        JsonObject result = await(service.markAllRead(auth0Id));
        assertTrue(result.getBoolean("success"));

        long unreadCount = await(pool
            .preparedQuery("SELECT COUNT(*) FROM notifications WHERE user_id = $1 AND read = FALSE")
            .execute(Tuple.of(userId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0L, unreadCount);
    }

    @Test
    void markAllRead_noNotifications_succeeds() throws Exception {
        String auth0Id = insertUser("auth0|markall-user02", "MarkAllUser02");
        JsonObject result = await(service.markAllRead(auth0Id));
        assertTrue(result.getBoolean("success"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // markRead
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void markRead_marksSpecificNotificationAsRead() throws Exception {
        String auth0Id = insertUser("auth0|mark-user01", "MarkUser01");
        UUID   userId  = findUserId(auth0Id);

        UUID notifId = insertNotification(userId, "t1", "T1", "M1", null);
        insertNotification(userId, "t2", "T2", "M2", null); // second stays unread

        JsonObject result = await(service.markRead(auth0Id, notifId));
        assertTrue(result.getBoolean("success"));

        // Specific notification is read
        boolean isRead = await(pool
            .preparedQuery("SELECT read FROM notifications WHERE id = $1")
            .execute(Tuple.of(notifId))
            .map(rs -> rs.iterator().next().getBoolean("read")));
        assertTrue(isRead);

        // Second notification still unread
        long unreadCount = await(pool
            .preparedQuery("SELECT COUNT(*) FROM notifications WHERE user_id = $1 AND read = FALSE")
            .execute(Tuple.of(userId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, unreadCount);
    }

    @Test
    void markRead_doesNotMarkOtherUsersNotification() throws Exception {
        String auth0A = insertUser("auth0|markown-userA", "MarkOwnUserA");
        String auth0B = insertUser("auth0|markown-userB", "MarkOwnUserB");
        UUID   userBId = findUserId(auth0B);

        UUID bNotifId = insertNotification(userBId, "t1", "T1", "M1", null);

        // User A tries to mark user B's notification as read — silently does nothing
        await(service.markRead(auth0A, bNotifId));

        boolean isRead = await(pool
            .preparedQuery("SELECT read FROM notifications WHERE id = $1")
            .execute(Tuple.of(bNotifId))
            .map(rs -> rs.iterator().next().getBoolean("read")));
        assertFalse(isRead);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private long insertManager(String name) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages) " +
            "VALUES ($1,'Corp','Title','img','active','approved',0,0,'{}') RETURNING id")
            .execute(Tuple.of(name))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private String insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id, email, username, first_name, last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User")));
        return auth0Id;
    }

    private UUID findUserId(String auth0Id) throws Exception {
        return await(pool
            .preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private UUID insertNotification(UUID userId, String type, String title, String message, Long managerId) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO notifications(user_id, type, title, message, manager_id) VALUES ($1,$2,$3,$4,$5) RETURNING id")
            .execute(Tuple.of(userId, type, title, message, managerId))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private UUID insertNotificationWithManager(UUID userId, String type, String title, String message, long managerId) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO notifications(user_id, type, title, message, manager_id) VALUES ($1,$2,$3,$4,$5) RETURNING id")
            .execute(Tuple.of(userId, type, title, message, managerId))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private UUID insertReadNotification(UUID userId, String type, String title, String message, Long managerId) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO notifications(user_id, type, title, message, manager_id, read) VALUES ($1,$2,$3,$4,$5,TRUE) RETURNING id")
            .execute(Tuple.of(userId, type, title, message, managerId))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private static ServiceException assertServiceException(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            fail("Expected future to fail");
            return null;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ServiceException se) return se;
            fail("Expected ServiceException but got: " + e.getCause());
            return null;
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
            return null;
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
