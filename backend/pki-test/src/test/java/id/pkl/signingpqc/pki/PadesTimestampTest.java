package id.pkl.signingpqc.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.tsp.TimeStampToken;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import id.pkl.signingpqc.core.CadesSignatureBuilder;
import id.pkl.signingpqc.core.PadesSigner;
import id.pkl.signingpqc.core.PdfSignatureVerifier;
import id.pkl.signingpqc.core.SamplePdf;
import id.pkl.signingpqc.core.SignatureDetails;
import id.pkl.signingpqc.core.SignatureReport;
import id.pkl.signingpqc.core.SignatureTimestamps;
import id.pkl.signingpqc.core.SigningCredential;
import id.pkl.signingpqc.core.TimestampClient;
import id.pkl.signingpqc.core.TimestampException;
import id.pkl.signingpqc.core.TimestampReport;

/** Uji PAdES B-T: signature timestamp pada tanda tangan hybrid RSA + ML-DSA. */
class PadesTimestampTest {

    private static final SignerIdentity IDENTITY = new SignerIdentity("Budi Penanda Tangan Uji", "Kepala Seksi Uji",
            "Unit Uji", "SigningPQC Uji");

    private static TestPkiFactory factory;
    private static CaHierarchy rsa;
    private static CaHierarchy mldsa;
    private static SignerPair pair;
    private static LocalTsa rsaTsa;
    private static LocalTsa mldsaTsa;

    @BeforeAll
    static void setUp() throws Exception {
        factory = new TestPkiFactory();
        rsa = factory.createHierarchy(PkiFamily.RSA, "SigningPQC Uji");
        mldsa = factory.createHierarchy(PkiFamily.ML_DSA, "SigningPQC Uji");
        pair = factory.issueSignerPair(rsa, mldsa, IDENTITY);
        rsaTsa = new LocalTsa(factory.issueTsa(rsa.intermediate(), "TSA Uji RSA", TestPkiFactory.TSA_VALIDITY));
        mldsaTsa = new LocalTsa(factory.issueTsa(mldsa.intermediate(), "TSA Uji ML-DSA", TestPkiFactory.TSA_VALIDITY));
    }

    private static byte[] signHybridBt() throws Exception {
        PadesSigner signer = new PadesSigner();
        byte[] pdf = SamplePdf.create("Surat Uji B-T", "Tanda tangan hybrid dengan signature timestamp.");
        byte[] afterRsa = signer.signInvisible(pdf, pair.rsa(), SignatureDetails.of(IDENTITY.commonName(), "RSA"),
                new TimestampClient(rsaTsa));
        return signer.signInvisible(afterRsa, pair.mlDsa(), SignatureDetails.of(IDENTITY.commonName(), "ML-DSA"),
                new TimestampClient(mldsaTsa));
    }

    @Test
    void hybridBtKeduaTandaTanganPunyaTimestampSah() throws Exception {
        Instant before = Instant.now().minusSeconds(2);
        byte[] signed = signHybridBt();
        Instant after = Instant.now().plusSeconds(2);

        List<SignatureReport> reports = new PdfSignatureVerifier().verify(signed);
        assertEquals(2, reports.size());
        for (SignatureReport report : reports) {
            assertTrue(report.valid(), report.message());
            assertEquals("B-T", report.padesLevel());
            TimestampReport ts = report.signatureTimestamp();
            assertNotNull(ts);
            assertTrue(ts.valid(), ts.message());
            assertTrue(ts.time().isAfter(before) && ts.time().isBefore(after), ts.time().toString());
            // /M ditulis sebelum CMS dibuat (presisi detik), jadi tidak boleh lebih lambat dari timestamp.
            assertFalse(report.claimedSigningTime().isAfter(ts.time().plusSeconds(1)));
            System.out.printf("[%d] %s CMS %,d B, TSA %s (%s, imprint %s) %s%n", report.order(),
                    report.signatureAlgorithm(), report.cmsSizeBytes(), ts.tsaSubject(), ts.signatureAlgorithm(),
                    ts.imprintAlgorithm(), ts.time());
        }
        assertEquals("SHA-256", reports.get(0).signatureTimestamp().imprintAlgorithm());
        assertTrue(reports.get(0).signatureTimestamp().tsaSubject().contains("TSA Uji RSA"));
        assertEquals("SHA-512", reports.get(1).signatureTimestamp().imprintAlgorithm());
        assertEquals("ML-DSA-65", reports.get(1).signatureTimestamp().signatureAlgorithm());
        assertTrue(reports.get(1).coversWholeDocument());
    }

    @Test
    void tanpaTsaLevelnyaBb() throws Exception {
        byte[] pdf = SamplePdf.create("Surat Uji B-B", "Tanpa timestamp.");
        byte[] signed = new PadesSigner().signInvisible(pdf, pair.mlDsa(), SignatureDetails.of("Uji", "B-B"));
        SignatureReport report = new PdfSignatureVerifier().verify(signed).get(0);
        assertTrue(report.valid());
        assertNull(report.signatureTimestamp());
        assertEquals("B-B", report.padesLevel());
    }

    @Test
    void timestampAtasDataLainTerdeteksi() throws Exception {
        byte[] cms = new CadesSignatureBuilder().sign("isi".getBytes(), pair.mlDsa(), new TimestampClient(mldsaTsa));
        SignerInformation signer = new CMSSignedData(new org.bouncycastle.cms.CMSProcessableByteArray("isi".getBytes()),
                cms).getSignerInfos().getSigners().iterator().next();
        TimeStampToken token = SignatureTimestamps.tokenOf(signer);
        assertTrue(SignatureTimestamps.inspect(token, signer.getSignature()).valid());
        TimestampReport wrong = SignatureTimestamps.inspect(token, "nilai lain".getBytes());
        assertFalse(wrong.imprintMatches());
        assertFalse(wrong.valid());
    }

    @Test
    void penandatangananDibatalkanBilaTsaKedaluwarsa() throws Exception {
        SigningCredential shortLived = factory.issueTsa(mldsa.intermediate(), "TSA Uji Singkat", Duration.ofHours(1));
        LocalTsa expired = new LocalTsa(shortLived, Clock.offset(Clock.systemUTC(), Duration.ofHours(2)));
        byte[] pdf = SamplePdf.create("Surat Uji", "TSA kedaluwarsa.");
        TimestampException error = assertThrows(TimestampException.class, () -> new PadesSigner().signInvisible(pdf,
                pair.mlDsa(), SignatureDetails.of("Uji", "ML-DSA"), new TimestampClient(expired)));
        assertTrue(error.getMessage().contains("Sertifikat TSA tidak berlaku"), error.getMessage());
    }

    @Test
    void ubahSatuByteMembatalkanKeduaTandaTangan() throws Exception {
        byte[] signed = signHybridBt();
        // Byte ke-200 berada di bagian revisi awal (isi halaman), dicakup oleh kedua tanda tangan.
        int target = 200;
        signed[target] = (byte) (signed[target] ^ 0x01);
        for (SignatureReport report : new PdfSignatureVerifier().verify(signed)) {
            assertFalse(report.valid(), "tanda tangan " + report.order() + " harus ditolak");
            assertTrue(report.message().contains("telah diubah"), report.message());
        }
    }

    /** Eksperimen T-011: nama /Filter dan /SubFilter ML-DSA bisa diganti; CMS tetap sah bagi verifier kita. */
    @Test
    void namaHandlerEksperimenTercatatDanCmsTetapSah() throws Exception {
        byte[] pdf = SamplePdf.create("Surat Uji Eksperimen", "Kamus tanda tangan dengan handler lain.");
        byte[] signed = new PadesSigner().signInvisible(pdf, pair.rsa(), SignatureDetails.of(IDENTITY.commonName(), "RSA"));
        signed = new PadesSigner().withDictionaryNames("SigningPQC.PQC", "SigningPQC.CAdES.MLDSA")
                .signInvisible(signed, pair.mlDsa(), SignatureDetails.of(IDENTITY.commonName(), "ML-DSA"));
        List<SignatureReport> reports = new PdfSignatureVerifier().verify(signed);
        assertEquals("ETSI.CAdES.detached", reports.get(0).subFilter());
        assertEquals("SigningPQC.CAdES.MLDSA", reports.get(1).subFilter());
        assertTrue(reports.get(1).valid(), reports.get(1).message());
        try (var document = org.apache.pdfbox.Loader.loadPDF(signed)) {
            assertEquals("SigningPQC.PQC", document.getSignatureDictionaries().get(1).getFilter());
        }
        assertThrows(IllegalArgumentException.class, () -> new PadesSigner().withDictionaryNames(" ", "x"));
    }
}
