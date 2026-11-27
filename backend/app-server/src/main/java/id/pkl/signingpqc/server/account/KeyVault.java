package id.pkl.signingpqc.server.account;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.Arrays;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import id.pkl.signingpqc.core.CryptoProviders;
import id.pkl.signingpqc.core.Pkcs12Credentials;
import id.pkl.signingpqc.core.SigningCredential;
import id.pkl.signingpqc.pki.SignerIdentity;
import id.pkl.signingpqc.pki.SignerPair;
import id.pkl.signingpqc.server.ApiException;

/**
 * Kunci privat akun dibungkus dua lapis: PKCS#12 berisi kunci RSA dan ML-DSA dienkripsi AES-256-GCM dengan DEK acak
 * ({@code keys.enc}); DEK dibungkus dengan KEK Argon2id dari passphrase ({@code dek.pp}, selalu ada) dan, hanya
 * selama mode TOTP aktif, dengan kunci aplikasi ({@code dek.app}).
 */
@Component
public class KeyVault {

    private static final char[] P12_PASSWORD = "keys".toCharArray();
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountStore store;
    private final AppKey appKey;

    public KeyVault(AccountStore store, AppKey appKey) {
        this.store = store;
        this.appKey = appKey;
    }

    public static byte[] newDek() {
        byte[] dek = new byte[32];
        RANDOM.nextBytes(dek);
        return dek;
    }

    /** {@code salt(16) || AES-GCM(KEK, dek)} dengan KEK = Argon2id(passphrase, salt). */
    public static byte[] wrapWithPassphrase(byte[] dek, char[] passphrase) throws GeneralSecurityException {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] kek = Argon2id.deriveKey(passphrase, salt);
        try {
            byte[] sealed = Aead.seal(kek, dek, aad("dek.pp"));
            byte[] out = Arrays.copyOf(salt, SALT_BYTES + sealed.length);
            System.arraycopy(sealed, 0, out, SALT_BYTES, sealed.length);
            return out;
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    public void create(String accountId, SignerPair pair, byte[] dek, byte[] dekPp, boolean withApp)
            throws GeneralSecurityException, IOException {
        Path folder = store.dir(accountId);
        Files.createDirectories(folder);
        byte[] p12 = pkcs12(pair);
        try {
            write(folder.resolve("keys.enc"), Aead.seal(dek, p12, aad("keys:" + accountId)));
        } finally {
            Arrays.fill(p12, (byte) 0);
        }
        write(folder.resolve("dek.pp"), dekPp);
        if (withApp) {
            writeDekApp(accountId, dek);
        }
    }

    public SignerPair unlockWithPassphrase(String accountId, char[] passphrase) throws GeneralSecurityException, IOException {
        byte[] dek = dekFromPassphrase(accountId, passphrase);
        try {
            return open(accountId, dek);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    public SignerPair unlockWithApp(String accountId) throws GeneralSecurityException, IOException {
        byte[] dek = dekFromApp(accountId);
        try {
            return open(accountId, dek);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    public boolean hasApp(String accountId) {
        return Files.exists(store.dir(accountId).resolve("dek.app"));
    }

    /** Pindah ke mode TOTP: buka DEK dengan passphrase lalu buat {@code dek.app}. */
    public void enableApp(String accountId, char[] passphrase) throws GeneralSecurityException, IOException {
        byte[] dek = dekFromPassphrase(accountId, passphrase);
        try {
            writeDekApp(accountId, dek);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /** Pindah ke mode Passphrase: passphrase dibuktikan dulu, lalu {@code dek.app} dihapus. */
    public void disableApp(String accountId, char[] passphrase) throws GeneralSecurityException, IOException {
        Arrays.fill(dekFromPassphrase(accountId, passphrase), (byte) 0);
        Files.deleteIfExists(store.dir(accountId).resolve("dek.app"));
    }

    public void writeTotpSecret(String accountId, byte[] secret) throws GeneralSecurityException, IOException {
        byte[] key = appKey.key();
        try {
            write(store.dir(accountId).resolve("totp.enc"), Aead.seal(key, secret, aad("totp:" + accountId)));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public byte[] totpSecret(String accountId) throws GeneralSecurityException, IOException {
        byte[] key = appKey.key();
        try {
            return Aead.open(key, Files.readAllBytes(store.dir(accountId).resolve("totp.enc")), aad("totp:" + accountId));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private byte[] dekFromPassphrase(String accountId, char[] passphrase) throws IOException {
        byte[] wrapped = Files.readAllBytes(store.dir(accountId).resolve("dek.pp"));
        byte[] salt = Arrays.copyOf(wrapped, SALT_BYTES);
        byte[] kek = Argon2id.deriveKey(passphrase, salt);
        try {
            return Aead.open(kek, Arrays.copyOfRange(wrapped, SALT_BYTES, wrapped.length), aad("dek.pp"));
        } catch (GeneralSecurityException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Konfirmasi tidak cocok");
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    private byte[] dekFromApp(String accountId) throws GeneralSecurityException, IOException {
        Path file = store.dir(accountId).resolve("dek.app");
        if (!Files.exists(file)) {
            throw ApiException.conflict("Mode TOTP belum aktif untuk akun ini");
        }
        byte[] key = appKey.key();
        try {
            return Aead.open(key, Files.readAllBytes(file), aad("dek.app:" + accountId));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private void writeDekApp(String accountId, byte[] dek) throws GeneralSecurityException, IOException {
        byte[] key = appKey.key();
        try {
            write(store.dir(accountId).resolve("dek.app"), Aead.seal(key, dek, aad("dek.app:" + accountId)));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private SignerPair open(String accountId, byte[] dek) throws GeneralSecurityException, IOException {
        byte[] p12 = Aead.open(dek, Files.readAllBytes(store.dir(accountId).resolve("keys.enc")), aad("keys:" + accountId));
        try {
            SigningCredential rsa = Pkcs12Credentials.read(p12, "rsa", P12_PASSWORD);
            SigningCredential mlDsa = Pkcs12Credentials.read(p12, "mldsa", P12_PASSWORD);
            X500Name subject = X500Name.getInstance(rsa.certificate().getSubjectX500Principal().getEncoded());
            String cn = IETFUtils.valueToString(subject.getRDNs(BCStyle.CN)[0].getFirst().getValue());
            String o = subject.getRDNs(BCStyle.O).length == 0 ? null
                    : IETFUtils.valueToString(subject.getRDNs(BCStyle.O)[0].getFirst().getValue());
            return new SignerPair(new SignerIdentity(cn, null, null, o), rsa, mlDsa);
        } finally {
            Arrays.fill(p12, (byte) 0);
        }
    }

    private static byte[] pkcs12(SignerPair pair) throws GeneralSecurityException, IOException {
        CryptoProviders.ensureRegistered();
        KeyStore keyStore = KeyStore.getInstance("PKCS12-AES256-AES128", CryptoProviders.BC);
        keyStore.load(null, null);
        keyStore.setKeyEntry("rsa", pair.rsa().privateKey(), P12_PASSWORD, pair.rsa().chain().toArray(new Certificate[0]));
        keyStore.setKeyEntry("mldsa", pair.mlDsa().privateKey(), P12_PASSWORD,
                pair.mlDsa().chain().toArray(new Certificate[0]));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        keyStore.store(out, P12_PASSWORD);
        return out.toByteArray();
    }

    private static void write(Path file, byte[] data) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        Files.write(temp, data);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static byte[] aad(String label) {
        return label.getBytes(StandardCharsets.US_ASCII);
    }
}
