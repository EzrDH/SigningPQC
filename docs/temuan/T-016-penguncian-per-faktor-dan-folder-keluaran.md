# T-016: Penguncian per faktor dan batas folder keluaran

| Butir | Isi |
|---|---|
| Tanggal | 26 Nov 2026 |
| Jenis | Temuan keamanan dan perbaikan |
| Sprint | 3 (aplikasi pengguna) |
| Sumber temuan | Tinjauan keamanan otomatis di laptop, setelah cabang `aplikasi-pengguna` di-push (commit `b67cb4b`) |
| Tingkat | Sedang (keduanya) |
| Lingkungan | Container Linux, OpenJDK 21 (`--release 21`), Maven offline |
| Status | Diperbaiki, uji lolos |

## Ringkasan
Ada dua celah di backend akun:

1. Orang yang tahu password tetapi tidak tahu passphrase atau kode TOTP bisa menebak faktor kedua tanpa batas.
2. Pengguna yang sudah masuk bisa mengarahkan folder keluaran ke folder data aplikasi, tempat akun, kunci, dan PKI uji disimpan.

Keduanya diperbaiki dengan TDD: uji ditulis dulu, terlihat gagal, lalu kode diperbaiki sampai uji lolos.

## Temuan 1: login berhasil mereset hitungan gagal konfirmasi
- **Gejala.** `LoginGuard` memakai satu penghitung untuk setiap akun. Gagal password, gagal passphrase atau TOTP saat tanda tangan, dan gagal passphrase saat mengganti metode masuk ke penghitung yang sama. Login yang berhasil memanggil `succeeded`, dan `succeeded` menghapus seluruh penghitung.
- **Akibat.** Penyerang yang tahu password bisa mencoba 4 tebakan konfirmasi, masuk ulang dengan password supaya penghitung kembali ke nol, lalu mengulanginya. Akun tidak pernah terkunci, jadi faktor kedua bisa ditebak terus. Untuk TOTP 6 digit ini paling berbahaya.
- **Perbaikan.** Penghitung kini dipisah per faktor: `PASSWORD` untuk masuk, dan `CONFIRM` untuk passphrase atau TOTP, baik saat tanda tangan maupun saat ganti metode. Lima kegagalan berturut-turut pada satu faktor tetap mengunci seluruh akun selama 5 menit (HTTP 423). Keberhasilan satu faktor hanya mengosongkan penghitungnya sendiri.
- **Uji (gagal sebelum perbaikan).**
  - `LoginGuardTest.loginBerhasilTidakMengosongkanHitunganKonfirmasi`
  - `AccountApiTest.loginBerhasilTidakMeresetHitunganKonfirmasi`: 4 passphrase salah, lalu login ulang berhasil, lalu 1 passphrase salah. Setelah itu passphrase yang benar pun ditolak dengan 423. Sebelum perbaikan hasilnya 200.
- **Uji tambahan.** `hitunganTiapFaktorTerpisah` (4 gagal password ditambah 4 gagal konfirmasi belum mengunci) dan `berhasilMengosongkanHitunganFaktorYangSama`.

## Temuan 2: folder keluaran boleh berada di dalam folder data aplikasi
- **Gejala.** `PUT /api/me/settings` menerima path absolut mana pun sebagai `outputDir`. Backend membuat foldernya, menulis berkas uji `.uji-tulis*.tmp`, lalu menyimpan hasil tanda tangan ke sana.
- **Akibat.** Pengguna yang sudah masuk bisa membuat folder dan berkas di dalam `<data-dir>`, termasuk `accounts/` milik akun lain dan folder `signers/`. Saat uji RED terlihat langsung: hasil tanda tangan `surat-ttd.pdf` dan `invisible-ttd.pdf` tersimpan di folder data.
- **Keputusan.** Memilih folder sendiri tetap diizinkan, karena desain meminta pengguna bisa memilih folder keluaran lewat isian path. Aplikasi ini aplikasi desktop lokal di 127.0.0.1. Yang ditolak hanya folder di dalam folder data aplikasi.
- **Perbaikan.**
  - `MeController.usableFolder` kini membandingkan path nyata folder tujuan dengan folder data. Bagian path yang sudah ada diselesaikan dengan `toRealPath`, sehingga `..` dan tautan simbolik ikut dihitung.
  - Pemeriksaan dilakukan sebelum `createDirectories`, lalu diulang sesudahnya pada path nyata.
  - Pesan galat: "Folder keluaran tidak boleh berada di dalam folder data aplikasi".
- **Uji (gagal sebelum perbaikan).** `AccountApiTest.folderKeluaranDiDalamFolderDataDitolak` memakai tiga path: folder data itu sendiri, `accounts/keluaran-baru`, dan `<folder keluaran>/../<folder data>/lain`. Ketiganya harus 400 dan tidak ada folder yang dibuat. Sebelum perbaikan hasilnya 200.

## Hasil uji
| Perintah | Hasil |
|---|---|
| `mvn -o -Dmaven.compiler.release=21 -pl app-server -am test -Dtest=AccountApiTest,LoginGuardTest` sebelum perbaikan | LoginGuardTest: 1 gagal (`loginBerhasilTidakMengosongkanHitunganKonfirmasi`). AccountApiTest: 4 gagal dan 4 error. Kedua uji baru gagal, dan uji tanda tangan sesudahnya ikut gagal karena folder keluaran akun uji sudah berpindah ke folder data |
| Uji yang sama sesudah perbaikan | LoginGuardTest 5/5, AccountApiTest 27/27 |
| Seluruh backend (`mvn -o test`) | 145/145 lolos (sebelumnya 141, ditambah 4 uji baru) |
| Laptop Windows, Eclipse Temurin 25.0.5.1, `.\mvnw.cmd -B test` pada commit `097f229` | BUILD SUCCESS, 145/145 lolos (Signer Core 7, PKI Uji 70, App Server 68), tanpa baris `[WARNING]` atau `[ERROR]` |

Tinjauan keamanan ulang di laptop atas `LoginGuard`, `AccountService`, dan `MeController` menyatakan kedua perbaikan bekerja sesuai maksud. Nama berkas keluaran aman: `OutputFolder.baseName` membuang pemisah path dan karakter aneh, dan berkas ditulis dengan `CREATE_NEW` sehingga tidak ada yang tertimpa.

## Keterbatasan
- Folder keluaran masih boleh berada di mana saja di luar folder data, selama proses backend bisa menulis di sana. Untuk aplikasi desktop satu pengguna ini sesuai desain. Bila backend kelak dipakai bersama oleh banyak orang, folder keluaran sebaiknya dibatasi ke satu folder dasar per akun.
- Penghitung penguncian masih disimpan di memori dan hilang saat backend dimulai ulang. Ini sama seperti sebelumnya.
- Penguncian tetap per akun, jadi orang lain yang tahu username bisa sengaja mengunci akun selama 5 menit. Ini sesuai desain.

- Pemeriksaan folder hanya dilakukan saat pengaturan disimpan. Saat tanda tangan dan perpanjangan, `MeDocumentController` menulis ke `account.outputDir()` tanpa memeriksa ulang. Bila folder itu kemudian diganti dengan junction atau tautan simbolik ke folder data, berkas `-ttd.pdf` baru bisa masuk ke sana. Dampaknya kecil karena yang ditulis hanya berkas PDF baru dan tidak ada yang tertimpa. Tingkat: rendah.
- Akun yang folder keluarannya disimpan sebelum perbaikan ini tidak diperiksa ulang. Ini hanya menyangkut akun uji lama.
- Siapa pun yang tahu username tetap bisa mengunci akun selama 5 menit dengan 5 password salah. Ini konsekuensi umum dari penguncian, bukan celah baru.

## Tindak lanjut
- ~~Jalankan ulang `.\mvnw.cmd -B test` di laptop dengan JDK 25~~ selesai, 145/145 lolos.
- Opsional: panggil pemeriksaan folder data juga di `OutputFolder.save`, supaya junction yang dibuat belakangan ikut tertolak.
