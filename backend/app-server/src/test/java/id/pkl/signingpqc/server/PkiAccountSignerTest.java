package id.pkl.signingpqc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;

import javax.security.auth.x500.X500Principal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import tools.jackson.databind.json.JsonMapper;

class PkiAccountSignerTest {

    private static final String CRL_URL = "http://127.0.0.1/api/pki/crl/";

    @TempDir
    Path dir;

    private PkiService create(boolean autoSetup) throws Exception {
        return new PkiService(dir, "uji", CRL_URL, JsonMapper.builder().build(), autoSetup, "SigningPQC Uji");
    }

    private PkiService ready() throws Exception {
        PkiService pki = create(false);
        pki.setup("SigningPQC Uji", false);
        return pki;
    }

    @Test
    void kunciAkunTidakDitulisKeDisk() throws Exception {
        PkiService pki = ready();
        PkiService.AccountSigner signer = pki.issueAccountSigner("budi.uji");
        Path folder = dir.resolve("signers").resolve(signer.record().id());
        try (var files = Files.list(folder)) {
            assertFalse(files.anyMatch(p -> p.toString().endsWith(".p12")));
        }
        assertTrue(Files.exists(folder.resolve("rsa.pem")));
        assertTrue(Files.exists(folder.resolve("mldsa.pem")));
        assertEquals(new X500Principal("CN=budi.uji,O=SigningPQC Uji,C=ID"),
                signer.pair().rsa().certificate().getSubjectX500Principal());
        assertTrue(signer.record().isAccount());
    }

    @Test
    void dimuatUlangDanBisaDicabut() throws Exception {
        String id = ready().issueAccountSigner("budi.uji").record().id();
        PkiService reloaded = create(false);
        assertTrue(reloaded.signerRecord(id).isAccount());
        List<X509Certificate> certs = reloaded.accountCertificates(id);
        assertEquals(2, certs.size());
        reloaded.revoke(id);
        assertTrue(reloaded.signerRecord(id).revoked());
        List<X509CRL> crls = reloaded.localValidationData(Instant.now().plusSeconds(1)).crls();
        assertTrue(crls.stream().anyMatch(crl -> crl.getRevokedCertificate(certs.get(0).getSerialNumber()) != null
                && crl.getIssuerX500Principal().equals(certs.get(0).getIssuerX500Principal())));
    }

    @Test
    void signerUntukAkunDitolak() throws Exception {
        PkiService pki = ready();
        String id = pki.issueAccountSigner("budi.uji").record().id();
        ApiException e = assertThrows(ApiException.class, () -> pki.signer(id));
        assertEquals(HttpStatus.CONFLICT, e.status());
    }

    @Test
    void resetMenghapusAkun() throws Exception {
        PkiService pki = ready();
        Path account = dir.resolve("accounts/x/account.json");
        Files.createDirectories(account.getParent());
        Files.writeString(account, "{}");
        pki.setup("SigningPQC Uji", true);
        assertFalse(Files.exists(dir.resolve("accounts")));
    }

    @Test
    void stateLamaTanpaFieldAccount() throws Exception {
        String id = ready().issueSigner("Budi Lama", null, null).id();
        Path state = dir.resolve("state.json");
        String text = Files.readString(state);
        String old = text.replaceAll(",\\s*\"account\"\\s*:\\s*(null|false)", "");
        assertFalse(old.contains("\"account\""), old);
        Files.writeString(state, old);
        PkiService reloaded = create(false);
        assertTrue(reloaded.ready());
        assertFalse(reloaded.signerRecord(id).isAccount());
        assertNotNull(reloaded.signer(id));
    }

    @Test
    void setupOtomatis() throws Exception {
        assertTrue(create(true).ready());
        assertFalse(new PkiService(dir.resolve("lain"), "uji", CRL_URL, JsonMapper.builder().build(), false,
                "SigningPQC Uji").ready());
    }
}
