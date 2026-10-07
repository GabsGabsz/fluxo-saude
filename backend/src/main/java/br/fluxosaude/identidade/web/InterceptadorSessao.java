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
 *   <li>troca de senha obrigatória: até trocar, só é possível consultar a sessão, trocar a senha ou sair;</li>
 *   <li>unidade ESPERADA pelo cliente (cabeçalho {@value #CABECALHO_UNIDADE}, opcional): a unidade ativa
 *       fica na sessão, compartilhada por todas as abas; se outra aba trocou a unidade, a requisição
 *       desta aba é recusada (409 {@code UNIDADE_ATIVA_ALTERADA}) em vez de ler ou gravar na unidade
 *       que o profissional não está vendo. As rotas de /api/sessao (consultar, trocar unidade,
 *       senha, sair) não são conferidas.</li>
 * </ol>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
@Component
class InterceptadorSessao implements HandlerInterceptor {

    /** Unidade que o cliente acredita estar ativa (a interface envia em toda requisição de API). */
    static final String CABECALHO_UNIDADE = "X-Fluxo-Unidade";

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
        String esperada = request.getHeader(CABECALHO_UNIDADE);
        if (usuario.isPresent() && esperada != null && !rotaDeSessao(request)
                && !esperada.equalsIgnoreCase(String.valueOf(usuario.get().unidadeAtiva()))) {
            RespostaDeErro.escrever(request, response, 409, "UNIDADE_ATIVA_ALTERADA",
                    "A unidade ativa mudou (por exemplo, em outra aba). Recarregue a tela.");
            return false;
        }
        return true;
    }

    private static boolean rotaDeSessao(HttpServletRequest r) {
        String caminho = r.getRequestURI().substring(r.getContextPath().length());
        return caminho.equals("/api/sessao") || caminho.startsWith("/api/sessao/");
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
