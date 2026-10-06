package br.fluxosaude.alerta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.alerta.dominio.Alerta;
import br.fluxosaude.alerta.dominio.MotorDeAlertas;
import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.SituacaoEpisodio;
import br.fluxosaude.alerta.dominio.TipoRegraAlerta;
import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.episodio.dominio.CategoriaBloqueio;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Fronteiras das regras com instantes explícitos (relógio controlado). */
class MotorDeAlertasTest {

    static final Instant T0 = Instant.parse("2026-10-06T08:12:00Z");
    static final UUID EP = UUID.randomUUID();
    static final UUID ETAPA_TRANSPORTE = UUID.randomUUID();
    static final UUID ETAPA_OUTRA = UUID.randomUUID();

    static RegraAlerta regra(TipoRegraAlerta tipo, UUID etapa, CategoriaBloqueio cat, Duration limite) {
        return new RegraAlerta(UUID.randomUUID(), "Regra " + tipo, tipo, etapa, cat, limite, "Ação esperada", true, 0);
    }

    static SituacaoEpisodio situacao(UUID etapa, Instant etapaDesde, Instant bloqueioDesde, CategoriaBloqueio cat,
                                     Instant ultimoRegistro, List<SituacaoEpisodio.PendenciaAberta> pendencias) {
        return new SituacaoEpisodio(EP, etapa, T0, etapaDesde, bloqueioDesde, cat, ultimoRegistro, pendencias);
    }

    @Test
    @DisplayName("Tempo na etapa: abaixo do limite não alerta; no limite exato alerta (atingido); acima alerta")
    void fronteiraTempoNaEtapa() {
        Instant desde = T0.plus(Duration.ofHours(1));
        RegraAlerta r = regra(TipoRegraAlerta.TEMPO_NA_ETAPA, ETAPA_TRANSPORTE, null, Duration.ofHours(2));
        SituacaoEpisodio s = situacao(ETAPA_TRANSPORTE, desde, null, null, desde, List.of());
        assertTrue(MotorDeAlertas.avaliar(s, List.of(r), desde.plus(Duration.ofHours(2)).minusNanos(1000)).isEmpty(),
                "1 µs antes do limite: sem alerta");
        List<Alerta> noLimite = MotorDeAlertas.avaliar(s, List.of(r), desde.plus(Duration.ofHours(2)));
        assertEquals(1, noLimite.size(), "limite atingido = alerta");
        assertEquals(desde, noLimite.get(0).referenciaEm());
        assertEquals(desde.plus(Duration.ofHours(2)), noLimite.get(0).atingidoEm());
        assertEquals(Duration.ZERO, noLimite.get(0).tempoAlemDoLimite(desde.plus(Duration.ofHours(2))));
        assertEquals(Duration.ofMinutes(30),
                MotorDeAlertas.avaliar(s, List.of(r), desde.plus(Duration.ofMinutes(150))).get(0)
                        .tempoAlemDoLimite(desde.plus(Duration.ofMinutes(150))));
    }

    @Test
    void regraDeEtapaEspecificaNaoValeParaOutraEtapa() {
        RegraAlerta r = regra(TipoRegraAlerta.TEMPO_NA_ETAPA, ETAPA_TRANSPORTE, null, Duration.ofMinutes(10));
        SituacaoEpisodio s = situacao(ETAPA_OUTRA, T0, null, null, T0, List.of());
        assertTrue(MotorDeAlertas.avaliar(s, List.of(r), T0.plus(Duration.ofDays(2))).isEmpty());
        RegraAlerta qualquer = regra(TipoRegraAlerta.TEMPO_NA_ETAPA, null, null, Duration.ofMinutes(10));
        assertEquals(1, MotorDeAlertas.avaliar(s, List.of(qualquer), T0.plus(Duration.ofMinutes(10))).size());
    }

    @Test
    void tempoTotalContaDaEntrada() {
        RegraAlerta r = regra(TipoRegraAlerta.TEMPO_TOTAL, null, null, Duration.ofHours(24));
        SituacaoEpisodio s = situacao(ETAPA_OUTRA, T0.plus(Duration.ofHours(20)), null, null, T0, List.of());
        assertTrue(MotorDeAlertas.avaliar(s, List.of(r), T0.plus(Duration.ofHours(24)).minusSeconds(1)).isEmpty());
        Alerta a = MotorDeAlertas.avaliar(s, List.of(r), T0.plus(Duration.ofHours(24))).get(0);
        assertEquals(T0, a.referenciaEm(), "conta da entrada, não da etapa");
    }

    @Test
    @DisplayName("Tempo bloqueado: só se bloqueado; filtro de categoria respeitado")
    void tempoBloqueado() {
        RegraAlerta todas = regra(TipoRegraAlerta.TEMPO_BLOQUEADO, null, null, Duration.ofHours(1));
        RegraAlerta soRegulacao = regra(TipoRegraAlerta.TEMPO_BLOQUEADO, null, CategoriaBloqueio.REGULACAO,
                Duration.ofHours(1));
        Instant agora = T0.plus(Duration.ofHours(5));
        SituacaoEpisodio livre = situacao(ETAPA_OUTRA, T0, null, null, T0, List.of());
        assertTrue(MotorDeAlertas.avaliar(livre, List.of(todas, soRegulacao), agora).isEmpty(), "sem bloqueio, sem alerta");
        SituacaoEpisodio leito = situacao(ETAPA_OUTRA, T0, T0.plus(Duration.ofHours(3)),
                CategoriaBloqueio.LEITO_CAPACIDADE, T0, List.of());
        List<Alerta> a = MotorDeAlertas.avaliar(leito, List.of(todas, soRegulacao), agora);
        assertEquals(1, a.size());
        assertEquals(todas.id(), a.get(0).regraId());
        assertEquals(T0.plus(Duration.ofHours(3)), a.get(0).referenciaEm(), "conta do início do bloqueio");
    }

    @Test
    void semAtualizacaoContaDoUltimoRegistro() {
        RegraAlerta r = regra(TipoRegraAlerta.SEM_ATUALIZACAO, null, null, Duration.ofHours(4));
        Instant ultimo = T0.plus(Duration.ofHours(10));
        SituacaoEpisodio s = situacao(ETAPA_OUTRA, T0, null, null, ultimo, List.of());
        assertTrue(MotorDeAlertas.avaliar(s, List.of(r), ultimo.plus(Duration.ofHours(4)).minusMillis(1)).isEmpty());
        assertEquals(ultimo, MotorDeAlertas.avaliar(s, List.of(r), ultimo.plus(Duration.ofHours(4))).get(0).referenciaEm());
    }

    @Test
    @DisplayName("Pendência vencida: no prazo exato ainda não venceu (mesma regra da Torre); depois, sim")
    void fronteiraPendenciaVencida() {
        Instant prazo = T0.plus(Duration.ofHours(2));
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        SituacaoEpisodio s = situacao(ETAPA_OUTRA, T0, null, null, T0, List.of(
                new SituacaoEpisodio.PendenciaAberta(p1, CategoriaBloqueio.LOGISTICA, prazo),
                new SituacaoEpisodio.PendenciaAberta(p2, CategoriaBloqueio.ADMINISTRATIVO, prazo.plus(Duration.ofHours(1)))));
        RegraAlerta todas = regra(TipoRegraAlerta.PENDENCIA_VENCIDA, null, null, null);
        assertTrue(MotorDeAlertas.avaliar(s, List.of(todas), prazo).isEmpty(), "prazo exato: não vencida");
        List<Alerta> depois = MotorDeAlertas.avaliar(s, List.of(todas), prazo.plusNanos(1000));
        assertEquals(1, depois.size());
        assertEquals(p1, depois.get(0).pendenciaId());
        assertEquals(2, MotorDeAlertas.avaliar(s, List.of(todas), prazo.plus(Duration.ofHours(2))).size(),
                "uma ocorrência por pendência");
        RegraAlerta soLogistica = regra(TipoRegraAlerta.PENDENCIA_VENCIDA, null, CategoriaBloqueio.LOGISTICA, null);
        assertEquals(List.of(p1), MotorDeAlertas.avaliar(s, List.of(soLogistica), prazo.plus(Duration.ofHours(2)))
                .stream().map(Alerta::pendenciaId).toList());
    }

    @Test
    void regraInativaNaoAlertaEOrdemEhPeloLimiteAtingido() {
        RegraAlerta inativa = new RegraAlerta(UUID.randomUUID(), "Inativa", TipoRegraAlerta.TEMPO_TOTAL, null, null,
                Duration.ofMinutes(1), null, false, 3);
        RegraAlerta total = regra(TipoRegraAlerta.TEMPO_TOTAL, null, null, Duration.ofHours(1));
        RegraAlerta etapa = regra(TipoRegraAlerta.TEMPO_NA_ETAPA, null, null, Duration.ofMinutes(30));
        SituacaoEpisodio s = situacao(ETAPA_OUTRA, T0.plus(Duration.ofHours(2)), null, null, T0, List.of());
        List<Alerta> a = MotorDeAlertas.avaliar(s, List.of(inativa, etapa, total), T0.plus(Duration.ofHours(3)));
        assertEquals(List.of(total.id(), etapa.id()), a.stream().map(Alerta::regraId).toList(),
                "primeiro o limite atingido há mais tempo");
    }

    @Test
    @DisplayName("Parâmetros da regra: sem valores implícitos e coerentes com o tipo (RN-014)")
    void validacaoDaRegra() {
        assertEquals("LIMITE_OBRIGATORIO", assertThrows(RegraVioladaException.class,
                () -> regra(TipoRegraAlerta.TEMPO_TOTAL, null, null, null)).codigo());
        assertEquals("LIMITE_INDEVIDO", assertThrows(RegraVioladaException.class,
                () -> regra(TipoRegraAlerta.PENDENCIA_VENCIDA, null, null, Duration.ofHours(1))).codigo());
        assertEquals("LIMITE_INVALIDO", assertThrows(RegraVioladaException.class,
                () -> regra(TipoRegraAlerta.TEMPO_TOTAL, null, null, Duration.ofSeconds(59))).codigo());
        assertEquals("LIMITE_INVALIDO", assertThrows(RegraVioladaException.class,
                () -> regra(TipoRegraAlerta.TEMPO_TOTAL, null, null, Duration.ofDays(31))).codigo());
        assertEquals("CATEGORIA_INDEVIDA", assertThrows(RegraVioladaException.class,
                () -> regra(TipoRegraAlerta.TEMPO_NA_ETAPA, null, CategoriaBloqueio.LOGISTICA, Duration.ofHours(1))).codigo());
        assertEquals("ETAPA_INDEVIDA", assertThrows(RegraVioladaException.class,
                () -> regra(TipoRegraAlerta.PENDENCIA_VENCIDA, ETAPA_OUTRA, null, null)).codigo());
        regra(TipoRegraAlerta.TEMPO_TOTAL, null, null, Duration.ofMinutes(1));
        regra(TipoRegraAlerta.TEMPO_TOTAL, null, null, Duration.ofDays(30));
    }
}
