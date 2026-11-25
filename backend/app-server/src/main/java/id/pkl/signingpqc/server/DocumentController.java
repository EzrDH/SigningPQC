package id.pkl.signingpqc.server;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import id.pkl.signingpqc.core.PdfSignatureVerifier;
import id.pkl.signingpqc.core.SignatureReport;
import id.pkl.signingpqc.core.ltv.DssReader;
import id.pkl.signingpqc.core.ltv.LtvService;
import id.pkl.signingpqc.core.ltv.TimestampProfile;
import id.pkl.signingpqc.core.ltv.ValidationData;
import id.pkl.signingpqc.core.validation.AlgorithmPolicy;
import id.pkl.signingpqc.core.validation.DocumentValidator;
import id.pkl.signingpqc.core.validation.OverallStatus;
import id.pkl.signingpqc.core.validation.ReasonCode;
import id.pkl.signingpqc.core.validation.ValidationReport;

/** Tanda tangan hybrid dan verifikasi dokumen PDF. */
@RestController
@RequestMapping("/api")
public class DocumentController {

    /** Ambang peringatan pembaruan (spesifikasi: 30 hari sebelum batas perlindungan). */
    static final Duration RENEWAL_WARNING = Duration.ofDays(30);

    private static final List<String> LEVELS = List.of("B-B", "B-T", "B-LT", "B-LTA");

    /**
     * Ringkasan verifikasi. {@code signatures} berisi tanda tangan saja (tanpa document timestamp);
     * {@code validation} berisi validasi berlapis lengkap (null bila PKI uji belum dibuat).
     */
    public record VerifyResponse(String fileName, boolean allValid, boolean hybridComplete, String summary,
            List<SignatureView> signatures, ValidationReport validation) {
    }

    /** Hasil satu tanda tangan untuk frontend (termasuk status sah dan level PAdES). */
    public record SignatureView(int order, String fieldName, String subFilter, String signatureAlgorithm,
            String digestAlgorithm, String signerSubject, java.time.Instant claimedSigningTime, int cmsSizeBytes,
            boolean coversWholeDocument, boolean valid, String padesLevel, String message,
            id.pkl.signingpqc.core.TimestampReport signatureTimestamp) {

        static SignatureView of(SignatureReport r) {
            return new SignatureView(r.order(), r.fieldName(), r.subFilter(), r.signatureAlgorithm(), r.digestAlgorithm(),
                    r.signerSubject(), r.claimedSigningTime(), r.cmsSizeBytes(), r.coversWholeDocument(), r.valid(),
                    r.padesLevel(), r.message(), r.signatureTimestamp());
        }
    }

    private final SigningService signing;
    private final PkiService pki;
    private final TsaService tsa;

    public DocumentController(SigningService signing, PkiService pki, TsaService tsa) {
        this.signing = signing;
        this.pki = pki;
        this.tsa = tsa;
    }

    /**
     * @param level B-B (tanpa timestamp), B-T (bawaan), B-LT (ditambah DSS), atau B-LTA (ditambah document timestamp
     *              TSA RSA generasi 1)
     */
    @PostMapping(value = "/sign", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> sign(@RequestPart("file") MultipartFile file, @RequestParam String signerId,
            @RequestParam(required = false) String reason, @RequestParam(required = false) String location,
            @RequestParam(defaultValue = "1") int page, @RequestParam(required = false) Float x,
            @RequestParam(required = false) Float y, @RequestParam(required = false) Float width,
            @RequestParam(required = false) Float height, @RequestParam(defaultValue = "true") boolean timestamp,
            @RequestParam(required = false) String level)
            throws IOException, GeneralSecurityException {
        String effective = level == null || level.isBlank() ? (timestamp ? "B-T" : "B-B") : level.strip().toUpperCase();
        if (!LEVELS.contains(effective)) {
            throw ApiException.badRequest("Level '" + level + "' tidak dikenal (pakai " + String.join(", ", LEVELS) + ")");
        }
        if (!timestamp && !"B-B".equals(effective)) {
            throw ApiException.badRequest("Level " + effective + " membutuhkan timestamp");
        }
        long start = System.nanoTime();
        byte[] signed = signing.signHybrid(file.getBytes(), signerId, reason, location,
                new SigningService.Placement(page, x, y, width, height), !"B-B".equals(effective));
        if ("B-LT".equals(effective) || "B-LTA".equals(effective)) {
            signed = LtvService.addValidationData(signed, pki.revocationSource(Instant.now()));
        }
        if ("B-LTA".equals(effective)) {
            signed = LtvService.addDocumentTimestamp(signed, tsa.client("rsa-g1", null), TimestampProfile.RSA);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(RequestInputs.outputName(file, "-ttd")).build().toString())
                .header("X-SigningPQC-Waktu-Ms", Long.toString(elapsedMs))
                .header("X-SigningPQC-Level", effective)
                .contentType(MediaType.APPLICATION_PDF)
                .body(signed);
    }

    /** @param at waktu pemeriksaan simulasi (ISO-8601, opsional; bawaan sekarang) */
    @PostMapping(value = "/verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VerifyResponse verify(@RequestPart("file") MultipartFile file, @RequestParam(required = false) String at)
            throws IOException {
        byte[] pdf = RequestInputs.pdf(file);
        Instant time = RequestInputs.time(at);
        List<SignatureReport> reports = new PdfSignatureVerifier().verify(pdf).stream()
                .filter(r -> !DssReader.ETSI_RFC3161.equals(r.subFilter()))
                .toList();
        boolean allValid = !reports.isEmpty() && reports.stream().allMatch(SignatureReport::valid);
        ValidationReport validation = null;
        if (pki.ready()) {
            Instant validationTime = time == null ? Instant.now() : time;
            ValidationData local;
            try {
                local = pki.localValidationData(validationTime);
            } catch (GeneralSecurityException e) {
                throw new IOException("Gagal menyiapkan CRL lokal PKI uji: " + e.getMessage(), e);
            }
            validation = new DocumentValidator(pki.trustAnchors(), AlgorithmPolicy.defaults(), RENEWAL_WARNING)
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
        return new VerifyResponse(file.getOriginalFilename(), allValid, hybrid, summary,
                reports.stream().map(SignatureView::of).toList(), validation);
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
