package id.pkl.signingpqc.server.account;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM dengan IV 12 byte acak di depan ciphertext. */
public final class Aead {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Aead() {
    }

    public static byte[] seal(byte[] key, byte[] plaintext, byte[] aad) throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(aad);
        byte[] body = cipher.doFinal(plaintext);
        byte[] out = Arrays.copyOf(iv, IV_BYTES + body.length);
        System.arraycopy(body, 0, out, IV_BYTES, body.length);
        return out;
    }

    public static byte[] open(byte[] key, byte[] sealed, byte[] aad) throws GeneralSecurityException {
        if (sealed == null || sealed.length < IV_BYTES + TAG_BITS / 8) {
            throw new GeneralSecurityException("Data terenkripsi rusak");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES));
        cipher.updateAAD(aad);
        return cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
    }
}
