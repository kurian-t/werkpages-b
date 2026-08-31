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
 * What a company merge must not destroy.
 *
 * The merge tool on the admin panel used to finish with {@code DELETE FROM companies}. Several
 * tables reference companies with ON DELETE CASCADE, so that one statement reached much further
 * than the merge intended: every interview review written about the merged company was deleted,
 * along with its aliases, and nothing recorded that any of it had existed.
 *
 * These tests are the guard on that. Each one describes a thing a merge is not allowed to do.
 */
class CompanyMergeDataLossIntegrationTest {

    static PostgreSQLContainer<?> pg;
    static Pool pool;
    static CompanyRepository companyRepo;
    static UUID adminId;

    @BeforeAll
    static void setUp() throws Exception {
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
        adminId = insertUser("admin@test.com");
    }

    @AfterAll
    static void tearDown() { if (pg != null) pg.stop(); }

    @Test
    void mergePreservesInterviewReviews() throws Exception {
        // The headline: these were being deleted outright by the cascade.
        long keep  = insertCompany("Crumbl");
        long merge = insertCompany("Crumbl Cookies");
        UUID user  = insertUser("interviewee1@test.com");
        insertInterviewReview(merge, user, 2024);

        await(companyRepo.mergeCompanies(keep, merge, adminId));

        Long survived = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM interview_reviews WHERE company_id = $1 AND deleted_at IS NULL")
            .execute(Tuple.of(keep)).map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(1L, survived,
            "the interview review moved to the surviving company instead of being destroyed");
    }

    @Test
    void mergeMovesAliasesAndKeepsTheOldNameFindable() throws Exception {
        long keep  = insertCompany("Acme");
        long merge = insertCompany("Acme Industries");
        await(pool.preparedQuery("INSERT INTO company_aliases(company_id, alias) VALUES ($1,$2)")
            .execute(Tuple.of(merge, "Acme Ind")).mapEmpty());

        await(companyRepo.mergeCompanies(keep, merge, adminId));

        Long moved = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM company_aliases WHERE company_id = $1 AND alias = $2")
            .execute(Tuple.of(keep, "Acme Ind")).map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(1L, moved, "the alias followed the data");

        Long oldName = await(pool.preparedQuery(
                "SELECT COUNT(*) AS c FROM company_aliases WHERE company_id = $1 AND alias = $2")
            .execute(Tuple.of(keep, "Acme Industries")).map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(1L, oldName,
            "and the merged company's own name became an alias, so searching it finds the survivor "
            + "rather than nothing - otherwise the next person simply recreates it");
    }

    @Test
    void mergeRetiresTheSourceRatherThanDeletingIt() throws Exception {
        long keep  = insertCompany("Globex");
        long merge = insertCompany("Globex Corporation");

        await(companyRepo.mergeCompanies(keep, merge, adminId));

        String status = await(pool.preparedQuery("SELECT status FROM companies WHERE id = $1")
            .execute(Tuple.of(merge)).map(rs -> rs.iterator().hasNext()
                ? rs.iterator().next().getString("status") : null));
        assertEquals("merged", status, "the source is retired, so nothing cascades and history survives");
    }

    @Test
    void mergeRecordsEveryRowItMoved() throws Exception {
        // The manifest is what makes an undo possible: exact row ids, not a summary.
        long keep  = insertCompany("Initech");
        long merge = insertCompany("Initech Systems");
        long managerId = insertManager("Moved Manager", "Initech Systems", merge);

        UUID mergeUuid = await(companyRepo.mergeCompanies(keep, merge, adminId));

        var row = await(pool.preparedQuery("""
                SELECT record_id, old_company_id, new_company_id, old_company_text
                FROM company_merge_records WHERE merge_id = $1 AND entity_type = 'manager'
                """)
            .execute(Tuple.of(mergeUuid)).map(rs -> rs.iterator().next()));
        assertEquals(String.valueOf(managerId), row.getString("record_id"));
        assertEquals(merge, row.getLong("old_company_id"));
        assertEquals(keep,  row.getLong("new_company_id"));
        assertEquals("Initech Systems", row.getString("old_company_text"),
            "the denormalised name is recorded too, or an undo could not restore the picker");
    }

    @Test
    void mergeKeepsTheOldUrlWorking() throws Exception {
        long keep  = insertCompany("Umbrella");
        long merge = insertCompany("Umbrella Corp");

        await(companyRepo.mergeCompanies(keep, merge, adminId));

        Long target = await(pool.preparedQuery(
                "SELECT company_id FROM company_redirects WHERE old_slug = $1")
            .execute(Tuple.of("umbrella-corp")).map(rs -> rs.iterator().hasNext()
                ? rs.iterator().next().getLong("company_id") : null));
        assertEquals(keep, target, "a shared link to the merged company must not become a 404");
    }

    @Test
    void mergeRefusesWhenInterviewReviewsWouldCollide() throws Exception {
        // Same person, same year, both companies. Both are genuine contributions and the unique
        // index cannot hold both, so there is no correct automatic answer - it stops and asks.
        long keep  = insertCompany("Wayne");
        long merge = insertCompany("Wayne Enterprises");
        UUID user  = insertUser("both@test.com");
        insertInterviewReview(keep,  user, 2024);
        insertInterviewReview(merge, user, 2024);

        Exception thrown = assertThrows(Exception.class,
            () -> await(companyRepo.mergeCompanies(keep, merge, adminId)));
        assertTrue(thrown.getMessage().contains("collide"),
            "expected a refusal explaining the collision, got: " + thrown.getMessage());

        // And it refused before touching anything.
        String status = await(pool.preparedQuery("SELECT status FROM companies WHERE id = $1")
            .execute(Tuple.of(merge)).map(rs -> rs.iterator().next().getString("status")));
        assertEquals("approved", status, "a refused merge leaves the source exactly as it was");
    }

    @Test
    void previewReportsWhatWouldMoveWithoutMovingIt() throws Exception {
        long keep  = insertCompany("Stark");
        long merge = insertCompany("Stark Industries");
        insertManager("Pepper Potts", "Stark Industries", merge);

        var preview = await(companyRepo.previewMerge(keep, merge));

        assertEquals(1L, preview.getLong("managers"));
        assertEquals("Stark", preview.getString("keepName"));
        assertFalse(preview.getBoolean("blocked"));

        String status = await(pool.preparedQuery("SELECT status FROM companies WHERE id = $1")
            .execute(Tuple.of(merge)).map(rs -> rs.iterator().next().getString("status")));
        assertEquals("approved", status, "preview must not write anything");
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
                "VALUES ($1,$2,$3,'VP','img','active','approved',0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, companyId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static UUID insertUser(String email) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO users(auth0_id,email,username) VALUES ($1,$2,$3) RETURNING id")
            .execute(Tuple.of("auth0|" + email, email, email.split("@")[0]))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private void insertInterviewReview(long companyId, UUID userId, int year) throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO interview_reviews(company_id,user_id,interview_year,overall_rating," +
                "difficulty,outcome) VALUES ($1,$2,$3,4,3,'offer')")
            .execute(Tuple.of(companyId, userId, year)).mapEmpty());
    }

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
