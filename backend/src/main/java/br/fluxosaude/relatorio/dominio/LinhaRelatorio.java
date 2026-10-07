package br.fluxosaude.relatorio.dominio;

import java.util.Objects;

/**
 * Uma linha de relatório no formato "longo" (mesma estrutura na tela, na impressão e no CSV). O
 * significado de cada coluna em cada seção está no dicionário ({@link DicionarioRelatorios}).
 * Valores ausentes ficam nulos (nunca zero quando não há dado). Minutos em ponto flutuante.
 *
 * @param periodo ATUAL ou ANTERIOR (só na evolução); nulo nos demais
 */
public record LinhaRelatorio(String periodo, String secao, String chave, String rotulo, String grupo, Long quantidade,
                             Long parte, Long base, Long episodios, Double minutos, Double media, Double mediana,
                             Double p90, Double maximo) {

    public LinhaRelatorio {
        Objects.requireNonNull(secao);
    }

    public LinhaRelatorio comPeriodo(String p) {
        return new LinhaRelatorio(p, secao, chave, rotulo, grupo, quantidade, parte, base, episodios, minutos, media,
                mediana, p90, maximo);
    }

    public LinhaRelatorio comRotulo(String r) {
        return new LinhaRelatorio(periodo, secao, chave, r, grupo, quantidade, parte, base, episodios, minutos, media,
                mediana, p90, maximo);
    }
}
