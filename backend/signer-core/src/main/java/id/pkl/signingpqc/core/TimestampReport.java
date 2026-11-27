package id.pkl.signingpqc.core;

import java.time.Instant;

/**
 * Hasil pemeriksaan satu token timestamp RFC 3161 (signature timestamp atau document timestamp).
 *
 * @param policyOid OID kebijakan TSA dari TSTInfo; null bila token tidak dapat dibaca
 */
public record TimestampReport(
        Instant time,
        String tsaSubject,
        String serialHex,
        String signatureAlgorithm,
        String imprintAlgorithm,
        boolean imprintMatches,
        boolean tokenValid,
        String message,
        String policyOid) {

    public TimestampReport(Instant time, String tsaSubject, String serialHex, String signatureAlgorithm,
            String imprintAlgorithm, boolean imprintMatches, boolean tokenValid, String message) {
        this(time, tsaSubject, serialHex, signatureAlgorithm, imprintAlgorithm, imprintMatches, tokenValid, message,
                null);
    }

    public boolean valid() {
        return imprintMatches && tokenValid;
    }
}
