package id.pkl.signingpqc.server.account;

import java.io.ByteArrayOutputStream;

/** Dekoder Base32 untuk uji (aplikasi hanya butuh encode). */
final class TestBase32 {

    private TestBase32() {
    }

    static byte[] decode(String text) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : text.toUpperCase().toCharArray()) {
            if (c == '=') {
                break;
            }
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
