package id.pkl.signingpqc.pki;

/** Hierarki dua tingkat CA: Root dan CA antara (penerbit sertifikat penanda tangan dan TSA). */
public record CaHierarchy(PkiFamily family, CertificateAuthority root, CertificateAuthority intermediate) {
}
