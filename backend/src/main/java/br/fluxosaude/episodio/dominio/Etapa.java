package br.fluxosaude.episodio.dominio;

import java.util.Objects;
import java.util.UUID;

/** Etapa configurada da unidade (fluxo.etapa). */
public record Etapa(UUID id, String codigo, String nome, NaturezaEtapa natureza, TipoDesfecho desfecho,
                    boolean inicial, boolean exigeMotivoBloqueio, boolean exigeProtocoloExterno,
                    boolean exigeJustificativa, boolean ativa) {

    public Etapa {
        Objects.requireNonNull(id);
        Objects.requireNonNull(codigo);
        Objects.requireNonNull(nome);
        Objects.requireNonNull(natureza);
        if ((natureza == NaturezaEtapa.DESFECHO) != (desfecho != null)) {
            throw new IllegalArgumentException("etapa de desfecho deve (e só ela pode) ter tipo de desfecho: " + codigo);
        }
        if (natureza == NaturezaEtapa.DESFECHO && (exigeMotivoBloqueio || inicial)) {
            throw new IllegalArgumentException("etapa de desfecho não pode ser inicial nem exigir bloqueio: " + codigo);
        }
    }

    public boolean terminal() {
        return natureza == NaturezaEtapa.DESFECHO;
    }
}
