package id.pkl.signingpqc.core.ltv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CRLException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;

import id.pkl.signingpqc.core.PdfSignatureVerifier;

/**
 * Menulis atau menggabungkan kamus DSS (ETSI EN 319 142-1, PAdES B-LT) sebagai pembaruan inkremental.
 *
 * <p>Sertifikat dan CRL disimpan sebagai stream berisi DER di /DSS/Certs dan /DSS/CRLs. Entri /VRI menunjuk ke
 * stream yang sama. Data yang sudah ada tidak ditulis ulang.
 */
public final class DssWriter {

    private DssWriter() {
    }

    /**
     * Kunci VRI: SHA-1 heksadesimal huruf besar dari nilai /Contents.
     *
     * <p>Untuk tanda tangan CMS dipakai seluruh isi /Contents (termasuk padding nol), sama dengan contoh
     * AddValidationInformation PDFBox. Untuk document timestamp (ETSI.RFC3161) dipakai DER token tanpa padding.
     */
    public static String vriKey(PDSignature signature, byte[] pdf) throws IOException {
        byte[] contents = signature.getContents(pdf);
        if (DssReader.ETSI_RFC3161.equals(signature.getSubFilter())) {
            contents = PdfSignatureVerifier.firstDerObject(contents);
        }
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(contents));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Menambahkan data validasi per kunci VRI. Mengembalikan PDF baru (PDF lama + revisi DSS). */
    public static byte[] append(byte[] pdf, Map<String, ValidationData> byVriKey) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
            COSDictionary dss = catalog.getCOSDictionary(DssReader.DSS);
            if (dss == null) {
                dss = new COSDictionary();
                dss.setDirect(true);
                catalog.setItem(DssReader.DSS, dss);
            }
            COSArray certs = arrayOf(dss, DssReader.CERTS);
            COSArray crls = arrayOf(dss, DssReader.CRLS);
            Map<String, COSStream> certStreams = index(certs);
            Map<String, COSStream> crlStreams = index(crls);

            COSDictionary vri = dss.getCOSDictionary(DssReader.VRI);
            if (vri == null) {
                vri = new COSDictionary();
                vri.setDirect(true);
                dss.setItem(DssReader.VRI, vri);
            }
            for (Map.Entry<String, ValidationData> entry : byVriKey.entrySet()) {
                COSName key = COSName.getPDFName(entry.getKey());
                COSDictionary vriEntry = vri.getCOSDictionary(key);
                if (vriEntry == null) {
                    vriEntry = new COSDictionary();
                    vriEntry.setDirect(true);
                    vri.setItem(key, vriEntry);
                }
                COSArray vriCerts = arrayOf(vriEntry, DssReader.CERT);
                COSArray vriCrls = arrayOf(vriEntry, DssReader.CRL);
                for (X509Certificate certificate : entry.getValue().certificates()) {
                    COSStream stream = streamFor(document, certStreams, certs, ValidationData.key(certificate),
                            encoded(certificate));
                    addOnce(vriCerts, stream);
                }
                for (X509CRL crl : entry.getValue().crls()) {
                    COSStream stream = streamFor(document, crlStreams, crls, ValidationData.key(crl), encoded(crl));
                    addOnce(vriCrls, stream);
                }
                vriEntry.setNeedToBeUpdated(true);
            }
            vri.setNeedToBeUpdated(true);
            dss.setNeedToBeUpdated(true);
            catalog.setNeedToBeUpdated(true);
            document.saveIncremental(output);
            return output.toByteArray();
        }
    }

    private static COSArray arrayOf(COSDictionary dictionary, COSName name) {
        COSArray array = dictionary.getCOSArray(name);
        if (array == null) {
            array = new COSArray();
            array.setDirect(true);
            dictionary.setItem(name, array);
        }
        array.setNeedToBeUpdated(true);
        return array;
    }

    private static Map<String, COSStream> index(COSArray array) throws IOException {
        Map<String, COSStream> index = new HashMap<>();
        for (int i = 0; i < array.size(); i++) {
            if (array.getObject(i) instanceof COSStream stream) {
                try (var in = stream.createInputStream()) {
                    index.put(HexFormat.of().formatHex(in.readAllBytes()), stream);
                }
            }
        }
        return index;
    }

    private static COSStream streamFor(PDDocument document, Map<String, COSStream> index, COSArray target,
            String key, byte[] der) throws IOException {
        COSStream existing = index.get(key);
        if (existing != null) {
            return existing;
        }
        COSStream stream = document.getDocument().createCOSStream();
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(der);
        }
        stream.setNeedToBeUpdated(true);
        target.add(stream);
        index.put(key, stream);
        return stream;
    }

    private static void addOnce(COSArray array, COSStream stream) {
        for (int i = 0; i < array.size(); i++) {
            if (array.getObject(i) == stream) {
                return;
            }
        }
        array.add(stream);
    }

    private static byte[] encoded(X509Certificate certificate) {
        try {
            return certificate.getEncoded();
        } catch (CertificateEncodingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static byte[] encoded(X509CRL crl) {
        try {
            return crl.getEncoded();
        } catch (CRLException e) {
            throw new IllegalArgumentException(e);
        }
    }

}
