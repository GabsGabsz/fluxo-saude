package br.fluxosaude.episodio.dominio;

/**
 * Criticidade OPERACIONAL da pendência. Não é classificação de risco clínica
 * (RN-001, RN-007) e nunca é derivada automaticamente de dados clínicos.
 */
public enum Criticidade {
    BAIXA,
    MEDIA,
    ALTA,
    CRITICA
}
