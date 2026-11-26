package id.pkl.signingpqc.server.account;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Klien HTTP kecil untuk uji REST akun (token Bearer opsional). */
class ApiClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final String base;

    ApiClient(int port) {
        this.base = "http://127.0.0.1:" + port;
    }

    private HttpRequest.Builder request(String path, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(request(path, token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<byte[]> getBytes(String path, String token) throws Exception {
        return http.send(request(path, token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    HttpResponse<String> postJson(String path, String token, String body) throws Exception {
        return http.send(request(path, token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> putJson(String path, String token, String body) throws Exception {
        return http.send(request(path, token).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Multipart dengan satu berkas {@code file} dan field teks berpasangan (nama, nilai, ...). */
    HttpResponse<byte[]> multipart(String method, String path, String token, byte[] file, String fileName,
            String contentType, List<String> fields) throws Exception {
        String boundary = "----uji" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int i = 0; i < fields.size(); i += 2) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fields.get(i)
                    + "\"\r\n\r\n" + fields.get(i + 1) + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(file);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(request(path, token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
