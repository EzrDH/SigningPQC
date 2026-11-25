package id.pkl.signingpqc.pki;

import java.security.cert.X509Certificate;

import id.pkl.signingpqc.core.SigningCredential;

/**
 * Satu CA uji: kunci privat dan rantai sertifikatnya (sertifikat CA di indeks 0, Root di akhir).
 *
 * @param id penanda unik, misalnya "rsa-root" atau "mldsa-intermediate", dipakai untuk nama berkas dan URL CRL
 */
public record CertificateAuthority(String id, PkiFamily family, SigningCredential credential) {

    public X509Certificate certificate() {
        return credential.certificate();
    }
}
