package org.werkpages.service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption with HMAC-SHA256 blind-index support.
 *
 * encrypt/decrypt: AES-256-GCM with a random 12-byte IV prepended to the ciphertext.
 * hmac: deterministic HMAC-SHA256 over the lowercased, trimmed input — used as a
 *       blind index so encrypted email values can still be checked for uniqueness.
 *
 * decrypt() is backward-compatible: if the input is not valid Base64 or is too
 * short to contain an IV, it is returned as-is (plaintext fallback for legacy rows
 * during the transition period).
 */
public class EncryptionService {

    private static final int GCM_IV_LEN  = 12;
    private static final int GCM_TAG_LEN = 128;

    private final SecretKey aesKey;
    private final SecretKey hmacKey;
    private final SecureRandom rng = new SecureRandom();

    public EncryptionService(byte[] aesKeyBytes, byte[] hmacKeyBytes) {
        this.aesKey  = new SecretKeySpec(aesKeyBytes,  "AES");
        this.hmacKey = new SecretKeySpec(hmacKeyBytes, "HmacSHA256");
    }

    /** Creates an EncryptionService from base64-encoded key strings (loaded from Secrets Manager). */
    public static EncryptionService from(String aesKeyBase64, String hmacKeyBase64) {
        return new EncryptionService(
            Base64.getDecoder().decode(aesKeyBase64),
            Base64.getDecoder().decode(hmacKeyBase64)
        );
    }

    /** Fixed-key instance for integration tests — never use in production. */
    public static EncryptionService forTest() {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) i;
        return new EncryptionService(key, key);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            rng.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt}.
     * If the value is not valid Base64 or is too short to be an encrypted blob,
     * returns the input unchanged — allowing plaintext legacy rows to pass through.
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length <= GCM_IV_LEN) return ciphertext; // not encrypted
            byte[] iv = new byte[GCM_IV_LEN];
            byte[] ct = new byte[combined.length - GCM_IV_LEN];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LEN);
            System.arraycopy(combined, GCM_IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LEN, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return ciphertext; // plaintext fallback for legacy rows
        }
    }

    /** HMAC-SHA256 over the normalised value — used as a searchable blind index for email. */
    public String hmac(String value) {
        if (value == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            byte[] hash = mac.doFinal(value.toLowerCase().trim().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC failed", e);
        }
    }
}
