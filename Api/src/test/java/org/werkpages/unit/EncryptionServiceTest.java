package org.werkpages.unit;

import org.junit.jupiter.api.Test;
import org.werkpages.service.EncryptionService;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class EncryptionServiceTest {

    private static final EncryptionService svc = EncryptionService.forTest();

    @Test
    void encryptNull_returnsNull() {
        assertNull(svc.encrypt(null));
    }

    @Test
    void decryptNull_returnsNull() {
        assertNull(svc.decrypt(null));
    }

    @Test
    void hmacNull_returnsNull() {
        assertNull(svc.hmac(null));
    }

    @Test
    void encryptDecrypt_roundtrip() {
        String plaintext = "hello@example.com";
        assertEquals(plaintext, svc.decrypt(svc.encrypt(plaintext)));
    }

    @Test
    void encrypt_randomIv_producedDifferentCiphertexts() {
        String plaintext = "same-input";
        String first = svc.encrypt(plaintext);
        String second = svc.encrypt(plaintext);
        assertNotEquals(first, second);
    }

    @Test
    void decrypt_shortBase64_returnedAsIs() {
        // A 12-byte-or-fewer decoded blob is treated as not-encrypted and returned unchanged.
        String shortBase64 = Base64.getEncoder().encodeToString(new byte[8]);
        assertEquals(shortBase64, svc.decrypt(shortBase64));
    }

    @Test
    void decrypt_plainNonBase64_returnedAsIs() {
        String plain = "hello@example.com";
        assertEquals(plain, svc.decrypt(plain));
    }

    @Test
    void hmac_isDeterministic() {
        String input = "user@example.com";
        assertEquals(svc.hmac(input), svc.hmac(input));
    }

    @Test
    void hmac_normalisesCase() {
        assertEquals(svc.hmac("TEST@EXAMPLE.COM"), svc.hmac("test@example.com"));
    }

    @Test
    void hmac_normalisesTrim() {
        assertEquals(svc.hmac("  user@example.com  "), svc.hmac("user@example.com"));
    }

    @Test
    void from_factory_roundtrip() {
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < 32; i++) keyBytes[i] = (byte) i;
        String b64 = Base64.getEncoder().encodeToString(keyBytes);
        EncryptionService fromSvc = EncryptionService.from(b64, b64);
        String plaintext = "roundtrip@test.com";
        assertEquals(plaintext, fromSvc.decrypt(fromSvc.encrypt(plaintext)));
    }

    @Test
    void forTest_factory_roundtrip() {
        EncryptionService forTestSvc = EncryptionService.forTest();
        String plaintext = "fortest@test.com";
        assertEquals(plaintext, forTestSvc.decrypt(forTestSvc.encrypt(plaintext)));
    }
}
