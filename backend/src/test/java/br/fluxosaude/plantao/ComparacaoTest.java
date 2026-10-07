package br.fluxosaude.plantao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import br.fluxosaude.episodio.dominio.CriticidadeOperacional;
import br.fluxosaude.plantao.dominio.Comparacao;
import br.fluxosaude.plantao.dominio.ConteudoPassagem;
import br.fluxosaude.plantao.dominio.ConteudoPassagem.CasoPassagem;
import br.fluxosaude.plantao.dominio.ConteudoPassagem.PendenciaPassagem;
import br.fluxosaude.plantao.dominio.Diferencas;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Entregue × atual, item a item (revisão do PR #10, ponto 2): cada mudança traz o valor entregue,
 * o atual e os campos que mudaram; e o conteúdo entregue + as mudanças exibidas reconstroem
 * EXATAMENTE o conteúdo atual (o que a assinatura do recebimento cobre).
 */
class ComparacaoTest {

    static final Instant T = Instant.parse("2026-10-06T12:00:00Z");

    static UUID id(int n) {
        return new UUID(7, n);
    }

    static CasoPassagem caso(int n, int versao, UUID etapa, UUID setor, UUID motivo, List<PendenciaPassagem> pendencias) {
        return new CasoPassagem(id(n), versao, etapa, setor, motivo, motivo == null ? null : CategoriaBloqueio.REGULACAO,
                motivo == null ? null : T.minus(Duration.ofHours(1)), T.minus(Duration.ofHours(8)), T.minus(Duration.ofHours(2)),
                false, false, List.of(), pendencias);
    }

    static PendenciaPassagem pend(int n, int versao, UUID setor, String papel, Instant prazo) {
        return new PendenciaPassagem(id(n), versao, CategoriaBloqueio.LOGISTICA, CriticidadeOperacional.MEDIA, prazo, false,
                null, setor, papel);
    }

    @Test
    void cadaMudancaComEntregueAtualECampos() {
        UUID e1 = id(901);
        UUID e2 = id(902);
        UUID s1 = id(801);
        UUID s2 = id(802);
        ConteudoPassagem entregue = new ConteudoPassagem(List.of(
                caso(1, 0, e1, s1, null, List.of(pend(11, 0, s1, null, T.plusSeconds(3600)), pend(12, 0, s1, null, T))),
                caso(2, 0, e1, s1, null, List.of()),
                caso(3, 0, e1, s1, null, List.of(pend(31, 0, s1, null, T)))));
        ConteudoPassagem atual = new ConteudoPassagem(List.of(
                // caso 1: setor e etapa mudaram; pendência 11 trocou de responsável e prazo; 12 encerrada; 13 nova
                caso(1, 2, e2, s2, null, List.of(pend(11, 1, null, "MEDICO", T.plusSeconds(7200)),
                        pend(13, 0, s2, null, T))),
                caso(2, 0, e1, s1, null, List.of()),                       // igual: não aparece
                caso(4, 0, e1, s1, null, List.of(pend(41, 0, s1, null, T)))));  // novo (3 encerrado)
        Comparacao c = Comparacao.entre(entregue, atual);

        Comparacao.Caso alterado = c.casos().stream().filter(x -> x.episodioId().equals(id(1))).findFirst().orElseThrow();
        assertEquals(Comparacao.Tipo.ALTERADO, alterado.tipo());
        assertEquals(List.of(Comparacao.CampoCaso.ETAPA, Comparacao.CampoCaso.SETOR), alterado.campos());
        assertEquals(s1, alterado.entregue().setorId());
        assertEquals(s2, alterado.atual().setorId());

        Comparacao.Pendencia p11 = c.pendencias().stream().filter(x -> x.id().equals(id(11))).findFirst().orElseThrow();
        assertEquals(id(1), p11.episodioId());
        assertEquals(List.of(Comparacao.CampoPendencia.RESPONSAVEL, Comparacao.CampoPendencia.PRAZO), p11.campos());
        assertEquals(s1, p11.entregue().responsavelSetorId());
        assertNull(p11.atual().responsavelSetorId());
        assertEquals("MEDICO", p11.atual().responsavelPapel());

        Diferencas d = c.diferencas();
        assertEquals(List.of(id(3)), d.casosEncerrados());
        assertEquals(List.of(id(4)), d.casosNovos());
        assertEquals(List.of(id(1)), d.casosAlterados());
        assertEquals(List.of(id(12), id(31)), d.pendenciasEncerradas());
        assertEquals(List.of(id(13), id(41)), d.pendenciasNovas());
        assertEquals(List.of(id(11)), d.pendenciasAlteradas());
        assertEquals(id(3), c.pendencias().stream().filter(x -> x.id().equals(id(31))).findFirst().orElseThrow().episodioId(),
                "pendência encerrada junto com o caso: vinculada ao caso entregue");
        assertEquals(d, Diferencas.entre(entregue, atual));

        assertEquals(atual.canonico(), reconstruir(entregue, c).canonico(),
                "entregue + mudanças exibidas = conteúdo atual assinado");
    }

    @Test
    void mudancaSoDeVersaoApareceComoOutroRegistro() {
        ConteudoPassagem entregue = new ConteudoPassagem(List.of(caso(1, 0, id(901), id(801), null, List.of())));
        ConteudoPassagem atual = new ConteudoPassagem(List.of(caso(1, 1, id(901), id(801), null, List.of())));
        Comparacao c = Comparacao.entre(entregue, atual);
        assertEquals(List.of(Comparacao.CampoCaso.OUTRO_REGISTRO), c.casos().get(0).campos());
        assertEquals(atual.canonico(), reconstruir(entregue, c).canonico());
        assertTrue(Comparacao.entre(entregue, entregue).casos().isEmpty());
    }

    static ConteudoPassagem.AlertaPassagem alerta(int regra, int versao, Instant referencia) {
        return new ConteudoPassagem.AlertaPassagem(id(600 + regra), versao, br.fluxosaude.alerta.dominio.TipoRegraAlerta.TEMPO_TOTAL,
                referencia, referencia.plus(Duration.ofHours(2)), null);
    }

    static CasoPassagem comAlertas(int versao, List<ConteudoPassagem.AlertaPassagem> alertas) {
        return new CasoPassagem(id(1), versao, id(901), id(801), null, null, null, T.minus(Duration.ofHours(8)),
                T.minus(Duration.ofHours(2)), !alertas.isEmpty(), false, alertas, List.of());
    }

    /** Revisão do PR #10 (alertas): troca A → B com a MESMA quantidade aparece como removido + adicionado. */
    @Test
    void trocaDeAlertaComMesmaQuantidadeIdentificaQualSaiuEQualEntrou() {
        Instant ref = T.minus(Duration.ofHours(8));
        ConteudoPassagem entregue = new ConteudoPassagem(List.of(comAlertas(0, List.of(alerta(1, 0, ref), alerta(3, 2, ref)))));
        ConteudoPassagem atual = new ConteudoPassagem(List.of(comAlertas(0, List.of(alerta(2, 0, ref), alerta(3, 2, ref)))));
        Comparacao.Caso caso = Comparacao.entre(entregue, atual).casos().get(0);
        assertEquals(List.of(Comparacao.CampoCaso.ALERTAS), caso.campos());
        Map<UUID, Comparacao.TipoAlerta> porRegra = new HashMap<>();
        caso.alertas().forEach(a -> porRegra.put(a.regraId(), a.tipo()));
        assertEquals(Map.of(id(601), Comparacao.TipoAlerta.REMOVIDO, id(602), Comparacao.TipoAlerta.ADICIONADO,
                id(603), Comparacao.TipoAlerta.MANTIDO), porRegra, "A removido, B adicionado, C mantido — não só 2 → 2");
        Comparacao.AlertaMudanca b = caso.alertas().stream().filter(a -> a.regraId().equals(id(602))).findFirst().orElseThrow();
        assertNull(b.entregue());
        assertEquals(alerta(2, 0, ref), b.atual());
        assertEquals(atual.canonico(), reconstruir(entregue, Comparacao.entre(entregue, atual)).canonico());
    }

    @Test
    void mesmaRegraEmOutraVersaoEhAlertaAlterado() {
        Instant ref = T.minus(Duration.ofHours(8));
        ConteudoPassagem entregue = new ConteudoPassagem(List.of(comAlertas(0, List.of(alerta(1, 0, ref)))));
        ConteudoPassagem atual = new ConteudoPassagem(List.of(comAlertas(0, List.of(alerta(1, 1, ref)))));
        Comparacao.AlertaMudanca a = Comparacao.entre(entregue, atual).casos().get(0).alertas().get(0);
        assertEquals(Comparacao.TipoAlerta.ALTERADO, a.tipo());
        assertEquals(List.of(Comparacao.CampoAlerta.VERSAO_REGRA), a.campos());
        assertEquals(0, a.entregue().regraVersao());
        assertEquals(1, a.atual().regraVersao());
        assertTrue(Comparacao.entre(entregue, entregue).casos().isEmpty());
    }

    /** O que a tela faz: parte do entregue e aplica cada mudança exibida (valores atuais). */
    static ConteudoPassagem reconstruir(ConteudoPassagem entregue, Comparacao c) {
        Map<UUID, CasoPassagem> casos = new HashMap<>();
        entregue.casos().forEach(x -> casos.put(x.episodioId(), x));
        Map<UUID, Map<UUID, PendenciaPassagem>> pendencias = new HashMap<>();
        entregue.casos().forEach(x -> {
            Map<UUID, PendenciaPassagem> m = new HashMap<>();
            x.pendencias().forEach(p -> m.put(p.id(), p));
            pendencias.put(x.episodioId(), m);
        });
        for (Comparacao.Caso x : c.casos()) {
            if (x.tipo() == Comparacao.Tipo.ENCERRADO) {
                casos.remove(x.episodioId());
                pendencias.remove(x.episodioId());
            } else {
                casos.put(x.episodioId(), x.atual());
                pendencias.computeIfAbsent(x.episodioId(), k -> new HashMap<>());
            }
        }
        for (Comparacao.Pendencia p : c.pendencias()) {
            Map<UUID, PendenciaPassagem> m = pendencias.get(p.episodioId());
            if (p.tipo() == Comparacao.Tipo.ENCERRADO) {
                if (m != null) {
                    m.remove(p.id());
                }
            } else {
                m.put(p.id(), p.atual());
            }
        }
        List<CasoPassagem> r = new ArrayList<>();
        casos.forEach((id, x) -> r.add(new CasoPassagem(x.episodioId(), x.versao(), x.etapaId(), x.setorId(), x.motivoId(),
                x.categoria(), x.bloqueioDesde(), x.entradaEm(), x.etapaDesde(), x.critico(), x.transferencia(), x.alertas(),
                new ArrayList<>(pendencias.get(id).values()))));
        return new ConteudoPassagem(r);
    }
}
