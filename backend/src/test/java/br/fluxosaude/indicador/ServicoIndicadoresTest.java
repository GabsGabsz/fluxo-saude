package br.fluxosaude.indicador;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.SituacaoEpisodio;
import br.fluxosaude.alerta.dominio.TipoRegraAlerta;
import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.Papel;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.indicador.aplicacao.RepositorioIndicadores;
import br.fluxosaude.indicador.aplicacao.ServicoIndicadores;
import br.fluxosaude.indicador.aplicacao.TransacaoIndicadores;
import br.fluxosaude.indicador.dominio.DicionarioIndicadores;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Regras do serviço de indicadores: permissão, período, ausência de dados, retrato completo. */
class ServicoIndicadoresTest {

    static final UUID UNIDADE = UUID.randomUUID();
    static final UUID SETOR = UUID.randomUUID();
    static final ContextoOrigem ORIGEM = new ContextoOrigem("10.0.0.1", "teste");
    // 06/10/2026 02:00Z = 05/10/2026 23:00 em Fortaleza (UTC-3): "hoje" local ainda é dia 5.
    static final Instant AGORA = Instant.parse("2026-10-06T02:00:00Z");

    /** Repositório falso: devolve agregados fixos e registra o período pedido. */
    static final class Banco implements TransacaoIndicadores, RepositorioIndicadores {
        List<AcimaDoLimite> limites = List.of();
        List<Motivo> motivos = List.of();
        List<RegraAlerta> regras = List.of();
        List<SituacaoEpisodio> abertos = new ArrayList<>();
        Duracoes permanencia = new Duracoes(0, 0, null, null, null, null);
        LocalDate pedidoInicio;
        LocalDate pedidoFim;

        @Override
        public <T> T executar(UsuarioAutenticado u, ContextoOrigem o, Function<RepositorioIndicadores, T> t) {
            return t.apply(this);
        }

        @Override public String fusoDaUnidade(UUID unidade) { return "America/Fortaleza"; }
        @Override public boolean setorDaUnidade(UUID setor) { return SETOR.equals(setor); }
        @Override public Periodo periodo(String fuso, LocalDate inicio, LocalDate fim) {
            pedidoInicio = inicio;
            pedidoFim = fim;
            return new Periodo(Instant.EPOCH, Instant.EPOCH.plusSeconds(86400));
        }
        @Override public Duracoes permanencia(UUID u, Periodo p, UUID s) { return permanencia; }
        @Override public List<AcimaDoLimite> acimaDosLimites(UUID u, Periodo p, UUID s) { return limites; }
        @Override public List<Desfecho> desfechos(UUID u, Periodo p, UUID s) {
            return List.of(new Desfecho("ALTA", 2), new Desfecho("TRANSFERENCIA", 3));
        }
        @Override public Duracoes solicitacaoAceite(UUID u, Periodo p, UUID s) { return new Duracoes(0, 1, null, null, null, null); }
        @Override public Duracoes aceiteSaida(UUID u, Periodo p, UUID s) { return new Duracoes(0, 0, null, null, null, null); }
        @Override public List<Motivo> motivos(UUID u, Periodo p, UUID s, Instant agora) { return motivos; }
        @Override public List<Dia> volumeDiario(UUID u, String f, LocalDate i, LocalDate fi, UUID s) { return List.of(); }
        @Override public List<ItemRetrato> retrato(UUID u, UUID s, Instant agora) {
            return List.of(new ItemRetrato("ABERTOS", null, null, abertos.size()));
        }
        @Override public List<RegraAlerta> regrasAtivas() { return regras; }
        @Override public List<SituacaoEpisodio> situacoesAbertas(UUID setor, int limite) {
            return abertos.stream().limit(limite).toList();
        }
    }

    Banco banco;
    ServicoIndicadores servico;
    final UsuarioAutenticado direcao = usuario(Papel.DIRECAO);

    static UsuarioAutenticado usuario(Papel papel) {
        return new UsuarioAutenticado(UUID.randomUUID(), "u", "U", Map.of(UNIDADE, Set.of(papel)), UNIDADE, false, 1);
    }

    static SituacaoEpisodio aberto(Duration ha) {
        return new SituacaoEpisodio(UUID.randomUUID(), UUID.randomUUID(), AGORA.minus(ha), AGORA.minus(ha), null, null,
                AGORA, List.of());
    }

    @BeforeEach
    void setUp() {
        banco = new Banco();
        servico = new ServicoIndicadores(banco, Clock.fixed(AGORA, ZoneOffset.UTC));
    }

    @Test
    void permissoes() {
        assertThrows(AcessoNegadoException.class, () -> servico.consultar(usuario(Papel.ENFERMAGEM), ORIGEM,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5), null));
        assertThrows(AcessoNegadoException.class, () -> servico.consultar(usuario(Papel.ADMINISTRADOR), ORIGEM,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5), null));
        assertThrows(AcessoNegadoException.class, () -> servico.dicionario(usuario(Papel.MEDICO)));
        servico.consultar(direcao, ORIGEM, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5), null);
        servico.consultar(usuario(Papel.COORDENACAO_FLUXO), ORIGEM, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5), null);
    }

    @Test
    void periodoNoFusoDaUnidade() {
        // "Hoje" em Fortaleza ainda é 05/10: pedir até 06/10 é futuro.
        assertEquals("PERIODO_FUTURO", assertThrows(RegraVioladaException.class, () -> servico.consultar(direcao, ORIGEM,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 6), null)).codigo());
        assertEquals("PERIODO_INVALIDO", assertThrows(RegraVioladaException.class, () -> servico.consultar(direcao, ORIGEM,
                LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 1), null)).codigo());
        assertEquals("PERIODO_LONGO", assertThrows(RegraVioladaException.class, () -> servico.consultar(direcao, ORIGEM,
                LocalDate.of(2025, 10, 4), LocalDate.of(2026, 10, 5), null)).codigo());
        servico.consultar(direcao, ORIGEM, LocalDate.of(2025, 10, 5), LocalDate.of(2026, 10, 5), null); // 366 dias: ok
        assertEquals(LocalDate.of(2025, 10, 5), banco.pedidoInicio);
        assertEquals("SETOR_INVALIDO", assertThrows(RegraVioladaException.class, () -> servico.consultar(direcao, ORIGEM,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5), UUID.randomUUID())).codigo());
        servico.consultar(direcao, ORIGEM, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5), SETOR);
    }

    @Test
    void ausenciaDeDadosNaoViraZeroNemDivisaoPorZero() {
        banco.limites = List.of(new RepositorioIndicadores.AcimaDoLimite(UUID.randomUUID(), "12 h", 0, 720, 0, 0));
        ServicoIndicadores.Resultado r = servico.consultar(direcao, ORIGEM, LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 5), null);
        assertNull(r.historico().permanencia().mediaMin(), "sem encerrados: média ausente");
        assertNull(r.historico().acimaDosLimites().get(0).percentual().valor(), "população zero: percentual ausente");
        assertTrue(r.historico().motivos().isEmpty());
        assertEquals(0.0, r.historico().minutosBloqueadosTotal());
        assertEquals(3, r.historico().transferencias());
        assertEquals("America/Fortaleza", r.fuso());

        banco.limites = List.of(new RepositorioIndicadores.AcimaDoLimite(UUID.randomUUID(), "12 h", 0, 720, 5, 4));
        banco.motivos = List.of(
                new RepositorioIndicadores.Motivo(UUID.randomUUID(), "A", "A", "REGULACAO", 600, 1, 1),
                new RepositorioIndicadores.Motivo(UUID.randomUUID(), "B", "B", "LOGISTICA", 200, 1, 1));
        r = servico.consultar(direcao, ORIGEM, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5), null);
        assertEquals(Double.valueOf(80.0), r.historico().acimaDosLimites().get(0).percentual().valor());
        assertEquals(Double.valueOf(75.0), r.historico().motivos().get(0).percentualDoTempo().valor());
        assertEquals(Double.valueOf(25.0), r.historico().motivos().get(1).percentualDoTempo().valor());
    }

    @Test
    void retratoAcimaDosLimitesSobreTodosOsAbertos() {
        ServicoIndicadores.Resultado semRegras = servico.consultar(direcao, ORIGEM, LocalDate.of(2026, 10, 5),
                LocalDate.of(2026, 10, 5), null);
        assertFalse(semRegras.retrato().regrasConfiguradas(), "sem regras: indisponível, não zero");
        assertFalse(semRegras.retrato().acimaDosLimitesDisponivel());

        UUID regra = UUID.randomUUID();
        banco.regras = List.of(new RegraAlerta(regra, "Permanência 6 h (ilustrativa)", TipoRegraAlerta.TEMPO_TOTAL, null,
                null, Duration.ofHours(6), null, true, 0));
        banco.abertos.add(aberto(Duration.ofHours(6)));     // fronteira: atingiu
        banco.abertos.add(aberto(Duration.ofHours(7)));
        banco.abertos.add(aberto(Duration.ofHours(5)));
        ServicoIndicadores.Resultado r = servico.consultar(direcao, ORIGEM, LocalDate.of(2026, 10, 5),
                LocalDate.of(2026, 10, 5), null);
        assertTrue(r.retrato().acimaDosLimitesDisponivel());
        assertEquals(2, r.retrato().acimaDosLimites().get(0).episodios());

        for (int i = 0; i < ServicoIndicadores.LIMITE_RETRATO; i++) {
            banco.abertos.add(aberto(Duration.ofHours(1)));
        }
        r = servico.consultar(direcao, ORIGEM, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5), null);
        assertFalse(r.retrato().acimaDosLimitesDisponivel(), "acima do limite técnico: indisponível, nunca parcial");
        assertTrue(r.retrato().acimaDosLimites().isEmpty());
    }

    @Test
    void dicionarioCompletoETodasAsFormulasSaoPropostas() {
        var defs = servico.dicionario(direcao);
        assertEquals(9, defs.size());
        defs.forEach(d -> {
            assertEquals(DicionarioIndicadores.PROPOSTA, d.situacao(), d.codigo());
            for (String campo : List.of(d.finalidade(), d.formula(), d.unidadeMedida(), d.populacao(), d.exclusoes(),
                    d.denominador(), d.campoTemporal(), d.abertosEEncerrados(), d.dadosAusentes(), d.periodoEFronteiras())) {
                assertFalse(campo == null || campo.isBlank(), d.codigo() + " com campo vazio");
            }
        });
    }
}
