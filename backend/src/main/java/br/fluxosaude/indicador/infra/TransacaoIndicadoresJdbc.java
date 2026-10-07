package br.fluxosaude.indicador.infra;

import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.indicador.aplicacao.RepositorioIndicadores;
import br.fluxosaude.indicador.aplicacao.TransacaoIndicadores;
import br.fluxosaude.infra.persistencia.ContextoRequisicao;
import br.fluxosaude.infra.persistencia.ExecutorTransacional;
import java.util.function.Function;

/** Transação com o contexto validado pelo banco (unidade ativa, papéis, versão de credencial). */
public final class TransacaoIndicadoresJdbc implements TransacaoIndicadores {

    private final ExecutorTransacional executor;

    public TransacaoIndicadoresJdbc(ExecutorTransacional executor) {
        this.executor = executor;
    }

    @Override
    public <T> T executar(UsuarioAutenticado usuario, ContextoOrigem origem, Function<RepositorioIndicadores, T> trabalho) {
        return executor.executar(ContextoRequisicao.de(usuario, origem),
                jdbc -> trabalho.apply(new RepositorioIndicadoresJdbc(jdbc)));
    }
}
