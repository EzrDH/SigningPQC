package id.pkl.signingpqc.server.account;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** Riwayat dokumen per akun di {@code history.json}: maksimal 200 entri, terbaru di depan. Tanpa isi dokumen. */
@Component
public class HistoryStore {

    static final int MAX_STORED = 200;
    static final int MAX_RETURNED = 50;

    /** @param activity "Ditandatangani", "Diverifikasi", atau "Diperpanjang" */
    public record Entry(String fileName, String activity, Instant time, String status, String path) {
    }

    private final AccountStore store;
    private final JsonMapper json;

    public HistoryStore(AccountStore store, JsonMapper json) {
        this.store = store;
        this.json = json;
    }

    public synchronized void add(String accountId, Entry entry) throws IOException {
        List<Entry> entries = new ArrayList<>(read(accountId));
        entries.addFirst(entry);
        if (entries.size() > MAX_STORED) {
            entries = new ArrayList<>(entries.subList(0, MAX_STORED));
        }
        Path file = file(accountId);
        Path temp = Files.createTempFile(file.getParent(), "history", ".tmp");
        json.writeValue(temp.toFile(), entries);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public synchronized List<Entry> latest(String accountId) {
        List<Entry> entries = read(accountId);
        return entries.subList(0, Math.min(MAX_RETURNED, entries.size()));
    }

    private List<Entry> read(String accountId) {
        Path file = file(accountId);
        if (!Files.exists(file)) {
            return List.of();
        }
        return Arrays.asList(json.readValue(file.toFile(), Entry[].class));
    }

    private Path file(String accountId) {
        return store.dir(accountId).resolve("history.json");
    }
}
