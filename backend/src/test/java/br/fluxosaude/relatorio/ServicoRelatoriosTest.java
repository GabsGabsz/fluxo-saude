package br.fluxosaude.relatorio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.SituacaoEpisodio;
import br.fluxosaude.alerta.dominio.TipoRegraAlerta;
import br.fluxosaude.compartilhado.ConflitoDeEstadoException;
import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.Papel;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.relatorio.aplicacao.RegistroRelatorios;
import br.fluxosaude.relatorio.aplicacao.RepositorioRelatorios;
import br.fluxosaude.relatorio.aplicacao.ServicoRelatorios;
import br.fluxosaude.relatorio.aplicacao.TokenRelatorio;
import br.fluxosaude.relatorio.aplicacao.TransacaoRelatorios;
import br.fluxosaude.relatorio.dominio.LinhaRelatorio;
import br.fluxosaude.relatorio.dominio.PendenciaOperacional;
import br.fluxosaude.relatorio.dominio.TipoRelatorio;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Relatórios: permissões, filtros, lista nominal sem parcialidade, assinatura do conjunto e exportação. */
class ServicoRelatoriosTest {

    static final UUID UNIDADE = UUID.randomUUID();
    static final UUID SETOR = UUID.randomUUID();
    static final ContextoOrigem ORIGEM = new ContextoOrigem("10.0.0.1", "teste");
    static final Instant T0 = Instant.parse("2026-10-07T15:00:00Z");   // 12:00 em Fortaleza
    static final LocalDate HOJE = LocalDate.of(2026, 10, 7);

    static final class Relogio extends Clock {
        Instant agora = T0;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }

    /** Banco em memória: devolve linhas fixas e registra as chamadas. */
    static final class Banco implements TransacaoRelatorios, RepositorioRelatorios, RegistroRelatorios {
        List<LinhaRelatorio> linhas = new ArrayList<>(List.of(linha("ENTRADAS", null, 3L, 1L)));
        List<PendenciaOperacional> lista = new ArrayList<>();
        List<RegraAlerta> regras = new ArrayList<>();
        List<SituacaoEpisodio> abertos = new ArrayList<>();
        final List<String> registros = new ArrayList<>();
        final List<Map<String, Object>> dadosRegistrados = new ArrayList<>();
        final List<Collection<UUID>> consultasNominais = new ArrayList<>();
        final List<Periodo> periodosPedidos = new ArrayList<>();
        String fuso = "America/Fortaleza";
        int transacoes;

        @Override
        public <T> T executar(UsuarioAutenticado usuario, ContextoOrigem origem, Function<RepositorioRelatorios, T> t) {
            transacoes++;
            return t.apply(this);
        }

        @Override
        public Unidade unidade(UUID id) {
            return new Unidade(id, "UPA Teste", fuso);
        }

        @Override
        public Optional<String> nomeSetor(UUID setor) {
            return SETOR.equals(setor) ? Optional.of("Observação") : Optional.empty();
        }

        @Override
        public Optional<String> nomeEtapa(UUID etapa) {
            return Optional.empty();
        }

        @Override
        public Periodo periodo(String fuso, LocalDate inicio, LocalDate fim) {
            Periodo p = new Periodo(inicio.atStartOfDay(ZoneId.of(fuso)).toInstant(),
                    fim.plusDays(1).atStartOfDay(ZoneId.of(fuso)).toInstant());
            periodosPedidos.add(p);
            return p;
        }

        @Override
        public List<LinhaRelatorio> resumo(UUID u, Periodo p, Instant agora, UUID setor) {
            return linhas;
        }

        @Override
        public List<LinhaRelatorio> gargalos(UUID u, Periodo p, Instant agora, UUID setor, UUID etapa, String categoria) {
            return linhas;
        }

        @Override
        public List<LinhaRelatorio> pendencias(UUID u, Periodo p, Instant agora, UUID setor, String categoria) {
            return linhas;
        }

        @Override
        public List<PendenciaOperacional> listaPendencias(UUID u, Instant agora, UUID setor, String categoria, int limite) {
            return lista.stream().limit(limite).toList();
        }

        @Override
        public List<LinhaRelatorio> metricasPeriodo(UUID u, Periodo p, Instant agora, UUID setor) {
            return linhas;
        }

        @Override
        public MudancasRegras mudancasRegras(UUID u, Instant desde, Instant ate) {
            return new MudancasRegras(2, T0.minus(Duration.ofDays(30)));
        }

        @Override
        public List<LinhaRelatorio> qualidade(UUID u, Periodo p, Instant agora, UUID setor) {
            return linhas;
        }

        @Override
        public List<RegraAlerta> regrasAtivas() {
            return regras;
        }

        @Override
        public List<SituacaoEpisodio> situacoesAbertas(UUID setor, int limite) {
            return abertos;
        }

        @Override
        public long registrarExportacao(UsuarioAutenticado usuario, ContextoOrigem origem, String acao, String tipo,
                                        Map<String, Object> dados) {
            registros.add(acao + ":" + tipo);
            dadosRegistrados.add(dados);
            return registros.size();
        }

        @Override
        public void registrarConsultaNominal(UsuarioAutenticado usuario, ContextoOrigem origem, String tipo,
                                             Collection<UUID> episodios, Map<String, Object> dados) {
            consultasNominais.add(List.copyOf(episodios));
        }
    }

    static LinhaRelatorio linha(String secao, String chave, Long quantidade, Long parte) {
        return new LinhaRelatorio(null, secao, chave, null, null, quantidade, parte, null, null, null, null, null, null, null);
    }

    static UsuarioAutenticado usuario(Papel... papeis) {
        return new UsuarioAutenticado(UUID.randomUUID(), "u", "Usuário", Map.of(UNIDADE, Set.of(papeis)), UNIDADE, false, 1);
    }

    static PendenciaOperacional pend(int n) {
        return new PendenciaOperacional(new UUID(1, n), new UUID(2, n), "Paciente " + n, "Obs", "Etapa", null, "Ação " + n,
                "LOGISTICA", "ALTA", "SETOR", "Obs", T0, T0.minus(Duration.ofHours(1)), true);
    }

    Banco banco;
    Relogio relogio;
    ServicoRelatorios servico;
    final UsuarioAutenticado coord = usuario(Papel.COORDENACAO_FLUXO);
    final UsuarioAutenticado direcao = usuario(Papel.DIRECAO);

    @BeforeEach
    void setUp() {
        banco = new Banco();
        relogio = new Relogio();
        byte[] chave = new byte[32];
        chave[0] = 7;
        servico = new ServicoRelatorios(banco, banco, new TokenRelatorio(chave), relogio);
    }

    ServicoRelatorios.Resultado consultar(UsuarioAutenticado u, TipoRelatorio t) {
        return servico.consultar(u, ORIGEM, t, HOJE.minusDays(6), HOJE, null, null, null);
    }

    @Test
    void permissoesEFiltros() {
        assertThrows(AcessoNegadoException.class, () -> consultar(usuario(Papel.ENFERMAGEM), TipoRelatorio.RESUMO));
        assertEquals("FILTRO_NAO_SUPORTADO", assertThrows(RegraVioladaException.class, () -> servico.consultar(coord, ORIGEM,
                TipoRelatorio.RESUMO, HOJE, HOJE, null, UUID.randomUUID(), null)).codigo());
        assertEquals("FILTRO_NAO_SUPORTADO", assertThrows(RegraVioladaException.class, () -> servico.consultar(coord, ORIGEM,
                TipoRelatorio.QUALIDADE, HOJE, HOJE, null, null, "LOGISTICA")).codigo());
        assertEquals("CATEGORIA_INVALIDA", assertThrows(RegraVioladaException.class, () -> servico.consultar(coord, ORIGEM,
                TipoRelatorio.GARGALOS, HOJE, HOJE, null, null, "X'; DROP")).codigo());
        assertEquals("PERIODO_LONGO", assertThrows(RegraVioladaException.class, () -> servico.consultar(coord, ORIGEM,
                TipoRelatorio.RESUMO, HOJE.minusDays(366), HOJE, null, null, null)).codigo());
        assertEquals("PERIODO_FUTURO", assertThrows(RegraVioladaException.class, () -> servico.consultar(coord, ORIGEM,
                TipoRelatorio.RESUMO, HOJE, HOJE.plusDays(1), null, null, null)).codigo());
        assertEquals("SETOR_INVALIDO", assertThrows(RegraVioladaException.class, () -> servico.consultar(coord, ORIGEM,
                TipoRelatorio.RESUMO, HOJE, HOJE, UUID.randomUUID(), null, null)).codigo());
        ServicoRelatorios.Resultado r = servico.consultar(coord, ORIGEM, TipoRelatorio.RESUMO, HOJE, HOJE, SETOR, null, null);
        assertEquals("Observação", r.filtros().setorNome());
        assertTrue(r.limitacoes().stream().anyMatch(l -> l.codigo().equals("ENTRADAS_SEM_SETOR")),
                "entrada sem setor de entrada vira limitação explícita");
        assertTrue(r.limitacoes().stream().anyMatch(l -> l.codigo().equals("GRUPOS_PEQUENOS")));
        assertTrue(r.cobertura().contains("Não representa toda a rede"));
    }

    @Test
    void listaNominalSoComAcessoNominalENuncaParcial() {
        banco.lista.addAll(List.of(pend(1), pend(2)));
        ServicoRelatorios.Resultado d = consultar(direcao, TipoRelatorio.PENDENCIAS);
        assertFalse(d.listaPendencias().disponivel());
        assertTrue(d.listaPendencias().itens().isEmpty(), "Direção: nenhum dado nominal");
        assertTrue(banco.consultasNominais.isEmpty());

        ServicoRelatorios.Resultado c = consultar(coord, TipoRelatorio.PENDENCIAS);
        assertTrue(c.listaPendencias().disponivel());
        assertEquals(2, c.listaPendencias().itens().size());
        assertEquals(List.of(List.of(new UUID(2, 1), new UUID(2, 2))), banco.consultasNominais,
                "leitura nominal registrada com os episódios exibidos");

        for (int i = 3; i <= ServicoRelatorios.LIMITE_LISTA + 1; i++) {
            banco.lista.add(pend(i));
        }
        ServicoRelatorios.Resultado grande = consultar(coord, TipoRelatorio.PENDENCIAS);
        assertFalse(grande.listaPendencias().disponivel());
        assertTrue(grande.listaPendencias().itens().isEmpty(), "acima do limite: nenhuma lista parcial");
        assertTrue(grande.listaPendencias().motivo().contains("Refine"));
    }

    @Test
    void assinaturaIdentificaOConjunto() {
        ServicoRelatorios.Resultado a = consultar(coord, TipoRelatorio.RESUMO);
        ServicoRelatorios.Resultado b = consultar(coord, TipoRelatorio.RESUMO);
        assertEquals(a.assinatura(), b.assinatura(), "mesmos dados e instantes: mesma assinatura");
        banco.linhas = List.of(linha("ENTRADAS", null, 4L, 1L));
        assertNotEquals(a.assinatura(), consultar(coord, TipoRelatorio.RESUMO).assinatura(), "um valor diferente muda a assinatura");
        assertEquals(64, a.assinatura().length());
        assertEquals("relatorios-v2", a.versaoCalculo());
        assertEquals(T0, a.referencia());
        assertEquals("ENTRADAS", a.verbetes().get("ENTRADAS"));
    }

    @Test
    void evolucaoComparaComPeriodoAnteriorDeMesmaDuracao() {
        ServicoRelatorios.Resultado r = servico.consultar(coord, ORIGEM, TipoRelatorio.EVOLUCAO, LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 10, 6), null, null, null);
        assertEquals(LocalDate.of(2026, 9, 23), r.comparacao().inicio());
        assertEquals(LocalDate.of(2026, 9, 29), r.comparacao().fim());
        assertEquals(168.0, r.comparacao().horasAtual());
        assertEquals(168.0, r.comparacao().horasAnterior());
        assertTrue(r.limitacoes().stream().anyMatch(l -> l.codigo().equals("PERIODOS_ENCERRADOS")));
        assertTrue(r.limitacoes().stream().anyMatch(l -> l.codigo().equals("PERIODOS_EQUIVALENTES")
                && l.texto().contains("mesma duração (168 h)")));
        assertEquals(Set.of("ATUAL", "ANTERIOR"), Set.copyOf(r.linhas().stream().map(LinhaRelatorio::periodo).toList()));
        assertTrue(r.limitacoes().stream().anyMatch(l -> l.codigo().equals("ALERTAS_NAO_COMPARADOS")));
        assertTrue(r.limitacoes().stream().anyMatch(l -> l.codigo().equals("REGRAS_ALTERADAS") && l.texto().startsWith("2 ")));
        assertEquals(1, banco.transacoes, "os dois períodos no MESMO instantâneo (uma transação)");
    }

    /** Revisão do PR #11, ponto 3: hoje ao meio-dia × ontem inteiro não é comparável. */
    @Test
    void evolucaoSoComPeriodosEncerradosNoFusoDaUnidade() {
        // 12:00 em Fortaleza (UTC−3): hoje = 07/10 → o fim pode ser no máximo 06/10.
        RegraVioladaException e = assertThrows(RegraVioladaException.class, () -> servico.consultar(coord, ORIGEM,
                TipoRelatorio.EVOLUCAO, HOJE.minusDays(6), HOJE, null, null, null));
        assertEquals("PERIODO_INCOMPLETO", e.codigo());
        assertTrue(e.getMessage().contains("2026-10-06") && e.getMessage().contains("America/Fortaleza"), e.getMessage());
        assertEquals(0, banco.periodosPedidos.size(), "nada calculado");
        // Fronteira da meia-noite LOCAL: 02:59:59Z de 08/10 ainda é 07/10 em Fortaleza (fim 07/10 recusado)...
        relogio.agora = Instant.parse("2026-10-08T02:59:59Z");
        assertEquals("PERIODO_INCOMPLETO", assertThrows(RegraVioladaException.class, () -> servico.consultar(coord, ORIGEM,
                TipoRelatorio.EVOLUCAO, HOJE, HOJE, null, null, null)).codigo());
        // ...e 03:00:00Z já é 08/10 local: o dia 07/10 está encerrado. Em UTC seria outro resultado.
        relogio.agora = Instant.parse("2026-10-08T03:00:00Z");
        ServicoRelatorios.Resultado r = servico.consultar(coord, ORIGEM, TipoRelatorio.EVOLUCAO, HOJE, HOJE, null, null, null);
        assertEquals(HOJE.minusDays(1), r.comparacao().inicio());
        // Os outros relatórios continuam podendo incluir hoje (estoque atual).
        assertEquals(HOJE.plusDays(1), servico.consultar(coord, ORIGEM, TipoRelatorio.RESUMO, HOJE.plusDays(1),
                HOJE.plusDays(1), null, null, null).fim());
    }

    @Test
    void evolucaoExplicitaDiferencaDeDuracaoPorHorarioDeVerao() {
        banco.fuso = "America/New_York";   // horário de verão termina em 01/11/2026 (dia local de 25 h)
        relogio.agora = Instant.parse("2026-11-10T15:00:00Z");
        ServicoRelatorios.Resultado r = servico.consultar(coord, ORIGEM, TipoRelatorio.EVOLUCAO, LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 1), null, null, null);
        assertEquals(25.0, r.comparacao().horasAtual());
        assertEquals(24.0, r.comparacao().horasAnterior());
        assertTrue(r.limitacoes().stream().anyMatch(l -> l.codigo().equals("PERIODOS_EQUIVALENTES")
                && l.texto().contains("atual 25 h, anterior 24 h")), "mesmo nº de dias locais, durações diferentes");
    }

    /** Revisão do PR #11, ponto 4: definições e limitações vão no próprio resultado assinado. */
    @Test
    void definicoesRelevantesNoResultadoENaAssinatura() {
        for (TipoRelatorio t : TipoRelatorio.values()) {
            LocalDate fim = t == TipoRelatorio.EVOLUCAO ? HOJE.minusDays(1) : HOJE;
            ServicoRelatorios.Resultado r = servico.consultar(coord, ORIGEM, t, fim.minusDays(2), fim, null, null, null);
            Set<String> codigos = Set.copyOf(r.definicoes().stream().map(d -> d.codigo()).toList());
            assertEquals(Set.copyOf(r.verbetes().values()), codigos, t + ": uma definição por verbete referenciado");
            r.definicoes().forEach(d -> {
                assertFalse(d.formula().isBlank());
                assertFalse(d.populacao().isBlank());
                assertFalse(d.unidadeMedida().isBlank());
                assertTrue(d.situacao().startsWith("PROPOSTA"));
            });
        }
        ServicoRelatorios.Resultado ev = servico.consultar(coord, ORIGEM, TipoRelatorio.EVOLUCAO, HOJE.minusDays(7),
                HOJE.minusDays(1), null, null, null);
        assertEquals("PERMANENCIA", ev.verbetes().get("PERMANENCIA"));
        assertEquals("ENCERRADAS", ev.verbetes().get("PENDENCIAS_ENCERRADAS"));
        assertTrue(ev.definicoes().stream().anyMatch(d -> d.codigo().equals("PERMANENCIA")
                && d.exclusoes().contains("encerramento administrativo")));
        assertTrue(ev.definicoes().stream().anyMatch(d -> d.codigo().equals("ENCERRADAS")
                && d.campoTemporal().contains("ÚLTIMO prazo")));
        assertTrue(ev.definicoes().stream().anyMatch(d -> d.codigo().equals("EVOLUCAO")
                && d.formula().contains("pontos percentuais")));
        assertTrue(ev.limitacoes().stream().anyMatch(l -> l.codigo().equals("PERMANENCIA_SEM_ADMINISTRATIVO")
                && "PERMANENCIA".equals(l.secao())));
        assertTrue(ev.limitacoes().stream().anyMatch(l -> l.codigo().equals("PRAZO_ULTIMO")
                && "PENDENCIAS_ENCERRADAS".equals(l.secao())));
        assertTrue(ev.limitacoes().stream().anyMatch(l -> l.codigo().equals("UNIDADES_DA_VARIACAO")));
        // Mesmo conjunto, mesmo instante: mesma assinatura; as definições entram na forma canônica.
        assertEquals(ev.assinatura(), servico.consultar(coord, ORIGEM, TipoRelatorio.EVOLUCAO, HOJE.minusDays(7),
                HOJE.minusDays(1), null, null, null).assinatura());
        ServicoRelatorios.Resultado direcaoQ = servico.consultar(direcao, ORIGEM, TipoRelatorio.PENDENCIAS, HOJE, HOJE, null,
                null, null);
        assertFalse(direcaoQ.definicoes().toString().contains("Paciente"), "definições sem dado nominal");
    }

    @Test
    void qualidadeSinalizaFatosSemSetorDaEpoca() {
        banco.linhas = new ArrayList<>(List.of(linha("SETOR_NAO_ATRIBUIDO", "REGISTROS", 2L, null),
                linha("SETOR_NAO_ATRIBUIDO", "BLOQUEIOS_INICIADOS", 1L, null)));
        ServicoRelatorios.Resultado r = servico.consultar(coord, ORIGEM, TipoRelatorio.QUALIDADE, HOJE, HOJE, SETOR, null, null);
        assertTrue(r.limitacoes().stream().anyMatch(l -> l.codigo().equals("SETOR_NAO_ATRIBUIDO") && l.texto().startsWith("3 ")
                && l.texto().contains("não foram atribuídos ao setor atual")));
        assertTrue(r.limitacoes().stream().anyMatch(l -> l.codigo().equals("REGISTRO_X_FATO")));
        assertTrue(r.limitacoes().stream().anyMatch(l -> l.codigo().equals("ESCOPO_LINHA_DO_TEMPO")
                && "LINHA_DO_TEMPO".equals(l.secao())));
        ServicoRelatorios.Resultado sem = servico.consultar(coord, ORIGEM, TipoRelatorio.QUALIDADE, HOJE, HOJE, null, null, null);
        assertTrue(sem.limitacoes().stream().noneMatch(l -> l.codigo().equals("SETOR_NAO_ATRIBUIDO")));
    }

    @Test
    void alertasSoAgoraESemRegraNaoViraZero() {
        ServicoRelatorios.Resultado sem = consultar(coord, TipoRelatorio.RESUMO);
        assertTrue(sem.linhas().stream().noneMatch(l -> l.secao().equals("CASOS_EM_ALERTA")));
        assertTrue(sem.limitacoes().stream().anyMatch(l -> l.codigo().equals("SEM_REGRAS")));

        UUID regra = UUID.randomUUID();
        banco.regras.add(new RegraAlerta(regra, "Permanência", TipoRegraAlerta.TEMPO_TOTAL, null, null, Duration.ofHours(2),
                null, true, 3));
        banco.abertos.add(new SituacaoEpisodio(new UUID(9, 1), new UUID(8, 1), T0.minus(Duration.ofHours(3)),
                T0.minus(Duration.ofHours(3)), null, null, T0, List.of()));
        banco.abertos.add(new SituacaoEpisodio(new UUID(9, 2), new UUID(8, 1), T0.minus(Duration.ofHours(1)),
                T0.minus(Duration.ofHours(1)), null, null, T0, List.of()));
        ServicoRelatorios.Resultado com = consultar(coord, TipoRelatorio.RESUMO);
        LinhaRelatorio casos = com.linhas().stream().filter(l -> l.secao().equals("CASOS_EM_ALERTA")).findFirst().orElseThrow();
        assertEquals(Long.valueOf(1), casos.quantidade());
        assertEquals(Long.valueOf(2), casos.base());
        LinhaRelatorio porRegra = com.linhas().stream().filter(l -> l.secao().equals("ALERTA_REGRA")).findFirst().orElseThrow();
        assertEquals("v3", porRegra.grupo());
    }

    @Test
    void exportacaoConfereOComprovanteERegistraSemRecalcular() {
        ServicoRelatorios.Resultado r = servico.consultar(coord, ORIGEM, TipoRelatorio.GARGALOS, HOJE.minusDays(2), HOJE,
                SETOR, null, "LOGISTICA");
        int transacoes = banco.transacoes;
        ServicoRelatorios.Exportacao e = servico.registrarExportacao(coord, ORIGEM, r.comprovante(),
                ServicoRelatorios.Formato.CSV);
        assertEquals(r.assinatura(), e.assinatura());
        assertEquals(transacoes, banco.transacoes, "exportar NÃO recalcula (mesmo conjunto que a tela)");
        assertEquals(List.of("RELATORIO_EXPORTADO:GARGALOS"), banco.registros);
        Map<String, Object> dados = banco.dadosRegistrados.get(0);
        assertEquals(r.assinatura(), dados.get("assinatura"));
        assertEquals(SETOR.toString(), dados.get("setor"));
        assertEquals("LOGISTICA", dados.get("categoria"));
        assertEquals(HOJE.minusDays(2).toString(), dados.get("inicio"));
        assertFalse(dados.toString().contains("Paciente"));

        servico.registrarExportacao(coord, ORIGEM, r.comprovante(), ServicoRelatorios.Formato.IMPRESSAO);
        assertEquals("RELATORIO_IMPRESSAO_SOLICITADA:GARGALOS", banco.registros.get(1));

        String adulterado = r.comprovante().substring(0, r.comprovante().length() - 3) + "AAA";
        assertEquals("COMPROVANTE_INVALIDO", assertThrows(RegraVioladaException.class,
                () -> servico.registrarExportacao(coord, ORIGEM, adulterado, ServicoRelatorios.Formato.CSV)).codigo());
        assertEquals("COMPROVANTE_INVALIDO", assertThrows(RegraVioladaException.class,
                () -> servico.registrarExportacao(usuario(Papel.COORDENACAO_FLUXO), ORIGEM, r.comprovante(),
                        ServicoRelatorios.Formato.CSV)).codigo(), "comprovante de outro usuário");
        relogio.agora = T0.plus(ServicoRelatorios.VALIDADE_COMPROVANTE).plusSeconds(1);
        assertEquals("RELATORIO_EXPIRADO", assertThrows(ConflitoDeEstadoException.class,
                () -> servico.registrarExportacao(coord, ORIGEM, r.comprovante(), ServicoRelatorios.Formato.CSV)).codigo());
        assertEquals(2, banco.registros.size(), "recusas não registram exportação");
    }

    @Test
    void comprovanteNominalExigePermissaoNominalVigente() {
        banco.lista.add(pend(1));
        ServicoRelatorios.Resultado r = consultar(coord, TipoRelatorio.PENDENCIAS);
        UsuarioAutenticado semNominal = new UsuarioAutenticado(coord.usuarioId(), "u", "Usuário",
                Map.of(UNIDADE, Set.of(Papel.DIRECAO)), UNIDADE, false, 1);
        assertThrows(AcessoNegadoException.class,
                () -> servico.registrarExportacao(semNominal, ORIGEM, r.comprovante(), ServicoRelatorios.Formato.CSV));
        assertTrue(banco.registros.isEmpty(), "nada registrado");
    }

    @Test
    void comprovanteDeOutraChaveNaoVale() {
        ServicoRelatorios.Resultado r = consultar(coord, TipoRelatorio.RESUMO);
        byte[] outra = new byte[32];
        outra[0] = 9;
        assertTrue(new TokenRelatorio(outra).verificar(r.comprovante()).isEmpty());
        assertTrue(new TokenRelatorio(outra).verificar("lixo").isEmpty());
        assertTrue(new TokenRelatorio(outra).verificar("a.b.c").isEmpty());
    }
}
