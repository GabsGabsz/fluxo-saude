package br.fluxosaude.infra.web;

import br.fluxosaude.compartilhado.LimiteExcedidoException;
import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.compartilhado.SobrecargaException;
import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.SessaoRevogadaException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Respostas de erro padronizadas (RFC 9457). Nunca expõe stack trace, SQL ou dados
 * internos; a correlação permite localizar o log completo (RNF-012). Logs de erro de banco
 * registram só o SQLSTATE — a mensagem do PostgreSQL pode conter valores (ex.: CNS) (LGPD).
 */
@RestControllerAdvice
public class TratadorDeErros {

    private static final Logger LOG = LoggerFactory.getLogger(TratadorDeErros.class);
    private static final HttpStatusCode UNPROCESSABLE = HttpStatusCode.valueOf(422);

    @ExceptionHandler(RegraVioladaException.class)
    ResponseEntity<ProblemDetail> regra(RegraVioladaException e, HttpServletRequest req) {
        return resposta(UNPROCESSABLE, e.codigo(), e.getMessage(), req);
    }

    @ExceptionHandler(AcessoNegadoException.class)
    ResponseEntity<ProblemDetail> acessoNegado(AcessoNegadoException e, HttpServletRequest req) {
        LOG.info("acesso negado: permissão {}", e.permissao());
        return resposta(HttpStatus.FORBIDDEN, "ACESSO_NEGADO", "Acesso negado", req);
    }

    /** Usuário desativado, lotação removida ou papéis alterados: encerra a sessão. */
    @ExceptionHandler(SessaoRevogadaException.class)
    ResponseEntity<ProblemDetail> sessaoRevogada(SessaoRevogadaException e, HttpServletRequest req) {
        HttpSession sessao = req.getSession(false);
        if (sessao != null) {
            sessao.invalidate();
        }
        SecurityContextHolder.getContextHolderStrategy().clearContext();
        return resposta(HttpStatus.UNAUTHORIZED, "SESSAO_REVOGADA",
                "Seu acesso foi alterado. Entre novamente.", req);
    }

    @ExceptionHandler(LimiteExcedidoException.class)
    ResponseEntity<ProblemDetail> limite(LimiteExcedidoException e, HttpServletRequest req) {
        ResponseEntity<ProblemDetail> r = resposta(HttpStatus.TOO_MANY_REQUESTS, "MUITAS_TENTATIVAS", e.getMessage(), req);
        return ResponseEntity.status(r.getStatusCode())
                .header("Retry-After", String.valueOf(e.aguardar().toSeconds()))
                .body(r.getBody());
    }

    @ExceptionHandler(SobrecargaException.class)
    ResponseEntity<ProblemDetail> sobrecarga(SobrecargaException e, HttpServletRequest req) {
        ResponseEntity<ProblemDetail> r = resposta(HttpStatus.SERVICE_UNAVAILABLE, "SOBRECARGA", e.getMessage(), req);
        return ResponseEntity.status(r.getStatusCode()).header("Retry-After", "5").body(r.getBody());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalida(Exception e, HttpServletRequest req) {
        return resposta(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA", "Requisição inválida", req);
    }

    /** RF-036: conflito de versão / escrita concorrente. */
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ProblemDetail> concorrencia(ConcurrencyFailureException e, HttpServletRequest req) {
        LOG.info("conflito de concorrência (SQLSTATE {})", sqlState(e));
        return resposta(HttpStatus.CONFLICT, "CONFLITO_DE_VERSAO",
                "O registro foi alterado por outra pessoa. Recarregue e tente novamente.", req);
    }

    /** Regra recusada pelo banco (última linha de defesa): mensagem genérica. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integridade(DataIntegrityViolationException e, HttpServletRequest req) {
        LOG.warn("operação recusada pelo banco (SQLSTATE {})", sqlState(e));
        return resposta(UNPROCESSABLE, "REGRA_DO_BANCO", "A operação viola uma regra do sistema.", req);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> inesperado(Exception e, HttpServletRequest req) {
        if (e instanceof ErrorResponse er) {
            // 404, 405, 415 etc. do próprio Spring MVC: mantém o status correto.
            return resposta(er.getStatusCode(), "REQUISICAO_NAO_ATENDIDA", "Requisição não atendida", req);
        }
        if (e instanceof DataAccessException dae) {
            LOG.error("erro de acesso a dados (SQLSTATE {}) — {}", sqlState(dae), dae.getClass().getSimpleName());
        } else {
            LOG.error("erro inesperado", e);
        }
        return resposta(HttpStatus.INTERNAL_SERVER_ERROR, "ERRO_INTERNO",
                "Erro inesperado. Informe o código de correlação à equipe de suporte.", req);
    }

    private static String sqlState(DataAccessException e) {
        Throwable t = e.getMostSpecificCause();
        return t instanceof SQLException sql ? sql.getSQLState() : "?";
    }

    static ResponseEntity<ProblemDetail> resposta(HttpStatusCode status, String codigo, String mensagem,
                                                  HttpServletRequest req) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, mensagem);
        p.setProperty("codigo", codigo);
        Object correlacao = req.getAttribute(FiltroCorrelacao.ATRIBUTO);
        if (correlacao != null) {
            p.setProperty("correlacao", correlacao.toString());
        }
        return ResponseEntity.status(status).body(p);
    }
}
