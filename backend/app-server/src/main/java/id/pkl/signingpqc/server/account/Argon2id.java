package id.pkl.signingpqc.server.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Argon2id (Bouncy Castle) untuk hash password dan kunci pembungkus dari passphrase. */
public final class Argon2id {

    static final int MEMORY_KIB = 65536;
    static final int ITERATIONS = 3;
    static final int PARALLELISM = 1;
    static final int SALT_BYTES = 16;
    static final int OUTPUT_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Argon2id() {
    }

    /** Hash berformat {@code argon2id$m$t$p$salt$hash} (base64 tanpa padding). */
    public static String hash(char[] secret) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] out = derive(secret, salt, MEMORY_KIB, ITERATIONS, PARALLELISM);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        String encoded = "argon2id$" + MEMORY_KIB + "$" + ITERATIONS + "$" + PARALLELISM + "$" + b64.encodeToString(salt)
                + "$" + b64.encodeToString(out);
        Arrays.fill(out, (byte) 0);
        return encoded;
    }

    public static boolean verify(char[] secret, String encoded) {
        String[] parts = encoded == null ? new String[0] : encoded.split("\\$");
        if (parts.length != 6 || !"argon2id".equals(parts[0])) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(parts[4]);
            byte[] expected = Base64.getDecoder().decode(parts[5]);
            byte[] actual = derive(secret, salt, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
            boolean equal = MessageDigest.isEqual(expected, actual);
            Arrays.fill(actual, (byte) 0);
            return equal;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Kunci 32 byte dari rahasia dan salt dengan parameter bawaan. */
    public static byte[] deriveKey(char[] secret, byte[] salt) {
        return derive(secret, salt, MEMORY_KIB, ITERATIONS, PARALLELISM);
    }

    private static byte[] derive(char[] secret, byte[] salt, int memory, int iterations, int parallelism) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memory)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] password = toUtf8(secret);
        byte[] out = new byte[OUTPUT_BYTES];
        try {
            generator.generateBytes(password, out);
        } finally {
            Arrays.fill(password, (byte) 0);
        }
        return out;
    }

    private static byte[] toUtf8(char[] chars) {
        java.nio.ByteBuffer buffer = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
        }
        return bytes;
    }
}
