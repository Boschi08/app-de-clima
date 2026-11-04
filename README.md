# Clima

App del clima para iPhone con:

- 📍 Cualquier lugar: buscás ciudades o pueblos (Carlos Paz, La Cumbrecita, Río Cuarto…), usás el GPS y guardás favoritos.
- 📱 Pronóstico actual, hora por hora y de **16 días**, con el detalle hora por hora de cada día.
- ☀️ Resumen del día como notificación todos los días a las ~9:00.
- 🧊 Avisos de **granizo**, **lluvia fuerte**, **tormentas** y **viento fuerte** para las próximas 12 horas.
- ⚠️ Alertas oficiales del **SMN** que incluyen al lugar.
- 🔔 Cada celular elige de qué lugar recibir los avisos.

**Para usarla:** abrí <https://boschi08.github.io/app-de-clima/> en Safari, tocá **Compartir ⬆️ → Agregar a inicio**, abrí **Clima** desde el ícono y tocá **🔔 Activar avisos**. Nada más. Se puede compartir el link con quien quieras: cada uno activa sus avisos solo.

Todo gratis: **GitHub Actions** corre el programa Java cada 15 minutos, **GitHub Pages** aloja la app y un **Worker de Cloudflare** guarda la lista de dispositivos que activaron los avisos.

## Cómo funciona

```
App en el iPhone (docs/, GitHub Pages)
  └─ "Activar avisos" → se registra sola en el Worker, con el lugar elegido

Cloudflare Worker (worker/)
  └─ Guarda las suscripciones y el lugar de cada una (KV). Solo GitHub puede leer la lista (token).

GitHub Actions (cada 15 min)
  └─ Java (src/clima)
       ├─ Open-Meteo  → pronóstico (combina modelos ECMWF, GFS, ICON…)
       ├─ Agrupa      → un pronóstico por cada lugar elegido (hasta 60 lugares)
       ├─ SMN (CAP)   → alertas oficiales cuyo polígono contiene a cada lugar
       ├─ Analiza     → resumen de las 9 + riesgos de las próximas 12 h
       ├─ Worker      → lista de dispositivos (borra los que desinstalaron la app)
       └─ Web Push    → notificación a cada dispositivo (cifrada, RFC 8291)
```

Umbrales de aviso (en `src/clima/Analisis.java` y `docs/app.js`):

| Aviso          | Condición                                                            |
|----------------|----------------------------------------------------------------------|
| Granizo        | Tormenta con granizo pronosticada, o tormenta con CAPE ≥ 2000 J/kg   |
| Lluvia fuerte  | ≥ 10 mm en una hora o ≥ 25 mm en 3 horas (probabilidad ≥ 40 %)       |
| Tormentas      | Código de tormenta eléctrica                                         |
| Viento         | Ráfagas ≥ 70 km/h                                                    |
| SMN            | Cualquier alerta o aviso vigente que incluya al lugar                |

Cada aviso se manda **una sola vez por día, tipo y lugar**. Lo enviado queda en `state/estado.json`.

## Configuración (ya hecha)

Secretos del repo (**Settings → Secrets and variables → Actions**):

| Secreto               | Para qué                                                         |
|-----------------------|------------------------------------------------------------------|
| `VAPID_PUBLIC_KEY`    | Clave pública de notificaciones (también está en `docs/config.js`) |
| `VAPID_PRIVATE_KEY`   | Clave privada de notificaciones (de `claves-vapid.txt`, no se sube) |
| `VAPID_SUBJECT`       | `https://boschi08.github.io/app-de-clima/`                       |
| `SUSCRIPCIONES_TOKEN` | Clave para leer la lista del Worker (la carga `conectar-servidor.ps1`) |
| `PUSH_SUBSCRIPTIONS`  | Opcional: suscripciones cargadas a mano                          |

Worker: `worker/` se publica con `npx wrangler deploy` (dentro de esa carpeta). Su secreto `TOKEN` es el mismo valor que `SUSCRIPCIONES_TOKEN`.

Probar el envío: **Actions → Clima Córdoba → Run workflow → modo `prueba`**.

## Probar en la PC

```powershell
javac -encoding UTF-8 -d out src/clima/*.java
java -cp out clima.Main ver        # muestra resumen y avisos sin mandar nada
```

Otros modos: `resumen` (manda el resumen ya), `prueba` (notificación de prueba), `claves` (genera claves VAPID nuevas).

## Notas
- GitHub puede atrasar las corridas programadas unos minutos, así que el resumen llega entre las 8:50 y las ~9:15.
- GitHub pausa los workflows programados de repos sin actividad por 60 días. Los commits automáticos del estado suelen alcanzar; si alguna vez se pausa, reactivalo en la pestaña **Actions**.
- El Worker acepta hasta 500 dispositivos (`MAXIMO_SUSCRIPCIONES` en `worker/src/index.js`).
- Más allá de 7 días el pronóstico es orientativo: la app lo marca así.
- Buscador de lugares: geocoding de Open-Meteo (sin clave).
- Ningún servicio puede predecir el granizo con exactitud. El aviso marca **riesgo alto**, y las alertas del SMN son la fuente oficial.
