package br.fluxosaude.compartilhado;

/** Recurso protegido saturado (ex.: verificações de senha simultâneas) — HTTP 503. */
public class SobrecargaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SobrecargaException(String mensagem) {
        super(mensagem);
    }
}
