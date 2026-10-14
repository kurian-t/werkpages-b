package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.service.ManagerMatcher;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Java normaliser and the PostgreSQL tokeniser must produce identical tokens.
 *
 * <p>If they disagree, the candidate query cannot retrieve a row that Java would then have
 * classified as a match - and the search silently creates a duplicate instead. The algorithm being
 * correct does not help when the row never arrives.
 *
 * <p>The first version of both sides used an ASCII-only character class, which did not fold accents
 * but DELETED the characters it could not represent: an accented name shredded into fragments and a
 * CJK name produced no tokens at all, making those records permanently unretrievable. This test
 * exists so that cannot come back.
 */
@Testcontainers
class ManagerNameTokenContractIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("tok_contract").withUsername("test").withPassword("test");

    static Pool pool;

    /** Deliberately international, and deliberately including the cases that used to break. */
    static final List<String> NAMES = List.of(
        "Aaron Yip", "Hélène Savard", "José García", "Müller", "Jean-Luc Picard",
        "Mary Ann De La Cruz", "Wei Li", "東京", "李", "O'Dell", "Ng Wu",
        "Anne-Marie O'Brien", "Łukasz Nowak", "Ivan Petrov", "Çağlar Şahin");

    @BeforeAll
    static void setUp() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations").load().migrate();
        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost()).setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername()).setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(3));
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Java and PostgreSQL produce identical name tokens")
    void tokenisersAgree() throws Exception {
        List<String> disagreements = new ArrayList<>();
        for (String name : NAMES) {
            List<String> java = tokensFromJava(name);
            List<String> sql  = tokensFromSql(name);
            if (!java.equals(sql)) disagreements.add(name + "  java=" + java + "  sql=" + sql);
        }
        assertTrue(disagreements.isEmpty(),
            "the two tokenisers disagree, so the candidate query cannot retrieve what the matcher "
            + "would match:\n  " + String.join("\n  ", disagreements));
    }

    @Test
    @DisplayName("no name tokenises to nothing")
    void everyNameHasTokens() throws Exception {
        for (String name : NAMES) {
            assertFalse(tokensFromSql(name).isEmpty(),
                "\"" + name + "\" produced no SQL tokens, so no candidate query could ever find it");
            assertFalse(tokensFromJava(name).isEmpty(),
                "\"" + name + "\" produced no Java tokens");
        }
    }

    @Test
    @DisplayName("accents fold rather than delete: typing without accents still retrieves")
    void accentsFoldBothWays() throws Exception {
        assertEquals(tokensFromSql("Hélène Savard"), tokensFromSql("Helene Savard"));
        assertEquals(tokensFromSql("José García"),   tokensFromSql("Jose Garcia"));
        assertEquals(tokensFromJava("Müller"),       tokensFromJava("Muller"));
        // folding is not deletion: the letters survive
        assertEquals(List.of("helene", "savard"), tokensFromSql("Hélène Savard"));
        assertEquals(List.of("muller"),           tokensFromSql("Müller"));
    }

    @Test
    @DisplayName("every CONFIRMED or PLAUSIBLE name is actually retrievable by the candidate query")
    void everyMatchIsRetrievable() throws Exception {
        // typed first, typed last, stored name
        String[][] cases = {
            {"Aaron", "Yip",         "Aaron Yip"},
            {"Aar",   "Yip",         "Aaron Yip"},
            {"Aaron", "Yi",          "Aaron Yip"},
            {"Aron",  "Yip",         "Aaron Yip"},
            {"Helene","Savard",      "Hélène Savard"},
            {"Hélène","Savard",      "Helene Savard"},
            {"Jose",  "Garcia",      "José García"},
            {"Jean-Luc","Picard",    "Jean Luc Picard"},
            {"Mary Ann","De La Cruz","Mary Ann De La Cruz"},
            {"Maria", "De La Cruz",  "Maria De La Cruz"},
            {"Wei",   "Li",          "Wei Li"},
            {"John",  "Smythe",      "John Smith"},
        };
        for (String[] c : cases) {
            ManagerMatcher.NameClass cls = ManagerMatcher.classifyName(c[0], c[1], c[2]);
            assertNotEquals(ManagerMatcher.NameClass.NONE, cls,
                "fixture expects " + c[0] + " " + c[1] + " to match " + c[2]);

            List<String> tokens = ManagerMatcher.candidateTokens(c[0], c[1]);
            assertTrue(retrievable(c[2], tokens),
                "Java classified \"" + c[0] + " " + c[1] + "\" vs \"" + c[2] + "\" as " + cls
                + ", but the candidate query using tokens " + tokens + " would not retrieve it. "
                + "A match the database never returns is a duplicate waiting to be created.");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<String> tokensFromJava(String name) {
        String n = ManagerMatcher.norm(name);
        return n.isEmpty() ? List.of() : Arrays.asList(n.split(" "));
    }

    private static List<String> tokensFromSql(String name) throws Exception {
        Row r = await(pool.preparedQuery("SELECT manager_name_tokens($1) AS t")
            .execute(Tuple.of(name))).iterator().next();
        String[] arr = r.getArrayOfStrings("t");
        return arr == null ? List.of() : Arrays.asList(arr);
    }

    /** Exactly the predicate the candidate query uses. */
    private static boolean retrievable(String storedName, List<String> candidateTokens) throws Exception {
        Row r = await(pool.preparedQuery(
                "SELECT manager_name_tokens($1) && $2::text[] AS hit")
            // Tuple.of(Object...) would FLATTEN the array into separate parameters, so the
            // array is added explicitly as one typed value.
            .execute(Tuple.tuple().addString(storedName)
                          .addArrayOfString(candidateTokens.toArray(new String[0]))))
            .iterator().next();
        return Boolean.TRUE.equals(r.getBoolean("hit"));
    }

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
