package id.pkl.signingpqc.server.account;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Daftar, masuk, dan keluar. Password dan passphrase tidak pernah dikembalikan atau dicatat. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record RegisterRequest(String username, String email, String password, String passphrase) {
        @Override
        public String toString() {
            return "RegisterRequest[" + username + "]";
        }
    }

    public record FinishRequest(String registrationId, String code) {
    }

    public record LoginRequest(String login, String password) {
        @Override
        public String toString() {
            return "LoginRequest[" + login + "]";
        }
    }

    private final AccountService accounts;
    private final SessionService sessions;

    public AuthController(AccountService accounts, SessionService sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    @PostMapping("/register/start")
    public AccountService.RegisterStart registerStart(@RequestBody RegisterRequest request) throws GeneralSecurityException {
        return accounts.registerStart(request.username(), request.email(), chars(request.password()),
                chars(request.passphrase()));
    }

    @PostMapping("/register/finish")
    public Map<String, String> registerFinish(@RequestBody FinishRequest request)
            throws GeneralSecurityException, IOException {
        return session(accounts.registerFinish(request.registrationId(), request.code()));
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody LoginRequest request) {
        return session(accounts.login(request.login(), chars(request.password())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String header) {
        sessions.remove(AuthInterceptor.bearer(header));
        return ResponseEntity.noContent().build();
    }

    private static Map<String, String> session(AccountService.Session session) {
        return Map.of("token", session.token(), "username", session.account().username());
    }

    private static char[] chars(String value) {
        return value == null ? null : value.toCharArray();
    }
}
