package org.werkpages.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EncryptionServiceTest {

    private EncryptionService enc;

    @BeforeEach
    void setUp() {
        enc = EncryptionService.forTest();
    }

    // ── encrypt / decrypt roundtrip ───────────────────────────────────────────

    @Test
    void encryptDecrypt_roundtrip() {
        String plaintext = "user@example.com";
        String ciphertext = enc.encrypt(plaintext);
        assertEquals(plaintext, enc.decrypt(ciphertext));
    }

    @Test
    void encryptDecrypt_emptyString() {
        String ciphertext = enc.encrypt("");
        assertEquals("", enc.decrypt(ciphertext));
    }

    @Test
    void encryptDecrypt_longValue() {
        String plaintext = "a".repeat(1000);
        assertEquals(plaintext, enc.decrypt(enc.encrypt(plaintext)));
    }

    @Test
    void encryptDecrypt_specialChars() {
        String plaintext = "user+tag@example.co.uk";
        assertEquals(plaintext, enc.decrypt(enc.encrypt(plaintext)));
    }

    @Test
    void encryptDecrypt_unicodeChars() {
        String plaintext = "renée@société.fr";
        assertEquals(plaintext, enc.decrypt(enc.encrypt(plaintext)));
    }

    // ── null handling ─────────────────────────────────────────────────────────

    @Test
    void encrypt_null_returnsNull() {
        assertNull(enc.encrypt(null));
    }

    @Test
    void decrypt_null_returnsNull() {
        assertNull(enc.decrypt(null));
    }

    @Test
    void hmac_null_returnsNull() {
        assertNull(enc.hmac(null));
    }

    // ── ciphertext properties ─────────────────────────────────────────────────

    @Test
    void encrypt_outputDiffersFromInput() {
        String plaintext = "user@example.com";
        assertNotEquals(plaintext, enc.encrypt(plaintext));
    }

    @Test
    void encrypt_sameValueTwice_producesDifferentCiphertexts() {
        // Random IV means each encryption is unique
        String plaintext = "user@example.com";
        String ct1 = enc.encrypt(plaintext);
        String ct2 = enc.encrypt(plaintext);
        assertNotEquals(ct1, ct2, "Two encryptions of the same value should differ (random IV)");
    }

    @Test
    void bothCiphertexts_decryptToSamePlaintext() {
        String plaintext = "user@example.com";
        String ct1 = enc.encrypt(plaintext);
        String ct2 = enc.encrypt(plaintext);
        assertEquals(plaintext, enc.decrypt(ct1));
        assertEquals(plaintext, enc.decrypt(ct2));
    }

    // ── legacy plaintext passthrough ──────────────────────────────────────────

    @Test
    void decrypt_plaintextEmail_returnedUnchanged() {
        // Legacy rows stored plaintext emails before encryption was introduced.
        // decrypt() must pass them through unchanged.
        String legacyPlaintext = "user@example.com";
        assertEquals(legacyPlaintext, enc.decrypt(legacyPlaintext));
    }

    @Test
    void decrypt_randomShortString_returnedUnchanged() {
        // Strings that decode from base64 but are too short to contain an IV are returned as-is
        String short64 = "dGVzdA=="; // "test" in base64, length 4 < 12 byte IV
        assertEquals(short64, enc.decrypt(short64));
    }

    @Test
    void decrypt_invalidBase64_returnedUnchanged() {
        // Strings that cannot be decoded from base64 should be returned as-is
        String notBase64 = "not-valid-base64!!!";
        assertEquals(notBase64, enc.decrypt(notBase64));
    }

    // ── HMAC determinism ──────────────────────────────────────────────────────

    @Test
    void hmac_samePlaintext_sameResult() {
        String h1 = enc.hmac("user@example.com");
        String h2 = enc.hmac("user@example.com");
        assertEquals(h1, h2, "HMAC must be deterministic");
    }

    @Test
    void hmac_differentValues_differentResults() {
        String h1 = enc.hmac("user1@example.com");
        String h2 = enc.hmac("user2@example.com");
        assertNotEquals(h1, h2);
    }

    @Test
    void hmac_notEmpty() {
        assertFalse(enc.hmac("user@example.com").isBlank());
    }

    // ── HMAC normalization: lowercase + trim ──────────────────────────────────

    @Test
    void hmac_caseInsensitive() {
        assertEquals(enc.hmac("user@example.com"), enc.hmac("USER@EXAMPLE.COM"));
        assertEquals(enc.hmac("User@Example.Com"), enc.hmac("user@example.com"));
    }

    @Test
    void hmac_trimsWhitespace() {
        assertEquals(enc.hmac("user@example.com"), enc.hmac("  user@example.com  "));
        assertEquals(enc.hmac("user@example.com"), enc.hmac("\tuser@example.com\n"));
    }

    @Test
    void hmac_caseAndTrimCombined() {
        assertEquals(enc.hmac("user@example.com"), enc.hmac("  USER@EXAMPLE.COM  "));
    }

    // ── from() factory ────────────────────────────────────────────────────────

    @Test
    void from_base64Keys_worksCorrectly() {
        // 32-byte keys encoded as base64
        byte[] aesKey  = new byte[32];
        byte[] hmacKey = new byte[32];
        for (int i = 0; i < 32; i++) { aesKey[i] = (byte)(i + 1); hmacKey[i] = (byte)(i + 10); }

        String aesB64  = java.util.Base64.getEncoder().encodeToString(aesKey);
        String hmacB64 = java.util.Base64.getEncoder().encodeToString(hmacKey);

        EncryptionService enc2 = EncryptionService.from(aesB64, hmacB64);
        String plaintext = "test@email.com";
        assertEquals(plaintext, enc2.decrypt(enc2.encrypt(plaintext)));
    }

    // ── cross-instance consistency (HMAC only — encrypt is not cross-instance) ─

    @Test
    void hmac_sameKeysProduceSameHmac() {
        // Two instances with the same keys should produce identical HMACs
        EncryptionService enc2 = EncryptionService.forTest();
        assertEquals(enc.hmac("user@example.com"), enc2.hmac("user@example.com"));
    }
}
