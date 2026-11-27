# T-017: Status verifikasi untuk penerbit yang tidak dikenal dan tanda tangan non-hybrid

| Butir | Isi |
|---|---|
| Tanggal | 26 Nov 2026 |
| Jenis | Temuan (kebijakan status) dan perbaikan |
| Sprint | 3 (aplikasi pengguna) |
| Sumber temuan | Uji manual di laptop: pengguna memverifikasi PDF yang hanya bertanda tangan RSA dari penerbit lain |
| Kebutuhan terkait | KF-23, KF-24, PRD bagian 6 |
| Acuan | ETSI EN 319 102-1 (status TOTAL-PASSED, TOTAL-FAILED, INDETERMINATE) |
| Lingkungan | Container Linux, OpenJDK 21 (`--release 21`), Vitest |
| Keputusan | Dipilih pengguna: "Ikuti ETSI + label hybrid" |
| Status | Diperbaiki, uji lolos |

## Ringkasan
Pengguna memverifikasi PDF yang hanya bertanda tangan RSA, dengan sertifikat dari penerbit di luar PKI uji. Aplikasi menampilkan "Dokumen tidak sah" dengan dua alasan: "Penerbit sertifikat tidak dipercaya" dan "Tanda tangan RSA dan ML-DSA tidak lengkap". Pengguna bertanya apakah label itu berasal dari kebijakan atau hanya asumsi.

Hasil penelusuran: kedua alasan itu adalah keputusan implementasi yang lebih keras daripada acuannya.

1. **Penerbit tidak dikenal.** `DocumentValidator.classify` memetakan `ROOT_TIDAK_DIPERCAYA` ke `TIDAK_SAH`. Menurut ETSI EN 319 102-1, rantai yang tidak bisa dibangun ke trust anchor berstatus INDETERMINATE (`NO_CERTIFICATE_CHAIN_FOUND`), bukan TOTAL-FAILED. Status gagal hanya dipakai bila ada bukti negatif, misalnya isi berubah, tanda tangan tidak cocok, atau sertifikat dicabut sebelum menandatangani.
2. **Tanda tangan non-hybrid.** KF-23 dan PRD bagian 6 berbunyi "sah hybrid hanya jika RSA dan ML-DSA keduanya sah". Aturan itu menentukan kapan dokumen boleh disebut sah *hybrid*. Aturan itu tidak mewajibkan dokumen yang hanya bertanda tangan RSA disebut tidak sah. Secara hukum di Indonesia (UU ITE dan PP 71/2019), TTE tersertifikasi dari PSrE yang diakui tetap sah tanpa ML-DSA, jadi label "tidak sah" bisa menyesatkan.

## Perbaikan
| Kondisi | Sebelum | Sesudah |
|---|---|---|
| Rantai tidak berakhir di root yang dipercaya aplikasi | Tidak sah (merah) | Tidak dapat dibuktikan (kuning), alasan "Penerbit sertifikat tidak dikenal oleh aplikasi ini" |
| Semua tanda tangan sah, tetapi tidak berpasangan RSA dan ML-DSA | Tidak sah (merah) | Kuning, judul "Tidak memenuhi kebijakan hybrid", subjudul "Tanda tangan sah, tetapi tidak berpasangan RSA dan ML-DSA", butir ✗ "Tanpa tanda tangan tahan komputer kuantum (ML-DSA)" |
| Isi berubah, tanda tangan tidak cocok, sertifikat dicabut | Tidak sah | Tetap tidak sah |
| RSA dan ML-DSA keduanya sah | Sah | Tetap sah (satu-satunya kondisi "sah hybrid") |

Perubahan kode:
- `signer-core` `DocumentValidator`: `ROOT_TIDAK_DIPERCAYA` menjadi `TIDAK_DAPAT_DIBUKTIKAN`. Status gabungan untuk hybrid yang tidak lengkap, tanpa lapisan yang tidak sah, juga menjadi `TIDAK_DAPAT_DIBUKTIKAN`. Peringatan `TANDA_TANGAN_HYBRID_TIDAK_LENGKAP` tetap dikirim.
- `frontend/src/lib/status.ts`: judul kuning "Tidak memenuhi kebijakan hybrid" bila semua lapisan sah tetapi `hybridComplete` bernilai salah. Teks alasan untuk `ROOT_TIDAK_DIPERCAYA` juga diganti.
- Dokumen kebutuhan: KF-23 dan PRD bagian 6 diperjelas.

## Hasil uji
| Uji | Sebelum perbaikan | Sesudah |
|---|---|---|
| `DocumentValidatorTest.penerbitTidakDipercayaTidakDapatDibuktikan` (trust anchor hanya root ML-DSA) | Gagal: `TIDAK_SAH` | Lolos |
| `DocumentValidatorTest.rsaSahTanpaMlDsaTidakMemenuhiKebijakanHybrid` (PDF hanya RSA + timestamp) | Gagal: `TIDAK_SAH` | Lolos |
| `status.test.ts`: label hybrid dan penerbit tidak dikenal | 2 gagal | Lolos |
| Seluruh backend | 145 | 147/147 lolos |
| Seluruh frontend | 46 | 48/48 lolos, `npm run build` berhasil |

Uji TC-03 (isi berubah), TC-04 (sertifikat dicabut), dan uji ML-DSA rusak tetap menghasilkan `TIDAK_SAH`.

## Keterbatasan
- Aplikasi hanya mengenal Root CA uji miliknya sendiri. Dokumen bertanda tangan sertifikat asli (misalnya dari BSrE) akan selalu berstatus "Tidak dapat dibuktikan". Ini sesuai lingkungan uji. Dokumen dan sertifikat asli tidak boleh dipakai sebagai bahan uji di repo ini.
- `hybridComplete` masih bernilai benar bila RSA dan ML-DSA ada tetapi salah satunya berstatus tidak dapat dibuktikan. Status gabungannya tetap kuning, bukan sah.

## Tindak lanjut
- Uji ulang di laptop dengan dokumen sintetis: hasil tanda tangan aplikasi (hijau), PDF bertanda tangan RSA saja dari PKI uji (kuning, "Tidak memenuhi kebijakan hybrid"), dan PDF yang diubah (merah).

## Revisi 27 Nov 2026: RSA saja cukup diberi peringatan
Setelah mencoba aplikasi, pengguna meminta agar dokumen yang hanya bertanda tangan RSA cukup diberi peringatan, bukan judul kuning.

| Kondisi | 26 Nov | 27 Nov |
|---|---|---|
| Semua tanda tangan sah dan penerbit dikenal, tetapi tidak berpasangan RSA dan ML-DSA | Kuning, "Tidak memenuhi kebijakan hybrid" (status `TIDAK_DAPAT_DIBUKTIKAN`) | Hijau "Dokumen sah" (status `SAH`, `hybridComplete` salah), dengan baris peringatan kuning "Peringatan: bukan tanda tangan hybrid. Dokumen tidak memuat tanda tangan ML-DSA yang tahan komputer kuantum." |
| Penerbit tidak dikenal (misalnya sertifikat asli di luar PKI uji) | Kuning, "Keabsahan tidak dapat dibuktikan" | Tetap, sesuai ETSI EN 319 102-1 |

Status "sah hybrid" tetap hanya dipakai untuk RSA + ML-DSA (`hybridComplete` benar), sesuai KF-23. Peringatan `TANDA_TANGAN_HYBRID_TIDAK_LENGKAP` tetap dikirim backend.

Uji:
- `DocumentValidatorTest.rsaSahTanpaMlDsaSahDenganPeringatanHybrid` kini mengharapkan `SAH`, dan gagal sebelum perubahan.
- Di `status.test.ts`, uji "sah dengan peringatan bukan hybrid" dan "dokumen hybrid sah tanpa peringatan" gagal sebelum perubahan.
- Sesudah perubahan: backend 147/147, frontend 50/50, dan `npm run build` berhasil.
