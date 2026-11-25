package id.pkl.signingpqc.server;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

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

/** Tanda tangan hybrid dan verifikasi dokumen PDF. */
@RestController
@RequestMapping("/api")
public class DocumentController {

    /** Ringkasan verifikasi. hybridComplete: ada tanda tangan RSA lalu ML-DSA terakhir yang menutup seluruh dokumen. */
    public record VerifyResponse(String fileName, boolean allValid, boolean hybridComplete, String summary,
            List<SignatureView> signatures) {
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

    public DocumentController(SigningService signing) {
        this.signing = signing;
    }

    @PostMapping(value = "/sign", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> sign(@RequestPart("file") MultipartFile file, @RequestParam String signerId,
            @RequestParam(required = false) String reason, @RequestParam(required = false) String location,
            @RequestParam(defaultValue = "1") int page, @RequestParam(required = false) Float x,
            @RequestParam(required = false) Float y, @RequestParam(required = false) Float width,
            @RequestParam(required = false) Float height, @RequestParam(defaultValue = "true") boolean timestamp)
            throws IOException, GeneralSecurityException {
        long start = System.nanoTime();
        byte[] signed = signing.signHybrid(file.getBytes(), signerId, reason, location,
                new SigningService.Placement(page, x, y, width, height), timestamp);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        String name = file.getOriginalFilename() == null ? "dokumen.pdf" : file.getOriginalFilename();
        String outName = name.toLowerCase().endsWith(".pdf") ? name.substring(0, name.length() - 4) + "-ttd.pdf"
                : name + "-ttd.pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(outName).build().toString())
                .header("X-SigningPQC-Waktu-Ms", Long.toString(elapsedMs))
                .contentType(MediaType.APPLICATION_PDF)
                .body(signed);
    }

    @PostMapping(value = "/verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VerifyResponse verify(@RequestPart("file") MultipartFile file) throws IOException {
        byte[] pdf = file.getBytes();
        if (!SigningService.looksLikePdf(pdf)) {
            throw ApiException.badRequest("Berkas bukan PDF");
        }
        List<SignatureReport> reports;
        try {
            reports = new PdfSignatureVerifier().verify(pdf);
        } catch (IOException e) {
            throw ApiException.badRequest("PDF tidak dapat dibaca: " + e.getMessage());
        }
        boolean allValid = !reports.isEmpty() && reports.stream().allMatch(SignatureReport::valid);
        boolean hybrid = reports.size() >= 2
                && reports.stream().anyMatch(r -> r.signatureAlgorithm().startsWith("RSA"))
                && reports.get(reports.size() - 1).signatureAlgorithm().startsWith("ML-DSA")
                && reports.get(reports.size() - 1).coversWholeDocument();
        String summary;
        if (reports.isEmpty()) {
            summary = "Dokumen tidak memiliki tanda tangan";
        } else if (allValid && hybrid) {
            summary = "Semua tanda tangan sah; tanda tangan ML-DSA menutup seluruh dokumen";
        } else if (allValid) {
            summary = "Semua tanda tangan sah, tetapi struktur hybrid RSA + ML-DSA tidak lengkap";
        } else {
            summary = "Ada tanda tangan yang tidak sah";
        }
        return new VerifyResponse(file.getOriginalFilename(), allValid, hybrid, summary,
                reports.stream().map(SignatureView::of).toList());
    }
}
