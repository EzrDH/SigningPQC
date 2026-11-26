package id.pkl.signingpqc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import id.pkl.signingpqc.core.SamplePdf;
import id.pkl.signingpqc.core.VisibleSignature;

/** Kotak bawaan 180 x 75 di kanan bawah halaman sebagaimana tampil, juga pada halaman berotasi. */
class PlacementTest {

    @Test
    void bawaanHalamanTegakDiKananBawah() throws Exception {
        VisibleSignature v = SigningService.placementOf(SamplePdf.create("Uji", "Tegak."), 1, null, null, null, null,
                null);
        assertEquals(595.28f - 180 - 40, v.x(), 0.01f);
        assertEquals(841.89f - 75 - 40, v.yFromTop(), 0.01f);
        assertEquals(180, v.width(), 0.01f);
        assertEquals(75, v.height(), 0.01f);
    }

    @Test
    void bawaanHalamanBerotasi90DiKananBawahTampilan() throws Exception {
        // Tampilan 841.89 x 595.28; kotak tampil (621.89, 480.28) 180 x 75. Halaman asli: u = dy, v = H - dx.
        VisibleSignature v = SigningService.placementOf(rotated(90), 1, null, null, null, null, null);
        assertEquals(480.28f, v.x(), 0.01f);
        assertEquals(841.89f - 621.89f - 180, v.yFromTop(), 0.01f);
        assertEquals(75, v.width(), 0.01f);
        assertEquals(180, v.height(), 0.01f);
    }

    @Test
    void koordinatDariKlienDipakaiApaAdanya() throws Exception {
        VisibleSignature v = SigningService.placementOf(rotated(90), 1, 400f, 162f, 75f, 180f, null);
        assertEquals(400, v.x(), 0.01f);
        assertEquals(162, v.yFromTop(), 0.01f);
        assertEquals(75, v.width(), 0.01f);
        assertEquals(180, v.height(), 0.01f);
    }

    /** Kotak yang tidak muat ditolak oleh placementOf, sebelum kunci dibuka, agar kode TOTP tidak terbuang. */
    @Test
    void kotakTidakMuatDitolakSebelumMembukaKunci() throws Exception {
        byte[] pdf = SamplePdf.create("Uji", "Batas.");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SigningService.placementOf(pdf, 1, 500f, 100f, 180f, 75f, null));
        // Rotasi 90: 180 x 75 di halaman asli tampil 75 x 180, di bawah minimal tampilan.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SigningService.placementOf(rotated(90), 1, 100f, 100f, 180f, 75f, null));
    }

    @Test
    void bawaanMengikutiCropBox() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(612, 792));
            page.setCropBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(36, 36, 540, 720));
            document.addPage(page);
            document.save(out);
            pdf = out.toByteArray();
        }
        VisibleSignature v = SigningService.placementOf(pdf, 1, null, null, null, null, null);
        assertEquals(540 - 180 - 40, v.x(), 0.01f);
        assertEquals(720 - 75 - 40, v.yFromTop(), 0.01f);
    }

    private static byte[] rotated(int degrees) throws Exception {
        try (PDDocument document = Loader.loadPDF(SamplePdf.create("Lanskap", "Berotasi."));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.getPage(0).setRotation(degrees);
            document.save(out);
            return out.toByteArray();
        }
    }
}
