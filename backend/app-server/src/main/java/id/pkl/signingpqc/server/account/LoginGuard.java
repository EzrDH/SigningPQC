package id.pkl.signingpqc.server.account;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import id.pkl.signingpqc.server.ApiException;

/**
 * Lima kegagalan berturut-turut pada satu faktor mengunci akun selama 5 menit. Faktor password (masuk) dan
 * faktor konfirmasi (passphrase atau TOTP) dihitung terpisah: keberhasilan satu faktor hanya mengosongkan
 * hitungannya sendiri, sehingga masuk ulang dengan password tidak mereset tebakan passphrase atau TOTP.
 */
@Component
public class LoginGuard {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(5);

    public enum Factor { PASSWORD, CONFIRM }

    private record Key(String accountId, Factor factor) {
    }

    private final Clock clock;
    private final Map<Key, Integer> failures = new HashMap<>();
    private final Map<String, Instant> lockedUntil = new HashMap<>();

    public LoginGuard(Clock clock) {
        this.clock = clock;
    }

    public synchronized void check(String accountId) {
        Instant until = lockedUntil.get(accountId);
        if (until != null && clock.instant().isBefore(until)) {
            throw new ApiException(HttpStatus.LOCKED, "Terlalu banyak percobaan. Coba lagi dalam 5 menit.");
        }
    }

    public synchronized void failed(String accountId, Factor factor) {
        Key key = new Key(accountId, factor);
        int count = failures.getOrDefault(key, 0) + 1;
        if (count >= MAX_FAILURES) {
            failures.remove(key);
            lockedUntil.put(accountId, clock.instant().plus(LOCK));
        } else {
            failures.put(key, count);
        }
    }

    public synchronized void succeeded(String accountId, Factor factor) {
        failures.remove(new Key(accountId, factor));
    }
}
