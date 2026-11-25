package id.pkl.signingpqc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HybridSigningSpikeTest {

    private static SigningCredential rsa;
    private static SigningCredential mldsa;
    private static byte[] hybrid;

    @BeforeAll
    static void signOnce() throws Exception {
        rsa = SpikeCredentials.selfSigned(SignatureAlgorithm.RSA_3072, "CN=Uji RSA, O=SigningPQC Uji, C=ID");
        mldsa = SpikeCredentials.selfSigned(SignatureAlgorithm.ML_DSA_65, "CN=Uji ML-DSA, O=SigningPQC Uji, C=ID");
        byte[] original = SamplePdf.create("Surat Uji", "Isi dokumen sintetis.");
        PadesSigner signer = new PadesSigner();
        byte[] afterRsa = signer.signInvisible(original, rsa, SignatureDetails.of("Uji", "RSA"));
        hybrid = signer.signInvisible(afterRsa, mldsa, SignatureDetails.of("Uji", "ML-DSA"));
    }

    @Test
    void bothSignaturesAreValidAndMlDsaCoversWholeDocument() throws Exception {
        List<SignatureReport> reports = new PdfSignatureVerifier().verify(hybrid);

        assertEquals(2, reports.size());
        SignatureReport first = reports.get(0);
        SignatureReport second = reports.get(1);

        assertTrue(first.valid(), first.message());
        assertTrue(first.signatureAlgorithm().startsWith("RSA"), first.signatureAlgorithm());
        assertEquals("SHA-256", first.digestAlgorithm());
        assertFalse(first.coversWholeDocument(), "RSA ditimpa revisi ML-DSA berikutnya");

        assertTrue(second.valid(), second.message());
        assertEquals("ML-DSA-65", second.signatureAlgorithm());
        assertEquals("SHA-512", second.digestAlgorithm());
        assertTrue(second.coversWholeDocument(), "ML-DSA harus menutup seluruh dokumen");
        assertEquals("ETSI.CAdES.detached", second.subFilter());
    }

    @Test
    void tamperingIsDetectedByBothSignatures() throws Exception {
        byte[] tampered = hybrid.clone();
        int target = indexOf(tampered, "Isi dokumen".getBytes());
        // Ubah satu byte di dalam stream isi halaman (tidak terkompresi pada PDF contoh).
        if (target < 0) {
            target = 200;
        }
        tampered[target] ^= 0x01;

        List<SignatureReport> reports = new PdfSignatureVerifier().verify(tampered);
        assertEquals(2, reports.size());
        reports.forEach(report -> assertFalse(report.valid(), "Harus ditolak: " + report.message()));
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
