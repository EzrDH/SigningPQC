package id.pkl.signingpqc.server.account;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kunci aplikasi 256-bit di {@code <data-dir>/app.key}. Membungkus DEK akun yang memakai mode TOTP dan rahasia TOTP.
 * Dibuat sekali; izin berkas hanya pemilik bila sistem berkas mendukung POSIX.
 */
@Component
public class AppKey {

    private final Path file;
    private byte[] key;

    public AppKey(@Value("${signingpqc.data-dir}") Path dataDir) {
        this.file = dataDir.resolve("app.key");
    }

    public synchronized byte[] key() {
        if (key == null) {
            try {
                if (!Files.exists(file)) {
                    create();
                }
                byte[] loaded = Files.readAllBytes(file);
                if (loaded.length != 32) {
                    throw new IllegalStateException("Berkas kunci aplikasi rusak: " + file);
                }
                key = loaded;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return key.clone();
    }

    private void create() throws IOException {
        Files.createDirectories(file.getParent());
        byte[] fresh = new byte[32];
        new SecureRandom().nextBytes(fresh);
        try {
            Files.write(file, fresh, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            return;
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows: izin mengikuti folder profil pengguna.
        }
    }
}
