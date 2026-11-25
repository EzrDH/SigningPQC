package id.pkl.signingpqc.core;

import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Base64;

/** Konversi sederhana ke format PEM. */
public final class Pem {

    private Pem() {
    }

    public static String certificate(X509Certificate certificate) throws CertificateEncodingException {
        return encode("CERTIFICATE", certificate.getEncoded());
    }

    /** Beberapa sertifikat berurutan dalam satu berkas PEM (mis. rantai atau bundel CA). */
    public static String certificates(java.util.List<X509Certificate> certificates) throws CertificateEncodingException {
        StringBuilder out = new StringBuilder();
        for (X509Certificate certificate : certificates) {
            out.append(certificate(certificate));
        }
        return out.toString();
    }

    public static String crl(java.security.cert.X509CRL crl) throws java.security.cert.CRLException {
        return encode("X509 CRL", crl.getEncoded());
    }

    private static String encode(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
