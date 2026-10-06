package br.fluxosaude.integracao;

import static br.fluxosaude.integracao.ClienteHttp.exigir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.identidade.infra.HashDeSenhaArgon2;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Endpoints de apoio à interface (etapa 6): catálogo da unidade ativa, unidades do usuário,
 * busca exata de paciente e alertas no detalhe do caso — com permissão, isolamento entre
 * unidades (troca de unidade sem mistura de dados) e auditoria.
 */
class CatalogoIT extends IntegracaoBase {

    static final String SENHA = "frase secreta do catalogo da upa";
    static final String MULTI = "coord.catalogo";
    static final String DIRECAO = "direcao.catalogo";
    static final String CNS = "291417776317066";

    static UUID unidadeA;
    static UUID unidadeB;
    private static final AtomicBoolean PREPARADO = new AtomicBoolean();

    @Autowired
    Environment env;

    @Autowired
    Flyway flyway;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void prepararUmaVez() throws Exception {
        if (PREPARADO.compareAndSet(false, true)) {
            String hash = new HashDeSenhaArgon2(1).gerar(SENHA);
            try (Connection c = conexaoDono()) {
                c.setAutoCommit(false);
                unidadeA = unidade(c, "UPA_CAT_A", "UPA Catalogo A", "America/Fortaleza");
                unidadeB = unidade(c, "UPA_CAT_B", "UPA Catalogo B", "America/Manaus");
                setor(c, unidadeA, "OBS_A", "Observacao A");
                setor(c, unidadeB, "OBS_B", "Observacao B");
                UUID multi = usuario(c, MULTI, "Coordenacao Catalogo", hash, unidadeA, "COORDENACAO_FLUXO");
                lotar(c, multi, unidadeB, "COORDENACAO_FLUXO");
                usuario(c, DIRECAO, "Direcao Catalogo", hash, unidadeA, "DIRECAO");
                try (PreparedStatement ps = c.prepareStatement("SELECT set_config('fluxo.usuario_id', ?, true)")) {
                    ps.setString(1, multi.toString());
                    ps.execute();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO fluxo.paciente (id, unidade_id, nome, cns) VALUES (?, ?, 'Paciente Catalogo', ?)")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, unidadeA);
                    ps.setString(3, CNS);
                    ps.executeUpdate();
                }
                c.commit();
            }
        }
    }

    @Test
    void apoioAInterface() throws Exception {
        ClienteHttp coord = cliente(MULTI);
        ClienteHttp direcao = cliente(DIRECAO);

        // ------------------------------------------------------------ unidades do usuário
        JsonNode unidades = json.readTree(exigir(200, coord.enviar("GET", "/api/sessao/unidades", null)).body());
        List<String> nomes = new ArrayList<>();
        unidades.forEach(u -> nomes.add(texto(u.get("nome"))));
        assertEquals(List.of("UPA Catalogo A", "UPA Catalogo B"), nomes);
        assertEquals(1, json.readTree(exigir(200, direcao.enviar("GET", "/api/sessao/unidades", null)).body()).size());

        // ------------------------------------------------------------ catálogo da unidade ativa
        exigir(200, coord.enviar("PUT", "/api/sessao/unidade", "{\"unidadeId\":\"" + unidadeA + "\"}"));
        JsonNode catA = json.readTree(exigir(200, coord.enviar("GET", "/api/catalogo", null)).body());
        assertEquals("UPA Catalogo A", texto(catA.get("unidade").get("nome")));
        assertEquals("America/Fortaleza", texto(catA.get("unidade").get("fusoHorario")));
        assertEquals(List.of("Observacao A"), nomesDe(catA.get("setores")), "só setores da unidade ativa");
        assertTrue(catA.get("etapas").size() > 5 && catA.get("transicoes").size() > 5 && catA.get("motivos").size() > 5);
        assertTrue(catA.get("especialidades").size() > 0);
        assertTrue(nomesDe(catA.get("profissionais")).contains("Coordenacao Catalogo"));
        JsonNode catDirecao = json.readTree(exigir(200, direcao.enviar("GET", "/api/catalogo", null)).body());
        assertEquals(0, catDirecao.get("profissionais").size(), "direção não recebe nomes de profissionais");

        // Troca de unidade: o catálogo passa a ser só o da B (sem mistura)
        exigir(200, coord.enviar("PUT", "/api/sessao/unidade", "{\"unidadeId\":\"" + unidadeB + "\"}"));
        JsonNode catB = json.readTree(exigir(200, coord.enviar("GET", "/api/catalogo", null)).body());
        assertEquals("UPA Catalogo B", texto(catB.get("unidade").get("nome")));
        assertEquals("America/Manaus", texto(catB.get("unidade").get("fusoHorario")));
        assertEquals(List.of("Observacao B"), nomesDe(catB.get("setores")));
        assertFalse(catB.toString().contains(texto(catA.get("etapas").get(0).get("id"))), "etapas da A não aparecem na B");
        // Paciente da A não é encontrado a partir da B
        assertEquals(0, json.readTree(exigir(200, coord.enviar("GET", "/api/pacientes?cns=" + CNS, null)).body()).size());

        // ------------------------------------------------------------ busca exata de paciente (na A)
        exigir(200, coord.enviar("PUT", "/api/sessao/unidade", "{\"unidadeId\":\"" + unidadeA + "\"}"));
        JsonNode achados = json.readTree(exigir(200, coord.enviar("GET", "/api/pacientes?cns=" + CNS, null)).body());
        assertEquals(1, achados.size());
        assertEquals("Paciente Catalogo", texto(achados.get(0).get("nome")));
        exigir(422, coord.enviar("GET", "/api/pacientes", null));
        exigir(422, coord.enviar("GET", "/api/pacientes?cns=123", null));
        exigir(403, direcao.enviar("GET", "/api/pacientes?cns=" + CNS, null));

        // ------------------------------------------------------------ caso com alertas (lista, mesmo vazia)
        String paciente = texto(achados.get(0).get("id"));
        JsonNode aberto = json.readTree(exigir(201, coord.enviar("POST", "/api/episodios",
                "{\"pacienteId\":\"" + paciente + "\",\"setorId\":\"" + texto(catA.get("setores").get(0).get("id")) + "\"}"))
                .body());
        JsonNode caso = json.readTree(exigir(200, coord.enviar("GET", "/api/episodios/" + texto(aberto.get("id")), null))
                .body());
        assertTrue(caso.get("alertas").isArray() && caso.get("alertas").size() == 0, "sem regras, sem alertas");

        // ------------------------------------------------------------ auditoria da busca nominal
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM auditoria.registro WHERE acao = 'CONSULTA_PACIENTE' AND recurso_id = ?")) {
            ps.setString(1, paciente);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getLong(1), "uma busca com resultado = um registro");
            }
        }
    }

    // ----------------------------------------------------------------------------

    private ClienteHttp cliente(String login) throws Exception {
        ClienteHttp c = new ClienteHttp(Integer.parseInt(env.getRequiredProperty("local.server.port")));
        c.entrar(login, SENHA);
        return c;
    }

    private static List<String> nomesDe(JsonNode lista) {
        List<String> r = new ArrayList<>();
        lista.forEach(n -> r.add(texto(n.get("nome"))));
        return r;
    }

    private static String texto(JsonNode n) {
        String s = n.toString();
        return s.length() >= 2 && s.startsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private static UUID unidade(Connection c, String codigo, String nome, String fuso) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fluxo.unidade (id, codigo, nome, tipo, fuso_horario) VALUES (?, ?, ?, 'UPA', ?)")) {
            ps.setObject(1, id);
            ps.setString(2, codigo);
            ps.setString(3, nome);
            ps.setString(4, fuso);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT fluxo.provisionar_unidade(?)")) {
            ps.setObject(1, id);
            ps.execute();
        }
        return id;
    }

    private static void setor(Connection c, UUID unidade, String codigo, String nome) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fluxo.setor (id, unidade_id, codigo, nome) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, unidade);
            ps.setString(3, codigo);
            ps.setString(4, nome);
            ps.executeUpdate();
        }
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
