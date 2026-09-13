package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.werkpages.repository.*;
import org.werkpages.service.AdminService;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The corporate-structure controls, as an admin actually reaches them.
 *
 * <p>The repository behind these is well covered; the service layer on top of it was not covered at
 * all. That layer is not a pass-through - it holds the authorisation check, the whitelist of
 * relationship types, the self-reference guard, and the translation of a database trigger's
 * constraint violation into something an admin can act on. Every one of those is a decision, and
 * none of them were exercised.
 */
class AdminCompanyRelationshipsIntegrationTest {

    static PostgreSQLContainer<?> pg;
    static Pool pool;
    static AdminService admin;

    @BeforeAll
    static void setUp() {
        pg = new PostgreSQLContainer<>("postgres:16-alpine");
        pg.start();
        Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
              .locations("classpath:db/migrations").load().migrate();
        PgConnectOptions opts = new PgConnectOptions()
            .setHost(pg.getHost()).setPort(pg.getFirstMappedPort())
            .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword());
        pool = PgBuilder.pool().connectingTo(opts).with(new PoolOptions().setMaxSize(4))
                        .using(Vertx.vertx()).build();
        admin = new AdminService(
            new UserRepository(pool), new ManagerRepository(pool), new ReviewRepository(pool), null,
            new NotificationRepository(pool), new CompanyRepository(pool),
            new MergeSuggestionsRepository(pool), pool);
    }

    @AfterAll
    static void tearDown() { if (pg != null) pg.stop(); }

    @BeforeEach
    void clean() throws Exception {
        await(pool.query("TRUNCATE company_relationships, managers, companies, users CASCADE")
            .execute().mapEmpty());
    }

    // ── Who may do any of this ───────────────────────────────────────────────

    @Test
    void everyCorporateStructureControlIsAdminOnly() throws Exception {
        /*
          These rewire which companies belong to which. Left open, anyone could attach a company
          they dislike to one they want it associated with, and the directory would say so.
        */
        long child  = insertCompany("Child Co");
        long parent = insertCompany("Parent Co");
        String plain = insertUser("auth0|rel-plain", "user");

        assertThrows(Exception.class,
            () -> await(admin.setCompanyParent(plain, child, parent, "SUBSIDIARY_OF")));
        assertThrows(Exception.class, () -> await(admin.removeCompanyParent(plain, child)));
        assertThrows(Exception.class, () -> await(admin.previewCompanyMerge(plain, parent, child)));
        assertThrows(Exception.class,
            () -> await(admin.undoCompanyMerge(plain, UUID.randomUUID())));

        // And a caller with no token at all, which is a different branch to a non-admin.
        assertThrows(Exception.class,
            () -> await(admin.setCompanyParent(null, child, parent, "SUBSIDIARY_OF")));
    }

    // ── Recording that one company is part of another ────────────────────────

    @Test
    void anAdminCanRecordAndRemoveAParent() throws Exception {
        // Not a merge: both keep their pages, managers and ratings, and the child stays searchable.
        long child  = insertCompany("Subsidiary Co");
        long parent = insertCompany("Holding Co");
        String a = insertUser("auth0|rel-admin-1", "admin");

        assertTrue(await(admin.setCompanyParent(a, child, parent, "SUBSIDIARY_OF")).getBoolean("success"));
        assertEquals(parent, parentOf(child));

        JsonObject removed = await(admin.removeCompanyParent(a, child));
        assertTrue(removed.getBoolean("success"));
        assertNull(parentOf(child), "the child is independent again");
    }

    @Test
    void anUnknownRelationshipTypeIsRefused() throws Exception {
        // A whitelist, because the type is rendered on the public page as a claim about ownership.
        long child  = insertCompany("Typed Child");
        long parent = insertCompany("Typed Parent");
        String a = insertUser("auth0|rel-admin-2", "admin");

        assertThrows(Exception.class,
            () -> await(admin.setCompanyParent(a, child, parent, "RIVAL_OF")));
        assertNull(parentOf(child));
    }

    @Test
    void aBlankTypeMeansSubsidiary() throws Exception {
        // The common case, so it is the default rather than a required field.
        long child  = insertCompany("Default Child");
        long parent = insertCompany("Default Parent");
        String a = insertUser("auth0|rel-admin-3", "admin");

        await(admin.setCompanyParent(a, child, parent, null));
        assertEquals("SUBSIDIARY_OF", typeOf(child));

        await(admin.removeCompanyParent(a, child));
        await(admin.setCompanyParent(a, child, parent, "   "));
        assertEquals("SUBSIDIARY_OF", typeOf(child));
    }

    @Test
    void theTypeIsAcceptedInAnyCase() throws Exception {
        long child  = insertCompany("Case Child");
        long parent = insertCompany("Case Parent");
        String a = insertUser("auth0|rel-admin-4", "admin");

        await(admin.setCompanyParent(a, child, parent, " brand_of "));
        assertEquals("BRAND_OF", typeOf(child));
    }

    @Test
    void aCompanyCannotBePartOfItself() throws Exception {
        long c = insertCompany("Ouroboros Inc");
        String a = insertUser("auth0|rel-admin-5", "admin");

        assertThrows(Exception.class, () -> await(admin.setCompanyParent(a, c, c, "SUBSIDIARY_OF")));
    }

    @Test
    void aLoopInTheOwnershipChainIsRefusedInWordsAnAdminCanActOn() throws Exception {
        /*
          The loop check lives in a database trigger so it fires for any writer. The service
          translates it, because an admin who sees a raw constraint violation learns nothing about
          what they did or how to undo it.
        */
        long a1 = insertCompany("Alpha Co");
        long b1 = insertCompany("Beta Co");
        String a = insertUser("auth0|rel-admin-6", "admin");

        await(admin.setCompanyParent(a, b1, a1, "SUBSIDIARY_OF"));   // Beta is part of Alpha

        Exception e = assertThrows(Exception.class,
            () -> await(admin.setCompanyParent(a, a1, b1, "SUBSIDIARY_OF")));
        assertTrue(String.valueOf(e.getMessage()).toLowerCase().contains("loop"),
            "the admin is told it would create a loop, not shown a constraint name");
    }

    @Test
    void removingAParentThatWasNeverThereIsNotAnError() throws Exception {
        // Idempotent on purpose: an admin clicking twice has not done anything wrong.
        long c = insertCompany("Unparented Co");
        String a = insertUser("auth0|rel-admin-7", "admin");

        JsonObject result = await(admin.removeCompanyParent(a, c));
        assertTrue(result.getBoolean("success"));
        assertFalse(result.getBoolean("removed"), "and it says plainly that nothing was removed");
    }

    // ── Previewing a merge ───────────────────────────────────────────────────

    @Test
    void aPreviewSaysWhatWouldMoveAndWritesNothing() throws Exception {
        // The point of a preview: an admin decides before anything is irreversible.
        long keep  = insertCompany("Survivor Co");
        long merge = insertCompany("Absorbed Co");
        insertManager("Pat Mergeable", "Absorbed Co", merge);
        String a = insertUser("auth0|rel-admin-8", "admin");

        JsonObject preview = await(admin.previewCompanyMerge(a, keep, merge));

        assertNotNull(preview);
        assertEquals(1L, managerCountFor(merge), "the preview moved nothing");
    }

    @Test
    void previewingAMergeOfACompanyWithItselfIsRefused() throws Exception {
        long c = insertCompany("Self Merge Co");
        String a = insertUser("auth0|rel-admin-9", "admin");

        assertThrows(Exception.class, () -> await(admin.previewCompanyMerge(a, c, c)));
    }

    // ── Undoing one ──────────────────────────────────────────────────────────

    @Test
    void undoingAMergeThatNeverHappenedIsRefusedRatherThanSilentlyDoingNothing() throws Exception {
        // A no-op reported as success would tell an admin their data came back when it did not.
        String a = insertUser("auth0|rel-admin-10", "admin");

        assertThrows(Exception.class, () -> await(admin.undoCompanyMerge(a, UUID.randomUUID())));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private long insertCompany(String name) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies(name,status,slug) VALUES ($1,'approved',$2) RETURNING id")
            .execute(Tuple.of(name, name.toLowerCase().replaceAll("[^a-z0-9]+", "-")))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertManager(String name, String company, long companyId) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO managers(name,company,company_id,title,status,approval_status," +
                "overall_rating,reviews_count,category_averages) " +
                "VALUES ($1,$2,$3,'VP','active','approved',4.0,2,'{}') RETURNING id")
            .execute(Tuple.of(name, company, companyId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private String insertUser(String auth0Id, String role) throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO users(auth0_id,username,email,role) VALUES ($1,$1,$1||'@test.com',$2)")
            .execute(Tuple.of(auth0Id, role)).mapEmpty());
        return auth0Id;
    }

    private Long parentOf(long childId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT parent_company_id FROM company_relationships WHERE child_company_id = $1")
            .execute(Tuple.of(childId))
            .map(rs -> rs.iterator().hasNext() ? rs.iterator().next().getLong("parent_company_id") : null));
    }

    private String typeOf(long childId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT relationship_type FROM company_relationships WHERE child_company_id = $1")
            .execute(Tuple.of(childId))
            .map(rs -> rs.iterator().hasNext() ? rs.iterator().next().getString("relationship_type") : null));
    }

    private long managerCountFor(long companyId) throws Exception {
        return await(pool.preparedQuery("SELECT COUNT(*) AS n FROM managers WHERE company_id = $1")
            .execute(Tuple.of(companyId)).map(rs -> rs.iterator().next().getLong("n")));
    }

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
