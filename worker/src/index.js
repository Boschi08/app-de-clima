// Guarda las suscripciones de quienes activan los avisos en la app.
//   POST   /suscribir       (app)     alta de un dispositivo
//   POST   /desuscribir     (app)     baja de un dispositivo
//   GET    /suscripciones   (GitHub)  lista completa, requiere el token
//   DELETE /suscripciones   (GitHub)  borra suscripciones vencidas, requiere el token

const ORIGEN_APP = "https://boschi08.github.io";
// Solo se aceptan servicios de push reales (Apple, Google, Mozilla, Microsoft).
const SERVICIOS_PUSH = [
  /^web\.push\.apple\.com$/,
  /^fcm\.googleapis\.com$/,
  /^updates\.push\.services\.mozilla\.com$/,
  /^push\.services\.mozilla\.com$/,
  /\.notify\.windows\.com$/
];
const MAXIMO_SUSCRIPCIONES = 500;
const TAMANIO_MAXIMO = 4096;

const CORS = {
  "Access-Control-Allow-Origin": ORIGEN_APP,
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
  "Access-Control-Max-Age": "86400"
};

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
    try {
      if (req.method === "POST" && url.pathname === "/suscribir") return await suscribir(req, env);
      if (req.method === "POST" && url.pathname === "/desuscribir") return await desuscribir(req, env);
      if (url.pathname === "/suscripciones") {
        if (!(await autorizado(req, env))) return respuesta({ error: "no autorizado" }, 401);
        if (req.method === "GET") return respuesta(await todas(env));
        if (req.method === "DELETE") return await borrarVencidas(req, env);
      }
      return respuesta({ error: "no encontrado" }, 404, CORS);
    } catch (e) {
      return respuesta({ error: "pedido inválido" }, 400, CORS);
    }
  }
};

async function suscribir(req, env) {
  const sub = validar(await leerJson(req));
  if (!sub) return respuesta({ error: "suscripción inválida" }, 400, CORS);
  const clave = await claveDe(sub.endpoint);
  if ((await env.SUSCRIPCIONES.get(clave)) === null) {
    const { keys } = await env.SUSCRIPCIONES.list({ limit: MAXIMO_SUSCRIPCIONES });
    if (keys.length >= MAXIMO_SUSCRIPCIONES) return respuesta({ error: "cupo lleno" }, 429, CORS);
  }
  await env.SUSCRIPCIONES.put(clave, JSON.stringify(sub));
  return respuesta({ ok: true }, 200, CORS);
}

async function desuscribir(req, env) {
  const { endpoint } = await leerJson(req);
  if (typeof endpoint !== "string") return respuesta({ error: "falta endpoint" }, 400, CORS);
  await env.SUSCRIPCIONES.delete(await claveDe(endpoint));
  return respuesta({ ok: true }, 200, CORS);
}

async function todas(env) {
  const lista = [];
  let cursor;
  do {
    const pagina = await env.SUSCRIPCIONES.list({ cursor });
    for (const k of pagina.keys) {
      const valor = await env.SUSCRIPCIONES.get(k.name);
      if (valor) lista.push(JSON.parse(valor));
    }
    cursor = pagina.list_complete ? undefined : pagina.cursor;
  } while (cursor);
  return lista;
}

async function borrarVencidas(req, env) {
  const { endpoints } = await leerJson(req);
  if (!Array.isArray(endpoints)) return respuesta({ error: "falta endpoints" }, 400);
  for (const e of endpoints) if (typeof e === "string") await env.SUSCRIPCIONES.delete(await claveDe(e));
  return respuesta({ borradas: endpoints.length });
}

// ---------- utilidades ----------

function validar(s) {
  if (!s || typeof s.endpoint !== "string" || !s.keys) return null;
  let host;
  try {
    const u = new URL(s.endpoint);
    if (u.protocol !== "https:") return null;
    host = u.hostname;
  } catch {
    return null;
  }
  if (!SERVICIOS_PUSH.some((r) => r.test(host))) return null;
  const b64 = /^[A-Za-z0-9_-]+=*$/;
  const { p256dh, auth } = s.keys;
  if (typeof p256dh !== "string" || typeof auth !== "string" || !b64.test(p256dh) || !b64.test(auth)) return null;
  if (p256dh.length < 80 || p256dh.length > 100 || auth.length < 16 || auth.length > 32) return null;
  return { endpoint: s.endpoint, keys: { p256dh, auth } };
}

async function leerJson(req) {
  const texto = await req.text();
  if (texto.length > TAMANIO_MAXIMO) throw new Error("demasiado grande");
  return JSON.parse(texto);
}

async function claveDe(endpoint) {
  const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(endpoint));
  return [...new Uint8Array(hash)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function autorizado(req, env) {
  const recibido = new TextEncoder().encode(req.headers.get("Authorization") || "");
  const esperado = new TextEncoder().encode("Bearer " + (env.TOKEN || ""));
  if (!env.TOKEN || recibido.byteLength !== esperado.byteLength) return false;
  return crypto.subtle.timingSafeEqual(recibido, esperado);
}

function respuesta(datos, estado = 200, extra = {}) {
  return new Response(JSON.stringify(datos), {
    status: estado,
    headers: { "Content-Type": "application/json; charset=utf-8", ...extra }
  });
}
