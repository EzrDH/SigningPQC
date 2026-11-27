package id.pkl.signingpqc.pki;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.GeneralSecurityException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import id.pkl.signingpqc.core.PadesSigner;
import id.pkl.signingpqc.core.PdfSignatureVerifier;
import id.pkl.signingpqc.core.Pkcs12Credentials;
import id.pkl.signingpqc.core.SamplePdf;
import id.pkl.signingpqc.core.SignatureAlgorithm;
import id.pkl.signingpqc.core.SignatureDetails;
import id.pkl.signingpqc.core.SignatureReport;
import id.pkl.signingpqc.core.SigningCredential;

/** Uji hierarki CA RSA dan ML-DSA, CRL, PKCS#12, dan tanda tangan hybrid dengan sertifikat terbitan CA. */
class TestPkiTest {

    private static final String ORG = "SigningPQC Uji";
    private static final char[] PASSWORD = "uji-signingpqc".toCharArray();
    private static final SignerIdentity IDENTITY = new SignerIdentity("Budi Penanda Tangan Uji", "Kepala Seksi Uji",
            "Unit Uji", ORG);

    private static TestPkiFactory factory;
    private static CaHierarchy rsa;
    private static CaHierarchy mldsa;
    private static SignerPair pair;

    @BeforeAll
    static void setUp() throws Exception {
        factory = new TestPkiFactory();
        rsa = factory.createHierarchy(PkiFamily.RSA, ORG);
        mldsa = factory.createHierarchy(PkiFamily.ML_DSA, ORG);
        pair = factory.issueSignerPair(rsa, mldsa, IDENTITY);
    }

    @Test
    void hierarkiMemakaiAlgoritmaYangDirencanakan() {
        assertEquals(SignatureAlgorithm.RSA_4096, SignatureAlgorithm.fromCertificate(rsa.root().certificate()));
        assertEquals(SignatureAlgorithm.RSA_3072, SignatureAlgorithm.fromCertificate(rsa.intermediate().certificate()));
        assertEquals(SignatureAlgorithm.ML_DSA_87, SignatureAlgorithm.fromCertificate(mldsa.root().certificate()));
        assertEquals(SignatureAlgorithm.ML_DSA_65,
                SignatureAlgorithm.fromCertificate(mldsa.intermediate().certificate()));
        assertEquals("2.16.840.1.101.3.4.3.19", mldsa.root().certificate().getSigAlgOID());
        assertEquals("2.16.840.1.101.3.4.3.19", mldsa.intermediate().certificate().getSigAlgOID());
        assertEquals("2.16.840.1.101.3.4.3.18", pair.mlDsa().certificate().getSigAlgOID());
        assertEquals(1, rsa.root().certificate().getBasicConstraints());
        assertEquals(0, mldsa.intermediate().certificate().getBasicConstraints());
        assertEquals(-1, pair.mlDsa().certificate().getBasicConstraints());
    }

    @Test
    void rantaiSahMenurutPkixUntukKeduaKeluarga() throws Exception {
        for (CaHierarchy hierarchy : List.of(rsa, mldsa)) {
            SigningCredential signer = hierarchy == rsa ? pair.rsa() : pair.mlDsa();
            assertEquals(3, signer.chain().size());
            PkixValidation.validate(signer.chain(), hierarchy.root().certificate(), crls(hierarchy));
        }
    }

    @Test
    void rantaiDitolakBilaRootBerbeda() {
        assertThrows(CertPathValidatorException.class,
                () -> PkixValidation.validate(pair.mlDsa().chain(), rsa.root().certificate(), null));
    }

    @Test
    void pasanganSertifikatPunyaIdentitasSamaDanKunciBerbeda() throws Exception {
        X509Certificate rsaCert = pair.rsa().certificate();
        X509Certificate mldsaCert = pair.mlDsa().certificate();
        assertEquals(rsaCert.getSubjectX500Principal(), mldsaCert.getSubjectX500Principal());
        assertNotEquals(rsaCert.getSerialNumber(), mldsaCert.getSerialNumber());
        assertTrue(rsaCert.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.36"));
        assertTrue(mldsaCert.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.36"));
        boolean[] keyUsage = mldsaCert.getKeyUsage();
        assertTrue(keyUsage[0], "digitalSignature");
        assertTrue(keyUsage[1], "nonRepudiation");
        assertFalse(keyUsage[5], "keyCertSign tidak boleh ada");
    }

    @Test
    void sertifikatPenandaTanganMemuatKebijakanSertifikatUji() throws Exception {
        for (X509Certificate certificate : List.of(pair.rsa().certificate(), pair.mlDsa().certificate())) {
            byte[] extension = certificate.getExtensionValue("2.5.29.32");
            assertTrue(extension != null, "certificatePolicies harus ada");
            org.bouncycastle.asn1.x509.CertificatePolicies policies = org.bouncycastle.asn1.x509.CertificatePolicies
                    .getInstance(org.bouncycastle.asn1.ASN1OctetString.getInstance(extension).getOctets());
            assertEquals(TestPkiFactory.TEST_CERTIFICATE_POLICY.getId(),
                    policies.getPolicyInformation()[0].getPolicyIdentifier().getId());
        }
        assertEquals("1.3.6.1.4.1.32473.2.1", TestPkiFactory.TEST_CERTIFICATE_POLICY.getId());
    }

    @Test
    void sertifikatTsaPunyaEkuTimeStampingKritis() throws Exception {
        for (CaHierarchy hierarchy : List.of(rsa, mldsa)) {
            SigningCredential tsa = factory.issueTsa(hierarchy.intermediate(), "TSA Uji " + hierarchy.family().label(),
                    TestPkiFactory.TSA_VALIDITY);
            X509Certificate cert = tsa.certificate();
            assertEquals(List.of("1.3.6.1.5.5.7.3.8"), cert.getExtendedKeyUsage());
            assertTrue(cert.getCriticalExtensionOIDs().contains("2.5.29.37"), "EKU harus kritis (RFC 3161)");
            PkixValidation.validate(tsa.chain(), hierarchy.root().certificate(), crls(hierarchy));
        }
    }

    @Test
    void sertifikatDicabutDitolakSaatCekCrl() throws Exception {
        for (CaHierarchy hierarchy : List.of(rsa, mldsa)) {
            SigningCredential revoked = factory.issueSigner(hierarchy.intermediate(), IDENTITY);
            X509CRL crl = factory.createCrl(hierarchy.intermediate(),
                    List.of(TestPkiFactory.RevokedCertificate.keyCompromise(revoked.certificate())), 2);
            assertTrue(crl.isRevoked(revoked.certificate()));
            crl.verify(hierarchy.intermediate().certificate().getPublicKey());
            X509CRL rootCrl = factory.createCrl(hierarchy.root(), List.of(), 1);

            CertPathValidatorException error = assertThrows(CertPathValidatorException.class,
                    () -> PkixValidation.validate(revoked.chain(), hierarchy.root().certificate(),
                            List.of(crl, rootCrl)));
            assertTrue(error.getMessage().toLowerCase().contains("revocation")
                    || error.getMessage().toLowerCase().contains("dicabut"), error.getMessage());
            // Sertifikat lain dari CA yang sama tetap sah dengan CRL yang sama.
            SigningCredential other = hierarchy == rsa ? pair.rsa() : pair.mlDsa();
            PkixValidation.validate(other.chain(), hierarchy.root().certificate(), List.of(crl, rootCrl));
        }
    }

    @Test
    void pkcs12BisaDisimpanDanDibacaKembali() throws Exception {
        for (SigningCredential credential : List.of(pair.rsa(), pair.mlDsa())) {
            byte[] p12 = Pkcs12Credentials.write(credential, "penanda-tangan", PASSWORD);
            SigningCredential loaded = Pkcs12Credentials.read(p12, PASSWORD);
            assertEquals(credential.algorithm(), loaded.algorithm());
            assertEquals(credential.chain(), loaded.chain());
            assertArrayEquals(credential.privateKey().getEncoded(), loaded.privateKey().getEncoded());
            assertThrows(GeneralSecurityException.class,
                    () -> Pkcs12Credentials.read(p12, "salah".toCharArray()));
        }
    }

    @Test
    void pdfHybridDenganSertifikatTerbitanCaSah() throws Exception {
        SigningCredential rsaLoaded = Pkcs12Credentials.read(
                Pkcs12Credentials.write(pair.rsa(), "rsa", PASSWORD), PASSWORD);
        SigningCredential mldsaLoaded = Pkcs12Credentials.read(
                Pkcs12Credentials.write(pair.mlDsa(), "mldsa", PASSWORD), PASSWORD);
        byte[] pdf = SamplePdf.create("Surat Uji CA", "Ditandatangani dengan sertifikat terbitan CA uji.");
        PadesSigner signer = new PadesSigner();
        byte[] signed = signer.signInvisible(pdf, rsaLoaded, SignatureDetails.of(IDENTITY.commonName(), "RSA"));
        signed = signer.signInvisible(signed, mldsaLoaded, SignatureDetails.of(IDENTITY.commonName(), "ML-DSA"));

        List<SignatureReport> reports = new PdfSignatureVerifier().verify(signed);
        assertEquals(2, reports.size());
        assertTrue(reports.get(0).valid(), reports.get(0).message());
        assertTrue(reports.get(1).valid(), reports.get(1).message());
        assertTrue(reports.get(1).coversWholeDocument());
        assertTrue(reports.get(1).signerSubject().contains("Budi Penanda Tangan Uji"));
    }

    private static List<X509CRL> crls(CaHierarchy hierarchy) throws GeneralSecurityException {
        return List.of(factory.createCrl(hierarchy.intermediate(), List.of(), 1),
                factory.createCrl(hierarchy.root(), List.of(), 1));
    }
}
