package id.pkl.signingpqc.server.account;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import id.pkl.signingpqc.pki.CaHierarchy;
import id.pkl.signingpqc.pki.PkiFamily;
import id.pkl.signingpqc.pki.SignerIdentity;
import id.pkl.signingpqc.pki.SignerPair;
import id.pkl.signingpqc.pki.TestPkiFactory;
import id.pkl.signingpqc.server.ApiException;
import tools.jackson.databind.json.JsonMapper;

class KeyVaultTest {

    private static final String PASSPHRASE = "passphrase-uji-panjang";
    private static final String PASSWORD = "password-uji-9";
    private static SignerPair pair;

    @TempDir
    Path dir;
    private AccountStore store;
    private KeyVault vault;

    @BeforeAll
    static void pki() throws Exception {
        TestPkiFactory factory = new TestPkiFactory();
        CaHierarchy rsa = factory.createHierarchy(PkiFamily.RSA, "SigningPQC Uji");
        CaHierarchy mlDsa = factory.createHierarchy(PkiFamily.ML_DSA, "SigningPQC Uji");
        pair = factory.issueSignerPair(rsa, mlDsa, new SignerIdentity("budi.uji", null, null, "SigningPQC Uji"));
    }

    @BeforeEach
    void setUp() {
        store = new AccountStore(dir, JsonMapper.builder().build());
        vault = new KeyVault(store, new AppKey(dir));
    }

    private void create(String id, boolean withApp) throws Exception {
        store.save(new Account(id, "budi.uji", "Budi.Uji@contoh.go.id", Argon2id.hash(PASSWORD.toCharArray()), "akun-1",
                Account.ConfirmMethod.TOTP, Account.Appearance.VISIBLE, dir.resolve("keluar").toString(), Instant.now(), 0));
        byte[] dek = KeyVault.newDek();
        vault.create(id, pair, dek, KeyVault.wrapWithPassphrase(dek, PASSPHRASE.toCharArray()), withApp);
    }

    @Test
    void passphraseBenarMembukaKunci() throws Exception {
        create("a1", false);
        SignerPair unlocked = vault.unlockWithPassphrase("a1", PASSPHRASE.toCharArray());
        assertArrayEquals(pair.rsa().privateKey().getEncoded(), unlocked.rsa().privateKey().getEncoded());
        assertArrayEquals(pair.mlDsa().privateKey().getEncoded(), unlocked.mlDsa().privateKey().getEncoded());
        assertEquals(pair.rsa().certificate(), unlocked.rsa().certificate());
        assertEquals("budi.uji", unlocked.identity().commonName());
    }

    @Test
    void passphraseSalahDitolak() throws Exception {
        create("a1", false);
        ApiException e = assertThrows(ApiException.class,
                () -> vault.unlockWithPassphrase("a1", "passphrase-salah-sekali".toCharArray()));
        assertEquals(HttpStatus.UNAUTHORIZED, e.status());
        assertEquals("Konfirmasi tidak cocok", e.getMessage());
    }

    @Test
    void modeTotpButuhDekApp() throws Exception {
        create("a1", false);
        assertFalse(vault.hasApp("a1"));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> vault.unlockWithApp("a1")).status());
        vault.enableApp("a1", PASSPHRASE.toCharArray());
        assertArrayEquals(pair.rsa().privateKey().getEncoded(), vault.unlockWithApp("a1").rsa().privateKey().getEncoded());
        vault.disableApp("a1", PASSPHRASE.toCharArray());
        assertFalse(Files.exists(store.dir("a1").resolve("dek.app")));
        assertThrows(ApiException.class, () -> vault.enableApp("a1", "passphrase-salah-sekali".toCharArray()));
        assertFalse(vault.hasApp("a1"));
    }

    @Test
    void tidakAdaRahasiaTerbuka() throws Exception {
        create("a1", true);
        vault.writeTotpSecret("a1", "rahasia-totp-20-byte".getBytes(StandardCharsets.US_ASCII));
        List<byte[]> secrets = List.of(PASSPHRASE.getBytes(StandardCharsets.UTF_8), PASSWORD.getBytes(StandardCharsets.UTF_8),
                "rahasia-totp-20-byte".getBytes(StandardCharsets.US_ASCII), pair.rsa().privateKey().getEncoded(),
                pair.mlDsa().privateKey().getEncoded());
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                byte[] content = Files.readAllBytes(file);
                for (byte[] secret : secrets) {
                    assertFalse(contains(content, secret), file + " memuat rahasia terbuka");
                }
            }
        }
    }

    @Test
    void rahasiaTotpBolakBalik() throws Exception {
        create("a1", true);
        byte[] secret = "rahasia-totp-20-byte".getBytes(StandardCharsets.US_ASCII);
        vault.writeTotpSecret("a1", secret);
        assertArrayEquals(secret, vault.totpSecret("a1"));
    }

    @Test
    void storeCariLoginTidakPekaHuruf() throws Exception {
        create("a1", false);
        assertEquals("a1", store.byLogin("BUDI.UJI").orElseThrow().id());
        assertEquals("a1", store.byLogin("budi.uji@contoh.go.id").orElseThrow().id());
        assertTrue(store.usernameTaken("Budi.Uji"));
        assertTrue(store.emailTaken("BUDI.UJI@contoh.go.id"));
        assertTrue(store.byLogin("orang.lain").isEmpty());
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
