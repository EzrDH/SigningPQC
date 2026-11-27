package id.pkl.signingpqc.server;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
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

import id.pkl.signingpqc.core.ltv.LtvService;
import id.pkl.signingpqc.core.ltv.TimestampProfile;

/** Tanda tangan hybrid dan verifikasi dokumen PDF. */
@RestController
@RequestMapping("/api")
public class DocumentController {

    private static final List<String> LEVELS = List.of("B-B", "B-T", "B-LT", "B-LTA");

    private final SigningService signing;
    private final PkiService pki;
    private final TsaService tsa;
    private final VerifyService verifier;

    public DocumentController(SigningService signing, PkiService pki, TsaService tsa, VerifyService verifier) {
        this.signing = signing;
        this.pki = pki;
        this.tsa = tsa;
        this.verifier = verifier;
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
    public VerifyService.VerifyResponse verify(@RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String at) throws IOException {
        return verifier.verify(RequestInputs.pdf(file), file.getOriginalFilename(), RequestInputs.time(at));
    }
}
