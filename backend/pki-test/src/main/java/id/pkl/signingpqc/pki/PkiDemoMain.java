package id.pkl.signingpqc.pki;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509CRL;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;

import id.pkl.signingpqc.core.PadesSigner;
import id.pkl.signingpqc.core.Pem;
import id.pkl.signingpqc.core.PdfSignatureVerifier;
import id.pkl.signingpqc.core.Pkcs12Credentials;
import id.pkl.signingpqc.core.SamplePdf;
import id.pkl.signingpqc.core.SignatureDetails;
import id.pkl.signingpqc.core.SignatureReport;
import id.pkl.signingpqc.core.SigningCredential;

/**
 * Membuat PKI uji lengkap ke sebuah folder agar bisa diperiksa silang dengan OpenSSL 3.5
 * (lihat scripts/cek-pki.ps1).
 *
 * <p>Isi folder: sertifikat CA (PEM), CRL, PKCS#12 penanda tangan dan TSA, satu sertifikat yang
 * dicabut, serta PDF hybrid yang ditandatangani dengan pasangan sertifikat terbitan CA.
 * Kata sandi PKCS#12 uji: {@value #PASSWORD}. Hanya untuk lingkungan uji.
 */
public final class PkiDemoMain {

    static final String PASSWORD = "uji-signingpqc";
    private static final String ORG = "SigningPQC Uji";

    private PkiDemoMain() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "pki-output");
        Files.createDirectories(out);
        char[] password = PASSWORD.toCharArray();
        TestPkiFactory factory = new TestPkiFactory();
        SignerIdentity identity = new SignerIdentity("Budi Penanda Tangan Uji", "Kepala Seksi Uji", "Unit Uji", ORG);

        long start = System.nanoTime();
        CaHierarchy rsa = factory.createHierarchy(PkiFamily.RSA, ORG);
        CaHierarchy mldsa = factory.createHierarchy(PkiFamily.ML_DSA, ORG);
        SignerPair pair = factory.issueSignerPair(rsa, mldsa, identity);
        System.out.printf("Waktu membuat 2 hierarki CA + pasangan penanda tangan: %d ms%n",
                (System.nanoTime() - start) / 1_000_000);

        java.util.Map<String, SigningCredential> tsas = new java.util.LinkedHashMap<>();
        for (CaHierarchy hierarchy : List.of(rsa, mldsa)) {
            String slug = hierarchy.family().slug();
            SigningCredential signer = hierarchy == rsa ? pair.rsa() : pair.mlDsa();
            SigningCredential tsa = factory.issueTsa(hierarchy.intermediate(), "TSA Uji " + hierarchy.family().label(),
                    TestPkiFactory.TSA_VALIDITY);
            tsas.put(slug, tsa);
            SigningCredential revoked = factory.issueSigner(hierarchy.intermediate(), identity);

            X509CRL rootCrl = factory.createCrl(hierarchy.root(), List.of(), 1);
            X509CRL intermediateCrl = factory.createCrl(hierarchy.intermediate(),
                    List.of(TestPkiFactory.RevokedCertificate.keyCompromise(revoked.certificate())), 1);

            Files.writeString(out.resolve(slug + "-root.pem"), Pem.certificate(hierarchy.root().certificate()));
            Files.writeString(out.resolve(slug + "-intermediate.pem"),
                    Pem.certificate(hierarchy.intermediate().certificate()));
            Files.writeString(out.resolve(slug + "-crl.pem"), Pem.crl(intermediateCrl) + Pem.crl(rootCrl));
            Files.writeString(out.resolve(slug + "-penanda-tangan.pem"), Pem.certificate(signer.certificate()));
            Files.writeString(out.resolve(slug + "-tsa.pem"), Pem.certificate(tsa.certificate()));
            Files.writeString(out.resolve(slug + "-dicabut.pem"), Pem.certificate(revoked.certificate()));
            Files.write(out.resolve(slug + "-penanda-tangan.p12"),
                    Pkcs12Credentials.write(signer, "penanda-tangan", password));
            Files.write(out.resolve(slug + "-tsa.p12"), Pkcs12Credentials.write(tsa, "tsa", password));
        }

        byte[] pdf = SamplePdf.create("Surat Uji CA SigningPQC",
                "Ditandatangani dengan pasangan sertifikat RSA dan ML-DSA terbitan CA uji.",
                "Dokumen sintetis, tidak memuat data asli BSrE.");
        SigningCredential rsaSigner = Pkcs12Credentials.read(Files.readAllBytes(out.resolve("rsa-penanda-tangan.p12")),
                password);
        SigningCredential mldsaSigner = Pkcs12Credentials.read(
                Files.readAllBytes(out.resolve("mldsa-penanda-tangan.p12")), password);
        PadesSigner signer = new PadesSigner();
        byte[] signed = signer.signInvisible(pdf, rsaSigner, SignatureDetails.of(identity.commonName(), "Tanda tangan RSA"));
        signed = signer.signInvisible(signed, mldsaSigner, SignatureDetails.of(identity.commonName(), "Tanda tangan ML-DSA"));
        Files.write(out.resolve("02-hybrid-ca.pdf"), signed);

        try (PDDocument document = Loader.loadPDF(signed)) {
            int index = 0;
            for (PDSignature signature : document.getSignatureDictionaries()) {
                index++;
                Files.write(out.resolve("tt" + index + ".p7s"),
                        PdfSignatureVerifier.firstDerObject(signature.getContents(signed)));
                Files.write(out.resolve("tt" + index + "-isi.bin"), signature.getSignedContent(signed));
            }
        }
        // Token RFC 3161 atas PDF hybrid dari TSA lokal RSA (SHA-256) dan ML-DSA (SHA-512), untuk dicek
        // dengan "openssl ts -verify". Permintaan (.tsq) ikut disimpan agar nonce dan imprint ikut diperiksa.
        for (var entry : tsas.entrySet()) {
            String digest = "rsa".equals(entry.getKey()) ? "SHA-256" : "SHA-512";
            byte[] imprint = java.security.MessageDigest.getInstance(digest).digest(signed);
            org.bouncycastle.tsp.TimeStampRequestGenerator generator = new org.bouncycastle.tsp.TimeStampRequestGenerator();
            generator.setCertReq(true);
            byte[] query = generator.generate(
                    "SHA-256".equals(digest) ? org.bouncycastle.asn1.nist.NISTObjectIdentifiers.id_sha256
                            : org.bouncycastle.asn1.nist.NISTObjectIdentifiers.id_sha512,
                    imprint, java.math.BigInteger.valueOf(System.nanoTime())).getEncoded();
            byte[] reply = new LocalTsa(entry.getValue()).handle(query);
            Files.write(out.resolve(entry.getKey() + "-ts.tsq"), query);
            Files.write(out.resolve(entry.getKey() + "-ts.tsr"), reply);
            var token = new org.bouncycastle.tsp.TimeStampResponse(reply).getTimeStampToken();
            System.out.printf("Timestamp %s: genTime %s, serial %s, sertifikat TSA sah=%s%n", entry.getKey(),
                    token.getTimeStampInfo().getGenTime().toInstant(), token.getTimeStampInfo().getSerialNumber(),
                    id.pkl.signingpqc.core.TimestampClient.verify(token) != null);
        }

        for (SignatureReport report : new PdfSignatureVerifier().verify(signed)) {
            System.out.printf("[%d] %-10s %s sah=%s%n", report.order(), report.signatureAlgorithm(),
                    report.signerSubject(), report.valid());
        }

        // PAdES B-T: tanda tangan RSA diberi timestamp TSA RSA, tanda tangan ML-DSA diberi timestamp TSA ML-DSA.
        byte[] bt = signer.signInvisible(pdf, rsaSigner, SignatureDetails.of(identity.commonName(), "Tanda tangan RSA (B-T)"),
                new id.pkl.signingpqc.core.TimestampClient(new LocalTsa(tsas.get("rsa"))));
        bt = signer.signInvisible(bt, mldsaSigner, SignatureDetails.of(identity.commonName(), "Tanda tangan ML-DSA (B-T)"),
                new id.pkl.signingpqc.core.TimestampClient(new LocalTsa(tsas.get("mldsa"))));
        Files.write(out.resolve("03-hybrid-bt.pdf"), bt);
        try (PDDocument document = Loader.loadPDF(bt)) {
            int index = 0;
            for (PDSignature signature : document.getSignatureDictionaries()) {
                index++;
                byte[] cms = PdfSignatureVerifier.firstDerObject(signature.getContents(bt));
                Files.write(out.resolve("bt" + index + ".p7s"), cms);
                Files.write(out.resolve("bt" + index + "-isi.bin"), signature.getSignedContent(bt));
                // Token signature timestamp dan data yang di-timestamp (nilai tanda tangan SignerInfo).
                var signerInfo = new org.bouncycastle.cms.CMSSignedData(cms).getSignerInfos().getSigners().iterator().next();
                Files.write(out.resolve("bt" + index + "-ts.tst"),
                        id.pkl.signingpqc.core.SignatureTimestamps.tokenOf(signerInfo).getEncoded());
                Files.write(out.resolve("bt" + index + "-ts-data.bin"), signerInfo.getSignature());
            }
        }
        for (SignatureReport report : new PdfSignatureVerifier().verify(bt)) {
            var ts = report.signatureTimestamp();
            System.out.printf("B-T [%d] %-10s level=%s sah=%s CMS %,d B, timestamp %s oleh %s (%s)%n", report.order(),
                    report.signatureAlgorithm(), report.padesLevel(), report.valid(), report.cmsSizeBytes(),
                    ts == null ? "-" : ts.time(), ts == null ? "-" : ts.tsaSubject(),
                    ts == null ? "-" : ts.signatureAlgorithm());
        }
        // Tanda tangan RSA visible (kotak di kanan bawah halaman 1) + ML-DSA invisible, keduanya B-T.
        var a4 = org.apache.pdfbox.pdmodel.common.PDRectangle.A4;
        var position = id.pkl.signingpqc.core.VisibleSignature.bottomRightOfFirstPage(a4.getWidth(), a4.getHeight(),
                List.of(identity.title() + ", " + identity.organizationalUnit()));
        byte[] visible = signer.signVisible(pdf, rsaSigner, SignatureDetails.of(identity.commonName(), "Persetujuan dokumen"),
                position, new id.pkl.signingpqc.core.TimestampClient(new LocalTsa(tsas.get("rsa"))));
        visible = signer.signInvisible(visible, mldsaSigner, SignatureDetails.of(identity.commonName(), "Persetujuan dokumen"),
                new id.pkl.signingpqc.core.TimestampClient(new LocalTsa(tsas.get("mldsa"))));
        Files.write(out.resolve("04-hybrid-visible-bt.pdf"), visible);
        try (PDDocument document = Loader.loadPDF(visible)) {
            javax.imageio.ImageIO.write(new org.apache.pdfbox.rendering.PDFRenderer(document).renderImage(0, 1.5f), "png",
                    out.resolve("04-halaman1.png").toFile());
        }
        for (SignatureReport report : new PdfSignatureVerifier().verify(visible)) {
            System.out.printf("Visible [%d] %-10s level=%s sah=%s%n", report.order(), report.signatureAlgorithm(),
                    report.padesLevel(), report.valid());
        }
        System.out.println("Keluaran PKI uji: " + out.toAbsolutePath());
    }
}
