package id.pkl.signingpqc.core;

import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;

/**
 * Algoritma tanda tangan yang didukung beserta digest yang dipakai pada CMS.
 *
 * <p>Untuk ML-DSA, digest SHA-512 mengikuti RFC 9882. Untuk RSA dipakai SHA-256 (PKCS#1 v1.5),
 * yang dikenali Adobe Reader.
 */
public enum SignatureAlgorithm {
    RSA_3072("RSA", 3072, "SHA256withRSA", NISTObjectIdentifiers.id_sha256, "SHA-256"),
    RSA_4096("RSA", 4096, "SHA256withRSA", NISTObjectIdentifiers.id_sha256, "SHA-256"),
    ML_DSA_44("ML-DSA-44", 0, "ML-DSA-44", NISTObjectIdentifiers.id_sha512, "SHA-512"),
    ML_DSA_65("ML-DSA-65", 0, "ML-DSA-65", NISTObjectIdentifiers.id_sha512, "SHA-512"),
    ML_DSA_87("ML-DSA-87", 0, "ML-DSA-87", NISTObjectIdentifiers.id_sha512, "SHA-512");

    private final String keyAlgorithm;
    private final int keySize;
    private final String jcaSignatureName;
    private final org.bouncycastle.asn1.ASN1ObjectIdentifier digestOid;
    private final String digestName;

    SignatureAlgorithm(String keyAlgorithm, int keySize, String jcaSignatureName,
            org.bouncycastle.asn1.ASN1ObjectIdentifier digestOid, String digestName) {
        this.keyAlgorithm = keyAlgorithm;
        this.keySize = keySize;
        this.jcaSignatureName = jcaSignatureName;
        this.digestOid = digestOid;
        this.digestName = digestName;
    }

    public String keyAlgorithm() {
        return keyAlgorithm;
    }

    public int keySize() {
        return keySize;
    }

    public String jcaSignatureName() {
        return jcaSignatureName;
    }

    public AlgorithmIdentifier digestAlgorithmIdentifier() {
        return new AlgorithmIdentifier(digestOid);
    }

    public String digestName() {
        return digestName;
    }

    public boolean isPostQuantum() {
        return keyAlgorithm.startsWith("ML-DSA");
    }

    /**
     * Menentukan algoritma dari kunci publik sertifikat (OID SubjectPublicKeyInfo dan panjang modulus RSA).
     *
     * @throws IllegalArgumentException bila algoritma kunci tidak didukung
     */
    public static SignatureAlgorithm fromCertificate(java.security.cert.X509Certificate certificate) {
        java.security.PublicKey key = certificate.getPublicKey();
        if (key instanceof java.security.interfaces.RSAPublicKey rsa) {
            int bits = rsa.getModulus().bitLength();
            if (bits == 3072) {
                return RSA_3072;
            }
            if (bits == 4096) {
                return RSA_4096;
            }
            throw new IllegalArgumentException("Panjang kunci RSA tidak didukung: " + bits);
        }
        org.bouncycastle.asn1.ASN1ObjectIdentifier oid = org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
                .getInstance(key.getEncoded()).getAlgorithm().getAlgorithm();
        if (NISTObjectIdentifiers.id_ml_dsa_44.equals(oid)) {
            return ML_DSA_44;
        }
        if (NISTObjectIdentifiers.id_ml_dsa_65.equals(oid)) {
            return ML_DSA_65;
        }
        if (NISTObjectIdentifiers.id_ml_dsa_87.equals(oid)) {
            return ML_DSA_87;
        }
        throw new IllegalArgumentException("Algoritma kunci tidak didukung: " + oid);
    }
}
