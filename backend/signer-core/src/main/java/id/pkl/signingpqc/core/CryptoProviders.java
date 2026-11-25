package id.pkl.signingpqc.core;

import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Mendaftarkan provider Bouncy Castle satu kali untuk seluruh aplikasi. */
public final class CryptoProviders {

    public static final String BC = BouncyCastleProvider.PROVIDER_NAME;

    private CryptoProviders() {
    }

    public static synchronized void ensureRegistered() {
        if (Security.getProvider(BC) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }
}
