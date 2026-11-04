package clima;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** GET con reintentos: las APIs públicas a veces fallan un momento. */
final class Http {

    static final HttpClient CLIENTE = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Http() {}

    static String get(String url) throws IOException, InterruptedException {
        return new String(getBytes(url), StandardCharsets.UTF_8);
    }

    static byte[] getBytes(String url) throws IOException, InterruptedException {
        IOException ultimo = null;
        for (int intento = 1; intento <= 3; intento++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "ClimaCordoba/1.0")
                        .GET().build();
                HttpResponse<byte[]> r = CLIENTE.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() == 200) return r.body();
                ultimo = new IOException("HTTP " + r.statusCode() + " en " + url);
            } catch (IOException e) {
                ultimo = e;
            }
            Thread.sleep(2000L * intento);
        }
        throw ultimo;
    }
}
