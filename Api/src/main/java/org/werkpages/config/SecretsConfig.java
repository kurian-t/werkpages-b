package org.werkpages.config;

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
    public final String auth0Domain;       // original tenant domain (e.g. werkpages.com.auth0.com)
    public final String auth0CustomDomain; // custom domain when set (e.g. auth.werkpages.com); null if not configured
    public final String auth0ClientId;
    public final String auth0ClientSecret;
    public final String auth0Audience;

    /** Returns the domain to use for auth flows (JWKS, signup, signin). Prefers custom domain. */
    public String effectiveAuthDomain() {
        return (auth0CustomDomain != null && !auth0CustomDomain.isBlank()) ? auth0CustomDomain : auth0Domain;
    }

    // Cloudflare Turnstile (optional — null disables CAPTCHA verification)
    public final String turnstileSecretKey;

    // PII encryption — base64-encoded 32-byte keys
    // Optional in dev (null = no encryption); required in production
    public final String encryptionKey; // AES-256 key for email / name encryption
    public final String hmacKey;       // HMAC-SHA256 key for email blind index

    // Anthropic API key for AI-powered manager deduplication
    public final String anthropicApiKey;

    // Static secret for authenticating the hourly deduplication cron job
    public final String cronSecret;

    private SecretsConfig(
        String dbHost, int dbPort, String dbName, String dbUser, String dbPassword,
        String auth0Domain, String auth0CustomDomain,
        String auth0ClientId, String auth0ClientSecret, String auth0Audience,
        String turnstileSecretKey,
        String encryptionKey, String hmacKey,
        String anthropicApiKey, String cronSecret
    ) {
        this.dbHost              = dbHost;
        this.dbPort              = dbPort;
        this.dbName              = dbName;
        this.dbUser              = dbUser;
        this.dbPassword          = dbPassword;
        this.auth0Domain         = auth0Domain;
        this.auth0CustomDomain   = auth0CustomDomain;
        this.auth0ClientId       = auth0ClientId;
        this.auth0ClientSecret   = auth0ClientSecret;
        this.auth0Audience       = auth0Audience;
        this.turnstileSecretKey  = turnstileSecretKey;
        this.encryptionKey       = encryptionKey;
        this.hmacKey             = hmacKey;
        this.anthropicApiKey     = anthropicApiKey;
        this.cronSecret          = cronSecret;
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
        String auth0Domain        = requireEnv("AUTH0_DOMAIN");
        String auth0CustomDomain  = getEnv("AUTH0_CUSTOM_DOMAIN", null); // optional
        String auth0ClientId      = requireEnv("AUTH0_CLIENT_ID");
        String auth0ClientSecret  = requireEnv("AUTH0_CLIENT_SECRET");
        String auth0Audience      = requireEnv("AUTH0_AUDIENCE");
        // Optional — dev can leave unset to skip CAPTCHA verification
        String turnstileSecretKey = getEnv("TURNSTILE_SECRET_KEY", null);
        // Optional in dev — null means PII is stored unencrypted (not safe for production)
        String encryptionKey = getEnv("ENCRYPTION_KEY", null);
        String hmacKey       = getEnv("HMAC_KEY", null);
        // Optional in dev — null disables the AI deduplication job
        String anthropicApiKey = getEnv("ANTHROPIC_API_KEY", null);
        // Optional in dev — null disables cron secret authentication
        String cronSecret = getEnv("CRON_SECRET", null);

        if (encryptionKey == null || hmacKey == null) {
            System.out.println("⚠ ENCRYPTION_KEY / HMAC_KEY not set — PII will NOT be encrypted (dev only)");
        }

        System.out.println("✓ Secrets loaded from environment variables");

        return new SecretsConfig(
            dbHost, dbPort, dbName, dbUser, dbPassword,
            auth0Domain, auth0CustomDomain,
            auth0ClientId, auth0ClientSecret, auth0Audience,
            turnstileSecretKey, encryptionKey, hmacKey, anthropicApiKey, cronSecret
        );
    }

    // ── Load from AWS Secrets Manager (production) ────────────────────────────

    private static SecretsConfig loadFromAws() {
        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.CA_CENTRAL_1)
                .build();

            JsonObject dbSecret    = fetchSecret(client, "werkpages/prod/db");
            JsonObject auth0Secret = fetchSecret(client, "werkpages/prod/auth0");

            client.close();

            String dbHost            = dbSecret.getString("host");
            int    dbPort            = Integer.parseInt(dbSecret.getString("port", "5432"));
            String dbName            = dbSecret.getString("dbname");
            String dbUser            = dbSecret.getString("username");
            String dbPassword        = dbSecret.getString("password");
            String auth0Domain        = auth0Secret.getString("domain");
            String auth0CustomDomain  = auth0Secret.getString("custom_domain"); // optional key
            String auth0ClientId      = auth0Secret.getString("client_id");
            String auth0ClientSecret  = auth0Secret.getString("client_secret");
            String auth0Audience      = auth0Secret.getString("audience");
            // Optional — absent key in the secret means CAPTCHA disabled
            String turnstileSecretKey = auth0Secret.getString("turnstile_secret_key");
            String encryptionKey      = auth0Secret.getString("encryption_key");
            String hmacKey            = auth0Secret.getString("hmac_key");
            String anthropicApiKey    = auth0Secret.getString("anthropic_api_key");
            String cronSecret         = auth0Secret.getString("cron_secret");

            System.out.println("✓ Secrets loaded from AWS Secrets Manager");

            return new SecretsConfig(
                dbHost, dbPort, dbName, dbUser, dbPassword,
                auth0Domain, auth0CustomDomain,
                auth0ClientId, auth0ClientSecret, auth0Audience,
                turnstileSecretKey, encryptionKey, hmacKey, anthropicApiKey, cronSecret
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