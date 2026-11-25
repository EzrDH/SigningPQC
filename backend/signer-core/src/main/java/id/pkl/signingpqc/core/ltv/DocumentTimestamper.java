package id.pkl.signingpqc.core.ltv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.bouncycastle.tsp.TimeStampToken;

import id.pkl.signingpqc.core.TimestampClient;

/**
 * Menambahkan document timestamp (ETSI EN 319 142-1, PAdES B-LTA) sebagai pembaruan inkremental.
 *
 * <p>Kamus: /Type /DocTimeStamp, /Filter /Adobe.PPKLite, /SubFilter /ETSI.RFC3161. /Contents berisi token RFC 3161
 * atas seluruh byte dokumen di luar /Contents itu sendiri, sehingga timestamp menutup semua lapisan sebelumnya.
 */
public final class DocumentTimestamper {

    private DocumentTimestamper() {
    }

    public static byte[] add(byte[] pdf, TimestampClient tsa, TimestampProfile profile)
            throws IOException, GeneralSecurityException {
        try (PDDocument document = Loader.loadPDF(pdf);
                SignatureOptions options = new SignatureOptions();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDSignature signature = new PDSignature();
            signature.setType(COSName.DOC_TIME_STAMP);
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(COSName.getPDFName(DssReader.ETSI_RFC3161));
            options.setPreferredSignatureSize(profile.reservedBytes());
            document.addSignature(signature, options);

            ExternalSigningSupport external = document.saveIncrementalForExternalSigning(output);
            byte[] content;
            try (InputStream in = external.getContent()) {
                content = in.readAllBytes();
            }
            TimeStampToken token = tsa.timestamp(content, profile.digestName());
            byte[] encoded = token.getEncoded();
            if (encoded.length > profile.reservedBytes()) {
                throw new GeneralSecurityException("Ruang timestamp tidak cukup: token " + encoded.length
                        + " byte, ruang " + profile.reservedBytes() + " byte");
            }
            external.setSignature(encoded);
            return output.toByteArray();
        }
    }
}
