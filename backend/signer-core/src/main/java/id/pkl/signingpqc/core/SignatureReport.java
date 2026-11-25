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
        String message) {

    public boolean valid() {
        return cryptographicallyValid && signingCertificateMatches;
    }
}
