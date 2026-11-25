# T-001: Bukti teknis Sprint 0, tanda tangan hybrid RSA + ML-DSA

| Butir | Isi |
|---|---|
| Tanggal | Rabu, 25 November 2026 |
| Jenis | Pengujian |
| Sprint | 0 |
| Kebutuhan terkait | KF-13, KF-14, KF-20, KF-23, KNF-02, KNF-04; TC-01, TC-03, TC-10 |
| Lingkungan | Windows (laptop pengembang), JDK 21.0.8, Bouncy Castle 1.87, PDFBox 3.0.8, OpenSSL 3.5.9 |
| Versi kode | commit `9ad480c` |
| Status | Lulus |

## Ringkasan

PDF contoh berhasil ditandatangani RSA-3072 lalu ML-DSA-65 (keduanya invisible) sebagai dua incremental update. Kedua tanda tangan sah menurut verifikator aplikasi dan OpenSSL 3.5.9, dan perubahan satu byte pada isi PDF membuat keduanya ditolak.

## Langkah

1. `cd backend`
2. `.\mvnw.cmd test` menjalankan `HybridSigningSpikeTest` (2 kasus uji).
3. `.\mvnw.cmd -q -pl signer-core compile exec:java` menjalankan `SpikeMain`. Keluaran tersimpan di `backend\spike-output\`.
4. Dari folder repositori: `powershell -ExecutionPolicy Bypass -File scripts\cek-openssl.ps1`

## Hasil

### Uji JUnit

| Kasus uji | Hasil |
|---|---|
| Kedua tanda tangan sah, ML-DSA menutup seluruh dokumen, SubFilter ETSI.CAdES.detached | Lulus |
| Satu byte isi PDF diubah, kedua tanda tangan ditolak | Lulus |

### Verifikasi aplikasi (SpikeMain)

| No | Algoritma | Digest | Ukuran CMS | Menutup seluruh dokumen | Sah |
|---|---|---|---|---|---|
| 1 | RSA-3072 (sha256WithRSAEncryption) | SHA-256 | 1.931 B | Tidak (ditimpa revisi ML-DSA) | Ya |
| 2 | ML-DSA-65 | SHA-512 | 9.370 B | Ya | Ya |

### Verifikasi silang OpenSSL 3.5.9

| No | Algoritma terbaca | Hasil `openssl cms -verify -noverify` |
|---|---|---|
| 1 | sha256, sha256WithRSAEncryption | CMS Verification successful |
| 2 | sha512, ML-DSA-65 (2.16.840.1.101.3.4.3.18) | CMS Verification successful |

Catatan: `-noverify` dipakai karena sertifikat masih self-signed, jadi yang diuji adalah tanda tangan dan isi, bukan rantai sertifikat.

### Ukuran dan waktu

| Ukuran | Nilai |
|---|---|
| PDF asli | 962 B |
| Setelah tanda tangan RSA | 34.848 B |
| Setelah tanda tangan ML-DSA | 134.440 B |
| Waktu tanda tangan hybrid (tanpa TSA, satu kali jalan) | sekitar 84 ms |

## Analisis

- CMS ML-DSA buatan Bouncy Castle 1.87 dapat dibaca dan diverifikasi oleh implementasi lain (OpenSSL 3.5.9). Ini bukti awal interoperabilitas sesuai RFC 9882 (digest SHA-512 untuk ML-DSA-65).
- Penyisipan CMS ML-DSA ke PDF lewat PDFBox (external signing) berjalan tanpa kendala. Risiko teknis terbesar proyek sudah terjawab.
- Kenaikan ukuran PDF sebagian besar berasal dari ruang /Contents yang dicadangkan, bukan dari tanda tangannya. Cadangan saat ini 16 KB untuk RSA dan 48 KB untuk ML-DSA, ditulis dalam heksadesimal sehingga menjadi dua kali lipat. Isi CMS sebenarnya hanya 1,9 KB dan 9,4 KB.
- Waktu 84 ms jauh di bawah target KNF-02 (2 detik), tetapi baru satu kali pengukuran tanpa TSA. Pengukuran resmi (100 kali, rata-rata dan P95) dilakukan di Sprint 5.

## Tindak lanjut

- Sprint 1: ganti sertifikat self-signed dengan hierarki CA uji, tambahkan timestamp, dan buat tampilan visible untuk RSA.
- Sprint 1: sesuaikan ruang cadangan /Contents setelah ukuran token timestamp diketahui, supaya PDF tidak membengkak tanpa perlu.
