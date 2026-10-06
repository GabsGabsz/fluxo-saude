package br.fluxosaude.identidade.aplicacao;

import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.util.function.Function;

/** Unidade de trabalho da gestão de usuários (uma transação com o contexto do administrador). */
@FunctionalInterface
public interface TransacaoUsuarios {

    <T> T executar(UsuarioAutenticado administrador, ContextoOrigem origem, Function<RepositorioUsuarios, T> trabalho);
}
