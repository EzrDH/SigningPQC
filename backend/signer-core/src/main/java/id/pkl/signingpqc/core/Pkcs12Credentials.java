package id.pkl.signingpqc.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Menyimpan dan membaca kredensial penanda tangan (kunci privat + rantai sertifikat) dalam PKCS#12.
 *
 * <p>Memakai implementasi PKCS#12 Bouncy Castle agar kunci ML-DSA ikut tersimpan. Kata sandi wajib
 * diisi; berkas .p12 tidak boleh di-commit ke repositori.
 */
public final class Pkcs12Credentials {

    /** Format baca: PKCS#12 apa pun yang dikenali Bouncy Castle. */
    private static final String KEYSTORE_TYPE = "PKCS12";
    /**
     * Format tulis: kunci dan sertifikat dienkripsi PBES2/AES-256 (bukan RC2-40 bawaan lama), sehingga
     * dapat dibuka OpenSSL 3 tanpa provider "legacy".
     */
    private static final String KEYSTORE_WRITE_TYPE = "PKCS12-AES256-AES128";

    private Pkcs12Credentials() {
    }

    public static byte[] write(SigningCredential credential, String alias, char[] password)
            throws GeneralSecurityException {
        requirePassword(password);
        CryptoProviders.ensureRegistered();
        try {
            KeyStore store = KeyStore.getInstance(KEYSTORE_WRITE_TYPE, CryptoProviders.BC);
            store.load(null, null);
            store.setKeyEntry(alias, credential.privateKey(), password,
                    credential.chain().toArray(new Certificate[0]));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            store.store(out, password);
            return out.toByteArray();
        } catch (IOException e) {
            throw new GeneralSecurityException("Gagal menulis PKCS#12: " + e.getMessage(), e);
        }
    }

    /** Membaca entri kunci pertama di dalam berkas PKCS#12. */
    public static SigningCredential read(byte[] pkcs12, char[] password) throws GeneralSecurityException {
        return read(pkcs12, null, password);
    }

    /** Membaca entri kunci dengan alias tertentu (atau entri kunci pertama bila alias null). */
    public static SigningCredential read(byte[] pkcs12, String alias, char[] password)
            throws GeneralSecurityException {
        requirePassword(password);
        CryptoProviders.ensureRegistered();
        try {
            KeyStore store = KeyStore.getInstance(KEYSTORE_TYPE, CryptoProviders.BC);
            store.load(new ByteArrayInputStream(pkcs12), password);
            String chosen = alias != null ? alias : firstKeyAlias(store);
            Key key = store.getKey(chosen, password);
            if (!(key instanceof PrivateKey privateKey)) {
                throw new GeneralSecurityException("Entri '" + chosen + "' tidak berisi kunci privat");
            }
            Certificate[] chain = store.getCertificateChain(chosen);
            if (chain == null || chain.length == 0) {
                throw new GeneralSecurityException("Entri '" + chosen + "' tidak berisi rantai sertifikat");
            }
            List<X509Certificate> certificates = new ArrayList<>();
            for (Certificate certificate : chain) {
                certificates.add((X509Certificate) certificate);
            }
            SignatureAlgorithm algorithm = SignatureAlgorithm.fromCertificate(certificates.get(0));
            return new SigningCredential(algorithm, privateKey, List.copyOf(certificates));
        } catch (IOException e) {
            throw new GeneralSecurityException("Gagal membaca PKCS#12 (kata sandi salah atau berkas rusak): "
                    + e.getMessage(), e);
        }
    }

    private static String firstKeyAlias(KeyStore store) throws GeneralSecurityException {
        for (String alias : Collections.list(store.aliases())) {
            if (store.isKeyEntry(alias)) {
                return alias;
            }
        }
        throw new GeneralSecurityException("Berkas PKCS#12 tidak berisi entri kunci");
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Kata sandi PKCS#12 wajib diisi");
        }
    }
}
