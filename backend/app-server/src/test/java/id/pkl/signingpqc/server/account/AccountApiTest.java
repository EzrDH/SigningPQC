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

    // ------------------------------------------------------------------ Task 6: profil, pengaturan, spesimen

    static byte[] png(int width, int height) throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(java.awt.Color.BLUE);
        g.drawLine(5, height / 2, width - 5, height / 3);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    String accountDir() throws Exception {
        try (var folders = Files.list(DATA_DIR.resolve("accounts"))) {
            for (Path folder : folders.toList()) {
                if (Files.readString(folder.resolve("account.json")).contains("\"budi.uji\"")) {
                    return folder.toString();
                }
            }
        }
        throw new IllegalStateException("akun budi.uji tidak ada");
    }

    @Test
    @Order(10)
    void profilBerisiDuaSertifikat() throws Exception {
        HttpResponse<String> response = api().get("/api/me", token);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode me = body(response);
        assertEquals("budi.uji", me.get("username").asString());
        assertEquals("budi.uji@contoh.go.id", me.get("email").asString());
        assertEquals(2, me.get("certificates").size());
        assertEquals("RSA 3072 bit", me.get("certificates").get(0).get("algorithm").asString());
        assertTrue(me.get("certificates").get(1).get("algorithm").asString().startsWith("ML-DSA"));
        assertFalse(me.get("certificates").get(0).get("revoked").asBoolean());
        assertFalse(me.get("certificates").get(0).get("serialHex").asString().isBlank());
        assertEquals("TOTP", me.get("confirmMethod").asString());
        assertEquals("VISIBLE", me.get("appearance").asString());
        assertEquals(OUTPUT_DIR.toString(), me.get("outputDir").asString());
        assertFalse(me.get("hasSpecimen").asBoolean());
        assertFalse(me.toString().contains("passwordHash"));
    }

    @Test
    @Order(11)
    void gantiMetodeButuhPassphrase() throws Exception {
        Path dir = Path.of(accountDir());
        assertEquals(400, api().putJson("/api/me/settings", token, "{\"confirmMethod\":\"PASSPHRASE\"}").statusCode());
        assertEquals(401, api().putJson("/api/me/settings", token,
                "{\"confirmMethod\":\"PASSPHRASE\",\"passphrase\":\"passphrase-salah-sekali\"}").statusCode());
        assertEquals(200, api().putJson("/api/me/settings", token,
                "{\"confirmMethod\":\"PASSPHRASE\",\"passphrase\":\"" + PASSPHRASE + "\"}").statusCode());
        assertFalse(Files.exists(dir.resolve("dek.app")));
        assertEquals("PASSPHRASE", body(api().get("/api/me", token)).get("confirmMethod").asString());
        assertEquals(200, api().putJson("/api/me/settings", token,
                "{\"confirmMethod\":\"TOTP\",\"passphrase\":\"" + PASSPHRASE + "\"}").statusCode());
        assertTrue(Files.exists(dir.resolve("dek.app")));
        assertEquals(200, api().putJson("/api/me/settings", token, "{\"appearance\":\"INVISIBLE\"}").statusCode());
        assertEquals("INVISIBLE", body(api().get("/api/me", token)).get("appearance").asString());
        assertEquals(200, api().putJson("/api/me/settings", token, "{\"appearance\":\"VISIBLE\"}").statusCode());
    }

    @Test
    @Order(12)
    void folderKeluaranRelatifDitolak() throws Exception {
        assertEquals(400, api().putJson("/api/me/settings", token, "{\"outputDir\":\"relatif/folder\"}").statusCode());
        assertEquals(OUTPUT_DIR.toString(), body(api().get("/api/me", token)).get("outputDir").asString());
        Path other = OUTPUT_DIR.resolve("lain");
        assertEquals(200, api().putJson("/api/me/settings", token,
                "{\"outputDir\":" + json.writeValueAsString(other.toString()) + "}").statusCode());
        assertTrue(Files.isDirectory(other));
        assertEquals(200, api().putJson("/api/me/settings", token,
                "{\"outputDir\":" + json.writeValueAsString(OUTPUT_DIR.toString()) + "}").statusCode());
    }

    @Test
    @Order(13)
    void folderKeluaranTidakBisaDitulis() throws Exception {
        Path file = Files.writeString(Files.createTempFile("bukan-folder", ".txt"), "x");
        HttpResponse<String> response = api().putJson("/api/me/settings", token,
                "{\"outputDir\":" + json.writeValueAsString(file.toString()) + "}");
        assertEquals(400, response.statusCode());
        assertEquals("Folder tidak bisa dipakai untuk menyimpan", body(response).get("error").asString());
        assertEquals(OUTPUT_DIR.toString(), body(api().get("/api/me", token)).get("outputDir").asString());
    }

    @Test
    @Order(14)
    void spesimenPngDiterima() throws Exception {
        assertEquals(404, api().getBytes("/api/me/specimen", token).statusCode());
        HttpResponse<byte[]> upload = api().multipart("PUT", "/api/me/specimen", token, png(300, 120), "ttd.png",
                "image/png", java.util.List.of());
        assertEquals(200, upload.statusCode(), new String(upload.body(), StandardCharsets.UTF_8));
        HttpResponse<byte[]> image = api().getBytes("/api/me/specimen", token);
        assertEquals(200, image.statusCode());
        assertEquals("image/png", image.headers().firstValue("Content-Type").orElse(""));
        assertTrue(body(api().get("/api/me", token)).get("hasSpecimen").asBoolean());
    }

    @Test
    @Order(15)
    void spesimenBukanGambarDitolak() throws Exception {
        assertEquals(400, api().multipart("PUT", "/api/me/specimen", token, "bukan gambar".getBytes(), "ttd.png",
                "image/png", java.util.List.of()).statusCode());
        assertEquals(400, api().multipart("PUT", "/api/me/specimen", token, png(2100, 100), "ttd.png", "image/png",
                java.util.List.of()).statusCode());
    }

    @Test
    @Order(16)
    void riwayatKosong() throws Exception {
        HttpResponse<String> history = api().get("/api/me/history", token);
        assertEquals(200, history.statusCode());
        assertEquals(0, body(history).size());
    }

    // ------------------------------------------------------------------ Task 7: tanda tangan dan folder keluaran

    static String lastCode;

    HttpResponse<byte[]> sign(String bearer, byte[] pdf, String fileName, String... fields) throws Exception {
        return api().multipart("POST", "/api/me/sign", bearer, pdf, fileName, "application/pdf", java.util.List.of(fields));
    }

    static Path savedPath(HttpResponse<byte[]> response) {
        return Path.of(URLDecoder.decode(response.headers().firstValue("X-Saved-Path").orElseThrow(),
                StandardCharsets.UTF_8));
    }

    static long countFiles(Path dir) throws Exception {
        try (var files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    JsonNode verifyOpen(byte[] pdf) throws Exception {
        HttpResponse<byte[]> response = api().multipart("POST", "/api/verify", null, pdf, "cek.pdf", "application/pdf",
                java.util.List.of());
        assertEquals(200, response.statusCode());
        return json.readTree(new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    @Order(20)
    void tandaTanganVisibleTersimpan() throws Exception {
        byte[] pdf = id.pkl.signingpqc.core.SamplePdf.create("Surat Uji Aplikasi", "Ditandatangani dari aplikasi.");
        lastCode = nextCode(totpSecretBase32);
        HttpResponse<byte[]> response = sign(token, pdf, "surat.pdf", "code", lastCode);
        assertEquals(200, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        Path saved = savedPath(response);
        assertEquals("surat-ttd.pdf", saved.getFileName().toString());
        assertEquals(OUTPUT_DIR, saved.getParent());
        assertTrue(java.util.Arrays.equals(response.body(), Files.readAllBytes(saved)));
        JsonNode result = verifyOpen(response.body());
        assertEquals("SAH", result.get("validation").get("status").asString(), result.toString());
        JsonNode layers = result.get("validation").get("layers");
        assertEquals("LTA", layers.get(layers.size() - 1).get("pades").asString(), layers.toString());
        assertTrue(result.get("validation").get("hybridComplete").asBoolean());
    }

    @Test
    @Order(21)
    void tandaTanganKeduaTidakMenimpa() throws Exception {
        byte[] first = Files.readAllBytes(OUTPUT_DIR.resolve("surat-ttd.pdf"));
        byte[] pdf = id.pkl.signingpqc.core.SamplePdf.create("Surat Uji Aplikasi", "Kedua.");
        HttpResponse<byte[]> response = sign(token, pdf, "surat.pdf", "code", nextCode(totpSecretBase32));
        assertEquals(200, response.statusCode());
        assertEquals("surat-ttd-2.pdf", savedPath(response).getFileName().toString());
        assertTrue(java.util.Arrays.equals(first, Files.readAllBytes(OUTPUT_DIR.resolve("surat-ttd.pdf"))));
    }

    @Test
    @Order(22)
    void invisibleTanpaWidgetTampak() throws Exception {
        assertEquals(200, api().putJson("/api/me/settings", token, "{\"appearance\":\"INVISIBLE\"}").statusCode());
        byte[] pdf = id.pkl.signingpqc.core.SamplePdf.create("Surat Invisible", "Tanpa kotak.");
        HttpResponse<byte[]> response = sign(token, pdf, "invisible.pdf", "code", nextCode(totpSecretBase32));
        assertEquals(200, response.statusCode());
        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(response.body())) {
            var fields = document.getDocumentCatalog().getAcroForm().getFields().stream()
                    .filter(org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField.class::isInstance)
                    .map(org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField.class::cast).toList();
            assertTrue(fields.size() >= 2);
            for (var field : fields) {
                assertEquals(0f, field.getWidgets().get(0).getRectangle().getWidth(), 0.01f, field.getFullyQualifiedName());
            }
        }
        assertEquals("SAH", verifyOpen(response.body()).get("validation").get("status").asString());
        assertEquals(200, api().putJson("/api/me/settings", token, "{\"appearance\":\"VISIBLE\"}").statusCode());
    }

    @Test
    @Order(23)
    void kodeSalahTanpaBerkas() throws Exception {
        long before = countFiles(OUTPUT_DIR);
        clock.advance(Duration.ofSeconds(Totp.PERIOD_SECONDS));
        String now = Totp.code(TestBase32.decode(totpSecretBase32), clock.instant().getEpochSecond() / Totp.PERIOD_SECONDS);
        String wrong = now.equals("123456") ? "654321" : "123456";
        HttpResponse<byte[]> response = sign(token, id.pkl.signingpqc.core.SamplePdf.create("x", "y"), "salah.pdf",
                "code", wrong);
        assertEquals(401, response.statusCode());
        assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("Konfirmasi tidak cocok"));
        assertEquals(before, countFiles(OUTPUT_DIR));
    }

    @Test
    @Order(24)
    void kodeDipakaiUlangDitolak() throws Exception {
        long before = countFiles(OUTPUT_DIR);
        HttpResponse<byte[]> response = sign(token, id.pkl.signingpqc.core.SamplePdf.create("x", "y"), "ulang.pdf",
                "code", lastCode);
        assertEquals(401, response.statusCode());
        assertEquals(before, countFiles(OUTPUT_DIR));
    }

    @Test
    @Order(25)
    void bukanPdfDitolak() throws Exception {
        long before = countFiles(OUTPUT_DIR);
        HttpResponse<byte[]> response = sign(token, "bukan pdf".getBytes(), "teks.pdf", "code", nextCode(totpSecretBase32));
        assertEquals(400, response.statusCode());
        assertEquals(before, countFiles(OUTPUT_DIR));
    }

    @Test
    @Order(26)
    void riwayatTercatat() throws Exception {
        JsonNode history = body(api().get("/api/me/history", token));
        assertTrue(history.size() >= 3, history.toString());
        assertEquals("Ditandatangani", history.get(history.size() - 1).get("activity").asString());
        assertEquals("surat-ttd.pdf", history.get(history.size() - 1).get("fileName").asString());
        assertEquals("invisible-ttd.pdf", history.get(0).get("fileName").asString());
    }

    @Test
    @Order(27)
    void passphraseMembukaKunci() throws Exception {
        String dina = register(this, "dina.uji", "dina@contoh.go.id");
        assertEquals(200, api().putJson("/api/me/settings", dina,
                "{\"confirmMethod\":\"PASSPHRASE\",\"passphrase\":\"" + PASSPHRASE + "\"}").statusCode());
        byte[] pdf = id.pkl.signingpqc.core.SamplePdf.create("Surat Dina", "Passphrase.");
        assertEquals(200, sign(dina, pdf, "dina.pdf", "passphrase", PASSPHRASE).statusCode());
        for (int i = 0; i < 5; i++) {
            assertEquals(401, sign(dina, pdf, "dina.pdf", "passphrase", "passphrase-salah-sekali").statusCode());
        }
        assertEquals(423, sign(dina, pdf, "dina.pdf", "passphrase", PASSPHRASE).statusCode());
    }
}
