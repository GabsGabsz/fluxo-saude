package br.fluxosaude.alerta.aplicacao;

import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.util.function.Function;

/** Unidade de trabalho dos alertas (uma transação com o contexto do usuário). */
@FunctionalInterface
public interface TransacaoAlertas {

    <T> T executar(UsuarioAutenticado usuario, ContextoOrigem origem, Function<RepositorioAlertas, T> trabalho);
}
