package br.fluxosaude.identidade.web;

import br.fluxosaude.configuracao.PropriedadesSeguranca;
import br.fluxosaude.infra.web.RespostaDeErro;
import br.fluxosaude.infra.web.ProvedorContexto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Regras de sessão aplicadas a toda requisição de API:
 * <ol>
 *   <li>validade ABSOLUTA (padrão 12 h), independentemente da inatividade (30 min no Spring Session);</li>
 *   <li>troca de senha obrigatória: até trocar, só é possível consultar a sessão, trocar a senha ou sair.</li>
 * </ol>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
@Component
class InterceptadorSessao implements HandlerInterceptor {

    private final ProvedorContexto provedor;
    private final PropriedadesSeguranca propriedades;
    private final Clock relogio;

    InterceptadorSessao(ProvedorContexto provedor, PropriedadesSeguranca propriedades, Clock relogio) {
        this.provedor = provedor;
        this.propriedades = propriedades;
        this.relogio = relogio;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        HttpSession sessao = request.getSession(false);
        if (sessao != null && provedor.usuario().isPresent() && expirada(sessao)) {
            // Validade absoluta (falha fechada: sem horário de login registrado = expirada).
            sessao.invalidate();
            SecurityContextHolder.getContextHolderStrategy().clearContext();
            if (!loginOuCsrf(request)) {
                RespostaDeErro.escrever(request, response, 401, "SESSAO_EXPIRADA", "Sessão expirada. Entre novamente.");
                return false;
            }
            return true; // segue para o novo login / obtenção de CSRF sem a sessão antiga
        }
        var usuario = provedor.usuario();
        if (usuario.isPresent() && usuario.get().deveTrocarSenha() && !permitidoDuranteTrocaDeSenha(request)) {
            RespostaDeErro.escrever(request, response, 403, "TROCA_DE_SENHA_OBRIGATORIA",
                    "É necessário trocar a senha antes de continuar.");
            return false;
        }
        return true;
    }

    private static boolean permitidoDuranteTrocaDeSenha(HttpServletRequest r) {
        String caminho = r.getRequestURI().substring(r.getContextPath().length());
        return caminho.equals("/api/sessao") || caminho.equals("/api/sessao/senha") || caminho.equals("/api/sessao/csrf");
    }

    private boolean expirada(HttpSession sessao) {
        Object loginEm = sessao.getAttribute(SessaoController.ATRIBUTO_LOGIN_EM);
        return !(loginEm instanceof Long t) || relogio.millis() - t > propriedades.duracaoAbsolutaSessao().toMillis();
    }

    private static boolean loginOuCsrf(HttpServletRequest r) {
        String caminho = r.getRequestURI().substring(r.getContextPath().length());
        return (caminho.equals("/api/sessao") && "POST".equals(r.getMethod())) || caminho.equals("/api/sessao/csrf");
    }
}
