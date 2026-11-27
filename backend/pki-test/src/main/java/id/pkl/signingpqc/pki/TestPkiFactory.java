package id.pkl.signingpqc.pki;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.CRLNumber;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import id.pkl.signingpqc.core.CryptoProviders;
import id.pkl.signingpqc.core.SignatureAlgorithm;
import id.pkl.signingpqc.core.SigningCredential;

/**
 * Membuat hierarki CA uji, sertifikat penanda tangan, sertifikat TSA, dan CRL.
 *
 * <p>Profil mengikuti RFC 5280 (RSA) dan RFC 9881 (ML-DSA). Hanya untuk lingkungan uji.
 */
public final class TestPkiFactory {

    /** Alamat dasar CRL yang ditulis ke ekstensi CRL Distribution Points (dilayani backend lokal). */
    public static final String DEFAULT_CRL_BASE_URL = "http://127.0.0.1:8085/api/pki/crl/";

    public static final Duration ROOT_VALIDITY = Duration.ofDays(3650);
    public static final Duration INTERMEDIATE_VALIDITY = Duration.ofDays(1825);
    public static final Duration SIGNER_VALIDITY = Duration.ofDays(730);
    public static final Duration TSA_VALIDITY = Duration.ofDays(1825);
    public static final Duration CRL_VALIDITY = Duration.ofDays(7);

    /**
     * OID kebijakan sertifikat uji di sertifikat penanda tangan, di bawah arc contoh IANA 32473 (RFC 5612). Bukan
     * kebijakan CA sungguhan.
     */
    public static final org.bouncycastle.asn1.ASN1ObjectIdentifier TEST_CERTIFICATE_POLICY =
            new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.3.6.1.4.1.32473.2.1");

    /** Toleransi jam: sertifikat berlaku mulai sedikit sebelum waktu pembuatan. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final SecureRandom random = new SecureRandom();
    private final String crlBaseUrl;
    private final Clock clock;

    public TestPkiFactory() {
        this(DEFAULT_CRL_BASE_URL);
    }

    public TestPkiFactory(String crlBaseUrl) {
        this(crlBaseUrl, Clock.systemUTC());
    }

    /**
     * @param clock jam untuk notBefore sertifikat dan thisUpdate CRL; bisa digeser untuk simulasi waktu
     */
    public TestPkiFactory(String crlBaseUrl, Clock clock) {
        CryptoProviders.ensureRegistered();
        this.crlBaseUrl = crlBaseUrl;
        this.clock = clock;
    }

    public Clock clock() {
        return clock;
    }

    /** Membuat Root CA dan CA antara untuk satu keluarga algoritma. */
    public CaHierarchy createHierarchy(PkiFamily family, String organization) throws GeneralSecurityException {
        return createHierarchy(family, organization, ROOT_VALIDITY, INTERMEDIATE_VALIDITY);
    }

    /** Seperti {@link #createHierarchy(PkiFamily, String)}, dengan masa berlaku root dan CA antara pilihan. */
    public CaHierarchy createHierarchy(PkiFamily family, String organization, Duration rootValidity,
            Duration intermediateValidity) throws GeneralSecurityException {
        Instant now = clock.instant();
        String rootId = family.slug() + "-root";
        String intermediateId = family.slug() + "-intermediate";

        KeyPair rootKeys = generateKeyPair(family.rootAlgorithm());
        X500Name rootName = caName("Root CA Uji " + family.label(), organization);
        X509Certificate rootCert = build(rootName, rootKeys.getPublic(), rootName, rootKeys.getPrivate(), null,
                family.rootAlgorithm(), now, rootValidity, builder -> {
                    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(1));
                    builder.addExtension(Extension.keyUsage, true,
                            new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
                });
        CertificateAuthority root = new CertificateAuthority(rootId, family,
                new SigningCredential(family.rootAlgorithm(), rootKeys.getPrivate(), List.of(rootCert)));

        KeyPair intermediateKeys = generateKeyPair(family.intermediateAlgorithm());
        X500Name intermediateName = caName("CA Antara Uji " + family.label(), organization);
        X509Certificate intermediateCert = build(intermediateName, intermediateKeys.getPublic(), rootName,
                rootKeys.getPrivate(), rootCert, family.rootAlgorithm(), now, intermediateValidity, builder -> {
                    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
                    builder.addExtension(Extension.keyUsage, true,
                            new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
                    builder.addExtension(Extension.cRLDistributionPoints, false, crlDistributionPoint(rootId));
                });
        CertificateAuthority intermediate = new CertificateAuthority(intermediateId, family,
                new SigningCredential(family.intermediateAlgorithm(), intermediateKeys.getPrivate(),
                        List.of(intermediateCert, rootCert)));

        return new CaHierarchy(family, root, intermediate);
    }

    /** Menerbitkan sertifikat penanda tangan dokumen (digitalSignature + nonRepudiation). */
    public SigningCredential issueSigner(CertificateAuthority issuer, SignerIdentity identity)
            throws GeneralSecurityException {
        return issueSigner(issuer, identity, SIGNER_VALIDITY);
    }

    public SigningCredential issueSigner(CertificateAuthority issuer, SignerIdentity identity, Duration validity)
            throws GeneralSecurityException {
        SignatureAlgorithm algorithm = issuer.family().endEntityAlgorithm();
        KeyPair keys = generateKeyPair(algorithm);
        X509Certificate cert = build(identity.toX500Name(), keys.getPublic(), subjectOf(issuer), issuer.credential().privateKey(),
                issuer.certificate(), issuer.credential().algorithm(), clock.instant(), validity, builder -> {
                    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
                    builder.addExtension(Extension.keyUsage, true,
                            new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
                    builder.addExtension(Extension.extendedKeyUsage, false,
                            new ExtendedKeyUsage(KeyPurposeId.getInstance(
                                    new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.3.6.1.5.5.7.3.36"))));
                    builder.addExtension(Extension.certificatePolicies, false,
                            new org.bouncycastle.asn1.x509.CertificatePolicies(
                                    new org.bouncycastle.asn1.x509.PolicyInformation(TEST_CERTIFICATE_POLICY)));
                    builder.addExtension(Extension.cRLDistributionPoints, false, crlDistributionPoint(issuer.id()));
                });
        return new SigningCredential(algorithm, keys.getPrivate(), chainOf(cert, issuer));
    }

    /** Menerbitkan pasangan sertifikat RSA dan ML-DSA dengan identitas yang sama. */
    public SignerPair issueSignerPair(CaHierarchy rsa, CaHierarchy mlDsa, SignerIdentity identity)
            throws GeneralSecurityException {
        if (rsa.family() != PkiFamily.RSA || mlDsa.family() != PkiFamily.ML_DSA) {
            throw new IllegalArgumentException("Butuh satu hierarki RSA dan satu hierarki ML-DSA");
        }
        return new SignerPair(identity, issueSigner(rsa.intermediate(), identity),
                issueSigner(mlDsa.intermediate(), identity));
    }

    /**
     * Menerbitkan sertifikat TSA (extendedKeyUsage id-kp-timeStamping kritis, sesuai RFC 3161).
     *
     * @param validity masa berlaku; bisa dibuat sangat pendek untuk simulasi TSA kedaluwarsa
     */
    public SigningCredential issueTsa(CertificateAuthority issuer, String name, Duration validity)
            throws GeneralSecurityException {
        SignatureAlgorithm algorithm = issuer.family().endEntityAlgorithm();
        KeyPair keys = generateKeyPair(algorithm);
        X500Name subject = new X500Name("CN=" + name + ", O=" + organizationOf(issuer) + ", C=ID");
        X509Certificate cert = build(subject, keys.getPublic(), subjectOf(issuer), issuer.credential().privateKey(),
                issuer.certificate(), issuer.credential().algorithm(), clock.instant(), validity, builder -> {
                    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
                    builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
                    builder.addExtension(Extension.extendedKeyUsage, true,
                            new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));
                    builder.addExtension(Extension.cRLDistributionPoints, false, crlDistributionPoint(issuer.id()));
                });
        return new SigningCredential(algorithm, keys.getPrivate(), chainOf(cert, issuer));
    }

    /** Membuat CRL yang ditandatangani CA, berisi daftar sertifikat yang dicabut. */
    public X509CRL createCrl(CertificateAuthority issuer, Collection<RevokedCertificate> revoked, long crlNumber)
            throws GeneralSecurityException {
        return createCrl(issuer, revoked, crlNumber, clock.instant());
    }

    /** Membuat CRL dengan thisUpdate tertentu (nextUpdate = thisUpdate + {@link #CRL_VALIDITY}). */
    public X509CRL createCrl(CertificateAuthority issuer, Collection<RevokedCertificate> revoked, long crlNumber,
            Instant thisUpdate) throws GeneralSecurityException {
        Instant now = thisUpdate;
        try {
            X509v2CRLBuilder builder = new X509v2CRLBuilder(
                    new JcaX509CertificateHolder(issuer.certificate()).getSubject(), Date.from(now));
            builder.setNextUpdate(Date.from(now.plus(CRL_VALIDITY)));
            for (RevokedCertificate entry : revoked) {
                builder.addCRLEntry(entry.serialNumber(), Date.from(entry.revocationTime()), entry.reason());
            }
            JcaX509ExtensionUtils utils = new JcaX509ExtensionUtils();
            builder.addExtension(Extension.authorityKeyIdentifier, false,
                    utils.createAuthorityKeyIdentifier(issuer.certificate()));
            builder.addExtension(Extension.cRLNumber, false, new CRLNumber(BigInteger.valueOf(crlNumber)));
            ContentSigner signer = contentSigner(issuer.credential().algorithm(), issuer.credential().privateKey());
            return new JcaX509CRLConverter().setProvider(CryptoProviders.BC).getCRL(builder.build(signer));
        } catch (IOException | OperatorCreationException e) {
            throw new GeneralSecurityException("Gagal membuat CRL: " + e.getMessage(), e);
        }
    }

    /** Data satu sertifikat yang dicabut. */
    public record RevokedCertificate(BigInteger serialNumber, Instant revocationTime, int reason) {

        public static RevokedCertificate keyCompromise(X509Certificate certificate) {
            return new RevokedCertificate(certificate.getSerialNumber(), Instant.now(), CRLReason.keyCompromise);
        }
    }

    // ---------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface ExtensionWriter {
        void write(X509v3CertificateBuilder builder) throws IOException;
    }

    private X509Certificate build(X500Name subject, PublicKey subjectKey, X500Name issuer, PrivateKey issuerKey,
            X509Certificate issuerCert, SignatureAlgorithm issuerAlgorithm, Instant now, Duration validity,
            ExtensionWriter extensions) throws GeneralSecurityException {
        try {
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuer, serialNumber(),
                    Date.from(now.minus(CLOCK_SKEW)), Date.from(now.plus(validity)), subject, subjectKey);
            JcaX509ExtensionUtils utils = new JcaX509ExtensionUtils();
            builder.addExtension(Extension.subjectKeyIdentifier, false, utils.createSubjectKeyIdentifier(subjectKey));
            builder.addExtension(Extension.authorityKeyIdentifier, false, issuerCert == null
                    ? utils.createAuthorityKeyIdentifier(subjectKey)
                    : utils.createAuthorityKeyIdentifier(issuerCert));
            extensions.write(builder);
            ContentSigner signer = contentSigner(issuerAlgorithm, issuerKey);
            return new JcaX509CertificateConverter().setProvider(CryptoProviders.BC).getCertificate(builder.build(signer));
        } catch (IOException | OperatorCreationException e) {
            throw new GeneralSecurityException("Gagal membuat sertifikat: " + e.getMessage(), e);
        }
    }

    private static ContentSigner contentSigner(SignatureAlgorithm algorithm, PrivateKey key)
            throws OperatorCreationException {
        return new JcaContentSignerBuilder(algorithm.jcaSignatureName()).setProvider(CryptoProviders.BC).build(key);
    }

    private KeyPair generateKeyPair(SignatureAlgorithm algorithm) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm.keyAlgorithm(), CryptoProviders.BC);
        if (algorithm.keySize() > 0) {
            generator.initialize(algorithm.keySize(), random);
        }
        return generator.generateKeyPair();
    }

    /** Nomor seri acak positif 127 bit (RFC 5280: unik, paling panjang 20 oktet). */
    private BigInteger serialNumber() {
        return new BigInteger(127, random).setBit(126);
    }

    private CRLDistPoint crlDistributionPoint(String caId) {
        GeneralName url = new GeneralName(GeneralName.uniformResourceIdentifier, crlBaseUrl + caId + ".crl");
        DistributionPoint point = new DistributionPoint(
                new DistributionPointName(new GeneralNames(url)), null, null);
        return new CRLDistPoint(new DistributionPoint[] {point});
    }

    private static X500Name caName(String commonName, String organization) {
        return new X500Name("CN=" + commonName + ", O=" + organization + ", C=ID");
    }

    private static X500Name subjectOf(CertificateAuthority ca) throws GeneralSecurityException {
        return new JcaX509CertificateHolder(ca.certificate()).getSubject();
    }

    private static String organizationOf(CertificateAuthority ca) throws GeneralSecurityException {
        X500Name name = subjectOf(ca);
        var rdns = name.getRDNs(org.bouncycastle.asn1.x500.style.BCStyle.O);
        return rdns.length == 0 ? "SigningPQC Uji" : rdns[0].getFirst().getValue().toString();
    }

    private static List<X509Certificate> chainOf(X509Certificate leaf, CertificateAuthority issuer) {
        List<X509Certificate> chain = new ArrayList<>();
        chain.add(leaf);
        chain.addAll(issuer.credential().chain());
        return chain;
    }
}
