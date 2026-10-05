package br.fluxosaude.episodio.dominio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.compartilhado.RegraVioladaException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Regras introduzidas ou alteradas pela ERS v1.1. */
class RegrasV11Test {

    private FluxoDeTeste t;

    @BeforeEach
    void setUp() {
        t = new FluxoDeTeste();
    }

    private static String erro(Executable e) {
        return assertThrows(RegraVioladaException.class, e).codigo();
    }

    private Episodio abrir(MomentoInformado entrada, boolean jaTemAtivo, String justificativaDup) {
        return Episodio.abrir(new Episodio.ComandoAbertura(FluxoDeTeste.PACIENTE, FluxoDeTeste.SETOR, entrada,
                justificativaDup), jaTemAtivo, t.fluxo, FluxoDeTeste.AUTOR, t.relogio, t.ids);
    }

    @Test
    @DisplayName("RF-003: duplicidade não bloqueia, exige justificativa e gera evento próprio")
    void duplicidadeJustificada() {
        assertEquals("POSSIVEL_DUPLICIDADE", erro(() -> abrir(MomentoInformado.agora(t.relogio), true, null)));
        Episodio ep = abrir(MomentoInformado.agora(t.relogio), true, "Retorno após evasão");
        assertEquals("Retorno após evasão", ep.justificativaDuplicidade().orElseThrow());
        assertTrue(ep.retirarEventos().stream().anyMatch(e -> e.tipo() == TipoEvento.DUPLICIDADE_JUSTIFICADA));
        // sem episódio ativo, justificativa não é exigida nem registrada
        Episodio normal = abrir(MomentoInformado.agora(t.relogio), false, "ignorada");
        assertFalse(normal.justificativaDuplicidade().isPresent());
    }

    @Test
    @DisplayName("RNF-017: horário anterior ao do servidor é ajuste manual — exige justificativa e fica marcado")
    void ajusteManualDeHorario() {
        Instant vinteMinAtras = t.relogio.instant().minus(Duration.ofMinutes(20));
        assertEquals("AJUSTE_SEM_JUSTIFICATIVA", erro(() -> abrir(MomentoInformado.ajustado(vinteMinAtras, null), false, null)));
        Episodio ep = abrir(MomentoInformado.ajustado(vinteMinAtras, "Paciente chegou durante queda do sistema"), false, null);
        EventoEpisodio aberto = ep.retirarEventos().get(0);
        assertEquals("true", aberto.dados().get("ajuste_manual"));
        assertEquals("Paciente chegou durante queda do sistema", aberto.dados().get("ajuste_justificativa"));
        assertEquals(vinteMinAtras, ep.entradaEm());
    }

    @Test
    void horarioDentroDoLimiarNaoEhAjuste() {
        Episodio ep = abrir(MomentoInformado.ajustado(t.relogio.instant().minus(Duration.ofMinutes(3)), null), false, null);
        assertFalse(ep.retirarEventos().get(0).dados().containsKey("ajuste_manual"));
    }

    @Test
    @DisplayName("Retroatividade é parâmetro da unidade (RN-014: nada hardcoded)")
    void retroatividadeParametrizavel() {
        PoliticaTempo ampla = new PoliticaTempo(Duration.ofMinutes(2), Duration.ofHours(48), Duration.ofMinutes(5));
        Instant trintaHoras = t.relogio.instant().minus(Duration.ofHours(30));
        assertEquals("RETROATIVIDADE_EXCEDIDA", erro(() -> PoliticaTempo.PADRAO.validar(
                MomentoInformado.ajustado(trintaHoras, "contingência"), t.relogio.instant(), null, "x")));
        assertTrue(ampla.validar(MomentoInformado.ajustado(trintaHoras, "contingência"), t.relogio.instant(), null, "x")
                .ajusteManual());
        assertThrows(IllegalArgumentException.class,
                () -> new PoliticaTempo(Duration.ofMinutes(2), Duration.ofDays(8), Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("Ajuste manual vale para todos os eventos do mesmo comando")
    void ajusteEmTodosOsEventosDoComando() {
        Episodio ep = t.abrirAgora();
        ep.retirarEventos();
        t.relogio.avancar(Duration.ofHours(1));
        Instant antes = t.relogio.instant().minus(Duration.ofMinutes(30));
        ep.mudarEtapa(new Episodio.ComandoMudancaEtapa(t.etapa("AGUARDANDO_EXAME_PARECER"),
                MomentoInformado.ajustado(antes, "Registro feito ao fim do atendimento"),
                new Episodio.MotivoInformado(t.motivo("AGUARDANDO_EXAME"), null), null, null),
                t.fluxo, FluxoDeTeste.AUTOR, t.relogio, t.ids);
        List<EventoEpisodio> eventos = ep.retirarEventos();
        assertEquals(2, eventos.size());
        assertTrue(eventos.stream().allMatch(e -> "true".equals(e.dados().get("ajuste_manual"))));
    }

    @Test
    @DisplayName("RF-035: causa em investigação exige justificativa")
    void causaEmInvestigacao() {
        MotivoBloqueio investigacao = new MotivoBloqueio(UUID.randomUUID(), CategoriaBloqueio.NAO_DEFINIDA,
                "CAUSA_EM_INVESTIGACAO", "Causa em investigação", true, true);
        assertTrue(investigacao.exigeDetalhe());
        assertThrows(IllegalArgumentException.class, () -> new MotivoBloqueio(UUID.randomUUID(),
                CategoriaBloqueio.NAO_DEFINIDA, "X", "x", false, true));
    }

    @Test
    @DisplayName("RF-038: pseudônimo para telas coletivas não expõe nome")
    void pseudonimo() {
        UUID ep = UUID.fromString("33333333-0000-0000-0000-00000000a3f2");
        assertEquals("M.S.C · A3F2", Pseudonimo.de("Maria da Silva Costa Pereira", ep));
        assertEquals("J.A · A3F2", Pseudonimo.de("  josé   Álvares ", ep));
        assertEquals("? · A3F2", Pseudonimo.de(null, ep));
        assertFalse(Pseudonimo.de("Maria da Silva", ep).contains("Maria"));
    }
}
