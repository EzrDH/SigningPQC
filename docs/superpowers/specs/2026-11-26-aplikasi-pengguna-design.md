# Rancangan: aplikasi pengguna (Vue) dengan akun, passphrase/TOTP, dan spesimen

Tanggal: 26 Nov 2026 · Status: draf, menunggu persetujuan ejer. Sudah melewati satu pemeriksaan pembaca baru (bagian 10).
Mockup acuan: `docs/superpowers/specs/2026-11-26-aplikasi-pengguna-mockup.html` (versi 7, juga di artifact
"Mockup aplikasi TTE").

## 1. Maksud
Lapisan LTV sudah selesai di backend (Sprint 2, PR #1). Sekarang dibutuhkan aplikasi yang nyaman bagi pengguna biasa.
Demo 18 Desember ditonton banyak tim BSrE dan kepala bidang, dan ejer sendiri yang mengoperasikan aplikasinya.

Pengguna utama punya dua kebutuhan:
1. **Menandatangani PDF.**
2. **Memverifikasi PDF.** Ini nilai jual utamanya, karena belum ada pembaca PDF yang bisa memeriksa tanda tangan
   ML-DSA.

Detail teknis (level PAdES, lapisan, DSS, TSA) cukup dipahami pengembang. Detail itu tidak ditampilkan di alur
utama. Detail sertifikat dan tanda tangan baru muncul saat pengguna menekan "Lihat detail", seperti di pembaca PDF.

## 2. Tujuan, bukan tujuan, anti-tujuan
Tujuan:
- Daftar, masuk, menandatangani, memverifikasi, pengaturan, dan profil berjalan lewat antarmuka Vue di laptop, dengan
  backend Spring Boot di 127.0.0.1.
- Kunci privat pengguna hanya bisa dipakai setelah konfirmasi passphrase atau TOTP (cara 1 yang dipilih ejer, lihat
  bagian 4).
- Setiap tanda tangan otomatis hybrid (RSA lalu ML-DSA) dan B-LTA. Pengguna tidak memilih level atau algoritma.
- Dokumen bertanda tangan langsung tersimpan di folder keluaran pengguna, tanpa tombol unduh.
- Tampilan minimalis dan elegan dengan nuansa biru instansi, mengikuti mockup versi 7.

Bukan tujuan (saat ini):
- Passkey. Pilihannya tampil tetapi nonaktif dengan label "Segera".
- Verifikasi email, lupa password, pemulihan passphrase, dan akun di banyak perangkat.
- Pembungkus Electron. Ini dikerjakan di Sprint 4, tetapi rancangan ini tidak boleh menghalanginya.
- Pengingat otomatis perpanjangan keabsahan. Cukup tampil sebagai keterangan di hasil verifikasi.
- Halaman pengembang atau visualisasi lapisan. Ejer memutuskan detail ini cukup untuk pengembang (API, T-012).

Anti-tujuan:
- Istilah teknis di alur utama (PAdES, B-LTA, DSS, CMS, lapisan).
- Tombol atau jalur ganda ke tujuan yang sama. Topbar hanya berisi Beranda, Pengaturan, dan Profil, dan riwayat hanya
  ada di Beranda.
- Kalimat penjelas pada pilihan di Pengaturan.
- Passphrase, kode TOTP, atau password tercatat di log, berkas, atau respons API.
- Logo atau nama BSSN/BSrE dipakai sebagai logo aplikasi. Logo sementara berupa perisai generik, dan nama aplikasi
  belum ditampilkan.

## 3. Batasan
- Lingkungan uji saja. Data sintetis, PKI dan TSA buatan sendiri. Jangan meng-commit kunci, akun, atau spesimen.
- Backend: Java 25, Spring Boot 4.2, BC 1.87, PDFBox 3.0.8, port 8085 di 127.0.0.1. Folder data
  `${user.home}/SigningPQC-uji`.
- Frontend: Vue 3 + Vite + TypeScript, Pinia, Vue Router, pdfjs-dist (PRD). Selama on lokus dijalankan dengan
  `npm run dev`, dan Vite mem-proxy `/api` ke 127.0.0.1:8085, jadi CORS tidak diperlukan.
- Target demo Jumat 18 Desember 2026.
- Semua teks antarmuka dalam Bahasa Indonesia. Pengecualiannya label pilihan "Visible" dan "Invisible", yang memang
  diminta dalam bahasa Inggris.
- Setiap temuan atau hasil uji dicatat di `docs/temuan/` (T-013 dan seterusnya).

## 4. Pendekatan
Tiga cara yang dipertimbangkan untuk akun dan konfirmasi tanda tangan:

| Cara | Ringkas | Untung | Rugi |
|---|---|---|---|
| **1. Akun lokal, passphrase membuka kunci (dipilih)** | Kunci pengguna dienkripsi. Passphrase atau TOTP membuka kunci sesaat untuk menandatangani. | Jawaban keamanan kuat di depan BSrE: berkas kunci yang dicuri tidak bisa dipakai tanpa passphrase. | Sekitar 3 hari kerja backend. |
| 2. Akun lokal, passphrase/TOTP hanya gerbang | Kunci disimpan seperti sekarang, dan backend hanya mengecek passphrase/TOTP. | Lebih cepat, sekitar 1,5 hari. | Passphrase tidak terikat ke kunci, jadi lemah bila ditanya soal pencurian berkas. |
| 3. Server identitas terpisah (Keycloak) | IdP sungguhan. | Paling mirip produksi. | Layanan tambahan yang ikut dibungkus Electron. Terlalu berat untuk PoC. |

Ejer memilih cara 1.

## 5. Tampilan dan alur (keputusan ejer, dari mockup versi 1 sampai 6)
Navigasi: topbar berisi tiga ikon di tengah, yaitu Beranda, Pengaturan, dan Profil. Topbar tidak memuat teks atau
nama aplikasi. Halaman Masuk dan Daftar tidak punya topbar.

| Layar | Isi |
|---|---|
| Masuk | Logo, username atau email, password, tombol Masuk, tautan Daftar. Satu kolom di tengah. |
| Daftar (3 langkah) | (1) Username, email, password. Tidak ada nama lengkap atau jabatan. (2) Buat passphrase dan ulangi, minimal 12 karakter. (3) Pindai QR authenticator, lalu masukkan kode 6 digit untuk memastikan. Akun baru dibuat setelah kode benar. |
| Beranda | Sapaan, dua kartu (Tandatangani dokumen, Verifikasi dokumen), dan tombol "Lihat riwayat dokumen" yang membuka atau menutup daftar riwayat. |
| Unggah | Kembali ke Beranda, judul sesuai mode, dan area drag and drop atau klik untuk memilih PDF. Setelah PDF dipilih, aplikasi langsung pindah ke Tandatangani atau Verifikasi. |
| Tandatangani | Penampil PDF (pdf.js) dengan navigasi halaman. Bila Visible, ada spesimen yang bisa diseret dan diubah ukurannya. Panel kanan berisi judul, satu kalimat petunjuk, dan tombol Tandatangani. Tombol membuka dialog yang meminta passphrase atau kode TOTP sesuai Pengaturan. Setelah berhasil, tombol hilang dan muncul "Dokumen ditandatangani dan tersimpan di <path>". |
| Verifikasi | Penampil PDF. Panel kanan berisi status besar (Dokumen sah / Tidak sah / Tidak dapat dibuktikan), empat butir dalam bahasa biasa, tanggal keabsahan berakhir, dan tombol "Lihat detail". Bila perlu perpanjangan, muncul tombol "Perpanjang keabsahan". |
| Lihat detail | Dialog dengan satu tab per tanda tangan (RSA, ML-DSA). Setiap tab punya sub-tab Ringkasan, Sertifikat, Rantai sertifikat, dan Timestamp, seperti panel tanda tangan di pembaca PDF. |
| Pengaturan | Tiga kotak tanpa kalimat penjelas: Tampilan tanda tangan (Visible / Invisible, plus unggah spesimen bila Visible), Konfirmasi tanda tangan (Passphrase / TOTP / Passkey nonaktif "Segera"), dan Lokasi penyimpanan (path plus tombol Ubah). |
| Profil | Inisial, username, email, tanggal terdaftar, dua kartu sertifikat (RSA dan ML-DSA: status, algoritma, penerbit, nomor seri, masa berlaku), dan tombol Keluar. |

Gaya (dari mockup): putih, tinta #0e1726, satu biru instansi #0f3d91 dengan tint #eef3fc, dan hijau #13804a khusus
untuk "sah". Font Plus Jakarta Sans. Sudut membulat 10 sampai 16 px, tanpa bayangan berat. Mode gelap tidak wajib.

Bahasa status hasil verifikasi (pemetaan dari `validation.status` dan peringatan):
- `SAH` tanpa peringatan: "Dokumen sah". Subjudul "Tanda tangan asli, isi tidak berubah".
- `SAH` dengan peringatan: "Dokumen sah" dengan catatan, misalnya "Sertifikat dicabut setelah ditandatangani".
- `TIDAK_DAPAT_DIBUKTIKAN`: "Keabsahan tidak dapat dibuktikan" (kuning), dengan alasan dari ReasonCode dalam bahasa
  biasa.
- `TIDAK_SAH`: "Dokumen tidak sah" (merah), dengan alasan.
- PDF tanpa tanda tangan: "Dokumen ini tidak memiliki tanda tangan elektronik".

## 6. Backend

### 6.1 Akun
- Disimpan di `<data-dir>/accounts/<accountId>/account.json`. Berkas akun tidak pernah memuat rahasia dalam bentuk
  terbuka. Isinya: id, username (unik, huruf kecil), email (unik), hash password, signerId (pasangan sertifikat dari PKI uji), metode konfirmasi (`PASSPHRASE` | `TOTP`), tampilan
  (`VISIBLE` | `INVISIBLE`), folder keluaran, dan waktu dibuat.
- Hash password memakai Argon2id dari Bouncy Castle (`Argon2BytesGenerator`). Parameter m=64 MiB, t=3, p=1, salt
  16 byte, keluaran 32 byte. Hash dibandingkan dengan perbandingan waktu-konstan.
- Pendaftaran membuat pasangan sertifikat lewat `PkiService.issueSigner` dengan CN = username,
  tanpa jabatan dan unit (subjek contoh: `CN=budi.uji, O=SigningPQC Uji, C=ID`).
- `issueSigner` yang sekarang tidak bisa dipakai apa adanya, karena ia menulis `signers/<id>/*.p12` dengan password
  konstanta dan `load()` memuat semua kunci ke memori. Itu akan meninggalkan salinan kunci akun yang lemah. Dibuat
  metode baru, misalnya `issueAccountSigner(commonName)`, yang:
  - mengembalikan kunci dan sertifikat di memori tanpa menulis `.p12` ke `signers/`;
  - tetap mencatat penanda tangan di `state.json` dengan tanda `account: true` dan sertifikatnya saja, agar
    pencabutan (`/api/pki/signers/{id}/revoke`), CRL, dan `revocationSource()` tetap bekerja;
  - `load()` melewati pemuatan kunci untuk penanda tangan bertanda `account`.
- PKI uji belum ada saat start: perilaku sekarang hanya mencatat log. Ditambah properti
  `signingpqc.pki.auto-setup` (bawaan `true` di `application.properties`, `false` di profil uji agar uji REST lama
  tidak berubah). Bila `true` dan PKI belum siap, backend menjalankan setup saat start.
- Reset PKI (`POST /api/pki/setup?reset=true`) juga menghapus `accounts/` dan semua sesi, karena akun tanpa CA dan
  sertifikatnya tidak berguna. Ini lingkungan uji dengan data sintetis, jadi tidak ada migrasi.
- Sertifikat akun yang dicabut: Profil menampilkan "Dicabut", dan tanda tangan ditolak dengan pesan
  "Sertifikat Anda sudah dicabut". Penerbitan ulang sertifikat tidak termasuk cakupan PoC ini.

### 6.2 Kunci yang dibungkus dua kali
1. Saat mendaftar, kunci privat RSA dan ML-DSA disimpan dalam satu PKCS#12 (`keys.p12`). PKCS#12 ini dienkripsi
   dengan **kunci data (DEK)** acak 256-bit, memakai AES-256-GCM atas byte PKCS#12. Password internal PKCS#12 boleh
   tetap konstanta, karena perlindungannya datang dari AES-GCM.
2. DEK dibungkus dengan AES-256-GCM:
   - `dek.pp` selalu ada: dengan KEK hasil Argon2id(passphrase, salt sendiri, parameter seperti password).
   - `dek.app` **hanya ada selama mode TOTP aktif**: dengan **kunci aplikasi** `<data-dir>/app.key` (256-bit acak,
     dibuat sekali, izin berkas hanya pemilik). Dalam mode Passphrase berkas ini tidak ada, sehingga pencuri folder
     data beserta `app.key` tetap tidak bisa membuka kunci.
   - Pindah ke TOTP: pengguna memasukkan passphrase sekali (dialog kecil di Pengaturan), backend membuka DEK lewat
     `dek.pp` lalu membuat `dek.app`. Pindah ke Passphrase: pengguna memasukkan passphrase, lalu `dek.app` dihapus.
   - Saat mendaftar, metode awal adalah TOTP (authenticator baru saja dihubungkan), jadi `dek.app` dibuat saat itu.
3. Rahasia TOTP disimpan terenkripsi dengan kunci aplikasi (`totp.enc`).
4. Saat menandatangani:
   - Mode Passphrase: passphrase membuka `dek.pp`, lalu DEK membuka kunci.
   - Mode TOTP: kode diverifikasi terlebih dahulu, lalu kunci aplikasi membuka `dek.app`.
   - Kunci hanya ada di memori selama satu permintaan tanda tangan. Array byte dan char dihapus sesudahnya sebisa
     mungkin di Java.
5. Batasan yang jujur, dicatat di T-013: mode TOTP melindungi dari pencurian berkas akun saja, bukan dari pencurian
   seluruh folder data beserta `app.key`. Mode passphrase melindungi dari keduanya.
6. Kunci pasangan sertifikat lama (penanda tangan uji non-akun, `/api/pki/signers`) tetap disimpan seperti sekarang,
   agar uji dan demo lama tetap jalan. Endpoint `/api/sign` lama hanya bisa memakai penanda tangan non-akun, karena
   kunci akun tidak bisa dibuka tanpa konfirmasi.

### 6.3 TOTP
- RFC 6238: HMAC-SHA1, 6 digit, periode 30 detik, jendela ±1 langkah. Rahasia 20 byte acak.
- URI `otpauth://totp/<label>?secret=<base32>&issuer=<issuer>`. Label berisi username, dan issuer "TTE Uji"
  (sementara, sampai nama aplikasi ditentukan).
- Kode QR dibuat di frontend dari URI itu dengan pustaka `qrcode` (npm). URI hanya dikirim sekali, saat langkah 3
  pendaftaran.
- Kode yang sama tidak boleh dipakai dua kali. Langkah waktu terakhir yang berhasil disimpan per akun.
- Diuji dengan vektor RFC 6238 Lampiran B (SHA1).

### 6.4 Sesi dan pembatasan percobaan
- `POST /api/auth/login` mengembalikan token acak 256-bit (base64url). Token disimpan di memori backend dengan batas
  menganggur 30 menit dan batas mutlak 8 jam. Backend dimulai ulang berarti semua sesi habis, dan itu dapat
  diterima.
- Frontend mengirim `Authorization: Bearer <token>` dan menyimpannya di `sessionStorage` (hilang saat jendela
  ditutup).
- Lima kali gagal (login, passphrase, atau TOTP) berturut-turut per akun berarti akun dikunci 5 menit. Pesan galat
  tidak membedakan "username tidak ada" dan "password salah".
- Semua endpoint `/api/me/**` butuh token. Endpoint `/api/verify`, `/api/pki/**`, `/api/ltv/**`, `/api/tsa/**`,
  dan `/api/sign` lama tetap terbuka (termasuk simulasi waktu `at`) di 127.0.0.1 untuk pengembang dan uji. Ini dicatat sebagai keterbatasan.
- Tidak memakai Spring Security. Cukup satu `HandlerInterceptor` untuk memeriksa token, agar dependensi tetap kecil.
  Builder boleh memakai Spring Security bila lebih sederhana.

### 6.5 API baru

| Metode dan path | Isi |
|---|---|
| `POST /api/auth/register/start` | Body JSON berisi username, email, password, dan passphrase. Data divalidasi (unik, password minimal 8, passphrase minimal 12). Hasilnya `registrationId` sementara (berlaku 10 menit) dan `otpauthUri`. Akun belum dibuat. |
| `POST /api/auth/register/finish` | `registrationId` dan `code`. Bila kode benar, akun dibuat (sertifikat, kunci, berkas) dan respons berisi token sesi (langsung masuk). |
| `POST /api/auth/login` | `login` (username atau email) dan `password`. Respons berisi token dan profil singkat. |
| `POST /api/auth/logout` | Menghapus sesi. |
| `GET /api/me` | Profil, ringkasan dua sertifikat (status aktif/dicabut, algoritma, penerbit, seri hex, masa berlaku), dan pengaturan. |
| `PUT /api/me/settings` | `confirmMethod`, `appearance`, `outputDir`. Mengubah `confirmMethod` wajib menyertakan `passphrase` (lihat 6.2), dan passphrase salah dihitung ke batas 5 kali gagal. `outputDir` harus path absolut. Folder dibuat bila belum ada, dan ditolak (400) bila tidak bisa ditulis. |
| `PUT /api/me/specimen` | Unggah PNG atau JPEG (maksimal 1 MB, maksimal 2000 px per sisi). Gambar disimpan sebagai PNG di folder akun. |
| `GET /api/me/specimen` | Gambar spesimen untuk pratinjau (404 bila belum ada). |
| `POST /api/me/sign` | Multipart: `file`, `page`, `x`, `y`, `width`, `height` (dalam point PDF; diabaikan bila Invisible), dan `passphrase` atau `code`. Backend menandatangani hybrid B-LTA, menyimpan hasil ke folder keluaran, mencatat riwayat, lalu mengembalikan PDF bertanda tangan dengan header `X-Saved-Path` (path lengkap). |
| `POST /api/me/renew` | Multipart `file`. Backend menambah DSS dan document timestamp dengan TSA yang sesuai (lihat 6.8), menyimpan ke folder keluaran, lalu mengembalikan PDF dengan `X-Saved-Path`. |
| `GET /api/me/history` | 50 entri terakhir: nama berkas, kegiatan (Ditandatangani / Diverifikasi / Diperpanjang), waktu, status, dan path. |
| `POST /api/me/verify` | Multipart `file`. Tanpa `at`. Isi respons sama dengan `/api/verify` ditambah detail sertifikat per tanda tangan (bagian 6.7), dan mencatat riwayat "Diverifikasi". Aplikasi pengguna hanya memakai endpoint ini. |
| `POST /api/verify` (pengembang) | Tetap terbuka dan tetap menerima `at`. Ditambah detail yang sama (6.7). Tidak mencatat riwayat. Uji REST dan `coba-rest.ps1` lama tidak berubah. |

Galat memakai format `ApiErrors` yang ada: JSON berisi `error` dalam Bahasa Indonesia yang bisa langsung ditampilkan.

### 6.6 Spesimen dan tampilan Visible
- `VisibleSignature` diperluas dengan gambar opsional. Bila ada gambar, appearance stream menggambar spesimen
  (`PDImageXObject`) dengan skala pas ke kotak tanpa distorsi. Spesimen dibuat rata kiri dan tengah vertikal, tanpa
  teks tambahan.
- Bila Visible tetapi belum ada spesimen, dipakai appearance teks standar yang sudah ada ("Ditandatangani secara
  elektronik", username, waktu). Halaman Tandatangani menampilkan petunjuk "Belum ada spesimen".
- Invisible: tanda tangan RSA juga invisible (tanpa widget yang tampak). ML-DSA memang selalu invisible.
- Ukuran minimal kotak tetap 120 x 60 point. Ukuran bawaan 180 x 75 point di kanan bawah halaman yang sedang dilihat.

### 6.7 Detail verifikasi
`SignatureView` ditambah field-field berikut, diambil dari sertifikat di CMS dan hasil `DocumentValidator`:
- Sertifikat penanda tangan: subjek, penerbit, seri (hex), notBefore/notAfter, algoritma dan ukuran kunci, key usage.
- Rantai: daftar subjek dari penanda tangan sampai root, dengan penanda root tepercaya.
- Status pencabutan: dari `ChainResult` lapisan tanda tangan.
- Timestamp: TSA, genTime, algoritma, dan seri (sudah ada di `TimestampReport`).
- Bagian revisi yang dicakup: "seluruh dokumen saat ditandatangani" atau "revisi N dari M".

Frontend hanya menampilkan field ini di dialog Lihat detail. Tidak ada field baru yang memuat istilah PAdES di
tampilan utama.

### 6.8 Perpanjangan keabsahan
Tombol "Perpanjang keabsahan" muncul hanya bila `validation.renewalRecommended` bernilai true, yaitu TSA lapisan
teratas masih berlaku tetapi akan kedaluwarsa dalam batas peringatan. Batas ini dijadikan properti
`signingpqc.renewal-warning-days` (bawaan 30).

Bila TSA lapisan teratas sudah kedaluwarsa, perpanjangan tidak menolong: stempel baru dibuat sesudah TSA lama habis,
sehingga tidak bisa membuktikan lapisan di bawahnya (perilaku `DocumentValidator` dari Sprint 2). Tombol tidak
muncul, dan hasil verifikasi menulis "Masa keabsahan sudah berakhir dan tidak bisa diperpanjang lagi."

Dokumen yang ditandatangani di hari demo terlindungi sampai TSA RSA G1 berakhir (2028), jadi tombol ini tidak muncul
di demo biasa. Bila perpanjangan perlu diperagakan, dipakai jalur pengembang Sprint 2 (`/api/ltv/**` dengan `at`),
bukan aplikasi pengguna.

TSA yang dipilih: TSA dengan sisa masa berlaku terpanjang yang algoritmanya masih aman pada waktu itu. Di PKI uji
urutannya ML-DSA, lalu rsa-g2, lalu rsa-g1. Artinya perpanjangan dari aplikasi selalu memakai TSA ML-DSA. Simulasi
waktu (`at`) tidak tersedia di aplikasi pengguna, hanya lewat endpoint pengembang.

### 6.9 Folder keluaran
- Bawaan `${user.home}/output-signing`, dibuat saat pertama dipakai.
- Nama berkas keluaran `<nama-asli>-ttd.pdf`. Bila sudah ada, dipakai `-ttd-2.pdf`, `-ttd-3.pdf`, dan seterusnya.
  Hasil perpanjangan memakai akhiran `-diperpanjang`.
- Pemilihan folder di versi web memakai isian path dari tombol Ubah, yang divalidasi backend. Di Electron (Sprint 4)
  tombol Ubah membuka dialog folder bawaan.

### 6.10 Riwayat
`<data-dir>/accounts/<id>/history.json` menyimpan maksimal 200 entri, dan API mengembalikan 50 terbaru. Riwayat hanya
berisi nama berkas dan path, bukan isi dokumen.

## 7. Frontend
- Struktur: `src/router` (rute di bawah), `src/stores` (Pinia: `auth`, `settings`, `document` yang menyimpan berkas
  terpilih di memori), `src/api` (fetch wrapper dengan token dan galat), `src/views`, `src/components`
  (`TopBar`, `PdfViewer`, `SpecimenBox`, `ConfirmDialog`, `OtpInput`, `DropZone`, `VerifyResult`, `SignatureDetail`).
- Rute: `/masuk`, `/daftar`, `/` (Beranda), `/unggah/:mode` (`tandatangani` | `verifikasi`), `/tandatangani`,
  `/verifikasi`, `/pengaturan`, `/profil`. Semua kecuali masuk dan daftar butuh sesi, dan pengguna tanpa sesi diarahkan
  ke `/masuk`.
- PdfViewer memakai pdfjs-dist, merender halaman ke canvas, dengan navigasi halaman dan zoom sederhana (pas lebar).
  Koordinat spesimen dikonversi dari piksel layar ke point PDF dengan memperhitungkan skala dan rotasi halaman.
- Berkas PDF hanya disimpan di memori (store). Tidak ada penyimpanan berkas di browser.
- Tidak memakai pustaka komponen UI. CSS sendiri dengan token warna dan jarak dari mockup.
- Uji: Vitest untuk store dan konversi koordinat. Alur utama dicek manual di browser, dengan tangkapan layar untuk
  catatan temuan.

## 8. Pengujian dan catatan
- JUnit:
  - Pembungkusan kunci: passphrase benar membuka, passphrase salah gagal tanpa membocorkan alasan.
  - TOTP: vektor RFC 6238, jendela ±1, kode tidak bisa dipakai ulang.
  - Argon2id: hash dan verifikasi.
  - REST: daftar (start/finish), masuk, kunci setelah 5 kali gagal, tanda tangan Visible dengan spesimen dan
    Invisible, berkas tersimpan di folder keluaran, verify dengan detail, perpanjang.
  - Uji REST lama tetap hijau.
- Uji silang: PDF hasil aplikasi diperiksa `cek-pki.ps1` atau OpenSSL bila relevan, dan dibuka di Adobe (RSA sah,
  ML-DSA tidak dikenali, sesuai T-011).
- Catatan temuan: T-013 (akun dan pembungkusan kunci: rancangan, hasil uji, keterbatasan) dan T-014 (antarmuka:
  tangkapan layar setiap layar, hasil uji pengguna).

## 9. Diserahkan ke builder
- Pemecahan komponen Vue dan nama berkas di dalam struktur bagian 7.
- Spring Security atau interceptor sendiri (bagian 6.4).
- Pustaka QR di frontend, asalkan offline dan bisa dibungkus Electron.
- Detail animasi dan transisi, asalkan sederhana (satu transisi halus saat ganti layar sudah cukup).
- Cara menghapus rahasia dari memori Java sebisanya.

## 10. Hasil pemeriksaan pembaca baru
Satu pembaca baru (perspektif builder) membaca dokumen ini bersama kode yang ada. Lima pertanyaannya dijawab
langsung di dokumen:
1. `dek.app` dalam mode Passphrase: kini hanya ada selama mode TOTP aktif (6.2), dan ganti metode butuh passphrase (6.5).
2. Perpanjangan di demo dan dokumen yang sudah kedaluwarsa: 6.8.
3. `/api/verify` terbuka atau butuh login: tetap terbuka untuk pengembang, dan aplikasi memakai `/api/me/verify` (6.4, 6.5).
4. Akun saat PKI di-reset atau sertifikat dicabut, serta salinan kunci di `signers/`: 6.1.
5. Versi mockup acuan: versi 7.

Hal kecil yang diputuskan builder: field galat bernama `error`, `TimestampReport` perlu nomor seri, koordinat y
diukur dari atas halaman, dan versi Spring Boot mengikuti pom.

