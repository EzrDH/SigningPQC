# Lapisan LT, LTA, Pembaruan TSA, dan TSA-PQC: Rencana Implementasi

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PDF hybrid bisa ditambah lapisan 3 (DSS), lapisan 4 (document timestamp), dan pembaruan berulang (lapisan 5, 6, ...) termasuk TSA ML-DSA. Verifikator menelusuri rantai lapisan pada waktu nyata atau waktu simulasi.

**Architecture:** Semua logika PDF dan validasi ada di `signer-core` (paket baru `id.pkl.signingpqc.core.ltv` dan `id.pkl.signingpqc.core.validation`). `pki-test` menyediakan PKI berumur panjang, TSA bergenerasi, dan sumber CRL uji. `app-server` hanya meneruskan ke REST. Setiap lapisan adalah pembaruan inkremental PDFBox.

**Tech Stack:** Java 25, Bouncy Castle 1.87, PDFBox 3.0.8, Spring Boot 4.2, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-11-25-lapisan-ltv-pembaruan-tsa-design.md`

## Global Constraints
- JDK 25 (enforcer). Build dan tes **hanya di laptop**: `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.5.10002-hotspot'`, lalu `.\mvnw.cmd -B ...` di `backend\`. Container tidak punya JDK 25.
- Commit dan push memakai git Windows di laptop (git di VM tidak bisa menghapus berkas lock).
- Pesan aplikasi, nama uji, dan dokumen memakai Bahasa Indonesia.
- Lingkungan uji saja, tanpa data asli BSrE. Jangan meng-commit `*.p12` atau `*.key`.
- Struktur tetap PAdES standar: SubFilter `ETSI.CAdES.detached` (tanda tangan) dan `ETSI.RFC3161` (document timestamp), Filter `Adobe.PPKLite`.
- Lapisan 4 dan 5 memakai TSA RSA (generasi 1 dan 2), lapisan 6 memakai TSA ML-DSA.
- Batas aman bawaan: semua tanda tangan RSA tidak aman mulai `2035-01-01T00:00:00Z` (NIST IR 8547 draf). ML-DSA tanpa batas. Ambang peringatan bawaan 30 hari.
- Setiap hasil uji dicatat di `docs/temuan/T-XXX` (aturan AGENTS.md).

## Review Focus
1. PDF yang sudah punya `/DSS` dari alat lain: DSS digabung, entri lama tidak hilang (uji di Task 2).
2. Document timestamp pada PDF B-B tanpa DSS: tidak boleh gagal saat membuat. Validator melaporkan `DATA_VALIDASI_TIDAK_ADA`, bukan exception (uji di Task 6).
3. Waktu pemeriksaan lebih awal dari waktu tanda tangan: status `TIDAK_DAPAT_DIBUKTIKAN` dengan kode `WAKTU_PEMERIKSAAN_TIDAK_SAH` (uji di Task 6).
4. Ada revisi tambahan setelah lapisan terakhir, misalnya anotasi: laporan memberi peringatan `PERUBAHAN_SETELAH_LAPISAN_TERAKHIR` dan status tidak `SAH` penuh (uji di Task 6).
5. Berkas bukan PDF atau PDF terenkripsi ke endpoint LTV: HTTP 400, bukan 500 (uji di Task 7).

---

### Task 1: PKI uji berumur panjang dan TSA bergenerasi

**Files:**
- Modify: `backend/pki-test/src/main/java/id/pkl/signingpqc/pki/TestPkiFactory.java`
- Create: `backend/pki-test/src/main/java/id/pkl/signingpqc/pki/LongTermPki.java`
- Test: `backend/pki-test/src/test/java/id/pkl/signingpqc/pki/LongTermPkiTest.java`

**Interfaces:**
- Produces:
  - `TestPkiFactory(String crlBaseUrl, Clock clock)`. Semua `Instant.now()` diganti `clock.instant()`; konstruktor lama memakai `Clock.systemUTC()`.
  - `CaHierarchy createHierarchy(PkiFamily family, String organization, Duration rootValidity, Duration intermediateValidity)`.
  - `X509CRL createCrl(CertificateAuthority issuer, Collection<RevokedCertificate> revoked, long crlNumber, Instant thisUpdate)`.
  - `record LongTermPki(CaHierarchy rsa, CaHierarchy mlDsa, SignerPair signer, SigningCredential tsaRsaGen1, SigningCredential tsaRsaGen2, SigningCredential tsaMlDsa)` dengan `static LongTermPki create(TestPkiFactory factory, SignerIdentity identity)`.
  - Masa berlaku: root 30 tahun, intermediate 25 tahun, TSA RSA gen1 2 tahun, gen2 10 tahun, TSA ML-DSA 20 tahun, penanda tangan 2 tahun.

- [ ] **Step 1: Tulis uji gagal** `LongTermPkiTest.masaBerlakuSesuaiProfil`: `tsaRsaGen1.notAfter` sekitar sekarang + 730 hari (toleransi 1 hari), gen2 + 3650 hari, ML-DSA + 7300 hari, intermediate ML-DSA + 9125 hari. `pki.tsaRsaGen1().certificate().getSubjectX500Principal()` memuat `CN=TSA Uji RSA G1`. Uji kedua `crlDenganThisUpdateTertentu`: `createCrl(..., Instant.parse("2030-01-01T00:00:00Z"))` memberi `getThisUpdate()` yang sama.
- [ ] **Step 2:** `.\mvnw.cmd -B -pl pki-test -am test -Dtest=LongTermPkiTest -Dsurefire.failIfNoSpecifiedTests=false`, harapannya gagal kompilasi.
- [ ] **Step 3:** Implementasikan antarmuka di atas.
- [ ] **Step 4:** Jalankan uji yang sama sampai lolos, lalu seluruh `pki-test` (27 uji lama tetap lolos).
- [ ] **Step 5:** Commit `"Sprint 2: PKI uji jangka panjang dan TSA bergenerasi"`.

### Task 2: Kamus DSS (lapisan 3, penulisan dan pembacaan)

**Files:**
- Create: `backend/signer-core/src/main/java/id/pkl/signingpqc/core/ltv/ValidationData.java`
- Create: `backend/signer-core/src/main/java/id/pkl/signingpqc/core/ltv/DssWriter.java`
- Create: `backend/signer-core/src/main/java/id/pkl/signingpqc/core/ltv/DssReader.java`
- Test: `backend/pki-test/src/test/java/id/pkl/signingpqc/pki/DssTest.java` (di pki-test karena butuh PKI uji)

**Interfaces:**
- Produces:
  - `record ValidationData(List<X509Certificate> certificates, List<X509CRL> crls)` dengan `merge(ValidationData)` yang membuang duplikat (dibandingkan lewat DER).
  - `final class DssWriter { static byte[] append(byte[] pdf, Map<String, ValidationData> byVriKey) throws IOException }`. Menambahkan atau menggabungkan `/DSS` (`/Certs`, `/CRLs`, `/VRI`) lewat `saveIncremental`. Entri yang sama tidak ditulis dua kali.
  - `static String vriKey(byte[] contents)`: SHA-1 heksadesimal huruf besar dari isi `/Contents` (DER CMS tanpa padding nol, pakai `PdfSignatureVerifier.firstDerObject`).
  - `final class DssReader { static ValidationData readAll(byte[] pdf); static Map<String, ValidationData> readVri(byte[] pdf) }`. PDF tanpa DSS menghasilkan data kosong.

- [ ] **Step 1: Tulis uji gagal** `DssTest`:
  - `dssDitulisDanTandaTanganTetapSah`: PDF hybrid B-T, lalu `append` dengan rantai RSA dan ML-DSA serta 4 CRL. `readAll` berisi semua sertifikat dan CRL (bandingkan DER). Kedua `SignatureReport.valid()` tetap true. Kunci VRI sama dengan `vriKey` kedua tanda tangan.
  - `dssLamaDigabung` (Review Focus 1): `append` dua kali dengan data berbeda. `readAll` berisi gabungan, tanpa duplikat.
  - `pdfTanpaDssMemberiDataKosong`.
- [ ] **Step 2:** Jalankan, harapannya gagal kompilasi.
- [ ] **Step 3:** Implementasikan. Stream sertifikat dan CRL berisi DER mentah. `/VRI/<kunci>` berisi `/Cert` dan `/CRL` yang menunjuk ke stream yang sama dengan `/DSS`. Tandai objek yang diubah dengan `setNeedToBeUpdated(true)` agar `saveIncremental` menulisnya.
- [ ] **Step 4:** Jalankan sampai lolos.
- [ ] **Step 5:** Commit `"Sprint 2: kamus DSS (PAdES B-LT)"`.

### Task 3: Document timestamp (lapisan 4 dan berikutnya)

**Files:**
- Create: `backend/signer-core/src/main/java/id/pkl/signingpqc/core/ltv/TimestampProfile.java`
- Create: `backend/signer-core/src/main/java/id/pkl/signingpqc/core/ltv/DocumentTimestamper.java`
- Test: `backend/pki-test/src/test/java/id/pkl/signingpqc/pki/DocumentTimestampTest.java`

**Interfaces:**
- Produces:
  - `enum TimestampProfile { RSA("SHA-256", 16 * 1024), ML_DSA("SHA-512", 48 * 1024) }` dengan `digestName()` dan `reservedBytes()`.
  - `final class DocumentTimestamper { static byte[] add(byte[] pdf, TimestampClient tsa, TimestampProfile profile) throws IOException, GeneralSecurityException }`. Kamus `/Type /DocTimeStamp`, `/Filter /Adobe.PPKLite`, `/SubFilter /ETSI.RFC3161`. Isinya `TimeStampToken.getEncoded()` atas data ByteRange.

- [ ] **Step 1: Tulis uji gagal** `DocumentTimestampTest`:
  - `timestampRsaMenutupSeluruhDokumen`: `getSignatureDictionaries()` berisi 3 entri, yang terakhir punya SubFilter `ETSI.RFC3161` dan ByteRange sampai akhir berkas. Token terbaca dengan `new TimeStampToken(new CMSSignedData(contents))`, imprint SHA-256 cocok, dan dua tanda tangan lama tetap sah.
  - `timestampMlDsaMemakaiSha512`: imprint SHA-512, algoritma tanda tangan token `2.16.840.1.101.3.4.3.18`.
  - `tigaTimestampBerurutan`: RSA gen1, RSA gen2, lalu ML-DSA. Setiap token cocok dengan ByteRange masing-masing.
- [ ] **Step 2:** Jalankan, harapannya gagal.
- [ ] **Step 3:** Implementasikan dengan pola external signing seperti `PadesSigner.sign`. Bila token melebihi `reservedBytes`, lempar `GeneralSecurityException` dengan pesan "Ruang timestamp tidak cukup".
- [ ] **Step 4:** Jalankan sampai lolos.
- [ ] **Step 5:** Commit `"Sprint 2: document timestamp (PAdES B-LTA)"`.

### Task 4: Sumber data validasi dan alur LTV

**Files:**
- Create: `backend/signer-core/src/main/java/id/pkl/signingpqc/core/ltv/RevocationSource.java`
- Create: `backend/signer-core/src/main/java/id/pkl/signingpqc/core/ltv/LtvService.java`
- Create: `backend/pki-test/src/main/java/id/pkl/signingpqc/pki/TestPkiRevocationSource.java`
- Test: `backend/pki-test/src/test/java/id/pkl/signingpqc/pki/LtvServiceTest.java`

**Interfaces:**
- Consumes: `DssWriter`, `DssReader`, `DocumentTimestamper`, `TimestampProfile` (Task 2 dan 3). `TestPkiFactory.createCrl(..., Instant thisUpdate)` (Task 1).
- Produces:
  - `interface RevocationSource { ValidationData collect(X509Certificate leaf, Collection<X509Certificate> pool) throws GeneralSecurityException; }`. Hasilnya rantai sampai root beserta CRL setiap penerbit.
  - `TestPkiRevocationSource(Collection<CertificateAuthority> cas, TestPkiFactory factory, Clock clock)` dengan `revoke(X509Certificate cert, Instant when)`. CRL dibuat dengan `thisUpdate = clock.instant()`.
  - `final class LtvService`:
    - `static byte[] addValidationData(byte[] pdf, RevocationSource source)`: untuk setiap tanda tangan, mencakup sertifikat penanda tangan dan rantai TSA dari signature timestamp-nya, serta setiap document timestamp yang belum punya VRI.
    - `static byte[] addDocumentTimestamp(byte[] pdf, TimestampClient tsa, TimestampProfile profile)`
    - `static byte[] renew(byte[] pdf, RevocationSource source, TimestampClient tsa, TimestampProfile profile)`, yaitu `addValidationData` lalu `addDocumentTimestamp`.

- [ ] **Step 1: Tulis uji gagal** `LtvServiceTest`:
  - `addValidationDataMencakupPenandaTanganDanTsa`: VRI berisi dua kunci (dua tanda tangan). Masing-masing memuat sertifikat penanda tangan, intermediate, root, sertifikat TSA, dan minimal 2 CRL.
  - `renewMenambahVriUntukTimestampSebelumnya`: setelah `addDocumentTimestamp` (gen1) lalu `renew` (gen2), VRI memuat kunci document timestamp gen1, dan jumlah kamus tanda tangan menjadi 4.
- [ ] **Step 2:** Jalankan, harapannya gagal.
- [ ] **Step 3:** Implementasikan.
- [ ] **Step 4:** Jalankan sampai lolos.
- [ ] **Step 5:** Commit `"Sprint 2: alur LTV dan pembaruan timestamp"`.

### Task 5: Validasi rantai sertifikat pada waktu tertentu

**Files:**
- Create: `backend/signer-core/src/main/java/id/pkl/signingpqc/core/validation/ReasonCode.java`
- Create: `backend/signer-core/src/main/java/id/pkl/signingpqc/core/validation/AlgorithmPolicy.java`
- Create: `backend/signer-core/src/main/java/id/pkl/signingpqc/core/validation/ChainValidator.java`
- Modify: `backend/pki-test/src/main/java/id/pkl/signingpqc/pki/PkixValidation.java` (pakai `ChainValidator` bila cocok, jangan duplikasi)
- Test: `backend/pki-test/src/test/java/id/pkl/signingpqc/pki/ChainValidatorTest.java`

**Interfaces:**
- Produces:
  - `enum ReasonCode { OK, DOKUMEN_BERUBAH, TANDA_TANGAN_TIDAK_COCOK, SERTIFIKAT_DICABUT, DICABUT_SETELAH_DITANDATANGANI, SERTIFIKAT_TIDAK_BERLAKU, ROOT_TIDAK_DIPERCAYA, STATUS_PENCABUTAN_TIDAK_DIKETAHUI, DATA_VALIDASI_TIDAK_ADA, TSA_KEDALUWARSA_TANPA_PEMBARUAN, ALGORITMA_TIDAK_AMAN_PADA_WAKTU_PEMERIKSAAN, WAKTU_PEMERIKSAAN_TIDAK_SAH, PERUBAHAN_SETELAH_LAPISAN_TERAKHIR, TANDA_TANGAN_HYBRID_TIDAK_LENGKAP }`. Setiap nilai punya `message()` berbahasa Indonesia.
  - `record AlgorithmPolicy(Map<String, Instant> unsafeFrom)` dengan `static AlgorithmPolicy defaults()` (kunci `"RSA"` bernilai `2035-01-01T00:00:00Z`) dan `Optional<Instant> unsafeFrom(SignatureAlgorithm algorithm)`.
  - `record ChainResult(ReasonCode code, List<X509Certificate> path, Instant chainNotAfter, String detail)` dengan `boolean valid()`. `DICABUT_SETELAH_DITANDATANGANI` dihitung sah, tetapi diberi peringatan.
  - `final class ChainValidator { ChainValidator(Set<X509Certificate> trustAnchors); ChainResult validate(X509Certificate leaf, ValidationData data, Instant time) }`.
    - Jalur dibangun dari `data.certificates()` sampai trust anchor.
    - PKIX (BC) dijalankan pada `time` dengan revokasi dimatikan.
    - Revokasi diperiksa sendiri untuk setiap sertifikat non-root. CRL diterima bila tanda tangannya valid oleh penerbit dan (`thisUpdate >= time` atau `thisUpdate <= time <= nextUpdate`).
    - Serial ada di CRL dengan `revocationDate <= time` memberi `SERTIFIKAT_DICABUT`. Bila `revocationDate > time`, hasilnya `DICABUT_SETELAH_DITANDATANGANI`. Tidak ada CRL yang memenuhi syarat memberi `STATUS_PENCABUTAN_TIDAK_DIKETAHUI`.
    - `chainNotAfter` adalah nilai minimum `notAfter` dari seluruh jalur.

- [ ] **Step 1: Tulis uji gagal** `ChainValidatorTest`: `rantaiSah`, `dicabutSebelumWaktuPemeriksaan`, `dicabutSesudahnyaTetapSahDenganPeringatan`, `sertifikatKedaluwarsaPadaWaktuItu` (memberi `SERTIFIKAT_TIDAK_BERLAKU`), `tanpaCrl` (memberi `STATUS_PENCABUTAN_TIDAK_DIKETAHUI`), `rootLainDitolak` (memberi `ROOT_TIDAK_DIPERCAYA`), `crlLamaTidakBerlakuDiTerima`. Uji terakhir: CRL dengan `thisUpdate` = waktu − 30 hari dan masa berlaku 7 hari memberi `STATUS_PENCABUTAN_TIDAK_DIKETAHUI`.
- [ ] **Step 2:** Jalankan, harapannya gagal.
- [ ] **Step 3:** Implementasikan.
- [ ] **Step 4:** Jalankan sampai lolos, lalu seluruh `pki-test`.
- [ ] **Step 5:** Commit `"Sprint 2: validasi rantai pada waktu tertentu"`.

### Task 6: Validator dokumen berlapis

**Files:**
- Create: `backend/signer-core/src/main/java/id/pkl/signingpqc/core/validation/DocumentValidator.java`
- Create: `backend/signer-core/src/main/java/id/pkl/signingpqc/core/validation/ValidationReport.java`
- Create: `backend/signer-core/src/main/java/id/pkl/signingpqc/core/validation/LayerReport.java`
- Test: `backend/pki-test/src/test/java/id/pkl/signingpqc/pki/DocumentValidatorTest.java`

**Interfaces:**
- Consumes: `ChainValidator`, `AlgorithmPolicy`, `ReasonCode` (Task 5). `DssReader` (Task 2). Pemeriksaan kriptografi per tanda tangan dari `PdfSignatureVerifier` (yang sudah ada).
- Produces:
  - `enum OverallStatus { SAH, TIDAK_SAH, TIDAK_DAPAT_DIBUKTIKAN }`
  - `enum LayerKind { TANDA_TANGAN, DATA_VALIDASI, DOCUMENT_TIMESTAMP }`
  - `record LayerReport(int revision, LayerKind kind, String pades, String algorithm, String subject, Instant time, Instant provenAt, ReasonCode code, String detail, TimestampReport signatureTimestamp)`.
    - `pades` diisi "B-T", "LT", atau "LTA".
    - `time` adalah waktu lapisan dibuat (genTime, atau waktu signature timestamp).
    - `provenAt` adalah waktu yang dibuktikan lapisan di atasnya.
  - `record ValidationReport(OverallStatus status, boolean hybridComplete, Instant validationTime, List<LayerReport> layers, Instant protectionUntil, String protectionLimitReason, boolean renewalRecommended, List<ReasonCode> warnings)`
  - `final class DocumentValidator`:
    - Konstruktor: `DocumentValidator(Set<X509Certificate> trustAnchors, AlgorithmPolicy policy, Duration warningThreshold)`.
    - Metode: `ValidationReport validate(byte[] pdf, Instant validationTime)`.

Aturan (inti spesifikasi, diterapkan berurutan):
1. Urutkan semua revisi: tanda tangan, DSS, dan document timestamp. Data validasi yang dipakai adalah `DssReader.readAll` dari seluruh dokumen.
2. Lapisan terbaru (timestamp terakhir, atau tanda tangan bila belum ada timestamp) dibuktikan pada `validationTime`. Lapisan i dibuktikan pada `time` lapisan timestamp di atasnya (`provenAt`).
3. Setiap lapisan bertanda tangan sah bila:
   - kriptografinya sah;
   - rantainya sah pada `time`-nya sendiri;
   - `provenAt` lebih kecil dari `chainNotAfter`. Bila tidak, kodenya `TSA_KEDALUWARSA_TANPA_PEMBARUAN`, atau `SERTIFIKAT_TIDAK_BERLAKU` untuk tanda tangan;
   - `provenAt` lebih kecil dari batas aman algoritmanya. Bila tidak, kodenya `ALGORITMA_TIDAK_AMAN_PADA_WAKTU_PEMERIKSAAN`.
4. Status lapisan:
   - Kriptografi gagal, ByteRange salah, atau sertifikat dicabut membuat lapisan `TIDAK_SAH`.
   - Kode waktu atau batas (`TSA_KEDALUWARSA_TANPA_PEMBARUAN`, `ALGORITMA_TIDAK_AMAN...`, `STATUS_PENCABUTAN_TIDAK_DIKETAHUI`, `DATA_VALIDASI_TIDAK_ADA`, `WAKTU_PEMERIKSAAN_TIDAK_SAH`) membuat lapisan `TIDAK_DAPAT_DIBUKTIKAN`.
5. Status dokumen:
   - Bila ada lapisan `TIDAK_SAH`, dokumen `TIDAK_SAH`.
   - Kebijakan AND: tanda tangan RSA dan ML-DSA harus ada dan keduanya sah. Bila tidak, dokumen `TIDAK_SAH` dengan `TANDA_TANGAN_HYBRID_TIDAK_LENGKAP`.
   - Selain itu, bila ada lapisan `TIDAK_DAPAT_DIBUKTIKAN`, dokumen ikut `TIDAK_DAPAT_DIBUKTIKAN`. Bila tidak ada, dokumen `SAH`.
6. Batas perlindungan dihitung dari lapisan terbaru: nilai minimum antara `chainNotAfter` dan batas aman algoritmanya. `renewalRecommended` bernilai true bila `protectionUntil - validationTime < warningThreshold`.
7. Revisi tambahan setelah lapisan terakhir yang bukan DSS memberi peringatan `PERUBAHAN_SETELAH_LAPISAN_TERAKHIR`, dan status tidak boleh `SAH`.

- [ ] **Step 1: Tulis uji gagal** `DocumentValidatorTest`. Semua uji memakai `LongTermPki`, jam TSA dan jam CRL yang digeser, serta trust anchor kedua root:
  - `tc01HybridBtSahSekarang`: `SAH`, `hybridComplete`, 2 lapisan tanda tangan.
  - `tc03SatuByteDiubah`: `TIDAK_SAH`, ada lapisan dengan kode `DOKUMEN_BERUBAH`.
  - `tc04PenandaTanganDicabutSebelumMenandatangani`: `TIDAK_SAH` dengan `SERTIFIKAT_DICABUT`.
  - `tc05LtaSahSekarang`: lapisan 3 dan 4 ada (LT dan LTA), status `SAH`.
  - `tc06RantaiPembaruanSahTahun2040`. Langkahnya:
    - Tandatangani dan tambah LT pada t0.
    - Timestamp gen1 pada t0.
    - Pembaruan gen2 pada (gen1.notAfter − 10 hari).
    - Pembaruan ML-DSA pada `2034-06-01`.
    - Validasi pada `2040-01-01`.
    - Harapan: `SAH`; lapisan RSA punya `provenAt` sebelum 2035; `protectionUntil` sama dengan `chainNotAfter` TSA ML-DSA.
  - `tc07TanpaPembaruanSetelahGen1Kedaluwarsa`: B-LTA hanya gen1, validasi pada gen1.notAfter + 1 hari. Hasilnya `TIDAK_DAPAT_DIBUKTIKAN` dengan `TSA_KEDALUWARSA_TANPA_PEMBARUAN`.
  - `hanyaRsaSetelah2035`: rantai pembaruan gen1 dan gen2 tanpa ML-DSA, validasi pada `2036-01-01`. Hasilnya `TIDAK_DAPAT_DIBUKTIKAN` dengan `ALGORITMA_TIDAK_AMAN_PADA_WAKTU_PEMERIKSAAN`.
  - `peringatanPembaruan30Hari`: validasi pada gen1.notAfter − 20 hari memberi `renewalRecommended` true.
  - `mlDsaRusakMelanggarKebijakanAnd`: byte CMS ML-DSA diubah. Hasilnya `TIDAK_SAH`.
  - `ltaTanpaDss` (Review Focus 2): hasilnya `TIDAK_DAPAT_DIBUKTIKAN` dengan `DATA_VALIDASI_TIDAK_ADA`, tanpa exception.
  - `waktuPemeriksaanSebelumTandaTangan` (Review Focus 3): hasilnya `WAKTU_PEMERIKSAAN_TIDAK_SAH`.
  - `revisiTambahanSetelahLapisanTerakhir` (Review Focus 4): tambahkan anotasi lewat `saveIncremental`. Peringatan `PERUBAHAN_SETELAH_LAPISAN_TERAKHIR` muncul dan status bukan `SAH`.
- [ ] **Step 2:** Jalankan, harapannya gagal.
- [ ] **Step 3:** Implementasikan.
- [ ] **Step 4:** Jalankan sampai lolos, lalu `clean install` penuh.
- [ ] **Step 5:** Commit `"Sprint 2: validator dokumen berlapis (KF-19 s.d. KF-29)"`.

### Task 7: REST, demo, dan uji silang

**Files:**
- Modify: `backend/app-server/src/main/java/id/pkl/signingpqc/server/PkiService.java`. PKI baru memakai profil jangka panjang dan menyimpan tiga TSA: `rsa-g1`, `rsa-g2`, `mldsa`. Sediakan `RevocationSource revocationSource()` dan `Set<X509Certificate> trustAnchors()`.
- Modify: `backend/app-server/src/main/java/id/pkl/signingpqc/server/TsaService.java`. `client(String tsaId, Instant at)` memakai `LocalTsa` dengan jam tetap bila `at` diisi.
- Create: `backend/app-server/src/main/java/id/pkl/signingpqc/server/LtvController.java`
- Modify: `backend/app-server/src/main/java/id/pkl/signingpqc/server/DocumentController.java`. `/api/sign` mendapat `level=B-T|B-LT|B-LTA`. `/api/verify` mendapat `at` (opsional) dan mengembalikan `ValidationReport`.
- Modify: `backend/pki-test/src/main/java/id/pkl/signingpqc/pki/PkiDemoMain.java`. Tambah `05-hybrid-lt.pdf`, `06-hybrid-lta.pdf`, dan `07-hybrid-pembaruan-pqc.pdf` (skenario tc06), lalu cetak laporan validasi pada sekarang dan pada 2040.
- Modify: `scripts/cek-pki.ps1`. Token document timestamp RSA diperiksa dengan `openssl ts -verify -data`, token ML-DSA dengan `openssl cms -verify -binary` ditambah perbandingan imprint.
- Test: `backend/app-server/src/test/java/id/pkl/signingpqc/server/RestApiTest.java`

**Interfaces:**
- Consumes: `LtvService`, `DocumentValidator`, `TimestampProfile`, `RevocationSource`.
- Produces (REST):
  - `POST /api/ltv/validation-data`: multipart `file`, mengembalikan PDF.
  - `POST /api/ltv/timestamp?tsa=rsa-g1|rsa-g2|mldsa&at=<ISO opsional>`: mengembalikan PDF.
  - `POST /api/ltv/renew?tsa=...&at=...`: mengembalikan PDF.
  - `POST /api/verify?at=<ISO opsional>`: mengembalikan JSON `ValidationReport` (enum sebagai string, waktu ISO-8601).

- [ ] **Step 1: Tulis uji gagal** `RestApiTest`:
  - `alurLengkapSampaiPqc`: sign `level=B-LTA`, renew `rsa-g2` dengan `at` = gen1.notAfter − 10 hari, renew `mldsa` dengan `at=2034-06-01T00:00:00Z`, lalu verify dengan `at=2040-01-01T00:00:00Z`. Harapan: `status` = `SAH` dan 6 atau lebih lapisan.
  - `ltvMenolakBukanPdf` (Review Focus 5): hasilnya HTTP 400.
  - `ltvMenolakPdfTerenkripsi`: hasilnya HTTP 400.
- [ ] **Step 2:** Jalankan, harapannya gagal.
- [ ] **Step 3:** Implementasikan. `at` hanya diterima untuk TSA uji lokal, dengan pesan "Waktu simulasi hanya untuk TSA uji".
- [ ] **Step 4:** `clean install` penuh, `-pl pki-test exec:java`, dan `scripts\cek-pki.ps1`. Semua lolos.
- [ ] **Step 5:** Commit `"Sprint 2: REST LTV, demo lapisan, uji silang OpenSSL"`.

### Task 8: Catatan temuan dan uji pembaca PDF

**Files:**
- Create: `docs/temuan/T-012-lapisan-lt-lta-dan-pembaruan-tsa-pqc.md` (plus baris indeks dan gambar di `docs/temuan/img/`)
- Modify: `docs/03-Jadwal-Sprint-SigningPQC.md` (urutan baru: lapisan lalu frontend), `README.md` (bagian LTV dan cara menjalankan demo)

- [ ] **Step 1:** Catat lingkungan, langkah, jumlah uji, ukuran berkas per lapisan, waktu proses per lapisan (rata-rata 20 kali), hasil OpenSSL, dan laporan validasi sekarang dan 2040.
- [ ] **Step 2:** Minta ejer membuka `06` dan `07` di PdfCraft dan Adobe, lalu catat hasilnya (harapan: "LTV enabled" untuk lapisan RSA, timestamp ML-DSA ditandai tidak sah).
- [ ] **Step 3:** Commit dan push `"Sprint 2: catatan T-012 lapisan LTV"`.
