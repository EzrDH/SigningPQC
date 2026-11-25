# SigningPQC

Purwarupa aplikasi penandatanganan PDF **hybrid RSA + ML-DSA** (FIPS 204) untuk PKL di BSrE.
Tanda tangan RSA tampil di halaman, tanda tangan ML-DSA disematkan tersembunyi (invisible) dan menutup
seluruh dokumen. Format mengikuti PAdES (ETSI EN 319 142), CMS (RFC 5652, RFC 9882), dan timestamp RFC 3161.

> Lingkungan uji. Semua sertifikat, kunci, dan TSA adalah buatan sendiri. Jangan memakai data asli.

## Struktur

| Folder | Isi |
|---|---|
| `docs/` | PRD, dokumen kebutuhan, jadwal sprint |
| `docs/temuan/` | Catatan temuan dan hasil pengujian (T-001, T-002, ...) |
| `backend/` | Java 21, Maven multi-modul: `signer-core` (Bouncy Castle 1.87, PDFBox 3.0.8), `app-server` (Spring Boot 4.1, REST lokal) |
| `frontend/` | Vue 3 + Vite + TypeScript |
| `scripts/` | Skrip bantu (uji silang OpenSSL) |

## Menjalankan

Prasyarat: JDK 21, Node.js 22+, OpenSSL 3.5 (opsional, untuk uji silang). Maven tidak perlu dipasang, cukup `mvnw`.

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

## Status

Sprint 0 (25 sampai 27 Nov 2026): kerangka proyek dan bukti tanda tangan ML-DSA di PDF. Lihat `docs/03-Jadwal-Sprint-SigningPQC.md`.
