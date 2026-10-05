package br.fluxosaude.episodio.dominio;

import java.util.regex.Pattern;

/** Validação do Cartão Nacional de Saúde (espelha fluxo.cns_valido no banco). */
public final class Cns {

    private static final Pattern FORMATO = Pattern.compile("^[12789][0-9]{14}$");

    private Cns() {
    }

    public static boolean valido(String cns) {
        if (cns == null || !FORMATO.matcher(cns).matches()) {
            return false;
        }
        int soma = 0;
        for (int i = 0; i < 15; i++) {
            soma += (cns.charAt(i) - '0') * (15 - i);
        }
        return soma % 11 == 0;
    }
}
