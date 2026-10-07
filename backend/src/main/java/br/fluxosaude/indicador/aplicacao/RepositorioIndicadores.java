package br.fluxosaude.indicador.aplicacao;

import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.SituacaoEpisodio;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Consultas agregadas dos indicadores (funções fluxo.ind_*, V16). Nada aqui devolve nome, CNS ou
 * identificador de episódio/paciente — só contagens e durações (aptas ao perfil Direção).
 */
public interface RepositorioIndicadores {

    record Periodo(Instant inicio, Instant fim) {
    }

    /**
     * Estatística de durações em minutos: média/mediana nulas quando não há dados (nunca zero).
     * {@code naoIncluidos}: na permanência, os excluídos (encerramento administrativo); nos tempos de
     * transferência, os episódios sem o marco inicial registrado (dado ausente).
     */
    record Duracoes(long incluidos, long naoIncluidos, Double mediaMin, Double medianaMin, Double minimoMin, Double maximoMin) {
    }

    record AcimaDoLimite(UUID regraId, String regraNome, int regraVersao, long limiteMin, long populacao, long acima) {
    }

    record Desfecho(String desfecho, long quantidade) {
    }

    record Motivo(UUID motivoId, String codigo, String descricao, String categoria, double minutos, long inicios,
                  long episodios) {
    }

    record Dia(LocalDate dia, long entradas, long saidas) {
    }

    record ItemRetrato(String dimensao, UUID chave, String nome, long quantidade) {
    }

    String fusoDaUnidade(UUID unidade);

    boolean setorDaUnidade(UUID setor);

    Periodo periodo(String fuso, LocalDate inicio, LocalDate fim);

    Duracoes permanencia(UUID unidade, Periodo p, UUID setor);

    List<AcimaDoLimite> acimaDosLimites(UUID unidade, Periodo p, UUID setor);

    List<Desfecho> desfechos(UUID unidade, Periodo p, UUID setor);

    Duracoes solicitacaoAceite(UUID unidade, Periodo p, UUID setor);

    Duracoes aceiteSaida(UUID unidade, Periodo p, UUID setor);

    List<Motivo> motivos(UUID unidade, Periodo p, UUID setor, Instant agora);

    List<Dia> volumeDiario(UUID unidade, String fuso, LocalDate inicio, LocalDate fim, UUID setor);

    List<ItemRetrato> retrato(UUID unidade, UUID setor, Instant agora);

    List<RegraAlerta> regrasAtivas();

    /** Situação de TODOS os episódios abertos (até {@code limite}), sem dados nominais. */
    List<SituacaoEpisodio> situacoesAbertas(UUID setor, int limite);
}
