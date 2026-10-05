package br.fluxosaude.compartilhado;

/**
 * RF-036 / RNF-014: o registro mudou desde que o usuário o leu (HTTP 409). O cliente deve
 * recarregar e reaplicar a ação — nunca sobrescrever em silêncio.
 */
public class ConflitoDeVersaoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConflitoDeVersaoException() {
        super("O registro foi alterado por outra pessoa. Recarregue e tente novamente.");
    }
}
