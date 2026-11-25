package id.pkl.signingpqc.pki;

import id.pkl.signingpqc.core.SigningCredential;

/**
 * Pasangan sertifikat penanda tangan untuk skema hybrid: satu RSA (dari hierarki RSA) dan satu ML-DSA
 * (dari hierarki ML-DSA) dengan identitas (DN subjek) yang sama.
 */
public record SignerPair(SignerIdentity identity, SigningCredential rsa, SigningCredential mlDsa) {
}
