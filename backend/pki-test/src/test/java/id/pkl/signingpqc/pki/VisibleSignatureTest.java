package id.pkl.signingpqc.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import id.pkl.signingpqc.core.PadesSigner;
import id.pkl.signingpqc.core.PdfSignatureVerifier;
import id.pkl.signingpqc.core.SamplePdf;
import id.pkl.signingpqc.core.SignatureDetails;
import id.pkl.signingpqc.core.SignatureReport;
import id.pkl.signingpqc.core.TimestampClient;
import id.pkl.signingpqc.core.VisibleSignature;

/** Uji tanda tangan RSA visible + ML-DSA invisible, keduanya B-T. */
class VisibleSignatureTest {

    private static final SignerIdentity IDENTITY = new SignerIdentity("Budi Penanda Tangan Uji", "Kepala Seksi Uji",
            "Unit Uji", "SigningPQC Uji");

    private static SignerPair pair;
    private static LocalTsa rsaTsa;
    private static LocalTsa mldsaTsa;
    private static byte[] signed;
    private static VisibleSignature position;

    @BeforeAll
    static void setUp() throws Exception {
        TestPkiFactory factory = new TestPkiFactory();
        CaHierarchy rsa = factory.createHierarchy(PkiFamily.RSA, "SigningPQC Uji");
        CaHierarchy mldsa = factory.createHierarchy(PkiFamily.ML_DSA, "SigningPQC Uji");
        pair = factory.issueSignerPair(rsa, mldsa, IDENTITY);
        rsaTsa = new LocalTsa(factory.issueTsa(rsa.intermediate(), "TSA Uji RSA", TestPkiFactory.TSA_VALIDITY));
        mldsaTsa = new LocalTsa(factory.issueTsa(mldsa.intermediate(), "TSA Uji ML-DSA", TestPkiFactory.TSA_VALIDITY));

        byte[] pdf = SamplePdf.create("Surat Uji Visible", "Tanda tangan RSA tampil, ML-DSA tersembunyi.");
        PDRectangle page = PDRectangle.A4;
        position = VisibleSignature.bottomRightOfFirstPage(page.getWidth(), page.getHeight(),
                List.of(IDENTITY.title() + ", " + IDENTITY.organizationalUnit()));
        PadesSigner signer = new PadesSigner();
        byte[] afterRsa = signer.signVisible(pdf, pair.rsa(), SignatureDetails.of(IDENTITY.commonName(), "Persetujuan"),
                position, new TimestampClient(rsaTsa));
        signed = signer.signInvisible(afterRsa, pair.mlDsa(), SignatureDetails.of(IDENTITY.commonName(), "Persetujuan"),
                new TimestampClient(mldsaTsa));
    }

    @Test
    void keduaTandaTanganSahDanBt() throws Exception {
        List<SignatureReport> reports = new PdfSignatureVerifier().verify(signed);
        assertEquals(2, reports.size());
        for (SignatureReport report : reports) {
            assertTrue(report.valid(), report.message());
            assertEquals("B-T", report.padesLevel());
        }
        assertTrue(reports.get(1).coversWholeDocument());
    }

    @Test
    void hanyaTandaTanganRsaYangTampil() throws Exception {
        try (PDDocument document = Loader.loadPDF(signed)) {
            List<PDSignatureField> fields = document.getSignatureDictionaries().isEmpty() ? List.of()
                    : document.getDocumentCatalog().getAcroForm().getFields().stream()
                            .filter(PDSignatureField.class::isInstance).map(PDSignatureField.class::cast).toList();
            assertEquals(2, fields.size());
            PDAnnotationWidget rsaWidget = fields.get(0).getWidgets().get(0);
            PDAnnotationWidget mldsaWidget = fields.get(1).getWidgets().get(0);
            assertEquals(220f, rsaWidget.getRectangle().getWidth(), 0.01f);
            assertEquals(90f, rsaWidget.getRectangle().getHeight(), 0.01f);
            assertNotNull(rsaWidget.getAppearance().getNormalAppearance());
            assertEquals(0f, mldsaWidget.getRectangle().getWidth(), 0.01f, "ML-DSA invisible");
            assertTrue(document.getPage(0).getAnnotations().contains(rsaWidget), "widget RSA ada di halaman 1");
        }
    }

    @Test
    void kotakTerlihatSaatHalamanDirender() throws Exception {
        try (PDDocument document = Loader.loadPDF(signed)) {
            float scale = 2f;
            BufferedImage image = new PDFRenderer(document).renderImage(0, scale);
            int dark = 0;
            int x0 = Math.round(position.x() * scale);
            int y0 = Math.round(position.yFromTop() * scale);
            for (int x = x0; x < x0 + position.width() * scale; x++) {
                for (int y = y0; y < y0 + position.height() * scale; y++) {
                    int rgb = image.getRGB(x, y);
                    int luminance = ((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff);
                    if (luminance < 300) {
                        dark++;
                    }
                }
            }
            Path out = Path.of("target", "visible-signature-halaman1.png");
            Files.createDirectories(out.getParent());
            ImageIO.write(image, "png", out.toFile());
            assertTrue(dark > 500, "teks dan bingkai harus tergambar, piksel gelap = " + dark);
        }
    }

    @Test
    void posisiDiLuarHalamanDitolak() throws Exception {
        byte[] pdf = SamplePdf.create("Surat Uji", "Posisi salah.");
        VisibleSignature outside = new VisibleSignature(0, 500, 800, 220, 90, List.of());
        assertThrows(IllegalArgumentException.class, () -> new PadesSigner().signVisible(pdf, pair.rsa(),
                SignatureDetails.of("Uji", "Uji"), outside, null));
        VisibleSignature noPage = new VisibleSignature(3, 40, 40, 220, 90, List.of());
        assertThrows(IllegalArgumentException.class, () -> new PadesSigner().signVisible(pdf, pair.rsa(),
                SignatureDetails.of("Uji", "Uji"), noPage, null));
    }

    @Test
    void spesimenGambarDigambarTanpaTeks() throws Exception {
        BufferedImage specimen = new BufferedImage(400, 160, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = specimen.createGraphics();
        g.setColor(java.awt.Color.BLUE);
        g.drawLine(10, 80, 390, 40);
        g.dispose();
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        ImageIO.write(specimen, "png", png);

        byte[] pdf = SamplePdf.create("Surat Uji Spesimen", "Spesimen gambar.");
        VisibleSignature box = new VisibleSignature(0, 300, 600, 180, 75, List.of(), png.toByteArray());
        byte[] out = new PadesSigner().signVisible(pdf, pair.rsa(), SignatureDetails.of(IDENTITY.commonName(), "Uji"),
                box, null);
        assertTrue(new PdfSignatureVerifier().verify(out).get(0).valid());
        try (PDDocument document = Loader.loadPDF(out)) {
            PDSignatureField field = document.getDocumentCatalog().getAcroForm().getFields().stream()
                    .filter(PDSignatureField.class::isInstance).map(PDSignatureField.class::cast).findFirst().orElseThrow();
            PDFormXObject appearance = new PDFormXObject(field.getWidgets().get(0).getAppearance().getNormalAppearance()
                    .getAppearanceStream().getCOSObject());
            List<PDImageXObject> images = new java.util.ArrayList<>();
            StringBuilder content = new StringBuilder();
            collect(appearance, images, content, 0);
            assertEquals(1, images.size());
            assertEquals(400, images.get(0).getWidth());
            assertEquals(160, images.get(0).getHeight());
            assertTrue(!content.toString().contains("Tj") && !content.toString().contains("TJ"),
                    "spesimen tanpa teks: " + content);
        }
    }

    /**
     * T-024: Adobe Reader gagal memvalidasi tanda tangan bila /Resources /XObject appearance langsung menunjuk gambar.
     * Gambar harus berada di form XObject dalam, dan /DecodeParms gambar ditulis langsung (bukan referensi).
     */
    @Test
    void spesimenGambarDibungkusFormDalamAgarTerbacaAdobe() throws Exception {
        BufferedImage specimen = new BufferedImage(400, 160, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = specimen.createGraphics();
        g.setColor(java.awt.Color.BLUE);
        g.drawLine(10, 80, 390, 40);
        g.dispose();
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        ImageIO.write(specimen, "png", png);

        byte[] pdf = SamplePdf.create("Surat Uji Spesimen", "Spesimen gambar.");
        VisibleSignature box = new VisibleSignature(0, 300, 600, 180, 75, List.of(), png.toByteArray());
        byte[] out = new PadesSigner().signVisible(pdf, pair.rsa(), SignatureDetails.of(IDENTITY.commonName(), "Uji"),
                box, null);
        try (PDDocument document = Loader.loadPDF(out)) {
            PDFormXObject appearance = new PDFormXObject(firstWidget(document).getAppearance().getNormalAppearance()
                    .getAppearanceStream().getCOSObject());
            PDResources resources = appearance.getResources();
            int forms = 0;
            for (COSName name : resources.getXObjectNames()) {
                PDXObject x = resources.getXObject(name);
                assertTrue(x instanceof PDFormXObject, "appearance tidak boleh langsung menunjuk gambar: " + name);
                forms++;
                PDResources inner = ((PDFormXObject) x).getResources();
                for (COSName innerName : inner.getXObjectNames()) {
                    if (inner.getXObject(innerName) instanceof PDImageXObject image) {
                        org.apache.pdfbox.cos.COSBase parms = image.getCOSObject()
                                .getItem(COSName.DECODE_PARMS);
                        assertTrue(parms == null || parms instanceof org.apache.pdfbox.cos.COSDictionary,
                                "/DecodeParms harus langsung, bukan referensi: " + parms);
                    }
                }
            }
            assertEquals(1, forms);
        }
    }

    @Test
    void spesimenBukanGambarDitolak() throws Exception {
        byte[] pdf = SamplePdf.create("Surat Uji", "Spesimen salah.");
        VisibleSignature box = new VisibleSignature(0, 300, 600, 180, 75, List.of(), "bukan".getBytes());
        Exception e = assertThrows(Exception.class, () -> new PadesSigner().signVisible(pdf, pair.rsa(),
                SignatureDetails.of("Uji", "Uji"), box, null));
        assertTrue(e instanceof java.io.IOException || e instanceof IllegalArgumentException, e.toString());
    }

    /**
     * Halaman ber-/Rotate 90 (umum pada hasil pindai lanskap). Kotak yang tampil di layar 180 x 75 di posisi
     * (500, 400) setara kotak halaman asli x=400, yFromTop=162, 75 x 180; pada rotasi 270 setara x=120, yFromTop=500
     * (lihat frontend/src/lib/coords.ts). Spesimen
     * harus tampil tegak (lebar lebih besar dari tinggi) di posisi itu saat halaman dirender dengan rotasinya.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "rotasi {0}")
    @org.junit.jupiter.params.provider.CsvSource({"90, 400, 162", "270, 120, 500"})
    void halamanBerotasiSpesimenTampilTegakDiPosisiYangDipilih(int rotation, float boxX, float boxYFromTop)
            throws Exception {
        // Separuh kiri hitam, separuh kanan putih: membedakan tegak dari terbalik atau tercermin.
        BufferedImage specimen = new BufferedImage(400, 160, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = specimen.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 400, 160);
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(0, 0, 200, 160);
        g.dispose();
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        ImageIO.write(specimen, "png", png);

        byte[] pdf;
        try (PDDocument document = Loader.loadPDF(SamplePdf.create("Surat Lanskap", "Halaman berotasi 90."));
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            document.getPage(0).setRotation(rotation);
            document.save(out);
            pdf = out.toByteArray();
        }
        VisibleSignature box = new VisibleSignature(0, boxX, boxYFromTop, 75, 180, List.of(), png.toByteArray());
        byte[] out = new PadesSigner().signVisible(pdf, pair.rsa(), SignatureDetails.of(IDENTITY.commonName(), "Uji"),
                box, null);
        assertTrue(new PdfSignatureVerifier().verify(out).get(0).valid());

        try (PDDocument document = Loader.loadPDF(out)) {
            BufferedImage image = new PDFRenderer(document).renderImage(0, 1f);
            assertTrue(image.getWidth() > image.getHeight(), "halaman tampil lanskap");
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
            for (int x = 480; x < 700; x++) {
                for (int y = 380; y < 495; y++) {
                    int rgb = image.getRGB(x, y);
                    if (((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff) < 150) {
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y);
                        maxY = Math.max(maxY, y);
                    }
                }
            }
            assertTrue(maxX > 0, "spesimen harus tergambar di dalam kotak yang dipilih");
            int width = maxX - minX + 1;
            int height = maxY - minY + 1;
            // Kotak 180 x 75, gambar 400 x 160 diskalakan 0,45 menjadi 180 x 72; separuh kirinya 90 x 72.
            assertEquals(90, width, 3, "separuh hitam selebar 90 (tegak, bukan 72 bila masih miring)");
            assertEquals(72, height, 3, "tinggi 72");
            assertEquals(500, minX, 2, "separuh hitam di kiri kotak (tidak terbalik)");
            assertTrue(minY >= 400 && maxY <= 475, "di dalam kotak vertikal: " + minY + ".." + maxY);
        }
    }

    @Test
    void halamanBerotasiUkuranMinimalMengikutiTampilan() throws Exception {
        byte[] pdf;
        try (PDDocument document = Loader.loadPDF(SamplePdf.create("Surat Lanskap", "Kecil."));
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            document.getPage(0).setRotation(90);
            document.save(out);
            pdf = out.toByteArray();
        }
        // 180 x 75 di halaman asli tampil 75 x 180 di layar: lebar tampilan di bawah 120, ditolak.
        VisibleSignature tall = new VisibleSignature(0, 100, 100, 180, 75, List.of());
        assertThrows(IllegalArgumentException.class, () -> new PadesSigner().signVisible(pdf, pair.rsa(),
                SignatureDetails.of("Uji", "Uji"), tall, null));
    }

    @Test
    void posisiDiAtasTepiHalamanDitolak() throws Exception {
        byte[] pdf = SamplePdf.create("Surat Uji", "Posisi salah.");
        VisibleSignature above = new VisibleSignature(0, 40, -10, 180, 75, List.of());
        assertThrows(IllegalArgumentException.class, () -> new PadesSigner().signVisible(pdf, pair.rsa(),
                SignatureDetails.of("Uji", "Uji"), above, null));
    }

    /** A4 dari ReportLab (595.2755905511812 pt): kotak rapat kanan bawah dari frontend (dibulatkan 3 desimal) diterima. */
    @Test
    void kotakRapatTepiPadaHalamanDesimalPanjangDiterima() throws Exception {
        byte[] pdf = pageWithBoxes(new PDRectangle(595.2755905511812f, 841.8897637795277f), null);
        VisibleSignature edge = new VisibleSignature(0, 415.276f, 766.89f, 180, 75, List.of());
        byte[] out = new PadesSigner().signVisible(pdf, pair.rsa(), SignatureDetails.of("Uji", "Uji"), edge, null);
        try (PDDocument document = Loader.loadPDF(out)) {
            PDRectangle rect = firstWidget(document).getRectangle();
            PDRectangle media = document.getPage(0).getMediaBox();
            assertTrue(rect.getUpperRightX() <= media.getUpperRightX() && rect.getLowerLeftY() >= media.getLowerLeftY(),
                    "kotak di dalam halaman: " + rect);
            assertEquals(180, rect.getWidth(), 0.01f);
        }
    }

    /** CropBox [36 36 576 756] di MediaBox [0 0 612 792]: koordinat diukur dari pojok kiri atas area yang tampil. */
    @Test
    void koordinatMengikutiCropBox() throws Exception {
        byte[] pdf = pageWithBoxes(new PDRectangle(612, 792), new PDRectangle(36, 36, 540, 720));
        VisibleSignature box = new VisibleSignature(0, 100, 100, 180, 75, List.of());
        byte[] out = new PadesSigner().signVisible(pdf, pair.rsa(), SignatureDetails.of("Uji", "Uji"), box, null);
        try (PDDocument document = Loader.loadPDF(out)) {
            PDRectangle rect = firstWidget(document).getRectangle();
            assertEquals(136, rect.getLowerLeftX(), 0.01f);
            assertEquals(756 - 100 - 75, rect.getLowerLeftY(), 0.01f);
        }
        // Di luar CropBox (masih di dalam MediaBox) ditolak.
        VisibleSignature outside = new VisibleSignature(0, 400, 100, 180, 75, List.of());
        assertThrows(IllegalArgumentException.class, () -> new PadesSigner().signVisible(pdf, pair.rsa(),
                SignatureDetails.of("Uji", "Uji"), outside, null));
    }

    private static byte[] pageWithBoxes(PDRectangle media, PDRectangle crop) throws Exception {
        try (PDDocument document = new PDDocument(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage(media);
            if (crop != null) {
                page.setCropBox(crop);
            }
            document.addPage(page);
            document.save(out);
            return out.toByteArray();
        }
    }

    private static PDAnnotationWidget firstWidget(PDDocument document) {
        return document.getDocumentCatalog().getAcroForm().getFields().stream()
                .filter(PDSignatureField.class::isInstance).map(PDSignatureField.class::cast).findFirst().orElseThrow()
                .getWidgets().get(0);
    }

    private static void collect(PDFormXObject form, List<PDImageXObject> images, StringBuilder content, int depth)
            throws java.io.IOException {
        if (depth > 6) {
            return;
        }
        try (java.io.InputStream in = form.getContents()) {
            content.append(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1));
        }
        PDResources resources = form.getResources();
        if (resources == null) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject x = resources.getXObject(name);
            if (x instanceof PDImageXObject image) {
                images.add(image);
            } else if (x instanceof PDFormXObject inner) {
                collect(inner, images, content, depth + 1);
            }
        }
    }
}
