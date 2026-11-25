package id.pkl.signingpqc.core;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Sertifikat self-signed sederhana untuk bukti teknis Sprint 0.
 *
 * <p>Akan diganti hierarki CA uji (Root, CA antara, penanda tangan) pada Sprint 1.
 */
public final class SpikeCredentials {

    private SpikeCredentials() {
    }

    public static SigningCredential selfSigned(SignatureAlgorithm algorithm, String subjectDn)
            throws GeneralSecurityException {
        CryptoProviders.ensureRegistered();
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm.keyAlgorithm(), CryptoProviders.BC);
        if (algorithm.keySize() > 0) {
            generator.initialize(algorithm.keySize(), new SecureRandom());
        }
        KeyPair keyPair = generator.generateKeyPair();

        X500Name subject = new X500Name(subjectDn);
        Instant now = Instant.now();
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                new BigInteger(64, new SecureRandom()),
                Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(Duration.ofDays(365))),
                subject,
                keyPair.getPublic());
        try {
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
            ContentSigner signer = new JcaContentSignerBuilder(algorithm.jcaSignatureName())
                    .setProvider(CryptoProviders.BC)
                    .build(keyPair.getPrivate());
            X509Certificate certificate = new JcaX509CertificateConverter()
                    .setProvider(CryptoProviders.BC)
                    .getCertificate(builder.build(signer));
            return new SigningCredential(algorithm, keyPair.getPrivate(), List.of(certificate));
        } catch (OperatorCreationException | java.io.IOException e) {
            throw new GeneralSecurityException("Gagal membuat sertifikat uji: " + e.getMessage(), e);
        }
    }
}
