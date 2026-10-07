package br.fluxosaude.indicador.dominio;

import java.util.Objects;

/**
 * Verbete do dicionário de indicadores (RF-039): como o número é calculado, de forma explícita,
 * para que relatórios diferentes não o interpretem de jeitos diferentes.
 */
public record DefinicaoIndicador(String codigo, String nome, String requisitos, String finalidade, String formula,
                                 String unidadeMedida, String populacao, String exclusoes, String denominador,
                                 String marcoInicial, String marcoFinal, String campoTemporal,
                                 String abertosEEncerrados, String dadosAusentes, String repeticoes,
                                 String periodoEFronteiras, String situacao) {

    public DefinicaoIndicador {
        Objects.requireNonNull(codigo);
        Objects.requireNonNull(nome);
        Objects.requireNonNull(formula);
        Objects.requireNonNull(situacao);
    }
}
