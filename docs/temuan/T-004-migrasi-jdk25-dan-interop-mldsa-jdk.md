# T-004: Migrasi ke JDK 25 LTS dan uji silang ML-DSA bawaan JDK

| Butir | Isi |
|---|---|
| Tanggal | Rabu, 25 November 2026 |
| Jenis | Pengujian |
| Sprint | 0 |
| Kebutuhan terkait | KNF-01, KNF-04, KNF-11; TC-01, TC-03, TC-10 |
| Lingkungan | Windows, Eclipse Temurin JDK 25.0.5.1+2 LTS, Maven 3.9.16 (mvnw), Spring Boot 4.2.0, Bouncy Castle 1.87, PDFBox 3.0.8, OpenSSL 3.5.9, Node.js 24.22.1 |
| Versi kode | commit sesudah `d226301` (lihat riwayat git, pesan "Migrasi ke JDK 25 LTS") |
| Status | Lulus |

## Ringkasan

Proyek dipindahkan dari JDK 21 ke JDK 25 LTS beserta versi terbaru Spring Boot dan Maven. Semua uji lama tetap lulus. Uji baru menunjukkan tanda tangan ML-DSA-65 dari Bouncy Castle dapat diverifikasi oleh ML-DSA bawaan JDK 25 (JEP 497, provider SUN), dan sebaliknya.

## Perubahan setup

| Komponen | Sebelum | Sesudah |
|---|---|---|
| JDK | Oracle JDK 21.0.8 | Eclipse Temurin JDK 25.0.5.1 LTS (dipasang lewat winget, JAVA_HOME sistem otomatis ke JDK 25) |
| `maven.compiler.release` | 21 | 25 |
| Spring Boot | 4.1.3 | 4.2.0 |
| Maven (wrapper) | 3.9.11 | 3.9.16 |
| Plugin Maven | bawaan Maven | compiler 3.16.0, surefire 3.6.1, jar 3.5.1, resources 3.5.0, enforcer 3.7.0 |
| Pemeriksaan versi | tidak ada | maven-enforcer: build berhenti bila bukan JDK 25 |
| Frontend | Vue 3.5, Vite 8, TypeScript 6.0 | tetap (sudah terbaru yang kompatibel; TypeScript 7 belum didukung vue-tsc) |

JDK 21 dan JRE 17 tidak dihapus dan masih terpasang.

## Langkah

1. `winget install --id EclipseAdoptium.Temurin.25.JDK --exact`
2. `cd backend`, set `JAVA_HOME` ke `C:\Program Files\Eclipse Adoptium\jdk-25.0.5.10002-hotspot`
3. `.\mvnw.cmd clean test`
4. `.\mvnw.cmd -q -pl signer-core compile exec:java`, lalu `scripts\cek-openssl.ps1`
5. `.\mvnw.cmd -pl app-server -am spring-boot:run`, buka `/api/health`
6. `cd frontend`, `npm run build`

## Hasil

| Uji | Hasil |
|---|---|
| `HybridSigningSpikeTest` (2 kasus) | Lulus |
| `JdkMlDsaInteropTest`: JDK memverifikasi tanda tangan BC | Lulus |
| `JdkMlDsaInteropTest`: BC memverifikasi tanda tangan JDK | Lulus |
| SpikeMain: RSA dan ML-DSA sah | Lulus (CMS RSA 1.931 B, ML-DSA 9.367 B) |
| OpenSSL 3.5.9 `cms -verify` kedua tanda tangan | CMS Verification successful (2/2) |
| Backend `/api/health` | `{"java":"25.0.5.1","bouncyCastle":"1.87","pdfbox":"3.0.8","status":"ok"}` |
| Build frontend Vue | Berhasil |
| Build dengan JDK 21 | Ditolak enforcer dengan pesan "SigningPQC membutuhkan JDK 25 LTS" (sesuai harapan) |

Waktu tanda tangan hybrid satu kali jalan: 172 ms (jalan pertama setelah build, JVM belum hangat). Angka ini belum dibandingkan dengan JDK 21 secara adil; pengukuran resmi 100 kali dilakukan di Sprint 5.

### Rincian uji silang JDK

- **BC ke JDK:** PDF ditandatangani ML-DSA-65 dengan Bouncy Castle. Nilai message-digest pada atribut bertanda tangan dicocokkan dengan SHA-512 isi PDF yang dihitung `MessageDigest` JDK. Kunci publik dari sertifikat diubah menjadi kunci JDK (`KeyFactory "ML-DSA"`, provider SUN), lalu `Signature "ML-DSA"` JDK memverifikasi tanda tangan atas atribut bertanda tangan.
- **JDK ke BC:** pasangan kunci dibangkitkan oleh JDK (`KeyPairGenerator "ML-DSA-65"`, SUN). Sertifikat dan tanda tangan CMS dibuat dengan provider SUN, sedangkan struktur CMS tetap dari Bouncy Castle. Verifikator aplikasi (Bouncy Castle) menyatakan tanda tangan sah.

## Analisis

- Migrasi tidak memerlukan perubahan kode selain konfigurasi build. Semua pustaka yang dipakai sudah mendukung Java 25.
- Sekarang ada tiga implementasi ML-DSA yang saling cocok: Bouncy Castle 1.87, JDK 25 (SUN), dan OpenSSL 3.5.9. Ini memperkuat bukti interoperabilitas (KNF-04) dan sejalan dengan rencana uji lintas implementasi pada PRD.
- Ada satu insiden saat pengujian: perintah penghentian proses ikut menghentikan backend JDK 21 yang sedang dijalankan pengguna di port 8085. Backend perlu dijalankan ulang. Untuk pengujian berikutnya, proses yang dihentikan dibatasi hanya yang dibuat oleh pengujian itu sendiri.

## Tindak lanjut

- Terminal dan VS Code yang sudah terbuka perlu ditutup dan dibuka ulang supaya memakai JAVA_HOME JDK 25. Di VS Code, pilih JDK 25 untuk proyek (Java: Configure Java Runtime).
- Tanyakan ke pembimbing lapangan versi Java yang dipakai sistem BSrE, untuk catatan kesiapan integrasi.
