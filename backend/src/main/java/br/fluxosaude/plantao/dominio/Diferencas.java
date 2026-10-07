package br.fluxosaude.plantao.dominio;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * O que mudou entre o conteúdo ENTREGUE e o estado atual, mostrado a quem recebe: episódios
 * encerrados ou novos, casos alterados (qualquer campo do conteúdo), pendências encerradas, novas
 * ou alteradas (qualquer campo). Os valores de antes/depois estão em {@link Comparacao}. A
 * confirmação do recebimento assina o conteúdo entregue, estas diferenças e o conteúdo atual: se
 * algo mudar antes do clique, o recebimento é recusado.
 */
public record Diferencas(List<UUID> casosEncerrados, List<UUID> casosNovos, List<UUID> casosAlterados,
                         List<UUID> pendenciasEncerradas, List<UUID> pendenciasNovas, List<UUID> pendenciasAlteradas) {

    public Diferencas {
        casosEncerrados = ordenada(casosEncerrados);
        casosNovos = ordenada(casosNovos);
        casosAlterados = ordenada(casosAlterados);
        pendenciasEncerradas = ordenada(pendenciasEncerradas);
        pendenciasNovas = ordenada(pendenciasNovas);
        pendenciasAlteradas = ordenada(pendenciasAlteradas);
    }

    /** Ids das diferenças; o detalhe (antes/depois, campos) está em {@link Comparacao#entre}. */
    public static Diferencas entre(ConteudoPassagem entregue, ConteudoPassagem atual) {
        return Comparacao.entre(entregue, atual).diferencas();
    }

    public boolean vazia() {
        return casosEncerrados.isEmpty() && casosNovos.isEmpty() && casosAlterados.isEmpty()
                && pendenciasEncerradas.isEmpty() && pendenciasNovas.isEmpty() && pendenciasAlteradas.isEmpty();
    }

    /** Só contagens (o que fica gravado no recebimento: nenhum id ou nome). */
    public Map<String, Integer> contagens() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("casosEncerrados", casosEncerrados.size());
        m.put("casosNovos", casosNovos.size());
        m.put("casosAlterados", casosAlterados.size());
        m.put("pendenciasEncerradas", pendenciasEncerradas.size());
        m.put("pendenciasNovas", pendenciasNovas.size());
        m.put("pendenciasAlteradas", pendenciasAlteradas.size());
        return m;
    }

    /**
     * Assinatura do que o recebedor viu: conteúdo entregue + estas diferenças + o conteúdo ATUAL
     * (os casos e pendências novos ou alterados são exibidos com o estado atual: se mudarem de novo
     * antes do clique, a assinatura muda e o recebimento é recusado).
     */
    public String assinaturaRecebimento(String assinaturaEntregue, String assinaturaAtual) {
        Objects.requireNonNull(assinaturaEntregue);
        Objects.requireNonNull(assinaturaAtual);
        return Assinatura.de("recebimento:v2|" + assinaturaEntregue + "|" + canonico() + "|" + assinaturaAtual);
    }

    public String canonico() {
        return lista("casosEncerrados", casosEncerrados) + lista("casosNovos", casosNovos)
                + lista("casosAlterados", casosAlterados) + lista("pendenciasEncerradas", pendenciasEncerradas)
                + lista("pendenciasNovas", pendenciasNovas) + lista("pendenciasAlteradas", pendenciasAlteradas);
    }

    private static String lista(String nome, List<UUID> ids) {
        return nome + "=" + ids.stream().map(UUID::toString).collect(Collectors.joining(",")) + ";";
    }

    private static List<UUID> ordenada(List<UUID> ids) {
        return ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }
}
