package br.fluxosaude.infra.persistencia;

import br.fluxosaude.identidade.dominio.SessaoRevogadaException;
import java.sql.Array;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ÚNICO ponto de acesso ao banco para dados de negócio. Abre a transação (READ
 * COMMITTED, exigido pela auditoria — ADR-0003) e, como PRIMEIRO comando, aplica o
 * contexto com {@code set_config(..., true)} (escopo da transação: seguro com pool).
 * Sem isso, o RLS não mostra nada e qualquer escrita auditada é recusada (falha fechada).
 * Com usuário, o contexto passa por {@code fluxo.aplicar_contexto}, que confere no banco
 * se ele continua ativo e lotado na unidade com os mesmos papéis da sessão; se não,
 * {@link SessaoRevogadaException} (revogação imediata, ADR-0002 §7).
 *
 * <p>Regra de revisão: todo SQL usa parâmetros ({@code ?}); nunca concatenação.
 */
@Component
public class ExecutorTransacional {

    private static final Pattern IP = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");
    private static final Pattern CORRELACAO = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private final TransactionTemplate transacao;
    private final JdbcClient jdbc;

    public ExecutorTransacional(PlatformTransactionManager gerenciador, DataSource dataSource) {
        this.transacao = new TransactionTemplate(gerenciador);
        this.transacao.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.jdbc = JdbcClient.create(dataSource);
    }

    public <T> T executar(ContextoRequisicao contexto, Function<JdbcClient, T> trabalho) {
        Objects.requireNonNull(contexto);
        return transacao.execute(status -> {
            aplicar(contexto);
            return trabalho.apply(jdbc);
        });
    }

    public void executarSemRetorno(ContextoRequisicao contexto, java.util.function.Consumer<JdbcClient> trabalho) {
        executar(contexto, j -> {
            trabalho.accept(j);
            return Boolean.TRUE;
        });
    }

    private void aplicar(ContextoRequisicao c) {
        String ip = c.origem().ip() != null && IP.matcher(c.origem().ip()).matches() ? c.origem().ip() : "";
        String correlacao = c.origem().correlacaoId() != null && CORRELACAO.matcher(c.origem().correlacaoId()).matches()
                ? c.origem().correlacaoId() : "";
        if (c.usuarioId() == null) {
            jdbc.sql("""
                    SELECT concat(set_config('fluxo.usuario_id', '', true),
                                  set_config('fluxo.unidade_ids', '', true),
                                  set_config('fluxo.origem_ip', ?, true),
                                  set_config('fluxo.correlacao_id', ?, true))
                    """)
                .param(ip).param(correlacao)
                .query(String.class)
                .single();
            return;
        }
        // O banco confere usuário ativo, versão de credencial da sessão (V12) e lotação na
        // unidade, e devolve os papéis vigentes.
        Optional<Set<String>> papeis = jdbc.sql("SELECT fluxo.aplicar_contexto(?::uuid, ?::uuid, ?, ?, ?)")
            .param(c.usuarioId().toString())
            .param(c.unidadeAtiva() == null ? null : c.unidadeAtiva().toString())
            .param(ip).param(correlacao)
            .param(c.credencialVersao())
            .query((rs, n) -> {
                Array a = rs.getArray(1);
                if (a == null) {
                    return Optional.<Set<String>>empty();
                }
                Set<String> nomes = new HashSet<>();
                for (Object o : (Object[]) a.getArray()) {
                    nomes.add(o.toString());
                }
                return Optional.of(nomes);
            })
            .single();
        Set<String> esperados = c.papeisEsperados().stream().map(Enum::name).collect(Collectors.toSet());
        if (papeis.isEmpty() || !papeis.get().equals(esperados)) {
            throw new SessaoRevogadaException();
        }
    }
}
