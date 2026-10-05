package br.fluxosaude.episodio.aplicacao;

import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.util.function.Function;

/**
 * Unidade de trabalho: executa o caso de uso numa única transação, com o contexto do
 * usuário (unidade ativa) validado pelo banco (ADR-0002 §7). Tudo ou nada.
 */
public interface Transacao {

    <T> T executar(UsuarioAutenticado usuario, ContextoOrigem origem, Function<Repositorios, T> trabalho);
}
