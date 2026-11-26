package id.pkl.signingpqc.server.account;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import id.pkl.signingpqc.server.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Memeriksa token Bearer untuk {@code /api/me/**} dan menyimpan id akun di atribut request {@code accountId}. */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ACCOUNT_ID = "accountId";

    private final SessionService sessions;
    private final AccountStore store;

    public AuthInterceptor(SessionService sessions, AccountStore store) {
        this.sessions = sessions;
        this.store = store;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }
        String token = bearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        String accountId = sessions.accountId(token).orElse(null);
        if (accountId == null || store.byId(accountId).isEmpty()) {
            sessions.remove(token);
            // RFC 6750: membedakan sesi berakhir dari 401 lain seperti "Konfirmasi tidak cocok".
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Sesi berakhir, silakan masuk lagi");
        }
        request.setAttribute(ACCOUNT_ID, accountId);
        return true;
    }

    static String bearer(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).strip();
        return token.isEmpty() ? null : token;
    }
}
