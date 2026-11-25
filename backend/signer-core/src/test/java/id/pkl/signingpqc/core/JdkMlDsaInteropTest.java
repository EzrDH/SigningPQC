package id.pkl.signingpqc.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

/**
 * Uji interoperabilitas ML-DSA-65 antara Bouncy Castle dan implementasi bawaan JDK 25 (JEP 497, provider SUN).
 */
class JdkMlDsaInteropTest {

    private static final String JDK_PROVIDER = "SUN";

    @Test
    void jdkVerifiesSignatureCreatedByBouncyCastle() throws Exception {
        SigningCredential bcCredential = SpikeCredentials.selfSigned(SignatureAlgorithm.ML_DSA_65,
                "CN=Uji ML-DSA BC, O=SigningPQC Uji, C=ID");
        byte[] signed = new PadesSigner().signInvisible(SamplePdf.create("Uji BC ke JDK", "Isi"),
                bcCredential, SignatureDetails.of("Uji", "BC"));

        try (PDDocument document = Loader.loadPDF(signed)) {
            PDSignature signature = document.getSignatureDictionaries().get(0);
            byte[] content = signature.getSignedContent(signed);
            CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(content),
                    PdfSignatureVerifier.firstDerObject(signature.getContents(signed)));
            SignerInformation signer = cms.getSignerInfos().getSigners().iterator().next();

            // 1. message-digest di atribut bertanda tangan = SHA-512 isi PDF, dihitung dengan JDK.
            byte[] expectedDigest = MessageDigest.getInstance("SHA-512").digest(content);
            ASN1OctetString messageDigest = ASN1OctetString.getInstance(
                    signer.getSignedAttributes().get(CMSAttributes.messageDigest).getAttrValues().getObjectAt(0));
            assertArrayEquals(expectedDigest, messageDigest.getOctets());

            // 2. Tanda tangan ML-DSA atas atribut bertanda tangan diverifikasi oleh provider JDK.
            PublicKey jdkKey = KeyFactory.getInstance("ML-DSA", JDK_PROVIDER)
                    .generatePublic(new X509EncodedKeySpec(bcCredential.certificate().getPublicKey().getEncoded()));
            Signature verifier = Signature.getInstance("ML-DSA", JDK_PROVIDER);
            verifier.initVerify(jdkKey);
            verifier.update(signer.getEncodedSignedAttributes());
            assertTrue(verifier.verify(signer.getSignature()), "JDK harus menerima tanda tangan dari Bouncy Castle");
        }
    }

    @Test
    void bouncyCastleVerifiesSignatureCreatedByJdk() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("ML-DSA-65", JDK_PROVIDER);
        KeyPair keyPair = generator.generateKeyPair();
        X509Certificate certificate = jdkSignedCertificate(keyPair);
        SigningCredential jdkCredential = new SigningCredential(SignatureAlgorithm.ML_DSA_65,
                keyPair.getPrivate(), List.of(certificate));

        byte[] signed = new PadesSigner(new CadesSignatureBuilder(JDK_PROVIDER))
                .signInvisible(SamplePdf.create("Uji JDK ke BC", "Isi"), jdkCredential, SignatureDetails.of("Uji", "JDK"));

        List<SignatureReport> reports = new PdfSignatureVerifier().verify(signed);
        assertEquals(1, reports.size());
        assertEquals("ML-DSA-65", reports.get(0).signatureAlgorithm());
        assertTrue(reports.get(0).valid(), "Bouncy Castle harus menerima tanda tangan dari JDK: " + reports.get(0).message());
    }

    private static X509Certificate jdkSignedCertificate(KeyPair keyPair) throws Exception {
        CryptoProviders.ensureRegistered();
        X500Name subject = new X500Name("CN=Uji ML-DSA JDK, O=SigningPQC Uji, C=ID");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject,
                new BigInteger(64, new SecureRandom()), Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(Duration.ofDays(30))), subject, keyPair.getPublic());
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
        return new JcaX509CertificateConverter().setProvider(CryptoProviders.BC).getCertificate(
                builder.build(new JcaContentSignerBuilder("ML-DSA-65").setProvider(JDK_PROVIDER).build(keyPair.getPrivate())));
    }
}
