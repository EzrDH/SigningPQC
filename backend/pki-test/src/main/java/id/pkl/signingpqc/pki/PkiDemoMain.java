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
        System.out.println("Keluaran PKI uji: " + out.toAbsolutePath());
    }
}
