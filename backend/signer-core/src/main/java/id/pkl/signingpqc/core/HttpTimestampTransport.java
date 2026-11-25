package id.pkl.signingpqc.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Transport RFC 3161 melalui HTTP POST (application/timestamp-query, RFC 3161 bagian 3.4). */
public final class HttpTimestampTransport implements TimestampTransport {

    public static final String REQUEST_TYPE = "application/timestamp-query";
    public static final String RESPONSE_TYPE = "application/timestamp-reply";

    private final URI endpoint;
    private final HttpClient client;
    private final Duration timeout;

    public HttpTimestampTransport(URI endpoint) {
        this(endpoint, Duration.ofSeconds(10));
    }

    public HttpTimestampTransport(URI endpoint, Duration timeout) {
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public byte[] send(byte[] timeStampRequest) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", REQUEST_TYPE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(timeStampRequest))
                .build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("TSA " + endpoint + " membalas HTTP " + response.statusCode());
            }
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (!type.startsWith(RESPONSE_TYPE)) {
                throw new IOException("TSA " + endpoint + " membalas Content-Type tidak dikenal: " + type);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Permintaan ke TSA dibatalkan", e);
        }
    }
}
