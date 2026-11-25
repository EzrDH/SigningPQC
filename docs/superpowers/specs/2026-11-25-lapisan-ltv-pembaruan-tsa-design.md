# Rancangan: lapisan LT, LTA, pembaruan TSA, dan TSA-PQC

Tanggal: 25 Nov 2026 · Status: draf, menunggu persetujuan ejer

## 1. Maksud
Dokumen elektronik seperti KK atau ijazah harus bisa dibuktikan sah seumur hidup. Sistem TTE BSrE saat ini berhenti
di lapisan 3 (PAdES B-LT: data OCSP/CRL disimpan di dokumen). Pembimbing lapangan mengusulkan agar dokumen terus
dibungkus dengan timestamp berikutnya (PAdES B-LTA), dan lapisan terbarunya memakai TSA pasca-kuantum (TSA-PQC).
Dengan begitu bukti keabsahan tetap kuat walaupun RSA kelak bisa dipecahkan komputer kuantum.

Model lapisan dari papan tulis pembimbing:

| No | Lapisan | Level PAdES | Status |
|---|---|---|---|
| 1 | Tanda tangan (hybrid RSA visible + ML-DSA invisible) | B | Sudah (Sprint 1) |
| 2 | Signature timestamp per tanda tangan | T | Sudah (Sprint 1) |
| 3 | Data validasi: rantai sertifikat dan CRL di kamus DSS | LT ("LTV enabled") | Rancangan ini |
| 4 | Document timestamp pertama (TSA RSA) | LTA | Rancangan ini |
| 5 | Document timestamp pembaruan (TSA RSA generasi 2) | LTA | Rancangan ini |
| 6 | Document timestamp TSA-PQC (TSA ML-DSA) | LTA, pasca-kuantum | Rancangan ini |

Setiap lapisan baru menutup seluruh byte lapisan sebelumnya (kurung di gambar).

## 2. Tujuan, bukan tujuan, anti-tujuan
Tujuan:
- Menambahkan lapisan 3 sampai 6 pada PDF hybrid sebagai pembaruan inkremental (tanda tangan lama tidak rusak).
- Verifikator menelusuri rantai dari lapisan terbaru ke tanda tangan awal (KF-29). Setiap lapisan diperiksa pada
  waktu yang dibuktikan lapisan di atasnya, memakai data di DSS, bukan data dari internet.
- Aplikasi menghitung batas perlindungan dokumen dan memberi peringatan sebelum batas itu (KF-26, KF-27).
- Simulasi "masa depan" untuk demo: TSA generasi 1 kedaluwarsa, RSA dianggap tidak aman lagi, dokumen tetap sah
  karena rantai pembaruan berakhir di TSA-PQC (TC-06, TC-07).

Bukan tujuan (saat ini):
- Responder OCSP. Data revokasi memakai CRL saja. OCSP bisa ditambah di off lokus.
- TSA atau CA sungguhan, dan pembaruan otomatis terjadwal.
- Membuat Adobe menerima ML-DSA (lihat T-011).

Anti-tujuan:
- Pembaruan yang merusak atau mengubah lapisan lama.
- Verifikator yang mengambil CRL dari jaringan saat memeriksa dokumen lama. Dokumen harus bisa diperiksa secara
  mandiri (offline) dari isinya sendiri.

## 3. Pendekatan yang dipertimbangkan
1. **Pembaruan berlapis di PDF (dipilih).** DSS ditambah, lalu document timestamp (SubFilter ETSI.RFC3161) sebagai
   revisi inkremental. Ini sesuai ETSI EN 319 142-1 B-LTA dan gambar pembimbing, dan bisa dibaca pembaca PDF lain.
2. **Bukti terpisah (evidence record, RFC 4998) di luar PDF.** Lebih ringkas untuk arsip massal, tetapi PDF tidak lagi
   mandiri dan tidak sesuai gambar pembimbing. Tidak dipilih.
3. **Menandatangani ulang dokumen dengan ML-DSA.** Membutuhkan kunci penanda tangan lagi dan tidak membuktikan waktu
   tanda tangan awal. Tidak dipilih.

## 4. Komponen
Di `signer-core`:
- `ValidationData`: kumpulan sertifikat dan CRL (DER) yang akan disimpan.
- `DssWriter`: menambahkan atau memperbarui kamus `/DSS` di katalog dokumen dengan `/Certs`, `/CRLs`, dan `/VRI`
  (kunci VRI = SHA-1 heksadesimal huruf besar dari isi `/Contents` tanda tangan atau timestamp). Disimpan sebagai
  pembaruan inkremental.
- `DocumentTimestamper`: menambahkan kamus `/Type /DocTimeStamp`, `/SubFilter /ETSI.RFC3161`. Isinya token RFC 3161
  atas digest ByteRange. Digest SHA-256 untuk TSA RSA dan SHA-512 untuk TSA ML-DSA. Ruang `/Contents`: 16 KB (RSA) dan
  48 KB (ML-DSA).
- `LtvService` (alur):
  - `addValidationData(pdf, source)`: lapisan 3 untuk semua tanda tangan dan signature timestamp.
  - `addDocumentTimestamp(pdf, tsa)`: lapisan 4.
  - `renew(pdf, source, tsa)`: simpan data validasi untuk timestamp terakhir (rantai TSA dan CRL), lalu tambah
    document timestamp baru dari `tsa`. Ini lapisan 5, 6, dan seterusnya.
- `RevocationSource`: antarmuka untuk mengambil CRL dan rantai. Implementasi uji membaca dari `PkiService`, sehingga
  tidak perlu HTTP.
- `AlgorithmPolicy`: tabel batas aman algoritma yang bisa diatur. Bawaan mengikuti NIST IR 8547 (draf): RSA dan
  SHA-256 dengan RSA dianggap tidak aman mulai 2035, ML-DSA-65 tanpa batas yang diketahui.
- `ProtectionCalculator`: batas perlindungan = minimum dari (masa berlaku sertifikat TSA timestamp terbaru, masa
  berlaku rantai CA TSA itu, batas aman algoritma timestamp terbaru). Peringatan bila sisa kurang dari 30 hari
  (bisa diatur).
- `PdfSignatureVerifier` diperluas menjadi validator lengkap:
  - Membaca semua revisi: tanda tangan dan document timestamp beserta urutannya.
  - Menelusuri dari yang terbaru. Lapisan terbaru diperiksa pada waktu sekarang (atau waktu simulasi). Setiap
    lapisan di bawahnya diperiksa pada genTime timestamp yang menutupnya.
  - Rantai sertifikat (PKIX) memakai sertifikat dan CRL dari DSS. CRL harus diterbitkan setelah waktu yang
    dibuktikan dan sebelum waktu pemeriksaan.
  - Status gabungan dengan kebijakan AND: hybrid sah hanya bila RSA dan ML-DSA sah (KF-23).
  - Kode alasan yang mudah dipahami (KF-24), misalnya `DOKUMEN_BERUBAH`, `SERTIFIKAT_DICABUT`,
    `TSA_KEDALUWARSA_TANPA_PEMBARUAN`, `ALGORITMA_TIDAK_AMAN_PADA_WAKTU_PEMERIKSAAN`.
  - Mengembalikan daftar lapisan (nomor, jenis, level, TSA, algoritma, waktu, status) agar frontend bisa
    menggambarnya seperti papan tulis pembimbing.

Di `pki-test`:
- TSA bergenerasi: TSA RSA generasi 1 (masa berlaku pendek), TSA RSA generasi 2, dan TSA ML-DSA. Untuk simulasi,
  jam TSA dan jam verifikasi bisa digeser (`Clock`), sehingga skenario "15 tahun lagi" bisa diuji tanpa menunggu.

Di `app-server` (REST, KF-38):
- `POST /api/ltv/validation-data`: tambah lapisan 3.
- `POST /api/ltv/timestamp?tsa=rsa|mldsa`: tambah document timestamp.
- `POST /api/ltv/renew?tsa=rsa|mldsa`: pembaruan.
- `POST /api/verify?at=<waktu ISO>`: verifikasi, dengan waktu simulasi opsional, mengembalikan daftar lapisan dan
  batas perlindungan.
- `POST /api/sign` mendapat opsi `level=B-T|B-LT|B-LTA`.

## 5. Skenario demo (mengikuti gambar)
1. Tandatangani hybrid: lapisan 1 dan 2. Tambah DSS: lapisan 3. Tambah timestamp TSA RSA generasi 1: lapisan 4.
2. Geser jam mendekati kedaluwarsa TSA generasi 1. Aplikasi memberi peringatan, lalu pembaruan dengan TSA RSA
   generasi 2 menjadi lapisan 5.
3. Pembaruan dengan TSA ML-DSA: lapisan 6.
4. Verifikasi pada waktu simulasi setelah 2035, ketika RSA dianggap tidak aman. Hasilnya tetap sah, karena lapisan
   terbaru adalah ML-DSA dan setiap lapisan RSA dibuktikan sudah ada sebelum 2035.
5. Pembanding: dokumen yang hanya sampai lapisan 4 diverifikasi setelah TSA generasi 1 kedaluwarsa. Hasilnya "tidak
   dapat dibuktikan", disertai peringatan (TC-07).

## 6. Pengujian
- JUnit: DSS terbaca, tanda tangan lama tetap sah setelah setiap lapisan, rantai tiga pembaruan, TC-05, TC-06, TC-07,
  dan perubahan satu byte di setiap lapisan terdeteksi.
- OpenSSL: token document timestamp RSA lewat `openssl ts -verify`, token ML-DSA lewat `openssl cms` (T-006).
- Pembaca PDF: PdfCraft dan Adobe. Harapan: Adobe menampilkan "LTV enabled" untuk lapisan RSA dan kemungkinan
  menandai timestamp ML-DSA tidak sah (sama seperti T-011).

## 7. Keputusan yang sudah diambil
- Lapisan 1 tetap hybrid dua tanda tangan berurutan (keputusan Sprint 0), kecuali pembimbing menyatakan lain.
- Lapisan 4 dan 5 memakai TSA RSA, lapisan 6 memakai TSA ML-DSA (mengikuti gambar pembimbing).
- Struktur tetap PAdES standar (keputusan T-011).

## 8. Diserahkan ke pembangun
- Ukuran ruang `/Contents` setelah diukur.
- Format pasti kode alasan dan teks pesan.
- Apakah VRI ditulis untuk setiap timestamp (PAdES baseline menjadikannya opsional). Bawaan: ditulis, demi
  kompatibilitas Adobe.
