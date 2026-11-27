package id.pkl.signingpqc.server;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.web.multipart.MultipartFile;

/** Pemeriksaan masukan bersama: berkas PDF yang bisa diolah dan waktu simulasi ISO-8601. */
public final class RequestInputs {

    private RequestInputs() {
    }

    /** Isi berkas bila PDF biasa (tidak terenkripsi) yang bisa dibaca; selain itu 400. */
    public static byte[] pdf(MultipartFile file) throws IOException {
        byte[] data = file.getBytes();
        if (!SigningService.looksLikePdf(data)) {
            throw ApiException.badRequest("Berkas bukan PDF");
        }
        try (PDDocument document = Loader.loadPDF(data)) {
            if (document.isEncrypted()) {
                throw ApiException.badRequest("PDF terenkripsi tidak didukung");
            }
        } catch (InvalidPasswordException e) {
            throw ApiException.badRequest("PDF terenkripsi tidak didukung");
        } catch (IOException e) {
            throw ApiException.badRequest("PDF tidak dapat dibaca: " + e.getMessage());
        }
        return data;
    }

    /** Waktu simulasi opsional, misalnya 2034-06-01T00:00:00Z; null bila kosong. */
    public static Instant time(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Format waktu tidak sah (pakai ISO-8601, misalnya 2034-06-01T00:00:00Z): "
                    + value);
        }
    }

    public static String outputName(MultipartFile file, String suffix) {
        String name = file.getOriginalFilename() == null ? "dokumen.pdf" : file.getOriginalFilename();
        return name.toLowerCase().endsWith(".pdf") ? name.substring(0, name.length() - 4) + suffix + ".pdf"
                : name + suffix + ".pdf";
    }
}
