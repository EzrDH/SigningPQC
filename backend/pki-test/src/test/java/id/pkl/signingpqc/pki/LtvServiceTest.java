package id.pkl.signingpqc.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.junit.jupiter.api.Test;

import id.pkl.signingpqc.core.ltv.DssReader;
import id.pkl.signingpqc.core.ltv.DssWriter;
import id.pkl.signingpqc.core.ltv.LtvService;
import id.pkl.signingpqc.core.ltv.TimestampProfile;
import id.pkl.signingpqc.core.ltv.ValidationData;

/** Uji alur LTV: data validasi (lapisan 3) dan pembaruan timestamp. */
class LtvServiceTest {

    private static TestPkiRevocationSource source(Instant at) throws Exception {
        return new TestPkiRevocationSource(LtvFixtures.cas(), LtvFixtures.factory(), LtvFixtures.at(at));
    }

    private static List<String> keys(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<String> keys = new java.util.ArrayList<>();
            for (PDSignature signature : document.getSignatureDictionaries()) {
                keys.add(DssWriter.vriKey(signature, pdf));
            }
            return keys;
        }
    }

    @Test
    void addValidationDataMencakupPenandaTanganDanTsa() throws Exception {
        LongTermPki pki = LtvFixtures.pki();
        byte[] lt = LtvService.addValidationData(LtvFixtures.hybridBt(), source(LtvFixtures.T0));
        Map<String, ValidationData> vri = DssReader.readVri(lt);
        List<String> keys = keys(lt);
        assertEquals(2, keys.size());
        assertEquals(java.util.Set.copyOf(keys), vri.keySet());

        ValidationData rsa = vri.get(keys.get(0));
        List<X509Certificate> expectedRsa = List.of(pki.signer().rsa().certificate(),
                pki.rsa().intermediate().certificate(), pki.rsa().root().certificate(), pki.tsaRsaGen1().certificate());
        assertTrue(rsa.certificates().containsAll(expectedRsa), "rantai penanda tangan RSA dan TSA RSA G1");
        assertTrue(rsa.crls().size() >= 2);

        ValidationData mlDsa = vri.get(keys.get(1));
        List<X509Certificate> expectedMlDsa = List.of(pki.signer().mlDsa().certificate(),
                pki.mlDsa().intermediate().certificate(), pki.mlDsa().root().certificate(),
                pki.tsaMlDsa().certificate());
        assertTrue(mlDsa.certificates().containsAll(expectedMlDsa), "rantai penanda tangan ML-DSA dan TSA ML-DSA");
        assertTrue(mlDsa.crls().size() >= 2);
    }

    @Test
    void renewMenambahVriUntukTimestampSebelumnya() throws Exception {
        LongTermPki pki = LtvFixtures.pki();
        Instant t1 = pki.tsaRsaGen1().certificate().getNotAfter().toInstant().minus(java.time.Duration.ofDays(10));
        byte[] lta = LtvService.addDocumentTimestamp(
                LtvService.addValidationData(LtvFixtures.hybridBt(), source(LtvFixtures.T0)),
                LtvFixtures.tsa(pki.tsaRsaGen1(), LtvFixtures.T0), TimestampProfile.RSA);
        String gen1Key = keys(lta).get(2);
        assertTrue(!DssReader.readVri(lta).containsKey(gen1Key), "sebelum pembaruan, timestamp G1 belum punya VRI");

        byte[] renewed = LtvService.renew(lta, source(t1), LtvFixtures.tsa(pki.tsaRsaGen2(), t1), TimestampProfile.RSA);
        assertEquals(4, keys(renewed).size());
        ValidationData gen1 = DssReader.readVri(renewed).get(gen1Key);
        assertTrue(gen1.certificates().contains(pki.tsaRsaGen1().certificate()));
        assertTrue(gen1.crls().stream().anyMatch(crl -> crl.getThisUpdate().toInstant().equals(t1)),
                "CRL baru dibuat pada waktu pembaruan");
    }
}
