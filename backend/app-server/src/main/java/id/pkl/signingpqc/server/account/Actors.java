package id.pkl.signingpqc.server.account;

import org.springframework.stereotype.Component;

import id.pkl.signingpqc.server.ActivityLog;

/** Nama pelaku untuk log aktivitas: username akun, atau penanda untuk panggilan tanpa sesi. */
@Component
public class Actors {

    private final SessionService sessions;
    private final AccountStore store;

    public Actors(SessionService sessions, AccountStore store) {
        this.sessions = sessions;
        this.store = store;
    }

    public String byId(String accountId) {
        return accountId == null ? ActivityLog.UNKNOWN
                : store.byId(accountId).map(Account::username).orElse(ActivityLog.UNKNOWN);
    }

    /** Username pemilik token Bearer, atau "API pengembang" bila tanpa sesi yang sah. */
    public String byAuthorization(String header) {
        String token = AuthInterceptor.bearer(header);
        return sessions.accountId(token).flatMap(store::byId).map(Account::username).orElse(ActivityLog.DEVELOPER);
    }

    /** Username untuk nama atau email yang diketik saat masuk; teks yang diketik sendiri tidak pernah dicatat. */
    public String byLogin(String login) {
        return login == null ? ActivityLog.UNKNOWN
                : store.byLogin(login).map(Account::username).orElse(ActivityLog.UNKNOWN);
    }
}
