package br.fluxosaude.relatorio.aplicacao;

import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.SituacaoEpisodio;
import br.fluxosaude.relatorio.dominio.LinhaRelatorio;
import br.fluxosaude.relatorio.dominio.PendenciaOperacional;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Consultas dos relatórios gerenciais (funções fluxo.rel_*, V19). Todas rodam na MESMA transação
 * REPEATABLE READ somente leitura (mesmo instantâneo); RLS restringe à unidade ativa.
 */
public interface RepositorioRelatorios {

    record Periodo(Instant inicio, Instant fim) {
    }

    record Unidade(UUID id, String nome, String fuso) {
    }

    record MudancasRegras(long alteracoes, Instant historicoDesde) {
    }

    Unidade unidade(UUID id);

    /** Nome do setor, se for da unidade ativa (RLS). */
    Optional<String> nomeSetor(UUID setor);

    /** Nome da etapa, se for da unidade ativa (RLS). */
    Optional<String> nomeEtapa(UUID etapa);

    Periodo periodo(String fuso, LocalDate inicio, LocalDate fim);

    List<LinhaRelatorio> resumo(UUID unidade, Periodo p, Instant agora, UUID setor);

    List<LinhaRelatorio> gargalos(UUID unidade, Periodo p, Instant agora, UUID setor, UUID etapa, String categoria);

    List<LinhaRelatorio> pendencias(UUID unidade, Periodo p, Instant agora, UUID setor, String categoria);

    List<PendenciaOperacional> listaPendencias(UUID unidade, Instant agora, UUID setor, String categoria, int limite);

    List<LinhaRelatorio> metricasPeriodo(UUID unidade, Periodo p, Instant agora, UUID setor);

    MudancasRegras mudancasRegras(UUID unidade, Instant desde, Instant ate);

    List<LinhaRelatorio> qualidade(UUID unidade, Periodo p, Instant agora, UUID setor);

    List<RegraAlerta> regrasAtivas();

    /** Situação de TODOS os episódios abertos (até {@code limite}), sem dados nominais. */
    List<SituacaoEpisodio> situacoesAbertas(UUID setor, int limite);
}
