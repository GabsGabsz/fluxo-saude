package br.fluxosaude.infra.web;

import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.infra.persistencia.ContextoRequisicao;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Obtém usuário e origem da requisição atual. O IP é o do container
 * ({@code getRemoteAddr}); atrás de proxy, habilite explicitamente
 * {@code server.forward-headers-strategy} — cabeçalhos X-Forwarded-* não são
 * confiados por padrão (seriam forjáveis pelo cliente).
 */
@Component
public class ProvedorContexto {

    public Optional<UsuarioAutenticado> usuario() {
        Authentication a = SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
        if (a != null && a.isAuthenticated() && a.getPrincipal() instanceof UsuarioAutenticado u) {
            return Optional.of(u);
        }
        return Optional.empty();
    }

    public ContextoOrigem origem(HttpServletRequest request) {
        Object correlacao = request.getAttribute(FiltroCorrelacao.ATRIBUTO);
        return new ContextoOrigem(request.getRemoteAddr(), correlacao == null ? null : correlacao.toString());
    }

    public ContextoRequisicao contexto(UsuarioAutenticado usuario, HttpServletRequest request) {
        return ContextoRequisicao.de(usuario, origem(request));
    }
}
