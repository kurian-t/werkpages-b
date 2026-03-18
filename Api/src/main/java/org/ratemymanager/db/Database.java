package org.ratemymanager.db;

import io.vertx.core.Vertx;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Pool;
import org.flywaydb.core.Flyway;
import org.ratemymanager.config.SecretsConfig;

public class Database {

    private static Pool client;
    private static boolean useSSL = "true".equalsIgnoreCase(System.getenv("USE_AWS_SECRETS"));
    
    public static void init(Vertx vertx, SecretsConfig secrets, Runnable onReady) {
    	
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setPort(secrets.dbPort)
            .setHost(secrets.dbHost)
            .setDatabase(secrets.dbName)
            .setUser(secrets.dbUser)
            .setPassword(secrets.dbPassword);
           

		if (useSSL) {
			connectOptions.setSslMode(io.vertx.pgclient.SslMode.REQUIRE);
			connectOptions.setTrustAll(true);
		}

        PoolOptions poolOptions = new PoolOptions().setMaxSize(10);

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
                System.err.println("✗ Database init failed: " + res.cause().getMessage());
                res.cause().printStackTrace();
            }
        });
    }

    public static SqlClient getClient() {
        return client;
    }

    private static void runMigrations(PgConnectOptions connectOptions) {
    	String sslSuffix = useSSL ? "?sslmode=require" : "";
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