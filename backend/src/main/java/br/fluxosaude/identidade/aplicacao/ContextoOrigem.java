package br.fluxosaude.identidade.aplicacao;

/** Origem da requisição, propagada para a auditoria (RNF-002). */
public record ContextoOrigem(String ip, String correlacaoId) {
}
