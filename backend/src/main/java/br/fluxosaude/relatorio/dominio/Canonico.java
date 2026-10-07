package br.fluxosaude.relatorio.dominio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Forma canônica e assinatura SHA-256 de um conjunto de dados de relatório: a mesma assinatura
 * aparece na tela, na impressão, no CSV e no registro de auditoria da exportação, identificando
 * exatamente o conjunto exportado. Campos separados por '|' e linhas por '\n'; texto com escape
 * (\\, \|, \n) para que a forma seja inequívoca.
 */
public final class Canonico {

    private Canonico() {
    }

    public static String campo(Object v) {
        if (v == null) {
            return "∅";
        }
        String s = v.toString();
        return s.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "\\n").replace("\r", "\\r");
    }

    public static String linha(Object... campos) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < campos.length; i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(campo(campos[i]));
        }
        return sb.toString();
    }

    public static String juntar(List<String> linhas) {
        return linhas.stream().collect(Collectors.joining("\n"));
    }

    public static String sha256(String texto) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
