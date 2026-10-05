package br.fluxosaude.compartilhado;

import java.time.Duration;

/** Excesso de tentativas (HTTP 429). */
public class LimiteExcedidoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Duration aguardar;

    public LimiteExcedidoException(Duration aguardar) {
        super("Muitas tentativas. Aguarde alguns minutos.");
        this.aguardar = aguardar;
    }

    public Duration aguardar() {
        return aguardar;
    }
}
