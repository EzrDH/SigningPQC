package id.pkl.signingpqc.core;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampToken;

/**
 * Signature timestamp (PAdES B-T, ETSI EN 319 122-1 bagian 5.3): token RFC 3161 atas nilai tanda tangan
 * (SignerInfo.signature) disimpan sebagai atribut tak bertanda tangan id-aa-signatureTimeStampToken.
 */
public final class SignatureTimestamps {

    private SignatureTimestamps() {
    }

    /**
     * Menambahkan signature timestamp ke setiap SignerInfo.
     *
     * @param digestName digest message imprint ("SHA-256" untuk RSA, "SHA-512" untuk ML-DSA)
     */
    public static CMSSignedData addTo(CMSSignedData signedData, TimestampClient tsa, String digestName)
            throws GeneralSecurityException {
        List<SignerInformation> updated = new ArrayList<>();
        for (SignerInformation signer : signedData.getSignerInfos().getSigners()) {
            TimeStampToken token = tsa.timestamp(signer.getSignature(), digestName);
            Attribute attribute = new Attribute(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken,
                    new DERSet(token.toCMSSignedData().toASN1Structure()));
            AttributeTable unsigned = signer.getUnsignedAttributes() == null
                    ? new AttributeTable(attribute)
                    : signer.getUnsignedAttributes().add(attribute.getAttrType(), attribute.getAttrValues().getObjectAt(0));
            updated.add(SignerInformation.replaceUnsignedAttributes(signer, unsigned));
        }
        return CMSSignedData.replaceSigners(signedData, new SignerInformationStore(updated));
    }

    /** Membaca token signature timestamp dari SignerInfo; null bila tidak ada. */
    public static TimeStampToken tokenOf(SignerInformation signer) throws GeneralSecurityException {
        if (signer.getUnsignedAttributes() == null) {
            return null;
        }
        Attribute attribute = signer.getUnsignedAttributes().get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken);
        if (attribute == null) {
            return null;
        }
        ASN1Encodable value = attribute.getAttrValues().getObjectAt(0);
        try {
            return new TimeStampToken(ContentInfo.getInstance(value));
        } catch (TSPException | IOException e) {
            throw new TimestampException("Token signature timestamp tidak dapat dibaca: " + e.getMessage(), e);
        }
    }

    /** Memeriksa signature timestamp sebuah SignerInfo; null bila tidak ada timestamp. */
    public static TimestampReport inspect(SignerInformation signer) {
        TimeStampToken token;
        try {
            token = tokenOf(signer);
        } catch (GeneralSecurityException e) {
            return new TimestampReport(null, "-", "-", "-", "-", false, false, e.getMessage());
        }
        if (token == null) {
            return null;
        }
        return inspect(token, signer.getSignature());
    }

    /** Memeriksa token terhadap data yang seharusnya di-timestamp. */
    public static TimestampReport inspect(TimeStampToken token, byte[] timestampedData) {
        java.time.Instant time = token.getTimeStampInfo().getGenTime().toInstant();
        String serial = token.getTimeStampInfo().getSerialNumber().toString(16).toUpperCase();
        String imprintAlgorithm = AlgorithmNames.digest(token.getTimeStampInfo().getMessageImprintAlgOID().getId());
        String policy = token.getTimeStampInfo().getPolicy() == null ? null
                : token.getTimeStampInfo().getPolicy().getId();
        String signatureAlgorithm = "-";
        try {
            SignerInformation tsaSigner = token.toCMSSignedData().getSignerInfos().getSigners().iterator().next();
            signatureAlgorithm = AlgorithmNames.signature(tsaSigner.getEncryptionAlgOID());
        } catch (RuntimeException e) {
            // biarkan "-"
        }
        boolean imprintMatches;
        try {
            imprintMatches = TimestampClient.imprintMatches(token, timestampedData);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return new TimestampReport(time, "-", serial, signatureAlgorithm, imprintAlgorithm, false, false,
                    "Message imprint tidak dapat diperiksa: " + e.getMessage(), policy);
        }
        try {
            X509Certificate tsaCertificate = TimestampClient.verify(token);
            String subject = tsaCertificate.getSubjectX500Principal().getName();
            String message = imprintMatches ? "Timestamp sah dan cocok dengan nilai tanda tangan"
                    : "Timestamp sah tetapi tidak cocok dengan nilai tanda tangan";
            return new TimestampReport(time, subject, serial, signatureAlgorithm, imprintAlgorithm, imprintMatches, true,
                    message, policy);
        } catch (TimestampException e) {
            return new TimestampReport(time, "-", serial, signatureAlgorithm, imprintAlgorithm, imprintMatches, false,
                    e.getMessage(), policy);
        }
    }
}
