# Dokumen Kebutuhan SigningPQC

25 November 2026 · Versi 0.3 (frontend Vue, backend Java, target on lokus 18 Des) · Turunan dari PRD SigningPQC

Cara membaca: setiap kebutuhan punya kode, prioritas (W = wajib, S = sebaiknya, O = opsional), dan sprint target. Sprint 0 sampai 3 berada di periode on lokus (sampai 18 Des 2026), Sprint 4 sampai 6 di periode off lokus. Kode ini dipakai lagi di kasus uji dan laporan.

## 1. Kebutuhan fungsional

### A. Sertifikat dan CA uji

| Kode | Kebutuhan | Prioritas | Sprint |
|---|---|---|---|
| KF-01 | Aplikasi dapat membuat hierarki CA uji RSA (Root RSA-4096, CA antara RSA-3072) | W | 1 |
| KF-02 | Aplikasi dapat membuat hierarki CA uji ML-DSA (Root ML-DSA-87, CA antara ML-DSA-65) sesuai RFC 9881 | W | 1 |
| KF-03 | Aplikasi dapat menerbitkan pasangan sertifikat penanda tangan RSA dan ML-DSA dengan identitas (DN) yang sama | W | 1 |
| KF-04 | Kunci dan sertifikat penanda tangan disimpan dalam berkas PKCS#12 berpassword | W | 1 |
| KF-05 | Aplikasi dapat mencabut sertifikat dan menerbitkan CRL | S | 1 |
| KF-06 | Aplikasi menyediakan OCSP responder lokal sederhana | O | 5 |

### B. TSA uji

| Kode | Kebutuhan | Prioritas | Sprint |
|---|---|---|---|
| KF-07 | Aplikasi menjalankan TSA lokal RFC 3161 dengan kunci RSA | W | 1 |
| KF-08 | Aplikasi menjalankan TSA lokal RFC 3161 dengan kunci ML-DSA | W | 1 |
| KF-09 | Masa berlaku sertifikat TSA dapat diatur (misalnya 1 jam) untuk simulasi kedaluwarsa | W | 4 |
| KF-10 | Aplikasi mendukung beberapa generasi TSA (generasi lama dan pengganti) | W | 4 |

### C. Penandatanganan

| Kode | Kebutuhan | Prioritas | Sprint |
|---|---|---|---|
| KF-11 | Pengguna dapat membuka berkas PDF dan memilih pasangan sertifikat | W | 2 |
| KF-12 | Aplikasi menambahkan tanda tangan RSA yang tampil di halaman, dengan posisi dan halaman pilihan pengguna | W | 1 |
| KF-13 | Aplikasi menambahkan tanda tangan ML-DSA yang tersembunyi (invisible) setelah tanda tangan RSA, menutup seluruh dokumen | W | 0 dan 1 |
| KF-14 | Kedua tanda tangan memakai format CAdES detached (PAdES B-B) dan atribut signing-certificate-v2 | W | 1 |
| KF-15 | Setiap tanda tangan diberi signature timestamp dari TSA sesuai algoritmanya (PAdES B-T) | W | 1 |
| KF-16 | Aplikasi menyematkan data validasi (rantai sertifikat, CRL atau OCSP) ke kamus DSS (PAdES B-LT) | W | 3 |
| KF-17 | Aplikasi menambahkan document timestamp dari TSA ML-DSA (PAdES B-LTA) | W | 3 |
| KF-18 | Hasil disimpan sebagai berkas baru, berkas asli tidak ditimpa | W | 1 |

### D. Verifikasi

| Kode | Kebutuhan | Prioritas | Sprint |
|---|---|---|---|
| KF-19 | Aplikasi membaca semua tanda tangan dan timestamp dalam PDF beserta urutannya | W | 2 |
| KF-20 | Aplikasi memverifikasi keutuhan (ByteRange dan hash) setiap tanda tangan | W | 2 |
| KF-21 | Aplikasi memverifikasi tanda tangan RSA dan ML-DSA serta rantai sertifikatnya | W | 2 |
| KF-22 | Aplikasi memverifikasi signature timestamp dan document timestamp | W | 3 |
| KF-23 | Aplikasi menampilkan status gabungan: sah hybrid hanya jika RSA dan ML-DSA keduanya sah | W | 2 |
| KF-24 | Aplikasi menampilkan alasan kegagalan yang mudah dipahami beserta kodenya | W | 2 |
| KF-25 | Laporan verifikasi dapat diekspor | S | 5 |

### E. Pembaruan timestamp

| Kode | Kebutuhan | Prioritas | Sprint |
|---|---|---|---|
| KF-26 | Aplikasi menghitung batas perlindungan dokumen (kedaluwarsa TSA terakhir, rantai CA, atau batas usia algoritma) | W | 4 |
| KF-27 | Aplikasi memberi peringatan bila batas perlindungan kurang dari ambang (bawaan 30 hari) | W | 4 |
| KF-28 | Pengguna dapat memperbarui timestamp: data validasi TSA lama ditambahkan, lalu document timestamp baru dari TSA yang masih berlaku | W | 4 |
| KF-29 | Verifikator menelusuri rantai timestamp dari yang terbaru sampai tanda tangan awal | W | 4 |
| KF-30 | Pembaruan sekaligus untuk satu folder | O | 5 |

### F. Antarmuka desktop

| Kode | Kebutuhan | Prioritas | Sprint |
|---|---|---|---|
| KF-31 | Penampil PDF di Vue (pdf.js) dengan navigasi halaman dan zoom | W | 2 |
| KF-32 | Panel tanda tangan yang menampilkan status RSA, ML-DSA, dan timestamp dengan warna dan ikon | W | 2 |
| KF-33 | Wizard penandatanganan (pilih berkas, pilih sertifikat, atur posisi kotak RSA dengan seret di halaman, tandatangani) | W | 2 |
| KF-34 | Halaman pengelolaan CA, sertifikat, dan TSA uji | S | 3 |
| KF-35 | Log aktivitas lokal (siapa, kapan, berkas apa, hasil) | S | 3 |
| KF-36 | Installer Windows (electron-builder, NSIS atau .msi) | O | 5 |
| KF-37 | Sertifikat komposit ML-DSA + RSA (RFC 10073) sebagai pembanding | O | 5 |
| KF-38 | Backend menyediakan REST lokal untuk PKI uji, TSA, tanda tangan, verifikasi, dan pembaruan, terdokumentasi dengan OpenAPI | W | 1 dan 2 |
| KF-39 | Aplikasi desktop Electron menjalankan dan menghentikan backend Java secara otomatis | W | 4 |

## 2. Kebutuhan nonfungsional

| Kode | Aspek | Kebutuhan | Cara uji |
|---|---|---|---|
| KNF-01 | Kepatuhan | ML-DSA sesuai FIPS 204: grup wajib vektor NIST ACVP (keyGen, sigGen, sigVer) lulus 100% | JUnit dengan berkas ACVP |
| KNF-02 | Kinerja | Penandatanganan hybrid satu PDF paling lama 2 detik, di luar waktu tunggu TSA | Rata-rata dan P95 dari 100 kali percobaan |
| KNF-03 | Kompatibilitas | PDF hasil dapat dibuka di Adobe Reader, dan tanda tangan RSA terbaca sah | Uji manual di Adobe Reader terbaru |
| KNF-04 | Interoperabilitas | CMS ML-DSA dan sertifikat ML-DSA dapat diverifikasi OpenSSL 3.5 | Skrip uji silang |
| KNF-05 | Standar format | Struktur dokumen mengikuti PAdES B-LTA (ETSI EN 319 142-1), CMS RFC 5652 dan RFC 9882, TSP RFC 3161 | Pemeriksaan struktur dan EU DSS untuk bagian RSA |
| KNF-06 | Keamanan | Password PKCS#12 tidak dicatat di log; kunci privat tidak pernah ditulis tanpa enkripsi | Tinjauan kode dan pemeriksaan log |
| KNF-07 | Keamanan | Tidak ada dependensi dengan kerentanan tingkat tinggi | OWASP Dependency-Check |
| KNF-08 | Kualitas kode | Tidak ada temuan prioritas tinggi pada analisis statis; cakupan uji modul inti minimal 70% | SpotBugs dan JaCoCo |
| KNF-09 | Kemudahan pakai | Pengguna baru dapat menandatangani dokumen pertama dalam 5 menit dengan panduan | Uji dengan 2 sampai 3 rekan |
| KNF-10 | Bahasa | Antarmuka dan pesan galat dalam Bahasa Indonesia | Pemeriksaan tampilan |
| KNF-11 | Platform | Berjalan di Windows 10/11; JRE disertakan dalam paket, pengguna tidak perlu memasang Java | Uji di laptop lain tanpa Java |
| KNF-12 | Ukuran | Ukuran PDF bertambah dan dicatat per tingkat (B-T, B-LT, B-LTA) untuk dibandingkan dengan RSA saja | Tabel pengukuran |
| KNF-13 | Lingkungan | Hanya memakai data sintetis dan tidak terhubung ke sistem produksi | Tinjauan konfigurasi |
| KNF-14 | Keamanan | REST backend hanya mendengar di 127.0.0.1, memakai port acak dan token sesi; kunci privat tidak pernah dikirim ke frontend | Uji akses dari mesin lain dan tanpa token |
| KNF-15 | Keamanan | Electron memakai contextIsolation, tanpa nodeIntegration di halaman Vue, dan Content Security Policy ketat | Tinjauan konfigurasi Electron |

## 3. Kasus uji utama

| Kode | Skenario | Hasil yang diharapkan | Kebutuhan |
|---|---|---|---|
| TC-01 | Tandatangani PDF satu halaman secara hybrid | Dua tanda tangan, RSA tampil, ML-DSA tersembunyi, keduanya sah | KF-12 s.d. KF-15, KF-23 |
| TC-02 | Buka hasil TC-01 di Adobe Reader | Dokumen terbuka, tanda tangan RSA sah, tanda tangan ML-DSA tidak dikenal | KNF-03 |
| TC-03 | Ubah satu byte isi PDF setelah ditandatangani | Kedua tanda tangan ditolak dengan alasan dokumen berubah | KF-20 |
| TC-04 | Gunakan sertifikat yang sudah dicabut | Status ditolak dengan alasan sertifikat dicabut | KF-05, KF-21 |
| TC-05 | Tambahkan B-LT dan B-LTA | DSS dan document timestamp ada, dan validasi tetap sah | KF-16, KF-17 |
| TC-06 | Perbarui timestamp sebelum TSA generasi 1 kedaluwarsa, lalu validasi setelahnya | Dokumen tetap sah | KF-26 s.d. KF-29 |
| TC-07 | Validasi tanpa pembaruan setelah TSA generasi 1 kedaluwarsa | Status tidak dapat dibuktikan, disertai peringatan | KF-26, KF-29 |
| TC-08 | Jalankan vektor ACVP ML-DSA | 100% grup wajib lulus | KNF-01 |
| TC-09 | Ukur waktu tanda tangan hybrid 100 kali | Rata-rata 2 detik atau kurang | KNF-02 |
| TC-10 | Verifikasi CMS ML-DSA dengan OpenSSL | Lolos verifikasi | KNF-04 |
