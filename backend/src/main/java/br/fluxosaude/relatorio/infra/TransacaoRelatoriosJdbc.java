package br.fluxosaude.relatorio.infra;

import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.infra.persistencia.ContextoRequisicao;
import br.fluxosaude.infra.persistencia.ExecutorTransacional;
import br.fluxosaude.relatorio.aplicacao.RepositorioRelatorios;
import br.fluxosaude.relatorio.aplicacao.TransacaoRelatorios;
import java.util.function.Function;

/**
 * Leitura de um relatório na transação REPEATABLE READ somente leitura da etapa 7
 * (ExecutorTransacional.executarLeituraConsistente): todas as consultas no mesmo instantâneo,
 * sessão revalidada e RLS. Sem nenhuma gravação (a auditoria fica em RegistroRelatoriosJdbc).
 */
public final class TransacaoRelatoriosJdbc implements TransacaoRelatorios {

    private final ExecutorTransacional executor;

    public TransacaoRelatoriosJdbc(ExecutorTransacional executor) {
        this.executor = executor;
    }

    @Override
    public <T> T executar(UsuarioAutenticado usuario, ContextoOrigem origem, Function<RepositorioRelatorios, T> trabalho) {
        return executor.executarLeituraConsistente(ContextoRequisicao.de(usuario, origem),
                jdbc -> trabalho.apply(new RepositorioRelatoriosJdbc(jdbc)));
    }
}
