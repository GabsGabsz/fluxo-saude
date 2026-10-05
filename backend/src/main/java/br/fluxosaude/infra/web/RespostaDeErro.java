package br.fluxosaude.infra.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Escrita direta de problem+json em filtros/interceptadores (fora do Spring MVC).
 * Usa {@code setStatus} (não {@code sendError}), evitando o despacho interno para /error.
 */
public final class RespostaDeErro {

    private static final Pattern SEGURO = Pattern.compile("^[A-Za-z0-9 .,:;()À-ÿ_-]*$");

    private RespostaDeErro() {
    }

    public static void escrever(HttpServletRequest request, HttpServletResponse response, int status,
                                String codigo, String mensagem) throws IOException {
        Object correlacao = request.getAttribute(FiltroCorrelacao.ATRIBUTO);
        String c = correlacao == null ? "" : correlacao.toString();
        // Defesa: só textos fixos do próprio código chegam aqui; ainda assim, nada fora do padrão é escrito.
        if (!SEGURO.matcher(codigo).matches() || !SEGURO.matcher(mensagem).matches() || !SEGURO.matcher(c).matches()) {
            codigo = "ERRO";
            mensagem = "Erro";
            c = "";
        }
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"codigo\":\"" + codigo + "\",\"detail\":\""
                + mensagem + "\",\"correlacao\":\"" + c + "\"}");
    }
}
