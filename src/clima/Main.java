package clima;

import clima.Analisis.Aviso;
import clima.WebPush.Suscripcion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Punto de entrada. Modos:
 *   revisar  (por defecto) resumen de las 9 si corresponde + avisos nuevos, para cada lugar elegido. Lo corre GitHub Actions.
 *   ver      muestra todo por consola sin enviar ni guardar nada (para probar en la PC).
 *   resumen  fuerza el envío del resumen del día ahora.
 *   prueba   manda una notificación de prueba.
 *   claves   genera las claves VAPID en claves-vapid.txt.
 */
public final class Main {

    /** El resumen sale en la primera corrida desde esta hora (las corridas son cada 15 min). */
    static final LocalTime HORA_RESUMEN = LocalTime.of(8, 50);
    static final LocalTime LIMITE_RESUMEN = LocalTime.of(12, 0);
    /** Tope de lugares distintos por corrida, para no pasarse del límite gratuito de Open-Meteo. */
    static final int MAXIMO_LUGARES = 60;

    public static void main(String[] args) throws Exception {
        String modo = args.length > 0 ? args[0] : "revisar";
        switch (modo) {
            case "revisar" -> ejecutar(true, false);
            case "ver" -> ejecutar(false, false);
            case "resumen" -> ejecutar(true, true);
            case "prueba" -> prueba();
            case "claves" -> claves();
            default -> {
                System.err.println("Modo desconocido: " + modo + " (revisar | ver | resumen | prueba | claves)");
                System.exit(2);
            }
        }
    }

    private static void ejecutar(boolean enviar, boolean forzarResumen) throws Exception {
        Config cfg = Config.desdeEntorno();
        Estado estado = Estado.cargar(cfg.estado());
        ZonedDateTime ahora = ZonedDateTime.now(cfg.zona());
        estado.limpiar(ahora.toLocalDate());
        System.out.println("Clima - " + ahora.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));

        List<Smn.Alerta> smn;
        try {
            smn = Smn.vigentes(ahora.toOffsetDateTime());
            System.out.println("Alertas SMN vigentes en el país: " + smn.size());
        } catch (Exception e) {
            System.err.println("No se pudieron leer las alertas del SMN: " + e.getMessage());
            smn = List.of();
        }
        publicarAlertas(cfg, smn, ahora);

        // Cada dispositivo recibe los avisos del lugar que eligió en la app.
        Lugar porDefecto = new Lugar(cfg.lugar(), cfg.lat(), cfg.lon());
        WebPush push = enviar ? WebPush.desdeEntorno() : null;
        Map<String, Lugar> lugares = new LinkedHashMap<>();
        Map<String, List<Suscripcion>> destinos = new LinkedHashMap<>();
        if (push == null) {
            lugares.put(porDefecto.id(), porDefecto);
            destinos.put(porDefecto.id(), List.of());
        } else {
            for (Suscripcion s : push.suscripciones()) {
                Lugar l = s.lugar() != null ? s.lugar() : porDefecto;
                lugares.putIfAbsent(l.id(), l);
                destinos.computeIfAbsent(l.id(), k -> new ArrayList<>()).add(s);
            }
        }

        int revisados = 0;
        for (Lugar lugar : lugares.values()) {
            if (++revisados > MAXIMO_LUGARES) {
                System.err.println("Demasiados lugares distintos: se revisan solo los primeros " + MAXIMO_LUGARES + ".");
                break;
            }
            try {
                revisarLugar(lugar, destinos.get(lugar.id()), smn, estado, push, enviar, forzarResumen);
            } catch (Exception e) {
                System.err.println("No se pudo revisar " + lugar.nombre() + ": " + e.getMessage());
            }
        }

        if (push != null) push.limpiarVencidas();
        if (enviar) estado.guardar();
    }

    private static void revisarLugar(Lugar lugar, List<Suscripcion> destinos, List<Smn.Alerta> smnPais, Estado estado,
                                     WebPush push, boolean enviar, boolean forzarResumen) throws Exception {
        OpenMeteo.Pronostico pronostico = OpenMeteo.descargar(lugar);
        ZonedDateTime ahora = ZonedDateTime.now(pronostico.zona());
        LocalDate hoy = ahora.toLocalDate();
        List<Smn.Alerta> smn = smnPais.stream().filter(a -> a.afecta(lugar.lat(), lugar.lon())).toList();

        System.out.println();
        System.out.println("##### " + lugar.nombre() + " (" + lugar.id() + ") - " + destinos.size()
                + " dispositivo(s), " + smn.size() + " alerta(s) SMN, hora local "
                + ahora.format(DateTimeFormatter.ofPattern("HH:mm")));

        LocalTime hora = ahora.toLocalTime();
        boolean tocaResumen = forzarResumen || (!estado.resumenEnviado(lugar, hoy)
                && !hora.isBefore(HORA_RESUMEN) && hora.isBefore(LIMITE_RESUMEN));
        if (tocaResumen || !enviar) {
            Aviso resumen = Analisis.resumenDiario(lugar, pronostico, smn, hoy);
            if (notificar(push, resumen, destinos, !enviar)) estado.marcarResumen(lugar, hoy);
        }

        List<Aviso> avisos = new ArrayList<>(Analisis.deSmn(smn, hoy, pronostico.zona()));
        avisos.addAll(Analisis.riesgos(pronostico, ahora.toLocalDateTime()));
        if (avisos.isEmpty()) System.out.println("Sin riesgos en las próximas " + Analisis.HORAS_A_FUTURO + " h.");
        for (Aviso a : avisos) {
            // El nombre del lugar va en el título: con varios lugares se sabe de cuál es el aviso.
            Aviso conLugar = new Aviso(a.clave(), a.titulo() + " · " + lugar.nombre(), a.cuerpo(), a.vence());
            if (estado.yaEnviado(lugar, a.clave())) {
                System.out.println("(ya avisado) " + conLugar.titulo());
            } else if (notificar(push, conLugar, destinos, !enviar)) {
                estado.marcar(lugar, a.clave(), a.vence());
            }
        }
    }

    private static boolean notificar(WebPush push, Aviso aviso, List<Suscripcion> destinos, boolean soloMostrar) {
        System.out.println();
        System.out.println("== " + aviso.titulo());
        System.out.println(aviso.cuerpo());
        if (soloMostrar || push == null || destinos.isEmpty()) return false;
        return push.enviar(aviso, destinos);
    }

    /**
     * Deja todas las alertas vigentes del SMN, con sus zonas, en docs/datos/alertas.json:
     * la app muestra las que tocan al lugar que estés mirando.
     */
    private static void publicarAlertas(Config cfg, List<Smn.Alerta> alertas, ZonedDateTime ahora) throws IOException {
        List<Object> lista = new ArrayList<>();
        for (Smn.Alerta a : alertas) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("titulo", a.titulo());
            m.put("descripcion", a.descripcion());
            m.put("nivel", a.nivel());
            m.put("desde", a.desde() == null ? null : a.desde().toString());
            m.put("hasta", a.hasta() == null ? null : a.hasta().toString());
            m.put("poligonos", new ArrayList<Object>(a.poligonos()));
            lista.add(m);
        }
        Path ruta = cfg.alertasPublicas();
        if (Files.exists(ruta)) {
            Object anterior = Json.obj(Json.parse(Files.readString(ruta, StandardCharsets.UTF_8))).get("alertas");
            if (Json.escribir(anterior).equals(Json.escribir(lista))) return; // sin cambios: no ensuciar el repo
        }
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("actualizado", ahora.toOffsetDateTime().toString());
        datos.put("alertas", lista);
        Files.createDirectories(ruta.getParent());
        Files.writeString(ruta, Json.escribir(datos) + "\n", StandardCharsets.UTF_8);
    }

    private static void prueba() throws Exception {
        WebPush push = WebPush.desdeEntorno();
        if (push == null) System.exit(1);
        Aviso a = new Aviso("prueba", "✅ ¡Avisos funcionando!",
                "Así te van a llegar el resumen de las 9 y las alertas de granizo y lluvia fuerte.",
                LocalDate.now());
        boolean llego = push.enviar(a);
        push.limpiarVencidas();
        if (!llego) System.exit(1);
    }

    private static void claves() throws Exception {
        String[] k = WebPush.generarClaves();
        Path archivo = Path.of("claves-vapid.txt");
        Files.writeString(archivo, "VAPID_PUBLIC_KEY=" + k[0] + "\nVAPID_PRIVATE_KEY=" + k[1] + "\n");
        System.out.println("Claves guardadas en " + archivo.toAbsolutePath() + " (no lo subas a GitHub).");
        System.out.println("Clave pública (va en docs/config.js): " + k[0]);
    }
}
