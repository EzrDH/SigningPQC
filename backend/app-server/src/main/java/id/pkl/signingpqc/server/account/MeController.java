package id.pkl.signingpqc.server.account;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint pengguna yang sudah masuk. */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final AccountStore store;

    public MeController(AccountStore store) {
        this.store = store;
    }

    @GetMapping
    public Map<String, String> me(@RequestAttribute(AuthInterceptor.ACCOUNT_ID) String accountId) {
        return Map.of("username", store.byId(accountId).orElseThrow().username());
    }
}
