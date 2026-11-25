# T-010: REST lokal untuk alur TTE hybrid dan pengukuran waktu tanda tangan

| Butir | Isi |
|---|---|
| Tanggal | 25 Nov 2026 |
| Jenis | Pengujian |
| Sprint | 1 (dikerjakan lebih awal) |
| Kebutuhan terkait | KF-01 s.d. KF-08, KF-11 s.d. KF-15, KF-20, KNF-02, KNF-14, TC-01, TC-03, TC-04 (bagian sertifikat), TC-09 |
| Lingkungan | Windows 11 (laptop, Intel Core i7-12700H), Temurin JDK 25.0.5.1, Spring Boot 4.2.0, Bouncy Castle 1.87, PDFBox 3.0.8 |
| Versi kode | commit "Sprint 1: REST lokal PKI, TSA, tanda tangan hybrid, verifikasi" |
| Status | Lulus |

## Ringkasan
Backend sekarang menyediakan seluruh alur TTE hybrid lewat REST lokal di 127.0.0.1: membuat PKI uji, menerbitkan
dan mencabut penanda tangan, CRL, TSA RFC 3161 lewat HTTP, tanda tangan hybrid B-T, dan verifikasi. Ada 8 uji REST
ujung ke ujung, sehingga total uji menjadi 40, dan semuanya lolos di laptop. Waktu tanda tangan hybrid B-T rata-rata
24 ms di server dan 66 ms ujung ke ujung (100 kali). Angka ini jauh di bawah target 2 detik (KNF-02).

## Langkah
1. `cd backend`, set `JAVA_HOME` ke JDK 25, lalu `.\mvnw.cmd -B clean install`.
2. Jalankan backend: `.\mvnw.cmd -pl app-server spring-boot:run`, atau
   `java -jar app-server\target\app-server-0.1.0-SNAPSHOT.jar`.
3. Dari folder repositori: `powershell -ExecutionPolicy Bypass -File scripts\coba-rest.ps1 -Pdf "backend\spike-output\00-asli.pdf"`.
4. Untuk TC-09: `/api/sign` dipanggil 100 kali dengan curl, lalu waktu dicatat dari header `X-SigningPQC-Waktu-Ms`
   (waktu proses di server) dan dari stopwatch PowerShell (ujung ke ujung).

Uji di laptop memakai port 18085 dan folder data sementara, agar tidak mengganggu backend milikmu di port 8085.
Setelah selesai, hanya proses uji itu yang dihentikan (dicek lewat PID dan baris perintahnya).

## Hasil

Endpoint (lengkapnya di README bagian "REST lokal"):

| Endpoint | Fungsi |
|---|---|
| `GET /api/pki`, `POST /api/pki/setup` | Status PKI uji, membuat CA RSA/ML-DSA dan TSA |
| `POST /api/pki/signers`, `POST /api/pki/signers/{id}/revoke` | Menerbitkan dan mencabut pasangan sertifikat |
| `GET /api/pki/crl/{caId}.crl`, `GET /api/pki/ca/{caId}.crt` | CRL (alamat yang sama dengan CRL Distribution Points di sertifikat) dan sertifikat CA |
| `POST /api/tsa/rsa`, `POST /api/tsa/mldsa` | TSA RFC 3161 lewat HTTP |
| `POST /api/sign` | RSA visible (B-T), lalu ML-DSA invisible (B-T). Bila satu langkah gagal, tidak ada PDF yang dihasilkan |
| `POST /api/verify` | Hasil per tanda tangan, level PAdES, timestamp, dan ringkasan hybrid |

Uji REST `RestApiTest` (8 uji, semua lolos di laptop):

| Uji | Hasil |
|---|---|
| PKI belum ada, lalu dibuat (4 CA, 2 TSA); membuat ulang tanpa reset ditolak 409 | Lolos |
| Menerbitkan penanda tangan: RSA-3072 dan ML-DSA-65 dengan DN sama (termasuk T=jabatan) | Lolos |
| Tanda tangan hybrid lewat REST: 2 tanda tangan sah, B-T, nama berkas `*-ttd.pdf` | Lolos |
| Verifikasi lewat REST: semua sah dan hybrid lengkap; PDF diubah 1 byte terdeteksi "telah diubah" | Lolos |
| TSA lewat HTTP untuk RSA dan ML-DSA; TSA yang tidak dikenal dijawab 404 | Lolos |
| Pencabutan: CRL ditandatangani CA, berisi 1 sertifikat dicabut, nomor CRL naik; tanda tangan dengan sertifikat dicabut ditolak 409 | Lolos |
| PKI dimuat ulang dari folder data (PKCS#12 + state.json) setelah backend dimulai ulang | Lolos |
| Masukan salah: penanda tangan tidak ada (404), berkas bukan PDF (400), nama kosong (400) | Lolos |

Hasil `coba-rest.ps1` di laptop (backend dari JAR):

| No | Algoritma | Level | Sah | Menutup seluruh dokumen | TSA |
|---|---|---|---|---|---|
| 1 | RSA (SHA-256) | B-T | ya | tidak (memang revisi pertama) | CN=TSA Uji RSA |
| 2 | ML-DSA-65 | B-T | ya | ya | CN=TSA Uji ML-DSA |

TC-09, 100 kali tanda tangan hybrid B-T untuk PDF 1 halaman (962 byte):

| Ukuran | Rata-rata | Median | p95 | Min | Maks |
|---|---|---|---|---|---|
| Di server (RSA visible B-T + ML-DSA B-T) | 24,3 ms | 24 ms | 32 ms | 17 ms | 36 ms |
| Ujung ke ujung (curl, termasuk unggah dan unduh) | 65,7 ms | 63 ms | 88 ms | 45 ms | 105 ms |

Permintaan pertama setelah backend menyala butuh 323 ms karena JVM belum panas (JIT).

## Analisis
- Seluruh alur TTE hybrid sudah berjalan benar dari REST, sesuai target prototipe on lokus. Langkah berikutnya
  tinggal antarmuka Vue yang memanggil endpoint ini.
- Waktu tanda tangan jauh di bawah target 2 detik. Bagian terberat bukan ML-DSA, tetapi penyusunan PDF dan
  pembuatan dua token timestamp.
- Ukuran PDF hasil sekitar 250 KB untuk PDF asli 1 KB. Penyebabnya, ruang /Contents dicadangkan tetap (RSA 32 KB dan
  ML-DSA 88 KB, ditulis heksadesimal sehingga menjadi dua kali lipat). Ini bisa dikecilkan nanti dengan
  menghitung ruang dari ukuran CMS yang sebenarnya, atau dengan tidak menyertakan root di dalam CMS.
- Penemuan saat pengembangan: Spring MVC butuh opsi kompilator `-parameters` agar `@RequestParam` tanpa nama
  eksplisit bisa dipakai. Opsi `maven.compiler.parameters=true` ditambahkan di POM induk, karena proyek tidak
  memakai parent Spring Boot yang biasanya menyalakannya.
- DN di jawaban REST sekarang ditulis dengan nama atribut yang mudah dibaca (`T=Kepala Seksi Uji`), bukan bentuk
  OID (`2.5.4.12=#0c...`).
- Keamanan: backend hanya mendengar di 127.0.0.1. Kata sandi PKCS#12 uji ada di `application.properties` dan hanya
  boleh dipakai di lingkungan uji. Folder data ada di luar repositori (`%USERPROFILE%\SigningPQC-uji`).

## Tindak lanjut
- Buka `backend\spike-output\00-asli-ttd.pdf` (hasil `coba-rest.ps1`) di PdfCraft dan Adobe Reader, lalu catat hasilnya
  (langkah 6 Sprint 1).
- Sprint 2: halaman Vue untuk PKI uji, tanda tangan (unggah PDF, pilih penanda tangan, posisi kotak), dan verifikasi.
- Sprint 2: validator lengkap (rantai, CRL pada waktu timestamp) dan kebijakan AND untuk status hybrid.
