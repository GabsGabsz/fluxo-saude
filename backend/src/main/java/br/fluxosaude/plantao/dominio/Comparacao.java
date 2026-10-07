package br.fluxosaude.plantao.dominio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Conteúdo ENTREGUE × situação ATUAL, item a item, para quem vai confirmar o recebimento: para
 * cada caso ou pendência que mudou, como estava na entrega, como está agora e quais campos mudaram.
 * É o que a tela mostra; a assinatura do recebimento cobre o conteúdo entregue, as
 * {@link Diferencas} e o conteúdo atual inteiro ({@link Diferencas#assinaturaRecebimento}).
 *
 * <p>Um caso é "alterado" quando QUALQUER campo do conteúdo difere (exceto a lista de
 * pendências, comparada pendência a pendência); uma pendência, quando qualquer campo difere. Assim,
 * tudo o que não aparece como diferença é idêntico no entregue e no atual, e conteúdo entregue +
 * diferenças exibidas reconstroem exatamente o conteúdo atual assinado.
 */
public record Comparacao(Diferencas diferencas, List<Caso> casos, List<Pendencia> pendencias) {

    public enum Tipo { NOVO, ENCERRADO, ALTERADO }

    /** {@code OUTRO_REGISTRO}: mudou só a versão (ex.: observação ou protocolo registrados). */
    public enum CampoCaso { ETAPA, SETOR, MOTIVO_BLOQUEIO, CRITICO, TRANSFERENCIA, ALERTAS, ENTRADA, OUTRO_REGISTRO }

    /** {@code OUTRO_REGISTRO}: mudou só a versão da pendência. */
    public enum CampoPendencia { RESPONSAVEL, PRAZO, VENCIMENTO, CRITICIDADE, CATEGORIA, OUTRO_REGISTRO }

    /** @param entregue nulo quando NOVO; @param atual nulo quando ENCERRADO. */
    public record Caso(Tipo tipo, UUID episodioId, ConteudoPassagem.CasoPassagem entregue,
                       ConteudoPassagem.CasoPassagem atual, List<CampoCaso> campos) {
        public Caso {
            Objects.requireNonNull(tipo);
            Objects.requireNonNull(episodioId);
            campos = List.copyOf(campos);
        }
    }

    /** Pendência sempre vinculada ao seu caso ({@code episodioId}). */
    public record Pendencia(Tipo tipo, UUID episodioId, ConteudoPassagem.PendenciaPassagem entregue,
                            ConteudoPassagem.PendenciaPassagem atual, List<CampoPendencia> campos) {
        public Pendencia {
            Objects.requireNonNull(tipo);
            Objects.requireNonNull(episodioId);
            campos = List.copyOf(campos);
        }

        public UUID id() {
            return entregue != null ? entregue.id() : atual.id();
        }
    }

    public Comparacao {
        Objects.requireNonNull(diferencas);
        casos = List.copyOf(casos);
        pendencias = List.copyOf(pendencias);
    }

    public static Comparacao entre(ConteudoPassagem entregue, ConteudoPassagem atual) {
        Map<UUID, ConteudoPassagem.CasoPassagem> antes = porCaso(entregue);
        Map<UUID, ConteudoPassagem.CasoPassagem> agora = porCaso(atual);
        Map<UUID, Vinculada> pAntes = porPendencia(entregue);
        Map<UUID, Vinculada> pAgora = porPendencia(atual);

        List<Caso> casos = new ArrayList<>();
        antes.forEach((id, c) -> {
            ConteudoPassagem.CasoPassagem n = agora.get(id);
            if (n == null) {
                casos.add(new Caso(Tipo.ENCERRADO, id, c, null, List.of()));
            } else {
                List<CampoCaso> campos = campos(c, n);
                if (!campos.isEmpty()) {
                    casos.add(new Caso(Tipo.ALTERADO, id, c, n, campos));
                }
            }
        });
        agora.forEach((id, n) -> {
            if (!antes.containsKey(id)) {
                casos.add(new Caso(Tipo.NOVO, id, null, n, List.of()));
            }
        });

        List<Pendencia> pendencias = new ArrayList<>();
        pAntes.forEach((id, v) -> {
            Vinculada n = pAgora.get(id);
            if (n == null) {
                pendencias.add(new Pendencia(Tipo.ENCERRADO, v.episodioId(), v.p(), null, List.of()));
            } else {
                List<CampoPendencia> campos = campos(v.p(), n.p());
                if (!campos.isEmpty()) {
                    pendencias.add(new Pendencia(Tipo.ALTERADO, n.episodioId(), v.p(), n.p(), campos));
                }
            }
        });
        pAgora.forEach((id, n) -> {
            if (!pAntes.containsKey(id)) {
                pendencias.add(new Pendencia(Tipo.NOVO, n.episodioId(), null, n.p(), List.of()));
            }
        });

        casos.sort(Comparator.comparing((Caso c) -> c.episodioId().toString()));
        pendencias.sort(Comparator.comparing((Pendencia p) -> p.id().toString()));
        Diferencas d = new Diferencas(
                ids(casos, Tipo.ENCERRADO), ids(casos, Tipo.NOVO), ids(casos, Tipo.ALTERADO),
                idsP(pendencias, Tipo.ENCERRADO), idsP(pendencias, Tipo.NOVO), idsP(pendencias, Tipo.ALTERADO));
        return new Comparacao(d, casos, pendencias);
    }

    /** Campos que mudaram (vazio = caso idêntico, desconsiderando a lista de pendências). */
    static List<CampoCaso> campos(ConteudoPassagem.CasoPassagem a, ConteudoPassagem.CasoPassagem b) {
        List<CampoCaso> r = new ArrayList<>();
        if (!Objects.equals(a.etapaId(), b.etapaId()) || !Objects.equals(a.etapaDesde(), b.etapaDesde())) {
            r.add(CampoCaso.ETAPA);
        }
        if (!Objects.equals(a.setorId(), b.setorId())) {
            r.add(CampoCaso.SETOR);
        }
        if (!Objects.equals(a.motivoId(), b.motivoId()) || a.categoria() != b.categoria()
                || !Objects.equals(a.bloqueioDesde(), b.bloqueioDesde())) {
            r.add(CampoCaso.MOTIVO_BLOQUEIO);
        }
        if (a.critico() != b.critico()) {
            r.add(CampoCaso.CRITICO);
        }
        if (a.transferencia() != b.transferencia()) {
            r.add(CampoCaso.TRANSFERENCIA);
        }
        if (!a.alertas().equals(b.alertas())) {
            r.add(CampoCaso.ALERTAS);
        }
        if (!Objects.equals(a.entradaEm(), b.entradaEm())) {
            r.add(CampoCaso.ENTRADA);
        }
        if (r.isEmpty() && a.versao() != b.versao()) {
            r.add(CampoCaso.OUTRO_REGISTRO);
        }
        return r;
    }

    static List<CampoPendencia> campos(ConteudoPassagem.PendenciaPassagem a, ConteudoPassagem.PendenciaPassagem b) {
        List<CampoPendencia> r = new ArrayList<>();
        if (!Objects.equals(a.responsavelUsuarioId(), b.responsavelUsuarioId())
                || !Objects.equals(a.responsavelSetorId(), b.responsavelSetorId())
                || !Objects.equals(a.responsavelPapel(), b.responsavelPapel())) {
            r.add(CampoPendencia.RESPONSAVEL);
        }
        if (!Objects.equals(a.prazo(), b.prazo())) {
            r.add(CampoPendencia.PRAZO);
        }
        if (a.vencida() != b.vencida()) {
            r.add(CampoPendencia.VENCIMENTO);
        }
        if (a.criticidade() != b.criticidade()) {
            r.add(CampoPendencia.CRITICIDADE);
        }
        if (a.categoria() != b.categoria()) {
            r.add(CampoPendencia.CATEGORIA);
        }
        if (r.isEmpty() && a.versao() != b.versao()) {
            r.add(CampoPendencia.OUTRO_REGISTRO);
        }
        return r;
    }

    // ----------------------------------------------------------------------------

    private record Vinculada(UUID episodioId, ConteudoPassagem.PendenciaPassagem p) {
    }

    private static Map<UUID, ConteudoPassagem.CasoPassagem> porCaso(ConteudoPassagem c) {
        Map<UUID, ConteudoPassagem.CasoPassagem> m = new LinkedHashMap<>();
        c.casos().forEach(x -> m.put(x.episodioId(), x));
        return m;
    }

    private static Map<UUID, Vinculada> porPendencia(ConteudoPassagem c) {
        Map<UUID, Vinculada> m = new LinkedHashMap<>();
        c.casos().forEach(x -> x.pendencias().forEach(p -> m.put(p.id(), new Vinculada(x.episodioId(), p))));
        return m;
    }

    private static List<UUID> ids(List<Caso> casos, Tipo tipo) {
        return casos.stream().filter(c -> c.tipo() == tipo).map(Caso::episodioId).toList();
    }

    private static List<UUID> idsP(List<Pendencia> pendencias, Tipo tipo) {
        return pendencias.stream().filter(p -> p.tipo() == tipo).map(Pendencia::id).toList();
    }
}
