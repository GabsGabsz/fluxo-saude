package br.fluxosaude.indicador.aplicacao;

import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.util.function.Function;

/** Unidade de trabalho dos indicadores (leitura, com o contexto do usuário). */
@FunctionalInterface
public interface TransacaoIndicadores {

    <T> T executar(UsuarioAutenticado usuario, ContextoOrigem origem, Function<RepositorioIndicadores, T> trabalho);
}
