# T-014: Spesimen pada halaman PDF berotasi

| Butir | Isi |
|---|---|
| Tanggal | 26 Nov 2026 |
| Jenis | Temuan (bug) dan perbaikan |
| Sprint | 3 (aplikasi pengguna) |
| Kebutuhan terkait | Rencana `docs/superpowers/plans/2026-11-26-aplikasi-pengguna.md`, Review Focus 4 dan Task 12 |
| Lingkungan | Container Linux, OpenJDK 21 (`--release 21`), PDFBox 3.0.8, Vitest 5 |
| Versi kode | branch `aplikasi-pengguna`, setelah commit `f00c208` |
| Status | Diperbaiki, uji lolos |

## Ringkasan
Halaman PDF bisa punya atribut `/Rotate` 90, 180, atau 270 derajat. Ini umum pada hasil pindai lanskap. Sebelum perbaikan, `signer-core` mengabaikan atribut itu. Akibatnya ada tiga masalah pada halaman berotasi 90 atau 270:

1. Kotak yang di layar berukuran 180 x 75 menjadi 75 x 180 di koordinat halaman asli, lalu ditolak dengan pesan "Kotak tanda tangan minimal 120 x 60 point".
2. Kotak yang lolos digambar miring 90 derajat di layar, karena isi kotak tidak ikut diputar.
3. Posisi bawaan (bila klien tidak mengirim koordinat) dihitung di kanan bawah halaman asli. Setelah halaman diputar, posisi itu tampil di pojok lain.

Posisi kotak sudah benar sejak awal, karena frontend mengonversinya ke koordinat halaman asli. Masalahnya ada pada ukuran minimal, orientasi isi, dan posisi bawaan.

## Cara kerja konversi
Frontend (`frontend/src/lib/coords.ts`) mengubah kotak di layar ke koordinat halaman tanpa rotasi. Untuk titik tampilan `(dx, dy)` dan halaman asli `W x H`, titik halaman `(u, v)` dengan `v` diukur dari atas:

| Rotasi | u | v |
|---|---|---|
| 0 | dx | dy |
| 90 | dy | H - dx |
| 180 | W - dx | H - dy |
| 270 | W - dy | dx |

Backend memakai rumus yang sama untuk posisi bawaan (`SigningService.placementOf`).

## Perbaikan
- **`SignatureAppearance`:**
  - Isi kotak digambar dalam ukuran yang tampil di layar. Pada rotasi 90 dan 270, lebar dan tinggi ditukar.
  - `Matrix` appearance stream diisi `AffineTransform.getQuadrantRotateInstance(rotasi / 90)`. Matrix ini memutar isi berlawanan arah `/Rotate`, sehingga setelah penampil memutar halaman, isi tampil tegak.
  - Penampil memetakan BBox yang sudah diputar ke `Rect` widget (ISO 32000-1 12.5.5). Karena itu ukurannya pas tanpa skala tambahan.
- **Ukuran minimal 120 x 60 point:**
  - Kini diperiksa terhadap ukuran yang tampil, di `SignatureAppearance.rectangle`, karena hanya bagian itu yang tahu rotasi halaman.
  - Konstruktor `VisibleSignature` menerima kedua orientasi.
- **Batas atas halaman:** kotak dengan `yFromTop` negatif kini ditolak. Sebelumnya hanya batas kiri, kanan, dan bawah yang diperiksa.
- **Posisi bawaan:** `SigningService.placement` menghitung 180 x 75 point di kanan bawah halaman sebagaimana tampil, lalu mengonversinya.

## Uji
| Uji | Hasil |
|---|---|
| `coords.test.ts`: rotasi 0, 90, 180, 270, kebalikan `pdfToScreen`, kotak bawaan Letter | 6 lolos |
| `VisibleSignatureTest.halamanBerotasiSpesimenTampilTegakDiPosisiYangDipilih` (rotasi 90 dan 270) | Lolos |
| `VisibleSignatureTest.halamanBerotasiUkuranMinimalMengikutiTampilan` | Lolos |
| `VisibleSignatureTest.posisiDiAtasTepiHalamanDitolak` | Lolos |
| `PlacementTest` (bawaan tegak, bawaan rotasi 90, koordinat klien) | 3 lolos |

Uji rotasi memakai spesimen 400 x 160 piksel yang separuh kirinya hitam. Halaman dirender PDFBox dengan rotasinya, lalu diperiksa tiga hal:
- Bidang hitam berukuran 90 x 72 piksel. Bila spesimen masih miring, ukurannya 30 x 75.
- Bidang hitam mulai di x = 500, tepi kiri kotak yang dipilih. Bila spesimen terbalik, bidang hitam ada di separuh kanan kotak.
- Bidang hitam berada di dalam kotak secara vertikal.

Sebelum perbaikan, ketiga uji `VisibleSignatureTest` baru gagal. Uji rotasi gagal dengan `IllegalArgumentException: Kotak tanda tangan minimal 120 x 60 point`.

## Analisis
Pembaca PDF memutar seluruh halaman, termasuk anotasi, menurut `/Rotate`. Isi appearance stream tidak otomatis "tegak". Pembuat tanda tanganlah yang harus memutar isinya. Contoh `CreateVisibleSignature2` di PDFBox memakai pola yang sama.

Halaman 180 derajat tidak menukar ukuran, tetapi tanpa Matrix isinya tampil terbalik. Karena itu Matrix juga dipasang untuk rotasi 180.

## Keterbatasan yang tersisa
- Frontend memakai ukuran halaman dari pdf.js (`page.view`, yaitu CropBox), sedangkan backend memakai MediaBox. Pada PDF yang CropBox-nya lebih kecil atau bergeser dari MediaBox, posisi bisa meleset sebesar selisihnya. PDF hasil aplikasi kantor umumnya tidak punya CropBox berbeda.
- Hasil visual di Adobe Acrobat Reader belum dicek. Rencananya dicek bersama tangkapan layar T-012.

## Tindak lanjut
Uji manual di Adobe Reader dengan satu PDF lanskap hasil pindai (rotasi 90) dilakukan pada tahap uji laptop.
