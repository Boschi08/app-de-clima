package clima;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Recuerda qué ya se mandó, para no repetir avisos. Se guarda en state/estado.json dentro del repo. */
final class Estado {

    private final Path ruta;
    String ultimoResumen = "";
    private final Map<String, String> enviadas = new TreeMap<>();

    private Estado(Path ruta) {
        this.ruta = ruta;
    }

    static Estado cargar(Path ruta) throws IOException {
        Estado e = new Estado(ruta);
        if (Files.exists(ruta)) {
            Map<String, Object> m = Json.obj(Json.parse(Files.readString(ruta, StandardCharsets.UTF_8)));
            e.ultimoResumen = Json.str(m.get("ultimoResumen"));
            Json.obj(m.get("enviadas")).forEach((k, v) -> e.enviadas.put(k, Json.str(v)));
        }
        return e;
    }

    boolean yaEnviado(String clave) {
        return enviadas.containsKey(clave);
    }

    void marcar(String clave, LocalDate vence) {
        enviadas.put(clave, vence.toString());
    }

    void limpiar(LocalDate hoy) {
        enviadas.values().removeIf(v -> LocalDate.parse(v).isBefore(hoy));
    }

    void guardar() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ultimoResumen", ultimoResumen);
        m.put("enviadas", new LinkedHashMap<String, Object>(enviadas));
        Files.createDirectories(ruta.getParent());
        Files.writeString(ruta, Json.escribir(m) + "\n", StandardCharsets.UTF_8);
    }
}
