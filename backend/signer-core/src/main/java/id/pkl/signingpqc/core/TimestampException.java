package id.pkl.signingpqc.core;

import java.security.GeneralSecurityException;

/** TSA menolak permintaan atau tokennya tidak sah. */
public class TimestampException extends GeneralSecurityException {

    private static final long serialVersionUID = 1L;

    public TimestampException(String message) {
        super(message);
    }

    public TimestampException(String message, Throwable cause) {
        super(message, cause);
    }
}
