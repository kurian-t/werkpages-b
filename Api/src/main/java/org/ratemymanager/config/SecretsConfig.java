package org.ratemymanager.config;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import io.vertx.core.json.JsonObject;

/**
 * Fetches all app secrets from AWS Secrets Manager at startup.
 * Call SecretsConfig.load() once before initializing anything else.
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

    private SecretsConfig(
        String dbHost, int dbPort, String dbName, String dbUser, String dbPassword,
        String auth0Domain, String auth0ClientId, String auth0ClientSecret, String auth0Audience
    ) {
        this.dbHost           = dbHost;
        this.dbPort           = dbPort;
        this.dbName           = dbName;
        this.dbUser           = dbUser;
        this.dbPassword       = dbPassword;
        this.auth0Domain      = auth0Domain;
        this.auth0ClientId    = auth0ClientId;
        this.auth0ClientSecret = auth0ClientSecret;
        this.auth0Audience    = auth0Audience;
    }

    /**
     * Fetches secrets synchronously from AWS Secrets Manager.
     * This is intentionally blocking — call it once at startup before Vert.x starts.
     */
    public static SecretsConfig load() {
        SecretsManagerClient client = SecretsManagerClient.builder()
            .region(Region.CA_CENTRAL_1)
            .build();

        // Fetch DB secret
        JsonObject dbSecret = fetchSecret(client, "ratemymanagers/prod/db");
        String dbHost     = dbSecret.getString("host");
        int    dbPort     = Integer.parseInt(dbSecret.getString("port", "5432"));
        String dbName     = dbSecret.getString("dbname");
        String dbUser     = dbSecret.getString("username");
        String dbPassword = dbSecret.getString("password");

        // Fetch Auth0 secret
        JsonObject auth0Secret = fetchSecret(client, "ratemymanagers/prod/auth0");
        String auth0Domain       = auth0Secret.getString("domain");
        String auth0ClientId     = auth0Secret.getString("client_id");
        String auth0ClientSecret = auth0Secret.getString("client_secret");
        String auth0Audience     = auth0Secret.getString("audience");

        client.close();

        System.out.println("✓ Secrets loaded from AWS Secrets Manager");

        return new SecretsConfig(
            dbHost, dbPort, dbName, dbUser, dbPassword,
            auth0Domain, auth0ClientId, auth0ClientSecret, auth0Audience
        );
    }

    private static JsonObject fetchSecret(SecretsManagerClient client, String secretName) {
        String value = client.getSecretValue(
            GetSecretValueRequest.builder().secretId(secretName).build()
        ).secretString();
        return new JsonObject(value);
    }
}