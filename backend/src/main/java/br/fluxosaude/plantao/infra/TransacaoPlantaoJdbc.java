package br.fluxosaude.plantao.infra;

import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.infra.persistencia.ContextoRequisicao;
import br.fluxosaude.infra.persistencia.ExecutorTransacional;
import br.fluxosaude.plantao.aplicacao.RepositorioPlantao;
import br.fluxosaude.plantao.aplicacao.TransacaoPlantao;
import java.util.function.Function;

/** Transação com o contexto validado pelo banco (unidade ativa, papéis, versão de credencial). */
public final class TransacaoPlantaoJdbc implements TransacaoPlantao {

    private final ExecutorTransacional executor;

    public TransacaoPlantaoJdbc(ExecutorTransacional executor) {
        this.executor = executor;
    }

    @Override
    public <T> T executar(UsuarioAutenticado usuario, ContextoOrigem origem, Function<RepositorioPlantao, T> trabalho) {
        return executor.executar(ContextoRequisicao.de(usuario, origem), jdbc -> trabalho.apply(new RepositorioPlantaoJdbc(jdbc)));
    }
}
