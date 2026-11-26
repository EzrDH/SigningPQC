# T-013: Akun pengguna, pembungkusan kunci, TOTP, dan tanda tangan dari aplikasi

| Butir | Isi |
|---|---|
| Tanggal | 26 Nov 2026 |
| Jenis | Pengujian |
| Sprint | 3 (aplikasi pengguna) |
| Kebutuhan terkait | Spesifikasi `docs/superpowers/specs/2026-11-26-aplikasi-pengguna-design.md` bagian 6 |
| Lingkungan | Container Linux, 4 vCPU Intel Xeon 2.10 GHz, OpenJDK 21 (kompilasi `--release 21`, enforcer dilewati), Maven offline, Spring Boot 4.2, Bouncy Castle 1.87, PDFBox 3.0.8. Uji JDK 25 di laptop menyusul bersama uji frontend. |
| Versi kode | branch `aplikasi-pengguna`, commit `a625554` |
| Status | Lulus (130 dari 130 uji JUnit) |

## Ringkasan
Backend kini punya akun lokal (daftar, masuk, sesi), dan kunci privat setiap akun hanya bisa dipakai setelah konfirmasi passphrase atau kode TOTP. Tanda tangan dari aplikasi selalu hybrid RSA lalu ML-DSA dengan level B-LTA. Hasilnya langsung tersimpan di folder keluaran pengguna. Verifikasi pengguna menampilkan detail sertifikat, rantai, dan timestamp untuk RSA maupun ML-DSA. Seluruh 130 uji backend lolos, termasuk 12 uji REST lama.

## Rancangan yang diuji

### Penyimpanan kunci
Satu akun disimpan di `<data-dir>/accounts/<id>/` dengan berkas berikut:

| Berkas | Isi | Dilindungi oleh |
|---|---|---|
| `account.json` | username, email, hash password Argon2id, id penanda tangan, pengaturan | tidak memuat rahasia terbuka |
| `keys.enc` | PKCS#12 berisi kunci RSA 3072 dan ML-DSA-65 (alias `rsa`, `mldsa`) | AES-256-GCM dengan DEK acak 256-bit |
| `dek.pp` | DEK yang dibungkus | KEK = Argon2id(passphrase, salt 16 byte) |
| `dek.app` | DEK yang dibungkus, **hanya ada selama mode TOTP aktif** | kunci aplikasi `<data-dir>/app.key` |
| `totp.enc` | rahasia TOTP 20 byte | kunci aplikasi |
| `specimen.png`, `history.json` | spesimen dan riwayat (nama berkas, tanpa isi dokumen) | tidak rahasia |

Sertifikat akun ditulis di `signers/<id>/rsa.pem` dan `mldsa.pem` agar pencabutan dan CRL tetap bekerja. Kuncinya tidak pernah ditulis ke `signers/`. Penanda tangan uji lama tetap memakai `.p12` berpassword konstanta.

### Parameter
- Argon2id m = 64 MiB, t = 3, p = 1, keluaran 32 byte. Satu hash memakan rata-rata **218 ms** (tercepat 198 ms) di container ini. Pengukurannya diulang 5 kali dengan program kecil `Ukur` yang memanggil `Argon2id.hash`.
- AES-256-GCM dengan IV 12 byte acak dan tag 128 bit. AAD mengikat setiap blob ke akunnya (`keys:<id>`, `dek.app:<id>`, `totp:<id>`), jadi blob tidak bisa ditukar antarakun.
- TOTP RFC 6238: HMAC-SHA1, 6 digit, 30 detik, jendela ±1 langkah. Langkah terakhir yang dipakai disimpan, sehingga kode yang sama tidak bisa dipakai dua kali.
- Sesi: token 256-bit di memori, menganggur 30 menit, mutlak 8 jam.
- Penguncian: 5 kegagalan berturut-turut (password, passphrase, atau TOTP) mengunci akun 5 menit (HTTP 423).

## Langkah
1. `cd backend && mvn -o -q -Denforcer.skip=true -Dmaven.compiler.release=21 -pl app-server -am test`
2. Laporan per kelas dibaca dari `*/target/surefire-reports/*.txt`.

## Hasil

| Kelas uji | Uji | Gagal | Yang dibuktikan |
|---|---|---|---|
| `AccountCryptoTest` | 6 | 0 | Argon2id hash dan verifikasi; AES-GCM menolak tamper dan AAD lain; vektor RFC 6238 Lampiran B (287082, 081804, 005924, 279037); jendela ±1 dan penolakan pemakaian ulang; Base32; URI otpauth |
| `PkiAccountSignerTest` | 6 | 0 | tidak ada `.p12` untuk akun; subjek `CN=budi.uji,O=SigningPQC Uji,C=ID`; akun bisa dicabut setelah dimuat ulang; `state.json` lama tetap terbaca; reset menghapus `accounts/`; setup otomatis |
| `KeyVaultTest` | 6 | 0 | passphrase benar membuka kunci; passphrase salah ditolak dengan pesan "Konfirmasi tidak cocok"; `dek.app` hanya ada di mode TOTP; **tidak ada passphrase, password, rahasia TOTP, atau kunci privat dalam bentuk terbuka di berkas mana pun** |
| `LoginGuardTest` | 3 | 0 | kunci 5 menit setelah 5 gagal; sesi habis setelah 30 menit menganggur dan setelah 8 jam |
| `AccountApiTest` | 25 | 0 | daftar dua langkah dengan TOTP; username dan email unik; validasi input; masuk dengan username atau email; pesan sama untuk akun tidak ada dan password salah; ganti metode butuh passphrase; folder keluaran relatif atau berupa berkas ditolak; spesimen PNG; tanda tangan Visible dan Invisible tersimpan otomatis (`surat-ttd.pdf`, lalu `surat-ttd-2.pdf` tanpa menimpa); kode salah atau dipakai ulang tidak menghasilkan berkas; bukan PDF ditolak 400; riwayat; verifikasi dengan detail rantai 3 tingkat sampai root tepercaya |
| `RenewApiTest` | 1 | 0 | perpanjangan memakai TSA ML-DSA, tersimpan sebagai `-diperpanjang.pdf`, hasilnya tetap SAH |
| Uji lama (`RestApiTest`, `pki-test`, `signer-core`) | 83 | 0 | perilaku Sprint 0 sampai 2 tidak berubah |
| **Jumlah** | **130** | **0** | |

Contoh hasil verifikasi pengguna atas dokumen dari aplikasi: status `SAH`, `hybridComplete = true`, lapisan teratas `LTA`. Detail RSA berisi `RSA 3072 bit`, rantai `CN=budi.uji` lalu `CA Antara RSA Uji` lalu root (tepercaya), status pencabutan "Tidak dicabut", dan nomor seri timestamp. Detail ML-DSA berisi kunci `ML-DSA-65` dan cakupan "Seluruh dokumen saat ditandatangani".

## Analisis
- **Passphrase benar-benar membuka kunci (cara 1).** Pencuri berkas akun tanpa passphrase hanya mendapat blob AES-GCM. Dalam mode Passphrase, `dek.app` tidak ada, jadi pencuri seluruh folder data beserta `app.key` pun tidak bisa membuka kunci.
- **Cakupan ML-DSA.** B-LTA selalu menambah dua revisi (DSS dan document timestamp) sesudah tanda tangan ML-DSA, jadi PDFBox menganggapnya tidak menutup seluruh berkas. Aplikasi tetap menulis "Seluruh dokumen saat ditandatangani" selama validator tidak menemukan perubahan isi sesudahnya.
- **Biaya Argon2id.** Sekitar 0,2 detik per hash dapat diterima untuk masuk dan tanda tangan, dan sekaligus memperlambat tebakan offline.

## Keterbatasan
1. Mode TOTP tidak melindungi dari pencurian seluruh folder data beserta `app.key`, karena `dek.app` dibuka dengan kunci aplikasi. Mode Passphrase melindungi dari keduanya.
2. Endpoint pengembang (`/api/pki/**`, `/api/ltv/**`, `/api/tsa/**`, `/api/sign`, `/api/verify`) tetap terbuka tanpa login di 127.0.0.1, sesuai keputusan desain.
3. Sesi disimpan di memori, jadi hilang saat backend dimulai ulang.
4. Sertifikat akun yang dicabut tidak bisa diterbitkan ulang di PoC ini.
5. Reset PKI uji menghapus semua akun.
6. Java tidak bisa menjamin rahasia terhapus dari memori: `String` password dan passphrase dari JSON tetap ada sampai dibersihkan GC. Array byte DEK, KEK, dan PKCS#12 dikosongkan sesudah dipakai.

## Tindak lanjut
- Uji ulang dengan JDK 25 di laptop bersama uji frontend (T-014).
- Frontend Vue memakai endpoint `/api/auth/**` dan `/api/me/**` (Task 10 sampai 15).
