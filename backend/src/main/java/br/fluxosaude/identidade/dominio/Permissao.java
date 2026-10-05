package br.fluxosaude.identidade.dominio;

/**
 * Ações autorizáveis. A autorização é sempre por PERMISSÃO (nunca "if papel == X" espalhado
 * pelo código), o que permite ajustar a matriz sem caçar regras pelo sistema (RN-015).
 */
public enum Permissao {
    /** Ver casos com identificação nominal (torre, caso, travados). */
    EPISODIO_VER,
    EPISODIO_ABRIR,
    /** Etapa, motivo, protocolo, destino, setor. */
    EPISODIO_ALTERAR,
    EPISODIO_ENCERRAR,
    PENDENCIA_GERENCIAR,
    OBSERVACAO_REGISTRAR,
    /** RNF-017: registrar fato com horário anterior ao do servidor (ajuste manual). */
    HORARIO_AJUSTAR,
    /** RF-003 / RF-037: confirmar duplicidade e reconciliar cadastros. */
    PACIENTE_RECONCILIAR,
    /** RN-016: registrar/resolver divergência com sistema oficial. */
    DIVERGENCIA_REGISTRAR,
    TRANSPORTE_VER_FILA,
    TRANSPORTE_ATUALIZAR,
    PLANTAO_GERENCIAR,
    /** RF-038: painel coletivo pseudonimizado (sem nome/CNS). */
    PAINEL_COLETIVO_VER,
    INDICADORES_VER,
    CONFIGURACAO_GERENCIAR,
    USUARIO_GERENCIAR,
    AUDITORIA_VER
}
