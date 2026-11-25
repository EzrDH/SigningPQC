package id.pkl.signingpqc.core;

import java.util.List;

/**
 * Posisi dan isi tambahan kotak tanda tangan yang tampil di halaman (dipakai untuk tanda tangan RSA).
 *
 * @param pageIndex nomor halaman, mulai dari 0
 * @param x jarak dari tepi kiri halaman (point, 1 point = 1/72 inci)
 * @param yFromTop jarak dari tepi atas halaman (point)
 * @param width lebar kotak (point)
 * @param height tinggi kotak (point)
 * @param extraLines baris tambahan di bawah nama, misalnya jabatan
 */
public record VisibleSignature(int pageIndex, float x, float yFromTop, float width, float height,
        List<String> extraLines) {

    public VisibleSignature {
        if (width < 120 || height < 60) {
            throw new IllegalArgumentException("Kotak tanda tangan minimal 120 x 60 point");
        }
        extraLines = extraLines == null ? List.of() : List.copyOf(extraLines);
    }

    /** Kotak 220 x 90 point di kanan bawah halaman pertama, dengan margin 40 point. */
    public static VisibleSignature bottomRightOfFirstPage(float pageWidth, float pageHeight, List<String> extraLines) {
        float width = 220;
        float height = 90;
        return new VisibleSignature(0, pageWidth - width - 40, pageHeight - height - 40, width, height, extraLines);
    }
}
