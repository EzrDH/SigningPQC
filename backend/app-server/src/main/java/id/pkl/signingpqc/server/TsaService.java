package id.pkl.signingpqc.server;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.security.GeneralSecurityException;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import id.pkl.signingpqc.core.HttpTimestampTransport;
import id.pkl.signingpqc.core.SigningCredential;
import id.pkl.signingpqc.core.TimestampClient;
import id.pkl.signingpqc.core.TimestampTransport;
import id.pkl.signingpqc.core.ltv.TimestampProfile;
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

    /**
     * Klien TSA berdasarkan id ({@link PkiService#TSA_IDS}). Dengan {@code at}, TSA uji lokal memakai jam tetap
     * (waktu simulasi); TSA luar tidak bisa disimulasikan.
     */
    public TimestampClient client(String tsaId, Instant at) throws GeneralSecurityException {
        SigningCredential credential = pki.tsa(tsaId);
        PkiFamily family = familyOfTsa(tsaId);
        String url = externalUrls.get(family);
        boolean external = url != null && !url.isBlank() && credential == pki.tsa(family);
        if (at != null) {
            if (external) {
                throw ApiException.badRequest("Waktu simulasi hanya untuk TSA uji");
            }
            return new TimestampClient(new LocalTsa(credential, Clock.fixed(at, ZoneOffset.UTC)));
        }
        if (external) {
            return new TimestampClient(new HttpTimestampTransport(URI.create(url)));
        }
        return new TimestampClient(new LocalTsa(credential));
    }

    /** Profil document timestamp: SHA-512 untuk TSA ML-DSA, SHA-256 untuk TSA RSA. */
    public static TimestampProfile profile(String tsaId) {
        return familyOfTsa(tsaId) == PkiFamily.ML_DSA ? TimestampProfile.ML_DSA : TimestampProfile.RSA;
    }

    private static PkiFamily familyOfTsa(String tsaId) {
        return tsaId != null && tsaId.startsWith("rsa") ? PkiFamily.RSA : PkiFamily.ML_DSA;
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
