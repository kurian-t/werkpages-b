package org.ratemymanager.integration;

import io.vertx.core.Future;
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
import org.ratemymanager.repository.EditRepository;
import org.ratemymanager.repository.ManagerRepository;
import org.ratemymanager.repository.NotificationRepository;
import org.ratemymanager.repository.ReviewRepository;
import org.ratemymanager.repository.UserRepository;
import org.ratemymanager.service.AdminService;
import org.ratemymanager.service.ServiceException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class AdminServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("ratemymanager_test")
        .withUsername("test")
        .withPassword("test");

    static Pool              pool;
    static AdminService      service;
    static UserRepository    userRepo;
    static ManagerRepository managerRepo;

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

        pool        = PgPool.pool(connectOptions, new PoolOptions().setMaxSize(5));
        userRepo    = new UserRepository(pool);
        managerRepo = new ManagerRepository(pool);
        ReviewRepository       reviewRepo = new ReviewRepository(pool);
        EditRepository         editRepo   = new EditRepository(pool);
        NotificationRepository notifRepo  = new NotificationRepository(pool);
        service = new AdminService(userRepo, managerRepo, reviewRepo, editRepo, notifRepo);
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
    // requireAdmin guards
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void nullAuth0Id_returns401() {
        ServiceException ex = assertServiceException(service.getPendingManagers(null, 10, 0));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void userNotFound_returns401() {
        ServiceException ex = assertServiceException(service.getPendingManagers("auth0|nobody", 10, 0));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void nonAdminUser_returns403() throws Exception {
        String auth0Id = insertUser("auth0|regular01", "Regular01", "user");
        ServiceException ex = assertServiceException(service.getPendingManagers(auth0Id, 10, 0));
        assertEquals(403, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getPendingManagers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getPendingManagers_noPending_returnsEmpty() throws Exception {
        String adminAuth0 = insertUser("auth0|admin01", "Admin01", "admin");
        JsonObject result = await(service.getPendingManagers(adminAuth0, 10, 0));
        assertEquals(0, result.getJsonArray("data").size());
    }

    @Test
    void getPendingManagers_returnsPendingManagers() throws Exception {
        String adminAuth0 = insertUser("auth0|admin02", "Admin02", "admin");
        insertPendingManager("Alice Smith", "Corp", "Director", null);

        JsonObject result = await(service.getPendingManagers(adminAuth0, 10, 0));
        assertEquals(1, result.getJsonArray("data").size());
        assertEquals("Alice Smith", result.getJsonArray("data").getJsonObject(0).getString("name"));
        assertEquals(10, result.getInteger("limit"));
        assertEquals(0, result.getInteger("offset"));
    }

    @Test
    void getPendingManagers_doesNotReturnApprovedManagers() throws Exception {
        String adminAuth0 = insertUser("auth0|admin03", "Admin03", "admin");
        insertApprovedManager("Already Approved", "Corp", "Title");
        insertPendingManager("Pending One", "Corp", "Title", null);

        JsonObject result = await(service.getPendingManagers(adminAuth0, 10, 0));
        assertEquals(1, result.getJsonArray("data").size());
        assertEquals("Pending One", result.getJsonArray("data").getJsonObject(0).getString("name"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // approvePendingManager
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void approvePendingManager_notFound_returns404() throws Exception {
        String adminAuth0 = insertUser("auth0|admin04", "Admin04", "admin");
        ServiceException ex = assertServiceException(service.approvePendingManager(adminAuth0, 999999L, null));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void approvePendingManager_alreadyApproved_returns404() throws Exception {
        String adminAuth0 = insertUser("auth0|admin05", "Admin05", "admin");
        long managerId = insertApprovedManager("Already Approved", "Corp", "Title");
        ServiceException ex = assertServiceException(service.approvePendingManager(adminAuth0, managerId, null));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void approvePendingManager_success_setsApprovedStatus() throws Exception {
        String adminAuth0 = insertUser("auth0|admin06", "Admin06", "admin");
        long managerId = insertPendingManager("Bob Brown", "Corp", "Title", null);

        JsonObject result = await(service.approvePendingManager(adminAuth0, managerId, null));
        assertTrue(result.getBoolean("success"));

        String status = await(pool
            .preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("approval_status")));
        assertEquals("approved", status);
    }

    @Test
    void approvePendingManager_withSubmitter_sendsNotification() throws Exception {
        String adminAuth0    = insertUser("auth0|admin07", "Admin07", "admin");
        String submitterAuth = insertUser("auth0|sub01",   "Submitter01", "user");
        UUID   submitterId   = findUserId(submitterAuth);
        long managerId = insertPendingManager("Carol Davis", "Corp", "Title", submitterId);

        await(service.approvePendingManager(adminAuth0, managerId, null));
        Thread.sleep(300);

        long notifCount = await(pool
            .preparedQuery("SELECT COUNT(*) FROM notifications WHERE user_id = $1 AND type = $2")
            .execute(Tuple.of(submitterId, "manager_approved"))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, notifCount);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // rejectPendingManager
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void rejectPendingManager_notFound_returns404() throws Exception {
        String adminAuth0 = insertUser("auth0|admin08", "Admin08", "admin");
        ServiceException ex = assertServiceException(service.rejectPendingManager(adminAuth0, 999999L, null));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void rejectPendingManager_success_setsRejectedStatus() throws Exception {
        String adminAuth0 = insertUser("auth0|admin09", "Admin09", "admin");
        long managerId = insertPendingManager("Eve White", "Corp", "Title", null);

        JsonObject result = await(service.rejectPendingManager(adminAuth0, managerId, "Spam"));
        assertTrue(result.getBoolean("success"));

        String status = await(pool
            .preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("approval_status")));
        assertEquals("rejected", status);
    }

    @Test
    void rejectPendingManager_withSubmitter_sendsNotificationWithNameCompanyAndReason() throws Exception {
        String adminAuth0    = insertUser("auth0|admin10", "Admin10", "admin");
        String submitterAuth = insertUser("auth0|sub02",   "Submitter02", "user");
        UUID   submitterId   = findUserId(submitterAuth);
        long managerId = insertPendingManager("Frank Lee", "TechCorp", "Title", submitterId);

        await(service.rejectPendingManager(adminAuth0, managerId, "Duplicate"));
        Thread.sleep(300);

        String message = await(pool
            .preparedQuery("SELECT message FROM notifications WHERE user_id = $1 AND type = $2")
            .execute(Tuple.of(submitterId, "manager_rejected"))
            .map(rs -> rs.iterator().next().getString("message")));
        assertTrue(message.contains("Frank Lee"));
        assertTrue(message.contains("TechCorp"));
        assertTrue(message.contains("Reason: Duplicate"));
    }

    @Test
    void rejectPendingManager_withSubmitter_nullReason_notificationHasNoReasonLine() throws Exception {
        String adminAuth0    = insertUser("auth0|admin30", "Admin30", "admin");
        String submitterAuth = insertUser("auth0|sub30",   "Submitter30", "user");
        UUID   submitterId   = findUserId(submitterAuth);
        long managerId = insertPendingManager("Grace Kim", "StartupInc", "Title", submitterId);

        await(service.rejectPendingManager(adminAuth0, managerId, null));
        Thread.sleep(300);

        String message = await(pool
            .preparedQuery("SELECT message FROM notifications WHERE user_id = $1 AND type = $2")
            .execute(Tuple.of(submitterId, "manager_rejected"))
            .map(rs -> rs.iterator().next().getString("message")));
        assertTrue(message.contains("Grace Kim"));
        assertTrue(message.contains("StartupInc"));
        assertFalse(message.contains("Reason:"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getPendingEdits
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getPendingEdits_noPending_returnsEmpty() throws Exception {
        String adminAuth0 = insertUser("auth0|admin11", "Admin11", "admin");
        JsonObject result = await(service.getPendingEdits(adminAuth0, 10, 0));
        assertEquals(0, result.getJsonArray("data").size());
    }

    @Test
    void getPendingEdits_withPendingEdit_returnsIt() throws Exception {
        String adminAuth0 = insertUser("auth0|admin12", "Admin12", "admin");
        String userAuth   = insertUser("auth0|editor01", "Editor01", "user");
        UUID   userId     = findUserId(userAuth);
        long managerId    = insertApprovedManager("Grace Hall", "Corp", "Engineer");
        insertPendingEdit(managerId, userId, "NewCorp", "VP", null, null);

        JsonObject result = await(service.getPendingEdits(adminAuth0, 10, 0));
        assertEquals(1, result.getJsonArray("data").size());
        assertEquals(managerId, result.getJsonArray("data").getJsonObject(0).getLong("managerId"));
        assertEquals("NewCorp", result.getJsonArray("data").getJsonObject(0).getString("newCompany"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // approveEdit
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void approveEdit_notFound_returns404() throws Exception {
        String adminAuth0 = insertUser("auth0|admin13", "Admin13", "admin");
        ServiceException ex = assertServiceException(service.approveEdit(adminAuth0, UUID.randomUUID()));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void approveEdit_alreadyApproved_returns409() throws Exception {
        String adminAuth0 = insertUser("auth0|admin14", "Admin14", "admin");
        String userAuth   = insertUser("auth0|editor02", "Editor02", "user");
        UUID   userId     = findUserId(userAuth);
        long managerId    = insertApprovedManager("Henry Ice", "OldCorp", "Engineer");
        UUID editId = insertPendingEdit(managerId, userId, "NewCorp", "VP", null, null);

        // Approve once
        await(service.approveEdit(adminAuth0, editId));

        // Try to approve again
        ServiceException ex = assertServiceException(service.approveEdit(adminAuth0, editId));
        assertEquals(409, ex.getStatusCode());
    }

    @Test
    void approveEdit_success_updatesManagerProfile() throws Exception {
        String adminAuth0 = insertUser("auth0|admin15", "Admin15", "admin");
        String userAuth   = insertUser("auth0|editor03", "Editor03", "user");
        UUID   userId     = findUserId(userAuth);
        long managerId    = insertApprovedManager("Iris Jones", "OldCorp", "Engineer");
        UUID editId = insertPendingEdit(managerId, userId, "NewCorp", "Senior Engineer", null, null);

        JsonObject result = await(service.approveEdit(adminAuth0, editId));
        assertTrue(result.getBoolean("success"));

        // Manager profile updated
        io.vertx.sqlclient.Row mgr = await(pool
            .preparedQuery("SELECT company, title FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));
        assertEquals("NewCorp",          mgr.getString("company"));
        assertEquals("Senior Engineer",  mgr.getString("title"));

        // Edit status is approved
        String editStatus = await(pool
            .preparedQuery("SELECT status FROM manager_edits WHERE id = $1")
            .execute(Tuple.of(editId))
            .map(rs -> rs.iterator().next().getString("status")));
        assertEquals("approved", editStatus);

        // Career history entry created
        long histCount = await(pool
            .preparedQuery("SELECT COUNT(*) FROM career_history WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertTrue(histCount >= 1L);
    }

    @Test
    void approveEdit_withProposedBy_sendsNotification() throws Exception {
        String adminAuth0 = insertUser("auth0|admin16", "Admin16", "admin");
        String userAuth   = insertUser("auth0|editor04", "Editor04", "user");
        UUID   userId     = findUserId(userAuth);
        long managerId    = insertApprovedManager("Jack Kim", "Corp", "Engineer");
        UUID editId = insertPendingEdit(managerId, userId, "NewCorp", null, null, null);

        await(service.approveEdit(adminAuth0, editId));
        Thread.sleep(300);

        long notifCount = await(pool
            .preparedQuery("SELECT COUNT(*) FROM notifications WHERE user_id = $1 AND type = $2")
            .execute(Tuple.of(userId, "review_accepted"))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, notifCount);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // rejectEdit
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void rejectEdit_notFound_returns404() throws Exception {
        String adminAuth0 = insertUser("auth0|admin17", "Admin17", "admin");
        ServiceException ex = assertServiceException(service.rejectEdit(adminAuth0, UUID.randomUUID()));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void rejectEdit_success_marksEditRejected() throws Exception {
        String adminAuth0 = insertUser("auth0|admin18", "Admin18", "admin");
        String userAuth   = insertUser("auth0|editor05", "Editor05", "user");
        UUID   userId     = findUserId(userAuth);
        long managerId    = insertApprovedManager("Karen Lee", "Corp", "Engineer");
        UUID editId = insertPendingEdit(managerId, userId, "NewCorp", null, null, null);

        JsonObject result = await(service.rejectEdit(adminAuth0, editId));
        assertTrue(result.getBoolean("success"));

        String status = await(pool
            .preparedQuery("SELECT status FROM manager_edits WHERE id = $1")
            .execute(Tuple.of(editId))
            .map(rs -> rs.iterator().next().getString("status")));
        assertEquals("rejected", status);
    }

    @Test
    void rejectEdit_alreadyRejected_returns404() throws Exception {
        String adminAuth0 = insertUser("auth0|admin19", "Admin19", "admin");
        String userAuth   = insertUser("auth0|editor06", "Editor06", "user");
        UUID   userId     = findUserId(userAuth);
        long managerId    = insertApprovedManager("Leo Mars", "Corp", "Engineer");
        UUID editId = insertPendingEdit(managerId, userId, "NewCorp", null, null, null);

        await(service.rejectEdit(adminAuth0, editId));

        ServiceException ex = assertServiceException(service.rejectEdit(adminAuth0, editId));
        assertEquals(404, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getUsers / getBannedUsers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getUsers_returnsOnlyNonAdminUsers() throws Exception {
        String adminAuth0 = insertUser("auth0|admin20", "Admin20", "admin");
        insertUser("auth0|regular02", "Regular02", "user");
        insertUser("auth0|regular03", "Regular03", "user");

        JsonObject result = await(service.getUsers(adminAuth0, 10, 0));
        assertEquals(2, result.getJsonArray("data").size());
    }

    @Test
    void getUsers_bannedUser_isBannedTrue() throws Exception {
        String adminAuth0 = insertUser("auth0|admin21", "Admin21", "admin");
        String userAuth   = insertUser("auth0|banned01", "BannedUser01", "user");
        UUID   userId     = findUserId(userAuth);
        await(pool.preparedQuery("INSERT INTO banned_users(user_id, reason, banned_by) VALUES ($1,$2,$3)")
            .execute(Tuple.of(userId, "Spam", "admin_user")));

        JsonObject result = await(service.getUsers(adminAuth0, 10, 0));
        assertTrue(result.getJsonArray("data").getJsonObject(0).getBoolean("isBanned"));
    }

    @Test
    void getBannedUsers_returnsBannedList() throws Exception {
        String adminAuth0 = insertUser("auth0|admin22", "Admin22", "admin");
        String userAuth   = insertUser("auth0|tobebanned", "ToBeBanned01", "user");
        UUID   userId     = findUserId(userAuth);
        await(pool.preparedQuery("INSERT INTO banned_users(user_id, reason, banned_by) VALUES ($1,$2,$3)")
            .execute(Tuple.of(userId, "Harassment", "admin_user")));

        JsonObject result = await(service.getBannedUsers(adminAuth0, 10, 0));
        assertEquals(1, result.getJsonArray("data").size());
        assertEquals("Harassment", result.getJsonArray("data").getJsonObject(0).getString("reason"));
        assertEquals(userId.toString(), result.getJsonArray("data").getJsonObject(0).getString("userId"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // banUser / unbanUser
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void banUser_nullReason_returns400() throws Exception {
        String adminAuth0 = insertUser("auth0|admin23", "Admin23", "admin");
        ServiceException ex = assertServiceException(service.banUser(adminAuth0, UUID.randomUUID(), null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void banUser_success_createsBanRecord() throws Exception {
        String adminAuth0 = insertUser("auth0|admin24", "Admin24", "admin");
        String userAuth   = insertUser("auth0|target01", "Target01", "user");
        UUID   targetId   = findUserId(userAuth);

        JsonObject result = await(service.banUser(adminAuth0, targetId, "Violates ToS"));
        assertTrue(result.getBoolean("success"));

        long banCount = await(pool
            .preparedQuery("SELECT COUNT(*) FROM banned_users WHERE user_id = $1")
            .execute(Tuple.of(targetId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, banCount);
    }

    @Test
    void banUser_alreadyBanned_returns409() throws Exception {
        String adminAuth0 = insertUser("auth0|admin25", "Admin25", "admin");
        String userAuth   = insertUser("auth0|target02", "Target02", "user");
        UUID   targetId   = findUserId(userAuth);
        await(pool.preparedQuery("INSERT INTO banned_users(user_id, reason, banned_by) VALUES ($1,$2,$3)")
            .execute(Tuple.of(targetId, "Spam", "admin_user")));

        ServiceException ex = assertServiceException(service.banUser(adminAuth0, targetId, "Still spam"));
        assertEquals(409, ex.getStatusCode());
    }

    @Test
    void unbanUser_notBanned_returns404() throws Exception {
        String adminAuth0 = insertUser("auth0|admin26", "Admin26", "admin");
        String userAuth   = insertUser("auth0|notbanned01", "NotBanned01", "user");
        UUID   userId     = findUserId(userAuth);

        ServiceException ex = assertServiceException(service.unbanUser(adminAuth0, userId));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void unbanUser_success_removesBanRecord() throws Exception {
        String adminAuth0 = insertUser("auth0|admin27", "Admin27", "admin");
        String userAuth   = insertUser("auth0|tounban01", "ToUnban01", "user");
        UUID   userId     = findUserId(userAuth);
        await(pool.preparedQuery("INSERT INTO banned_users(user_id, reason, banned_by) VALUES ($1,$2,$3)")
            .execute(Tuple.of(userId, "Test", "admin_user")));

        JsonObject result = await(service.unbanUser(adminAuth0, userId));
        assertTrue(result.getBoolean("success"));

        long banCount = await(pool
            .preparedQuery("SELECT COUNT(*) FROM banned_users WHERE user_id = $1")
            .execute(Tuple.of(userId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0L, banCount);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // mergeManagers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void mergeManagers_sameId_returns400() throws Exception {
        String adminAuth0 = insertUser("auth0|admin28", "Admin28", "admin");
        long managerId = insertApprovedManager("Self Manager", "Corp", "Title");
        ServiceException ex = assertServiceException(service.mergeManagers(adminAuth0, managerId, managerId));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void mergeManagers_oneNotFound_returns404() throws Exception {
        String adminAuth0 = insertUser("auth0|admin29", "Admin29", "admin");
        long keepId = insertApprovedManager("Keep Manager", "Corp", "Title");
        ServiceException ex = assertServiceException(service.mergeManagers(adminAuth0, keepId, 999999L));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void mergeManagers_success_deletesSourceManager() throws Exception {
        String adminAuth0 = insertUser("auth0|admin30", "Admin30", "admin");
        String userAuth   = insertUser("auth0|merge-user", "MergeUser01", "user");
        UUID   userId     = findUserId(userAuth);
        long keepId  = insertApprovedManager("Keep Manager", "Corp", "Title");
        long mergeId = insertApprovedManager("Merge Manager", "Corp", "Title");

        insertReview(mergeId, userId);

        JsonObject result = await(service.mergeManagers(adminAuth0, keepId, mergeId));
        assertTrue(result.getBoolean("success"));
        assertEquals(keepId, result.getLong("keepId"));

        // Merge manager deleted
        long mergeExists = await(pool
            .preparedQuery("SELECT COUNT(*) FROM managers WHERE id = $1")
            .execute(Tuple.of(mergeId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0L, mergeExists);

        // Keep manager exists
        long keepExists = await(pool
            .preparedQuery("SELECT COUNT(*) FROM managers WHERE id = $1")
            .execute(Tuple.of(keepId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, keepExists);
    }

    @Test
    void mergeManagers_movesReviewsToKeepManager() throws Exception {
        String adminAuth0 = insertUser("auth0|admin31", "Admin31", "admin");
        String userAuth   = insertUser("auth0|merge-user2", "MergeUser02", "user");
        UUID   userId     = findUserId(userAuth);
        long keepId  = insertApprovedManager("Keep Manager2", "Corp", "Title");
        long mergeId = insertApprovedManager("Merge Manager2", "Corp", "Title");

        insertReview(mergeId, userId);

        await(service.mergeManagers(adminAuth0, keepId, mergeId));

        long keepReviews = await(pool
            .preparedQuery("SELECT COUNT(*) FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(keepId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, keepReviews);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // adminEditManager
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void adminEditManager_nonAdmin_returns403() throws Exception {
        String userAuth   = insertUser("auth0|edit-user01", "EditUser01", "user");
        long managerId    = insertApprovedManager("Edit Target", "OldCorp", "OldTitle");
        ServiceException ex = assertServiceException(service.adminEditManager(userAuth, managerId, "New Name", null, null, null));
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void adminEditManager_notFound_returns404() throws Exception {
        String adminAuth = insertUser("auth0|edit-admin01", "EditAdmin01", "admin");
        ServiceException ex = assertServiceException(service.adminEditManager(adminAuth, 999999L, "Name", null, null, null));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void adminEditManager_blankName_returns400() throws Exception {
        String adminAuth = insertUser("auth0|edit-admin02", "EditAdmin02", "admin");
        long managerId   = insertApprovedManager("Test", "Corp", "Title");
        ServiceException ex = assertServiceException(service.adminEditManager(adminAuth, managerId, "   ", null, null, null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void adminEditManager_updatesName() throws Exception {
        String adminAuth = insertUser("auth0|edit-admin03", "EditAdmin03", "admin");
        long managerId   = insertApprovedManager("Old Name", "Corp", "Title");

        JsonObject result = await(service.adminEditManager(adminAuth, managerId, "New Name", null, null, null));
        assertTrue(result.getBoolean("success"));
        assertEquals("New Name", result.getString("name"));

        String name = await(pool.preparedQuery("SELECT name FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("name")));
        assertEquals("New Name", name);
    }

    @Test
    void adminEditManager_updatesTitle_cascadesToReviews() throws Exception {
        String adminAuth = insertUser("auth0|edit-admin04", "EditAdmin04", "admin");
        String userAuth  = insertUser("auth0|edit-rev01",   "EditRev01",   "user");
        UUID   userId    = findUserId(userAuth);
        long managerId   = insertApprovedManager("Manager A", "Corp", "OldTitle");
        insertReviewWithValues(managerId, userId, "Corp", "OldTitle");

        await(service.adminEditManager(adminAuth, managerId, null, "NewTitle", null, null));

        String reviewTitle = await(pool.preparedQuery(
            "SELECT manager_title FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("manager_title")));
        assertEquals("NewTitle", reviewTitle);
    }

    @Test
    void adminEditManager_updatesCompany_cascadesToReviews() throws Exception {
        String adminAuth = insertUser("auth0|edit-admin05", "EditAdmin05", "admin");
        String userAuth  = insertUser("auth0|edit-rev02",   "EditRev02",   "user");
        UUID   userId    = findUserId(userAuth);
        long managerId   = insertApprovedManager("Manager B", "OldCorp", "Title");
        insertReviewWithValues(managerId, userId, "OldCorp", "Title");

        await(service.adminEditManager(adminAuth, managerId, null, null, "NewCorp", null));

        String reviewCompany = await(pool.preparedQuery(
            "SELECT manager_company FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("manager_company")));
        assertEquals("NewCorp", reviewCompany);
    }

    @Test
    void adminEditManager_updatesTitleAndCompany_cascadesToCareerHistory() throws Exception {
        String adminAuth = insertUser("auth0|edit-admin06", "EditAdmin06", "admin");
        long managerId   = insertApprovedManager("Manager C", "OldCorp", "OldTitle");
        // Insert a career_history entry with the old values
        await(pool.preparedQuery(
            "INSERT INTO career_history(manager_id, company, title, start_date) VALUES ($1,$2,$3,now())")
            .execute(Tuple.of(managerId, "OldCorp", "OldTitle")));

        await(service.adminEditManager(adminAuth, managerId, null, "NewTitle", "NewCorp", null));

        io.vertx.sqlclient.Row hist = await(pool.preparedQuery(
            "SELECT title, company FROM career_history WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));
        assertEquals("NewTitle", hist.getString("title"));
        assertEquals("NewCorp",  hist.getString("company"));
    }

    @Test
    void adminEditManager_titleChangeDoesNotCascadeToMismatchedReviews() throws Exception {
        String adminAuth  = insertUser("auth0|edit-admin07", "EditAdmin07", "admin");
        String userAuth1  = insertUser("auth0|edit-rev03",   "EditRev03",   "user");
        String userAuth2  = insertUser("auth0|edit-rev04",   "EditRev04",   "user");
        UUID   userId1    = findUserId(userAuth1);
        UUID   userId2    = findUserId(userAuth2);
        long managerId    = insertApprovedManager("Manager D", "Corp", "OldTitle");
        insertReview(managerId, userId1); // manager_title = "Title" (from insertReview helper)
        // Insert a review with a custom (different) title
        await(pool.preparedQuery("""
            INSERT INTO reviews(manager_id, user_id, author, overall_rating,
                communication_style, perceived_approachability, perceived_clarity_of_expectations,
                feedback_style, perceived_supportiveness, decision_making_style,
                organization_and_planning_style, delegation_style, perceived_professional_demeanor,
                overall_working_experience, manager_company, manager_title, text, verified, helpful_count)
            VALUES ($1,$2,'Test',3.0,3.0,3.0,3.0,3.0,3.0,3.0,3.0,3.0,3.0,3.0,'Corp','DifferentTitle','text',false,0)
            """).execute(Tuple.of(managerId, userId2)));

        // Change title from OldTitle to NewTitle — should only affect the review that had OldTitle
        // Note: insertReview uses "Title" not "OldTitle", so neither review matches "OldTitle"
        await(service.adminEditManager(adminAuth, managerId, null, "NewTitle", null, null));

        // The review with "DifferentTitle" should be unchanged
        long unchangedCount = await(pool.preparedQuery(
            "SELECT COUNT(*) FROM reviews WHERE manager_id = $1 AND manager_title = 'DifferentTitle'")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, unchangedCount);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private String insertUser(String auth0Id, String username, String role) throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id, email, username, first_name, last_name, role) " +
            "VALUES ($1,$2,$3,$4,$5,$6)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User", role)));
        return auth0Id;
    }

    private UUID findUserId(String auth0Id) throws Exception {
        return await(pool
            .preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private long insertPendingManager(String name, String company, String title, UUID submittedBy) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages,submitted_by) " +
            "VALUES ($1,$2,$3,'img','active','pending_approval',0,0,'{}',$4) RETURNING id")
            .execute(Tuple.of(name, company, title, submittedBy))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertApprovedManager(String name, String company, String title) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages) " +
            "VALUES ($1,$2,$3,'img','active','approved',0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private UUID insertPendingEdit(long managerId, UUID proposedBy, String newCompany,
                                    String newTitle, String newStatus, String newLinkedinUrl) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO manager_edits(manager_id, proposed_by, new_company, new_title, new_status, new_linkedin_url) " +
            "VALUES ($1,$2,$3,$4,$5,$6) RETURNING id")
            .execute(Tuple.of(managerId, proposedBy, newCompany, newTitle, newStatus, newLinkedinUrl))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private void insertReview(long managerId, UUID userId) throws Exception {
        insertReviewWithValues(managerId, userId, "Corp", "Title");
    }

    private void insertReviewWithValues(long managerId, UUID userId, String company, String title) throws Exception {
        await(pool.preparedQuery("""
            INSERT INTO reviews(manager_id, user_id, author, overall_rating,
                communication_style, perceived_approachability, perceived_clarity_of_expectations,
                feedback_style, perceived_supportiveness, decision_making_style,
                organization_and_planning_style, delegation_style, perceived_professional_demeanor,
                overall_working_experience, manager_company, manager_title, text, verified, helpful_count)
            VALUES ($1,$2,'Test',3.0,3.0,3.0,3.0,3.0,3.0,3.0,3.0,3.0,3.0,3.0,$3,$4,'text',false,0)
            """).execute(Tuple.of(managerId, userId, company, title)));
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
