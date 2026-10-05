package br.fluxosaude.episodio.dominio;

import br.fluxosaude.compartilhado.RegraVioladaException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Configuração do fluxo de UMA unidade: etapas, grafo de transições e motivos.
 * Imutável e validada na construção (configuração inconsistente não entra no domínio).
 */
public final class FluxoConfigurado {

    /** Aresta do grafo de transições. */
    public record Transicao(UUID origemId, UUID destinoId) {
        public Transicao {
            Objects.requireNonNull(origemId);
            Objects.requireNonNull(destinoId);
        }
    }

    private final UUID unidadeId;
    private final Map<UUID, Etapa> etapas;
    private final Set<Transicao> transicoes;
    private final Map<UUID, MotivoBloqueio> motivos;
    private final Etapa etapaInicial;
    private final PoliticaTempo politicaTempo;

    public FluxoConfigurado(UUID unidadeId, Collection<Etapa> etapas, Collection<Transicao> transicoes,
                            Collection<MotivoBloqueio> motivos, PoliticaTempo politicaTempo) {
        this.unidadeId = Objects.requireNonNull(unidadeId);
        this.politicaTempo = Objects.requireNonNull(politicaTempo);
        Map<UUID, Etapa> e = new HashMap<>();
        for (Etapa etapa : etapas) {
            if (e.put(etapa.id(), etapa) != null) {
                throw new IllegalArgumentException("etapa duplicada: " + etapa.id());
            }
        }
        this.etapas = Map.copyOf(e);

        List<Etapa> iniciais = e.values().stream().filter(x -> x.inicial() && x.ativa()).toList();
        if (iniciais.size() != 1) {
            throw new IllegalStateException("a unidade deve ter exatamente uma etapa inicial ativa (tem " + iniciais.size() + ")");
        }
        this.etapaInicial = iniciais.get(0);

        Set<Transicao> t = new HashSet<>();
        for (Transicao tr : transicoes) {
            Etapa origem = this.etapas.get(tr.origemId());
            if (origem == null || !this.etapas.containsKey(tr.destinoId())) {
                throw new IllegalStateException("transição referencia etapa inexistente");
            }
            if (origem.terminal()) {
                throw new IllegalStateException("etapa terminal com transição de saída: " + origem.codigo());
            }
            if (tr.origemId().equals(tr.destinoId())) {
                throw new IllegalStateException("transição para a própria etapa: " + origem.codigo());
            }
            t.add(tr);
        }
        this.transicoes = Set.copyOf(t);

        Map<UUID, MotivoBloqueio> m = new HashMap<>();
        for (MotivoBloqueio motivo : motivos) {
            m.put(motivo.id(), motivo);
        }
        this.motivos = Map.copyOf(m);
    }

    public UUID unidadeId() {
        return unidadeId;
    }

    public Etapa etapaInicial() {
        return etapaInicial;
    }

    public PoliticaTempo politicaTempo() {
        return politicaTempo;
    }

    public Etapa etapa(UUID id) {
        Etapa e = etapas.get(id);
        RegraVioladaException.exigir(e != null, "ETAPA_INEXISTENTE", "Etapa não pertence à unidade");
        return e;
    }

    public MotivoBloqueio motivo(UUID id) {
        MotivoBloqueio m = motivos.get(id);
        RegraVioladaException.exigir(m != null, "MOTIVO_INEXISTENTE", "Motivo de bloqueio não pertence à unidade");
        return m;
    }

    public boolean transicaoPermitida(UUID origemId, UUID destinoId) {
        return transicoes.contains(new Transicao(origemId, destinoId));
    }

    public List<Etapa> destinosPossiveis(UUID origemId) {
        return transicoes.stream()
                .filter(t -> t.origemId().equals(origemId))
                .map(t -> etapas.get(t.destinoId()))
                .filter(Etapa::ativa)
                .toList();
    }
}
