package id.pkl.signingpqc.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;

/**
 * Menambahkan tanda tangan PAdES (ETSI.CAdES.detached) ke PDF sebagai incremental update.
 *
 * <p>Tanda tangan bisa invisible (dipakai untuk ML-DSA) atau visible dengan kotak di halaman (dipakai untuk RSA),
 * dengan atau tanpa signature timestamp (B-B atau B-T).
 */
public final class PadesSigner {

    /** Ruang /Contents (byte biner) untuk CMS RSA dan ML-DSA tanpa timestamp. */
    static final int RSA_SIGNATURE_SPACE = 16 * 1024;
    static final int ML_DSA_SIGNATURE_SPACE = 48 * 1024;
    /** Tambahan ruang untuk token signature timestamp (token beserta rantai sertifikat TSA). */
    static final int RSA_TIMESTAMP_SPACE = 16 * 1024;
    static final int ML_DSA_TIMESTAMP_SPACE = 40 * 1024;

    private final CadesSignatureBuilder cadesBuilder;
    private final String filter;
    private final String subFilter;

    public PadesSigner() {
        this(new CadesSignatureBuilder());
    }

    public PadesSigner(CadesSignatureBuilder cadesBuilder) {
        this(cadesBuilder, PDSignature.FILTER_ADOBE_PPKLITE.getName(),
                PDSignature.SUBFILTER_ETSI_CADES_DETACHED.getName());
    }

    private PadesSigner(CadesSignatureBuilder cadesBuilder, String filter, String subFilter) {
        this.cadesBuilder = cadesBuilder;
        this.filter = filter;
        this.subFilter = subFilter;
    }

    /**
     * Salinan signer dengan nama /Filter dan /SubFilter lain di kamus tanda tangan.
     *
     * <p>Hanya untuk eksperimen interoperabilitas (T-011): melihat apakah pembaca PDF yang belum mengenal
     * ML-DSA menampilkan tanda tangan sebagai "tidak diketahui" alih-alih "tidak sah" bila handler-nya
     * tidak dikenal. Nilai bawaan (Adobe.PPKLite + ETSI.CAdES.detached) adalah yang sesuai PAdES.
     */
    public PadesSigner withDictionaryNames(String filter, String subFilter) {
        if (filter == null || filter.isBlank() || subFilter == null || subFilter.isBlank()) {
            throw new IllegalArgumentException("Filter dan SubFilter wajib diisi");
        }
        return new PadesSigner(cadesBuilder, filter, subFilter);
    }

    public byte[] signInvisible(byte[] pdf, SigningCredential credential, SignatureDetails details)
            throws IOException, GeneralSecurityException {
        return sign(pdf, credential, details, null, null);
    }

    /** Tanda tangan invisible; bila tsa tidak null, CMS diberi signature timestamp (PAdES B-T). */
    public byte[] signInvisible(byte[] pdf, SigningCredential credential, SignatureDetails details,
            TimestampClient tsa) throws IOException, GeneralSecurityException {
        return sign(pdf, credential, details, tsa, null);
    }

    /** Tanda tangan yang tampil sebagai kotak di halaman; bila tsa tidak null, level B-T. */
    public byte[] signVisible(byte[] pdf, SigningCredential credential, SignatureDetails details,
            VisibleSignature visible, TimestampClient tsa) throws IOException, GeneralSecurityException {
        if (visible == null) {
            throw new IllegalArgumentException("Posisi tanda tangan visible wajib diisi");
        }
        return sign(pdf, credential, details, tsa, visible);
    }

    private byte[] sign(byte[] pdf, SigningCredential credential, SignatureDetails details, TimestampClient tsa,
            VisibleSignature visible) throws IOException, GeneralSecurityException {
        int space = signatureSpace(credential.algorithm(), tsa != null);
        try (PDDocument document = Loader.loadPDF(pdf);
                SignatureOptions options = new SignatureOptions();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            Calendar signDate = Calendar.getInstance(TimeZone.getTimeZone(SignatureAppearance.WIB));
            PDSignature signature = new PDSignature();
            signature.setFilter(COSName.getPDFName(filter));
            signature.setSubFilter(COSName.getPDFName(subFilter));
            signature.setName(details.signerName());
            signature.setReason(details.reason());
            signature.setLocation(details.location());
            signature.setSignDate(signDate);

            options.setPreferredSignatureSize(space);
            if (visible != null) {
                PDRectangle rect = SignatureAppearance.rectangle(document, visible);
                List<String> lines = SignatureAppearance.lines(details, visible, signDate, credential.algorithm());
                options.setVisualSignature(SignatureAppearance.template(document, visible, rect, lines));
                options.setPage(visible.pageIndex());
            }
            document.addSignature(signature, options);

            ExternalSigningSupport externalSigning = document.saveIncrementalForExternalSigning(output);
            byte[] toBeSigned;
            try (InputStream content = externalSigning.getContent()) {
                toBeSigned = content.readAllBytes();
            }
            byte[] cms = cadesBuilder.sign(toBeSigned, credential, tsa);
            if (cms.length > space) {
                throw new GeneralSecurityException("Ruang tanda tangan tidak cukup: CMS " + cms.length
                        + " byte, ruang " + space + " byte");
            }
            externalSigning.setSignature(cms);
            return output.toByteArray();
        }
    }

    static int signatureSpace(SignatureAlgorithm algorithm, boolean withTimestamp) {
        if (algorithm.isPostQuantum()) {
            return ML_DSA_SIGNATURE_SPACE + (withTimestamp ? ML_DSA_TIMESTAMP_SPACE : 0);
        }
        return RSA_SIGNATURE_SPACE + (withTimestamp ? RSA_TIMESTAMP_SPACE : 0);
    }
}
