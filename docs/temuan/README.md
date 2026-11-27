# Catatan Temuan dan Hasil Pengujian

Setiap temuan (perilaku aplikasi lain, bug, keterbatasan pustaka) dan setiap hasil pengujian dicatat sebagai satu berkas di folder ini. Catatan dipakai sebagai bahan laporan harian, laporan mingguan, laporan pengujian, dan laporan akhir.

## Aturan penulisan

1. Satu temuan atau satu sesi pengujian = satu berkas `T-XXX-judul-singkat.md`, nomor urut naik.
2. Isi mengikuti templat di bawah: ringkas, tetapi lengkap sampai bisa diulang orang lain.
3. Tangkapan layar disimpan di `img/` dengan awalan nomor catatan.
4. Tambahkan satu baris ke daftar catatan di bawah.

## Daftar catatan

| No | Tanggal | Jenis | Judul | Status |
|---|---|---|---|---|
| T-001 | 25 Nov 2026 | Pengujian | Bukti teknis Sprint 0: tanda tangan hybrid RSA + ML-DSA | Lulus |
| T-002 | 25 Nov 2026 | Temuan kompatibilitas | PdfCraft tidak dapat memverifikasi tanda tangan ML-DSA-65 | Tercatat |
| T-003 | 25 Nov 2026 | Bug | spring-boot:run gagal pada build multi-modul | Diperbaiki |
| T-004 | 25 Nov 2026 | Pengujian | Migrasi ke JDK 25 LTS dan uji silang ML-DSA bawaan JDK | Lulus |
| T-005 | 25 Nov 2026 | Pengujian | PKI uji RSA dan ML-DSA, CRL, dan PKCS#12 | Lulus |
| T-006 | 25 Nov 2026 | Pengujian dan temuan kompatibilitas | TSA lokal RFC 3161 dan keterbatasan `openssl ts` untuk ML-DSA | Lulus, keterbatasan tercatat |
| T-007 | 25 Nov 2026 | Pengujian | PAdES B-T: signature timestamp pada tanda tangan hybrid | Lulus |
| T-008 | 25 Nov 2026 | Pengujian | Tanda tangan RSA visible dan ML-DSA invisible | Lulus |
| T-009 | 25 Nov 2026 | Pengujian | Uji vektor NIST ACVP untuk ML-DSA (615 kasus, 39 grup) | Lulus |
| T-010 | 25 Nov 2026 | Pengujian | REST lokal untuk alur TTE hybrid dan waktu tanda tangan (rata-rata 24 ms) | Lulus |
| T-011 | 25 Nov 2026 | Pengujian, bug, dan temuan kompatibilitas | Uji ulang di PdfCraft dan Adobe: OID kebijakan TSA ditolak, ML-DSA dianggap tidak sah oleh Adobe | OID diperbaiki, keterbatasan Adobe diterima |
| T-012 | 25 Nov 2026 | Pengujian | Lapisan LTV (B-LT, B-LTA) dan pembaruan TSA sampai TSA ML-DSA: dokumen sah pada simulasi 2040 | Lulus (uji pembaca PDF menyusul) |
| T-013 | 26 Nov 2026 | Pengujian | Akun pengguna, pembungkusan kunci passphrase/TOTP, dan tanda tangan B-LTA dari aplikasi (130 uji) | Lulus (JDK 25 menyusul) |
| T-014 | 26 Nov 2026 | Temuan | Spesimen pada halaman PDF berotasi: ukuran minimal, orientasi, dan posisi bawaan kini mengikuti /Rotate | Diperbaiki |
| T-015 | 26 Nov 2026 | Pengujian | Antarmuka pengguna Vue: delapan layar sesuai mockup v7, 39 uji Vitest, alur lengkap di browser; bug logout saat kode salah diperbaiki | Lulus (container dan laptop) |
| T-016 | 26 Nov 2026 | Temuan | Keamanan akun: penghitung penguncian dipisah per faktor (login tidak lagi mereset tebakan passphrase/TOTP) dan folder keluaran tidak boleh di dalam folder data | Diperbaiki |
| T-017 | 26 Nov 2026 | Temuan | Status verifikasi mengikuti ETSI EN 319 102-1: penerbit tak dikenal menjadi "tidak dapat dibuktikan", tanda tangan sah tanpa pasangan RSA+ML-DSA menjadi sah dengan peringatan "bukan hybrid" (revisi 27 Nov) | Diperbaiki |
| T-018 | 26 Nov 2026 | Pengujian dan temuan kompatibilitas | Uji langsung di browser Windows (31 langkah, JDK 25): seluruh alur lolos, ditemukan intersepsi HTTP 204 pada `POST /api/me/sign` oleh pengelola unduhan karena `Content-Disposition: attachment` | Lulus dengan temuan |
| T-019 | 27 Nov 2026 | Temuan | Bukti pada detail verifikasi: OID algoritma dan kebijakan, sidik jari SHA-256, CRL beserta sumbernya, jangkar kepercayaan, revisi yang ditutup tiap tanda tangan, dan tab "Lapisan dokumen" (DSS dan document timestamp) | Diperbaiki |
| T-020 | 27 Nov 2026 | Pengujian | Halaman Lingkungan uji (CA, TSA, penanda tangan, cabut) dan log aktivitas lokal perangkat (siapa, kapan, berkas, hasil) | Lulus |
| T-021 | 27 Nov 2026 | Pengujian dan temuan kompatibilitas | TC-05: B-LT dan B-LTA dari aplikasi sah di validator aplikasi dan pyHanko tanpa CRL daring; pyHanko menerima ML-DSA-65 | Lulus (Adobe menyusul) |
| T-022 | 27 Nov 2026 | Temuan keamanan dependensi | Lima kerentanan moderate `npm audit` (glob, minimatch lewat @vue/test-utils, khusus pengembangan) diatasi dengan overrides | Diperbaiki |

## Templat

```markdown
# T-XXX: Judul singkat

| Butir | Isi |
|---|---|
| Tanggal | |
| Jenis | Pengujian / Temuan kompatibilitas / Bug / Keterbatasan |
| Sprint | |
| Kebutuhan terkait | KF-.., KNF-.., TC-.. |
| Lingkungan | OS, Java, pustaka, aplikasi dan versinya |
| Versi kode | commit |
| Status | Lulus / Gagal / Tercatat / Diperbaiki |

## Ringkasan
Satu sampai tiga kalimat: apa yang diuji atau ditemukan, dan hasilnya.

## Langkah
Langkah yang bisa diulang.

## Hasil
Apa yang terlihat, angka, dan tangkapan layar.

## Analisis
Mengapa hasilnya begitu dan apa artinya bagi proyek.

## Tindak lanjut
Apa yang dilakukan berikutnya dan di sprint mana.
```
