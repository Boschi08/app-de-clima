// Configuración de la app. La ubicación tiene que coincidir con la de src/clima/Config.java.
window.CLIMA_CONFIG = {
  lugar: "Córdoba",
  lat: -31.4167,
  lon: -64.1833,
  zona: "America/Argentina/Cordoba",
  // Clave pública VAPID (la privada va solo en los secretos de GitHub).
  vapidPublicKey: "BApB-2z35Lb2w0TKl67dU6WdB6nRlgX9DSf7mj6Dw1t4VNDPu9jlma8yAyQhEhGd3jyPFGH1TNpZE-gOpN8auwE",
  // Repositorio de GitHub "usuario/nombre", para leer las alertas del SMN que deja el servidor.
  repo: "Boschi08/app-de-clima",
  // Worker de Cloudflare (carpeta worker/) donde se registran los dispositivos que activan avisos.
  servidor: "https://clima-avisos.juliboschi003.workers.dev"
};
