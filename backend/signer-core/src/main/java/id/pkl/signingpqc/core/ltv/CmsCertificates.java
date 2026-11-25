package id.pkl.signingpqc.core.ltv;

import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerId;

import id.pkl.signingpqc.core.CryptoProviders;

/** Mengambil sertifikat dari struktur CMS (tanda tangan atau token timestamp). */
public final class CmsCertificates {

    private CmsCertificates() {
    }

    /** Semua sertifikat yang dibawa CMS. */
    public static List<X509Certificate> all(CMSSignedData cms) throws GeneralSecurityException {
        List<X509Certificate> out = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Collection<X509CertificateHolder> holders = cms.getCertificates().getMatches(null);
        for (X509CertificateHolder holder : holders) {
            out.add(convert(holder));
        }
        return out;
    }

    /** Sertifikat yang cocok dengan SignerId, atau null bila tidak dibawa. */
    public static X509Certificate signer(CMSSignedData cms, SignerId sid) throws GeneralSecurityException {
        @SuppressWarnings("unchecked")
        Collection<X509CertificateHolder> matches = cms.getCertificates().getMatches(sid);
        return matches.isEmpty() ? null : convert(matches.iterator().next());
    }

    private static X509Certificate convert(X509CertificateHolder holder) throws GeneralSecurityException {
        return new JcaX509CertificateConverter().setProvider(CryptoProviders.BC).getCertificate(holder);
    }
}
