package org.werkpages.db;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.config.SecretsConfig;

import java.lang.reflect.Constructor;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Vertx;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A FAILED MIGRATION MUST KILL THE PROCESS, NOT DECORATE THE LOG.
 *
 * <h2>The defect this exists to prevent</h2>
 *
 * <p>{@code Database.init} used to handle a Flyway failure like this, and this was the whole of it:
 *
 * <pre>
 *   System.err.println("✗ Database init failed: " + res.cause().getMessage());
 *   res.cause().printStackTrace();
 * </pre>
 *
 * <p>No exit, no rethrow, no retry. That would be merely untidy if the process then stopped, but
 * <b>the entire HTTP server is constructed inside the success callback</b> of that same method. So
 * a failed migration produced a JVM that was running, had no listener on any port, and had written
 * a stack trace to a stream nobody tails.
 *
 * <p>Everything downstream then reported success. {@code docker compose up -d} returned 0. The
 * container stayed in the process table. There is no {@code HEALTHCHECK} in the Dockerfile and no
 * health endpoint in either backend, so anything checking "is it alive" got the answer "yes" from
 * a service that was entirely absent. <b>A deploy that had destroyed the service looked like a
 * deploy that had worked</b>, and the only way to find out was for a person to try the site.
 *
 * <h2>Why the test is shaped like this</h2>
 *
 * <p>Two separate claims have to be proven, and one test cannot prove both.
 *
 * <ol>
 *   <li><b>A real Flyway failure reaches the fatal path, and the server is never built.</b> Proven
 *       against a real PostgreSQL container with a genuinely broken migration run - not a mocked
 *       Flyway, because the thing being tested is precisely what happens when the real one throws.
 *   <li><b>The fatal path really ends the process with a nonzero status.</b> That cannot be
 *       asserted in-process: a test that proves it would be killing the JVM doing the asserting.
 *       It is proven by forking a JVM and reading its exit code, which is the only form of
 *       evidence an orchestrator actually acts on.
 * </ol>
 *
 * <p>This test lives in {@code org.werkpages.db} so that {@code exitProcess} can stay
 * package-private - the seam is widened for one test rather than for the whole application - and
 * in the {@code RestApi} module because that is where {@code APP_ENV} is configured for the test
 * harness, which {@code Database.useSSL()} requires.
 *
 * <p>Test 1 swaps {@code Database.exitProcess} so it can watch the decision instead of dying of
 * it. That seam exists for this test and for nothing else. Test 2 does not use the seam at all,
 * which is what keeps test 1 honest: if the default wiring were a no-op, test 1 would still pass
 * and test 2 would fail.
 */
@Tag("critical")
@Testcontainers
class MigrationFailFastIntegrationTest {

    static PostgreSQLContainer<?> PG;

    @BeforeAll
    static void start() {
        PG = new PostgreSQLContainer<>("postgres:15-alpine");
        PG.start();
    }

    @AfterAll
    static void stop() {
        if (PG != null) PG.stop();
    }

    /**
     * {@code SecretsConfig}'s constructor is private and {@code load()} reads process environment,
     * which a test cannot set for itself. Reflection is the narrow way in, and it is confined to
     * this one helper so the rest of the test reads normally.
     */
    private static SecretsConfig secretsFor(PostgreSQLContainer<?> pg) throws Exception {
        Constructor<SecretsConfig> c = SecretsConfig.class.getDeclaredConstructor(
            String.class, int.class, String.class, String.class, String.class,
            String.class, String.class, String.class, String.class, String.class,
            String.class, String.class, String.class, String.class, String.class);
        c.setAccessible(true);
        return c.newInstance(
            pg.getHost(), pg.getFirstMappedPort(), pg.getDatabaseName(),
            pg.getUsername(), pg.getPassword(),
            "test.auth0.com", null, "cid", "csecret", "https://api.test/",
            "turnstile", null, null, null, "cron-secret");
    }

    @Test
    @DisplayName("a failed migration exits nonzero and never reaches the code that starts the server")
    void aFailedMigrationExitsNonZeroAndNeverStartsTheServer() throws Exception {
        /*
          A GENUINE Flyway failure, caused the way a real one is caused: the database is not in the
          state the migration stream expects. V1__init.sql opens with CREATE TABLE users, so a
          database that already has a `users` table makes the very first migration throw. This is
          not a contrived error - it is what a half-initialised or hand-edited database looks like,
          and it is the failure most likely to happen during the V97 window.
        */
        try (Connection c = DriverManager.getConnection(
                 PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE users (id INT)");
        }

        AtomicInteger exitStatus = new AtomicInteger(Integer.MIN_VALUE);
        AtomicBoolean serverWouldHaveStarted = new AtomicBoolean(false);
        CompletableFuture<Void> decided = new CompletableFuture<>();

        java.util.function.IntConsumer original = Database.exitProcess;
        Vertx vertx = Vertx.vertx();
        try {
            Database.exitProcess = status -> {
                exitStatus.set(status);
                decided.complete(null);
            };

            // onReady is where MainVerticle builds the router, the handlers and the HTTP server.
            // If it runs at all, a broken database has just produced a listening service.
            Database.init(vertx, secretsFor(PG), () -> {
                serverWouldHaveStarted.set(true);
                decided.complete(null);
            });

            /*
              A TIMEOUT HERE IS THE REGRESSION, not a slow machine.

              With the old swallow-and-continue handler this is exactly what happened: the failure
              callback printed, returned, and nothing else ever ran. onReady was never called and
              the process was never ended, so boot neither succeeded nor failed - it simply stopped
              halfway, forever. That is the production shape of this defect, and it is why a
              liveness check could never detect it.
            */
            try {
                decided.get(90, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                fail("startup neither completed nor exited after a failed migration: the boot hung "
                   + "half-finished, which is the defect this test exists to prevent");
            }

            assertFalse(serverWouldHaveStarted.get(),
                "onReady ran after the migration failed, so the HTTP server would have been "
                + "started against a database in an unknown state");
            assertEquals(1, exitStatus.get(),
                "a failed migration must exit the process with a nonzero status; "
                + "anything else leaves a container that looks healthy and serves nothing");
            assertNull(Database.getClient(),
                "no connection pool may be published when initialisation failed");
        } finally {
            Database.exitProcess = original;
            vertx.close();
        }
    }

    /**
     * The claim test 1 cannot make: that the default handler really ends the process, and really
     * with a status an orchestrator treats as failure.
     *
     * <p>Forked deliberately. The assertion is on an exit code, and an exit code only exists once
     * something has actually exited.
     */
    @Test
    @DisplayName("the default fatal handler ends a real process with status 1")
    void theDefaultFatalHandlerEndsARealProcessWithStatusOne() throws Exception {
        String java = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder pb = new ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"),
            FailFastProbe.class.getName());
        pb.redirectErrorStream(true);
        Process p = pb.start();

        String output = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the probe JVM did not terminate");

        assertEquals(1, p.exitValue(),
            "Database.failFast must terminate the JVM with status 1. Probe output:\n" + output);
        assertFalse(output.contains("STILL RUNNING"),
            "failFast returned to its caller instead of ending the process. Probe output:\n"
            + output);
        assertTrue(output.contains("deliberate boot failure"),
            "the cause must reach the log before the process leaves. Probe output:\n" + output);
    }

    /** Run in a forked JVM by the test above. Must stay public and static. */
    public static final class FailFastProbe {
        public static void main(String[] args) {
            Database.failFast("probe", new IllegalStateException("deliberate boot failure"));
            // Only reachable if failFast did not exit, which is the regression.
            System.out.println("STILL RUNNING");
        }
    }
}
