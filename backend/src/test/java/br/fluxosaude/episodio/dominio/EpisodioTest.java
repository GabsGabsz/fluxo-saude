package br.fluxosaude.episodio.dominio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.compartilhado.RegraVioladaException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class EpisodioTest {

    private FluxoDeTeste t;

    @BeforeEach
    void setUp() {
        t = new FluxoDeTeste();
    }

    private static String codigoDoErro(Executable e) {
        return assertThrows(RegraVioladaException.class, e).codigo();
    }

    private static List<TipoEvento> tipos(List<EventoEpisodio> eventos) {
        return eventos.stream().map(EventoEpisodio::tipo).collect(Collectors.toList());
    }

    @Test
    @DisplayName("ERS §11: cenário completo de transferência reproduz os tempos 25h06 e 1h50")
    void cenarioCompletoDaErs() {
        Episodio ep = t.abrirAgora();                                        // 08:12 entrada
        t.relogio.avancar(Duration.ofMinutes(110));                           // 10:02 decisão
        t.mudar(ep, "AGUARDANDO_SOLICITACAO_TRANSFERENCIA", "SOLICITACAO_NAO_ENVIADA");
        t.relogio.avancar(Duration.ofMinutes(12));                            // 10:14 protocolo
        Instant solicitacao = t.relogio.instant();
        t.mudar(ep, "TRANSFERENCIA_SOLICITADA", "AGUARDANDO_ANALISE_ACEITE",
                new ProtocoloExterno("REGULA_PI", "2026-000123"), null);
        t.relogio.avancar(Duration.ofHours(24));                              // dia seguinte 10:14
        t.mudar(ep, "AGUARDANDO_RECURSO_LEITO", "SEM_LEITO_ESPECIALIDADE");
        t.relogio.avancar(Duration.ofMinutes(66));                            // 11:20 aceite
        Instant aceite = t.relogio.instant();
        t.mudar(ep, "ACEITO", null);
        t.relogio.avancar(Duration.ofMinutes(2));                             // 11:22 transporte
        t.mudar(ep, "AGUARDANDO_TRANSPORTE", "TRANSPORTE_PENDENTE");
        t.relogio.avancar(Duration.ofMinutes(108));                           // 13:10 saída
        Instant saida = t.relogio.instant();
        t.mudar(ep, "TRANSFERIDO", null);

        assertEquals(Duration.ofHours(25).plusMinutes(6), Duration.between(solicitacao, aceite));
        assertEquals(Duration.ofHours(1).plusMinutes(50), Duration.between(aceite, saida));

        assertTrue(ep.encerrado());
        assertEquals(TipoDesfecho.TRANSFERENCIA, ep.desfecho().orElseThrow());
        assertFalse(ep.bloqueio().isPresent(), "desfecho remove o bloqueio");

        // RN-008: relógios param no encerramento
        Duration total = ep.tempoTotal(t.relogio.instant());
        t.relogio.avancar(Duration.ofDays(3));
        assertEquals(total, ep.tempoTotal(t.relogio.instant()));
        assertEquals(Duration.ofHours(28).plusMinutes(58), total);

        List<TipoEvento> eventos = tipos(ep.retirarEventos());
        assertEquals(TipoEvento.EPISODIO_ABERTO, eventos.get(0));
        assertEquals(TipoEvento.EPISODIO_ENCERRADO, eventos.get(eventos.size() - 1));
        assertTrue(eventos.contains(TipoEvento.PROTOCOLO_REGISTRADO));
        assertTrue(ep.retirarEventos().isEmpty(), "eventos são entregues uma única vez");
    }

    @Nested
    @DisplayName("Abertura (RF-002)")
    class Abertura {
        @Test
        void abreNaEtapaInicialComEvento() {
            Episodio ep = t.abrirAgora();
            assertEquals(t.etapa("EM_ATENDIMENTO"), ep.etapaId());
            assertEquals(ep.entradaEm(), ep.etapaDesde());
            assertEquals(List.of(TipoEvento.EPISODIO_ABERTO), tipos(ep.retirarEventos()));
            assertEquals(7, ep.id().version(), "ID é UUIDv7");
        }

        @Test
        void rejeitaEntradaNoFuturo() {
            Instant futuro = t.relogio.instant().plus(Duration.ofMinutes(10));
            assertEquals("DATA_FUTURA", codigoDoErro(() -> Episodio.abrir(
                    new Episodio.ComandoAbertura(FluxoDeTeste.PACIENTE, FluxoDeTeste.SETOR,
                            MomentoInformado.ajustado(futuro, null), null), false,
                    t.fluxo, FluxoDeTeste.AUTOR, t.relogio, t.ids)));
        }

        @Test
        void aceitaPequenaDiferencaDeRelogio() {
            Instant quase = t.relogio.instant().plus(Duration.ofSeconds(90));
            Episodio.abrir(new Episodio.ComandoAbertura(FluxoDeTeste.PACIENTE, FluxoDeTeste.SETOR,
                    MomentoInformado.ajustado(quase, null), null), false, t.fluxo, FluxoDeTeste.AUTOR, t.relogio, t.ids);
        }

        @Test
        void rejeitaRetroatividadeAcimaDoLimite() {
            Instant antigo = t.relogio.instant().minus(Duration.ofHours(25));
            assertEquals("RETROATIVIDADE_EXCEDIDA", codigoDoErro(() -> Episodio.abrir(
                    new Episodio.ComandoAbertura(FluxoDeTeste.PACIENTE, FluxoDeTeste.SETOR,
                            MomentoInformado.ajustado(antigo, "Registro atrasado"), null), false,
                    t.fluxo, FluxoDeTeste.AUTOR, t.relogio, t.ids)));
        }
    }

    @Nested
    @DisplayName("Mudança de etapa (RF-004, RF-008, RF-009, RN-003)")
    class MudancaDeEtapa {
        @Test
        void exigeMotivoEmEtapaDeEspera() {
            Episodio ep = t.abrirAgora();
            assertEquals("RN-003", codigoDoErro(() -> t.mudar(ep, "AGUARDANDO_EXAME_PARECER", null)));
        }

        @Test
        void rejeitaTransicaoForaDoGrafo() {
            Episodio ep = t.abrirAgora();
            assertEquals("TRANSICAO_NAO_PERMITIDA", codigoDoErro(() -> t.mudar(ep, "ACEITO", null)));
        }

        @Test
        void rejeitaEtapaInativa() {
            Episodio ep = t.abrirAgora();
            assertEquals("ETAPA_INATIVA", codigoDoErro(() -> t.mudar(ep, "ETAPA_DESATIVADA", null)));
        }

        @Test
        void rejeitaMotivoInativo() {
            Episodio ep = t.abrirAgora();
            assertEquals("MOTIVO_INATIVO",
                    codigoDoErro(() -> t.mudar(ep, "AGUARDANDO_EXAME_PARECER", "MOTIVO_DESATIVADO")));
        }

        @Test
        void exigeProtocoloParaTransferenciaSolicitada() {
            Episodio ep = t.abrirAgora();
            t.mudar(ep, "AGUARDANDO_SOLICITACAO_TRANSFERENCIA", "SOLICITACAO_NAO_ENVIADA");
            assertEquals("PROTOCOLO_OBRIGATORIO",
                    codigoDoErro(() -> t.mudar(ep, "TRANSFERENCIA_SOLICITADA", "AGUARDANDO_ANALISE_ACEITE")));
        }

        @Test
        void outrosExigeDetalhe() {
            Episodio ep = t.abrirAgora();
            assertEquals("DETALHE_OBRIGATORIO", codigoDoErro(() -> t.mudar(ep, "AGUARDANDO_EXAME_PARECER", "OUTROS")));
            ep.mudarEtapa(new Episodio.ComandoMudancaEtapa(t.etapa("AGUARDANDO_EXAME_PARECER"), MomentoInformado.agora(t.relogio),
                    new Episodio.MotivoInformado(t.motivo("OUTROS"), "Aguardando familiar trazer exame externo"),
                    null, null), t.fluxo, FluxoDeTeste.AUTOR, t.relogio, t.ids);
            assertEquals("Aguardando familiar trazer exame externo", ep.bloqueio().orElseThrow().detalhe());
        }

        @Test
        void naoAceitaFatoAnteriorAoUltimoRegistrado() {
            Episodio ep = t.abrirAgora();
            t.relogio.avancar(Duration.ofHours(1));
            t.mudar(ep, "AGUARDANDO_EXAME_PARECER", "AGUARDANDO_EXAME");
            Instant antes = t.relogio.instant().minus(Duration.ofMinutes(30));
            assertEquals("CRONOLOGIA", codigoDoErro(() -> ep.mudarEtapa(
                    new Episodio.ComandoMudancaEtapa(t.etapa("EM_ATENDIMENTO"),
                            MomentoInformado.ajustado(antes, "Registro atrasado"), null, null, null),
                    t.fluxo, FluxoDeTeste.AUTOR, t.relogio, t.ids)));
        }

        @Test
        void comandoInvalidoNaoAlteraEstadoNemGeraEventos() {
            Episodio ep = t.abrirAgora();
            ep.retirarEventos();
            // Protocolo válido + justificativa indevida => deve falhar sem registrar o protocolo
            assertEquals("JUSTIFICATIVA_INDEVIDA", codigoDoErro(() -> ep.mudarEtapa(
                    new Episodio.ComandoMudancaEtapa(t.etapa("AGUARDANDO_SOLICITACAO_TRANSFERENCIA"),
                            MomentoInformado.agora(t.relogio),
                            new Episodio.MotivoInformado(t.motivo("SOLICITACAO_NAO_ENVIADA"), null),
                            new ProtocoloExterno("REGULA_PI", "123"), "não se aplica"),
                    t.fluxo, FluxoDeTeste.AUTOR, t.relogio, t.ids)));
            assertEquals(t.etapa("EM_ATENDIMENTO"), ep.etapaId());
            assertFalse(ep.protocolo().isPresent());
            assertFalse(ep.bloqueio().isPresent());
            assertTrue(ep.retirarEventos().isEmpty());
        }

        @Test
        void tempoNaEtapaReiniciaAoMudarDeEtapa() {
            Episodio ep = t.abrirAgora();
            t.relogio.avancar(Duration.ofHours(2));
            t.mudar(ep, "AGUARDANDO_EXAME_PARECER", "AGUARDANDO_EXAME");
            t.relogio.avancar(Duration.ofMinutes(45));
            assertEquals(Duration.ofMinutes(45), ep.tempoNaEtapa(t.relogio.instant()));
            assertEquals(Duration.ofMinutes(165), ep.tempoTotal(t.relogio.instant()));
        }
    }

    @Nested
    @DisplayName("Bloqueio (ERS §4.3)")
    class BloqueioDoEpisodio {
        @Test
        void mesmoMotivoPreservaInicioDoBloqueio() {
            Episodio ep = t.abrirAgora();
            t.mudar(ep, "AGUARDANDO_SOLICITACAO_TRANSFERENCIA", "SOLICITACAO_NAO_ENVIADA");
            Instant inicio = ep.bloqueio().orElseThrow().desde();
            t.relogio.avancar(Duration.ofHours(3));
            t.mudar(ep, "TRANSFERENCIA_SOLICITADA", "SOLICITACAO_NAO_ENVIADA",
                    new ProtocoloExterno("REGULA_PI", "1"), null);
            assertEquals(inicio, ep.bloqueio().orElseThrow().desde());
            assertEquals(Duration.ofHours(3), ep.tempoBloqueado(t.relogio.instant()).orElseThrow());
        }

        @Test
        void motivoDiferenteReiniciaRelogioDoBloqueio() {
            Episodio ep = t.abrirAgora();
            t.mudar(ep, "AGUARDANDO_SOLICITACAO_TRANSFERENCIA", "SOLICITACAO_NAO_ENVIADA");
            t.relogio.avancar(Duration.ofHours(3));
            ep.definirMotivo(new Episodio.MotivoInformado(t.motivo("SEM_LEITO_ESPECIALIDADE"), null),
                    MomentoInformado.agora(t.relogio), t.fluxo, FluxoDeTeste.AUTOR, t.relogio, t.ids);
            assertEquals(t.relogio.instant(), ep.bloqueio().orElseThrow().desde());
        }

        @Test
        void naoRemoveMotivoDeEtapaQueExige() {
            Episodio ep = t.abrirAgora();
            t.mudar(ep, "AGUARDANDO_EXAME_PARECER", "AGUARDANDO_EXAME");
            assertEquals("RN-003", codigoDoErro(() -> ep.definirMotivo(null, MomentoInformado.agora(t.relogio), t.fluxo,
                    FluxoDeTeste.AUTOR, t.relogio, t.ids)));
        }

        @Test
        void voltarAoAtendimentoRemoveBloqueio() {
            Episodio ep = t.abrirAgora();
            t.mudar(ep, "AGUARDANDO_EXAME_PARECER", "AGUARDANDO_EXAME");
            t.mudar(ep, "EM_ATENDIMENTO", null);
            assertFalse(ep.bloqueio().isPresent());
            assertTrue(tipos(ep.retirarEventos()).contains(TipoEvento.BLOQUEIO_REMOVIDO));
        }
    }

    @Nested
    @DisplayName("Desfecho (RF-015, RN-008)")
    class Desfecho {
        @Test
        void encerramentoAdministrativoExigeJustificativa() {
            Episodio ep = t.abrirAgora();
            assertEquals("JUSTIFICATIVA_OBRIGATORIA", codigoDoErro(() -> t.mudar(ep, "CANCELADO_ENCERRADO", null)));
            t.mudar(ep, "CANCELADO_ENCERRADO", null, null, "Cadastro aberto em duplicidade");
            assertEquals(TipoDesfecho.ENCERRAMENTO_ADMINISTRATIVO, ep.desfecho().orElseThrow());
            assertEquals("Cadastro aberto em duplicidade", ep.justificativaEncerramento().orElseThrow());
        }

        @Test
        void desfechoNaoAdmiteMotivo() {
            Episodio ep = t.abrirAgora();
            assertEquals("DESFECHO_SEM_BLOQUEIO", codigoDoErro(() -> t.mudar(ep, "ALTA", "AGUARDANDO_EXAME")));
        }

        @Test
        void episodioEncerradoEImutavel() {
            Episodio ep = t.abrirAgora();
            t.mudar(ep, "ALTA", null);
            assertEquals("EPISODIO_ENCERRADO", codigoDoErro(() -> t.mudar(ep, "EM_ATENDIMENTO", null)));
            assertEquals("EPISODIO_ENCERRADO", codigoDoErro(() -> ep.registrarProtocolo(
                    new ProtocoloExterno("REGULA_PI", "1"), MomentoInformado.agora(t.relogio), t.fluxo, FluxoDeTeste.AUTOR,
                    t.relogio, t.ids)));
            assertEquals("EPISODIO_ENCERRADO", codigoDoErro(() -> ep.transferirSetor(
                    java.util.UUID.randomUUID(), MomentoInformado.agora(t.relogio), t.fluxo, FluxoDeTeste.AUTOR,
                    t.relogio, t.ids)));
        }
    }

    @Test
    void protocoloRejeitaCaracteresInvalidos() {
        assertEquals("PROTOCOLO_INVALIDO", codigoDoErro(() -> new ProtocoloExterno("REGULA_PI", "1; DROP TABLE")));
        assertEquals("PROTOCOLO_INVALIDO", codigoDoErro(() -> new ProtocoloExterno("regula", "123")));
    }
}
