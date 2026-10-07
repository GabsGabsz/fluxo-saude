package br.fluxosaude.relatorio.aplicacao;

import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.util.function.Function;

/**
 * Leitura de um relatório: UMA transação somente leitura em REPEATABLE READ (mesmo instantâneo
 * para todas as consultas), com o contexto revalidado pelo banco. Não grava nada — nem auditoria.
 */
@FunctionalInterface
public interface TransacaoRelatorios {

    <T> T executar(UsuarioAutenticado usuario, ContextoOrigem origem, Function<RepositorioRelatorios, T> trabalho);
}
