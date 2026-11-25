package id.pkl.signingpqc.core.validation;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import id.pkl.signingpqc.core.SignatureAlgorithm;

/**
 * Batas waktu aman setiap keluarga algoritma tanda tangan ("RSA", "ML-DSA").
 *
 * <p>Bawaan mengikuti NIST IR 8547 (draf): tanda tangan RSA dianggap tidak aman mulai 1 Januari 2035. ML-DSA belum
 * punya batas. Tabel bisa diganti untuk simulasi kebijakan lain.
 */
public record AlgorithmPolicy(Map<String, Instant> unsafeFrom) {

    public static final Instant RSA_UNSAFE_FROM = Instant.parse("2035-01-01T00:00:00Z");

    public AlgorithmPolicy {
        unsafeFrom = Map.copyOf(unsafeFrom);
    }

    public static AlgorithmPolicy defaults() {
        return new AlgorithmPolicy(Map.of("RSA", RSA_UNSAFE_FROM));
    }

    public static String family(SignatureAlgorithm algorithm) {
        return algorithm.isPostQuantum() ? "ML-DSA" : "RSA";
    }

    public Optional<Instant> unsafeFrom(SignatureAlgorithm algorithm) {
        return Optional.ofNullable(unsafeFrom.get(family(algorithm)));
    }

    /** Keluarga yang dikenal kebijakan ini. ML-DSA ada di sini walaupun belum punya batas aman. */
    private static final Set<String> KNOWN_FAMILIES = Set.of("RSA", "ML-DSA");

    /**
     * Keluarga dari nama algoritma tampilan atau OID, misalnya "RSA (SHA-256)" atau 1.2.840.113549.1.1.12 menjadi
     * "RSA", "ML-DSA-65" atau 2.16.840.1.101.3.4.3.18 menjadi "ML-DSA".
     */
    public static String familyOf(String algorithmName) {
        if (algorithmName == null) {
            return "-";
        }
        if (algorithmName.startsWith("ML-DSA") || algorithmName.matches("2\\.16\\.840\\.1\\.101\\.3\\.4\\.3\\.1[789]")) {
            return "ML-DSA";
        }
        if (algorithmName.startsWith("RSA") || algorithmName.startsWith("1.2.840.113549.1.1.")) {
            return "RSA";
        }
        return algorithmName;
    }

    /**
     * Batas aman untuk nama algoritma tampilan (lihat {@link #familyOf(String)}). Algoritma yang tidak dikenal
     * dianggap tidak aman sejak awal (fail-closed), bukan aman selamanya.
     */
    public Optional<Instant> unsafeFromName(String algorithmName) {
        String family = familyOf(algorithmName);
        if (!KNOWN_FAMILIES.contains(family)) {
            return Optional.of(Instant.EPOCH);
        }
        return Optional.ofNullable(unsafeFrom.get(family));
    }
}
