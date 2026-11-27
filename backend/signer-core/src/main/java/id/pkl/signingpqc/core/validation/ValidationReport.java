package id.pkl.signingpqc.core.validation;

import java.time.Instant;
import java.util.List;

/**
 * Hasil validasi dokumen berlapis.
 *
 * @param hybridComplete        tanda tangan RSA dan ML-DSA keduanya ada dan tidak ada yang rusak (kebijakan AND)
 * @param protectionUntil       batas perlindungan lapisan teratas: minimum masa berlaku rantai TSA dan batas aman
 *                              algoritmanya
 * @param renewalRecommended    sisa perlindungan kurang dari ambang peringatan
 * @param warnings              kode yang tidak membatalkan, misalnya dicabut setelah ditandatangani
 */
public record ValidationReport(
        OverallStatus status,
        boolean hybridComplete,
        Instant validationTime,
        List<LayerReport> layers,
        Instant protectionUntil,
        String protectionLimitReason,
        boolean renewalRecommended,
        List<ReasonCode> warnings) {

    public ValidationReport {
        layers = List.copyOf(layers);
        warnings = List.copyOf(warnings);
    }
}
