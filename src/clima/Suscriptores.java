package clima;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Lista de dispositivos que activaron los avisos, guardada en el Worker de Cloudflare (worker/). */
final class Suscriptores {

    private final String url;
    private final String token;

    private Suscriptores(String url, String token) {
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.token = token;
    }

    /** null si no está configurado el Worker (SUSCRIPCIONES_URL / SUSCRIPCIONES_TOKEN). */
    static Suscriptores desdeEntorno() {
        String url = Config.env("SUSCRIPCIONES_URL", "");
        String token = Config.env("SUSCRIPCIONES_TOKEN", "");
        return url.isEmpty() || token.isEmpty() ? null : new Suscriptores(url, token);
    }

    List<WebPush.Suscripcion> descargar() throws IOException, InterruptedException {
        HttpResponse<String> r = Http.CLIENTE.send(pedido().GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        return WebPush.suscripciones(r.body());
    }

    void borrar(Collection<String> endpoints) throws IOException, InterruptedException {
        String cuerpo = Json.escribir(Map.of("endpoints", new ArrayList<Object>(endpoints)));
        HttpResponse<String> r = Http.CLIENTE.send(pedido()
                        .header("Content-Type", "application/json")
                        .method("DELETE", HttpRequest.BodyPublishers.ofString(cuerpo, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
    }

    private HttpRequest.Builder pedido() {
        return HttpRequest.newBuilder(URI.create(url + "/suscripciones"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", "ClimaCordoba/1.0");
    }
}
