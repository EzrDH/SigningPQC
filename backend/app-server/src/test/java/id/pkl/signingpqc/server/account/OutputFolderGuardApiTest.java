package id.pkl.signingpqc.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLDecoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
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

/**
 * Tindak lanjut T-016: folder keluaran diperiksa ulang saat menyimpan. Folder yang sah ketika disimpan di Pengaturan
 * lalu diganti tautan simbolik (atau junction di Windows) ke folder data tidak boleh menerima berkas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutputFolderGuardApiTest {

    static final Path DATA_DIR;
    static final Path BASE;

    static {
        try {
            DATA_DIR = Files.createTempDirectory("signingpqc-jaga-");
            BASE = Files.createTempDirectory("signingpqc-jaga-keluaran-");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("signingpqc.data-dir", DATA_DIR::toString);
        registry.add("signingpqc.pki.auto-setup", () -> "true");
        registry.add("signingpqc.default-output-dir", () -> BASE.resolve("awal").toString());
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

    String secret;

    String nextCode() {
        clock.advance(Duration.ofSeconds(Totp.PERIOD_SECONDS));
        return Totp.code(TestBase32.decode(secret), clock.instant().getEpochSecond() / Totp.PERIOD_SECONDS);
    }

    static long pdfCount(Path dir) throws Exception {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".pdf")).count();
        }
    }

    @Test
    void folderYangDigantiTautanKeFolderDataDitolakSaatMenyimpan() throws Exception {
        ApiClient api = new ApiClient(port);
        HttpResponse<String> start = api.postJson("/api/auth/register/start", null, "{\"username\":\"jaga.uji\","
                + "\"email\":\"jaga@contoh.go.id\",\"password\":\"password-jaga-9\","
                + "\"passphrase\":\"passphrase-jaga-panjang\"}");
        JsonNode started = json.readTree(start.body());
        secret = URLDecoder.decode(started.get("otpauthUri").asString().replaceAll(".*[?&]secret=([^&]+).*", "$1"),
                StandardCharsets.UTF_8);
        String token = json.readTree(api.postJson("/api/auth/register/finish", null, "{\"registrationId\":\""
                + started.get("registrationId").asString() + "\",\"code\":\"" + nextCode() + "\"}").body())
                .get("token").asString();

        Path folder = BASE.resolve("pindah");
        HttpResponse<String> settings = api.putJson("/api/me/settings", token, "{\"appearance\":\"INVISIBLE\","
                + "\"outputDir\":" + json.writeValueAsString(folder.toString()) + "}");
        assertEquals(200, settings.statusCode(), settings.body());

        // Sesudah disetujui, folder diganti tautan ke folder data.
        try (Stream<Path> leftovers = Files.list(folder)) {
            for (Path p : leftovers.toList()) {
                Files.delete(p);
            }
        }
        Files.delete(folder);
        Files.createSymbolicLink(folder, DATA_DIR);
        long before = pdfCount(DATA_DIR);

        HttpResponse<byte[]> signed = api.multipart("POST", "/api/me/sign", token, SamplePdf.create("Jaga", "Isi."),
                "jaga.pdf", "application/pdf", List.of("code", nextCode()));
        String body = new String(signed.body(), StandardCharsets.UTF_8);
        assertEquals(409, signed.statusCode(), body);
        assertTrue(body.contains("Folder keluaran tidak boleh berada di dalam folder data aplikasi"), body);
        assertEquals(before, pdfCount(DATA_DIR), "tidak ada PDF baru di folder data");

        // Setelah tautan diganti folder biasa lagi, tanda tangan kembali berhasil.
        Files.delete(folder);
        Files.createDirectories(folder);
        HttpResponse<byte[]> again = api.multipart("POST", "/api/me/sign", token, SamplePdf.create("Jaga", "Isi."),
                "jaga.pdf", "application/pdf", List.of("code", nextCode()));
        assertEquals(200, again.statusCode(), new String(again.body(), StandardCharsets.UTF_8));
        assertTrue(Files.exists(folder.resolve("jaga-ttd.pdf")));
    }
}
