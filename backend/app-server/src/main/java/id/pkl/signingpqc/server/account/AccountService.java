package id.pkl.signingpqc.server.account;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import id.pkl.signingpqc.pki.SignerPair;
import id.pkl.signingpqc.server.ApiException;
import id.pkl.signingpqc.server.PkiService;

/** Pendaftaran dua langkah, masuk, dan pembukaan kunci dengan passphrase atau TOTP. */
@Service
public class AccountService {

    static final Duration REGISTRATION_TTL = Duration.ofMinutes(10);
    static final String TOTP_ISSUER = "TTE Uji";
    private static final String DUMMY_HASH = Argon2id.hash("tidak-dipakai".toCharArray());

    public record RegisterStart(String registrationId, String otpauthUri) {
    }

    public record Session(String token, Account account) {
    }

    private record Pending(String username, String email, String passwordHash, byte[] dek, byte[] dekPp,
            byte[] totpSecret, Instant expires) {

        void wipe() {
            Arrays.fill(dek, (byte) 0);
            Arrays.fill(totpSecret, (byte) 0);
        }
    }

    private final AccountStore store;
    private final KeyVault vault;
    private final PkiService pki;
    private final SessionService sessions;
    private final LoginGuard guard;
    private final Clock clock;
    private final String defaultOutputDir;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public AccountService(AccountStore store, KeyVault vault, PkiService pki, SessionService sessions, LoginGuard guard,
            Clock clock, @Value("${signingpqc.default-output-dir:${user.home}/output-signing}") String defaultOutputDir) {
        this.store = store;
        this.vault = vault;
        this.pki = pki;
        this.sessions = sessions;
        this.guard = guard;
        this.clock = clock;
        this.defaultOutputDir = defaultOutputDir;
    }

    public RegisterStart registerStart(String username, String email, char[] password, char[] passphrase)
            throws GeneralSecurityException {
        String user = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        String mail = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (!user.matches("[a-z0-9._-]{3,32}")) {
            throw ApiException.badRequest("Username 3 sampai 32 karakter: huruf, angka, titik, minus, atau garis bawah");
        }
        if (!mail.matches("[^@\\s]+@[^@\\s]+")) {
            throw ApiException.badRequest("Email tidak sah");
        }
        if (password == null || password.length < 8) {
            throw ApiException.badRequest("Password minimal 8 karakter");
        }
        if (passphrase == null || passphrase.length < 12) {
            throw ApiException.badRequest("Passphrase minimal 12 karakter");
        }
        requireUnique(user, mail);
        pruneExpired();
        byte[] dek = KeyVault.newDek();
        byte[] totpSecret = new byte[20];
        random.nextBytes(totpSecret);
        Pending entry = new Pending(user, mail, Argon2id.hash(password), dek, KeyVault.wrapWithPassphrase(dek, passphrase),
                totpSecret, clock.instant().plus(REGISTRATION_TTL));
        String id = newId(16);
        pending.put(id, entry);
        return new RegisterStart(id, Totp.uri(TOTP_ISSUER, user, totpSecret));
    }

    public Session registerFinish(String registrationId, String code) throws GeneralSecurityException, IOException {
        Pending entry = registrationId == null ? null : pending.get(registrationId);
        if (entry == null || clock.instant().isAfter(entry.expires())) {
            if (entry != null) {
                pending.remove(registrationId).wipe();
            }
            throw new ApiException(HttpStatus.GONE, "Pendaftaran kedaluwarsa, ulangi dari awal");
        }
        OptionalLong step = Totp.verify(entry.totpSecret(), code, clock.instant(), 0);
        if (step.isEmpty()) {
            throw ApiException.badRequest("Kode tidak cocok");
        }
        synchronized (this) {
            if (pending.remove(registrationId) == null) {
                throw new ApiException(HttpStatus.GONE, "Pendaftaran kedaluwarsa, ulangi dari awal");
            }
            try {
                requireUnique(entry.username(), entry.email());
                PkiService.AccountSigner signer = pki.issueAccountSigner(entry.username());
                String id = newId(8);
                vault.create(id, signer.pair(), entry.dek(), entry.dekPp(), true);
                vault.writeTotpSecret(id, entry.totpSecret());
                Account account = new Account(id, entry.username(), entry.email(), entry.passwordHash(),
                        signer.record().id(), Account.ConfirmMethod.TOTP, Account.Appearance.VISIBLE, defaultOutputDir,
                        clock.instant(), step.getAsLong());
                store.save(account);
                return new Session(sessions.create(id), account);
            } finally {
                entry.wipe();
            }
        }
    }

    public Session login(String login, char[] password) {
        Account account = store.byLogin(login).orElse(null);
        if (account == null) {
            Argon2id.verify(password == null ? new char[0] : password, DUMMY_HASH);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Username/email atau password salah");
        }
        guard.check(account.id());
        if (password == null || !Argon2id.verify(password, account.passwordHash())) {
            guard.failed(account.id());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Username/email atau password salah");
        }
        guard.succeeded(account.id());
        return new Session(sessions.create(account.id()), account);
    }

    /** Membuka kunci sesuai metode konfirmasi akun. Gagal dihitung untuk penguncian. */
    public SignerPair unlock(Account account, String passphrase, String code) throws GeneralSecurityException, IOException {
        guard.check(account.id());
        if (account.confirmMethod() == Account.ConfirmMethod.PASSPHRASE) {
            if (passphrase == null || passphrase.isEmpty()) {
                throw ApiException.badRequest("Masukkan passphrase");
            }
            try {
                SignerPair pair = vault.unlockWithPassphrase(account.id(), passphrase.toCharArray());
                guard.succeeded(account.id());
                return pair;
            } catch (ApiException e) {
                guard.failed(account.id());
                throw e;
            }
        }
        if (code == null || code.isEmpty()) {
            throw ApiException.badRequest("Masukkan kode dari authenticator");
        }
        byte[] secret = vault.totpSecret(account.id());
        OptionalLong step;
        try {
            step = Totp.verify(secret, code.strip(), clock.instant(), account.lastTotpStep());
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
        if (step.isEmpty()) {
            guard.failed(account.id());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Konfirmasi tidak cocok");
        }
        store.save(account.withLastTotpStep(step.getAsLong()));
        guard.succeeded(account.id());
        return vault.unlockWithApp(account.id());
    }

    /** Ganti metode konfirmasi. Passphrase wajib dan dihitung untuk penguncian bila salah. */
    public Account changeConfirmMethod(Account account, Account.ConfirmMethod method, String passphrase)
            throws GeneralSecurityException, IOException {
        if (method == account.confirmMethod()) {
            return account;
        }
        if (passphrase == null || passphrase.isEmpty()) {
            throw ApiException.badRequest("Masukkan passphrase untuk mengganti metode konfirmasi");
        }
        guard.check(account.id());
        try {
            if (method == Account.ConfirmMethod.TOTP) {
                vault.enableApp(account.id(), passphrase.toCharArray());
            } else {
                vault.disableApp(account.id(), passphrase.toCharArray());
            }
        } catch (ApiException e) {
            guard.failed(account.id());
            throw e;
        }
        guard.succeeded(account.id());
        Account updated = account.withConfirmMethod(method);
        store.save(updated);
        return updated;
    }

    private void requireUnique(String username, String email) {
        if (store.usernameTaken(username)) {
            throw ApiException.conflict("Username sudah dipakai");
        }
        if (store.emailTaken(email)) {
            throw ApiException.conflict("Email sudah dipakai");
        }
    }

    private void pruneExpired() {
        Instant now = clock.instant();
        pending.entrySet().removeIf(e -> {
            if (now.isAfter(e.getValue().expires())) {
                e.getValue().wipe();
                return true;
            }
            return false;
        });
    }

    private String newId(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }
}
