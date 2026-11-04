package clima;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parser y escritor JSON mínimo, para no depender de librerías externas. */
final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String texto) {
        Json p = new Json(texto);
        Object v = p.valor();
        p.espacios();
        if (p.i != p.s.length()) throw p.error("texto sobrante");
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    // ---------- lectura ----------

    private Object valor() {
        espacios();
        if (i >= s.length()) throw error("fin inesperado");
        char c = s.charAt(i);
        switch (c) {
            case '{': return objeto();
            case '[': return lista();
            case '"': return cadena();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default: return numero();
        }
    }

    private Map<String, Object> objeto() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        espacios();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            espacios();
            String clave = cadena();
            espacios();
            if (s.charAt(i++) != ':') throw error("se esperaba ':'");
            m.put(clave, valor());
            espacios();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw error("se esperaba ',' o '}'");
        }
    }

    private List<Object> lista() {
        List<Object> l = new ArrayList<>();
        i++;
        espacios();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            l.add(valor());
            espacios();
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw error("se esperaba ',' o ']'");
        }
    }

    private String cadena() {
        if (s.charAt(i) != '"') throw error("se esperaba '\"'");
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: sb.append(e);
            }
        }
    }

    private Double numero() {
        int inicio = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (inicio == i) throw error("valor inválido");
        return Double.parseDouble(s.substring(inicio, i));
    }

    private void literal(String palabra) {
        if (!s.startsWith(palabra, i)) throw error("se esperaba " + palabra);
        i += palabra.length();
    }

    private void espacios() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("JSON inválido (" + msg + ") en posición " + i);
    }

    // ---------- escritura ----------

    static String escribir(Object o) {
        StringBuilder sb = new StringBuilder();
        escribir(o, sb, "");
        return sb.toString();
    }

    private static void escribir(Object o, StringBuilder sb, String sangria) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int n = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append(sangria).append("  ");
                texto(e.getKey().toString(), sb);
                sb.append(": ");
                escribir(e.getValue(), sb, sangria + "  ");
                sb.append(++n < m.size() ? ",\n" : "\n");
            }
            sb.append(sangria).append('}');
        } else if (o instanceof List<?> l) {
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            for (int k = 0; k < l.size(); k++) {
                sb.append(sangria).append("  ");
                escribir(l.get(k), sb, sangria + "  ");
                sb.append(k + 1 < l.size() ? ",\n" : "\n");
            }
            sb.append(sangria).append(']');
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else {
            texto(o.toString(), sb);
        }
    }

    private static void texto(String t, StringBuilder sb) {
        sb.append('"');
        for (char c : t.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
