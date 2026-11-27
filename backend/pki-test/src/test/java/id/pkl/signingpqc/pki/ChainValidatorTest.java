package id.pkl.signingpqc.pki;

import static id.pkl.signingpqc.pki.LtvFixtures.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.bouncycastle.asn1.x509.CRLReason;
import org.junit.jupiter.api.Test;

import id.pkl.signingpqc.core.SigningCredential;
import id.pkl.signingpqc.core.ltv.ValidationData;
import id.pkl.signingpqc.core.validation.ChainResult;
import id.pkl.signingpqc.core.validation.ChainValidator;
import id.pkl.signingpqc.core.validation.ReasonCode;
import id.pkl.signingpqc.pki.TestPkiFactory.RevokedCertificate;

/** Uji validasi rantai sertifikat pada waktu tertentu (bukan waktu sekarang). */
class ChainValidatorTest {

    private static ValidationData data(SigningCredential credential, List<X509CRL> crls) {
        List<X509Certificate> certs = new ArrayList<>(credential.chain());
        return new ValidationData(certs, crls);
    }

    /** CRL dari CA antara dan root RSA dengan thisUpdate tertentu, opsional mencabut satu sertifikat. */
    private static List<X509CRL> rsaCrls(Instant thisUpdate, RevokedCertificate revoked) throws Exception {
        LongTermPki pki = LtvFixtures.pki();
        TestPkiFactory factory = LtvFixtures.factory();
        List<RevokedCertificate> list = revoked == null ? List.of() : List.of(revoked);
        return List.of(factory.createCrl(pki.rsa().intermediate(), list, 1, thisUpdate),
                factory.createCrl(pki.rsa().root(), List.of(), 1, thisUpdate));
    }

    private static ChainValidator validator() throws Exception {
        return new ChainValidator(LtvFixtures.trustAnchors());
    }

    @Test
    void rantaiSah() throws Exception {
        SigningCredential signer = LtvFixtures.pki().signer().rsa();
        Instant at = T0.plus(Duration.ofHours(1));
        ChainResult result = validator().validate(signer.certificate(), data(signer, rsaCrls(at, null)), at);
        assertEquals(ReasonCode.OK, result.code(), result.detail());
        assertTrue(result.valid());
        assertEquals(signer.certificate().getNotAfter().toInstant(), result.chainNotAfter());
    }

    @Test
    void dicabutSebelumWaktuPemeriksaan() throws Exception {
        SigningCredential signer = LtvFixtures.pki().signer().rsa();
        RevokedCertificate revoked = new RevokedCertificate(signer.certificate().getSerialNumber(),
                T0.plus(Duration.ofDays(1)), CRLReason.keyCompromise);
        List<X509CRL> crls = rsaCrls(T0.plus(Duration.ofDays(2)), revoked);
        ChainResult result = validator().validate(signer.certificate(), data(signer, crls), T0.plus(Duration.ofDays(3)));
        assertEquals(ReasonCode.SERTIFIKAT_DICABUT, result.code(), result.detail());
        assertFalse(result.valid());
    }

    @Test
    void dicabutSesudahnyaTetapSahDenganPeringatan() throws Exception {
        SigningCredential signer = LtvFixtures.pki().signer().rsa();
        RevokedCertificate revoked = new RevokedCertificate(signer.certificate().getSerialNumber(),
                T0.plus(Duration.ofDays(1)), CRLReason.keyCompromise);
        List<X509CRL> crls = rsaCrls(T0.plus(Duration.ofDays(2)), revoked);
        ChainResult result = validator().validate(signer.certificate(), data(signer, crls), T0.plus(Duration.ofHours(12)));
        assertEquals(ReasonCode.DICABUT_SETELAH_DITANDATANGANI, result.code(), result.detail());
        assertTrue(result.valid());
        assertEquals(T0.plus(Duration.ofDays(1)), result.revokedAt(), "waktu pencabutan tetap dicatat");
    }

    @Test
    void sertifikatKedaluwarsaPadaWaktuItu() throws Exception {
        SigningCredential gen1 = LtvFixtures.pki().tsaRsaGen1();
        Instant at = T0.plus(Duration.ofDays(800));
        ChainResult result = validator().validate(gen1.certificate(), data(gen1, rsaCrls(at, null)), at);
        assertEquals(ReasonCode.SERTIFIKAT_TIDAK_BERLAKU, result.code(), result.detail());
    }

    @Test
    void tanpaCrl() throws Exception {
        SigningCredential signer = LtvFixtures.pki().signer().rsa();
        Instant at = T0.plus(Duration.ofHours(1));
        ChainResult result = validator().validate(signer.certificate(), data(signer, List.of()), at);
        assertEquals(ReasonCode.STATUS_PENCABUTAN_TIDAK_DIKETAHUI, result.code(), result.detail());
    }

    @Test
    void rootLainDitolak() throws Exception {
        LongTermPki pki = LtvFixtures.pki();
        SigningCredential signer = pki.signer().rsa();
        Instant at = T0.plus(Duration.ofHours(1));
        ChainValidator onlyMlDsa = new ChainValidator(Set.of(pki.mlDsa().root().certificate()));
        ChainResult result = onlyMlDsa.validate(signer.certificate(), data(signer, rsaCrls(at, null)), at);
        assertEquals(ReasonCode.ROOT_TIDAK_DIPERCAYA, result.code(), result.detail());
    }

    @Test
    void crlLamaTidakBerlakuDiTerima() throws Exception {
        SigningCredential signer = LtvFixtures.pki().signer().rsa();
        Instant at = T0.plus(Duration.ofDays(60));
        List<X509CRL> crls = rsaCrls(at.minus(Duration.ofDays(30)), null);
        ChainResult result = validator().validate(signer.certificate(), data(signer, crls), at);
        assertEquals(ReasonCode.STATUS_PENCABUTAN_TIDAK_DIKETAHUI, result.code(), result.detail());
    }
}
