package id.pkl.signingpqc.pki;

import id.pkl.signingpqc.core.SignatureAlgorithm;

/**
 * Keluarga hierarki CA uji. Kedua keluarga punya bentuk yang sama (Root, CA antara, entitas akhir)
 * supaya perbandingan RSA dan ML-DSA adil.
 */
public enum PkiFamily {
    RSA("RSA", SignatureAlgorithm.RSA_4096, SignatureAlgorithm.RSA_3072, SignatureAlgorithm.RSA_3072),
    ML_DSA("ML-DSA", SignatureAlgorithm.ML_DSA_87, SignatureAlgorithm.ML_DSA_65, SignatureAlgorithm.ML_DSA_65);

    private final String label;
    private final SignatureAlgorithm rootAlgorithm;
    private final SignatureAlgorithm intermediateAlgorithm;
    private final SignatureAlgorithm endEntityAlgorithm;

    PkiFamily(String label, SignatureAlgorithm rootAlgorithm, SignatureAlgorithm intermediateAlgorithm,
            SignatureAlgorithm endEntityAlgorithm) {
        this.label = label;
        this.rootAlgorithm = rootAlgorithm;
        this.intermediateAlgorithm = intermediateAlgorithm;
        this.endEntityAlgorithm = endEntityAlgorithm;
    }

    public String label() {
        return label;
    }

    public SignatureAlgorithm rootAlgorithm() {
        return rootAlgorithm;
    }

    public SignatureAlgorithm intermediateAlgorithm() {
        return intermediateAlgorithm;
    }

    public SignatureAlgorithm endEntityAlgorithm() {
        return endEntityAlgorithm;
    }

    /** Penanda pendek untuk nama berkas dan URL, misalnya "rsa" atau "mldsa". */
    public String slug() {
        return this == RSA ? "rsa" : "mldsa";
    }
}
