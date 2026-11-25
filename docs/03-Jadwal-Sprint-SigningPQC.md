# Tahapan, Jadwal, dan Sprint SigningPQC

25 November 2026 · Versi 0.3 (frontend Vue, backend Java, target on lokus) · Periode 25 Nov 2026 sampai 5 Feb 2027

## 1. Target utama

**On lokus berakhir Jumat, 18 Desember 2026** (2 November + 7 minggu). Pada tanggal itu prototipe harus sudah jadi dan alur TTE berfungsi dengan benar dari awal sampai akhir:

1. Pengguna mengunggah PDF di aplikasi Vue.
2. Backend Java menandatangani PDF secara hybrid: tanda tangan RSA tampil di halaman, lalu tanda tangan ML-DSA tersembunyi, masing-masing dengan timestamp (PAdES B-T).
3. Backend menambahkan data validasi dan document timestamp (PAdES B-LT dan B-LTA).
4. Hasilnya dapat diverifikasi di aplikasi, dan tanda tangan RSA terbaca sah di Adobe Reader.
5. Dokumen yang diubah setelah ditandatangani ditolak.

Sisa waktu on lokus hanya sekitar 3,5 minggu. Karena itu fitur yang bukan inti alur TTE dipindah ke periode off lokus, yaitu pembaruan TSA saat kedaluwarsa, pembungkus desktop Electron, dan fitur opsional.

## 2. Prinsip agar efektif

1. **Kerjakan yang paling berisiko dulu.** Penyisipan CMS ML-DSA ke PDF lewat PDFBox dibuktikan di Sprint 0.
2. **Backend dulu, tampilan menyusul cepat.** Inti kriptografi selesai di Sprint 1, halaman Vue dibuat di Sprint 2 dan langsung memanggil REST backend.
3. **Jalankan sebagai aplikasi web lokal selama on lokus.** Backend dengan Spring Boot dan frontend Vue dengan Vite di laptop. Pembungkusan desktop (Electron) dilakukan setelah alurnya benar.
4. **Setiap Jumat ada demo** ke pembimbing lapangan, berupa satu PDF hasil yang bisa dibuka dan dicek.
5. **Fitur opsional di akhir** dan boleh dipangkas.

## 3. Gambaran jadwal

| Periode | Sprint | Waktu | Fokus | Tonggak |
|---|---|---|---|---|
| On lokus | Sprint 0 | Rabu 25 sampai Jumat 27 Nov | Persiapan, kerangka proyek, bukti ML-DSA di PDF | PDF ML-DSA pertama |
| On lokus | Sprint 1 | 30 Nov sampai 4 Des | CA dan TSA uji, tanda tangan hybrid B-T di backend | PDF hybrid dari CLI dan REST |
| On lokus | Sprint 2 | 7 sampai 11 Des | Lapisan jangka panjang: DSS, document timestamp, pembaruan TSA sampai TSA ML-DSA, validator berlapis (selesai lebih awal, 25 Nov, T-012) | Dokumen sah pada simulasi 2040 |
| On lokus | Sprint 3 | 14 sampai 18 Des | Halaman Vue: tanda tangan, verifikasi, visualisasi lapisan, tombol Perbarui; stabilisasi | **Demo prototipe 18 Des** |
| Off lokus | Sprint 4 | 21 Des sampai 8 Jan (ada libur) | Electron, panduan, perbaikan dari umpan balik demo | Aplikasi desktop |
| Off lokus | Sprint 5 | 11 sampai 22 Jan | Pengujian, pengukuran, usulan migrasi | Laporan pengujian |
| Off lokus | Sprint 6 | 25 Jan sampai 5 Feb | Serah terima, laporan, presentasi | Laporan proyek akhir 29 Jan |

## 4. Rincian sprint on lokus

### Sprint 0: Persiapan (Rabu 25 sampai Jumat 27 Nov)

- [ ] Konfirmasi ke pembimbing lapangan: Vue + Java Bouncy Castle, struktur hybrid berurutan, CA dan TSA uji buatan sendiri, target 18 Des
- [ ] Pasang Maven dan Extension Pack for Java di VS Code (JDK 25 dan Node.js 24 sudah ada)
- [ ] Kerangka repositori: backend Maven multi-modul (Spring Boot 4.1) dan frontend Vue 3 + Vite + TypeScript
- [ ] Bukti teknis: satu PDF ditandatangani ML-DSA-65 lewat PDFBox + Bouncy Castle, lalu dicek dengan OpenSSL

Demo Jumat 27 Nov: PDF bertanda tangan ML-DSA pertama.

### Sprint 1: Inti backend (30 Nov sampai 4 Des)

- [ ] Hierarki CA RSA dan ML-DSA, pasangan sertifikat penanda tangan dalam PKCS#12, CRL (KF-01 s.d. KF-05)
- [ ] TSA lokal RSA dan ML-DSA (KF-07, KF-08)
- [ ] Tanda tangan hybrid: RSA visible lalu ML-DSA invisible, masing-masing dengan timestamp (KF-12 s.d. KF-15, KF-18)
- [ ] REST: setup PKI, terbitkan penanda tangan, tanda tangan (KF-38)
- [ ] Uji ACVP ML-DSA di JUnit memakai vektor yang sudah ada (KNF-01)

Demo Jumat 4 Des: PDF hybrid dibuka di Adobe Reader, tanda tangan RSA sah.

### Perubahan urutan (25 Nov 2026)

Pembimbing menjelaskan struktur PDF berlapis di papan tulis: dokumen seperti KK atau ijazah harus bisa dibuktikan
sah seumur hidup, sedangkan sistem yang dipakai saat ini berhenti di lapisan 3 (OCSP/CRL). Karena itu lapisan
jangka panjang (DSS, document timestamp, pembaruan TSA sampai TSA ML-DSA) dikerjakan lebih dulu, lalu frontend Vue.
Lapisan selesai 25 Nov 2026 (T-012). Butir verifikator Sprint 2 dan butir LTV Sprint 3/4 di bawah sudah tercakup.

### Sprint 2: Verifikasi dan antarmuka Vue (7 sampai 11 Des)

- [x] Verifikator: daftar tanda tangan, ByteRange, CMS, rantai sertifikat, timestamp, status gabungan (KF-19 s.d. KF-21, KF-23) (selesai 25 Nov, T-012)
- [x] REST verifikasi (KF-38) (selesai 25 Nov, T-012)
- [ ] Halaman Vue: unggah PDF, penampil pdf.js, wizard tanda tangan dengan posisi kotak RSA, hasil verifikasi (KF-11, KF-31 s.d. KF-33)
- [x] Pesan galat yang mudah dipahami (KF-24) (selesai 25 Nov, T-012)
- [ ] Uji TC-01 s.d. TC-04 dan TC-10

Demo Jumat 11 Des: seluruh alur TTE dijalankan dari Vue.

### Sprint 3: Validasi jangka panjang dan stabilisasi (14 sampai 18 Des)

- [x] Kamus DSS dan VRI (KF-16) serta document timestamp (KF-17) (selesai 25 Nov, T-012)
- [x] Verifikasi document timestamp (KF-22) (selesai 25 Nov, T-012)
- [ ] Halaman CA, sertifikat, TSA uji, dan log aktivitas (KF-34, KF-35)
- [ ] Uji TC-05 dan uji ulang seluruh alur, perbaikan bug
- [ ] README cara menjalankan dan panduan singkat

**Demo Jumat 18 Des (akhir on lokus):** prototipe Vue + Java yang menandatangani, menambahkan LTV, dan memverifikasi PDF hybrid dengan benar.

Bila waktu Sprint 3 tidak cukup, DSS dan document timestamp boleh bergeser ke Sprint 4. Alur inti (tanda tangan hybrid B-T dan verifikasi dari Vue) tetap harus jadi pada 18 Des.

## 5. Rincian sprint off lokus

### Sprint 4: Pembaruan TSA dan aplikasi desktop (21 Des sampai 8 Jan)

- [x] TSA generasi 1 bermasa berlaku pendek dan TSA generasi 2 (KF-09, KF-10) (selesai 25 Nov, T-012)
- [ ] Batas perlindungan dan peringatan sudah di backend (T-012); tombol Perbarui timestamp di Vue (KF-26 s.d. KF-29)
- [x] Uji TC-06 dan TC-07 (selesai 25 Nov, T-012)
- [ ] Pembungkus Electron yang menjalankan backend Java beserta JRE hasil jlink (KF-39)
- [ ] Panduan penggunaan

### Sprint 5: Pengujian (11 sampai 22 Jan)

- [ ] Semua kasus uji TC-01 s.d. TC-10
- [ ] Pengukuran waktu dan ukuran dibanding RSA saja (KNF-02, KNF-12)
- [ ] SpotBugs, JaCoCo, OWASP Dependency-Check (KNF-07, KNF-08)
- [ ] Uji kemudahan pakai dengan 2 sampai 3 rekan (KNF-09)
- [ ] Fitur opsional bila waktu ada: ekspor laporan, folder sekaligus, OCSP, komposit, installer
- [ ] Dokumen usulan mekanisme migrasi RSA ke PQC

### Sprint 6: Serah terima (25 Jan sampai 5 Feb)

- [ ] Rapikan kode, README, dan dokumentasi teknis
- [ ] Laporan proyek akhir, tenggat Jumat 29 Jan pukul 22.00 WIB
- [ ] Bahan presentasi dan revisi laporan akhir

## 6. Rutinitas mingguan

| Hari | Kegiatan |
|---|---|
| Senin pagi | Tentukan 3 sampai 5 target sprint, tulis sebagai issue di GitHub |
| Selasa sampai Kamis | Kerjakan target, commit kecil setiap hari, catat hasil untuk laporan harian |
| Jumat siang | Demo singkat 10 menit ke pembimbing lapangan |
| Jumat sore | Laporan mingguan (tenggat 22.00 WIB) dan rencana sprint berikutnya |

## 7. Definisi selesai

Satu tugas dianggap selesai jika:

1. Kodenya sudah di-push ke GitHub.
2. Ada uji JUnit yang lulus untuk fungsi tersebut.
3. Ada contoh berkas hasil (PDF atau sertifikat) yang bisa dicek.
4. Sudah dicatat di laporan harian.

## 8. Jika jadwal mundur

Yang tidak boleh dipangkas sebelum 18 Des: tanda tangan hybrid RSA + ML-DSA dengan timestamp, verifikasi, dan halaman Vue untuk keduanya. Urutan pemangkasan bila waktu tidak cukup: installer, sertifikat komposit, pembaruan sekaligus satu folder, OCSP, ekspor laporan, halaman pengelolaan CA (diganti tombol setup otomatis), lalu DSS dan document timestamp (bergeser ke Sprint 4).
