package id.pkl.signingpqc.core;

import java.util.List;

/**
 * Posisi dan isi tambahan kotak tanda tangan yang tampil di halaman (dipakai untuk tanda tangan RSA).
 *
 * Semua ukuran dalam sistem koordinat halaman tanpa /Rotate. Pada halaman berotasi 90 atau 270 derajat lebar dan
 * tinggi yang tampil di layar tertukar; batas minimal 120 x 60 point berlaku untuk ukuran yang tampil dan diperiksa
 * oleh SignatureAppearance yang tahu rotasi halaman.
 *
 * @param pageIndex nomor halaman, mulai dari 0
 * @param x jarak dari tepi kiri halaman (point, 1 point = 1/72 inci)
 * @param yFromTop jarak dari tepi atas halaman (point)
 * @param width lebar kotak (point)
 * @param height tinggi kotak (point)
 * @param extraLines baris tambahan di bawah nama, misalnya jabatan
 * @param specimenPng gambar spesimen opsional (PNG atau JPEG)
 */
public record VisibleSignature(int pageIndex, float x, float yFromTop, float width, float height,
        List<String> extraLines, byte[] specimenPng) {

    public VisibleSignature {
        // Salah satu orientasi harus memenuhi minimal 120 x 60; orientasi yang benar diperiksa terhadap rotasi halaman.
        if (!fitsMinimum(width, height) && !fitsMinimum(height, width)) {
            throw new IllegalArgumentException("Kotak tanda tangan minimal 120 x 60 point");
        }
        extraLines = extraLines == null ? List.of() : List.copyOf(extraLines);
        specimenPng = specimenPng == null ? null : specimenPng.clone();
    }

    static boolean fitsMinimum(float width, float height) {
        return width >= 120 && height >= 60;
    }

    /**
     * Memeriksa kotak muat di halaman dokumen (batas CropBox dan ukuran minimal sesuai rotasi), sama dengan pemeriksaan
     * saat menandatangani. Dipakai sebelum kunci dibuka agar konfirmasi pengguna tidak terbuang.
     *
     * @throws IllegalArgumentException bila halaman tidak ada atau kotak tidak muat
     */
    public void requireFits(org.apache.pdfbox.pdmodel.PDDocument document) {
        SignatureAppearance.rectangle(document, this);
    }

    /** Kotak dengan teks standar (tanpa spesimen gambar). */
    public VisibleSignature(int pageIndex, float x, float yFromTop, float width, float height, List<String> extraLines) {
        this(pageIndex, x, yFromTop, width, height, extraLines, null);
    }

    /** Gambar spesimen (PNG atau JPEG); bila ada, kotak hanya berisi gambar ini tanpa teks. Null bila tidak ada. */
    @Override
    public byte[] specimenPng() {
        return specimenPng == null ? null : specimenPng.clone();
    }

    /** Kotak 220 x 90 point di kanan bawah halaman pertama, dengan margin 40 point. */
    public static VisibleSignature bottomRightOfFirstPage(float pageWidth, float pageHeight, List<String> extraLines) {
        float width = 220;
        float height = 90;
        return new VisibleSignature(0, pageWidth - width - 40, pageHeight - height - 40, width, height, extraLines);
    }
}
