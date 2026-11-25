package id.pkl.signingpqc.core.validation;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

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
}
