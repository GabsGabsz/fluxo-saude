package br.fluxosaude.integracao;

import static br.fluxosaude.integracao.ClienteHttp.exigir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Exposição da interface web (ADR-0008): só arquivos estáticos, só GET, na mesma origem da API,
 * com a CSP restritiva; /api/** continua exigindo sessão e nada além do estático fica público.
 */
class InterfaceEstaticaIT extends IntegracaoBase {

    @Autowired
    Environment env;

    private ClienteHttp anonimo() {
        return new ClienteHttp(Integer.parseInt(env.getRequiredProperty("local.server.port")));
    }

    @Test
    void estaticoPublicoSoLeituraEApiProtegida() throws Exception {
        ClienteHttp c = anonimo();

        HttpResponse<String> raiz = exigir(200, c.enviar("GET", "/", null));
        assertTrue(raiz.body().contains("<script type=\"module\" src=\"/app/js/main.js\"></script>"));
        String csp = raiz.headers().firstValue("Content-Security-Policy").orElse("");
        assertTrue(csp.contains("default-src 'self'") && csp.contains("frame-ancestors 'none'"), csp);
        assertFalse(raiz.body().contains("<script>"), "sem script inline");

        HttpResponse<String> js = exigir(200, c.enviar("GET", "/app/js/main.js", null));
        assertTrue(js.headers().firstValue("Content-Type").orElse("").contains("javascript"));
        exigir(200, c.enviar("GET", "/app/css/app.css", null));
        exigir(200, c.enviar("GET", "/favicon.svg", null));
        exigir(200, c.enviar("GET", "/index.html", null));

        // Escrita em caminho estático: recusada (sem sessão => 401; nunca 200).
        int post = c.enviar("POST", "/", "{}").statusCode();
        assertTrue(post == 401 || post == 403, "POST / => " + post);
        int put = c.enviar("PUT", "/app/js/main.js", "{}").statusCode();
        assertTrue(put == 401 || put == 403, "PUT estático => " + put);

        // A API continua exigindo sessão.
        assertEquals(401, c.enviar("GET", "/api/episodios", null).statusCode());
        assertEquals(401, c.enviar("GET", "/api/catalogo", null).statusCode());

        // Nada fora de /, /index.html, /favicon.svg e /app/** é público.
        assertNotEquals(200, c.enviar("GET", "/application.yml", null).statusCode());
        assertNotEquals(200, c.enviar("GET", "/db/migration/V1__nucleo.sql", null).statusCode());
        assertNotEquals(200, c.enviar("GET", "/app/../application.yml", null).statusCode());
        assertNotEquals(200, c.enviar("GET", "/actuator/env", null).statusCode());
        int inexistente = c.enviar("GET", "/app/nao-existe.js", null).statusCode();
        assertTrue(inexistente == 404 || inexistente == 401, "estático inexistente => " + inexistente);
    }
}
