package org.ratemymanager.config;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;
import io.vertx.core.json.JsonObject;

/**
 * Loads all app secrets either from environment variables (local dev)
 * or from AWS Secrets Manager (production).
 *
 * Local dev: set environment variables (see .env.example)
 * Production: set USE_AWS_SECRETS=true and ensure IAM role is attached
 */
public class SecretsConfig {

    // Database
    public final String dbHost;
    public final int    dbPort;
    public final String dbName;
    public final String dbUser;
    public final String dbPassword;

    // Auth0
    public final String auth0Domain;
    public final String auth0ClientId;
    public final String auth0ClientSecret;
    public final String auth0Audience;

    // Cloudflare Turnstile (optional — null disables CAPTCHA verification)
    public final String turnstileSecretKey;

    private SecretsConfig(
        String dbHost, int dbPort, String dbName, String dbUser, String dbPassword,
        String auth0Domain, String auth0ClientId, String auth0ClientSecret, String auth0Audience,
        String turnstileSecretKey
    ) {
        this.dbHost              = dbHost;
        this.dbPort              = dbPort;
        this.dbName              = dbName;
        this.dbUser              = dbUser;
        this.dbPassword          = dbPassword;
        this.auth0Domain         = auth0Domain;
        this.auth0ClientId       = auth0ClientId;
        this.auth0ClientSecret   = auth0ClientSecret;
        this.auth0Audience       = auth0Audience;
        this.turnstileSecretKey  = turnstileSecretKey;
    }

    /**
     * Loads secrets from environment variables if USE_AWS_SECRETS is not set,
     * otherwise fetches from AWS Secrets Manager.
     */
    public static SecretsConfig load() {
        boolean useAws = "true".equalsIgnoreCase(System.getenv("USE_AWS_SECRETS"));

        if (useAws) {
            System.out.println("Loading secrets from AWS Secrets Manager...");
            return loadFromAws();
        } else {
            System.out.println("Loading secrets from environment variables...");
            return loadFromEnv();
        }
    }

    // ── Load from environment variables (local dev) ───────────────────────────

    private static SecretsConfig loadFromEnv() {
        String dbHost            = requireEnv("DB_HOST");
        int    dbPort            = Integer.parseInt(getEnv("DB_PORT", "5432"));
        String dbName            = requireEnv("DB_NAME");
        String dbUser            = requireEnv("DB_USER");
        String dbPassword        = requireEnv("DB_PASSWORD");
        String auth0Domain       = requireEnv("AUTH0_DOMAIN");
        String auth0ClientId     = requireEnv("AUTH0_CLIENT_ID");
        String auth0ClientSecret = requireEnv("AUTH0_CLIENT_SECRET");
        String auth0Audience     = requireEnv("AUTH0_AUDIENCE");
        // Optional — dev can leave unset to skip CAPTCHA verification
        String turnstileSecretKey = getEnv("TURNSTILE_SECRET_KEY", null);

        System.out.println("✓ Secrets loaded from environment variables");

        return new SecretsConfig(
            dbHost, dbPort, dbName, dbUser, dbPassword,
            auth0Domain, auth0ClientId, auth0ClientSecret, auth0Audience,
            turnstileSecretKey
        );
    }

    // ── Load from AWS Secrets Manager (production) ────────────────────────────

    private static SecretsConfig loadFromAws() {
        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.CA_CENTRAL_1)
                .build();

            JsonObject dbSecret    = fetchSecret(client, "ratemymanagers/prod/db");
            JsonObject auth0Secret = fetchSecret(client, "ratemymanagers/prod/auth0");

            client.close();

            String dbHost            = dbSecret.getString("host");
            int    dbPort            = Integer.parseInt(dbSecret.getString("port", "5432"));
            String dbName            = dbSecret.getString("dbname");
            String dbUser            = dbSecret.getString("username");
            String dbPassword        = dbSecret.getString("password");
            String auth0Domain        = auth0Secret.getString("domain");
            String auth0ClientId      = auth0Secret.getString("client_id");
            String auth0ClientSecret  = auth0Secret.getString("client_secret");
            String auth0Audience      = auth0Secret.getString("audience");
            // Optional — absent key in the secret means CAPTCHA disabled
            String turnstileSecretKey = auth0Secret.getString("turnstile_secret_key");

            System.out.println("✓ Secrets loaded from AWS Secrets Manager");

            return new SecretsConfig(
                dbHost, dbPort, dbName, dbUser, dbPassword,
                auth0Domain, auth0ClientId, auth0ClientSecret, auth0Audience,
                turnstileSecretKey
            );
        } catch (SecretsManagerException e) {
            throw new RuntimeException("Failed to load secrets from AWS Secrets Manager: " + e.getMessage(), e);
        }
    }

    private static JsonObject fetchSecret(SecretsManagerClient client, String secretName) {
        String value = client.getSecretValue(
            GetSecretValueRequest.builder().secretId(secretName).build()
        ).secretString();
        return new JsonObject(value);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new RuntimeException("Required environment variable not set: " + name);
        }
        return value;
    }

    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}