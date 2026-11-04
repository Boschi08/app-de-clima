"use strict";

const C = window.CLIMA_CONFIG;

// Mismos umbrales que src/clima/Analisis.java
const LLUVIA_FUERTE_MM_H = 10;
const PROB_MINIMA = 40;
const CAPE_GRANIZO = 2000;
const RAFAGAS_FUERTES = 70;
const HORAS_A_FUTURO = 12;
const DIAS_CONFIABLES = 7; // de ahí en adelante el pronóstico es orientativo

const SUGERIDOS = ["Villa Carlos Paz", "Cosquín", "La Falda", "Alta Gracia", "Villa General Belgrano",
  "Mina Clavero", "Río Cuarto", "Villa María", "La Cumbrecita", "Capilla del Monte"];

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
  return n.charAt(0).toUpperCase() + n.slice(1) + " " + d.getDate();
}

function fechaLarga(fecha) {
  const t = new Date(fecha + "T12:00:00").toLocaleDateString("es-AR", { weekday: "long", day: "numeric", month: "long" });
  return t.charAt(0).toUpperCase() + t.slice(1);
}

function escapar(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

// Guardado local (puede fallar en modo privado: la app sigue andando igual).
const guardado = {
  leer(clave, porDefecto) {
    try { const v = localStorage.getItem(clave); return v === null ? porDefecto : JSON.parse(v); } catch { return porDefecto; }
  },
  escribir(clave, valor) {
    try { localStorage.setItem(clave, JSON.stringify(valor)); } catch {}
  }
};

// ---------- lugares ----------

const POR_DEFECTO = { nombre: C.lugar, region: "Córdoba", lat: C.lat, lon: C.lon };
// Mismo redondeo que Lugar.id() en Java: agrupa lugares a ~1 km.
const idLugar = (l) => l.lat.toFixed(2) + "," + l.lon.toFixed(2);

let lugares = guardado.leer("lugares", null);
if (!Array.isArray(lugares) || !lugares.length) lugares = [POR_DEFECTO];
let actual = Math.min(Math.max(0, guardado.leer("lugarActual", 0)), lugares.length - 1);
let idAvisos = guardado.leer("lugarAvisos", idLugar(lugares[0]));

const lugarActual = () => lugares[actual];
const lugarAvisos = () => lugares.find((l) => idLugar(l) === idAvisos) || lugares[0];

function guardarLugares() {
  guardado.escribir("lugares", lugares);
  guardado.escribir("lugarActual", actual);
  guardado.escribir("lugarAvisos", idAvisos);
}

function elegirLugar(i) {
  actual = i;
  guardarLugares();
  cerrarHojas();
  window.scrollTo(0, 0);
  cargar();
  pintarNotificaciones();
}

function agregarLugar(l) {
  const existente = lugares.findIndex((x) => idLugar(x) === idLugar(l) || (l.gps && x.gps));
  if (existente >= 0) {
    const eraAvisos = idLugar(lugares[existente]) === idAvisos;
    lugares[existente] = l;
    if (eraAvisos) cambiarLugarAvisos(idLugar(l));
    elegirLugar(existente);
  } else {
    lugares.push(l);
    elegirLugar(lugares.length - 1);
  }
}

function borrarLugar(i) {
  if (lugares.length === 1) return;
  const borrado = lugares.splice(i, 1)[0];
  if (actual >= lugares.length || actual === i) actual = 0;
  else if (actual > i) actual--;
  if (idLugar(borrado) === idAvisos) cambiarLugarAvisos(idLugar(lugares[0]));
  guardarLugares();
  pintarLugares();
  cargar();
}

async function geocodificar(texto) {
  const consultar = async (nombre, soloArgentina) => {
    const url = "https://geocoding-api.open-meteo.com/v1/search?language=es&format=json&count=8&name="
      + encodeURIComponent(nombre) + (soloArgentina ? "&countryCode=AR" : "");
    return (await (await fetch(url)).json()).results || [];
  };
  // El buscador solo encuentra nombres que empiezan igual: "Carlos Paz" no encuentra "Villa Carlos Paz".
  let resultados = await consultar(texto, true);
  for (const prefijo of ["Villa", "La", "San", "Santa", "El", "Los", "Las"]) {
    if (resultados.length) break;
    resultados = await consultar(prefijo + " " + texto, true);
  }
  if (!resultados.length) resultados = await consultar(texto, false);
  // Primero los de la provincia de Córdoba (hay "La Cumbrecita" también en Catamarca).
  resultados.sort((a, b) => /C[oó]rdoba/.test(b.admin1 || "") - /C[oó]rdoba/.test(a.admin1 || ""));
  const d = { results: resultados };
  // "Ciudad de Río Cuarto" -> "Río Cuarto", "Departamento de Punilla" -> "Punilla", etc.
  const corto = (s) => (s || "").replace(/^(Ciudad|Municipio|Partido|Departamento|Provincia|Comuna)( de)? /i, "");
  return (d.results || []).map((x) => {
    const nombre = corto(x.name);
    const zona = corto(x.admin2);
    return {
      nombre,
      region: [zona && zona !== nombre ? zona : null, corto(x.admin1), x.country_code !== "AR" ? x.country : null]
        .filter(Boolean).join(", "),
      lat: x.latitude,
      lon: x.longitude
    };
  });
}

let resultadosBusqueda = [];
let temporizadorBusqueda = null;

async function buscar(texto) {
  const caja = $("resultados");
  if (texto.trim().length < 2) { caja.innerHTML = ""; return; }
  caja.innerHTML = `<p class="nota">Buscando…</p>`;
  try {
    resultadosBusqueda = await geocodificar(texto.trim());
    caja.innerHTML = resultadosBusqueda.length
      ? resultadosBusqueda.map((l, i) => `<button class="fila-lugar" type="button" data-resultado="${i}">
          <span><b>${escapar(l.nombre)}</b><small>${escapar(l.region)}</small></span><span aria-hidden="true">＋</span>
        </button>`).join("")
      : `<p class="nota">No encontré "${escapar(texto)}". Probá con otro nombre.</p>`;
  } catch {
    caja.innerHTML = `<p class="nota">No se pudo buscar. Revisá la conexión.</p>`;
  }
}

async function elegirSugerido(nombre) {
  $("buscar").value = nombre;
  await buscar(nombre);
  if (resultadosBusqueda.length) agregarLugar(resultadosBusqueda[0]);
}

function usarMiUbicacion() {
  const boton = $("mi-ubicacion");
  if (!navigator.geolocation) { alert("Este dispositivo no permite usar la ubicación."); return; }
  boton.disabled = true;
  boton.textContent = "📍 Buscando tu ubicación…";
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      boton.disabled = false;
      boton.textContent = "📍 Usar mi ubicación";
      agregarLugar({ nombre: "Mi ubicación", region: "Según el GPS", gps: true,
        lat: r(pos.coords.latitude * 1e4) / 1e4, lon: r(pos.coords.longitude * 1e4) / 1e4 });
    },
    () => {
      boton.disabled = false;
      boton.textContent = "📍 Usar mi ubicación";
      alert("No se pudo obtener la ubicación. Revisá que Clima tenga permiso en Ajustes → Privacidad → Localización.");
    },
    { enableHighAccuracy: false, timeout: 15000, maximumAge: 10 * 60 * 1000 }
  );
}

function pintarLugares() {
  $("sugeridos").innerHTML = SUGERIDOS.map((n) => `<button class="chip" type="button" data-sugerido="${escapar(n)}">${escapar(n)}</button>`).join("");
  $("mis-lugares").innerHTML = lugares.map((l, i) => `
    <div class="fila-lugar${i === actual ? " activo" : ""}">
      <button class="elegir" type="button" data-elegir="${i}">
        <b>${escapar(l.nombre)}${idLugar(l) === idAvisos ? ' <span title="Recibís avisos de este lugar">🔔</span>' : ""}</b>
        <small>${escapar(l.region || "")}</small>
      </button>
      ${lugares.length > 1 ? `<button class="borrar" type="button" data-borrar="${i}" aria-label="Quitar ${escapar(l.nombre)}">✕</button>` : ""}
    </div>`).join("");
}

// ---------- hojas (paneles que suben desde abajo) ----------

function abrirHoja(id) {
  $(id).classList.remove("oculto");
  document.body.classList.add("con-hoja");
}

function cerrarHojas() {
  document.querySelectorAll(".hoja").forEach((h) => h.classList.add("oculto"));
  document.body.classList.remove("con-hoja");
}

// ---------- datos ----------

let datos = null;

async function pronostico(l) {
  const p = new URLSearchParams({
    latitude: l.lat, longitude: l.lon, timezone: "auto", forecast_days: 16,
    current: "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_gusts_10m,is_day",
    hourly: "temperature_2m,apparent_temperature,precipitation_probability,precipitation,weather_code,wind_speed_10m,wind_gusts_10m,cape,is_day",
    daily: "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_gusts_10m_max,uv_index_max,sunrise,sunset"
  });
  const resp = await fetch("https://api.open-meteo.com/v1/forecast?" + p);
  if (!resp.ok) throw new Error("Open-Meteo respondió " + resp.status);
  const d = await resp.json();
  guardado.escribir("pronostico:" + idLugar(l), d);
  return d;
}

/** Ray casting sobre "lat,lon lat,lon ..." (igual que Smn.dentro en Java). */
function dentro(poligono, lat, lon) {
  const pts = poligono.trim().split(/\s+/).map((p) => p.split(",").map(Number));
  let adentro = false;
  for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
    const [la1, lo1] = pts[i], [la2, lo2] = pts[j];
    if ((la1 > lat) !== (la2 > lat) && lon < ((lo2 - lo1) * (lat - la1)) / (la2 - la1) + lo1) adentro = !adentro;
  }
  return adentro;
}

async function alertasSmn(l) {
  const url = C.repo && !C.repo.startsWith("TU_USUARIO")
    ? `https://raw.githubusercontent.com/${C.repo}/main/docs/datos/alertas.json?t=${Date.now()}`
    : `datos/alertas.json?t=${Date.now()}`;
  try {
    const resp = await fetch(url, { cache: "no-store" });
    if (!resp.ok) return [];
    const d = await resp.json();
    const ahora = Date.now();
    return (d.alertas || []).filter((a) => (!a.hasta || new Date(a.hasta).getTime() > ahora)
      && (a.poligonos || []).some((p) => dentro(p, l.lat, l.lon)));
  } catch {
    return [];
  }
}

// ---------- riesgos (igual que el servidor) ----------

function esGranizo(h, i) {
  return h.weather_code[i] === 96 || h.weather_code[i] === 99 || (h.weather_code[i] >= 95 && h.cape[i] >= CAPE_GRANIZO);
}

function esLluviaFuerte(h, i) {
  return h.precipitation[i] >= LLUVIA_FUERTE_MM_H && h.precipitation_probability[i] >= PROB_MINIMA;
}

function riesgos(h, desde) {
  const idx = [];
  for (let i = desde; i < Math.min(desde + HORAS_A_FUTURO, h.time.length); i++) idx.push(i);
  const franja = (lista) => {
    const hs = lista.map((i) => new Date(h.time[i]).getHours());
    return hs.length === 1 ? `a las ${hs[0]} h` : `de ${hs[0]} a ${(hs[hs.length - 1] + 1) % 24} h`;
  };
  const avisos = [];
  const granizo = idx.filter((i) => esGranizo(h, i));
  if (granizo.length) avisos.push({ nivel: "naranja", titulo: "🧊 Riesgo de granizo " + franja(granizo),
    texto: "Tormentas fuertes con atmósfera muy inestable. Guardá el auto bajo techo." });
  const lluvia = idx.filter((i) => esLluviaFuerte(h, i));
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
  datos = d;
  const l = lugarActual();
  const a = d.current, h = d.hourly, dia = d.daily;
  const ia = info(a.weather_code, a.is_day === 1);

  document.title = "Clima · " + l.nombre;
  $("lugar").textContent = l.nombre;
  $("region").textContent = l.region || "";
  $("temp").textContent = r(a.temperature_2m) + "°";
  $("estado").textContent = ia.emoji + " " + ia.texto;
  $("maxmin").textContent = `Máx ${r(dia.temperature_2m_max[0])}° · Mín ${r(dia.temperature_2m_min[0])}°`;
  $("detalles").textContent = `Sensación ${r(a.apparent_temperature)}° · Humedad ${r(a.relative_humidity_2m)}% · Viento ${r(a.wind_speed_10m)} km/h`;

  const c = a.weather_code;
  document.body.classList.remove("cielo-dia", "cielo-noche", "cielo-nublado", "cielo-lluvia", "cielo-tormenta");
  document.body.classList.add(c >= 95 ? "cielo-tormenta" : c >= 51 ? "cielo-lluvia"
    : a.is_day !== 1 ? "cielo-noche" : c >= 3 ? "cielo-nublado" : "cielo-dia");

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

  const minTotal = Math.min(...dia.temperature_2m_min), maxTotal = Math.max(...dia.temperature_2m_max);
  const rango = Math.max(1, maxTotal - minTotal);
  $("dias").innerHTML = dia.time.map((f, i) => {
    const id = info(dia.weather_code[i]);
    const izq = ((dia.temperature_2m_min[i] - minTotal) / rango) * 100;
    const ancho = ((dia.temperature_2m_max[i] - dia.temperature_2m_min[i]) / rango) * 100;
    const prob = dia.precipitation_probability_max[i];
    const separador = i === DIAS_CONFIABLES
      ? `<div class="separador">Más adelante · orientativo, puede cambiar</div>` : "";
    return `${separador}<button class="dia${i >= DIAS_CONFIABLES ? " lejano" : ""}" type="button" data-dia="${i}">
      <span class="nombre">${nombreDia(f, i)}</span>
      <span class="ic" title="${id.texto}">${id.emoji}</span>
      <span class="min">${r(dia.temperature_2m_min[i])}°</span>
      <span class="barra"><span style="left:${izq}%;width:${Math.max(ancho, 4)}%"></span></span>
      <span class="max">${r(dia.temperature_2m_max[i])}°</span>
      <span class="prob">${prob >= 30 ? `💧 ${prob}%${dia.precipitation_sum[i] >= 1 ? " · " + r(dia.precipitation_sum[i]) + " mm" : ""}` : ""}</span>
    </button>`;
  }).join("");

  const hora = (iso) => iso.slice(11, 16);
  $("extra").innerHTML = `
    <div class="dato"><div class="et">Amanecer</div><div class="val">🌅 ${hora(dia.sunrise[0])}</div></div>
    <div class="dato"><div class="et">Atardecer</div><div class="val">🌇 ${hora(dia.sunset[0])}</div></div>
    <div class="dato"><div class="et">Índice UV</div><div class="val">🔆 ${r(dia.uv_index_max[0])}</div></div>
    <div class="dato"><div class="et">Ráfagas</div><div class="val">💨 ${r(a.wind_gusts_10m)} km/h</div></div>`;

  $("actualizado").textContent = "Actualizado " + new Date().toLocaleTimeString("es-AR", { hour: "2-digit", minute: "2-digit" });
}

function abrirDia(i) {
  if (!datos) return;
  const dia = datos.daily, h = datos.hourly, fecha = dia.time[i];
  const id = info(dia.weather_code[i]);
  const hora = (iso) => (iso ? iso.slice(11, 16) : "–");

  let filas = "";
  for (let k = 0; k < h.time.length; k++) {
    if (!h.time[k].startsWith(fecha)) continue;
    const ih = info(h.weather_code[k], h.is_day[k] === 1);
    const prob = h.precipitation_probability[k];
    const mm = h.precipitation[k];
    const riesgo = esGranizo(h, k) ? "🧊" : esLluviaFuerte(h, k) ? "⚠️" : h.wind_gusts_10m[k] >= RAFAGAS_FUERTES ? "💨" : "";
    filas += `<tr${riesgo ? ' class="riesgo"' : ""}>
      <td>${h.time[k].slice(11, 13)} h</td>
      <td title="${ih.texto}">${ih.emoji}</td>
      <td class="fuerte">${r(h.temperature_2m[k])}°</td>
      <td>${prob == null ? "–" : prob + "%"}</td>
      <td>${mm >= 0.1 ? mm.toFixed(1) + " mm" : ""}</td>
      <td>${r(h.wind_gusts_10m[k])}</td>
      <td>${riesgo}</td>
    </tr>`;
  }

  $("titulo-dia").textContent = (i === 0 ? "Hoy · " : i === 1 ? "Mañana · " : "") + fechaLarga(fecha);
  $("detalle-dia").innerHTML = `
    ${i >= DIAS_CONFIABLES ? `<p class="aviso-lejano">⚠️ Faltan ${i} días: tomalo como una tendencia, el pronóstico puede cambiar bastante.</p>` : ""}
    <div class="resumen-dia">
      <div class="ic-grande">${id.emoji}</div>
      <div>
        <div class="desc">${id.texto}</div>
        <div class="mm">Máx <b>${r(dia.temperature_2m_max[i])}°</b> · Mín <b>${r(dia.temperature_2m_min[i])}°</b></div>
        <div class="lugar-dia">${escapar(lugarActual().nombre)}</div>
      </div>
    </div>
    <div class="datos-dia">
      <div class="dato"><div class="et">Lluvia</div><div class="val">💧 ${dia.precipitation_probability_max[i] ?? "–"}%</div><small>${r(dia.precipitation_sum[i] || 0)} mm en total</small></div>
      <div class="dato"><div class="et">Ráfagas</div><div class="val">💨 ${r(dia.wind_gusts_10m_max[i])}</div><small>km/h máximo</small></div>
      <div class="dato"><div class="et">Índice UV</div><div class="val">🔆 ${dia.uv_index_max[i] == null ? "–" : r(dia.uv_index_max[i])}</div></div>
      <div class="dato"><div class="et">Sol</div><div class="val sol">🌅 ${hora(dia.sunrise[i])}<br>🌇 ${hora(dia.sunset[i])}</div></div>
    </div>
    <h4>Hora por hora</h4>
    <table class="tabla-horas">
      <thead><tr><th>Hora</th><th></th><th>Temp</th><th>Lluvia</th><th></th><th>Ráf.</th><th></th></tr></thead>
      <tbody>${filas}</tbody>
    </table>
    <p class="nota">Ráf. = ráfagas de viento en km/h · 🧊 riesgo de granizo · ⚠️ lluvia fuerte · 💨 viento fuerte</p>`;
  abrirHoja("hoja-dia");
  $("hoja-dia").querySelector(".hoja-contenido").scrollTop = 0;
}

async function cargar() {
  const l = lugarActual();
  $("actualizar").disabled = true;
  $("lugar").textContent = l.nombre;
  $("region").textContent = l.region || "";
  try {
    const [d, smn] = await Promise.all([pronostico(l), alertasSmn(l)]);
    if (lugarActual() === l) pintar(d, smn);
  } catch (e) {
    const viejo = guardado.leer("pronostico:" + idLugar(l), null);
    if (viejo) {
      pintar(viejo, []);
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

function cambiarLugarAvisos(id) {
  idAvisos = id;
  guardarLugares();
  try { localStorage.removeItem("registrado"); } catch {}
  pintarNotificaciones();
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
    caja.innerHTML = `<p>Activá los avisos para recibir el resumen todos los días a las 9 y alertas de granizo, lluvia fuerte y tormentas de <b>${escapar(lugarActual().nombre)}</b>.</p>
      <button class="boton" id="activar" type="button">🔔 Activar avisos</button>`;
    $("activar").addEventListener("click", activar);
    return;
  }

  const registrada = await registrar(sub);
  const opciones = lugares.map((l) => {
    const id = idLugar(l);
    return `<option value="${id}"${id === idLugar(lugarAvisos()) ? " selected" : ""}>${escapar(l.nombre)}</option>`;
  }).join("");
  caja.innerHTML = registrada
    ? `<p>✅ Avisos activados en este dispositivo.</p>
       <label class="selector">Recibís avisos de
         <select id="lugar-avisos">${opciones}</select>
       </label>
       <p class="nota">Para elegir otro lugar, agregalo primero tocando el nombre de arriba.</p>
       <button class="boton secundario" id="desactivar" type="button">Desactivar avisos</button>`
    : `<p>⚠️ Los avisos están activados pero no se pudieron registrar en el servidor. Revisá la conexión.</p>
       <button class="boton" id="reintentar" type="button">Reintentar</button>`;
  if (registrada) {
    $("desactivar").addEventListener("click", () => desactivar(sub));
    $("lugar-avisos").addEventListener("change", (e) => cambiarLugarAvisos(e.target.value));
  } else {
    $("reintentar").addEventListener("click", pintarNotificaciones);
  }
}

/** Avisa al servidor que este dispositivo quiere avisos (y de qué lugar). Se repite una vez por semana por las dudas. */
async function registrar(sub) {
  const l = lugarAvisos();
  const marca = sub.endpoint + "|" + idLugar(l) + "|" + Math.floor(Date.now() / (7 * 864e5));
  if (guardado.leer("registrado", null) === marca) return true;
  try {
    const resp = await fetch(C.servidor + "/suscribir", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...sub.toJSON(), lugar: { nombre: l.nombre, lat: l.lat, lon: l.lon } })
    });
    if (!resp.ok) return false;
    guardado.escribir("registrado", marca);
    return true;
  } catch {
    return false;
  }
}

async function activar() {
  const boton = $("activar");
  boton.disabled = true;
  idAvisos = idLugar(lugarActual());
  guardarLugares();
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

$("elegir-lugar").addEventListener("click", () => {
  pintarLugares();
  $("buscar").value = "";
  $("resultados").innerHTML = "";
  abrirHoja("hoja-lugares");
});
$("buscar").addEventListener("input", (e) => {
  clearTimeout(temporizadorBusqueda);
  temporizadorBusqueda = setTimeout(() => buscar(e.target.value), 350);
});
$("buscar").addEventListener("keydown", (e) => {
  if (e.key === "Enter") { clearTimeout(temporizadorBusqueda); buscar(e.target.value); }
});
$("mi-ubicacion").addEventListener("click", usarMiUbicacion);

// Un solo manejador de toques para listas que se redibujan.
document.addEventListener("click", (e) => {
  const t = e.target.closest("[data-resultado],[data-sugerido],[data-elegir],[data-borrar],[data-dia],[data-cerrar]");
  if (e.target.classList.contains("hoja")) { cerrarHojas(); return; }
  if (!t) return;
  if (t.dataset.resultado !== undefined) agregarLugar(resultadosBusqueda[+t.dataset.resultado]);
  else if (t.dataset.sugerido !== undefined) elegirSugerido(t.dataset.sugerido);
  else if (t.dataset.elegir !== undefined) elegirLugar(+t.dataset.elegir);
  else if (t.dataset.borrar !== undefined) borrarLugar(+t.dataset.borrar);
  else if (t.dataset.dia !== undefined) abrirDia(+t.dataset.dia);
  else if (t.dataset.cerrar !== undefined) cerrarHojas();
});
document.addEventListener("keydown", (e) => { if (e.key === "Escape") cerrarHojas(); });

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("sw.js")
    .then(() => navigator.serviceWorker.ready)
    .then((reg) => { registro = reg; pintarNotificaciones(); })
    .catch(() => pintarNotificaciones());
} else {
  pintarNotificaciones();
}
cargar();
