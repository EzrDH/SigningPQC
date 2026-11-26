package id.pkl.signingpqc.server.account;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import id.pkl.signingpqc.server.ApiException;

class LoginGuardTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-26T02:00:00Z"));

    @Test
    void kunciSetelahLimaGagalSelamaLimaMenit() {
        LoginGuard guard = new LoginGuard(clock);
        for (int i = 0; i < 4; i++) {
            guard.failed("a1");
        }
        assertDoesNotThrow(() -> guard.check("a1"));
        guard.failed("a1");
        ApiException e = assertThrows(ApiException.class, () -> guard.check("a1"));
        assertEquals(HttpStatus.LOCKED, e.status());
        assertEquals("Terlalu banyak percobaan. Coba lagi dalam 5 menit.", e.getMessage());
        assertDoesNotThrow(() -> guard.check("a2"));
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertDoesNotThrow(() -> guard.check("a1"));
    }

    @Test
    void berhasilMengosongkanHitungan() {
        LoginGuard guard = new LoginGuard(clock);
        for (int i = 0; i < 4; i++) {
            guard.failed("a1");
        }
        guard.succeeded("a1");
        guard.failed("a1");
        assertDoesNotThrow(() -> guard.check("a1"));
    }

    @Test
    void sesiKedaluwarsaSaatMenganggurDanBatasMutlak() {
        SessionService sessions = new SessionService(clock);
        String idle = sessions.create("a1");
        assertNotEquals(idle, sessions.create("a1"));
        assertTrue(idle.matches("[A-Za-z0-9_-]{43}"), idle);
        clock.advance(Duration.ofMinutes(29));
        assertEquals("a1", sessions.accountId(idle).orElseThrow());
        clock.advance(Duration.ofMinutes(31));
        assertTrue(sessions.accountId(idle).isEmpty());

        String active = sessions.create("a1");
        for (int i = 0; i < 17; i++) {
            clock.advance(Duration.ofMinutes(29));
            sessions.accountId(active);
        }
        assertTrue(sessions.accountId(active).isEmpty(), "batas mutlak 8 jam");

        String gone = sessions.create("a1");
        sessions.remove(gone);
        assertTrue(sessions.accountId(gone).isEmpty());
    }
}
