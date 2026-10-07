package br.fluxosaude.plantao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.TipoRegraAlerta;
import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import br.fluxosaude.episodio.dominio.CriticidadeOperacional;
import br.fluxosaude.episodio.dominio.NaturezaEtapa;
import br.fluxosaude.plantao.dominio.Assinatura;
import br.fluxosaude.plantao.dominio.CasoAtual;
import br.fluxosaude.plantao.dominio.ComposicaoPassagem;
import br.fluxosaude.plantao.dominio.ConteudoPassagem;
import br.fluxosaude.plantao.dominio.Diferencas;
import br.fluxosaude.plantao.dominio.PendenciaAtual;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Composição, assinatura canônica e diferenças da passagem de plantão (domínio puro). */
class ComposicaoPassagemTest {

    static final Instant AGORA = Instant.parse("2026-10-06T12:00:00Z");
    static final UUID ETAPA = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    static final UUID SETOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }

    static CasoAtual caso(int n, int versao, NaturezaEtapa natureza, boolean protocolo, boolean destino,
                          List<PendenciaAtual> pendencias) {
        return new CasoAtual(id(n), versao, ETAPA, natureza, SETOR, null, null, null,
                AGORA.minus(Duration.ofHours(10)), AGORA.minus(Duration.ofHours(1)), AGORA.minus(Duration.ofMinutes(30)),
                protocolo, destino, pendencias);
    }

    static PendenciaAtual pendencia(int n, int versao, CriticidadeOperacional crit, Instant prazo) {
        return new PendenciaAtual(id(n), versao, CategoriaBloqueio.REGULACAO, crit, prazo, null, SETOR, null);
    }

    static RegraAlerta tempoTotal(Duration limite, int versao) {
        return new RegraAlerta(id(900), "Permanência (ilustrativa)", TipoRegraAlerta.TEMPO_TOTAL, null, null, limite,
                "Acionar coordenação", true, versao);
    }

    @Test
    void todosOsAbertosEntramComAsMarcacoesDeContinuidade() {
        List<CasoAtual> casos = List.of(
                caso(1, 0, NaturezaEtapa.ATENDIMENTO, false, false, List.of()),
                caso(2, 0, NaturezaEtapa.ESPERA, true, false, List.of()),                 // protocolo => transferência
                caso(3, 0, NaturezaEtapa.ESPERA, false, true, List.of()),                 // destino => transferência
                caso(4, 0, NaturezaEtapa.ACEITO, false, false, List.of()),
                caso(5, 0, NaturezaEtapa.TRANSPORTE, false, false, List.of()),
                caso(6, 0, NaturezaEtapa.ATENDIMENTO, false, false,
                        List.of(pendencia(61, 0, CriticidadeOperacional.CRITICA, AGORA.plusSeconds(3600)))),
                caso(7, 0, NaturezaEtapa.ATENDIMENTO, false, false,
                        List.of(pendencia(71, 0, CriticidadeOperacional.ALTA, AGORA))));       // prazo == agora: não vencida
        ConteudoPassagem c = ComposicaoPassagem.compor(casos, List.of(), AGORA);
        assertEquals(7, c.totalCasos(), "nada é filtrado: todos os abertos entram");
        assertEquals(4, c.totalTransferencias());
        assertEquals(1, c.totalCriticos(), "sem regras: só a pendência CRÍTICA marca o caso como crítico");
        assertEquals(2, c.totalPendencias());
        assertEquals(0, c.totalVencidas(), "fronteira: prazo igual a agora ainda não venceu");

        ConteudoPassagem depois = ComposicaoPassagem.compor(casos, List.of(), AGORA.plusMillis(1));
        assertEquals(1, depois.totalVencidas(), "um instante depois do prazo: vencida");
        assertNotEquals(c.assinatura(), depois.assinatura(), "vencimento muda o conteúdo (e a assinatura)");
    }

    @Test
    void alertaOperacionalMarcaCritico() {
        List<CasoAtual> casos = List.of(caso(1, 0, NaturezaEtapa.ATENDIMENTO, false, false, List.of()));
        ConteudoPassagem abaixo = ComposicaoPassagem.compor(casos, List.of(tempoTotal(Duration.ofHours(11), 0)), AGORA);
        ConteudoPassagem noLimite = ComposicaoPassagem.compor(casos, List.of(tempoTotal(Duration.ofHours(10), 0)), AGORA);
        assertEquals(0, abaixo.totalCriticos());
        assertEquals(1, noLimite.totalCriticos(), "limite atingido (>=) => alerta => crítico");
        assertEquals(1, noLimite.casos().get(0).alertas().size());
        // nova versão da regra = conteúdo diferente
        ConteudoPassagem regraV1 = ComposicaoPassagem.compor(casos, List.of(tempoTotal(Duration.ofHours(10), 1)), AGORA);
        assertNotEquals(noLimite.assinatura(), regraV1.assinatura());
    }

    @Test
    void assinaturaDeterministicaIndependeDaOrdemDeLeitura() {
        List<CasoAtual> casos = new ArrayList<>(List.of(
                caso(3, 2, NaturezaEtapa.ESPERA, false, false, List.of(
                        pendencia(32, 0, CriticidadeOperacional.BAIXA, AGORA.plusSeconds(60)),
                        pendencia(31, 1, CriticidadeOperacional.MEDIA, AGORA.plusSeconds(60)))),
                caso(1, 0, NaturezaEtapa.ATENDIMENTO, false, false, List.of())));
        ConteudoPassagem a = ComposicaoPassagem.compor(casos, List.of(), AGORA);
        java.util.Collections.reverse(casos);
        ConteudoPassagem b = ComposicaoPassagem.compor(casos, List.of(), AGORA);
        assertEquals(a.canonico(), b.canonico());
        assertEquals(a.assinatura(), b.assinatura());
        assertTrue(Assinatura.valida(a.assinatura()));
        assertEquals(id(1), a.casos().get(0).episodioId(), "ordem canônica por id");

        // Qualquer mudança relevante muda a assinatura: versão do episódio, versão da pendência.
        List<CasoAtual> outraVersao = List.of(caso(1, 1, NaturezaEtapa.ATENDIMENTO, false, false, List.of()),
                casos.get(1));
        assertNotEquals(a.assinatura(), ComposicaoPassagem.compor(outraVersao, List.of(), AGORA).assinatura());
    }

    @Test
    void conteudoCanonicoSoTemIdentificadoresCodigosEInstantes() {
        ConteudoPassagem c = ComposicaoPassagem.compor(List.of(caso(1, 0, NaturezaEtapa.ESPERA, true, false,
                List.of(pendencia(11, 0, CriticidadeOperacional.ALTA, AGORA.plusSeconds(60))))), List.of(), AGORA);
        String json = c.canonico();
        assertTrue(json.startsWith("{\"formato\":1,\"casos\":[{\"episodioId\":\"" + id(1)));
        assertFalse(json.contains(" "), "sem texto livre (nenhum espaço)");
        assertThrows(IllegalArgumentException.class, () -> new ConteudoPassagem.PendenciaPassagem(id(1), 0,
                CategoriaBloqueio.OUTROS, CriticidadeOperacional.ALTA, AGORA, false, null, null, "nome de pessoa"));
    }

    @Test
    void diferencasEntreEntregueEAtual() {
        List<CasoAtual> entregues = List.of(
                caso(1, 0, NaturezaEtapa.ATENDIMENTO, false, false, List.of(
                        pendencia(11, 0, CriticidadeOperacional.ALTA, AGORA.plusSeconds(600)),
                        pendencia(12, 0, CriticidadeOperacional.ALTA, AGORA.plusSeconds(600)))),
                caso(2, 0, NaturezaEtapa.ATENDIMENTO, false, false, List.of()),
                caso(3, 0, NaturezaEtapa.ATENDIMENTO, false, false, List.of()));
        ConteudoPassagem entregue = ComposicaoPassagem.compor(entregues, List.of(), AGORA);
        Diferencas nenhuma = Diferencas.entre(entregue, ComposicaoPassagem.compor(entregues, List.of(), AGORA));
        assertTrue(nenhuma.vazia());

        // Depois: episódio 2 encerrado, 3 alterado, 4 novo; pendência 12 resolvida, 11 venceu, 13 nova.
        List<CasoAtual> atuais = List.of(
                caso(1, 0, NaturezaEtapa.ATENDIMENTO, false, false, List.of(
                        pendencia(11, 0, CriticidadeOperacional.ALTA, AGORA.plusSeconds(600)),
                        pendencia(13, 0, CriticidadeOperacional.BAIXA, AGORA.plusSeconds(9000)))),
                caso(3, 1, NaturezaEtapa.ATENDIMENTO, false, false, List.of()),
                caso(4, 0, NaturezaEtapa.ATENDIMENTO, false, false, List.of()));
        Diferencas d = Diferencas.entre(entregue, ComposicaoPassagem.compor(atuais, List.of(), AGORA.plusSeconds(601)));
        assertEquals(List.of(id(2)), d.casosEncerrados());
        assertEquals(List.of(id(4)), d.casosNovos());
        assertEquals(List.of(id(3)), d.casosAlterados());
        assertEquals(List.of(id(12)), d.pendenciasEncerradas());
        assertEquals(List.of(id(13)), d.pendenciasNovas());
        assertEquals(List.of(id(11)), d.pendenciasAlteradas(), "vencimento é alteração relevante");
        assertEquals(Integer.valueOf(1), d.contagens().get("casosEncerrados"));

        ConteudoPassagem atual = ComposicaoPassagem.compor(atuais, List.of(), AGORA.plusSeconds(601));
        String s1 = d.assinaturaRecebimento(entregue.assinatura(), atual.assinatura());
        assertNotEquals(s1, nenhuma.assinaturaRecebimento(entregue.assinatura(), entregue.assinatura()),
                "o recebimento assina também as diferenças vistas");
        // Caso NOVO muda de novo (mesma lista de ids nas diferenças): a assinatura também muda.
        List<CasoAtual> atuaisDepois = List.of(atuais.get(0), atuais.get(1),
                caso(4, 1, NaturezaEtapa.ESPERA, false, false, List.of()));
        ConteudoPassagem atual2 = ComposicaoPassagem.compor(atuaisDepois, List.of(), AGORA.plusSeconds(601));
        Diferencas d2 = Diferencas.entre(entregue, atual2);
        assertEquals(d.canonico(), d2.canonico(), "mesmos ids nas diferenças");
        assertNotEquals(s1, d2.assinaturaRecebimento(entregue.assinatura(), atual2.assinatura()),
                "o estado atual exibido também é assinado");
    }
}
