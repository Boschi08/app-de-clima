package clima;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Recuerda qué ya se mandó a cada lugar, para no repetir avisos. Se guarda en state/estado.json dentro del repo.
 * Las claves llevan adelante el id del lugar ("-31.42,-64.18|lluvia:2026-11-04").
 */
final class Estado {

    private final Path ruta;
    private final Map<String, String> resumenes = new TreeMap<>();
    private final Map<String, String> enviadas = new TreeMap<>();

    private Estado(Path ruta) {
        this.ruta = ruta;
    }

    static Estado cargar(Path ruta) throws IOException {
        Estado e = new Estado(ruta);
        if (Files.exists(ruta)) {
            Map<String, Object> m = Json.obj(Json.parse(Files.readString(ruta, StandardCharsets.UTF_8)));
            Json.obj(m.get("resumenes")).forEach((k, v) -> e.resumenes.put(k, Json.str(v)));
            Json.obj(m.get("enviadas")).forEach((k, v) -> e.enviadas.put(k, Json.str(v)));
        }
        return e;
    }

    boolean resumenEnviado(Lugar lugar, LocalDate dia) {
        return dia.toString().equals(resumenes.get(lugar.id()));
    }

    void marcarResumen(Lugar lugar, LocalDate dia) {
        resumenes.put(lugar.id(), dia.toString());
    }

    boolean yaEnviado(Lugar lugar, String clave) {
        return enviadas.containsKey(lugar.id() + "|" + clave);
    }

    void marcar(Lugar lugar, String clave, LocalDate vence) {
        enviadas.put(lugar.id() + "|" + clave, vence.toString());
    }

    void limpiar(LocalDate hoy) {
        enviadas.values().removeIf(v -> LocalDate.parse(v).isBefore(hoy));
        resumenes.values().removeIf(v -> LocalDate.parse(v).isBefore(hoy.minusDays(7)));
    }

    void guardar() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resumenes", new LinkedHashMap<String, Object>(resumenes));
        m.put("enviadas", new LinkedHashMap<String, Object>(enviadas));
        Files.createDirectories(ruta.getParent());
        Files.writeString(ruta, Json.escribir(m) + "\n", StandardCharsets.UTF_8);
    }
}
