package id.pkl.signingpqc.core.validation;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;

/**
 * Hasil validasi rantai pada satu waktu.
 *
 * @param path rantai dari sertifikat daun sampai root (root ikut, bila ketemu)
 * @param chainNotAfter masa berlaku terpendek di rantai; null bila rantai tidak tersusun
 */
public record ChainResult(ReasonCode code, List<X509Certificate> path, Instant chainNotAfter, String detail) {

    public ChainResult {
        path = path == null ? List.of() : List.copyOf(path);
    }

    /** Sah, termasuk bila sertifikat baru dicabut setelah waktu yang dibuktikan. */
    public boolean valid() {
        return code == ReasonCode.OK || code == ReasonCode.DICABUT_SETELAH_DITANDATANGANI;
    }
}
