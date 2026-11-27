# T-021: Uji TC-05 (B-LT dan B-LTA) dengan validator independen pyHanko

| Butir | Isi |
|---|---|
| Tanggal | 27 Nov 2026 |
| Jenis | Pengujian dan temuan kompatibilitas |
| Sprint | 3 |
| Kebutuhan terkait | KF-16, KF-17, TC-05 (juga menyentuh KNF-03) |
| Lingkungan | Container Linux, Temurin JDK 25.0.1, backend `app-server` jar dari branch `sprint3-halaman-pki-log`, pyHanko 0.38.2 (CLI 0.6.0), pyhanko-certvalidator 0.33.0, cryptography 50.0.2 |
| Status | Lulus di validator aplikasi dan pyHanko. Adobe Reader: RSA dan document timestamp sah dan "LTV enabled" (T-024) |

## Tujuan
TC-05: "Tambahkan B-LT dan B-LTA. Hasil yang diharapkan: DSS dan document timestamp ada, dan validasi tetap sah."
T-012 sudah membuktikan ini di JUnit dan dengan validator aplikasi sendiri. Uji ini menambahkan **validator independen**
yang tidak berbagi kode dengan aplikasi, dan memeriksa dokumen yang dihasilkan **lewat aplikasi pengguna**
(`POST /api/me/sign`, otomatis hybrid B-LTA).

## Langkah
1. Jalankan backend dengan folder data baru (PKI uji dibuat otomatis).
2. Buat `tc05-surat.pdf` satu halaman (`SamplePdf`).
3. Daftar akun `tc05b.uji` (TOTP), tanda tangani `tc05-surat.pdf` lewat `POST /api/me/sign` (Visible). Waktu respons
   1,6 detik, berkas tersimpan sebagai `tc05-surat-ttd.pdf`.
4. Verifikasi dengan aplikasi (`POST /api/me/verify`).
5. Pembanding dari endpoint pengembang `POST /api/sign` dengan `level` = `B-T`, `B-LT`, `B-LTA`
   (penanda tangan uji non-akun).
6. Unduh `rsa-root.crt` dan `mldsa-root.crt` dari `GET /api/pki/ca/...`, sebagai satu-satunya jangkar kepercayaan.
7. Baca struktur dengan `python scripts/lapisan-pyhanko.py <berkas>`.
8. **Matikan backend**, sehingga titik distribusi CRL `http://127.0.0.1:8085/api/pki/crl/...` tidak bisa dihubungi.
   Lalu validasi tiap berkas secara AdES (ETSI EN 319 102-1):
   `pyhanko sign adesverify --executive-summary --trust-replace --trust rsa-root.crt --trust mldsa-root.crt <berkas>`

## Hasil

### Struktur (langkah 7)
| Berkas | Revisi | Tanda tangan | Document timestamp | DSS |
|---|---|---|---|---|
| `B-T` (pengembang) | 3 | RSA (rev 2), ML-DSA (rev 3), `ETSI.CAdES.detached` | - | tidak ada |
| `B-LT` (pengembang) | 4 | sama | - | 8 sertifikat, 8 CRL, 2 VRI |
| `B-LTA` (pengembang) | 5 | sama | `/DocTimeStamp`, `ETSI.RFC3161` (rev 5) | 8 sertifikat, 8 CRL, 2 VRI |
| `tc05-surat-ttd.pdf` (aplikasi) | 5 | sama | `/DocTimeStamp`, `ETSI.RFC3161` (rev 5) | 8 sertifikat, 8 CRL, 2 VRI |

### Validator aplikasi (langkah 4)
Status `SAH`, hybrid lengkap, tanpa peringatan. Lapisan: rev 2 RSA B-T SAH, rev 3 ML-DSA B-T SAH, rev 4 data validasi
LT SAH, rev 5 document timestamp LTA SAH. Perlindungan sampai 26 Nov 2028 (masa berlaku rantai TSA RSA G1). Tidak
disarankan diperpanjang.

### pyHanko tanpa akses CRL daring (langkah 8)
| Berkas | RSA (Signature1) | ML-DSA (Signature2) | Keterangan pyHanko |
|---|---|---|---|
| `B-T` | INVALID | INVALID | "no revocation information could be found for intermediate certificate" (CA Antara RSA dan ML-DSA) |
| `B-LT` | VALID | VALID | Peringatan: tidak ada document timestamp setelah tanda tangan, "Failed to get control time for point-in-time validation" |
| `B-LTA` | VALID | VALID | Tanpa peringatan |
| `tc05-surat-ttd.pdf` | VALID | VALID | Tanpa peringatan |

Dengan backend menyala, `pyhanko sign validate --pretty-print` juga menyatakan kedua tanda tangan pada
`tc05-surat-ttd.pdf` **VALID**: "The signature is cryptographically sound", mekanisme `sha256_rsa` dan `mldsa65`,
timestamp tanda tangan dari TSA Uji RSA G1 dan TSA Uji ML-DSA dipercaya, dan "All modifications relate to signing
and form filling operations".

## Analisis
- **TC-05 lulus.** Dokumen dari aplikasi memuat DSS (sertifikat, CRL, VRI) dan document timestamp RFC 3161, dan tetap
  dinyatakan sah, baik oleh validator aplikasi maupun oleh pyHanko.
- Uji tanpa jaringan memperlihatkan fungsi tiap lapisan secara nyata. B-T tidak bisa divalidasi tanpa CRL daring,
  B-LT bisa karena CRL ada di DSS, dan B-LTA lolos tanpa peringatan karena document timestamp memberi bukti waktu
  (proof of existence) untuk data validasi di DSS.
- **Temuan kompatibilitas baru:** pyHanko 0.38 (dengan cryptography 50) memverifikasi **ML-DSA-65** dalam CMS PAdES
  dan menyatakannya sah, termasuk rantai ML-DSA dan TSA ML-DSA. Ini validator pihak ketiga pertama dalam proyek ini
  yang menerima tanda tangan ML-DSA aplikasi (Adobe dan PdfCraft menolak, T-002 dan T-011). Ini juga bukti silang
  bahwa OID dan struktur CMS ML-DSA yang dihasilkan sesuai standar (RFC 9882 untuk ML-DSA di CMS, mengikuti T-004
  dan T-019).
- Jumlah CRL di DSS (8) lebih banyak dari jumlah CA (4). Ini karena CRL dikumpulkan per rantai (dua tanda tangan dan
  dua TSA), dan tidak mengganggu validasi. Penghapusan duplikat bisa dipertimbangkan untuk memperkecil berkas, tetapi
  tidak wajib.

## Tindak lanjut
- ~~Buka `tc05-surat-ttd.pdf` di Adobe Reader di laptop dan periksa apakah tanda tangan RSA dinyatakan "LTV enabled"
  setelah Root CA Uji RSA dipercaya.~~ Selesai 27 Nov 2026: tanda tangan RSA dan document timestamp dinyatakan
  "LTV enabled" oleh Adobe (T-024).
- Pertimbangkan pyHanko sebagai validator pembanding di laporan pengujian Sprint 5, di samping validator aplikasi.
