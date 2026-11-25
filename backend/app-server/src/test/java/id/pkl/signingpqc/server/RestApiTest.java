package id.pkl.signingpqc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.bouncycastle.tsp.TimeStampToken;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import id.pkl.signingpqc.core.CryptoProviders;
import id.pkl.signingpqc.core.HttpTimestampTransport;
import id.pkl.signingpqc.core.PdfSignatureVerifier;
import id.pkl.signingpqc.core.SamplePdf;
import id.pkl.signingpqc.core.SignatureReport;
import id.pkl.signingpqc.core.TimestampClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Uji ujung ke ujung REST lokal: PKI uji, TSA HTTP, tanda tangan hybrid, verifikasi, pencabutan, CRL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RestApiTest {

    private static final Path DATA_DIR;

    static {
        try {
            DATA_DIR = Files.createTempDirectory("signingpqc-uji-");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("signingpqc.data-dir", DATA_DIR::toString);
    }

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();
    private static String signerId;
    private static byte[] signedPdf;

    @Test
    @Order(1)
    void pkiBelumDibuatLaluDibuat() throws Exception {
        JsonNode status = getJson("/api/pki");
        assertFalse(status.get("ready").asBoolean());

        HttpResponse<String> created = postJson("/api/pki/setup", "{\"organization\":\"SigningPQC Uji\"}");
        assertEquals(201, created.statusCode(), created.body());
        JsonNode body = json.readTree(created.body());
        assertTrue(body.get("ready").asBoolean());
        assertEquals(4, body.get("cas").size());
        assertEquals(3, body.get("tsas").size());

        HttpResponse<String> again = postJson("/api/pki/setup", "{\"organization\":\"SigningPQC Uji\"}");
        assertEquals(409, again.statusCode());
        assertTrue(json.readTree(again.body()).get("error").asString().contains("sudah dibuat"));
    }

    @Test
    @Order(2)
    void terbitkanPenandaTangan() throws Exception {
        HttpResponse<String> response = postJson("/api/pki/signers",
                "{\"commonName\":\"Budi Penanda Tangan Uji\",\"title\":\"Kepala Seksi Uji\",\"unit\":\"Unit Uji\"}");
        assertEquals(201, response.statusCode(), response.body());
        JsonNode signer = json.readTree(response.body());
        signerId = signer.get("id").asString();
        assertEquals("RSA_3072", signer.get("rsa").get("algorithm").asString());
        assertEquals("ML_DSA_65", signer.get("mlDsa").get("algorithm").asString());
        assertEquals(signer.get("rsa").get("subject").asString(), signer.get("mlDsa").get("subject").asString());
        assertTrue(signer.get("rsa").get("subject").asString().contains("T=Kepala Seksi Uji"),
                signer.get("rsa").get("subject").asString());
        assertEquals(1, getJson("/api/pki/signers").size());
    }

    @Test
    @Order(3)
    void tandaTanganHybridLewatRest() throws Exception {
        byte[] pdf = SamplePdf.create("Surat Uji REST", "Ditandatangani lewat REST lokal.");
        HttpResponse<byte[]> response = postMultipart("/api/sign", pdf, "surat.pdf",
                List.of("signerId", signerId, "reason", "Persetujuan", "location", "Jakarta"));
        assertEquals(200, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        assertEquals("application/pdf", response.headers().firstValue("Content-Type").orElse(""));
        assertTrue(response.headers().firstValue("Content-Disposition").orElse("").contains("surat-ttd.pdf"));
        signedPdf = response.body();

        List<SignatureReport> reports = new PdfSignatureVerifier().verify(signedPdf);
        assertEquals(2, reports.size());
        assertTrue(reports.get(0).signatureAlgorithm().startsWith("RSA"));
        assertEquals("ML-DSA-65", reports.get(1).signatureAlgorithm());
        for (SignatureReport report : reports) {
            assertTrue(report.valid(), report.message());
            assertEquals("B-T", report.padesLevel());
        }
        System.out.println("Waktu tanda tangan hybrid B-T lewat REST: "
                + response.headers().firstValue("X-SigningPQC-Waktu-Ms").orElse("?") + " ms");
    }

    @Test
    @Order(4)
    void verifikasiLewatRest() throws Exception {
        HttpResponse<byte[]> response = postMultipart("/api/verify", signedPdf, "surat-ttd.pdf", List.of());
        assertEquals(200, response.statusCode());
        JsonNode body = json.readTree(response.body());
        assertTrue(body.get("allValid").asBoolean());
        assertTrue(body.get("hybridComplete").asBoolean());
        assertEquals("B-T", body.get("signatures").get(1).get("padesLevel").asString());
        JsonNode ts = body.get("signatures").get(1).get("signatureTimestamp");
        assertTrue(ts.get("tokenValid").asBoolean() && ts.get("imprintMatches").asBoolean(), ts.toString());

        byte[] tampered = signedPdf.clone();
        tampered[200] ^= 0x01;
        JsonNode bad = json.readTree(postMultipart("/api/verify", tampered, "rusak.pdf", List.of()).body());
        assertFalse(bad.get("allValid").asBoolean());
        assertTrue(bad.get("signatures").get(0).get("message").asString().contains("telah diubah"));
    }

    @Test
    @Order(5)
    void tsaLewatHttp() throws Exception {
        for (String family : List.of("rsa", "mldsa")) {
            TimestampClient client = new TimestampClient(
                    new HttpTimestampTransport(URI.create(base() + "/api/tsa/" + family)));
            String digest = family.equals("rsa") ? "SHA-256" : "SHA-512";
            TimeStampToken token = client.timestamp("data uji".getBytes(StandardCharsets.UTF_8), digest);
            assertTrue(TimestampClient.imprintMatches(token, "data uji".getBytes(StandardCharsets.UTF_8)));
        }
        HttpResponse<String> unknown = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/tsa/dsa"))
                .header("Content-Type", HttpTimestampTransport.REQUEST_TYPE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {1})).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, unknown.statusCode());
    }

    @Test
    @Order(6)
    void cabutPenandaTanganLaluCrlDanTandaTanganDitolak() throws Exception {
        CryptoProviders.ensureRegistered();
        CertificateFactory factory = CertificateFactory.getInstance("X.509", CryptoProviders.BC);
        X509Certificate ca = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(
                getBytes("/api/pki/ca/mldsa-intermediate.crt")));
        X509CRL before = (X509CRL) factory.generateCRL(new ByteArrayInputStream(getBytes("/api/pki/crl/mldsa-intermediate.crl")));
        before.verify(ca.getPublicKey(), CryptoProviders.BC);
        assertTrue(before.getRevokedCertificates() == null || before.getRevokedCertificates().isEmpty());

        HttpResponse<String> revoked = postJson("/api/pki/signers/" + signerId + "/revoke", "");
        assertEquals(200, revoked.statusCode(), revoked.body());
        assertTrue(json.readTree(revoked.body()).get("revoked").asBoolean());

        X509CRL after = (X509CRL) factory.generateCRL(new ByteArrayInputStream(getBytes("/api/pki/crl/mldsa-intermediate.crl")));
        after.verify(ca.getPublicKey(), CryptoProviders.BC);
        assertEquals(1, after.getRevokedCertificates().size());
        assertNotEquals(before.getExtensionValue("2.5.29.20").length, 0);
        assertTrue(new java.math.BigInteger(1, after.getExtensionValue("2.5.29.20"))
                .compareTo(new java.math.BigInteger(1, before.getExtensionValue("2.5.29.20"))) > 0, "nomor CRL naik");

        HttpResponse<byte[]> refused = postMultipart("/api/sign", SamplePdf.create("Uji", "Dicabut"), "x.pdf",
                List.of("signerId", signerId));
        assertEquals(409, refused.statusCode());
        assertTrue(new String(refused.body(), StandardCharsets.UTF_8).contains("dicabut"));
    }

    @Test
    @Order(7)
    void pkiTetapAdaSetelahDimuatUlang() throws Exception {
        PkiService reloaded = new PkiService(DATA_DIR, "uji-signingpqc", "http://127.0.0.1:" + port + "/api/pki/crl/",
                json);
        assertTrue(reloaded.ready());
        assertEquals(1, reloaded.signerRecords().size());
        assertTrue(reloaded.signerRecord(signerId).revoked());
        assertEquals(4, reloaded.cas().size());
    }

    @Test
    @Order(8)
    void masukanSalahDitolakDenganPesanJelas() throws Exception {
        assertEquals(404, postMultipart("/api/sign", SamplePdf.create("Uji", "x"), "x.pdf",
                List.of("signerId", "tidak-ada")).statusCode());
        HttpResponse<byte[]> notPdf = postMultipart("/api/verify", "bukan pdf".getBytes(), "x.txt", List.of());
        assertEquals(400, notPdf.statusCode());
        assertTrue(new String(notPdf.body(), StandardCharsets.UTF_8).contains("bukan PDF"));
        assertEquals(400, postJson("/api/pki/signers", "{\"commonName\":\"\"}").statusCode());
    }

    @Test
    @Order(9)
    void alurLengkapSampaiPqc() throws Exception {
        HttpResponse<String> created = postJson("/api/pki/signers", "{\"commonName\":\"Sari Penanda Tangan LTV\"}");
        assertEquals(201, created.statusCode(), created.body());
        String id = json.readTree(created.body()).get("id").asString();

        byte[] pdf = SamplePdf.create("Ijazah Uji REST", "Dokumen sintetis untuk uji lapisan LTV.");
        HttpResponse<byte[]> signed = postMultipart("/api/sign", pdf, "ijazah.pdf",
                List.of("signerId", id, "level", "B-LTA"));
        assertEquals(200, signed.statusCode(), new String(signed.body(), StandardCharsets.UTF_8));

        Instant gen1NotAfter = null;
        for (JsonNode tsa : getJson("/api/pki").get("tsas")) {
            if ("tsa-rsa-g1".equals(tsa.get("id").asString())) {
                gen1NotAfter = instant(tsa.get("certificate").get("notAfter"));
            }
        }
        assertTrue(gen1NotAfter != null, "TSA rsa-g1 harus ada");
        Instant t1 = gen1NotAfter.minus(Duration.ofDays(10));

        HttpResponse<byte[]> gen2 = postMultipart("/api/ltv/renew?tsa=rsa-g2&at=" + t1, signed.body(), "ijazah.pdf",
                List.of());
        assertEquals(200, gen2.statusCode(), new String(gen2.body(), StandardCharsets.UTF_8));
        HttpResponse<byte[]> pqc = postMultipart("/api/ltv/renew?tsa=mldsa&at=2034-06-01T00:00:00Z", gen2.body(),
                "ijazah.pdf", List.of());
        assertEquals(200, pqc.statusCode(), new String(pqc.body(), StandardCharsets.UTF_8));

        HttpResponse<byte[]> verified = postMultipart("/api/verify?at=2040-01-01T00:00:00Z", pqc.body(), "ijazah.pdf",
                List.of());
        assertEquals(200, verified.statusCode());
        JsonNode validation = json.readTree(verified.body()).get("validation");
        assertEquals("SAH", validation.get("status").asString(), validation.toString());
        assertTrue(validation.get("layers").size() >= 6, validation.toString());
    }

    @Test
    @Order(10)
    void ltvMenolakBukanPdf() throws Exception {
        for (String path : List.of("/api/ltv/validation-data", "/api/ltv/timestamp?tsa=rsa-g1",
                "/api/ltv/renew?tsa=mldsa")) {
            HttpResponse<byte[]> response = postMultipart(path, "bukan pdf".getBytes(), "x.txt", List.of());
            assertEquals(400, response.statusCode(), path);
            assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("bukan PDF"), path);
        }
        HttpResponse<byte[]> badTime = postMultipart("/api/ltv/timestamp?tsa=rsa-g1&at=kemarin",
                SamplePdf.create("Uji", "x"), "x.pdf", List.of());
        assertEquals(400, badTime.statusCode());
        HttpResponse<byte[]> badTsa = postMultipart("/api/ltv/timestamp?tsa=dsa", SamplePdf.create("Uji", "x"),
                "x.pdf", List.of());
        assertEquals(404, badTsa.statusCode());
    }

    @Test
    @Order(11)
    void ltvMenolakPdfTerenkripsi() throws Exception {
        byte[] encrypted;
        try (PDDocument document = Loader.loadPDF(SamplePdf.create("Terkunci", "PDF terenkripsi uji"))) {
            StandardProtectionPolicy policy = new StandardProtectionPolicy("pemilik-uji", "", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            encrypted = out.toByteArray();
        }
        for (String path : List.of("/api/ltv/validation-data", "/api/ltv/timestamp?tsa=rsa-g1")) {
            HttpResponse<byte[]> response = postMultipart(path, encrypted, "terkunci.pdf", List.of());
            assertEquals(400, response.statusCode(), path);
            assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("terenkripsi"), path);
        }
    }

    // ---------------------------------------------------------------------------------------------

    private static Instant instant(JsonNode node) {
        if (node.isNumber()) {
            return Instant.ofEpochMilli(Math.round(node.asDouble() * 1000));
        }
        return Instant.parse(node.asString());
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private JsonNode getJson(String path) throws Exception {
        return json.readTree(http.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
    }

    private byte[] getBytes(String path) throws Exception {
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> postMultipart(String path, byte[] file, String fileName, List<String> fields)
            throws Exception {
        String boundary = "----signingpqc" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int i = 0; i < fields.size(); i += 2) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fields.get(i)
                    + "\"\r\n\r\n" + fields.get(i + 1) + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(file);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
