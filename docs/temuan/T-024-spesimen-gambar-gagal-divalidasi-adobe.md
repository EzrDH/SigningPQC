# T-024: Tanda tangan RSA dengan spesimen gambar gagal divalidasi Adobe Reader

| Butir | Isi |
|---|---|
| Tanggal | 27 November 2026 |
| Jenis | Temuan kompatibilitas |
| Sprint | 3 |
| Kebutuhan terkait | KF-12 (tanda tangan visible), TC-05 |
| Lingkungan | Windows 11, Adobe Acrobat Reader (versi terbaru per 27 Nov 2026); container Linux, Temurin 25.0.1, PDFBox 3.0.8, pyHanko 0.38.2 |
| Versi kode | ditemukan pada 7d8d75b, diperbaiki pada commit sesudahnya (cabang `sprint3-halaman-pki-log`) |
| Status | Diperbaiki, dikonfirmasi di Adobe Reader |

## Ringkasan
Saat memeriksa ulang keluaran T-016 di Adobe Reader, tanda tangan RSA (Rev. 1) yang memakai spesimen gambar
selalu tampil "An error occurred while attempting to validate this signature" dengan "Last Checked: Never".
Tanda tangan yang sama dengan tampilan teks divalidasi normal. pyHanko dan validator aplikasi menilai semua
berkas SAH. Penyebabnya adalah bentuk appearance: `/Resources /XObject` langsung menunjuk gambar. Kini gambar
dibungkus form XObject dalam, dan `/DecodeParms` gambar ditulis langsung.

## Langkah
1. Jalankan backend dengan data uji baru, buat akun dengan dan tanpa spesimen, lalu tanda tangani dua PDF
   sumber: hasil PDFBox (`tc05-surat.pdf`) dan PDF buatan tangan (`surat-uji.pdf`).
2. Empat varian dibuat dengan skrip uji (`varian.mjs`):
   - A: PDF PDFBox + spesimen PNG transparan
   - B: PDF buatan tangan, tanpa spesimen (tampilan teks)
   - C: PDF PDFBox + spesimen PNG opak
   - D: PDF buatan tangan + spesimen PNG opak
3. Buka keempatnya di Adobe Reader, buka panel Signatures, dan rentangkan tiap tanda tangan.
4. Bandingkan objek revisi 1 antar varian (`python scripts/revisi-pdf.py berkas.pdf 1`, isi stream biner dibuang), dan periksa sintaks
   dengan qpdf (`pikepdf.check_pdf_syntax`).

## Hasil

| Varian | Spesimen | Rev. 1 (RSA) di Adobe |
|---|---|---|
| A | gambar transparan | galat, Last Checked: Never |
| B | teks | "Document has not been modified", identitas belum dipercaya |
| C | gambar opak | galat, Last Checked: Never |
| D | gambar opak | galat, Last Checked: Never |

Tangkapan layar: `img/T-024-adobe-a-pdfbox-spesimen-transparan.png`, `img/T-024-adobe-b-tanpa-spesimen.png`,
`img/T-024-adobe-c-pdfbox-spesimen-opak.png`, `img/T-024-adobe-d-buatan-tangan-spesimen-opak.png`.

- Perbedaan revisi 1 antara B dan D hanya isi appearance: dua font Helvetica (B) diganti satu gambar `/Im1`
  beserta `/DecodeParms` sebagai objek terpisah (D). ByteRange, tabel xref, kamus /Sig, dan CMS sama bentuknya.
- qpdf tidak menemukan galat sintaks pada keempat berkas. pyHanko menilai semua tanda tangan RSA SAH.
- Transparansi gambar dan asal PDF bukan penyebab (C dan D gagal juga).
- Rev. 2 (ML-DSA) tetap "invalid" di semua varian, sesuai keterbatasan Adobe yang tercatat di T-011.

## Analisis
Laporan pengembang lain dengan gejala serupa (Acrobat: "Expected a dict object", sekitar 30 varian diuji)
menyimpulkan Acrobat gagal memvalidasi tanda tangan bila `/Resources /XObject` pada appearance langsung
menunjuk gambar, dan berhasil bila gambar digambar lewat form XObject dalam yang punya `/Resources` sendiri
(https://github.com/tetitu/tetitulz.pdf/pull/8). Bentuk bersarang ini juga yang dipakai Acrobat sendiri
(lapisan n0/n2). Hasil varian di atas cocok dengan penjelasan itu.

Perbaikan di `SignatureAppearance.specimenForm`:
- appearance `/N` kini hanya berisi `/Form1 Do`; form dalam (BBox sama) berisi gambar `/Im1`;
- `/DecodeParms` gambar dan SMask dijadikan kamus langsung agar tidak ada referensi tak langsung di kamus gambar
  (bentuk ini juga sesuai keluaran Acrobat). Ini langkah pengaman tambahan, belum dibuktikan terpisah.

Tanda tangan yang sudah dibuat sebelum perbaikan tidak bisa diperbaiki tanpa merusaknya; dokumen tersebut perlu
ditandatangani ulang dari salinan aslinya. Validitas kriptografisnya sendiri tidak terpengaruh (pyHanko SAH).

Uji baru `VisibleSignatureTest.spesimenGambarDibungkusFormDalamAgarTerbacaAdobe` gagal sebelum perbaikan
("appearance tidak boleh langsung menunjuk gambar: Im1") dan lulus sesudahnya. Seluruh uji backend lulus
(signer-core 7, pki-test 74, app-server 79). Tiga PDF uji baru (E transparan, F opak, G halaman lanskap
ber-/Rotate) dinilai SAH oleh pyHanko dengan root uji sebagai jangkar.

### Konfirmasi Adobe sesudah perbaikan (27 Nov 2026, 22.14 WIB)

| Berkas | Spesimen | Rev. 1 (RSA) di Adobe |
|---|---|---|
| E | gambar transparan | "Document has not been modified since this signature was applied", identitas belum dipercaya |
| F | gambar opak | sama dengan E |
| G | gambar transparan, halaman ber-/Rotate 90 | sama dengan E; spesimen tampil tegak di kanan bawah halaman lanskap |

Tangkapan layar: `img/T-024-adobe-e-perbaikan-spesimen-transparan.png`,
`img/T-024-adobe-f-perbaikan-spesimen-opak.png`, `img/T-024-adobe-g-perbaikan-halaman-lanskap.png`.

Galat "An error occurred while attempting to validate this signature" hilang pada ketiga berkas, jadi perbaikan
bekerja. Status "validity is unknown" dan "embedded timestamp ... could not be verified" muncul karena root uji
belum diimpor sebagai tepercaya di Adobe, bukan karena tanda tangannya. Rev. 2 (ML-DSA) tetap "invalid" sesuai
T-011. Berkas G sekaligus menutup uji visual halaman berotasi yang tertunda di T-014.

## Tindak lanjut
- Selesai: konfirmasi E, F, G di Adobe Reader.
- Untuk membaca status identitas dan LTV, impor
  `Root-CA-Uji-RSA.crt` sebagai root tepercaya di Adobe (Preferences, Signatures, Identities & Trusted
  Certificates).
- Tidak dipisahkan mana dari dua perubahan yang menentukan; keduanya dipertahankan karena sama-sama bentuk yang dipakai Acrobat.
