package br.fluxosaude.compartilhado;

import java.util.Objects;

/**
 * O estado no servidor não corresponde ao que o usuário viu (HTTP 409 com código próprio):
 * ex.: o conteúdo de uma passagem de plantão mudou entre a leitura e a confirmação. Nada é
 * gravado; o cliente relê e o usuário decide de novo — nunca uma confirmação silenciosa.
 */
public class ConflitoDeEstadoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String codigo;

    public ConflitoDeEstadoException(String codigo, String mensagem) {
        super(mensagem);
        this.codigo = Objects.requireNonNull(codigo);
    }

    public String codigo() {
        return codigo;
    }
}
