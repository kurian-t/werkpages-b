package org.ratemymanager.db;

import io.vertx.core.Vertx;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Pool;
import org.flywaydb.core.Flyway;

public class Database {

    private static Pool client;

    // Initialize pool and run Flyway migrations
    public static void init(Vertx vertx) {
        // PostgreSQL connection options
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setPort(5432)
                .setHost("localhost")
                .setDatabase("rmm")
                .setUser("postgres")
                .setPassword("postgres");

        PoolOptions poolOptions = new PoolOptions().setMaxSize(10);

        // Run Flyway migrations asynchronously
        vertx.executeBlocking(promise -> {
            try {
                Class.forName("org.postgresql.Driver"); // load driver
                runMigrations(connectOptions);
                promise.complete();
            } catch (Exception e) {
                promise.fail(e);
            }
        }, res -> {
            if (res.succeeded()) {
                client = Pool.pool(vertx, connectOptions, poolOptions);
                System.out.println("Database pool ready!");
            } else {
                res.cause().printStackTrace();
            }
        });
    }

    public static SqlClient getClient() {
        return client;
    }

    // Flyway migration
    private static void runMigrations(PgConnectOptions connectOptions) {
        String jdbcUrl = String.format(
                "jdbc:postgresql://%s:%d/%s",
                connectOptions.getHost(),
                connectOptions.getPort(),
                connectOptions.getDatabase()
        );
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            // Set the ClassLoader to the one that loaded Database.class (inside the fat JAR)
            Thread.currentThread().setContextClassLoader(Database.class.getClassLoader());

            Flyway flyway = Flyway.configure()
                    .dataSource(jdbcUrl, connectOptions.getUser(), connectOptions.getPassword())
                    .schemas("public")
                    .locations("classpath:db/migrations")
                    .load();

            flyway.migrate();
        } finally {
            // Restore original ClassLoader
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }
}