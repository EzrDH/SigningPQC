package id.pkl.signingpqc.server;

import java.util.Map;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import id.pkl.signingpqc.core.CryptoProviders;

/** Endpoint pemeriksaan sederhana untuk memastikan frontend terhubung ke backend. */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        CryptoProviders.ensureRegistered();
        return Map.of(
                "status", "ok",
                "java", System.getProperty("java.version"),
                "bouncyCastle", new BouncyCastleProvider().getVersionStr(),
                "pdfbox", org.apache.pdfbox.util.Version.getVersion());
    }
}
