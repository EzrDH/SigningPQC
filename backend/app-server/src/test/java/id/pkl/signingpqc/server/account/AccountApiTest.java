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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Uji ujung ke ujung REST akun: daftar, masuk, sesi, penguncian, pengaturan, tanda tangan, verifikasi. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccountApiTest {

    static final String PASSWORD = "password-uji-9";
    static final String PASSPHRASE = "passphrase-uji-panjang";
    static final Path DATA_DIR;
    static final Path OUTPUT_DIR;

    static {
        try {
            DATA_DIR = Files.createTempDirectory("signingpqc-akun-");
            OUTPUT_DIR = Files.createTempDirectory("signingpqc-keluaran-");
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
    static String totpSecretBase32;

    ApiClient api() {
        return new ApiClient(port);
    }

    JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    /** Kode TOTP berikutnya: jam dimajukan satu periode agar langkahnya belum pernah dipakai. */
    String nextCode(String base32) {
        clock.advance(Duration.ofSeconds(Totp.PERIOD_SECONDS));
        return Totp.code(TestBase32.decode(base32), clock.instant().getEpochSecond() / Totp.PERIOD_SECONDS);
    }

    static String register(AccountApiTest t, String username, String email) throws Exception {
        HttpResponse<String> start = t.api().postJson("/api/auth/register/start", null, "{\"username\":\"" + username
                + "\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"passphrase\":\"" + PASSPHRASE + "\"}");
        assertEquals(200, start.statusCode(), start.body());
        JsonNode started = t.body(start);
        String uri = started.get("otpauthUri").asString();
        assertTrue(uri.startsWith("otpauth://totp/"), uri);
        String secret = URLDecoder.decode(uri.replaceAll(".*[?&]secret=([^&]+).*", "$1"), StandardCharsets.UTF_8);
        if ("budi.uji".equals(username)) {
            totpSecretBase32 = secret;
        }
        HttpResponse<String> finish = t.api().postJson("/api/auth/register/finish", null, "{\"registrationId\":\""
                + started.get("registrationId").asString() + "\",\"code\":\"" + t.nextCode(secret) + "\"}");
        assertEquals(200, finish.statusCode(), finish.body());
        return t.body(finish).get("token").asString();
    }

    @Test
    @Order(1)
    void daftarDuaLangkah() throws Exception {
        token = register(this, "budi.uji", "budi.uji@contoh.go.id");
        assertFalse(token.isBlank());
    }

    @Test
    @Order(2)
    void usernameDanEmailUnik() throws Exception {
        assertEquals(409, api().postJson("/api/auth/register/start", null, "{\"username\":\"BUDI.UJI\",\"email\":\"lain@contoh.go.id\",\"password\":\""
                + PASSWORD + "\",\"passphrase\":\"" + PASSPHRASE + "\"}").statusCode());
        assertEquals(409, api().postJson("/api/auth/register/start", null, "{\"username\":\"lain\",\"email\":\"Budi.Uji@contoh.go.id\",\"password\":\""
                + PASSWORD + "\",\"passphrase\":\"" + PASSPHRASE + "\"}").statusCode());
    }

    @Test
    @Order(3)
    void validasiInput() throws Exception {
        String ok = PASSWORD;
        assertEquals(400, startWith("siti.uji", "siti@contoh.go.id", "pendek7", PASSPHRASE).statusCode());
        assertEquals(400, startWith("siti.uji", "siti@contoh.go.id", ok, "sebelas1234").statusCode());
        assertEquals(400, startWith("Ab", "siti@contoh.go.id", ok, PASSPHRASE).statusCode());
        assertEquals(400, startWith("siti.uji", "bukan-email", ok, PASSPHRASE).statusCode());
        HttpResponse<String> wrongCode = api().postJson("/api/auth/register/finish", null,
                "{\"registrationId\":\"" + body(startWith("siti.uji", "siti@contoh.go.id", ok, PASSPHRASE))
                        .get("registrationId").asString() + "\",\"code\":\"000000\"}");
        assertEquals(400, wrongCode.statusCode());
        assertEquals("Kode tidak cocok", body(wrongCode).get("error").asString());
        assertEquals(410, api().postJson("/api/auth/register/finish", null,
                "{\"registrationId\":\"tidak-ada\",\"code\":\"000000\"}").statusCode());
    }

    private HttpResponse<String> startWith(String username, String email, String password, String passphrase)
            throws Exception {
        return api().postJson("/api/auth/register/start", null, "{\"username\":\"" + username + "\",\"email\":\"" + email
                + "\",\"password\":\"" + password + "\",\"passphrase\":\"" + passphrase + "\"}");
    }

    @Test
    @Order(4)
    void masukDenganEmailAtauUsername() throws Exception {
        assertEquals(200, login("budi.uji", PASSWORD).statusCode());
        assertEquals(200, login("BUDI.UJI@contoh.go.id", PASSWORD).statusCode());
        HttpResponse<String> wrong = login("budi.uji", "password-salah");
        HttpResponse<String> missing = login("tidak.ada", PASSWORD);
        assertEquals(401, wrong.statusCode());
        assertEquals(401, missing.statusCode());
        assertEquals(body(wrong).get("error").asString(), body(missing).get("error").asString());
    }

    HttpResponse<String> login(String login, String password) throws Exception {
        return api().postJson("/api/auth/login", null, "{\"login\":\"" + login + "\",\"password\":\"" + password + "\"}");
    }

    @Test
    @Order(5)
    void kunciSetelahLimaGagal() throws Exception {
        register(this, "rina.uji", "rina@contoh.go.id");
        for (int i = 0; i < 5; i++) {
            assertEquals(401, login("rina.uji", "password-salah").statusCode());
        }
        assertEquals(423, login("rina.uji", PASSWORD).statusCode());
        clock.advance(Duration.ofMinutes(6));
        assertEquals(200, login("rina.uji", PASSWORD).statusCode());
    }

    @Test
    @Order(6)
    void meTanpaTokenDitolak() throws Exception {
        assertEquals(401, api().get("/api/me", null).statusCode());
        assertEquals(401, api().get("/api/me", "token-palsu").statusCode());
        token = body(login("budi.uji", PASSWORD)).get("token").asString();
    }

    @Test
    @Order(7)
    void berkasAkunTanpaRahasia() throws Exception {
        try (var files = Files.walk(DATA_DIR.resolve("accounts"))) {
            for (Path file : files.filter(f -> f.getFileName().toString().equals("account.json")).toList()) {
                String text = Files.readString(file);
                assertFalse(text.contains(PASSWORD), file.toString());
                assertFalse(text.contains(PASSPHRASE), file.toString());
            }
        }
    }
}
