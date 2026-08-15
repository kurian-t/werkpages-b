package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.ManagerRepository;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ManagerRepositoryCoverageIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool              pool;
    static ManagerRepository managerRepo;
    static CompanyRepository companyRepo;

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
        managerRepo = new ManagerRepository(pool);
        companyRepo = new CompanyRepository(pool);
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
    // search() with userId — lines 119-132 (userId branches)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void search_withUserId_noFilters_returnsApprovedGhostAndUserPending() throws Exception {
        UUID userId      = insertUser("auth0|search01", "SearchUser01");
        UUID otherUserId = insertUser("auth0|search01b", "SearchUser01b");
        insertApprovedManager("Alice A", "Corp", "Engineer");
        insertGhostManager("Ghost G", "Corp", "Director");
        insertPendingManagerSearchCreated("Pending P", "Corp", "Title", userId);
        insertPendingManagerSearchCreated("Other Pending", "Corp", "Title", otherUserId);

        RowSet<Row> rows = await(managerRepo.search(10, 0, null, null, null, userId));
        List<String> names = rowNames(rows);
        assertTrue(names.contains("Alice A"));
        assertTrue(names.contains("Ghost G"));
        assertTrue(names.contains("Pending P"));
        assertFalse(names.contains("Other Pending"), "pending by different user must not appear");
    }

    @Test
    void search_withUserId_searchPattern_includesUserOwnPending() throws Exception {
        UUID userId = insertUser("auth0|search02", "SearchUser02");
        insertApprovedManager("Jane Approved", "Corp", "E");
        insertPendingManagerSearchCreated("Jane Pending", "Corp", "E", userId);

        RowSet<Row> rows = await(managerRepo.search(10, 0, "%Jane%", null, null, userId));
        List<String> names = rowNames(rows);
        assertTrue(names.contains("Jane Approved"));
        assertTrue(names.contains("Jane Pending"));
    }

    @Test
    void search_withUserId_companyPattern_filtersCorrectly() throws Exception {
        UUID userId = insertUser("auth0|search03", "SearchUser03");
        insertApprovedManager("Manager One", "Acme", "E");
        insertApprovedManager("Manager Two", "Other", "E");
        insertPendingManagerSearchCreated("Pending Acme", "Acme", "E", userId);

        RowSet<Row> rows = await(managerRepo.search(10, 0, null, "%Acme%", null, userId));
        List<String> names = rowNames(rows);
        assertTrue(names.contains("Manager One"));
        assertTrue(names.contains("Pending Acme"));
        assertFalse(names.contains("Manager Two"));
    }

    @Test
    void search_withUserId_searchAndCompanyPattern_combinedFilter() throws Exception {
        UUID userId = insertUser("auth0|search04", "SearchUser04");
        insertApprovedManager("Bob Smith", "Acme", "E");
        insertApprovedManager("Bob Jones", "Other", "E");
        insertPendingManagerSearchCreated("Bob Pending", "Acme", "E", userId);

        RowSet<Row> rows = await(managerRepo.search(10, 0, "%Bob%", "%Acme%", null, userId));
        List<String> names = rowNames(rows);
        assertTrue(names.contains("Bob Smith"));
        assertTrue(names.contains("Bob Pending"));
        assertFalse(names.contains("Bob Jones"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // search() without userId — lines 134-146 (companyPattern + noFilter branches)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void search_noUserId_companyPattern_filtersToMatchingCompany() throws Exception {
        insertApprovedManager("Alice", "Acme", "E");
        insertApprovedManager("Bob",   "Other", "E");

        RowSet<Row> rows = await(managerRepo.search(10, 0, null, "%Acme%", null));
        List<String> names = rowNames(rows);
        assertTrue(names.contains("Alice"));
        assertFalse(names.contains("Bob"));
    }

    @Test
    void search_noUserId_noFilters_returnsAllApprovedAndGhost() throws Exception {
        insertApprovedManager("Carol", "Corp", "E");
        insertGhostManager("Dave", "Corp", "E");

        RowSet<Row> rows = await(managerRepo.search(10, 0, null, null, null));
        List<String> names = rowNames(rows);
        assertTrue(names.contains("Carol"));
        assertTrue(names.contains("Dave"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // buildOrderBy — lines 154-156 ("rating" and "reviews" sort)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void search_sortByRating_ordersHighestFirst() throws Exception {
        insertApprovedManagerWithRating("Low Rated",  "Corp", "E", 2.0, 1);
        insertApprovedManagerWithRating("High Rated", "Corp", "E", 4.5, 1);

        RowSet<Row> rows = await(managerRepo.search(10, 0, null, null, "rating"));
        List<String> names = rowNames(rows);
        assertEquals("High Rated", names.get(0));
    }

    @Test
    void search_sortByReviews_ordersHighestFirst() throws Exception {
        insertApprovedManagerWithRating("Few Reviews",  "Corp", "E", 3.0, 1);
        insertApprovedManagerWithRating("Many Reviews", "Corp", "E", 3.0, 10);

        RowSet<Row> rows = await(managerRepo.search(10, 0, null, null, "reviews"));
        List<String> names = rowNames(rows);
        assertEquals("Many Reviews", names.get(0));
    }

    @Test
    void search_sortByName_ordersAlphabetically() throws Exception {
        insertApprovedManager("Zara", "Corp", "E");
        insertApprovedManager("Aaron", "Corp", "E");

        RowSet<Row> rows = await(managerRepo.search(10, 0, null, null, "name"));
        List<String> names = rowNames(rows);
        assertEquals("Aaron", names.get(0));
        assertEquals("Zara",  names.get(1));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // count() — lines 175-181 (searchAndCompany + companyOnly branches)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void count_searchAndCompany_countsMatchingManagers() throws Exception {
        insertApprovedManager("Alice", "Acme", "E");
        insertApprovedManager("Bob",   "Acme", "E");
        insertApprovedManager("Alice", "Other", "E");

        long count = await(managerRepo.count("%Alice%", "%Acme%"));
        assertEquals(1L, count);
    }

    @Test
    void count_companyOnly_countsManagersInCompany() throws Exception {
        insertApprovedManager("One", "Acme", "E");
        insertApprovedManager("Two", "Acme", "E");
        insertApprovedManager("Three", "Other", "E");

        long count = await(managerRepo.count(null, "%Acme%"));
        assertEquals(2L, count);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findAllCompanies — line 199-201
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void findAllCompanies_returnsDistinctCompaniesForApprovedAndGhost() throws Exception {
        insertApprovedManager("A", "Alpha", "E");
        insertApprovedManager("B", "Alpha", "E");
        insertGhostManager("C",   "Beta",  "E");
        insertPendingManager("D", "Gamma", "E", null);

        RowSet<Row> rows = await(managerRepo.findAllCompanies());
        List<String> companies = new ArrayList<>();
        for (Row row : rows) companies.add(row.getString("company"));
        assertTrue(companies.contains("Alpha"));
        assertTrue(companies.contains("Beta"));
        assertFalse(companies.contains("Gamma"), "pending company must not appear");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findPendingByUser — lines 238-248
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void findPendingByUser_returnsOnlyPendingAndRejectedForUser() throws Exception {
        UUID userId = insertUser("auth0|fpbu01", "FpbuUser01");
        insertPendingManager("Pending One",   "Corp", "E", userId);
        insertRejectedManager("Rejected One", "Corp", "E", userId);
        insertApprovedManager("Approved One", "Corp", "E");

        RowSet<Row> rows = await(managerRepo.findPendingByUser(userId));
        List<String> names = rowNames(rows);
        assertTrue(names.contains("Pending One"));
        assertTrue(names.contains("Rejected One"));
        assertFalse(names.contains("Approved One"));
    }

    @Test
    void findPendingByUser_excludesSearchCreatedManagers() throws Exception {
        UUID userId = insertUser("auth0|fpbu02", "FpbuUser02");
        insertPendingManager("Regular Pending", "Corp", "E", userId);
        insertPendingManagerSearchCreated("Search Created", "Corp", "E", userId);

        RowSet<Row> rows = await(managerRepo.findPendingByUser(userId));
        List<String> names = rowNames(rows);
        assertTrue(names.contains("Regular Pending"));
        assertFalse(names.contains("Search Created"), "search-created managers must be excluded");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // update() — lines 304-327 (partial fields, empty result)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void update_updatesCompanyAndTitle() throws Exception {
        long managerId = insertApprovedManager("Manager X", "OldCorp", "OldTitle");

        Optional<Row> result = await(managerRepo.update(managerId, "NewCorp", "NewTitle",
                null, null, null, null, null, null, null));

        assertTrue(result.isPresent());
        assertEquals("NewCorp", result.get().getString("company"));
        assertEquals("NewTitle", result.get().getString("title"));
    }

    @Test
    void update_nonExistentManager_returnsEmpty() throws Exception {
        Optional<Row> result = await(managerRepo.update(999999L, "Corp", null,
                null, null, null, null, null, null, null));
        assertTrue(result.isEmpty());
    }

    @Test
    void update_updatesMultipleFields() throws Exception {
        long managerId = insertApprovedManager("Manager Y", "Corp", "Title");

        Optional<Row> result = await(managerRepo.update(managerId, "NewCorp", "NewTitle",
                null, null, "active", "CA", "https://linkedin.com/in/y", "https://logo.com/y.png", null));

        assertTrue(result.isPresent());
        assertEquals("NewCorp", result.get().getString("company"));
        assertEquals("CA", result.get().getString("country"));
        assertEquals("https://linkedin.com/in/y", result.get().getString("linkedin_url"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // approve() — lines 329-338 (not pending → empty)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void approve_ghostManager_returnsEmpty() throws Exception {
        long managerId = insertGhostManager("Ghost One", "Corp", "E");
        Optional<Row> result = await(managerRepo.approve(managerId));
        assertTrue(result.isEmpty(), "approve() must not approve ghost managers");
    }

    @Test
    void approve_pendingManager_setsApprovedStatus() throws Exception {
        long managerId = insertPendingManager("Pending One", "Corp", "E", null);
        Optional<Row> result = await(managerRepo.approve(managerId));
        assertTrue(result.isPresent());
        String status = await(pool
            .preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("approval_status")));
        assertEquals("approved", status);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // reject() — lines 347-356 (not pending → empty)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void reject_approvedManager_returnsEmpty() throws Exception {
        long managerId = insertApprovedManager("Approved", "Corp", "E");
        Optional<Row> result = await(managerRepo.reject(managerId));
        assertTrue(result.isEmpty(), "reject() must not reject already-approved managers");
    }

    @Test
    void reject_pendingManager_setsRejectedStatus() throws Exception {
        long managerId = insertPendingManager("Pending Reject", "Corp", "E", null);
        Optional<Row> result = await(managerRepo.reject(managerId));
        assertTrue(result.isPresent());
        String status = await(pool
            .preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("approval_status")));
        assertEquals("rejected", status);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // insertCareerEntry / closeOpenCareerEntry / updateOpenCareerEntry — lines 373-407
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void insertCareerEntry_persistsEntry() throws Exception {
        long managerId = insertApprovedManager("Career Manager", "Corp", "E");
        OffsetDateTime start = OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime end   = OffsetDateTime.of(2022, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        await(managerRepo.insertCareerEntry(managerId, "PastCorp", "Junior Dev", start, end, null));

        RowSet<Row> rows = await(managerRepo.getCareerHistory(managerId));
        List<String> companies = new ArrayList<>();
        for (Row row : rows) companies.add(row.getString("company"));
        assertTrue(companies.contains("PastCorp"));
    }

    @Test
    void closeOpenCareerEntry_closesEntryWithNullEndDate() throws Exception {
        long managerId = insertApprovedManager("Close Entry Manager", "Corp", "E");
        OffsetDateTime start = OffsetDateTime.of(2018, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        await(managerRepo.insertCareerEntry(managerId, "OpenCorp", "Dev", start, null, null));

        OffsetDateTime closeDate = OffsetDateTime.of(2022, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        int closed = await(managerRepo.closeOpenCareerEntry(managerId, closeDate));
        assertEquals(1, closed);

        OffsetDateTime endDate = await(pool
            .preparedQuery("SELECT end_date FROM career_history WHERE manager_id = $1 AND company = 'OpenCorp'")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getOffsetDateTime("end_date")));
        assertNotNull(endDate);
    }

    @Test
    void closeOpenCareerEntry_doesNotCloseWhenStartAfterEndDate() throws Exception {
        long managerId = insertApprovedManager("No Close Manager", "Corp", "E");
        OffsetDateTime futureStart = OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        await(managerRepo.insertCareerEntry(managerId, "FutureCorp", "Dev", futureStart, null, null));

        OffsetDateTime oldDate = OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        int closed = await(managerRepo.closeOpenCareerEntry(managerId, oldDate));
        assertEquals(0, closed, "Must not close an entry whose start_date > endDate");
    }

    @Test
    void updateOpenCareerEntry_updatesCompanyAndTitle() throws Exception {
        long managerId = insertApprovedManager("Update Career Manager", "Corp", "E");
        OffsetDateTime start = OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        await(managerRepo.insertCareerEntry(managerId, "OldCorp", "OldTitle", start, null, null));

        await(managerRepo.updateOpenCareerEntry(managerId, "UpdatedCorp", "UpdatedTitle"));

        Row entry = await(pool
            .preparedQuery("SELECT company, title FROM career_history WHERE manager_id = $1 AND end_date IS NULL")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));
        assertEquals("UpdatedCorp", entry.getString("company"));
        assertEquals("UpdatedTitle", entry.getString("title"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // createGhost — lines 637-651
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void createGhost_createsManagerWithGhostStatus() throws Exception {
        Row company = await(companyRepo.findOrCreate("GhostCreateCorp", null, null));
        long companyId = company.getLong("id");

        Row manager = await(managerRepo.createGhost(
            "Ghost Created", "GhostCreateCorp", "Director",
            "US", "NY", "New York", null, companyId));

        assertEquals("ghost", manager.getString("approval_status"));
        assertEquals("Ghost Created", manager.getString("name"));
        assertNotNull(manager.getString("slug"));
    }

    @Test
    void createGhost_withNullState_doesNotThrow() throws Exception {
        Row company = await(companyRepo.findOrCreate("GhostNullStateCorp", null, null));
        long companyId = company.getLong("id");

        Row manager = await(managerRepo.createGhost(
            "Ghost No State", "GhostNullStateCorp", "Engineer",
            "CA", null, null, null, companyId));

        assertNotNull(manager);
        assertEquals("ghost", manager.getString("approval_status"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // createPending — lines 654-670
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void createPending_createsManagerWithPendingApprovalStatus() throws Exception {
        Row company = await(companyRepo.findOrCreate("PendingCreateCorp", null, null));
        long companyId = company.getLong("id");

        Row manager = await(managerRepo.createPending(
            "Pending Created", "PendingCreateCorp", "Engineer",
            "active", "US", "CA", null, companyId));

        assertEquals("pending_approval", manager.getString("approval_status"));
        assertEquals("Pending Created", manager.getString("name"));
        assertNotNull(manager.getString("slug"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // createSearchPending — lines 597-616
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void createSearchPending_createsManagerLinkedToSearchUser() throws Exception {
        UUID userId = insertUser("auth0|csp01", "CspUser01");
        Row company = await(companyRepo.findOrCreate("SearchPendingCorp", null, null));
        long companyId = company.getLong("id");

        Row manager = await(managerRepo.createSearchPending(
            "Search Pending", "SearchPendingCorp", "Manager",
            "US", "TX", "Houston", null, companyId, userId));

        assertEquals("pending_approval", manager.getString("approval_status"));
        assertEquals(userId, manager.getUUID("search_created_by_user_id"));
        assertEquals(userId, manager.getUUID("submitted_by"));
        assertNotNull(manager.getString("slug"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findByNameAndCompany — lines 585-594
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void findByNameAndCompany_returnsApprovedAndGhostManagers() throws Exception {
        insertApprovedManager("Jane Doe", "TechCorp", "Engineer");
        insertGhostManager("Jane Doe", "TechCorp", "Director");
        insertPendingManager("Jane Doe", "TechCorp", "VP", null);

        RowSet<Row> rows = await(managerRepo.findByNameAndCompany("Jane Doe", "TechCorp"));
        List<String> statuses = new ArrayList<>();
        for (Row row : rows) statuses.add(row.getString("approval_status"));
        assertTrue(statuses.contains("approved"));
        assertTrue(statuses.contains("ghost"));
        assertFalse(statuses.contains("pending_approval"));
    }

    @Test
    void findByNameAndCompany_returnsEmpty_whenNoMatch() throws Exception {
        insertApprovedManager("Alice Smith", "Corp", "E");

        RowSet<Row> rows = await(managerRepo.findByNameAndCompany("Nobody", "NoCorp"));
        assertFalse(rows.iterator().hasNext());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findSimilar — lines 224-235
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void findSimilar_returnsApprovedAndGhostManagers() throws Exception {
        insertApprovedManager("Alice Smith", "Acme", "E");
        insertGhostManager("Alice Smithson", "Other", "E");
        insertPendingManager("Alice Smithers", "Corp", "E", null);

        RowSet<Row> rows = await(managerRepo.findSimilar("%Alice%", "%Acme%"));
        List<String> names = rowNames(rows);
        assertTrue(names.contains("Alice Smith"));
        assertTrue(names.contains("Alice Smithson"));
        assertFalse(names.contains("Alice Smithers"));
    }

    @Test
    void findSimilar_ranksCompanyMatchFirst() throws Exception {
        insertApprovedManager("Bob Jones", "Acme", "E");
        insertApprovedManager("Bob Jones", "Other", "E");

        RowSet<Row> rows = await(managerRepo.findSimilar("%Bob Jones%", "%Acme%"));
        List<String> names = rowNames(rows);
        assertEquals(2, names.size());
        // The Acme manager must come first (company match rank)
        Row first = rows.iterator().next();
        assertEquals("Acme", first.getString("company"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // countApproved — line 193-197
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void countApproved_countsApprovedAndGhostManagers() throws Exception {
        insertApprovedManager("Approved One", "Corp", "E");
        insertGhostManager("Ghost One", "Corp", "E");
        insertPendingManager("Pending One", "Corp", "E", null);

        long count = await(managerRepo.countApproved());
        assertEquals(2L, count);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findGhostForAdmin / approveGhost — lines 250-271
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void findGhostForAdmin_returnsOnlyGhostManagers() throws Exception {
        insertApprovedManager("Approved", "Corp", "E");
        insertGhostManager("Ghost One", "Corp", "E");
        insertGhostManager("Ghost Two", "Corp", "E");
        insertPendingManager("Pending", "Corp", "E", null);

        RowSet<Row> rows = await(managerRepo.findGhostForAdmin(10, 0));
        List<String> names = rowNames(rows);
        assertTrue(names.contains("Ghost One"));
        assertTrue(names.contains("Ghost Two"));
        assertFalse(names.contains("Approved"));
        assertFalse(names.contains("Pending"));
    }

    @Test
    void approveGhost_ghostManager_returnsRow() throws Exception {
        long managerId = insertGhostManager("Ghost Approve", "Corp", "E");
        Optional<Row> result = await(managerRepo.approveGhost(managerId));
        assertTrue(result.isPresent());
        assertEquals(managerId, (long) result.get().getLong("id"));
    }

    @Test
    void approveGhost_approvedManager_returnsEmpty() throws Exception {
        long managerId = insertApprovedManager("Already Approved", "Corp", "E");
        Optional<Row> result = await(managerRepo.approveGhost(managerId));
        assertTrue(result.isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findById — lines 98-105
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void findById_existingManager_returnsRow() throws Exception {
        long managerId = insertApprovedManager("Find By Id Manager", "Corp", "E");
        Optional<Row> result = await(managerRepo.findById(managerId));
        assertTrue(result.isPresent());
        assertEquals("Find By Id Manager", result.get().getString("name"));
    }

    @Test
    void findById_nonExistentManager_returnsEmpty() throws Exception {
        Optional<Row> result = await(managerRepo.findById(999999L));
        assertTrue(result.isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // countExistingById — lines 365-369
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void countExistingById_returnsCorrectCountForExistingManagers() throws Exception {
        long m1 = insertApprovedManager("Count One", "Corp", "E");
        long m2 = insertApprovedManager("Count Two", "Corp", "E");

        int count = await(managerRepo.countExistingById(new Long[]{m1, m2, 999999L}));
        assertEquals(2, count);
    }

    @Test
    void countExistingById_allMissing_returnsZero() throws Exception {
        int count = await(managerRepo.countExistingById(new Long[]{888888L, 999999L}));
        assertEquals(0, count);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // updateLogoUrl — lines 341-344
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void updateLogoUrl_existingManager_updatesAndReturnsTrue() throws Exception {
        long managerId = insertApprovedManager("Logo Update Manager", "Corp", "E");
        boolean updated = await(managerRepo.updateLogoUrl(managerId, "https://cdn.example.com/logo.png"));
        assertTrue(updated);

        String logo = await(pool
            .preparedQuery("SELECT company_logo_url FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("company_logo_url")));
        assertEquals("https://cdn.example.com/logo.png", logo);
    }

    @Test
    void updateLogoUrl_nonExistentManager_returnsFalse() throws Exception {
        boolean updated = await(managerRepo.updateLogoUrl(999999L, "https://example.com/logo.png"));
        assertFalse(updated);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // promoteGhostToPending — lines 744-753
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void promoteGhostToPending_ghostManager_setsPendingApproval() throws Exception {
        long managerId = insertGhostManager("Promotable Ghost", "Corp", "E");
        Optional<Row> result = await(managerRepo.promoteGhostToPending(managerId));
        assertTrue(result.isPresent());

        String status = await(pool
            .preparedQuery("SELECT approval_status FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("approval_status")));
        assertEquals("pending_approval", status);
    }

    @Test
    void promoteGhostToPending_nonGhostManager_returnsEmpty() throws Exception {
        long managerId = insertApprovedManager("Not A Ghost", "Corp", "E");
        Optional<Row> result = await(managerRepo.promoteGhostToPending(managerId));
        assertTrue(result.isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findByIdFlat / hasCareerHistory — lines 709-721
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void findByIdFlat_existingManager_returnsRow() throws Exception {
        long managerId = insertApprovedManager("Flat Find", "Corp", "E");
        Optional<Row> result = await(managerRepo.findByIdFlat(managerId));
        assertTrue(result.isPresent());
        assertEquals("Flat Find", result.get().getString("name"));
    }

    @Test
    void findByIdFlat_nonExistentManager_returnsEmpty() throws Exception {
        Optional<Row> result = await(managerRepo.findByIdFlat(999999L));
        assertTrue(result.isEmpty());
    }

    @Test
    void hasCareerHistory_withHistory_returnsTrue() throws Exception {
        long managerId = insertApprovedManager("Has History", "Corp", "E");
        OffsetDateTime start = OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        await(managerRepo.insertCareerEntry(managerId, "PastCorp", "Dev", start, null, null));

        boolean has = await(managerRepo.hasCareerHistory(managerId));
        assertTrue(has);
    }

    @Test
    void hasCareerHistory_withoutHistory_returnsFalse() throws Exception {
        long managerId = insertApprovedManager("No History", "Corp", "E");
        boolean has = await(managerRepo.hasCareerHistory(managerId));
        assertFalse(has);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // existsManagerWithCompanyName — lines 849-871
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void existsManagerWithCompanyName_withApprovedManager_returnsTrue() throws Exception {
        insertApprovedManager("Exists Manager", "ExistsCompany", "E");
        boolean exists = await(managerRepo.existsManagerWithCompanyName("ExistsCompany"));
        assertTrue(exists);
    }

    @Test
    void existsManagerWithCompanyName_withGhostManager_returnsTrue() throws Exception {
        insertGhostManager("Ghost Exists", "GhostExistsCo", "E");
        boolean exists = await(managerRepo.existsManagerWithCompanyName("GhostExistsCo"));
        assertTrue(exists);
    }

    @Test
    void existsManagerWithCompanyName_noManager_returnsFalse() throws Exception {
        boolean exists = await(managerRepo.existsManagerWithCompanyName("NonExistentCompanyXyz"));
        assertFalse(exists);
    }

    @Test
    void existsManagerWithCompanyName_caseInsensitiveMatch() throws Exception {
        insertApprovedManager("Case Manager", "MixedCase Corp", "E");
        boolean exists = await(managerRepo.existsManagerWithCompanyName("mixedcase corp"));
        assertTrue(exists);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findSlugs — lines 806-816
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void findSlugs_withLinkedCompany_returnsBothSlugs() throws Exception {
        Row company = await(companyRepo.findOrCreate("SlugsCorp", null, null));
        long companyId = company.getLong("id");
        Row manager = await(managerRepo.createAutoApproved(
            "Slugs Manager", "SlugsCorp", "Engineer",
            "US", "CA", null, null, null, companyId));

        Optional<Row> result = await(managerRepo.findSlugs(manager.getLong("id")));
        assertTrue(result.isPresent());
        assertNotNull(result.get().getString("slug"));
        assertNotNull(result.get().getString("company_slug"));
    }

    @Test
    void findSlugs_withoutLinkedCompany_returnsManagerSlugWithNullCompanySlug() throws Exception {
        long managerId = await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,status,approval_status,overall_rating,reviews_count,category_averages,slug) " +
            "VALUES ('No Co Manager','NoCo','E','active','ghost',0,0,'{}','no-co-manager') RETURNING id")
            .execute()
            .map(rs -> rs.iterator().next().getLong("id")));

        Optional<Row> result = await(managerRepo.findSlugs(managerId));
        assertTrue(result.isPresent());
        assertEquals("no-co-manager", result.get().getString("slug"));
        assertNull(result.get().getString("company_slug"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findByCompanyExact — lines 676-686
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void findByCompanyExact_caseInsensitiveMatch() throws Exception {
        insertApprovedManager("ExactA", "Exact Corp", "E");
        insertGhostManager("ExactB",   "Exact Corp", "E");
        insertRejectedManager("ExactC", "Exact Corp", "E", null);

        RowSet<Row> rows = await(managerRepo.findByCompanyExact("exact corp"));
        List<String> names = rowNames(rows);
        assertTrue(names.contains("ExactA"));
        assertTrue(names.contains("ExactB"));
        assertFalse(names.contains("ExactC"), "rejected manager must not appear");
    }

    @Test
    void findByCompanyExact_noMatch_returnsEmpty() throws Exception {
        insertApprovedManager("Irrelevant", "SomeCorp", "E");
        RowSet<Row> rows = await(managerRepo.findByCompanyExact("NoSuchCompanyXyz"));
        assertFalse(rows.iterator().hasNext());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // updateForAttach — lines 689-705
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void updateForAttach_updatesManagerFields() throws Exception {
        long managerId = insertGhostManager("Ghost For Attach", "Corp", "OldTitle");
        UUID submitter = insertUser("auth0|attach01", "AttachUser01");

        Optional<Row> result = await(managerRepo.updateForAttach(managerId,
            "Updated Name", "New Title", "retired", "CA",
            "https://linkedin.com/in/attach", "https://logo.com/attach.png", submitter));

        assertTrue(result.isPresent());
        assertEquals("Updated Name", result.get().getString("name"));
        assertEquals("New Title", result.get().getString("title"));
        assertEquals("CA", result.get().getString("country"));
    }

    @Test
    void updateForAttach_nonExistentManager_returnsEmpty() throws Exception {
        UUID submitter = insertUser("auth0|attach02", "AttachUser02");
        Optional<Row> result = await(managerRepo.updateForAttach(999999L,
            "Name", "Title", "active", "US", null, null, submitter));
        assertTrue(result.isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // countSubmittedTodayByUser — line 472-475
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void countSubmittedTodayByUser_countsManagersSubmittedToday() throws Exception {
        UUID userId = insertUser("auth0|today01", "TodayUser01");
        insertPendingManager("Today One", "Corp", "E", userId);
        insertPendingManager("Today Two", "Corp", "E", userId);

        long count = await(managerRepo.countSubmittedTodayByUser(userId));
        assertEquals(2L, count);
    }

    @Test
    void countSubmittedTodayByUser_noSubmissions_returnsZero() throws Exception {
        UUID userId = insertUser("auth0|today02", "TodayUser02");
        long count = await(managerRepo.countSubmittedTodayByUser(userId));
        assertEquals(0L, count);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private UUID insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id, email, username, first_name, last_name, role) " +
            "VALUES ($1,$2,$3,$4,$5,$6)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User", "user")));
        return await(pool
            .preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private long insertApprovedManager(String name, String company, String title) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,status,approval_status,overall_rating,reviews_count,category_averages) " +
            "VALUES ($1,$2,$3,'active','approved',0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertApprovedManagerWithRating(String name, String company, String title,
                                                  double rating, int reviewsCount) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,status,approval_status,overall_rating,reviews_count,category_averages) " +
            "VALUES ($1,$2,$3,'active','approved',$4,$5,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title, rating, reviewsCount))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertGhostManager(String name, String company, String title) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,status,approval_status,overall_rating,reviews_count,category_averages) " +
            "VALUES ($1,$2,$3,'active','ghost',0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertPendingManager(String name, String company, String title, UUID submittedBy) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,status,approval_status,overall_rating,reviews_count,category_averages,submitted_by) " +
            "VALUES ($1,$2,$3,'active','pending_approval',0,0,'{}',$4) RETURNING id")
            .execute(Tuple.of(name, company, title, submittedBy))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertRejectedManager(String name, String company, String title, UUID submittedBy) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,status,approval_status,overall_rating,reviews_count,category_averages,submitted_by) " +
            "VALUES ($1,$2,$3,'active','rejected',0,0,'{}',$4) RETURNING id")
            .execute(Tuple.of(name, company, title, submittedBy))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertPendingManagerSearchCreated(String name, String company, String title,
                                                    UUID searchCreatedByUserId) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,status,approval_status,overall_rating,reviews_count,category_averages," +
            "submitted_by,search_created_by_user_id) " +
            "VALUES ($1,$2,$3,'active','pending_approval',0,0,'{}',$4,$4) RETURNING id")
            .execute(Tuple.of(name, company, title, searchCreatedByUserId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private List<String> rowNames(RowSet<Row> rows) {
        List<String> names = new ArrayList<>();
        for (Row row : rows) names.add(row.getString("name"));
        return names;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
