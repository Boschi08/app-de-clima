package clima;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Alertas y avisos oficiales del Servicio Meteorológico Nacional (formato CAP).
 * Cada alerta trae los polígonos de la zona afectada: con eso se sabe a qué lugares les toca.
 */
final class Smn {

    static final String LISTADO = "https://ssl.smn.gob.ar/CAP/AR.php";
    // El listado llega como RSS o como HTML según el cliente: buscamos los enlaces CAP en cualquier formato.
    private static final Pattern ENLACE =
            Pattern.compile("(https?://ssl\\.smn\\.gob\\.ar/feeds/CAP/[^\"'<>\\s]+\\.xml)");
    private static final Locale ES = Locale.forLanguageTag("es-AR");

    /** {@code poligonos}: cada uno como "lat,lon lat,lon ...". */
    record Alerta(String evento, String titulo, String descripcion, String nivel,
                  OffsetDateTime desde, OffsetDateTime hasta, List<String> poligonos) {

        boolean afecta(double lat, double lon) {
            for (String p : poligonos) if (dentro(p, lat, lon)) return true;
            return false;
        }
    }

    private Smn() {}

    /** Todas las alertas vigentes del país. */
    static List<Alerta> vigentes(OffsetDateTime ahora) throws IOException, InterruptedException {
        Set<String> urls = new LinkedHashSet<>();
        Matcher m = ENLACE.matcher(Http.get(LISTADO));
        while (m.find()) urls.add(m.group(1));

        // La misma alerta viene repartida en varios archivos (un polígono por archivo): se juntan.
        Map<String, Alerta> unicas = new LinkedHashMap<>();
        for (String url : urls) {
            try {
                for (Alerta a : leer(Http.getBytes(url), ahora)) {
                    unicas.merge(a.evento().toLowerCase(ES) + "|" + a.hasta(), a, (vieja, nueva) -> {
                        List<String> todos = new ArrayList<>(vieja.poligonos());
                        for (String p : nueva.poligonos()) if (!todos.contains(p)) todos.add(p);
                        return new Alerta(vieja.evento(), vieja.titulo(), vieja.descripcion(), vieja.nivel(),
                                vieja.desde(), vieja.hasta(), todos);
                    });
                }
            } catch (Exception e) {
                System.err.println("SMN: no se pudo leer " + url + " (" + e.getMessage() + ")");
            }
        }
        return new ArrayList<>(unicas.values());
    }

    static List<Alerta> leer(byte[] xml, OffsetDateTime ahora) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        Element raiz = doc.getDocumentElement();

        if (!"Actual".equalsIgnoreCase(texto(raiz, "status"))
                || "Cancel".equalsIgnoreCase(texto(raiz, "msgType"))) {
            return List.of();
        }
        OffsetDateTime enviado = fecha(texto(raiz, "sent"));

        List<Alerta> salida = new ArrayList<>();
        NodeList infos = raiz.getElementsByTagNameNS("*", "info");
        for (int i = 0; i < infos.getLength(); i++) {
            Element info = (Element) infos.item(i);
            OffsetDateTime hasta = fecha(texto(info, "expires"));
            if (hasta != null && hasta.isBefore(ahora)) continue;

            List<String> poligonos = new ArrayList<>();
            NodeList nodos = info.getElementsByTagNameNS("*", "polygon");
            for (int k = 0; k < nodos.getLength(); k++) poligonos.add(simplificar(nodos.item(k).getTextContent()));
            if (poligonos.isEmpty()) continue; // sin zona no se puede saber a quién le toca

            OffsetDateTime desde = fecha(texto(info, "onset"));
            if (desde == null) desde = enviado;
            String evento = texto(info, "event");
            String titular = texto(info, "headline");
            String nivel = nivel(titular, texto(info, "severity"));
            String titulo = titular.toUpperCase(ES).startsWith("AVISO")
                    ? oracion(titular)
                    : "Alerta " + nivel + " por " + evento.toLowerCase(ES);
            salida.add(new Alerta(evento, titulo, oracion(texto(info, "description")), nivel, desde, hasta, poligonos));
        }
        return salida;
    }

    /** Redondea a 2 decimales (~1 km) y saca puntos repetidos: el archivo público queda mucho más chico. */
    private static String simplificar(String poligono) {
        List<String> puntos = new ArrayList<>();
        for (String par : poligono.trim().split("\\s+")) {
            String[] c = par.split(",");
            if (c.length < 2) continue;
            String p = String.format(Locale.ROOT, "%.2f,%.2f", Double.parseDouble(c[0]), Double.parseDouble(c[1]));
            if (puntos.isEmpty() || !puntos.get(puntos.size() - 1).equals(p)) puntos.add(p);
        }
        return String.join(" ", puntos);
    }

    /** Ray casting sobre "lat,lon lat,lon ...". */
    static boolean dentro(String poligono, double lat, double lon) {
        String[] pares = poligono.trim().split("\\s+");
        double[] la = new double[pares.length];
        double[] lo = new double[pares.length];
        int n = 0;
        for (String par : pares) {
            String[] c = par.split(",");
            if (c.length < 2) continue;
            la[n] = Double.parseDouble(c[0]);
            lo[n] = Double.parseDouble(c[1]);
            n++;
        }
        boolean adentro = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            if ((la[i] > lat) != (la[j] > lat)
                    && lon < (lo[j] - lo[i]) * (lat - la[i]) / (la[j] - la[i]) + lo[i]) {
                adentro = !adentro;
            }
        }
        return adentro;
    }

    private static String nivel(String titular, String severidad) {
        String t = sinAcentos(titular);
        if (t.contains("ROJ")) return "roja";
        if (t.contains("NARANJA")) return "naranja";
        if (t.contains("AMARILL")) return "amarilla";
        return switch (severidad) {
            case "Extreme" -> "roja";
            case "Severe" -> "naranja";
            default -> "amarilla";
        };
    }

    private static String texto(Element padre, String nombre) {
        NodeList l = padre.getElementsByTagNameNS("*", nombre);
        return l.getLength() == 0 ? "" : l.item(0).getTextContent().trim();
    }

    private static OffsetDateTime fecha(String s) {
        try {
            return s.isEmpty() ? null : OffsetDateTime.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sinAcentos(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(ES);
    }

    /** "TORMENTAS FUERTES" -> "Tormentas fuertes" (solo si viene todo en mayúsculas). */
    private static String oracion(String s) {
        if (s.isEmpty() || !s.equals(s.toUpperCase(ES))) return s;
        String min = s.toLowerCase(ES);
        return Character.toUpperCase(min.charAt(0)) + min.substring(1);
    }
}
