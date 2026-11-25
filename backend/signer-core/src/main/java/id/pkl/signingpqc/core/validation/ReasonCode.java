package id.pkl.signingpqc.core.validation;

/** Kode alasan hasil validasi beserta pesan yang mudah dipahami (KF-24). */
public enum ReasonCode {
    OK("Sah"),
    DOKUMEN_BERUBAH("Isi dokumen berubah setelah lapisan ini dibuat"),
    TANDA_TANGAN_TIDAK_COCOK("Nilai tanda tangan tidak cocok dengan sertifikat atau strukturnya rusak"),
    SERTIFIKAT_DICABUT("Sertifikat sudah dicabut sebelum atau pada waktu yang dibuktikan"),
    DICABUT_SETELAH_DITANDATANGANI("Sertifikat dicabut setelah tanda tangan dibuat; tanda tangan tetap sah"),
    SERTIFIKAT_TIDAK_BERLAKU("Sertifikat belum atau sudah tidak berlaku pada waktu yang dibuktikan"),
    RANTAI_TIDAK_SAH("Rantai sertifikat tidak memenuhi aturan RFC 5280"),
    ROOT_TIDAK_DIPERCAYA("Rantai sertifikat tidak berakhir di root yang dipercaya"),
    STATUS_PENCABUTAN_TIDAK_DIKETAHUI("Tidak ada CRL yang berlaku untuk membuktikan sertifikat belum dicabut"),
    DATA_VALIDASI_TIDAK_ADA("Data validasi (rantai atau CRL) tidak tersimpan di dokumen"),
    TSA_KEDALUWARSA_TANPA_PEMBARUAN("Sertifikat TSA atau CA-nya kedaluwarsa sebelum ada timestamp pembaruan"),
    ALGORITMA_TIDAK_AMAN_PADA_WAKTU_PEMERIKSAAN(
            "Algoritma lapisan ini sudah dianggap tidak aman sebelum ada lapisan pembaruan yang melindunginya"),
    WAKTU_PEMERIKSAAN_TIDAK_SAH("Waktu pemeriksaan lebih awal dari waktu lapisan dibuat"),
    PERUBAHAN_SETELAH_LAPISAN_TERAKHIR("Ada perubahan dokumen setelah lapisan terakhir yang tidak dilindungi"),
    TANDA_TANGAN_HYBRID_TIDAK_LENGKAP("Tanda tangan hybrid harus berisi RSA dan ML-DSA yang keduanya sah");

    private final String message;

    ReasonCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
