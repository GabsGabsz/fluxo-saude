package br.fluxosaude.episodio.dominio;

/** Tipos de evento da linha do tempo (RF-014). */
public enum TipoEvento {
    EPISODIO_ABERTO,
    /** RF-003: episódio aberto apesar de outro ativo do mesmo paciente, com justificativa. */
    DUPLICIDADE_JUSTIFICADA,
    ETAPA_ALTERADA,
    BLOQUEIO_DEFINIDO,
    BLOQUEIO_REMOVIDO,
    PROTOCOLO_REGISTRADO,
    DESTINO_DEFINIDO,
    SETOR_ALTERADO,
    PENDENCIA_CRIADA,
    PENDENCIA_ATUALIZADA,
    PENDENCIA_ENCERRADA,
    OBSERVACAO_REGISTRADA,
    EPISODIO_ENCERRADO,
    CORRECAO,
    /** RN-016: divergência com sistema oficial registrada para reconciliação. */
    DIVERGENCIA_REGISTRADA,
    DIVERGENCIA_RESOLVIDA
}
