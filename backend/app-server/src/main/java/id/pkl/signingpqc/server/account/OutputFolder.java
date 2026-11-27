package id.pkl.signingpqc.server.account;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Menyimpan hasil ke folder keluaran tanpa menimpa: nama-ttd.pdf, nama-ttd-2.pdf, dan seterusnya. */
public final class OutputFolder {

    private OutputFolder() {
    }

    public static Path save(Path dir, String originalName, String suffix, byte[] data) throws IOException {
        Files.createDirectories(dir);
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
