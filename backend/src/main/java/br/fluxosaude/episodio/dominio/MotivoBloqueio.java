package br.fluxosaude.episodio.dominio;

import java.util.Objects;
import java.util.UUID;

/** Motivo de bloqueio configurado da unidade (fluxo.motivo_bloqueio). */
public record MotivoBloqueio(UUID id, CategoriaBloqueio categoria, String codigo, String descricao,
                             boolean exigeDetalhe, boolean ativo) {

    public MotivoBloqueio {
        Objects.requireNonNull(id);
        Objects.requireNonNull(categoria);
        Objects.requireNonNull(codigo);
        Objects.requireNonNull(descricao);
        if (categoria == CategoriaBloqueio.OUTROS && !exigeDetalhe) {
            throw new IllegalArgumentException("motivo da categoria OUTROS deve exigir detalhe");
        }
    }
}
