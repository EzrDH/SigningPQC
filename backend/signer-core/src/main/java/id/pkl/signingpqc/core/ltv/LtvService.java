package id.pkl.signingpqc.core.ltv;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampToken;

import id.pkl.signingpqc.core.PdfSignatureVerifier;
import id.pkl.signingpqc.core.SignatureTimestamps;
import id.pkl.signingpqc.core.TimestampClient;

/**
 * Alur lapisan jangka panjang: data validasi (lapisan 3), document timestamp (lapisan 4), dan pembaruan
 * (lapisan 5, 6, ...).
 */
public final class LtvService {

    private LtvService() {
    }

    /**
     * Menyimpan rantai dan CRL untuk setiap tanda tangan (termasuk TSA signature timestamp-nya) dan setiap
     * document timestamp ke DSS. CRL diambil saat ini juga dari {@code source}.
     */
    public static byte[] addValidationData(byte[] pdf, RevocationSource source)
            throws IOException, GeneralSecurityException {
        Map<String, ValidationData> byKey = new LinkedHashMap<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            for (PDSignature signature : document.getSignatureDictionaries()) {
                byKey.put(DssWriter.vriKey(signature, pdf), collect(signature, pdf, source));
            }
        }
        if (byKey.isEmpty()) {
            throw new GeneralSecurityException("Dokumen belum memiliki tanda tangan");
        }
        return DssWriter.append(pdf, byKey);
    }

    public static byte[] addDocumentTimestamp(byte[] pdf, TimestampClient tsa, TimestampProfile profile)
            throws IOException, GeneralSecurityException {
        return DocumentTimestamper.add(pdf, tsa, profile);
    }

    /** Pembaruan: simpan data validasi untuk semua lapisan (termasuk timestamp terakhir), lalu timestamp baru. */
    public static byte[] renew(byte[] pdf, RevocationSource source, TimestampClient tsa, TimestampProfile profile)
            throws IOException, GeneralSecurityException {
        return addDocumentTimestamp(addValidationData(pdf, source), tsa, profile);
    }

    private static ValidationData collect(PDSignature signature, byte[] pdf, RevocationSource source)
            throws IOException, GeneralSecurityException {
        byte[] der = PdfSignatureVerifier.firstDerObject(signature.getContents(pdf));
        try {
            if (DssReader.ETSI_RFC3161.equals(signature.getSubFilter())) {
                return forToken(new TimeStampToken(new CMSSignedData(der)), source);
            }
            CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(signature.getSignedContent(pdf)), der);
            SignerInformation signer = cms.getSignerInfos().getSigners().iterator().next();
            X509Certificate certificate = CmsCertificates.signer(cms, signer.getSID());
            if (certificate == null) {
                throw new GeneralSecurityException("Sertifikat penanda tangan tidak ada di dalam CMS");
            }
            ValidationData data = source.collect(certificate, CmsCertificates.all(cms));
            TimeStampToken token = SignatureTimestamps.tokenOf(signer);
            return token == null ? data : data.merge(forToken(token, source));
        } catch (CMSException | TSPException e) {
            throw new GeneralSecurityException("Struktur tanda tangan tidak dapat dibaca: " + e.getMessage(), e);
        }
    }

    private static ValidationData forToken(TimeStampToken token, RevocationSource source)
            throws GeneralSecurityException {
        X509Certificate tsa = TimestampClient.verify(token);
        List<X509Certificate> pool = CmsCertificates.all(token.toCMSSignedData());
        return source.collect(tsa, pool);
    }
}
