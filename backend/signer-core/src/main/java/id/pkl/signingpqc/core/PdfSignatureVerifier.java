package id.pkl.signingpqc.core;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

/**
 * Memeriksa setiap tanda tangan PAdES di dalam PDF: keutuhan isi (message-digest atas ByteRange),
 * keabsahan tanda tangan kriptografis (RSA atau ML-DSA), dan kecocokan signing-certificate-v2.
 *
 * <p>Signature timestamp (B-T) diperiksa: tanda tangan token, ESSCertID TSA, dan imprint atas nilai
 * tanda tangan. Rantai sertifikat, pencabutan, dan validasi pada waktu timestamp ditambahkan pada Sprint 2-3.
 */
public final class PdfSignatureVerifier {

    public PdfSignatureVerifier() {
        CryptoProviders.ensureRegistered();
    }

    public List<SignatureReport> verify(byte[] pdf) throws IOException {
        List<SignatureReport> reports = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int order = 0;
            for (PDSignature signature : document.getSignatureDictionaries()) {
                order++;
                reports.add(verifyOne(order, signature, pdf));
            }
        }
        return reports;
    }

    private SignatureReport verifyOne(int order, PDSignature signature, byte[] pdf) {
        String fieldName = signature.getName();
        String subFilter = signature.getSubFilter();
        int[] byteRange = signature.getByteRange();
        boolean coversWholeDocument = byteRange != null && byteRange.length == 4
                && byteRange[0] == 0 && (long) byteRange[2] + byteRange[3] == pdf.length;
        java.time.Instant claimed = signature.getSignDate() == null ? null
                : signature.getSignDate().toInstant();
        try {
            byte[] cmsBytes = firstDerObject(signature.getContents(pdf));
            byte[] signedContent = signature.getSignedContent(pdf);
            CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(signedContent), cmsBytes);
            SignerInformation signer = cms.getSignerInfos().getSigners().iterator().next();
            @SuppressWarnings("unchecked")
            Collection<X509CertificateHolder> matches = cms.getCertificates().getMatches(signer.getSID());
            if (matches.isEmpty()) {
                return failed(order, fieldName, subFilter, claimed, coversWholeDocument,
                        "Sertifikat penanda tangan tidak ditemukan di dalam CMS");
            }
            X509CertificateHolder certificate = matches.iterator().next();
            boolean valid;
            boolean digestMismatch = false;
            try {
                valid = signer.verify(new JcaSimpleSignerInfoVerifierBuilder()
                        .setProvider(CryptoProviders.BC)
                        .build(certificate));
            } catch (org.bouncycastle.cms.CMSSignerDigestMismatchException e) {
                valid = false;
                digestMismatch = true;
            }
            boolean certMatches = signingCertificateMatches(signer, certificate);
            String message;
            if (digestMismatch) {
                message = "Isi dokumen telah diubah setelah ditandatangani (message-digest tidak cocok)";
            } else if (!valid) {
                message = "Tanda tangan tidak sah atau dokumen telah diubah";
            } else if (!certMatches) {
                message = "Atribut signing-certificate-v2 tidak cocok dengan sertifikat penanda tangan";
            } else {
                message = "Tanda tangan sah dan isi yang ditandatangani utuh";
            }
            return new SignatureReport(order, fieldName, subFilter,
                    AlgorithmNames.signature(signer.getEncryptionAlgOID()),
                    AlgorithmNames.digest(signer.getDigestAlgOID()),
                    certificate.getSubject().toString(), claimed, cmsBytes.length,
                    coversWholeDocument, certMatches, valid, message, SignatureTimestamps.inspect(signer));
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return failed(order, fieldName, subFilter, claimed, coversWholeDocument,
                    "Tanda tangan tidak dapat diverifikasi: " + reason);
        }
    }

    private static boolean signingCertificateMatches(SignerInformation signer, X509CertificateHolder certificate)
            throws IOException, OperatorCreationException {
        if (signer.getSignedAttributes() == null) {
            return false;
        }
        Attribute attribute = signer.getSignedAttributes().get(PKCSObjectIdentifiers.id_aa_signingCertificateV2);
        if (attribute == null) {
            return false;
        }
        SigningCertificateV2 value = SigningCertificateV2.getInstance(attribute.getAttrValues().getObjectAt(0));
        ESSCertIDv2 first = value.getCerts()[0];
        DigestCalculator calculator = new JcaDigestCalculatorProviderBuilder()
                .setProvider(CryptoProviders.BC)
                .build()
                .get(first.getHashAlgorithm());
        try (OutputStream out = calculator.getOutputStream()) {
            out.write(certificate.getEncoded());
        }
        return Arrays.equals(calculator.getDigest(), first.getCertHash());
    }

    /**
     * /Contents diisi nol sampai ukuran cadangan. Hanya objek DER pertama (CMS) yang diambil,
     * sehingga byte nol yang sah di akhir tanda tangan tidak ikut terbuang.
     */
    public static byte[] firstDerObject(byte[] contents) throws IOException {
        try (ASN1InputStream in = new ASN1InputStream(contents)) {
            return in.readObject().getEncoded(ASN1Encoding.DER);
        }
    }

    private static SignatureReport failed(int order, String fieldName, String subFilter,
            java.time.Instant claimed, boolean coversWholeDocument, String message) {
        return new SignatureReport(order, fieldName, subFilter, "-", "-", "-", claimed, 0,
                coversWholeDocument, false, false, message, null);
    }
}
