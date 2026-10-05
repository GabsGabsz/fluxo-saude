package br.fluxosaude.identidade.dominio;

/** Perfis de acesso da ERS §3 (espelha fluxo.papel). */
public enum Papel {
    ADMINISTRADOR,
    /** Coordenação de fluxo, regulação interna ou NIR — conforme a estrutura real da unidade (RN-015). */
    COORDENACAO_FLUXO,
    ENFERMAGEM,
    MEDICO,
    TRANSPORTE,
    DIRECAO,
    AUDITORIA
}
