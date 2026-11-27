# SigningPQC

Purwarupa aplikasi penandatanganan PDF **hybrid RSA + ML-DSA** (FIPS 204).
Tanda tangan RSA tampil di halaman, tanda tangan ML-DSA disematkan tersembunyi (invisible) dan menutup
seluruh dokumen. Format mengikuti PAdES (ETSI EN 319 142), CMS (RFC 5652, RFC 9882), dan timestamp RFC 3161.

> Lingkungan uji. Semua sertifikat, kunci, dan TSA adalah buatan sendiri. Jangan memakai data asli.

## Struktur

| Folder | Isi |
|---|---|
| `docs/` | PRD, dokumen kebutuhan, jadwal sprint |
| `docs/temuan/` | Catatan temuan dan hasil pengujian (T-001, T-002, ...) |
| `backend/` | Java 25 LTS, Maven multi-modul: `signer-core` (Bouncy Castle 1.87, PDFBox 3.0.8), `pki-test` (CA uji RSA dan ML-DSA, CRL, PKCS#12), `app-server` (Spring Boot 4.2, REST lokal) |
| `frontend/` | Vue 3 + Vite + TypeScript |
| `scripts/` | Skrip bantu (uji silang OpenSSL, alur REST, lapisan PDF dengan pyHanko) |

## Menjalankan aplikasi

### Prasyarat

| Perangkat lunak | Versi | Catatan |
|---|---|---|
| JDK | 25 LTS (mis. Temurin 25) | `JAVA_HOME` harus menunjuk ke JDK 25. Build berhenti dengan pesan jelas bila bukan JDK 25. Maven tidak perlu dipasang, cukup `mvnw.cmd`. |
| Node.js | 22 atau lebih baru | Untuk frontend Vue. |
| Aplikasi authenticator | Google Authenticator, Microsoft Authenticator, atau sejenis | Untuk kode TOTP saat mendaftar dan menandatangani. |
| OpenSSL 3.5, Python 3 | opsional | Hanya untuk uji silang (`scripts/`). |

Periksa dulu di PowerShell:

```powershell
java -version        # harus 25.x
echo $env:JAVA_HOME  # folder JDK 25
node -v              # v22 atau lebih baru
```

### Pertama kali

```powershell
# Terminal 1: backend di http://127.0.0.1:8085
cd backend
.\mvnw.cmd -pl app-server -am spring-boot:run

# Terminal 2: frontend di http://127.0.0.1:5173
cd frontend
npm install
npm run dev
```

Buka http://127.0.0.1:5173 di browser. Backend dan frontend hanya mendengarkan di `127.0.0.1`, jadi tidak bisa
dibuka dari komputer lain. Saat pertama jalan, backend membuat PKI uji sendiri (CA RSA dan ML-DSA, tiga TSA), dan
ini bisa memakan sekitar setengah menit. Bila berhasil, `http://127.0.0.1:8085/api/health` menjawab `"status":"ok"`.

Selanjutnya cukup jalankan dua perintah yang sama (`npm install` hanya perlu diulang bila `package.json` berubah).
Hentikan keduanya dengan `Ctrl+C`.

Sebagai pengganti `spring-boot:run`, backend juga bisa dijalankan dari jar hasil build:

```powershell
cd backend
.\mvnw.cmd -B -q -DskipTests package
java -jar app-server\target\app-server-0.1.0-SNAPSHOT.jar
```

### Panduan singkat

1. **Daftar.** Pilih Daftar. Isi username, email, dan password (minimal 8 karakter). Buat passphrase (minimal 12
   karakter), pindai QR dengan aplikasi authenticator, lalu masukkan kode 6 digit. Akun baru langsung mendapat
   sepasang sertifikat uji RSA dan ML-DSA.
2. **Tandatangani.** Di Beranda pilih Tandatangani dokumen, lalu pilih PDF. Bila tampilan Visible, seret kotak
   spesimen ke posisi yang diinginkan. Tekan Tandatangani dan masukkan kode TOTP atau passphrase. Hasilnya otomatis
   tersimpan sebagai `nama-ttd.pdf` di folder keluaran. Setiap tanda tangan otomatis hybrid (RSA lalu ML-DSA) dan
   B-LTA.
3. **Verifikasi.** Pilih Verifikasi dokumen, lalu pilih PDF. Status tampil langsung, dan Lihat detail membuka
   sertifikat, rantai, timestamp, dan lapisan dokumen.
4. **Pengaturan.** Atur tampilan (Visible atau Invisible), unggah spesimen PNG atau JPEG, pilih konfirmasi
   (Passphrase atau TOTP), dan ubah lokasi penyimpanan. Kotak Lingkungan uji membuka halaman pengembang.
5. **Lingkungan uji.** Berisi daftar CA dan TSA uji beserta unduhan sertifikat dan CRL, penanda tangan beserta
   tombol cabut, dan log aktivitas semua akun di perangkat ini (siapa, kapan, berkas, hasil).
6. **Profil.** Data akun, dua kartu sertifikat, dan tombol Keluar.

### Lokasi data

| Data | Lokasi bawaan | Properti |
|---|---|---|
| PKI uji, akun, kunci terenkripsi, riwayat, log aktivitas (`activity.json`) | `%USERPROFILE%\SigningPQC-uji` | `signingpqc.data-dir` |
| Hasil tanda tangan untuk akun baru | `%USERPROFILE%\output-signing` (bisa diubah per akun di Pengaturan) | `signingpqc.default-output-dir` |

Properti bisa diganti saat menjalankan, misalnya
`java -jar app-server\target\app-server-0.1.0-SNAPSHOT.jar --signingpqc.data-dir=D:\uji-data --server.port=18085`.
Bila port backend diganti, sesuaikan juga `proxy` di `frontend/vite.config.ts`.

Untuk memulai dari nol, hentikan backend lalu hapus folder data, atau panggil `POST /api/pki/setup?reset=true`
dengan body `{"organization": "SigningPQC Uji"}`. Reset menghapus PKI dan semua akun. Log aktivitas tetap disimpan.

### Bila ada masalah

| Gejala | Penyebab dan penyelesaian |
|---|---|
| Build berhenti dengan pesan versi Java | `JAVA_HOME` bukan JDK 25. Arahkan ke JDK 25, lalu buka terminal baru. |
| "Server belum berjalan atau tidak bisa dihubungi" di aplikasi | Backend belum jalan atau masih membuat PKI uji. Tunggu sampai log menulis `Started SigningPqcApplication`. |
| `Port 8085 was already in use` | Backend lama masih berjalan. Hentikan dengan `Get-NetTCPConnection -LocalPort 8085` lalu `Stop-Process -Id <OwningProcess>`. |
| Vite memakai port selain 5173 | Port 5173 terpakai. Hentikan proses lama atau buka alamat yang dicetak Vite. |
| Tanda tangan atau unduhan direbut pengelola unduhan (IDM) | Matikan integrasi browser IDM untuk `127.0.0.1` (T-015 dan T-018). |
| Akun terkunci setelah salah berkali-kali | Penguncian sementara per faktor (T-016). Tunggu sesuai pesan, lalu coba lagi. |

### Pengujian

```powershell
cd backend;  .\mvnw.cmd test    # JUnit: signer-core, pki-test, app-server
cd frontend; npm test           # Vitest
cd frontend; npm run build      # pemeriksaan tipe dan build produksi
```

Hasil setiap sesi uji dicatat di `docs/temuan/` (lihat `docs/temuan/README.md`).

## Bukti teknis Sprint 0

`SpikeMain` membuat PDF contoh, menandatangani RSA-3072 lalu ML-DSA-65 (keduanya invisible), memverifikasi,
dan mengekspor CMS ke `backend/spike-output/` untuk dicek silang dengan OpenSSL.

```powershell
cd backend
.\mvnw.cmd -q -pl signer-core compile exec:java
cd ..
powershell -ExecutionPolicy Bypass -File scripts\cek-openssl.ps1
```

## PKI uji (Sprint 1)

`PkiDemoMain` membuat dua hierarki CA uji (RSA: Root RSA-4096, CA antara RSA-3072; ML-DSA: Root ML-DSA-87,
CA antara ML-DSA-65), pasangan sertifikat penanda tangan RSA + ML-DSA dengan identitas yang sama, sertifikat TSA,
satu sertifikat yang dicabut, CRL, berkas PKCS#12, PDF hybrid `02-hybrid-ca.pdf`, serta token timestamp RFC 3161
dari TSA lokal RSA dan ML-DSA (`*-ts.tsq`, `*-ts.tsr`) ke `backend/pki-output/`.
Kata sandi PKCS#12 uji: `uji-signingpqc` (hanya lingkungan uji; folder keluaran tidak di-commit).

```powershell
cd backend
.\mvnw.cmd -B -q -DskipTests install
.\mvnw.cmd -B -q -pl pki-test exec:java
cd ..
powershell -ExecutionPolicy Bypass -File scripts\cek-pki.ps1
```

## REST lokal (Sprint 1)

Backend menyediakan PKI uji, TSA RFC 3161, tanda tangan hybrid, dan verifikasi di `http://127.0.0.1:8085`.
Data PKI uji (PKCS#12 berpassword dan `state.json`) disimpan di `%USERPROFILE%\SigningPQC-uji`.

| Metode dan alamat | Fungsi |
|---|---|
| `GET /api/pki` | Status PKI uji, CA, TSA, dan penanda tangan |
| `POST /api/pki/setup` `{"organization": "..."}` | Membuat CA RSA dan ML-DSA serta TSA (`?reset=true` untuk membuat ulang) |
| `POST /api/pki/signers` `{"commonName", "title", "unit"}` | Menerbitkan pasangan sertifikat RSA + ML-DSA |
| `POST /api/pki/signers/{id}/revoke` | Mencabut kedua sertifikat penanda tangan |
| `GET /api/pki/crl/{caId}.crl`, `GET /api/pki/ca/{caId}.crt`, `.pem` | CRL dan sertifikat CA (`rsa-root`, `rsa-intermediate`, `mldsa-root`, `mldsa-intermediate`) |
| `GET /api/pki/tsa/{id}.crt` | Sertifikat TSA uji (`rsa-g1`, `rsa-g2`, `mldsa`) |
| `POST /api/tsa/rsa`, `POST /api/tsa/mldsa` | TSA RFC 3161 (`application/timestamp-query`) |
| `POST /api/sign` (multipart: `file`, `signerId`, opsional `reason`, `location`, `page`, `x`, `y`, `width`, `height`, `timestamp`, `level`) | Tanda tangan hybrid: RSA visible lalu ML-DSA invisible. `level`: `B-B`, `B-T` (bawaan), `B-LT`, atau `B-LTA` |
| `POST /api/verify` (multipart: `file`, opsional `at`) | Hasil setiap tanda tangan dan `validation`: validasi berlapis pada waktu `at` (ISO-8601, bawaan sekarang) |
| `POST /api/ltv/validation-data` (multipart: `file`, opsional `at`) | Lapisan 3: rantai dan CRL ke kamus DSS |
| `POST /api/ltv/timestamp?tsa=rsa-g1\|rsa-g2\|mldsa` (opsional `at`) | Lapisan 4: document timestamp |
| `POST /api/ltv/renew?tsa=...` (opsional `at`) | Pembaruan: DSS untuk lapisan terakhir, lalu document timestamp baru |

Aplikasi pengguna memakai `/api/auth/**` (daftar, masuk, keluar) dan `/api/me/**` (butuh token sesi: profil,
pengaturan, spesimen, tanda tangan, verifikasi, perpanjangan, riwayat, dan log aktivitas `GET /api/me/activity`).
Rinciannya di `docs/04-User-Flow-SigningPQC.md`.

Coba seluruh alur (backend harus berjalan):

```powershell
powershell -ExecutionPolicy Bypass -File scripts\coba-rest.ps1 -Pdf "backend\spike-output\00-asli.pdf"
```

## Lapisan jangka panjang (Sprint 2)

Dokumen bisa dibungkus berlapis sesuai gambar pembimbing: tanda tangan hybrid B-T (lapisan 1-2), DSS (lapisan 3),
document timestamp TSA RSA G1 (lapisan 4), pembaruan TSA RSA G2 (lapisan 5), dan pembaruan TSA ML-DSA (lapisan 6).
Validator memeriksa setiap lapisan pada waktu yang dibuktikan lapisan di atasnya. Status hasilnya `SAH`, `TIDAK_SAH`,
atau `TIDAK_DAPAT_DIBUKTIKAN`, dengan kode alasan dan batas perlindungan.

`exec:java` (lihat PKI uji di atas) juga membuat `05-hybrid-lt.pdf`, `06-hybrid-lta.pdf`, dan
`07-hybrid-pembaruan-pqc.pdf`, lalu mencetak laporan validasi. `07` tetap sah pada simulasi 2040. `06` tanpa
pembaruan tidak dapat dibuktikan setelah TSA G1 kedaluwarsa. Waktu simulasi dibuat dengan jam TSA dan CRL yang
digeser, dan hanya berlaku untuk TSA uji.

PKI uji dari Sprint 1 belum punya TSA `rsa-g2`. Buat ulang dengan `POST /api/pki/setup?reset=true` untuk memakai
profil jangka panjang: root 30 tahun, CA antara 25 tahun, TSA RSA G1 2 tahun, G2 10 tahun, dan ML-DSA 20 tahun.
Lihat `docs/temuan/T-012-lapisan-lt-lta-dan-pembaruan-tsa-pqc.md`.

## Status

Sprint 0 sampai 2 selesai: bukti tanda tangan ML-DSA di PDF, PKI uji, TSA lokal RFC 3161, PAdES B-T, REST lokal,
uji vektor NIST ACVP, serta lapisan jangka panjang (DSS, document timestamp, pembaruan TSA sampai TSA ML-DSA,
validator berlapis, T-012). Aplikasi pengguna Vue dengan akun, passphrase/TOTP, dan spesimen selesai (T-013 sampai
T-019). Sprint 3 berjalan: halaman Lingkungan uji dan log aktivitas (T-020), TC-05 dengan pyHanko (T-021).
Catatan: Adobe menandai tanda tangan ML-DSA tidak sah karena belum mendukung ML-DSA (keterbatasan yang diterima,
T-011), sedangkan pyHanko 0.38 sudah menerimanya (T-021). Lihat `docs/03-Jadwal-Sprint-SigningPQC.md`.
