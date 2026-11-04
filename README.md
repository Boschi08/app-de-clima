# Clima Córdoba

App del clima para iPhone con:

- 📱 Pronóstico actual, hora por hora y de 7 días.
- ☀️ Resumen del día como notificación todos los días a las ~9:00.
- 🧊 Avisos de **granizo**, **lluvia fuerte**, **tormentas** y **viento fuerte** para las próximas 12 horas.
- ⚠️ Alertas oficiales del **SMN**, solo las que incluyen a Córdoba.

Todo gratis: **GitHub Actions** corre el programa Java cada 15 minutos y **GitHub Pages** aloja la app.

## Cómo funciona

```
GitHub Actions (cada 15 min)
  └─ Java (src/clima)
       ├─ Open-Meteo  → pronóstico (combina modelos ECMWF, GFS, ICON…)
       ├─ SMN (CAP)   → alertas oficiales cuyo polígono contiene a Córdoba
       ├─ Analiza     → resumen de las 9 + riesgos de las próximas 12 h
       └─ Web Push    → notificación al iPhone (cifrada, RFC 8291)

GitHub Pages (carpeta docs/)
  └─ Web app instalable en el iPhone ("Agregar a inicio")
```

Umbrales de aviso (en `src/clima/Analisis.java` y `docs/app.js`):

| Aviso          | Condición                                                            |
|----------------|----------------------------------------------------------------------|
| Granizo        | Tormenta con granizo pronosticada, o tormenta con CAPE ≥ 2000 J/kg   |
| Lluvia fuerte  | ≥ 10 mm en una hora o ≥ 25 mm en 3 horas (probabilidad ≥ 40 %)       |
| Tormentas      | Código de tormenta eléctrica                                         |
| Viento         | Ráfagas ≥ 70 km/h                                                    |
| SMN            | Cualquier alerta o aviso vigente que incluya a Córdoba               |

Cada aviso se manda **una sola vez por día y tipo**. Lo enviado queda en `state/estado.json`.

## Puesta en marcha (una sola vez)

### 1. Subir el proyecto a GitHub
1. Creá una cuenta en <https://github.com> si no tenés.
2. Creá un repositorio **público** llamado `app-de-clima`. Tiene que ser público porque así GitHub Actions no tiene límite de minutos.
3. Subí todo el contenido de esta carpeta **menos `claves-vapid.txt`**, con git o arrastrando los archivos en *Add file → Upload files*.

### 2. Configurar los secretos
En el repo: **Settings → Secrets and variables → Actions → New repository secret**

| Secreto              | Valor                                                                |
|----------------------|----------------------------------------------------------------------|
| `VAPID_PUBLIC_KEY`   | La línea `VAPID_PUBLIC_KEY=` de `claves-vapid.txt` (solo el valor)   |
| `VAPID_PRIVATE_KEY`  | La línea `VAPID_PRIVATE_KEY=` de `claves-vapid.txt` (solo el valor)  |
| `VAPID_SUBJECT`      | `https://TU_USUARIO.github.io/app-de-clima/`                         |
| `PUSH_SUBSCRIPTIONS` | Se carga en el paso 5                                                |

### 3. Publicar la app
1. **Settings → Pages**: *Source* = **Deploy from a branch**, rama `main`, carpeta **`/docs`**.
2. Editá `docs/config.js` y reemplazá `TU_USUARIO` por tu usuario de GitHub.
3. En un minuto la app queda en `https://TU_USUARIO.github.io/app-de-clima/`.

### 4. Instalarla en el iPhone (iOS 16.4 o superior)
1. Abrí ese link en **Safari**.
2. Tocá **Compartir ⬆️ → Agregar a inicio**.
3. Abrí **Clima** desde el ícono nuevo, no desde Safari.

### 5. Activar los avisos
1. En la app, tocá **🔔 Activar avisos** y aceptá el permiso.
2. Tocá **📋 Copiar código**.
3. Pegalo en el secreto `PUSH_SUBSCRIPTIONS` en GitHub.
   - Para varios celulares, poné todos los códigos así: `[código1, código2]`.
4. Probalo: **Actions → Clima Córdoba → Run workflow → modo `prueba`**.

Listo. A partir de ahí corre solo.

## Probar en la PC

```powershell
javac -encoding UTF-8 -d out src/clima/*.java
java -cp out clima.Main ver        # muestra resumen y avisos sin mandar nada
```

Otros modos: `resumen` (manda el resumen ya), `prueba` (notificación de prueba), `claves` (genera claves VAPID nuevas).

## Notas
- GitHub puede atrasar las corridas programadas unos minutos, así que el resumen llega entre las 8:50 y las ~9:15.
- GitHub pausa los workflows programados de repos sin actividad por 60 días. Los commits automáticos del estado suelen alcanzar; si alguna vez se pausa, reactivalo en la pestaña **Actions**.
- Si cambiás de celular o reinstalás la app, activá los avisos otra vez y actualizá `PUSH_SUBSCRIPTIONS`.
- Ningún servicio puede predecir el granizo con exactitud. El aviso marca **riesgo alto**, y las alertas del SMN son la fuente oficial.
