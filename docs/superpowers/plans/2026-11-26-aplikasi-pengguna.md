# Aplikasi Pengguna (Vue) dengan Akun, Passphrase/TOTP, dan Spesimen: Rencana Implementasi

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pengguna bisa mendaftar, masuk, menandatangani PDF (hybrid RSA lalu ML-DSA, B-LTA) setelah konfirmasi passphrase atau TOTP, memverifikasi PDF dengan detail sertifikat, dan mengatur tampilan, metode konfirmasi, serta folder keluaran, lewat antarmuka Vue sesuai mockup versi 7.

**Architecture:** Backend `app-server` mendapat paket baru `id.pkl.signingpqc.server.account` (akun, pembungkusan kunci, TOTP, sesi, riwayat) dan controller `/api/auth/**` serta `/api/me/**`. `PkiService` mendapat penanda tangan akun yang kuncinya tidak pernah ditulis dengan password konstanta. `signer-core` mendapat spesimen gambar pada `VisibleSignature`, serta nomor seri di `TimestampReport`. Frontend `frontend/` dibangun ulang dengan Vue Router, Pinia, pdfjs-dist, dan qrcode, tanpa pustaka komponen UI.

**Tech Stack:** Java 25, Spring Boot 4.2, Bouncy Castle 1.87 (Argon2id), PDFBox 3.0.8, JUnit 5; Vue 3, Vite, TypeScript, Pinia, Vue Router, pdfjs-dist, qrcode, Vitest.

**Spec:** `docs/superpowers/specs/2026-11-26-aplikasi-pengguna-design.md` (disetujui ejer 26 Nov 2026). Mockup acuan: `docs/superpowers/specs/2026-11-26-aplikasi-pengguna-mockup.html` (versi 7).

## Global Constraints
- Build dan uji backend di container: `cd backend && mvn -o -q -Denforcer.skip=true -Dmaven.compiler.release=21 -pl app-server -am test` (JDK 21). Konfirmasi akhir dengan JDK 25 di laptop lewat Remote Control: `.\mvnw.cmd -B test` di `backend\`.
- Frontend: `cd frontend && npm test` (Vitest) dan `npm run build` (vue-tsc + vite) harus lolos.
- Pesan aplikasi, nama uji, dan dokumen dalam Bahasa Indonesia. Pengecualian: label "Visible" dan "Invisible".
- Galat API berbentuk JSON `{"error": "<pesan Bahasa Indonesia>"}` lewat `ApiException`/`ApiErrors` yang ada.
- Passphrase, kode TOTP, password, DEK, dan rahasia TOTP tidak boleh muncul di log, respons API, atau berkas dalam bentuk terbuka.
- Lingkungan uji saja. Jangan meng-commit `*.p12`, `*.key`, `app.key`, folder `accounts/`, atau spesimen.
- Argon2id: m=65536 KiB, t=3, p=1, salt 16 byte, keluaran 32 byte (password dan KEK passphrase).
- AES-256-GCM: IV 12 byte acak per enkripsi, tag 128 bit.
- TOTP RFC 6238: HMAC-SHA1, 6 digit, periode 30 detik, jendela ±1 langkah, rahasia 20 byte, kode tidak boleh dipakai ulang.
- Sesi: token 32 byte acak (base64url), menganggur 30 menit, mutlak 8 jam, di memori.
- Penguncian: 5 kegagalan berturut-turut per akun (password, passphrase, atau TOTP) mengunci 5 menit.
- Validasi daftar: username `^[a-z0-9._-]{3,32}$` (diubah ke huruf kecil), email memuat satu `@`, password minimal 8, passphrase minimal 12.
- Spesimen: PNG atau JPEG, maksimal 1 MB, maksimal 2000 px per sisi, disimpan sebagai PNG.
- Kotak tanda tangan minimal 120 x 60 point, bawaan 180 x 75 point.
- Folder keluaran bawaan `${user.home}/output-signing`. Akhiran `-ttd`, `-ttd-2`, ... dan `-diperpanjang`.
- Riwayat: maksimal 200 entri disimpan, API mengembalikan 50 terbaru.
- Warna: tinta `#0e1726`, biru `#0f3d91`, tint `#eef3fc`, hijau sah `#13804a`. Font Plus Jakarta Sans (berkas lokal, bukan CDN, agar Electron bisa offline).
- Setiap hasil uji dicatat di `docs/temuan/` (T-013 backend akun, T-014 antarmuka).

## Review Focus
1. Passphrase atau kode TOTP salah saat menandatangani: tidak ada PDF, pesan tidak membedakan alasan, dihitung ke penguncian, dan percobaan ke-6 ditolak walau benar (uji di Task 5 dan Task 7).
2. Menandatangani berkas yang sama dua kali: berkas kedua `-ttd-2.pdf`, berkas pertama tidak tertimpa (uji di Task 7).
3. Berkas bukan PDF ke `/api/me/sign` atau `/api/me/verify`: HTTP 400 dengan pesan, bukan 500, dan tidak ada berkas di folder keluaran (uji di Task 7).
4. Halaman PDF berotasi 90 derajat atau berukuran bukan A4: spesimen yang diletakkan di layar jatuh di posisi yang sama di PDF (uji di Task 12).
5. Folder keluaran relatif atau tidak bisa ditulis: HTTP 400 dan pengaturan lama tetap (uji di Task 6).

---

### Task 1: Primitif kriptografi akun (Argon2id, AES-GCM, TOTP, Base32)

**Files:**
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/Argon2id.java`
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/Aead.java`
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/Totp.java`
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/Base32.java`
- Test: `backend/app-server/src/test/java/id/pkl/signingpqc/server/account/AccountCryptoTest.java`

**Interfaces:**
- Produces:
  - `final class Argon2id`: `static String hash(char[] secret)` menghasilkan teks `argon2id$65536$3$1$<salt b64>$<hash b64>`; `static boolean verify(char[] secret, String encoded)` (perbandingan `MessageDigest.isEqual`); `static byte[] deriveKey(char[] secret, byte[] salt)` (32 byte).
  - `final class Aead`: `static byte[] seal(byte[] key, byte[] plaintext, byte[] aad)` menghasilkan `IV(12) || ciphertext+tag`; `static byte[] open(byte[] key, byte[] sealed, byte[] aad) throws GeneralSecurityException`.
  - `final class Totp`: `static String code(byte[] secret, long step)`; `static OptionalLong verify(byte[] secret, String code, Instant now, long lastUsedStep)` mengembalikan langkah yang cocok (dalam ±1 dan `> lastUsedStep`) atau kosong; `static String uri(String issuer, String label, byte[] secret)`; konstanta `PERIOD_SECONDS = 30`.
  - `final class Base32`: `static String encode(byte[] data)` RFC 4648 tanpa padding.

- [ ] **Step 1: Tulis uji gagal** `AccountCryptoTest`:
  - `argon2idHashDanVerifikasi`: `verify("rahasia-uji-123".toCharArray(), hash(...))` true; kata lain false; hasil `hash` diawali `argon2id$65536$3$1$`; dua `hash` dari teks sama berbeda (salt acak).
  - `aeadBolakBalikDanTamper`: `open(seal(k, p, aad))` sama dengan `p`; membalik satu bit ciphertext atau memakai `aad` lain melempar `GeneralSecurityException`.
  - `totpVektorRfc6238`: rahasia ASCII `"12345678901234567890"`; `code(s, 59/30)` = `"287082"`, `code(s, 1111111109L/30)` = `"081804"`, `code(s, 1234567890L/30)` = `"005924"`, `code(s, 2000000000L/30)` = `"279037"`.
  - `totpJendelaDanTanpaPemakaianUlang`: kode langkah `n-1`, `n`, `n+1` diterima pada `now` di langkah `n`; langkah `n+2` ditolak; kode yang sama dengan `lastUsedStep = n` ditolak.
  - `base32Rfc4648`: `encode("foobar".getBytes(US_ASCII))` = `"MZXW6YTBOI"`.
  - `uriOtpauth`: `uri("TTE Uji", "budi.uji", s)` = `"otpauth://totp/TTE%20Uji:budi.uji?secret=<base32>&issuer=TTE%20Uji&algorithm=SHA1&digits=6&period=30"`.
- [ ] **Step 2:** `cd backend && mvn -o -q -Denforcer.skip=true -Dmaven.compiler.release=21 -pl app-server -am test -Dtest=AccountCryptoTest -Dsurefire.failIfNoSpecifiedTests=false`. Harapan: gagal kompilasi.
- [ ] **Step 3:** Implementasikan. Argon2 lewat `org.bouncycastle.crypto.generators.Argon2BytesGenerator` dengan `Argon2Parameters.ARGON2_id`, versi 0x13. TOTP dengan `javax.crypto.Mac` `HmacSHA1`, dynamic truncation RFC 4226.
- [ ] **Step 4:** Jalankan uji yang sama. Harapan: 6 lolos.
- [ ] **Step 5:** Commit `"Aplikasi pengguna: primitif kriptografi akun (Argon2id, AES-GCM, TOTP)"`.

### Task 2: Penanda tangan akun di PKI uji, reset, dan setup otomatis

**Files:**
- Modify: `backend/app-server/src/main/java/id/pkl/signingpqc/server/PkiService.java`
- Modify: `backend/app-server/src/main/resources/application.properties`
- Modify: `backend/app-server/src/test/java/id/pkl/signingpqc/server/RestApiTest.java` (hanya `@DynamicPropertySource`)
- Test: `backend/app-server/src/test/java/id/pkl/signingpqc/server/PkiAccountSignerTest.java`

**Interfaces:**
- Produces:
  - `SignerRecord` mendapat komponen terakhir `Boolean account` (boxed, agar `state.json` lama tanpa field ini tetap terbaca; `null` berarti bukan akun). Tambah `boolean isAccount()`.
  - `public record AccountSigner(SignerRecord record, SignerPair pair)` di `PkiService`.
  - `synchronized AccountSigner issueAccountSigner(String commonName)`: terbitkan pasangan sertifikat tanpa jabatan dan unit, tulis hanya `signers/<id>/rsa.pem` dan `signers/<id>/mldsa.pem` (rantai), tidak menulis `.p12`, dan catat di `state.json` dengan `account = true`.
  - `synchronized List<X509Certificate> accountCertificates(String id)`: `[rsaLeaf, mlDsaLeaf]`.
  - `signerRecord(id)` dan `revoke(id)` bekerja untuk penanda tangan akun (pakai sertifikat, bukan `SignerPair`). `signer(id)` untuk akun melempar 409 `"Kunci penanda tangan akun hanya bisa dibuka lewat konfirmasi pengguna"`.
  - `revocationSource(at)` mencocokkan pencabutan juga terhadap sertifikat akun.
  - `setup(org, reset)` juga menghapus `<data-dir>/accounts`.
  - Konstruktor menjadi `PkiService(Path dataDir, String password, String crlBaseUrl, JsonMapper json, boolean autoSetup, String organization)`, dengan `@Value("${signingpqc.pki.auto-setup:false}") boolean autoSetup` dan `@Value("${signingpqc.pki.organization:SigningPQC Uji}") String organization`. Bila `autoSetup` dan PKI belum siap setelah `load()`, panggil `setup(organization, false)`.
  - `application.properties`: `signingpqc.pki.auto-setup=true`, `signingpqc.pki.organization=SigningPQC Uji`, `signingpqc.renewal-warning-days=30`, `signingpqc.default-output-dir=${user.home}/output-signing`.
  - `RestApiTest` menambah `registry.add("signingpqc.pki.auto-setup", () -> "false")`.

- [ ] **Step 1: Tulis uji gagal** `PkiAccountSignerTest` (JUnit biasa; `new PkiService(tempDir, "uji", "http://127.0.0.1/crl/", JsonMapper.shared(), false, "SigningPQC Uji")` lalu `setup`):
  - `kunciAkunTidakDitulisKeDisk`: setelah `issueAccountSigner("budi.uji")`, tidak ada berkas `*.p12` di `signers/<id>/`, ada `rsa.pem` dan `mldsa.pem`; subjek RSA sama dengan `CN=budi.uji,O=SigningPQC Uji,C=ID` (bandingkan `X500Principal`).
  - `dimuatUlangDanBisaDicabut`: `PkiService` baru pada folder sama; `signerRecord(id).isAccount()` true; `revoke(id)` berhasil; `localValidationData(now).crls()` memuat CRL dengan `getRevokedCertificate(serial RSA)` tidak null.
  - `signerUntukAkunDitolak`: `signer(id)` melempar `ApiException` status 409.
  - `resetMenghapusAkun`: buat `accounts/x/account.json`, lalu `setup(org, true)`; folder `accounts` tidak ada.
  - `stateLamaTanpaFieldAccount`: tulis `state.json` dari PKI yang dibuat lalu hapus field `account` dari JSON penanda tangan non-akun; muat ulang; `signerRecord(id).isAccount()` false.
  - `setupOtomatis`: konstruktor dengan `autoSetup = true` pada folder kosong; `ready()` true.
- [ ] **Step 2:** Jalankan `-Dtest=PkiAccountSignerTest`. Harapan: gagal kompilasi.
- [ ] **Step 3:** Implementasikan. Sertifikat akun dibaca dari `.pem` di `load()` dengan `CertificateFactory`, disimpan di peta `accountCertificates`.
- [ ] **Step 4:** Jalankan uji itu lalu seluruh `app-server`. Harapan: semua lolos, termasuk `RestApiTest` lama.
- [ ] **Step 5:** Commit `"Aplikasi pengguna: penanda tangan akun tanpa salinan kunci, reset, setup otomatis"`.

### Task 3: Spesimen gambar dan tanda tangan RSA invisible

**Files:**
- Modify: `backend/signer-core/src/main/java/id/pkl/signingpqc/core/VisibleSignature.java`
- Modify: `backend/signer-core/src/main/java/id/pkl/signingpqc/core/SignatureAppearance.java`
- Modify: `backend/app-server/src/main/java/id/pkl/signingpqc/server/SigningService.java`
- Test: `backend/pki-test/src/test/java/id/pkl/signingpqc/pki/VisibleSignatureTest.java` (tambah uji)

**Interfaces:**
- Produces:
  - `VisibleSignature(int pageIndex, float x, float yFromTop, float width, float height, List<String> extraLines, byte[] specimenPng)`; konstruktor 6 argumen lama tetap ada dan memberi `specimenPng = null`. Accessor mengembalikan salinan array.
  - Bila `specimenPng != null`, appearance hanya menggambar gambar itu (`PDImageXObject.createFromByteArray`), diskalakan pas tanpa distorsi, rata kiri, tengah vertikal, tanpa latar, bingkai, atau teks.
  - `SigningService.signHybrid(byte[] pdf, SignerPair pair, SignerRecord record, VisibleSignature visibleOrNull) -> byte[]`: RSA visible bila `visibleOrNull != null`, selain itu RSA invisible; lalu ML-DSA invisible; keduanya dengan signature timestamp; lalu DSS (`pki.revocationSource(now)`) dan document timestamp `rsa-g1` (B-LTA). Endpoint `/api/sign` lama tetap memakai metode lamanya.
  - `SigningService.placement(byte[] pdf, int page, Float x, Float y, Float w, Float h, byte[] specimenPng) -> VisibleSignature`: bawaan 180 x 75 point di kanan bawah halaman yang diminta dengan margin 40, baris tambahan kosong.

- [ ] **Step 1: Tulis uji gagal** di `VisibleSignatureTest`:
  - `spesimenGambarDigambarTanpaTeks`: PNG 400 x 160 buatan `BufferedImage`; tanda tangani visible kotak 180 x 75; widget `/AP /N` punya tepat satu XObject image berukuran 400 x 160; `PDFTextStripper` di area kotak tidak memuat `"Ditandatangani"`; `PdfSignatureVerifier` melaporkan tanda tangan sah.
  - `spesimenBukanGambarDitolak`: `specimenPng = "bukan".getBytes()` melempar `IOException` atau `IllegalArgumentException` saat menandatangani.
- [ ] **Step 2:** `mvn -o -q -Denforcer.skip=true -Dmaven.compiler.release=21 -pl pki-test -am test -Dtest=VisibleSignatureTest -Dsurefire.failIfNoSpecifiedTests=false`. Harapan: gagal kompilasi.
- [ ] **Step 3:** Implementasikan `VisibleSignature`, `SignatureAppearance`, dan dua metode `SigningService`.
- [ ] **Step 4:** Jalankan uji itu, lalu `pki-test` dan `app-server` penuh. Harapan: semua lolos.
- [ ] **Step 5:** Commit `"Aplikasi pengguna: spesimen gambar dan tanda tangan RSA invisible"`.

### Task 4: Penyimpanan akun dan brankas kunci

**Files:**
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/Account.java`
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/AccountStore.java`
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/AppKey.java`
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/KeyVault.java`
- Test: `backend/app-server/src/test/java/id/pkl/signingpqc/server/account/KeyVaultTest.java`

**Interfaces:**
- Consumes: `Argon2id`, `Aead` (Task 1); `PkiService.AccountSigner` (Task 2).
- Produces:
  - `enum ConfirmMethod { PASSPHRASE, TOTP }`, `enum Appearance { VISIBLE, INVISIBLE }` (di `Account.java` sebagai nested enum).
  - `record Account(String id, String username, String email, String passwordHash, String signerId, ConfirmMethod confirmMethod, Appearance appearance, String outputDir, Instant createdAt, long lastTotpStep)` dengan metode `with...` untuk field yang berubah.
  - `@Component AccountStore(@Value("${signingpqc.data-dir}") Path dataDir, JsonMapper json)`: `Path dir(String id)`, `Optional<Account> byId(String id)`, `Optional<Account> byLogin(String usernameOrEmail)` (tidak peka huruf besar), `boolean usernameTaken(String)`, `boolean emailTaken(String)`, `void save(Account)` (tulis ke berkas sementara lalu `Files.move` ATOMIC_MOVE).
  - `@Component AppKey`: `byte[] key()`; membuat `<data-dir>/app.key` 32 byte sekali, izin `rw-------` bila sistem berkas POSIX.
  - `@Component KeyVault(AccountStore, AppKey)`:
    - `void create(String accountId, SignerPair pair, byte[] dek, byte[] dekPp, boolean withApp)`: `keys.enc` = `Aead.seal(dek, pkcs12(pair), aad="keys:"+id)`; `dek.pp` = `dekPp` (sudah dibungkus); `dek.app` bila `withApp`.
    - `static byte[] newDek()`, `static byte[] wrapWithPassphrase(byte[] dek, char[] passphrase)` (format `salt(16) || Aead.seal(KEK, dek, "dek.pp")`).
    - `SignerPair unlockWithPassphrase(String id, char[] passphrase)`; `SignerPair unlockWithApp(String id)`. Passphrase salah melempar `ApiException` 401 `"Konfirmasi tidak cocok"`.
    - `void enableApp(String id, char[] passphrase)`; `void disableApp(String id, char[] passphrase)`; `boolean hasApp(String id)`.
    - `void writeTotpSecret(String id, byte[] secret)`, `byte[] totpSecret(String id)` (`totp.enc` dengan `AppKey`).
  - PKCS#12 di dalam `keys.enc` memakai alias `rsa` dan `mldsa` dan password internal konstanta `"keys"`; perlindungan datang dari AES-GCM.

- [ ] **Step 1: Tulis uji gagal** `KeyVaultTest` (PKI uji dari `TestPkiFactory` di folder sementara):
  - `passphraseBenarMembukaKunci`: kunci RSA hasil `unlockWithPassphrase` sama (`getEncoded`) dengan kunci asli.
  - `passphraseSalahDitolak`: melempar `ApiException` status 401 dengan pesan `"Konfirmasi tidak cocok"`.
  - `modeTotpButuhDekApp`: tanpa `withApp`, `unlockWithApp` melempar 409; setelah `enableApp(id, passphrase)` berhasil; setelah `disableApp` berkas `dek.app` tidak ada.
  - `tidakAdaRahasiaTerbuka`: semua berkas di folder akun tidak memuat byte passphrase, password, atau `getEncoded()` kunci privat RSA (cari subarray).
  - `rahasiaTotpBolakBalik`: `totpSecret(id)` sama dengan yang ditulis.
  - `storeCariLoginTidakPekaHuruf`: `byLogin("BUDI.UJI")` dan `byLogin("Budi.Uji@contoh.go.id")` menemukan akun.
- [ ] **Step 2:** Jalankan `-Dtest=KeyVaultTest`. Harapan: gagal kompilasi.
- [ ] **Step 3:** Implementasikan. Hapus array DEK/KEK dengan `Arrays.fill` di blok `finally`.
- [ ] **Step 4:** Jalankan uji itu. Harapan: 6 lolos.
- [ ] **Step 5:** Commit `"Aplikasi pengguna: penyimpanan akun dan brankas kunci berlapis"`.

### Task 5: Daftar, masuk, sesi, penguncian, dan interceptor

**Files:**
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/LoginGuard.java`
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/SessionService.java`
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/AccountService.java`
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/AuthController.java`
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/AuthInterceptor.java`
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/WebConfig.java`
- Test: `backend/app-server/src/test/java/id/pkl/signingpqc/server/account/AccountApiTest.java`
- Test: `backend/app-server/src/test/java/id/pkl/signingpqc/server/account/LoginGuardTest.java`

**Interfaces:**
- Consumes: Task 1, 2, 4.
- Produces:
  - `@Component LoginGuard(Clock clock)`: `void check(String accountId)` melempar `ApiException` 423 `"Terlalu banyak percobaan. Coba lagi dalam 5 menit."` bila terkunci; `void failed(String accountId)`; `void succeeded(String accountId)`.
  - `@Component SessionService(Clock clock)`: `String create(String accountId)`; `Optional<String> accountId(String token)` (memperbarui waktu menganggur); `void remove(String token)`.
  - Bean `Clock` (`Clock.systemUTC()`) di `WebConfig`.
  - `@Service AccountService`:
    - `record RegisterStart(String registrationId, String otpauthUri)`; `RegisterStart registerStart(String username, String email, char[] password, char[] passphrase)`: validasi (Global Constraints), 409 `"Username sudah dipakai"` / `"Email sudah dipakai"`; menyimpan pendaftaran tertunda di memori 10 menit berisi hash password, DEK, `dek.pp`, rahasia TOTP (passphrase tidak disimpan).
    - `record Session(String token, Account account)`; `Session registerFinish(String registrationId, String code)`: kode salah 400 `"Kode tidak cocok"`; pendaftaran kedaluwarsa 410 `"Pendaftaran kedaluwarsa, ulangi dari awal"`; bila benar memanggil `pki.issueAccountSigner(username)`, `KeyVault.create(..., withApp = true)`, `confirmMethod = TOTP`, `appearance = VISIBLE`, `outputDir = ${user.home}/output-signing`.
    - `Session login(String login, char[] password)`: gagal 401 `"Username/email atau password salah"` untuk akun tidak ada maupun password salah.
    - `SignerPair unlock(Account account, String passphrase, String code)`: sesuai `confirmMethod`; memakai `LoginGuard`; kode TOTP benar menyimpan `lastTotpStep`.
  - `AuthController` di `/api/auth`: `POST register/start` (JSON `username,email,password,passphrase` → `{registrationId, otpauthUri}`), `POST register/finish` (JSON `registrationId,code` → `{token, username}`), `POST login` (JSON `login,password` → `{token, username}`), `POST logout`.
  - `AuthInterceptor` untuk `/api/me/**`: tanpa token atau token tidak sah → 401 `"Sesi berakhir, silakan masuk lagi"`; akun yang sudah tidak ada (setelah reset PKI) juga 401. Menyimpan `accountId` di atribut request `"accountId"`.

- [ ] **Step 1: Tulis uji gagal** `LoginGuardTest` dengan `Clock` yang bisa dimajukan: 5 kali `failed` lalu `check` melempar 423; setelah jam maju 5 menit 1 detik `check` lolos; `succeeded` mengosongkan hitungan. `SessionService` di kelas yang sama: token kedaluwarsa setelah 30 menit menganggur dan setelah 8 jam meski aktif.
- [ ] **Step 2: Tulis uji gagal** `AccountApiTest` (`@SpringBootTest` RANDOM_PORT, `@TestMethodOrder`, folder data sementara, `signingpqc.pki.auto-setup=true`, `user.home` tidak dipakai: set `signingpqc.default-output-dir` ke folder sementara; properti ini dibaca `AccountService` dengan bawaan `${user.home}/output-signing`):
  - Order 1 `daftarDuaLangkah`: `register/start` → 200 dengan `otpauthUri` diawali `otpauth://totp/`; hitung kode dari `secret` di URI dengan `Totp.code`; `register/finish` → 200 dengan `token`.
  - Order 2 `usernameDanEmailUnik`: `register/start` dengan username sama → 409.
  - Order 3 `validasiInput`: password 7 karakter → 400; passphrase 11 karakter → 400; username `"Ab"` → 400.
  - Order 4 `masukDenganEmailAtauUsername`: keduanya 200; password salah 401 dengan pesan sama untuk username tidak ada.
  - Order 5 `kunciSetelahLimaGagal`: 5 kali password salah, lalu password benar → 423.
  - Order 6 `meTanpaTokenDitolak`: `GET /api/me` tanpa token → 401.
  - Order 7 `berkasAkunTanpaRahasia`: isi `account.json` tidak memuat password maupun passphrase uji.
- [ ] **Step 3:** Jalankan `-Dtest='LoginGuardTest,AccountApiTest'`. Harapan: gagal kompilasi.
- [ ] **Step 4:** Implementasikan. Order 5 memakai akun kedua agar akun utama tidak terkunci untuk task berikutnya.
- [ ] **Step 5:** Jalankan uji itu lalu seluruh `app-server`. Harapan: semua lolos.
- [ ] **Step 6:** Commit `"Aplikasi pengguna: daftar, masuk, sesi, dan penguncian"`.

### Task 6: Profil, pengaturan, dan spesimen

**Files:**
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/MeController.java`
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/HistoryStore.java`
- Modify: `backend/app-server/src/test/java/id/pkl/signingpqc/server/account/AccountApiTest.java`

**Interfaces:**
- Consumes: Task 2, 4, 5.
- Produces:
  - `record CertificateSummary(String algorithm, String issuer, String serialHex, Instant notBefore, Instant notAfter, boolean revoked)`; `record MeResponse(String username, String email, Instant createdAt, List<CertificateSummary> certificates, String confirmMethod, String appearance, String outputDir, boolean hasSpecimen)`.
  - `GET /api/me` → `MeResponse` (`certificates` berurutan RSA lalu ML-DSA; `algorithm` `"RSA 3072 bit"` dan `"ML-DSA-65"`).
  - `PUT /api/me/settings` JSON `{confirmMethod?, appearance?, outputDir?, passphrase?}`: mengubah `confirmMethod` tanpa `passphrase` → 400 `"Masukkan passphrase untuk mengganti metode konfirmasi"`; passphrase salah → 401 dan dihitung `LoginGuard`; ke TOTP memanggil `KeyVault.enableApp`, ke PASSPHRASE memanggil `disableApp`. `outputDir` harus absolut, dibuat bila belum ada, dan diuji tulis dengan berkas sementara; gagal → 400 `"Folder tidak bisa dipakai untuk menyimpan"` tanpa mengubah apa pun.
  - `PUT /api/me/specimen` multipart `file`: bukan PNG/JPEG (`ImageIO.read` null) → 400; > 1 MB → 400; sisi > 2000 px → 400; disimpan `specimen.png`. `GET /api/me/specimen` → `image/png` atau 404.
  - `@Component HistoryStore`: `record Entry(String fileName, String activity, Instant time, String status, String path)`; `void add(String accountId, Entry)` (maksimal 200, terbaru di depan); `List<Entry> latest(String accountId)` (50). `activity` salah satu `"Ditandatangani"`, `"Diverifikasi"`, `"Diperpanjang"`.
  - `GET /api/me/history` → `List<Entry>`.

- [ ] **Step 1: Tulis uji gagal** (tambah ke `AccountApiTest`, order 10 sampai 16):
  - `profilBerisiDuaSertifikat`: `GET /api/me` memuat `username`, dua sertifikat, `confirmMethod = "TOTP"`, `appearance = "VISIBLE"`, `hasSpecimen = false`.
  - `gantiMetodeButuhPassphrase`: tanpa passphrase 400; passphrase salah 401; benar ke `PASSPHRASE` 200 dan `dek.app` hilang; kembali ke `TOTP` 200 dan `dek.app` ada.
  - `folderKeluaranRelatifDitolak`: `outputDir = "relatif/folder"` → 400 dan `GET /api/me` tetap `outputDir` lama.
  - `folderKeluaranTidakBisaDitulis`: `outputDir` menunjuk ke sebuah berkas biasa → 400.
  - `spesimenPngDiterima`: unggah PNG 300 x 120 → 200; `GET /api/me/specimen` 200 `image/png`; `hasSpecimen` true.
  - `spesimenBukanGambarDitolak`: teks biasa → 400; PNG 2100 x 100 → 400.
  - `riwayatKosong`: `GET /api/me/history` → `[]`.
- [ ] **Step 2:** Jalankan `-Dtest=AccountApiTest`. Harapan: order baru gagal (404).
- [ ] **Step 3:** Implementasikan `MeController` dan `HistoryStore`.
- [ ] **Step 4:** Jalankan `AccountApiTest` lalu seluruh `app-server`. Harapan: semua lolos.
- [ ] **Step 5:** Commit `"Aplikasi pengguna: profil, pengaturan, spesimen, riwayat"`.

### Task 7: Tanda tangan dari aplikasi dan folder keluaran

**Files:**
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/OutputFolder.java`
- Modify: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/MeController.java`
- Modify: `backend/app-server/src/test/java/id/pkl/signingpqc/server/account/AccountApiTest.java`

**Interfaces:**
- Consumes: `SigningService.signHybrid(pdf, pair, record, visibleOrNull)` dan `placement(...)` (Task 3); `AccountService.unlock` (Task 5); `HistoryStore` (Task 6).
- Produces:
  - `final class OutputFolder`: `static Path save(Path dir, String originalName, String suffix, byte[] data)`: nama dasar tanpa `.pdf`, karakter selain huruf, angka, spasi, `.`, `-`, `_` diganti `_`; coba `<dasar><suffix>.pdf`, lalu `<dasar><suffix>-2.pdf`, dan seterusnya dengan `StandardOpenOption.CREATE_NEW` agar tidak menimpa.
  - `POST /api/me/sign` multipart `file`, `page` (mulai 1), `x`, `y`, `width`, `height` (point, y dari atas, opsional), `passphrase` atau `code`. Urutan: cek PDF (`RequestInputs.pdf`), cek sertifikat akun tidak dicabut (409 `"Sertifikat Anda sudah dicabut"`), `unlock`, tanda tangani (Visible memakai spesimen bila ada), simpan dengan `OutputFolder.save(outputDir, nama, "-ttd", pdf)`, catat riwayat `"Ditandatangani"`, kembalikan PDF dengan header `X-Saved-Path` (path absolut, di-URL-encode UTF-8).

- [ ] **Step 1: Tulis uji gagal** (order 20 sampai 26):
  - `tandaTanganVisibleTersimpan`: dengan kode TOTP benar, `SamplePdf`, posisi default → 200, `X-Saved-Path` berakhiran `surat-ttd.pdf`, berkas ada di folder keluaran dan bytenya sama dengan respons; `POST /api/verify` atas hasilnya memberi `validation.status = "SAH"` dan lapisan teratas `pades = "LTA"`.
  - `tandaTanganKeduaTidakMenimpa`: berkas sama lagi → `surat-ttd-2.pdf`, `surat-ttd.pdf` tidak berubah.
  - `invisibleTanpaWidgetTampak`: `appearance = INVISIBLE`; tanda tangan RSA di hasil punya rectangle widget 0 x 0.
  - `kodeSalahTanpaBerkas`: kode salah → 401 `"Konfirmasi tidak cocok"`, jumlah berkas di folder keluaran tidak bertambah.
  - `kodeDipakaiUlangDitolak`: kode yang sama dengan tanda tangan sebelumnya → 401.
  - `bukanPdfDitolak`: berkas teks → 400, folder keluaran tidak bertambah.
  - `riwayatTercatat`: `GET /api/me/history` memuat entri `"Ditandatangani"` dengan `fileName = "surat-ttd.pdf"`.
  - Mode passphrase (`passphraseMembukaKunci`, memakai akun kedua dari Task 5 setelah kuncinya lewat, atau akun ketiga yang didaftarkan di sini, agar akun utama tidak terkunci untuk Task 8): ganti ke PASSPHRASE, tanda tangan dengan passphrase benar → 200; dengan passphrase salah lima kali → percobaan berikutnya 423 walau benar.
- [ ] **Step 2:** Jalankan `-Dtest=AccountApiTest`. Harapan: order baru gagal.
- [ ] **Step 3:** Implementasikan. Kode TOTP diterima sebagai string; dua kode berbeda pada langkah yang sama tidak mungkin, jadi uji pemakaian ulang memakai kode yang sama.
- [ ] **Step 4:** Jalankan `AccountApiTest` dan seluruh `app-server`. Harapan: semua lolos.
- [ ] **Step 5:** Commit `"Aplikasi pengguna: tanda tangan dengan konfirmasi dan penyimpanan otomatis"`.

### Task 8: Detail verifikasi, `/api/me/verify`, dan perpanjangan

**Files:**
- Modify: `backend/signer-core/src/main/java/id/pkl/signingpqc/core/TimestampReport.java` (tambah `serialHex`)
- Modify: `backend/signer-core/src/main/java/id/pkl/signingpqc/core/SignatureTimestamps.java`
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/VerifyService.java`
- Modify: `backend/app-server/src/main/java/id/pkl/signingpqc/server/DocumentController.java`
- Modify: `backend/app-server/src/main/java/id/pkl/signingpqc/server/account/MeController.java`
- Modify: `backend/app-server/src/test/java/id/pkl/signingpqc/server/account/AccountApiTest.java`
- Create: `backend/app-server/src/test/java/id/pkl/signingpqc/server/account/RenewApiTest.java`

**Interfaces:**
- Consumes: `DocumentValidator`, `PkiService.localValidationData`, `CmsCertificates`, `LtvService.renew`, `TsaService.client("mldsa", null)`.
- Produces:
  - `TimestampReport` mendapat komponen `String serialHex` setelah `tsaSubject`; semua `new TimestampReport(...)` diperbarui (`"-"` bila tidak ada).
  - `record CertificateDetail(String subject, String issuer, String serialHex, Instant notBefore, Instant notAfter, String keyAlgorithm, String keyUsage)`; `record ChainEntry(String subject, boolean trustedRoot)`; `record SignatureDetail(CertificateDetail certificate, List<ChainEntry> chain, String revocation, String coverage)`.
  - `SignatureView` mendapat komponen terakhir `SignatureDetail detail`.
  - `@Service VerifyService`: `VerifyResponse verify(byte[] pdf, String fileName, Instant at)` berisi logika yang kini ada di `DocumentController.verify`, ditambah detail. Rantai dibangun dari sertifikat CMS ditambah sertifikat CA `pki.cas()`, mencocokkan issuer ke subjek sampai self-signed; `trustedRoot` true bila ada di `pki.trustAnchors()`. `revocation`: `"Dicabut"` bila kode lapisan tanda tangan itu `SERTIFIKAT_DICABUT`, `"Dicabut setelah ditandatangani"` bila peringatannya `DICABUT_SETELAH_DITANDATANGANI` dan subjeknya cocok, `"Tidak diketahui"` bila `STATUS_PENCABUTAN_TIDAK_DIKETAHUI`, selain itu `"Tidak dicabut"`. `coverage`: `"Seluruh dokumen saat ditandatangani"` bila `coversWholeDocument`, selain itu `"Revisi <order> dari <jumlah revisi>"`.
  - Ambang peringatan dari `@Value("${signingpqc.renewal-warning-days:30}")`; `DocumentController.RENEWAL_WARNING` dihapus.
  - `POST /api/verify` tetap terbuka dengan `at`, memanggil `VerifyService`, tidak mencatat riwayat.
  - `POST /api/me/verify` multipart `file` tanpa `at`: memanggil `VerifyService`, mencatat riwayat `"Diverifikasi"` dengan `status` = `validation.status` (atau `"TANPA_TANDA_TANGAN"`).
  - `POST /api/me/renew` multipart `file`: verifikasi dulu; bila `validation == null` atau `!renewalRecommended` → 409 `"Dokumen ini belum perlu atau sudah tidak bisa diperpanjang"`; selain itu `LtvService.renew(pdf, pki.revocationSource(now), tsa.client("mldsa", null), TsaService.profile("mldsa"))`, simpan dengan akhiran `"-diperpanjang"`, riwayat `"Diperpanjang"`, header `X-Saved-Path`.

- [ ] **Step 1: Tulis uji gagal**:
  - Di `AccountApiTest` order 30 `verifikasiDenganDetail`: `POST /api/me/verify` atas hasil Task 7 → `signatures[0].detail.certificate.subject` memuat `CN=budi.uji`, `chain` panjang 3 dengan elemen terakhir `trustedRoot = true`, `revocation = "Tidak dicabut"`, `coverage` tidak kosong, `signatureTimestamp.serialHex` bukan `"-"`; riwayat bertambah `"Diverifikasi"`.
  - Order 31 `verifyLamaTetapTerbuka`: `POST /api/verify` tanpa token → 200 dan riwayat tidak bertambah.
  - Order 32 `perpanjangDitolakBilaBelumPerlu`: `POST /api/me/renew` → 409.
  - `RenewApiTest` (kelas sendiri, `signingpqc.renewal-warning-days=1000` sehingga dokumen baru (TSA RSA G1 berlaku 730 hari) langsung disarankan diperpanjang): daftar, tanda tangan, `POST /api/me/renew` → 200, berkas `-diperpanjang.pdf` ada, verifikasi hasilnya `SAH` dengan lapisan teratas memakai TSA ML-DSA (`layers` terakhir `algorithm` diawali `ML-DSA`).
- [ ] **Step 2:** Jalankan `-Dtest='AccountApiTest,RenewApiTest'`. Harapan: gagal.
- [ ] **Step 3:** Implementasikan. Perbarui juga `RestApiTest` bila ada akses `signatureTimestamp` yang berubah bentuk.
- [ ] **Step 4:** Jalankan seluruh backend: `mvn -o -q -Denforcer.skip=true -Dmaven.compiler.release=21 test` di `backend`. Harapan: semua lolos.
- [ ] **Step 5:** Commit `"Aplikasi pengguna: detail verifikasi, verifikasi pengguna, perpanjangan"`.

### Task 9: Catatan T-013 dan uji JDK 25 di laptop

**Files:**
- Create: `docs/temuan/T-013-akun-dan-pembungkusan-kunci.md`
- Modify: `docs/temuan/README.md` (daftar catatan)
- Modify: `.gitignore` (tambah `accounts/`, `app.key`, `output-signing/` bila folder data pernah di dalam repo)

- [ ] **Step 1:** Sinkronkan ke laptop dan jalankan `.\mvnw.cmd -B test` dengan JDK 25 lewat Remote Control. Harapan: semua uji lolos; catat jumlahnya.
- [ ] **Step 2:** Tulis T-013 mengikuti templat `docs/temuan/README.md`: lingkungan, commit, rancangan pembungkusan (DEK, `dek.pp`, `dek.app` hanya mode TOTP), parameter Argon2id dan waktu satu hash yang terukur, hasil uji per kelas, keterbatasan (mode TOTP tidak melindungi dari pencurian folder data beserta `app.key`; endpoint pengembang terbuka di 127.0.0.1; sesi hilang saat backend dimulai ulang; tanpa penerbitan ulang sertifikat).
- [ ] **Step 3:** Commit `"T-013: akun dan pembungkusan kunci"`.

### Task 10: Kerangka frontend, klien API, store sesi, router, topbar

**Files:**
- Modify: `frontend/package.json`, `frontend/vite.config.ts`, `frontend/src/main.ts`, `frontend/src/App.vue`, `frontend/src/style.css`
- Create: `frontend/src/api/client.ts`, `frontend/src/stores/auth.ts`, `frontend/src/router/index.ts`, `frontend/src/components/TopBar.vue`, `frontend/src/components/AppLogo.vue`, `frontend/src/assets/fonts/` (Plus Jakarta Sans woff2, lisensi OFL disertakan)
- Delete: `frontend/src/assets/vite.svg`, `frontend/src/assets/vue.svg`
- Test: `frontend/src/api/client.test.ts`, `frontend/src/router/guard.test.ts`

**Interfaces:**
- Produces:
  - Dependensi: `vue-router`, `pinia`, `pdfjs-dist`, `qrcode`; dev: `vitest`, `jsdom`, `@types/qrcode`. Skrip `"test": "vitest run"`.
  - `class ApiError extends Error { status: number }`; `api.get<T>(path)`, `api.postJson<T>(path, body)`, `api.putJson<T>(path, body)`, `api.postForm(path, form): Promise<Response>`, `api.putForm<T>(path, form)`. Header `Authorization: Bearer <token>` bila ada. Respons tidak OK → `ApiError` dengan pesan dari field `error` (atau `"Terjadi kesalahan (<status>)"`). Status 401 di luar `/api/auth/` memanggil `onUnauthorized` (store menghapus token dan router ke `/masuk`).
  - Store `useAuth`: `token` (dari/ke `sessionStorage` kunci `"tte.token"`), `username`, `login(login, password)`, `logout()`, `setSession(token, username)`, getter `isLoggedIn`.
  - Rute: `/masuk`, `/daftar`, `/` (Beranda), `/unggah/:mode(tandatangani|verifikasi)`, `/tandatangani`, `/verifikasi`, `/pengaturan`, `/profil`. `meta.public` untuk masuk dan daftar. Guard: rute non-publik tanpa sesi → `/masuk`; rute publik dengan sesi → `/`.
  - `TopBar.vue`: tiga ikon di tengah (Beranda, Pengaturan, Profil) dengan `aria-label`, ikon aktif diberi latar tint. Tidak tampil di rute publik.
  - Token warna dan jarak di `style.css` sebagai CSS variable (`--ink`, `--pri`, `--tint`, `--ok`, dll.) disalin dari mockup.

- [ ] **Step 1:** `npm install vue-router pinia pdfjs-dist qrcode` dan `npm install -D vitest jsdom @types/qrcode`. Harapan: `package.json` memuat semuanya.
- [ ] **Step 2: Tulis uji gagal**: `client.test.ts` (fetch tiruan: 400 dengan `{"error":"Berkas bukan PDF"}` → `ApiError` pesan itu; token dikirim sebagai Bearer; 401 di `/api/me` memanggil `onUnauthorized`, di `/api/auth/login` tidak). `guard.test.ts`: tanpa token `/profil` → `/masuk`; dengan token `/masuk` → `/`.
- [ ] **Step 3:** `npm test`. Harapan: gagal (modul belum ada).
- [ ] **Step 4:** Implementasikan.
- [ ] **Step 5:** `npm test` dan `npm run build`. Harapan: lolos.
- [ ] **Step 6:** Commit `"Frontend: kerangka, klien API, sesi, router, topbar"`.

### Task 11: Layar Masuk dan Daftar

**Files:**
- Create: `frontend/src/views/MasukView.vue`, `frontend/src/views/DaftarView.vue`, `frontend/src/components/OtpInput.vue`
- Test: `frontend/src/components/OtpInput.test.ts`

**Interfaces:**
- Consumes: `api`, `useAuth` (Task 10).
- Produces:
  - `OtpInput.vue`: `v-model` string 6 digit; enam kotak; tempel 6 digit mengisi semua; hanya angka; emit `complete` saat 6 digit terisi.
  - Masuk: logo, "Username atau email", "Password", tombol "Masuk", tautan "Daftar". Galat tampil di bawah form.
  - Daftar langkah 1: Username, Email, Password (tanpa nama lengkap dan jabatan). Langkah 2: Passphrase dan Ulangi passphrase, petunjuk "Minimal 12 karakter. Passphrase tidak bisa dipulihkan.". Tombol Lanjut di langkah 2 memanggil `register/start`. Langkah 3: QR dari `otpauthUri` (`QRCode.toDataURL`), tiga langkah petunjuk, `OtpInput`, tombol "Buat akun" memanggil `register/finish` lalu `setSession` dan ke `/`. Indikator tiga titik di atas.
  - Validasi sisi klien sama dengan Global Constraints (pesan Bahasa Indonesia); passphrase dan ulangan harus sama.

- [ ] **Step 1: Tulis uji gagal** `OtpInput.test.ts` (`@vue/test-utils` atau mount manual dengan jsdom): mengetik "12a456" menghasilkan model `"12456"` belum lengkap; tempel `"123456"` emit `complete`.
- [ ] **Step 2:** `npm test`. Harapan: gagal.
- [ ] **Step 3:** Implementasikan ketiga berkas sesuai mockup versi 7.
- [ ] **Step 4:** `npm test` dan `npm run build`. Harapan: lolos.
- [ ] **Step 5:** Commit `"Frontend: layar masuk dan daftar dengan QR authenticator"`.

### Task 12: Beranda, Unggah, penampil PDF, dan konversi koordinat spesimen

**Files:**
- Create: `frontend/src/views/BerandaView.vue`, `frontend/src/views/UnggahView.vue`, `frontend/src/components/DropZone.vue`, `frontend/src/components/PdfViewer.vue`, `frontend/src/components/SpecimenBox.vue`, `frontend/src/stores/document.ts`, `frontend/src/lib/coords.ts`
- Test: `frontend/src/lib/coords.test.ts`

**Interfaces:**
- Produces:
  - Store `useDocument`: `file: File | null`, `bytes: ArrayBuffer | null`, `set(file)`, `clear()`.
  - `DropZone.vue`: drag and drop atau klik; hanya `application/pdf` atau nama `.pdf`; emit `file`.
  - Beranda: sapaan sesuai jam lokal ("Selamat pagi/siang/sore/malam"), dua kartu ke `/unggah/tandatangani` dan `/unggah/verifikasi`, tombol "Lihat riwayat dokumen" yang membuka atau menutup daftar dari `GET /api/me/history`.
  - Unggah: tautan "‹ Beranda", judul sesuai mode, `DropZone`; setelah berkas dipilih → `useDocument.set` lalu ke `/tandatangani` atau `/verifikasi`.
  - `PdfViewer.vue`: props `data: ArrayBuffer`; render satu halaman ke canvas pas lebar dengan pdfjs-dist (worker dari `pdfjs-dist/build/pdf.worker.min.mjs?url`); navigasi halaman; slot default di atas canvas; emit `page` `{ pageNumber, viewport: { width, height, scale, rotation }, pageSizePt: { width, height } }`.
  - `SpecimenBox.vue`: kotak yang bisa diseret dan diubah ukuran (pegangan kanan bawah), dibatasi di dalam halaman, ukuran minimal setara 120 x 60 point pada skala saat ini; menampilkan gambar spesimen atau teks "Belum ada spesimen".
  - `lib/coords.ts`: `screenToPdf(box: {left, top, width, height}, view: {scale, rotation: 0|90|180|270, pageWidthPt, pageHeightPt}): {x, yFromTop, width, height}` dalam point pada sistem koordinat halaman tanpa rotasi (sesuai `VisibleSignature`), dan `defaultBox(view)` = 180 x 75 point di kanan bawah dengan margin 40 point.

- [ ] **Step 1: Tulis uji gagal** `coords.test.ts`:
  - Rotasi 0, skala 1.5, A4 (595 x 842): kotak layar `{left: 150, top: 300, width: 270, height: 112.5}` → `{x: 100, yFromTop: 200, width: 180, height: 75}`.
  - Rotasi 90, skala 1, A4: kotak layar `{left: 0, top: 0, width: 75, height: 180}` (pojok kiri atas tampilan) → `{x: 0, yFromTop: 767, width: 180, height: 75}`. Pada rotasi 90 searah jarum jam, titik tampilan `(dx, dy)` setara titik halaman asli `u = dy`, `v = tinggi - dx` (v diukur dari atas), sehingga pojok kiri atas tampilan adalah pojok kiri bawah halaman asli dan lebar serta tinggi tertukar.
  - Rotasi 0, halaman Letter 612 x 792, skala 1: `defaultBox` → `{x: 392, yFromTop: 677, width: 180, height: 75}`.
- [ ] **Step 2:** `npm test`. Harapan: gagal.
- [ ] **Step 3:** Implementasikan `coords.ts` lalu komponen dan view.
- [ ] **Step 4:** `npm test` dan `npm run build`. Harapan: lolos.
- [ ] **Step 5:** Commit `"Frontend: beranda, unggah, penampil PDF, spesimen yang bisa diseret"`.

### Task 13: Layar Tandatangani dan dialog konfirmasi

**Files:**
- Create: `frontend/src/views/TandatanganiView.vue`, `frontend/src/components/ConfirmDialog.vue`, `frontend/src/stores/settings.ts`

**Interfaces:**
- Consumes: `PdfViewer`, `SpecimenBox`, `screenToPdf`, `defaultBox`, `useDocument`, `OtpInput`.
- Produces:
  - Store `useSettings`: `me: MeResponse | null`, `load()`, `save(patch)`, `uploadSpecimen(file)`, `specimenUrl` (object URL dari `GET /api/me/specimen`).
  - `ConfirmDialog.vue`: props `method: 'PASSPHRASE' | 'TOTP'`; isian password "Passphrase" atau `OtpInput`; emit `confirm(value)` dan `cancel`; menampilkan `error`.
  - Tandatangani: tanpa berkas di store → kembali ke `/unggah/tandatangani`. Penampil PDF, `SpecimenBox` hanya bila `appearance === 'VISIBLE'` (posisi awal `defaultBox`), panel kanan berisi nama berkas, satu kalimat petunjuk, tombol "Tandatangani". Konfirmasi mengirim `POST /api/me/sign` (`page`, `x`, `y`, `width`, `height` dari `screenToPdf`, plus `passphrase` atau `code`). Berhasil: tombol hilang, tampil "Dokumen ditandatangani dan tersimpan di <X-Saved-Path ter-decode>". Gagal: pesan di dialog, dialog tetap terbuka.

- [ ] **Step 1:** Implementasikan.
- [ ] **Step 2:** `npm run build`. Harapan: lolos.
- [ ] **Step 3:** Uji manual dengan backend nyata (container: `mvn -o -Denforcer.skip=true -Dmaven.compiler.release=21 -pl app-server spring-boot:run` dengan `signingpqc.data-dir` sementara; frontend `npm run dev`; Playwright headless). Alur: daftar, tanda tangan Visible dengan spesimen, berkas muncul di folder keluaran, kode salah menampilkan "Konfirmasi tidak cocok". Simpan tangkapan layar di `docs/temuan/img/T-014-*.png`.
- [ ] **Step 4:** Commit `"Frontend: layar tandatangani dengan konfirmasi passphrase/TOTP"`.

### Task 14: Layar Verifikasi, Lihat detail, dan Perpanjang

**Files:**
- Create: `frontend/src/views/VerifikasiView.vue`, `frontend/src/components/VerifyResult.vue`, `frontend/src/components/SignatureDetail.vue`, `frontend/src/lib/status.ts`
- Test: `frontend/src/lib/status.test.ts`

**Interfaces:**
- Produces:
  - `lib/status.ts`: `describe(res: VerifyResponse): { tone: 'ok' | 'warn' | 'bad' | 'none', title: string, subtitle: string, points: string[], until: string | null, canRenew: boolean, renewMessage: string | null }` dengan pemetaan bagian 5 spesifikasi: `SAH` tanpa peringatan → `ok`, "Dokumen sah", "Tanda tangan asli, isi tidak berubah"; `SAH` dengan peringatan → `ok` dan catatan dari pesan `ReasonCode`; `TIDAK_DAPAT_DIBUKTIKAN` → `warn`, "Keabsahan tidak dapat dibuktikan"; `TIDAK_SAH` → `bad`, "Dokumen tidak sah"; tanpa tanda tangan → `none`, "Dokumen ini tidak memiliki tanda tangan elektronik". `canRenew = validation.renewalRecommended`. Bila ada lapisan dengan kode `TSA_KEDALUWARSA_TANPA_PEMBARUAN`, `renewMessage = "Masa keabsahan sudah berakhir dan tidak bisa diperpanjang lagi."`. `until` diformat `id-ID` dari `validation.protectionUntil`.
  - Empat butir `points` (bila ada tanda tangan): penanda tangan, waktu tanda tangan (WIB), "Isi tidak berubah sejak ditandatangani" atau alasannya, "Tanda tangan tahan komputer kuantum (ML-DSA)" bila `hybridComplete`.
  - `SignatureDetail.vue`: dialog, satu tab per tanda tangan ("Tanda tangan 1 · RSA", "Tanda tangan 2 · ML-DSA"), sub-tab Ringkasan, Sertifikat, Rantai sertifikat, Timestamp dari `signatures[i].detail` dan `signatureTimestamp`.
  - Verifikasi memakai `POST /api/me/verify`; tombol "Perpanjang keabsahan" hanya bila `canRenew`, memanggil `POST /api/me/renew` lalu menampilkan path tersimpan dan memverifikasi ulang hasilnya.

- [ ] **Step 1: Tulis uji gagal** `status.test.ts` dengan lima respons contoh (sah, sah dengan `DICABUT_SETELAH_DITANDATANGANI`, tidak dapat dibuktikan dengan `TSA_KEDALUWARSA_TANPA_PEMBARUAN`, tidak sah, tanpa tanda tangan) dan nilai `tone`, `title`, `canRenew`, `renewMessage` di atas.
- [ ] **Step 2:** `npm test`. Harapan: gagal.
- [ ] **Step 3:** Implementasikan.
- [ ] **Step 4:** `npm test`, `npm run build`, lalu uji manual: verifikasi hasil Task 13 (sah, detail ML-DSA tampil), PDF yang diubah setelah ditandatangani (tidak sah), PDF tanpa tanda tangan. Tangkapan layar ke `docs/temuan/img/`.
- [ ] **Step 5:** Commit `"Frontend: layar verifikasi dengan detail sertifikat RSA dan ML-DSA"`.

### Task 15: Pengaturan, Profil, catatan T-014

**Files:**
- Create: `frontend/src/views/PengaturanView.vue`, `frontend/src/views/ProfilView.vue`
- Create: `docs/temuan/T-014-antarmuka-pengguna.md`
- Modify: `docs/temuan/README.md`, `frontend/README.md`

**Interfaces:**
- Consumes: `useSettings`, `useAuth`, `ConfirmDialog` (mode passphrase).
- Produces:
  - Pengaturan, tiga kotak tanpa kalimat penjelas:
    - "Tampilan tanda tangan": pilihan Visible dan Invisible; bila Visible, pratinjau spesimen dan tombol "Unggah spesimen".
    - "Konfirmasi tanda tangan": pilihan Passphrase, TOTP, dan Passkey nonaktif dengan label "Segera". Mengganti pilihan membuka `ConfirmDialog` mode passphrase dan baru tersimpan setelah backend menerima; bila batal, pilihan kembali.
    - "Lokasi penyimpanan": path saat ini dan tombol "Ubah" yang membuka isian path (versi web) lalu `PUT /api/me/settings`.
  - Profil: inisial dari username, username, email, kotak Username/Email/Terdaftar, judul "Sertifikat elektronik" dengan dua kartu (Status Aktif/Dicabut, Algoritma, Penerbit, Nomor seri, Berlaku), tombol "Keluar" (`POST /api/auth/logout`, hapus sesi, ke `/masuk`).

- [ ] **Step 1:** Implementasikan kedua view.
- [ ] **Step 2:** `npm test` dan `npm run build`. Harapan: lolos.
- [ ] **Step 3:** Uji manual seluruh alur sesuai mockup versi 7: daftar, masuk, Beranda, riwayat, tanda tangan Visible dan Invisible, verifikasi dan detail, ganti metode ke Passphrase lalu tanda tangan, ganti folder keluaran, Profil, Keluar. Tangkapan layar setiap layar (lebar 1280).
- [ ] **Step 4:** Tulis T-014 (lingkungan, commit, langkah, tangkapan layar, hasil Vitest, temuan dan tindak lanjut) dan perbarui `frontend/README.md` (cara menjalankan backend dan `npm run dev`).
- [ ] **Step 5:** Commit `"Frontend: pengaturan, profil, catatan T-014"`.
