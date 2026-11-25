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
