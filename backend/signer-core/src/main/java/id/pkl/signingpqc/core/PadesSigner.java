package id.pkl.signingpqc.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Calendar;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;

/**
 * Menambahkan tanda tangan PAdES (ETSI.CAdES.detached) ke PDF sebagai incremental update.
 *
 * <p>Sprint 0: tanda tangan invisible. Tampilan visible untuk RSA ditambahkan pada Sprint 1.
 */
public final class PadesSigner {

    /** Ruang /Contents (byte biner) untuk tanda tangan RSA dan ML-DSA, termasuk cadangan timestamp. */
    static final int RSA_SIGNATURE_SPACE = 16 * 1024;
    static final int ML_DSA_SIGNATURE_SPACE = 48 * 1024;

    private final CadesSignatureBuilder cadesBuilder;

    public PadesSigner() {
        this(new CadesSignatureBuilder());
    }

    public PadesSigner(CadesSignatureBuilder cadesBuilder) {
        this.cadesBuilder = cadesBuilder;
    }

    public byte[] signInvisible(byte[] pdf, SigningCredential credential, SignatureDetails details)
            throws IOException, GeneralSecurityException {
        try (PDDocument document = Loader.loadPDF(pdf);
                SignatureOptions options = new SignatureOptions();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);
            signature.setName(details.signerName());
            signature.setReason(details.reason());
            signature.setLocation(details.location());
            signature.setSignDate(Calendar.getInstance());

            options.setPreferredSignatureSize(signatureSpace(credential.algorithm()));
            document.addSignature(signature, options);

            ExternalSigningSupport externalSigning = document.saveIncrementalForExternalSigning(output);
            byte[] toBeSigned;
            try (InputStream content = externalSigning.getContent()) {
                toBeSigned = content.readAllBytes();
            }
            byte[] cms = cadesBuilder.sign(toBeSigned, credential);
            if (cms.length > signatureSpace(credential.algorithm())) {
                throw new GeneralSecurityException("Ruang tanda tangan tidak cukup: CMS " + cms.length + " byte");
            }
            externalSigning.setSignature(cms);
            return output.toByteArray();
        }
    }

    static int signatureSpace(SignatureAlgorithm algorithm) {
        return algorithm.isPostQuantum() ? ML_DSA_SIGNATURE_SPACE : RSA_SIGNATURE_SPACE;
    }
}
