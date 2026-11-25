package id.pkl.signingpqc.core;

/** Keterangan yang dicatat di kamus tanda tangan PDF. */
public record SignatureDetails(String signerName, String reason, String location) {

    public static SignatureDetails of(String signerName, String reason) {
        return new SignatureDetails(signerName, reason, "Jakarta");
    }
}
