package id.pkl.signingpqc.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Log aktivitas lokal perangkat (KF-35) di {@code <data-dir>/activity.json}: siapa, kapan, aktivitas apa, berkas apa,
 * dan hasilnya. Berlaku untuk semua akun, terbaru di depan, maksimal {@value #MAX_STORED} entri. Tidak pernah memuat
 * password, passphrase, kode TOTP, token, atau isi dokumen.
 */
@Component
public class ActivityLog {

    static final int MAX_STORED = 1000;
    static final int MAX_RETURNED = 200;

    public static final String SUCCESS = "BERHASIL";
    public static final String FAILED = "GAGAL";
    /** Pelaku untuk panggilan REST tanpa sesi akun (skrip uji, Postman). */
    public static final String DEVELOPER = "API pengembang";
    public static final String UNKNOWN = "(tidak dikenal)";

    private static final Logger LOG = LoggerFactory.getLogger(ActivityLog.class);

    /**
     * @param result {@link #SUCCESS}, {@link #FAILED}, atau status verifikasi (SAH, TIDAK_SAH, ...)
     * @param detail keterangan singkat, misalnya pesan galat atau nama berkas keluaran
     */
    public record Entry(Instant time, String actor, String action, String fileName, String result, String detail) {
    }

    @FunctionalInterface
    public interface Action<T> {
        T run() throws IOException, GeneralSecurityException;
    }

    private final Path file;
    private final JsonMapper json;

    public ActivityLog(@Value("${signingpqc.data-dir}") Path dataDir, JsonMapper json) {
        this.file = dataDir.resolve("activity.json");
        this.json = json;
    }

    public void add(String actor, String action, String fileName, String result, String detail) {
        Entry entry = new Entry(Instant.now(), actor == null ? UNKNOWN : actor, action, baseName(fileName), result,
                detail);
        try {
            write(entry);
        } catch (IOException | RuntimeException e) {
            // Log aktivitas tidak boleh menggagalkan tanda tangan atau verifikasi yang sudah berhasil.
            LOG.warn("Log aktivitas tidak bisa ditulis: {}", e.getMessage());
        }
    }

    /** Menjalankan aksi, lalu mencatat hasilnya, baik berhasil maupun gagal (pesan galat menjadi detail). */
    public <T> T track(String actor, String action, String fileName, Function<T, String> result,
            Function<T, String> detail, Action<T> call) throws IOException, GeneralSecurityException {
        T value;
        try {
            value = call.run();
        } catch (ApiException | IllegalArgumentException e) {
            add(actor, action, fileName, FAILED, e.getMessage());
            throw e;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            add(actor, action, fileName, FAILED, "Galat sistem");
            throw e;
        }
        add(actor, action, fileName, result == null ? SUCCESS : result.apply(value),
                detail == null ? null : detail.apply(value));
        return value;
    }

    public synchronized List<Entry> latest() {
        List<Entry> entries = read();
        return entries.subList(0, Math.min(MAX_RETURNED, entries.size()));
    }

    private synchronized void write(Entry entry) throws IOException {
        List<Entry> entries = new ArrayList<>(read());
        entries.addFirst(entry);
        if (entries.size() > MAX_STORED) {
            entries = new ArrayList<>(entries.subList(0, MAX_STORED));
        }
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "activity", ".tmp");
        json.writeValue(temp.toFile(), entries);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private List<Entry> read() {
        if (!Files.exists(file)) {
            return List.of();
        }
        return Arrays.asList(json.readValue(file.toFile(), Entry[].class));
    }

    static String baseName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.substring(name.replace('\\', '/').lastIndexOf('/') + 1);
    }
}
