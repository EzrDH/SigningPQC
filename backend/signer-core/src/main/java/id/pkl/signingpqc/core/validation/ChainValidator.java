package id.pkl.signingpqc.core.validation;

import java.security.GeneralSecurityException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import id.pkl.signingpqc.core.CryptoProviders;
import id.pkl.signingpqc.core.ltv.ValidationData;

/**
 * Validasi rantai sertifikat pada waktu tertentu memakai data dari dokumen (DSS), tanpa jaringan.
 *
 * <ol>
 *   <li>Rantai disusun dari sertifikat di data validasi sampai salah satu root yang dipercaya.</li>
 *   <li>Setiap sertifikat (termasuk root) harus berlaku pada waktu itu.</li>
 *   <li>Aturan RFC 5280 diperiksa dengan PKIX Bouncy Castle (revokasi dimatikan, diperiksa sendiri).</li>
 *   <li>Revokasi: CRL dari penerbit, tanda tangannya sah, dan (diterbitkan pada/sesudah waktu itu, atau berlaku
 *       pada waktu itu). Dicabut pada/sebelum waktu itu berarti tidak sah.</li>
 * </ol>
 */
public final class ChainValidator {

    private static final int MAX_DEPTH = 8;

    private final Set<X509Certificate> trustAnchors;

    public ChainValidator(Set<X509Certificate> trustAnchors) {
        if (trustAnchors == null || trustAnchors.isEmpty()) {
            throw new IllegalArgumentException("Minimal satu root yang dipercaya");
        }
        this.trustAnchors = Set.copyOf(trustAnchors);
    }

    public ChainResult validate(X509Certificate leaf, ValidationData data, Instant time) {
        List<X509Certificate> path = new ArrayList<>();
        path.add(leaf);
        X509Certificate current = leaf;
        while (!trustAnchors.contains(current)) {
            if (path.size() > MAX_DEPTH) {
                return new ChainResult(ReasonCode.RANTAI_TIDAK_SAH, path, null, "Rantai terlalu panjang");
            }
            X509Certificate issuer = findIssuer(current, data.certificates());
            if (issuer == null) {
                if (isSelfSigned(current)) {
                    return new ChainResult(ReasonCode.ROOT_TIDAK_DIPERCAYA, path, null,
                            current.getSubjectX500Principal().getName());
                }
                return new ChainResult(ReasonCode.DATA_VALIDASI_TIDAK_ADA, path, null,
                        "Penerbit tidak ditemukan: " + current.getIssuerX500Principal().getName());
            }
            path.add(issuer);
            current = issuer;
        }
        Instant notAfter = path.stream().map(c -> c.getNotAfter().toInstant()).min(Instant::compareTo).orElseThrow();

        for (X509Certificate certificate : path) {
            Instant from = certificate.getNotBefore().toInstant();
            Instant to = certificate.getNotAfter().toInstant();
            if (time.isBefore(from) || time.isAfter(to)) {
                return new ChainResult(ReasonCode.SERTIFIKAT_TIDAK_BERLAKU, path, notAfter,
                        certificate.getSubjectX500Principal().getName() + " berlaku " + from + " s.d. " + to);
            }
        }

        try {
            pkix(path, time);
        } catch (CertPathValidatorException e) {
            return new ChainResult(ReasonCode.RANTAI_TIDAK_SAH, path, notAfter, e.getMessage());
        } catch (GeneralSecurityException e) {
            return new ChainResult(ReasonCode.RANTAI_TIDAK_SAH, path, notAfter, e.getMessage());
        }

        ReasonCode outcome = ReasonCode.OK;
        String detail = "Rantai sah pada " + time;
        for (int i = 0; i < path.size() - 1; i++) {
            X509Certificate certificate = path.get(i);
            X509Certificate issuer = path.get(i + 1);
            List<X509CRL> usable = usableCrls(issuer, data.crls(), time);
            if (usable.isEmpty()) {
                return new ChainResult(ReasonCode.STATUS_PENCABUTAN_TIDAK_DIKETAHUI, path, notAfter,
                        "Tidak ada CRL yang berlaku dari " + issuer.getSubjectX500Principal().getName());
            }
            for (X509CRL crl : usable) {
                X509CRLEntry entry = crl.getRevokedCertificate(certificate.getSerialNumber());
                if (entry == null) {
                    continue;
                }
                Instant revokedAt = entry.getRevocationDate().toInstant();
                String subject = certificate.getSubjectX500Principal().getName();
                if (!revokedAt.isAfter(time)) {
                    return new ChainResult(ReasonCode.SERTIFIKAT_DICABUT, path, notAfter,
                            subject + " dicabut pada " + revokedAt);
                }
                outcome = ReasonCode.DICABUT_SETELAH_DITANDATANGANI;
                detail = subject + " dicabut pada " + revokedAt + ", setelah " + time;
            }
        }
        return new ChainResult(outcome, path, notAfter, detail);
    }

    private X509Certificate findIssuer(X509Certificate certificate, List<X509Certificate> pool) {
        Set<X509Certificate> candidates = new LinkedHashSet<>(trustAnchors);
        candidates.addAll(pool);
        for (X509Certificate candidate : candidates) {
            if (candidate.equals(certificate)
                    || !candidate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
                continue;
            }
            try {
                certificate.verify(candidate.getPublicKey(), CryptoProviders.BC);
                return candidate;
            } catch (GeneralSecurityException e) {
                // nama sama, kunci lain
            }
        }
        return null;
    }

    private void pkix(List<X509Certificate> path, Instant time) throws GeneralSecurityException {
        CryptoProviders.ensureRegistered();
        X509Certificate anchor = path.get(path.size() - 1);
        List<X509Certificate> withoutAnchor = path.subList(0, path.size() - 1);
        if (withoutAnchor.isEmpty()) {
            return;
        }
        CertificateFactory factory = CertificateFactory.getInstance("X.509", CryptoProviders.BC);
        CertPath certPath = factory.generateCertPath(withoutAnchor);
        PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(anchor, null)));
        parameters.setDate(Date.from(time));
        parameters.setRevocationEnabled(false);
        CertPathValidator.getInstance("PKIX", CryptoProviders.BC).validate(certPath, parameters);
    }

    private static List<X509CRL> usableCrls(X509Certificate issuer, List<X509CRL> crls, Instant time) {
        return crls.stream().filter(crl -> {
            if (!crl.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
                return false;
            }
            try {
                crl.verify(issuer.getPublicKey(), CryptoProviders.BC);
            } catch (GeneralSecurityException e) {
                return false;
            }
            Instant thisUpdate = crl.getThisUpdate().toInstant();
            Instant nextUpdate = crl.getNextUpdate() == null ? null : crl.getNextUpdate().toInstant();
            boolean issuedAfter = !thisUpdate.isBefore(time);
            boolean currentAtTime = !thisUpdate.isAfter(time) && (nextUpdate == null || !time.isAfter(nextUpdate));
            return issuedAfter || currentAtTime;
        }).collect(Collectors.toList());
    }

    private static boolean isSelfSigned(X509Certificate certificate) {
        if (!certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
            return false;
        }
        try {
            certificate.verify(certificate.getPublicKey(), CryptoProviders.BC);
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
