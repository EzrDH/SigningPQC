package id.pkl.signingpqc.core.validation;

/** Status gabungan dokumen atau satu lapisan. */
public enum OverallStatus {
    /** Semua lapisan sah dan rantai bukti tidak terputus. */
    SAH,
    /** Ada kerusakan kriptografis, dokumen berubah, sertifikat dicabut, atau kebijakan hybrid dilanggar. */
    TIDAK_SAH,
    /** Tidak ada kerusakan, tetapi bukti waktunya tidak cukup (TSA kedaluwarsa, algoritma tidak aman, data hilang). */
    TIDAK_DAPAT_DIBUKTIKAN
}
