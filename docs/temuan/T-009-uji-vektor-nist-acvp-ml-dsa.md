# T-009: Uji vektor NIST ACVP untuk ML-DSA (FIPS 204)

| Butir | Isi |
|---|---|
| Tanggal | 25 Nov 2026 |
| Jenis | Pengujian |
| Sprint | 1 (dikerjakan lebih awal) |
| Kebutuhan terkait | KNF-01, TC-08 |
| Lingkungan | Windows 11 (laptop), Temurin JDK 25.0.5.1, Bouncy Castle 1.87; juga di Linux (container) |
| Versi kode | commit "Sprint 1: uji vektor NIST ACVP ML-DSA" |
| Status | Lulus (100% grup) |

## Ringkasan
Implementasi ML-DSA Bouncy Castle 1.87 yang dipakai aplikasi diuji dengan vektor resmi NIST ACVP (algoritma ML-DSA,
revision FIPS204, vsId 42). Ada 39 grup dan 615 kasus uji untuk keyGen, sigGen, dan sigVer pada ML-DSA-44/65/87.
Semua grup dan semua kasus lolos, termasuk 144 tanda tangan rusak pada sigVer yang benar ditolak.

## Langkah
1. Vektor (berkas `internalProjection.json` dari NIST ACVP-Server, berisi masukan dan jawaban) disimpan dalam bentuk
   gzip di `backend/signer-core/src/test/resources/acvp-mldsa/` (sekitar 8 MB).
2. `cd backend`, set `JAVA_HOME` ke JDK 25, lalu `.\mvnw.cmd -B install`. Uji `AcvpMlDsaTest` berjalan otomatis
   dan mencetak ringkasan per grup.

## Hasil

| Mode | Grup | Kasus | Hasil |
|---|---|---|---|
| keyGen (seed ke pk dan sk) | 3 (ML-DSA-44, 65, 87) | 75 | 75/75 lolos |
| sigGen deterministik | 12 | 180 | 180/180 lolos (tanda tangan sama persis byte per byte) |
| sigGen acak (rnd dari vektor) | 12 | 180 | 180/180 lolos |
| sigVer | 12 | 180 | 180/180 lolos, 36 diterima dan 144 ditolak sesuai harapan |
| **Total** | **39** | **615** | **100%** |

Setiap mode sigGen dan sigVer mencakup tiga varian: external pure (dengan context), external preHash (12 fungsi
hash: SHA2-224/256/384/512, SHA2-512/224, SHA2-512/256, SHA3-224/256/384/512, SHAKE-128, SHAKE-256), dan internal
(dengan dan tanpa externalMu). Waktu jalan seluruh uji sekitar 1 detik.

## Analisis
- Jalur external pure diuji dengan API `MLDSASigner` biasa (update lalu generateSignature, dengan context). Ini
  jalur yang dipakai aplikasi untuk CMS/PAdES (context kosong), jadi hasil ini langsung mendukung keabsahan
  tanda tangan ML-DSA di aplikasi.
- Jalur preHash dan internal dihitung lewat mu = SHAKE256(tr || M', 64) sesuai FIPS 204 Algoritma 2, 4, dan 7,
  lalu memakai `generateMuSignature` dan `verifyMuSignature` Bouncy Castle. Dengan cara ini semua 12 fungsi hash
  preHash bisa diuji, padahal kelas `HashMLDSASigner` Bouncy Castle hanya menyediakan SHA-512.
- Kunci privat dari vektor (encoding expanded) dibaca lewat PrivateKeyInfo RFC 9881. Pembacaan ini sekaligus
  menguji parser kunci ML-DSA yang dipakai saat membuka PKCS#12.
- `MLDSAPrivateKeyParameters.getPrivateKey()` sudah deprecated di BC 1.87. Uji memakai
  `withPreferredFormat(EXPANDED_KEY).getEncoded()`.
- Catatan: ini uji mandiri dengan vektor ACVP, bukan validasi CAVP resmi. Sertifikat CAVP hanya bisa diperoleh
  lewat laboratorium terakreditasi.

## Tindak lanjut
- Uji ini ikut berjalan di setiap build, sehingga pembaruan Bouncy Castle berikutnya otomatis diuji ulang.
- Pengukuran waktu tanda tangan hybrid 100 kali (TC-09) dilakukan setelah endpoint REST tersedia.
