# Frontend SigningPQC

Aplikasi pengguna untuk tanda tangan elektronik hybrid (RSA lalu ML-DSA). Dibangun dengan Vue 3, Vite, dan
TypeScript. Penampil PDF memakai pdf.js. Tidak ada pustaka komponen UI; gaya diambil dari mockup versi 7
(`docs/superpowers/specs/2026-11-26-aplikasi-pengguna-mockup.html`).

Hanya untuk lingkungan uji dengan data sintetis.

## Menjalankan

1. Jalankan backend (port 127.0.0.1:8085) dari folder `backend`:

   ```bash
   .\mvnw.cmd -pl app-server -am spring-boot:run          # Windows, JDK 25
   # atau jar hasil build:
   java -jar app-server/target/app-server-0.1.0-SNAPSHOT.jar
   ```

   PKI uji dibuat otomatis saat pertama kali jalan (`signingpqc.pki.auto-setup=true`). Data akun dan kunci ada di
   `signingpqc.data-dir` (bawaan `~/SigningPQC-uji`). Hasil tanda tangan disimpan di folder keluaran yang diatur di
   layar Pengaturan (bawaan `~/output-signing`).

2. Jalankan frontend:

   ```bash
   npm install
   npm run dev     # http://127.0.0.1:5173, /api diteruskan ke backend 127.0.0.1:8085
   ```

3. Buka http://127.0.0.1:5173, pilih Daftar, lalu pindai QR dengan Google Authenticator atau aplikasi sejenis.

## Perintah

| Perintah | Fungsi |
|---|---|
| `npm run dev` | Server pengembangan dengan proxy `/api` |
| `npm test` | Uji unit Vitest (klien API, router, kotak OTP, validasi, konversi koordinat, status verifikasi) |
| `npm run build` | Pemeriksaan tipe `vue-tsc` lalu build ke `dist/` |

## Struktur

| Folder | Isi |
|---|---|
| `src/api` | Klien fetch (token Bearer, galat JSON) dan tipe respons backend |
| `src/stores` | Sesi (`sessionStorage`), dokumen yang sedang dikerjakan, pengaturan akun |
| `src/lib` | Logika murni yang diuji: `coords.ts` (layar ke koordinat PDF, termasuk halaman berotasi), `status.ts` (hasil verifikasi ke bahasa pengguna), `validasi.ts`, `pki.ts` (nama CA/TSA, masa berlaku, hasil log aktivitas) |
| `src/components` | Topbar, kotak OTP, area unggah, penampil PDF, kotak spesimen, dialog konfirmasi, hasil dan detail verifikasi |
| `src/views` | Masuk, Daftar, Beranda, Unggah, Tandatangani, Verifikasi, Pengaturan, Lingkungan uji, Profil |
| `e2e` | Skrip uji manual Playwright (lihat di bawah) |

## Uji ujung ke ujung (manual)

Skrip di `e2e/` menjalankan alur nyata di Chromium headless terhadap backend dan `npm run dev` yang sedang jalan.
Skrip ini tidak ikut `npm test` dan butuh Playwright terpasang.

```bash
python3 e2e/buat-pdf-uji.py /tmp/e2e/files          # dua PDF sintetis: A4 tegak dan A4 /Rotate 90
node e2e/alur-tandatangani.cjs <folder-tangkapan>   # daftar, spesimen, tanda tangan, kode salah
node e2e/alur-verifikasi.cjs <folder-tangkapan>     # sah, diubah, tanpa tanda tangan
node e2e/alur-pengaturan-profil.cjs <folder-tangkapan> <folder-keluaran-baru>
```

Hasil dan tangkapan layar terakhir dicatat di `docs/temuan/T-015-antarmuka-pengguna.md`.
