package br.fluxosaude.identidade.aplicacao;

import java.security.SecureRandom;
import java.util.function.Supplier;

/**
 * Senha provisória aleatória (CSPRNG): 20 símbolos de um alfabeto de 31 sem caracteres
 * ambíguos (sem 0/o, 1/l/i), em grupos de 5 — cerca de 99 bits de entropia. Entregue
 * uma única vez ao administrador; o usuário é obrigado a trocá-la no primeiro acesso.
 */
public final class GeradorSenhaProvisoria implements Supplier<String> {

    private static final char[] ALFABETO = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final int GRUPOS = 4;
    private static final int POR_GRUPO = 5;

    private final SecureRandom aleatorio = new SecureRandom();

    @Override
    public String get() {
        StringBuilder sb = new StringBuilder(GRUPOS * (POR_GRUPO + 1));
        for (int g = 0; g < GRUPOS; g++) {
            if (g > 0) {
                sb.append('-');
            }
            for (int i = 0; i < POR_GRUPO; i++) {
                sb.append(ALFABETO[aleatorio.nextInt(ALFABETO.length)]);
            }
        }
        return sb.toString();
    }
}
