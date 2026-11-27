package id.pkl.signingpqc.server.account;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;

import javax.imageio.ImageIO;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import id.pkl.signingpqc.server.ActivityLog;
import id.pkl.signingpqc.server.ApiException;
import id.pkl.signingpqc.server.PkiService;

/** Endpoint pengguna yang sudah masuk: profil, pengaturan, spesimen, riwayat. */
@RestController
@RequestMapping("/api/me")
public class MeController {

    static final int SPECIMEN_MAX_BYTES = 1024 * 1024;
    static final int SPECIMEN_MAX_SIDE = 2000;

    public record CertificateSummary(String algorithm, String issuer, String serialHex, Instant notBefore,
            Instant notAfter, boolean revoked) {
    }

    public record MeResponse(String username, String email, Instant createdAt, List<CertificateSummary> certificates,
            String confirmMethod, String appearance, String outputDir, boolean hasSpecimen) {
    }

    public record SettingsRequest(Account.ConfirmMethod confirmMethod, Account.Appearance appearance, String outputDir,
            String passphrase) {
        @Override
        public String toString() {
            return "SettingsRequest[" + confirmMethod + ", " + appearance + ", " + outputDir + "]";
        }
    }

    private final AccountStore store;
    private final AccountService accounts;
    private final PkiService pki;
    private final HistoryStore history;
    private final Path dataDir;

    private final ActivityLog activity;

    public MeController(AccountStore store, AccountService accounts, PkiService pki, HistoryStore history,
            ActivityLog activity) {
        this.activity = activity;
        this.dataDir = pki.dataDir();
        this.store = store;
        this.accounts = accounts;
        this.pki = pki;
        this.history = history;
    }

    @GetMapping
    public MeResponse me(@RequestAttribute(AuthInterceptor.ACCOUNT_ID) String accountId) {
        return response(account(accountId));
    }

    @PutMapping("/settings")
    public MeResponse settings(@RequestAttribute(AuthInterceptor.ACCOUNT_ID) String accountId,
            @RequestBody SettingsRequest request) throws GeneralSecurityException, IOException {
        return activity.track(account(accountId).username(), "Mengubah pengaturan", null, null,
                r -> settingsDetail(request), () -> applySettings(accountId, request));
    }

    private static String settingsDetail(SettingsRequest request) {
        List<String> parts = new java.util.ArrayList<>();
        if (request.confirmMethod() != null) {
            parts.add("Konfirmasi " + request.confirmMethod());
        }
        if (request.appearance() != null) {
            parts.add("Tampilan " + request.appearance());
        }
        if (request.outputDir() != null) {
            parts.add("Folder keluaran " + request.outputDir());
        }
        return String.join(", ", parts);
    }

    private MeResponse applySettings(String accountId, SettingsRequest request)
            throws GeneralSecurityException, IOException {
        Account account = account(accountId);
        String outputDir = request.outputDir() == null ? null : usableFolder(request.outputDir());
        if (request.confirmMethod() != null) {
            account = accounts.changeConfirmMethod(account, request.confirmMethod(), request.passphrase());
        }
        if (request.appearance() != null) {
            account = account.withAppearance(request.appearance());
        }
        if (outputDir != null) {
            account = account.withOutputDir(outputDir);
        }
        store.save(account);
        return response(account);
    }

    @PutMapping(value = "/specimen", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MeResponse uploadSpecimen(@RequestAttribute(AuthInterceptor.ACCOUNT_ID) String accountId,
            @RequestPart("file") MultipartFile file) throws IOException, GeneralSecurityException {
        return activity.track(account(accountId).username(), "Mengganti spesimen", file.getOriginalFilename(), null,
                null, () -> saveSpecimen(accountId, file));
    }

    private MeResponse saveSpecimen(String accountId, MultipartFile file) throws IOException {
        if (file.getSize() > SPECIMEN_MAX_BYTES) {
            throw ApiException.badRequest("Spesimen maksimal 1 MB");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(file.getBytes()));
        if (image == null) {
            throw ApiException.badRequest("Spesimen harus gambar PNG atau JPEG");
        }
        if (image.getWidth() > SPECIMEN_MAX_SIDE || image.getHeight() > SPECIMEN_MAX_SIDE) {
            throw ApiException.badRequest("Spesimen maksimal 2000 piksel per sisi");
        }
        Path target = specimenFile(accountId);
        Path temp = Files.createTempFile(target.getParent(), "specimen", ".tmp");
        ImageIO.write(image, "png", temp.toFile());
        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return response(account(accountId));
    }

    @GetMapping(value = "/specimen", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> specimen(@RequestAttribute(AuthInterceptor.ACCOUNT_ID) String accountId)
            throws IOException {
        Path file = specimenFile(accountId);
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(Files.readAllBytes(file));
    }

    @GetMapping("/history")
    public List<HistoryStore.Entry> history(@RequestAttribute(AuthInterceptor.ACCOUNT_ID) String accountId) {
        return history.latest(accountId);
    }

    /** Log aktivitas perangkat ini (semua akun), terbaru di depan (KF-35). */
    @GetMapping("/activity")
    public List<ActivityLog.Entry> activity() {
        return activity.latest();
    }

    // ---------------------------------------------------------------------------------------------------------

    Account account(String accountId) {
        return store.byId(accountId).orElseThrow(() -> ApiException.notFound("Akun tidak ditemukan"));
    }

    Path specimenFile(String accountId) {
        return store.dir(accountId).resolve("specimen.png");
    }

    private MeResponse response(Account account) {
        List<X509Certificate> certificates = pki.accountCertificates(account.signerId());
        boolean revoked = pki.signerRecord(account.signerId()).revoked();
        List<CertificateSummary> summaries = certificates.stream().map(c -> summary(c, revoked)).toList();
        return new MeResponse(account.username(), account.email(), account.createdAt(), summaries,
                account.confirmMethod().name(), account.appearance().name(), account.outputDir(),
                Files.exists(specimenFile(account.id())));
    }

    private static CertificateSummary summary(X509Certificate certificate, boolean revoked) {
        String algorithm = certificate.getPublicKey() instanceof RSAPublicKey rsa
                ? "RSA " + rsa.getModulus().bitLength() + " bit"
                : certificate.getPublicKey().getAlgorithm();
        X500Name issuer = X500Name.getInstance(certificate.getIssuerX500Principal().getEncoded());
        String issuerName = issuer.getRDNs(BCStyle.CN).length == 0 ? issuer.toString()
                : IETFUtils.valueToString(issuer.getRDNs(BCStyle.CN)[0].getFirst().getValue());
        return new CertificateSummary(algorithm, issuerName, certificate.getSerialNumber().toString(16).toUpperCase(),
                certificate.getNotBefore().toInstant(), certificate.getNotAfter().toInstant(), revoked);
    }

    /**
     * Path absolut yang bisa dibuat dan ditulisi, di luar folder data aplikasi (akun, kunci, PKI uji);
     * dikembalikan dalam bentuk normal.
     */
    private String usableFolder(String value) {
        Path folder;
        try {
            folder = Path.of(value.strip());
        } catch (java.nio.file.InvalidPathException e) {
            throw ApiException.badRequest("Folder tidak bisa dipakai untuk menyimpan");
        }
        if (!folder.isAbsolute()) {
            throw ApiException.badRequest("Folder harus berupa path lengkap, misalnya C:\\Users\\nama\\output-signing");
        }
        if (insideDataDir(folder)) {
            throw ApiException.badRequest("Folder keluaran tidak boleh berada di dalam folder data aplikasi");
        }
        try {
            Files.createDirectories(folder);
            if (insideDataDir(folder.toRealPath())) {
                throw ApiException.badRequest("Folder keluaran tidak boleh berada di dalam folder data aplikasi");
            }
            Path probe = Files.createTempFile(folder, ".uji-tulis", ".tmp");
            Files.delete(probe);
        } catch (IOException | SecurityException e) {
            throw ApiException.badRequest("Folder tidak bisa dipakai untuk menyimpan");
        }
        return folder.normalize().toString();
    }

    /**
     * Membandingkan path nyata: bagian path yang sudah ada diselesaikan lewat {@code toRealPath} (tautan simbolik
     * ikut diikuti), sisanya ditempel dalam bentuk normal.
     */
    private boolean insideDataDir(Path folder) {
        return OutputFolder.inside(folder, dataDir);
    }
}
