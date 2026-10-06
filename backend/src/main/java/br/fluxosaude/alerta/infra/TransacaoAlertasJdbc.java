package br.fluxosaude.alerta.infra;

import br.fluxosaude.alerta.aplicacao.RepositorioAlertas;
import br.fluxosaude.alerta.aplicacao.TransacaoAlertas;
import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.infra.persistencia.ContextoRequisicao;
import br.fluxosaude.infra.persistencia.ExecutorTransacional;
import java.util.function.Function;

/** Transação com o contexto validado pelo banco (unidade ativa, papéis, versão de credencial). */
public final class TransacaoAlertasJdbc implements TransacaoAlertas {

    private final ExecutorTransacional executor;

    public TransacaoAlertasJdbc(ExecutorTransacional executor) {
        this.executor = executor;
    }

    @Override
    public <T> T executar(UsuarioAutenticado usuario, ContextoOrigem origem, Function<RepositorioAlertas, T> trabalho) {
        return executor.executar(ContextoRequisicao.de(usuario, origem), jdbc -> trabalho.apply(new RepositorioAlertasJdbc(jdbc)));
    }
}
