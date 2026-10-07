package br.fluxosaude.integracao;

import static br.fluxosaude.integracao.ClienteHttp.exigir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.alerta.dominio.RegraAlerta;
import br.fluxosaude.alerta.dominio.SituacaoEpisodio;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.Papel;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.identidade.infra.HashDeSenhaArgon2;
import br.fluxosaude.infra.persistencia.ExecutorTransacional;
import br.fluxosaude.relatorio.aplicacao.RepositorioRelatorios;
import br.fluxosaude.relatorio.aplicacao.ServicoRelatorios;
import br.fluxosaude.relatorio.aplicacao.TokenRelatorio;
import br.fluxosaude.relatorio.aplicacao.TransacaoRelatorios;
import br.fluxosaude.relatorio.dominio.LinhaRelatorio;
import br.fluxosaude.relatorio.dominio.PendenciaOperacional;
import br.fluxosaude.relatorio.dominio.TipoRelatorio;
import br.fluxosaude.relatorio.infra.RegistroRelatoriosJdbc;
import br.fluxosaude.relatorio.infra.TransacaoRelatoriosJdbc;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/**
 * Atualização concorrente durante a geração e a exportação (issue #9), com conexões reais: o
 * relatório roda pela transação REAL (REPEATABLE READ somente leitura); logo depois da primeira
 * consulta, OUTRA requisição HTTP encerra um episódio e confirma. As consultas seguintes do MESMO
 * relatório (casos em alerta pelo motor da Torre) continuam vendo o estado anterior — sem mistura.
 * Depois, a exportação do resultado visto é registrada com a assinatura DELE (não recalcula), e um
 * novo cálculo tem outra assinatura: nenhuma divergência silenciosa entre tela e arquivo.
 */
class RelatoriosConsistenciaIT extends IntegracaoBase {

    static final String SENHA = "frase secreta da consistencia dos relatorios";
    static final String FUSO = "America/Fortaleza";

    @Autowired
    Environment env;

    @Autowired
    ExecutorTransacional executor;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void relatorioUsaUmEstadoSoEExportacaoNaoDivergeDoVisto() throws Exception {
        UUID unidade = UUID.randomUUID();
        UUID setor = UUID.randomUUID();
        UUID coordId = UUID.randomUUID();
        UUID adm = UUID.randomUUID();
        String hash = new HashDeSenhaArgon2(1).gerar(SENHA);
        try (Connection c = conexaoDono()) {
            c.setAutoCommit(false);
            sql(c, "INSERT INTO fluxo.unidade (id, codigo, nome, tipo, fuso_horario) VALUES (?, 'UPA_REL_SNAP', "
                    + "'UPA Relatorios Instantaneo', 'UPA', '" + FUSO + "')", unidade);
            sql(c, "SELECT fluxo.provisionar_unidade(?)", unidade);
            sql(c, "INSERT INTO fluxo.setor (id, unidade_id, codigo, nome) VALUES (?, ?, 'S1', 'Setor Instantaneo')", setor, unidade);
            sql(c, "INSERT INTO fluxo.usuario (id, login, nome, senha_hash, deve_trocar_senha, unidade_gestora_id) "
                    + "VALUES (?, 'coord.relsnap', 'Coordenacao Rel Instantaneo', ?, false, ?)", coordId, hash, unidade);
            sql(c, "INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel) VALUES (?, ?, 'COORDENACAO_FLUXO')", coordId, unidade);
            sql(c, "INSERT INTO fluxo.usuario (id, login, nome, senha_hash, deve_trocar_senha, unidade_gestora_id) "
                    + "VALUES (?, 'adm.relsnap', 'Adm Rel Instantaneo', ?, false, ?)", adm, hash, unidade);
            sql(c, "INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel) VALUES (?, ?, 'ADMINISTRADOR')", adm, unidade);
            c.commit();
        }
        ClienteHttp http = new ClienteHttp(Integer.parseInt(env.getRequiredProperty("local.server.port")));
        http.entrar("coord.relsnap", SENHA);
        ClienteHttp admin = new ClienteHttp(Integer.parseInt(env.getRequiredProperty("local.server.port")));
        admin.entrar("adm.relsnap", SENHA);
        exigir(201, admin.enviar("POST", "/api/config/regras-alerta",
                "{\"nome\":\"Permanencia (ilustrativa)\",\"tipo\":\"TEMPO_TOTAL\",\"limiteMinutos\":60}"));
        Instant duasHoras = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofHours(2));
        String ep1 = abrir(http, "Paciente Ficticio Rel Instantaneo Um", setor, duasHoras);
        abrir(http, "Paciente Ficticio Rel Instantaneo Dois", setor, duasHoras);
        UUID alta;
        int credencial;
        try (Connection c = conexaoDono()) {
            alta = umId(c, "SELECT id FROM fluxo.etapa WHERE unidade_id = ? AND codigo = 'ALTA'", unidade);
            try (PreparedStatement ps = c.prepareStatement("SELECT credencial_versao FROM fluxo.usuario WHERE id = ?")) {
                ps.setObject(1, coordId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    credencial = rs.getInt(1);
                }
            }
        }
        UsuarioAutenticado coord = new UsuarioAutenticado(coordId, "coord.relsnap", "Coordenacao Rel Instantaneo",
                Map.of(unidade, Set.of(Papel.COORDENACAO_FLUXO)), unidade, false, credencial);
        ContextoOrigem origem = new ContextoOrigem("127.0.0.1", "it-rel-consistencia");
        byte[] chave = new byte[32];
        chave[0] = 1;
        TokenRelatorio tokens = new TokenRelatorio(chave);
        RegistroRelatoriosJdbc registro = new RegistroRelatoriosJdbc(executor);
        TransacaoRelatorios real = new TransacaoRelatoriosJdbc(executor);
        LocalDate hoje = LocalDate.now(ZoneId.of(FUSO));

        // ---------------------------------------------------------------- encerramento logo após a 1ª consulta
        AtomicBoolean gravou = new AtomicBoolean();
        TransacaoRelatorios comGancho = new TransacaoRelatorios() {
            @Override
            public <T> T executar(UsuarioAutenticado u, ContextoOrigem o, Function<RepositorioRelatorios, T> trabalho) {
                return real.executar(u, o, r -> trabalho.apply(new Gancho(r, () -> {
                    try {
                        exigir(200, http.enviar("PUT", "/api/episodios/" + ep1 + "/etapa",
                                "{\"versao\":0,\"etapaId\":\"" + alta + "\"}"));
                        gravou.set(true);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })));
            }
        };
        ServicoRelatorios.Resultado visto = new ServicoRelatorios(comGancho, registro, tokens, Clock.systemUTC())
                .consultar(coord, origem, TipoRelatorio.RESUMO, hoje.minusDays(1), hoje, null, null, null);
        assertTrue(gravou.get(), "a gravação concorrente aconteceu no meio do relatório");
        assertEquals(2L, valor(visto, "ABERTOS", "quantidade"));
        assertEquals(2L, valor(visto, "CASOS_EM_ALERTA", "base"), "alertas no MESMO estado do estoque");
        assertEquals(2L, valor(visto, "CASOS_EM_ALERTA", "quantidade"));
        assertEquals(0L, valor(visto, "ENCERRAMENTOS_TOTAL", "quantidade"));

        // ---------------------------------------------------------------- exportar o que foi visto
        ServicoRelatorios servico = new ServicoRelatorios(real, registro, tokens, Clock.systemUTC());
        ServicoRelatorios.Exportacao e = servico.registrarExportacao(coord, origem, visto.comprovante(),
                ServicoRelatorios.Formato.CSV);
        assertEquals(visto.assinatura(), e.assinatura(), "a exportação identifica o conjunto VISTO (sem recalcular)");
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT dados ->> 'assinatura' FROM auditoria.registro WHERE id = ?")) {
            ps.setLong(1, e.registro());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(visto.assinatura(), rs.getString(1));
            }
        }
        ServicoRelatorios.Resultado novo = servico.consultar(coord, origem, TipoRelatorio.RESUMO, hoje.minusDays(1), hoje,
                null, null, null);
        assertEquals(1L, valor(novo, "ABERTOS", "quantidade"));
        assertEquals(1L, valor(novo, "CASOS_EM_ALERTA", "base"));
        assertEquals(1L, valor(novo, "ENCERRAMENTOS_TOTAL", "quantidade"));
        assertNotEquals(visto.assinatura(), novo.assinatura(), "conjunto novo = assinatura nova (nada silencioso)");
    }

    // ----------------------------------------------------------------------------

    private static long valor(ServicoRelatorios.Resultado r, String secao, String campo) {
        LinhaRelatorio l = r.linhas().stream().filter(x -> x.secao().equals(secao) && x.chave() == null).findFirst()
                .orElseThrow(() -> new AssertionError("seção " + secao));
        return "base".equals(campo) ? l.base() : l.quantidade();
    }

    /** Repositório real decorado: executa a gravação logo após a 1ª consulta (resumo). */
    private record Gancho(RepositorioRelatorios r, Runnable gravacao) implements RepositorioRelatorios {
        @Override
        public List<LinhaRelatorio> resumo(UUID u, Periodo p, Instant agora, UUID setor) {
            List<LinhaRelatorio> l = r.resumo(u, p, agora, setor);
            gravacao.run();
            return l;
        }

        @Override
        public Unidade unidade(UUID id) {
            return r.unidade(id);
        }

        @Override
        public Optional<String> nomeSetor(UUID setor) {
            return r.nomeSetor(setor);
        }

        @Override
        public Optional<String> nomeEtapa(UUID etapa) {
            return r.nomeEtapa(etapa);
        }

        @Override
        public Periodo periodo(String fuso, LocalDate inicio, LocalDate fim) {
            return r.periodo(fuso, inicio, fim);
        }

        @Override
        public List<LinhaRelatorio> gargalos(UUID u, Periodo p, Instant agora, UUID setor, UUID etapa, String categoria) {
            return r.gargalos(u, p, agora, setor, etapa, categoria);
        }

        @Override
        public List<LinhaRelatorio> pendencias(UUID u, Periodo p, Instant agora, UUID setor, String categoria) {
            return r.pendencias(u, p, agora, setor, categoria);
        }

        @Override
        public List<PendenciaOperacional> listaPendencias(UUID u, Instant agora, UUID setor, String categoria, int limite) {
            return r.listaPendencias(u, agora, setor, categoria, limite);
        }

        @Override
        public List<LinhaRelatorio> metricasPeriodo(UUID u, Periodo p, Instant agora, UUID setor) {
            return r.metricasPeriodo(u, p, agora, setor);
        }

        @Override
        public MudancasRegras mudancasRegras(UUID u, Instant desde, Instant ate) {
            return r.mudancasRegras(u, desde, ate);
        }

        @Override
        public List<LinhaRelatorio> qualidade(UUID u, Periodo p, Instant agora, UUID setor) {
            return r.qualidade(u, p, agora, setor);
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

    private String abrir(ClienteHttp c, String nome, UUID setor, Instant quando) throws Exception {
        String corpo = exigir(201, c.enviar("POST", "/api/episodios", "{\"novoPaciente\":{\"nome\":\"" + nome + "\"},\"setorId\":\""
                + setor + "\",\"momento\":{\"ocorridoEm\":\"" + quando + "\",\"justificativaAjuste\":\"Registro tardio do teste\"}}"))
                .body();
        String id = json.readTree(corpo).get("id").toString();
        return id.substring(1, id.length() - 1);
    }

    private static UUID umId(Connection c, String sql, UUID unidade) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, unidade);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static void sql(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.execute();
        }
    }
}
