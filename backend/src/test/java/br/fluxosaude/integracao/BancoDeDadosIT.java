package br.fluxosaude.integracao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Sobe PostgreSQL 16 real com o MESMO bootstrap de produção (infra/db/init),
 * aplica as migrações via Flyway (papel dono) e verifica as garantias de
 * segurança a partir do papel da aplicação.
 *
 * <p>Requer Docker. Executado pelo failsafe ({@code mvn verify}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BancoDeDadosIT {

    private static final String SENHA_DONO = "dono-somente-teste";
    private static final String SENHA_APP = "app-somente-teste";

    @SuppressWarnings("resource") // ciclo de vida gerenciado pelo Ryuk do Testcontainers
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withEnv("POSTGRES_PASSWORD", "superusuario-somente-teste")
            .withEnv("FLUXO_OWNER_PASSWORD", SENHA_DONO)
            .withEnv("FLUXO_APP_PASSWORD", SENHA_APP)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "infra", "db", "init", "01-bootstrap.sh"), 0755),
                    "/docker-entrypoint-initdb.d/01-bootstrap.sh")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void banco(DynamicPropertyRegistry r) {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/fluxo";
        r.add("spring.datasource.url", () -> url);
        r.add("spring.datasource.username", () -> "fluxo_app");
        r.add("spring.datasource.password", () -> SENHA_APP);
        r.add("spring.flyway.enabled", () -> "true");   // no teste, migra no mesmo processo
        r.add("spring.flyway.url", () -> url);
        r.add("spring.flyway.user", () -> "fluxo_owner");
        r.add("spring.flyway.password", () -> SENHA_DONO);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Environment env;

    @Autowired
    Flyway flyway;

    @Test
    void migracoesAplicadas() {
        assertTrue(flyway.info().applied().length >= 6, "migrações V1..V6 aplicadas");
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
        assertEquals(0, n);
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
