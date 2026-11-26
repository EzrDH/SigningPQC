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

    @Test
    void spesimenBukanGambarDitolak() throws Exception {
        byte[] pdf = SamplePdf.create("Surat Uji", "Spesimen salah.");
        VisibleSignature box = new VisibleSignature(0, 300, 600, 180, 75, List.of(), "bukan".getBytes());
        Exception e = assertThrows(Exception.class, () -> new PadesSigner().signVisible(pdf, pair.rsa(),
                SignatureDetails.of("Uji", "Uji"), box, null));
        assertTrue(e instanceof java.io.IOException || e instanceof IllegalArgumentException, e.toString());
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
