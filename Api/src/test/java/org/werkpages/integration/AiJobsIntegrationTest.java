package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.MergeSuggestionsRepository;
import org.werkpages.service.AnthropicClient;
import org.werkpages.service.DeduplicationJob;
import org.werkpages.service.IndustryClassificationJob;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * The two background jobs that call Anthropic.
 *
 * <p>Both sat at 0% coverage for the same reason: they are constructed only when
 * {@code ANTHROPIC_API_KEY} is set, which it never is in a test run, so nothing ever reached them.
 * That is a property of the wiring rather than of the jobs, and it left the batching, the
 * error-recovery and the termination conditions completely unexercised - in a pair of loops that
 * spend real money per iteration.
 *
 * <p>The client is mocked. What is under test is what the jobs do with its answers: that a failure
 * on one pair does not abandon the rest, that an unclassifiable company is still written so the
 * loop terminates, and that the batch actually stops.
 */
@Testcontainers
class AiJobsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test").withUsername("test").withPassword("test");

    static Pool pool;
    static MergeSuggestionsRepository suggestions;
    static CompanyRepository companies;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations").load().migrate();

        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost()).setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername()).setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(5));

        suggestions = new MergeSuggestionsRepository(pool);
        companies   = new CompanyRepository(pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query(
            "TRUNCATE merge_suggestions, reviews, managers, company_stats_live, companies, users CASCADE")
            .execute().mapEmpty());
    }

    @AfterAll
    static void tearDownAll() { if (pool != null) pool.close(); }

    // ── Deduplication ─────────────────────────────────────────────────────────

    @Test
    void anEvaluatedPairIsStoredAsASuggestion() throws Exception {
        long companyId = insertCompany("Dedupe Co");
        long a = insertManager("Ada Twin", companyId);
        long b = insertManager("Ada Twinn", companyId);

        AnthropicClient client = mock(AnthropicClient.class);
        when(client.evaluatePair(any(), any())).thenReturn(Future.succeededFuture(
            new AnthropicClient.EvaluationResult("HIGH", "same name and company")));

        runAndSettle(new DeduplicationJob(suggestions, client));

        Row row = await(pool.preparedQuery(
                "SELECT confidence, reason, status FROM merge_suggestions "
                + "WHERE manager_id_a IN ($1,$2) AND manager_id_b IN ($1,$2)")
            .execute(Tuple.of(a, b)).map(rs -> rs.iterator().hasNext() ? rs.iterator().next() : null));

        assertNotNull(row, "the pair the model evaluated is written");
        assertEquals("HIGH", row.getString("confidence"));
        assertEquals("pending", row.getString("status"), "a suggestion waits for an admin");
    }

    @Test
    void oneFailedPairDoesNotAbandonTheRest() throws Exception {
        /*
         * The loop is sequential and recursive. A pair that throws used to be the interesting case:
         * if the failure path did not continue, one bad response would silently end the run and
         * every later pair would go unevaluated until somebody noticed the queue was short.
         */
        long companyId = insertCompany("Resilient Co");
        insertManager("Bea Twin", companyId);
        insertManager("Bea Twinn", companyId);
        insertManager("Cal Twin", companyId);
        insertManager("Cal Twinn", companyId);

        AnthropicClient client = mock(AnthropicClient.class);
        when(client.evaluatePair(any(), any()))
            .thenReturn(Future.failedFuture(new RuntimeException("model unavailable")))
            .thenReturn(Future.succeededFuture(
                new AnthropicClient.EvaluationResult("LOW", "different people")));

        runAndSettle(new DeduplicationJob(suggestions, client));

        verify(client, atLeast(2)).evaluatePair(any(), any());
        Long written = await(pool.query("SELECT COUNT(*) AS n FROM merge_suggestions")
            .execute().map(rs -> rs.iterator().next().getLong("n")));
        assertTrue(written >= 1, "the pairs after the failure were still evaluated and stored");
    }

    @Test
    void noCandidatePairsIsNotAnError() throws Exception {
        // An empty database is the ordinary state on a new deployment, not a failure.
        AnthropicClient client = mock(AnthropicClient.class);
        runAndSettle(new DeduplicationJob(suggestions, client));
        verify(client, never()).evaluatePair(any(), any());
    }

    // ── Industry classification ───────────────────────────────────────────────

    @Test
    void classifyingWritesTheIndustryAndReportsWhatItDid() throws Exception {
        long companyId = insertCompany("Classify Co");
        insertManager("Dana Lead", companyId);   // a company needs a manager to be classified

        AnthropicClient client = mock(AnthropicClient.class);
        when(client.classifyIndustries(anyList()))
            .thenReturn(Future.succeededFuture(Map.of(companyId, "Technology")));

        var summary = await(new IndustryClassificationJob(companies, client).run());

        assertEquals("Technology", await(pool.preparedQuery(
                "SELECT industry FROM companies WHERE id = $1").execute(Tuple.of(companyId))
            .map(rs -> rs.iterator().next().getString("industry"))));
        assertTrue(summary.getInteger("classified") >= 1, "the run says how many it did");
    }

    @Test
    void aCompanyTheModelSkipsIsStillWrittenSoTheLoopTerminates() throws Exception {
        /*
         * The termination condition. The job selects companies with a NULL industry, so anything
         * the model declines to classify would be selected again on the next batch, forever. It is
         * coerced to "Other" for exactly that reason, and this is the test that says so.
         */
        long companyId = insertCompany("Unclassifiable Co");
        insertManager("Eli Lead", companyId);

        AnthropicClient client = mock(AnthropicClient.class);
        when(client.classifyIndustries(anyList())).thenReturn(Future.succeededFuture(Map.of()));

        await(new IndustryClassificationJob(companies, client).run());

        assertEquals("Other", await(pool.preparedQuery(
                "SELECT industry FROM companies WHERE id = $1").execute(Tuple.of(companyId))
            .map(rs -> rs.iterator().next().getString("industry"))),
            "skipped companies leave the unclassified set rather than being retried forever");
    }

    @Test
    void aCompanyWithNoManagersIsNotWorthAnApiCall() throws Exception {
        // Empty ghost shells are the majority of the table. Spending a call on one is money for
        // an industry nobody will ever see.
        insertCompany("Empty Shell Co");

        AnthropicClient client = mock(AnthropicClient.class);
        when(client.classifyIndustries(anyList())).thenReturn(Future.succeededFuture(Map.of()));

        await(new IndustryClassificationJob(companies, client).run());

        verify(client, never()).classifyIndustries(anyList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** run() is fire-and-forget, so give its sequential chain a moment to drain. */
    private static void runAndSettle(DeduplicationJob job) throws Exception {
        job.run();
        Thread.sleep(1200);
    }

    private long insertCompany(String name) throws Exception {
        return await(pool.preparedQuery("INSERT INTO companies (name) VALUES ($1) RETURNING id")
            .execute(Tuple.of(name)).map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertManager(String name, long companyId) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO managers (name, company, title, status, approval_status, country, "
                + "overall_rating, reviews_count, category_averages, company_id) "
                + "SELECT $1, c.name, 'Manager', 'active', 'approved', 'Canada', 4.0, 2, '{}'::jsonb, c.id "
                + "FROM companies c WHERE c.id = $2 RETURNING id")
            .execute(Tuple.of(name, companyId)).map(rs -> rs.iterator().next().getLong("id")));
    }

    private static <T> T await(Future<T> future) throws Exception {
        CompletableFuture<T> cf = new CompletableFuture<>();
        future.onSuccess(cf::complete).onFailure(cf::completeExceptionally);
        return cf.get(30, TimeUnit.SECONDS);
    }
}
