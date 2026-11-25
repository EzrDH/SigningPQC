package id.pkl.signingpqc.pki;

import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import id.pkl.signingpqc.core.PadesSigner;
import id.pkl.signingpqc.core.SamplePdf;
import id.pkl.signingpqc.core.SignatureDetails;
import id.pkl.signingpqc.core.TimestampClient;

/** Perlengkapan uji bersama untuk lapisan LTV: PKI jangka panjang dan PDF hybrid B-T. */
final class LtvFixtures {

    static final Instant T0 = Instant.parse("2026-11-25T08:00:00Z");
    static final SignerIdentity IDENTITY = new SignerIdentity("Budi Penanda Tangan Uji", "Kepala Seksi Uji",
            "Unit Uji", "SigningPQC Uji");

    private static LongTermPki shared;
    private static TestPkiFactory sharedFactory;

    private LtvFixtures() {
    }

    /** PKI jangka panjang yang dibuat pada {@link #T0} (dibagi antar uji karena pembuatan kunci lambat). */
    static synchronized LongTermPki pki() throws Exception {
        if (shared == null) {
            sharedFactory = new TestPkiFactory(TestPkiFactory.DEFAULT_CRL_BASE_URL, Clock.fixed(T0, ZoneOffset.UTC));
            shared = LongTermPki.create(sharedFactory, IDENTITY);
        }
        return shared;
    }

    static synchronized TestPkiFactory factory() throws Exception {
        pki();
        return sharedFactory;
    }

    static Clock at(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    static TimestampClient tsa(id.pkl.signingpqc.core.SigningCredential credential, Instant at) throws Exception {
        return new TimestampClient(new LocalTsa(credential, at(at)));
    }

    /** PDF hybrid B-T: RSA (TSA RSA G1) lalu ML-DSA (TSA ML-DSA), keduanya dicap pada {@link #T0}. */
    static byte[] hybridBt() throws Exception {
        LongTermPki pki = pki();
        PadesSigner signer = new PadesSigner();
        byte[] pdf = SamplePdf.create("Ijazah Uji", "Dokumen sintetis untuk uji LTV.");
        byte[] signed = signer.signInvisible(pdf, pki.signer().rsa(),
                SignatureDetails.of(IDENTITY.commonName(), "RSA"), tsa(pki.tsaRsaGen1(), T0));
        return signer.signInvisible(signed, pki.signer().mlDsa(),
                SignatureDetails.of(IDENTITY.commonName(), "ML-DSA"), tsa(pki.tsaMlDsa(), T0));
    }

    /** Semua sertifikat CA (root dan antara) dari kedua hierarki. */
    static List<CertificateAuthority> cas() throws Exception {
        LongTermPki pki = pki();
        List<CertificateAuthority> cas = new ArrayList<>();
        cas.add(pki.rsa().root());
        cas.add(pki.rsa().intermediate());
        cas.add(pki.mlDsa().root());
        cas.add(pki.mlDsa().intermediate());
        return cas;
    }

    static java.util.Set<X509Certificate> trustAnchors() throws Exception {
        LongTermPki pki = pki();
        return java.util.Set.of(pki.rsa().root().certificate(), pki.mlDsa().root().certificate());
    }
}
