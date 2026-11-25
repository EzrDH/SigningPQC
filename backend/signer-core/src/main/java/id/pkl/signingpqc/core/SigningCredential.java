package id.pkl.signingpqc.core;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Kunci privat penanda tangan beserta rantai sertifikatnya (sertifikat penanda tangan di indeks 0).
 */
public record SigningCredential(SignatureAlgorithm algorithm, PrivateKey privateKey,
        List<X509Certificate> chain) {

    public SigningCredential {
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("Rantai sertifikat tidak boleh kosong");
        }
        chain = List.copyOf(chain);
    }

    public X509Certificate certificate() {
        return chain.get(0);
    }
}
