package br.fluxosaude.compartilhado;

import java.util.Map;
import java.util.TreeMap;

/**
 * Serializa um objeto JSON PLANO (chave → texto), suficiente para os dados dos eventos da
 * linha do tempo (o banco só aceita objeto plano, ver fluxo.dados_evento_validos).
 * Escapa conforme RFC 8259; chaves em ordem estável. Evita dependência de biblioteca JSON
 * no núcleo e qualquer chance de serializar objetos arbitrários.
 */
public final class JsonPlano {

    private JsonPlano() {
    }

    public static String de(Map<String, String> dados) {
        StringBuilder sb = new StringBuilder("{");
        boolean primeiro = true;
        for (Map.Entry<String, String> e : new TreeMap<>(dados).entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (!primeiro) {
                sb.append(',');
            }
            primeiro = false;
            escapar(sb, e.getKey());
            sb.append(':');
            escapar(sb, e.getValue());
        }
        return sb.append('}').toString();
    }

    private static void escapar(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
