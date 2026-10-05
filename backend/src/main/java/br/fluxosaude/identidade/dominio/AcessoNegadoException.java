package br.fluxosaude.identidade.dominio;

/** Usuário autenticado sem a permissão necessária. Mensagem genérica de propósito. */
public class AcessoNegadoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Permissao permissao;

    public AcessoNegadoException(Permissao permissao) {
        super("Acesso negado");
        this.permissao = permissao;
    }

    public Permissao permissao() {
        return permissao;
    }

    public static void exigir(UsuarioAutenticado usuario, Permissao permissao) {
        if (usuario == null || !usuario.pode(permissao)) {
            throw new AcessoNegadoException(permissao);
        }
    }
}
