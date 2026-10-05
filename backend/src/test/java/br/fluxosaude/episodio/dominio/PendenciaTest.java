package br.fluxosaude.episodio.dominio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.compartilhado.RegraVioladaException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class PendenciaTest {

    private FluxoDeTeste t;
    private Episodio ep;

    @BeforeEach
    void setUp() {
        t = new FluxoDeTeste();
        ep = t.abrirAgora();
    }

    private static String erro(Executable e) {
        return assertThrows(RegraVioladaException.class, e).codigo();
    }

    private Pendencia criar(Duration prazoEm) {
        return Pendencia.criar(ep, new Pendencia.ComandoCriacao(CategoriaBloqueio.LOGISTICA, "  Acionar transporte  ",
                        new Responsavel.Perfil(Papel.TRANSPORTE), t.relogio.instant().plus(prazoEm), Criticidade.ALTA),
                FluxoDeTeste.AUTOR, t.relogio, t.ids);
    }

    @Test
    @DisplayName("RF-007: cria aberta, com texto normalizado e evento sem o texto livre")
    void criaPendencia() {
        Pendencia p = criar(Duration.ofHours(1));
        assertEquals(StatusPendencia.ABERTA, p.status());
        assertEquals("Acionar transporte", p.descricao());
        EventoEpisodio ev = p.retirarEventos().get(0);
        assertEquals(TipoEvento.PENDENCIA_CRIADA, ev.tipo());
        assertEquals(ep.id(), ev.episodioId());
        assertFalse(ev.dados().containsValue("Acionar transporte"), "texto livre não vai para o evento");
    }

    @Test
    void rejeitaPrazoNoPassadoEDescricaoVazia() {
        assertEquals("PRAZO_PASSADO", erro(() -> criar(Duration.ofHours(-1))));
        assertEquals("CAMPO_OBRIGATORIO", erro(() -> Pendencia.criar(ep, new Pendencia.ComandoCriacao(
                CategoriaBloqueio.LOGISTICA, "   ", new Responsavel.Perfil(Papel.TRANSPORTE),
                t.relogio.instant().plusSeconds(60), Criticidade.BAIXA), FluxoDeTeste.AUTOR, t.relogio, t.ids)));
    }

    @Test
    @DisplayName("RN-005: resolução e cancelamento exigem texto")
    void encerramentoExigeTexto() {
        Pendencia p = criar(Duration.ofHours(1));
        assertEquals("CAMPO_OBRIGATORIO", erro(() -> p.resolver(" ", FluxoDeTeste.AUTOR, t.relogio, t.ids)));
        assertEquals("CAMPO_OBRIGATORIO", erro(() -> p.cancelar(null, FluxoDeTeste.AUTOR, t.relogio, t.ids)));
        assertEquals(StatusPendencia.ABERTA, p.status());
        p.resolver("Ambulância acionada", FluxoDeTeste.AUTOR, t.relogio, t.ids);
        assertEquals(StatusPendencia.RESOLVIDA, p.status());
    }

    @Test
    void pendenciaEncerradaEImutavel() {
        Pendencia p = criar(Duration.ofHours(1));
        p.cancelar("Criada por engano", FluxoDeTeste.AUTOR, t.relogio, t.ids);
        assertEquals("PENDENCIA_ENCERRADA", erro(() -> p.alterarPrazo(t.relogio.instant().plusSeconds(600),
                FluxoDeTeste.AUTOR, t.relogio, t.ids)));
        assertEquals("PENDENCIA_ENCERRADA", erro(() -> p.reatribuir(new Responsavel.Usuario(UUID.randomUUID()),
                FluxoDeTeste.AUTOR, t.relogio, t.ids)));
        assertEquals("PENDENCIA_ENCERRADA", erro(() -> p.resolver("x y z", FluxoDeTeste.AUTOR, t.relogio, t.ids)));
    }

    @Test
    void vencimento() {
        Pendencia p = criar(Duration.ofMinutes(30));
        assertFalse(p.vencida(t.relogio.instant()));
        t.relogio.avancar(Duration.ofMinutes(31));
        assertTrue(p.vencida(t.relogio.instant()));
        p.resolver("Feito com atraso", FluxoDeTeste.AUTOR, t.relogio, t.ids);
        assertFalse(p.vencida(t.relogio.instant()), "encerrada não conta como vencida");
    }

    @Test
    @DisplayName("RN-008: desfecho encerra pendências e bloqueia novas")
    void desfecho() {
        Pendencia p = criar(Duration.ofHours(1));
        t.mudar(ep, "ALTA", null);
        p.encerrarPorDesfecho(TipoDesfecho.ALTA, FluxoDeTeste.AUTOR, t.relogio, t.ids);
        assertEquals(StatusPendencia.ENCERRADA_POR_DESFECHO, p.status());
        assertTrue(p.resolucao().contains("ALTA"));
        assertEquals("EPISODIO_ENCERRADO", erro(() -> criar(Duration.ofHours(1))));
    }

    @Test
    void reatribuicaoEPrazoGeramEvento() {
        Pendencia p = criar(Duration.ofHours(1));
        p.retirarEventos();
        p.reatribuir(new Responsavel.Setor(FluxoDeTeste.SETOR), FluxoDeTeste.AUTOR, t.relogio, t.ids);
        p.alterarPrazo(t.relogio.instant().plus(Duration.ofHours(4)), FluxoDeTeste.AUTOR, t.relogio, t.ids);
        p.reatribuir(new Responsavel.Setor(FluxoDeTeste.SETOR), FluxoDeTeste.AUTOR, t.relogio, t.ids); // no-op
        assertEquals(2, p.retirarEventos().size());
    }
}
