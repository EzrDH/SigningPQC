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
        var noToken = api().get("/api/me", null);
        assertEquals(401, noToken.statusCode());
        // Penanda sesi berakhir (RFC 6750) agar klien bisa membedakannya dari "Konfirmasi tidak cocok".
        assertEquals("Bearer", noToken.headers().firstValue("WWW-Authenticate").orElse(null));
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
    @Order(13)
    void folderKeluaranDiDalamFolderDataDitolak() throws Exception {
        Path inside = DATA_DIR.resolve("accounts").resolve("keluaran-baru");
        Path traversal = OUTPUT_DIR.resolve("..").resolve(DATA_DIR.getFileName()).resolve("lain");
        for (Path target : java.util.List.of(DATA_DIR, inside, traversal)) {
            HttpResponse<String> response = api().putJson("/api/me/settings", token,
                    "{\"outputDir\":" + json.writeValueAsString(target.toString()) + "}");
            assertEquals(400, response.statusCode(), target.toString());
            assertEquals("Folder keluaran tidak boleh berada di dalam folder data aplikasi",
                    body(response).get("error").asString());
        }
        assertFalse(Files.exists(inside), "folder tidak dibuat");
        assertFalse(Files.exists(DATA_DIR.resolve("lain")), "folder tidak dibuat");
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

    /** Jawaban tanda tangan/perpanjangan berupa JSON {fileName, savedPath, pdfBase64}, bukan berkas PDF. */
    static JsonNode savedDocument(HttpResponse<byte[]> response) {
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
                "jawaban harus JSON agar tidak direbut pengelola unduhan");
        return JsonMapper.shared().readTree(new String(response.body(), StandardCharsets.UTF_8));
    }

    static Path savedPath(HttpResponse<byte[]> response) {
        return Path.of(savedDocument(response).get("savedPath").asString());
    }

    static byte[] signedPdf(HttpResponse<byte[]> response) {
        return java.util.Base64.getDecoder().decode(savedDocument(response).get("pdfBase64").asString());
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
        assertEquals("surat-ttd.pdf", savedDocument(response).get("fileName").asString());
        assertTrue(java.util.Arrays.equals(signedPdf(response), Files.readAllBytes(saved)));
        JsonNode result = verifyOpen(signedPdf(response));
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
        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(signedPdf(response))) {
            var fields = document.getDocumentCatalog().getAcroForm().getFields().stream()
                    .filter(org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField.class::isInstance)
                    .map(org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField.class::cast).toList();
            assertTrue(fields.size() >= 2);
            for (var field : fields) {
                assertEquals(0f, field.getWidgets().get(0).getRectangle().getWidth(), 0.01f, field.getFullyQualifiedName());
            }
        }
        assertEquals("SAH", verifyOpen(signedPdf(response)).get("validation").get("status").asString());
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
        assertTrue(response.headers().firstValue("WWW-Authenticate").isEmpty(), "konfirmasi salah bukan sesi berakhir");
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

    @Test
    @Order(28)
    void loginBerhasilTidakMeresetHitunganKonfirmasi() throws Exception {
        String eko = register(this, "eko.uji", "eko@contoh.go.id");
        assertEquals(200, api().putJson("/api/me/settings", eko,
                "{\"confirmMethod\":\"PASSPHRASE\",\"passphrase\":\"" + PASSPHRASE + "\"}").statusCode());
        byte[] pdf = id.pkl.signingpqc.core.SamplePdf.create("Surat Eko", "Tebakan passphrase.");
        for (int i = 0; i < 4; i++) {
            assertEquals(401, sign(eko, pdf, "eko.pdf", "passphrase", "passphrase-salah-sekali").statusCode());
        }
        HttpResponse<String> relogin = login("eko.uji", PASSWORD);
        assertEquals(200, relogin.statusCode(), relogin.body());
        String fresh = body(relogin).get("token").asString();
        assertEquals(401, sign(fresh, pdf, "eko.pdf", "passphrase", "passphrase-salah-sekali").statusCode());
        assertEquals(423, sign(fresh, pdf, "eko.pdf", "passphrase", PASSPHRASE).statusCode());
    }

    // ------------------------------------------------------------------ Task 8: detail verifikasi dan perpanjangan

    @Test
    @Order(30)
    void verifikasiDenganDetail() throws Exception {
        int before = body(api().get("/api/me/history", token)).size();
        byte[] signed = Files.readAllBytes(OUTPUT_DIR.resolve("surat-ttd.pdf"));
        HttpResponse<byte[]> response = api().multipart("POST", "/api/me/verify", token, signed, "surat-ttd.pdf",
                "application/pdf", java.util.List.of());
        assertEquals(200, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        JsonNode result = json.readTree(new String(response.body(), StandardCharsets.UTF_8));
        assertEquals("SAH", result.get("validation").get("status").asString());
        JsonNode rsa = result.get("signatures").get(0);
        JsonNode detail = rsa.get("detail");
        assertTrue(detail.get("certificate").get("subject").asString().contains("CN=budi.uji"), detail.toString());
        assertTrue(detail.get("certificate").get("issuer").asString().contains("CA Antara"), detail.toString());
        assertEquals("RSA 3072 bit", detail.get("certificate").get("keyAlgorithm").asString());
        JsonNode chain = detail.get("chain");
        assertEquals(3, chain.size(), chain.toString());
        assertTrue(chain.get(2).get("trustedRoot").asBoolean());
        assertFalse(chain.get(0).get("trustedRoot").asBoolean());
        assertEquals("Tidak dicabut", detail.get("revocation").asString());
        assertFalse(detail.get("coverage").asString().isBlank());
        assertEquals("Seluruh dokumen saat ditandatangani",
                result.get("signatures").get(1).get("detail").get("coverage").asString());
        assertTrue(result.get("signatures").get(1).get("detail").get("certificate").get("keyAlgorithm").asString()
                .startsWith("ML-DSA"));
        assertFalse("-".equals(rsa.get("signatureTimestamp").get("serialHex").asString()));
        JsonNode history = body(api().get("/api/me/history", token));
        assertEquals(before + 1, history.size());
        assertEquals("Diverifikasi", history.get(0).get("activity").asString());
        assertEquals("SAH", history.get(0).get("status").asString());
    }

    private static final String FINGERPRINT = "^[0-9A-F]{2}(:[0-9A-F]{2}){31}$";

    @Test
    @Order(30)
    void verifikasiMemuatBuktiDanOid() throws Exception {
        JsonNode result = verifyOpen(Files.readAllBytes(OUTPUT_DIR.resolve("surat-ttd.pdf")));
        JsonNode rsa = result.get("signatures").get(0);
        JsonNode mlDsa = result.get("signatures").get(1);
        JsonNode rsaDetail = rsa.get("detail");
        JsonNode mlDsaDetail = mlDsa.get("detail");

        // a. algoritma hash dan level PAdES beserta OID
        assertEquals("1.2.840.113549.1.1.11", rsaDetail.get("signatureAlgorithmOid").asString(), rsaDetail.toString());
        assertEquals("2.16.840.1.101.3.4.2.1", rsaDetail.get("digestAlgorithmOid").asString());
        assertEquals("2.16.840.1.101.3.4.3.18", mlDsaDetail.get("signatureAlgorithmOid").asString());
        assertEquals("2.16.840.1.101.3.4.2.3", mlDsaDetail.get("digestAlgorithmOid").asString());

        // b. sidik jari, kunci publik, EKU, kebijakan sertifikat
        JsonNode certificate = rsaDetail.get("certificate");
        assertTrue(certificate.get("fingerprintSha256").asString().matches(FINGERPRINT), certificate.toString());
        assertEquals("1.2.840.113549.1.1.1", certificate.get("publicKey").get("oid").asString());
        assertEquals("1.2.840.113549.1.1.11", certificate.get("signatureAlgorithm").get("oid").asString());
        assertEquals("1.3.6.1.5.5.7.3.36", certificate.get("extendedKeyUsage").get(0).get("oid").asString());
        assertEquals("Penandatanganan dokumen", certificate.get("extendedKeyUsage").get(0).get("name").asString());
        assertEquals("1.3.6.1.4.1.32473.2.1", certificate.get("certificatePolicies").get(0).get("oid").asString());
        assertEquals("2.16.840.1.101.3.4.3.18", mlDsaDetail.get("certificate").get("publicKey").get("oid").asString());

        // c + e. rincian rantai dan sumber CRL
        JsonNode chain = rsaDetail.get("chain");
        for (JsonNode entry : chain) {
            assertFalse(entry.get("serialHex").asString().isBlank(), entry.toString());
            assertTrue(entry.get("fingerprintSha256").asString().matches(FINGERPRINT), entry.toString());
            assertTrue(entry.has("notBefore") && entry.has("notAfter"), entry.toString());
        }
        JsonNode crl = chain.get(0).get("crls").get(0);
        assertTrue(crl.get("issuer").asString().contains("CA Antara"), crl.toString());
        assertTrue(crl.get("source").asString().contains("DSS"), crl.toString());
        assertTrue(crl.has("thisUpdate") && crl.has("nextUpdate"), crl.toString());
        assertFalse(crl.get("listed").asBoolean());
        assertEquals(0, chain.get(2).get("crls").size(), "root tidak dicek CRL");

        // d. jangkar kepercayaan yang dipakai
        JsonNode anchors = result.get("trustAnchors");
        assertEquals(2, anchors.size(), anchors.toString());
        assertEquals(chain.get(2).get("fingerprintSha256").asString(), anchors.get(0).get("fingerprintSha256").asString());

        // f. revisi dan perlindungan tanda tangan berikutnya
        assertEquals(2, rsaDetail.get("revision").asInt());
        assertEquals("Isi dokumen sampai revisi 2", rsaDetail.get("coverage").asString());
        assertEquals(3, mlDsaDetail.get("revision").asInt());
        assertTrue(rsaDetail.get("protectedBy").asString().contains("ML-DSA"), rsaDetail.toString());
        assertTrue(mlDsaDetail.get("protectedBy").isNull(), mlDsaDetail.toString());

        // g. DSS dan document timestamp beserta OID kebijakan TSA
        JsonNode dss = result.get("dss");
        assertTrue(dss.get("present").asBoolean(), dss.toString());
        assertTrue(dss.get("certificates").asInt() > 0 && dss.get("crls").asInt() > 0, dss.toString());
        assertEquals("1.3.6.1.4.1.32473.1.1", rsa.get("signatureTimestamp").get("policyOid").asString());
        JsonNode docTimestamp = null;
        for (JsonNode layer : result.get("validation").get("layers")) {
            if ("DOCUMENT_TIMESTAMP".equals(layer.get("kind").asString())) {
                docTimestamp = layer;
            }
        }
        assertTrue(docTimestamp != null, result.get("validation").toString());
        assertEquals("1.3.6.1.4.1.32473.1.1", docTimestamp.get("signatureTimestamp").get("policyOid").asString());
        assertFalse("-".equals(docTimestamp.get("signatureTimestamp").get("serialHex").asString()));
    }

    @Test
    @Order(31)
    void verifyLamaTetapTerbuka() throws Exception {
        int before = body(api().get("/api/me/history", token)).size();
        JsonNode result = verifyOpen(Files.readAllBytes(OUTPUT_DIR.resolve("surat-ttd.pdf")));
        assertTrue(result.get("signatures").get(0).has("detail"));
        assertEquals(before, body(api().get("/api/me/history", token)).size());
    }

    @Test
    @Order(32)
    void perpanjangDitolakBilaBelumPerlu() throws Exception {
        HttpResponse<byte[]> response = api().multipart("POST", "/api/me/renew", token,
                Files.readAllBytes(OUTPUT_DIR.resolve("surat-ttd.pdf")), "surat-ttd.pdf", "application/pdf",
                java.util.List.of());
        assertEquals(409, response.statusCode());
        assertTrue(new String(response.body(), StandardCharsets.UTF_8)
                .contains("Dokumen ini belum perlu atau sudah tidak bisa diperpanjang"));
    }
}
