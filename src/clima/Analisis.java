package clima;

import clima.OpenMeteo.Dia;
import clima.OpenMeteo.Hora;
import clima.OpenMeteo.Pronostico;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Arma el resumen de las 9 y detecta riesgos (granizo, lluvia fuerte, tormentas, viento). */
final class Analisis {

    // Umbrales de los avisos. La app web usa los mismos (docs/app.js).
    static final double LLUVIA_FUERTE_MM_H = 10;
    static final double LLUVIA_FUERTE_3H_MM = 25;
    static final double PROB_MINIMA = 40;
    static final double CAPE_GRANIZO = 2000;
    static final double RAFAGAS_FUERTES = 70;
    static final int HORAS_A_FUTURO = 12;

    /** Notificación lista para mandar. {@code vence}: fecha a partir de la cual se olvida que se envió. */
    record Aviso(String clave, String titulo, String cuerpo, LocalDate vence) {}

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM");

    private Analisis() {}

    // ---------- resumen diario ----------

    static Aviso resumenDiario(Config cfg, Pronostico p, List<Smn.Alerta> smn, LocalDate hoy) {
        Dia d = p.dia(hoy).orElseThrow(() -> new IllegalStateException("Sin pronóstico para " + hoy));
        List<Hora> horas = p.entre(hoy.atStartOfDay(), hoy.plusDays(1).atStartOfDay());

        String titulo = emoji(d.codigo(), true) + " Hoy en " + cfg.lugar() + ": "
                + grados(d.min()) + " / " + grados(d.max());

        List<String> lineas = new ArrayList<>();
        lineas.add(descripcion(d.codigo()) + ".");

        List<String> bloques = new ArrayList<>();
        agregar(bloques, bloque(horas, "Mañana", 7, 12, 9));
        agregar(bloques, bloque(horas, "Tarde", 13, 19, 15));
        agregar(bloques, bloque(horas, "Noche", 20, 23, 21));
        if (!bloques.isEmpty()) lineas.add(String.join(" · ", bloques));

        // Lluvia de 7 a 24 h: la de la madrugada ya pasó cuando llega el resumen.
        List<Hora> resto = horas.stream().filter(h -> h.hora().getHour() >= 7).toList();
        List<Hora> conLluvia = resto.stream().filter(h -> h.probLluvia() >= PROB_MINIMA).toList();
        double mm = resto.stream().mapToDouble(h -> Double.isNaN(h.lluvia()) ? 0 : h.lluvia()).sum();
        double prob = maximo(resto, Hora::probLluvia);
        if (conLluvia.isEmpty() && mm < 1) {
            lineas.add(prob >= 20 ? "☂️ Baja probabilidad de lluvia (" + Math.round(prob) + "%)." : "☂️ No se espera lluvia.");
        } else {
            String l = "🌧️ Lluvia " + Math.round(prob) + "%";
            if (mm >= 0.5) l += " (" + Math.round(mm) + " mm)";
            if (!conLluvia.isEmpty()) l += ", más probable " + franjaHoras(conLluvia);
            lineas.add(l + ".");
        }
        if (d.rafagasMax() >= 35) lineas.add("💨 Ráfagas de hasta " + Math.round(d.rafagasMax()) + " km/h.");
        if (d.uvMax() >= 6) lineas.add("🔆 UV " + Math.round(d.uvMax()) + " (" + uv(d.uvMax()) + "), usá protector.");
        if (d.max() >= 33) lineas.add("🥵 Mucho calor: hidratate.");
        if (d.min() <= 3) lineas.add("🥶 Frío intenso a la mañana, posibles heladas.");
        for (Smn.Alerta a : smn) lineas.add("⚠️ SMN: " + a.titulo() + ".");

        return new Aviso("resumen:" + hoy, titulo, String.join("\n", lineas), hoy.plusDays(1));
    }

    private static void agregar(List<String> l, String s) {
        if (s != null) l.add(s);
    }

    private static String bloque(List<Hora> horas, String nombre, int desde, int hasta, int horaRef) {
        List<Hora> b = horas.stream()
                .filter(h -> h.hora().getHour() >= desde && h.hora().getHour() <= hasta)
                .toList();
        if (b.isEmpty()) return null;
        Hora ref = b.stream().filter(h -> h.hora().getHour() == horaRef).findFirst().orElse(b.get(0));
        return nombre + " " + emoji(codigoRepresentativo(b), ref.esDia()) + " " + grados(ref.temp());
    }

    /** Si en el bloque hay precipitación, manda el peor código; si no, el más frecuente. */
    private static int codigoRepresentativo(List<Hora> b) {
        int peor = b.stream().mapToInt(Hora::codigo).max().orElse(0);
        if (peor >= 51) return peor;
        return b.stream().collect(Collectors.groupingBy(Hora::codigo, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(peor);
    }

    // ---------- riesgos (próximas horas) ----------

    static List<Aviso> riesgos(Pronostico p, LocalDateTime ahora) {
        LocalDateTime desde = ahora.truncatedTo(ChronoUnit.HOURS);
        List<Hora> v = p.entre(desde, desde.plusHours(HORAS_A_FUTURO));
        LocalDate hoy = ahora.toLocalDate();
        List<Aviso> avisos = new ArrayList<>();

        List<Hora> granizo = filtrar(v, h -> h.codigo() == 96 || h.codigo() == 99
                || (h.codigo() >= 95 && h.cape() >= CAPE_GRANIZO));
        if (!granizo.isEmpty()) {
            boolean explicito = granizo.stream().anyMatch(h -> h.codigo() == 96 || h.codigo() == 99);
            double cape = maximo(granizo, Hora::cape);
            String cuerpo = (explicito
                    ? "Se pronostican tormentas con granizo."
                    : "Tormentas fuertes con atmósfera muy inestable (CAPE " + Math.round(cape) + " J/kg).")
                    + " Guardá el auto bajo techo y no salgas durante la tormenta.";
            avisos.add(aviso("granizo", granizo, hoy, "🧊 Riesgo de granizo " + cuando(granizo, hoy), cuerpo));
        }

        List<Hora> lluvia = filtrar(v, h -> h.lluvia() >= LLUVIA_FUERTE_MM_H && h.probLluvia() >= PROB_MINIMA);
        if (lluvia.isEmpty()) lluvia = acumulado3h(v);
        if (!lluvia.isEmpty()) {
            double maxHora = maximo(lluvia, Hora::lluvia);
            double total = v.stream().mapToDouble(h -> Double.isNaN(h.lluvia()) ? 0 : h.lluvia()).sum();
            String cuerpo = "Hasta " + Math.round(maxHora) + " mm en una hora (prob. "
                    + Math.round(maximo(lluvia, Hora::probLluvia)) + "%), " + Math.round(total)
                    + " mm en las próximas " + HORAS_A_FUTURO + " h. Evitá calles anegadas y revisá desagües.";
            avisos.add(aviso("lluvia", lluvia, hoy, "🌧️ Lluvia fuerte " + cuando(lluvia, hoy), cuerpo));
        }

        if (granizo.isEmpty()) {
            List<Hora> tormenta = filtrar(v, h -> h.codigo() >= 95);
            if (!tormenta.isEmpty()) {
                avisos.add(aviso("tormenta", tormenta, hoy, "⛈️ Tormentas " + cuando(tormenta, hoy),
                        "Posibles tormentas eléctricas. Desenchufá aparatos sensibles y evitá estar al aire libre."));
            }
        }

        List<Hora> viento = filtrar(v, h -> h.rafagas() >= RAFAGAS_FUERTES);
        if (!viento.isEmpty()) {
            avisos.add(aviso("viento", viento, hoy, "💨 Ráfagas fuertes " + cuando(viento, hoy),
                    "Ráfagas de hasta " + Math.round(maximo(viento, Hora::rafagas))
                            + " km/h. Asegurá objetos sueltos y alejate de árboles y carteles."));
        }
        return avisos;
    }

    /** Ventanas de 3 horas que juntan mucha lluvia aunque ninguna hora sola llegue al umbral. */
    private static List<Hora> acumulado3h(List<Hora> v) {
        List<Hora> resultado = new ArrayList<>();
        for (int i = 0; i + 2 < v.size(); i++) {
            List<Hora> ventana = v.subList(i, i + 3);
            double suma = ventana.stream().mapToDouble(h -> Double.isNaN(h.lluvia()) ? 0 : h.lluvia()).sum();
            if (suma >= LLUVIA_FUERTE_3H_MM && maximo(ventana, Hora::probLluvia) >= PROB_MINIMA) {
                for (Hora h : ventana) if (!resultado.contains(h)) resultado.add(h);
            }
        }
        return resultado;
    }

    private static Aviso aviso(String tipo, List<Hora> horas, LocalDate hoy, String titulo, String cuerpo) {
        LocalDate fecha = horas.get(0).hora().toLocalDate();
        return new Aviso(tipo + ":" + fecha, titulo, cuerpo, fecha.plusDays(1));
    }

    // ---------- alertas oficiales ----------

    static List<Aviso> deSmn(List<Smn.Alerta> alertas, LocalDate hoy, ZoneId zona) {
        List<Aviso> avisos = new ArrayList<>();
        for (Smn.Alerta a : alertas) {
            LocalDate inicio = a.desde() != null ? a.desde().toLocalDate() : hoy;
            String cuerpo = a.descripcion();
            if (a.hasta() != null) {
                LocalDateTime fin = a.hasta().atZoneSameInstant(zona).toLocalDateTime();
                cuerpo += " Vigente hasta las " + fin.format(HORA)
                        + (fin.toLocalDate().equals(hoy) ? "" : " del " + fin.format(DIA)) + ".";
            }
            LocalDate vence = a.hasta() != null ? a.hasta().toLocalDate().plusDays(1) : hoy.plusDays(1);
            avisos.add(new Aviso("smn:" + a.evento().toLowerCase(Locale.ROOT) + ":" + inicio,
                    "⚠️ SMN: " + a.titulo(), cuerpo, vence));
        }
        return avisos;
    }

    // ---------- utilidades de texto ----------

    private static List<Hora> filtrar(List<Hora> horas, Predicate<Hora> condicion) {
        return horas.stream().filter(condicion).toList();
    }

    private static double maximo(List<Hora> horas, Function<Hora, Double> campo) {
        return horas.stream().map(campo).filter(x -> !x.isNaN()).max(Comparator.naturalOrder()).orElse(0.0);
    }

    /** "hoy de 17 a 20 h", "mañana a las 3 h", etc. */
    private static String cuando(List<Hora> horas, LocalDate hoy) {
        LocalDate fecha = horas.get(0).hora().toLocalDate();
        String dia = fecha.equals(hoy) ? "hoy" : fecha.equals(hoy.plusDays(1)) ? "mañana" : "el " + fecha.format(DIA);
        return dia + " " + franjaHoras(horas);
    }

    private static String franjaHoras(List<Hora> horas) {
        int inicio = horas.get(0).hora().getHour();
        LocalDateTime ultima = horas.get(horas.size() - 1).hora();
        if (horas.size() == 1) return "a las " + inicio + " h";
        return "de " + inicio + " a " + ultima.plusHours(1).getHour() + " h";
    }

    static String grados(double t) {
        return Double.isNaN(t) ? "–" : Math.round(t) + "°";
    }

    private static String uv(double uv) {
        if (uv >= 11) return "extremo";
        if (uv >= 8) return "muy alto";
        if (uv >= 6) return "alto";
        if (uv >= 3) return "moderado";
        return "bajo";
    }

    static String descripcion(int codigo) {
        return switch (codigo) {
            case 0 -> "Despejado";
            case 1 -> "Mayormente despejado";
            case 2 -> "Parcialmente nublado";
            case 3 -> "Nublado";
            case 45, 48 -> "Niebla";
            case 51, 53, 55 -> "Llovizna";
            case 56, 57 -> "Llovizna helada";
            case 61 -> "Lluvia débil";
            case 63 -> "Lluvia moderada";
            case 65 -> "Lluvia fuerte";
            case 66, 67 -> "Lluvia helada";
            case 71, 73, 75, 77 -> "Nieve";
            case 80, 81 -> "Chaparrones";
            case 82 -> "Chaparrones fuertes";
            case 85, 86 -> "Nevadas";
            case 95 -> "Tormentas";
            case 96, 99 -> "Tormentas con granizo";
            default -> "Sin datos";
        };
    }

    static String emoji(int codigo, boolean esDia) {
        return switch (codigo) {
            case 0 -> esDia ? "☀️" : "🌙";
            case 1 -> esDia ? "🌤️" : "🌙";
            case 2 -> "⛅";
            case 3 -> "☁️";
            case 45, 48 -> "🌫️";
            case 51, 53, 55, 56, 57, 80, 81 -> "🌦️";
            case 61, 63, 65, 66, 67, 82 -> "🌧️";
            case 71, 73, 75, 77, 85, 86 -> "❄️";
            case 95, 96, 99 -> "⛈️";
            default -> "🌡️";
        };
    }
}
