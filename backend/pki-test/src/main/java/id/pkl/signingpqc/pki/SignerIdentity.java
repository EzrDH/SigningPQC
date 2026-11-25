package id.pkl.signingpqc.pki;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;

/**
 * Identitas penanda tangan uji. Nama dan instansi harus sintetis, bukan data orang sungguhan.
 */
public record SignerIdentity(String commonName, String title, String organizationalUnit, String organization) {

    public X500Name toX500Name() {
        X500NameBuilder builder = new X500NameBuilder(BCStyle.INSTANCE);
        builder.addRDN(BCStyle.C, "ID");
        builder.addRDN(BCStyle.O, organization);
        if (organizationalUnit != null && !organizationalUnit.isBlank()) {
            builder.addRDN(BCStyle.OU, organizationalUnit);
        }
        if (title != null && !title.isBlank()) {
            builder.addRDN(BCStyle.T, title);
        }
        builder.addRDN(BCStyle.CN, commonName);
        return builder.build();
    }
}
