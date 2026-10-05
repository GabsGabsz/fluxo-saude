package br.fluxosaude.integracao;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Cliente HTTP mínimo dos testes de integração, com cookies explícitos (sem CookieManager)
 * e envio automático do token CSRF atual no cabeçalho X-XSRF-TOKEN — como faz o front-end.
 */
final class ClienteHttp {

    private final HttpClient http = HttpClient.newHttpClient();
    private final int porta;
    final Map<String, String> cookies = new HashMap<>();

    ClienteHttp(int porta) {
        this.porta = porta;
    }

    HttpResponse<String> enviar(String metodo, String caminho, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho));
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

    /** Login completo: CSRF → login → token CSRF novo (rotacionado no login). */
    void entrar(String login, String senha) throws Exception {
        exigir(200, enviar("GET", "/api/sessao/csrf", null));
        exigir(200, enviar("POST", "/api/sessao", "{\"login\":\"" + login + "\",\"senha\":\"" + senha + "\"}"));
        exigir(200, enviar("GET", "/api/sessao/csrf", null));
    }

    static HttpResponse<String> exigir(int status, HttpResponse<String> r) {
        if (r.statusCode() != status) {
            throw new AssertionError("esperado HTTP " + status + ", obtido " + r.statusCode() + ": " + r.body());
        }
        return r;
    }
}
