package id.pkl.signingpqc.pki;

import java.security.GeneralSecurityException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXCertPathValidatorResult;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;

import id.pkl.signingpqc.core.CryptoProviders;

/**
 * Validasi rantai sertifikat RFC 5280 (PKIX) memakai Bouncy Castle, termasuk cek CRL bila diberikan.
 *
 * <p>Dipakai untuk menguji PKI uji. Versi untuk verifikasi dokumen (waktu tanda tangan, timestamp,
 * DSS) dibuat pada Sprint 2.
 */
public final class PkixValidation {

    private PkixValidation() {
    }

    /**
     * @param chain rantai dari sertifikat daun ke atas; sertifikat root boleh ikut dan akan dibuang
     * @param trustAnchor root yang dipercaya
     * @param crls CRL untuk setiap CA; null berarti cek pencabutan dimatikan
     */
    public static PKIXCertPathValidatorResult validate(List<X509Certificate> chain, X509Certificate trustAnchor,
            Collection<X509CRL> crls) throws GeneralSecurityException {
        return validate(chain, trustAnchor, crls, new Date());
    }

    public static PKIXCertPathValidatorResult validate(List<X509Certificate> chain, X509Certificate trustAnchor,
            Collection<X509CRL> crls, Date validationTime) throws GeneralSecurityException {
        CryptoProviders.ensureRegistered();
        List<X509Certificate> path = chain.stream().filter(cert -> !cert.equals(trustAnchor)).toList();
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509", CryptoProviders.BC);
        CertPath certPath = certificateFactory.generateCertPath(path);

        PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(trustAnchor, null)));
        parameters.setDate(validationTime);
        if (crls == null) {
            parameters.setRevocationEnabled(false);
        } else {
            parameters.setRevocationEnabled(true);
            parameters.addCertStore(CertStore.getInstance("Collection",
                    new CollectionCertStoreParameters(crls), CryptoProviders.BC));
        }
        CertPathValidator validator = CertPathValidator.getInstance("PKIX", CryptoProviders.BC);
        return (PKIXCertPathValidatorResult) validator.validate(certPath, parameters);
    }
}
