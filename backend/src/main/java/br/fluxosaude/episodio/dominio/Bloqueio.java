package br.fluxosaude.episodio.dominio;

import br.fluxosaude.compartilhado.Textos;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Motivo atual pelo qual o episódio está parado (ERS §4.3). */
public record Bloqueio(UUID motivoId, String detalhe, Instant desde) {

    public Bloqueio {
        Objects.requireNonNull(motivoId);
        Objects.requireNonNull(desde);
        detalhe = Textos.opcional(detalhe, "Detalhe do bloqueio", 3, 500);
    }
}
