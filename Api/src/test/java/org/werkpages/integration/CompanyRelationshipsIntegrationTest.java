package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.werkpages.repository.CompanyRepository;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Corporate structure: Zehrs is part of Loblaw.
 *
 * The distinction these tests protect is the one the whole feature rests on. A duplicate means
 * there was only ever one company, and the answer is a merge. A relationship means there are two
 * real companies and one owns the other, and the answer is a link between them. A Zehrs store
 * manager is not a Loblaw corporate manager, so a relationship must never quietly behave like a
 * merge: the child keeps its page, its managers, its ratings and its place in search.
 */
class CompanyRelationshipsIntegrationTest {

    static PostgreSQLContainer<?> pg;
    static Pool pool;
    static CompanyRepository companyRepo;

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
        companyRepo = new CompanyRepository(pool);
    }

    @AfterAll
    static void tearDown() { if (pg != null) pg.stop(); }

    @Test
    void aChildKnowsItsParentAndAParentKnowsItsChildren() throws Exception {
        long loblaw = insertCompany("Loblaw Companies");
        long zehrs  = insertCompany("Zehrs Markets");
        long frills = insertCompany("No Frills");

        await(companyRepo.setCompanyParent(zehrs,  loblaw, "BRAND_OF"));
        await(companyRepo.setCompanyParent(frills, loblaw, "BRAND_OF"));

        var parent = await(companyRepo.findCompanyParent(zehrs));
        assertTrue(parent.isPresent());
        assertEquals("Loblaw Companies", parent.get().getString("name"));
        assertEquals("BRAND_OF", parent.get().getString("relationship_type"));

        var children = await(companyRepo.findCompanyChildren(loblaw));
        assertEquals(2, children.size());
    }

    @Test
    void aRelationshipLeavesTheChildCompletelyIntact() throws Exception {
        // The point of the whole distinction. Being part of a group changes nothing about the
        // child: it keeps its own row, its own managers and its own search presence. If any of
        // this stopped being true, the relationship would have become a merge by accident.
        long parent = insertCompany("Sobeys");
        long child  = insertCompany("Safeway Canada");
        long managerId = insertManager("Sam Store", "Safeway Canada", child);
        insertManagerlessStats(child);

        await(companyRepo.setCompanyParent(child, parent, "SUBSIDIARY_OF"));

        String status = await(pool.preparedQuery("SELECT status FROM companies WHERE id = $1")
            .execute(Tuple.of(child)).map(rs -> rs.iterator().next().getString("status")));
        assertEquals("approved", status, "the child is not retired by being owned");

        Long stillThere = await(pool.preparedQuery("SELECT company_id FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId)).map(rs -> rs.iterator().next().getLong("company_id")));
        assertEquals(child, stillThere, "its managers did not move to the parent");

        var suggestions = await(companyRepo.searchForPicker("Safeway"));
        assertTrue(suggestions.size() > 0, "and it is still findable in its own right");
    }

    @Test
    void settingAParentAgainRepointsRatherThanAddingASecond() throws Exception {
        // Correcting a mistake, not recording two owners. Every surface asks "what is this part
        // of?", which has no answer if there are two.
        long first  = insertCompany("Wrong Parent Ltd");
        long second = insertCompany("Right Parent Ltd");
        long child  = insertCompany("Confused Subsidiary");

        await(companyRepo.setCompanyParent(child, first,  "SUBSIDIARY_OF"));
        await(companyRepo.setCompanyParent(child, second, "SUBSIDIARY_OF"));

        Long rows = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM company_relationships WHERE child_company_id = $1")
            .execute(Tuple.of(child)).map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(1L, rows, "one parent, corrected in place");

        var parent = await(companyRepo.findCompanyParent(child));
        assertEquals("Right Parent Ltd", parent.get().getString("name"));
    }

    @Test
    void aCompanyCannotBePartOfItself() throws Exception {
        long solo = insertCompany("Solo Corp");
        assertThrows(Exception.class,
            () -> await(companyRepo.setCompanyParent(solo, solo, "SUBSIDIARY_OF")));
    }

    @Test
    void aDirectLoopIsRejected() throws Exception {
        long a = insertCompany("Loop A");
        long b = insertCompany("Loop B");
        await(companyRepo.setCompanyParent(b, a, "SUBSIDIARY_OF"));

        Exception thrown = assertThrows(Exception.class,
            () -> await(companyRepo.setCompanyParent(a, b, "SUBSIDIARY_OF")));
        assertTrue(thrown.getMessage().toLowerCase().contains("loop"),
            "expected the loop to be named, got: " + thrown.getMessage());
    }

    @Test
    void anIndirectLoopIsAlsoRejected() throws Exception {
        // A → B → C, then trying to put A under C. The one-step CHECK cannot see this; the
        // trigger walking the ownership chain can. Without it, every recursive query over this
        // table would hang.
        long a = insertCompany("Chain A");
        long b = insertCompany("Chain B");
        long c = insertCompany("Chain C");
        await(companyRepo.setCompanyParent(b, a, "SUBSIDIARY_OF"));
        await(companyRepo.setCompanyParent(c, b, "SUBSIDIARY_OF"));

        assertThrows(Exception.class,
            () -> await(companyRepo.setCompanyParent(a, c, "SUBSIDIARY_OF")));
    }

    @Test
    void multipleLevelsAreAllowed() throws Exception {
        // Loblaw → Shoppers → something. Real ownership nests, and only loops are forbidden.
        long top    = insertCompany("Top Holdings");
        long middle = insertCompany("Middle Group");
        long bottom = insertCompany("Bottom Brand");

        await(companyRepo.setCompanyParent(middle, top,    "SUBSIDIARY_OF"));
        await(companyRepo.setCompanyParent(bottom, middle, "BRAND_OF"));

        assertEquals("Middle Group", await(companyRepo.findCompanyParent(bottom)).get().getString("name"));
        assertEquals("Top Holdings", await(companyRepo.findCompanyParent(middle)).get().getString("name"));
    }

    @Test
    void aMergedParentIsNotShownAsAParent() throws Exception {
        // Sending a reader to a retired company would be worse than showing nothing at all.
        long parent = insertCompany("Absorbed Parent");
        long child  = insertCompany("Orphaned Child");
        await(companyRepo.setCompanyParent(child, parent, "SUBSIDIARY_OF"));

        await(pool.preparedQuery("UPDATE companies SET status = 'merged' WHERE id = $1")
            .execute(Tuple.of(parent)).mapEmpty());

        assertTrue(await(companyRepo.findCompanyParent(child)).isEmpty());
    }

    @Test
    void detachingLeavesBothCompaniesInPlace() throws Exception {
        long parent = insertCompany("Divesting Group");
        long child  = insertCompany("Sold Off Ltd");
        await(companyRepo.setCompanyParent(child, parent, "SUBSIDIARY_OF"));

        assertTrue(await(companyRepo.removeCompanyParent(child)));

        assertTrue(await(companyRepo.findCompanyParent(child)).isEmpty());
        Long both = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM companies WHERE id IN ($1,$2)")
            .execute(Tuple.of(parent, child)).map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(2L, both, "detaching is not deleting");
    }

    @Test
    void childrenWithNoManagersYetStillAppearInTheGroup() throws Exception {
        // company_stats_live is LEFT JOINed for ordering, never used to decide membership. A brand
        // nobody has reviewed yet is still part of the group.
        long parent = insertCompany("Big Group");
        long quiet  = insertCompany("Quiet Brand");
        await(companyRepo.setCompanyParent(quiet, parent, "BRAND_OF"));

        var children = await(companyRepo.findCompanyChildren(parent));
        assertEquals(1, children.size());
        assertEquals(0L, children.iterator().next().getLong("manager_count"));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private long insertCompany(String name) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies(name,status,slug) VALUES ($1,'approved',$2) RETURNING id")
            .execute(Tuple.of(name, name.toLowerCase().replaceAll("[^a-z0-9]+", "-")))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertManager(String name, String company, long companyId) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO managers(name,company,company_id,title,image,status,approval_status," +
                "overall_rating,reviews_count,category_averages) " +
                "VALUES ($1,$2,$3,'VP','img','active','approved',4.0,2,'{}') RETURNING id")
            .execute(Tuple.of(name, company, companyId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private void insertManagerlessStats(long companyId) throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO company_stats_live(company_id, manager_count, total_reviews, avg_rating) " +
                "VALUES ($1,1,2,4.0) ON CONFLICT (company_id) DO NOTHING")
            .execute(Tuple.of(companyId)).mapEmpty());
    }

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
