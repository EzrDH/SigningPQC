package id.pkl.signingpqc.server;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import id.pkl.signingpqc.core.HttpTimestampTransport;
import id.pkl.signingpqc.core.SigningCredential;
import id.pkl.signingpqc.core.TimestampClient;
import id.pkl.signingpqc.core.TimestampTransport;
import id.pkl.signingpqc.pki.LocalTsa;
import id.pkl.signingpqc.pki.PkiFamily;

/**
 * TSA lokal RSA dan ML-DSA (dari PKI uji), dilayani lewat HTTP di /api/tsa/{rsa|mldsa}. Untuk tanda tangan,
 * TSA dipanggil di dalam proses, kecuali alamat TSA luar diisi di signingpqc.tsa.rsa-url / mldsa-url.
 */
@Service
public class TsaService {

    private final PkiService pki;
    private final Map<PkiFamily, String> externalUrls = new EnumMap<>(PkiFamily.class);
    private final Map<PkiFamily, LocalTsa> local = new EnumMap<>(PkiFamily.class);
    private final Map<PkiFamily, SigningCredential> localSource = new EnumMap<>(PkiFamily.class);

    public TsaService(PkiService pki, @Value("${signingpqc.tsa.rsa-url:}") String rsaUrl,
            @Value("${signingpqc.tsa.mldsa-url:}") String mldsaUrl) {
        this.pki = pki;
        externalUrls.put(PkiFamily.RSA, rsaUrl);
        externalUrls.put(PkiFamily.ML_DSA, mldsaUrl);
    }

    /** TSA lokal untuk keluarga algoritma; dibuat ulang bila PKI uji dibuat ulang. */
    public synchronized LocalTsa local(PkiFamily family) throws GeneralSecurityException {
        SigningCredential credential = pki.tsa(family);
        if (local.get(family) == null || localSource.get(family) != credential) {
            local.put(family, new LocalTsa(credential));
            localSource.put(family, credential);
        }
        return local.get(family);
    }

    public TimestampClient client(PkiFamily family) throws GeneralSecurityException {
        String url = externalUrls.get(family);
        TimestampTransport transport = url == null || url.isBlank() ? local(family)
                : new HttpTimestampTransport(URI.create(url));
        return new TimestampClient(transport);
    }

    public String description(PkiFamily family) {
        String url = externalUrls.get(family);
        return url == null || url.isBlank() ? "TSA lokal " + family.label() + " (di dalam proses)" : url;
    }

    public static PkiFamily family(String slug) {
        for (PkiFamily family : PkiFamily.values()) {
            if (family.slug().equals(slug)) {
                return family;
            }
        }
        throw ApiException.notFound("TSA '" + slug + "' tidak dikenal (pakai rsa atau mldsa)");
    }
}
