package br.fluxosaude.episodio.dominio;

/**
 * CriticidadeOperacional OPERACIONAL da pendência (urgência de ação/atraso).
 * Nunca é classificação de risco clínico, nunca é derivada de dado clínico e não
 * deve ser apresentada como equivalente a ela (RN-001, RN-007, RN-013).
 */
public enum CriticidadeOperacional {
    BAIXA,
    MEDIA,
    ALTA,
    CRITICA
}
