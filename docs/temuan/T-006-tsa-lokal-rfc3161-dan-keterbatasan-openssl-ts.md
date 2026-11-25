# T-006: TSA lokal RFC 3161 (RSA dan ML-DSA) dan keterbatasan `openssl ts` untuk ML-DSA

| Butir | Isi |
|---|---|
| Tanggal | 25 Nov 2026 |
| Jenis | Pengujian dan temuan kompatibilitas |
| Sprint | 1 (dikerjakan lebih awal) |
| Kebutuhan terkait | KF-07, KF-08, KF-09 (dasar simulasi kedaluwarsa), KNF-04 |
| Lingkungan | Windows 11 (laptop), Temurin JDK 25.0.5.1, Bouncy Castle 1.87, OpenSSL 3.5.9 (Git for Windows); uji awal juga di Linux (container) dengan OpenSSL 3.0.13 untuk RSA |
| Versi kode | commit "Sprint 1: TSA lokal RFC 3161 RSA dan ML-DSA" |
| Status | Lulus, dengan satu keterbatasan alat uji tercatat |

## Ringkasan
TSA lokal RFC 3161 dengan kunci RSA-3072 dan ML-DSA-65 sudah berjalan, beserta klien timestamp yang memeriksa
jawaban TSA. Semua 16 uji JUnit modul `pki-test` lolos, dan 20 dari 20 uji silang OpenSSL sesuai harapan.
Temuan: perintah `openssl ts` di OpenSSL 3.5.9 belum bisa membuat maupun memverifikasi token ML-DSA, sedangkan
perintah `openssl cms` bisa. Token ML-DSA buatan aplikasi terbukti sah lewat `openssl cms -verify`.

## Langkah
1. `cd backend`, set `JAVA_HOME` ke JDK 25, lalu `.\mvnw.cmd -B clean install`.
2. `.\mvnw.cmd -B -q -pl pki-test exec:java`. Selain PKI uji, demo ini meminta timestamp atas `02-hybrid-ca.pdf`
   ke TSA RSA (SHA-256) dan TSA ML-DSA (SHA-512), lalu menyimpan `rsa-ts.tsq/.tsr` dan `mldsa-ts.tsq/.tsr`.
3. `cd ..` lalu `powershell -ExecutionPolicy Bypass -File scripts\cek-pki.ps1`.
4. Untuk temuan: coba buat token ML-DSA dengan OpenSSL sendiri
   (`openssl ts -reply -config ts.cnf -queryfile mldsa-ts.tsq -inkey kunci-tsa.pem -signer mldsa-tsa.pem`).

## Hasil

Komponen baru:

| Komponen | Modul | Fungsi |
|---|---|---|
| `TimestampClient` | signer-core | Membuat TimeStampReq (nonce 64 bit, certReq), memeriksa status, nonce, imprint, kebijakan, tanda tangan token, ESSCertID, EKU TSA |
| `TimestampTransport`, `HttpTimestampTransport` | signer-core | Pengiriman permintaan; HTTP POST `application/timestamp-query` |
| `LocalTsa` | pki-test | TSA uji: imprint SHA-256/384/512, accuracy 1 detik, ESSCertIDv2, rantai TSA disertakan, nomor seri naik, menolak bila sertifikat TSA tidak berlaku pada jam TSA |

Profil token: kebijakan uji `2.25.36705446734633049719210837150943547147` (OID berbasis UUID). Token RSA memakai
sha256WithRSAEncryption dan digest SHA-256. Token ML-DSA memakai ML-DSA-65 (OID 2.16.840.1.101.3.4.3.18) dan
digest SHA-512 sesuai RFC 9882.

Uji JUnit `LocalTsaTest` (8 uji, semua lolos):

| Uji | Hasil |
|---|---|
| TSA RSA: token sah, genTime sesuai jam, kebijakan, nonce, accuracy, imprint, rantai TSA (3 sertifikat) disertakan | Lolos |
| TSA ML-DSA: token sah, algoritma ML-DSA-65, digest SHA-512, rantai TSA sah sampai root ML-DSA | Lolos |
| Nomor seri token unik dan naik | Lolos |
| Token tidak cocok bila data berbeda | Lolos |
| Imprint SHA-1 ditolak (status rejection, failInfo badAlg) | Lolos |
| Kebijakan TSA yang tidak dikenal ditolak | Lolos |
| Permintaan rusak dijawab rejection (badDataFormat), TSA tidak crash | Lolos |
| Sertifikat TSA berlaku 1 jam; setelah jam TSA dimajukan 2 jam, permintaan ditolak "Sertifikat TSA tidak berlaku" (RSA dan ML-DSA) | Lolos |

Uji silang OpenSSL 3.5.9 (`scripts\cek-pki.ps1`): 20 dari 20 sesuai harapan. Tambahan dibanding T-005:

| Uji | Harapan | Hasil |
|---|---|---|
| RSA: `openssl ts -verify` terhadap permintaan (nonce, imprint) | lolos | Verification: OK |
| RSA: `openssl ts -verify` terhadap PDF | lolos | Verification: OK |
| RSA: `openssl ts -verify` terhadap data lain | ditolak | ditolak |
| ML-DSA: `openssl ts -verify` | (lihat temuan) | ditolak: `provider signature not supported ... ML-DSA-65 verify_init` di `PKCS7_signatureVerify` |
| ML-DSA: token diekstrak (`ts -reply -token_out`), lalu `openssl cms -verify` dengan rantai sampai root ML-DSA | lolos | CMS Verification successful |
| ML-DSA: imprint di TSTInfo sama dengan SHA-512 PDF | lolos | sama (`60D5ED76...F47317DB`) |
| ML-DSA: imprint dibandingkan dengan SHA-512 data lain | ditolak | berbeda |

Pembanding: OpenSSL sendiri juga gagal membuat token ML-DSA:
`PKCS7_SIGNER_INFO_set: signing not supported for this key type` di `ts_RESP_sign`.

## Analisis
- Modul `ts` OpenSSL 3.5 masih dibangun di atas kode PKCS7 lama yang memakai pola hash-lalu-tanda-tangan
  (digest-sign). ML-DSA murni tidak didukung di jalur itu. Modul `cms` OpenSSL sudah diperbarui untuk ML-DSA
  (RFC 9882), sehingga token yang sama sah bila diperiksa lewat `openssl cms`. Jadi kegagalan ini keterbatasan alat
  uji, bukan kesalahan token buatan aplikasi.
- Akibatnya bagi proyek: tidak semua aplikasi luar bisa memeriksa timestamp ML-DSA. Sama seperti tanda tangan
  ML-DSA di PdfCraft (T-002), pembaca PDF umum kemungkinan belum mengenali token TSA ML-DSA. Ini mendukung
  desain hybrid: jalur RSA (tanda tangan dan TSA RSA) tetap terbaca pembaca PDF umum, sedangkan jalur ML-DSA
  divalidasi oleh validator aplikasi ini dan oleh `openssl cms`.
- Untuk pengujian berikutnya, token ML-DSA diperiksa dengan `openssl cms -verify` dan perbandingan imprint,
  seperti di skrip `cek-pki.ps1`.
- Bouncy Castle mengisi failInfo untuk imprint SHA-1 dengan nilai 132 (badAlg dan badDataFormat sekaligus), bukan
  hanya badAlg. Uji memeriksa bit badAlg saja.
- Jam TSA yang bisa diganti (`LocalTsa.setClock`) menjadi dasar simulasi TSA kedaluwarsa untuk pembaruan timestamp
  (PAdES B-LTA) di Sprint 4.

## Tindak lanjut
- Sprint 1 langkah 3: signature timestamp (PAdES B-T). Rencana: tanda tangan RSA diberi timestamp dari TSA RSA,
  tanda tangan ML-DSA diberi timestamp dari TSA ML-DSA (imprint SHA-512).
- Sprint 1 langkah 5: endpoint HTTP TSA di backend (`/api/tsa/rsa`, `/api/tsa/mldsa`), lalu uji dengan permintaan
  buatan `openssl ts -query`.
- Pantau rilis OpenSSL berikutnya untuk dukungan ML-DSA di `openssl ts`.
