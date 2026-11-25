package id.pkl.signingpqc.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;

/**
 * Bukti teknis Sprint 0: PDF ditandatangani RSA-3072 lalu ML-DSA-65 (keduanya invisible),
 * diverifikasi, dan setiap CMS diekspor agar bisa dicek silang dengan OpenSSL 3.5.
 *
 * <p>Pemakaian: {@code java -cp ... id.pkl.signingpqc.core.SpikeMain [folder-keluaran]}
 */
public final class SpikeMain {

    private SpikeMain() {
    }

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "spike-output");
        Files.createDirectories(outDir);

        byte[] original = SamplePdf.create("Surat Uji SigningPQC",
                "Dokumen sintetis untuk bukti teknis tanda tangan hybrid RSA + ML-DSA.",
                "Tidak memuat data asli BSrE.");
        Files.write(outDir.resolve("00-asli.pdf"), original);

        SigningCredential rsa = SpikeCredentials.selfSigned(SignatureAlgorithm.RSA_3072,
                "CN=Penanda Tangan Uji (RSA), O=SigningPQC Uji, C=ID");
        SigningCredential mldsa = SpikeCredentials.selfSigned(SignatureAlgorithm.ML_DSA_65,
                "CN=Penanda Tangan Uji (ML-DSA), O=SigningPQC Uji, C=ID");
        Files.writeString(outDir.resolve("sertifikat-rsa.pem"), Pem.certificate(rsa.certificate()));
        Files.writeString(outDir.resolve("sertifikat-mldsa.pem"), Pem.certificate(mldsa.certificate()));

        PadesSigner signer = new PadesSigner();
        long start = System.nanoTime();
        byte[] afterRsa = signer.signInvisible(original, rsa, SignatureDetails.of("Penanda Tangan Uji", "Tanda tangan RSA"));
        byte[] hybrid = signer.signInvisible(afterRsa, mldsa, SignatureDetails.of("Penanda Tangan Uji", "Tanda tangan ML-DSA"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        Files.write(outDir.resolve("01-hybrid.pdf"), hybrid);

        exportCms(hybrid, outDir);

        System.out.printf("Ukuran PDF: asli %,d B, setelah RSA %,d B, setelah ML-DSA %,d B%n",
                original.length, afterRsa.length, hybrid.length);
        System.out.printf("Waktu tanda tangan hybrid (tanpa TSA): %d ms%n", elapsedMs);
        List<SignatureReport> reports = new PdfSignatureVerifier().verify(hybrid);
        for (SignatureReport report : reports) {
            System.out.printf("[%d] %-10s digest %-8s CMS %,6d B  seluruh dokumen=%-5s sah=%-5s %s%n",
                    report.order(), report.signatureAlgorithm(), report.digestAlgorithm(), report.cmsSizeBytes(),
                    report.coversWholeDocument(), report.valid(), report.message());
        }
    }

    private static void exportCms(byte[] pdf, Path outDir) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int index = 0;
            for (PDSignature signature : document.getSignatureDictionaries()) {
                index++;
                byte[] cms = PdfSignatureVerifier.firstDerObject(signature.getContents(pdf));
                Files.write(outDir.resolve("tt" + index + ".p7s"), cms);
                Files.write(outDir.resolve("tt" + index + "-isi.bin"), signature.getSignedContent(pdf));
            }
        }
    }
}
