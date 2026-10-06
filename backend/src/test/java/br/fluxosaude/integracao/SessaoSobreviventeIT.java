package br.fluxosaude.integracao;

import static br.fluxosaude.integracao.ClienteHttp.exigir;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.identidade.aplicacao.SessoesPort;
import br.fluxosaude.identidade.infra.HashDeSenhaArgon2;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sessão que SOBREVIVE à remoção física (a exclusão das sessões falha) continua recusada
 * após redefinição de senha, mudança de papéis e desativação/reativação: a recusa vem da
 * versão de credencial conferida pelo banco em toda transação (V12), não da exclusão.
 *
 * <p>Usa um contexto Spring próprio, em que o encerramento de sessões SEMPRE falha.
 */
class SessaoSobreviventeIT extends IntegracaoBase {

    static final String SENHA = "frase secreta da sessao sobrevivente";
    static final String ADM = "adm.sobrevive";

    /** Encerramento de sessões que sempre falha (simula banco de sessões indisponível). */
    @TestConfiguration
    static class EncerramentoFalho {
        static final AtomicInteger CHAMADAS = new AtomicInteger();

        @Bean
        @Primary
        SessoesPort sessoesQueFalham() {
            return usuarioId -> {
                CHAMADAS.incrementAndGet();
                throw new IllegalStateException("repositório de sessões indisponível (simulado)");
            };
        }
    }

    private static final AtomicBoolean PREPARADO = new AtomicBoolean();
    static UUID unidade;

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
                unidade = UUID.randomUUID();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO fluxo.unidade (id, codigo, nome, tipo) VALUES (?, 'UPA_SOBREVIVE', 'Unidade sessoes', 'UPA')")) {
                    ps.setObject(1, unidade);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT fluxo.provisionar_unidade(?)")) {
                    ps.setObject(1, unidade);
                    ps.execute();
                }
                UUID adm = UUID.randomUUID();
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO fluxo.usuario (id, login, nome, senha_hash, "
                        + "deve_trocar_senha, unidade_gestora_id) VALUES (?, ?, 'Administradora Sessoes', ?, false, ?)")) {
                    ps.setObject(1, adm);
                    ps.setString(2, ADM);
                    ps.setString(3, hash);
                    ps.setObject(4, unidade);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel) VALUES (?, ?, 'ADMINISTRADOR')")) {
                    ps.setObject(1, adm);
                    ps.setObject(2, unidade);
                    ps.executeUpdate();
                }
                c.commit();
            }
        }
    }

    @Test
    void sessaoAntigaRecusadaMesmoSemExclusaoFisica() throws Exception {
        ClienteHttp adm = cliente(ADM, SENHA);

        // Profissional criado, primeiro acesso e troca da senha provisória
        JsonNode criado = json.readTree(exigir(201, adm.enviar("POST", "/api/admin/usuarios",
                "{\"login\":\"sobrevive.enf\",\"nome\":\"Profissional Sessoes\",\"papeis\":[\"ENFERMAGEM\"]}")).body());
        UUID id = UUID.fromString(texto(criado.get("id")));
        ClienteHttp primeiro = cliente("sobrevive.enf", texto(criado.get("senhaProvisoria")));
        String senha = "orquidea branca na janela do norte";
        exigir(204, primeiro.enviar("PUT", "/api/sessao/senha",
                "{\"senhaAtual\":\"" + texto(criado.get("senhaProvisoria")) + "\",\"novaSenha\":\"" + senha + "\"}"));
        exigir(200, primeiro.enviar("GET", "/api/sessao/csrf", null));
        exigir(200, primeiro.enviar("GET", "/api/episodios", null)); // a sessão de quem trocou segue válida

        // 1) Redefinição de senha pelo administrador com falha ao apagar sessões
        ClienteHttp antiga = cliente("sobrevive.enf", senha);
        exigir(200, antiga.enviar("GET", "/api/episodios", null));
        int antes = EncerramentoFalho.CHAMADAS.get();
        JsonNode reset = json.readTree(exigir(200, adm.enviar("POST", "/api/admin/usuarios/" + id + "/senha-provisoria",
                "{\"versao\":" + versao(adm, id) + "}")).body());
        assertTrue(EncerramentoFalho.CHAMADAS.get() > antes, "tentou encerrar as sessões e falhou");
        assertTrue(sessoesNoBanco(id) > 0, "a sessão antiga continua gravada (exclusão física falhou)");
        revogada(antiga.enviar("GET", "/api/episodios", null));
        revogada(primeiro.enviar("GET", "/api/sessao", null));

        // 2) Papéis alterados e depois restaurados: a sessão de antes continua recusada
        String provisoria = texto(reset.get("senhaProvisoria"));
        ClienteHttp nova = cliente("sobrevive.enf", provisoria);
        String senha2 = "girassol amarelo no patio de tras";
        exigir(204, nova.enviar("PUT", "/api/sessao/senha",
                "{\"senhaAtual\":\"" + provisoria + "\",\"novaSenha\":\"" + senha2 + "\"}"));
        exigir(200, nova.enviar("GET", "/api/sessao/csrf", null));
        exigir(200, nova.enviar("GET", "/api/episodios", null));
        exigir(200, adm.enviar("PUT", "/api/admin/usuarios/" + id + "/papeis",
                "{\"versao\":" + versao(adm, id) + ",\"papeis\":[\"MEDICO\"]}"));
        exigir(200, adm.enviar("PUT", "/api/admin/usuarios/" + id + "/papeis",
                "{\"versao\":" + versao(adm, id) + ",\"papeis\":[\"ENFERMAGEM\"]}"));
        assertTrue(sessoesNoBanco(id) > 0);
        revogada(nova.enviar("GET", "/api/episodios", null));

        // 3) Desativar e reativar não "ressuscita" a sessão de antes
        ClienteHttp outra = cliente("sobrevive.enf", senha2);
        exigir(200, outra.enviar("GET", "/api/episodios", null));
        exigir(200, adm.enviar("PUT", "/api/admin/usuarios/" + id + "/situacao",
                "{\"versao\":" + versao(adm, id) + ",\"ativo\":false}"));
        exigir(200, adm.enviar("PUT", "/api/admin/usuarios/" + id + "/situacao",
                "{\"versao\":" + versao(adm, id) + ",\"ativo\":true}"));
        assertTrue(sessoesNoBanco(id) > 0);
        revogada(outra.enviar("GET", "/api/episodios", null));

        // Um novo login com a senha vigente funciona normalmente
        exigir(200, cliente("sobrevive.enf", senha2).enviar("GET", "/api/episodios", null));

        // 4) Mudança feita direto no banco (sem a aplicação e sem apagar sessões)
        ClienteHttp viaDba = cliente("sobrevive.enf", senha2);
        exigir(200, viaDba.enviar("GET", "/api/episodios", null));
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("UPDATE fluxo.usuario SET senha_hash = ?, versao = versao + 1 WHERE id = ?")) {
            ps.setString(1, new HashDeSenhaArgon2(1).gerar("senha definida pelo dba para recuperacao"));
            ps.setObject(2, id);
            ps.executeUpdate();
        }
        revogada(viaDba.enviar("GET", "/api/episodios", null));
    }

    // ----------------------------------------------------------------------------

    private static void revogada(HttpResponse<String> r) {
        exigir(401, r);
        assertTrue(r.body().contains("SESSAO_REVOGADA"), r.body());
    }

    private ClienteHttp cliente(String login, String senha) throws Exception {
        ClienteHttp c = new ClienteHttp(Integer.parseInt(env.getRequiredProperty("local.server.port")));
        c.entrar(login, senha);
        return c;
    }

    private int versao(ClienteHttp adm, UUID id) throws Exception {
        return json.readTree(exigir(200, adm.enviar("GET", "/api/admin/usuarios/" + id, null)).body()).get("versao").asInt();
    }

    private static String texto(JsonNode n) {
        String s = n.toString();
        return s.length() >= 2 && s.startsWith("\"") ? s.substring(1, s.length() - 1) : s;
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
}
