package br.fluxosaude.episodio.dominio;

/** ERS §4.3 (espelha fluxo.categoria_bloqueio). */
public enum CategoriaBloqueio {
    ASSISTENCIAL,
    REGULACAO,
    LOGISTICA,
    LEITO_CAPACIDADE,
    ADMINISTRATIVO,
    OUTROS,
    /** RF-035: causa ainda não definida / em investigação (temporária, exige justificativa). */
    NAO_DEFINIDA
}
