package id.pkl.signingpqc.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Membuat PDF contoh sintetis untuk pengujian. */
public final class SamplePdf {

    private SamplePdf() {
    }

    public static byte[] create(String title, String... lines) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(bold, 16);
                content.newLineAtOffset(72, 760);
                content.showText(title);
                content.setFont(regular, 11);
                for (String line : lines) {
                    content.newLineAtOffset(0, -20);
                    content.showText(line);
                }
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
