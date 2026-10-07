package br.fluxosaude.integracao;

import static br.fluxosaude.integracao.ClienteHttp.exigir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.identidade.infra.HashDeSenhaArgon2;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Indicadores via HTTP com PostgreSQL real: valores calculados à mão (durações a partir do mesmo
 * "agora" do banco), conjunto vazio e denominador zero, filtro de setor, agregação sobre TODOS os
 * abertos (mais do que uma página da Torre), permissões e resposta da Direção sem dado nominal.
 */
class IndicadoresIT extends IntegracaoBase {

    static final String SENHA = "frase secreta dos indicadores da upa";
    static final String FUSO = "America/Fortaleza";
    static final int ABERTOS_EM_MASSA = 520;
    static UUID unidade;
    static UUID setor1;
    static UUID setor2;
    static UUID outroSetor;
    static UUID episodioX1;
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
        setor1 = UUID.randomUUID();
        setor2 = UUID.randomUUID();
        outroSetor = UUID.randomUUID();
        episodioX1 = UUID.randomUUID();
        UUID adm = UUID.randomUUID();
        try (Connection c = conexaoDono(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("INSERT INTO fluxo.unidade (id, codigo, nome, tipo, fuso_horario) VALUES ('" + unidade
                    + "', 'UPA_IND', 'UPA Indicadores', 'UPA', '" + FUSO + "'), ('" + outra
                    + "', 'UPA_IND_2', 'UPA Indicadores 2', 'UPA', '" + FUSO + "')");
            st.execute("SELECT fluxo.provisionar_unidade('" + unidade + "'), fluxo.provisionar_unidade('" + outra + "')");
            st.execute("INSERT INTO fluxo.setor (id, unidade_id, codigo, nome) VALUES ('" + setor1 + "', '" + unidade
                    + "', 'S1', 'Setor 1'), ('" + setor2 + "', '" + unidade + "', 'S2', 'Setor 2'), ('" + outroSetor
                    + "', '" + outra + "', 'S1', 'Setor outra')");
            usuario(c, adm, "adm.ind", "Administracao Ind", hash, "ADMINISTRADOR");
            usuario(c, UUID.randomUUID(), "dir.ind", "Direcao Ind", hash, "DIRECAO");
            usuario(c, UUID.randomUUID(), "coord.ind", "Coordenacao Ind", hash, "COORDENACAO_FLUXO");
            usuario(c, UUID.randomUUID(), "enf.ind", "Enfermagem Ind", hash, "ENFERMAGEM");
            st.execute("SELECT set_config('fluxo.usuario_id', '" + adm + "', true)");
            String u = "'" + unidade + "'";
            String aj = "'{\"ajuste_manual\": true, \"ajuste_justificativa\": \"carga de teste\"}'::jsonb";
            // X1: 480 min (alta); bloqueio SEM_VAGA de 360 min. X2: 120 min. X3: transferência 660 min
            // (solicitação -> aceite 360; aceite -> saída 240). X4: encerramento administrativo (excluído).
            // X5: aberto há 7 h (acima do limite de 400 min agora). Mais 520 abertos há 1 h.
            st.execute("INSERT INTO fluxo.paciente (id, unidade_id, nome) SELECT ('99999999-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid, "
                    + u + ", 'Paciente Ind ' || n FROM generate_series(1, " + (5 + ABERTOS_EM_MASSA) + ") n");
            String et = "(SELECT id FROM fluxo.etapa WHERE unidade_id = " + u + " AND codigo = '%s')";
            st.execute(String.format("INSERT INTO fluxo.episodio (id, unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde) VALUES "
                    + "('" + episodioX1 + "', " + u + ", '99999999-0000-0000-0000-000000000001', '" + setor1 + "', now() - interval '10 hours', " + et + ", now() - interval '10 hours'),"
                    + "('88888888-0000-0000-0000-000000000002', " + u + ", '99999999-0000-0000-0000-000000000002', '" + setor1 + "', now() - interval '6 hours', " + et + ", now() - interval '6 hours'),"
                    + "('88888888-0000-0000-0000-000000000003', " + u + ", '99999999-0000-0000-0000-000000000003', '" + setor1 + "', now() - interval '12 hours', " + et + ", now() - interval '5 hours'),"
                    + "('88888888-0000-0000-0000-000000000004', " + u + ", '99999999-0000-0000-0000-000000000004', '" + setor1 + "', now() - interval '8 hours', " + et + ", now() - interval '8 hours'),"
                    + "('88888888-0000-0000-0000-000000000005', " + u + ", '99999999-0000-0000-0000-000000000005', '" + setor1 + "', now() - interval '7 hours', " + et + ", now() - interval '7 hours')",
                    "EM_ATENDIMENTO", "EM_ATENDIMENTO", "ACEITO", "EM_ATENDIMENTO", "EM_ATENDIMENTO"));
            st.execute("INSERT INTO fluxo.episodio (unidade_id, paciente_id, setor_id, entrada_em, etapa_id, etapa_desde) "
                    + "SELECT " + u + ", ('99999999-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid, '" + setor1
                    + "', now() - interval '1 hour', " + String.format(et, "EM_ATENDIMENTO") + ", now() - interval '1 hour' "
                    + "FROM generate_series(6, " + (5 + ABERTOS_EM_MASSA) + ") n");
            st.execute("INSERT INTO fluxo.evento_episodio (id, unidade_id, episodio_id, tipo, ocorrido_em, dados) VALUES "
                    + "(gen_random_uuid(), " + u + ", '" + episodioX1 + "', 'BLOQUEIO_DEFINIDO', now() - interval '9 hours', " + aj + " || '{\"motivo\": \"SEM_VAGA\", \"categoria\": \"REGULACAO\"}'),"
                    + "(gen_random_uuid(), " + u + ", '" + episodioX1 + "', 'BLOQUEIO_REMOVIDO', now() - interval '3 hours', " + aj + " || '{\"motivo\": \"SEM_VAGA\"}'),"
                    + "(gen_random_uuid(), " + u + ", '88888888-0000-0000-0000-000000000003', 'ETAPA_ALTERADA', now() - interval '11 hours', " + aj + " || '{\"de\": \"AGUARDANDO_SOLICITACAO_TRANSFERENCIA\", \"para\": \"TRANSFERENCIA_SOLICITADA\"}'),"
                    + "(gen_random_uuid(), " + u + ", '88888888-0000-0000-0000-000000000003', 'ETAPA_ALTERADA', now() - interval '5 hours', " + aj + " || '{\"de\": \"TRANSFERENCIA_SOLICITADA\", \"para\": \"ACEITO\"}')");
            st.execute(String.format("UPDATE fluxo.episodio SET etapa_id = " + et + ", etapa_desde = now() - interval '2 hours', desfecho = 'ALTA', "
                    + "encerrado_em = now() - interval '2 hours', versao = versao + 1 WHERE id = '" + episodioX1 + "'", "ALTA"));
            st.execute(String.format("UPDATE fluxo.episodio SET etapa_id = " + et + ", etapa_desde = now() - interval '4 hours', desfecho = 'ALTA', "
                    + "encerrado_em = now() - interval '4 hours', versao = versao + 1 WHERE id = '88888888-0000-0000-0000-000000000002'", "ALTA"));
            st.execute(String.format("UPDATE fluxo.episodio SET etapa_id = " + et + ", etapa_desde = now() - interval '1 hour', desfecho = 'TRANSFERENCIA', "
                    + "encerrado_em = now() - interval '1 hour', versao = versao + 1 WHERE id = '88888888-0000-0000-0000-000000000003'", "TRANSFERIDO"));
            st.execute(String.format("UPDATE fluxo.episodio SET etapa_id = " + et + ", etapa_desde = now() - interval '7 hours', "
                    + "desfecho = 'ENCERRAMENTO_ADMINISTRATIVO', encerrado_em = now() - interval '7 hours', "
                    + "justificativa_encerramento = 'Registro indevido', versao = versao + 1 WHERE id = '88888888-0000-0000-0000-000000000004'",
                    "CANCELADO_ENCERRADO"));
            st.execute("INSERT INTO fluxo.regra_alerta (id, unidade_id, nome, tipo, limite) VALUES (gen_random_uuid(), " + u
                    + ", 'Permanencia 400 min (ilustrativa)', 'TEMPO_TOTAL', interval '400 minutes')");
            c.commit();
        }
    }

    @Test
    void valoresAgregadosPermissoesESemDadoNominal() throws Exception {
        ClienteHttp direcao = cliente("dir.ind");
        LocalDate hoje = LocalDate.now(ZoneId.of(FUSO));
        String periodo = "inicio=" + hoje.minusDays(1) + "&fim=" + hoje;

        HttpResponse<String> resposta = exigir(200, direcao.enviar("GET", "/api/indicadores?" + periodo, null));
        String corpo = resposta.body();
        JsonNode r = json.readTree(corpo);
        JsonNode h = r.get("historico");
        assertEquals(3, h.get("permanencia").get("incluidos").asInt());
        assertEquals(1, h.get("permanencia").get("naoIncluidos").asInt(), "excluídos (encerramento administrativo)");
        assertEquals(420.0, h.get("permanencia").get("mediaMin").asDouble(), 0.001);
        assertEquals(480.0, h.get("permanencia").get("medianaMin").asDouble(), 0.001);
        JsonNode limite = h.get("acimaDosLimites").get(0);
        assertEquals(3, limite.get("regra").get("populacao").asInt());
        assertEquals(2, limite.get("regra").get("acima").asInt());
        assertEquals(66.666, limite.get("percentual").get("valor").asDouble(), 0.01);
        assertEquals(1, h.get("transferencias").asInt());
        assertEquals(360.0, h.get("solicitacaoAceite").get("mediaMin").asDouble(), 0.001);
        assertEquals(240.0, h.get("aceiteSaida").get("mediaMin").asDouble(), 0.001);
        JsonNode motivo = h.get("motivos").get(0);
        assertEquals("SEM_VAGA", texto(motivo.get("motivo").get("codigo")));
        assertEquals(360.0, motivo.get("motivo").get("minutos").asDouble(), 0.001);
        assertEquals(100.0, motivo.get("percentualDoTempo").get("valor").asDouble(), 0.001);
        assertEquals(FUSO, texto(r.get("fuso")));

        // Retrato: TODOS os abertos (mais que a página máxima da Torre), acima do limite agora.
        long abertos = 0;
        for (JsonNode i : r.get("retrato").get("itens")) {
            if ("ABERTOS".equals(texto(i.get("dimensao")))) {
                abertos = i.get("quantidade").asLong();
            }
        }
        assertEquals(ABERTOS_EM_MASSA + 1, abertos);
        assertTrue(r.get("retrato").get("acimaDosLimitesDisponivel").asBoolean());
        assertEquals(1, r.get("retrato").get("acimaDosLimites").get(0).get("episodios").asInt());
        ClienteHttp coord = cliente("coord.ind");
        JsonNode torre = json.readTree(exigir(200, coord.enviar("GET", "/api/episodios?limite=500", null)).body());
        assertEquals(500, torre.get("itens").size(), "a Torre pagina; os indicadores não dependem dela");

        // Direção: nenhum dado nominal ou identificador de episódio/paciente
        assertFalse(corpo.contains("Paciente Ind"), "sem nomes");
        assertFalse(corpo.contains("pacienteNome") || corpo.contains("cns") || corpo.contains("episodioId"));
        assertFalse(corpo.contains(episodioX1.toString()), "sem identificador de episódio");
        assertFalse(corpo.contains("99999999-0000"), "sem identificador de paciente");

        // Conjunto vazio e denominador zero
        LocalDate antigo = hoje.minusDays(40);
        JsonNode vazio = json.readTree(exigir(200, direcao.enviar("GET", "/api/indicadores?inicio=" + antigo + "&fim="
                + antigo, null)).body()).get("historico");
        assertEquals(0, vazio.get("permanencia").get("incluidos").asInt());
        assertTrue(vazio.get("permanencia").get("mediaMin").isNull(), "sem dados: ausente, não zero");
        assertTrue(vazio.get("acimaDosLimites").get(0).get("percentual").get("valor").isNull(), "sem divisão por zero");
        assertEquals(0, vazio.get("motivos").size());

        // Filtro de setor (sem dados no S2) e setor de outra unidade recusado
        JsonNode s2 = json.readTree(exigir(200, direcao.enviar("GET", "/api/indicadores?" + periodo + "&setor=" + setor2,
                null)).body());
        assertEquals(0, s2.get("historico").get("permanencia").get("incluidos").asInt());
        assertTrue(direcao.enviar("GET", "/api/indicadores?" + periodo + "&setor=" + outroSetor, null).body()
                .contains("SETOR_INVALIDO"));

        // Validação de período e permissões
        exigir(422, direcao.enviar("GET", "/api/indicadores?inicio=" + hoje + "&fim=" + hoje.minusDays(1), null));
        exigir(422, direcao.enviar("GET", "/api/indicadores?inicio=" + hoje + "&fim=" + hoje.plusDays(1), null));
        exigir(422, direcao.enviar("GET", "/api/indicadores?inicio=" + hoje.minusDays(400) + "&fim=" + hoje, null));
        exigir(400, direcao.enviar("GET", "/api/indicadores", null));
        exigir(403, cliente("enf.ind").enviar("GET", "/api/indicadores?" + periodo, null));
        exigir(403, cliente("adm.ind").enviar("GET", "/api/indicadores/dicionario", null));
        exigir(200, coord.enviar("GET", "/api/indicadores?" + periodo, null));
        JsonNode dic = json.readTree(exigir(200, direcao.enviar("GET", "/api/indicadores/dicionario", null)).body());
        assertEquals(9, dic.size());
        // Direção continua sem acesso nominal
        exigir(403, direcao.enviar("GET", "/api/episodios", null));
        exigir(403, direcao.enviar("GET", "/api/plantao/previa", null));
    }

    // ----------------------------------------------------------------------------

    private ClienteHttp cliente(String login) throws Exception {
        ClienteHttp c = new ClienteHttp(Integer.parseInt(env.getRequiredProperty("local.server.port")));
        c.entrar(login, SENHA);
        return c;
    }

    private static String texto(JsonNode n) {
        String s = n.toString();
        return s.length() >= 2 && s.startsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private static void usuario(Connection c, UUID id, String login, String nome, String hash, String papel)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO fluxo.usuario (id, login, nome, senha_hash, "
                + "deve_trocar_senha, unidade_gestora_id) VALUES (?, ?, ?, ?, false, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, login);
            ps.setString(3, nome);
            ps.setString(4, hash);
            ps.setObject(5, unidade);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel) VALUES (?, ?, CAST(? AS fluxo.papel))")) {
            ps.setObject(1, id);
            ps.setObject(2, unidade);
            ps.setString(3, papel);
            ps.executeUpdate();
        }
    }
}
