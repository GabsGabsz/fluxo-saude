package br.fluxosaude.integracao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Garantias de segurança do banco vistas a partir do papel da aplicação. */
class BancoDeDadosIT extends IntegracaoBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Environment env;

    @Autowired
    Flyway flyway;

    @Test
    void migracoesAplicadas() {
        assertTrue(flyway.info().applied().length >= 7, "migrações V1..V7 aplicadas");
        assertEquals(0, flyway.info().pending().length, "nenhuma migração pendente");
    }

    @Test
    void historicoDoFlywayNaoEhAcessivelPelaAplicacao() {
        assertThrows(DataAccessException.class,
                () -> jdbc.queryForObject("SELECT count(*) FROM fluxo.flyway_schema_history", Integer.class));
    }

    @Test
    void aplicacaoConectaComPapelSemPrivilegios() {
        Map<String, Object> papel = jdbc.queryForMap(
                "SELECT current_user AS nome, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");
        assertEquals("fluxo_app", papel.get("nome"));
        assertFalse((Boolean) papel.get("rolsuper"));
        assertFalse((Boolean) papel.get("rolbypassrls"));
    }

    @Test
    void rlsHabilitadoEmTodasAsTabelasComUnidade() {
        List<String> semRls = jdbc.queryForList("""
                SELECT c.relname FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'unidade_id' AND NOT a.attisdropped
                 WHERE n.nspname = 'fluxo' AND c.relkind = 'r' AND NOT c.relrowsecurity
                """, String.class);
        assertTrue(semRls.isEmpty(), "tabelas com unidade_id sem RLS: " + semRls);
    }

    @Test
    void semContextoNenhumDadoEhVisivel() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM fluxo.etapa", Integer.class);
        assertEquals(0, n.intValue());
    }

    @Test
    void endpointsDeNegocioNegadosEHealthPublico() throws Exception {
        String base = "http://localhost:" + env.getRequiredProperty("local.server.port");
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> health = http.send(HttpRequest.newBuilder(URI.create(base + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode());
        HttpResponse<String> negocio = http.send(HttpRequest.newBuilder(URI.create(base + "/api/episodios")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(negocio.statusCode() == 401 || negocio.statusCode() == 403,
                "esperado 401/403, obtido " + negocio.statusCode());
    }
}
