package id.pkl.signingpqc.server;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bouncycastle.asn1.x509.CRLReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import id.pkl.signingpqc.core.Pem;
import id.pkl.signingpqc.core.Pkcs12Credentials;
import id.pkl.signingpqc.core.SigningCredential;
import id.pkl.signingpqc.pki.CaHierarchy;
import id.pkl.signingpqc.core.ltv.RevocationSource;
import id.pkl.signingpqc.core.ltv.ValidationData;
import id.pkl.signingpqc.pki.CertificateAuthority;
import id.pkl.signingpqc.pki.LongTermPki;
import id.pkl.signingpqc.pki.PkiFamily;
import id.pkl.signingpqc.pki.SignerIdentity;
import id.pkl.signingpqc.pki.SignerPair;
import id.pkl.signingpqc.pki.TestPkiFactory;
import id.pkl.signingpqc.pki.TestPkiRevocationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * PKI uji yang dipakai backend: dua hierarki CA (RSA dan ML-DSA), satu TSA per keluarga, pasangan sertifikat
 * penanda tangan, pencabutan, dan CRL. Disimpan di folder data (PKCS#12 berpassword + state.json) agar tetap ada
 * setelah backend dimulai ulang. Hanya untuk lingkungan uji.
 */
@Service
public class PkiService {

    private static final Logger LOG = LoggerFactory.getLogger(PkiService.class);
    private static final String STATE_FILE = "state.json";

    /** TSA uji profil jangka panjang: RSA generasi 1 (2 tahun), RSA generasi 2 (10 tahun), ML-DSA (20 tahun). */
    public static final List<String> TSA_IDS = List.of("rsa-g1", "rsa-g2", "mldsa");

    /** Data yang disimpan di state.json (tanpa kunci; kunci ada di berkas .p12). */
    public record State(String organization, Instant createdAt, Map<String, Long> crlNumbers,
            List<Revocation> revocations, List<SignerRecord> signers) {
    }

    public record Revocation(String caId, String serialHex, Instant time, int reason) {
    }

    public record SignerRecord(String id, String commonName, String title, String unit, Instant createdAt,
            boolean revoked) {
    }

    private final Path dataDir;
    private final char[] password;
    private final JsonMapper json;
    private final TestPkiFactory factory;
    private final SecureRandom random = new SecureRandom();

    private State state;
    private final Map<String, CertificateAuthority> cas = new LinkedHashMap<>();
    private final Map<String, SigningCredential> tsas = new LinkedHashMap<>();
    private final Map<String, SignerPair> signers = new LinkedHashMap<>();

    public PkiService(@Value("${signingpqc.data-dir}") Path dataDir,
            @Value("${signingpqc.keystore-password}") String password,
            @Value("${signingpqc.crl-base-url}") String crlBaseUrl, JsonMapper json) throws IOException {
        this.dataDir = dataDir;
        this.password = password.toCharArray();
        this.json = json;
        this.factory = new TestPkiFactory(crlBaseUrl);
        load();
    }

    // --------------------------------------------------------------------------------- status

    public synchronized boolean ready() {
        return state != null;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized Map<String, CertificateAuthority> cas() {
        return Map.copyOf(cas);
    }

    /** TSA bawaan per keluarga untuk signature timestamp: RSA generasi 1 dan ML-DSA. */
    public synchronized SigningCredential tsa(PkiFamily family) {
        return tsa(family == PkiFamily.RSA ? "rsa-g1" : "mldsa");
    }

    /** TSA berdasarkan id ({@link #TSA_IDS}); "rsa" berarti "rsa-g1". */
    public synchronized SigningCredential tsa(String id) {
        requireReady();
        String key = "rsa".equals(id) ? "rsa-g1" : id;
        if (!TSA_IDS.contains(key)) {
            throw ApiException.notFound("TSA '" + id + "' tidak dikenal (pakai " + String.join(", ", TSA_IDS) + ")");
        }
        SigningCredential credential = tsas.get(key);
        if (credential == null) {
            throw ApiException.conflict("PKI uji dibuat dengan profil lama tanpa TSA '" + key
                    + "'. Buat ulang lewat POST /api/pki/setup?reset=true");
        }
        return credential;
    }

    /** Id TSA yang tersedia, berurutan. */
    public synchronized List<String> tsaIds() {
        return TSA_IDS.stream().filter(tsas::containsKey).toList();
    }

    /** Root kedua hierarki sebagai trust anchor validasi. */
    public synchronized Set<X509Certificate> trustAnchors() {
        requireReady();
        return Set.of(cas.get(PkiFamily.RSA.slug() + "-root").certificate(),
                cas.get(PkiFamily.ML_DSA.slug() + "-root").certificate());
    }

    /**
     * Sumber rantai dan CRL untuk lapisan data validasi, dengan CRL bertanggal {@code at} (waktu simulasi atau
     * sekarang). Pencabutan yang tercatat sampai {@code at} ikut dimasukkan.
     */
    public synchronized RevocationSource revocationSource(Instant at) {
        requireReady();
        TestPkiRevocationSource source = new TestPkiRevocationSource(cas.values(), factory, Clock.fixed(at, ZoneOffset.UTC));
        for (Revocation revocation : state.revocations()) {
            CertificateAuthority ca = cas.get(revocation.caId());
            for (SignerPair pair : signers.values()) {
                for (SigningCredential credential : List.of(pair.rsa(), pair.mlDsa())) {
                    X509Certificate certificate = credential.certificate();
                    if (ca != null && certificate.getSerialNumber().toString(16).equals(revocation.serialHex())
                            && certificate.getIssuerX500Principal().equals(ca.certificate().getSubjectX500Principal())) {
                        source.revoke(certificate, revocation.time());
                    }
                }
            }
        }
        return source;
    }

    /**
     * Data validasi lokal PKI uji untuk pemeriksaan: semua sertifikat CA dan satu CRL per CA bertanggal {@code at}
     * yang memuat pencabutan tercatat sampai {@code at}. Tidak menaikkan nomor CRL yang tersimpan, karena CRL ini
     * hanya dipakai di memori untuk validasi (dokumen tanpa DSS tetap bisa dicek status pencabutannya).
     */
    public synchronized ValidationData localValidationData(Instant at) throws GeneralSecurityException {
        requireReady();
        List<X509Certificate> certificates = new ArrayList<>();
        List<X509CRL> crls = new ArrayList<>();
        for (Map.Entry<String, CertificateAuthority> entry : cas.entrySet()) {
            String caId = entry.getKey();
            List<TestPkiFactory.RevokedCertificate> revoked = state.revocations().stream()
                    .filter(r -> r.caId().equals(caId) && !r.time().isAfter(at))
                    .map(r -> new TestPkiFactory.RevokedCertificate(new BigInteger(r.serialHex(), 16), r.time(),
                            r.reason()))
                    .toList();
            certificates.add(entry.getValue().certificate());
            crls.add(factory.createCrl(entry.getValue(), revoked, state.crlNumbers().getOrDefault(caId, 0L) + 1, at));
        }
        return new ValidationData(certificates, crls);
    }

    public synchronized SignerPair signer(String id) {
        requireReady();
        SignerPair pair = signers.get(id);
        if (pair == null) {
            throw ApiException.notFound("Penanda tangan '" + id + "' tidak ditemukan");
        }
        return pair;
    }

    public synchronized SignerRecord signerRecord(String id) {
        signer(id);
        return state.signers().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    public synchronized List<SignerRecord> signerRecords() {
        return ready() ? List.copyOf(state.signers()) : List.of();
    }

    public Path dataDir() {
        return dataDir;
    }

    // --------------------------------------------------------------------------------- perubahan

    /** Membuat dua hierarki CA (profil jangka panjang) dan tiga TSA. Bila sudah ada dan reset=false, ditolak (409). */
    public synchronized State setup(String organization, boolean reset) throws GeneralSecurityException, IOException {
        if (ready() && !reset) {
            throw ApiException.conflict("PKI uji sudah dibuat. Pakai reset=true untuk membuat ulang (semua sertifikat lama hilang).");
        }
        if (organization == null || organization.isBlank()) {
            throw ApiException.badRequest("Nama organisasi wajib diisi");
        }
        cas.clear();
        tsas.clear();
        signers.clear();
        // Hanya berkas milik PKI uji yang dihapus, bukan seluruh folder data.
        for (String name : List.of("ca", "tsa", "signers", STATE_FILE)) {
            Path path = dataDir.resolve(name);
            if (Files.exists(path)) {
                deleteRecursively(path);
            }
        }
        Map<String, Long> crlNumbers = new LinkedHashMap<>();
        Map<PkiFamily, CaHierarchy> hierarchies = new EnumMap<>(PkiFamily.class);
        for (PkiFamily family : PkiFamily.values()) {
            CaHierarchy hierarchy = factory.createHierarchy(family, organization.strip(), LongTermPki.ROOT_VALIDITY,
                    LongTermPki.INTERMEDIATE_VALIDITY);
            hierarchies.put(family, hierarchy);
            for (CertificateAuthority ca : List.of(hierarchy.root(), hierarchy.intermediate())) {
                cas.put(ca.id(), ca);
                crlNumbers.put(ca.id(), 0L);
                writeCredential("ca/" + ca.id(), ca.credential());
            }
        }
        CertificateAuthority rsaIntermediate = hierarchies.get(PkiFamily.RSA).intermediate();
        CertificateAuthority mlDsaIntermediate = hierarchies.get(PkiFamily.ML_DSA).intermediate();
        tsas.put("rsa-g1", factory.issueTsa(rsaIntermediate, "TSA Uji RSA G1", LongTermPki.TSA_RSA_GEN1_VALIDITY));
        tsas.put("rsa-g2", factory.issueTsa(rsaIntermediate, "TSA Uji RSA G2", LongTermPki.TSA_RSA_GEN2_VALIDITY));
        tsas.put("mldsa", factory.issueTsa(mlDsaIntermediate, "TSA Uji ML-DSA", LongTermPki.TSA_ML_DSA_VALIDITY));
        for (Map.Entry<String, SigningCredential> entry : tsas.entrySet()) {
            writeCredential("tsa/" + entry.getKey(), entry.getValue());
        }
        state = new State(organization.strip(), Instant.now(), crlNumbers, List.of(), List.of());
        saveState();
        LOG.info("PKI uji dibuat di {}", dataDir.toAbsolutePath());
        return state;
    }

    public synchronized SignerRecord issueSigner(String commonName, String title, String unit)
            throws GeneralSecurityException, IOException {
        requireReady();
        if (commonName == null || commonName.isBlank()) {
            throw ApiException.badRequest("Nama penanda tangan wajib diisi");
        }
        SignerIdentity identity = new SignerIdentity(commonName.strip(), blankToNull(title), blankToNull(unit),
                state.organization());
        SignerPair pair = factory.issueSignerPair(hierarchy(PkiFamily.RSA), hierarchy(PkiFamily.ML_DSA), identity);
        String id = "pt-" + HexFormat.of().formatHex(random.generateSeed(4));
        signers.put(id, pair);
        writeCredential("signers/" + id + "/rsa", pair.rsa());
        writeCredential("signers/" + id + "/mldsa", pair.mlDsa());
        SignerRecord record = new SignerRecord(id, identity.commonName(), identity.title(), identity.organizationalUnit(),
                Instant.now(), false);
        List<SignerRecord> list = new ArrayList<>(state.signers());
        list.add(record);
        state = new State(state.organization(), state.createdAt(), state.crlNumbers(), state.revocations(), list);
        saveState();
        return record;
    }

    /** Mencabut kedua sertifikat penanda tangan (alasan keyCompromise). */
    public synchronized SignerRecord revoke(String id) throws IOException {
        SignerPair pair = signer(id);
        SignerRecord current = signerRecord(id);
        if (current.revoked()) {
            throw ApiException.conflict("Penanda tangan '" + id + "' sudah dicabut");
        }
        List<Revocation> revocations = new ArrayList<>(state.revocations());
        Instant now = Instant.now();
        revocations.add(new Revocation(PkiFamily.RSA.slug() + "-intermediate",
                pair.rsa().certificate().getSerialNumber().toString(16), now, CRLReason.keyCompromise));
        revocations.add(new Revocation(PkiFamily.ML_DSA.slug() + "-intermediate",
                pair.mlDsa().certificate().getSerialNumber().toString(16), now, CRLReason.keyCompromise));
        List<SignerRecord> list = state.signers().stream()
                .map(s -> s.id().equals(id) ? new SignerRecord(s.id(), s.commonName(), s.title(), s.unit(),
                        s.createdAt(), true) : s)
                .toList();
        state = new State(state.organization(), state.createdAt(), state.crlNumbers(), revocations, list);
        saveState();
        return signerRecord(id);
    }

    /** CRL terbaru untuk CA (nomor CRL naik setiap kali diterbitkan). */
    public synchronized X509CRL crl(String caId) throws GeneralSecurityException, IOException {
        requireReady();
        CertificateAuthority ca = cas.get(caId);
        if (ca == null) {
            throw ApiException.notFound("CA '" + caId + "' tidak ditemukan");
        }
        List<TestPkiFactory.RevokedCertificate> revoked = state.revocations().stream()
                .filter(r -> r.caId().equals(caId))
                .map(r -> new TestPkiFactory.RevokedCertificate(new BigInteger(r.serialHex(), 16), r.time(), r.reason()))
                .toList();
        Map<String, Long> numbers = new LinkedHashMap<>(state.crlNumbers());
        long next = numbers.getOrDefault(caId, 0L) + 1;
        numbers.put(caId, next);
        state = new State(state.organization(), state.createdAt(), numbers, state.revocations(), state.signers());
        saveState();
        return factory.createCrl(ca, revoked, next);
    }

    public synchronized X509Certificate caCertificate(String caId) {
        requireReady();
        CertificateAuthority ca = cas.get(caId);
        if (ca == null) {
            throw ApiException.notFound("CA '" + caId + "' tidak ditemukan");
        }
        return ca.certificate();
    }

    // --------------------------------------------------------------------------------- penyimpanan

    private void load() throws IOException {
        Path stateFile = dataDir.resolve(STATE_FILE);
        if (!Files.exists(stateFile)) {
            LOG.info("PKI uji belum dibuat (folder data {})", dataDir.toAbsolutePath());
            return;
        }
        try {
            State loaded = json.readValue(stateFile.toFile(), State.class);
            for (PkiFamily family : PkiFamily.values()) {
                for (String suffix : List.of("-root", "-intermediate")) {
                    String id = family.slug() + suffix;
                    cas.put(id, new CertificateAuthority(id, family, readCredential("ca/" + id)));
                }
            }
            for (String id : TSA_IDS) {
                // Profil lama (Sprint 1) menyimpan tsa/rsa dan tsa/mldsa; tsa/rsa dipakai sebagai generasi 1.
                String file = Files.exists(dataDir.resolve("tsa/" + id + ".p12")) ? id
                        : "rsa-g1".equals(id) ? "rsa" : id;
                if (Files.exists(dataDir.resolve("tsa/" + file + ".p12"))) {
                    tsas.put(id, readCredential("tsa/" + file));
                }
            }
            for (SignerRecord record : loaded.signers()) {
                SignerIdentity identity = new SignerIdentity(record.commonName(), record.title(), record.unit(),
                        loaded.organization());
                signers.put(record.id(), new SignerPair(identity, readCredential("signers/" + record.id() + "/rsa"),
                        readCredential("signers/" + record.id() + "/mldsa")));
            }
            state = loaded;
            LOG.info("PKI uji dimuat dari {} ({} penanda tangan)", dataDir.toAbsolutePath(), signers.size());
        } catch (GeneralSecurityException | RuntimeException e) {
            cas.clear();
            tsas.clear();
            signers.clear();
            state = null;
            LOG.warn("PKI uji di {} tidak dapat dimuat ({}). Buat ulang lewat POST /api/pki/setup?reset=true",
                    dataDir.toAbsolutePath(), e.getMessage());
        }
    }

    private void saveState() throws IOException {
        Files.createDirectories(dataDir);
        json.writerWithDefaultPrettyPrinter().writeValue(dataDir.resolve(STATE_FILE).toFile(), state);
    }

    private void writeCredential(String name, SigningCredential credential) throws GeneralSecurityException, IOException {
        Path p12 = dataDir.resolve(name + ".p12");
        Files.createDirectories(p12.getParent());
        Files.write(p12, Pkcs12Credentials.write(credential, p12.getFileName().toString(), password));
        Files.writeString(dataDir.resolve(name + ".pem"), Pem.certificates(credential.chain()));
    }

    private SigningCredential readCredential(String name) throws GeneralSecurityException, IOException {
        return Pkcs12Credentials.read(Files.readAllBytes(dataDir.resolve(name + ".p12")), password);
    }

    private CaHierarchy hierarchy(PkiFamily family) {
        return new CaHierarchy(family, cas.get(family.slug() + "-root"), cas.get(family.slug() + "-intermediate"));
    }

    private void requireReady() {
        if (state == null) {
            throw ApiException.conflict("PKI uji belum dibuat. Jalankan POST /api/pki/setup lebih dulu.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
