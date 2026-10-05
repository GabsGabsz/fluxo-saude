package br.fluxosaude.compartilhado;

import java.util.Objects;

/**
 * Violação de regra de negócio. O {@code codigo} é estável (contrato da API,
 * ex.: "RN-003") e a mensagem é legível para o usuário final — nunca contém dados
 * pessoais, para poder ir a logs sem risco.
 */
public class RegraVioladaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String codigo;

    public RegraVioladaException(String codigo, String mensagem) {
        super(mensagem);
        this.codigo = Objects.requireNonNull(codigo);
    }

    public String codigo() {
        return codigo;
    }

    public static void exigir(boolean condicao, String codigo, String mensagem) {
        if (!condicao) {
            throw new RegraVioladaException(codigo, mensagem);
        }
    }
}
