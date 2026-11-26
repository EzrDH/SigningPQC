package id.pkl.signingpqc.server;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

/** Verifikasi dokumen beserta detail sertifikat per tanda tangan, dipakai endpoint pengembang dan pengguna. */
@Service
public class VerifyService {

    /**
     * Ringkasan verifikasi. {@code signatures} berisi tanda tangan saja (tanpa document timestamp);
     * {@code validation} berisi validasi berlapis lengkap (null bila PKI uji belum dibuat).
     */
    public record VerifyResponse(String fileName, boolean allValid, boolean hybridComplete, String summary,
            List<SignatureView> signatures, ValidationReport validation) {
    }

    /** Hasil satu tanda tangan untuk frontend, termasuk detail seperti panel tanda tangan pembaca PDF. */
    public record SignatureView(int order, String fieldName, String subFilter, String signatureAlgorithm,
            String digestAlgorithm, String signerSubject, Instant claimedSigningTime, int cmsSizeBytes,
            boolean coversWholeDocument, boolean valid, String padesLevel, String message,
            TimestampReport signatureTimestamp, SignatureDetail detail) {
    }

    public record CertificateDetail(String subject, String issuer, String serialHex, Instant notBefore,
            Instant notAfter, String keyAlgorithm, String keyUsage) {
    }

    public record ChainEntry(String subject, boolean trustedRoot) {
    }

    public record SignatureDetail(CertificateDetail certificate, List<ChainEntry> chain, String revocation,
            String coverage) {
    }

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
        if (pki.ready()) {
            Instant validationTime = at == null ? Instant.now() : at;
            ValidationData local;
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
        return new VerifyResponse(fileName, allValid, hybrid, summary, views(pdf, reports, validation), validation);
    }

    private List<SignatureView> views(byte[] pdf, List<SignatureReport> reports, ValidationReport validation)
            throws IOException {
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
                SignatureDetail detail = detail(dictionaries.get(r.order() - 1), pdf, whole, layer, revisions);
                views.add(new SignatureView(r.order(), r.fieldName(), r.subFilter(), r.signatureAlgorithm(),
                        r.digestAlgorithm(), r.signerSubject(), r.claimedSigningTime(), r.cmsSizeBytes(),
                        r.coversWholeDocument(), r.valid(), r.padesLevel(), r.message(), r.signatureTimestamp(), detail));
            }
        }
        return views;
    }

    private SignatureDetail detail(PDSignature dictionary, byte[] pdf, boolean whole, LayerReport layer, int revisions) {
        String coverage = whole ? "Seluruh dokumen saat ditandatangani"
                : layer != null && revisions > 0 ? "Revisi " + layer.revision() + " dari " + revisions
                        : "Sebagian dokumen (ada perubahan sesudahnya)";
        try {
            CMSSignedData cms = new CMSSignedData(PdfSignatureVerifier.firstDerObject(dictionary.getContents(pdf)));
            SignerInformation signerInfo = cms.getSignerInfos().getSigners().iterator().next();
            X509Certificate signer = CmsCertificates.signer(cms, signerInfo.getSID());
            Set<X509Certificate> pool = new LinkedHashSet<>(CmsCertificates.all(cms));
            pki.cas().values().forEach(ca -> pool.add(ca.certificate()));
            return new SignatureDetail(certificate(signer), chain(signer, pool), revocation(layer), coverage);
        } catch (Exception e) {
            LOG.warn("Detail sertifikat tanda tangan tidak dapat dibaca: {}", e.toString());
            return new SignatureDetail(null, List.of(), revocation(layer), coverage);
        }
    }

    private List<ChainEntry> chain(X509Certificate leaf, Set<X509Certificate> pool) {
        Set<X509Certificate> anchors = pki.ready() ? pki.trustAnchors() : Set.of();
        List<ChainEntry> chain = new ArrayList<>();
        X509Certificate current = leaf;
        for (int depth = 0; current != null && depth < 8; depth++) {
            chain.add(new ChainEntry(current.getSubjectX500Principal().getName(), anchors.contains(current)));
            if (current.getSubjectX500Principal().equals(current.getIssuerX500Principal())) {
                break;
            }
            X509Certificate child = current;
            current = pool.stream()
                    .filter(c -> c.getSubjectX500Principal().equals(child.getIssuerX500Principal()))
                    .filter(c -> verifies(child, c))
                    .findFirst().orElse(null);
        }
        return chain;
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
        return new CertificateDetail(certificate.getSubjectX500Principal().getName(),
                certificate.getIssuerX500Principal().getName(), certificate.getSerialNumber().toString(16).toUpperCase(),
                certificate.getNotBefore().toInstant(), certificate.getNotAfter().toInstant(), keyAlgorithm,
                String.join(", ", names));
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
