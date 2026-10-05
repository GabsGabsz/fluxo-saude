package br.fluxosaude.episodio.dominio;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Identificação minimizada para telas coletivas/TV (RF-038, RNF-015): iniciais
 * (no máximo 3) + 4 caracteres derivados do ID do episódio. Ex.: "M.S.C · 3F2A".
 * Nunca exibe nome completo, CNS ou data de nascimento.
 */
public final class Pseudonimo {

    private static final java.util.Set<String> PARTICULAS = java.util.Set.of("da", "de", "do", "das", "dos", "e");

    private Pseudonimo() {
    }

    public static String de(String nomeCompleto, UUID episodioId) {
        Objects.requireNonNull(episodioId);
        StringBuilder iniciais = new StringBuilder();
        if (nomeCompleto != null) {
            String semAcento = Normalizer.normalize(nomeCompleto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            for (String parte : semAcento.trim().split("\\s+")) {
                if (parte.isEmpty() || PARTICULAS.contains(parte.toLowerCase(Locale.ROOT))
                        || !Character.isLetter(parte.charAt(0))) {
                    continue;
                }
                if (iniciais.length() > 0) {
                    iniciais.append('.');
                }
                iniciais.append(Character.toUpperCase(parte.charAt(0)));
                if (iniciais.length() >= 5) { // 3 iniciais: "A.B.C"
                    break;
                }
            }
        }
        String sufixo = String.format("%016X", episodioId.getLeastSignificantBits()).substring(12);
        return (iniciais.length() == 0 ? "?" : iniciais.toString()) + " · " + sufixo;
    }
}
