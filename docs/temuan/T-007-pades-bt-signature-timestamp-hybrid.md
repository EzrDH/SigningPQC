# T-007: PAdES B-T, signature timestamp pada tanda tangan hybrid

| Butir | Isi |
|---|---|
| Tanggal | 25 Nov 2026 |
| Jenis | Pengujian |
| Sprint | 1 (dikerjakan lebih awal) |
| Kebutuhan terkait | KF-12 s.d. KF-15 (bagian timestamp), KF-20, KNF-04, TC-03, TC-10 |
| Lingkungan | Windows 11 (laptop), Temurin JDK 25.0.5.1, Bouncy Castle 1.87, PDFBox 3.0.8, OpenSSL 3.5.9 |
| Versi kode | commit "Sprint 1: PAdES B-T signature timestamp hybrid" |
| Status | Lulus |

## Ringkasan
Tanda tangan hybrid sekarang bisa dibuat pada level PAdES B-T. Tanda tangan RSA diberi timestamp dari TSA RSA
(imprint SHA-256), dan tanda tangan ML-DSA diberi timestamp dari TSA ML-DSA (imprint SHA-512). Verifier aplikasi
memeriksa token dan melaporkan level B-T. Semua 25 uji JUnit lolos (4 signer-core dan 21 pki-test), dan
27 dari 27 uji silang OpenSSL 3.5.9 sesuai harapan.

## Langkah
1. `cd backend`, set `JAVA_HOME` ke JDK 25, lalu `.\mvnw.cmd -B clean install`.
2. `.\mvnw.cmd -B -q -pl pki-test exec:java`. Demo ini membuat `03-hybrid-bt.pdf`, lalu mengekspor `bt1.p7s` dan
   `bt2.p7s`, token signature timestamp `btN-ts.tst`, dan nilai tanda tangan yang di-timestamp `btN-ts-data.bin`.
3. `cd ..` lalu `powershell -ExecutionPolicy Bypass -File scripts\cek-pki.ps1`.

## Hasil

Cara kerja: setelah CMS dibuat, nilai `SignerInfo.signature` di-hash, lalu dikirim ke TSA. Token yang diterima
disimpan sebagai atribut tak bertanda tangan `id-aa-signatureTimeStampToken` (1.2.840.113549.1.9.16.2.14)
sesuai ETSI EN 319 122-1. Bila TSA menolak, misalnya karena sertifikat TSA kedaluwarsa, penandatanganan
dibatalkan dan tidak ada PDF yang dihasilkan.

Hasil verifier aplikasi untuk `03-hybrid-bt.pdf`:

| Tanda tangan | Level | Sah | Ukuran CMS | Timestamp oleh | Algoritma token |
|---|---|---|---|---|---|
| 1, RSA-3072 | B-T | ya | 10.126 B | CN=TSA Uji RSA | RSA, imprint SHA-256 |
| 2, ML-DSA-65 | B-T | ya | 48.799 B | CN=TSA Uji ML-DSA | ML-DSA-65, imprint SHA-512 |

Uji JUnit `PadesTimestampTest` (5 uji, semua lolos):

| Uji | Hasil |
|---|---|
| Hybrid B-T: kedua tanda tangan sah, level B-T, waktu timestamp wajar, /M tidak lebih lambat dari timestamp | Lolos |
| Tanpa TSA, level tetap B-B dan tidak ada timestamp | Lolos |
| Token diperiksa terhadap data lain, imprint tidak cocok dan dinyatakan tidak sah | Lolos |
| TSA kedaluwarsa, penandatanganan dibatalkan dengan pesan "Sertifikat TSA tidak berlaku" | Lolos |
| Satu byte PDF diubah, kedua tanda tangan ditolak dengan pesan "Isi dokumen telah diubah ... (message-digest tidak cocok)" | Lolos |

Uji silang OpenSSL 3.5.9, tambahan dibanding T-006:

| Uji | Harapan | Hasil |
|---|---|---|
| CMS RSA B-T, rantai sampai root | lolos | CMS Verification successful |
| CMS ML-DSA B-T, rantai sampai root | lolos | CMS Verification successful |
| Atribut signatureTimeStampToken ada di kedua CMS (`openssl cms -cmsout -print`) | ada | 1 kali di setiap CMS |
| Token RSA diperiksa terhadap nilai tanda tangan (`openssl ts -verify -token_in`) | lolos | Verification: OK |
| Token ML-DSA lewat `openssl cms -verify` dengan rantai | lolos | CMS Verification successful |
| Imprint token ML-DSA sama dengan SHA-512 nilai tanda tangan | sama | `C03F8CCB...1172527AC` |

## Analisis
- Struktur B-T sudah benar dan bisa diperiksa alat lain. Pada jalur RSA, OpenSSL memeriksa token secara penuh.
  Pada jalur ML-DSA, pemeriksaan memakai `openssl cms` karena keterbatasan `openssl ts` yang dicatat di T-006.
- Ukuran: CMS ML-DSA dengan timestamp sekitar 48,8 KB. Sebagian besar ukuran itu berasal dari enam sertifikat
  ML-DSA, yaitu rantai penanda tangan dan rantai TSA, masing-masing berisi root ML-DSA-87. Ruang /Contents untuk
  ML-DSA dengan timestamp dinaikkan menjadi 88 KB (48 + 40 KB), dan untuk RSA menjadi 32 KB. Ukuran bisa dikurangi
  nanti dengan tidak menyertakan root di dalam CMS, karena root tetap tersimpan di DSS pada B-LT.
- Verifier memisahkan pesan "isi dokumen diubah" (message-digest tidak cocok) dari "tanda tangan tidak sah". Ini
  memudahkan pembacaan hasil di antarmuka nanti.
- Belum diperiksa: rantai sertifikat TSA dan penanda tangan pada waktu timestamp, serta pencabutan. Bagian ini
  menjadi bagian validator di Sprint 2 dan data DSS (B-LT) di Sprint 3.

## Tindak lanjut
- Sprint 1 langkah 4: tampilan visible untuk tanda tangan RSA.
- Sprint 1 langkah 5: endpoint REST TSA dan tanda tangan, lalu uji ulang di PdfCraft/Adobe untuk melihat apakah
  timestamp RSA dikenali.
