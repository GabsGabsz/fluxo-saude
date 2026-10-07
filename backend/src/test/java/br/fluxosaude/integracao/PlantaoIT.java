package br.fluxosaude.integracao;

import static br.fluxosaude.integracao.ClienteHttp.exigir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.identidade.infra.HashDeSenhaArgon2;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Passagem de plantão via HTTP com PostgreSQL real: fluxo entrega → recebimento por outro
 * profissional, conflito entre leitura e confirmação (episódio alterado, pendência resolvida),
 * uma pendente por unidade, permissões, isolamento entre unidades, sessão revogada, ausência de
 * efeitos sobre episódios e conteúdo gravado sem nomes.
 */
class PlantaoIT extends IntegracaoBase {

    static final String SENHA = "frase secreta da passagem de plantao";
    static UUID unidadeP;
    static UUID unidadeQ;
    static UUID setorP1;
    static UUID setorP2;
    private static final AtomicBoolean PREPARADO = new AtomicBoolean();

    @Autowired
    Environment env;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void prepararUmaVez() throws Exception {
        if (PREPARADO.compareAndSet(false, true)) {
            String hash = new HashDeSenhaArgon2(1).gerar(SENHA);
            try (Connection c = conexaoDono()) {
                c.setAutoCommit(false);
                unidadeP = unidade(c, "UPA_PLANT_P", "UPA Plantao P");
                unidadeQ = unidade(c, "UPA_PLANT_Q", "UPA Plantao Q");
                setorP1 = setor(c, unidadeP, "OBS_P", "Observacao P");
                setorP2 = setor(c, unidadeP, "EMG_P", "Emergencia P");
                setor(c, unidadeQ, "OBS_Q", "Observacao Q");
                UUID adm = usuario(c, "adm.plantao", "Administracao Plantao", hash, unidadeP, "ADMINISTRADOR");
                usuario(c, "enf.plantao", "Enfermagem Plantao", hash, unidadeP, "ENFERMAGEM");
                usuario(c, "med.plantao", "Medicina Plantao", hash, unidadeP, "MEDICO");
                usuario(c, "dir.plantao", "Direcao Plantao", hash, unidadeP, "DIRECAO");
                UUID multi = usuario(c, "coord.plantao", "Coordenacao Plantao", hash, unidadeP, "COORDENACAO_FLUXO");
                lotar(c, multi, unidadeQ, "COORDENACAO_FLUXO");
                usuario(c, "coord.q.plantao", "Coordenacao Q", hash, unidadeQ, "COORDENACAO_FLUXO");
                assertTrue(adm != null);
                c.commit();
            }
        }
    }

    @Test
    void fluxoConflitosPermissoesEIsolamento() throws Exception {
        ClienteHttp enf = cliente("enf.plantao");
        ClienteHttp med = cliente("med.plantao");

        // ---------------------------------------------------------------- estado: 2 casos, 1 pendência
        String ep1 = abrir(enf, "Paciente Ficticio Plantao Um", setorP1);
        String ep2 = abrir(enf, "Paciente Ficticio Plantao Dois", setorP1);
        String prazo = java.time.Instant.now().plusSeconds(4 * 3600).toString();
        String pend = texto(json.readTree(exigir(201, enf.enviar("POST", "/api/episodios/" + ep1 + "/pendencias",
                "{\"categoria\":\"LOGISTICA\",\"descricao\":\"Acionar transporte\",\"responsavel\":{\"setorId\":\"" + setorP1
                + "\"},\"prazo\":\"" + prazo + "\",\"criticidade\":\"CRITICA\"}")).body()).get("id"));

        // ---------------------------------------------------------------- prévia (todos os abertos)
        JsonNode previa = json.readTree(exigir(200, enf.enviar("GET", "/api/plantao/previa", null)).body());
        assertEquals(2, previa.get("totais").get("casos").asInt());
        assertEquals(1, previa.get("totais").get("pendencias").asInt());
        assertEquals(1, previa.get("totais").get("criticos").asInt(), "pendência de criticidade operacional CRÍTICA");
        assertTrue(previa.get("periodoInicio").isNull(), "primeira passagem da unidade");
        assertTrue(previa.toString().contains("Paciente Ficticio Plantao Um"), "nome lido do estado atual");
        String versaoEp2 = texto(json.readTree(exigir(200, enf.enviar("GET", "/api/episodios/" + ep2, null)).body())
                .get("resumo").get("versao"));

        // ---------------------------------------------------------------- conflito: episódio alterado antes da entrega
        exigir(200, med.enviar("PUT", "/api/episodios/" + ep2 + "/setor",
                "{\"versao\":" + versaoEp2 + ",\"setorId\":\"" + setorP2 + "\"}"));
        HttpResponse<String> desatualizada = enf.enviar("POST", "/api/plantao/passagens",
                "{\"assinatura\":\"" + texto(previa.get("assinatura")) + "\"}");
        assertEquals(409, desatualizada.statusCode());
        assertTrue(desatualizada.body().contains("PASSAGEM_DESATUALIZADA"));
        assertEquals(0, json.readTree(exigir(200, enf.enviar("GET", "/api/plantao/passagens", null)).body()).size(),
                "nada gravado");

        // ---------------------------------------------------------------- nova leitura + entrega
        previa = json.readTree(exigir(200, enf.enviar("GET", "/api/plantao/previa", null)).body());
        String versaoEp2Entregue = texto(json.readTree(exigir(200, enf.enviar("GET", "/api/episodios/" + ep2, null)).body())
                .get("resumo").get("versao"));
        String id = texto(json.readTree(exigir(201, enf.enviar("POST", "/api/plantao/passagens",
                "{\"assinatura\":\"" + texto(previa.get("assinatura")) + "\",\"observacao\":\"Leito 4 em higienizacao\"}"))
                .body()).get("id"));
        HttpResponse<String> segunda = enf.enviar("POST", "/api/plantao/passagens",
                "{\"assinatura\":\"" + texto(previa.get("assinatura")) + "\"}");
        assertEquals(409, segunda.statusCode());
        assertTrue(segunda.body().contains("PASSAGEM_PENDENTE"));

        // ---------------------------------------------------------------- quem entregou não recebe
        JsonNode detalheEnf = json.readTree(exigir(200, enf.enviar("GET", "/api/plantao/passagens/" + id, null)).body());
        HttpResponse<String> proprio = enf.enviar("POST", "/api/plantao/passagens/" + id + "/recebimento",
                "{\"versao\":0,\"assinatura\":\"" + texto(detalheEnf.get("assinaturaRecebimento")) + "\"}");
        assertEquals(422, proprio.statusCode());
        assertTrue(proprio.body().contains("RECEBEDOR_E_ENTREGADOR"));

        // ---------------------------------------------------------------- recebimento com diferenças mudadas: 409
        JsonNode detalhe = json.readTree(exigir(200, med.enviar("GET", "/api/plantao/passagens/" + id, null)).body());
        assertTrue(detalhe.get("integra").asBoolean());
        assertEquals(0, detalhe.get("diferencas").get("pendenciasEncerradas").size());
        String versaoPend = texto(json.readTree(exigir(200, enf.enviar("GET", "/api/episodios/" + ep1, null)).body())
                .get("pendencias").get(0).get("versao"));
        exigir(200, enf.enviar("POST", "/api/pendencias/" + pend + "/resolucao",
                "{\"versao\":" + versaoPend + ",\"texto\":\"Transporte acionado\"}"));
        HttpResponse<String> velha = med.enviar("POST", "/api/plantao/passagens/" + id + "/recebimento",
                "{\"versao\":0,\"assinatura\":\"" + texto(detalhe.get("assinaturaRecebimento")) + "\"}");
        assertEquals(409, velha.statusCode());
        assertTrue(velha.body().contains("RECEBIMENTO_DESATUALIZADO"));
        detalhe = json.readTree(exigir(200, med.enviar("GET", "/api/plantao/passagens/" + id, null)).body());
        assertEquals("ENTREGUE", texto(detalhe.get("passagem").get("status")), "nada confirmado");
        assertEquals(pend, texto(detalhe.get("diferencas").get("pendenciasEncerradas").get(0)));
        // Versão lida errada
        assertEquals(409, med.enviar("POST", "/api/plantao/passagens/" + id + "/recebimento",
                "{\"versao\":5,\"assinatura\":\"" + texto(detalhe.get("assinaturaRecebimento")) + "\"}").statusCode());
        exigir(204, med.enviar("POST", "/api/plantao/passagens/" + id + "/recebimento",
                "{\"versao\":0,\"assinatura\":\"" + texto(detalhe.get("assinaturaRecebimento")) + "\"}"));
        JsonNode recebida = json.readTree(exigir(200, med.enviar("GET", "/api/plantao/passagens/" + id, null)).body());
        assertEquals("RECEBIDA", texto(recebida.get("passagem").get("status")));
        assertEquals("Medicina Plantao", texto(recebida.get("passagem").get("recebidaPorNome")));
        assertEquals(1, recebida.get("passagem").get("diferencasRecebimento").get("pendenciasEncerradas").asInt());
        assertTrue(recebida.get("diferencas").isNull());

        // A passagem (entrega + recebimento) não alterou episódios: ep2 só foi mudado pelo usuário, antes da entrega
        JsonNode caso2 = json.readTree(exigir(200, med.enviar("GET", "/api/episodios/" + ep2, null)).body());
        assertEquals(versaoEp2Entregue, texto(caso2.get("resumo").get("versao")));
        assertTrue(caso2.get("encerradoEm").isNull());
        JsonNode caso1 = json.readTree(exigir(200, med.enviar("GET", "/api/episodios/" + ep1, null)).body());
        assertEquals("RESOLVIDA", texto(caso1.get("pendencias").get(0).get("status")), "só a ação do usuário mudou a pendência");

        // ---------------------------------------------------------------- próxima prévia: período desde a recebida
        JsonNode proxima = json.readTree(exigir(200, med.enviar("GET", "/api/plantao/previa", null)).body());
        assertEquals(texto(recebida.get("passagem").get("entregueEm")), texto(proxima.get("periodoInicio")));

        // ---------------------------------------------------------------- permissões
        exigir(403, cliente("dir.plantao").enviar("GET", "/api/plantao/previa", null));
        exigir(403, cliente("adm.plantao").enviar("GET", "/api/plantao/passagens", null));

        // ---------------------------------------------------------------- isolamento e troca de unidade
        ClienteHttp coordQ = cliente("coord.q.plantao");
        exigir(404, coordQ.enviar("GET", "/api/plantao/passagens/" + id, null));
        assertEquals(0, json.readTree(exigir(200, coordQ.enviar("GET", "/api/plantao/passagens", null)).body()).size());
        ClienteHttp multi = cliente("coord.plantao");
        exigir(200, multi.enviar("PUT", "/api/sessao/unidade", "{\"unidadeId\":\"" + unidadeQ + "\"}"));
        JsonNode previaQ = json.readTree(exigir(200, multi.enviar("GET", "/api/plantao/previa", null)).body());
        assertEquals(0, previaQ.get("totais").get("casos").asInt(), "na Q não aparecem casos da P");
        exigir(404, multi.enviar("GET", "/api/plantao/passagens/" + id, null));

        // ---------------------------------------------------------------- conteúdo gravado sem nomes; auditoria
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT conteudo::text FROM fluxo.passagem_conteudo WHERE passagem_id = ?")) {
            ps.setObject(1, UUID.fromString(id));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                String conteudo = rs.getString(1);
                assertFalse(conteudo.contains("Paciente Ficticio"), "sem nome do paciente no registro da passagem");
                assertFalse(conteudo.contains("Acionar transporte"), "sem texto livre de pendência");
            }
        }
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT string_agg(acao, ',' ORDER BY id) FROM auditoria.registro "
                     + "WHERE recurso = 'fluxo.passagem_plantao' AND recurso_id = ? AND acao LIKE 'PASSAGEM_%'")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals("PASSAGEM_ENTREGUE,PASSAGEM_RECEBIDA", rs.getString(1));
            }
        }

        // ---------------------------------------------------------------- sessão revogada
        ClienteHttp adm = cliente("adm.plantao");
        JsonNode lista = json.readTree(exigir(200, adm.enviar("GET", "/api/admin/usuarios", null)).body());
        JsonNode alvo = null;
        for (JsonNode u : lista.get("itens")) {
            if ("med.plantao".equals(texto(u.get("login")))) {
                alvo = u;
            }
        }
        exigir(200, adm.enviar("POST", "/api/admin/usuarios/" + texto(alvo.get("id")) + "/senha-provisoria",
                "{\"versao\":" + alvo.get("versao").asInt() + "}"));
        exigir(401, med.enviar("GET", "/api/plantao/previa", null));
    }

    // ----------------------------------------------------------------------------

    private String abrir(ClienteHttp c, String nome, UUID setor) throws Exception {
        return texto(json.readTree(exigir(201, c.enviar("POST", "/api/episodios",
                "{\"novoPaciente\":{\"nome\":\"" + nome + "\"},\"setorId\":\"" + setor + "\"}")).body()).get("id"));
    }

    private ClienteHttp cliente(String login) throws Exception {
        ClienteHttp c = new ClienteHttp(Integer.parseInt(env.getRequiredProperty("local.server.port")));
        c.entrar(login, SENHA);
        return c;
    }

    private static String texto(JsonNode n) {
        String s = n.toString();
        return s.length() >= 2 && s.startsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private static UUID unidade(Connection c, String codigo, String nome) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fluxo.unidade (id, codigo, nome, tipo) VALUES (?, ?, ?, 'UPA')")) {
            ps.setObject(1, id);
            ps.setString(2, codigo);
            ps.setString(3, nome);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT fluxo.provisionar_unidade(?)")) {
            ps.setObject(1, id);
            ps.execute();
        }
        return id;
    }

    private static UUID setor(Connection c, UUID unidade, String codigo, String nome) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fluxo.setor (id, unidade_id, codigo, nome) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, unidade);
            ps.setString(3, codigo);
            ps.setString(4, nome);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID usuario(Connection c, String login, String nome, String hash, UUID unidade, String papel)
            throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO fluxo.usuario (id, login, nome, senha_hash, "
                + "deve_trocar_senha, unidade_gestora_id) VALUES (?, ?, ?, ?, false, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, login);
            ps.setString(3, nome);
            ps.setString(4, hash);
            ps.setObject(5, unidade);
            ps.executeUpdate();
        }
        lotar(c, id, unidade, papel);
        return id;
    }

    private static void lotar(Connection c, UUID usuario, UUID unidade, String papel) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel) VALUES (?, ?, CAST(? AS fluxo.papel))")) {
            ps.setObject(1, usuario);
            ps.setObject(2, unidade);
            ps.setString(3, papel);
            ps.executeUpdate();
        }
    }
}
