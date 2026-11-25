package id.pkl.signingpqc.pki;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cmp.PKIFailureInfo;
import org.bouncycastle.asn1.cmp.PKIStatus;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.SignerInfoGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;

import id.pkl.signingpqc.core.CryptoProviders;
import id.pkl.signingpqc.core.SigningCredential;
import id.pkl.signingpqc.core.TimestampTransport;

/**
 * TSA lokal RFC 3161 untuk lingkungan uji, dengan kunci RSA atau ML-DSA.
 *
 * <ul>
 *   <li>Message imprint yang diterima: SHA-256, SHA-384, SHA-512.</li>
 *   <li>Token memuat ESSCertIDv2 (RFC 5035), accuracy 1 detik, nonce bila diminta, dan sertifikat TSA
 *       beserta rantainya bila certReq = true.</li>
 *   <li>Digest CMS di dalam token: SHA-256 untuk RSA, SHA-512 untuk ML-DSA (RFC 9882).</li>
 *   <li>Permintaan ditolak bila sertifikat TSA belum atau sudah tidak berlaku pada jam TSA. Jam dapat
 *       diganti ({@link Clock}) untuk simulasi TSA kedaluwarsa.</li>
 * </ul>
 *
 * <p>Kelas ini juga bisa dipakai langsung sebagai {@link TimestampTransport} di dalam proses (tanpa HTTP).
 */
public final class LocalTsa implements TimestampTransport {

    /**
     * OID kebijakan TSA uji, di bawah nomor enterprise 32473 yang dicadangkan untuk dokumentasi (RFC 5612).
     *
     * <p>Sebelumnya dipakai OID berbasis UUID (2.25.&lt;bilangan 128 bit&gt;). Arc sebesar itu sah menurut
     * X.660, tetapi PdfCraft menolaknya ("OID arc too large") sehingga timestamp tidak bisa dibaca (T-011).
     * Semua arc di sini muat dalam 32 bit.
     */
    public static final ASN1ObjectIdentifier TEST_POLICY = new ASN1ObjectIdentifier("1.3.6.1.4.1.32473.1.1");

    private static final Set<ASN1ObjectIdentifier> ACCEPTED_DIGESTS = Set.of(
            NISTObjectIdentifiers.id_sha256, NISTObjectIdentifiers.id_sha384, NISTObjectIdentifiers.id_sha512);

    private final SigningCredential credential;
    private final TimeStampResponseGenerator responseGenerator;
    private final AtomicReference<Clock> clock;
    private final Object serialLock = new Object();
    private BigInteger nextSerial;

    public LocalTsa(SigningCredential credential) throws GeneralSecurityException {
        this(credential, Clock.systemUTC());
    }

    public LocalTsa(SigningCredential credential, Clock clock) throws GeneralSecurityException {
        CryptoProviders.ensureRegistered();
        this.credential = credential;
        this.clock = new AtomicReference<>(clock);
        this.nextSerial = new BigInteger(63, new SecureRandom());
        try {
            DigestCalculatorProvider digests = new JcaDigestCalculatorProviderBuilder()
                    .setProvider(CryptoProviders.BC).build();
            AlgorithmIdentifier cmsDigest = credential.algorithm().digestAlgorithmIdentifier();
            ContentSigner signer = new JcaContentSignerBuilder(credential.algorithm().jcaSignatureName())
                    .setProvider(CryptoProviders.BC).build(credential.privateKey());
            SignerInfoGenerator signerInfo = new JcaSignerInfoGeneratorBuilder(digests)
                    .setContentDigest(cmsDigest)
                    .build(signer, credential.certificate());
            // Digest untuk ESSCertIDv2 (hash sertifikat TSA) mengikuti digest CMS.
            DigestCalculator certIdDigest = digests.get(cmsDigest);
            TimeStampTokenGenerator tokenGenerator = new TimeStampTokenGenerator(signerInfo, certIdDigest, TEST_POLICY);
            tokenGenerator.setAccuracySeconds(1);
            tokenGenerator.addCertificates(new JcaCertStore(credential.chain()));
            this.responseGenerator = new TimeStampResponseGenerator(tokenGenerator, ACCEPTED_DIGESTS,
                    Set.of(TEST_POLICY));
        } catch (OperatorCreationException | TSPException e) {
            throw new GeneralSecurityException("Gagal menyiapkan TSA: " + e.getMessage(), e);
        }
    }

    public X509Certificate certificate() {
        return credential.certificate();
    }

    public SigningCredential credential() {
        return credential;
    }

    /** Mengganti jam TSA (simulasi waktu, misalnya setelah sertifikat TSA kedaluwarsa). */
    public void setClock(Clock newClock) {
        clock.set(newClock);
    }

    @Override
    public byte[] send(byte[] timeStampRequest) throws IOException {
        return handle(timeStampRequest);
    }

    /** Memproses TimeStampReq (DER) dan mengembalikan TimeStampResp (DER). Tidak pernah melempar karena isi permintaan. */
    public byte[] handle(byte[] requestDer) throws IOException {
        Instant now = clock.get().instant();
        try {
            TimeStampRequest request;
            try {
                request = new TimeStampRequest(requestDer);
            } catch (IOException | IllegalArgumentException e) {
                return fail(PKIFailureInfo.badDataFormat, "Permintaan timestamp tidak dapat dibaca");
            }
            X509Certificate cert = credential.certificate();
            if (now.isBefore(cert.getNotBefore().toInstant()) || now.isAfter(cert.getNotAfter().toInstant())) {
                return fail(PKIFailureInfo.timeNotAvailable,
                        "Sertifikat TSA tidak berlaku pada " + now + " (berlaku sampai " + cert.getNotAfter().toInstant() + ")");
            }
            TimeStampResponse response = responseGenerator.generate(request, serial(), Date.from(now));
            return response.getEncoded();
        } catch (TSPException e) {
            return fail(PKIFailureInfo.systemFailure, "Kesalahan TSA: " + e.getMessage());
        }
    }

    private byte[] fail(int failInfo, String message) throws IOException {
        try {
            return responseGenerator.generateFailResponse(PKIStatus.REJECTION, failInfo, message).getEncoded();
        } catch (TSPException e) {
            throw new IOException(e);
        }
    }

    private BigInteger serial() {
        synchronized (serialLock) {
            nextSerial = nextSerial.add(BigInteger.ONE);
            return nextSerial;
        }
    }
}
