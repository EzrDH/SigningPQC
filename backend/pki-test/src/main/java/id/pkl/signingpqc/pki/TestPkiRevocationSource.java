package id.pkl.signingpqc.pki;

import java.security.GeneralSecurityException;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.bouncycastle.asn1.x509.CRLReason;

import id.pkl.signingpqc.core.ltv.RevocationSource;
import id.pkl.signingpqc.core.ltv.ValidationData;

/**
 * Sumber data validasi dari PKI uji, tanpa HTTP: rantai disusun dari CA yang dikenal, dan CRL dibuat langsung
 * dengan thisUpdate = jam sumber ini (bisa digeser untuk simulasi pembaruan di masa depan).
 */
public final class TestPkiRevocationSource implements RevocationSource {

    private final List<CertificateAuthority> cas;
    private final TestPkiFactory factory;
    private final AtomicReference<Clock> clock;
    private final Map<String, RevokedEntry> revoked = new LinkedHashMap<>();
    private final AtomicLong crlNumber = new AtomicLong(1);

    private record RevokedEntry(X509Certificate certificate, Instant time) { }

    public TestPkiRevocationSource(Collection<CertificateAuthority> cas, TestPkiFactory factory, Clock clock) {
        this.cas = List.copyOf(cas);
        this.factory = factory;
        this.clock = new AtomicReference<>(clock);
    }

    public void setClock(Clock newClock) {
        clock.set(newClock);
    }

    /** Mencatat pencabutan sertifikat pada waktu tertentu (alasan keyCompromise). */
    public synchronized void revoke(X509Certificate certificate, Instant when) {
        revoked.put(certificate.getSerialNumber() + "@" + certificate.getIssuerX500Principal().getName(),
                new RevokedEntry(certificate, when));
    }

    @Override
    public synchronized ValidationData collect(X509Certificate leaf, Collection<X509Certificate> pool)
            throws GeneralSecurityException {
        List<X509Certificate> chain = new ArrayList<>();
        List<X509CRL> crls = new ArrayList<>();
        X509Certificate current = leaf;
        chain.add(current);
        while (!isSelfSigned(current)) {
            CertificateAuthority issuer = issuerOf(current);
            if (issuer == null) {
                throw new GeneralSecurityException("Penerbit tidak dikenal PKI uji: "
                        + current.getIssuerX500Principal().getName());
            }
            crls.add(crlOf(issuer));
            current = issuer.certificate();
            chain.add(current);
        }
        return new ValidationData(chain, crls);
    }

    private X509CRL crlOf(CertificateAuthority issuer) throws GeneralSecurityException {
        List<TestPkiFactory.RevokedCertificate> entries = new ArrayList<>();
        Instant now = clock.get().instant();
        for (RevokedEntry entry : revoked.values()) {
            if (entry.certificate().getIssuerX500Principal().equals(issuer.certificate().getSubjectX500Principal())
                    && !entry.time().isAfter(now)) {
                entries.add(new TestPkiFactory.RevokedCertificate(entry.certificate().getSerialNumber(),
                        entry.time(), CRLReason.keyCompromise));
            }
        }
        return factory.createCrl(issuer, entries, crlNumber.getAndIncrement(), now);
    }

    private CertificateAuthority issuerOf(X509Certificate certificate) {
        for (CertificateAuthority ca : cas) {
            if (ca.certificate().getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
                try {
                    certificate.verify(ca.certificate().getPublicKey());
                    return ca;
                } catch (GeneralSecurityException e) {
                    // nama sama, kunci lain: coba CA berikutnya
                }
            }
        }
        return null;
    }

    private static boolean isSelfSigned(X509Certificate certificate) {
        if (!certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
            return false;
        }
        try {
            certificate.verify(certificate.getPublicKey());
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
