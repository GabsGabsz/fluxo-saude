package br.fluxosaude.plantao.dominio;

import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import br.fluxosaude.episodio.dominio.NaturezaEtapa;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Estado ATUAL de um episódio aberto, lido numa única consulta (instantâneo coerente), com o
 * necessário para compor a passagem: só identificadores, códigos, instantes e versões.
 *
 * @param protocoloRegistrado há protocolo externo de regulação registrado
 * @param destinoDefinido     há especialidade/destino requerido definido
 */
public record CasoAtual(UUID episodioId, int versao, UUID etapaId, NaturezaEtapa natureza, UUID setorId,
                        UUID motivoId, CategoriaBloqueio categoria, Instant bloqueioDesde, Instant entradaEm,
                        Instant etapaDesde, Instant ultimoRegistroEm, boolean protocoloRegistrado,
                        boolean destinoDefinido, List<PendenciaAtual> pendencias) {

    public CasoAtual {
        Objects.requireNonNull(episodioId);
        Objects.requireNonNull(etapaId);
        Objects.requireNonNull(natureza);
        Objects.requireNonNull(setorId);
        Objects.requireNonNull(entradaEm);
        Objects.requireNonNull(etapaDesde);
        pendencias = List.copyOf(pendencias);
    }
}
