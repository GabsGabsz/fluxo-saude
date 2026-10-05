package br.fluxosaude.episodio.dominio;

public enum StatusPendencia {
    ABERTA,
    RESOLVIDA,
    CANCELADA,
    ENCERRADA_POR_DESFECHO;

    public boolean encerrada() {
        return this != ABERTA;
    }
}
