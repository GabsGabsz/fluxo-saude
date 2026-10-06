package br.fluxosaude.alerta.aplicacao;

import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.SituacaoEpisodio;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência dos alertas. Toda operação roda numa transação com o contexto
 * validado pelo banco (unidade ativa, papéis e versão de credencial) e RLS por unidade.
 */
public interface RepositorioAlertas {

    /** Pendência aberta com o que o painel mostra: próxima ação, responsável e prazo. */
    record PendenciaResumo(UUID id, String descricao, String responsavel, Instant prazo) {
    }

    /** Episódio aberto com dados de exibição (nominais: só para quem tem EPISODIO_VER). */
    record EpisodioMonitorado(SituacaoEpisodio situacao, String pacienteNome, String setorNome, String etapaNome,
                              String motivoDescricao, String categoriaBloqueio, List<PendenciaResumo> pendencias) {
        public EpisodioMonitorado {
            pendencias = List.copyOf(pendencias);
        }
    }

    /** Chave de uma ocorrência de alerta (ver {@code Alerta}), incluindo a versão da regra. */
    record Ocorrencia(UUID episodioId, UUID regraId, int regraVersao, Instant referenciaEm, UUID pendenciaId) {
    }

    record Ciencia(String autorNome, Instant registradaEm) {
    }

    /** Versão e situação vigentes de uma regra, lidas com a regra TRAVADA até o fim da transação. */
    record EstadoRegra(int versao, boolean ativa) {
    }

    List<RegraAlerta> regras(boolean somenteAtivas);

    Optional<RegraAlerta> regra(UUID id);

    boolean etapaDaUnidade(UUID etapaId);

    void inserirRegra(RegraAlerta regra);

    /** UPDATE versionado; versão divergente → {@code ConflitoDeVersaoException}. */
    void atualizarRegra(RegraAlerta regra, int versaoLida);

    /** Até {@code limite} episódios abertos da unidade ativa, do mais antigo para o mais novo. */
    List<EpisodioMonitorado> episodiosAbertos(int limite);

    /** Só os episódios informados que estão abertos (e visíveis na unidade ativa). */
    List<EpisodioMonitorado> episodiosAbertos(Collection<UUID> ids);

    /** Ciências já registradas para os episódios informados. */
    Map<Ocorrencia, Ciencia> ciencias(Collection<UUID> episodioIds);

    /**
     * Trava a regra (FOR SHARE, até o fim da transação) e devolve versão e situação vigentes;
     * vazio se não existir na unidade ativa. Uma alteração concorrente da regra espera esta
     * transação terminar (ou, se confirmou antes, a versão devolvida já é a nova).
     */
    Optional<EstadoRegra> travarRegra(UUID regraId);

    /**
     * Registra a ciência; {@code false} se a mesma ocorrência (na mesma versão) já tinha ciência.
     * O banco recusa versão diferente da vigente com {@code ConflitoDeVersaoException}.
     */
    boolean registrarCiencia(UUID id, Ocorrencia ocorrencia);
}
