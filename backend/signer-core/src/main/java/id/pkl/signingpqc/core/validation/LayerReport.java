package id.pkl.signingpqc.core.validation;

import java.time.Instant;

import id.pkl.signingpqc.core.TimestampReport;

/**
 * Hasil pemeriksaan satu lapisan.
 *
 * @param revision           nomor revisi PDF (revisi 1 adalah dokumen asli)
 * @param pades              "B-B", "B-T", "LT", atau "LTA"
 * @param time               waktu lapisan dibuat: genTime, atau waktu signature timestamp untuk tanda tangan
 * @param provenAt           waktu yang dibuktikan lapisan di atasnya (waktu pemeriksaan untuk lapisan teratas)
 * @param status             status lapisan ini saja
 * @param signatureTimestamp signature timestamp (hanya untuk tanda tangan B-T)
 */
public record LayerReport(
        int revision,
        LayerKind kind,
        String pades,
        String algorithm,
        String subject,
        Instant time,
        Instant provenAt,
        ReasonCode code,
        OverallStatus status,
        String detail,
        TimestampReport signatureTimestamp) {
}
