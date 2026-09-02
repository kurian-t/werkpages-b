package org.werkpages.tools;

import org.werkpages.service.EncryptionService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One-time script to encrypt existing plaintext email, first_name, and last_name
 * values for all users that pre-date the V25 migration.
 *
 * Run after deploying V25 and before removing the plaintext columns (V26).
 *
 * Required environment variables (same as the app):
 *   DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD
 *   ENCRYPTION_KEY  (base64-encoded 32-byte AES key)
 *   HMAC_KEY        (base64-encoded 32-byte HMAC key)
 *
 * Run:
 *   mvn compile -pl Api -am -q
 *   java -cp Api/target/classes:$(find ~/.m2 -name "postgresql-*.jar" | head -1) \
 *        org.werkpages.tools.BackfillEncryption
 *
 * Or via Maven exec plugin (see below).
 */
public class BackfillEncryption {

    public static void main(String[] args) throws Exception {
        String encKey = require("ENCRYPTION_KEY");
        String hmacKey = require("HMAC_KEY");
        EncryptionService enc = EncryptionService.from(encKey, hmacKey);

        String host     = require("DB_HOST");
        int    port     = Integer.parseInt(getEnv("DB_PORT", "5432"));
        String dbName   = require("DB_NAME");
        String user     = require("DB_USER");
        String password = require("DB_PASSWORD");

        String url = "jdbc:postgresql://" + host + ":" + port + "/" + dbName;
        System.out.println("Connecting to " + host + ":" + port + "/" + dbName + " ...");

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(false);

            // Find all rows that still have plaintext email (not yet backfilled)
            List<Object[]> toBackfill = new ArrayList<>();
            try (PreparedStatement sel = conn.prepareStatement(
                    "SELECT id, email, first_name, last_name FROM users " +
                    "WHERE email_encrypted IS NULL AND email IS NOT NULL")) {
                ResultSet rs = sel.executeQuery();
                while (rs.next()) {
                    toBackfill.add(new Object[]{
                        rs.getObject("id"),          // UUID
                        rs.getString("email"),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                    });
                }
            }

            System.out.println("Found " + toBackfill.size() + " row(s) to backfill.");
            if (toBackfill.isEmpty()) {
                System.out.println("Nothing to do.");
                return;
            }

            int updated = 0;
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE users SET " +
                    "  email_encrypted      = ?, " +
                    "  email_hash           = ?, " +
                    "  first_name_encrypted = ?, " +
                    "  last_name_encrypted  = ?, " +
                    "  email      = NULL, " +
                    "  first_name = NULL, " +
                    "  last_name  = NULL  " +
                    "WHERE id = ?")) {

                for (Object[] row : toBackfill) {
                    UUID   id        = (UUID)   row[0];
                    String email     =           (String) row[1];
                    String firstName = nullToEmpty((String) row[2]);
                    String lastName  = nullToEmpty((String) row[3]);

                    upd.setString(1, enc.encrypt(email));
                    upd.setString(2, enc.hmac(email));
                    upd.setString(3, enc.encrypt(firstName));
                    upd.setString(4, enc.encrypt(lastName));
                    upd.setObject(5, id);
                    upd.addBatch();
                    updated++;

                    if (updated % 100 == 0) {
                        System.out.println("  Batching row " + updated + " / " + toBackfill.size() + " ...");
                    }
                }

                upd.executeBatch();
            }

            conn.commit();
            System.out.println("✓ Backfill complete — " + updated + " row(s) encrypted.");
            System.out.println("  You can now deploy V26 to drop the plaintext columns when ready.");
        }
    }

    private static String nullToEmpty(String s) { return s != null ? s : ""; }

    private static String require(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) throw new RuntimeException("Required env var not set: " + name);
        return v;
    }

    private static String getEnv(String name, String def) {
        return org.werkpages.service.Fields.env(name, def);
    }
}
