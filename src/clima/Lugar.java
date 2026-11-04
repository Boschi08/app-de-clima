package clima;

import java.util.Locale;

/** Un lugar para el pronóstico. El id agrupa a quienes eligieron el mismo punto (redondeo de ~1 km). */
record Lugar(String nombre, double lat, double lon) {

    String id() {
        return String.format(Locale.ROOT, "%.2f,%.2f", lat, lon);
    }
}
