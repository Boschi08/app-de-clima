package clima;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Notificaciones Web Push (las que recibe la app instalada en el iPhone), solo con el JDK.
 * Cifrado aes128gcm (RFC 8291 / RFC 8188) y autenticación VAPID (RFC 8292).
 */
final class WebPush {

    /** {@code lugar}: de dónde quiere los avisos ese dispositivo (null = lugar por defecto). */
    record Suscripcion(String endpoint, String p256dh, String auth, Lugar lugar) {}

    private static final ECParameterSpec P256 = parametrosP256();
    private static final SecureRandom AZAR = new SecureRandom();

    private final byte[] vapidPublica;
    private final ECPrivateKey vapidPrivada;
    private final String subject;
    private final List<Suscripcion> suscripciones;
    private final Suscriptores remoto;
    private final Set<String> vencidas = new LinkedHashSet<>();

    private WebPush(byte[] vapidPublica, ECPrivateKey vapidPrivada, String subject,
                    List<Suscripcion> suscripciones, Suscriptores remoto) {
        this.vapidPublica = vapidPublica;
        this.vapidPrivada = vapidPrivada;
        this.subject = subject;
        this.suscripciones = suscripciones;
        this.remoto = remoto;
    }

    /**
     * Lee las claves de los secretos de GitHub y junta las suscripciones: las que se registraron
     * solas desde la app (Worker de Cloudflare) más las cargadas a mano en PUSH_SUBSCRIPTIONS.
     * Devuelve null si falta algo.
     */
    static WebPush desdeEntorno() throws GeneralSecurityException {
        String pub = Config.env("VAPID_PUBLIC_KEY", "");
        String priv = Config.env("VAPID_PRIVATE_KEY", "");
        if (pub.isEmpty() || priv.isEmpty()) {
            System.err.println("Faltan los secretos VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY: no se envía nada.");
            return null;
        }
        Map<String, Suscripcion> unicas = new LinkedHashMap<>();
        Suscriptores remoto = Suscriptores.desdeEntorno();
        if (remoto != null) {
            try {
                for (Suscripcion s : remoto.descargar()) unicas.put(s.endpoint(), s);
            } catch (Exception e) {
                System.err.println("No se pudieron leer los suscriptores del Worker: " + e.getMessage());
            }
        }
        for (Suscripcion s : suscripciones(Config.env("PUSH_SUBSCRIPTIONS", ""))) unicas.putIfAbsent(s.endpoint(), s);
        if (unicas.isEmpty()) {
            System.err.println("No hay suscripciones todavía: activá los avisos en la app.");
            return null;
        }
        System.out.println("Dispositivos suscriptos: " + unicas.size());
        String subject = Config.env("VAPID_SUBJECT", "mailto:clima-cordoba@users.noreply.github.com");
        return new WebPush(b64(pub), clavePrivada(b64(priv)), subject, new ArrayList<>(unicas.values()), remoto);
    }

    /** Borra del Worker los dispositivos que desinstalaron la app o desactivaron los avisos. */
    void limpiarVencidas() {
        if (vencidas.isEmpty() || remoto == null) return;
        try {
            remoto.borrar(vencidas);
            System.out.println("Suscripciones vencidas borradas: " + vencidas.size());
        } catch (Exception e) {
            System.err.println("No se pudieron borrar las suscripciones vencidas: " + e.getMessage());
        }
    }

    /** Acepta una suscripción (objeto JSON) o varias (lista JSON), tal como las genera el navegador. */
    static List<Suscripcion> suscripciones(String json) {
        List<Suscripcion> lista = new ArrayList<>();
        if (json.isBlank()) return lista;
        Object raiz = Json.parse(json);
        List<Object> elementos = raiz instanceof List ? Json.arr(raiz) : List.of(raiz);
        for (Object o : elementos) {
            Map<String, Object> s = Json.obj(o);
            Map<String, Object> keys = Json.obj(s.get("keys"));
            lista.add(new Suscripcion(Json.str(s.get("endpoint")), Json.str(keys.get("p256dh")),
                    Json.str(keys.get("auth")), lugar(s.get("lugar"))));
        }
        return lista;
    }

    private static Lugar lugar(Object o) {
        Map<String, Object> m = Json.obj(o);
        if (!(m.get("lat") instanceof Number lat) || !(m.get("lon") instanceof Number lon)) return null;
        if (Math.abs(lat.doubleValue()) > 90 || Math.abs(lon.doubleValue()) > 180) return null;
        String nombre = Json.str(m.get("nombre")).trim();
        return new Lugar(nombre.isEmpty() ? "tu lugar" : nombre, lat.doubleValue(), lon.doubleValue());
    }

    List<Suscripcion> suscripciones() {
        return suscripciones;
    }

    /** Manda el aviso a todos los dispositivos. true si llegó a al menos uno. */
    boolean enviar(Analisis.Aviso aviso) {
        return enviar(aviso, suscripciones);
    }

    /** Manda el aviso a esos dispositivos. true si llegó a al menos uno. */
    boolean enviar(Analisis.Aviso aviso, List<Suscripcion> destinos) {
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("title", aviso.titulo());
        datos.put("body", aviso.cuerpo());
        datos.put("tag", aviso.clave());
        datos.put("url", "./");
        byte[] payload = Json.escribir(datos).getBytes(StandardCharsets.UTF_8);

        boolean alguno = false;
        for (Suscripcion s : destinos) {
            String disp = URI.create(s.endpoint()).getHost();
            try {
                int estado = enviar(s, payload);
                if (estado >= 200 && estado < 300) {
                    alguno = true;
                    System.out.println("  -> enviado a " + disp);
                } else if (estado == 404 || estado == 410) {
                    vencidas.add(s.endpoint());
                    System.err.println("  -> " + disp + ": suscripción vencida (" + estado + "), se borra.");
                } else {
                    System.err.println("  -> " + disp + ": error HTTP " + estado);
                }
            } catch (Exception e) {
                System.err.println("  -> " + disp + ": " + e);
            }
        }
        return alguno;
    }

    private int enviar(Suscripcion s, byte[] payload) throws Exception {
        byte[] salt = new byte[16];
        AZAR.nextBytes(salt);
        byte[] cuerpo = cifrar(payload, b64(s.p256dh()), b64(s.auth()), nuevoParDeClaves(), salt);

        URI destino = URI.create(s.endpoint());
        String audiencia = destino.getScheme() + "://" + destino.getHost();
        HttpRequest req = HttpRequest.newBuilder(destino)
                .timeout(Duration.ofSeconds(30))
                .header("TTL", "86400")
                .header("Urgency", "high")
                .header("Content-Type", "application/octet-stream")
                .header("Content-Encoding", "aes128gcm")
                .header("Authorization", "vapid t=" + jwt(audiencia) + ", k=" + b64(vapidPublica))
                .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo))
                .build();
        HttpResponse<String> r = Http.CLIENTE.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 300 && !r.body().isBlank()) System.err.println("     respuesta: " + r.body());
        return r.statusCode();
    }

    /** Cuerpo cifrado aes128gcm: salt | rs | idlen | clave pública efímera | texto cifrado. */
    static byte[] cifrar(byte[] payload, byte[] uaPublica, byte[] auth, KeyPair efimera, byte[] salt)
            throws GeneralSecurityException {
        byte[] asPublica = puntoSinComprimir((ECPublicKey) efimera.getPublic());

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(efimera.getPrivate());
        ka.doPhase(clavePublica(uaPublica), true);
        byte[] ecdh = ka.generateSecret();

        byte[] prkClave = hmac(auth, ecdh);
        byte[] ikm = hmac(prkClave, concat(ascii("WebPush: info\0"), uaPublica, asPublica, new byte[] {1}));
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat(ascii("Content-Encoding: aes128gcm\0"), new byte[] {1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat(ascii("Content-Encoding: nonce\0"), new byte[] {1})), 12);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] cifrado = c.doFinal(concat(payload, new byte[] {2})); // 0x02 = último registro

        return ByteBuffer.allocate(16 + 4 + 1 + asPublica.length + cifrado.length)
                .put(salt).putInt(4096).put((byte) asPublica.length).put(asPublica).put(cifrado)
                .array();
    }

    private String jwt(String audiencia) throws GeneralSecurityException {
        long exp = System.currentTimeMillis() / 1000 + 3300;
        String header = b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aud", audiencia);
        claims.put("exp", exp);
        claims.put("sub", subject);
        String cuerpo = b64(Json.escribir(claims).replaceAll("\\s+", "").getBytes(StandardCharsets.UTF_8));
        Signature firma = Signature.getInstance("SHA256withECDSAinP1363Format");
        firma.initSign(vapidPrivada);
        firma.update((header + "." + cuerpo).getBytes(StandardCharsets.US_ASCII));
        return header + "." + cuerpo + "." + b64(firma.sign());
    }

    // ---------- claves ----------

    /** Genera un par VAPID nuevo: {pública, privada} en base64url. */
    static String[] generarClaves() throws GeneralSecurityException {
        KeyPair kp = nuevoParDeClaves();
        byte[] priv = aBytes(((ECPrivateKey) kp.getPrivate()).getS());
        return new String[] {b64(puntoSinComprimir((ECPublicKey) kp.getPublic())), b64(priv)};
    }

    static KeyPair nuevoParDeClaves() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    static ECPublicKey clavePublica(byte[] punto) throws GeneralSecurityException {
        if (punto.length != 65 || punto[0] != 4) throw new GeneralSecurityException("Clave pública inválida");
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(punto, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(punto, 33, 65));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    }

    static ECPrivateKey clavePrivada(byte[] d) throws GeneralSecurityException {
        return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), P256));
    }

    static byte[] puntoSinComprimir(ECPublicKey k) {
        return concat(new byte[] {4}, aBytes(k.getW().getAffineX()), aBytes(k.getW().getAffineY()));
    }

    /** Entero a exactamente 32 bytes big-endian. */
    private static byte[] aBytes(BigInteger n) {
        byte[] b = n.toByteArray();
        if (b.length == 32) return b;
        byte[] r = new byte[32];
        if (b.length > 32) System.arraycopy(b, b.length - 32, r, 0, 32);
        else System.arraycopy(b, 0, r, 32 - b.length, b.length);
        return r;
    }

    private static ECParameterSpec parametrosP256() {
        try {
            AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
            p.init(new ECGenParameterSpec("secp256r1"));
            return p.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- utilidades ----------

    private static byte[] hmac(byte[] clave, byte[] datos) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(clave, "HmacSHA256"));
        return mac.doFinal(datos);
    }

    private static byte[] concat(byte[]... partes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : partes) out.writeBytes(p);
        return out.toByteArray();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] b64(String s) {
        return Base64.getUrlDecoder().decode(s.trim().replace('+', '-').replace('/', '_'));
    }

    static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
