package br.fluxosaude.identidade.infra;

import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.aplicacao.RepositorioUsuarios;
import br.fluxosaude.identidade.aplicacao.TransacaoUsuarios;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.infra.persistencia.ContextoRequisicao;
import br.fluxosaude.infra.persistencia.ExecutorTransacional;
import java.util.function.Function;

/** Transação com o contexto do administrador validado pelo banco ({@code fluxo.aplicar_contexto}). */
public final class TransacaoUsuariosJdbc implements TransacaoUsuarios {

    private final ExecutorTransacional executor;

    public TransacaoUsuariosJdbc(ExecutorTransacional executor) {
        this.executor = executor;
    }

    @Override
    public <T> T executar(UsuarioAutenticado administrador, ContextoOrigem origem,
                          Function<RepositorioUsuarios, T> trabalho) {
        return executor.executar(ContextoRequisicao.de(administrador, origem),
                jdbc -> trabalho.apply(new RepositorioUsuariosJdbc(jdbc)));
    }
}
