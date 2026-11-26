package id.pkl.signingpqc.server;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import id.pkl.signingpqc.core.SignatureAlgorithm;
import id.pkl.signingpqc.pki.CertificateAuthority;
import id.pkl.signingpqc.pki.PkiFamily;

/** REST PKI uji: membuat CA dan TSA, menerbitkan dan mencabut penanda tangan, CRL, dan sertifikat CA. */
@RestController
@RequestMapping("/api/pki")
public class PkiController {

    public record SetupRequest(String organization) {
    }

    public record SignerRequest(String commonName, String title, String unit) {
    }

    public record CertificateInfo(String subject, String issuer, String algorithm, String serialHex, Instant notBefore,
            Instant notAfter) {

        static CertificateInfo of(X509Certificate cert) {
            return new CertificateInfo(name(cert.getSubjectX500Principal()), name(cert.getIssuerX500Principal()),
                    SignatureAlgorithm.fromCertificate(cert).name(), cert.getSerialNumber().toString(16),
                    cert.getNotBefore().toInstant(), cert.getNotAfter().toInstant());
        }

        /** DN dengan nama atribut yang mudah dibaca (mis. T= untuk jabatan), sama seperti hasil verifikasi. */
        private static String name(javax.security.auth.x500.X500Principal principal) {
            return org.bouncycastle.asn1.x500.X500Name.getInstance(org.bouncycastle.asn1.x500.style.BCStyle.INSTANCE,
                    principal.getEncoded()).toString();
        }
    }

    public record CaInfo(String id, String family, CertificateInfo certificate) {
    }

    public record SignerInfo(String id, String commonName, String title, String unit, boolean revoked,
            CertificateInfo rsa, CertificateInfo mlDsa) {
    }

    public record Status(boolean ready, String organization, Instant createdAt, String dataDir, List<CaInfo> cas,
            List<CaInfo> tsas, List<SignerInfo> signers) {
    }

    private final PkiService pki;

    public PkiController(PkiService pki) {
        this.pki = pki;
    }

    @GetMapping
    public Status status() {
        if (!pki.ready()) {
            return new Status(false, null, null, pki.dataDir().toAbsolutePath().toString(), List.of(), List.of(),
                    List.of());
        }
        List<CaInfo> cas = new ArrayList<>();
        for (CertificateAuthority ca : pki.cas().values().stream()
                .sorted(java.util.Comparator.comparing(CertificateAuthority::id)).toList()) {
            cas.add(new CaInfo(ca.id(), ca.family().label(), CertificateInfo.of(ca.certificate())));
        }
        List<CaInfo> tsas = new ArrayList<>();
        for (String id : pki.tsaIds()) {
            PkiFamily family = id.startsWith("rsa") ? PkiFamily.RSA : PkiFamily.ML_DSA;
            tsas.add(new CaInfo("tsa-" + id, family.label(), CertificateInfo.of(pki.tsa(id).certificate())));
        }
        PkiService.State state = pki.state();
        return new Status(true, state.organization(), state.createdAt(), pki.dataDir().toAbsolutePath().toString(),
                cas, tsas, signers());
    }

    @PostMapping("/setup")
    public ResponseEntity<Status> setup(@RequestBody SetupRequest request,
            @RequestParam(defaultValue = "false") boolean reset) throws GeneralSecurityException, IOException {
        pki.setup(request.organization(), reset);
        return ResponseEntity.status(HttpStatus.CREATED).body(status());
    }

    @GetMapping("/signers")
    public List<SignerInfo> signers() {
        return pki.signerRecords().stream().map(this::info).toList();
    }

    @PostMapping("/signers")
    public ResponseEntity<SignerInfo> issue(@RequestBody SignerRequest request)
            throws GeneralSecurityException, IOException {
        PkiService.SignerRecord record = pki.issueSigner(request.commonName(), request.title(), request.unit());
        return ResponseEntity.status(HttpStatus.CREATED).body(info(record));
    }

    @PostMapping("/signers/{id}/revoke")
    public SignerInfo revoke(@PathVariable String id) throws IOException {
        return info(pki.revoke(id));
    }

    @GetMapping(value = "/crl/{caId}.crl", produces = "application/pkix-crl")
    public byte[] crl(@PathVariable String caId) throws GeneralSecurityException, IOException {
        return pki.crl(caId).getEncoded();
    }

    @GetMapping(value = "/ca/{caId}.crt", produces = "application/pkix-cert")
    public byte[] caCertificate(@PathVariable String caId) throws GeneralSecurityException {
        return pki.caCertificate(caId).getEncoded();
    }

    @GetMapping(value = "/ca/{caId}.pem", produces = MediaType.TEXT_PLAIN_VALUE)
    public String caPem(@PathVariable String caId) throws GeneralSecurityException {
        return id.pkl.signingpqc.core.Pem.certificate(pki.caCertificate(caId));
    }

    private SignerInfo info(PkiService.SignerRecord record) {
        java.util.List<java.security.cert.X509Certificate> certificates = pki.signerCertificates(record.id());
        return new SignerInfo(record.id(), record.commonName(), record.title(), record.unit(), record.revoked(),
                CertificateInfo.of(certificates.get(0)), CertificateInfo.of(certificates.get(1)));
    }

    static Map<String, String> message(String text) {
        return Map.of("message", text);
    }
}
