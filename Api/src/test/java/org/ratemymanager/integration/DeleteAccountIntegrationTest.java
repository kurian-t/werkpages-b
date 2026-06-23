package org.ratemymanager.integration;

import io.vertx.core.Future;
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
import org.ratemymanager.repository.UserRepository;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class DeleteAccountIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("ratemymanager_test")
        .withUsername("test")
        .withPassword("test");

    static Pool           pool;
    static UserRepository userRepo;

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

        pool     = PgPool.pool(connectOptions, new PoolOptions().setMaxSize(5));
        userRepo = new UserRepository(pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, companies, users CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void deleteAccount_noGhostManager_succeeds() throws Exception {
        UUID userId = insertUser("auth0|del1", "deluser1");

        await(userRepo.deleteWithReviewAnonymization(userId));

        long count = await(pool.preparedQuery("SELECT COUNT(*) FROM users WHERE id = $1")
            .execute(Tuple.of(userId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0, count, "User row should be deleted");
    }

    @Test
    void deleteAccount_withGhostManagerCreated_succeedsAndNullsSearchCreatedByUserId() throws Exception {
        UUID userId = insertUser("auth0|del2", "deluser2");

        // Simulate the user having triggered ghost manager creation via /find
        await(pool.preparedQuery("""
                INSERT INTO companies (name, status, created_at, updated_at)
                VALUES ('Acme Corp', 'ghost', now(), now())
                """).execute());
        long companyId = await(pool.preparedQuery("SELECT id FROM companies WHERE name = 'Acme Corp'")
            .execute().map(rs -> rs.iterator().next().getLong("id")));
        await(pool.preparedQuery("""
                INSERT INTO managers
                (name, company, title, status, approval_status, overall_rating, reviews_count,
                 category_averages, search_created_by_user_id, company_id, created_at, updated_at)
                VALUES ('Alice Smith','Acme Corp','Engineer','active','ghost',0,0,'{}', $1, $2, now(), now())
                """).execute(Tuple.of(userId, companyId)));

        await(userRepo.deleteWithReviewAnonymization(userId));

        long userCount = await(pool.preparedQuery("SELECT COUNT(*) FROM users WHERE id = $1")
            .execute(Tuple.of(userId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(0, userCount, "User row should be deleted");

        Object searchCreatedBy = await(pool.query("SELECT search_created_by_user_id FROM managers WHERE name = 'Alice Smith'")
            .execute().map(rs -> rs.iterator().next().getValue("search_created_by_user_id")));
        assertNull(searchCreatedBy, "search_created_by_user_id should be nulled out after account deletion");
    }

    @Test
    void deleteAccount_reviewsAnonymized() throws Exception {
        UUID userId = insertUser("auth0|del3", "deluser3");

        await(pool.preparedQuery("""
                INSERT INTO companies (name, status, created_at, updated_at) VALUES ('Corp', 'ghost', now(), now())
                """).execute());
        long companyId = await(pool.preparedQuery("SELECT id FROM companies WHERE name = 'Corp'")
            .execute().map(rs -> rs.iterator().next().getLong("id")));
        await(pool.preparedQuery("""
                INSERT INTO managers
                (name, company, title, status, approval_status, overall_rating, reviews_count,
                 category_averages, company_id, created_at, updated_at)
                VALUES ('Bob Jones','Corp','Manager','active','ghost',4.0,1,'{}', $1, now(), now())
                """).execute(Tuple.of(companyId)));
        long managerId = await(pool.preparedQuery("SELECT id FROM managers WHERE name = 'Bob Jones'")
            .execute().map(rs -> rs.iterator().next().getLong("id")));
        await(pool.preparedQuery("""
                INSERT INTO reviews
                (manager_id, user_id, author, overall_rating,
                 communication_style, perceived_approachability, perceived_clarity_of_expectations,
                 feedback_style, perceived_supportiveness, decision_making_style,
                 organization_and_planning_style, delegation_style, perceived_professional_demeanor,
                 overall_working_experience, manager_company, manager_title,
                 worked_from, verified, helpful_count, weight, created_at, updated_at)
                VALUES ($1, $2, 'TestUser99', 4.0, 4,4,4,4,4,4,4,4,4,4, 'Corp','Manager','2023-01-01',true,0,false,now(),now())
                """).execute(Tuple.of(managerId, userId)));

        await(userRepo.deleteWithReviewAnonymization(userId));

        String author = await(pool.preparedQuery("SELECT author FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("author")));
        assertEquals("Anonymous User", author, "Review author should be anonymized");

        Object reviewUserId = await(pool.preparedQuery("SELECT user_id FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getValue("user_id")));
        assertNull(reviewUserId, "Review user_id should be nulled out");
    }

    private UUID insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id, email, username, first_name, last_name, role) " +
            "VALUES ($1,$2,$3,$4,$5,$6)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User", "user")));
        return await(pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
