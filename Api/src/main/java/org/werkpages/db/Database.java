package org.werkpages.db;

import io.vertx.core.Vertx;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Pool;
import org.flywaydb.core.Flyway;
import org.werkpages.config.SecretsConfig;

public class Database {

    private static Pool client;
    /**
     * TLS to the database, decided by the environment rather than by where secrets came from.
     * Those were the same variable, so a deployment that sourced secrets any other way silently
     * connected in plaintext.
     *
     * Read on use rather than in a static initialiser: APP_ENV is required and throws when
     * absent, and an initialiser that throws makes the whole class unloadable - which broke
     * tests that only wanted to read the migrations flag beside it.
     */
    private static boolean useSSL() {
        return org.werkpages.config.AppEnv.current().isProduction();
    }
    
    public static void init(Vertx vertx, SecretsConfig secrets, Runnable onReady) {
    	
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setPort(secrets.dbPort)
            .setHost(secrets.dbHost)
            .setDatabase(secrets.dbName)
            .setUser(secrets.dbUser)
            .setPassword(secrets.dbPassword)
            .setConnectTimeout(5000); // 5 s to establish a connection; fail fast rather than hang
           

		if (useSSL()) {
			connectOptions.setSslMode(io.vertx.pgclient.SslMode.REQUIRE);
			connectOptions.setTrustAll(true);
		}

        PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(20)        // doubled from 10 — headroom for concurrent requests
            .setIdleTimeout(30);   // reclaim idle connections after 30 s (keeps RDS costs down)

        vertx.executeBlocking(promise -> {
            try {
                Class.forName("org.postgresql.Driver");
                runMigrations(connectOptions);
                promise.complete();
            } catch (Exception e) {
                promise.fail(e);
            }
        }, res -> {
            if (res.succeeded()) {
                client = Pool.pool(vertx, connectOptions, poolOptions);
                System.out.println("✓ Database pool ready");
                if (onReady != null) onReady.run();
            } else {
                failFast("Database init failed", res.cause());
            }
        });
    }

    /**
     * How the process leaves when startup cannot complete.
     *
     * <p>Replaceable <b>only</b> so a regression test can observe the decision instead of killing
     * the JVM that is asserting on it. Nothing in production ever reassigns it.
     */
    static java.util.function.IntConsumer exitProcess = System::exit;

    /**
     * Startup failed. Say why, and LEAVE.
     *
     * <p><b>This used to print and carry on,</b> and that was the single most dangerous line in
     * the deployment. The HTTP server is constructed inside {@code init}'s success callback, so a
     * failed migration produced a process that was alive, listening on nothing, and holding a
     * stack trace nobody was watching. {@code docker compose up -d} reported success, a
     * process-liveness check passed, and the deploy looked clean while the service was entirely
     * absent. A container that is wrong must die so that something notices.
     *
     * <p>It is deliberately the same exit for every boot failure - migrations, JWKS, the router
     * factory, the listen call - because they share the consequence. One place owns "boot failed"
     * rather than four places each deciding to shrug.
     *
     * <p>The streams are flushed first. {@code System.exit} does not flush them for you, and a
     * diagnosis that never reached the log is the same as no diagnosis.
     */
    public static void failFast(String what, Throwable cause) {
        System.err.println("✗ " + what + ": "
            + (cause == null ? "(no cause reported)" : String.valueOf(cause.getMessage())));
        if (cause != null) cause.printStackTrace();
        System.out.flush();
        System.err.flush();
        exitProcess.accept(1);
    }

    public static SqlClient getClient() {
        return client;
    }

    /**
     * The pool itself, for the few callers that need a connection rather than a query.
     *
     * <p>An advisory SESSION lock belongs to a connection, so anything coordinating across
     * processes - company_stats maintenance, which must not overlap between the scheduled sweep and
     * an admin action, and across the two backends sharing this database - has to pin one.
     * {@link #getClient()} deliberately hides that, and remains the right accessor for ordinary
     * queries.
     */
    public static Pool getPool() {
        return client;
    }

    private static void runMigrations(PgConnectOptions connectOptions) {
    	String sslSuffix = useSSL() ? "?sslmode=require" : "";
    	String jdbcUrl = String.format(
    		    "jdbc:postgresql://%s:%d/%s%s",
    		    connectOptions.getHost(),
    		    connectOptions.getPort(),
    		    connectOptions.getDatabase(),
    		    sslSuffix
    		);
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(Database.class.getClassLoader());
            Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, connectOptions.getUser(), connectOptions.getPassword())
                .schemas("public")
                .locations("classpath:db/migrations")
                .load();
            flyway.migrate();
            System.out.println("✓ Flyway migrations complete");
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }
}