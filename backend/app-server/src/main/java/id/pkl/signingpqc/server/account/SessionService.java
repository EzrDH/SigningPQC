package id.pkl.signingpqc.server.account;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/** Sesi di memori: token acak 256-bit, menganggur maksimal 30 menit, mutlak 8 jam. */
@Component
public class SessionService {

    static final Duration IDLE = Duration.ofMinutes(30);
    static final Duration ABSOLUTE = Duration.ofHours(8);

    private record Session(String accountId, Instant created, Instant lastUsed) {
    }

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new HashMap<>();

    public SessionService(Clock clock) {
        this.clock = clock;
    }

    public synchronized String create(String accountId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        sessions.put(token, new Session(accountId, now, now));
        return token;
    }

    public synchronized Optional<String> accountId(String token) {
        Session session = token == null ? null : sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (now.isAfter(session.lastUsed().plus(IDLE)) || now.isAfter(session.created().plus(ABSOLUTE))) {
            sessions.remove(token);
            return Optional.empty();
        }
        sessions.put(token, new Session(session.accountId(), session.created(), now));
        return Optional.of(session.accountId());
    }

    public synchronized void remove(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }
}
