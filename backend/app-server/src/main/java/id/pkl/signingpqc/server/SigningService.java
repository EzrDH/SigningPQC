package id.pkl.signingpqc.server;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.stereotype.Service;

import id.pkl.signingpqc.core.PadesSigner;
import id.pkl.signingpqc.core.SignatureDetails;
import id.pkl.signingpqc.core.VisibleSignature;
import id.pkl.signingpqc.pki.PkiFamily;
import id.pkl.signingpqc.pki.SignerPair;

/**
 * Alur tanda tangan hybrid: (1) RSA visible, lalu (2) ML-DSA invisible yang menutup seluruh dokumen. Keduanya
 * B-T bila timestamp diaktifkan. Bila salah satu langkah gagal, seluruh proses dibatalkan dan tidak ada PDF.
 */
@Service
public class SigningService {

    /** Posisi kotak tanda tangan RSA; x/y null berarti kanan bawah halaman. Halaman mulai dari 1. */
    public record Placement(int page, Float x, Float y, Float width, Float height) {
    }

    private final PkiService pki;
    private final TsaService tsa;
    private final PadesSigner signer = new PadesSigner();

    public SigningService(PkiService pki, TsaService tsa) {
        this.pki = pki;
        this.tsa = tsa;
    }

    public byte[] signHybrid(byte[] pdf, String signerId, String reason, String location, Placement placement,
            boolean timestamp) throws IOException, GeneralSecurityException {
        SignerPair pair = pki.signer(signerId);
        PkiService.SignerRecord record = pki.signerRecord(signerId);
        if (record.revoked()) {
            throw ApiException.conflict("Sertifikat penanda tangan '" + signerId + "' sudah dicabut");
        }
        if (!looksLikePdf(pdf)) {
            throw ApiException.badRequest("Berkas bukan PDF");
        }
        VisibleSignature visible = visible(pdf, placement, record);
        SignatureDetails details = new SignatureDetails(record.commonName(),
                reason == null || reason.isBlank() ? "Persetujuan dokumen" : reason.strip(),
                location == null || location.isBlank() ? "Jakarta" : location.strip());

        byte[] afterRsa = signer.signVisible(pdf, pair.rsa(), details, visible,
                timestamp ? tsa.client(PkiFamily.RSA) : null);
        return signer.signInvisible(afterRsa, pair.mlDsa(), details, timestamp ? tsa.client(PkiFamily.ML_DSA) : null);
    }

    static boolean looksLikePdf(byte[] data) {
        return data != null && data.length >= 5
                && new String(data, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1).equals("%PDF-");
    }

    private static VisibleSignature visible(byte[] pdf, Placement placement, PkiService.SignerRecord record)
            throws IOException {
        List<String> extra = new ArrayList<>();
        String jabatan = String.join(", ", java.util.stream.Stream.of(record.title(), record.unit())
                .filter(v -> v != null && !v.isBlank()).toList());
        if (!jabatan.isBlank()) {
            extra.add(jabatan);
        }
        int pageIndex = placement == null ? 0 : placement.page() - 1;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (pageIndex < 0 || pageIndex >= document.getNumberOfPages()) {
                throw ApiException.badRequest("Halaman " + (pageIndex + 1) + " tidak ada (dokumen punya "
                        + document.getNumberOfPages() + " halaman)");
            }
            PDRectangle box = document.getPage(pageIndex).getMediaBox();
            float width = placement != null && placement.width() != null ? placement.width() : 220;
            float height = placement != null && placement.height() != null ? placement.height() : 90;
            float x = placement != null && placement.x() != null ? placement.x() : box.getWidth() - width - 40;
            float y = placement != null && placement.y() != null ? placement.y() : box.getHeight() - height - 40;
            return new VisibleSignature(pageIndex, x, y, width, height, extra);
        }
    }
}
