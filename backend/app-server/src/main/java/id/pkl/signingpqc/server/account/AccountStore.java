package id.pkl.signingpqc.server.account;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** Menyimpan akun di {@code <data-dir>/accounts/<id>/account.json}. */
@Component
public class AccountStore {

    private static final String FILE = "account.json";
    private final Path root;
    private final JsonMapper json;

    public AccountStore(@Value("${signingpqc.data-dir}") Path dataDir, JsonMapper json) {
        this.root = dataDir.resolve("accounts");
        this.json = json;
    }

    public Path dir(String id) {
        if (id == null || !id.matches("[a-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Id akun tidak sah");
        }
        return root.resolve(id);
    }

    public synchronized Optional<Account> byId(String id) {
        Path file = dir(id).resolve(FILE);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return Optional.of(json.readValue(file.toFile(), Account.class));
    }

    /** Cari berdasarkan username atau email, tidak peka huruf besar. */
    public synchronized Optional<Account> byLogin(String usernameOrEmail) {
        if (usernameOrEmail == null) {
            return Optional.empty();
        }
        String key = usernameOrEmail.strip().toLowerCase(Locale.ROOT);
        return all().stream()
                .filter(a -> a.username().equalsIgnoreCase(key) || a.email().equalsIgnoreCase(key))
                .findFirst();
    }

    public synchronized boolean usernameTaken(String username) {
        return username != null && all().stream().anyMatch(a -> a.username().equalsIgnoreCase(username.strip()));
    }

    public synchronized boolean emailTaken(String email) {
        return email != null && all().stream().anyMatch(a -> a.email().equalsIgnoreCase(email.strip()));
    }

    public synchronized void save(Account account) throws IOException {
        Path folder = dir(account.id());
        Files.createDirectories(folder);
        Path temp = Files.createTempFile(folder, "account", ".tmp");
        json.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), account);
        Files.move(temp, folder.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private List<Account> all() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var folders = Files.list(root)) {
            return folders.map(f -> f.resolve(FILE)).filter(Files::exists)
                    .map(f -> json.readValue(f.toFile(), Account.class)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
