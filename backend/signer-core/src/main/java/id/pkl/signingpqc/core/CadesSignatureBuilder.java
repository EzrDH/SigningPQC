package id.pkl.signingpqc.core;

import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.IssuerSerial;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cms.CMSAttributeTableGenerator;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator;
import org.bouncycastle.cms.SignerInfoGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

/**
 * Membuat CMS SignedData detached bergaya CAdES (dipakai PAdES dengan SubFilter ETSI.CAdES.detached).
 *
 * <p>Atribut bertanda tangan: content-type, message-digest, signing-certificate-v2 (RFC 5035),
 * dan CMS algorithm protection (RFC 6211). Atribut signing-time sengaja dihapus karena PAdES baseline
 * memakai waktu dari kamus tanda tangan PDF (/M) dan timestamp.
 */
public final class CadesSignatureBuilder {

    private final String signatureProvider;

    /** Tanda tangan dibuat dengan provider Bouncy Castle. */
    public CadesSignatureBuilder() {
        this(CryptoProviders.BC);
    }

    /**
     * Tanda tangan dibuat dengan provider JCA tertentu, misalnya "SUN" untuk ML-DSA bawaan JDK 24+
     * (dipakai pada uji interoperabilitas). Struktur CMS tetap dibuat oleh Bouncy Castle.
     */
    public CadesSignatureBuilder(String signatureProvider) {
        CryptoProviders.ensureRegistered();
        this.signatureProvider = signatureProvider;
    }

    /**
     * Menandatangani isi yang ditunjuk ByteRange PDF dan mengembalikan CMS dalam bentuk DER.
     */
    public byte[] sign(byte[] content, SigningCredential credential) throws GeneralSecurityException {
        return sign(content, credential, null);
    }

    /**
     * Seperti {@link #sign(byte[], SigningCredential)}, dan bila tsa tidak null menambahkan signature
     * timestamp (PAdES B-T) dengan imprint memakai digest algoritma tanda tangan (SHA-256 atau SHA-512).
     */
    public byte[] sign(byte[] content, SigningCredential credential, TimestampClient tsa)
            throws GeneralSecurityException {
        try {
            DigestCalculatorProvider digestProvider = new JcaDigestCalculatorProviderBuilder()
                    .setProvider(CryptoProviders.BC)
                    .build();
            ContentSigner contentSigner = new JcaContentSignerBuilder(credential.algorithm().jcaSignatureName())
                    .setProvider(signatureProvider)
                    .build(credential.privateKey());

            Attribute signingCertificateV2 = signingCertificateV2(credential.certificate(), digestProvider);
            ASN1EncodableVector extraAttributes = new ASN1EncodableVector();
            extraAttributes.add(signingCertificateV2);
            DefaultSignedAttributeTableGenerator defaults =
                    new DefaultSignedAttributeTableGenerator(new AttributeTable(extraAttributes));
            CMSAttributeTableGenerator withoutSigningTime =
                    parameters -> defaults.getAttributes(parameters).remove(CMSAttributes.signingTime);

            SignerInfoGenerator signerInfoGenerator = new JcaSignerInfoGeneratorBuilder(digestProvider)
                    .setSignedAttributeGenerator(withoutSigningTime)
                    .setContentDigest(credential.algorithm().digestAlgorithmIdentifier())
                    .build(contentSigner, credential.certificate());

            CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
            generator.addSignerInfoGenerator(signerInfoGenerator);
            generator.addCertificates(new JcaCertStore(credential.chain()));

            CMSSignedData signedData = generator.generate(new CMSProcessableByteArray(content), false);
            if (tsa != null) {
                signedData = SignatureTimestamps.addTo(signedData, tsa, credential.algorithm().digestName());
            }
            return signedData.getEncoded("DER");
        } catch (OperatorCreationException | CMSException | IOException e) {
            throw new GeneralSecurityException("Gagal membuat CMS: " + e.getMessage(), e);
        }
    }

    private static Attribute signingCertificateV2(X509Certificate certificate,
            DigestCalculatorProvider digestProvider)
            throws OperatorCreationException, IOException, CertificateEncodingException {
        DigestCalculator sha256 = digestProvider.get(new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256));
        try (OutputStream out = sha256.getOutputStream()) {
            out.write(certificate.getEncoded());
        }
        X509CertificateHolder holder = new JcaX509CertificateHolder(certificate);
        IssuerSerial issuerSerial = new IssuerSerial(
                new GeneralNames(new GeneralName(holder.getIssuer())), holder.getSerialNumber());
        // Konstruktor tanpa AlgorithmIdentifier memakai SHA-256 sebagai nilai bawaan (RFC 5035).
        ESSCertIDv2 certId = new ESSCertIDv2(sha256.getDigest(), issuerSerial);
        SigningCertificateV2 value = new SigningCertificateV2(new ESSCertIDv2[] {certId});
        return new Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2, new DERSet(value));
    }
}
