package id.pkl.signingpqc.core;

import java.util.Map;

/** Nama algoritma yang mudah dibaca dari OID pada SignerInfo. */
public final class AlgorithmNames {

    private static final Map<String, String> SIGNATURE = Map.of(
            "1.2.840.113549.1.1.1", "RSA",
            "1.2.840.113549.1.1.11", "RSA (SHA-256)",
            "1.2.840.113549.1.1.10", "RSA-PSS",
            "2.16.840.1.101.3.4.3.17", "ML-DSA-44",
            "2.16.840.1.101.3.4.3.18", "ML-DSA-65",
            "2.16.840.1.101.3.4.3.19", "ML-DSA-87");

    private static final Map<String, String> DIGEST = Map.of(
            "2.16.840.1.101.3.4.2.1", "SHA-256",
            "2.16.840.1.101.3.4.2.2", "SHA-384",
            "2.16.840.1.101.3.4.2.3", "SHA-512",
            "2.16.840.1.101.3.4.2.12", "SHAKE256");

    private AlgorithmNames() {
    }

    public static String signature(String oid) {
        return SIGNATURE.getOrDefault(oid, oid);
    }

    public static String digest(String oid) {
        return DIGEST.getOrDefault(oid, oid);
    }

    /** Nama pendek untuk tampilan, misalnya "RSA-3072" atau "ML-DSA-65". */
    static String shortName(SignatureAlgorithm algorithm) {
        return algorithm.isPostQuantum() ? algorithm.keyAlgorithm() : "RSA-" + algorithm.keySize();
    }
}
