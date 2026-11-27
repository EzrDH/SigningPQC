package id.pkl.signingpqc.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLDecoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import id.pkl.signingpqc.core.SamplePdf;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** KF-35: log aktivitas lokal (siapa, kapan, berkas apa, hasil) untuk semua akun di perangkat ini. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ActivityApiTest {

    static final String PASSWORD = "password-log-9";
    static final String PASSPHRASE = "passphrase-log-panjang";
    static final Path DATA_DIR;
    static final Path OUTPUT_DIR;

    static {
        try {
            DATA_DIR = Files.createTempDirectory("signingpqc-log-");
            OUTPUT_DIR = Files.createTempDirectory("signingpqc-log-keluaran-");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("signingpqc.data-dir", DATA_DIR::toString);
        registry.add("signingpqc.pki.auto-setup", () -> "true");
        registry.add("signingpqc.default-output-dir", OUTPUT_DIR::toString);
    }

    @TestConfiguration
    static class Jam {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.now());
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    JsonMapper json;
    @Autowired
    MutableClock clock;

    static String token;
    static String secret;

    ApiClient api() {
        return new ApiClient(port);
    }

    JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    String nextCode() {
        clock.advance(Duration.ofSeconds(Totp.PERIOD_SECONDS));
        return Totp.code(TestBase32.decode(secret), clock.instant().getEpochSecond() / Totp.PERIOD_SECONDS);
    }

    JsonNode activity() throws Exception {
        HttpResponse<String> response = api().get("/api/me/activity", token);
        assertEquals(200, response.statusCode(), response.body());
        return body(response);
    }

    static void assertEntry(JsonNode entry, String actor, String action, String fileName, String result) {
        assertEquals(actor, entry.get("actor").asString(), entry.toString());
        assertEquals(action, entry.get("action").asString(), entry.toString());
        if (fileName == null) {
            assertTrue(entry.get("fileName") == null || entry.get("fileName").isNull(), entry.toString());
        } else {
            assertEquals(fileName, entry.get("fileName").asString(), entry.toString());
        }
        assertEquals(result, entry.get("result").asString(), entry.toString());
        assertFalse(entry.get("time").asString().isBlank());
    }

    @Test
    @Order(1)
    void daftarTercatat() throws Exception {
        HttpResponse<String> start = api().postJson("/api/auth/register/start", null, "{\"username\":\"rina.log\","
                + "\"email\":\"rina.log@contoh.go.id\",\"password\":\"" + PASSWORD + "\",\"passphrase\":\""
                + PASSPHRASE + "\"}");
        assertEquals(200, start.statusCode(), start.body());
        String uri = body(start).get("otpauthUri").asString();
        secret = URLDecoder.decode(uri.replaceAll(".*[?&]secret=([^&]+).*", "$1"), StandardCharsets.UTF_8);
        HttpResponse<String> finish = api().postJson("/api/auth/register/finish", null, "{\"registrationId\":\""
                + body(start).get("registrationId").asString() + "\",\"code\":\"" + nextCode() + "\"}");
        assertEquals(200, finish.statusCode(), finish.body());
        token = body(finish).get("token").asString();

        JsonNode log = activity();
        assertEntry(log.get(0), "rina.log", "Mendaftar", null, "BERHASIL");
    }

    @Test
    @Order(2)
    void tanpaSesiDitolak() throws Exception {
        assertEquals(401, api().get("/api/me/activity", null).statusCode());
    }

    @Test
    @Order(3)
    void masukGagalDanBerhasilTercatat() throws Exception {
        assertEquals(401, api().postJson("/api/auth/login", null,
                "{\"login\":\"rina.log\",\"password\":\"salah-sekali-1\"}").statusCode());
        assertEquals(401, api().postJson("/api/auth/login", null,
                "{\"login\":\"orang.asing\",\"password\":\"salah-sekali-1\"}").statusCode());
        HttpResponse<String> ok = api().postJson("/api/auth/login", null,
                "{\"login\":\"rina.log@contoh.go.id\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(200, ok.statusCode(), ok.body());

        JsonNode log = activity();
        assertEntry(log.get(0), "rina.log", "Masuk", null, "BERHASIL");
        assertEntry(log.get(1), "(tidak dikenal)", "Masuk", null, "GAGAL");
        assertEntry(log.get(2), "rina.log", "Masuk", null, "GAGAL");
        assertEquals("Username/email atau password salah", log.get(2).get("detail").asString());
    }

    @Test
    @Order(4)
    void pengaturanTercatat() throws Exception {
        HttpResponse<String> response = api().putJson("/api/me/settings", token, "{\"appearance\":\"INVISIBLE\"}");
        assertEquals(200, response.statusCode(), response.body());
        assertEntry(activity().get(0), "rina.log", "Mengubah pengaturan", null, "BERHASIL");
    }

    HttpResponse<byte[]> sign(String fileName, String code) throws Exception {
        return api().multipart("POST", "/api/me/sign", token, SamplePdf.create("Surat Log", "Isi."), fileName,
                "application/pdf", List.of("code", code));
    }

    @Test
    @Order(5)
    void tandaTanganBerhasilDanGagalTercatat() throws Exception {
        HttpResponse<byte[]> ok = sign("surat-log.pdf", nextCode());
        assertEquals(200, ok.statusCode(), new String(ok.body(), StandardCharsets.UTF_8));
        clock.advance(Duration.ofSeconds(Totp.PERIOD_SECONDS));
        String now = Totp.code(TestBase32.decode(secret), clock.instant().getEpochSecond() / Totp.PERIOD_SECONDS);
        HttpResponse<byte[]> wrong = sign("salah.pdf", now.equals("123456") ? "654321" : "123456");
        assertEquals(401, wrong.statusCode());

        JsonNode log = activity();
        assertEntry(log.get(0), "rina.log", "Menandatangani", "salah.pdf", "GAGAL");
        assertEquals("Konfirmasi tidak cocok", log.get(0).get("detail").asString());
        assertEntry(log.get(1), "rina.log", "Menandatangani", "surat-log.pdf", "BERHASIL");
        assertEquals("Tersimpan sebagai surat-log-ttd.pdf", log.get(1).get("detail").asString());
    }

    @Test
    @Order(6)
    void verifikasiTercatatDenganStatus() throws Exception {
        HttpResponse<byte[]> plain = api().multipart("POST", "/api/me/verify", token, SamplePdf.create("a", "b"),
                "polos.pdf", "application/pdf", List.of());
        assertEquals(200, plain.statusCode());
        assertEquals(400, api().multipart("POST", "/api/me/verify", token, "bukan pdf".getBytes(), "bukan.pdf",
                "application/pdf", List.of()).statusCode());

        JsonNode log = activity();
        assertEntry(log.get(0), "rina.log", "Memverifikasi", "bukan.pdf", "GAGAL");
        assertEntry(log.get(1), "rina.log", "Memverifikasi", "polos.pdf", "TANPA_TANDA_TANGAN");
    }

    String accountSignerId() throws Exception {
        for (JsonNode signer : body(api().get("/api/pki/signers", null))) {
            if ("rina.log".equals(signer.get("commonName").asString())) {
                return signer.get("id").asString();
            }
        }
        throw new AssertionError("penanda tangan akun tidak ditemukan");
    }

    @Test
    @Order(7)
    void pkiTercatatDenganPelakunya() throws Exception {
        HttpResponse<String> issued = api().postJson("/api/pki/signers", null,
                "{\"commonName\":\"Penanda Uji Log\",\"title\":\"Staf\",\"unit\":\"Uji\"}");
        assertEquals(201, issued.statusCode(), issued.body());
        HttpResponse<String> revoked = api().postJson("/api/pki/signers/" + accountSignerId() + "/revoke", token, "");
        assertEquals(200, revoked.statusCode(), revoked.body());
        assertEquals(404, api().postJson("/api/pki/signers/tidak-ada/revoke", token, "").statusCode());

        JsonNode log = activity();
        assertEntry(log.get(0), "rina.log", "Mencabut sertifikat", null, "GAGAL");
        assertEntry(log.get(1), "rina.log", "Mencabut sertifikat", null, "BERHASIL");
        assertEquals("rina.log", log.get(1).get("detail").asString());
        assertEntry(log.get(2), "API pengembang", "Menerbitkan sertifikat", null, "BERHASIL");
        assertEquals("Penanda Uji Log", log.get(2).get("detail").asString());
    }

    @Test
    @Order(8)
    void keluarTercatatDanBerkasTanpaRahasia() throws Exception {
        String keep = token;
        assertEquals(204, api().postJson("/api/auth/logout", keep, "").statusCode());
        HttpResponse<String> again = api().postJson("/api/auth/login", null,
                "{\"login\":\"rina.log\",\"password\":\"" + PASSWORD + "\"}");
        token = body(again).get("token").asString();
        JsonNode log = activity();
        assertEntry(log.get(1), "rina.log", "Keluar", null, "BERHASIL");

        String stored = Files.readString(DATA_DIR.resolve("activity.json"));
        assertFalse(stored.contains(PASSWORD));
        assertFalse(stored.contains(PASSPHRASE));
        assertFalse(stored.contains(keep));
        assertFalse(stored.contains(secret));
    }

    @Test
    @Order(9)
    void statusPkiUntukHalamanLingkunganUji() throws Exception {
        JsonNode status = body(api().get("/api/pki", null));
        assertTrue(status.get("ready").asBoolean());
        assertEquals(4, status.get("cas").size());
        assertEquals(3, status.get("tsas").size());
        JsonNode account = null;
        JsonNode developer = null;
        for (JsonNode signer : status.get("signers")) {
            if ("rina.log".equals(signer.get("commonName").asString())) {
                account = signer;
            } else if ("Penanda Uji Log".equals(signer.get("commonName").asString())) {
                developer = signer;
            }
        }
        assertTrue(account.get("account").asBoolean());
        assertTrue(account.get("revoked").asBoolean());
        assertFalse(developer.get("account").asBoolean());
        assertFalse(developer.get("createdAt").asString().isBlank());

        for (String tsa : List.of("rsa-g1", "rsa-g2", "mldsa")) {
            HttpResponse<byte[]> crt = api().getBytes("/api/pki/tsa/" + tsa + ".crt", null);
            assertEquals(200, crt.statusCode(), tsa);
            java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate) java.security.cert
                    .CertificateFactory.getInstance("X.509", new org.bouncycastle.jce.provider.BouncyCastleProvider())
                    .generateCertificate(new java.io.ByteArrayInputStream(crt.body()));
            assertTrue(cert.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.8"), "EKU timeStamping " + tsa);
        }
        assertEquals(404, api().getBytes("/api/pki/tsa/tidak-ada.crt", null).statusCode());
    }
}
