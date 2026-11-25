package id.pkl.signingpqc.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.Xof;
import org.bouncycastle.crypto.digests.SHA224Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA384Digest;
import org.bouncycastle.crypto.digests.SHA3Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.digests.SHA512tDigest;
import org.bouncycastle.crypto.digests.SHAKEDigest;
import org.bouncycastle.crypto.params.MLDSAParameters;
import org.bouncycastle.crypto.params.MLDSAPrivateKeyParameters;
import org.bouncycastle.crypto.params.MLDSAPublicKeyParameters;
import org.bouncycastle.crypto.params.ParametersWithContext;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.crypto.signers.MLDSASigner;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Uji kesesuaian ML-DSA (FIPS 204) di Bouncy Castle dengan vektor NIST ACVP (vsId 42, revision FIPS204):
 * keyGen, sigGen, dan sigVer untuk ML-DSA-44/65/87, semua grup.
 *
 * <p>Jalur yang dipakai:
 * <ul>
 *   <li>external + pure: API {@link MLDSASigner} biasa (update + generateSignature) dengan context, yaitu jalur yang
 *       dipakai aplikasi untuk CMS (context kosong).</li>
 *   <li>external + preHash, internal, dan externalMu: mu = SHAKE256(tr || M', 64) dihitung sesuai FIPS 204
 *       Algoritma 2, 4, dan 7, lalu ditandatangani/diverifikasi dengan generateMuSignature/verifyMuSignature.</li>
 * </ul>
 */
class AcvpMlDsaTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, MLDSAParameters> PARAMETERS = Map.of(
            "ML-DSA-44", MLDSAParameters.ml_dsa_44,
            "ML-DSA-65", MLDSAParameters.ml_dsa_65,
            "ML-DSA-87", MLDSAParameters.ml_dsa_87);

    private static final Map<String, ASN1ObjectIdentifier> PRIVATE_KEY_OID = Map.of(
            "ML-DSA-44", NISTObjectIdentifiers.id_ml_dsa_44,
            "ML-DSA-65", NISTObjectIdentifiers.id_ml_dsa_65,
            "ML-DSA-87", NISTObjectIdentifiers.id_ml_dsa_87);

    @Test
    void keyGen() throws Exception {
        List<String> summary = new ArrayList<>();
        for (JsonNode group : groups("ML-DSA-keyGen-FIPS204.json.gz")) {
            MLDSAParameters parameters = PARAMETERS.get(group.get("parameterSet").asString());
            int passed = 0;
            for (JsonNode test : group.get("tests")) {
                MLDSAPrivateKeyParameters key = new MLDSAPrivateKeyParameters(parameters, hex(test, "seed"));
                assertArrayEquals(hex(test, "pk"), key.getPublicKeyParameters().getEncoded(), id(group, test));
                assertArrayEquals(hex(test, "sk"),
                        key.withPreferredFormat(MLDSAPrivateKeyParameters.EXPANDED_KEY).getEncoded(), id(group, test));
                passed++;
            }
            summary.add(groupLabel(group) + " " + passed + "/" + group.get("tests").size());
        }
        print("keyGen", summary);
    }

    @Test
    void sigGen() throws Exception {
        List<String> summary = new ArrayList<>();
        for (JsonNode group : groups("ML-DSA-sigGen-FIPS204.json.gz")) {
            String set = group.get("parameterSet").asString();
            boolean deterministic = group.get("deterministic").asBoolean();
            int passed = 0;
            for (JsonNode test : group.get("tests")) {
                MLDSAPrivateKeyParameters sk = privateKey(set, hex(test, "sk"));
                byte[] rnd = deterministic ? new byte[32] : hex(test, "rnd");
                byte[] signature;
                if (isExternalPure(group)) {
                    MLDSASigner signer = new MLDSASigner();
                    signer.init(true, new ParametersWithContext(
                            new ParametersWithRandom(sk, new FixedRandom(rnd)), hex(test, "context")));
                    byte[] message = hex(test, "message");
                    signer.update(message, 0, message.length);
                    signature = signer.generateSignature();
                } else {
                    MLDSASigner signer = new MLDSASigner();
                    signer.init(true, new ParametersWithRandom(sk, new FixedRandom(rnd)));
                    signature = signer.generateMuSignature(mu(group, test, hex(test, "pk")));
                }
                assertArrayEquals(hex(test, "signature"), signature, id(group, test));
                passed++;
            }
            summary.add(groupLabel(group) + (deterministic ? " deterministik " : " acak ") + passed + "/"
                    + group.get("tests").size());
        }
        print("sigGen", summary);
    }

    @Test
    void sigVer() throws Exception {
        List<String> summary = new ArrayList<>();
        int rejected = 0;
        for (JsonNode group : groups("ML-DSA-sigVer-FIPS204.json.gz")) {
            MLDSAParameters parameters = PARAMETERS.get(group.get("parameterSet").asString());
            int passed = 0;
            for (JsonNode test : group.get("tests")) {
                MLDSAPublicKeyParameters pk = new MLDSAPublicKeyParameters(parameters, hex(test, "pk"));
                byte[] signature = hex(test, "signature");
                boolean result;
                if (isExternalPure(group)) {
                    MLDSASigner verifier = new MLDSASigner();
                    verifier.init(false, new ParametersWithContext(pk, hex(test, "context")));
                    byte[] message = hex(test, "message");
                    verifier.update(message, 0, message.length);
                    result = verifier.verifySignature(signature);
                } else {
                    MLDSASigner verifier = new MLDSASigner();
                    verifier.init(false, pk);
                    result = verifier.verifyMuSignature(mu(group, test, hex(test, "pk")), signature);
                }
                boolean expected = test.get("testPassed").asBoolean();
                assertEquals(expected, result, id(group, test) + " alasan: " + test.get("reason").asString());
                if (!expected) {
                    rejected++;
                }
                passed++;
            }
            summary.add(groupLabel(group) + " " + passed + "/" + group.get("tests").size());
        }
        summary.add("termasuk " + rejected + " tanda tangan rusak yang benar ditolak");
        print("sigVer", summary);
    }

    // ---------------------------------------------------------------------------------------------

    private static boolean isExternalPure(JsonNode group) {
        return "external".equals(group.get("signatureInterface").asString())
                && "pure".equals(group.get("preHash").asString());
    }

    /** mu = SHAKE256(tr || M', 64) dengan tr = SHAKE256(pk, 64) (FIPS 204 Algoritma 7 baris 6). */
    private static byte[] mu(JsonNode group, JsonNode test, byte[] pk) {
        if (group.has("externalMu") && group.get("externalMu").asBoolean()) {
            return hex(test, "mu");
        }
        byte[] messagePrime;
        if ("internal".equals(group.get("signatureInterface").asString())) {
            messagePrime = hex(test, "message");
        } else {
            // External preHash (Algoritma 4): M' = 1 || |ctx| || ctx || OID(PH) || PH(M).
            byte[] context = hex(test, "context");
            String hashAlg = test.get("hashAlg").asString();
            byte[] oid = encodedOid(hashAlg);
            byte[] digest = preHash(hashAlg, hex(test, "message"));
            messagePrime = concat(new byte[] {1, (byte) context.length}, context, oid, digest);
        }
        byte[] tr = shake256(pk, 64);
        return shake256(concat(tr, messagePrime), 64);
    }

    private static byte[] encodedOid(String hashAlg) {
        ASN1ObjectIdentifier oid = switch (hashAlg) {
            case "SHA2-224" -> NISTObjectIdentifiers.id_sha224;
            case "SHA2-256" -> NISTObjectIdentifiers.id_sha256;
            case "SHA2-384" -> NISTObjectIdentifiers.id_sha384;
            case "SHA2-512" -> NISTObjectIdentifiers.id_sha512;
            case "SHA2-512/224" -> NISTObjectIdentifiers.id_sha512_224;
            case "SHA2-512/256" -> NISTObjectIdentifiers.id_sha512_256;
            case "SHA3-224" -> NISTObjectIdentifiers.id_sha3_224;
            case "SHA3-256" -> NISTObjectIdentifiers.id_sha3_256;
            case "SHA3-384" -> NISTObjectIdentifiers.id_sha3_384;
            case "SHA3-512" -> NISTObjectIdentifiers.id_sha3_512;
            case "SHAKE-128" -> NISTObjectIdentifiers.id_shake128;
            case "SHAKE-256" -> NISTObjectIdentifiers.id_shake256;
            default -> throw new IllegalArgumentException("hashAlg tidak dikenal: " + hashAlg);
        };
        try {
            return oid.getEncoded();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] preHash(String hashAlg, byte[] message) {
        if (hashAlg.startsWith("SHAKE")) {
            // FIPS 204 bagian 5.4: SHAKE128 keluaran 256 bit, SHAKE256 keluaran 512 bit.
            int bits = hashAlg.equals("SHAKE-128") ? 128 : 256;
            SHAKEDigest shake = new SHAKEDigest(bits);
            shake.update(message, 0, message.length);
            byte[] out = new byte[bits == 128 ? 32 : 64];
            shake.doFinal(out, 0, out.length);
            return out;
        }
        Digest digest = switch (hashAlg) {
            case "SHA2-224" -> new SHA224Digest();
            case "SHA2-256" -> new SHA256Digest();
            case "SHA2-384" -> new SHA384Digest();
            case "SHA2-512" -> new SHA512Digest();
            case "SHA2-512/224" -> new SHA512tDigest(224);
            case "SHA2-512/256" -> new SHA512tDigest(256);
            case "SHA3-224" -> new SHA3Digest(224);
            case "SHA3-256" -> new SHA3Digest(256);
            case "SHA3-384" -> new SHA3Digest(384);
            case "SHA3-512" -> new SHA3Digest(512);
            default -> throw new IllegalArgumentException("hashAlg tidak dikenal: " + hashAlg);
        };
        digest.update(message, 0, message.length);
        byte[] out = new byte[digest.getDigestSize()];
        digest.doFinal(out, 0);
        return out;
    }

    private static byte[] shake256(byte[] input, int length) {
        Xof shake = new SHAKEDigest(256);
        shake.update(input, 0, input.length);
        byte[] out = new byte[length];
        shake.doFinal(out, 0, length);
        return out;
    }

    /** Kunci privat dari encoding expanded FIPS 204 (sk), lewat PrivateKeyInfo RFC 9881 (pilihan expandedKey). */
    private static MLDSAPrivateKeyParameters privateKey(String set, byte[] sk) throws IOException {
        PrivateKeyInfo info = new PrivateKeyInfo(new AlgorithmIdentifier(PRIVATE_KEY_OID.get(set)),
                new DEROctetString(sk));
        return (MLDSAPrivateKeyParameters) PrivateKeyFactory.createKey(info);
    }

    private static List<JsonNode> groups(String resource) throws IOException {
        try (InputStream in = new GZIPInputStream(AcvpMlDsaTest.class.getResourceAsStream("/acvp-mldsa/" + resource))) {
            JsonNode root = JSON.readTree(in);
            assertEquals("FIPS204", root.get("revision").asString());
            List<JsonNode> groups = new ArrayList<>();
            root.get("testGroups").forEach(groups::add);
            assertTrue(!groups.isEmpty());
            return groups;
        }
    }

    private static byte[] hex(JsonNode test, String field) {
        JsonNode node = test.get(field);
        return node == null || node.isNull() ? new byte[0] : HEX.parseHex(node.asString());
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    private static String groupLabel(JsonNode group) {
        StringBuilder label = new StringBuilder("tg" + group.get("tgId").asInt() + " " + group.get("parameterSet").asString());
        if (group.has("signatureInterface")) {
            label.append(' ').append(group.get("signatureInterface").asString());
        }
        if (group.has("preHash")) {
            label.append(' ').append(group.get("preHash").asString());
        }
        if (group.has("externalMu") && group.get("externalMu").asBoolean()) {
            label.append(" externalMu");
        }
        return label.toString();
    }

    private static String id(JsonNode group, JsonNode test) {
        return groupLabel(group) + " tcId " + test.get("tcId").asInt();
    }

    private static void print(String mode, List<String> summary) {
        System.out.println("ACVP ML-DSA " + mode + ":");
        summary.forEach(line -> System.out.println("  " + line));
    }

    /** SecureRandom yang selalu mengembalikan nilai rnd dari vektor uji (32 byte). */
    private static final class FixedRandom extends SecureRandom {
        private static final long serialVersionUID = 1L;
        private final byte[] value;

        FixedRandom(byte[] value) {
            this.value = value.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            System.arraycopy(value, 0, bytes, 0, Math.min(value.length, bytes.length));
        }
    }
}
