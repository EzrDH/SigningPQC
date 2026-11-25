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
| `scripts/` | Skrip bantu (uji silang OpenSSL) |

## Menjalankan

Prasyarat: JDK 25 LTS, Node.js 22+, OpenSSL 3.5 (opsional, untuk uji silang). Maven tidak perlu dipasang, cukup `mvnw`. Pastikan `JAVA_HOME` menunjuk ke JDK 25 (build akan berhenti dengan pesan jelas bila bukan JDK 25).

```powershell
# 1. Backend: uji lalu jalankan REST di http://127.0.0.1:8085
cd backend
.\mvnw.cmd test
.\mvnw.cmd -pl app-server -am spring-boot:run

# 2. Frontend (terminal lain): http://127.0.0.1:5173
cd frontend
npm install
npm run dev
```

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
| `GET /api/pki/crl/{caId}.crl`, `GET /api/pki/ca/{caId}.crt` | CRL dan sertifikat CA (`rsa-root`, `rsa-intermediate`, `mldsa-root`, `mldsa-intermediate`) |
| `POST /api/tsa/rsa`, `POST /api/tsa/mldsa` | TSA RFC 3161 (`application/timestamp-query`) |
| `POST /api/sign` (multipart: `file`, `signerId`, opsional `reason`, `location`, `page`, `x`, `y`, `width`, `height`, `timestamp`) | Tanda tangan hybrid: RSA visible lalu ML-DSA invisible, keduanya B-T |
| `POST /api/verify` (multipart: `file`) | Hasil verifikasi setiap tanda tangan |

Coba seluruh alur (backend harus berjalan):

```powershell
powershell -ExecutionPolicy Bypass -File scripts\coba-rest.ps1 -Pdf "backend\spike-output\00-asli.pdf"
```

## Status

Sprint 0 selesai (kerangka proyek dan bukti tanda tangan ML-DSA di PDF). Sprint 1 selesai: PKI uji, TSA lokal RFC 3161, PAdES B-T, tanda tangan RSA visible, uji vektor NIST ACVP, REST lokal, dan uji ulang di PdfCraft/Adobe (T-011). Catatan: Adobe menandai tanda tangan ML-DSA tidak sah karena belum mendukung ML-DSA; ini keterbatasan yang diterima. Lihat `docs/03-Jadwal-Sprint-SigningPQC.md`.
