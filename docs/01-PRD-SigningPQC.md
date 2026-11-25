# PRD SigningPQC

25 November 2026 · Versi 0.3 (frontend Vue, backend Java, target on lokus 18 Des) · Repositori: https://github.com/EzrDH/SigningPQC

## 1. Latar belakang

Tanda tangan elektronik saat ini memakai RSA, yang nantinya bisa dipecahkan oleh komputer kuantum. NIST sudah menetapkan ML-DSA (FIPS 204) sebagai penggantinya, tetapi pembaca PDF seperti Adobe Reader belum bisa memverifikasi tanda tangan ML-DSA. Karena itu dibutuhkan masa transisi: dokumen ditandatangani dengan RSA (supaya tetap terbaca aplikasi yang ada) dan ML-DSA (supaya tetap aman di masa depan) secara bersamaan.

## 2. Tujuan

1. Menandatangani PDF dengan dua sertifikat sekaligus: RSA yang tampil di halaman dan ML-DSA yang tersembunyi (invisible).
2. Membuktikan dokumen tetap bisa dibuka dan tanda tangan RSA tetap sah di Adobe Reader.
3. Memverifikasi kedua tanda tangan di aplikasi sendiri, selama pembaca PDF umum belum mendukung ML-DSA.
4. Menjaga dokumen tetap bisa diverifikasi dalam jangka panjang dengan memperbarui penanda waktu (timestamp) sebelum sertifikat TSA lama kedaluwarsa.

## Target waktu

Prototipe Vue + Java dengan alur TTE hybrid yang berfungsi benar (tanda tangan, timestamp, LTV, verifikasi) selesai pada akhir on lokus, Jumat 18 Desember 2026. Pembaruan TSA saat kedaluwarsa dan pembungkus desktop Electron diselesaikan di periode off lokus.

## 3. Di luar cakupan

- Tidak terhubung ke sistem produksi BSrE. Semua sertifikat dan TSA adalah buatan sendiri untuk pengujian.
- Tidak ada manajemen kunci tingkat produksi (HSM, keystore terpusat). Kunci uji disimpan di berkas PKCS#12 lokal.
- Tidak membuat layanan web publik. REST backend hanya untuk komunikasi lokal di dalam aplikasi desktop.
- Sertifikat komposit (RFC 10073) hanya opsional bila waktu tersisa.

## 4. Pengguna

| Pengguna | Kebutuhan |
|---|---|
| Penanda tangan (misalnya pejabat instansi) | Menandatangani PDF dengan mudah tanpa memahami detail kriptografi |
| Penerima atau verifikator dokumen | Mengetahui apakah tanda tangan RSA dan ML-DSA sah serta kapan ditandatangani |
| Admin arsip dokumen | Mengetahui dokumen mana yang timestamp-nya akan kedaluwarsa, lalu memperbaruinya |
| Tim pengembang BSrE | Melihat contoh struktur dokumen hybrid untuk bahan migrasi |

## 5. Cara kerja singkat

1. Pengguna membuka PDF di aplikasi.
2. Pengguna memilih pasangan sertifikat (RSA dan ML-DSA) dan posisi kotak tanda tangan RSA.
3. Aplikasi menambahkan tanda tangan RSA yang tampil di halaman, lengkap dengan timestamp dari TSA RSA.
4. Aplikasi menambahkan tanda tangan ML-DSA yang tersembunyi dan menutup seluruh isi dokumen, termasuk tanda tangan RSA, lengkap dengan timestamp dari TSA ML-DSA.
5. Aplikasi menyematkan data validasi (rantai sertifikat dan status pencabutan) lalu menambahkan document timestamp.
6. Aplikasi menampilkan hasil verifikasi: RSA, ML-DSA, timestamp, dan status gabungan.
7. Ketika sertifikat TSA terakhir mendekati masa kedaluwarsa, aplikasi memberi peringatan dan pengguna menekan tombol Perbarui timestamp.

## 6. Struktur dokumen bertanda tangan

| Lapisan | Isi | Terlihat? |
|---|---|---|
| PDF asli | Isi dokumen | Ya |
| Tanda tangan 1 | RSA-3072, sertifikat RSA, timestamp TSA RSA | Ya, kotak di halaman |
| Tanda tangan 2 | ML-DSA-65, sertifikat ML-DSA, timestamp TSA ML-DSA | Tidak (invisible) |
| Data validasi (DSS) | Rantai sertifikat, CRL atau OCSP untuk semua tanda tangan dan TSA | Tidak |
| Document timestamp | Timestamp atas seluruh dokumen dari TSA ML-DSA | Tidak |
| Pembaruan berikutnya | Data validasi TSA lama dan document timestamp baru | Tidak |

Setiap lapisan ditambahkan sebagai incremental update, jadi isi sebelumnya tidak pernah diubah. Formatnya mengikuti PAdES (ETSI EN 319 142) sampai tingkat B-LTA.

Dokumen dinyatakan sah hybrid hanya jika tanda tangan RSA dan ML-DSA keduanya sah.

## 7. Fitur utama

| Prioritas | Fitur |
|---|---|
| Wajib | Membuat CA uji RSA dan ML-DSA, serta sertifikat penanda tangan berpasangan |
| Wajib | TSA uji RSA dan ML-DSA (RFC 3161) |
| Wajib | Tanda tangan hybrid: RSA visible lalu ML-DSA invisible |
| Wajib | Verifikasi kedua tanda tangan dan timestamp |
| Wajib | Data validasi jangka panjang (B-LT) dan document timestamp (B-LTA) |
| Wajib | Peringatan kedaluwarsa dan pembaruan timestamp |
| Wajib | Aplikasi desktop (Vue + Electron) dengan penampil PDF |
| Sebaiknya | Laporan verifikasi yang bisa diekspor (PDF atau teks) |
| Sebaiknya | Pengukuran ukuran dan waktu dibanding RSA saja |
| Opsional | Pembaruan timestamp sekaligus untuk satu folder |
| Opsional | Sertifikat komposit ML-DSA + RSA sebagai pembanding |
| Opsional | Installer Windows (.msi) |

Rincian fitur ada di dokumen kebutuhan (02-Kebutuhan-SigningPQC.md).

## 8. Teknologi

Frontend memakai Vue agar sejalan dengan teknologi yang dipakai BSrE, dan backend memakai Java dengan Bouncy Castle. Keduanya dibungkus menjadi satu aplikasi desktop dengan Electron.

| Bagian | Pilihan |
|---|---|
| Frontend | Vue 3 + Vite + TypeScript, Pinia (state), Vue Router |
| Penampil PDF | pdf.js (pdfjs-dist) di Vue |
| Backend | Java 25 LTS + Spring Boot 4.2, REST lokal di 127.0.0.1 |
| Kriptografi | Bouncy Castle 1.87 (bcprov, bcpkix) |
| PDF di backend | Apache PDFBox 3.0.8 (penyisipan tanda tangan, DSS, document timestamp) |
| Pembungkus desktop | Electron: menjalankan backend Java (JRE hasil jlink) dan menampilkan Vue |
| CLI uji | picocli, untuk demo Sprint 1 sampai 3 sebelum antarmuka jadi |
| Build | Maven (backend), npm (frontend dan Electron) |
| Uji | JUnit 5, vektor NIST ACVP, Vitest (frontend), OpenSSL 3.5, Adobe Reader |

Cara kerja di dalam aplikasi:

1. Electron membuka aplikasi lalu menjalankan backend Java di port acak pada 127.0.0.1, dengan token sesi acak.
2. Vue menampilkan PDF dengan pdf.js dan memanggil REST backend untuk tanda tangan, verifikasi, dan pembaruan timestamp.
3. Backend mengerjakan semua proses kriptografi dan PDF. Kunci privat tidak pernah dikirim ke frontend.
4. Selama on lokus (Sprint 0 sampai 3), backend dan Vue dijalankan di laptop seperti aplikasi web lokal. Pembungkusan Electron dilakukan di Sprint 4 (off lokus).

Karena backend berbentuk REST, aplikasi ini nantinya juga bisa dijadikan layanan web BSrE tanpa menulis ulang inti kriptografinya.

## 9. Ukuran keberhasilan

1. PDF hasil aplikasi dapat dibuka di Adobe Reader, dan tanda tangan RSA terbaca utuh (sah bila Root CA dipercaya).
2. Tanda tangan ML-DSA lolos verifikasi di aplikasi sendiri dan di OpenSSL.
3. Uji vektor ACVP ML-DSA untuk grup wajib lulus 100%.
4. Dokumen yang diubah satu byte saja ditolak oleh verifikator.
5. Skenario pembaruan berhasil: dokumen tetap sah setelah sertifikat TSA generasi pertama kedaluwarsa.
6. Penandatanganan hybrid satu dokumen selesai paling lama 2 detik (di luar waktu tunggu TSA).

## 10. Risiko dan asumsi

- Adobe Reader menandai tanda tangan ML-DSA sebagai **tidak sah** (bukan tidak dikenal), sehingga panel Adobe menampilkan "At least one signature is invalid". Mengganti /Filter atau /SubFilter tidak mengubah hasil ini. Keterbatasan ini diterima (keputusan 25 Nov 2026): struktur tetap PAdES standar, dan verifier aplikasi menjadi acuan sampai Adobe mendukung ML-DSA. Lihat T-011.
- Stack berubah dari desain Hari 8 (Go, modul CA Java, Vue) menjadi Vue + Java Spring Boot dalam aplikasi desktop Electron. Vue dan Java + Bouncy Castle tetap dipakai, layanan Go dihapus. Perubahan ini perlu disetujui pembimbing lapangan.
- Electron menambah ukuran aplikasi (sekitar 150 sampai 250 MB termasuk JRE). Ini wajar untuk prototipe.
- Libur Natal dan Tahun Baru mengurangi waktu di minggu ke-10 dan ke-11.
