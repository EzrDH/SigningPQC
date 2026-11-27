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

import id.pkl.signingpqc.server.ActivityLog;

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
    private final ActivityLog activity;
    private final Actors actors;

    public AuthController(AccountService accounts, SessionService sessions, ActivityLog activity, Actors actors) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.activity = activity;
        this.actors = actors;
    }

    @PostMapping("/register/start")
    public AccountService.RegisterStart registerStart(@RequestBody RegisterRequest request) throws GeneralSecurityException {
        return accounts.registerStart(request.username(), request.email(), chars(request.password()),
                chars(request.passphrase()));
    }

    @PostMapping("/register/finish")
    public Map<String, String> registerFinish(@RequestBody FinishRequest request)
            throws GeneralSecurityException, IOException {
        AccountService.Session session = accounts.registerFinish(request.registrationId(), request.code());
        activity.add(session.account().username(), "Mendaftar", null, ActivityLog.SUCCESS, null);
        return session(session);
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody LoginRequest request) throws IOException, GeneralSecurityException {
        return session(activity.track(actors.byLogin(request.login()), "Masuk", null, null, null,
                () -> accounts.login(request.login(), chars(request.password()))));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String header) {
        String token = AuthInterceptor.bearer(header);
        String accountId = sessions.accountId(token).orElse(null);
        sessions.remove(token);
        if (accountId != null) {
            activity.add(actors.byId(accountId), "Keluar", null, ActivityLog.SUCCESS, null);
        }
        return ResponseEntity.noContent().build();
    }

    private static Map<String, String> session(AccountService.Session session) {
        return Map.of("token", session.token(), "username", session.account().username());
    }

    private static char[] chars(String value) {
        return value == null ? null : value.toCharArray();
    }
}
