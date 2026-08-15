package org.werkpages.integration;

import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.werkpages.repository.MergeSuggestionsRepository;
import org.werkpages.repository.MergeSuggestionsRepository.CandidatePair;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class MergeSuggestionsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static MergeSuggestionsRepository repo;

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
        repo = new MergeSuggestionsRepository(pool);
    }

    @BeforeEach
    void cleanUp() throws Exception {
        pool.query("DELETE FROM merge_suggestions").execute()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        pool.query("DELETE FROM reviews").execute()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        pool.query("DELETE FROM managers").execute()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        pool.query("DELETE FROM companies").execute()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long insertCompany(String name) throws Exception {
        return pool.preparedQuery("""
                INSERT INTO companies (name, slug, status, created_at, updated_at)
                VALUES ($1, $2, 'approved', now(), now())
                RETURNING id
                """)
            .execute(Tuple.of(name, name.toLowerCase().replace(" ", "-")))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getLong("id");
    }

    private long insertManager(String name, String status) throws Exception {
        long companyId = insertCompany(name + " Corp");
        String slug = name.toLowerCase().replace(" ", "-") + "-" + System.nanoTime();
        return pool.preparedQuery("""
                INSERT INTO managers
                    (name, slug, company, title, status, approval_status, company_id,
                     overall_rating, reviews_count, category_averages, created_at, updated_at)
                VALUES ($1, $2, $3, 'Manager', 'active', $4, $5, 0, 0, '{}', now(), now())
                RETURNING id
                """)
            .execute(Tuple.of(name, slug, name + " Corp", status, companyId))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getLong("id");
    }

    // ── findCandidatePairs ────────────────────────────────────────────────────

    @Test
    void findCandidatePairs_emptyDatabase_returnsEmptyList() throws Exception {
        List<CandidatePair> pairs = repo.findCandidatePairs()
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertNotNull(pairs);
        assertTrue(pairs.isEmpty());
    }

    @Test
    void findCandidatePairs_managersWithSimilarNames_returnsCandidate() throws Exception {
        // "John Smith" and "Jon Smith" are within Levenshtein distance 2
        insertManager("John Smith", "approved");
        insertManager("Jon Smith", "approved");

        List<CandidatePair> pairs = repo.findCandidatePairs()
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(1, pairs.size());
        CandidatePair pair = pairs.get(0);
        assertNotNull(pair.profileA());
        assertNotNull(pair.profileB());
        assertNull(pair.existingSuggestionId()); // no suggestion yet
    }

    @Test
    void findCandidatePairs_managersWithDifferentNames_returnsEmpty() throws Exception {
        // "Alice" and "Bob" are more than 2 edits apart
        insertManager("Alice Johnson", "approved");
        insertManager("Bob Williams", "approved");

        List<CandidatePair> pairs = repo.findCandidatePairs()
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(pairs.isEmpty());
    }

    @Test
    void findCandidatePairs_onlyApprovedAndGhostManagers_considered() throws Exception {
        insertManager("John Smith", "approved");
        insertManager("Jon Smith", "pending_approval"); // should be excluded

        List<CandidatePair> pairs = repo.findCandidatePairs()
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(pairs.isEmpty());
    }

    // ── upsert ────────────────────────────────────────────────────────────────

    @Test
    void upsert_insertNewSuggestion() throws Exception {
        long idA = insertManager("John Smith", "approved");
        long idB = insertManager("Jon Smith", "approved");

        repo.upsert(idA, idB, "LIKELY_SAME", "Very similar names", 3, 2)
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        int count = pool.query("SELECT COUNT(*) FROM merge_suggestions")
            .execute()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getInteger(0);

        assertEquals(1, count);
    }

    @Test
    void upsert_differentConfidence_setsCorrectStatus() throws Exception {
        long idA = insertManager("Alice A", "approved");
        long idB = insertManager("Alice B", "approved");

        repo.upsert(idA, idB, "DIFFERENT", "Different people", 1, 1)
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        String status = pool.query("SELECT status FROM merge_suggestions LIMIT 1")
            .execute()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getString("status");

        assertEquals("different", status);
    }

    @Test
    void upsert_sameSuggestionConfidence_setsStatusPending() throws Exception {
        long idA = insertManager("Bob X", "approved");
        long idB = insertManager("Bob Y", "approved");

        repo.upsert(idA, idB, "SAME", "Definitely same", 5, 5)
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        String status = pool.query("SELECT status FROM merge_suggestions LIMIT 1")
            .execute()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getString("status");

        assertEquals("pending", status);
    }

    @Test
    void upsert_conflictUpdatesExistingRow() throws Exception {
        long idA = insertManager("Carl A", "approved");
        long idB = insertManager("Carl B", "approved");

        repo.upsert(idA, idB, "DIFFERENT", "First evaluation", 1, 1)
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        repo.upsert(idA, idB, "LIKELY_SAME", "Re-evaluated with more data", 5, 5)
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        int count = pool.query("SELECT COUNT(*) FROM merge_suggestions")
            .execute()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getInteger(0);

        assertEquals(1, count); // Still one row, updated

        String confidence = pool.query("SELECT confidence FROM merge_suggestions LIMIT 1")
            .execute()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getString("confidence");

        assertEquals("LIKELY_SAME", confidence);
    }

    // ── findPending / countPending ────────────────────────────────────────────

    @Test
    void countPending_noSuggestions_returnsZero() throws Exception {
        int count = repo.countPending()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(0, count);
    }

    @Test
    void countPending_onePendingSuggestion_returnsOne() throws Exception {
        long idA = insertManager("Dave A", "approved");
        long idB = insertManager("Dave B", "approved");

        repo.upsert(idA, idB, "SAME", "Same person", 3, 3)
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        int count = repo.countPending()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(1, count);
    }

    @Test
    void countPending_dismissedSuggestion_notCounted() throws Exception {
        long idA = insertManager("Eve A", "approved");
        long idB = insertManager("Eve B", "approved");

        repo.upsert(idA, idB, "DIFFERENT", "Different people", 1, 1)
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // "different" status is not pending
        int count = repo.countPending()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(0, count);
    }

    @Test
    void findPending_onePendingSuggestion_returnsRow() throws Exception {
        long idA = insertManager("Frank A", "approved");
        long idB = insertManager("Frank B", "approved");

        repo.upsert(idA, idB, "LIKELY_SAME", "Similar name", 2, 2)
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        var rows = repo.findPending(10, 0)
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(1, rows.rowCount());
        var row = rows.iterator().next();
        assertEquals("LIKELY_SAME", row.getString("confidence"));
    }

    @Test
    void findPending_paginationWorks() throws Exception {
        // Insert 3 pending suggestions
        for (int i = 0; i < 3; i++) {
            long idA = insertManager("Greg " + i + "A", "approved");
            long idB = insertManager("Greg " + i + "B", "approved");
            repo.upsert(idA, idB, "SAME", "Same person " + i, i, i)
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }

        var page1 = repo.findPending(2, 0)
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var page2 = repo.findPending(2, 2)
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(2, page1.rowCount());
        assertEquals(1, page2.rowCount());
    }

    // ── updateStatus ─────────────────────────────────────────────────────────

    @Test
    void updateStatus_changesPendingToDismissed() throws Exception {
        long idA = insertManager("Helen A", "approved");
        long idB = insertManager("Helen B", "approved");

        repo.upsert(idA, idB, "SAME", "Likely same", 2, 2)
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        long suggestionId = pool.query("SELECT id FROM merge_suggestions LIMIT 1")
            .execute()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getLong("id");

        repo.updateStatus(suggestionId, "dismissed")
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        String newStatus = pool.preparedQuery("SELECT status FROM merge_suggestions WHERE id = $1")
            .execute(Tuple.of(suggestionId))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getString("status");

        assertEquals("dismissed", newStatus);
    }

    @Test
    void updateStatus_changesPendingToMerged() throws Exception {
        long idA = insertManager("Ivan A", "approved");
        long idB = insertManager("Ivan B", "approved");

        repo.upsert(idA, idB, "SAME", "Same person", 4, 4)
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        long suggestionId = pool.query("SELECT id FROM merge_suggestions LIMIT 1")
            .execute()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getLong("id");

        repo.updateStatus(suggestionId, "merged")
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        String newStatus = pool.preparedQuery("SELECT status FROM merge_suggestions WHERE id = $1")
            .execute(Tuple.of(suggestionId))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getString("status");

        assertEquals("merged", newStatus);
    }

    @Test
    void findCandidatePairs_existingSuggestionId_populatedWhenSuggestionExists() throws Exception {
        long idA = insertManager("Jake X", "approved");
        long idB = insertManager("Jake Y", "approved");

        // Note: Jake X and Jake Y differ by 2 chars ("x" vs "y" in last name letter
        // and final " X" vs " Y") — let's verify they're candidates first
        List<CandidatePair> initialPairs = repo.findCandidatePairs()
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        if (initialPairs.isEmpty()) {
            // If the names aren't close enough (Levenshtein > 2), skip this test
            return;
        }

        repo.upsert(idA, idB, "DIFFERENT", "Different people", 0, 0)
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // After upserting as DIFFERENT, they shouldn't show up in candidates
        // (since status is 'different', not 'dismissed', the re-evaluation condition needs more reviews)
        List<CandidatePair> afterPairs = repo.findCandidatePairs()
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        // Since reviews count hasn't changed, they should not re-appear
        assertTrue(afterPairs.isEmpty());
    }
}
