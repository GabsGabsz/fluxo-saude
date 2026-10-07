package br.fluxosaude.relatorio.aplicacao;

import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Registros de auditoria dos relatórios (ADR-0003): sempre numa transação READ COMMITTED PRÓPRIA,
 * separada da leitura somente leitura em REPEATABLE READ (que não pode gravar). Ator, unidade,
 * IP, correlação e instante vêm do contexto do banco; {@code dados} só com códigos, datas, ids de
 * filtro e a assinatura — nunca nomes ou linhas do relatório.
 */
public interface RegistroRelatorios {

    /** Exportação (CSV) ou impressão SOLICITADA (o navegador não comprova que imprimiu). */
    long registrarExportacao(UsuarioAutenticado usuario, ContextoOrigem origem, String acao, String tipo,
                             Map<String, Object> dados);

    /** Leitura nominal (lista operacional de pendências): conjunto de episódios exibidos (V17). */
    void registrarConsultaNominal(UsuarioAutenticado usuario, ContextoOrigem origem, String tipo,
                                  Collection<UUID> episodios, Map<String, Object> dados);
}
