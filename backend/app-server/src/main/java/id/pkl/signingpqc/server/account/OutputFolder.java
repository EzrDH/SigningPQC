package id.pkl.signingpqc.server.account;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import id.pkl.signingpqc.server.ApiException;

/** Menyimpan hasil ke folder keluaran tanpa menimpa: nama-ttd.pdf, nama-ttd-2.pdf, dan seterusnya. */
public final class OutputFolder {

    private OutputFolder() {
    }

    static final String INSIDE_DATA_DIR = "Folder keluaran tidak boleh berada di dalam folder data aplikasi";

    /**
     * Menolak folder keluaran yang (lewat path nyata, termasuk tautan simbolik atau junction) berada di dalam folder
     * data. Dipanggil saat menyimpan, karena folder yang sah ketika Pengaturan disimpan bisa diganti tautan sesudahnya
     * (tindak lanjut T-016).
     */
    public static void requireOutside(Path dir, Path dataDir) {
        if (inside(dir, dataDir)) {
            throw ApiException.conflict(INSIDE_DATA_DIR + ". Ubah lokasi penyimpanan di Pengaturan.");
        }
    }

    static boolean inside(Path folder, Path dataDir) {
        return realOrNormal(folder.toAbsolutePath()).startsWith(realOrNormal(dataDir.toAbsolutePath()));
    }

    /** Path nyata untuk bagian yang sudah ada, ditambah sisa path yang belum ada. */
    static Path realOrNormal(Path path) {
        Path normal = path.normalize();
        Path existing = normal;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return normal;
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(normal));
        } catch (IOException e) {
            return normal;
        }
    }

    public static Path save(Path dir, Path dataDir, String originalName, String suffix, byte[] data)
            throws IOException {
        requireOutside(dir, dataDir);
        Files.createDirectories(dir);
        requireOutside(dir, dataDir);
        String base = baseName(originalName);
        for (int n = 1; n < 10_000; n++) {
            Path target = dir.resolve(base + suffix + (n == 1 ? "" : "-" + n) + ".pdf");
            try {
                Files.write(target, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return target.toAbsolutePath();
            } catch (FileAlreadyExistsException e) {
                // coba nomor berikutnya
            }
        }
        throw new IOException("Terlalu banyak berkas bernama sama di " + dir);
    }

    static String baseName(String originalName) {
        String name = originalName == null || originalName.isBlank() ? "dokumen" : originalName.strip();
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.toLowerCase().endsWith(".pdf")) {
            name = name.substring(0, name.length() - 4);
        }
        name = name.replaceAll("[^\\p{L}\\p{N} ._-]", "_").strip();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        return name.isEmpty() ? "dokumen" : name;
    }
}
