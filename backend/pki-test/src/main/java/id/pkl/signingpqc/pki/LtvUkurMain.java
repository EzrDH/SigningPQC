package id.pkl.signingpqc.pki;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import id.pkl.signingpqc.core.PadesSigner;
import id.pkl.signingpqc.core.SamplePdf;
import id.pkl.signingpqc.core.SignatureDetails;
import id.pkl.signingpqc.core.SigningCredential;
import id.pkl.signingpqc.core.TimestampClient;
import id.pkl.signingpqc.core.ltv.LtvService;
import id.pkl.signingpqc.core.ltv.TimestampProfile;
import id.pkl.signingpqc.core.validation.AlgorithmPolicy;
import id.pkl.signingpqc.core.validation.DocumentValidator;
import id.pkl.signingpqc.core.validation.ValidationReport;

/**
 * Pengukuran untuk catatan T-012: ukuran berkas setelah setiap lapisan dan waktu proses rata-rata setiap lapisan
 * (3 putaran pemanasan dibuang, lalu 20 putaran diukur). Hanya data sintetis.
 */
public final class LtvUkurMain {

    private static final int WARMUP = 3;
    private static final int RUNS = 20;

    private LtvUkurMain() {
    }

    public static void main(String[] args) throws Exception {
        TestPkiFactory factory = new TestPkiFactory();
        SignerIdentity identity = new SignerIdentity("Budi Penanda Tangan Uji", "Kepala Seksi Uji", "Unit Uji",
                "SigningPQC Uji");
        LongTermPki lt = LongTermPki.create(factory, identity);
        List<CertificateAuthority> cas = List.of(lt.rsa().root(), lt.rsa().intermediate(), lt.mlDsa().root(),
                lt.mlDsa().intermediate());
        DocumentValidator validator = new DocumentValidator(
                Set.of(lt.rsa().root().certificate(), lt.mlDsa().root().certificate()), AlgorithmPolicy.defaults(),
                Duration.ofDays(30));
        Instant renewal = lt.tsaRsaGen1().certificate().getNotAfter().toInstant().minus(Duration.ofDays(10));
        Instant pqc = Instant.parse("2034-06-01T00:00:00Z");
        Instant check = Instant.parse("2040-01-01T00:00:00Z");
        PadesSigner signer = new PadesSigner();
        SignatureDetails details = SignatureDetails.of(identity.commonName(), "Persetujuan dokumen");
        byte[] pdf = SamplePdf.create("Ijazah Uji", "Dokumen sintetis untuk pengukuran lapisan LTV.");

        Map<String, long[]> nanos = new LinkedHashMap<>();
        for (String step : List.of("1. Tanda tangan RSA + signature timestamp G1",
                "2. Tanda tangan ML-DSA + signature timestamp ML-DSA", "3. DSS (rantai + CRL)",
                "4. Document timestamp TSA RSA G1", "5. Pembaruan: DSS + timestamp TSA RSA G2",
                "6. Pembaruan: DSS + timestamp TSA ML-DSA", "Validasi 07 pada 2040")) {
            nanos.put(step, new long[RUNS]);
        }
        Map<String, Integer> sizes = new LinkedHashMap<>();
        String status = "";
        for (int run = 0; run < WARMUP + RUNS; run++) {
            Instant now = Instant.now();
            long[] t = new long[8];
            t[0] = System.nanoTime();
            byte[] rsa = signer.signInvisible(pdf, lt.signer().rsa(), details, client(lt.tsaRsaGen1(), now));
            t[1] = System.nanoTime();
            byte[] bt = signer.signInvisible(rsa, lt.signer().mlDsa(), details, client(lt.tsaMlDsa(), now));
            t[2] = System.nanoTime();
            byte[] ltPdf = LtvService.addValidationData(bt, source(cas, factory, now));
            t[3] = System.nanoTime();
            byte[] lta = LtvService.addDocumentTimestamp(ltPdf, client(lt.tsaRsaGen1(), now), TimestampProfile.RSA);
            t[4] = System.nanoTime();
            byte[] g2 = LtvService.renew(lta, source(cas, factory, renewal), client(lt.tsaRsaGen2(), renewal),
                    TimestampProfile.RSA);
            t[5] = System.nanoTime();
            byte[] pq = LtvService.renew(g2, source(cas, factory, pqc), client(lt.tsaMlDsa(), pqc),
                    TimestampProfile.ML_DSA);
            t[6] = System.nanoTime();
            ValidationReport report = validator.validate(pq, check);
            t[7] = System.nanoTime();
            if (run >= WARMUP) {
                int i = 0;
                for (long[] values : nanos.values()) {
                    values[run - WARMUP] = t[i + 1] - t[i];
                    i++;
                }
            }
            sizes.put("PDF asli", pdf.length);
            sizes.put("Lapisan 1: + tanda tangan RSA (B-T)", rsa.length);
            sizes.put("Lapisan 2: + tanda tangan ML-DSA (B-T)", bt.length);
            sizes.put("Lapisan 3: + DSS (B-LT)", ltPdf.length);
            sizes.put("Lapisan 4: + timestamp RSA G1 (B-LTA)", lta.length);
            sizes.put("Lapisan 5: + DSS + timestamp RSA G2", g2.length);
            sizes.put("Lapisan 6: + DSS + timestamp ML-DSA", pq.length);
            status = report.status() + ", " + report.layers().size() + " lapisan";
        }

        System.out.println("| Langkah | Rata-rata (ms) | Min (ms) | Maks (ms) |");
        System.out.println("|---|---:|---:|---:|");
        for (Map.Entry<String, long[]> entry : nanos.entrySet()) {
            long[] values = entry.getValue();
            double sum = 0;
            long min = Long.MAX_VALUE;
            long max = 0;
            for (long value : values) {
                sum += value;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            System.out.printf("| %s | %.1f | %.1f | %.1f |%n", entry.getKey(), sum / values.length / 1e6, min / 1e6,
                    max / 1e6);
        }
        System.out.println();
        System.out.println("| Tahap | Ukuran (byte) | Tambahan (byte) |");
        System.out.println("|---|---:|---:|");
        int previous = 0;
        for (Map.Entry<String, Integer> entry : sizes.entrySet()) {
            System.out.printf("| %s | %,d | %s |%n", entry.getKey(), entry.getValue(),
                    previous == 0 ? "-" : String.format("%,d", entry.getValue() - previous));
            previous = entry.getValue();
        }
        System.out.println();
        System.out.println("Hasil validasi 07 pada 2040: " + status + " (" + RUNS + " putaran, " + WARMUP
                + " pemanasan dibuang)");
    }

    private static TimestampClient client(SigningCredential tsa, Instant at) throws Exception {
        return new TimestampClient(new LocalTsa(tsa, Clock.fixed(at, ZoneOffset.UTC)));
    }

    private static TestPkiRevocationSource source(List<CertificateAuthority> cas, TestPkiFactory factory, Instant at) {
        return new TestPkiRevocationSource(cas, factory, Clock.fixed(at, ZoneOffset.UTC));
    }
}
