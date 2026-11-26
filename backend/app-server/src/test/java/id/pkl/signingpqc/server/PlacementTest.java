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

    private static byte[] rotated(int degrees) throws Exception {
        try (PDDocument document = Loader.loadPDF(SamplePdf.create("Lanskap", "Berotasi."));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.getPage(0).setRotation(degrees);
            document.save(out);
            return out.toByteArray();
        }
    }
}
