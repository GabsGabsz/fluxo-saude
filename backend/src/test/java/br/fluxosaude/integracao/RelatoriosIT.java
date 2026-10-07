package br.fluxosaude.integracao;

import static br.fluxosaude.integracao.ClienteHttp.exigir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.identidade.infra.HashDeSenhaArgon2;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Relatórios gerenciais via HTTP com PostgreSQL real (issue #9): valores calculados à mão a partir
 * de operações feitas pela própria API (linha do tempo real, com troca de setor), agregação além
 * da página da Torre, filtros por setor pela linha do tempo, lista nominal só com acesso nominal,
 * leitura nominal auditada, exportação registrada com o comprovante, isolamento, permissões e
 * sessão revogada.
 */
class RelatoriosIT extends IntegracaoBase {

    static final String SENHA = "frase secreta dos relatorios gerenciais";
    static final String FUSO = "America/Fortaleza";
    static final int ABERTOS_EM_MASSA = 505;   // mais que a página padrão da Torre (500)
    static UUID unidade;
    static UUID s1;
    static UUID s2;
    static Instant h;
    static String e1;
    static String e2;
    static String e3;
    private static final AtomicBoolean PREPARADO = new AtomicBoolean();

    @Autowired
    Environment env;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void prepararUmaVez() throws Exception {
        if (!PREPARADO.compareAndSet(false, true)) {
            return;
        }
        String hash = new HashDeSenhaArgon2(1).gerar(SENHA);
        unidade = UUID.randomUUID();
        UUID outra = UUID.randomUUID();
        s1 = UUID.randomUUID();
        s2 = UUID.randomUUID();
        UUID adm = UUID.randomUUID();
        try (Connection c = conexaoDono()) {
            c.setAutoCommit(false);
            sql(c, "INSERT INTO fluxo.unidade (id, codigo, nome, tipo, fuso_horario) VALUES (?, 'UPA_REL', 'UPA Relatorios', 'UPA', '"
                    + FUSO + "'), (?, 'UPA_REL_2', 'UPA Relatorios 2', 'UPA', '" + FUSO + "')", unidade, outra);
            sql(c, "SELECT fluxo.provisionar_unidade(?), fluxo.provisionar_unidade(?)", unidade, outra);
            sql(c, "INSERT INTO fluxo.setor (id, unidade_id, codigo, nome) VALUES (?, ?, 'S1', 'Observacao Rel'), "
                    + "(?, ?, 'S2', 'Emergencia Rel'), (gen_random_uuid(), ?, 'S1', 'Setor Outra')", s1, unidade, s2, unidade, outra);
            usuario(c, adm, "adm.rel", "Administracao Rel", hash, unidade, "ADMINISTRADOR");
            usuario(c, UUID.randomUUID(), "coord.rel", "Coordenacao Rel", hash, unidade, "COORDENACAO_FLUXO");
            usuario(c, UUID.randomUUID(), "dir.rel", "Direcao Rel", hash, unidade, "DIRECAO");
            usuario(c, UUID.randomUUID(), "enf.rel", "Enfermagem Rel", hash, unidade, "ENFERMAGEM");
            usuario(c, UUID.randomUUID(), "coord.rel2", "Coordenacao Rel 2", hash, outra, "COORDENACAO_FLUXO");
            // Volume além da página da Torre: abertos sem linha do tempo (registro legado).
            sql(c, "SELECT set_config('fluxo.usuario_id', ?, true)", adm.toString());
            sql(c, "INSERT INTO fluxo.paciente (id, unidade_id, nome) SELECT ('77777777-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid, ?, "
                    + "'Paciente Massa ' || n FROM generate_series(1, " + ABERTOS_EM_MASSA + ") n", unidade);
            sql(c, "INSERT INTO fluxo.episodio (unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde) "
                    + "SELECT ?, ('77777777-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid, ?, now() - interval '30 minutes', "
                    + "(SELECT id FROM fluxo.etapa WHERE unidade_id = ? AND codigo = 'EM_ATENDIMENTO'), now() - interval '30 minutes' "
                    + "FROM generate_series(1, " + ABERTOS_EM_MASSA + ") n", unidade, s1, unidade);
            c.commit();
        }
        // Linha do tempo real, pela API (instantes informados com ajuste justificado).
        ClienteHttp coord = cliente("coord.rel");
        h = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofHours(10));
        // E1: S1 em atendimento; aguardando leito (LEITO_CAPACIDADE) H+1h; vai para S2 H+2h; volta ao atendimento H+4h; alta H+5h.
        e1 = abrir(coord, "Paciente Ficticio Relatorio Um", s1, h);
        int v = etapa(coord, e1, 0, "AGUARDANDO_RECURSO_LEITO", "SEM_LEITO_ESPECIALIDADE", h.plus(Duration.ofHours(1)));
        v = setor(coord, e1, v, s2, h.plus(Duration.ofHours(2)));
        v = etapa(coord, e1, v, "EM_ATENDIMENTO", null, h.plus(Duration.ofHours(4)));
        etapa(coord, e1, v, "ALTA", null, h.plus(Duration.ofHours(5)));
        // E2: S1; aguardando leito desde H+3h30 (em curso).
        e2 = abrir(coord, "Paciente Ficticio Relatorio Dois", s1, h.plus(Duration.ofHours(3)));
        etapa(coord, e2, 0, "AGUARDANDO_RECURSO_LEITO", "SEM_LEITO_ESPECIALIDADE", h.plus(Duration.ofMinutes(210)));
        // E3: S2, em atendimento desde H+6h.
        e3 = abrir(coord, "Paciente Ficticio Relatorio Tres", s2, h.plus(Duration.ofHours(6)));
        exigir(201, coord.enviar("POST", "/api/episodios/" + e2 + "/pendencias", "{\"categoria\":\"LOGISTICA\",\"descricao\":"
                + "\"=HYPERLINK(\\\"http://x\\\") confirmar transporte\",\"responsavel\":{\"setorId\":\"" + s1 + "\"},\"prazo\":\""
                + Instant.now().plus(Duration.ofHours(4)) + "\",\"criticidade\":\"ALTA\"}"));
        exigir(201, coord.enviar("POST", "/api/episodios/" + e3 + "/pendencias", "{\"categoria\":\"REGULACAO\",\"descricao\":"
                + "\"Cobrar vaga; \\\"urgente\\\"\",\"responsavel\":{\"papel\":\"MEDICO\"},\"prazo\":\""
                + Instant.now().plus(Duration.ofHours(6)) + "\",\"criticidade\":\"MEDIA\"}"));
    }

    @Test
    void valoresCalculadosAMaoFiltrosESemPaginacao() throws Exception {
        ClienteHttp coord = cliente("coord.rel");
        String periodo = periodo();

        // ---------------------------------------------------------------- resumo
        JsonNode r = get(coord, "/api/relatorios/resumo?" + periodo);
        assertEquals(3 + ABERTOS_EM_MASSA, valor(r, "ENTRADAS", null, "quantidade"), "todas as entradas, sem paginação");
        assertEquals(ABERTOS_EM_MASSA, valor(r, "ENTRADAS", null, "parte"), "carga legada sem setor de entrada");
        assertEquals(1, valor(r, "ENCERRAMENTOS", "ALTA", "quantidade"));
        assertEquals(2 + ABERTOS_EM_MASSA, valor(r, "ABERTOS", null, "quantidade"), "estoque além da página da Torre");
        assertEquals(1, valor(r, "ABERTOS_SETOR", s2.toString(), "quantidade"));
        assertEquals(1, valor(r, "BLOQUEADOS_AGORA", null, "quantidade"));
        assertEquals(2, valor(r, "PENDENCIAS_ABERTAS", null, "quantidade"));
        assertEquals(64, s(r.get("assinatura")).length());
        assertNotNull(s(r.get("comprovante")));
        Instant ref = Instant.parse(s(r.get("referencia")));

        // ---------------------------------------------------------------- gargalos (linha do tempo)
        JsonNode g = get(coord, "/api/relatorios/gargalos?" + periodo);
        JsonNode atend = linha(g, "ETAPA_CONCLUIDA", "EM_ATENDIMENTO");
        assertEquals(3, atend.get("quantidade").asInt(), "E1 passou duas vezes pelo atendimento (60 + 60) e E2 uma (30)");
        assertEquals(2, atend.get("episodios").asInt());
        assertEquals(60.0, atend.get("mediana").asDouble(), 0.01);
        assertEquals(180.0, linha(g, "ETAPA_CONCLUIDA", "AGUARDANDO_RECURSO_LEITO").get("mediana").asDouble(), 0.01);
        double idadeE2 = Duration.between(h.plus(Duration.ofMinutes(210)), ref).toSeconds() / 60.0;
        assertEquals(idadeE2, linha(g, "ETAPA_EM_CURSO", "AGUARDANDO_RECURSO_LEITO").get("mediana").asDouble(), 0.02,
                "em curso: idade, separada das concluídas");
        JsonNode leito = linha(g, "BLOQUEIO_CATEGORIA", "LEITO_CAPACIDADE");
        assertEquals(180.0 + idadeE2, leito.get("minutos").asDouble(), 0.05);
        assertEquals(2, leito.get("parte").asInt(), "dois bloqueios iniciados no período");
        assertEquals(180.0, linha(g, "BLOQUEIO_CONCLUIDO", "LEITO_CAPACIDADE").get("mediana").asDouble(), 0.01);
        double desdeE2 = Duration.between(h.plus(Duration.ofHours(3)), ref).toSeconds() / 60.0;
        double desdeE3 = Duration.between(h.plus(Duration.ofHours(6)), ref).toSeconds() / 60.0;
        assertEquals(120.0 + desdeE2, linha(g, "SETOR_TEMPO", s1.toString()).get("minutos").asDouble(), 0.05,
                "S1: E1 120 min (antes de mudar de setor) + E2 desde a entrada");
        assertEquals(180.0 + desdeE3, linha(g, "SETOR_TEMPO", s2.toString()).get("minutos").asDouble(), 0.05,
                "S2: E1 180 min (depois da troca) + E3 desde a entrada");

        // Filtro S2: só o trecho do bloqueio de E1 vivido em S2 (120 de 180 min); E2 está em S1.
        JsonNode g2 = get(coord, "/api/relatorios/gargalos?" + periodo + "&setor=" + s2);
        assertEquals(120.0, linha(g2, "BLOQUEIO_CATEGORIA", "LEITO_CAPACIDADE").get("minutos").asDouble(), 0.01);
        assertEquals(0, linha(g2, "BLOQUEIO_CATEGORIA", "LEITO_CAPACIDADE").get("parte").asInt(), "iniciado em S1, não em S2");
        assertEquals(1, linha(g2, "ETAPA_CONCLUIDA", "EM_ATENDIMENTO").get("quantidade").asInt(),
                "só o atendimento que começou em S2");
        assertTrue(g2.get("limitacoes").toString().contains("FILTRO_DE_SETOR"));

        // ---------------------------------------------------------------- pendências: lista nominal (coordenação)
        long marco = ultimoRegistro();
        JsonNode p = get(coord, "/api/relatorios/pendencias?" + periodo);
        assertTrue(p.get("listaPendencias").get("disponivel").asBoolean());
        assertEquals(2, p.get("listaPendencias").get("itens").size());
        assertTrue(p.toString().contains("Paciente Ficticio Relatorio Dois"));
        assertEquals(1, contar(marco, "CONSULTA_RELATORIO_PENDENCIAS"), "leitura nominal registrada");

        // ---------------------------------------------------------------- evolução e qualidade
        // Evolução só com períodos encerrados (fim até ontem no fuso da unidade): incluir hoje é recusado.
        HttpResponse<String> incompleta = exigir(422, coord.enviar("GET", "/api/relatorios/evolucao?" + periodo, null));
        assertTrue(incompleta.body().contains("PERIODO_INCOMPLETO"), incompleta.body());
        LocalDate hoje = LocalDate.now(ZoneId.of(FUSO));
        JsonNode ev = get(coord, "/api/relatorios/evolucao?inicio=" + hoje.minusDays(2) + "&fim=" + hoje.minusDays(1));
        assertEquals(hoje.minusDays(4).toString(), s(ev.get("comparacao").get("inicio")));
        assertTrue(ev.get("limitacoes").toString().contains("ALERTAS_NAO_COMPARADOS"));
        assertTrue(ev.get("limitacoes").toString().contains("PERIODOS_ENCERRADOS"));
        // Definições relevantes DENTRO do resultado (assinado), sem depender do dicionário separado.
        assertTrue(ev.get("definicoes").toString().contains("encerramento administrativo"), "permanência: exclusão");
        assertTrue(ev.get("definicoes").toString().contains("ÚLTIMO prazo"), "pendências: último prazo");
        assertEquals("PERMANENCIA", s(ev.get("verbetes").get("PERMANENCIA")));
        JsonNode q = get(coord, "/api/relatorios/qualidade?" + periodo);
        assertEquals(3, valor(q, "LINHA_DO_TEMPO", null, "quantidade"), "E1, E2 e E3 têm linha do tempo");
        assertEquals(3 + ABERTOS_EM_MASSA, valor(q, "LINHA_DO_TEMPO", null, "base"));
        assertTrue(q.get("limitacoes").toString().contains("SEM_CRITERIO_CONFIGURADO"), "sem regra: sem prazo presumido");

        // ---------------------------------------------------------------- validações
        exigir(422, coord.enviar("GET", "/api/relatorios/resumo?" + periodo + "&etapa=" + UUID.randomUUID(), null));
        exigir(422, coord.enviar("GET", "/api/relatorios/gargalos?" + periodo + "&categoria=NAO_EXISTE", null));
        exigir(422, coord.enviar("GET", "/api/relatorios/inexistente?" + periodo, null));
        exigir(400, coord.enviar("GET", "/api/relatorios/resumo?inicio=ontem&fim=hoje", null));
    }

    @Test
    void exportacaoRegistradaDirecaoSemNominalIsolamentoESessao() throws Exception {
        ClienteHttp coord = cliente("coord.rel");
        ClienteHttp dir = cliente("dir.rel");
        String periodo = periodo();
        JsonNode r = get(coord, "/api/relatorios/gargalos?" + periodo + "&setor=" + s2);

        // Exportação: comprovante conferido; auditoria com a assinatura e os filtros do comprovante, sem nomes.
        long marco = ultimoRegistro();
        JsonNode e = json.readTree(exigir(201, coord.enviar("POST", "/api/relatorios/exportacoes",
                "{\"comprovante\":\"" + s(r.get("comprovante")) + "\",\"formato\":\"CSV\"}")).body());
        assertEquals(s(r.get("assinatura")), s(e.get("assinatura")));
        String dados = dadosDoRegistro(marco, "RELATORIO_EXPORTADO");
        assertTrue(dados.contains(s(r.get("assinatura"))));
        assertTrue(dados.contains(s2.toString()), "filtro registrado");
        assertFalse(dados.contains("Paciente"), "sem conteúdo nominal na auditoria");
        exigir(201, coord.enviar("POST", "/api/relatorios/exportacoes",
                "{\"comprovante\":\"" + s(r.get("comprovante")) + "\",\"formato\":\"IMPRESSAO\"}"));
        assertEquals(1, contar(marco, "RELATORIO_IMPRESSAO_SOLICITADA"));
        String adulterado = s(r.get("comprovante")).replaceFirst(".$", "A");
        exigir(422, coord.enviar("POST", "/api/relatorios/exportacoes", "{\"comprovante\":\"x" + adulterado + "\",\"formato\":\"CSV\"}"));
        exigir(422, dir.enviar("POST", "/api/relatorios/exportacoes",
                "{\"comprovante\":\"" + s(r.get("comprovante")) + "\",\"formato\":\"CSV\"}"));

        // Direção: agregados, sem lista nominal, sem nomes nem ids de episódio.
        String corpo = exigir(200, dir.enviar("GET", "/api/relatorios/pendencias?" + periodo, null)).body();
        JsonNode d = json.readTree(corpo);
        assertFalse(d.get("listaPendencias").get("disponivel").asBoolean());
        assertEquals(0, d.get("listaPendencias").get("itens").size());
        for (String proibido : new String[] {"Paciente", e1, e2, e3, "HYPERLINK", "Cobrar vaga"}) {
            assertFalse(corpo.contains(proibido), "Direção sem dado nominal: " + proibido);
        }
        String resumoDir = exigir(200, dir.enviar("GET", "/api/relatorios/resumo?" + periodo, null)).body();
        assertFalse(resumoDir.contains("Paciente") || resumoDir.contains(e1));

        // Permissões e isolamento.
        exigir(403, cliente("enf.rel").enviar("GET", "/api/relatorios/resumo?" + periodo, null));
        ClienteHttp outra = cliente("coord.rel2");
        JsonNode o = get(outra, "/api/relatorios/resumo?" + periodo);
        assertEquals(0, valor(o, "ABERTOS", null, "quantidade"), "outra unidade não vê nada desta");
        exigir(422, outra.enviar("GET", "/api/relatorios/resumo?" + periodo + "&setor=" + s1, null));
        exigir(422, outra.enviar("POST", "/api/relatorios/exportacoes",
                "{\"comprovante\":\"" + s(r.get("comprovante")) + "\",\"formato\":\"CSV\"}"));

        // Sessão revogada: a senha provisória redefinida derruba a sessão da Direção.
        ClienteHttp adm = cliente("adm.rel");
        JsonNode lista = json.readTree(exigir(200, adm.enviar("GET", "/api/admin/usuarios", null)).body());
        for (JsonNode u : lista.get("itens")) {
            if ("dir.rel".equals(s(u.get("login")))) {
                exigir(200, adm.enviar("POST", "/api/admin/usuarios/" + s(u.get("id")) + "/senha-provisoria",
                        "{\"versao\":" + u.get("versao").asInt() + "}"));
            }
        }
        exigir(401, dir.enviar("GET", "/api/relatorios/resumo?" + periodo, null));
    }

    // ----------------------------------------------------------------------------

    private static String s(JsonNode n) {
        String t = n.toString();
        return t.length() >= 2 && t.startsWith("\"") ? t.substring(1, t.length() - 1) : t;
    }

    private String periodo() {
        LocalDate hoje = LocalDate.now(ZoneId.of(FUSO));
        return "inicio=" + hoje.minusDays(1) + "&fim=" + hoje;
    }

    private JsonNode get(ClienteHttp c, String url) throws Exception {
        HttpResponse<String> r = exigir(200, c.enviar("GET", url, null));
        return json.readTree(r.body());
    }

    private static JsonNode linha(JsonNode r, String secao, String chave) {
        for (JsonNode l : r.get("linhas")) {
            if (secao.equals(s(l.get("secao"))) && (chave == null ? l.get("chave").isNull()
                    : chave.equals(s(l.get("chave"))))) {
                return l;
            }
        }
        throw new AssertionError("linha " + secao + "/" + chave + " ausente: " + r.get("linhas"));
    }

    private static long valor(JsonNode r, String secao, String chave, String campo) {
        return linha(r, secao, chave).get(campo).asLong();
    }

    private String abrir(ClienteHttp c, String nome, UUID setor, Instant quando) throws Exception {
        return s(json.readTree(exigir(201, c.enviar("POST", "/api/episodios", "{\"novoPaciente\":{\"nome\":\"" + nome
                + "\"},\"setorId\":\"" + setor + "\",\"momento\":" + momento(quando) + "}")).body()).get("id"));
    }

    private int etapa(ClienteHttp c, String ep, int versao, String etapa, String motivo, Instant quando) throws Exception {
        String corpo = "{\"versao\":" + versao + ",\"etapaId\":\"" + id("fluxo.etapa", etapa) + "\""
                + (motivo == null ? "" : ",\"motivoId\":\"" + id("fluxo.motivo_bloqueio", motivo) + "\"")
                + ",\"momento\":" + momento(quando) + "}";
        return json.readTree(exigir(200, c.enviar("PUT", "/api/episodios/" + ep + "/etapa", corpo)).body()).get("versao").asInt();
    }

    private int setor(ClienteHttp c, String ep, int versao, UUID setor, Instant quando) throws Exception {
        return json.readTree(exigir(200, c.enviar("PUT", "/api/episodios/" + ep + "/setor", "{\"versao\":" + versao
                + ",\"setorId\":\"" + setor + "\",\"momento\":" + momento(quando) + "}")).body()).get("versao").asInt();
    }

    private static String momento(Instant quando) {
        return "{\"ocorridoEm\":\"" + quando + "\",\"justificativaAjuste\":\"Lancamento retroativo do teste\"}";
    }

    private static UUID id(String tabela, String codigo) throws Exception {
        if (!tabela.equals("fluxo.etapa") && !tabela.equals("fluxo.motivo_bloqueio")) {
            throw new IllegalArgumentException(tabela);
        }
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM " + tabela + " WHERE unidade_id = ? AND codigo = ?")) {
            ps.setObject(1, unidade);
            ps.setString(2, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), codigo);
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static long ultimoRegistro() throws Exception {
        try (Connection c = conexaoDono(); PreparedStatement ps = c.prepareStatement("SELECT coalesce(max(id), 0) FROM auditoria.registro");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static int contar(long depoisDe, String acao) throws Exception {
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM auditoria.registro WHERE id > ? AND acao = ? AND unidade_id = ?")) {
            ps.setLong(1, depoisDe);
            ps.setString(2, acao);
            ps.setObject(3, unidade);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String dadosDoRegistro(long depoisDe, String acao) throws Exception {
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT dados::text FROM auditoria.registro WHERE id > ? AND acao = ? "
                     + "AND unidade_id = ? AND recurso = 'relatorio' ORDER BY id LIMIT 1")) {
            ps.setLong(1, depoisDe);
            ps.setString(2, acao);
            ps.setObject(3, unidade);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "registro " + acao);
                return rs.getString(1);
            }
        }
    }

    private ClienteHttp cliente(String login) throws Exception {
        ClienteHttp c = new ClienteHttp(Integer.parseInt(env.getRequiredProperty("local.server.port")));
        c.entrar(login, SENHA);
        return c;
    }

    private static void sql(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.execute();
        }
    }

    private static void usuario(Connection c, UUID id, String login, String nome, String hash, UUID unidade, String papel)
            throws Exception {
        sql(c, "INSERT INTO fluxo.usuario (id, login, nome, senha_hash, deve_trocar_senha, unidade_gestora_id) "
                + "VALUES (?, ?, ?, ?, false, ?)", id, login, nome, hash, unidade);
        sql(c, "INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel) VALUES (?, ?, CAST(? AS fluxo.papel))", id, unidade, papel);
    }
}
