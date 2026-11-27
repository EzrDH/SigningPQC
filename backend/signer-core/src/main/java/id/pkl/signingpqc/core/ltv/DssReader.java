package id.pkl.signingpqc.core.ltv;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

import id.pkl.signingpqc.core.CryptoProviders;

/** Membaca kamus DSS (PAdES B-LT) dari PDF. PDF tanpa DSS menghasilkan data kosong. */
public final class DssReader {

    /** SubFilter document timestamp (ETSI EN 319 142-1); PDFBox 3 belum punya konstantanya. */
    public static final String ETSI_RFC3161 = "ETSI.RFC3161";

    static final COSName DSS = COSName.getPDFName("DSS");
    static final COSName CERTS = COSName.getPDFName("Certs");
    static final COSName CRLS = COSName.getPDFName("CRLs");
    static final COSName VRI = COSName.getPDFName("VRI");
    static final COSName CERT = COSName.getPDFName("Cert");
    static final COSName CRL = COSName.getPDFName("CRL");

    private DssReader() {
    }

    /** Semua sertifikat dan CRL di /DSS/Certs dan /DSS/CRLs. */
    public static ValidationData readAll(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            COSDictionary dss = dss(document);
            if (dss == null) {
                return ValidationData.EMPTY;
            }
            return read(dss.getCOSArray(CERTS), dss.getCOSArray(CRLS));
        }
    }

    /** Isi /DSS/VRI: kunci (SHA-1 heksadesimal huruf besar) ke data validasi tanda tangan itu. */
    public static Map<String, ValidationData> readVri(byte[] pdf) throws IOException {
        Map<String, ValidationData> result = new LinkedHashMap<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            COSDictionary dss = dss(document);
            COSDictionary vri = dss == null ? null : dss.getCOSDictionary(VRI);
            if (vri == null) {
                return result;
            }
            for (COSName key : vri.keySet()) {
                COSDictionary entry = vri.getCOSDictionary(key);
                if (entry != null) {
                    result.put(key.getName(), read(entry.getCOSArray(CERT), entry.getCOSArray(CRL)));
                }
            }
        }
        return result;
    }

    static COSDictionary dss(PDDocument document) {
        return document.getDocumentCatalog().getCOSObject().getCOSDictionary(DSS);
    }

    static ValidationData read(COSArray certs, COSArray crls) throws IOException {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509", CryptoProviders.BC);
            List<X509Certificate> certificates = new ArrayList<>();
            for (byte[] der : streams(certs)) {
                certificates.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
            }
            List<X509CRL> revocations = new ArrayList<>();
            for (byte[] der : streams(crls)) {
                revocations.add((X509CRL) factory.generateCRL(new ByteArrayInputStream(der)));
            }
            return new ValidationData(certificates, revocations);
        } catch (GeneralSecurityException e) {
            throw new IOException("Data DSS tidak dapat dibaca: " + e.getMessage(), e);
        }
    }

    static List<byte[]> streams(COSArray array) throws IOException {
        List<byte[]> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.size(); i++) {
            COSBase item = array.getObject(i);
            if (item instanceof COSStream stream) {
                try (InputStream in = stream.createInputStream()) {
                    out.add(in.readAllBytes());
                }
            }
        }
        return out;
    }
}
