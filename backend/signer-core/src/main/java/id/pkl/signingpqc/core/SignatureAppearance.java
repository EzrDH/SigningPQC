package id.pkl.signingpqc.core;

import java.awt.geom.AffineTransform;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;

/**
 * Membuat templat tampilan tanda tangan (widget + appearance stream) untuk {@code SignatureOptions.setVisualSignature}.
 * Pola ini mengikuti contoh CreateVisibleSignature2 dari Apache PDFBox.
 */
final class SignatureAppearance {

    static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss 'WIB'");

    private SignatureAppearance() {
    }

    /** Rectangle widget dalam koordinat PDF (titik asal di kiri bawah). */
    static PDRectangle rectangle(PDDocument document, VisibleSignature visible) {
        if (visible.pageIndex() < 0 || visible.pageIndex() >= document.getNumberOfPages()) {
            throw new IllegalArgumentException("Halaman " + (visible.pageIndex() + 1) + " tidak ada di dokumen");
        }
        PDPage page = document.getPage(visible.pageIndex());
        PDRectangle mediaBox = page.getMediaBox();
        float lowerLeftY = mediaBox.getHeight() - visible.yFromTop() - visible.height();
        if (visible.x() < 0 || visible.yFromTop() < 0 || lowerLeftY < 0
                || visible.x() + visible.width() > mediaBox.getWidth()) {
            throw new IllegalArgumentException("Kotak tanda tangan keluar dari halaman");
        }
        boolean turned = quarterTurns(page) % 2 == 1;
        float shownWidth = turned ? visible.height() : visible.width();
        float shownHeight = turned ? visible.width() : visible.height();
        if (!VisibleSignature.fitsMinimum(shownWidth, shownHeight)) {
            throw new IllegalArgumentException("Kotak tanda tangan minimal 120 x 60 point");
        }
        return new PDRectangle(visible.x(), lowerLeftY, visible.width(), visible.height());
    }

    /** /Rotate halaman dalam kelipatan 90 derajat searah jarum jam (0 sampai 3). */
    static int quarterTurns(PDPage page) {
        return Math.floorMod(page.getRotation(), 360) / 90;
    }

    static List<String> lines(SignatureDetails details, VisibleSignature visible, Calendar signDate,
            SignatureAlgorithm algorithm) {
        List<String> lines = new ArrayList<>();
        lines.add("Ditandatangani secara elektronik oleh:");
        lines.add(details.signerName());
        lines.addAll(visible.extraLines());
        lines.add("Tanggal: " + DATE.format(signDate.toInstant().atZone(WIB)));
        if (details.reason() != null && !details.reason().isBlank()) {
            lines.add("Alasan: " + details.reason());
        }
        lines.add("Hybrid " + AlgorithmNames.shortName(algorithm) + " + ML-DSA (pasca-kuantum)");
        return lines;
    }

    static InputStream template(PDDocument source, VisibleSignature visible, PDRectangle rect, List<String> lines)
            throws IOException {
        try (PDDocument template = new PDDocument()) {
            PDPage sourcePage = source.getPage(visible.pageIndex());
            int turns = quarterTurns(sourcePage);
            PDPage page = new PDPage(sourcePage.getMediaBox());
            template.addPage(page);
            PDAcroForm acroForm = new PDAcroForm(template);
            template.getDocumentCatalog().setAcroForm(acroForm);
            PDSignatureField field = new PDSignatureField(acroForm);
            PDAnnotationWidget widget = field.getWidgets().get(0);
            acroForm.setSignaturesExist(true);
            acroForm.setAppendOnly(true);
            acroForm.getCOSObject().setDirect(true);
            acroForm.getFields().add(field);
            widget.setRectangle(rect);

            PDFormXObject form = new PDFormXObject(new PDStream(template));
            form.setResources(new PDResources());
            form.setFormType(1);
            // Isi digambar dalam orientasi yang tampil di layar. Pada halaman berotasi, Matrix memutar isi berlawanan
            // arah /Rotate sehingga setelah penampil memutar halaman, spesimen dan teks tampil tegak. Penampil
            // memetakan BBox yang sudah diputar ke Rect (ISO 32000-1 12.5.5), jadi ukurannya pas tanpa skala.
            boolean turned = turns % 2 == 1;
            float width = turned ? rect.getHeight() : rect.getWidth();
            float height = turned ? rect.getWidth() : rect.getHeight();
            form.setBBox(new PDRectangle(width, height));
            if (turns != 0) {
                form.setMatrix(AffineTransform.getQuadrantRotateInstance(turns));
            }

            PDAppearanceDictionary appearance = new PDAppearanceDictionary();
            appearance.getCOSObject().setDirect(true);
            PDAppearanceStream stream = new PDAppearanceStream(form.getCOSObject());
            appearance.setNormalAppearance(stream);
            widget.setAppearance(appearance);

            try (PDPageContentStream content = new PDPageContentStream(template, stream)) {
                byte[] specimen = visible.specimenPng();
                if (specimen != null) {
                    drawSpecimen(content, PDImageXObject.createFromByteArray(template, specimen, "spesimen"), width,
                            height);
                } else {
                    draw(content, width, height, lines);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            template.save(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    /** Spesimen diskalakan pas ke kotak tanpa distorsi, rata kiri dan tengah vertikal, tanpa latar dan teks. */
    private static void drawSpecimen(PDPageContentStream content, PDImageXObject image, float width, float height)
            throws IOException {
        float scale = Math.min(width / image.getWidth(), height / image.getHeight());
        float drawWidth = image.getWidth() * scale;
        float drawHeight = image.getHeight() * scale;
        content.drawImage(image, 0, (height - drawHeight) / 2, drawWidth, drawHeight);
    }

    private static void draw(PDPageContentStream content, float width, float height, List<String> lines)
            throws IOException {
        // Latar dan bingkai.
        content.setNonStrokingColor(0.95f, 0.97f, 1.0f);
        content.addRect(0, 0, width, height);
        content.fill();
        content.setStrokingColor(0.10f, 0.30f, 0.60f);
        content.setLineWidth(1.2f);
        content.addRect(0.6f, 0.6f, width - 1.2f, height - 1.2f);
        content.stroke();

        PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        float padding = 6;
        float fontSize = fitFontSize(lines, regular, bold, width - 2 * padding, height - 2 * padding);
        float leading = fontSize * 1.25f;

        content.setNonStrokingColor(0.05f, 0.10f, 0.25f);
        content.beginText();
        content.newLineAtOffset(padding, height - padding - fontSize);
        for (int i = 0; i < lines.size(); i++) {
            content.setFont(i == 1 ? bold : regular, fontSize);
            content.showText(lines.get(i));
            content.newLineAtOffset(0, -leading);
        }
        content.endText();
    }

    /** Ukuran huruf terbesar (maksimal 9 pt, minimal 5 pt) yang muat di dalam kotak. */
    private static float fitFontSize(List<String> lines, PDType1Font regular, PDType1Font bold, float maxWidth,
            float maxHeight) throws IOException {
        for (float size = 9f; size > 5f; size -= 0.25f) {
            boolean fitsHeight = size + (lines.size() - 1) * size * 1.25f <= maxHeight;
            boolean fitsWidth = true;
            for (int i = 0; i < lines.size() && fitsWidth; i++) {
                PDType1Font font = i == 1 ? bold : regular;
                fitsWidth = font.getStringWidth(lines.get(i)) / 1000 * size <= maxWidth;
            }
            if (fitsHeight && fitsWidth) {
                return size;
            }
        }
        return 5f;
    }
}
