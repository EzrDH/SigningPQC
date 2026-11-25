package id.pkl.signingpqc.core.ltv;

import java.security.cert.CRLException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data validasi yang disimpan di kamus DSS: sertifikat dan CRL (bentuk DER).
 *
 * <p>Duplikat dibandingkan lewat isi DER, sehingga sertifikat yang sama dari dua sumber hanya disimpan sekali.
 */
public record ValidationData(List<X509Certificate> certificates, List<X509CRL> crls) {

    public static final ValidationData EMPTY = new ValidationData(List.of(), List.of());

    public ValidationData {
        certificates = List.copyOf(dedupeCerts(certificates == null ? List.of() : certificates));
        crls = List.copyOf(dedupeCrls(crls == null ? List.of() : crls));
    }

    /** Gabungan data ini dan data lain, tanpa duplikat, urutan data ini lebih dulu. */
    public ValidationData merge(ValidationData other) {
        List<X509Certificate> certs = new ArrayList<>(certificates);
        certs.addAll(other.certificates);
        List<X509CRL> allCrls = new ArrayList<>(crls);
        allCrls.addAll(other.crls);
        return new ValidationData(certs, allCrls);
    }

    public boolean isEmpty() {
        return certificates.isEmpty() && crls.isEmpty();
    }

    static String key(X509Certificate certificate) {
        try {
            return HexFormat.of().formatHex(certificate.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new IllegalArgumentException("Sertifikat tidak dapat dikodekan", e);
        }
    }

    static String key(X509CRL crl) {
        try {
            return HexFormat.of().formatHex(crl.getEncoded());
        } catch (CRLException e) {
            throw new IllegalArgumentException("CRL tidak dapat dikodekan", e);
        }
    }

    private static List<X509Certificate> dedupeCerts(List<X509Certificate> in) {
        Map<String, X509Certificate> out = new LinkedHashMap<>();
        in.forEach(c -> out.putIfAbsent(key(c), c));
        return new ArrayList<>(out.values());
    }

    private static List<X509CRL> dedupeCrls(List<X509CRL> in) {
        Map<String, X509CRL> out = new LinkedHashMap<>();
        in.forEach(c -> out.putIfAbsent(key(c), c));
        return new ArrayList<>(out.values());
    }
}
