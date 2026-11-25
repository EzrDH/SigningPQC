package id.pkl.signingpqc.pki;

import java.security.GeneralSecurityException;
import java.time.Duration;

import id.pkl.signingpqc.core.SigningCredential;

/**
 * PKI uji berumur panjang untuk skenario LTV dan pembaruan TSA (PAdES B-LTA).
 *
 * <p>Root 30 tahun dan CA antara 25 tahun, sehingga rantai TSA masih berlaku pada waktu simulasi jauh ke depan.
 * TSA RSA generasi 1 sengaja pendek (2 tahun) agar skenario pembaruan sebelum kedaluwarsa bisa diuji. TSA RSA
 * generasi 2 berlaku 10 tahun, TSA ML-DSA 20 tahun.
 */
public record LongTermPki(CaHierarchy rsa, CaHierarchy mlDsa, SignerPair signer, SigningCredential tsaRsaGen1,
        SigningCredential tsaRsaGen2, SigningCredential tsaMlDsa) {

    public static final Duration ROOT_VALIDITY = Duration.ofDays(10950);
    public static final Duration INTERMEDIATE_VALIDITY = Duration.ofDays(9125);
    public static final Duration TSA_RSA_GEN1_VALIDITY = Duration.ofDays(730);
    public static final Duration TSA_RSA_GEN2_VALIDITY = Duration.ofDays(3650);
    public static final Duration TSA_ML_DSA_VALIDITY = Duration.ofDays(7300);

    public static LongTermPki create(TestPkiFactory factory, SignerIdentity identity) throws GeneralSecurityException {
        String organization = identity.organization();
        CaHierarchy rsa = factory.createHierarchy(PkiFamily.RSA, organization, ROOT_VALIDITY, INTERMEDIATE_VALIDITY);
        CaHierarchy mlDsa = factory.createHierarchy(PkiFamily.ML_DSA, organization, ROOT_VALIDITY,
                INTERMEDIATE_VALIDITY);
        SignerPair signer = factory.issueSignerPair(rsa, mlDsa, identity);
        SigningCredential gen1 = factory.issueTsa(rsa.intermediate(), "TSA Uji RSA G1", TSA_RSA_GEN1_VALIDITY);
        SigningCredential gen2 = factory.issueTsa(rsa.intermediate(), "TSA Uji RSA G2", TSA_RSA_GEN2_VALIDITY);
        SigningCredential pqc = factory.issueTsa(mlDsa.intermediate(), "TSA Uji ML-DSA", TSA_ML_DSA_VALIDITY);
        return new LongTermPki(rsa, mlDsa, signer, gen1, gen2, pqc);
    }
}
