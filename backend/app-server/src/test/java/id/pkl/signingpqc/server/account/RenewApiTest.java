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

/** Perpanjangan dari aplikasi: ambang peringatan dibuat 1000 hari agar dokumen baru langsung disarankan diperpanjang. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RenewApiTest {

    static final Path DATA_DIR;
    static final Path OUTPUT_DIR;

    static {
        try {
            DATA_DIR = Files.createTempDirectory("signingpqc-perpanjang-");
            OUTPUT_DIR = Files.createTempDirectory("signingpqc-perpanjang-keluaran-");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("signingpqc.data-dir", DATA_DIR::toString);
        registry.add("signingpqc.pki.auto-setup", () -> "true");
        registry.add("signingpqc.default-output-dir", OUTPUT_DIR::toString);
        registry.add("signingpqc.renewal-warning-days", () -> "1000");
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

    @Test
    void perpanjangMemakaiTsaMlDsa() throws Exception {
        ApiClient api = new ApiClient(port);
        HttpResponse<String> start = api.postJson("/api/auth/register/start", null,
                "{\"username\":\"eka.uji\",\"email\":\"eka@contoh.go.id\",\"password\":\"password-uji-9\","
                        + "\"passphrase\":\"passphrase-uji-panjang\"}");
        assertEquals(200, start.statusCode(), start.body());
        JsonNode started = json.readTree(start.body());
        String uri = started.get("otpauthUri").asString();
        byte[] secret = TestBase32.decode(URLDecoder.decode(uri.replaceAll(".*[?&]secret=([^&]+).*", "$1"),
                StandardCharsets.UTF_8));
        clock.advance(Duration.ofSeconds(30));
        HttpResponse<String> finish = api.postJson("/api/auth/register/finish", null, "{\"registrationId\":\""
                + started.get("registrationId").asString() + "\",\"code\":\""
                + Totp.code(secret, clock.instant().getEpochSecond() / 30) + "\"}");
        String token = json.readTree(finish.body()).get("token").asString();
        clock.advance(Duration.ofSeconds(30));
        HttpResponse<byte[]> signed = api.multipart("POST", "/api/me/sign", token,
                SamplePdf.create("Surat Perpanjang", "Uji perpanjangan."), "surat.pdf", "application/pdf",
                List.of("code", Totp.code(secret, clock.instant().getEpochSecond() / 30)));
        assertEquals(200, signed.statusCode(), new String(signed.body(), StandardCharsets.UTF_8));

        HttpResponse<byte[]> renewed = api.multipart("POST", "/api/me/renew", token, signed.body(), "surat-ttd.pdf",
                "application/pdf", List.of());
        assertEquals(200, renewed.statusCode(), new String(renewed.body(), StandardCharsets.UTF_8));
        Path saved = Path.of(URLDecoder.decode(renewed.headers().firstValue("X-Saved-Path").orElseThrow(),
                StandardCharsets.UTF_8));
        assertEquals("surat-ttd-diperpanjang.pdf", saved.getFileName().toString());
        assertTrue(Files.exists(saved));

        HttpResponse<byte[]> verified = api.multipart("POST", "/api/me/verify", token, renewed.body(), "x.pdf",
                "application/pdf", List.of());
        JsonNode validation = json.readTree(new String(verified.body(), StandardCharsets.UTF_8)).get("validation");
        assertEquals("SAH", validation.get("status").asString(), validation.toString());
        JsonNode layers = validation.get("layers");
        assertTrue(layers.get(layers.size() - 1).get("algorithm").asString().startsWith("ML-DSA"), layers.toString());
        JsonNode history = json.readTree(api.get("/api/me/history", token).body());
        assertTrue(history.toString().contains("Diperpanjang"), history.toString());
    }
}
