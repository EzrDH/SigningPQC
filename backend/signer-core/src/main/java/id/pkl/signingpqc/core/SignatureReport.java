package id.pkl.signingpqc.core;

import java.time.Instant;

/** Hasil verifikasi satu tanda tangan di dalam PDF. */
public record SignatureReport(
        int order,
        String fieldName,
        String subFilter,
        String signatureAlgorithm,
        String digestAlgorithm,
        String signerSubject,
        Instant claimedSigningTime,
        int cmsSizeBytes,
        boolean coversWholeDocument,
        boolean signingCertificateMatches,
        boolean cryptographicallyValid,
        String message,
        TimestampReport signatureTimestamp) {

    /** Sah bila tanda tangan dan signing-certificate-v2 benar, dan signature timestamp (bila ada) juga sah. */
    public boolean valid() {
        return cryptographicallyValid && signingCertificateMatches
                && (signatureTimestamp == null || signatureTimestamp.valid());
    }

    /** Level PAdES baseline yang terpenuhi secara struktur: B-T bila ada signature timestamp sah, selain itu B-B. */
    public String padesLevel() {
        return signatureTimestamp != null && signatureTimestamp.valid() ? "B-T" : "B-B";
    }
}
