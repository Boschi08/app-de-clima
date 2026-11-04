package clima;

import clima.Analisis.Aviso;
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
 *   revisar  (por defecto) resumen de las 9 si corresponde + avisos nuevos. Lo corre GitHub Actions.
 *   ver      muestra todo por consola sin enviar ni guardar nada (para probar en la PC).
 *   resumen  fuerza el envío del resumen del día ahora.
 *   prueba   manda una notificación de prueba.
 *   claves   genera las claves VAPID en claves-vapid.txt.
 */
public final class Main {

    /** El resumen sale en la primera corrida desde esta hora (las corridas son cada 15 min). */
    static final LocalTime HORA_RESUMEN = LocalTime.of(8, 50);
    static final LocalTime LIMITE_RESUMEN = LocalTime.of(12, 0);

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
        LocalDate hoy = ahora.toLocalDate();
        estado.limpiar(hoy);
        System.out.println("Clima " + cfg.lugar() + " - " + ahora.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));

        OpenMeteo.Pronostico pronostico = OpenMeteo.descargar(cfg);
        List<Smn.Alerta> smn;
        try {
            smn = Smn.alertasPara(cfg, ahora.toOffsetDateTime());
            System.out.println("Alertas SMN vigentes para " + cfg.lugar() + ": " + smn.size());
        } catch (Exception e) {
            System.err.println("No se pudieron leer las alertas del SMN: " + e.getMessage());
            smn = List.of();
        }
        publicarAlertas(cfg, smn, ahora);

        WebPush push = enviar ? WebPush.desdeEntorno() : null;

        LocalTime hora = ahora.toLocalTime();
        boolean tocaResumen = forzarResumen || (!hoy.toString().equals(estado.ultimoResumen)
                && !hora.isBefore(HORA_RESUMEN) && hora.isBefore(LIMITE_RESUMEN));
        if (tocaResumen || !enviar) {
            Aviso resumen = Analisis.resumenDiario(cfg, pronostico, smn, hoy);
            if (notificar(push, resumen, !enviar)) estado.ultimoResumen = hoy.toString();
        }

        List<Aviso> avisos = new ArrayList<>(Analisis.deSmn(smn, hoy, cfg.zona()));
        avisos.addAll(Analisis.riesgos(pronostico, ahora.toLocalDateTime()));
        if (avisos.isEmpty()) System.out.println("Sin riesgos en las próximas " + Analisis.HORAS_A_FUTURO + " h.");
        for (Aviso a : avisos) {
            if (estado.yaEnviado(a.clave())) {
                System.out.println("(ya avisado) " + a.titulo());
            } else if (notificar(push, a, !enviar)) {
                estado.marcar(a.clave(), a.vence());
            }
        }

        if (push != null) push.limpiarVencidas();
        if (enviar) estado.guardar();
    }

    private static boolean notificar(WebPush push, Aviso aviso, boolean soloMostrar) {
        System.out.println();
        System.out.println("== " + aviso.titulo());
        System.out.println(aviso.cuerpo());
        if (soloMostrar || push == null) return false;
        return push.enviar(aviso);
    }

    /** Deja las alertas del SMN en docs/datos/alertas.json para que la app las muestre. */
    private static void publicarAlertas(Config cfg, List<Smn.Alerta> alertas, ZonedDateTime ahora) throws IOException {
        List<Object> lista = new ArrayList<>();
        for (Smn.Alerta a : alertas) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("titulo", a.titulo());
            m.put("descripcion", a.descripcion());
            m.put("nivel", a.nivel());
            m.put("desde", a.desde() == null ? null : a.desde().toString());
            m.put("hasta", a.hasta() == null ? null : a.hasta().toString());
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
