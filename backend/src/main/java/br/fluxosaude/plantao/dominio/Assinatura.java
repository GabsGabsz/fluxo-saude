package br.fluxosaude.plantao.dominio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 em hexadecimal minúsculo (64 caracteres) de um texto canônico. */
public final class Assinatura {

    private Assinatura() {
    }

    public static String de(String canonico) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(canonico.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    public static boolean valida(String assinatura) {
        return assinatura != null && assinatura.matches("^[0-9a-f]{64}$");
    }
}
