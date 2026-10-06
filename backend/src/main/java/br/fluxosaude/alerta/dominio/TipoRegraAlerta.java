package br.fluxosaude.alerta.dominio;

/**
 * Tipos de regra de "paciente travado" (ERS §10.2, RF-018). Espelha {@code fluxo.tipo_regra_alerta}.
 * Todos derivam de tempo, pendência ou falta de atualização — nunca de dado clínico (Anexo B).
 */
public enum TipoRegraAlerta {
    /** Etapa atual há mais que o limite (opcional: só numa etapa — ex.: "transporte atrasado"). */
    TEMPO_NA_ETAPA,
    /** Episódio aberto há mais que o limite. */
    TEMPO_TOTAL,
    /** Bloqueado há mais que o limite (opcional: só uma categoria de motivo). */
    TEMPO_BLOQUEADO,
    /** Nenhum registro na linha do tempo há mais que o limite ("atualização não realizada"). */
    SEM_ATUALIZACAO,
    /** Pendência aberta com prazo vencido (o prazo é o da própria pendência; sem limite). */
    PENDENCIA_VENCIDA;

    public boolean exigeLimite() {
        return this != PENDENCIA_VENCIDA;
    }

    public boolean aceitaEtapa() {
        return this != PENDENCIA_VENCIDA;
    }

    public boolean aceitaCategoria() {
        return this == TEMPO_BLOQUEADO || this == PENDENCIA_VENCIDA;
    }
}
