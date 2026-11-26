# T-015: Uji antarmuka aplikasi pengguna (Vue)

| Butir | Isi |
|---|---|
| Tanggal | 26 Nov 2026 |
| Jenis | Pengujian |
| Sprint | 3 (aplikasi pengguna) |
| Kebutuhan terkait | Spesifikasi `docs/superpowers/specs/2026-11-26-aplikasi-pengguna-design.md` bagian 5, mockup versi 7 |
| Lingkungan | Container Linux. Backend: jar `app-server` (OpenJDK 21, `--release 21`) dengan `signingpqc.data-dir` dan folder keluaran sementara. Frontend: Node 22, Vite 8, Vue 3.5, pdf.js 6.5, Vitest 5. Browser: Chromium headless lewat Playwright, jendela 1280 x 860. |
| Versi kode | branch `aplikasi-pengguna`, commit `461d6ba` sampai commit Task 15 |
| Status | Lulus. Dua bug ditemukan dan diperbaiki (T-014 dan bagian Temuan di bawah). |

## Ringkasan
Delapan layar sudah dibuat sesuai mockup versi 7: Masuk, Daftar, Beranda, Unggah, Tandatangani, Verifikasi, Pengaturan, dan Profil. Semua alur diuji di browser terhadap backend nyata dengan data sintetis.

Alur yang diuji: daftar, masuk, tanda tangan Visible dan Invisible, verifikasi dan detail, ganti metode konfirmasi, ganti folder keluaran, profil, dan keluar.

Uji ini menemukan dua bug:
- Kode konfirmasi yang salah membuat pengguna ter-logout.
- Spesimen pada halaman berotasi tidak benar (dicatat terpisah di T-014).

Keduanya sudah diperbaiki dengan uji yang gagal lebih dulu. Setelah review akhir dan perbaikannya, seluruh 39 uji Vitest dan 141 uji backend lolos.

## Uji otomatis (Vitest)
| Berkas | Isi | Jumlah |
|---|---|---|
| `api/client.test.ts` | Pesan galat dari JSON, token Bearer, 401 sesi vs 401 konfirmasi salah | 6 |
| `router/guard.test.ts` | Tanpa sesi ke Masuk, halaman publik saat sudah masuk ke Beranda, selain itu lewat | 3 |
| `components/OtpInput.test.ts` | Enam kotak, hanya angka, tempel 6 digit, tempel berspasi | 4 |
| `lib/validasi.test.ts` | Aturan dan pesan sama dengan backend | 8 |
| `lib/coords.test.ts` | Rotasi 0, 90, 180, 270, kebalikan, kotak bawaan Letter, kotak rapat tepi pada halaman berukuran desimal panjang | 9 |
| `lib/status.test.ts` | Status riwayat, lima respons verifikasi, perpanjangan | 8 |
| `stores/auth.test.ts` | Sesi berakhir mengosongkan dokumen dan pengaturan pengguna sebelumnya | 1 |
| **Total** | | **39 lolos** |

`npm run build` (vue-tsc dan vite) lolos tanpa galat tipe.

## Uji manual di browser
Skrip ada di `frontend/e2e/`. PDF uji dibuat sintetis oleh `buat-pdf-uji.py`: A4 tegak dan A4 dengan `/Rotate 90`.

| No | Langkah | Hasil |
|---|---|---|
| 1 | Daftar dengan password 6 karakter | Ditolak di klien: "Password minimal 8 karakter" |
| 2 | Ulangi passphrase berbeda | Ditolak: "Passphrase dan ulangannya tidak sama" |
| 3 | Daftar sampai kode TOTP benar | Akun dibuat, langsung masuk ke Beranda |
| 4 | Riwayat akun baru | "Belum ada riwayat..." |
| 5 | Unggah spesimen, tanda tangan Visible A4, seret spesimen | Tersimpan `surat-uji-ttd.pdf` |
| 6 | Kode TOTP salah di dialog | "Konfirmasi tidak cocok", dialog tetap terbuka, pengguna tetap masuk |
| 7 | Tanda tangan Visible pada halaman rotasi 90 | Tersimpan `pindai-lanskap-ttd.pdf`, spesimen tegak |
| 8 | Posisi spesimen hasil render `pdftoppm` dibanding kotak di layar | Di dalam kotak, untuk A4 dan rotasi 90 |
| 9 | Verifikasi dokumen hasil langkah 5 | "Dokumen sah", empat butir, berlaku sampai 25 November 2028 |
| 10 | Lihat detail, tab ML-DSA | Sertifikat ML-DSA-65, rantai 3 tingkat ke root tepercaya, timestamp TSA ML-DSA |
| 11 | Verifikasi dokumen yang diubah 1 byte | "Dokumen tidak sah: Isi dokumen berubah setelah ditandatangani" |
| 12 | Verifikasi PDF tanpa tanda tangan | "Dokumen ini tidak memiliki tanda tangan elektronik" |
| 13 | Ganti ke Invisible lalu tanda tangan | Tidak ada kotak spesimen. Tersimpan dengan akhiran `-ttd-N` karena nama sudah ada; berkas lama tidak tertimpa. |
| 14 | Ganti metode ke Passphrase, lalu Batal | Pilihan kembali ke TOTP |
| 15 | Ganti metode dengan passphrase salah, lalu benar | "Konfirmasi tidak cocok", lalu tersimpan |
| 16 | Ganti folder ke path relatif, lalu absolut | Relatif ditolak dengan contoh path lengkap; absolut diterima |
| 17 | Tanda tangan dengan passphrase | Tersimpan di folder baru |
| 18 | Profil | Inisial, data akun, dua kartu sertifikat Aktif |
| 19 | Keluar, lalu buka `#/profil` | Kembali ke Masuk, token sesi terhapus |
| 20 | Masuk lagi dan buka riwayat | Dua entri riwayat akun ini |

Tidak ada galat JavaScript di halaman. Satu-satunya galat konsol adalah respons 401 yang memang diharapkan dari kode salah di langkah 6.

### Tangkapan layar
| Layar | Berkas |
|---|---|
| Masuk | ![Masuk](img/T-015-01-masuk.png) |
| Daftar langkah 1, 2, 3 (QR diburamkan) | ![Daftar 1](img/T-015-02-daftar-1.png) ![Daftar 2](img/T-015-03-daftar-2.png) ![Daftar 3](img/T-015-04-daftar-3-qr.png) |
| Beranda | ![Beranda](img/T-015-05-beranda.png) |
| Unggah | ![Unggah](img/T-015-06-unggah.png) |
| Tandatangani A4 dan rotasi 90 | ![A4](img/T-015-07-tandatangani-a4.png) ![Rotasi 90](img/T-015-07-tandatangani-rotasi90.png) |
| Kode salah | ![Kode salah](img/T-015-08-kode-salah.png) |
| Berhasil | ![Berhasil](img/T-015-09-berhasil-a4.png) |
| Riwayat | ![Riwayat](img/T-015-10-riwayat.png) |
| Verifikasi sah, diubah, tanpa tanda tangan | ![Sah](img/T-015-11-verifikasi-sah.png) ![Diubah](img/T-015-11-verifikasi-diubah.png) ![Tanpa](img/T-015-11-verifikasi-tanpa-ttd.png) |
| Detail ML-DSA | ![Ringkasan](img/T-015-12-detail-mldsa-ringkasan.png) ![Sertifikat](img/T-015-13-detail-mldsa-sertifikat.png) ![Timestamp](img/T-015-14-detail-mldsa-timestamp.png) |
| Pengaturan awal dan setelah diubah | ![Awal](img/T-015-15-pengaturan-awal.png) ![Diubah](img/T-015-18-pengaturan-passphrase-folder.png) |
| Invisible | ![Invisible](img/T-015-16-tandatangani-invisible.png) |
| Ganti metode, passphrase salah | ![Salah](img/T-015-17-ganti-metode-salah.png) |
| Profil | ![Profil](img/T-015-19-profil.png) |

## Temuan

### 1. Kode konfirmasi salah membuat pengguna ter-logout (diperbaiki)
- **Gejala.** Pada uji langkah 6, setelah kode salah dimasukkan, aplikasi langsung kembali ke layar Masuk. Pesan "Konfirmasi tidak cocok" tidak pernah tampil.
- **Penyebab.** Backend memakai HTTP 401 untuk dua hal berbeda: sesi berakhir dan konfirmasi salah. Klien menganggap semua 401 sebagai sesi berakhir.
- **Perbaikan.**
  - `AuthInterceptor` kini mengirim header `WWW-Authenticate: Bearer` untuk sesi yang tidak sah, sesuai RFC 6750.
  - Klien hanya mengakhiri sesi bila header itu ada.
- **Uji.**
  - Uji klien baru "401 konfirmasi salah (tanpa WWW-Authenticate) tidak mengakhiri sesi" gagal sebelum perbaikan dan lolos sesudahnya.
  - `AccountApiTest` memeriksa header ada pada 401 sesi dan tidak ada pada 401 konfirmasi salah.

### 2. Spesimen pada halaman berotasi, kotak di tepi halaman, dan CropBox (diperbaiki)
Lihat T-014, termasuk bagian "Perbaikan setelah review akhir".

### 3. Data pengguna sebelumnya tetap ada setelah sesi berakhir (diperbaiki, dari review akhir)
- **Gejala.** Bila sesi berakhir karena waktu habis, dokumen yang sedang dibuka dan pengaturan akun tetap tersimpan di memori halaman. Orang berikutnya yang masuk di jendela yang sama bisa membuka dokumen itu lewat layar Tandatangani.
- **Perbaikan.** `useAuth().clear()` kini juga mengosongkan store dokumen dan pengaturan. Fungsi ini dipakai saat keluar maupun saat sesi berakhir.
- **Uji.** `stores/auth.test.ts` gagal sebelum perbaikan.

### 4. CSS mockup yang terbuang saat porting (diperbaiki)
Aturan `.viewer` dan `.av` sempat hilang karena saringan kelas kerangka mockup ikut membuang nama yang berawalan sama. Gejalanya: latar penampil tidak tampil dan nama berkas tidak sejajar halaman. Tangkapan layar membantu menemukannya, lalu kedua aturan dipulihkan.

## Keterbatasan
- **Perpanjangan keabsahan belum diuji di browser.** Tombol hanya muncul bila `renewalRecommended`, yaitu bila masa keabsahan kurang dari 30 hari. Kondisi itu butuh simulasi waktu. Logika backend sudah diuji di `RenewApiTest`, dan logika tampilan diuji di `status.test.ts`.
- **Pilih folder memakai isian path.** Versi web belum punya dialog pilih folder. Electron (Sprint 4) bisa memakai dialog folder sistem.
- **Belum diuji di laptop.** Uji ini berjalan di container dengan JDK 21. Uji JDK 25 dan uji di laptop Windows menyusul.
- **Mode gelap tidak diuji.** Mode gelap tidak wajib menurut spesifikasi.

## Tindak lanjut
- Review akhir seluruh cabang `aplikasi-pengguna`.
- Uji JDK 25 di laptop (`.\mvnw.cmd -B test`) dan coba aplikasi langsung di Windows.
- Cek tampilan spesimen di Adobe Acrobat Reader untuk halaman tegak dan berotasi (T-014), bersama tangkapan layar T-012.
