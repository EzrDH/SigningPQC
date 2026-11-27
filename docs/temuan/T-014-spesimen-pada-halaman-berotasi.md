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
- ~~Frontend memakai CropBox, backend memakai MediaBox.~~ Sudah diperbaiki setelah review akhir: backend kini juga mengukur koordinat dari pojok kiri atas CropBox (lihat bagian "Perbaikan setelah review akhir").
- ~~Hasil visual di Adobe Acrobat Reader belum dicek.~~ Sudah dicek 27 Nov 2026: spesimen tampil tegak di halaman rotasi 90 (T-024, berkas G). Rencananya dicek bersama tangkapan layar T-012. Uji browser T-015 sudah memastikan posisi dan orientasi spesimen pada render pdftoppm (Poppler) untuk halaman rotasi 90.

## Perbaikan setelah review akhir
Review akhir seluruh cabang menemukan dua masalah lain yang masih terkait posisi kotak. Keduanya sudah diperbaiki, dan setiap perbaikan diawali uji yang gagal.

1. **Kotak yang rapat ke tepi kanan atau bawah ditolak setelah kode TOTP terpakai.**
   - Penyebab: pada halaman berukuran desimal panjang, misalnya A4 buatan ReportLab (595.2755905511812 pt), frontend membulatkan x dan lebar sendiri-sendiri ke 3 desimal. Hasilnya x + lebar = 595.276, sedikit melewati tepi halaman.
   - Penyebab kedua: pemeriksaan batas di backend baru berjalan saat menandatangani, sesudah kunci dibuka. Kode TOTP sudah terpakai, lalu pengguna mendapat pesan "Kotak tanda tangan keluar dari halaman".
   - Perbaikan:
     - `coords.ts` membulatkan tepi dekat ke atas dan tepi jauh ke bawah, dan tidak pernah melewati ukuran halaman.
     - Backend menerima selisih pembulatan sampai 0,5 pt lalu merapatkan kotak ke dalam halaman.
     - `SigningService.placementOf` menjalankan pemeriksaan lengkap (`VisibleSignature.requireFits`: batas halaman dan ukuran minimal sesuai rotasi) sebelum kunci dibuka.
   - Uji: `coords.test.ts` (3 skala), `VisibleSignatureTest.kotakRapatTepiPadaHalamanDesimalPanjangDiterima`, `PlacementTest.kotakTidakMuatDitolakSebelumMembukaKunci`.
2. **PDF yang CropBox-nya berbeda dari MediaBox.**
   - Penyebab: pdf.js menampilkan area CropBox, tetapi backend mengukur dari MediaBox. Untuk MediaBox [0 0 612 792] dengan CropBox [36 36 576 756], tanda tangan bergeser 36 pt ke kiri dan ke atas.
   - Perbaikan: `SignatureAppearance.rectangle` dan `placementOf` kini mengukur dari pojok kiri atas CropBox (`getCropBox()`, yang sudah dipotong ke MediaBox) dan memeriksa batas terhadap CropBox.
   - Uji: `VisibleSignatureTest.koordinatMengikutiCropBox` dan `PlacementTest.bawaanMengikutiCropBox`.

## Uji ulang di laptop (JDK 25)
Pada 26 November 2026 seluruh uji backend dijalankan ulang di laptop Windows dengan Eclipse Temurin 25.0.5.1+2 LTS. Hasilnya 141 uji lolos, termasuk uji rotasi 90/270, uji kotak di tepi halaman, dan uji CropBox di `VisibleSignatureTest` serta `PlacementTest`.

## Tindak lanjut
Uji manual di Adobe Acrobat Reader masih perlu dilakukan dengan satu PDF lanskap (rotasi 90) dan satu PDF tegak. Yang dicek: spesimen tampil tegak, posisinya sama dengan pratinjau, dan panel tanda tangan menampilkan dua tanda tangan. Sertakan tangkapan layarnya di catatan ini.
