package br.fluxosaude.identidade.web;

import br.fluxosaude.configuracao.PropriedadesSeguranca;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.aplicacao.ResultadoAutenticacao;
import br.fluxosaude.identidade.aplicacao.ServicoAutenticacao;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.infra.persistencia.ContextoRequisicao;
import br.fluxosaude.infra.persistencia.ExecutorTransacional;
import br.fluxosaude.infra.web.ProvedorContexto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sessão do usuário (RF-001, ADR-0002).
 * <pre>
 * GET    /api/sessao/csrf     obtém o cookie XSRF-TOKEN (público)
 * POST   /api/sessao          login (público, exige CSRF)
 * GET    /api/sessao          dados da sessão atual
 * PUT    /api/sessao/unidade  troca a unidade ativa
 * PUT    /api/sessao/senha    troca a senha (obrigatória no primeiro acesso)
 * DELETE /api/sessao          logout
 * </pre>
 * Após login e troca de senha o token CSRF é rotacionado: o cookie XSRF-TOKEN antigo é
 * expirado e a requisição seguinte (qualquer uma, no modo SPA) recebe um token novo; o
 * cliente deve sempre enviar o valor ATUAL do cookie no cabeçalho X-XSRF-TOKEN.
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
@RestController
@RequestMapping("/api/sessao")
class SessaoController {

    static final String ATRIBUTO_LOGIN_EM = "fluxo.sessao.login_em";
    private static final String MENSAGEM_FALHA = "Login ou senha inválidos";

    private final ServicoAutenticacao servico;
    private final SecurityContextRepository repositorioContexto;
    private final FindByIndexNameSessionRepository<? extends Session> sessoes;
    private final ProvedorContexto provedor;
    private final ExecutorTransacional executor;
    private final PropriedadesSeguranca propriedades;
    private final Clock relogio;
    private final SecurityContextHolderStrategy estrategia = SecurityContextHolder.getContextHolderStrategy();

    SessaoController(ServicoAutenticacao servico, SecurityContextRepository repositorioContexto,
                     FindByIndexNameSessionRepository<? extends Session> sessoes, ProvedorContexto provedor,
                     ExecutorTransacional executor, PropriedadesSeguranca propriedades, Clock relogio) {
        this.servico = servico;
        this.repositorioContexto = repositorioContexto;
        this.sessoes = sessoes;
        this.provedor = provedor;
        this.executor = executor;
        this.propriedades = propriedades;
        this.relogio = relogio;
    }

    @GetMapping("/csrf")
    SessaoDtos.CsrfResponse csrf(CsrfToken token) {
        token.getToken(); // força a geração/gravação do cookie XSRF-TOKEN
        return new SessaoDtos.CsrfResponse(token.getHeaderName());
    }

    @PostMapping
    ResponseEntity<?> login(@Valid @RequestBody SessaoDtos.LoginRequest corpo,
                            HttpServletRequest request, HttpServletResponse response) {
        ContextoOrigem origem = provedor.origem(request);
        ResultadoAutenticacao resultado = servico.autenticar(corpo.login(), corpo.senha(), origem);

        if (resultado instanceof ResultadoAutenticacao.LimiteExcedido) {
            return erro(HttpStatus.TOO_MANY_REQUESTS, "MUITAS_TENTATIVAS",
                    "Muitas tentativas. Aguarde alguns minutos.", request,
                    String.valueOf(propriedades.janelaTentativas().toSeconds()));
        }
        if (!(resultado instanceof ResultadoAutenticacao.Sucesso sucesso)) {
            return erro(HttpStatus.UNAUTHORIZED, "CREDENCIAIS_INVALIDAS", MENSAGEM_FALHA, request, null);
        }

        UsuarioAutenticado usuario = sucesso.usuario();
        // Fixação de sessão: descarta qualquer sessão anterior (inclusive atributos de outro
        // usuário num terminal compartilhado) e cria uma nova, com ID novo.
        HttpSession anterior = request.getSession(false);
        if (anterior != null) {
            anterior.invalidate();
        }
        HttpSession nova = request.getSession(true);
        nova.setAttribute(ATRIBUTO_LOGIN_EM, relogio.millis());
        salvar(usuario, request, response);
        rotacionarCsrf(request, response);
        // A sessão nova só é gravada no fim da requisição; as listadas aqui são todas anteriores.
        encerrarSessoesExcedentes(usuario, nova.getId(), propriedades.maxSessoesPorUsuario() - 1);
        return ResponseEntity.ok(SessaoDtos.SessaoResponse.de(usuario));
    }

    /** Também revalida a sessão no banco (usuário ativo, lotação e papéis inalterados). */
    @GetMapping
    SessaoDtos.SessaoResponse atual(HttpServletRequest request) {
        UsuarioAutenticado usuario = usuarioAtual();
        executor.executar(provedor.contexto(usuario, request), jdbc -> Boolean.TRUE);
        return SessaoDtos.SessaoResponse.de(usuario);
    }

    @PutMapping("/unidade")
    SessaoDtos.SessaoResponse trocarUnidade(@Valid @RequestBody SessaoDtos.TrocaUnidadeRequest corpo,
                                           HttpServletRequest request, HttpServletResponse response) {
        UsuarioAutenticado atual = usuarioAtual();
        if (!atual.lotacoes().containsKey(corpo.unidadeId())) {
            throw new AcessoNegadoException(null);
        }
        UsuarioAutenticado novo = atual.comUnidadeAtiva(corpo.unidadeId());
        executor.executarSemRetorno(provedor.contexto(novo, request), jdbc -> jdbc
                .sql("SELECT auditoria.registrar('UNIDADE_ATIVA_ALTERADA', 'sessao', NULL, '{}'::jsonb, ?::uuid)")
                .param(corpo.unidadeId().toString())
                .query(Long.class).single());
        salvar(novo, request, response);
        return SessaoDtos.SessaoResponse.de(novo);
    }

    @PutMapping("/senha")
    ResponseEntity<Void> trocarSenha(@Valid @RequestBody SessaoDtos.TrocaSenhaRequest corpo,
                                     HttpServletRequest request, HttpServletResponse response) {
        UsuarioAutenticado atual = usuarioAtual();
        UsuarioAutenticado novo = servico.trocarSenha(atual, corpo.senhaAtual(), corpo.novaSenha(),
                provedor.origem(request));
        // Encerra todas as OUTRAS sessões do usuário ANTES de mudar o ID: o repositório
        // conhece a sessão atual pelo ID antigo (o novo só é gravado no fim da requisição).
        String idAtualNoRepositorio = request.getSession().getId();
        encerrarSessoesExcedentes(novo, idAtualNoRepositorio, 0);
        request.changeSessionId();
        salvar(novo, request, response);
        rotacionarCsrf(request, response);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    ResponseEntity<Void> logout(HttpServletRequest request) {
        provedor.usuario().ifPresent(u -> executor.executarSemRetorno(provedor.contexto(u, request), jdbc -> jdbc
                .sql("SELECT auditoria.registrar('LOGOUT', 'sessao')")
                .query(Long.class).single()));
        HttpSession sessao = request.getSession(false);
        if (sessao != null) {
            sessao.invalidate();
        }
        estrategia.clearContext();
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------------------------

    private UsuarioAutenticado usuarioAtual() {
        return provedor.usuario().orElseThrow(() -> new AcessoNegadoException(null));
    }

    private void salvar(UsuarioAutenticado usuario, HttpServletRequest request, HttpServletResponse response) {
        var autoridades = usuario.permissoes().stream()
                .map(p -> new SimpleGrantedAuthority("PERM_" + p.name()))
                .collect(Collectors.toList());
        SecurityContext contexto = estrategia.createEmptyContext();
        contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(usuario, null, autoridades));
        estrategia.setContext(contexto);
        repositorioContexto.saveContext(contexto, request, response);
    }

    /**
     * Rotaciona o token CSRF após mudança de autenticação: o cookie atual é expirado e a
     * próxima requisição recebe um token novo (mesmos nomes/padrões do modo SPA).
     */
    private static void rotacionarCsrf(HttpServletRequest request, HttpServletResponse response) {
        CookieCsrfTokenRepository.withHttpOnlyFalse().saveToken(null, request, response);
    }

    /** Mantém no máximo {@code manter} OUTRAS sessões do usuário (as de uso mais recente). */
    private void encerrarSessoesExcedentes(UsuarioAutenticado usuario, String sessaoAtual, int manter) {
        Map<String, ? extends Session> existentes = sessoes.findByPrincipalName(usuario.getName());
        existentes.values().stream()
                .filter(s -> !s.getId().equals(sessaoAtual))
                .sorted(Comparator.comparing(Session::getLastAccessedTime).reversed())
                .skip(Math.max(0, manter))
                .map(Session::getId)
                .toList()
                .forEach(sessoes::deleteById);
    }

    private static ResponseEntity<ProblemDetail> erro(HttpStatus status, String codigo, String mensagem,
                                                      HttpServletRequest request, String retryAfter) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, mensagem);
        p.setProperty("codigo", codigo);
        Object correlacao = request.getAttribute(br.fluxosaude.infra.web.FiltroCorrelacao.ATRIBUTO);
        if (correlacao != null) {
            p.setProperty("correlacao", correlacao.toString());
        }
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status);
        if (retryAfter != null) {
            b.header("Retry-After", retryAfter);
        }
        return b.body(p);
    }
}
