package id.pkl.signingpqc.pki;

import static id.pkl.signingpqc.pki.LtvFixtures.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.junit.jupiter.api.Test;

import id.pkl.signingpqc.core.ltv.LtvService;
import id.pkl.signingpqc.core.ltv.TimestampProfile;
import id.pkl.signingpqc.core.validation.AlgorithmPolicy;
import id.pkl.signingpqc.core.validation.DocumentValidator;
import id.pkl.signingpqc.core.validation.LayerKind;
import id.pkl.signingpqc.core.validation.LayerReport;
import id.pkl.signingpqc.core.validation.OverallStatus;
import id.pkl.signingpqc.core.validation.ReasonCode;
import id.pkl.signingpqc.core.validation.ValidationReport;

/** Uji validator dokumen berlapis: TC-01, TC-03 s.d. TC-07 dan Review Focus 2-4. */
class DocumentValidatorTest {

    private static final Instant PQC_RENEWAL = Instant.parse("2034-06-01T00:00:00Z");

    private static TestPkiRevocationSource source(Instant at) throws Exception {
        return new TestPkiRevocationSource(LtvFixtures.cas(), LtvFixtures.factory(), LtvFixtures.at(at));
    }

    private static DocumentValidator validator() throws Exception {
        return new DocumentValidator(LtvFixtures.trustAnchors(), AlgorithmPolicy.defaults(), Duration.ofDays(30));
    }

    private static Instant gen1NotAfter() throws Exception {
        return LtvFixtures.pki().tsaRsaGen1().certificate().getNotAfter().toInstant();
    }

    /** Lapisan 1-4: hybrid B-T, DSS pada T0, document timestamp TSA RSA G1 pada T0. */
    private static byte[] ltaGen1() throws Exception {
        byte[] lt = LtvService.addValidationData(LtvFixtures.hybridBt(), source(T0));
        return LtvService.addDocumentTimestamp(lt, LtvFixtures.tsa(LtvFixtures.pki().tsaRsaGen1(), T0),
                TimestampProfile.RSA);
    }

    /** Lapisan 5: pembaruan TSA RSA G2 sepuluh hari sebelum G1 kedaluwarsa. */
    private static byte[] renewedGen2() throws Exception {
        Instant t1 = gen1NotAfter().minus(Duration.ofDays(10));
        return LtvService.renew(ltaGen1(), source(t1), LtvFixtures.tsa(LtvFixtures.pki().tsaRsaGen2(), t1),
                TimestampProfile.RSA);
    }

    /** Lapisan 6: pembaruan TSA ML-DSA pada 2034-06-01. */
    private static byte[] renewedPqc() throws Exception {
        return LtvService.renew(renewedGen2(), source(PQC_RENEWAL),
                LtvFixtures.tsa(LtvFixtures.pki().tsaMlDsa(), PQC_RENEWAL), TimestampProfile.ML_DSA);
    }

    private static boolean hasCode(ValidationReport report, ReasonCode code) {
        return report.layers().stream().anyMatch(layer -> layer.code() == code);
    }

    private static List<LayerReport> ofKind(ValidationReport report, LayerKind kind) {
        return report.layers().stream().filter(layer -> layer.kind() == kind).toList();
    }

    private static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    @Test
    void tc01HybridBtSahSekarang() throws Exception {
        ValidationReport report = validator().validate(LtvFixtures.hybridBt(), T0.plus(Duration.ofHours(1)));
        assertEquals(OverallStatus.SAH, report.status(), report.toString());
        assertTrue(report.hybridComplete());
        assertEquals(2, ofKind(report, LayerKind.TANDA_TANGAN).size());
        ofKind(report, LayerKind.TANDA_TANGAN).forEach(layer -> assertEquals("B-T", layer.pades()));
    }

    @Test
    void tc03SatuByteDiubah() throws Exception {
        byte[] pdf = LtvFixtures.hybridBt();
        // Isi halaman terkompresi; ubah satu byte data stream pertama (struktur PDF tetap terbaca).
        int stream = indexOf(pdf, "stream".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(stream > 0, "PDF contoh harus punya stream");
        pdf[stream + 10] ^= 0x01;
        ValidationReport report = validator().validate(pdf, T0.plus(Duration.ofHours(1)));
        assertEquals(OverallStatus.TIDAK_SAH, report.status());
        assertTrue(hasCode(report, ReasonCode.DOKUMEN_BERUBAH), report.toString());
    }

    @Test
    void tc04PenandaTanganDicabutSebelumMenandatangani() throws Exception {
        TestPkiRevocationSource revoking = source(T0);
        revoking.revoke(LtvFixtures.pki().signer().rsa().certificate(), T0.minusSeconds(60));
        byte[] lt = LtvService.addValidationData(LtvFixtures.hybridBt(), revoking);
        ValidationReport report = validator().validate(lt, T0.plus(Duration.ofHours(1)));
        assertEquals(OverallStatus.TIDAK_SAH, report.status());
        assertTrue(hasCode(report, ReasonCode.SERTIFIKAT_DICABUT), report.toString());
    }

    @Test
    void tc05LtaSahSekarang() throws Exception {
        ValidationReport report = validator().validate(ltaGen1(), T0.plus(Duration.ofHours(1)));
        assertEquals(OverallStatus.SAH, report.status(), report.toString());
        assertEquals(1, ofKind(report, LayerKind.DATA_VALIDASI).size());
        assertEquals(1, ofKind(report, LayerKind.DOCUMENT_TIMESTAMP).size());
        assertEquals("LT", ofKind(report, LayerKind.DATA_VALIDASI).get(0).pades());
        assertEquals("LTA", ofKind(report, LayerKind.DOCUMENT_TIMESTAMP).get(0).pades());
        assertFalse(report.renewalRecommended());
    }

    @Test
    void tc06RantaiPembaruanSahTahun2040() throws Exception {
        ValidationReport report = validator().validate(renewedPqc(), Instant.parse("2040-01-01T00:00:00Z"));
        assertEquals(OverallStatus.SAH, report.status(), report.toString());
        assertTrue(report.hybridComplete());
        assertEquals(3, ofKind(report, LayerKind.DOCUMENT_TIMESTAMP).size());
        for (LayerReport layer : report.layers()) {
            if (layer.algorithm().startsWith("RSA")) {
                assertTrue(layer.provenAt().isBefore(AlgorithmPolicy.RSA_UNSAFE_FROM),
                        "lapisan RSA harus dibuktikan sebelum 2035: " + layer);
            }
        }
        assertEquals(LtvFixtures.pki().tsaMlDsa().certificate().getNotAfter().toInstant(), report.protectionUntil());
    }

    @Test
    void tc07TanpaPembaruanSetelahGen1Kedaluwarsa() throws Exception {
        ValidationReport report = validator().validate(ltaGen1(), gen1NotAfter().plus(Duration.ofDays(1)));
        assertEquals(OverallStatus.TIDAK_DAPAT_DIBUKTIKAN, report.status(), report.toString());
        assertTrue(hasCode(report, ReasonCode.TSA_KEDALUWARSA_TANPA_PEMBARUAN), report.toString());
    }

    @Test
    void hanyaRsaSetelah2035() throws Exception {
        ValidationReport report = validator().validate(renewedGen2(), Instant.parse("2036-01-01T00:00:00Z"));
        assertEquals(OverallStatus.TIDAK_DAPAT_DIBUKTIKAN, report.status(), report.toString());
        assertTrue(hasCode(report, ReasonCode.ALGORITMA_TIDAK_AMAN_PADA_WAKTU_PEMERIKSAAN), report.toString());
    }

    @Test
    void peringatanPembaruan30Hari() throws Exception {
        ValidationReport report = validator().validate(ltaGen1(), gen1NotAfter().minus(Duration.ofDays(20)));
        assertEquals(OverallStatus.SAH, report.status(), report.toString());
        assertTrue(report.renewalRecommended());
        assertEquals(gen1NotAfter(), report.protectionUntil());
    }

    @Test
    void mlDsaRusakMelanggarKebijakanAnd() throws Exception {
        byte[] pdf = LtvFixtures.hybridBt();
        int[] byteRange;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<PDSignature> signatures = new ArrayList<>(document.getSignatureDictionaries());
            signatures.sort(java.util.Comparator.comparingInt(s -> s.getByteRange()[1]));
            byteRange = signatures.get(1).getByteRange();
        }
        int position = byteRange[1] + 1 + 2000;
        pdf[position] = (byte) (pdf[position] == '0' ? '1' : '0');
        ValidationReport report = validator().validate(pdf, T0.plus(Duration.ofHours(1)));
        assertEquals(OverallStatus.TIDAK_SAH, report.status(), report.toString());
        assertFalse(report.hybridComplete());
    }

    @Test
    void ltaTanpaDss() throws Exception {
        byte[] pdf = LtvService.addDocumentTimestamp(LtvFixtures.hybridBt(),
                LtvFixtures.tsa(LtvFixtures.pki().tsaRsaGen1(), T0), TimestampProfile.RSA);
        ValidationReport report = validator().validate(pdf, T0.plus(Duration.ofHours(1)));
        assertEquals(OverallStatus.TIDAK_DAPAT_DIBUKTIKAN, report.status(), report.toString());
        assertTrue(hasCode(report, ReasonCode.DATA_VALIDASI_TIDAK_ADA), report.toString());
    }

    @Test
    void waktuPemeriksaanSebelumTandaTangan() throws Exception {
        ValidationReport report = validator().validate(LtvFixtures.hybridBt(), T0.minus(Duration.ofDays(1)));
        assertNotEquals(OverallStatus.SAH, report.status());
        assertTrue(hasCode(report, ReasonCode.WAKTU_PEMERIKSAAN_TIDAK_SAH), report.toString());
    }

    @Test
    void revisiTambahanSetelahLapisanTerakhir() throws Exception {
        byte[] lta = ltaGen1();
        byte[] changed;
        try (PDDocument document = Loader.loadPDF(lta)) {
            PDPage page = document.getPage(0);
            PDAnnotationText note = new PDAnnotationText();
            note.setRectangle(new PDRectangle(50, 50, 20, 20));
            note.setContents("Catatan yang ditambahkan setelah lapisan terakhir");
            List<PDAnnotation> annotations = new ArrayList<>(page.getAnnotations());
            annotations.add(note);
            page.setAnnotations(annotations);
            note.getCOSObject().setNeedToBeUpdated(true);
            page.getCOSObject().setNeedToBeUpdated(true);
            COSArray annots = page.getCOSObject().getCOSArray(COSName.ANNOTS);
            annots.setNeedToBeUpdated(true);
            document.getDocumentCatalog().getCOSObject().setNeedToBeUpdated(true);
            document.getDocumentCatalog().getPages().getCOSObject().setNeedToBeUpdated(true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.saveIncremental(out);
            changed = out.toByteArray();
        }
        assertTrue(changed.length > lta.length);
        ValidationReport report = validator().validate(changed, T0.plus(Duration.ofHours(1)));
        assertTrue(report.warnings().contains(ReasonCode.PERUBAHAN_SETELAH_LAPISAN_TERAKHIR), report.toString());
        assertNotEquals(OverallStatus.SAH, report.status());
    }
}
