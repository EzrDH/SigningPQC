package id.pkl.signingpqc.core.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.tsp.TimeStampToken;

import id.pkl.signingpqc.core.PdfSignatureVerifier;
import id.pkl.signingpqc.core.SignatureReport;
import id.pkl.signingpqc.core.SignatureTimestamps;
import id.pkl.signingpqc.core.TimestampClient;
import id.pkl.signingpqc.core.TimestampReport;
import id.pkl.signingpqc.core.ltv.CmsCertificates;
import id.pkl.signingpqc.core.ltv.DssReader;
import id.pkl.signingpqc.core.ltv.ValidationData;

/**
 * Validator dokumen berlapis (KF-19 s.d. KF-29).
 *
 * <p>Revisi PDF diurutkan: tanda tangan (lapisan 1-2), DSS (lapisan 3), dan document timestamp (lapisan 4 dst.).
 * Lapisan teratas diperiksa pada waktu pemeriksaan. Setiap lapisan di bawahnya diperiksa pada genTime document
 * timestamp yang menutupnya ({@code provenAt}). Rantai sertifikat dan CRL diambil dari DSS dan sertifikat yang
 * tertanam di CMS/token, tidak dari internet.
 *
 * <p>Lapisan teratas belum punya data pencabutan di DSS (data itu baru disimpan saat pembaruan berikutnya). Bila
 * CRL-nya tidak ada, status pencabutan dicatat sebagai peringatan, bukan kegagalan.
 */
public final class DocumentValidator {

    private static final byte[] EOF_MARKER = "%%EOF".getBytes(StandardCharsets.US_ASCII);

    private final ChainValidator chains;
    private final AlgorithmPolicy policy;
    private final Duration warningThreshold;
    private final PdfSignatureVerifier verifier = new PdfSignatureVerifier();

    public DocumentValidator(Set<X509Certificate> trustAnchors, AlgorithmPolicy policy, Duration warningThreshold) {
        this.chains = new ChainValidator(trustAnchors);
        this.policy = policy;
        this.warningThreshold = warningThreshold;
    }

    /** Satu revisi PDF. {@code signature} null untuk revisi tanpa tanda tangan (DSS atau perubahan lain). */
    private record Revision(int end, PDSignature signature, SignatureReport report, boolean dss, boolean foreign) {
    }

    /** Objek yang ditulis ulang oleh satu revisi: jumlahnya, yang bukan DSS/timestamp, dan apakah ada /DSS. */
    private record Diff(int changed, List<String> foreign, boolean hasDss) {
    }

    /** Kunci yang boleh berubah pada objek lama: penambahan DSS, field tanda tangan, dan widget-nya. */
    private static final Set<COSName> LTV_KEYS = Set.of(COSName.getPDFName("DSS"), COSName.ACRO_FORM, COSName.ANNOTS,
            COSName.FIELDS, COSName.SIG_FLAGS);

    /** Hasil sementara pemeriksaan satu lapisan bertanda tangan. */
    private static final class Eval {
        ReasonCode code = ReasonCode.OK;
        OverallStatus status = OverallStatus.SAH;
        String detail = ReasonCode.OK.message();
        String algorithm = "-";
        String subject = "-";
        String pades;
        Instant time;
        TimestampReport signatureTimestamp;
        Instant protectionUntil;
        String protectionReason;

        Eval fail(ReasonCode reason, OverallStatus outcome, String text) {
            code = reason;
            status = outcome;
            detail = text == null ? reason.message() : text;
            return this;
        }
    }

    public ValidationReport validate(byte[] pdf, Instant validationTime) throws IOException {
        return validate(pdf, validationTime, ValidationData.EMPTY);
    }

    /**
     * @param local data validasi lokal tambahan (misalnya CRL terkini dari CA uji di mesin ini), dipakai bersama DSS.
     *              Bukan data dari internet.
     */
    public ValidationReport validate(byte[] pdf, Instant validationTime, ValidationData local) throws IOException {
        List<PDSignature> signatures;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            signatures = new ArrayList<>(document.getSignatureDictionaries());
        }
        List<SignatureReport> reports = verifier.verify(pdf);
        List<Revision> revisions = revisions(pdf, signatures, reports);
        ValidationData dss = DssReader.readAll(pdf).merge(local);

        List<Revision> signed = revisions.stream().filter(r -> r.signature() != null).toList();
        int lastSignedEnd = signed.isEmpty() ? -1 : signed.get(signed.size() - 1).end();

        // genTime setiap document timestamp, untuk menentukan provenAt lapisan di bawahnya.
        List<Instant> times = new ArrayList<>();
        for (Revision revision : signed) {
            Instant time = null;
            if (isDocTimestamp(revision.signature())) {
                time = docTimestampTime(revision.signature(), pdf);
            }
            times.add(time);
        }

        Set<ReasonCode> warnings = new LinkedHashSet<>();
        if (trailingChange(pdf, signatures)) {
            warnings.add(ReasonCode.PERUBAHAN_SETELAH_LAPISAN_TERAKHIR);
        }
        List<LayerReport> layers = new ArrayList<>();
        Instant protectionUntil = null;
        String protectionReason = null;
        int signedIndex = 0;
        for (Revision revision : revisions) {
            int number = revisions.indexOf(revision) + 2;
            if (revision.signature() == null) {
                if (revision.foreign()) {
                    warnings.add(revision.end() > lastSignedEnd ? ReasonCode.PERUBAHAN_SETELAH_LAPISAN_TERAKHIR
                            : ReasonCode.PERUBAHAN_ISI_TIDAK_DITANDATANGANI);
                } else if (revision.dss()) {
                    layers.add(new LayerReport(number, LayerKind.DATA_VALIDASI, "LT", "-", "-", null, null,
                            ReasonCode.OK, OverallStatus.SAH, "Rantai sertifikat dan CRL disimpan di DSS", null));
                }
                continue;
            }
            if (revision.foreign()) {
                warnings.add(ReasonCode.PERUBAHAN_ISI_TIDAK_DITANDATANGANI);
            }
            Instant provenAt = validationTime;
            boolean latest = true;
            for (int j = signedIndex + 1; j < signed.size(); j++) {
                if (times.get(j) != null) {
                    provenAt = times.get(j);
                    latest = false;
                    break;
                }
            }
            Eval eval = isDocTimestamp(revision.signature())
                    ? docTimestamp(revision.signature(), pdf, provenAt, latest, dss, warnings)
                    : signature(revision.signature(), revision.report(), pdf, provenAt, latest, dss, warnings);
            LayerKind kind = isDocTimestamp(revision.signature()) ? LayerKind.DOCUMENT_TIMESTAMP : LayerKind.TANDA_TANGAN;
            layers.add(new LayerReport(number, kind, eval.pades, eval.algorithm, eval.subject, eval.time, provenAt,
                    eval.code, eval.status, eval.detail, eval.signatureTimestamp));
            if (latest && eval.protectionUntil != null
                    && (protectionUntil == null || eval.protectionUntil.isBefore(protectionUntil))) {
                protectionUntil = eval.protectionUntil;
                protectionReason = eval.protectionReason;
            }
            signedIndex++;
        }

        boolean rsa = false;
        boolean mlDsa = false;
        for (LayerReport layer : layers) {
            if (layer.kind() == LayerKind.TANDA_TANGAN && layer.status() != OverallStatus.TIDAK_SAH) {
                String family = AlgorithmPolicy.familyOf(layer.algorithm());
                rsa |= "RSA".equals(family);
                mlDsa |= "ML-DSA".equals(family);
            }
        }
        boolean hybridComplete = rsa && mlDsa;

        OverallStatus status;
        if (layers.stream().anyMatch(l -> l.status() == OverallStatus.TIDAK_SAH)) {
            status = OverallStatus.TIDAK_SAH;
        } else if (layers.stream().anyMatch(l -> l.status() == OverallStatus.TIDAK_DAPAT_DIBUKTIKAN)) {
            status = OverallStatus.TIDAK_DAPAT_DIBUKTIKAN;
        } else {
            status = OverallStatus.SAH;
        }
        // KF-23: "sah hybrid" hanya untuk RSA + ML-DSA (hybridComplete). Tanda tangan yang sah tanpa pasangannya tetap
        // sah, dengan peringatan bahwa dokumen tidak memenuhi kebijakan hybrid (T-017, revisi 27 Nov 2026).
        if (!hybridComplete) {
            warnings.add(ReasonCode.TANDA_TANGAN_HYBRID_TIDAK_LENGKAP);
        }
        if (status == OverallStatus.SAH && (warnings.contains(ReasonCode.PERUBAHAN_SETELAH_LAPISAN_TERAKHIR)
                || warnings.contains(ReasonCode.PERUBAHAN_ISI_TIDAK_DITANDATANGANI))) {
            status = OverallStatus.TIDAK_DAPAT_DIBUKTIKAN;
        }
        boolean renewal = protectionUntil != null
                && Duration.between(validationTime, protectionUntil).compareTo(warningThreshold) < 0;
        return new ValidationReport(status, hybridComplete, validationTime, layers, protectionUntil,
                protectionReason, renewal, new ArrayList<>(warnings));
    }

    // ---------------------------------------------------------------------------------------------
    // Lapisan tanda tangan (1-2)

    private Eval signature(PDSignature signature, SignatureReport report, byte[] pdf, Instant provenAt,
            boolean latest, ValidationData dss, Set<ReasonCode> warnings) {
        Eval eval = new Eval();
        TimestampReport ts = report.signatureTimestamp();
        eval.algorithm = report.signatureAlgorithm();
        eval.subject = report.signerSubject();
        eval.signatureTimestamp = ts;
        eval.pades = ts != null && ts.valid() ? "B-T" : "B-B";
        eval.time = ts != null ? ts.time() : report.claimedSigningTime();

        if (!byteRangeValid(signature, pdf)) {
            return eval.fail(ReasonCode.DOKUMEN_BERUBAH, OverallStatus.TIDAK_SAH, "ByteRange tidak sah");
        }
        if (!report.cryptographicallyValid()) {
            ReasonCode code = report.message().contains("message-digest") ? ReasonCode.DOKUMEN_BERUBAH
                    : ReasonCode.TANDA_TANGAN_TIDAK_COCOK;
            return eval.fail(code, OverallStatus.TIDAK_SAH, report.message());
        }
        if (!report.signingCertificateMatches()) {
            return eval.fail(ReasonCode.TANDA_TANGAN_TIDAK_COCOK, OverallStatus.TIDAK_SAH, report.message());
        }
        if (ts != null && !ts.valid()) {
            return eval.fail(ReasonCode.TANDA_TANGAN_TIDAK_COCOK, OverallStatus.TIDAK_SAH,
                    "Signature timestamp tidak sah: " + ts.message());
        }
        if (eval.time != null && provenAt.isBefore(eval.time)) {
            return eval.fail(ReasonCode.WAKTU_PEMERIKSAAN_TIDAK_SAH, OverallStatus.TIDAK_DAPAT_DIBUKTIKAN,
                    "Waktu pemeriksaan " + provenAt + " lebih awal dari " + eval.time);
        }
        if (dss.isEmpty() && !latest) {
            return eval.fail(ReasonCode.DATA_VALIDASI_TIDAK_ADA, OverallStatus.TIDAK_DAPAT_DIBUKTIKAN, null);
        }

        X509Certificate signerCertificate;
        List<X509Certificate> embedded;
        TimeStampToken token;
        try {
            byte[] der = PdfSignatureVerifier.firstDerObject(signature.getContents(pdf));
            CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(signature.getSignedContent(pdf)), der);
            SignerInformation signer = cms.getSignerInfos().getSigners().iterator().next();
            signerCertificate = CmsCertificates.signer(cms, signer.getSID());
            embedded = CmsCertificates.all(cms);
            token = SignatureTimestamps.tokenOf(signer);
        } catch (Exception e) {
            return eval.fail(ReasonCode.TANDA_TANGAN_TIDAK_COCOK, OverallStatus.TIDAK_SAH, e.getMessage());
        }

        Instant signerTime = ts != null ? ts.time() : provenAt;
        ChainResult signerChain = chains.validate(signerCertificate, withEmbedded(dss, embedded), signerTime);
        if (classify(eval, signerChain, latest, ts != null, warnings)) {
            return eval;
        }
        Optional<Instant> signerUnsafe = policy.unsafeFromName(eval.algorithm);
        if (signerUnsafe.isPresent() && !signerTime.isBefore(signerUnsafe.get())) {
            return eval.fail(ReasonCode.ALGORITMA_TIDAK_AMAN_PADA_WAKTU_PEMERIKSAAN,
                    OverallStatus.TIDAK_DAPAT_DIBUKTIKAN, AlgorithmPolicy.familyOf(eval.algorithm)
                            + " tidak aman sejak " + signerUnsafe.get() + ", lapisan dibuktikan pada " + signerTime);
        }
        if (token == null) {
            protection(eval, signerChain.chainNotAfter(), signerUnsafe, eval.algorithm, eval.subject);
            return eval;
        }
        return checkTimestampProof(eval, token, ts.time(), ts.signatureAlgorithm(), provenAt, latest, dss, warnings);
    }

    // ---------------------------------------------------------------------------------------------
    // Lapisan document timestamp (4 dst.)

    private Eval docTimestamp(PDSignature signature, byte[] pdf, Instant provenAt, boolean latest, ValidationData dss,
            Set<ReasonCode> warnings) {
        Eval eval = new Eval();
        eval.pades = "LTA";
        TimeStampToken token;
        TimestampReport report;
        try {
            byte[] der = PdfSignatureVerifier.firstDerObject(signature.getContents(pdf));
            token = new TimeStampToken(new CMSSignedData(der));
            report = SignatureTimestamps.inspect(token, signature.getSignedContent(pdf));
        } catch (Exception e) {
            return eval.fail(ReasonCode.TANDA_TANGAN_TIDAK_COCOK, OverallStatus.TIDAK_SAH,
                    "Token document timestamp tidak dapat dibaca: " + e.getMessage());
        }
        eval.time = report.time();
        eval.algorithm = report.signatureAlgorithm();
        eval.subject = report.tsaSubject();
        if (!byteRangeValid(signature, pdf) || !report.imprintMatches()) {
            return eval.fail(ReasonCode.DOKUMEN_BERUBAH, OverallStatus.TIDAK_SAH,
                    "Message imprint tidak cocok dengan isi dokumen");
        }
        if (!report.tokenValid()) {
            return eval.fail(ReasonCode.TANDA_TANGAN_TIDAK_COCOK, OverallStatus.TIDAK_SAH, report.message());
        }
        if (provenAt.isBefore(eval.time)) {
            return eval.fail(ReasonCode.WAKTU_PEMERIKSAAN_TIDAK_SAH, OverallStatus.TIDAK_DAPAT_DIBUKTIKAN,
                    "Waktu pemeriksaan " + provenAt + " lebih awal dari " + eval.time);
        }
        if (dss.isEmpty() && !latest) {
            return eval.fail(ReasonCode.DATA_VALIDASI_TIDAK_ADA, OverallStatus.TIDAK_DAPAT_DIBUKTIKAN, null);
        }
        return checkTimestampProof(eval, token, eval.time, eval.algorithm, provenAt, latest, dss, warnings);
    }

    /** Rantai TSA sah pada genTime, lalu masih berlaku dan algoritmanya masih aman pada {@code provenAt}. */
    private Eval checkTimestampProof(Eval eval, TimeStampToken token, Instant genTime, String algorithm,
            Instant provenAt, boolean latest, ValidationData dss, Set<ReasonCode> warnings) {
        X509Certificate tsa;
        List<X509Certificate> embedded;
        try {
            tsa = TimestampClient.verify(token);
            embedded = CmsCertificates.all(token.toCMSSignedData());
        } catch (GeneralSecurityException e) {
            return eval.fail(ReasonCode.TANDA_TANGAN_TIDAK_COCOK, OverallStatus.TIDAK_SAH, e.getMessage());
        }
        ChainResult chain = chains.validate(tsa, withEmbedded(dss, embedded), genTime);
        if (classify(eval, chain, latest, true, warnings)) {
            return eval;
        }
        String subject = tsa.getSubjectX500Principal().getName();
        if (chain.revokedAt() != null && !chain.revokedAt().isAfter(provenAt)) {
            // Kunci TSA yang dicabut sebelum token dilindungi lapisan berikutnya bisa dipakai membuat token mundur.
            return eval.fail(ReasonCode.SERTIFIKAT_DICABUT, OverallStatus.TIDAK_SAH, "Rantai TSA " + subject
                    + " dicabut pada " + chain.revokedAt() + ", sebelum dilindungi lapisan berikutnya (" + provenAt + ")");
        }
        Optional<Instant> unsafe = policy.unsafeFromName(algorithm);
        protection(eval, chain.chainNotAfter(), unsafe, algorithm, subject);
        if (!provenAt.isBefore(chain.chainNotAfter())) {
            return eval.fail(ReasonCode.TSA_KEDALUWARSA_TANPA_PEMBARUAN, OverallStatus.TIDAK_DAPAT_DIBUKTIKAN,
                    "Rantai TSA " + subject + " berlaku sampai " + chain.chainNotAfter() + ", dibutuhkan sampai "
                            + provenAt);
        }
        if (unsafe.isPresent() && !provenAt.isBefore(unsafe.get())) {
            return eval.fail(ReasonCode.ALGORITMA_TIDAK_AMAN_PADA_WAKTU_PEMERIKSAAN,
                    OverallStatus.TIDAK_DAPAT_DIBUKTIKAN, AlgorithmPolicy.familyOf(algorithm) + " tidak aman sejak "
                            + unsafe.get() + ", dibutuhkan sampai " + provenAt);
        }
        return eval;
    }

    /**
     * Menerjemahkan hasil rantai ke status lapisan. Mengembalikan true bila lapisan gagal.
     *
     * @param strictValidity sertifikat yang tidak berlaku pada waktunya sendiri berarti tidak sah (bukan sekadar
     *                       tidak dapat dibuktikan)
     */
    private static boolean classify(Eval eval, ChainResult chain, boolean latest, boolean strictValidity,
            Set<ReasonCode> warnings) {
        switch (chain.code()) {
            case OK:
                return false;
            case DICABUT_SETELAH_DITANDATANGANI:
                warnings.add(chain.code());
                return false;
            case STATUS_PENCABUTAN_TIDAK_DIKETAHUI:
                if (latest) {
                    warnings.add(chain.code());
                    return false;
                }
                eval.fail(chain.code(), OverallStatus.TIDAK_DAPAT_DIBUKTIKAN, chain.detail());
                return true;
            case DATA_VALIDASI_TIDAK_ADA:
            case ROOT_TIDAK_DIPERCAYA:
                // ETSI EN 319 102-1: rantai tanpa trust anchor berstatus INDETERMINATE, bukan gagal (T-017).
                eval.fail(chain.code(), OverallStatus.TIDAK_DAPAT_DIBUKTIKAN, chain.detail());
                return true;
            case SERTIFIKAT_TIDAK_BERLAKU:
                eval.fail(chain.code(), strictValidity ? OverallStatus.TIDAK_SAH : OverallStatus.TIDAK_DAPAT_DIBUKTIKAN,
                        chain.detail());
                return true;
            default:
                eval.fail(chain.code(), OverallStatus.TIDAK_SAH, chain.detail());
                return true;
        }
    }

    private static void protection(Eval eval, Instant chainNotAfter, Optional<Instant> unsafe, String algorithm,
            String subject) {
        if (chainNotAfter == null) {
            return;
        }
        if (unsafe.isPresent() && unsafe.get().isBefore(chainNotAfter)) {
            eval.protectionUntil = unsafe.get();
            eval.protectionReason = "Batas aman algoritma " + AlgorithmPolicy.familyOf(algorithm);
        } else {
            eval.protectionUntil = chainNotAfter;
            eval.protectionReason = "Masa berlaku rantai sertifikat " + subject;
        }
    }

    private static ValidationData withEmbedded(ValidationData dss, List<X509Certificate> embedded) {
        return new ValidationData(embedded, List.of()).merge(dss);
    }

    // ---------------------------------------------------------------------------------------------
    // Revisi

    private static boolean isDocTimestamp(PDSignature signature) {
        return DssReader.ETSI_RFC3161.equals(signature.getSubFilter());
    }

    private static Instant docTimestampTime(PDSignature signature, byte[] pdf) {
        try {
            byte[] der = PdfSignatureVerifier.firstDerObject(signature.getContents(pdf));
            return new TimeStampToken(new CMSSignedData(der)).getTimeStampInfo().getGenTime().toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean byteRangeValid(PDSignature signature, byte[] pdf) {
        int[] range = signature.getByteRange();
        return range != null && range.length == 4 && range[0] == 0 && range[1] > 0 && range[2] > range[1]
                && range[3] >= 0 && (long) range[2] + range[3] <= pdf.length;
    }

    private static int end(PDSignature signature, byte[] pdf) {
        int[] range = signature.getByteRange();
        if (range == null || range.length != 4) {
            return pdf.length;
        }
        return (int) Math.min((long) range[2] + range[3], pdf.length);
    }

    /**
     * Mengurutkan revisi setelah tanda tangan pertama. Revisi tanpa tanda tangan dikenali dari penanda %%EOF.
     * Untuk revisi tanpa tanda tangan dan revisi document timestamp, objek yang ditulis ulang dibandingkan dengan
     * revisi sebelumnya: yang boleh hanya DSS dan field/widget tanda tangan. Selain itu dicatat sebagai perubahan isi.
     */
    private static List<Revision> revisions(byte[] pdf, List<PDSignature> signatures, List<SignatureReport> reports) {
        java.util.Map<Integer, Integer> byEnd = new java.util.HashMap<>();
        for (int i = 0; i < signatures.size(); i++) {
            byEnd.put(end(signatures.get(i), pdf), i);
        }
        List<Revision> result = new ArrayList<>();
        if (byEnd.isEmpty()) {
            return result;
        }
        TreeSet<Integer> signedEnds = new TreeSet<>(byEnd.keySet());
        int firstSigned = signedEnds.first();
        TreeSet<Integer> ends = new TreeSet<>(signedEnds);
        for (int end : eofEnds(pdf)) {
            Integer near = signedEnds.ceiling(end - 2);
            if (end > firstSigned && (near == null || near > end + 2)) {
                ends.add(end);
            }
        }
        Integer previous = null;
        for (int end : ends) {
            Integer index = byEnd.get(end);
            if (index != null) {
                PDSignature signature = signatures.get(index);
                SignatureReport report = index < reports.size() ? reports.get(index) : null;
                boolean foreign = previous != null && isDocTimestamp(signature)
                        && !changes(pdf, previous, end).foreign().isEmpty();
                result.add(new Revision(end, signature, report, false, foreign));
            } else {
                Diff diff = changes(pdf, previous, end);
                boolean foreign = !diff.foreign().isEmpty();
                result.add(new Revision(end, null, null, !foreign && diff.changed() > 0 && diff.hasDss(), foreign));
            }
            previous = end;
        }
        return result;
    }

    /** Ada byte selain spasi setelah revisi terakhir yang dikenal (pembaruan tanpa %%EOF). */
    private static boolean trailingChange(byte[] pdf, List<PDSignature> signatures) {
        int last = 0;
        for (int end : eofEnds(pdf)) {
            last = Math.max(last, end);
        }
        for (PDSignature signature : signatures) {
            last = Math.max(last, end(signature, pdf));
        }
        for (int i = last; i < pdf.length; i++) {
            byte b = pdf[i];
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n' && b != '\f' && b != 0) {
                return true;
            }
        }
        return false;
    }

    /** Membandingkan objek yang ditulis revisi [{@code from}, {@code to}) dengan keadaan sebelum revisi itu. */
    private static Diff changes(byte[] pdf, int from, int to) {
        try (PDDocument before = Loader.loadPDF(Arrays.copyOf(pdf, from));
                PDDocument after = Loader.loadPDF(Arrays.copyOf(pdf, to))) {
            COSDocument a = before.getDocument();
            COSDocument b = after.getDocument();
            java.util.Map<COSObjectKey, Long> xa = a.getXrefTable();
            java.util.Map<COSObjectKey, Long> xb = b.getXrefTable();
            COSDictionary catalog = after.getDocumentCatalog().getCOSObject();
            COSBase dssItem = catalog.getItem(COSName.getPDFName("DSS"));
            Set<COSObjectKey> fromDss = new HashSet<>();
            reach(dssItem, fromDss, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 0);
            Set<COSObjectKey> fromSignatures = new HashSet<>(fromDss);
            reachSignatureFields(catalog, fromSignatures);

            int changed = 0;
            List<String> foreign = new ArrayList<>();
            for (java.util.Map.Entry<COSObjectKey, Long> entry : xb.entrySet()) {
                COSObjectKey key = entry.getKey();
                long offset = entry.getValue();
                boolean written;
                if (offset >= 0) {
                    written = offset >= from;
                } else {
                    Long container = xb.get(new COSObjectKey(-offset, 0));
                    written = container != null && container >= from;
                }
                if (!written) {
                    continue;
                }
                COSObject holder = b.getObjectFromPool(key);
                COSBase now = holder == null ? null : holder.getObject();
                if (now instanceof COSDictionary dict && (COSName.XREF.equals(dict.getCOSName(COSName.TYPE))
                        || COSName.OBJ_STM.equals(dict.getCOSName(COSName.TYPE)))) {
                    continue;
                }
                changed++;
                if (!xa.containsKey(key)) {
                    if (!fromSignatures.contains(key)) {
                        foreign.add("objek baru " + key + " " + describe(now));
                    }
                    continue;
                }
                if (fromDss.contains(key)) {
                    continue;
                }
                COSObject oldHolder = a.getObjectFromPool(key);
                COSBase old = oldHolder == null ? null : oldHolder.getObject();
                if (!sameExceptLtv(old, now, fromSignatures)) {
                    foreign.add("objek " + key + " " + describe(now) + " diubah");
                }
            }
            return new Diff(changed, foreign, dssItem != null);
        } catch (IOException | RuntimeException e) {
            return new Diff(0, List.of("revisi tidak dapat dibaca: " + e.getMessage()), false);
        }
    }

    /** Objek lama yang ditulis ulang hanya boleh berbeda pada kunci LTV, atau berupa array yang hanya ditambah. */
    private static boolean sameExceptLtv(COSBase old, COSBase now, Set<COSObjectKey> allowedNew) {
        if (old instanceof COSStream || now instanceof COSStream) {
            return false;
        }
        if (old instanceof COSDictionary oldDict && now instanceof COSDictionary newDict) {
            return canon(oldDict, LTV_KEYS, 0).equals(canon(newDict, LTV_KEYS, 0));
        }
        if (old instanceof COSArray oldArray && now instanceof COSArray newArray) {
            List<String> remaining = new ArrayList<>();
            for (int i = 0; i < oldArray.size(); i++) {
                remaining.add(canon(oldArray.get(i), Set.of(), 0));
            }
            for (int i = 0; i < newArray.size(); i++) {
                COSBase element = newArray.get(i);
                if (remaining.remove(canon(element, Set.of(), 0))) {
                    continue;
                }
                if (!(element instanceof COSObject ref) || ref.getKey() == null || !allowedNew.contains(ref.getKey())) {
                    return false;
                }
            }
            return remaining.isEmpty();
        }
        return false;
    }

    /** Bentuk teks yang stabil untuk perbandingan; referensi dibandingkan dari nomor objeknya. */
    private static String canon(COSBase base, Set<COSName> ignore, int depth) {
        if (base == null) {
            return "null";
        }
        if (base instanceof COSObject ref) {
            return ref.getKey() == null ? "R?" + ref.getObjectNumber() : "R" + ref.getKey();
        }
        if (depth > 8) {
            return "...";
        }
        if (base instanceof COSDictionary dict) {
            java.util.TreeMap<String, String> entries = new java.util.TreeMap<>();
            for (COSName name : dict.keySet()) {
                if (!ignore.contains(name)) {
                    entries.put(name.getName(), canon(dict.getItem(name), Set.of(), depth + 1));
                }
            }
            return entries.toString();
        }
        if (base instanceof COSArray array) {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                items.add(canon(array.get(i), Set.of(), depth + 1));
            }
            return items.toString();
        }
        if (base instanceof COSString string) {
            return "s:" + java.util.HexFormat.of().formatHex(string.getBytes());
        }
        return base.toString();
    }

    /** Kunci objek yang terjangkau dari field tanda tangan (termasuk /V, widget, dan tampilannya). */
    private static void reachSignatureFields(COSDictionary catalog, Set<COSObjectKey> keys) {
        COSBase form = catalog.getDictionaryObject(COSName.ACRO_FORM);
        if (!(form instanceof COSDictionary acroForm)) {
            return;
        }
        COSBase fields = acroForm.getDictionaryObject(COSName.FIELDS);
        if (!(fields instanceof COSArray array)) {
            return;
        }
        Set<COSBase> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int i = 0; i < array.size(); i++) {
            COSBase item = array.get(i);
            COSBase field = item instanceof COSObject ref ? ref.getObject() : item;
            if (field instanceof COSDictionary dict && COSName.SIG.equals(dict.getCOSName(COSName.FT))) {
                reach(item, keys, seen, 0);
            }
        }
    }

    /** Menelusuri objek terjangkau tanpa naik ke halaman (/P) atau induk (/Parent). */
    private static void reach(COSBase base, Set<COSObjectKey> keys, Set<COSBase> seen, int depth) {
        if (base == null || depth > 64) {
            return;
        }
        if (base instanceof COSObject ref) {
            if (ref.getKey() != null && !keys.add(ref.getKey())) {
                return;
            }
            base = ref.getObject();
            if (base == null) {
                return;
            }
        }
        if (!seen.add(base)) {
            return;
        }
        if (base instanceof COSDictionary dict) {
            for (COSName name : dict.keySet()) {
                if (!COSName.P.equals(name) && !COSName.PARENT.equals(name)) {
                    reach(dict.getItem(name), keys, seen, depth + 1);
                }
            }
        } else if (base instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                reach(array.get(i), keys, seen, depth + 1);
            }
        }
    }

    private static String describe(COSBase base) {
        if (base instanceof COSDictionary dict) {
            COSName type = dict.getCOSName(COSName.TYPE);
            COSName subtype = dict.getCOSName(COSName.SUBTYPE);
            return (type == null ? "" : type.getName()) + (subtype == null ? "" : "/" + subtype.getName());
        }
        return base == null ? "null" : base.getClass().getSimpleName();
    }

    private static List<Integer> eofEnds(byte[] pdf) {
        List<Integer> ends = new ArrayList<>();
        outer:
        for (int i = 0; i <= pdf.length - EOF_MARKER.length; i++) {
            for (int j = 0; j < EOF_MARKER.length; j++) {
                if (pdf[i + j] != EOF_MARKER[j]) {
                    continue outer;
                }
            }
            int end = i + EOF_MARKER.length;
            while (end < pdf.length && end < i + EOF_MARKER.length + 2 && (pdf[end] == '\r' || pdf[end] == '\n')) {
                end++;
            }
            ends.add(end);
        }
        return ends;
    }
}
