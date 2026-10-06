package br.fluxosaude.alerta.dominio;

import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Estado operacional de um episódio ABERTO, suficiente para avaliar as regras (só tempos,
 * motivo e prazos — nenhum dado clínico).
 *
 * @param bloqueioDesde      nulo se não está bloqueado
 * @param categoriaBloqueio  categoria do motivo atual (nula se não bloqueado)
 * @param ultimoRegistroEm   último registro na linha do tempo (horário do servidor)
 */
public record SituacaoEpisodio(UUID episodioId, UUID etapaId, Instant entradaEm, Instant etapaDesde,
                               Instant bloqueioDesde, CategoriaBloqueio categoriaBloqueio, Instant ultimoRegistroEm,
                               List<PendenciaAberta> pendencias) {

    /** Pendência aberta: só o necessário para o prazo. */
    public record PendenciaAberta(UUID id, CategoriaBloqueio categoria, Instant prazo) {
        public PendenciaAberta {
            Objects.requireNonNull(id);
            Objects.requireNonNull(categoria);
            Objects.requireNonNull(prazo);
        }
    }

    public SituacaoEpisodio {
        Objects.requireNonNull(episodioId);
        Objects.requireNonNull(etapaId);
        Objects.requireNonNull(entradaEm);
        Objects.requireNonNull(etapaDesde);
        pendencias = List.copyOf(pendencias);
    }
}
