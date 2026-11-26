package id.pkl.signingpqc.server.account;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class AccountCryptoTest {

    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void argon2idHashDanVerifikasi() {
        String hash = Argon2id.hash("rahasia-uji-123".toCharArray());
        assertTrue(hash.startsWith("argon2id$65536$3$1$"), hash);
        assertTrue(Argon2id.verify("rahasia-uji-123".toCharArray(), hash));
        assertFalse(Argon2id.verify("rahasia-uji-124".toCharArray(), hash));
        assertNotEquals(hash, Argon2id.hash("rahasia-uji-123".toCharArray()));
        assertEquals(32, Argon2id.deriveKey("x".toCharArray(), new byte[16]).length);
    }

    @Test
    void aeadBolakBalikDanTamper() throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        byte[] plain = "isi rahasia".getBytes(StandardCharsets.UTF_8);
        byte[] aad = "keys:a1".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = Aead.seal(key, plain, aad);
        assertArrayEquals(plain, Aead.open(key, sealed, aad));
        byte[] tampered = sealed.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> Aead.open(key, tampered, aad));
        assertThrows(GeneralSecurityException.class,
                () -> Aead.open(key, sealed, "keys:a2".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void totpVektorRfc6238() {
        assertEquals("287082", Totp.code(RFC_SECRET, 59L / 30));
        assertEquals("081804", Totp.code(RFC_SECRET, 1111111109L / 30));
        assertEquals("005924", Totp.code(RFC_SECRET, 1234567890L / 30));
        assertEquals("279037", Totp.code(RFC_SECRET, 2000000000L / 30));
    }

    @Test
    void totpJendelaDanTanpaPemakaianUlang() {
        long n = 1234567890L / 30;
        Instant now = Instant.ofEpochSecond(n * 30 + 10);
        assertEquals(n - 1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, n - 1), now, 0).getAsLong());
        assertEquals(n, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, n), now, 0).getAsLong());
        assertEquals(n + 1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, n + 1), now, 0).getAsLong());
        assertTrue(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, n + 2), now, 0).isEmpty());
        assertTrue(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, n), now, n).isEmpty());
    }

    @Test
    void base32Rfc4648() {
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void uriOtpauth() {
        assertEquals("otpauth://totp/TTE%20Uji:budi.uji?secret=" + Base32.encode(RFC_SECRET)
                + "&issuer=TTE%20Uji&algorithm=SHA1&digits=6&period=30", Totp.uri("TTE Uji", "budi.uji", RFC_SECRET));
    }
}
