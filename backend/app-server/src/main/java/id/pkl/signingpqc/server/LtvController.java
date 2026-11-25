package id.pkl.signingpqc.server;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;

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

/**
 * Lapisan jangka panjang (KF-38): data validasi (lapisan 3), document timestamp (lapisan 4), dan pembaruan
 * (lapisan 5, 6, ...). Parameter {@code at} opsional: waktu simulasi untuk TSA uji dan tanggal CRL.
 */
@RestController
@RequestMapping("/api/ltv")
public class LtvController {

    private final PkiService pki;
    private final TsaService tsa;

    public LtvController(PkiService pki, TsaService tsa) {
        this.pki = pki;
        this.tsa = tsa;
    }

    @PostMapping(value = "/validation-data", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> validationData(@RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String at) throws IOException, GeneralSecurityException {
        byte[] pdf = RequestInputs.pdf(file);
        Instant when = orNow(RequestInputs.time(at));
        return pdf(LtvService.addValidationData(pdf, pki.revocationSource(when)), RequestInputs.outputName(file, "-lt"));
    }

    @PostMapping(value = "/timestamp", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> timestamp(@RequestPart("file") MultipartFile file,
            @RequestParam(name = "tsa", defaultValue = "rsa-g1") String tsaId, @RequestParam(required = false) String at)
            throws IOException, GeneralSecurityException {
        byte[] pdf = RequestInputs.pdf(file);
        Instant when = RequestInputs.time(at);
        byte[] out = LtvService.addDocumentTimestamp(pdf, tsa.client(tsaId, when), TsaService.profile(tsaId));
        return pdf(out, RequestInputs.outputName(file, "-lta"));
    }

    @PostMapping(value = "/renew", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> renew(@RequestPart("file") MultipartFile file,
            @RequestParam(name = "tsa") String tsaId, @RequestParam(required = false) String at)
            throws IOException, GeneralSecurityException {
        byte[] pdf = RequestInputs.pdf(file);
        Instant when = RequestInputs.time(at);
        byte[] out = LtvService.renew(pdf, pki.revocationSource(orNow(when)), tsa.client(tsaId, when),
                TsaService.profile(tsaId));
        return pdf(out, RequestInputs.outputName(file, "-pembaruan"));
    }

    private static Instant orNow(Instant at) {
        return at == null ? Instant.now() : at;
    }

    private static ResponseEntity<byte[]> pdf(byte[] body, String name) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(body);
    }
}
