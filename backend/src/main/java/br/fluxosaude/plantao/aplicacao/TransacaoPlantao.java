package br.fluxosaude.plantao.aplicacao;

import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.util.function.Function;

/** Unidade de trabalho da passagem de plantão (uma transação com o contexto do usuário). */
@FunctionalInterface
public interface TransacaoPlantao {

    <T> T executar(UsuarioAutenticado usuario, ContextoOrigem origem, Function<RepositorioPlantao, T> trabalho);
}
