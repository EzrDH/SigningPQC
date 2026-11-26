package id.pkl.signingpqc.server.account;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import id.pkl.signingpqc.core.VisibleSignature;
import id.pkl.signingpqc.pki.SignerPair;
import id.pkl.signingpqc.server.ApiException;
import id.pkl.signingpqc.server.PkiService;
import id.pkl.signingpqc.server.RequestInputs;
import id.pkl.signingpqc.server.SigningService;

/** Dokumen milik pengguna yang sudah masuk: tanda tangan dengan konfirmasi dan penyimpanan otomatis. */
@RestController
@RequestMapping("/api/me")
public class MeDocumentController {

    public static final String SAVED_PATH = "X-Saved-Path";

    private final AccountStore store;
    private final AccountService accounts;
    private final PkiService pki;
    private final SigningService signing;
    private final HistoryStore history;

    public MeDocumentController(AccountStore store, AccountService accounts, PkiService pki, SigningService signing,
            HistoryStore history) {
        this.store = store;
        this.accounts = accounts;
        this.pki = pki;
        this.signing = signing;
        this.history = history;
    }

    @PostMapping(value = "/sign", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> sign(@RequestAttribute(AuthInterceptor.ACCOUNT_ID) String accountId,
            @RequestPart("file") MultipartFile file, @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) Float x, @RequestParam(required = false) Float y,
            @RequestParam(required = false) Float width, @RequestParam(required = false) Float height,
            @RequestParam(required = false) String passphrase, @RequestParam(required = false) String code)
            throws IOException, GeneralSecurityException {
        Account account = store.byId(accountId).orElseThrow(() -> ApiException.notFound("Akun tidak ditemukan"));
        byte[] pdf = RequestInputs.pdf(file);
        PkiService.SignerRecord record = pki.signerRecord(account.signerId());
        if (record.revoked()) {
            throw ApiException.conflict("Sertifikat Anda sudah dicabut");
        }
        VisibleSignature visible = null;
        if (account.appearance() == Account.Appearance.VISIBLE) {
            Path specimen = store.dir(accountId).resolve("specimen.png");
            visible = signing.placement(pdf, page, x, y, width, height,
                    Files.exists(specimen) ? Files.readAllBytes(specimen) : null);
        }
        SignerPair pair = accounts.unlock(account, passphrase, code);
        byte[] signed = signing.signHybrid(pdf, pair, record, visible);
        Path saved = OutputFolder.save(Path.of(account.outputDir()), file.getOriginalFilename(), "-ttd", signed);
        history.add(accountId, new HistoryStore.Entry(saved.getFileName().toString(), "Ditandatangani", Instant.now(),
                "SAH", saved.toString()));
        return pdf(signed, saved);
    }

    static ResponseEntity<byte[]> pdf(byte[] body, Path saved) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(saved.getFileName().toString(), StandardCharsets.UTF_8)
                                .build().toString())
                .header(SAVED_PATH, URLEncoder.encode(saved.toString(), StandardCharsets.UTF_8).replace("+", "%20"))
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, SAVED_PATH)
                .contentType(MediaType.APPLICATION_PDF)
                .body(body);
    }
}
