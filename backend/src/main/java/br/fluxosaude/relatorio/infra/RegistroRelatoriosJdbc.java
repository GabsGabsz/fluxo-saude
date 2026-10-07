package br.fluxosaude.relatorio.infra;

import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import br.fluxosaude.infra.persistencia.ContextoRequisicao;
import br.fluxosaude.infra.persistencia.ExecutorTransacional;
import br.fluxosaude.relatorio.aplicacao.RegistroRelatorios;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Auditoria dos relatórios numa transação READ COMMITTED própria (a cadeia de auditoria exige esse
 * isolamento; a leitura do relatório é somente leitura). Sessão revalidada pelo banco também aqui.
 * Dados do evento: só chaves fixas do código e valores curtos validados (códigos, datas, ids, hash).
 */
public final class RegistroRelatoriosJdbc implements RegistroRelatorios {

    private static final Pattern CHAVE = Pattern.compile("^[A-Za-z]{1,40}$");
    private static final Pattern VALOR = Pattern.compile("^[A-Za-z0-9_:.-]{0,64}$");

    private final ExecutorTransacional executor;

    public RegistroRelatoriosJdbc(ExecutorTransacional executor) {
        this.executor = executor;
    }

    @Override
    public long registrarExportacao(UsuarioAutenticado usuario, ContextoOrigem origem, String acao, String tipo,
                                    Map<String, Object> dados) {
        return executor.executar(ContextoRequisicao.de(usuario, origem), jdbc -> jdbc
            .sql("SELECT auditoria.registrar(?, 'relatorio', ?, CAST(? AS jsonb), (fluxo.ctx_unidades())[1])")
            .param(acao).param(tipo).param(json(dados))
            .query(Long.class).single());
    }

    @Override
    public void registrarConsultaNominal(UsuarioAutenticado usuario, ContextoOrigem origem, String tipo,
                                         Collection<UUID> episodios, Map<String, Object> dados) {
        executor.executarSemRetorno(ContextoRequisicao.de(usuario, origem), jdbc -> jdbc
            .sql("""
                    SELECT auditoria.registrar_consulta('CONSULTA_RELATORIO_PENDENCIAS', 'relatorio', ?, CAST(? AS uuid[]),
                                                        CAST(? AS jsonb), (fluxo.ctx_unidades())[1])
                    """)
            .param(tipo)
            .param(episodios.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}")))
            .param(json(dados))
            .query(Long.class).single());
    }

    static String json(Map<String, ?> dados) {
        return dados.entrySet().stream()
            .map(e -> {
                Object v = e.getValue();
                boolean escalar = v instanceof Number || v instanceof Boolean;
                if (!CHAVE.matcher(e.getKey()).matches() || (!escalar && !VALOR.matcher(String.valueOf(v)).matches())) {
                    throw new IllegalArgumentException("dado de auditoria fora do formato");
                }
                return "\"" + e.getKey() + "\":" + (escalar ? v.toString() : "\"" + v + "\"");
            })
            .collect(Collectors.joining(",", "{", "}"));
    }
}
