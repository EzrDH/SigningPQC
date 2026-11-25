package id.pkl.signingpqc.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.junit.jupiter.api.Test;

import id.pkl.signingpqc.core.PdfSignatureVerifier;
import id.pkl.signingpqc.core.SamplePdf;
import id.pkl.signingpqc.core.SignatureReport;
import id.pkl.signingpqc.core.ltv.DssReader;
import id.pkl.signingpqc.core.ltv.DssWriter;
import id.pkl.signingpqc.core.ltv.ValidationData;

/** Uji kamus DSS (PAdES B-LT, lapisan 3). */
class DssTest {

    private static Set<String> der(List<?> items) throws Exception {
        Set<String> out = new HashSet<>();
        for (Object item : items) {
            byte[] encoded = item instanceof X509Certificate c ? c.getEncoded() : ((X509CRL) item).getEncoded();
            out.add(java.util.HexFormat.of().formatHex(encoded));
        }
        return out;
    }

    private static ValidationData rsaData() throws Exception {
        LongTermPki pki = LtvFixtures.pki();
        TestPkiFactory f = LtvFixtures.factory();
        return new ValidationData(pki.signer().rsa().chain(),
                List.of(f.createCrl(pki.rsa().root(), List.of(), 1), f.createCrl(pki.rsa().intermediate(), List.of(), 1)));
    }

    private static ValidationData mlDsaData() throws Exception {
        LongTermPki pki = LtvFixtures.pki();
        TestPkiFactory f = LtvFixtures.factory();
        return new ValidationData(pki.signer().mlDsa().chain(),
                List.of(f.createCrl(pki.mlDsa().root(), List.of(), 1),
                        f.createCrl(pki.mlDsa().intermediate(), List.of(), 1)));
    }

    @Test
    void dssDitulisDanTandaTanganTetapSah() throws Exception {
        byte[] bt = LtvFixtures.hybridBt();
        Map<String, ValidationData> byKey = new LinkedHashMap<>();
        try (PDDocument document = Loader.loadPDF(bt)) {
            List<PDSignature> signatures = document.getSignatureDictionaries();
            byKey.put(DssWriter.vriKey(signatures.get(0), bt), rsaData());
            byKey.put(DssWriter.vriKey(signatures.get(1), bt), mlDsaData());
        }
        byte[] lt = DssWriter.append(bt, byKey);

        assertTrue(lt.length > bt.length);
        assertEquals(java.util.HexFormat.of().formatHex(bt),
                java.util.HexFormat.of().formatHex(java.util.Arrays.copyOf(lt, bt.length)), "pembaruan inkremental");
        ValidationData all = DssReader.readAll(lt);
        ValidationData expected = rsaData().merge(mlDsaData());
        assertEquals(der(expected.certificates()), der(all.certificates()));
        assertEquals(4, all.crls().size());
        assertEquals(byKey.keySet(), DssReader.readVri(lt).keySet());
        assertEquals(der(rsaData().certificates()),
                der(DssReader.readVri(lt).get(byKey.keySet().iterator().next()).certificates()));
        for (SignatureReport report : new PdfSignatureVerifier().verify(lt)) {
            assertTrue(report.valid(), report.message());
        }
    }

    @Test
    void dssLamaDigabung() throws Exception {
        byte[] bt = LtvFixtures.hybridBt();
        byte[] once = DssWriter.append(bt, Map.of("AAAA", rsaData()));
        byte[] twice = DssWriter.append(once, Map.of("BBBB", mlDsaData()));
        ValidationData all = DssReader.readAll(twice);
        assertEquals(der(rsaData().merge(mlDsaData()).certificates()), der(all.certificates()));
        assertEquals(all.certificates().size(), der(all.certificates()).size(), "tanpa duplikat");
        assertEquals(Set.of("AAAA", "BBBB"), DssReader.readVri(twice).keySet());
        byte[] again = DssWriter.append(twice, Map.of("AAAA", rsaData()));
        assertEquals(all.certificates().size(), DssReader.readAll(again).certificates().size(), "data sama tidak ditulis ulang");
    }

    @Test
    void pdfTanpaDssMemberiDataKosong() throws Exception {
        byte[] pdf = SamplePdf.create("Tanpa DSS", "kosong");
        assertTrue(DssReader.readAll(pdf).certificates().isEmpty());
        assertTrue(DssReader.readAll(pdf).crls().isEmpty());
        assertTrue(DssReader.readVri(pdf).isEmpty());
    }
}
