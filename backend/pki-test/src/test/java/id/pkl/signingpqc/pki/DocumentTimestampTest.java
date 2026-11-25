package id.pkl.signingpqc.pki;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.tsp.TimeStampToken;
import org.junit.jupiter.api.Test;

import id.pkl.signingpqc.core.PdfSignatureVerifier;
import id.pkl.signingpqc.core.SignatureReport;
import id.pkl.signingpqc.core.ltv.DocumentTimestamper;
import id.pkl.signingpqc.core.ltv.TimestampProfile;

/** Uji document timestamp (PAdES B-LTA, lapisan 4 dan berikutnya). */
class DocumentTimestampTest {

    private record Stamp(PDSignature dictionary, TimeStampToken token, byte[] signedContent) { }

    private static List<Stamp> stamps(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getSignatureDictionaries().stream()
                    .filter(s -> "ETSI.RFC3161".equals(s.getSubFilter()))
                    .map(s -> {
                        try {
                            byte[] der = PdfSignatureVerifier.firstDerObject(s.getContents(pdf));
                            return new Stamp(s, new TimeStampToken(new CMSSignedData(der)), s.getSignedContent(pdf));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }).toList();
        }
    }

    private static void assertSignaturesStillValid(byte[] pdf) throws Exception {
        List<SignatureReport> reports = new PdfSignatureVerifier().verify(pdf).stream()
                .filter(r -> "ETSI.CAdES.detached".equals(r.subFilter())).toList();
        assertEquals(2, reports.size());
        reports.forEach(r -> assertTrue(r.valid(), r.message()));
    }

    @Test
    void timestampRsaMenutupSeluruhDokumen() throws Exception {
        LongTermPki pki = LtvFixtures.pki();
        byte[] bt = LtvFixtures.hybridBt();
        byte[] lta = DocumentTimestamper.add(bt, LtvFixtures.tsa(pki.tsaRsaGen1(), LtvFixtures.T0),
                TimestampProfile.RSA);
        try (PDDocument document = Loader.loadPDF(lta)) {
            List<PDSignature> all = document.getSignatureDictionaries();
            assertEquals(3, all.size());
            PDSignature last = all.get(2);
            assertEquals("ETSI.RFC3161", last.getSubFilter());
            assertEquals("Adobe.PPKLite", last.getFilter());
            assertEquals("DocTimeStamp", last.getCOSObject().getNameAsString(org.apache.pdfbox.cos.COSName.TYPE));
            int[] range = last.getByteRange();
            assertEquals(lta.length, range[2] + range[3]);
        }
        Stamp stamp = stamps(lta).get(0);
        assertEquals("2.16.840.1.101.3.4.2.1", stamp.token().getTimeStampInfo().getMessageImprintAlgOID().getId());
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(stamp.signedContent()),
                stamp.token().getTimeStampInfo().getMessageImprintDigest());
        assertEquals(LtvFixtures.T0, stamp.token().getTimeStampInfo().getGenTime().toInstant());
        assertSignaturesStillValid(lta);
    }

    @Test
    void timestampMlDsaMemakaiSha512() throws Exception {
        LongTermPki pki = LtvFixtures.pki();
        byte[] lta = DocumentTimestamper.add(LtvFixtures.hybridBt(),
                LtvFixtures.tsa(pki.tsaMlDsa(), LtvFixtures.T0), TimestampProfile.ML_DSA);
        Stamp stamp = stamps(lta).get(0);
        assertEquals("2.16.840.1.101.3.4.2.3", stamp.token().getTimeStampInfo().getMessageImprintAlgOID().getId());
        assertArrayEquals(MessageDigest.getInstance("SHA-512").digest(stamp.signedContent()),
                stamp.token().getTimeStampInfo().getMessageImprintDigest());
        assertEquals("2.16.840.1.101.3.4.3.18",
                stamp.token().toCMSSignedData().getSignerInfos().getSigners().iterator().next().getEncryptionAlgOID());
        assertSignaturesStillValid(lta);
    }

    @Test
    void tigaTimestampBerurutan() throws Exception {
        LongTermPki pki = LtvFixtures.pki();
        Instant t1 = Instant.parse("2028-11-14T00:00:00Z");
        Instant t2 = Instant.parse("2034-06-01T00:00:00Z");
        byte[] pdf = DocumentTimestamper.add(LtvFixtures.hybridBt(),
                LtvFixtures.tsa(pki.tsaRsaGen1(), LtvFixtures.T0), TimestampProfile.RSA);
        pdf = DocumentTimestamper.add(pdf, LtvFixtures.tsa(pki.tsaRsaGen2(), t1), TimestampProfile.RSA);
        pdf = DocumentTimestamper.add(pdf, LtvFixtures.tsa(pki.tsaMlDsa(), t2), TimestampProfile.ML_DSA);
        List<Stamp> stamps = stamps(pdf);
        assertEquals(3, stamps.size());
        assertEquals(List.of(LtvFixtures.T0, t1, t2),
                stamps.stream().map(s -> s.token().getTimeStampInfo().getGenTime().toInstant()).toList());
        String[] digests = {"SHA-256", "SHA-256", "SHA-512"};
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(MessageDigest.getInstance(digests[i]).digest(stamps.get(i).signedContent()),
                    stamps.get(i).token().getTimeStampInfo().getMessageImprintDigest());
        }
        assertSignaturesStillValid(pdf);
    }
}
