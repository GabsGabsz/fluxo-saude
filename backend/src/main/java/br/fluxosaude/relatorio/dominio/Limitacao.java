package br.fluxosaude.relatorio.dominio;

import java.util.Objects;

/**
 * Limitação de cobertura ou de interpretação exibida JUNTO do relatório (tela, impressão e CSV).
 *
 * @param secao seção a que se refere (nula = relatório inteiro)
 */
public record Limitacao(String codigo, String secao, String texto) {

    public Limitacao {
        Objects.requireNonNull(codigo);
        Objects.requireNonNull(texto);
    }
}
