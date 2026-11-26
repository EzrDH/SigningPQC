package id.pkl.signingpqc.server.account;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import id.pkl.signingpqc.server.ApiException;

/** Lima kegagalan berturut-turut (password, passphrase, atau TOTP) mengunci akun selama 5 menit. */
@Component
public class LoginGuard {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(5);

    private record State(int failures, Instant lockedUntil) {
    }

    private final Clock clock;
    private final Map<String, State> states = new HashMap<>();

    public LoginGuard(Clock clock) {
        this.clock = clock;
    }

    public synchronized void check(String accountId) {
        State state = states.get(accountId);
        if (state != null && state.lockedUntil() != null && clock.instant().isBefore(state.lockedUntil())) {
            throw new ApiException(HttpStatus.LOCKED, "Terlalu banyak percobaan. Coba lagi dalam 5 menit.");
        }
    }

    public synchronized void failed(String accountId) {
        State state = states.getOrDefault(accountId, new State(0, null));
        int failures = state.failures() + 1;
        states.put(accountId, failures >= MAX_FAILURES ? new State(0, clock.instant().plus(LOCK)) : new State(failures, null));
    }

    public synchronized void succeeded(String accountId) {
        states.remove(accountId);
    }
}
