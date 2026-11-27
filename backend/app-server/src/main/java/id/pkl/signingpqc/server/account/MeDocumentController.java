package id.pkl.signingpqc.server.account;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import id.pkl.signingpqc.core.VisibleSignature;
import id.pkl.signingpqc.core.ltv.LtvService;
import id.pkl.signingpqc.pki.SignerPair;
import id.pkl.signingpqc.server.ActivityLog;
import id.pkl.signingpqc.server.ApiException;
import id.pkl.signingpqc.server.PkiService;
import id.pkl.signingpqc.server.RequestInputs;
import id.pkl.signingpqc.server.SigningService;
import id.pkl.signingpqc.server.TsaService;
import id.pkl.signingpqc.server.VerifyService;

/** Dokumen milik pengguna yang sudah masuk: tanda tangan dengan konfirmasi dan penyimpanan otomatis. */
@RestController
@RequestMapping("/api/me")
public class MeDocumentController {

    /** TSA dengan sisa masa berlaku terpanjang dan algoritma yang tetap aman (spesifikasi 6.8). */
    static final String RENEW_TSA = "mldsa";

    private final AccountStore store;
    private final AccountService accounts;
    private final PkiService pki;
    private final SigningService signing;
    private final HistoryStore history;
    private final VerifyService verifier;
    private final TsaService tsa;

    private final ActivityLog activity;
    private final Actors actors;

    public MeDocumentController(AccountStore store, AccountService accounts, PkiService pki, SigningService signing,
            HistoryStore history, VerifyService verifier, TsaService tsa, ActivityLog activity, Actors actors) {
        this.activity = activity;
        this.actors = actors;
        this.store = store;
        this.accounts = accounts;
        this.pki = pki;
        this.signing = signing;
        this.history = history;
        this.verifier = verifier;
        this.tsa = tsa;
    }

    @PostMapping(value = "/sign", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SavedDocument sign(@RequestAttribute(AuthInterceptor.ACCOUNT_ID) String accountId,
            @RequestPart("file") MultipartFile file, @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) Float x, @RequestParam(required = false) Float y,
            @RequestParam(required = false) Float width, @RequestParam(required = false) Float height,
            @RequestParam(required = false) String passphrase, @RequestParam(required = false) String code)
            throws IOException, GeneralSecurityException {
        return activity.track(actors.byId(accountId), "Menandatangani", file.getOriginalFilename(), null,
                MeDocumentController::savedAs, () -> signDocument(accountId, file, page, x, y, width, height,
                        passphrase, code));
    }

    private SavedDocument signDocument(String accountId, MultipartFile file, int page, Float x, Float y, Float width,
            Float height, String passphrase, String code) throws IOException, GeneralSecurityException {
        Account account = store.byId(accountId).orElseThrow(() -> ApiException.notFound("Akun tidak ditemukan"));
        byte[] pdf = RequestInputs.pdf(file);
        PkiService.SignerRecord record = pki.signerRecord(account.signerId());
        if (record.revoked()) {
            throw ApiException.conflict("Sertifikat Anda sudah dicabut");
        }
        // Diperiksa sebelum kunci dibuka, supaya kode TOTP tidak terpakai untuk tanda tangan yang tidak bisa disimpan.
        OutputFolder.requireOutside(Path.of(account.outputDir()), pki.dataDir());
        VisibleSignature visible = null;
        if (account.appearance() == Account.Appearance.VISIBLE) {
            Path specimen = store.dir(accountId).resolve("specimen.png");
            visible = signing.placement(pdf, page, x, y, width, height,
                    Files.exists(specimen) ? Files.readAllBytes(specimen) : null);
        }
        SignerPair pair = accounts.unlock(account, passphrase, code);
        byte[] signed = signing.signHybrid(pdf, pair, record, visible);
        Path saved = OutputFolder.save(Path.of(account.outputDir()), pki.dataDir(), file.getOriginalFilename(), "-ttd",
                signed);
        history.add(accountId, new HistoryStore.Entry(saved.getFileName().toString(), "Ditandatangani", Instant.now(),
                "SAH", saved.toString()));
        return SavedDocument.of(signed, saved);
    }

    /** Verifikasi untuk pengguna: tanpa waktu simulasi, dicatat di riwayat. */
    @PostMapping(value = "/verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VerifyService.VerifyResponse verify(@RequestAttribute(AuthInterceptor.ACCOUNT_ID) String accountId,
            @RequestPart("file") MultipartFile file) throws IOException, GeneralSecurityException {
        return activity.track(actors.byId(accountId), "Memverifikasi", file.getOriginalFilename(),
                MeDocumentController::status, null, () -> {
                    VerifyService.VerifyResponse result = verifier.verify(RequestInputs.pdf(file),
                            file.getOriginalFilename(), null);
                    history.add(accountId, new HistoryStore.Entry(displayName(file), "Diverifikasi", Instant.now(),
                            status(result), null));
                    return result;
                });
    }

    static String status(VerifyService.VerifyResponse result) {
        return result.signatures().isEmpty() ? "TANPA_TANDA_TANGAN"
                : result.validation() == null ? "TIDAK_DIPERIKSA" : result.validation().status().name();
    }

    private static String savedAs(SavedDocument saved) {
        return "Tersimpan sebagai " + saved.fileName();
    }

    /** Perpanjangan keabsahan dengan TSA ML-DSA; hanya bila hasil verifikasi menyarankannya. */
    @PostMapping(value = "/renew", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SavedDocument renew(@RequestAttribute(AuthInterceptor.ACCOUNT_ID) String accountId,
            @RequestPart("file") MultipartFile file) throws IOException, GeneralSecurityException {
        return activity.track(actors.byId(accountId), "Memperpanjang keabsahan", file.getOriginalFilename(), null,
                MeDocumentController::savedAs, () -> renewDocument(accountId, file));
    }

    private SavedDocument renewDocument(String accountId, MultipartFile file)
            throws IOException, GeneralSecurityException {
        Account account = store.byId(accountId).orElseThrow(() -> ApiException.notFound("Akun tidak ditemukan"));
        byte[] pdf = RequestInputs.pdf(file);
        VerifyService.VerifyResponse result = verifier.verify(pdf, file.getOriginalFilename(), null);
        if (result.validation() == null || !result.validation().renewalRecommended()) {
            throw ApiException.conflict("Dokumen ini belum perlu atau sudah tidak bisa diperpanjang");
        }
        byte[] renewed = LtvService.renew(pdf, pki.revocationSource(Instant.now()), tsa.client(RENEW_TSA, null),
                TsaService.profile(RENEW_TSA));
        Path saved = OutputFolder.save(Path.of(account.outputDir()), pki.dataDir(), file.getOriginalFilename(),
                "-diperpanjang", renewed);
        history.add(accountId, new HistoryStore.Entry(saved.getFileName().toString(), "Diperpanjang", Instant.now(),
                "SAH", saved.toString()));
        return SavedDocument.of(renewed, saved);
    }

    private static String displayName(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "dokumen.pdf" : file.getOriginalFilename();
        return name.substring(name.replace('\\', '/').lastIndexOf('/') + 1);
    }

    /**
     * Hasil tanda tangan atau perpanjangan. Sengaja JSON, bukan berkas PDF: pengelola unduhan di browser (misalnya
     * IDM) merebut jawaban application/pdf dan memutus permintaan aplikasi (T-015 temuan 7).
     */
    public record SavedDocument(String fileName, String savedPath, String pdfBase64) {
        static SavedDocument of(byte[] pdf, Path saved) {
            return new SavedDocument(saved.getFileName().toString(), saved.toString(),
                    java.util.Base64.getEncoder().encodeToString(pdf));
        }
    }
}
