package br.fluxosaude.episodio.aplicacao;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lado de leitura (telas). Modelos achatados, sem regras: o que pode ser lido é decidido
 * pelo RLS (unidade) e pelo {@link ServicoConsultas} (permissão).
 */
public interface Consultas {

    /** Ordenações permitidas (RF-010). Lista fechada: nunca texto livre em ORDER BY. */
    enum Ordem { TEMPO_TOTAL, TEMPO_NA_ETAPA, TEMPO_BLOQUEADO, CRITICIDADE, SETOR, ETAPA, MOTIVO, PRAZO }

    /** Filtros da Torre de Controle (RF-012). Campos nulos = sem filtro. */
    record FiltroTorre(UUID setorId, UUID etapaId, UUID motivoId, String categoriaBloqueio, UUID especialidadeId,
                       UUID responsavelUsuarioId, Integer minutosMinimosNaEtapa, Boolean somenteComPendenciaVencida,
                       Ordem ordem, boolean decrescente, int limite) {
    }

    record LinhaTorre(UUID episodioId, int versao, UUID pacienteId, String pacienteNome,
                      UUID setorId, String setorNome, UUID etapaId, String etapaCodigo, String etapaNome,
                      String natureza, Instant entradaEm, Instant etapaDesde,
                      UUID motivoId, String motivoCodigo, String motivoDescricao, String categoriaBloqueio,
                      Instant bloqueioDesde, String protocoloSistema, String protocoloNumero,
                      String especialidadeNome, int pendenciasAbertas, int pendenciasVencidas,
                      Instant proximoPrazo, String maiorCriticidade) {
    }

    record LinhaPendencia(UUID id, int versao, String categoria, String descricao, UUID responsavelUsuarioId,
                     String responsavelUsuarioNome, UUID responsavelSetorId, String responsavelSetorNome,
                     String responsavelPapel, Instant prazo, String criticidade, String status, Instant criadaEm,
                     String resolucao, Instant encerradaEm) {
    }

    record LinhaEvento(UUID id, String tipo, Instant ocorridoEm, Instant registradoEm, UUID autorId, String autorNome,
                  String dadosJson, UUID corrigeEventoId) {
    }

    record Observacao(UUID id, String texto, UUID autorId, String autorNome, Instant registradaEm) {
    }

    /** Limites da leitura do caso (resposta limitada, mesmo com histórico muito longo). */
    int MAX_EVENTOS_CASO = 1000;
    int MAX_PENDENCIAS_CASO = 200;
    int MAX_OBSERVACOES_CASO = 200;

    /**
     * Caso completo. Com histórico acima dos limites, vêm os registros MAIS RECENTES (e todas as
     * pendências abertas primeiro) e {@code historicoTruncado} = true — nunca corte silencioso.
     */
    record Caso(LinhaTorre resumo, String pacienteCns, String pacienteIdentificador, java.time.LocalDate pacienteNascimento,
                String destinoDescricao, String motivoDetalhe, String desfecho, Instant encerradoEm,
                String justificativaEncerramento, String justificativaDuplicidade,
                List<LinhaPendencia> pendencias, List<LinhaEvento> linhaDoTempo, List<Observacao> observacoes,
                boolean historicoTruncado) {
    }

    /** Linha do painel coletivo: o nome sai daqui só para virar pseudônimo no servidor. */
    record LinhaPainel(UUID episodioId, String pacienteNome, String setorNome, String etapaNome, String natureza,
                       Instant entradaEm, Instant etapaDesde, String categoriaBloqueio, Instant bloqueioDesde,
                       int pendenciasVencidas) {
    }

    List<LinhaTorre> torre(FiltroTorre filtro);

    Optional<Caso> caso(UUID episodioId);

    List<LinhaPainel> painel(int limite);

    /** RNF-002: registra consulta nominal a um caso (auditoria de acesso). */
    void registrarConsultaDeCaso(UUID episodioId, UUID unidadeId);
}
