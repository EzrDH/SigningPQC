package id.pkl.signingpqc.core.ltv;

import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.Collection;

/** Sumber data validasi: rantai sertifikat sampai root dan CRL setiap penerbit dalam rantai itu. */
@FunctionalInterface
public interface RevocationSource {

    /**
     * @param leaf sertifikat penanda tangan atau TSA
     * @param pool sertifikat tambahan yang dibawa tanda tangan (boleh dipakai untuk menyusun rantai)
     */
    ValidationData collect(X509Certificate leaf, Collection<X509Certificate> pool) throws GeneralSecurityException;
}
