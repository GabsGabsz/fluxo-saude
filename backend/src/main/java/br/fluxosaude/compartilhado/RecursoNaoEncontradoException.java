package br.fluxosaude.compartilhado;

/**
 * Recurso inexistente OU fora do alcance do usuário (outra unidade): a resposta é a mesma
 * nos dois casos (HTTP 404), para não revelar a existência de registros de outras unidades.
 */
public class RecursoNaoEncontradoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RecursoNaoEncontradoException(String recurso) {
        super("Registro não encontrado: " + recurso);
    }
}
