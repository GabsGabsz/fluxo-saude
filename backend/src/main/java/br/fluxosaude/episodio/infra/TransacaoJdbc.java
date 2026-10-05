package br.fluxosaude.episodio.infra;

import br.fluxosaude.episodio.aplicacao.Repositorios;
import br.fluxosaude.episodio.aplicacao.Transacao;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.infra.persistencia.ContextoRequisicao;
import br.fluxosaude.infra.persistencia.ExecutorTransacional;
import java.util.function.Function;

/**
 * Unidade de trabalho sobre o {@link ExecutorTransacional}: uma transação READ COMMITTED,
 * contexto validado pelo banco ({@code fluxo.aplicar_contexto}) e repositórios ligados a ela.
 */
public final class TransacaoJdbc implements Transacao {

    private final ExecutorTransacional executor;

    public TransacaoJdbc(ExecutorTransacional executor) {
        this.executor = executor;
    }

    @Override
    public <T> T executar(UsuarioAutenticado usuario, ContextoOrigem origem, Function<Repositorios, T> trabalho) {
        return executor.executar(ContextoRequisicao.de(usuario, origem), jdbc -> trabalho.apply(new RepositoriosJdbc(jdbc)));
    }
}
