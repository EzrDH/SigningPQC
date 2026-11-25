package id.pkl.signingpqc.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.cmp.PKIFailureInfo;
import org.bouncycastle.asn1.cmp.PKIStatus;
import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import id.pkl.signingpqc.core.SigningCredential;
import id.pkl.signingpqc.core.TimestampClient;
import id.pkl.signingpqc.core.TimestampException;

/** Uji TSA lokal RFC 3161 (RSA dan ML-DSA) bersama klien timestamp. */
class LocalTsaTest {

    private static final byte[] DATA = "nilai tanda tangan contoh".getBytes(StandardCharsets.UTF_8);

    private static TestPkiFactory factory;
    private static CaHierarchy rsa;
    private static CaHierarchy mldsa;
    private static LocalTsa rsaTsa;
    private static LocalTsa mldsaTsa;

    @BeforeAll
    static void setUp() throws Exception {
        factory = new TestPkiFactory();
        rsa = factory.createHierarchy(PkiFamily.RSA, "SigningPQC Uji");
        mldsa = factory.createHierarchy(PkiFamily.ML_DSA, "SigningPQC Uji");
        rsaTsa = new LocalTsa(factory.issueTsa(rsa.intermediate(), "TSA Uji RSA", TestPkiFactory.TSA_VALIDITY));
        mldsaTsa = new LocalTsa(factory.issueTsa(mldsa.intermediate(), "TSA Uji ML-DSA", TestPkiFactory.TSA_VALIDITY));
    }

    @Test
    void tsaRsaMenerbitkanTokenSah() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        TimeStampToken token = new TimestampClient(rsaTsa, LocalTsa.TEST_POLICY).timestamp(DATA, "SHA-256");
        Instant genTime = token.getTimeStampInfo().getGenTime().toInstant();
        assertFalse(genTime.isBefore(before));
        assertFalse(genTime.isAfter(Instant.now().plusSeconds(1)));
        assertEquals(LocalTsa.TEST_POLICY, token.getTimeStampInfo().getPolicy());
        assertNotNull(token.getTimeStampInfo().getNonce());
        assertEquals(1, token.getTimeStampInfo().getAccuracy().getSeconds().intValueExact());
        assertTrue(TimestampClient.imprintMatches(token, DATA));
        assertEquals(rsaTsa.certificate(), TimestampClient.verify(token));
        SignerInformation signer = token.toCMSSignedData().getSignerInfos().getSigners().iterator().next();
        assertEquals("1.2.840.113549.1.1.11", signer.getEncryptionAlgOID(), "sha256WithRSAEncryption");
        assertEquals(NISTObjectIdentifiers.id_sha256.getId(), signer.getDigestAlgOID());
        assertEquals(3, token.getCertificates().getMatches(null).size(), "rantai TSA lengkap disertakan");
    }

    @Test
    void tsaMlDsaMenerbitkanTokenSahDenganSha512() throws Exception {
        TimeStampToken token = new TimestampClient(mldsaTsa).timestamp(DATA, "SHA-512");
        assertTrue(TimestampClient.imprintMatches(token, DATA));
        assertEquals(mldsaTsa.certificate(), TimestampClient.verify(token));
        SignerInformation signer = token.toCMSSignedData().getSignerInfos().getSigners().iterator().next();
        assertEquals("2.16.840.1.101.3.4.3.18", signer.getEncryptionAlgOID(), "ML-DSA-65");
        assertEquals(NISTObjectIdentifiers.id_sha512.getId(), signer.getDigestAlgOID(), "RFC 9882: SHA-512");
        // Rantai TSA ML-DSA sah sampai root ML-DSA.
        PkixValidation.validate(mldsaTsa.credential().chain(), mldsa.root().certificate(), null);
    }

    @Test
    void nomorSeriTokenUnikDanNaik() throws Exception {
        TimestampClient client = new TimestampClient(rsaTsa);
        BigInteger first = client.timestamp(DATA, "SHA-256").getTimeStampInfo().getSerialNumber();
        BigInteger second = client.timestamp(DATA, "SHA-256").getTimeStampInfo().getSerialNumber();
        assertTrue(second.compareTo(first) > 0);
    }

    @Test
    void tokenTidakCocokUntukDataLain() throws Exception {
        TimeStampToken token = new TimestampClient(mldsaTsa).timestamp(DATA, "SHA-512");
        assertFalse(TimestampClient.imprintMatches(token, "data lain".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void algoritmaHashLemahDitolak() throws Exception {
        TimeStampRequestGenerator generator = new TimeStampRequestGenerator();
        byte[] sha1 = MessageDigest.getInstance("SHA-1").digest(DATA);
        byte[] answer = rsaTsa.handle(generator.generate(OIWObjectIdentifiers.idSHA1, sha1).getEncoded());
        TimeStampResponse response = new TimeStampResponse(answer);
        assertEquals(PKIStatus.REJECTION, response.getStatus());
        assertTrue((response.getFailInfo().intValue() & PKIFailureInfo.badAlg) != 0, "failInfo badAlg");
    }

    @Test
    void kebijakanLainDitolak() throws Exception {
        TimestampClient client = new TimestampClient(rsaTsa,
                new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.3.4.5"));
        TimestampException error = assertThrows(TimestampException.class, () -> client.timestamp(DATA, "SHA-256"));
        assertTrue(error.getMessage().contains("status 2"), error.getMessage());
    }

    @Test
    void permintaanRusakDitolakTanpaException() throws Exception {
        TimeStampResponse response = new TimeStampResponse(rsaTsa.handle(new byte[] {1, 2, 3}));
        assertEquals(PKIStatus.REJECTION, response.getStatus());
        assertTrue((response.getFailInfo().intValue() & PKIFailureInfo.badDataFormat) != 0, "failInfo badDataFormat");
    }

    @Test
    void tsaDenganSertifikatKedaluwarsaMenolakPermintaan() throws Exception {
        for (CaHierarchy hierarchy : List.of(rsa, mldsa)) {
            SigningCredential shortLived = factory.issueTsa(hierarchy.intermediate(), "TSA Uji Singkat",
                    Duration.ofHours(1));
            LocalTsa tsa = new LocalTsa(shortLived);
            assertNotNull(new TimestampClient(tsa).timestamp(DATA, "SHA-256"), "masih berlaku");

            tsa.setClock(Clock.offset(Clock.systemUTC(), Duration.ofHours(2)));
            TimestampException error = assertThrows(TimestampException.class,
                    () -> new TimestampClient(tsa).timestamp(DATA, "SHA-256"));
            assertTrue(error.getMessage().contains("Sertifikat TSA tidak berlaku"), error.getMessage());
        }
    }

    /**
     * Regresi T-011: PdfCraft menolak token yang memuat OID dengan arc lebih dari 64 bit ("OID arc too large").
     * Semua OID di token (termasuk di dalam sertifikat TSA dan TSTInfo) harus punya arc yang muat dalam 32 bit.
     */
    @Test
    void semuaOidDiTokenPunyaArcKecil() throws Exception {
        for (TimeStampToken token : List.of(new TimestampClient(rsaTsa).timestamp(DATA, "SHA-256"),
                new TimestampClient(mldsaTsa).timestamp(DATA, "SHA-512"))) {
            List<String> oids = new java.util.ArrayList<>();
            collectOids(ASN1Primitive.fromByteArray(token.getEncoded()), oids);
            assertTrue(oids.contains(LocalTsa.TEST_POLICY.getId()), "OID kebijakan ada di token");
            for (String oid : oids) {
                for (String arc : oid.split("\\.")) {
                    assertTrue(new BigInteger(arc).bitLength() <= 32, "arc terlalu besar di OID " + oid);
                }
            }
        }
    }

    /** Menelusuri struktur ASN.1, termasuk OCTET STRING yang membungkus DER (eContent TSTInfo, ekstensi). */
    private static void collectOids(ASN1Encodable node, List<String> oids) {
        ASN1Primitive primitive = node.toASN1Primitive();
        if (primitive instanceof ASN1ObjectIdentifier oid) {
            oids.add(oid.getId());
        } else if (primitive instanceof ASN1Sequence sequence) {
            sequence.forEach(child -> collectOids(child, oids));
        } else if (primitive instanceof ASN1Set set) {
            set.forEach(child -> collectOids(child, oids));
        } else if (primitive instanceof ASN1TaggedObject tagged) {
            collectOids(tagged.getBaseObject(), oids);
        } else if (primitive instanceof ASN1OctetString octets && octets.getOctets().length > 0
                && octets.getOctets()[0] == 0x30) {
            try {
                collectOids(ASN1Primitive.fromByteArray(octets.getOctets()), oids);
            } catch (Exception | StackOverflowError notDer) {
                // Bukan DER (mis. nilai hash): abaikan.
            }
        }
    }
}
