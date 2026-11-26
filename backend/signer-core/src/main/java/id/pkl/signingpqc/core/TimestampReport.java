package id.pkl.signingpqc.core;

import java.time.Instant;

/** Hasil pemeriksaan satu token timestamp RFC 3161 (signature timestamp atau document timestamp). */
public record TimestampReport(
        Instant time,
        String tsaSubject,
        String serialHex,
        String signatureAlgorithm,
        String imprintAlgorithm,
        boolean imprintMatches,
        boolean tokenValid,
        String message) {

    public boolean valid() {
        return imprintMatches && tokenValid;
    }
}
