package br.fluxosaude.identidade.dominio;

/**
 * O banco recusou o contexto da sessão: usuário desativado, lotação removida ou papéis
 * alterados desde o login. A sessão deve ser encerrada (HTTP 401) e o usuário reautenticado.
 */
public class SessaoRevogadaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SessaoRevogadaException() {
        super("Sessão revogada");
    }
}
