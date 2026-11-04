package clima;

import java.nio.file.Path;
import java.time.ZoneId;

/** Ubicación y rutas. Todo se puede cambiar con variables de entorno. */
record Config(String lugar, double lat, double lon, ZoneId zona, Path estado, Path alertasPublicas) {

    static Config desdeEntorno() {
        return new Config(
                env("CLIMA_LUGAR", "Córdoba"),
                Double.parseDouble(env("CLIMA_LAT", "-31.4167")),
                Double.parseDouble(env("CLIMA_LON", "-64.1833")),
                ZoneId.of(env("CLIMA_ZONA", "America/Argentina/Cordoba")),
                Path.of("state", "estado.json"),
                Path.of("docs", "datos", "alertas.json"));
    }

    static String env(String nombre, String porDefecto) {
        String v = System.getenv(nombre);
        return v == null || v.isBlank() ? porDefecto : v.trim();
    }
}
