# T-012: Lapisan LTV (B-LT, B-LTA) dan pembaruan TSA sampai TSA ML-DSA

| Butir | Isi |
|---|---|
| Tanggal | 25 Nov 2026 |
| Jenis | Pengujian |
| Sprint | 2 (lapisan jangka panjang, sesuai papan tulis pembimbing) |
| Kebutuhan terkait | KF-16, KF-17, KF-19 s.d. KF-29, KF-38, TC-01, TC-03 s.d. TC-07 |
| Lingkungan | Windows 11 (laptop), Temurin JDK 25.0.5, Bouncy Castle 1.87, PDFBox 3.0.8, OpenSSL 3.5.9 |
| Berkas uji | `backend\pki-output\05-hybrid-lt.pdf`, `06-hybrid-lta.pdf`, `07-hybrid-pembaruan-pqc.pdf` (dibuat ulang setiap `exec:java`) |
| Status | Lulus di JUnit dan OpenSSL. Empat temuan review akhir sudah diperbaiki (lihat bagian Perbaikan). Uji di PdfCraft dan Adobe menunggu tangkapan layar |

## Ringkasan
Dokumen hybrid sekarang bisa dibungkus berlapis seperti gambar pembimbing: tanda tangan (lapisan 1-2), data
validasi DSS (lapisan 3), document timestamp TSA RSA G1 (lapisan 4), pembaruan TSA RSA G2 (lapisan 5), dan
pembaruan TSA ML-DSA (lapisan 6). Validator baru memeriksa setiap lapisan pada waktu yang dibuktikan lapisan di
atasnya. Hasilnya, dokumen `07` dinyatakan **SAH pada simulasi tahun 2040**, walaupun TSA G1 sudah kedaluwarsa dan
RSA dianggap tidak aman sejak 2035. Pembandingnya, dokumen `06` yang tidak diperbarui dinyatakan **TIDAK DAPAT
DIBUKTIKAN** sehari setelah TSA G1 kedaluwarsa. Ini menjawab kebutuhan dokumen seumur hidup (KK, ijazah) yang di
sistem saat ini berhenti di lapisan 3 (OCSP/CRL).

## Lapisan di dalam PDF

| Lapisan (papan tulis) | Revisi PDF | Isi | Level PAdES |
|---|---|---|---|
| 1 Sig | 2, 3 | Tanda tangan RSA (visible) dan ML-DSA (invisible), masing-masing dengan signature timestamp | B-T |
| 2 TSA | di dalam revisi 2 dan 3 | Signature timestamp TSA RSA G1 (untuk RSA) dan TSA ML-DSA (untuk ML-DSA) | B-T |
| 3 OCSP/CRL | 4 | Kamus `/DSS`: rantai sertifikat, CRL, dan `/VRI` per tanda tangan | B-LT |
| 4 TSA | 5 | Document timestamp TSA RSA G1 (`/SubFilter /ETSI.RFC3161`, SHA-256) | B-LTA |
| 5 TSA | 6, 7 | DSS untuk timestamp G1, lalu document timestamp TSA RSA G2 | B-LTA |
| 6 TSA-PQC | 8, 9 | DSS untuk timestamp G2, lalu document timestamp TSA ML-DSA (SHA-512) | B-LTA |

Revisi 1 adalah PDF asli. Validator menampilkan 8 lapisan untuk `07` (2 tanda tangan, 3 DSS, 3 document timestamp).

## Langkah
1. Build dan uji seluruh modul dari folder `backend`: `.\mvnw.cmd -B -pl app-server -am install`.
2. Buat PDF demo dan cetak laporan validasi: `.\mvnw.cmd -B -q -pl pki-test exec:java`.
3. Uji silang dari folder repositori: `powershell -ExecutionPolicy Bypass -File scripts\cek-pki.ps1`.
4. Ukur ukuran dan waktu per lapisan: kelas `id.pkl.signingpqc.pki.LtvUkurMain` (3 putaran pemanasan dibuang,
   20 putaran diukur).
5. Lewat REST: `POST /api/sign` dengan `level=B-LTA`, lalu `POST /api/ltv/renew?tsa=rsa-g2&at=<waktu>`,
   `POST /api/ltv/renew?tsa=mldsa&at=2034-06-01T00:00:00Z`, dan `POST /api/verify?at=2040-01-01T00:00:00Z`.

Waktu simulasi dibuat dengan jam TSA dan jam CRL yang digeser, bukan dengan mengubah jam komputer.

## Hasil

### Uji JUnit: 73 lolos (sebelum perbaikan review; sesudahnya 81, lihat di bawah)
signer-core 7, pki-test 55, app-server 11. Uji baru di sprint ini:

| Kelas uji | Jumlah | Yang dibuktikan |
|---|---:|---|
| `LongTermPkiTest` | 2 | Masa berlaku root 30 th, CA antara 25 th, TSA G1 2 th, G2 10 th, ML-DSA 20 th; CRL dengan thisUpdate tertentu |
| `DssTest` | 3 | DSS ditulis dan tanda tangan lama tetap sah, DSS lama digabung, PDF tanpa DSS memberi data kosong |
| `DocumentTimestampTest` | 3 | Timestamp RSA menutup seluruh dokumen, ML-DSA memakai SHA-512, tiga timestamp berurutan |
| `LtvServiceTest` | 2 | VRI untuk penanda tangan dan TSA, pembaruan menambah VRI untuk timestamp sebelumnya |
| `ChainValidatorTest` | 7 | Rantai pada waktu tertentu: sah, dicabut sebelum atau sesudah, kedaluwarsa, tanpa CRL, root lain, CRL basi |
| `DocumentValidatorTest` | 12 | TC-01, TC-03 s.d. TC-07, RSA saja setelah 2035, peringatan 30 hari, kebijakan AND, LTA tanpa DSS, waktu pemeriksaan sebelum tanda tangan, revisi tambahan |
| `RestApiTest` (baru) | 3 | Alur REST sampai TSA ML-DSA (SAH pada 2040), tolak bukan PDF (400), tolak PDF terenkripsi (400) |

### Laporan validasi (keluaran `exec:java`)

| Berkas dan waktu pemeriksaan | Status | Batas perlindungan | Keterangan |
|---|---|---|---|
| `06` sekarang | SAH | 24 Nov 2028 (rantai TSA RSA G1) | Semua lapisan OK |
| `06` sehari setelah TSA G1 kedaluwarsa (TC-07) | TIDAK DAPAT DIBUKTIKAN | 24 Nov 2028 | Lapisan 4: `TSA_KEDALUWARSA_TANPA_PEMBARUAN` |
| `07` pada 1 Jan 2040 (TC-06) | SAH | 20 Nov 2046 (rantai TSA ML-DSA) | Semua lapisan OK |

Rincian `07` pada 2040. "Dibuat" adalah genTime lapisan itu; "dibuktikan" adalah waktu yang dibuktikan lapisan di
atasnya:

| Revisi | Lapisan | Algoritma | Dibuat | Dibuktikan | Hasil |
|---|---|---|---|---|---|
| 2 | Tanda tangan B-T | RSA | 25 Nov 2026 | 25 Nov 2026 (timestamp G1) | OK |
| 3 | Tanda tangan B-T | ML-DSA-65 | 25 Nov 2026 | 25 Nov 2026 (timestamp G1) | OK |
| 4 | DSS | - | - | - | OK |
| 5 | Document timestamp G1 | RSA | 25 Nov 2026 | 14 Nov 2028 (timestamp G2) | OK |
| 6 | DSS | - | - | - | OK |
| 7 | Document timestamp G2 | RSA | 14 Nov 2028 | 1 Jun 2034 (timestamp ML-DSA) | OK |
| 8 | DSS | - | - | - | OK |
| 9 | Document timestamp ML-DSA | ML-DSA-65 | 1 Jun 2034 | 1 Jan 2040 (waktu pemeriksaan) | OK |

Setiap lapisan RSA dibuktikan sebelum 1 Jan 2035, batas aman RSA (NIST IR 8547, draf). Lapisan teratas memakai
ML-DSA yang belum punya batas aman.

### Uji silang OpenSSL: 33 dari 33 sesuai
Sebanyak 27 uji lama tetap sesuai. Ada 6 uji baru atas token document timestamp `07`:
- `openssl ts -verify -token_in -data` lolos untuk timestamp RSA G1 dan G2 atas revisinya masing-masing. Uji ini
  ditolak bila datanya dari revisi lain.
- Token ML-DSA lolos `openssl cms -verify -binary` dengan rantai ML-DSA. `openssl ts` belum mendukung ML-DSA
  (T-006). Imprint TSTInfo sama dengan SHA-512 revisi PDF dan berbeda dari revisi lain.

### Ukuran berkas per lapisan

| Tahap | Ukuran (byte) | Tambahan (byte) |
|---|---:|---:|
| PDF asli | 915 | - |
| Lapisan 1: + tanda tangan RSA (B-T) | 67.581 | 66.666 |
| Lapisan 2: + tanda tangan ML-DSA (B-T) | 249.100 | 181.519 |
| Lapisan 3: + DSS (B-LT) | 301.938 | 52.838 |
| Lapisan 4: + timestamp RSA G1 (B-LTA) | 336.442 | 34.504 |
| Lapisan 5: + DSS + timestamp RSA G2 | 398.241 | 61.799 |
| Lapisan 6: + DSS + timestamp ML-DSA | 527.752 | 129.511 |

### Waktu proses per lapisan (rata-rata 20 putaran)

| Langkah | Rata-rata (ms) | Min (ms) | Maks (ms) |
|---|---:|---:|---:|
| 1. Tanda tangan RSA + signature timestamp G1 | 9,0 | 7,9 | 10,7 |
| 2. Tanda tangan ML-DSA + signature timestamp ML-DSA | 7,4 | 6,3 | 9,4 |
| 3. DSS (rantai + CRL) | 32,2 | 28,9 | 38,0 |
| 4. Document timestamp TSA RSA G1 | 8,7 | 8,0 | 9,8 |
| 5. Pembaruan: DSS + timestamp TSA RSA G2 | 51,4 | 47,8 | 57,7 |
| 6. Pembaruan: DSS + timestamp TSA ML-DSA | 62,9 | 58,4 | 73,6 |
| Validasi `07` pada 2040 | 57,5 | 52,0 | 68,7 |

### Pembaca PDF (PdfCraft dan Adobe)
Menunggu tangkapan layar `06` dan `07`. Harapan: Adobe menampilkan "LTV enabled" untuk lapisan RSA, dan timestamp
ML-DSA ditandai tidak sah, sama seperti tanda tangan ML-DSA di T-011.

Pembaruan 27 Nov 2026: untuk dokumen B-LTA dari aplikasi, Adobe sudah menyatakan tanda tangan RSA dan document
timestamp RSA "LTV enabled" setelah root uji dipercaya (T-024). Berkas `06` dan `07` (pembaruan sampai TSA ML-DSA)
belum dicek di Adobe dan PdfCraft.

### Perbaikan hasil review akhir
Review seluruh branch menemukan empat celah. Masing-masing dibuktikan dulu dengan uji yang gagal, lalu diperbaiki.
Setelah perbaikan, seluruh uji lolos: **81 dari 81** (signer-core 7, pki-test 62, app-server 12). Angka ini dari
container dengan JDK 21, lalu diulang di laptop dengan JDK 25 dan hasilnya sama (81 lolos). Uji silang OpenSSL
tetap 33 dari 33 sesuai.

| Temuan | Masalah sebelum diperbaiki | Perbaikan | Uji pembukti |
|---|---|---|---|
| C1 (kritis) | Isi dokumen yang diubah di antara lapisan, di dalam revisi DSS, atau tanpa `%%EOF` tetap dinyatakan SAH | Setiap revisi diperiksa lewat tabel xref. Objek baru harus terjangkau dari `/DSS` atau field tanda tangan. Objek lama hanya boleh berubah pada kunci DSS, AcroForm, Annots, Fields, dan SigFlags. Stream yang berubah selalu ditolak. Hasilnya peringatan `PERUBAHAN_ISI_TIDAK_DITANDATANGANI` dan status turun ke TIDAK DAPAT DIBUKTIKAN | `perubahanDiAntaraLapisanTerdeteksi`, `dssSebelumTandaTanganTidakMenyamarkanPerubahan`, `perubahanDiDalamRevisiTimestampTerdeteksi`, `byteTambahanTanpaEofTerdeteksi` |
| I1 (penting) | Penanda tangan yang dicabut tetap SAH tanpa catatan bila dokumen belum punya DSS. Ringkasan berbunyi "semua lapisan terbukti" walaupun ada peringatan | `/api/verify` kini juga memakai CRL lokal PKI uji (semua CA, bertanggal waktu pemeriksaan, tidak disimpan). Ringkasan menjadi "Dokumen sah dengan catatan: ..." bila ada peringatan | `crlLokalMendeteksiPencabutanTanpaDss`, `RestApiTest.verifikasiMemakaiCrlLokalPkiUji` |
| I2 (penting) | Pencabutan sertifikat TSA hanya dibandingkan dengan waktu timestamp dibuat | Pencabutan dibandingkan dengan waktu yang harus dibuktikan timestamp itu (waktu lapisan berikutnya). TSA yang dicabut sebelum pembaruan memberi `SERTIFIKAT_DICABUT` dan TIDAK SAH | `tsaDicabutSebelumPembaruanTidakSah`, `dicabutSesudahnyaTetapSahDenganPeringatan` |
| M1 (dinaikkan) | Algoritma yang tidak dikenal dianggap aman selamanya | Algoritma di luar RSA dan ML-DSA dianggap tidak aman (fail-closed). OID mentah RSA dan ML-DSA dipetakan ke familinya | `algoritmaTakDikenalTidakDianggapAman` |

Catatan pengujian: waktu CRL dan timestamp berpresisi detik. Pencabutan pada detik yang sama dengan timestamp
dianggap sudah dicabut (sikap konservatif). Karena itu uji REST pencabutan diberi jeda 1,1 detik sebelum mencabut.

Demo `exec:java` setelah perbaikan memberi hasil yang sama seperti sebelumnya: `06` SAH sekarang, `06` TIDAK DAPAT
DIBUKTIKAN setelah G1 kedaluwarsa, dan `07` SAH pada 2040 dengan 9 revisi. Pemeriksaan perubahan baru tidak memberi
peringatan palsu pada lapisan DSS dan document timestamp yang wajar.

## Analisis
- **Rantai bukti.** Setiap lapisan hanya perlu bertahan sampai lapisan di atasnya dibuat. TSA G1 cukup sah sampai
  timestamp G2 dibuat 10 hari sebelum G1 kedaluwarsa. RSA cukup aman sampai timestamp ML-DSA dibuat pada 2034.
  Dokumen tetap sah selama lapisan teratasnya masih berlaku. Tanpa pembaruan, bukti putus saat TSA lapisan teratas
  kedaluwarsa (TC-07).
- **Data validasi.** Rantai dan CRL diambil dari DSS, tidak dari internet. CRL harus terbit pada atau setelah waktu
  yang dibuktikan. Lapisan teratas belum punya CRL di DSS, karena data itu baru disimpan pada pembaruan berikutnya.
  Validator mencatatnya sebagai peringatan `STATUS_PENCABUTAN_TIDAK_DIKETAHUI`, bukan kegagalan.
- **Ukuran.** Tambahan terbesar berasal dari ruang `/Contents` yang dicadangkan dan ditulis heksadesimal (2 karakter
  per byte), dan dari ukuran ML-DSA. Tanda tangan ML-DSA menambah sekitar 181 KB. Timestamp ML-DSA dengan DSS-nya
  menambah sekitar 130 KB, sedangkan timestamp RSA dengan DSS-nya sekitar 62 KB. Untuk dokumen seumur hidup yang
  diperbarui misalnya setiap 10 tahun, ukuran tumbuh secara linear dan masih wajar.
- **Waktu.** Setiap pembaruan mengumpulkan ulang rantai dan CRL untuk semua lapisan, jadi waktunya naik sedikit
  setiap lapisan (51 ms lalu 63 ms). Semua langkah jauh di bawah 1 detik.
- **Kebijakan AND tetap berlaku.** Bila CMS ML-DSA diubah satu byte, dokumen `TIDAK_SAH`, walaupun tanda tangan RSA
  masih sah.

## Keterbatasan
- OCSP belum dibuat. Pencabutan hanya lewat CRL, sesuai rancangan.
- TSA dan CA hanya uji lokal. Waktu simulasi (`at`) ditolak untuk TSA luar ("Waktu simulasi hanya untuk TSA uji").
- Pemeriksaan perubahan per revisi mengikuti struktur yang ditulis aplikasi ini. Alat lain yang menulis DSS dengan
  kunci tambahan (misalnya `/DR` atau `/Perms`) bisa memicu peringatan palsu.
- CRL dipakai bila terbit pada atau setelah waktu yang dibuktikan, tanpa batas atas. CRL lokal untuk simulasi jauh
  di masa depan bisa diterbitkan oleh CA yang pada waktu itu sudah kedaluwarsa (M2).
- DSS yang rusak membuat `/api/verify` gagal dengan 500, belum 400 atau laporan yang rapi (M3).
- Pemeriksaan hybrid belum mencocokkan identitas penanda tangan RSA dan ML-DSA serta urutannya (M4).
- Penomoran lapisan menganggap PDF asli hanya satu revisi (M5).
- Parameter `at` membuat TSA uji bisa memberi timestamp mundur. Sebaiknya diaktifkan lewat flag konfigurasi (M6).
- Nomor CRL simulasi di DSS tidak berurutan dengan CRL dari `/api/pki/crl`.
- PKI uji lama (Sprint 1) tetap bisa dimuat, tetapi belum punya TSA `rsa-g2`. Buat ulang dengan
  `POST /api/pki/setup?reset=true` untuk memakai profil jangka panjang.

## Tindak lanjut
- Catat hasil PdfCraft dan Adobe untuk `06` dan `07` di catatan ini.
- Frontend Vue: wizard tanda tangan dengan pilihan level, tombol Perbarui, dan visualisasi lapisan seperti papan
  tulis pembimbing (memakai `validation.layers` dari `/api/verify`).
- Pembaruan terjadwal otomatis dan OCSP tetap di luar cakupan PoC ini.
