package br.fluxosaude.episodio.dominio;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Fato imutável da linha do tempo. {@code ocorridoEm} é o instante do fato (pode
 * ser retroativo); o instante de registro é atribuído pelo banco.
 * {@code dados} contém apenas identificadores e códigos — nunca texto clínico.
 */
public record EventoEpisodio(UUID id, UUID episodioId, TipoEvento tipo, Instant ocorridoEm,
                             UUID autorId, Map<String, String> dados, UUID corrigeEventoId) {

    public EventoEpisodio {
        Objects.requireNonNull(id);
        Objects.requireNonNull(episodioId);
        Objects.requireNonNull(tipo);
        Objects.requireNonNull(ocorridoEm);
        Objects.requireNonNull(autorId);
        dados = Map.copyOf(dados == null ? Map.of() : dados);
    }
}
