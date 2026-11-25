package id.pkl.signingpqc.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Uji PKI jangka panjang dan TSA bergenerasi untuk skenario LTV. */
class LongTermPkiTest {

    private static final Instant NOW = Instant.parse("2026-11-25T08:00:00Z");
    private static final SignerIdentity IDENTITY = new SignerIdentity("Budi Penanda Tangan Uji", "Kepala Seksi Uji",
            "Unit Uji", "SigningPQC Uji");

    private static TestPkiFactory factory;
    private static LongTermPki pki;

    @BeforeAll
    static void setUp() throws Exception {
        factory = new TestPkiFactory(TestPkiFactory.DEFAULT_CRL_BASE_URL, Clock.fixed(NOW, ZoneOffset.UTC));
        pki = LongTermPki.create(factory, IDENTITY);
    }

    private static void assertNotAfter(X509Certificate cert, Duration expected) {
        Duration actual = Duration.between(NOW, cert.getNotAfter().toInstant());
        assertTrue(actual.minus(expected).abs().compareTo(Duration.ofDays(1)) <= 0,
                cert.getSubjectX500Principal() + ": " + actual.toDays() + " hari, harapan " + expected.toDays());
    }

    @Test
    void masaBerlakuSesuaiProfil() {
        assertNotAfter(pki.tsaRsaGen1().certificate(), Duration.ofDays(730));
        assertNotAfter(pki.tsaRsaGen2().certificate(), Duration.ofDays(3650));
        assertNotAfter(pki.tsaMlDsa().certificate(), Duration.ofDays(7300));
        assertNotAfter(pki.mlDsa().intermediate().certificate(), Duration.ofDays(9125));
        assertNotAfter(pki.rsa().root().certificate(), Duration.ofDays(10950));
        assertNotAfter(pki.signer().rsa().certificate(), Duration.ofDays(730));
        assertTrue(pki.tsaRsaGen1().certificate().getSubjectX500Principal().getName().contains("CN=TSA Uji RSA G1"));
        assertTrue(pki.tsaRsaGen2().certificate().getSubjectX500Principal().getName().contains("CN=TSA Uji RSA G2"));
        assertTrue(pki.tsaMlDsa().certificate().getSubjectX500Principal().getName().contains("CN=TSA Uji ML-DSA"));
        assertEquals(NOW.minus(Duration.ofMinutes(5)), pki.tsaRsaGen1().certificate().getNotBefore().toInstant());
    }

    @Test
    void crlDenganThisUpdateTertentu() throws Exception {
        Instant thisUpdate = Instant.parse("2030-01-01T00:00:00Z");
        X509CRL crl = factory.createCrl(pki.rsa().intermediate(), List.of(), 7, thisUpdate);
        assertEquals(thisUpdate, crl.getThisUpdate().toInstant());
        assertEquals(thisUpdate.plus(TestPkiFactory.CRL_VALIDITY), crl.getNextUpdate().toInstant());
        crl.verify(pki.rsa().intermediate().certificate().getPublicKey());
    }
}
