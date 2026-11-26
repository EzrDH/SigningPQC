package id.pkl.signingpqc.server;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.stereotype.Service;

import id.pkl.signingpqc.core.PadesSigner;
import id.pkl.signingpqc.core.SignatureDetails;
import id.pkl.signingpqc.core.VisibleSignature;
import id.pkl.signingpqc.core.ltv.LtvService;
import id.pkl.signingpqc.core.ltv.TimestampProfile;
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

    /**
     * Tanda tangan untuk akun: RSA (visible bila {@code visibleOrNull} tidak null, selain itu invisible), lalu ML-DSA
     * invisible yang menutup dokumen, keduanya dengan signature timestamp; lalu data validasi (DSS) dan document
     * timestamp TSA RSA generasi 1, sehingga hasilnya PAdES B-LTA.
     */
    public byte[] signHybrid(byte[] pdf, SignerPair pair, PkiService.SignerRecord record, VisibleSignature visibleOrNull)
            throws IOException, GeneralSecurityException {
        if (!looksLikePdf(pdf)) {
            throw ApiException.badRequest("Berkas bukan PDF");
        }
        SignatureDetails details = new SignatureDetails(record.commonName(), "Persetujuan dokumen", "Jakarta");
        byte[] afterRsa = visibleOrNull != null
                ? signer.signVisible(pdf, pair.rsa(), details, visibleOrNull, tsa.client(PkiFamily.RSA))
                : signer.signInvisible(pdf, pair.rsa(), details, tsa.client(PkiFamily.RSA));
        byte[] signed = signer.signInvisible(afterRsa, pair.mlDsa(), details, tsa.client(PkiFamily.ML_DSA));
        signed = LtvService.addValidationData(signed, pki.revocationSource(Instant.now()));
        return LtvService.addDocumentTimestamp(signed, tsa.client("rsa-g1", null), TimestampProfile.RSA);
    }

    /**
     * Kotak tanda tangan di halaman {@code page} (mulai 1). Posisi dan ukuran dalam point dengan y dari atas, pada
     * koordinat halaman tanpa /Rotate (seperti {@link VisibleSignature}); yang kosong memakai bawaan 180 x 75 point di
     * kanan bawah halaman sebagaimana tampil, dengan margin 40.
     */
    public VisibleSignature placement(byte[] pdf, int page, Float x, Float y, Float width, Float height,
            byte[] specimenPng) throws IOException {
        return placementOf(pdf, page, x, y, width, height, specimenPng);
    }

    static VisibleSignature placementOf(byte[] pdf, int page, Float x, Float y, Float width, Float height,
            byte[] specimenPng) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (page < 1 || page > document.getNumberOfPages()) {
                throw ApiException.badRequest("Halaman " + page + " tidak ada (dokumen punya "
                        + document.getNumberOfPages() + " halaman)");
            }
            var pdPage = document.getPage(page - 1);
            PDRectangle box = pdPage.getMediaBox();
            int turns = Math.floorMod(pdPage.getRotation(), 360) / 90;
            boolean turned = turns % 2 == 1;
            float pageW = box.getWidth();
            float pageH = box.getHeight();
            // Ukuran di halaman asli: bila tidak diberikan, 180 x 75 sebagaimana tampil.
            float w = width != null ? width : turned ? 75 : 180;
            float h = height != null ? height : turned ? 180 : 75;
            float left = x != null ? x : 0;
            float top = y != null ? y : 0;
            if (x == null || y == null) {
                float shownW = turned ? h : w;
                float shownH = turned ? w : h;
                float dispW = turned ? pageH : pageW;
                float dispH = turned ? pageW : pageH;
                float[] a = toPage(dispW - shownW - 40, dispH - shownH - 40, turns, pageW, pageH);
                float[] b = toPage(dispW - 40, dispH - 40, turns, pageW, pageH);
                if (x == null) {
                    left = Math.min(a[0], b[0]);
                }
                if (y == null) {
                    top = Math.min(a[1], b[1]);
                }
            }
            return new VisibleSignature(page - 1, left, top, w, h, List.of(), specimenPng);
        }
    }

    /** Titik tampilan (dx, dy) ke titik halaman asli (u, v dari atas); sama dengan frontend/src/lib/coords.ts. */
    private static float[] toPage(float dx, float dy, int turns, float pageW, float pageH) {
        return switch (turns) {
            case 1 -> new float[] {dy, pageH - dx};
            case 2 -> new float[] {pageW - dx, pageH - dy};
            case 3 -> new float[] {pageW - dy, dx};
            default -> new float[] {dx, dy};
        };
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
