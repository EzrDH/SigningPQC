package id.pkl.signingpqc.server;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.x509.CertificatePolicies;
import org.bouncycastle.asn1.x509.PolicyInformation;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import id.pkl.signingpqc.core.AlgorithmNames;
import id.pkl.signingpqc.core.PdfSignatureVerifier;
import id.pkl.signingpqc.core.SignatureReport;
import id.pkl.signingpqc.core.TimestampReport;
import id.pkl.signingpqc.core.ltv.CmsCertificates;
import id.pkl.signingpqc.core.ltv.DssReader;
import id.pkl.signingpqc.core.ltv.ValidationData;
import id.pkl.signingpqc.core.validation.AlgorithmPolicy;
import id.pkl.signingpqc.core.validation.DocumentValidator;
import id.pkl.signingpqc.core.validation.LayerKind;
import id.pkl.signingpqc.core.validation.LayerReport;
import id.pkl.signingpqc.core.validation.OverallStatus;
import id.pkl.signingpqc.core.validation.ReasonCode;
import id.pkl.signingpqc.core.validation.ValidationReport;
import id.pkl.signingpqc.pki.TestPkiFactory;

/** Verifikasi dokumen beserta detail sertifikat per tanda tangan, dipakai endpoint pengembang dan pengguna. */
@Service
public class VerifyService {

    /**
     * Ringkasan verifikasi. {@code signatures} berisi tanda tangan saja (tanpa document timestamp);
     * {@code validation} berisi validasi berlapis lengkap (null bila PKI uji belum dibuat).
     *
     * @param trustAnchors root tepercaya yang menjadi ujung rantai tanda tangan, urut kemunculan
     * @param dss          isi kamus DSS dokumen (data validasi jangka panjang)
     */
    public record VerifyResponse(String fileName, boolean allValid, boolean hybridComplete, String summary,
            List<SignatureView> signatures, ValidationReport validation, List<AnchorView> trustAnchors, DssView dss) {
    }

    /** Hasil satu tanda tangan untuk frontend, termasuk detail seperti panel tanda tangan pembaca PDF. */
    public record SignatureView(int order, String fieldName, String subFilter, String signatureAlgorithm,
            String digestAlgorithm, String signerSubject, Instant claimedSigningTime, int cmsSizeBytes,
            boolean coversWholeDocument, boolean valid, String padesLevel, String message,
            TimestampReport signatureTimestamp, SignatureDetail detail) {
    }

    /** Nama yang mudah dibaca beserta OID-nya. */
    public record Oid(String name, String oid) {
    }

    /** @param fingerprintSha256 SHA-256 dari DER sertifikat, heksadesimal huruf besar dipisah titik dua */
    public record CertificateDetail(String subject, String issuer, String serialHex, Instant notBefore,
            Instant notAfter, String keyAlgorithm, String keyUsage, String fingerprintSha256, Oid publicKey,
            Oid signatureAlgorithm, List<Oid> extendedKeyUsage, List<Oid> certificatePolicies) {
    }

    /**
     * CRL dari penerbit sertifikat yang tersedia saat pemeriksaan.
     *
     * @param source "DSS dokumen", "CRL lokal PKI uji", atau keduanya
     * @param listed nomor seri sertifikat tercantum di CRL ini
     */
    public record CrlEvidence(String issuer, String crlNumber, Instant thisUpdate, Instant nextUpdate, String source,
            boolean listed, Instant revokedAt) {
    }

    /** @param crls CRL penerbit sertifikat ini; kosong untuk root (root tidak dicek lewat CRL) */
    public record ChainEntry(String subject, boolean trustedRoot, String serialHex, Instant notBefore,
            Instant notAfter, String fingerprintSha256, List<CrlEvidence> crls) {
    }

    /**
     * @param revision    revisi PDF yang ditutup tanda tangan ini (0 bila tidak diketahui)
     * @param protectedBy tanda tangan sah berikutnya yang ikut menandatangani revisi ini; null bila tidak ada
     */
    public record SignatureDetail(CertificateDetail certificate, List<ChainEntry> chain, String revocation,
            String coverage, int revision, String signatureAlgorithmOid, String digestAlgorithmOid,
            String protectedBy) {
    }

    public record AnchorView(String subject, String fingerprintSha256) {
    }

    public record DssView(boolean present, int certificates, int crls) {
    }

    private static final Map<String, String> EKU = Map.of(
            "1.3.6.1.5.5.7.3.36", "Penandatanganan dokumen",
            "1.3.6.1.5.5.7.3.8", "Timestamp",
            "1.3.6.1.5.5.7.3.4", "Perlindungan email",
            "1.3.6.1.5.5.7.3.3", "Penandatanganan kode",
            "1.3.6.1.5.5.7.3.2", "Autentikasi klien",
            "1.3.6.1.5.5.7.3.1", "Autentikasi server");

    private static final Map<String, String> POLICY = Map.of(
            TestPkiFactory.TEST_CERTIFICATE_POLICY.getId(), "Kebijakan sertifikat uji (bukan CA sungguhan)",
            "2.5.29.32.0", "Kebijakan apa pun");

    private static final HexFormat FINGERPRINT = HexFormat.ofDelimiter(":").withUpperCase();

    private static final String[] KEY_USAGE = { "Tanda tangan digital", "Nirsangkal", "Penyandian kunci",
            "Penyandian data", "Kesepakatan kunci", "Penandatanganan sertifikat", "Penandatanganan CRL",
            "Hanya enkripsi", "Hanya dekripsi" };

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(VerifyService.class);

    private static final Set<ReasonCode> CHANGE_CODES = Set.of(ReasonCode.DOKUMEN_BERUBAH,
            ReasonCode.PERUBAHAN_ISI_TIDAK_DITANDATANGANI, ReasonCode.PERUBAHAN_SETELAH_LAPISAN_TERAKHIR);

    private final PkiService pki;
    private final Duration warning;

    public VerifyService(PkiService pki, @Value("${signingpqc.renewal-warning-days:30}") long warningDays) {
        this.pki = pki;
        this.warning = Duration.ofDays(warningDays);
    }

    /** @param at waktu pemeriksaan simulasi; null berarti sekarang */
    public VerifyResponse verify(byte[] pdf, String fileName, Instant at) throws IOException {
        List<SignatureReport> reports = new PdfSignatureVerifier().verify(pdf).stream()
                .filter(r -> !DssReader.ETSI_RFC3161.equals(r.subFilter()))
                .toList();
        boolean allValid = !reports.isEmpty() && reports.stream().allMatch(SignatureReport::valid);
        ValidationReport validation = null;
        ValidationData dss = DssReader.readAll(pdf);
        ValidationData local = ValidationData.EMPTY;
        if (pki.ready()) {
            Instant validationTime = at == null ? Instant.now() : at;
            try {
                local = pki.localValidationData(validationTime);
            } catch (GeneralSecurityException e) {
                throw new IOException("Gagal menyiapkan CRL lokal PKI uji: " + e.getMessage(), e);
            }
            validation = new DocumentValidator(pki.trustAnchors(), AlgorithmPolicy.defaults(), warning)
                    .validate(pdf, validationTime, local);
        }
        boolean hybrid = validation != null ? validation.hybridComplete()
                : reports.size() >= 2
                        && reports.stream().anyMatch(r -> r.signatureAlgorithm().startsWith("RSA"))
                        && reports.get(reports.size() - 1).signatureAlgorithm().startsWith("ML-DSA");
        String summary;
        if (reports.isEmpty()) {
            summary = "Dokumen tidak memiliki tanda tangan";
        } else if (validation != null) {
            summary = summary(validation);
        } else if (allValid && hybrid) {
            summary = "Semua tanda tangan sah; tanda tangan ML-DSA menutup seluruh dokumen";
        } else if (allValid) {
            summary = "Semua tanda tangan sah, tetapi struktur hybrid RSA + ML-DSA tidak lengkap";
        } else {
            summary = "Ada tanda tangan yang tidak sah";
        }
        List<SignatureView> views = views(pdf, reports, validation, dss, local);
        List<AnchorView> anchors = new ArrayList<>();
        for (SignatureView view : views) {
            for (ChainEntry entry : view.detail().chain()) {
                AnchorView anchor = new AnchorView(entry.subject(), entry.fingerprintSha256());
                if (entry.trustedRoot() && !anchors.contains(anchor)) {
                    anchors.add(anchor);
                }
            }
        }
        DssView dssView = new DssView(!dss.isEmpty(), dss.certificates().size(), dss.crls().size());
        return new VerifyResponse(fileName, allValid, hybrid, summary, views, validation, anchors, dssView);
    }

    private List<SignatureView> views(byte[] pdf, List<SignatureReport> reports, ValidationReport validation,
            ValidationData dss, ValidationData local) throws IOException {
        List<LayerReport> signatureLayers = validation == null ? List.of()
                : validation.layers().stream().filter(l -> l.kind() == LayerKind.TANDA_TANGAN).toList();
        int revisions = validation == null ? 0
                : validation.layers().stream().mapToInt(LayerReport::revision).max().orElse(0);
        // Tanda tangan terakhir tetap menutup seluruh dokumen bila revisi sesudahnya hanya data validasi dan document
        // timestamp yang sah (B-LTA), yaitu tanpa perubahan isi yang terdeteksi.
        boolean changedAfter = validation == null || validation.status() == OverallStatus.TIDAK_SAH
                || validation.layers().stream().anyMatch(l -> CHANGE_CODES.contains(l.code()))
                || validation.warnings().stream().anyMatch(CHANGE_CODES::contains);
        List<SignatureView> views = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<PDSignature> dictionaries = document.getSignatureDictionaries();
            for (int i = 0; i < reports.size(); i++) {
                SignatureReport r = reports.get(i);
                LayerReport layer = i < signatureLayers.size() ? signatureLayers.get(i) : null;
                boolean whole = r.coversWholeDocument() || (i == reports.size() - 1 && !changedAfter);
                SignatureReport next = i + 1 < reports.size() ? reports.get(i + 1) : null;
                LayerReport nextLayer = i + 1 < signatureLayers.size() ? signatureLayers.get(i + 1) : null;
                String protectedBy = next == null || !next.valid() ? null
                        : "Tanda tangan " + next.signatureAlgorithm()
                                + (nextLayer == null ? "" : " pada revisi " + nextLayer.revision())
                                + " ikut menandatangani revisi ini beserta tanda tangannya";
                SignatureDetail detail = detail(dictionaries.get(r.order() - 1), pdf, whole, layer, revisions,
                        protectedBy, dss, local);
                views.add(new SignatureView(r.order(), r.fieldName(), r.subFilter(), r.signatureAlgorithm(),
                        r.digestAlgorithm(), r.signerSubject(), r.claimedSigningTime(), r.cmsSizeBytes(),
                        r.coversWholeDocument(), r.valid(), r.padesLevel(), r.message(), r.signatureTimestamp(), detail));
            }
        }
        return views;
    }

    private SignatureDetail detail(PDSignature dictionary, byte[] pdf, boolean whole, LayerReport layer, int revisions,
            String protectedBy, ValidationData dss, ValidationData local) {
        String coverage = whole ? "Seluruh dokumen saat ditandatangani"
                : layer != null && revisions > 0 ? "Isi dokumen sampai revisi " + layer.revision()
                        : "Sebagian dokumen (ada perubahan sesudahnya)";
        try {
            CMSSignedData cms = new CMSSignedData(PdfSignatureVerifier.firstDerObject(dictionary.getContents(pdf)));
            SignerInformation signerInfo = cms.getSignerInfos().getSigners().iterator().next();
            X509Certificate signer = CmsCertificates.signer(cms, signerInfo.getSID());
            Set<X509Certificate> pool = new LinkedHashSet<>(CmsCertificates.all(cms));
            pki.cas().values().forEach(ca -> pool.add(ca.certificate()));
            return new SignatureDetail(certificate(signer), chain(signer, pool, dss, local), revocation(layer),
                    coverage, revision(layer), signerInfo.getEncryptionAlgOID(), signerInfo.getDigestAlgOID(),
                    protectedBy);
        } catch (Exception e) {
            LOG.warn("Detail sertifikat tanda tangan tidak dapat dibaca: {}", e.toString());
            return new SignatureDetail(null, List.of(), revocation(layer), coverage, revision(layer), null, null,
                    protectedBy);
        }
    }

    private static int revision(LayerReport layer) {
        return layer == null ? 0 : layer.revision();
    }

    private List<ChainEntry> chain(X509Certificate leaf, Set<X509Certificate> pool, ValidationData dss,
            ValidationData local) {
        Set<X509Certificate> anchors = pki.ready() ? pki.trustAnchors() : Set.of();
        List<X509Certificate> path = new ArrayList<>();
        X509Certificate current = leaf;
        for (int depth = 0; current != null && depth < 8; depth++) {
            path.add(current);
            if (current.getSubjectX500Principal().equals(current.getIssuerX500Principal())) {
                break;
            }
            X509Certificate child = current;
            current = pool.stream()
                    .filter(c -> c.getSubjectX500Principal().equals(child.getIssuerX500Principal()))
                    .filter(c -> verifies(child, c))
                    .findFirst().orElse(null);
        }
        List<ChainEntry> chain = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            X509Certificate certificate = path.get(i);
            List<CrlEvidence> crls = i + 1 < path.size() ? crls(certificate, path.get(i + 1), dss, local) : List.of();
            chain.add(new ChainEntry(certificate.getSubjectX500Principal().getName(), anchors.contains(certificate),
                    serial(certificate.getSerialNumber()), certificate.getNotBefore().toInstant(),
                    certificate.getNotAfter().toInstant(), fingerprint(certificate), crls));
        }
        return chain;
    }

    /** CRL dari penerbit yang tanda tangannya sah, dari DSS dokumen lebih dulu lalu CRL lokal PKI uji. */
    private static List<CrlEvidence> crls(X509Certificate certificate, X509Certificate issuer, ValidationData dss,
            ValidationData local) {
        List<CrlEvidence> out = new ArrayList<>();
        for (X509CRL crl : dss.merge(local).crls()) {
            if (!crl.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
                continue;
            }
            try {
                crl.verify(issuer.getPublicKey(), id.pkl.signingpqc.core.CryptoProviders.BC);
            } catch (GeneralSecurityException e) {
                continue;
            }
            boolean inDss = dss.crls().contains(crl);
            boolean inLocal = local.crls().contains(crl);
            String source = inDss && inLocal ? "DSS dokumen dan CRL lokal PKI uji"
                    : inDss ? "DSS dokumen" : "CRL lokal PKI uji";
            X509CRLEntry entry = crl.getRevokedCertificate(certificate.getSerialNumber());
            out.add(new CrlEvidence(crl.getIssuerX500Principal().getName(), crlNumber(crl),
                    crl.getThisUpdate().toInstant(),
                    crl.getNextUpdate() == null ? null : crl.getNextUpdate().toInstant(), source, entry != null,
                    entry == null ? null : entry.getRevocationDate().toInstant()));
        }
        return out;
    }

    private static String crlNumber(X509CRL crl) {
        byte[] value = crl.getExtensionValue("2.5.29.20");
        if (value == null) {
            return null;
        }
        try {
            return ASN1Integer.getInstance(ASN1OctetString.getInstance(value).getOctets()).getValue().toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String serial(BigInteger serial) {
        return serial.toString(16).toUpperCase();
    }

    static String fingerprint(X509Certificate certificate) {
        try {
            return FINGERPRINT.formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new IllegalStateException("Sidik jari sertifikat tidak dapat dihitung", e);
        }
    }

    private static boolean verifies(X509Certificate child, X509Certificate issuer) {
        try {
            child.verify(issuer.getPublicKey(), id.pkl.signingpqc.core.CryptoProviders.BC);
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static String revocation(LayerReport layer) {
        if (layer == null) {
            return "Tidak diketahui";
        }
        return switch (layer.code()) {
            case SERTIFIKAT_DICABUT -> "Dicabut";
            case DICABUT_SETELAH_DITANDATANGANI -> "Dicabut setelah ditandatangani";
            case STATUS_PENCABUTAN_TIDAK_DIKETAHUI -> "Tidak diketahui";
            default -> "Tidak dicabut";
        };
    }

    private static CertificateDetail certificate(X509Certificate certificate) {
        String keyAlgorithm = certificate.getPublicKey() instanceof RSAPublicKey rsa
                ? "RSA " + rsa.getModulus().bitLength() + " bit"
                : certificate.getPublicKey().getAlgorithm();
        boolean[] usage = certificate.getKeyUsage();
        List<String> names = new ArrayList<>();
        for (int i = 0; usage != null && i < usage.length && i < KEY_USAGE.length; i++) {
            if (usage[i]) {
                names.add(KEY_USAGE[i]);
            }
        }
        String keyOid = SubjectPublicKeyInfo.getInstance(certificate.getPublicKey().getEncoded()).getAlgorithm()
                .getAlgorithm().getId();
        return new CertificateDetail(certificate.getSubjectX500Principal().getName(),
                certificate.getIssuerX500Principal().getName(), serial(certificate.getSerialNumber()),
                certificate.getNotBefore().toInstant(), certificate.getNotAfter().toInstant(), keyAlgorithm,
                String.join(", ", names), fingerprint(certificate), new Oid(AlgorithmNames.signature(keyOid), keyOid),
                new Oid(AlgorithmNames.signature(certificate.getSigAlgOID()), certificate.getSigAlgOID()),
                extendedKeyUsage(certificate), certificatePolicies(certificate));
    }

    private static List<Oid> extendedKeyUsage(X509Certificate certificate) {
        try {
            List<String> oids = certificate.getExtendedKeyUsage();
            return oids == null ? List.of()
                    : oids.stream().map(oid -> new Oid(EKU.getOrDefault(oid, oid), oid)).toList();
        } catch (CertificateParsingException e) {
            return List.of();
        }
    }

    private static List<Oid> certificatePolicies(X509Certificate certificate) {
        byte[] value = certificate.getExtensionValue("2.5.29.32");
        if (value == null) {
            return List.of();
        }
        try {
            List<Oid> out = new ArrayList<>();
            for (PolicyInformation policy : CertificatePolicies
                    .getInstance(ASN1OctetString.getInstance(value).getOctets()).getPolicyInformation()) {
                String oid = policy.getPolicyIdentifier().getId();
                out.add(new Oid(POLICY.getOrDefault(oid, oid), oid));
            }
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static String summary(ValidationReport report) {
        if (report.status() == OverallStatus.SAH) {
            String notes = report.warnings().stream().map(ReasonCode::message).distinct()
                    .collect(Collectors.joining("; "));
            String base = notes.isEmpty() ? "Dokumen sah" : "Dokumen sah dengan catatan: " + notes;
            if (report.renewalRecommended()) {
                return base + "; perlindungan berakhir " + report.protectionUntil() + ", segera perbarui timestamp";
            }
            return notes.isEmpty() ? "Dokumen sah; semua lapisan terbukti" : base;
        }
        if (report.status() == OverallStatus.TIDAK_DAPAT_DIBUKTIKAN) {
            return "Keabsahan dokumen tidak dapat dibuktikan pada waktu pemeriksaan";
        }
        return "Dokumen tidak sah";
    }
}
