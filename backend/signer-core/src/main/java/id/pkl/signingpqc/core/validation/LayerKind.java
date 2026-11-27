package id.pkl.signingpqc.core.validation;

/** Jenis lapisan di dalam PDF, mengikuti gambar papan tulis (1-2 tanda tangan, 3 DSS, 4-6 timestamp). */
public enum LayerKind {
    TANDA_TANGAN,
    DATA_VALIDASI,
    DOCUMENT_TIMESTAMP
}
