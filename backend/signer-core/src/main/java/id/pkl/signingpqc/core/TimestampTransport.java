package id.pkl.signingpqc.core;

import java.io.IOException;

/** Mengirim TimeStampReq (DER, RFC 3161) ke TSA dan mengembalikan TimeStampResp (DER). */
@FunctionalInterface
public interface TimestampTransport {

    byte[] send(byte[] timeStampRequest) throws IOException;
}
