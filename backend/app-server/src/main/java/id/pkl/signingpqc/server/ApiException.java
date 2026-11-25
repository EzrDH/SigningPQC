package id.pkl.signingpqc.server;

import org.springframework.http.HttpStatus;

/** Kesalahan yang dikembalikan ke klien sebagai JSON {"error": "..."} dengan status HTTP tertentu. */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }
}
