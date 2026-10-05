package br.fluxosaude.identidade.infra;

import br.fluxosaude.identidade.aplicacao.ContextoOrigem;
import br.fluxosaude.identidade.aplicacao.CredenciaisPort;
import br.fluxosaude.identidade.dominio.Papel;
import br.fluxosaude.infra.persistencia.ContextoRequisicao;
import br.fluxosaude.infra.persistencia.ExecutorTransacional;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Credenciais sobre as funções controladas do banco (V6). SQL sempre parametrizado. */
public final class CredenciaisJdbc implements CredenciaisPort {

    private final ExecutorTransacional executor;
    private final int maxFalhasConta;
    private final Duration bloqueioConta;

    public CredenciaisJdbc(ExecutorTransacional executor, int maxFalhasConta, Duration bloqueioConta) {
        this.executor = executor;
        this.maxFalhasConta = maxFalhasConta;
        this.bloqueioConta = bloqueioConta;
    }

    @Override
    public Optional<Credencial> buscarPorLogin(String login, ContextoOrigem origem) {
        return executor.executar(ContextoRequisicao.anonimo(origem), jdbc -> jdbc.sql("""
                    SELECT usuario_id, senha_hash, ativo, deve_trocar_senha, bloqueado_ate
                      FROM fluxo.credencial_para_login(?)
                    """)
                .param(login)
                .query((rs, n) -> new Credencial(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4),
                        instante(rs.getObject(5, OffsetDateTime.class))))
                .optional());
    }

    @Override
    public Optional<Instant> registrarTentativa(UUID usuarioIdOuNulo, boolean sucesso, ContextoOrigem origem) {
        return executor.executar(ContextoRequisicao.anonimo(origem), jdbc -> jdbc.sql("""
                    SELECT fluxo.registrar_tentativa_login(?::uuid, ?, ?, ? * interval '1 second')
                    """)
                .param(usuarioIdOuNulo == null ? null : usuarioIdOuNulo.toString())
                .param(sucesso)
                .param(maxFalhasConta)
                .param(bloqueioConta.toSeconds())
                .query((rs, n) -> Optional.ofNullable(instante(rs.getObject(1, OffsetDateTime.class))))
                .single());
    }

    @Override
    public Map<UUID, Set<Papel>> lotacoes(UUID usuarioId, ContextoOrigem origem) {
        record Linha(UUID unidade, String papel) {
        }
        List<Linha> linhas = executor.executar(ContextoRequisicao.anonimo(origem), jdbc -> jdbc.sql("""
                    SELECT unidade_id, papel::text FROM fluxo.lotacoes_para_autenticacao(?::uuid)
                    """)
                .param(usuarioId.toString())
                .query((rs, n) -> new Linha(rs.getObject(1, UUID.class), rs.getString(2)))
                .list());
        Map<UUID, Set<Papel>> resultado = new HashMap<>();
        for (Linha l : linhas) {
            Papel papel;
            try {
                papel = Papel.valueOf(l.papel());
            } catch (IllegalArgumentException desconhecido) {
                continue; // papel novo no banco e desconhecido pelo código: não concede nada (falha fechada)
            }
            resultado.computeIfAbsent(l.unidade(), k -> EnumSet.noneOf(Papel.class)).add(papel);
        }
        return resultado;
    }

    @Override
    public Perfil perfil(UUID usuarioId, ContextoOrigem origem) {
        return executor.executar(ContextoRequisicao.proprioUsuario(usuarioId, origem), jdbc -> jdbc.sql("""
                    SELECT login::text, nome FROM fluxo.usuario WHERE id = ?::uuid
                    """)
                .param(usuarioId.toString())
                .query((rs, n) -> new Perfil(rs.getString(1), rs.getString(2)))
                .single());
    }

    @Override
    public void registrarTentativaDuranteBloqueio(UUID usuarioId, ContextoOrigem origem) {
        executor.executar(ContextoRequisicao.anonimo(origem), jdbc -> jdbc
                .sql("SELECT fluxo.registrar_tentativa_bloqueada(?::uuid)")
                .param(usuarioId.toString())
                .query(Boolean.class)
                .single());
    }

    @Override
    public void registrarFalhaNaTrocaDeSenha(UUID usuarioId, ContextoOrigem origem) {
        executor.executar(ContextoRequisicao.proprioUsuario(usuarioId, origem), jdbc -> jdbc
                .sql("SELECT auditoria.registrar('SENHA_ATUAL_INCORRETA', 'autenticacao')")
                .query(Long.class)
                .single());
    }

    /** O hash não é legível por SELECT direto (V8): função que devolve só o do próprio usuário. */
    @Override
    public String hashAtual(UUID usuarioId, ContextoOrigem origem) {
        return executor.executar(ContextoRequisicao.proprioUsuario(usuarioId, origem), jdbc -> jdbc
                .sql("SELECT fluxo.hash_senha_propria()")
                .query(String.class)
                .single());
    }

    @Override
    public void gravarNovaSenha(UUID usuarioId, String novoHash, ContextoOrigem origem) {
        Boolean ok = executor.executar(ContextoRequisicao.proprioUsuario(usuarioId, origem), jdbc -> jdbc
                .sql("SELECT fluxo.alterar_senha_propria(?)")
                .param(novoHash)
                .query(Boolean.class)
                .single());
        if (!Boolean.TRUE.equals(ok)) {
            throw new IllegalStateException("troca de senha não aplicada");
        }
    }

    private static Instant instante(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
