# T-005: PKI uji RSA dan ML-DSA, CRL, dan PKCS#12

| Butir | Isi |
|---|---|
| Tanggal | 25 Nov 2026 |
| Jenis | Pengujian |
| Sprint | 1 (dikerjakan lebih awal) |
| Kebutuhan terkait | KF-01, KF-02, KF-03, KF-04, KF-05, TC-04 (bagian sertifikat), TC-10 |
| Lingkungan | Windows 11 (laptop), Temurin JDK 25.0.5.1, Bouncy Castle 1.87, PDFBox 3.0.8, OpenSSL 3.5.9; uji awal juga di Linux (container) dengan JDK 25.0.5.1 |
| Versi kode | commit "Sprint 1: PKI uji RSA dan ML-DSA, CRL, PKCS#12" |
| Status | Lulus |

## Ringkasan
Modul baru `pki-test` membuat dua hierarki CA uji (RSA dan ML-DSA), pasangan sertifikat penanda tangan dengan
identitas sama, sertifikat TSA, CRL, dan berkas PKCS#12. Semua 8 uji JUnit lolos, dan 13 dari 13 uji silang
OpenSSL 3.5.9 sesuai harapan, termasuk validasi rantai ML-DSA sampai root dan penolakan sertifikat yang dicabut.

## Langkah
1. `cd backend`, set `JAVA_HOME` ke JDK 25.
2. `.\mvnw.cmd -B clean install` (menjalankan semua uji JUnit).
3. `.\mvnw.cmd -B -q -pl pki-test exec:java` (membuat PKI uji ke `backend\pki-output\`).
4. `cd ..` lalu `powershell -ExecutionPolicy Bypass -File scripts\cek-pki.ps1`.

## Hasil

Profil PKI uji yang dibuat:

| Sertifikat | RSA | ML-DSA | Ekstensi utama |
|---|---|---|---|
| Root CA | RSA-4096, ditandatangani sendiri, 10 tahun | ML-DSA-87 (OID ...3.19), 10 tahun | basicConstraints CA pathLen 1, keyUsage keyCertSign + cRLSign (kritis) |
| CA antara | RSA-3072, 5 tahun | ML-DSA-65, ditandatangani ML-DSA-87 | basicConstraints CA pathLen 0, CRL Distribution Points |
| Penanda tangan | RSA-3072, 2 tahun | ML-DSA-65 (OID ...3.18), 2 tahun | keyUsage digitalSignature + nonRepudiation (kritis), EKU documentSigning 1.3.6.1.5.5.7.3.36 |
| TSA | RSA-3072, 5 tahun | ML-DSA-65, 5 tahun | EKU id-kp-timeStamping (kritis, sesuai RFC 3161) |

Identitas penanda tangan yang sama pada kedua sertifikat: `C=ID, O=SigningPQC Uji, OU=Unit Uji, T=Kepala Seksi Uji, CN=Budi Penanda Tangan Uji` (data sintetis).

Uji JUnit (`TestPkiTest`, 8 uji, semua lolos di Windows dan Linux):

| Uji | Hasil |
|---|---|
| Algoritma tiap tingkat sesuai rencana (RSA-4096/3072, ML-DSA-87/65), basicConstraints benar | Lolos |
| Rantai penanda tangan sah menurut PKIX (Bouncy Castle) untuk kedua keluarga, dengan cek CRL | Lolos |
| Rantai ML-DSA ditolak bila root yang dipercaya salah | Lolos |
| Pasangan sertifikat: DN sama, nomor seri berbeda, keyUsage dan EKU benar | Lolos |
| Sertifikat TSA punya EKU timeStamping kritis dan rantainya sah | Lolos |
| Sertifikat dicabut (keyCompromise) ditolak saat cek CRL; sertifikat lain dari CA yang sama tetap sah | Lolos |
| PKCS#12 disimpan lalu dibaca kembali (kunci, rantai, algoritma sama); sandi salah ditolak | Lolos |
| PDF hybrid ditandatangani dengan kredensial dari PKCS#12 terbitan CA, kedua tanda tangan sah | Lolos |

Uji silang OpenSSL 3.5.9 (`scripts\cek-pki.ps1`): 13 dari 13 sesuai harapan.

| Uji | Harapan | Hasil |
|---|---|---|
| RSA dan ML-DSA: rantai penanda tangan + CRL (`openssl verify -crl_check_all`) | lolos | lolos |
| RSA dan ML-DSA: rantai TSA dengan `-purpose timestampsign` | lolos | lolos |
| RSA dan ML-DSA: sertifikat dicabut | ditolak | ditolak ("error 23 certificate revoked") |
| RSA dan ML-DSA: buka PKCS#12 dengan sandi benar | lolos | lolos |
| RSA dan ML-DSA: buka PKCS#12 dengan sandi salah | ditolak | ditolak |
| CMS RSA di `02-hybrid-ca.pdf`, rantai sampai root RSA | lolos | "CMS Verification successful" |
| CMS ML-DSA di `02-hybrid-ca.pdf`, rantai sampai root ML-DSA | lolos | "CMS Verification successful" |
| CMS ML-DSA diverifikasi dengan root RSA (salah) | ditolak | ditolak |

Waktu membuat dua hierarki CA dan satu pasangan penanda tangan: sekitar 2,1 detik di laptop (sebagian besar
untuk membangkitkan kunci RSA-4096 dan RSA-3072). Pembangkitan kunci ML-DSA jauh lebih cepat.

## Analisis
- Sertifikat ML-DSA buatan Bouncy Castle dikenali OpenSSL 3.5 sebagai `ML-DSA-65` dan rantainya bisa divalidasi
  sampai Root ML-DSA-87, jadi profil RFC 9881 yang dipakai sudah interoperabel di luar Java.
- Temuan teknis: format PKCS#12 bawaan Bouncy Castle (tipe `PKCS12`) mengenkripsi sertifikat dengan RC2-40, yang
  ditolak OpenSSL 3 tanpa provider "legacy" (`unsupported: RC2-40-CBC`). Penulisan diganti ke tipe
  `PKCS12-AES256-AES128` (PBES2, PBKDF2-HMAC-SHA256, AES-256 untuk kunci), sehingga berkas bisa dibuka OpenSSL 3
  tanpa opsi tambahan. Pembacaan tetap menerima PKCS#12 format apa pun.
- Validasi PKIX di `PkixValidation` baru memeriksa rantai dan CRL pada waktu sekarang. Validasi pada waktu
  tanda tangan (memakai timestamp) dan data DSS dikerjakan pada Sprint 2 dan 3.
- Catatan kosmetik: PowerShell 5 menampilkan pesan stderr OpenSSL sebagai `NativeCommandError` berwarna merah
  walaupun hasilnya benar. Skrip `cek-pki.ps1` sudah diubah agar pesan itu tampil sebagai teks biasa.
- Kata sandi PKCS#12 uji (`uji-signingpqc`) dan folder `pki-output/` hanya untuk lingkungan uji dan tidak di-commit.

## Tindak lanjut
- Sprint 1 langkah 2: TSA uji RFC 3161 (RSA dan ML-DSA) memakai sertifikat TSA dari modul ini.
- Sprint 1 langkah 5: endpoint REST untuk membuat PKI, menerbitkan penanda tangan, dan melayani CRL di
  `http://127.0.0.1:8085/api/pki/crl/` (alamat ini sudah ditulis di ekstensi CRL Distribution Points).
