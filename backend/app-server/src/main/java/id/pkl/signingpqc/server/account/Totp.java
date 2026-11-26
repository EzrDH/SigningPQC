package id.pkl.signingpqc.server.account;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** TOTP RFC 6238: HMAC-SHA1, 6 digit, periode 30 detik, jendela ±1 langkah. */
public final class Totp {

    public static final int PERIOD_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int WINDOW = 1;

    private Totp() {
    }

    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 tidak tersedia", e);
        }
    }

    /** Langkah yang cocok (dalam ±1 dan lebih besar dari {@code lastUsedStep}), atau kosong. */
    public static OptionalLong verify(byte[] secret, String code, Instant now, long lastUsedStep) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        long current = Math.floorDiv(now.getEpochSecond(), PERIOD_SECONDS);
        byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            if (step > lastUsedStep
                    && MessageDigest.isEqual(given, code(secret, step).getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    public static String uri(String issuer, String label, byte[] secret) {
        String i = encode(issuer);
        return "otpauth://totp/" + i + ":" + encode(label) + "?secret=" + Base32.encode(secret) + "&issuer=" + i
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
