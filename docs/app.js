"use strict";

const C = window.CLIMA_CONFIG;

// Mismos umbrales que src/clima/Analisis.java
const LLUVIA_FUERTE_MM_H = 10;
const PROB_MINIMA = 40;
const CAPE_GRANIZO = 2000;
const RAFAGAS_FUERTES = 70;
const HORAS_A_FUTURO = 12;

const CODIGOS = {
  0: ["Despejado", "☀️", "🌙"], 1: ["Mayormente despejado", "🌤️", "🌙"], 2: ["Parcialmente nublado", "⛅", "☁️"],
  3: ["Nublado", "☁️"], 45: ["Niebla", "🌫️"], 48: ["Niebla", "🌫️"],
  51: ["Llovizna", "🌦️"], 53: ["Llovizna", "🌦️"], 55: ["Llovizna", "🌦️"], 56: ["Llovizna helada", "🌦️"], 57: ["Llovizna helada", "🌦️"],
  61: ["Lluvia débil", "🌧️"], 63: ["Lluvia moderada", "🌧️"], 65: ["Lluvia fuerte", "🌧️"], 66: ["Lluvia helada", "🌧️"], 67: ["Lluvia helada", "🌧️"],
  71: ["Nieve", "❄️"], 73: ["Nieve", "❄️"], 75: ["Nieve", "❄️"], 77: ["Nieve", "❄️"],
  80: ["Chaparrones", "🌦️"], 81: ["Chaparrones", "🌦️"], 82: ["Chaparrones fuertes", "🌧️"], 85: ["Nevadas", "❄️"], 86: ["Nevadas", "❄️"],
  95: ["Tormentas", "⛈️"], 96: ["Tormentas con granizo", "⛈️"], 99: ["Tormentas con granizo", "⛈️"]
};

const $ = (id) => document.getElementById(id);
const r = Math.round;

function info(codigo, esDia = true) {
  const c = CODIGOS[codigo] || ["Sin datos", "🌡️"];
  return { texto: c[0], emoji: !esDia && c[2] ? c[2] : c[1] };
}

function nombreDia(fecha, i) {
  if (i === 0) return "Hoy";
  if (i === 1) return "Mañana";
  const d = new Date(fecha + "T12:00:00");
  const n = d.toLocaleDateString("es-AR", { weekday: "short" }).replace(".", "");
  return n.charAt(0).toUpperCase() + n.slice(1);
}

function escapar(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

// ---------- datos ----------

async function pronostico() {
  const p = new URLSearchParams({
    latitude: C.lat, longitude: C.lon, timezone: C.zona, forecast_days: 7,
    current: "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_gusts_10m,is_day",
    hourly: "temperature_2m,precipitation_probability,precipitation,weather_code,wind_gusts_10m,cape,is_day",
    daily: "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,uv_index_max,sunrise,sunset"
  });
  const resp = await fetch("https://api.open-meteo.com/v1/forecast?" + p);
  if (!resp.ok) throw new Error("Open-Meteo respondió " + resp.status);
  const datos = await resp.json();
  try { localStorage.setItem("pronostico", JSON.stringify(datos)); } catch {}
  return datos;
}

async function alertasSmn() {
  const url = C.repo && !C.repo.startsWith("TU_USUARIO")
    ? `https://raw.githubusercontent.com/${C.repo}/main/docs/datos/alertas.json?t=${Date.now()}`
    : `datos/alertas.json?t=${Date.now()}`;
  try {
    const resp = await fetch(url, { cache: "no-store" });
    if (!resp.ok) return [];
    const d = await resp.json();
    const ahora = Date.now();
    return (d.alertas || []).filter((a) => !a.hasta || new Date(a.hasta).getTime() > ahora);
  } catch {
    return [];
  }
}

// ---------- riesgos de las próximas horas (igual que el servidor) ----------

function riesgos(h, desde) {
  const idx = [];
  for (let i = desde; i < Math.min(desde + HORAS_A_FUTURO, h.time.length); i++) idx.push(i);
  const franja = (lista) => {
    const hs = lista.map((i) => new Date(h.time[i]).getHours());
    return hs.length === 1 ? `a las ${hs[0]} h` : `de ${hs[0]} a ${(hs[hs.length - 1] + 1) % 24} h`;
  };
  const avisos = [];
  const granizo = idx.filter((i) => h.weather_code[i] === 96 || h.weather_code[i] === 99
    || (h.weather_code[i] >= 95 && h.cape[i] >= CAPE_GRANIZO));
  if (granizo.length) avisos.push({ nivel: "naranja", titulo: "🧊 Riesgo de granizo " + franja(granizo),
    texto: "Tormentas fuertes con atmósfera muy inestable. Guardá el auto bajo techo." });
  const lluvia = idx.filter((i) => h.precipitation[i] >= LLUVIA_FUERTE_MM_H && h.precipitation_probability[i] >= PROB_MINIMA);
  if (lluvia.length) avisos.push({ nivel: "amarilla", titulo: "🌧️ Lluvia fuerte " + franja(lluvia),
    texto: `Hasta ${r(Math.max(...lluvia.map((i) => h.precipitation[i])))} mm en una hora.` });
  const tormenta = idx.filter((i) => h.weather_code[i] >= 95);
  if (tormenta.length && !granizo.length) avisos.push({ nivel: "amarilla", titulo: "⛈️ Tormentas " + franja(tormenta),
    texto: "Posibles tormentas eléctricas." });
  const viento = idx.filter((i) => h.wind_gusts_10m[i] >= RAFAGAS_FUERTES);
  if (viento.length) avisos.push({ nivel: "amarilla", titulo: "💨 Ráfagas fuertes " + franja(viento),
    texto: `Hasta ${r(Math.max(...viento.map((i) => h.wind_gusts_10m[i])))} km/h.` });
  return { avisos, horasRiesgo: new Set([...granizo, ...lluvia, ...viento]) };
}

// ---------- pintar ----------

function pintar(d, smn) {
  const a = d.current, h = d.hourly, dia = d.daily;
  const ia = info(a.weather_code, a.is_day === 1);

  $("lugar").textContent = C.lugar;
  $("temp").textContent = r(a.temperature_2m) + "°";
  $("estado").textContent = ia.emoji + " " + ia.texto;
  $("maxmin").textContent = `Máx ${r(dia.temperature_2m_max[0])}° · Mín ${r(dia.temperature_2m_min[0])}°`;
  $("detalles").textContent = `Sensación ${r(a.apparent_temperature)}° · Humedad ${r(a.relative_humidity_2m)}% · Viento ${r(a.wind_speed_10m)} km/h`;

  const c = a.weather_code;
  document.body.className = c >= 95 ? "cielo-tormenta" : c >= 51 ? "cielo-lluvia"
    : a.is_day !== 1 ? "cielo-noche" : c >= 3 ? "cielo-nublado" : "cielo-dia";

  // Hora actual dentro de la serie horaria
  const horaActual = a.time.slice(0, 13) + ":00";
  let desde = h.time.indexOf(horaActual);
  if (desde < 0) desde = 0;
  const { avisos, horasRiesgo } = riesgos(h, desde);

  const tarjetas = smn.map((x) => `
    <div class="aviso ${escapar(x.nivel)}">
      <strong>⚠️ ${escapar(x.titulo)}</strong>
      <p>${escapar(x.descripcion)}</p>
      <p class="fuente">Alerta oficial SMN${x.hasta ? " · hasta " + new Date(x.hasta).toLocaleString("es-AR", { weekday: "short", hour: "2-digit", minute: "2-digit" }) : ""}</p>
    </div>`).concat(avisos.map((x) => `
    <div class="aviso ${x.nivel}">
      <strong>${escapar(x.titulo)}</strong>
      <p>${escapar(x.texto)}</p>
      <p class="fuente">Según el pronóstico</p>
    </div>`));
  $("avisos").innerHTML = tarjetas.join("");

  let horas = "";
  for (let i = desde; i < Math.min(desde + 24, h.time.length); i++) {
    const ih = info(h.weather_code[i], h.is_day[i] === 1);
    const prob = h.precipitation_probability[i];
    horas += `<div class="hora${horasRiesgo.has(i) ? " riesgo" : ""}">
      <div class="h">${i === desde ? "Ahora" : new Date(h.time[i]).getHours() + " h"}</div>
      <div class="ic" title="${ih.texto}">${ih.emoji}</div>
      <div class="t">${r(h.temperature_2m[i])}°</div>
      <div class="p">${prob >= 20 ? prob + "%" : ""}</div>
    </div>`;
  }
  $("horas").innerHTML = horas;

  const minSemana = Math.min(...dia.temperature_2m_min), maxSemana = Math.max(...dia.temperature_2m_max);
  const rango = Math.max(1, maxSemana - minSemana);
  $("dias").innerHTML = dia.time.map((f, i) => {
    const id = info(dia.weather_code[i]);
    const izq = ((dia.temperature_2m_min[i] - minSemana) / rango) * 100;
    const ancho = ((dia.temperature_2m_max[i] - dia.temperature_2m_min[i]) / rango) * 100;
    const prob = dia.precipitation_probability_max[i];
    return `<div class="dia">
      <div>${nombreDia(f, i)}</div>
      <div class="ic" title="${id.texto}">${id.emoji}</div>
      <div class="min">${r(dia.temperature_2m_min[i])}°</div>
      <div class="barra"><span style="left:${izq}%;width:${Math.max(ancho, 4)}%"></span></div>
      <div class="max">${r(dia.temperature_2m_max[i])}°</div>
    </div>${prob >= 30 ? `<div class="prob" style="margin:-6px 0 4px 62px">💧 ${prob}% · ${r(dia.precipitation_sum[i])} mm</div>` : ""}`;
  }).join("");

  const hora = (iso) => iso.slice(11, 16);
  $("extra").innerHTML = `
    <div class="dato"><div class="et">Amanecer</div><div class="val">🌅 ${hora(dia.sunrise[0])}</div></div>
    <div class="dato"><div class="et">Atardecer</div><div class="val">🌇 ${hora(dia.sunset[0])}</div></div>
    <div class="dato"><div class="et">Índice UV</div><div class="val">🔆 ${r(dia.uv_index_max[0])}</div></div>
    <div class="dato"><div class="et">Ráfagas</div><div class="val">💨 ${r(a.wind_gusts_10m)} km/h</div></div>`;

  $("actualizado").textContent = "Actualizado " + new Date().toLocaleTimeString("es-AR", { hour: "2-digit", minute: "2-digit" });
}

async function cargar() {
  $("actualizar").disabled = true;
  try {
    const [d, smn] = await Promise.all([pronostico(), alertasSmn()]);
    pintar(d, smn);
  } catch (e) {
    let guardado = null;
    try { guardado = JSON.parse(localStorage.getItem("pronostico")); } catch {}
    if (guardado) {
      pintar(guardado, []);
      $("actualizado").textContent = "Sin conexión: mostrando el último pronóstico guardado";
    } else {
      $("estado").textContent = "No se pudo cargar el pronóstico";
    }
  } finally {
    $("actualizar").disabled = false;
  }
}

// ---------- notificaciones ----------

let registro = null;

function claveServidor(b64) {
  const s = (b64 + "=".repeat((4 - (b64.length % 4)) % 4)).replace(/-/g, "+").replace(/_/g, "/");
  return Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
}

async function pintarNotificaciones() {
  const caja = $("noti-contenido");
  const esIOS = /iPad|iPhone|iPod/.test(navigator.userAgent);
  const instalada = window.navigator.standalone === true || matchMedia("(display-mode: standalone)").matches;

  if (!("serviceWorker" in navigator) || !("PushManager" in window) || !("Notification" in window)) {
    caja.innerHTML = esIOS && !instalada
      ? `<p>Para recibir el resumen de las 9 y las alertas de granizo y lluvia:</p>
         <p>1. Tocá <b>Compartir</b> <span aria-hidden="true">⬆️</span> abajo en Safari.<br>
            2. Elegí <b>Agregar a inicio</b>.<br>
            3. Abrí <b>Clima</b> desde el ícono y volvé a esta sección.</p>`
      : `<p>Este navegador no admite notificaciones. En iPhone necesitás iOS 16.4 o más nuevo.</p>`;
    return;
  }
  if (Notification.permission === "denied") {
    caja.innerHTML = `<p>Bloqueaste las notificaciones. Activalas en <b>Ajustes → Notificaciones → Clima</b>.</p>`;
    return;
  }

  registro = registro || (await navigator.serviceWorker.ready);
  const sub = await registro.pushManager.getSubscription();
  if (!sub) {
    caja.innerHTML = `<p>Activá los avisos para recibir el resumen todos los días a las 9 y alertas de granizo, lluvia fuerte y tormentas.</p>
      <button class="boton" id="activar" type="button">🔔 Activar avisos</button>`;
    $("activar").addEventListener("click", activar);
    return;
  }

  const registrada = await registrar(sub);
  caja.innerHTML = registrada
    ? `<p>✅ Avisos activados en este dispositivo. Te llega el resumen a las 9 y las alertas cuando haga falta.</p>
       <button class="boton secundario" id="desactivar" type="button">Desactivar avisos</button>`
    : `<p>⚠️ Los avisos están activados pero no se pudieron registrar en el servidor. Revisá la conexión.</p>
       <button class="boton" id="reintentar" type="button">Reintentar</button>`;
  if (registrada) $("desactivar").addEventListener("click", () => desactivar(sub));
  else $("reintentar").addEventListener("click", pintarNotificaciones);
}

/** Avisa al servidor que este dispositivo quiere recibir avisos. Se repite una vez por semana por las dudas. */
async function registrar(sub) {
  const marca = sub.endpoint + "|" + Math.floor(Date.now() / (7 * 864e5));
  try { if (localStorage.getItem("registrado") === marca) return true; } catch {}
  try {
    const resp = await fetch(C.servidor + "/suscribir", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(sub)
    });
    if (!resp.ok) return false;
    try { localStorage.setItem("registrado", marca); } catch {}
    return true;
  } catch {
    return false;
  }
}

async function activar() {
  const boton = $("activar");
  boton.disabled = true;
  try {
    // En iPhone la suscripción tiene que pedirse directo desde el toque del botón.
    await registro.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: claveServidor(C.vapidPublicKey) });
  } catch (e) {
    alert("No se pudieron activar los avisos: " + e.message);
  }
  pintarNotificaciones();
}

async function desactivar(sub) {
  if (!confirm("¿Dejar de recibir avisos del clima en este dispositivo?")) return;
  try {
    await fetch(C.servidor + "/desuscribir", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ endpoint: sub.endpoint })
    });
  } catch {}
  await sub.unsubscribe();
  try { localStorage.removeItem("registrado"); } catch {}
  pintarNotificaciones();
}

// ---------- inicio ----------

$("actualizar").addEventListener("click", cargar);
document.addEventListener("visibilitychange", () => { if (!document.hidden) cargar(); });

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("sw.js")
    .then(() => navigator.serviceWorker.ready)
    .then((reg) => { registro = reg; pintarNotificaciones(); })
    .catch(() => pintarNotificaciones());
} else {
  pintarNotificaciones();
}
cargar();
