package id.pkl.signingpqc.core;

import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Base64;

/** Konversi sederhana ke format PEM. */
public final class Pem {

    private Pem() {
    }

    public static String certificate(X509Certificate certificate) throws CertificateEncodingException {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(certificate.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n";
    }
}
