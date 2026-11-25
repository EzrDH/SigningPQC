package id.pkl.signingpqc.core.ltv;

/**
 * Profil document timestamp: digest message imprint dan ruang /Contents yang dicadangkan.
 *
 * <p>Ruang mencakup token beserta rantai sertifikat TSA (RSA sekitar 5 KB, ML-DSA sekitar 25 KB).
 */
public enum TimestampProfile {
    /** TSA RSA: imprint SHA-256. */
    RSA("SHA-256", 16 * 1024),
    /** TSA ML-DSA: imprint SHA-512 (RFC 9882). */
    ML_DSA("SHA-512", 48 * 1024);

    private final String digestName;
    private final int reservedBytes;

    TimestampProfile(String digestName, int reservedBytes) {
        this.digestName = digestName;
        this.reservedBytes = reservedBytes;
    }

    public String digestName() {
        return digestName;
    }

    public int reservedBytes() {
        return reservedBytes;
    }
}
