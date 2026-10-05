package br.fluxosaude.infra.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ID de correlação por requisição (RNF-012): aceito do cliente só se bem-formado,
 * senão gerado. Vai para o MDC (logs), para a resposta e para a auditoria.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroCorrelacao extends OncePerRequestFilter {

    public static final String CABECALHO = "X-Correlation-Id";
    public static final String ATRIBUTO = FiltroCorrelacao.class.getName() + ".id";
    private static final Pattern VALIDO = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String recebido = request.getHeader(CABECALHO);
        String id = recebido != null && VALIDO.matcher(recebido).matches() ? recebido : UUID.randomUUID().toString();
        request.setAttribute(ATRIBUTO, id);
        response.setHeader(CABECALHO, id);
        MDC.put("correlacao", id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("correlacao");
        }
    }
}
