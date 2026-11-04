package clima;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Pronóstico de Open-Meteo (combina los mejores modelos disponibles para la zona). */
final class OpenMeteo {

    record Hora(LocalDateTime hora, double temp, double sensacion, double probLluvia, double lluvia,
                int codigo, double rafagas, double cape, boolean esDia) {}

    record Dia(LocalDate fecha, int codigo, double max, double min, double lluvia, double probLluvia,
               double rafagasMax, double uvMax) {}

    /** {@code zona}: huso horario del lugar (lo informa Open-Meteo). */
    record Pronostico(List<Hora> horas, List<Dia> dias, ZoneId zona) {

        Optional<Dia> dia(LocalDate fecha) {
            return dias.stream().filter(d -> d.fecha().equals(fecha)).findFirst();
        }

        /** Horas en [desde, hasta). */
        List<Hora> entre(LocalDateTime desde, LocalDateTime hasta) {
            return horas.stream()
                    .filter(h -> !h.hora().isBefore(desde) && h.hora().isBefore(hasta))
                    .toList();
        }
    }

    private OpenMeteo() {}

    static Pronostico descargar(Lugar lugar) throws IOException, InterruptedException {
        String url = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + lugar.lat() + "&longitude=" + lugar.lon()
                + "&timezone=auto"
                + "&forecast_days=3"
                + "&hourly=temperature_2m,apparent_temperature,precipitation_probability,precipitation,"
                + "weather_code,wind_gusts_10m,cape,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,"
                + "precipitation_probability_max,wind_gusts_10m_max,uv_index_max";
        Map<String, Object> raiz = Json.obj(Json.parse(Http.get(url)));

        Map<String, Object> h = Json.obj(raiz.get("hourly"));
        List<Object> tiempos = Json.arr(h.get("time"));
        List<Hora> horas = new ArrayList<>();
        for (int i = 0; i < tiempos.size(); i++) {
            horas.add(new Hora(
                    LocalDateTime.parse(Json.str(tiempos.get(i))),
                    n(h, "temperature_2m", i),
                    n(h, "apparent_temperature", i),
                    n(h, "precipitation_probability", i),
                    n(h, "precipitation", i),
                    (int) n(h, "weather_code", i),
                    n(h, "wind_gusts_10m", i),
                    n(h, "cape", i),
                    n(h, "is_day", i) == 1));
        }

        Map<String, Object> d = Json.obj(raiz.get("daily"));
        List<Object> fechas = Json.arr(d.get("time"));
        List<Dia> dias = new ArrayList<>();
        for (int i = 0; i < fechas.size(); i++) {
            dias.add(new Dia(
                    LocalDate.parse(Json.str(fechas.get(i))),
                    (int) n(d, "weather_code", i),
                    n(d, "temperature_2m_max", i),
                    n(d, "temperature_2m_min", i),
                    n(d, "precipitation_sum", i),
                    n(d, "precipitation_probability_max", i),
                    n(d, "wind_gusts_10m_max", i),
                    n(d, "uv_index_max", i)));
        }
        ZoneId zona;
        try {
            zona = ZoneId.of(Json.str(raiz.get("timezone")));
        } catch (Exception e) {
            zona = Config.desdeEntorno().zona();
        }
        return new Pronostico(horas, dias, zona);
    }

    /** Valor numérico i-ésimo de una serie; NaN si falta. */
    private static double n(Map<String, Object> serie, String campo, int i) {
        List<Object> valores = Json.arr(serie.get(campo));
        if (i >= valores.size() || valores.get(i) == null) return Double.NaN;
        return ((Number) valores.get(i)).doubleValue();
    }
}
