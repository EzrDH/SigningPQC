package id.pkl.signingpqc.core;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collection;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.SignerInformationVerifier;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.util.Store;

/**
 * Klien RFC 3161: membuat TimeStampReq, mengirimnya lewat {@link TimestampTransport}, lalu memeriksa
 * jawaban (status, nonce, message imprint, kebijakan) dan tanda tangan token.
 *
 * <p>Dipakai untuk signature timestamp (PAdES B-T) dan document timestamp (PAdES B-LTA).
 */
public final class TimestampClient {

    private final TimestampTransport transport;
    private final ASN1ObjectIdentifier requestedPolicy;
    private final SecureRandom random = new SecureRandom();

    public TimestampClient(TimestampTransport transport) {
        this(transport, null);
    }

    /** @param requestedPolicy kebijakan TSA yang diminta; null berarti kebijakan bawaan TSA */
    public TimestampClient(TimestampTransport transport, ASN1ObjectIdentifier requestedPolicy) {
        CryptoProviders.ensureRegistered();
        this.transport = transport;
        this.requestedPolicy = requestedPolicy;
    }

    /**
     * Meminta timestamp atas data (data di-hash dengan digestName, misalnya "SHA-256" atau "SHA-512").
     */
    public TimeStampToken timestamp(byte[] data, String digestName) throws GeneralSecurityException {
        byte[] imprint = MessageDigest.getInstance(digestName, CryptoProviders.BC).digest(data);
        return timestampDigest(imprint, digestOid(digestName));
    }

    public TimeStampToken timestampDigest(byte[] imprint, ASN1ObjectIdentifier digestAlgorithm)
            throws GeneralSecurityException {
        TimeStampRequestGenerator generator = new TimeStampRequestGenerator();
        generator.setCertReq(true);
        if (requestedPolicy != null) {
            generator.setReqPolicy(requestedPolicy);
        }
        BigInteger nonce = new BigInteger(64, random);
        TimeStampRequest request = generator.generate(digestAlgorithm, imprint, nonce);
        try {
            byte[] answer = transport.send(request.getEncoded());
            TimeStampResponse response = new TimeStampResponse(answer);
            TimeStampToken token = response.getTimeStampToken();
            if (token == null) {
                throw new TimestampException("TSA menolak permintaan: status " + response.getStatus()
                        + (response.getStatusString() == null ? "" : " (" + response.getStatusString() + ")")
                        + (response.getFailInfo() == null ? "" : ", failInfo " + response.getFailInfo().intValue()));
            }
            // Memeriksa status, nonce, message imprint, dan kebijakan terhadap permintaan.
            response.validate(request);
            verify(token);
            return token;
        } catch (IOException e) {
            throw new TimestampException("Gagal menghubungi TSA atau membaca jawaban: " + e.getMessage(), e);
        } catch (TSPException e) {
            throw new TimestampException("Jawaban TSA tidak sesuai permintaan: " + e.getMessage(), e);
        }
    }

    /**
     * Memeriksa tanda tangan token dengan sertifikat TSA yang disertakan di dalam token, termasuk
     * ESSCertID/ESSCertIDv2 dan EKU id-kp-timeStamping kritis. Rantai sertifikat TSA belum diperiksa di sini.
     *
     * @return sertifikat TSA penanda tangan token
     */
    public static X509Certificate verify(TimeStampToken token) throws TimestampException {
        try {
            X509CertificateHolder tsaCertificate = tsaCertificate(token);
            SignerInformationVerifier verifier = new JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider(CryptoProviders.BC).build(tsaCertificate);
            token.validate(verifier);
            return new JcaX509CertificateConverter().setProvider(CryptoProviders.BC).getCertificate(tsaCertificate);
        } catch (TSPException | OperatorCreationException | java.security.cert.CertificateException e) {
            throw new TimestampException("Token timestamp tidak sah: " + e.getMessage(), e);
        }
    }

    /** Apakah message imprint token sama dengan hash data. */
    public static boolean imprintMatches(TimeStampToken token, byte[] data) throws GeneralSecurityException {
        ASN1ObjectIdentifier oid = token.getTimeStampInfo().getMessageImprintAlgOID();
        String name = digestName(oid);
        byte[] expected = MessageDigest.getInstance(name, CryptoProviders.BC).digest(data);
        return MessageDigest.isEqual(expected, token.getTimeStampInfo().getMessageImprintDigest());
    }

    @SuppressWarnings("unchecked")
    private static X509CertificateHolder tsaCertificate(TimeStampToken token) throws TimestampException {
        Store<X509CertificateHolder> store = token.getCertificates();
        Collection<X509CertificateHolder> matches = store.getMatches(token.getSID());
        if (matches.isEmpty()) {
            throw new TimestampException("Sertifikat TSA tidak disertakan di token (certReq)");
        }
        return matches.iterator().next();
    }

    static ASN1ObjectIdentifier digestOid(String digestName) {
        return switch (digestName) {
            case "SHA-256" -> NISTObjectIdentifiers.id_sha256;
            case "SHA-384" -> NISTObjectIdentifiers.id_sha384;
            case "SHA-512" -> NISTObjectIdentifiers.id_sha512;
            default -> throw new IllegalArgumentException("Digest tidak didukung untuk timestamp: " + digestName);
        };
    }

    static String digestName(ASN1ObjectIdentifier oid) {
        if (NISTObjectIdentifiers.id_sha256.equals(oid)) {
            return "SHA-256";
        }
        if (NISTObjectIdentifiers.id_sha384.equals(oid)) {
            return "SHA-384";
        }
        if (NISTObjectIdentifiers.id_sha512.equals(oid)) {
            return "SHA-512";
        }
        throw new IllegalArgumentException("Digest message imprint tidak didukung: " + oid);
    }
}
