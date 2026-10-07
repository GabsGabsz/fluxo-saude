package br.fluxosaude.integracao;

import static br.fluxosaude.integracao.ClienteHttp.exigir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.SituacaoEpisodio;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.Papel;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.identidade.infra.HashDeSenhaArgon2;
import br.fluxosaude.indicador.aplicacao.RepositorioIndicadores;
import br.fluxosaude.indicador.aplicacao.ServicoIndicadores;
import br.fluxosaude.indicador.aplicacao.TransacaoIndicadores;
import br.fluxosaude.indicador.infra.TransacaoIndicadoresJdbc;
import br.fluxosaude.infra.persistencia.ExecutorTransacional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/**
 * Visão única dos indicadores (ADR-0009; revisão do PR #10, ponto 4), com conexões reais: o
 * serviço de indicadores roda pela transação REAL da aplicação (TransacaoIndicadoresJdbc →
 * ExecutorTransacional, REPEATABLE READ somente leitura); entre a consulta de desfechos e as
 * seguintes, OUTRA requisição HTTP encerra um episódio e confirma (outra conexão do pool). A
 * resposta tem de ser coerente: nenhum indicador vê o encerramento pela metade. Determinístico:
 * a gravação acontece exatamente naquele ponto, de forma síncrona, e só se segue depois que ela
 * foi confirmada.
 */
class IndicadoresConsistenciaIT extends IntegracaoBase {

    static final String SENHA = "frase secreta da consistencia dos indicadores";
    static final String FUSO = "America/Fortaleza";

    @Autowired
    Environment env;

    @Autowired
    ExecutorTransacional executor;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void respostaUsaUmUnicoEstadoDoBancoMesmoComEncerramentoConcorrente() throws Exception {
        UUID unidade = UUID.randomUUID();
        UUID setor = UUID.randomUUID();
        UUID coordId = UUID.randomUUID();
        String hash = new HashDeSenhaArgon2(1).gerar(SENHA);
        try (Connection c = conexaoDono()) {
            c.setAutoCommit(false);
            executar(c, "INSERT INTO fluxo.unidade (id, codigo, nome, tipo, fuso_horario) VALUES (?, 'UPA_IND_SNAP', "
                    + "'UPA Indicadores Instantaneo', 'UPA', '" + FUSO + "')", unidade);
            executar(c, "SELECT fluxo.provisionar_unidade(?)", unidade);
            executar(c, "INSERT INTO fluxo.setor (id, unidade_id, codigo, nome) VALUES (?, ?, 'S1', 'Setor Instantaneo')",
                    setor, unidade);
            executar(c, "INSERT INTO fluxo.usuario (id, login, nome, senha_hash, deve_trocar_senha, unidade_gestora_id) "
                    + "VALUES (?, 'coord.snap', 'Coordenacao Instantaneo', '" + hash + "', false, ?)", coordId, unidade);
            executar(c, "INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel) VALUES (?, ?, 'COORDENACAO_FLUXO')",
                    coordId, unidade);
            c.commit();
        }
        ClienteHttp http = new ClienteHttp(Integer.parseInt(env.getRequiredProperty("local.server.port")));
        http.entrar("coord.snap", SENHA);
        String ep1 = abrir(http, "Paciente Ficticio Instantaneo Um", setor);
        abrir(http, "Paciente Ficticio Instantaneo Dois", setor);
        UUID alta = idEtapa(unidade, "ALTA");

        UsuarioAutenticado coord = new UsuarioAutenticado(coordId, "coord.snap", "Coordenacao Instantaneo",
                Map.of(unidade, Set.of(Papel.COORDENACAO_FLUXO)), unidade, false, credencialVersao(coordId));
        ContextoOrigem origem = new ContextoOrigem("127.0.0.1", "it-consistencia");
        LocalDate hoje = LocalDate.now(ZoneId.of(FUSO));

        // ---------------------------------------------------------------- leitura com gravação no meio
        AtomicBoolean gravou = new AtomicBoolean();
        TransacaoIndicadores real = new TransacaoIndicadoresJdbc(executor);
        ServicoIndicadores servico = new ServicoIndicadores(comGravacaoAposDesfechos(real, () -> {
            try {
                exigir(200, http.enviar("PUT", "/api/episodios/" + ep1 + "/etapa",
                        "{\"versao\":0,\"etapaId\":\"" + alta + "\"}"));
                assertTrue(encerrado(ep1), "encerramento confirmado em outra conexão antes de continuar a leitura");
                gravou.set(true);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }), Clock.systemUTC());
        ServicoIndicadores.Resultado r = servico.consultar(coord, origem, hoje.minusDays(1), hoje, null);
        assertTrue(gravou.get(), "a gravação concorrente aconteceu no meio da resposta");
        long desfechos = r.historico().desfechos().stream().mapToLong(RepositorioIndicadores.Desfecho::quantidade).sum();
        long permanencia = r.historico().permanencia().incluidos() + r.historico().permanencia().naoIncluidos();
        long saidas = r.historico().volumeDiario().stream().mapToLong(RepositorioIndicadores.Dia::saidas).sum();
        assertEquals(0, desfechos, "desfechos lidos antes do encerramento");
        assertEquals(desfechos, permanencia, "permanência no MESMO estado dos desfechos (não vê o encerramento)");
        assertEquals(desfechos, saidas, "volume diário no MESMO estado dos desfechos");
        assertEquals(2, abertos(r), "retrato no mesmo estado: os dois ainda abertos");

        // ---------------------------------------------------------------- nova leitura: tudo vê o encerramento
        ServicoIndicadores.Resultado depois = new ServicoIndicadores(real, Clock.systemUTC())
                .consultar(coord, origem, hoje.minusDays(1), hoje, null);
        assertEquals(1, depois.historico().desfechos().stream().mapToLong(RepositorioIndicadores.Desfecho::quantidade).sum());
        assertEquals(1, depois.historico().permanencia().incluidos() + depois.historico().permanencia().naoIncluidos());
        assertEquals(1, depois.historico().volumeDiario().stream().mapToLong(RepositorioIndicadores.Dia::saidas).sum());
        assertEquals(1, abertos(depois));

        // A transação dos indicadores é somente leitura em REPEATABLE READ (e revalida a sessão).
        String modo = executor.executarLeituraConsistente(
                br.fluxosaude.infra.persistencia.ContextoRequisicao.de(coord, origem),
                jdbc -> jdbc.sql("SELECT current_setting('transaction_isolation') || '/' || "
                        + "current_setting('transaction_read_only') || '/' || (fluxo.ctx_unidades())[1]")
                    .query(String.class).single());
        assertEquals("repeatable read/on/" + unidade, modo);
    }

    // ----------------------------------------------------------------------------

    private static long abertos(ServicoIndicadores.Resultado r) {
        return r.retrato().itens().stream().filter(i -> "ABERTOS".equals(i.dimensao()))
                .mapToLong(RepositorioIndicadores.ItemRetrato::quantidade).sum();
    }

    /** A transação real, com o repositório real decorado: executa {@code gravacao} logo após os desfechos. */
    private static TransacaoIndicadores comGravacaoAposDesfechos(TransacaoIndicadores real, Runnable gravacao) {
        return new TransacaoIndicadores() {
            @Override
            public <T> T executar(UsuarioAutenticado u, ContextoOrigem o, Function<RepositorioIndicadores, T> trabalho) {
                return real.executar(u, o, r -> trabalho.apply(new Gancho(r, gravacao)));
            }
        };
    }

    private record Gancho(RepositorioIndicadores r, Runnable gravacao) implements RepositorioIndicadores {
        @Override
        public List<Desfecho> desfechos(UUID unidade, Periodo p, UUID setor) {
            List<Desfecho> d = r.desfechos(unidade, p, setor);
            gravacao.run();
            return d;
        }

        @Override
        public String fusoDaUnidade(UUID unidade) {
            return r.fusoDaUnidade(unidade);
        }

        @Override
        public boolean setorDaUnidade(UUID setor) {
            return r.setorDaUnidade(setor);
        }

        @Override
        public Periodo periodo(String fuso, LocalDate inicio, LocalDate fim) {
            return r.periodo(fuso, inicio, fim);
        }

        @Override
        public Duracoes permanencia(UUID unidade, Periodo p, UUID setor) {
            return r.permanencia(unidade, p, setor);
        }

        @Override
        public List<AcimaDoLimite> acimaDosLimites(UUID unidade, Periodo p, UUID setor) {
            return r.acimaDosLimites(unidade, p, setor);
        }

        @Override
        public Duracoes solicitacaoAceite(UUID unidade, Periodo p, UUID setor) {
            return r.solicitacaoAceite(unidade, p, setor);
        }

        @Override
        public Duracoes aceiteSaida(UUID unidade, Periodo p, UUID setor) {
            return r.aceiteSaida(unidade, p, setor);
        }

        @Override
        public List<Motivo> motivos(UUID unidade, Periodo p, UUID setor, Instant agora) {
            return r.motivos(unidade, p, setor, agora);
        }

        @Override
        public List<Dia> volumeDiario(UUID unidade, String fuso, LocalDate inicio, LocalDate fim, UUID setor) {
            return r.volumeDiario(unidade, fuso, inicio, fim, setor);
        }

        @Override
        public List<ItemRetrato> retrato(UUID unidade, UUID setor, Instant agora) {
            return r.retrato(unidade, setor, agora);
        }

        @Override
        public List<RegraAlerta> regrasAtivas() {
            return r.regrasAtivas();
        }

        @Override
        public List<SituacaoEpisodio> situacoesAbertas(UUID setor, int limite) {
            return r.situacoesAbertas(setor, limite);
        }
    }

    private String abrir(ClienteHttp c, String nome, UUID setor) throws Exception {
        String corpo = exigir(201, c.enviar("POST", "/api/episodios",
                "{\"novoPaciente\":{\"nome\":\"" + nome + "\"},\"setorId\":\"" + setor + "\"}")).body();
        String id = json.readTree(corpo).get("id").toString();
        return id.substring(1, id.length() - 1);
    }

    private static void executar(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.execute();
        }
    }

    private static UUID idEtapa(UUID unidade, String codigo) throws Exception {
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM fluxo.etapa WHERE unidade_id = ? AND codigo = ?")) {
            ps.setObject(1, unidade);
            ps.setString(2, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static int credencialVersao(UUID usuario) throws Exception {
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT credencial_versao FROM fluxo.usuario WHERE id = ?")) {
            ps.setObject(1, usuario);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private static boolean encerrado(String episodio) throws Exception {
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT encerrado_em IS NOT NULL FROM fluxo.episodio WHERE id = ?")) {
            ps.setObject(1, UUID.fromString(episodio));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
