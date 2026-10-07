package br.fluxosaude.plantao.dominio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * O que mudou entre o conteúdo ENTREGUE e o estado atual, mostrado a quem recebe: episódios
 * encerrados ou novos, casos alterados (versão, marcações ou alertas), pendências encerradas,
 * novas ou alteradas (versão ou vencimento). A confirmação do recebimento assina o conteúdo
 * entregue E estas diferenças: se mudarem antes do clique, o recebimento é recusado.
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

    public static Diferencas entre(ConteudoPassagem entregue, ConteudoPassagem atual) {
        Map<UUID, ConteudoPassagem.CasoPassagem> antes = porId(entregue.casos(), ConteudoPassagem.CasoPassagem::episodioId);
        Map<UUID, ConteudoPassagem.CasoPassagem> agora = porId(atual.casos(), ConteudoPassagem.CasoPassagem::episodioId);
        Map<UUID, ConteudoPassagem.PendenciaPassagem> pAntes = porId(
                entregue.casos().stream().flatMap(c -> c.pendencias().stream()).toList(),
                ConteudoPassagem.PendenciaPassagem::id);
        Map<UUID, ConteudoPassagem.PendenciaPassagem> pAgora = porId(
                atual.casos().stream().flatMap(c -> c.pendencias().stream()).toList(),
                ConteudoPassagem.PendenciaPassagem::id);
        List<UUID> encerrados = new ArrayList<>();
        List<UUID> alterados = new ArrayList<>();
        antes.forEach((id, c) -> {
            ConteudoPassagem.CasoPassagem n = agora.get(id);
            if (n == null) {
                encerrados.add(id);
            } else if (n.versao() != c.versao() || n.critico() != c.critico() || n.transferencia() != c.transferencia()
                    || !n.alertas().equals(c.alertas())) {
                alterados.add(id);
            }
        });
        List<UUID> novos = agora.keySet().stream().filter(id -> !antes.containsKey(id)).toList();
        List<UUID> pEncerradas = new ArrayList<>();
        List<UUID> pAlteradas = new ArrayList<>();
        pAntes.forEach((id, p) -> {
            ConteudoPassagem.PendenciaPassagem n = pAgora.get(id);
            if (n == null) {
                pEncerradas.add(id);
            } else if (n.versao() != p.versao() || n.vencida() != p.vencida()) {
                pAlteradas.add(id);
            }
        });
        List<UUID> pNovas = pAgora.keySet().stream().filter(id -> !pAntes.containsKey(id)).toList();
        return new Diferencas(encerrados, novos, alterados, pEncerradas, pNovas, pAlteradas);
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

    /** Assinatura do que o recebedor viu: conteúdo entregue + estas diferenças. */
    public String assinaturaRecebimento(String assinaturaEntregue) {
        Objects.requireNonNull(assinaturaEntregue);
        return Assinatura.de("recebimento:v1|" + assinaturaEntregue + "|" + canonico());
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

    private static <T> Map<UUID, T> porId(List<T> itens, Function<T, UUID> id) {
        Map<UUID, T> m = new LinkedHashMap<>();
        itens.forEach(i -> m.put(id.apply(i), i));
        return m;
    }
}
