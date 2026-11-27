# T-023: Folder keluaran diperiksa ulang saat menyimpan (tindak lanjut T-016)

| Butir | Isi |
|---|---|
| Tanggal | 27 Nov 2026 |
| Jenis | Temuan keamanan dan perbaikan |
| Sprint | 3 |
| Kebutuhan terkait | Tindak lanjut T-016 (folder keluaran), KF-18 (penyimpanan hasil) |
| Lingkungan | Container Linux, Temurin JDK 25.0.1, Spring Boot 4.2 |
| Status | Diperbaiki, uji lolos |

## Temuan
T-016 menolak folder keluaran di dalam folder data, tetapi hanya saat Pengaturan disimpan. Bila folder yang sudah
disetujui kemudian diganti tautan simbolik (Linux/macOS) atau junction (Windows) ke folder data, tanda tangan dan
perpanjangan tetap menulis `-ttd.pdf` ke folder data. Akun lama yang folder keluarannya tersimpan sebelum T-016 juga
tidak pernah diperiksa.

Uji `OutputFolderGuardApiTest` membuktikan celah ini sebelum perbaikan: setelah folder `pindah` diganti tautan ke
folder data, `POST /api/me/sign` menjawab 200 dan `savedPath` menunjuk `.../pindah/jaga-ttd.pdf`, yang secara nyata
berada di folder data.

## Perbaikan
- `OutputFolder.requireOutside(dir, dataDir)` membandingkan path nyata (`toRealPath` pada bagian yang sudah ada) dan
  menolak dengan **409** "Folder keluaran tidak boleh berada di dalam folder data aplikasi. Ubah lokasi penyimpanan di
  Pengaturan."
- Dipanggil di `/api/me/sign` **sebelum** kunci dibuka, sehingga kode TOTP atau passphrase tidak terpakai untuk tanda
  tangan yang tidak bisa disimpan.
- `OutputFolder.save` sekarang menerima folder data dan memeriksa ulang sebelum dan sesudah `createDirectories`. Ini
  juga melindungi `/api/me/renew`.
- Logika path nyata dipindah dari `MeController` ke `OutputFolder` agar Pengaturan dan penyimpanan memakai aturan
  yang sama.
- Kegagalan ini ikut tercatat di log aktivitas (T-020) sebagai "Menandatangani, Gagal".

## Hasil
| Uji | Hasil |
|---|---|
| `OutputFolderGuardApiTest` sebelum perbaikan | Gagal: 200, berkas tertulis lewat tautan ke folder data |
| `OutputFolderGuardApiTest` sesudah perbaikan | Lulus: 409, tidak ada PDF baru di folder data; setelah tautan diganti folder biasa, tanda tangan kembali 200 dan `jaga-ttd.pdf` tersimpan |
| Seluruh uji `app-server` | 79 lulus, 0 gagal (termasuk `AccountApiTest.folderKeluaranDiDalamFolderDataDitolak` dari T-016) |

## Keterbatasan
- Masih ada jeda singkat antara pemeriksaan dan penulisan (TOCTOU). Untuk aplikasi desktop satu pengguna di
  127.0.0.1 dampaknya kecil: yang bisa ditulis hanya PDF baru dengan `CREATE_NEW`, tanpa menimpa berkas.
- Uji memakai tautan simbolik Linux. Junction Windows diselesaikan `toRealPath` dengan cara yang sama, tetapi belum
  diuji langsung di laptop.
