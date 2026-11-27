# User Flow Aplikasi SigningPQC

Dokumen ini memetakan seluruh alur pengguna (*user flow*) pada aplikasi **SigningPQC** (penandatanganan dan verifikasi PDF *hybrid* RSA-3072 + ML-DSA-65 dengan format PAdES B-LTA) berdasarkan implementasi aktual di `frontend/` (Vue 3 + Vue Router + Pinia + pdf.js) dan `backend/app-server/` (Spring Boot 4.2 + Bouncy Castle 1.87 + PDFBox 3.0.8) pada cabang `aplikasi-pengguna`.

---

## 1. Peta Layar, Rute, dan Navigasi

Aplikasi menggunakan *hash routing* (`createWebHashHistory`) agar kompatibel dengan pembungkusan desktop Electron. Terdapat **8 layar utama** dan **2 dialog modal**:

| Kategori | Layar / Modal | Rute | TopBar | Akses |
|---|---|---|---|---|
| Autentikasi (Publik) | **Masuk** (`MasukView`) | `#/masuk` | Tidak ada | Hanya tanpa sesi (jika sudah masuk, diarahkan ke `#/`) |
| Autentikasi (Publik) | **Daftar 3 Langkah** (`DaftarView`) | `#/daftar` | Tidak ada | Hanya tanpa sesi (jika sudah masuk, diarahkan ke `#/`) |
| Utama (Terlindungi) | **Beranda** (`BerandaView`) | `#/` | Ada (`Beranda` aktif) | Wajib sesi Bearer |
| Alur Dokumen | **Unggah PDF** (`UnggahView`) | `#/unggah/tandatangani` atau `#/unggah/verifikasi` | Ada (`Beranda` aktif) | Wajib sesi Bearer |
| Alur Dokumen | **Tandatangani** (`TandatanganiView`) | `#/tandatangani` | Ada (`Beranda` aktif) | Wajib sesi + PDF di memori (jika kosong -> `#/unggah/tandatangani`) |
| Alur Dokumen | **Verifikasi** (`VerifikasiView`) | `#/verifikasi` | Ada (`Beranda` aktif) | Wajib sesi + PDF di memori (jika kosong -> `#/unggah/verifikasi`) |
| Konfigurasi | **Pengaturan** (`PengaturanView`) | `#/pengaturan` | Ada (`Pengaturan` aktif) | Wajib sesi Bearer |
| Akun | **Profil** (`ProfilView`) | `#/profil` | Ada (`Profil` aktif) | Wajib sesi Bearer |
| Modal | **Dialog Konfirmasi** (`ConfirmDialog`) | Overlay di `Tandatangani` & `Pengaturan` | — | Meminta Passphrase atau TOTP 6 digit |
| Modal | **Detail Tanda Tangan** (`SignatureDetail`) | Overlay di `Verifikasi` | — | Menampilkan tab RSA & ML-DSA (4 sub-tab) |

> **Navigasi global (TopBar):** Tampil di semua layar terlindungi dengan 3 ikon di tengah: **Beranda** (`#/`), **Pengaturan** (`#/pengaturan`), dan **Profil** (`#/profil`). Rute tidak dikenal (`/:rest(.*)*`) dialihkan ke `#/`.

---

## 2. Alur Keseluruhan (End-to-End User Flow)

```mermaid
flowchart TD
    Start(["Buka Aplikasi"]) --> CheckSession{"Sesi aktif di sessionStorage?"}
    CheckSession -- "Tidak" --> Masuk["Layar Masuk (#/masuk)"]
    CheckSession -- "Ya" --> Beranda["Layar Beranda (#/)"]

    Masuk -- "Klik Daftar" --> Daftar1["Daftar Langkah 1: Username, Email, Password"]
    Daftar1 --> Daftar2["Daftar Langkah 2: Buat & Ulangi Passphrase"]
    Daftar2 --> Daftar3["Daftar Langkah 3: Pindai QR & Kode TOTP 6 Digit"]
    Daftar3 -- "Kode valid -> Buat akun, sertifikat RSA+ML-DSA & sesi" --> Beranda
    Masuk -- "Login valid" --> Beranda

    Beranda -- "Klik Lihat riwayat dokumen" --> Riwayat["Tampil / Sembunyikan Riwayat (maks. 50 entri)"]
    Beranda -- "Kartu Tandatangani dokumen" --> UnggahTTD["Unggah PDF (#/unggah/tandatangani)"]
    Beranda -- "Kartu Verifikasi dokumen" --> UnggahVer["Unggah PDF (#/unggah/verifikasi)"]
    Beranda -- "TopBar: Pengaturan" --> Pengaturan["Layar Pengaturan (#/pengaturan)"]
    Beranda -- "TopBar: Profil" --> Profil["Layar Profil (#/profil)"]

    UnggahTTD -- "Pilih / lepas berkas .pdf" --> TTD["Layar Tandatangani (#/tandatangani)"]
    TTD -- "Klik Tandatangani" --> DialogTTD["Modal Konfirmasi (TOTP atau Passphrase)"]
    DialogTTD -- "Valid -> Tanda tangan Hybrid B-LTA + Simpan otomatis" --> TTDSukses["Tampil path simpan & tombol Tandatangani dokumen lain"]
    TTDSukses -- "Tandatangani dokumen lain" --> UnggahTTD

    UnggahVer -- "Pilih / lepas berkas .pdf" --> Ver["Layar Verifikasi (#/verifikasi) - Auto verifikasi"]
    Ver -- "Klik Lihat detail (bila ada TTD)" --> Detail["Modal Detail Tanda Tangan (RSA / ML-DSA)"]
    Ver -- "Klik Perpanjang keabsahan (bila mendekati habis)" --> Renew["Perpanjang dengan TSA ML-DSA + Simpan + Verifikasi ulang"]
    Renew --> Ver
    Ver -- "Verifikasi dokumen lain" --> UnggahVer

    Profil -- "Klik Keluar" --> Logout["Hapus sesi, token, dokumen, & pengaturan di memori"]
    Logout --> Masuk
```

---

## 3. Rincian Alur per Fitur

### 3.1. Autentikasi: Masuk & Pendaftaran Akun Baru

Aplikasi menggunakan akun lokal di backend (`<data-dir>/accounts/<id>/`). Saat mendaftar, backend otomatis menerbitkan **pasangan sertifikat elektronik RSA-3072 dan ML-DSA-65** atas nama `CN=<username>`, mengenkripsi kunci privat dengan DEK (AES-256-GCM) yang dibungkus oleh *passphrase* (Argon2id) serta kunci aplikasi (untuk mode awal TOTP).

```mermaid
flowchart TD
    A["Halaman Masuk (#/masuk)"] --> B{"Sudah punya akun?"}

    B -- "Ya: Isi username/email & password" --> C["Validasi isian tidak kosong"]
    C -- "Kosong" --> C_Err["Pesan: Isi username atau email dan password"]
    C_Err --> A
    C -- "Terisi -> POST /api/auth/login" --> D{"Kredensial benar & tidak terkunci?"}
    D -- "Salah (401)" --> D_Err["Pesan: Username/email atau password salah (hitung gagal faktor PASSWORD)"]
    D_Err --> A
    D -- "5x gagal beruntun (423)" --> D_Lock["Akun dikunci 5 menit"]
    D_Lock --> A
    D -- "Berhasil (200)" --> Home["Simpan token di sessionStorage -> Beranda (#/)"]

    B -- "Belum: Klik Daftar" --> R1["Daftar Langkah 1/3: Username, Email, Password"]
    R1 -- "Klik Lanjut" --> V1{"Validasi klien: user 3-32, email valid, pass >= 8?"}
    V1 -- "Tidak" --> R1
    V1 -- "Ya" --> R2["Daftar Langkah 2/3: Passphrase & Ulangi (>= 12 karakter)"]
    R2 -- "Klik Kembali" --> R1
    R2 -- "Klik Lanjut" --> V2{"Validasi klien: >= 12 karakter & sama?"}
    V2 -- "Tidak" --> R2
    V2 -- "Ya -> POST /api/auth/register/start" --> V3{"Username & Email unik?"}
    V3 -- "Bentrok (409)" --> R1_Err["Kembali ke Langkah 1 dengan pesan konflik"]
    R1_Err --> R1
    V3 -- "Unik (200)" --> R3["Daftar Langkah 3/3: Render QR otpauth & OtpInput 6 digit (berlaku 10 menit)"]
    R3 -- "Isi 6 digit (otomatis kirim) -> POST /api/auth/register/finish" --> V4{"Kode TOTP cocok & belum kedaluwarsa?"}
    V4 -- "Kode salah (400)" --> R3_Err["Pesan: Kode tidak cocok, kotak dikosongkan"]
    R3_Err --> R3
    V4 -- "Kedaluwarsa > 10 menit (410)" --> R1_Exp["Reset pendaftaran -> Kembali ke Langkah 1"]
    R1_Exp --> R1
    V4 -- "Cocok (200)" --> Home
```

**Catatan penting alur autentikasi:**
- Metode konfirmasi awal setelah pendaftaran adalah **TOTP**, tampilan awal **Visible**, dan lokasi penyimpanan awal `${user.home}/output-signing`.
- Sesi disimpan di `sessionStorage` (otomatis hilang saat jendela/tab ditutup) dan di memori backend dengan batas menganggur **30 menit** serta batas mutlak **8 jam**.
- Jika di tengah penggunaan sesi berakhir (HTTP 401 dengan header `WWW-Authenticate: Bearer`), aplikasi otomatis membersihkan token, dokumen aktif, dan pengaturan di memori lalu mengembalikan pengguna ke `#/masuk`.

---

### 3.2. Beranda & Riwayat Dokumen

1. Pengguna disambut sesuai waktu lokal (*Selamat pagi / siang / sore / malam*).
2. Tersedia dua kartu aksi utama:
   - **Tandatangani dokumen** -> menuju `#/unggah/tandatangani`.
   - **Verifikasi dokumen** -> menuju `#/unggah/verifikasi`.
3. Tombol **Lihat riwayat dokumen** (toggle) memuat `GET /api/me/history` (maksimal 50 entri terbaru dari 200 yang tersimpan di `history.json`).
   - Jika belum ada aktivitas: tampil teks *"Belum ada riwayat..."*.
   - Jika ada: tampil daftar nama berkas, jenis kegiatan (`Ditandatangani`, `Diverifikasi`, `Diperpanjang`), waktu lokal, dan lencana status (`Sah`, `Tidak sah`, `Tidak dapat dibuktikan`, `Tanpa tanda tangan`, `Tidak diperiksa`).

---

### 3.3. Penandatanganan Dokumen (Hybrid RSA + ML-DSA, PAdES B-LTA)

Setiap penandatanganan dari aplikasi otomatis membubuhkan tanda tangan **RSA-3072** (tampil atau tersembunyi sesuai Pengaturan) + timestamp TSA RSA, lalu tanda tangan **ML-DSA-65** (selalu tersembunyi) + timestamp TSA ML-DSA, dilanjutkan penyematan data validasi (DSS) dan *document timestamp* TSA RSA G1 sehingga menghasilkan **PAdES B-LTA**.

```mermaid
flowchart TD
    U["Layar Unggah (#/unggah/tandatangani)"] --> Drop{"Pilih / tarik berkas"}
    Drop -- "Bukan .pdf" --> DropErr["Tampil galat: berkas bukan PDF"]
    DropErr --> U
    Drop -- "Berkas .pdf valid" --> LoadMem["Simpan PDF di memori (Pinia) -> #/tandatangani"]

    LoadMem --> LoadPref["Muat Pengaturan (GET /api/me & spesimen) + Render PDF (pdf.js)"]
    LoadPref --> MultiPage{"PDF > 1 halaman?"}
    MultiPage -- "Ya" --> NavPage["Navigasi halaman sebelumnya / berikutnya"]
    NavPage --> ModeCheck
    MultiPage -- "Tidak" --> ModeCheck

    ModeCheck{"Mode tampilan di Pengaturan?"}
    ModeCheck -- "Visible" --> SpecBox["Tampilkan kotak spesimen (bawaan 180x75 pt kanan bawah)"]
    SpecBox --> Adjust["Seret posisi (mouse/panah), ubah ukuran (min 120x60 pt), ikuti rotasi /Rotate"]
    Adjust --> ClickSign["Klik tombol Tandatangani"]
    ModeCheck -- "Invisible" --> NoBox["Tanpa kotak spesimen di halaman"]
    NoBox --> ClickSign

    ClickSign --> Modal{"Modal Konfirmasi sesuai metode aktif"}
    Modal -- "TOTP" --> InputOtp["Isi 6 digit kode authenticator (kirim otomatis)"]
    Modal -- "Passphrase" --> InputPp["Isi passphrase lalu klik Tandatangani"]
    Modal -- "Batal / Esc / klik luar" --> TTDWait["Tutup modal, kembali ke layar Tandatangani"]

    InputOtp --> CallApi["POST /api/me/sign (file, page, koordinat bila Visible, secret)"]
    InputPp --> CallApi

    CallApi --> ApiRes{"Respons backend?"}
    ApiRes -- "401 Konfirmasi salah (tanpa WWW-Authenticate)" --> ErrModal["Modal tetap terbuka, input dikosongkan, sesi tetap aktif (hitung gagal faktor CONFIRM)"]
    ErrModal --> Modal
    ApiRes -- "423 Akun terkunci (5x gagal CONFIRM)" --> ErrLock["Tampil pesan coba lagi dalam 5 menit"]
    ErrLock --> Modal
    ApiRes -- "409 Sertifikat dicabut / 400 Galat input" --> ErrOther["Tampil pesan galat di modal"]
    ErrOther --> Modal
    ApiRes -- "200 Berhasil" --> Done["Tutup modal, kunci kotak spesimen, simpan otomatis ke folder keluaran (-ttd.pdf / -ttd-N.pdf), catat riwayat"]
    Done --> NextDoc["Tampil banner sukses + tombol Tandatangani dokumen lain"]
    NextDoc -- "Klik" --> U
```

**Detail perilaku penandatanganan:**
- Jika mode `Visible` tetapi pengguna belum mengunggah gambar spesimen di Pengaturan, petunjuk menampilkan *"Belum ada spesimen. Unggah di Pengaturan, sementara dipakai teks standar."* dan backend menggambar blok teks standar.
- Jika berkas keluaran bernama sama sudah ada di folder keluaran, backend otomatis menambahkan nomor urut (`nama-ttd-2.pdf`, `nama-ttd-3.pdf`, dst.) sehingga berkas lama tidak pernah tertimpa.

---

### 3.4. Verifikasi Dokumen, Detail Tanda Tangan, & Perpanjangan Keabsahan

```mermaid
flowchart TD
    U["Layar Unggah (#/unggah/verifikasi)"] -- "Pilih / lepas .pdf" --> V["Layar Verifikasi (#/verifikasi)"]
    V --> Auto["Render PDF (pdf.js) & otomatis kirim POST /api/me/verify + catat riwayat"]
    Auto --> Verdict{"Hasil validasi berlapis"}

    Verdict -- "Tidak ada tanda tangan" --> ResNone["Status netral: Dokumen ini tidak memiliki tanda tangan elektronik"]
    Verdict -- "SAH" --> ResOk["Status hijau: Dokumen sah + 4 poin fakta + tanggal batas keabsahan"]
    Verdict -- "TIDAK_DAPAT_DIBUKTIKAN" --> ResWarn["Status kuning: Keabsahan tidak dapat dibuktikan + alasan"]
    Verdict -- "TIDAK_SAH" --> ResBad["Status merah: Dokumen tidak sah + alasan (mis. isi berubah / dicabut)"]

    ResOk --> CheckRenew{"renewalRecommended = true (sisa masa < 30 hari)?"}
    CheckRenew -- "Ya" --> BtnRenew["Tampilkan tombol utama: Perpanjang keabsahan"]
    CheckRenew -- "Tidak" --> Actions
    ResWarn --> CheckExp{"TSA sudah kedaluwarsa?"}
    CheckExp -- "Ya" --> NoteExp["Tampil catatan: Masa keabsahan sudah berakhir dan tidak bisa diperpanjang lagi"]
    NoteExp --> Actions
    CheckExp -- "Tidak" --> Actions
    ResBad --> Actions

    BtnRenew -- "Klik Perpanjang keabsahan" --> CallRenew["POST /api/me/renew (tambah DSS + Document Timestamp TSA ML-DSA, simpan -diperpanjang.pdf)"]
    CallRenew -- "Berhasil" --> ReloadDoc["Muat PDF baru ke penampil, tampilkan banner path simpan, lalu verifikasi ulang otomatis"]
    ReloadDoc --> Auto

    Actions["Tindakan pada panel kanan"] --> OpenDetail["Klik Lihat detail (jika signatures > 0)"]
    Actions --> VerifyOther["Klik Verifikasi dokumen lain -> #/unggah/verifikasi"]

    OpenDetail --> ModalDetail["Modal Detail Tanda Tangan"]
    ModalDetail --> TabSig["Pilih Tanda tangan: 1 RSA atau 2 ML-DSA"]
    TabSig --> SubTabs["Pilih Sub-tab: Ringkasan | Sertifikat | Rantai sertifikat | Timestamp"]
    SubTabs -- "Tutup / Esc / klik luar" --> V
```

---

### 3.5. Pengaturan

Layar `#/pengaturan` memuat tiga kartu konfigurasi yang langsung disinkronkan ke backend:

```mermaid
flowchart LR
    P["Layar Pengaturan (#/pengaturan)"] --> S1["1. Tampilan tanda tangan"]
    P --> S2["2. Konfirmasi tanda tangan"]
    P --> S3["3. Lokasi penyimpanan"]

    S1 --> S1V["Pilih Visible -> PUT /api/me/settings (langsung simpan)"]
    S1 --> S1I["Pilih Invisible -> PUT /api/me/settings (panel spesimen disembunyikan)"]
    S1V --> Spec["Unggah / Ganti spesimen (PNG/JPEG, <= 1 MB, <= 2000 px) -> PUT /api/me/specimen"]

    S2 --> S2Change["Pilih Passphrase atau TOTP (berbeda dari aktif)"]
    S2 --> S2Pk["Passkey: nonaktif dengan lencana Segera"]
    S2Change --> S2Modal["Modal Konfirmasi (WAJIB Passphrase)"]
    S2Modal -- "Batal / Esc" --> S2Revert["Pilihan radio kembali ke metode sebelumnya"]
    S2Modal -- "Passphrase salah (401)" --> S2Err["Pesan: Konfirmasi tidak cocok (hitung gagal CONFIRM)"]
    S2Modal -- "Passphrase benar (200)" --> S2Save["Backend buat / hapus dek.app -> Metode diperbarui"]

    S3 --> S3Edit["Klik Ubah -> Isian path absolut"]
    S3Edit -- "Batal" --> S3View["Kembali ke tampilan path aktif"]
    S3Edit -- "Simpan -> PUT /api/me/settings" --> S3Val{"Validasi backend: absolut, di luar data-dir, bisa ditulisi?"}
    S3Val -- "Tidak (400)" --> S3Err["Tampil galat (contoh path lengkap / larangan folder data)"]
    S3Val -- "Ya (200)" --> S3Ok["Folder dibuat bila belum ada & path tersimpan"]
```

---

### 3.6. Profil & Keluar

1. Membuka `#/profil` memanggil `GET /api/me` untuk menampilkan:
   - Inisial pengguna (dari pemecahan `username`), username, email, dan tanggal terdaftar.
   - Kartu **Sertifikat elektronik** untuk **Sertifikat RSA** (`RSA 3072 bit`) dan **Sertifikat ML-DSA** (`ML-DSA-65, tahan kuantum`), masing-masing berisi: Status (`Aktif` hijau atau `Dicabut` merah), Algoritma, Penerbit, Nomor seri (format heksadesimal per 2 digit), dan Masa berlaku.
2. Menekan tombol **Keluar**:
   - Memanggil `POST /api/auth/logout` untuk menghapus token di backend.
   - Menjalankan `auth.clear()` yang menghapus token & username dari `sessionStorage`, serta mengosongkan store `document` (PDF di memori) dan `settings` (profil + `URL.revokeObjectURL` spesimen) agar data pengguna tidak tertinggal untuk pengguna berikutnya.
   - Mengarahkan ke `#/masuk`.

---

## 4. Ringkasan Aturan Penjaga (Guards) & Kondisi Batas

| Kondisi | Perilaku Aplikasi |
|---|---|
| Mengakses halaman terlindungi tanpa sesi | Router guard mengalihkan ke `#/masuk`. |
| Mengakses `#/masuk` atau `#/daftar` saat sudah masuk | Router guard mengalihkan ke `#/`. |
| Mengakses `#/tandatangani` atau `#/verifikasi` tanpa PDF di memori | Komponen mengalihkan ke `#/unggah/tandatangani` atau `#/unggah/verifikasi`. |
| Sesi menganggur > 30 menit, lewat > 8 jam, atau backend dimulai ulang | Backend mengirim `401` + `WWW-Authenticate: Bearer`; klien otomatis membersihkan sesi dan mengarahkan ke `#/masuk`. |
| Konfirmasi passphrase / TOTP salah saat tanda tangan atau ganti metode | Backend mengirim `401` **tanpa** `WWW-Authenticate`; klien menampilkan `"Konfirmasi tidak cocok"` di modal tanpa mengeluarkan pengguna. |
| 5 kegagalan beruntun pada faktor `PASSWORD` atau faktor `CONFIRM` | Akun dikunci 5 menit (`HTTP 423`). Penghitung kedua faktor dipisah sehingga login ulang tidak mereset hitungan tebakan konfirmasi (T-016). |
| Kode TOTP yang sama dipakai dua kali dalam 30 detik | Ditolak (`Konfirmasi tidak cocok`) karena backend menyimpan `lastTotpStep` per akun. |
| Sertifikat akun dicabut lewat `/api/pki/signers/{id}/revoke` | Profil menampilkan status `Dicabut`; percobaan tanda tangan ditolak (`409`: `"Sertifikat Anda sudah dicabut"`). |
| Folder keluaran berupa path relatif atau berada di dalam `<data-dir>` | Ditolak (`400`) sebelum folder dibuat (T-016). |

---

## 5. Catatan Jalur Pengembang (Di Luar Antarmuka Pengguna)

Selain endpoint aplikasi pengguna (`/api/auth/**` dan `/api/me/**`), backend `app-server` di `127.0.0.1:8085` tetap menyediakan endpoint lokal terbuka untuk pengujian, demo simulasi waktu (`at`), dan pengembangan:
- **PKI uji:** `GET /api/pki`, `POST /api/pki/setup`, `GET/POST /api/pki/signers`, `POST /api/pki/signers/{id}/revoke`, `GET /api/pki/crl/{caId}.crl`, `GET /api/pki/ca/{caId}.crt|.pem`.
- **TSA RFC 3161:** `POST /api/tsa/rsa`, `POST /api/tsa/mldsa`.
- **Tanda tangan & verifikasi pengembang:** `POST /api/sign` (pilihan level `B-B`, `B-T`, `B-LT`, `B-LTA` untuk penanda tangan uji non-akun) dan `POST /api/verify` (menerima parameter waktu simulasi `at`, tidak mencatat riwayat).
- **LTV & Pembaruan simulasi:** `POST /api/ltv/validation-data`, `POST /api/ltv/timestamp`, `POST /api/ltv/renew` (menerima `tsa=rsa-g1|rsa-g2|mldsa` dan waktu simulasi `at`).
