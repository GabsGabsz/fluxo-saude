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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gestão de usuários e lotações de ponta a ponta (HTTP → serviço → PostgreSQL com RLS,
 * funções da V11, Spring Session JDBC e auditoria): permissões, autoalteração, alcance entre
 * unidades, usuário em duas unidades, revogação com encerramento de sessões, revalidação no
 * banco, concorrência (versão e revogação cruzada de administradores) e auditoria sem segredos.
 */
class GestaoUsuariosIT extends IntegracaoBase {

    static final String SENHA = "frase secreta da gestao de usuarios";
    static final String ADM_A = "adm.gu.a";
    static final String ADM2_A = "adm2.gu.a";
    static final String ADM_B = "adm.gu.b";
    static final String COORD_A = "coord.gu.a";
    static final String MULTI = "multi.gu";

    static UUID unidadeA;
    static UUID unidadeB;
    private static final AtomicBoolean PREPARADO = new AtomicBoolean();

    @Autowired
    Environment env;

    /** Garante que as migrações rodaram antes da preparação (ver SessaoIT). */
    @Autowired
    Flyway flyway;

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> senhasProvisorias = new ArrayList<>();

    @BeforeEach
    void prepararUmaVez() throws Exception {
        if (PREPARADO.compareAndSet(false, true)) {
            String hash = new HashDeSenhaArgon2(1).gerar(SENHA);
            try (Connection c = conexaoDono()) {
                c.setAutoCommit(false);
                unidadeA = unidade(c, "UPA_GU_A");
                unidadeB = unidade(c, "UPA_GU_B");
                usuario(c, ADM_A, "Administradora Unidade A", hash, unidadeA, "ADMINISTRADOR");
                usuario(c, ADM2_A, "Segundo Administrador A", hash, unidadeA, "ADMINISTRADOR");
                usuario(c, ADM_B, "Administrador Unidade B", hash, unidadeB, "ADMINISTRADOR");
                usuario(c, COORD_A, "Coordenacao Unidade A", hash, unidadeA, "COORDENACAO_FLUXO");
                UUID multi = usuario(c, MULTI, "Enfermeira Duas Unidades", hash, unidadeA, "ENFERMAGEM");
                lotar(c, multi, unidadeB, "ENFERMAGEM");
                c.commit();
            }
        }
    }

    @Test
    void gestaoDeUsuariosDePontaAPonta() throws Exception {
        ClienteHttp admA = cliente(ADM_A, SENHA);
        ClienteHttp adm2A = cliente(ADM2_A, SENHA);
        ClienteHttp admB = cliente(ADM_B, SENHA);
        ClienteHttp coord = cliente(COORD_A, SENHA);
        UUID idAdmA = id(ADM_A);
        UUID idAdmB = id(ADM_B);
        UUID idMulti = id(MULTI);
        UUID idCoord = id(COORD_A);

        // ---------------------------------------------------------------- sem permissão
        exigir(403, coord.enviar("GET", "/api/admin/usuarios", null));
        exigir(403, coord.enviar("POST", "/api/admin/usuarios",
                "{\"login\":\"via.coord\",\"nome\":\"Via Coordenacao\",\"papeis\":[\"ADMINISTRADOR\"]}"));
        exigir(403, coord.enviar("PUT", "/api/admin/usuarios/" + idCoord + "/papeis",
                "{\"versao\":0,\"papeis\":[\"ADMINISTRADOR\"]}"));
        assertEquals(Set.of("COORDENACAO_FLUXO"), papeisNoBanco(idCoord, unidadeA), "coordenação não se promove");

        // ---------------------------------------------------------------- criação + 1º acesso
        HttpResponse<String> criado = exigir(201, admA.enviar("POST", "/api/admin/usuarios", """
                {"login":"Novo.GU","nome":"Novo  Profissional","email":"novo.gu@upa.test","papeis":["ENFERMAGEM"]}"""));
        assertTrue(criado.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        JsonNode p = json.readTree(criado.body());
        UUID idNovo = UUID.fromString(texto(p.get("id")));
        String provisoria = texto(p.get("senhaProvisoria"));
        senhasProvisorias.add(provisoria);
        assertEquals(0, p.get("versao").asInt());
        exigir(422, admA.enviar("POST", "/api/admin/usuarios",
                "{\"login\":\"novo.gu\",\"nome\":\"Outro\",\"papeis\":[\"MEDICO\"]}"));
        exigir(400, admA.enviar("POST", "/api/admin/usuarios",
                "{\"login\":\"sem.papel\",\"nome\":\"Sem Papel\",\"papeis\":[]}"));
        exigir(400, admA.enviar("POST", "/api/admin/usuarios",
                "{\"login\":\"papel.ruim\",\"nome\":\"Papel Ruim\",\"papeis\":[\"SUPERUSUARIO\"]}"));

        ClienteHttp novo = cliente("novo.gu", provisoria);
        assertTrue(novo.enviar("GET", "/api/sessao", null).body().contains("\"deveTrocarSenha\":true"));
        exigir(403, novo.enviar("GET", "/api/episodios", null));            // troca obrigatória primeiro
        String senhaNovo = "orquidea amarela no corredor tres";
        exigir(204, novo.enviar("PUT", "/api/sessao/senha",
                "{\"senhaAtual\":\"" + provisoria + "\",\"novaSenha\":\"" + senhaNovo + "\"}"));
        exigir(200, novo.enviar("GET", "/api/sessao/csrf", null));
        exigir(200, novo.enviar("GET", "/api/episodios", null));

        // ---------------------------------------------------------------- autoalteração e escalada
        HttpResponse<String> auto = admA.enviar("PUT", "/api/admin/usuarios/" + idAdmA + "/papeis",
                "{\"versao\":0,\"papeis\":[\"ADMINISTRADOR\",\"COORDENACAO_FLUXO\"]}");
        exigir(422, auto);
        assertTrue(auto.body().contains("AUTOALTERACAO"));
        exigir(422, admA.enviar("PUT", "/api/admin/usuarios/" + idAdmA + "/situacao", "{\"versao\":0,\"ativo\":false}"));
        // Campo extra tentando apontar outra unidade: ignorado ou recusado, nunca obedecido —
        // a unidade vem só da sessão (unidade ativa revalidada pelo banco).
        int vNovo = versao(admA, idNovo);
        int extra = admA.enviar("PUT", "/api/admin/usuarios/" + idNovo + "/papeis",
                "{\"versao\":" + vNovo + ",\"papeis\":[\"ENFERMAGEM\",\"MEDICO\"],\"unidadeId\":\"" + unidadeB + "\"}")
                .statusCode();
        assertTrue(extra == 200 || extra == 400, "status " + extra);
        assertEquals(Set.of(), papeisNoBanco(idNovo, unidadeB), "nada criado na unidade B");
        // Usuário de outra unidade: invisível (404) para leitura e operações de conta
        exigir(404, admA.enviar("GET", "/api/admin/usuarios/" + idAdmB, null));
        exigir(404, admA.enviar("PUT", "/api/admin/usuarios/" + idAdmB + "/situacao", "{\"versao\":0,\"ativo\":false}"));
        exigir(404, admA.enviar("POST", "/api/admin/usuarios/" + idAdmB + "/senha-provisoria", "{\"versao\":0}"));
        assertTrue(ativo(idAdmB));

        // ---------------------------------------------------------------- usuário em duas unidades
        ClienteHttp multi = cliente(MULTI, SENHA);
        exigir(200, multi.enviar("PUT", "/api/sessao/unidade", "{\"unidadeId\":\"" + unidadeA + "\"}"));
        JsonNode vistaMulti = json.readTree(exigir(200, admA.enviar("GET", "/api/admin/usuarios/" + idMulti, null)).body());
        assertTrue(booleano(vistaMulti.get("possuiOutrasUnidades")));
        assertFalse(booleano(vistaMulti.get("contaGerenciavel")));
        int vMulti = vistaMulti.get("versao").asInt();
        exigir(403, admA.enviar("PUT", "/api/admin/usuarios/" + idMulti + "/situacao",
                "{\"versao\":" + vMulti + ",\"ativo\":false}"));
        exigir(403, admA.enviar("POST", "/api/admin/usuarios/" + idMulti + "/senha-provisoria", "{\"versao\":" + vMulti + "}"));
        exigir(403, admA.enviar("PUT", "/api/admin/usuarios/" + idMulti + "/conta",
                "{\"versao\":" + vMulti + ",\"nome\":\"Nome Trocado\"}"));
        assertTrue(ativo(idMulti));
        // A revoga só o acesso na A: sessões encerradas; a conta segue ativa na B
        exigir(200, admA.enviar("PUT", "/api/admin/usuarios/" + idMulti + "/papeis",
                "{\"versao\":" + vMulti + ",\"papeis\":[]}"));
        exigir(401, multi.enviar("GET", "/api/episodios", null));
        assertEquals(0, sessoesNoBanco(idMulti), "sessões do usuário removidas");
        assertEquals(Set.of("ENFERMAGEM"), papeisNoBanco(idMulti, unidadeB));
        ClienteHttp multiDeNovo = cliente(MULTI, SENHA);
        JsonNode sessaoMulti = json.readTree(exigir(200, multiDeNovo.enviar("GET", "/api/sessao", null)).body());
        assertEquals(unidadeB.toString(), texto(sessaoMulti.get("unidadeAtiva")));
        assertEquals(1, sessaoMulti.get("lotacoes").size());
        exigir(200, multiDeNovo.enviar("GET", "/api/episodios", null));
        exigir(404, admA.enviar("GET", "/api/admin/usuarios/" + idMulti, null));
        // A B tem a única lotação restante, mas não é a unidade gestora: não "herda" a conta
        JsonNode naB = json.readTree(exigir(200, admB.enviar("GET", "/api/admin/usuarios/" + idMulti, null)).body());
        assertFalse(booleano(naB.get("contaGerenciavel")));
        exigir(403, admB.enviar("POST", "/api/admin/usuarios/" + idMulti + "/senha-provisoria",
                "{\"versao\":" + naB.get("versao").asInt() + "}"));
        // Vincular de volta à A uma conta existente: busca pelo login + papéis
        JsonNode loc = json.readTree(exigir(200, admA.enviar("GET", "/api/admin/usuarios/busca?login=MULTI.GU", null)).body());
        assertFalse(booleano(loc.get("lotadoNaUnidade")));
        assertTrue(booleano(loc.get("vinculavel")));
        assertFalse(loc.toString().contains("Enfermeira"), "busca não expõe o nome");
        exigir(200, admA.enviar("PUT", "/api/admin/usuarios/" + idMulti + "/papeis",
                "{\"versao\":" + loc.get("versao").asInt() + ",\"papeis\":[\"MEDICO\"]}"));
        assertEquals(Set.of("MEDICO"), papeisNoBanco(idMulti, unidadeA));
        exigir(404, admA.enviar("GET", "/api/admin/usuarios/busca?login=nao.existe", null));

        // ---------------------------------------------------------------- conta órfã não é "adotável"
        JsonNode temp = json.readTree(exigir(201, admB.enviar("POST", "/api/admin/usuarios",
                "{\"login\":\"temp.gu\",\"nome\":\"Temporario Hospital\",\"papeis\":[\"TRANSPORTE\"]}")).body());
        UUID idTemp = UUID.fromString(texto(temp.get("id")));
        senhasProvisorias.add(texto(temp.get("senhaProvisoria")));
        exigir(200, admB.enviar("PUT", "/api/admin/usuarios/" + idTemp + "/papeis", "{\"versao\":0,\"papeis\":[]}"));
        assertFalse(ativo(idTemp), "sem nenhuma lotação, a conta é desativada");
        JsonNode orfa = json.readTree(exigir(200, admA.enviar("GET", "/api/admin/usuarios/busca?login=temp.gu", null)).body());
        assertFalse(booleano(orfa.get("vinculavel")));
        exigir(403, admA.enviar("PUT", "/api/admin/usuarios/" + idTemp + "/papeis",
                "{\"versao\":" + orfa.get("versao").asInt() + ",\"papeis\":[\"ADMINISTRADOR\"]}"));
        assertEquals(Set.of(), papeisNoBanco(idTemp, unidadeA), "órfã não foi adotada pela A");

        // ---------------------------------------------------------------- senha provisória (redefinição)
        ClienteHttp novoLogado = cliente("novo.gu", senhaNovo);       // sessão viva antes da redefinição
        exigir(200, novoLogado.enviar("GET", "/api/episodios", null));
        vNovo = versao(admA, idNovo);
        JsonNode reset = json.readTree(exigir(200, admA.enviar("POST", "/api/admin/usuarios/" + idNovo
                + "/senha-provisoria", "{\"versao\":" + vNovo + "}")).body());
        String provisoria2 = texto(reset.get("senhaProvisoria"));
        senhasProvisorias.add(provisoria2);
        exigir(401, novoLogado.enviar("GET", "/api/episodios", null));   // sessão encerrada pela redefinição
        assertEquals(0, sessoesNoBanco(idNovo));
        ClienteHttp senhaAntiga = new ClienteHttp(porta());
        senhaAntiga.enviar("GET", "/api/sessao/csrf", null);
        exigir(401, senhaAntiga.enviar("POST", "/api/sessao",
                "{\"login\":\"novo.gu\",\"senha\":\"" + senhaNovo + "\"}"));
        ClienteHttp novo2 = cliente("novo.gu", provisoria2);
        assertTrue(novo2.enviar("GET", "/api/sessao", null).body().contains("\"deveTrocarSenha\":true"));

        // ---------------------------------------------------------------- concorrência (mesma versão)
        int v = versao(admA, idNovo);
        String corpo1 = "{\"versao\":" + v + ",\"papeis\":[\"MEDICO\"]}";
        String corpo2 = "{\"versao\":" + v + ",\"papeis\":[\"TRANSPORTE\"]}";
        var f1 = CompletableFuture.supplyAsync(() -> enviar(admA, "PUT", "/api/admin/usuarios/" + idNovo + "/papeis", corpo1));
        var f2 = CompletableFuture.supplyAsync(() -> enviar(adm2A, "PUT", "/api/admin/usuarios/" + idNovo + "/papeis", corpo2));
        List<Integer> status = List.of(f1.get().statusCode(), f2.get().statusCode());
        assertTrue(status.contains(200) && status.contains(409), "exatamente um vence: " + status);
        Set<String> papeisFinais = papeisNoBanco(idNovo, unidadeA);
        assertTrue(papeisFinais.equals(Set.of("MEDICO")) || papeisFinais.equals(Set.of("TRANSPORTE")), papeisFinais::toString);
        exigir(409, admA.enviar("PUT", "/api/admin/usuarios/" + idNovo + "/papeis", corpo1)); // versão velha

        // ---------------------------------------------------------------- desativação
        v = versao(admA, idNovo);
        exigir(200, admA.enviar("PUT", "/api/admin/usuarios/" + idNovo + "/situacao", "{\"versao\":" + v + ",\"ativo\":false}"));
        exigir(401, novo2.enviar("GET", "/api/sessao", null));
        assertEquals(0, sessoesNoBanco(idNovo));
        ClienteHttp inativo = new ClienteHttp(porta());
        inativo.enviar("GET", "/api/sessao/csrf", null);
        exigir(401, inativo.enviar("POST", "/api/sessao", "{\"login\":\"novo.gu\",\"senha\":\"" + provisoria2 + "\"}"));

        // ---------------------------------------------------------------- revalidação sem apagar sessão
        // Mudança feita fora da API (DBA): a sessão antiga é recusada na requisição seguinte.
        exigir(200, coord.enviar("GET", "/api/episodios", null));
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE fluxo.lotacao SET papel = 'MEDICO' WHERE usuario_id = ? AND unidade_id = ?")) {
            ps.setObject(1, idCoord);
            ps.setObject(2, unidadeA);
            assertEquals(1, ps.executeUpdate());
        }
        HttpResponse<String> revogada = coord.enviar("GET", "/api/episodios", null);
        exigir(401, revogada);
        assertTrue(revogada.body().contains("SESSAO_REVOGADA"));

        // ---------------------------------------------------------------- revogação cruzada de administradores
        UUID idAdm2 = id(ADM2_A);
        int vA = versao(adm2A, idAdmA);
        int v2 = versao(admA, idAdm2);
        var r1 = CompletableFuture.supplyAsync(() -> enviar(admA, "PUT", "/api/admin/usuarios/" + idAdm2 + "/papeis",
                "{\"versao\":" + v2 + ",\"papeis\":[\"AUDITORIA\"]}"));
        var r2 = CompletableFuture.supplyAsync(() -> enviar(adm2A, "PUT", "/api/admin/usuarios/" + idAdmA + "/papeis",
                "{\"versao\":" + vA + ",\"papeis\":[\"AUDITORIA\"]}"));
        List<Integer> cruzado = List.of(r1.get().statusCode(), r2.get().statusCode());
        assertTrue(administradoresAtivos(unidadeA) >= 1, "unidade nunca fica sem administrador: " + cruzado);
        assertTrue(cruzado.contains(200), cruzado::toString);
        // O perdedor recebe 422 (último administrador), 403 (já não administra) ou 401 (sessão revogada).
        assertTrue(cruzado.stream().allMatch(s -> s == 200 || s == 401 || s == 403 || s == 422), cruzado::toString);

        // ---------------------------------------------------------------- auditoria
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT count(*) FILTER (WHERE acao = 'USUARIO_CRIADO'),
                            count(*) FILTER (WHERE acao = 'ACESSO_REVOGADO'),
                            count(*) FILTER (WHERE acao = 'PAPEIS_ALTERADOS'),
                            count(*) FILTER (WHERE acao = 'SENHA_PROVISORIA_DEFINIDA'),
                            count(*) FILTER (WHERE acao = 'CONTA_DESATIVADA'),
                            count(*) FILTER (WHERE dados::text LIKE '%argon2%'),
                            count(*) FILTER (WHERE dados::text LIKE ? OR dados::text LIKE ?),
                            (SELECT count(*) FROM auditoria.verificar_cadeia())
                       FROM auditoria.registro""")) {
            ps.setString(1, "%" + senhasProvisorias.get(0) + "%");
            ps.setString(2, "%" + senhasProvisorias.get(1) + "%");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getLong(1) >= 1, "criação auditada");
                assertTrue(rs.getLong(2) >= 1, "revogação auditada");
                assertTrue(rs.getLong(3) >= 1, "alteração de papéis auditada");
                assertTrue(rs.getLong(4) >= 1, "senha provisória auditada (sem a senha)");
                assertTrue(rs.getLong(5) >= 1, "desativação auditada");
                assertEquals(0, rs.getLong(6), "nenhum hash na auditoria");
                assertEquals(0, rs.getLong(7), "nenhuma senha provisória na auditoria");
                assertEquals(0, rs.getLong(8), "cadeia de auditoria íntegra");
            }
        }
    }

    // ----------------------------------------------------------------------------

    private int porta() {
        return Integer.parseInt(env.getRequiredProperty("local.server.port"));
    }

    private ClienteHttp cliente(String login, String senha) throws Exception {
        ClienteHttp c = new ClienteHttp(porta());
        c.entrar(login, senha);
        return c;
    }

    private static HttpResponse<String> enviar(ClienteHttp c, String metodo, String caminho, String corpo) {
        try {
            return c.enviar(metodo, caminho, corpo);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int versao(ClienteHttp adm, UUID id) throws Exception {
        return json.readTree(exigir(200, adm.enviar("GET", "/api/admin/usuarios/" + id, null)).body()).get("versao").asInt();
    }

    /** Valor textual de um nó JSON (independe de mudanças de nome da API entre versões do Jackson). */
    private static String texto(JsonNode n) {
        assertNotNull(n);
        String s = n.toString();
        return s.length() >= 2 && s.startsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private static boolean booleano(JsonNode n) {
        return "true".equals(texto(n));
    }

    private static UUID unidade(Connection c, String codigo) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fluxo.unidade (id, codigo, nome, tipo) VALUES (?, ?, 'Unidade de teste', 'UPA')")) {
            ps.setObject(1, id);
            ps.setString(2, codigo);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT fluxo.provisionar_unidade(?)")) {
            ps.setObject(1, id);
            ps.execute();
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

    private static UUID id(String login) throws Exception {
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM fluxo.usuario WHERE login = ?")) {
            ps.setString(1, login);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), login);
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static Set<String> papeisNoBanco(UUID usuario, UUID unidade) throws Exception {
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT papel::text FROM fluxo.lotacao WHERE usuario_id = ? AND unidade_id = ?")) {
            ps.setObject(1, usuario);
            ps.setObject(2, unidade);
            Set<String> s = new java.util.HashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    s.add(rs.getString(1));
                }
            }
            return s;
        }
    }

    private static boolean ativo(UUID usuario) throws Exception {
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("SELECT ativo FROM fluxo.usuario WHERE id = ?")) {
            ps.setObject(1, usuario);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getBoolean(1);
            }
        }
    }

    private static long sessoesNoBanco(UUID usuario) throws Exception {
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM sessao.SPRING_SESSION WHERE PRINCIPAL_NAME = ?")) {
            ps.setString(1, usuario.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long administradoresAtivos(UUID unidade) throws Exception {
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT count(*) FROM fluxo.lotacao l JOIN fluxo.usuario u ON u.id = l.usuario_id AND u.ativo
                      WHERE l.unidade_id = ? AND l.papel = 'ADMINISTRADOR'""")) {
            ps.setObject(1, unidade);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
