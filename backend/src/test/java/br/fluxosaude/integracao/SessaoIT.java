package br.fluxosaude.integracao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.identidade.infra.HashDeSenhaArgon2;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Fluxo completo de sessão contra a aplicação real (HTTP + PostgreSQL):
 * CSRF, login, troca de senha obrigatória, permissões, logout e auditoria.
 */
class SessaoIT extends IntegracaoBase {

    static final String LOGIN = "admin.sessao";
    static final String SENHA_INICIAL = "senha inicial do teste 2026";
    static final String NOVA_SENHA = "nova frase secreta do plantao";

    @Autowired
    Environment env;

    @BeforeAll
    static void criarAdministrador() throws Exception {
        String hash = new HashDeSenhaArgon2(1).gerar(SENHA_INICIAL);
        try (Connection c = conexaoDono()) {
            c.setAutoCommit(false);
            UUID unidade = UUID.randomUUID();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fluxo.unidade (id, codigo, nome, tipo) VALUES (?, 'UPA_IT', 'UPA Teste', 'UPA')")) {
                ps.setObject(1, unidade);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT fluxo.provisionar_unidade(?)")) {
                ps.setObject(1, unidade);
                ps.execute();
            }
            UUID usuario = UUID.randomUUID();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fluxo.usuario (id, login, nome, senha_hash, deve_trocar_senha) VALUES (?, ?, 'Admin Teste', ?, true)")) {
                ps.setObject(1, usuario);
                ps.setString(2, LOGIN);
                ps.setString(3, hash);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel) VALUES (?, ?, 'ADMINISTRADOR')")) {
                ps.setObject(1, usuario);
                ps.setObject(2, unidade);
                ps.executeUpdate();
            }
            c.commit();
        }
    }

    /** Cliente HTTP mínimo com cookies explícitos (sem dependência de CookieManager). */
    final class Cliente {
        final HttpClient http = HttpClient.newHttpClient();
        final Map<String, String> cookies = new HashMap<>();

        HttpResponse<String> enviar(String metodo, String caminho, String json) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + env.getRequiredProperty("local.server.port") + caminho));
            if (!cookies.isEmpty()) {
                b.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining("; ")));
            }
            String csrf = cookies.get("XSRF-TOKEN");
            if (csrf != null) {
                b.header("X-XSRF-TOKEN", csrf);
            }
            b.header("Content-Type", "application/json");
            b.method(metodo, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            for (String sc : r.headers().allValues("Set-Cookie")) {
                String par = sc.split(";", 2)[0];
                String nome = par.substring(0, par.indexOf('='));
                String valor = par.substring(par.indexOf('=') + 1);
                boolean expirado = sc.toLowerCase().contains("max-age=0") || valor.isEmpty();
                if (expirado) {
                    cookies.remove(nome);
                } else {
                    cookies.put(nome, valor);
                }
            }
            return r;
        }
    }

    @Test
    void fluxoCompletoDeSessao() throws Exception {
        Cliente cliente = new Cliente();

        // Sem sessão: 401
        assertEquals(401, cliente.enviar("GET", "/api/sessao", null).statusCode());

        // CSRF obrigatório inclusive no login
        HttpResponse<String> csrf = cliente.enviar("GET", "/api/sessao/csrf", null);
        assertEquals(200, csrf.statusCode());
        assertNotNull(cliente.cookies.get("XSRF-TOKEN"), "cookie XSRF-TOKEN emitido");
        Cliente semCsrf = new Cliente();
        assertEquals(403, semCsrf.enviar("POST", "/api/sessao", login(SENHA_INICIAL)).statusCode());

        // Senha errada: 401 genérico (não revela se o login existe)
        HttpResponse<String> errada = cliente.enviar("POST", "/api/sessao", login("senha errada qualquer"));
        assertEquals(401, errada.statusCode());
        assertTrue(errada.body().contains("CREDENCIAIS_INVALIDAS"));
        HttpResponse<String> inexistente = cliente.enviar("POST", "/api/sessao",
                "{\"login\":\"nao.existe\",\"senha\":\"qualquer senha longa\"}");
        assertEquals(401, inexistente.statusCode());
        assertEquals(semCorrelacao(errada.body()), semCorrelacao(inexistente.body()), "respostas idênticas");

        // Login correto: troca de senha obrigatória
        HttpResponse<String> ok = cliente.enviar("POST", "/api/sessao", login(SENHA_INICIAL));
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("\"deveTrocarSenha\":true"));
        assertTrue(cliente.cookies.containsKey("FLUXO"), "cookie de sessão emitido");
        assertTrue(ok.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"));
        assertEquals("nosniff", ok.headers().firstValue("X-Content-Type-Options").orElse(""));

        // CSRF rotacionado no login: o token antigo foi expirado; a escrita seguinte sem token
        // novo é recusada (e a própria resposta já entrega um token novo — modo SPA).
        assertFalse(cliente.cookies.containsKey("XSRF-TOKEN"), "token CSRF anterior expirado no login");
        assertEquals(403, cliente.enviar("PUT", "/api/sessao/senha",
                "{\"senhaAtual\":\"" + SENHA_INICIAL + "\",\"novaSenha\":\"" + NOVA_SENHA + "\"}").statusCode());
        assertNotNull(cliente.cookies.get("XSRF-TOKEN"), "token novo entregue");

        assertEquals(200, cliente.enviar("GET", "/api/sessao", null).statusCode());

        // Até trocar a senha, nada além da sessão é permitido
        assertEquals(403, cliente.enviar("PUT", "/api/sessao/unidade",
                "{\"unidadeId\":\"" + UUID.randomUUID() + "\"}").statusCode());

        // Política de senha aplicada
        HttpResponse<String> fraca = cliente.enviar("PUT", "/api/sessao/senha",
                "{\"senhaAtual\":\"" + SENHA_INICIAL + "\",\"novaSenha\":\"curta\"}");
        assertEquals(422, fraca.statusCode());
        assertTrue(fraca.body().contains("SENHA_CURTA"));

        HttpResponse<String> troca = cliente.enviar("PUT", "/api/sessao/senha",
                "{\"senhaAtual\":\"" + SENHA_INICIAL + "\",\"novaSenha\":\"" + NOVA_SENHA + "\"}");
        assertEquals(204, troca.statusCode(), troca.body());
        assertEquals(200, cliente.enviar("GET", "/api/sessao/csrf", null).statusCode()); // token novo

        HttpResponse<String> sessao = cliente.enviar("GET", "/api/sessao", null);
        assertEquals(200, sessao.statusCode());
        assertTrue(sessao.body().contains("\"deveTrocarSenha\":false"));
        assertTrue(sessao.body().contains("USUARIO_GERENCIAR"));
        assertFalse(sessao.body().contains("EPISODIO_VER"), "administrador não vê casos nominais");

        // Logout encerra a sessão
        assertEquals(204, cliente.enviar("DELETE", "/api/sessao", null).statusCode());
        assertEquals(401, cliente.enviar("GET", "/api/sessao", null).statusCode());

        // A senha antiga não vale mais; a nova vale
        Cliente outro = new Cliente();
        outro.enviar("GET", "/api/sessao/csrf", null);
        assertEquals(401, outro.enviar("POST", "/api/sessao", login(SENHA_INICIAL)).statusCode());
        assertEquals(200, outro.enviar("POST", "/api/sessao", login(NOVA_SENHA)).statusCode());

        // Revogação imediata: usuário desativado perde a sessão na requisição seguinte
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE fluxo.usuario SET ativo = false, versao = versao + 1 WHERE login = ?")) {
            ps.setString(1, LOGIN);
            assertEquals(1, ps.executeUpdate());
        }
        HttpResponse<String> revogada = outro.enviar("GET", "/api/sessao", null);
        assertEquals(401, revogada.statusCode());
        assertTrue(revogada.body().contains("SESSAO_REVOGADA"));
        assertEquals(401, outro.enviar("GET", "/api/sessao", null).statusCode(), "sessão encerrada");

        // Tudo auditado e cadeia íntegra
        try (Connection c = conexaoDono();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT count(*) FILTER (WHERE acao = 'LOGIN_SUCESSO'),
                            count(*) FILTER (WHERE acao = 'LOGIN_FALHA'),
                            count(*) FILTER (WHERE acao = 'LOGOUT'),
                            (SELECT count(*) FROM auditoria.verificar_cadeia())
                       FROM auditoria.registro""");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertTrue(rs.getLong(1) >= 2, "logins com sucesso auditados");
            assertTrue(rs.getLong(2) >= 3, "falhas auditadas (inclusive login inexistente)");
            assertTrue(rs.getLong(3) >= 1, "logout auditado");
            assertEquals(0, rs.getLong(4), "cadeia de auditoria íntegra");
        }
    }

    private static String login(String senha) {
        return "{\"login\":\"" + LOGIN + "\",\"senha\":\"" + senha + "\"}";
    }

    private static String semCorrelacao(String corpo) {
        return corpo.replaceAll("\"correlacao\":\"[^\"]*\"", "").replaceAll("\"instance\":\"[^\"]*\"", "");
    }
}
