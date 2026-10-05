package br.fluxosaude.compartilhado;

import java.util.regex.Pattern;

/** Validação de textos livres — mesmos limites dos CHECKs do banco. */
public final class Textos {

    private static final Pattern CONTROLE = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");

    private Textos() {
    }

    /**
     * Normaliza (trim) e valida tamanho. Rejeita caracteres de controle (exceto
     * quebra de linha/tab), que não têm uso legítimo e servem a ataques de log/terminal.
     */
    public static String obrigatorio(String valor, String campo, int min, int max) {
        RegraVioladaException.exigir(valor != null && !valor.isBlank(), "CAMPO_OBRIGATORIO",
                campo + " é obrigatório");
        String t = valor.strip();
        RegraVioladaException.exigir(t.length() >= min && t.length() <= max, "CAMPO_TAMANHO",
                campo + " deve ter entre " + min + " e " + max + " caracteres");
        RegraVioladaException.exigir(!CONTROLE.matcher(t).find(), "CAMPO_INVALIDO",
                campo + " contém caracteres inválidos");
        return t;
    }

    public static String opcional(String valor, String campo, int min, int max) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return obrigatorio(valor, campo, min, max);
    }
}
