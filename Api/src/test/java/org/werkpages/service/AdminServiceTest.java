package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.RowSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.NotificationRepository;
import org.werkpages.repository.ReviewRepository;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class AdminServiceTest {

    private static final String ADMIN_AUTH0_ID = "auth0|admin-user";
    private static final UUID   ADMIN_ID       = UUID.randomUUID();
    private static final long   MANAGER_ID     = 99L;

    private UserRepository         userRepo;
    private ManagerRepository      managerRepo;
    private ReviewRepository       reviewRepo;
    private EditRepository         editRepo;
    private NotificationRepository notifRepo;
    private CompanyRepository      companyRepo;
    private AdminService           service;

    @BeforeEach
    void setUp() {
        userRepo    = mock(UserRepository.class);
        managerRepo = mock(ManagerRepository.class);
        reviewRepo  = mock(ReviewRepository.class);
        editRepo    = mock(EditRepository.class);
        notifRepo   = mock(NotificationRepository.class);
        companyRepo = mock(CompanyRepository.class);
        when(companyRepo.refreshCompanyStats()).thenReturn(Future.succeededFuture());
        when(companyRepo.updateCompanyStatsForManager(anyLong())).thenReturn(Future.succeededFuture());
        // The stats write is awaited now rather than fired and forgotten, so the mock has
        // to answer with a real future instead of Mockito's default null.
        when(companyRepo.syncStatsForManager(anyLong())).thenReturn(Future.succeededFuture());
        when(companyRepo.updateCompanyStatsForCompany(anyLong())).thenReturn(Future.succeededFuture());
        when(managerRepo.findSlugs(anyLong())).thenReturn(Future.succeededFuture(Optional.empty()));
        when(managerRepo.findCurrentRoleStart(anyLong())).thenReturn(Future.succeededFuture(Optional.empty()));
        service     = new AdminService(userRepo, managerRepo, reviewRepo, editRepo, notifRepo, companyRepo);

        Row adminRow = adminUserRow(ADMIN_ID);
        when(userRepo.findByAuth0IdWithBan(ADMIN_AUTH0_ID))
            .thenReturn(Future.succeededFuture(Optional.of(adminRow)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // requireAdmin guards
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void nullAuth0Id_returns401() {
        ServiceException ex = assertServiceFails(service.getPendingManagers(null, 10, 0));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void userNotFound_returns401() {
        when(userRepo.findByAuth0IdWithBan(ADMIN_AUTH0_ID))
            .thenReturn(Future.succeededFuture(Optional.empty()));
        ServiceException ex = assertServiceFails(service.getPendingManagers(ADMIN_AUTH0_ID, 10, 0));
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    void nonAdminUser_returns403() {
        Row nonAdmin = regularUserRow(UUID.randomUUID());
        when(userRepo.findByAuth0IdWithBan(ADMIN_AUTH0_ID))
            .thenReturn(Future.succeededFuture(Optional.of(nonAdmin)));
        ServiceException ex = assertServiceFails(service.getPendingManagers(ADMIN_AUTH0_ID, 10, 0));
        assertEquals(403, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getPendingManagers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getPendingManagers_emptyResult_returnsPagedResponse() throws Exception {
        RowSet<Row> emptyRs = rowSetOf();
        when(managerRepo.findPendingForAdmin(10, 0)).thenReturn(Future.succeededFuture(emptyRs));

        JsonObject result = await(service.getPendingManagers(ADMIN_AUTH0_ID, 10, 0));
        assertEquals(0, result.getJsonArray("data").size());
        assertEquals(10, result.getInteger("limit"));
        assertEquals(0, result.getInteger("offset"));
    }

    @Test
    void getPendingManagers_withResults_mapsFieldsCorrectly() throws Exception {
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        RowSet<Row> rs = rowSetOf(pendingManagerRow(MANAGER_ID, "Alice Smith", "Acme", "Manager", "alice99", createdAt));
        when(managerRepo.findPendingForAdmin(20, 5)).thenReturn(Future.succeededFuture(rs));

        JsonObject result = await(service.getPendingManagers(ADMIN_AUTH0_ID, 20, 5));
        JsonArray data = result.getJsonArray("data");
        assertEquals(1, data.size());
        JsonObject item = data.getJsonObject(0);
        assertEquals(MANAGER_ID, item.getLong("id"));
        assertEquals("Alice Smith", item.getString("name"));
        assertEquals("Acme", item.getString("company"));
        assertEquals("Manager", item.getString("title"));
        assertEquals("alice99", item.getString("submittedBy"));
        assertEquals(20, result.getInteger("limit"));
        assertEquals(5, result.getInteger("offset"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // approvePendingManager
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void approvePendingManager_notFound_returns404() {
        when(managerRepo.approve(MANAGER_ID)).thenReturn(Future.succeededFuture(Optional.empty()));
        ServiceException ex = assertServiceFails(service.approvePendingManager(ADMIN_AUTH0_ID, MANAGER_ID, null));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void approvePendingManager_noLogo_setsNeedsLogoTrue() throws Exception {
        UUID submittedBy = UUID.randomUUID();
        Row approved = approvedManagerRow(MANAGER_ID, "Acme", null, submittedBy, "Bob Jones");
        when(managerRepo.approve(MANAGER_ID))
            .thenReturn(Future.succeededFuture(Optional.of(approved)));

        JsonObject result = await(service.approvePendingManager(ADMIN_AUTH0_ID, MANAGER_ID, null));
        assertTrue(result.getBoolean("success"));
        assertTrue(result.getBoolean("_needsLogo"));
        assertEquals("Acme", result.getString("_company"));
        verify(notifRepo).sendAsync(eq(submittedBy), eq("manager_approved"), anyString(), anyString(), eq(MANAGER_ID));
    }

    @Test
    void approvePendingManager_existingLogo_setsNeedsLogoFalse() throws Exception {
        Row approved = approvedManagerRow(MANAGER_ID, "Acme", "https://logo.url/acme.png", null, "Bob Jones");
        when(managerRepo.approve(MANAGER_ID))
            .thenReturn(Future.succeededFuture(Optional.of(approved)));

        JsonObject result = await(service.approvePendingManager(ADMIN_AUTH0_ID, MANAGER_ID, null));
        assertFalse(result.getBoolean("_needsLogo"));
    }

    @Test
    void approvePendingManager_nullSubmittedBy_noNotification() throws Exception {
        Row approved = approvedManagerRow(MANAGER_ID, "Corp", null, null, "NoName");
        when(managerRepo.approve(MANAGER_ID))
            .thenReturn(Future.succeededFuture(Optional.of(approved)));

        await(service.approvePendingManager(ADMIN_AUTH0_ID, MANAGER_ID, null));
        verify(notifRepo, never()).sendAsync(any(UUID.class), anyString(), anyString(), anyString(), any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // rejectPendingManager
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void rejectPendingManager_notFound_returns404() {
        when(managerRepo.reject(MANAGER_ID)).thenReturn(Future.succeededFuture(Optional.empty()));
        ServiceException ex = assertServiceFails(service.rejectPendingManager(ADMIN_AUTH0_ID, MANAGER_ID, null));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void rejectPendingManager_withReason_includesNameCompanyAndReasonInNotification() throws Exception {
        UUID submittedBy = UUID.randomUUID();
        Row rejected = rejectedManagerRow(MANAGER_ID, submittedBy, "Carol White", "Acme Corp");
        when(managerRepo.reject(MANAGER_ID))
            .thenReturn(Future.succeededFuture(Optional.of(rejected)));

        JsonObject result = await(service.rejectPendingManager(ADMIN_AUTH0_ID, MANAGER_ID, "Duplicate profile"));
        assertTrue(result.getBoolean("success"));
        verify(notifRepo).sendAsync(eq(submittedBy), eq("manager_rejected"), anyString(),
            argThat(msg -> msg.contains("Carol White")
                        && msg.contains("Acme Corp")
                        && msg.contains("Reason: Duplicate profile")));
    }

    @Test
    void rejectPendingManager_nullReason_messageContainsNameAndCompanyButNoReason() throws Exception {
        UUID submittedBy = UUID.randomUUID();
        Row rejected = rejectedManagerRow(MANAGER_ID, submittedBy, "Carol White", "Acme Corp");
        when(managerRepo.reject(MANAGER_ID))
            .thenReturn(Future.succeededFuture(Optional.of(rejected)));

        await(service.rejectPendingManager(ADMIN_AUTH0_ID, MANAGER_ID, null));
        verify(notifRepo).sendAsync(eq(submittedBy), eq("manager_rejected"), anyString(),
            argThat(msg -> msg.contains("Carol White")
                        && msg.contains("Acme Corp")
                        && !msg.contains("Reason:")));
    }

    @Test
    void rejectPendingManager_blankReason_messageContainsNameAndCompanyButNoReason() throws Exception {
        UUID submittedBy = UUID.randomUUID();
        Row rejected = rejectedManagerRow(MANAGER_ID, submittedBy, "Carol White", "Acme Corp");
        when(managerRepo.reject(MANAGER_ID))
            .thenReturn(Future.succeededFuture(Optional.of(rejected)));

        await(service.rejectPendingManager(ADMIN_AUTH0_ID, MANAGER_ID, "   "));
        verify(notifRepo).sendAsync(eq(submittedBy), eq("manager_rejected"), anyString(),
            argThat(msg -> msg.contains("Carol White")
                        && msg.contains("Acme Corp")
                        && !msg.contains("Reason:")));
    }

    @Test
    void rejectPendingManager_nullSubmittedBy_noNotification() throws Exception {
        Row rejected = rejectedManagerRow(MANAGER_ID, null, "Dave Brown", "Some Corp");
        when(managerRepo.reject(MANAGER_ID))
            .thenReturn(Future.succeededFuture(Optional.of(rejected)));

        await(service.rejectPendingManager(ADMIN_AUTH0_ID, MANAGER_ID, "test"));
        verify(notifRepo, never()).sendAsync(any(UUID.class), anyString(), anyString(), anyString());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getPendingEdits
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getPendingEdits_emptyResult_returnsPagedResponse() throws Exception {
        RowSet<Row> emptyRs = rowSetOf();
        when(editRepo.findPendingForAdmin(10, 0)).thenReturn(Future.succeededFuture(emptyRs));

        JsonObject result = await(service.getPendingEdits(ADMIN_AUTH0_ID, 10, 0));
        assertEquals(0, result.getJsonArray("data").size());
        assertEquals(10, result.getInteger("limit"));
        assertEquals(0, result.getInteger("offset"));
    }

    @Test
    void getPendingEdits_withResults_mapsFieldsCorrectly() throws Exception {
        UUID editId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        RowSet<Row> rs = rowSetOf(pendingEditRow(editId, MANAGER_ID, "Alice Smith",
            "Acme", "Director", "bob99", "NewCorp", "VP", "pending", createdAt));
        when(editRepo.findPendingForAdmin(10, 0)).thenReturn(Future.succeededFuture(rs));

        JsonObject result = await(service.getPendingEdits(ADMIN_AUTH0_ID, 10, 0));
        JsonArray data = result.getJsonArray("data");
        assertEquals(1, data.size());
        JsonObject item = data.getJsonObject(0);
        assertEquals(editId.toString(), item.getString("id"));
        assertEquals(MANAGER_ID, item.getLong("managerId"));
        assertEquals("Alice Smith", item.getString("managerName"));
        assertEquals("Acme", item.getString("currentCompany"));
        assertEquals("bob99", item.getString("requestedBy"));
        assertEquals("NewCorp", item.getString("newCompany"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // approveEdit
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void approveEdit_notFound_returns404() {
        UUID editId = UUID.randomUUID();
        when(editRepo.findByIdWithManager(editId)).thenReturn(Future.succeededFuture(Optional.empty()));
        ServiceException ex = assertServiceFails(service.approveEdit(ADMIN_AUTH0_ID, editId));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void approveEdit_notPending_returns409() {
        UUID editId = UUID.randomUUID();
        Row editRow = editRowForApprove(editId, MANAGER_ID, "approved", "Acme", "Dir",
            "NewCorp", "VP", null, null, UUID.randomUUID(), "Alice", OffsetDateTime.now(ZoneOffset.UTC));
        when(editRepo.findByIdWithManager(editId))
            .thenReturn(Future.succeededFuture(Optional.of(editRow)));
        ServiceException ex = assertServiceFails(service.approveEdit(ADMIN_AUTH0_ID, editId));
        assertEquals(409, ex.getStatusCode());
    }

    @Test
    void approveEdit_existingOpenEntry_success() throws Exception {
        UUID editId     = UUID.randomUUID();
        UUID proposedBy = UUID.randomUUID();
        Row editRow = editRowForApprove(editId, MANAGER_ID, "pending", "Acme", "Dir",
            "NewCorp", "VP", null, null, proposedBy, "Alice Smith", OffsetDateTime.now(ZoneOffset.UTC));
        Row companyRow = companyRowWithId(42L);
        when(editRepo.findByIdWithManager(editId))
            .thenReturn(Future.succeededFuture(Optional.of(editRow)));
        when(companyRepo.findOrCreate(anyString(), isNull(), isNull()))
            .thenReturn(Future.succeededFuture(companyRow));
        when(managerRepo.closeOpenCareerEntry(eq(MANAGER_ID), any()))
            .thenReturn(Future.succeededFuture(1));
        when(managerRepo.insertCareerEntry(eq(MANAGER_ID), anyString(), anyString(), any(), isNull(), any()))
            .thenReturn(Future.succeededFuture());
        when(managerRepo.update(eq(MANAGER_ID), anyString(), anyString(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any()))
            .thenReturn(Future.succeededFuture(Optional.empty()));
        when(editRepo.approve(eq(editId), eq(ADMIN_ID), any()))
            .thenReturn(Future.succeededFuture());

        JsonObject result = await(service.approveEdit(ADMIN_AUTH0_ID, editId));
        assertTrue(result.getBoolean("success"));
        assertEquals(MANAGER_ID, result.getLong("managerId"));
        assertEquals("NewCorp", result.getString("newCompany"));
        verify(notifRepo).sendAsync(eq(proposedBy), eq("review_accepted"), anyString(), anyString(), eq(MANAGER_ID));
        // Only one insertCareerEntry: the new career entry (open entry was closed by closeOpenCareerEntry)
        verify(managerRepo, times(1)).insertCareerEntry(eq(MANAGER_ID), anyString(), anyString(), any(), isNull(), any());
    }

    @Test
    void approveEdit_noOpenEntry_archivesOldAndInsertsNew() throws Exception {
        UUID editId = UUID.randomUUID();
        Row editRow = editRowForApprove(editId, MANAGER_ID, "pending", "Acme", "Dir",
            "NewCorp", "VP", null, null, null, "Alice Smith", OffsetDateTime.now(ZoneOffset.UTC));
        Row companyRow = companyRowWithId(42L);
        when(editRepo.findByIdWithManager(editId))
            .thenReturn(Future.succeededFuture(Optional.of(editRow)));
        when(companyRepo.findOrCreate(anyString(), isNull(), isNull()))
            .thenReturn(Future.succeededFuture(companyRow));
        when(managerRepo.closeOpenCareerEntry(eq(MANAGER_ID), any()))
            .thenReturn(Future.succeededFuture(0));
        when(managerRepo.insertCareerEntry(eq(MANAGER_ID), anyString(), anyString(), any(), any(), any()))
            .thenReturn(Future.succeededFuture());
        when(managerRepo.update(eq(MANAGER_ID), anyString(), anyString(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any()))
            .thenReturn(Future.succeededFuture(Optional.empty()));
        when(editRepo.approve(eq(editId), eq(ADMIN_ID), any()))
            .thenReturn(Future.succeededFuture());

        JsonObject result = await(service.approveEdit(ADMIN_AUTH0_ID, editId));
        assertTrue(result.getBoolean("success"));
        // Two insertCareerEntry calls: archive old, then insert new
        verify(managerRepo, times(2)).insertCareerEntry(eq(MANAGER_ID), anyString(), anyString(), any(), any(), any());
        verify(notifRepo, never()).sendAsync(any(UUID.class), anyString(), anyString(), anyString(), any());
    }

    @Test
    void approveEdit_nullProposedBy_noNotification() throws Exception {
        UUID editId = UUID.randomUUID();
        Row editRow = editRowForApprove(editId, MANAGER_ID, "pending", "Acme", "Dir",
            "NewCorp", "VP", null, null, null, "Alice", OffsetDateTime.now(ZoneOffset.UTC));
        Row companyRow = companyRowWithId(42L);
        when(editRepo.findByIdWithManager(editId))
            .thenReturn(Future.succeededFuture(Optional.of(editRow)));
        when(companyRepo.findOrCreate(anyString(), isNull(), isNull()))
            .thenReturn(Future.succeededFuture(companyRow));
        when(managerRepo.closeOpenCareerEntry(eq(MANAGER_ID), any()))
            .thenReturn(Future.succeededFuture(1));
        when(managerRepo.insertCareerEntry(eq(MANAGER_ID), anyString(), anyString(), any(), isNull(), any()))
            .thenReturn(Future.succeededFuture());
        when(managerRepo.update(eq(MANAGER_ID), anyString(), anyString(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any()))
            .thenReturn(Future.succeededFuture(Optional.empty()));
        when(editRepo.approve(eq(editId), eq(ADMIN_ID), any()))
            .thenReturn(Future.succeededFuture());

        await(service.approveEdit(ADMIN_AUTH0_ID, editId));
        verify(notifRepo, never()).sendAsync(any(UUID.class), anyString(), anyString(), anyString(), any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // rejectEdit
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void rejectEdit_notFound_returns404() {
        UUID editId = UUID.randomUUID();
        when(editRepo.findPendingById(editId)).thenReturn(Future.succeededFuture(Optional.empty()));
        ServiceException ex = assertServiceFails(service.rejectEdit(ADMIN_AUTH0_ID, editId));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void rejectEdit_notPending_returns404() {
        UUID editId = UUID.randomUUID();
        Row fetchRow = rejectEditFetchRow(editId, UUID.randomUUID(), "approved", "The Manager");
        when(editRepo.findPendingById(editId))
            .thenReturn(Future.succeededFuture(Optional.of(fetchRow)));
        ServiceException ex = assertServiceFails(service.rejectEdit(ADMIN_AUTH0_ID, editId));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void rejectEdit_repoReturnsEmpty_returns404() {
        UUID editId = UUID.randomUUID();
        Row fetchRow = rejectEditFetchRow(editId, UUID.randomUUID(), "pending", "The Manager");
        when(editRepo.findPendingById(editId))
            .thenReturn(Future.succeededFuture(Optional.of(fetchRow)));
        when(editRepo.reject(eq(editId), eq(ADMIN_ID), any()))
            .thenReturn(Future.succeededFuture(Optional.empty()));
        ServiceException ex = assertServiceFails(service.rejectEdit(ADMIN_AUTH0_ID, editId));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void rejectEdit_success_sendsNotification() throws Exception {
        UUID editId     = UUID.randomUUID();
        UUID proposedBy = UUID.randomUUID();
        Row fetchRow = rejectEditFetchRow(editId, proposedBy, "pending", "Bob Jones");
        when(editRepo.findPendingById(editId))
            .thenReturn(Future.succeededFuture(Optional.of(fetchRow)));
        when(editRepo.reject(eq(editId), eq(ADMIN_ID), any()))
            .thenReturn(Future.succeededFuture(Optional.of(mock(Row.class))));

        JsonObject result = await(service.rejectEdit(ADMIN_AUTH0_ID, editId));
        assertTrue(result.getBoolean("success"));
        verify(notifRepo).sendAsync(eq(proposedBy), eq("review_rejected"), anyString(), anyString());
    }

    @Test
    void rejectEdit_nullProposedBy_noNotification() throws Exception {
        UUID editId = UUID.randomUUID();
        Row fetchRow = rejectEditFetchRow(editId, null, "pending", "Bob Jones");
        when(editRepo.findPendingById(editId))
            .thenReturn(Future.succeededFuture(Optional.of(fetchRow)));
        when(editRepo.reject(eq(editId), eq(ADMIN_ID), any()))
            .thenReturn(Future.succeededFuture(Optional.of(mock(Row.class))));

        await(service.rejectEdit(ADMIN_AUTH0_ID, editId));
        verify(notifRepo, never()).sendAsync(any(UUID.class), anyString(), anyString(), anyString());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getUsers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getUsers_returnsMappedList() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID banId  = UUID.randomUUID();
        RowSet<Row> rs = rowSetOf(userListRow(userId, "alice", "Alice", "Smith", banId));
        when(userRepo.listNonAdminUsers(10, 0)).thenReturn(Future.succeededFuture(rs));

        JsonObject result = await(service.getUsers(ADMIN_AUTH0_ID, 10, 0));
        JsonArray data = result.getJsonArray("data");
        assertEquals(1, data.size());
        JsonObject item = data.getJsonObject(0);
        assertEquals(userId.toString(), item.getString("id"));
        assertEquals("alice", item.getString("username"));
        assertTrue(item.getBoolean("isBanned"));
    }

    @Test
    void getUsers_notBanned_isBannedFalse() throws Exception {
        RowSet<Row> rs = rowSetOf(userListRow(UUID.randomUUID(), "bob", "Bob", "Jones", null));
        when(userRepo.listNonAdminUsers(10, 0)).thenReturn(Future.succeededFuture(rs));

        JsonObject result = await(service.getUsers(ADMIN_AUTH0_ID, 10, 0));
        assertFalse(result.getJsonArray("data").getJsonObject(0).getBoolean("isBanned"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getBannedUsers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getBannedUsers_returnsMappedList() throws Exception {
        UUID banId    = UUID.randomUUID();
        UUID userId   = UUID.randomUUID();
        OffsetDateTime bannedAt = OffsetDateTime.now(ZoneOffset.UTC);
        RowSet<Row> rs = rowSetOf(bannedUserRow(banId, userId, "carol", "Spam", "admin_user", bannedAt));
        when(userRepo.listBannedUsers(10, 0)).thenReturn(Future.succeededFuture(rs));

        JsonObject result = await(service.getBannedUsers(ADMIN_AUTH0_ID, 10, 0));
        JsonArray data = result.getJsonArray("data");
        assertEquals(1, data.size());
        JsonObject item = data.getJsonObject(0);
        assertEquals(banId.toString(), item.getString("id"));
        assertEquals(userId.toString(), item.getString("userId"));
        assertEquals("carol", item.getString("username"));
        assertEquals("Spam", item.getString("reason"));
        assertEquals("admin_user", item.getString("bannedBy"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // banUser
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void banUser_nullReason_returns400() {
        ServiceException ex = assertServiceFails(service.banUser(ADMIN_AUTH0_ID, UUID.randomUUID(), null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void banUser_blankReason_returns400() {
        ServiceException ex = assertServiceFails(service.banUser(ADMIN_AUTH0_ID, UUID.randomUUID(), "   "));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void banUser_reasonTooLong_returns400() {
        ServiceException ex = assertServiceFails(service.banUser(ADMIN_AUTH0_ID, UUID.randomUUID(), "x".repeat(501)));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void banUser_reasonAtExactLimit_succeeds() throws Exception {
        UUID targetId = UUID.randomUUID();
        when(userRepo.findUsernameByAuth0Id(ADMIN_AUTH0_ID)).thenReturn(Future.succeededFuture("admin_user"));
        when(userRepo.banUser(eq(targetId), anyString(), anyString())).thenReturn(Future.succeededFuture(true));

        JsonObject result = await(service.banUser(ADMIN_AUTH0_ID, targetId, "x".repeat(500)));
        assertTrue(result.getBoolean("success"));
    }

    @Test
    void banUser_alreadyBanned_returns409() {
        UUID targetId = UUID.randomUUID();
        when(userRepo.findUsernameByAuth0Id(ADMIN_AUTH0_ID)).thenReturn(Future.succeededFuture("admin_user"));
        when(userRepo.banUser(eq(targetId), anyString(), anyString())).thenReturn(Future.succeededFuture(false));

        ServiceException ex = assertServiceFails(service.banUser(ADMIN_AUTH0_ID, targetId, "Spam"));
        assertEquals(409, ex.getStatusCode());
    }

    @Test
    void banUser_success_sendsNotification() throws Exception {
        UUID targetId = UUID.randomUUID();
        when(userRepo.findUsernameByAuth0Id(ADMIN_AUTH0_ID)).thenReturn(Future.succeededFuture("admin_user"));
        when(userRepo.banUser(eq(targetId), anyString(), anyString())).thenReturn(Future.succeededFuture(true));

        JsonObject result = await(service.banUser(ADMIN_AUTH0_ID, targetId, "Violates ToS"));
        assertTrue(result.getBoolean("success"));
        verify(notifRepo).sendAsync(eq(targetId), eq("user_banned"), anyString(), anyString());
    }

    @Test
    void banUser_reasonIsTrimmed() throws Exception {
        UUID targetId = UUID.randomUUID();
        when(userRepo.findUsernameByAuth0Id(ADMIN_AUTH0_ID)).thenReturn(Future.succeededFuture("admin_user"));
        when(userRepo.banUser(eq(targetId), eq("Spam"), anyString())).thenReturn(Future.succeededFuture(true));

        await(service.banUser(ADMIN_AUTH0_ID, targetId, "  Spam  "));
        verify(userRepo).banUser(eq(targetId), eq("Spam"), anyString());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // unbanUser
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void unbanUser_banNotFound_returns404() {
        UUID targetId = UUID.randomUUID();
        when(userRepo.unbanUser(targetId)).thenReturn(Future.succeededFuture(false));
        ServiceException ex = assertServiceFails(service.unbanUser(ADMIN_AUTH0_ID, targetId));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void unbanUser_success() throws Exception {
        UUID targetId = UUID.randomUUID();
        when(userRepo.unbanUser(targetId)).thenReturn(Future.succeededFuture(true));
        JsonObject result = await(service.unbanUser(ADMIN_AUTH0_ID, targetId));
        assertTrue(result.getBoolean("success"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // mergeManagers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void mergeManagers_sameId_returns400() {
        ServiceException ex = assertServiceFails(service.mergeManagers(ADMIN_AUTH0_ID, 5L, 5L));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void mergeManagers_oneNotFound_returns404() {
        when(managerRepo.countExistingById(any())).thenReturn(Future.succeededFuture(1));
        ServiceException ex = assertServiceFails(service.mergeManagers(ADMIN_AUTH0_ID, 1L, 2L));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void mergeManagers_neitherFound_returns404() {
        when(managerRepo.countExistingById(any())).thenReturn(Future.succeededFuture(0));
        ServiceException ex = assertServiceFails(service.mergeManagers(ADMIN_AUTH0_ID, 1L, 2L));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void mergeManagers_success_returnsKeepId() throws Exception {
        when(managerRepo.countExistingById(any())).thenReturn(Future.succeededFuture(2));
        when(reviewRepo.moveToManager(2L, 1L)).thenReturn(Future.succeededFuture(3));
        when(reviewRepo.deleteByManager(2L)).thenReturn(Future.succeededFuture());
        when(managerRepo.delete(2L)).thenReturn(Future.succeededFuture());
        when(managerRepo.mergeInlineRecalculate(1L)).thenReturn(Future.succeededFuture());

        JsonObject result = await(service.mergeManagers(ADMIN_AUTH0_ID, 1L, 2L));
        assertTrue(result.getBoolean("success"));
        assertEquals(1L, result.getLong("keepId"));
    }

    @Test
    void mergeManagers_checksDependencyOrder() throws Exception {
        when(managerRepo.countExistingById(any())).thenReturn(Future.succeededFuture(2));
        when(reviewRepo.moveToManager(3L, 1L)).thenReturn(Future.succeededFuture(0));
        when(reviewRepo.deleteByManager(3L)).thenReturn(Future.succeededFuture());
        when(managerRepo.delete(3L)).thenReturn(Future.succeededFuture());
        when(managerRepo.mergeInlineRecalculate(1L)).thenReturn(Future.succeededFuture());

        await(service.mergeManagers(ADMIN_AUTH0_ID, 1L, 3L));
        verify(reviewRepo).moveToManager(3L, 1L);
        verify(reviewRepo).deleteByManager(3L);
        verify(managerRepo).delete(3L);
        verify(managerRepo).mergeInlineRecalculate(1L);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // updateManagerLogo
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void updateManagerLogo_delegatesToRepo() throws Exception {
        when(managerRepo.updateLogoUrl(MANAGER_ID, "https://logo.url/x.png"))
            .thenReturn(Future.succeededFuture(true));
        await(service.updateManagerLogo(MANAGER_ID, "https://logo.url/x.png"));
        verify(managerRepo).updateLogoUrl(MANAGER_ID, "https://logo.url/x.png");
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

    private static Row adminUserRow(UUID id) {
        Row row = mock(Row.class);
        when(row.getUUID("id")).thenReturn(id);
        when(row.getString("role")).thenReturn("admin");
        return row;
    }

    private static Row regularUserRow(UUID id) {
        Row row = mock(Row.class);
        when(row.getUUID("id")).thenReturn(id);
        when(row.getString("role")).thenReturn("user");
        return row;
    }

    private static Row pendingManagerRow(long id, String name, String company, String title,
                                          String submittedByUsername, OffsetDateTime createdAt) {
        Row row = mock(Row.class);
        when(row.getLong("id")).thenReturn(id);
        when(row.getString("name")).thenReturn(name);
        when(row.getString("company")).thenReturn(company);
        when(row.getString("title")).thenReturn(title);
        when(row.getString("image")).thenReturn(null);
        when(row.getString("submitted_by_username")).thenReturn(submittedByUsername);
        when(row.getOffsetDateTime("created_at")).thenReturn(createdAt);
        return row;
    }

    private static Row approvedManagerRow(long id, String company, String logoUrl,
                                           UUID submittedBy, String name) {
        Row row = mock(Row.class);
        when(row.getLong("id")).thenReturn(id);
        when(row.getString("company")).thenReturn(company);
        when(row.getString("company_logo_url")).thenReturn(logoUrl);
        when(row.getUUID("submitted_by")).thenReturn(submittedBy);
        when(row.getString("name")).thenReturn(name);
        return row;
    }

    private static Row rejectedManagerRow(long id, UUID submittedBy, String name, String company) {
        Row row = mock(Row.class);
        when(row.getLong("id")).thenReturn(id);
        when(row.getUUID("submitted_by")).thenReturn(submittedBy);
        when(row.getString("name")).thenReturn(name);
        when(row.getString("company")).thenReturn(company);
        return row;
    }

    private static Row pendingEditRow(UUID id, long managerId, String managerName,
                                       String currentCompany, String currentTitle,
                                       String requestedBy, String newCompany, String newTitle,
                                       String status, OffsetDateTime createdAt) {
        Row row = mock(Row.class);
        when(row.getUUID("id")).thenReturn(id);
        when(row.getLong("manager_id")).thenReturn(managerId);
        when(row.getString("manager_name")).thenReturn(managerName);
        when(row.getString("current_company")).thenReturn(currentCompany);
        when(row.getString("current_title")).thenReturn(currentTitle);
        when(row.getString("requested_by")).thenReturn(requestedBy);
        when(row.getString("new_company")).thenReturn(newCompany);
        when(row.getString("new_title")).thenReturn(newTitle);
        when(row.getString("status")).thenReturn(status);
        when(row.getOffsetDateTime("created_at")).thenReturn(createdAt);
        return row;
    }

    private static Row editRowForApprove(UUID id, long managerId, String status,
                                          String currentCompany, String currentTitle,
                                          String newCompany, String newTitle,
                                          String newStatus, String newLinkedinUrl,
                                          UUID proposedBy, String managerName,
                                          OffsetDateTime managerCreatedAt) {
        Row row = mock(Row.class);
        when(row.getUUID("id")).thenReturn(id);
        when(row.getLong("manager_id")).thenReturn(managerId);
        when(row.getString("status")).thenReturn(status);
        when(row.getString("current_company")).thenReturn(currentCompany);
        when(row.getString("current_title")).thenReturn(currentTitle);
        when(row.getLong("current_company_id")).thenReturn(null);
        when(row.getString("new_company")).thenReturn(newCompany);
        when(row.getString("new_title")).thenReturn(newTitle);
        when(row.getString("new_status")).thenReturn(newStatus);
        when(row.getString("new_linkedin_url")).thenReturn(newLinkedinUrl);
        when(row.getUUID("proposed_by")).thenReturn(proposedBy);
        when(row.getString("manager_name")).thenReturn(managerName);
        when(row.getOffsetDateTime("manager_created_at")).thenReturn(managerCreatedAt);
        return row;
    }

    private static Row companyRowWithId(long id) {
        Row row = mock(Row.class);
        when(row.getLong("id")).thenReturn(id);
        return row;
    }

    private static Row rejectEditFetchRow(UUID id, UUID proposedBy, String status, String managerName) {
        Row row = mock(Row.class);
        when(row.getUUID("id")).thenReturn(id);
        when(row.getUUID("proposed_by")).thenReturn(proposedBy);
        when(row.getString("status")).thenReturn(status);
        when(row.getString("manager_name")).thenReturn(managerName);
        return row;
    }

    private static Row userListRow(UUID id, String username, String firstName, String lastName, UUID banId) {
        Row row = mock(Row.class);
        when(row.getUUID("id")).thenReturn(id);
        when(row.getString("username")).thenReturn(username);
        when(row.getString("first_name")).thenReturn(firstName);
        when(row.getString("last_name")).thenReturn(lastName);
        when(row.getUUID("ban_id")).thenReturn(banId);
        return row;
    }

    private static Row bannedUserRow(UUID id, UUID userId, String username, String reason,
                                      String bannedBy, OffsetDateTime bannedAt) {
        Row row = mock(Row.class);
        when(row.getUUID("id")).thenReturn(id);
        when(row.getUUID("user_id")).thenReturn(userId);
        when(row.getString("username")).thenReturn(username);
        when(row.getString("reason")).thenReturn(reason);
        when(row.getString("banned_by")).thenReturn(bannedBy);
        when(row.getOffsetDateTime("banned_at")).thenReturn(bannedAt);
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
