package id.pkl.signingpqc.server.account;

import java.time.Instant;

/**
 * Akun pengguna aplikasi. Tidak memuat rahasia dalam bentuk terbuka: password hanya sebagai hash Argon2id, kunci dan
 * rahasia TOTP ada di berkas terenkripsi terpisah.
 */
public record Account(String id, String username, String email, String passwordHash, String signerId,
        ConfirmMethod confirmMethod, Appearance appearance, String outputDir, Instant createdAt, long lastTotpStep) {

    public enum ConfirmMethod {
        PASSPHRASE, TOTP
    }

    public enum Appearance {
        VISIBLE, INVISIBLE
    }

    public Account withConfirmMethod(ConfirmMethod value) {
        return new Account(id, username, email, passwordHash, signerId, value, appearance, outputDir, createdAt,
                lastTotpStep);
    }

    public Account withAppearance(Appearance value) {
        return new Account(id, username, email, passwordHash, signerId, confirmMethod, value, outputDir, createdAt,
                lastTotpStep);
    }

    public Account withOutputDir(String value) {
        return new Account(id, username, email, passwordHash, signerId, confirmMethod, appearance, value, createdAt,
                lastTotpStep);
    }

    public Account withLastTotpStep(long value) {
        return new Account(id, username, email, passwordHash, signerId, confirmMethod, appearance, outputDir, createdAt,
                value);
    }
}
