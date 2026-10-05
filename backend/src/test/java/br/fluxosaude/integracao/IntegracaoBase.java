package br.fluxosaude.integracao;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base dos testes de integração: PostgreSQL 16 real com o MESMO bootstrap de produção
 * (infra/db/init), migrações via Flyway com o papel dono, aplicação com o papel restrito.
 * Um único container é compartilhado por todas as classes (contexto Spring em cache).
 * Requer Docker; executado pelo failsafe ({@code mvn verify}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class IntegracaoBase {

    static final String SENHA_DONO = "dono-somente-teste";
    static final String SENHA_APP = "app-somente-teste";

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

    static String urlJdbc() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/fluxo";
    }

    /** Conexão como DONO, só para preparar dados de teste (como faria a implantação). */
    static Connection conexaoDono() throws SQLException {
        return DriverManager.getConnection(urlJdbc(), "fluxo_owner", SENHA_DONO);
    }

    @DynamicPropertySource
    static void banco(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", IntegracaoBase::urlJdbc);
        r.add("spring.datasource.username", () -> "fluxo_app");
        r.add("spring.datasource.password", () -> SENHA_APP);
        r.add("spring.flyway.enabled", () -> "true");   // no teste, migra no mesmo processo
        r.add("spring.flyway.url", IntegracaoBase::urlJdbc);
        r.add("spring.flyway.user", () -> "fluxo_owner");
        r.add("spring.flyway.password", () -> SENHA_DONO);
        // HTTP sem TLS no teste: cookie de sessão sem Secure e sem prefixo __Host-.
        r.add("server.servlet.session.cookie.secure", () -> "false");
        r.add("server.servlet.session.cookie.name", () -> "FLUXO");
    }
}
